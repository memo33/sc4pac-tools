package io.github.memo33
package sc4pac

import scala.annotation.tailrec
import java.net.URLConnection
import coursier.cache as CC
import zio.{ZIO, Task, IO}

import Downloader.PartialDownloadSpec

/** This downloader implementation is based on Coursier's downloader (released under Apache License Version 2.0):
  * https://github.com/coursier/coursier/blob/8d93005b56dd84770c062aeae6d7a12c53948596/modules/cache/jvm/src/main/scala/coursier/cache/internal/Downloader.scala
  *
  * Our changes of the implementation resolve issues related to timeouts and resuming partial downloads.
  */
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
          CC.CacheLocks.withLockOr(cacheLocation, file)(
            doDownload(file, url, tmp),
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
    var conn: URLConnection = null

    try {
      val (conn0, partialDownload) = Downloader.urlConnectionMaybePartial(url, Downloader.PartialDownloadSpec.initBlocking(tmp))
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
            val (len, alreadyDownloaded) =
              partialDownload match {
                case Some(spec) =>  // len0 is remaining length in case of partial download
                  (len0 + spec.alreadyDownloaded - Constants.bufferSizeDownloadOverlap, spec.alreadyDownloaded)
                case None => (len0, 0L)
              }
            logger.downloadLength(url, len, alreadyDownloaded, watching = false)
            len
          }

        val lastModifiedOpt = Option(conn.getLastModified).filter(_ > 0L)

        def consumeStream(): Either[CC.ArtifactError, Unit] = {
          scala.util.Using.resource {
            val baseStream =
              if (conn.getContentEncoding == "gzip") new java.util.zip.GZIPInputStream(conn.getInputStream)
              else conn.getInputStream
            new java.io.BufferedInputStream(baseStream, Constants.bufferSizeDownload)
          } { in =>

            val overlapRegionMatches = partialDownload match {
              case Some(spec) => Downloader.startsWithBytes(in, spec.trailingBytes)  // consumes leading bytes
              case None => true
            }

            if (!overlapRegionMatches) {
              Left(new CC.ArtifactError.DownloadError(s"Partially downloaded file $tmp does not match remote file $url: delete the file and try again.", None))
            } else {
              scala.util.Using.resource(
                CC.CacheLocks.withStructureLock(cacheLocation) {
                  coursier.paths.Util.createDirectories(tmp.toPath.getParent);
                  new java.io.FileOutputStream(tmp, partialDownload.isDefined)
                }
              ) { out =>
                Downloader.readFullyTo(in, out, logger, url, alreadyDownloaded = partialDownload.map(_.alreadyDownloaded).getOrElse(0L))
                Right(())
              }
            }
          }
        }

        def lengthCheck(): Either[CC.ArtifactError, Unit] =
          lenOpt match {
            case None => Right(())
            case Some(len) =>
              val tmpLen = if (tmp.exists()) tmp.length() else 0L
              if (len == tmpLen)
                Right(())
              else
                Left(new CC.ArtifactError.WrongLength(tmpLen, len, tmp.getAbsolutePath))
          }

        for {
          _ <- consumeStream()
          _ <- lengthCheck()
        } yield {

          CC.CacheLocks.withStructureLock(cacheLocation) {
            coursier.paths.Util.createDirectories(file.toPath.getParent)
            java.nio.file.Files.move(tmp.toPath, file.toPath, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
          }

          for (lastModified <- lastModifiedOpt)
            file.setLastModified(lastModified)

          doTouchCheckFile(file, url)
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

  /** Consumes the first overlap.length bytes and returns whether input matches. */
  private def startsWithBytes(in: java.io.InputStream, overlap: Array[Byte]): Boolean = {
    val b = new Array[Byte](overlap.length)

    @tailrec
    def helper(count: Int): Boolean = {
      val read = in.read(b, count, overlap.length - count)
      if (read == -1) {  // stream ended prematurely
        false
      } else if (count + read < overlap.length) {  // continue reading
        helper(count + read)
      } else {  // array b has been filled
        assert(count + read == overlap.length)
        b.sameElements(overlap)
      }
    }
    helper(0)
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


  /** Set byte-range request property and return:
    * - Some(true) if response adheres to our range request,
    * - Some(false) if response does not adhere to our range request, so download starts from beginning,
    * - None if setting range was not possible since connection is incompatible or alreadyDownloaded is 0.
    */
  private def setRangeRequest(conn: URLConnection, alreadyDownloaded: Long): Option[Boolean] =
    if (alreadyDownloaded > 0L)
      conn match {
        case conn0: java.net.HttpURLConnection =>
          conn0.setRequestProperty("Range", s"bytes=$alreadyDownloaded-")
          val isPartial = conn0.getResponseCode == 206 || conn0.getResponseCode == 416
          def hasMatchingHeader = Option(conn0.getHeaderField("Content-Range")).exists(_.startsWith(s"bytes $alreadyDownloaded-"))
          Some(isPartial && hasMatchingHeader)  // Coursier's conditions are different/wrong
        case _ => None
      }
    else None


  private def is4xx(conn: URLConnection): Boolean =
    conn match {
      case conn0: java.net.HttpURLConnection => conn0.getResponseCode / 100 == 4
      case _ => false
    }

  final class PartialDownloadSpec(val alreadyDownloaded: Long, val trailingBytes: Array[Byte])
  object PartialDownloadSpec {
    def initBlocking(tmp: java.io.File): Option[PartialDownloadSpec] = {
      val alreadyDownloaded = tmp.length()
      if (alreadyDownloaded <= Constants.bufferSizeDownloadOverlap)
        None
      else {
        // TODO use cache lock?
        scala.util.Using.resource(new java.io.RandomAccessFile(tmp, "r")) { raf =>
          raf.seek(alreadyDownloaded - Constants.bufferSizeDownloadOverlap)
          val buf = new Array[Byte](Constants.bufferSizeDownloadOverlap)
          raf.readFully(buf)
          Some(PartialDownloadSpec(alreadyDownloaded, trailingBytes = buf))
        }
      }
    }
  }

  /** Open a URL connection for download, optionally for resuming a partial
    * download (if byte-serving is supported by the server).
    */
  private def urlConnectionMaybePartial(url0: String, specOpt: Option[PartialDownloadSpec]): (URLConnection, Option[PartialDownloadSpec]) = {

    var conn: URLConnection = null

    val res: Either[Option[PartialDownloadSpec], (URLConnection, Option[PartialDownloadSpec])] =
      try {
        conn = CC.CacheUrl.url(url0).openConnection()
        conn match {  // initialization
          case conn0: java.net.HttpURLConnection =>
            conn0.setRequestMethod("GET")
            conn0.setInstanceFollowRedirects(true)  // Coursier sets this to false and handles redirects manually
            conn0.setRequestProperty("User-Agent", Constants.userAgent)
            conn0.setRequestProperty("Accept", "*/*")
            conn0.setConnectTimeout(Constants.urlConnectTimeout.toMillis.toInt)  // timeout for establishing a connection
            conn0.setReadTimeout(Constants.urlReadTimeout.toMillis.toInt)  // timeout in case of internet outage while downloading a file
          case _ =>
        }

        def makeResult[A](x: A): Right[A, (URLConnection, A)] = {
          if (is4xx(conn)) {
            closeConn(conn)
            throw new Exception(s"Connection error 4xx: $conn")
          } else {
            Right((conn, x))
          }
        }

        specOpt match {
          case None =>  // no partial download desired (e.g. if .part file does not exist or is too short)
            makeResult(None)
          case Some(spec) =>  // partial download desired
            val rangeOffset = spec.alreadyDownloaded - Constants.bufferSizeDownloadOverlap  // small overlapping region for checking that partial file is still up-to-date
            setRangeRequest(conn, rangeOffset) match {
              case None | Some(false) =>  // established connection does not refer to a partial download, so we start a new connection without range request
                closeConn(conn)
                Left(None)  // next specOpt
              case Some(true) =>  // connection refers to a partial download
                makeResult(Some(spec))
            }
        }
      } catch {
        case scala.util.control.NonFatal(e) =>
          if (conn != null)
            closeConn(conn)
          throw e
      }

    res match {
      case Left(specOpt) =>
        urlConnectionMaybePartial(url0, specOpt)  // reconnect, possibly starting from 0
      case Right(ret) =>
        ret
    }
  }

}
