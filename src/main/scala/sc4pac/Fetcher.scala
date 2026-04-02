package io.github.memo33
package sc4pac

import zio.{ZIO, IO, Task, RIO, Ref}
import upickle.default as UP
import sc4pac.error.Artifact2Error
import Fetcher.Blob

/** A service for downloading files, storing the results in a cache on disk.
  * Call `setCacheLocation` before first use.
  */
trait Fetcher {

  def setCacheLocation(location: os.Path): Task[Unit]

  /** Default time-to-live is `Constants.cacheTtl` */
  def fetch(artifact: Artifact, credentials: Option[Downloader.Credentials], ttl: Option[scala.concurrent.duration.Duration] = None): IO[Artifact2Error, Blob]

  /** Retrieve the file contents as String from the cache or download if necessary, then parse as JSON. */
  def fetchAsJson[A : UP.Reader](artifact: Artifact): IO[Artifact2Error, A]
}

object Fetcher {

  private lazy val globalCacheRef: Ref[Option[FileCache]] = Ref.unsafe.make(None)(using zio.Unsafe)

  val live: zio.URLayer[Logger, Fetcher] = zio.ZLayer.fromFunction(Impl(_, globalCacheRef))

  class Blob(
    val path: os.Path,
    val fallbackFilename: IO[java.io.IOException, Option[String]],
  )

  /** Limits parallel downloads to 2 (ST rejects too many connections). */
  private[sc4pac] def createThreadPool() = coursier.cache.internal.ThreadUtil.fixedThreadPool(size = 2)

  class Impl(logger: Logger, cacheRef: Ref[Option[FileCache]]) extends Fetcher {

    override def setCacheLocation(path: os.Path) = {
      val location = path.toIO
      cacheRef.updateSome {
         // Re-use existing cache or initialize it. This is important so that
         // concurrent access to the API does not hit a locked cache.
         // We don't expect concurrent API access for different cache locations,
         // so it's fine to store just a reference to the most recent cache.
         case opt if !opt.exists(_.location == location) =>
           val coursierPool = createThreadPool()
           val cache = FileCache(location = location, pool = coursierPool)
                         .withTtl(Some(Constants.cacheTtl))  // 12 hours
           Some(cache)
       }
    }

    private def getCache: zio.UIO[FileCache] =
      cacheRef.get.someOrElseZIO(ZIO.dieMessage("Cache location has not been set"))

    override def fetch(artifact: Artifact, credentials: Option[Downloader.Credentials], ttl: Option[scala.concurrent.duration.Duration]) =
      for {
        cache                <- getCache.map(c => if (ttl.isDefined) c.withTtl(ttl) else c)
        file                 <- cache.fetchFile(artifact, credentials, logger)
        fallbackFilenameLazy <- cache.getFallbackFilename(file, logger).memoize
      } yield Blob(os.Path(file), fallbackFilenameLazy)  // file should already be absolute

    private def blobToText(blob: Blob): IO[java.io.IOException, String] =
      zio.ZIO.attemptBlockingIO {
        new String(java.nio.file.Files.readAllBytes(blob.path.toNIO), java.nio.charset.StandardCharsets.UTF_8)
      }

    override def fetchAsJson[A : UP.Reader](artifact: Artifact) = {
      def helper(artifact: Artifact): IO[Artifact2Error, A] =
        for {
          blob <- fetch(artifact, credentials = None)  // as we do not fetch text files from Simtropolis, no need for credentials
          text <- blobToText(blob).mapError {
                    case e: java.io.IOException => new Artifact2Error.DownloadError(
                      s"Caught ${e.getClass().getName()}${Option(e.getMessage).fold("")(" (" + _ + ")")} while reading ${blob.path}",
                      Some(e)
                    )
                  }
          data <- JsonIo.read[A](text, errMsg = artifact.url.toString)
                    .mapError(e => Artifact2Error.JsonFormatError(
                      reason = s"Json format error in file ${blob.path}. If the problem persists, try to manually delete the file from the cache.",
                      e,
                    ))
        } yield data

      helper(artifact)
        .catchSome { case scala.util.control.NonFatal(e) =>
          // retry once (e.g. when downloaded file is corrupted and cannot be parsed as JSON)
          logger.debug(s"""Redownloading artifact "${artifact.url}" due to error: $e""")
          helper(artifact.withForceRedownload(true))
        }
    }

  }
}
