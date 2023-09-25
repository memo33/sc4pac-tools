package io.github.memo33
package sc4pac

import coursier.cache as CC
import zio.Task

import sc4pac.CoursierZio.*  // implicit coursier-zio interop

/** A wrapper around Coursier's FileCache providing minimal functionality
  * in order to supply a custom downloader implementation.
  */
class FileCache private (csCache: CC.FileCache[Task]) extends CC.Cache[Task] {

  def location: java.io.File = csCache.location

  def logger: CC.CacheLogger = csCache.logger

  override def loggerOpt = Some(logger)

  def pool: java.util.concurrent.ExecutorService = csCache.pool

  def ec = csCache.ec

  /** Time-to-live before cached files expire and will be checked for updates
    * (only if they are `changing`).
    */
  def withTtl(ttl: Option[scala.concurrent.duration.Duration]): FileCache =
    new FileCache(csCache.withTtl(ttl))

  def ttl: Option[scala.concurrent.duration.Duration] = csCache.ttl

  /** The cache location corresponding to the URL, regardless of whether the
    * file already exists or not.
    */
  def localFile(url: String): java.io.File =
    csCache.localFile(url, user = None)

  // blocking
  private def isStale(file: java.io.File): Boolean = {
    def lastCheck = for {
      f <- Some(FileCache.ttlFile(file))
      if f.exists()
      ts = f.lastModified()
      if ts > 0L
    } yield ts

    ttl match {
      case None => true
      case Some(ttl) if !ttl.isFinite => false
      case Some(ttl) =>
        val now = System.currentTimeMillis()
        lastCheck.map(_ + ttl.toMillis < now).getOrElse(true)
    }
  }

  def file(artifact: coursier.util.Artifact): coursier.util.EitherT[Task, CC.ArtifactError, java.io.File] = {
    val destFile = localFile(artifact.url)
    if (destFile.exists() && !isStale(destFile)) {  // TODO other refresh policies?
      coursier.util.EitherT.point(destFile)
    } else {
      // TODO implement retry
      val dl = new Downloader(artifact, cacheLocation = location, localFile = destFile, logger, pool)
      coursier.util.EitherT(dl.download.either)
    }
  }

  def fetch: coursier.util.Artifact => coursier.util.EitherT[Task, String, String] = { artifact =>
    file(artifact).leftMap(_.toString).flatMap { (f: java.io.File) =>
      coursier.util.EitherT {
        zio.ZIO.attemptBlockingIO {
          new String(java.nio.file.Files.readAllBytes(f.toPath), java.nio.charset.StandardCharsets.UTF_8)
        }.mapError(_.toString).either
      }
    }
  }

}

object FileCache {
  def apply(location: java.io.File, logger: CC.CacheLogger, pool: java.util.concurrent.ExecutorService): FileCache = {
    val csCache = CC.FileCache[Task]().withLocation(location).withLogger(logger).withPool(pool)
    new FileCache(csCache)
  }

  // Copied from coursier internals:
  // https://github.com/coursier/coursier/blob/3e212b42d3bda5d80453b4e7804670ccf75d4197/modules/cache/jvm/src/main/scala/coursier/cache/internal/Downloader.scala#L436
  // TODO add regression test
  private[sc4pac] def ttlFile(file: java.io.File) = new java.io.File(file.getParent, s".${file.getName}.checked")
}
