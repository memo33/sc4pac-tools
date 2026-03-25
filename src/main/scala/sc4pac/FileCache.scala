package io.github.memo33
package sc4pac

import java.util.concurrent.ConcurrentHashMap
import java.io.IOException
import coursier.cache as CC
import zio.{ZIO, IO, Task, Promise}

import sc4pac.JsonData as JD
import sc4pac.error.Artifact2Error

/** A thin wrapper around Coursier's FileCache providing minimal functionality
  * in order to supply a custom downloader implementation.
  */
class FileCache private (
  csCache: CC.FileCache[Task],
  runningTasks: ConcurrentHashMap[java.net.URI, Promise[Artifact2Error, java.io.File]]
) {

  def location: java.io.File = csCache.location

  def pool: java.util.concurrent.ExecutorService = csCache.pool

  def ec = csCache.ec

  /** Time-to-live before cached files expire and will be checked for updates
    * (only if they are `changing`).
    */
  def withTtl(ttl: Option[scala.concurrent.duration.Duration]): FileCache =
    new FileCache(csCache.withTtl(ttl), runningTasks)

  def ttl: Option[scala.concurrent.duration.Duration] = csCache.ttl

  /** The cache location corresponding to the URL, regardless of whether the
    * file already exists or not.
    */
  def localFile(url: java.net.URI): java.io.File =
    csCache.localFile(url.toString, user = None)

  private def isManagedByCache(url: java.net.URI, file: java.io.File): Boolean = {
    if (url.getScheme == "file") false
    else true  // TODO as a safeguard, verify that local file is inside cache folder
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

  private def isOlderThan(file: java.io.File, timestamp: java.time.Instant): Boolean =
    lastCheck(file).map(downloadedAt => downloadedAt.isBefore(timestamp)).getOrElse(true)

  // blocking
  // For changing artifacts, determine if it is older than ttl.
  private def isStale(file: java.io.File): Boolean = {
    ttl match {
      case None => true  // if ttl does not exist, consider the file outdated
      case Some(ttl: scala.concurrent.duration.Duration.Infinite) => false
      case Some(ttl: scala.concurrent.duration.FiniteDuration) =>
        import scala.jdk.DurationConverters.*
        isOlderThan(file, java.time.Instant.now().minus(ttl.toJava))
    }
  }

  private def parseCheckFileOf(destFile: java.io.File, logger: Logger): IO[IOException, Option[JD.CheckFile]] =
    ZIO.attemptBlockingIO {
        val checkedFile = FileCache.ttlFile(destFile)
        if (!checkedFile.exists() || checkedFile.length() == 0)   // zero-length is possible for historic reasons
          None
        else
          JsonIo.readBlocking[JD.CheckFile](os.Path(checkedFile.getAbsolutePath()))
            .left.map { err =>
              logger.debug(s"Failed to read .checked file $checkedFile: $err")
            }.toOption
    }

  /** Retrieve the file from the cache or download it if necessary.
    *
    * Refresh policy: Download only files that are
    * - (a) corrupted (force redownload), or
    * - (b) absent, or
    * - (c) changing and outdated (according to ttl of cache), or
    * - (d) non-changing and the remote lastModified timestamp is newer than the local file, or
    * - (e) expected checksum is given and does not match local file and redownload allowed(json: yes, asset: no).
    *
    * If the server indicates "304 not modified" due to matching etag, the file
    * is only redownloaded when (a), (b) or (e).
    *
    * Otherwise, return local file, potentially failing with a checksum error.
    */
  def fetchFile(artifact: Artifact, credentials: Option[Downloader.Credentials], logger: Logger): IO[Artifact2Error, java.io.File] = {
    def task0: IO[Artifact2Error, java.io.File] = {
      val destFile = localFile(artifact.url)
      (for {
        getCheckFileLazy <- parseCheckFileOf(destFile, logger).memoize  // lazily evaluates only when needed
        destFileChecksumVerifiedLazy <- verifyChecksum(destFile, artifact, logger, getCheckFileLazy).memoize  // lazily evaluates only when needed
        condA    =  ZIO.succeed(artifact.forceRedownload)
        condB    <- ZIO.attemptBlockingIO(!destFile.exists()).memoize
        condC    <- ZIO.attemptBlockingIO(artifact.changing && isStale(destFile)).memoize
        condD    <- ZIO.attemptBlockingIO(artifact.lastModified.exists(remoteModificationDate => isOlderThan(destFile, remoteModificationDate))).memoize
        condE    =  ZIO.succeed(artifact.redownloadOnChecksumError) && destFileChecksumVerifiedLazy.map(_.isLeft)
        refresh  <- condA || condB || condC || condD || condE
        result   <- if (refresh) {
                      for {
                        etagOpt  <- getCheckFileLazy.map(_.flatMap(_.etag)).flatMap {
                                      case None => ZIO.succeed(None)
                                      case Some(etag) =>
                                        val ignoreETag = condA || condB || (condC || condD).negate  // where ¬(c ∨ d) implies (e), which avoids evaluating (e) unless necessary
                                        ZIO.unlessZIO(ignoreETag)(ZIO.succeed(etag))
                                    }
                        newFile  <- new Downloader(artifact, etagOpt, cacheLocation = location, localFile = destFile, logger, pool, credentials.getOrElse(Downloader.emptyCredentials)).download
                        // We enforce that checksums match (if present) to avoid redownloading same file repeatedly.
                        //
                        // In case of pkg.json files, there is a small chance (30 minutes time window, see `channelContentsTtl`)
                        // that checksums become out of sync when a pkg.json is updated remotely and the channel contents file
                        // is already cached locally. This will fix itself after 30 minutes.
                        // Alternatively the sc4pac-channel-contents.json file can be manually deleted from cache.
                        _ <- verifyChecksum(newFile, artifact, logger, parseCheckFileOf(newFile, logger)).absolve  // TODO add special handling for local files?
                      } yield newFile
                    } else {
                      destFileChecksumVerifiedLazy.absolve.map((_: Unit) => destFile)
                    }
      } yield result)
        .mapError {
          case e: Artifact2Error => e
          case e: IOException => new Artifact2Error.DownloadError(
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
      p0     <- Promise.make[Artifact2Error, java.io.File]
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

  /** If artifact has an expected checksum, check that it matches the cached
    * file. This is mainly used for checking whether certain cached json files are
    * out-of-date, and for ensuring data integrity of assets that define a checksum in their metadata. */
  private def verifyChecksum(file: java.io.File, artifact: Artifact, logger: Logger, getCheckFile: IO[IOException, Option[JD.CheckFile]]): IO[IOException, Either[Artifact2Error, Unit]] = {
    if (!isManagedByCache(artifact.url, file))
      // For local channels, there's no need to verify checksums as the local
      // channel files are always up-to-date and Downloader .checked files do not exist.
      ZIO.succeed(Right(()))
    else artifact.checksum.sha256 match
      case None => ZIO.succeed(Right(()))  // no validation if no checksum is given
      case Some(sha256Expected) => getCheckFile.map {
        case None => Left(Artifact2Error.ChecksumNotFound(sumType = "sha256", file = file.toString))
        case Some(data) =>
          logger.debug(s"Verifying checksum for file $file")
          data.checksum.sha256.toRight(left = Artifact2Error.ChecksumNotFound(sumType = "sha256", file = file.toString))
            .flatMap { sha256Actual =>
              if (sha256Actual == sha256Expected)
                Right(())
              else
                Left(Artifact2Error.WrongChecksum(sumType = "sha256", got = JD.Checksum.bytesToString(sha256Actual),
                  expected = JD.Checksum.bytesToString(sha256Expected), file = file.toString, sumFile = FileCache.ttlFile(file).toString))
            }
      }
  }

  def getFallbackFilename(file: java.io.File, logger: Logger): IO[IOException, Option[String]] = ZIO.attemptBlockingIO {
    val checkedFile = FileCache.ttlFile(file)
    if (!checkedFile.exists() || checkedFile.length() == 0)
      None
    else {
      JsonIo.readBlocking[JD.CheckFile](os.Path(checkedFile.getAbsolutePath())) match {
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
  def apply(location: java.io.File, pool: java.util.concurrent.ExecutorService): FileCache = {
    import sc4pac.CoursierZio.given  // implicit coursier-zio interop
    val csCache = CC.FileCache[Task]()
      .withLocation(location)
      .withPool(pool)
      .withLocalArtifactsShouldBeCached(false)  // not caching local files allows live-editing
    new FileCache(csCache, runningTasks = new ConcurrentHashMap())
  }

  // Copied from coursier internals:
  // https://github.com/coursier/coursier/blob/3e212b42d3bda5d80453b4e7804670ccf75d4197/modules/cache/jvm/src/main/scala/coursier/cache/internal/Downloader.scala#L436
  // TODO add regression test
  private[sc4pac] def ttlFile(file: java.io.File) = new java.io.File(file.getParent, s".${file.getName}.checked")
}
