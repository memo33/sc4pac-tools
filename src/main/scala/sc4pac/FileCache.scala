package io.github.memo33
package sc4pac

import java.util.concurrent.ConcurrentHashMap
import coursier.cache as CC
import zio.{ZIO, IO, Task, Promise}

/** A thin wrapper around Coursier's FileCache providing minimal functionality
  * in order to supply a custom downloader implementation.
  */
class FileCache private (
  csCache: CC.FileCache[Task],
  val logger: Logger,
  runningTasks: ConcurrentHashMap[String, Promise[CC.ArtifactError, java.io.File]]
) {

  def location: java.io.File = csCache.location

  def pool: java.util.concurrent.ExecutorService = csCache.pool

  def ec = csCache.ec

  /** Time-to-live before cached files expire and will be checked for updates
    * (only if they are `changing`).
    */
  def withTtl(ttl: Option[scala.concurrent.duration.Duration]): FileCache =
    new FileCache(csCache.withTtl(ttl), logger, runningTasks)

  def ttl: Option[scala.concurrent.duration.Duration] = csCache.ttl

  /** The cache location corresponding to the URL, regardless of whether the
    * file already exists or not.
    */
  def localFile(url: String): java.io.File =
    csCache.localFile(url, user = None)

  private def isManagedByCache(url: String, file: java.io.File): Boolean = {
    if (url.startsWith("file:/")) false
    else true  // TODO as a safeguard, verify that local file is inside cache folder
  }

  /** Remove a file from the cache if it is too old, so that the next cache
    * access retrieves a new copy of it.
    */
  def purgeFileIfOlderThan(url: String, timestamp: java.time.Instant): IO[CC.ArtifactError | java.io.IOException, Unit] = {
    val file = localFile(url)
    if (!isManagedByCache(url, file)) {
      ZIO.succeed(())  // we must not delete local files outside of cache
    } else {
      ZIO.attemptBlockingIO(CC.CacheLocks.withLockFor(location, file) {
        if (!file.exists()) {
          Right(())  // nothing to delete
        } else {
          lastCheck(file) match {
            case Some(downloadedAt) =>
              if (downloadedAt.isBefore(timestamp)) {
                val success = file.delete()  // we ignore if deletion fails
              }
              Right(())
            case None =>
              // Since the .checked file may be missing from cache for various issues
              // outside our conrol, we always delete the file in this case.
              System.err.println(s"The cache ttl-file for $file did not exist.")  // TODO logger.warn
              val success = file.delete()  // we ignore if deletion fails
              Right(())
          }
        }
      }).absolve
    }
  }

  // blocking
  private def lastCheck(file: java.io.File): Option[java.time.Instant] = {
    for {
      f <- Some(FileCache.ttlFile(file))
      if f.exists()
      ts = f.lastModified()
      if ts > 0L
    } yield java.time.Instant.ofEpochMilli(ts)
  }

  // blocking
  private def isStale(file: java.io.File): Boolean = {
    ttl match {
      case None => true  // if ttl-file does not exist, consider the file outdated
      case Some(ttl: scala.concurrent.duration.Duration.Infinite) => false
      case Some(ttl: scala.concurrent.duration.FiniteDuration) =>
        import scala.jdk.DurationConverters.*
        val now = java.time.Instant.now()
        lastCheck(file).map(_.isBefore(now.minus(ttl.toJava))).getOrElse(true)
    }
  }

  /** Retrieve the file from the cache or download it if necessary.
    *
    * Refresh policy: Download only files that are
    * - absent, or
    * - changing and outdated.
    * Otherwise, return local file.
    */
  def file(artifact: Artifact): IO[CC.ArtifactError, java.io.File] = {
    def task0: IO[CC.ArtifactError, java.io.File] = {
      val destFile = localFile(artifact.url)
      ZIO.ifZIO(ZIO.attemptBlockingIO(!destFile.exists() || (artifact.changing && isStale(destFile))))(
        onTrue = new Downloader(artifact, cacheLocation = location, localFile = destFile, logger, pool).download,
        onFalse = ZIO.succeed(destFile)
      ).mapError {
        case e: CC.ArtifactError => e
        case e: java.io.IOException => new CC.ArtifactError.DownloadError(
          s"Caught ${e.getClass().getName()}${Option(e.getMessage).fold("")(" (" + _ + ")")} while accessing $destFile",
          Some(e)
        )
      }
    }

    // Since we did not implement `ifLocked` in Downloader, we use an in-memory
    // cache of concurrently running tasks in order to avoid concurrent download
    // requests for the same URL.
    // (For example, this can happen when updating the sc4pac-channel-contents.json due to several missing packages.)
    // This assumes that only a single sc4pac instance is running.

    // First check if there is a concurrently running task.
    // If so, await its result, otherwise compute the result by running `task0`.
    for {
      p0     <- Promise.make[CC.ArtifactError, java.io.File]
      result <- ZIO.acquireReleaseWith(
                  acquire = ZIO.succeed(runningTasks.putIfAbsent(artifact.url, p0))
                )(
                  release = _ => ZIO.succeed(runningTasks.remove(artifact.url, p0))  // remove only if equal to our p0
                ){ p1 =>
                  if (p1 != null)  // key was present: there was already a running task for url
                    p1.await.zipLeft(ZIO.succeed(logger.concurrentCacheAccess(artifact.url)))
                  else
                    p0.complete(task0).flatMap(_ => p0.await)  // Note that `complete` also handles failure of `task0`
                }
    } yield (result: java.io.File)
  }

  /** Retrieve the file contents as String from the cache or download if necessary. */
  def fetchText: Artifact => IO[CC.ArtifactError, String] = { artifact =>
    file(artifact).flatMap { (f: java.io.File) =>
      zio.ZIO.attemptBlockingIO {
        new String(java.nio.file.Files.readAllBytes(f.toPath), java.nio.charset.StandardCharsets.UTF_8)
      }.mapError {
        case e: java.io.IOException => new CC.ArtifactError.DownloadError(
          s"Caught ${e.getClass().getName()}${Option(e.getMessage).fold("")(" (" + _ + ")")} while reading $f",
          Some(e)
        )
      }
    }
  }

  def getFallbackFilename(file: java.io.File): Option[String] = {
    val checkedFile = FileCache.ttlFile(file)
    if (!checkedFile.exists() || checkedFile.length() == 0)
      None
    else {
      JsonIo.readBlocking[JsonData.CheckFile](os.Path(checkedFile.getAbsolutePath())) match {
        case Left(err) =>
          logger.debug(s"Failed to read filename fallback: $err")
          None
        case Right(data) =>
          data.filename
      }
    }
  }

}

object FileCache {
  def apply(location: java.io.File, logger: Logger, pool: java.util.concurrent.ExecutorService): FileCache = {
    import sc4pac.CoursierZio.*  // implicit coursier-zio interop
    val csCache = CC.FileCache[Task]()
      .withLocation(location)
      // .withLogger(logger)  // TODO verify that this logger really is not needed
      .withPool(pool)
      .withLocalArtifactsShouldBeCached(false)  // not caching local files allows live-editing
    new FileCache(csCache, logger, runningTasks = new ConcurrentHashMap())
  }

  // Copied from coursier internals:
  // https://github.com/coursier/coursier/blob/3e212b42d3bda5d80453b4e7804670ccf75d4197/modules/cache/jvm/src/main/scala/coursier/cache/internal/Downloader.scala#L436
  // TODO add regression test
  private[sc4pac] def ttlFile(file: java.io.File) = new java.io.File(file.getParent, s".${file.getName}.checked")
}
