package io.github.memo33
package sc4pac

import scala.annotation.tailrec
import scala.collection.immutable.ArraySeq
import java.net.URLConnection
import java.util.concurrent.atomic.AtomicBoolean
import coursier.cache as CC
import zio.{ZIO, IO}
import upickle.default as UP
import sc4pac.JsonData as JD
import sc4pac.error.Artifact2Error

import Downloader.PartialDownloadSpec

/** This downloader implementation is based on Coursier's downloader (released under Apache License Version 2.0):
  * https://github.com/coursier/coursier/blob/8d93005b56dd84770c062aeae6d7a12c53948596/modules/cache/jvm/src/main/scala/coursier/cache/internal/Downloader.scala
  *
  * Our changes of the implementation resolve issues related to timeouts and resuming partial downloads.
  *
  * TODO This code is a mess and should be rewritten with less indirection and
  * better wrapping of failures (e.g. using Either or ZIO).
  */
class Downloader(
  artifact: Artifact,  // contains the URL
  cacheLocation: java.io.File,
  localFile: java.io.File,  // the local file after download
  logger: Logger,
  pool: java.util.concurrent.ExecutorService,
  credentials: Downloader.Credentials,
) {

  def download: IO[Artifact2Error, java.io.File] = {
    val url = artifact.url.toString
    logger.checkingArtifact(url, artifact)
    if (url.startsWith("file:/")) {
      // use local file directly
      ZIO.attemptBlocking {
        if (localFile.exists()) Right(localFile)
        else Left(new Artifact2Error.NotFound(localFile.toString))
      }.catchSome {
        case scala.util.control.NonFatal(e) => ZIO.succeed(Left(wrapDownloadError(e, url)))
      }.orDie.absolve
    } else if (artifact.localMirror.isDefined) {
      // download has previously failed, so copy local mirror of file instead
      val mirrorPath = artifact.localMirror.get
      ZIO.attemptBlocking {
        val localFilePath = os.Path(localFile.getAbsolutePath())
        if (localFilePath != mirrorPath) {
          os.copy.over(mirrorPath, localFilePath, replaceExisting = true, createFolders = true, followLinks = true)
        }
        doTouchCheckFile(localFile, url = url, filename = mirrorPath.lastOpt, sha256 = Downloader.computeChecksum(localFile))
        Right(localFile)
      }.catchSome {
        case e: java.io.FileNotFoundException if e.getMessage != null => ZIO.succeed(Left(new Artifact2Error.NotFound(e.getMessage)))
        case e: java.nio.file.NoSuchFileException if e.getMessage != null => ZIO.succeed(Left(new Artifact2Error.NotFound(e.getMessage)))
        case scala.util.control.NonFatal(e) => ZIO.succeed(Left(wrapDownloadError(e, url)))
      }.orDie.absolve
    } else {
      // download remote file
      remote(localFile, url).map(_ => localFile)
    }
  }

  private def wrapDownloadError(e: Throwable, url: String): Artifact2Error = e match {
    case e: Artifact2Error => e
    case _ => Artifact2Error.DownloadError(
      s"Caught ${e.getClass().getName()}${Option(e.getMessage).fold("")(" (" + _ + ")")} while downloading $url",
      Some(e)
    )
  }

  private def remote(file: java.io.File, url: String): IO[Artifact2Error, Unit] = {
    Downloader.attemptCancelableOnPool(pool, (isCanceled) => {
      val tmp = coursier.paths.CachePath.temporaryFile(file)  // file => .file.part
      logger.downloadingArtifact(url, artifact)
      var success = false
      try {
        val res = downloading(url, file)(
          CC.CacheLocks.withLockOr(cacheLocation, file)(
            doDownload(file, url, tmp, isCanceled),
            ifLocked = Some(Left(new Artifact2Error.Locked(file)))  // should not be an issue as long as just one instance of sc4pac is running
          ),
          ifLocked = Some(Left(new Artifact2Error.Locked(file)))  // should not be an issue as long as just one instance of sc4pac is running
        )
        success = res.isRight
        res
      } finally logger.downloadedArtifact(url, success = success)
    }).catchNonFatalOrDie {
      case e => ZIO.fail(wrapDownloadError(e, url))
    }
  }

  /** Wraps download with ArtifactError.DownloadError and ssl retry attempts and
    * resumption attempts.
    */
  private def downloading[T](url: String, file: java.io.File)(
    f: => Either[Artifact2Error, T], ifLocked: => Option[Either[Artifact2Error, T]]
  ): Either[Artifact2Error, T] = {

    @tailrec
    def helper(retrySsl: Int, retryResumption: Int): Either[Artifact2Error, T] = {
      require(retrySsl >= 0 && retryResumption >= 0)

      val resOpt: Either[(Int, Int), Either[Artifact2Error, T]] =
        try {
          val res0 = CC.CacheLocks.withUrlLock(url) {
            try f
            catch {
              case nfe: java.io.FileNotFoundException if nfe.getMessage != null =>
                Left(new Artifact2Error.NotFound(nfe.getMessage))
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
        case Right(Left(ex: Artifact2Error.WrongLength)) if ex.got < ex.expected && retryResumption >= 1 =>
          System.err.println(s"File transmission incomplete (${ex.got}/${ex.expected}): trying to resume download $url")
          helper(retrySsl, retryResumption - 1)
        case Right(res) => res
        case Left((retrySsl, retryResumption)) => helper(retrySsl, retryResumption)
      }
    }
    helper(Constants.sslRetryCount, Constants.resumeIncompleteDownloadAttemps)
  }

  /** Download in blocking fashion. */
  private def doDownload(file: java.io.File, url: String, tmp: java.io.File, isCanceled: AtomicBoolean): Either[Artifact2Error, Unit] = {
    var conn: URLConnection = null

    try {
      val (conn0, partialDownload) = Downloader.urlConnectionMaybePartial(url, Downloader.PartialDownloadSpec.initBlocking(tmp), credentials)
      conn = conn0

      val respCodeOpt = CC.CacheUrl.responseCode(conn)

      if (respCodeOpt.contains(404))
        Left(new Artifact2Error.NotFound(url))
      else if (respCodeOpt.contains(403))
        Left(new Artifact2Error.Forbidden(url))
      else if (respCodeOpt.contains(401))
        Left(new Artifact2Error.Unauthorized(url))
      else if (respCodeOpt.contains(429))
        Left(new Artifact2Error.RateLimited(url))
      else {
        val lenOpt: Option[Long] =
          for (len0 <- Option(conn.getContentLengthLong).filter(_ >= 0L).orElse(Downloader.lengthFromContentRange(conn))) yield {
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

        val filename: Option[String] =
          Option(conn.getHeaderField("content-disposition"))
            .filter(_.nonEmpty)
            .flatMap { contentDispositionString =>
              zio.http.Header.ContentDisposition.parse(contentDispositionString) match {
                case Left(err) => logger.debug(s"Failed to determine filename for $url: $err"); None
                case Right(zio.http.Header.ContentDisposition.Attachment(filename)) => filename
                case Right(zio.http.Header.ContentDisposition.Inline(filename)) => filename
                case Right(_: zio.http.Header.ContentDisposition.FormField) => None
              }
            }

        def consumeStream(): Either[Artifact2Error, Unit] = {
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
              Left(new Artifact2Error.DownloadError(s"Partially downloaded file $tmp does not match remote file $url: delete the file and try again.", None))
            } else {
              scala.util.Using.resource(
                CC.CacheLocks.withStructureLock(cacheLocation) {
                  coursier.paths.Util.createDirectories(tmp.toPath.getParent);
                  new java.io.FileOutputStream(tmp, partialDownload.isDefined)
                }
              ) { out =>
                val interrupted = !Downloader.readFullyTo(in, out, logger, url, isCanceled, alreadyDownloaded = partialDownload.map(_.alreadyDownloaded).getOrElse(0L))
                if (interrupted) {
                  val msg = s"Download was canceled, so partially downloaded file is incomplete: $tmp"
                  logger.warn(msg)  // we log this directly, since the error cannot usually be handled via API after websocket was closed
                  Left(new Artifact2Error.DownloadError(msg, None))
                } else {
                  Right(())
                }
              }
            }
          }
        }

        def lengthCheck(): Either[Artifact2Error, Unit] =
          lenOpt match {
            case None => Right(())
            case Some(len) =>
              val tmpLen = if (tmp.exists()) tmp.length() else 0L
              if (len == tmpLen)
                Right(())
              else
                Left(new Artifact2Error.WrongLength(tmpLen, len, tmp.getAbsolutePath))
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

          val checksum = Downloader.computeChecksum(file)

          doTouchCheckFile(file, url, filename, checksum)
        }
      }

    } finally {
      if (conn != null) Downloader.closeConn(conn)
    }

  }

  // Here, filename is the name as declared in the HTTP header. We store it in
  // the ttl file in order to be able to restore it if necessary.
  // As this filename may not be persistent, it makes sense not to store files
  // in the cache under this name directly.
  // Additionally we store a checksum to be able to quickly check if a file is
  // still up-to-date by comparing with an expected checksum value.
  def doTouchCheckFile(file: java.io.File, url: String, filename: Option[String], sha256: ArraySeq[Byte]): Unit = {  // without `updateLinks` as we do not download directories
    val ts = System.currentTimeMillis()
    val f  = FileCache.ttlFile(file)
    val arr: Array[Byte] =  // with older sc4pac versions, this could be an empty byte array
      UP.writeToByteArray(JD.CheckFile(filename = filename, checksum = JD.Checksum(sha256 = Some(sha256))))
    scala.util.Using.resource(new java.io.FileOutputStream(f)) { fos => fos.write(arr) }
    f.setLastModified(ts)
    ()
  }

}


object Downloader {

  def isFromSimtropolis(url: java.net.URI | java.net.URL | zio.http.URL): Boolean = {
    (url match {
      case u: java.net.URI => Some(u.getHost)
      case u: java.net.URL => Some(u.getHost)
      case u: zio.http.URL => u.host
    }).exists(host => host == "simtropolis.com" || host.endsWith(".simtropolis.com"))
  }

  class Credentials(val simtropolisToken: Option[String]) {
    def addAuthorizationToMatchingConnection(conn: java.net.HttpURLConnection): Unit = {
      if (simtropolisToken.isDefined && isFromSimtropolis(conn.getURL())) {
        conn.addRequestProperty("Authorization", s"""SC4PAC-TOKEN-ST userkey="${simtropolisToken.get}"""")
      }
    }
    // def addAuthorizationToMatchingRequest(req: zio.http.Request): zio.http.Request = {
    //   if (simtropolisToken.isDefined && isFromSimtropolis(req.url)) {
    //     req.updateHeaders(_.addHeader("Authorization", s"""SC4PAC-TOKEN-ST userkey="${simtropolisToken.get}""""))
    //   } else req
    // }
  }
  val emptyCredentialsLayer = zio.ZLayer.succeed(Credentials(simtropolisToken = None))

  /** Returns true on success, false if data transfer was canceled. */
  private def readFullyTo(
    in: java.io.InputStream,
    out: java.io.OutputStream,
    logger: Logger,
    url: String,
    isCanceled: AtomicBoolean,
    alreadyDownloaded: Long
  ): Boolean = {
    val b = new Array[Byte](Constants.bufferSizeDownload)

    @tailrec
    def helper(count: Long, logged: Boolean): Boolean = {
      if (isCanceled.get()) {
        false
      } else {
        val read = in.read(b)
        if (read >= 0) {
          out.write(b, 0, read)
          out.flush()
          if (count / Constants.downloadProgressQuantization < (count + read) / Constants.downloadProgressQuantization) {
            logger.downloadProgress(url, count + read)  // log only if crossing the quantization marks
            helper(count + read, logged = true)
          } else {
            helper(count + read, logged = false)
          }
        } else {
          if (!logged) logger.downloadProgress(url, count)  // log final size
          true
        }
      }
    }
    helper(alreadyDownloaded, logged = false)
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
  private def urlConnectionMaybePartial(url0: String, specOpt: Option[PartialDownloadSpec], credentials: Downloader.Credentials): (URLConnection, Option[PartialDownloadSpec]) = {

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
            credentials.addAuthorizationToMatchingConnection(conn0)
          case _ =>
        }

        def makeResult[A](x: A): Right[A, (URLConnection, A)] = {
          if (is4xx(conn)) {
            closeConn(conn)
            val respCodeOpt = CC.CacheUrl.responseCode(conn)
            if (respCodeOpt.contains(404))
              throw new Artifact2Error.NotFound(url0)
            else if (respCodeOpt.contains(403))
              throw new Artifact2Error.Forbidden(url0)
            else if (respCodeOpt.contains(401))
              throw new Artifact2Error.Unauthorized(url0)
            else if (respCodeOpt.contains(429))
              throw new Artifact2Error.RateLimited(url0)
            else
              throw new Exception(s"Connection error ${respCodeOpt.map(_.toString).getOrElse("4xx")}: $conn")  // TODO use an Artifact2Error
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
        urlConnectionMaybePartial(url0, specOpt, credentials)  // reconnect, possibly starting from 0
      case Right(ret) =>
        ret
    }
  }

  private val regexContentRange = raw"(?i)bytes (\d+)-(\d+)/(\d+)".r  // case-insensitive regex

  /** Parse length from content-range headers such as
    * `content-range: bytes 0-7521/7522`
    * when content-length header is missing (e.g. on STEX).
    */
  def lengthFromContentRange(conn: URLConnection): Option[Long] = {
    Option(conn.getHeaderField("Content-Range")).collect {
      case regexContentRange(start, end, len) =>
        // For compatibility with `getContentLengthLong` and partial downloads, return length from `start` offset.
        len.toLong - start.toLong
    }
  }

  // blocking computation of sha256
  def computeChecksum(file: java.io.File): ArraySeq[Byte] = {
    val md = java.security.MessageDigest.getInstance("SHA-256")
    scala.util.Using.resource {
      new java.io.FileInputStream(file)
    } { in =>
      val buf = new Array[Byte](Constants.bufferSizeDownload)
      var count = in.read(buf)
      while (count != -1) {
        md.update(buf, 0, count)
        count = in.read(buf)
      }
    }
    ArraySeq.from(md.digest())
  }

  /** This executes an effect on a pool, with the option to cancel the effectful
    * computation. On interrupt, the atomic boolean is flipped from false to true.
    *
    * By scheduling the downloads on the `cache.pool`, we use max 2 downloads
    * in parallel. (This requires that the effects are not already executed on the
    * `ZIO.blocking` pool, which would start to download EVERYTHING in parallel).
    */
  def attemptCancelableOnPool[E, A](pool: java.util.concurrent.ExecutorService, effect: AtomicBoolean => Either[E, A]): IO[Throwable | E, A] = {
    val isCanceled = AtomicBoolean(false)
    ZIO.attempt(effect(isCanceled))
      .onExecutionContext(scala.concurrent.ExecutionContext.fromExecutorService(pool))
      .fork.flatMap(_.join)
      .onInterrupt(ZIO.succeed(isCanceled.set(true)))
      .absolve
  }

}
