package io.github.memo33
package sc4pac

import scala.annotation.tailrec
import java.net.URLConnection
import coursier.cache as CC
import zio.{ZIO, Task, IO}

class Downloader(
  artifact: coursier.util.Artifact,  // contains the URL
  cacheLocation: java.io.File,
  localFile: java.io.File,  // the local file after download
  logger: coursier.cache.CacheLogger,
  pool: java.util.concurrent.ExecutorService
) {

  def download: IO[CC.ArtifactError, java.io.File] = {
    val url = artifact.url
    logger.checkingArtifact(url, artifact)
    val task =
      if (url.startsWith("file:/")) {
        ??? // TODO handle local files
      } else {
        remote(localFile, url)
      }
    task.map(_ => localFile)
  }

  private def remote(file: java.io.File, url: String): IO[CC.ArtifactError, Unit] = {
    ZIO.fromEither {
      val tmp = coursier.paths.CachePath.temporaryFile(file)  // file => .file.part
      logger.downloadingArtifact(url, artifact)
      var success = false
      try {
        val res = downloading(url, file)(
          CC.CacheLocks.withLockOr(
            cacheLocation, file)(doDownload(file, url, tmp),
            ifLocked = ???
          ),
          ifLocked = ???
        )
        success = res.isRight
        res
      } finally logger.downloadedArtifact(url, success = success)
    } //.onExecutionContext(scala.concurrent.ExecutionContext.fromExecutorService(pool))  // TODO this is currently handled in Resolution
  }

  /** Wraps download with ArtifactError.DownloadError and ssl retry attempts. */
  private def downloading[T](url: String, file: java.io.File)(
    f: => Either[CC.ArtifactError, T], ifLocked: => Option[Either[CC.ArtifactError, T]]
  ): Either[CC.ArtifactError, T] = {

    @tailrec
    def helper(retry: Int): Either[CC.ArtifactError, T] = {
      require(retry >= 0)

      val resOpt: Option[Either[CC.ArtifactError, T]] =
        try {
          val res0 = CC.CacheLocks.withUrlLock(url) {
            try f
            catch {
              case nfe: java.io.FileNotFoundException if nfe.getMessage != null =>
                Left(new CC.ArtifactError.NotFound(nfe.getMessage))
            }
          }
          res0.orElse(ifLocked)
        } catch {
          case _: javax.net.ssl.SSLException if retry >= 1 => None
          case scala.util.control.NonFatal(e) =>
            val ex = new CC.ArtifactError.DownloadError(
              s"Caught ${e.getClass().getName()}${Option(e.getMessage).fold("")(" (" + _ + ")")} while downloading $url",
              Some(e)
            )
            Some(Left(ex))
        }

      resOpt match {
        case Some(res) => res
        case None      => helper(retry - 1)
      }
    }
    helper(Constants.sslRetryCount)
  }

  /** Download in blocking fashion. */
  private def doDownload(file: java.io.File, url: String, tmp: java.io.File): Either[CC.ArtifactError, Unit] = {
    val alreadyDownloaded = tmp.length()
    var conn: URLConnection = null

    try {
      val (conn0, partialDownload) = CC.ConnectionBuilder(url)
        .withAlreadyDownloaded(alreadyDownloaded)
        .withMethod("GET")
        .withMaxRedirectionsOpt(Constants.maxRedirectionsOpt)
        .connectionMaybePartial()
      conn = conn0

      val respCodeOpt = CC.CacheUrl.responseCode(conn)

      if (respCodeOpt.contains(404))
        Left(new CC.ArtifactError.NotFound(url, permanent = Some(true)))
      else if (respCodeOpt.contains(403))
        Left(new CC.ArtifactError.Forbidden(url))
      else if (respCodeOpt.contains(401))
        Left(new CC.ArtifactError.Unauthorized(url, realm = CC.CacheUrl.realm(conn)))
      else {
        val lenOpt: Option[Long] =
          for (len0 <- Option(conn.getContentLengthLong) if len0 >= 0L) yield {
            val len = len0 + (if (partialDownload) alreadyDownloaded else 0L)
            logger.downloadLength(url, len, alreadyDownloaded, watching = false)
            len
          }

        val lastModifiedOpt = Option(conn.getLastModified).filter(_ > 0L)

        val result: Unit = {
          scala.util.Using.resource {
            val baseStream =
              if (conn.getContentEncoding == "gzip") new java.util.zip.GZIPInputStream(conn.getInputStream)
              else conn.getInputStream
            new java.io.BufferedInputStream(baseStream, Constants.bufferSizeDownload)
          } { in =>
            scala.util.Using.resource(
              CC.CacheLocks.withStructureLock(cacheLocation) {
                coursier.paths.Util.createDirectories(tmp.toPath.getParent);
                new java.io.FileOutputStream(tmp, partialDownload)
              }
            ) { out =>
              Downloader.readFullyTo(in, out, logger, url, if (partialDownload) alreadyDownloaded else 0L)
            }
          }
        }

        val lengthCheck: Either[CC.ArtifactError, Unit] =
          lenOpt match {
            case None => Right(())
            case Some(len) =>
              val tmpLen = if (tmp.exists()) tmp.length() else 0L
              if (len == tmpLen)
                Right(())
              else
                Left(new CC.ArtifactError.WrongLength(tmpLen, len, tmp.getAbsolutePath))
          }

        lengthCheck.flatMap { _ =>

          CC.CacheLocks.withStructureLock(cacheLocation) {
            coursier.paths.Util.createDirectories(file.toPath.getParent)
            java.nio.file.Files.move(tmp.toPath, file.toPath, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
          }

          for (lastModified <- lastModifiedOpt)
            file.setLastModified(lastModified)

          doTouchCheckFile(file, url)

          Right(result)
        }
      }

    } finally {
      if (conn != null) Downloader.closeConn(conn)
    }


  }

  def doTouchCheckFile(file: java.io.File, url: String): Unit = {  // without `updateLinks` as we do not download directories
    val ts = System.currentTimeMillis()
    val f  = FileCache.ttlFile(file)
    if (!f.exists()) {
      scala.util.Using.resource(new java.io.FileOutputStream(f)) { fos => fos.write(Array.empty[Byte]) }
    }
    f.setLastModified(ts)
  }

}


object Downloader {

  private def readFullyTo(
    in: java.io.InputStream,
    out: java.io.OutputStream,
    logger: coursier.cache.CacheLogger,
    url: String,
    alreadyDownloaded: Long
  ): Unit = {
    val b = new Array[Byte](Constants.bufferSizeDownload)

    @tailrec
    def helper(count: Long): Unit = {
      val read = in.read(b)
      if (read >= 0) {
        out.write(b, 0, read)
        out.flush()
        logger.downloadProgress(url, count + read)
        helper(count + read)
      }
    }
    helper(alreadyDownloaded)
  }


  private def closeConn(conn: URLConnection): Unit = {
    scala.util.Try(conn.getInputStream).toOption.filter(_ != null).foreach(_.close())
    conn match {
      case conn0: java.net.HttpURLConnection =>
        scala.util.Try(conn0.getErrorStream).toOption.filter(_ != null).foreach(_.close())
        conn0.disconnect()
      case _ =>
    }
  }

}
