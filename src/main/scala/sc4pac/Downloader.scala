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
    if (url.startsWith("file:/")) {
      ZIO.attemptBlocking {
        if (localFile.exists()) Right(localFile)
        else Left(new CC.ArtifactError.NotFound(localFile.toString))
      }.catchSome {
        case scala.util.control.NonFatal(e) => ZIO.succeed(Left(wrapDownloadError(e, url)))
      }.orDie.absolve
    } else {
      remote(localFile, url).map(_ => localFile)
    }
  }

  private def wrapDownloadError(e: Throwable, url: String): CC.ArtifactError = CC.ArtifactError.DownloadError(
    s"Caught ${e.getClass().getName()}${Option(e.getMessage).fold("")(" (" + _ + ")")} while downloading $url",
    Some(e)
  )

  private def remote(file: java.io.File, url: String): IO[CC.ArtifactError, Unit] = {
    val task = ZIO.fromEither {
      val tmp = coursier.paths.CachePath.temporaryFile(file)  // file => .file.part
      logger.downloadingArtifact(url, artifact)
      var success = false
      try {
        val res = downloading(url, file)(
          CC.CacheLocks.withLockOr(
            cacheLocation, file)(doDownload(file, url, tmp),
            ifLocked = Some(Left(new CC.ArtifactError.Locked(file)))  // should not be an issue as long as just one instance of sc4pac is running
          ),
          ifLocked = Some(Left(new CC.ArtifactError.Locked(file)))  // should not be an issue as long as just one instance of sc4pac is running
        )
        success = res.isRight
        res
      } finally logger.downloadedArtifact(url, success = success)
    }
    // By scheduling the downloads on the `cache.pool`, we use max 2 downloads
    // in parallel (this requires that the tasks are not already on the
    // `ZIO.blocking` pool, which would start to download EVERYTHING in parallel).
    task.onExecutionContext(scala.concurrent.ExecutionContext.fromExecutorService(pool))
  }

  /** Wraps download with ArtifactError.DownloadError and ssl retry attempts and
    * resumption attempts.
    */
  private def downloading[T](url: String, file: java.io.File)(
    f: => Either[CC.ArtifactError, T], ifLocked: => Option[Either[CC.ArtifactError, T]]
  ): Either[CC.ArtifactError, T] = {

    @tailrec
    def helper(retrySsl: Int, retryResumption: Int): Either[CC.ArtifactError, T] = {
      require(retrySsl >= 0 && retryResumption >= 0)

      val resOpt: Either[(Int, Int), Either[CC.ArtifactError, T]] =
        try {
          val res0 = CC.CacheLocks.withUrlLock(url) {
            try f
            catch {
              case nfe: java.io.FileNotFoundException if nfe.getMessage != null =>
                Left(new CC.ArtifactError.NotFound(nfe.getMessage))
            }
          }
          res0.orElse(ifLocked).toRight((retrySsl - 1, retryResumption))  // as a safe-guard, we also decrease retry counter here
        } catch {
          case _: javax.net.ssl.SSLException if retrySsl >= 1 => Left(retrySsl - 1, retryResumption)
          case _: java.net.SocketTimeoutException if retryResumption >= 1 =>
            System.err.println(s"Connection timeout: trying to resume download $url")
            Left(retrySsl, retryResumption - 1)
          case scala.util.control.NonFatal(e) => Right(Left(wrapDownloadError(e, url)))
        }

      resOpt match {
        case Right(Left(ex: CC.ArtifactError.WrongLength)) if ex.got < ex.expected && retryResumption >= 1 =>
          System.err.println(s"File transmission incomplete (${ex.got}/${ex.expected}): trying to resume download $url")
          helper(retrySsl, retryResumption - 1)
        case Right(res) => res
        case Left((retrySsl, retryResumption)) => helper(retrySsl, retryResumption)
      }
    }
    helper(Constants.sslRetryCount, Constants.resumeIncompleteDownloadAttemps)
  }

  /** Download in blocking fashion. */
  private def doDownload(file: java.io.File, url: String, tmp: java.io.File): Either[CC.ArtifactError, Unit] = {
    val alreadyDownloaded = tmp.length()
    var conn: URLConnection = null

    try {
      val (conn0, partialDownload) = Downloader.urlConnectionMaybePartial(url, alreadyDownloaded)  // TODO maxRedirectionsOpt unused?
        // CC.ConnectionBuilder(url)
        // .withAlreadyDownloaded(alreadyDownloaded)
        // .withMethod("GET")
        // .withMaxRedirectionsOpt(Constants.maxRedirectionsOpt)
        // .connectionMaybePartial()
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
            val len = if (partialDownload) len0 + alreadyDownloaded else len0  // len0 is remaining length in case of partial download
            // TODO check that length of partial downloads work as expected
            // if (alreadyDownloaded > len) {  // TODO there is no effective way to check this here
            //   ???
            // }
            logger.downloadLength(url, len, (if (partialDownload) alreadyDownloaded else 0L), watching = false)
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


  private def shouldStartOver(conn: URLConnection, alreadyDownloaded: Long): Option[Boolean] =
    if (alreadyDownloaded > 0L)
      conn match {
        case conn0: java.net.HttpURLConnection =>
          conn0.setRequestProperty("Range", s"bytes=$alreadyDownloaded-")

          val startOver = {
            val isPartial = conn0.getResponseCode == 206 || conn0.getResponseCode == 416
            !isPartial || {  // TODO Coursier's conditions are different/wrong
              val hasMatchingHeader = Option(conn0.getHeaderField("Content-Range"))
                .exists(_.startsWith(s"bytes $alreadyDownloaded-"))
              !hasMatchingHeader
            }
          }

          Some(startOver)
        case _ =>
          None
      }
    else
      None


  private def is4xx(conn: URLConnection): Boolean =
    conn match {
      case conn0: java.net.HttpURLConnection => conn0.getResponseCode / 100 == 4
      case _ => false
    }

  private def urlConnectionMaybePartial(
    url0: String,
    alreadyDownloaded: Long
  ): (URLConnection, Boolean) = {

    var conn: URLConnection = null

    val res: Either[Long, (URLConnection, Boolean)] =
      try {
        conn = CC.CacheUrl.url(url0).openConnection()
        conn match {  // initialization
          case conn0: java.net.HttpURLConnection =>
            conn0.setRequestMethod("GET")
            conn0.setInstanceFollowRedirects(true)  // TODO yes or no? Coursier sets this to false and handles redirects manually
            conn0.setRequestProperty("User-Agent", "Coursier/2.0")
            conn0.setRequestProperty("Accept", "*/*")
            conn0.setConnectTimeout(Constants.urlConnectTimeout.toMillis.toInt)  // timeout for establishing a connection
            conn0.setReadTimeout(Constants.urlReadTimeout.toMillis.toInt)  // timeout in case of internet outage while downloading a file
          case _ =>
        }

        val startOverOpt = shouldStartOver(conn, alreadyDownloaded)  // TODO Coursier itself should check this AFTER redirects

        startOverOpt match {
          case Some(true) =>
            closeConn(conn)
            Left(0L)  // alreadyDownloaded
          case _ =>  // no indication for need to start over   // TODO what if file uri connection? --> test this
            val partialDownload = startOverOpt.nonEmpty
            if (is4xx(conn)) {
              closeConn(conn)
              throw new Exception(s"Connection error 4xx: $conn")
            } else {
              Right((conn, partialDownload))
            }
        }
      } catch {
        case scala.util.control.NonFatal(e) =>
          if (conn != null)
            closeConn(conn)
          throw e
      }

    res match {
      case Left(alreadyDownloaded) =>
        urlConnectionMaybePartial(url0, alreadyDownloaded)
      case Right(ret) =>
        ret
    }
  }

}
