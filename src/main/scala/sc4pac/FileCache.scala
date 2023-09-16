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

  def fetch = csCache.fetch

  /** The cache location corresponding to the URL, regardless of whether the
    * file already exists or not.
    */
  def localFile(url: String): java.io.File =
    csCache.localFile(url, user = None)

  def file(artifact: coursier.util.Artifact): coursier.util.EitherT[Task, CC.ArtifactError, java.io.File] =
    csCache.file(artifact)

  /** Time-to-live before cached files expire and will be checked for updates
    * (only if they are `changing`).
    */
  def withTtl(ttl: Option[scala.concurrent.duration.Duration]): FileCache =
    new FileCache(csCache.withTtl(ttl))

  def ttl: Option[scala.concurrent.duration.Duration] = csCache.ttl
}

object FileCache {
  def apply(location: java.io.File, logger: CC.CacheLogger, pool: java.util.concurrent.ExecutorService): FileCache = {
    val csCache = CC.FileCache[Task]().withLocation(location).withLogger(logger).withPool(pool)
    new FileCache(csCache)
  }
}
