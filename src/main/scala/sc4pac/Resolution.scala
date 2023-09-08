package io.github.memo33
package sc4pac

import coursier.core as C
import coursier.util.Artifact
import zio.{ZIO, IO, Task}
import scala.collection.immutable.TreeSeqMap

import sc4pac.Constants.isSc4pacAsset
import sc4pac.error.Sc4pacIoException
import Resolution.{Dep, BareDep, DepAsset}

/** Wrapper around Coursier's resolution mechanism with more stringent types for
  * our purposes.
  */
object Resolution {

  sealed trait BareDep {
    def orgName: String
  }
  final case class BareModule(group: C.Organization, name: C.ModuleName) extends BareDep {  // a dependency without version information, variant data or any other attributes
    def orgName = s"${group.value}:${name.value}"
    def formattedDisplayString(gray: String => String): String = gray(s"${group.value}:") + name.value
  }
  final case class BareAsset(assetId: C.ModuleName) extends BareDep {
    def orgName = s"${Constants.sc4pacAssetOrg.value}:${assetId.value}"
  }

  /** An sc4pac asset or module (metadata package) containing the relevant
    * information for resolving dependencies.
    * For assets, this means the url and lastModified attributes.
    * For modules (metadata packages), this includes the variant.
    */
  sealed trait Dep {
    def isSc4pacAsset: Boolean
    def version: String
    def toBareDep: BareDep
    def orgName: String = toBareDep.orgName
  }

  object Dep {
    /** Given a package dependency without specified variant, use globalVariant
      * information to pick a concrete variant for the dependency, if dependency
      * supports multiple variants;
      * Given an asset reference dependency, look up its url and lastModified
      * attributes.
      */
    private[Resolution] def fromBareDependency(dependency: BareDep, globalVariant: Variant)(using ResolutionContext): Task[Dep] = dependency match {
      case BareAsset(assetId) =>
        // assets do not have variants
        val mod = C.Module(Constants.sc4pacAssetOrg, assetId, attributes = Map.empty)
        Find.concreteVersion(mod, Constants.versionLatestRelease)
          .flatMap(Find.packageData[Data.AssetData](mod, _))
          .flatMap {
            case None => ZIO.fail(new Sc4pacIoException(s"could not find attribute for ${mod}"))
            case Some(data) => ZIO.succeed(data.toDepAsset)
          }
      case bareMod @ BareModule(group, name) =>
        val mod = C.Module(group, name, attributes = Map.empty)
        for {
          concreteVersion  <- Find.concreteVersion(mod, Constants.versionLatestRelease)
          (_, variantData) <- Find.matchingVariant(bareMod, concreteVersion, globalVariant)
        } yield DepModule(group = group, name = name, version = concreteVersion, variant = variantData.variant)
    }
  }

  /** An sc4pac asset dependency: a leaf in the dependency tree. */
  final case class DepAsset(
    assetId: C.ModuleName,
    version: String,
    url: String,
    lastModified: Option[java.time.Instant]
  ) extends Dep {
    def isSc4pacAsset: Boolean = true
    def toBareDep: BareAsset = BareAsset(assetId)
  }

  /** An sc4pac metadata package dependency. */
  final case class DepModule(
    group: C.Organization,
    name: C.ModuleName,
    version: String,
    variant: Variant
  ) extends Dep {
    def isSc4pacAsset: Boolean = false
    def toBareDep: BareModule = BareModule(group, name)

    def formattedDisplayString(gray: String => String): String = {
      val variantStr = {
          val s = Data.VariantData.variantString(variant)
          if (s.nonEmpty) s" [$s]" else ""
      }
      gray(s"${group.value}:") + name.value + " " + gray(version + variantStr)
    }
  }

  // Copied from coursier internals:
  // https://github.com/coursier/coursier/blob/3e212b42d3bda5d80453b4e7804670ccf75d4197/modules/cache/jvm/src/main/scala/coursier/cache/internal/Downloader.scala#L436
  // TODO add regression test
  private def ttlFile(file: java.io.File) = new java.io.File(file.getParent, s".${file.getName}.checked")

  private def deleteStaleCachedFile(file: java.io.File, lastModified: java.time.Instant, cache: coursier.cache.FileCache[Task]): IO[coursier.cache.ArtifactError | Throwable, Unit] = {
    ZIO.attemptBlocking(coursier.cache.CacheLocks.withLockFor(cache.location, file) {
      // Since `file.lastModified()` can be older than the download time,
      // we use coursier's internally used `.checked` file to obtain the
      // actual download time.
      val fileChecked = ttlFile(file)
      if (!file.exists()) {
        Right(())  // nothing to delete
      } else if (fileChecked.exists()) {
        val downloadedAt = java.time.Instant.ofEpochMilli(fileChecked.lastModified())
        if (downloadedAt.isBefore(lastModified)) {
          val success = file.delete()  // we ignore if deletion fails
        }
        Right(())
      } else {
        // Since the .checked file may be missing from cache for various issues
        // outside our conrol, we always delete the file in this case.
        println(s"The cache file did not exist: $fileChecked")  // TODO logger.warn
        val success = file.delete()  // we ignore if deletion fails
        Right(())
      }
    }).absolve
  }

  /** This transforms the resolution.
    * In case of coursier.cache.ArtifactError,
    * the file was locked, so could not be deleted (hint: if error persists, manually delete the .lock file)
    */
  private def deleteStaleCachedFiles(assetsArtifacts: Seq[(DepAsset, Artifact)], cache: coursier.cache.FileCache[Task]): Task[Unit] = {
    ZIO.foreachDiscard(assetsArtifacts) { case (dep, artifact) =>
      if (artifact.changing) {
        ZIO.succeed(())  // artifact is changing, so cache.ttl (time-to-live) determines how long a file is cached
      } else {
        val lastModifiedOpt = dep.lastModified
        assert(lastModifiedOpt.isDefined, s"non-changing assets should have lastModified defined: $artifact $dep")
        deleteStaleCachedFile(cache.localFile(artifact.url, user=None), lastModifiedOpt.get, cache)
      }
    }
  }

  /** We resolve dependencies without concrete version information, but
    * implicitly always take the latest version. That way, we do not need to
    * worry about reconciliation strategies or dependency cycles.
    */
  def resolve(initialDependencies: Seq[BareDep], globalVariant: Variant)(using context: ResolutionContext): Task[Resolution] = {

    // TODO avoid looking up variants and packageData multiple times
    def lookupDependencies(dep: BareDep): Task[Seq[BareDep]] = dep match {
      case dep: BareAsset => ZIO.succeed(Seq.empty)
      case mod: BareModule => {
        Find.matchingVariant(mod, Constants.versionLatestRelease, globalVariant)
          .map { (pkgData, variantData) => variantData.bareDependencies }
      }
    }

    // Here we iteratively compute transitive dependencies.
    // The keys contain all reachable dependencies, the values may be empty sequences.
    // The TreeSeqMap preserves insertion order.
    val computeReachableDependencies: Task[TreeSeqMap[BareDep, Seq[BareDep]]] =
      ZIO.iterate((TreeSeqMap.empty[BareDep, Seq[BareDep]], initialDependencies))(_._2.nonEmpty) { (seen, remaining) =>
        for {
          seen2 <- ZIO.foreachPar(remaining.filterNot(seen.contains))(d => lookupDependencies(d).map(ds => (d, ds)))
          deps2 = seen2.flatMap(_._2).distinct
        } yield (seen ++ seen2, deps2)
      }.map(_._1)

    for {
      reachableDeps <- computeReachableDependencies
      nonbareDeps   <- ZIO.foreach(reachableDeps.keySet) { d => Dep.fromBareDependency(d, globalVariant).map(d -> _) }
    } yield Resolution(reachableDeps, nonbareDeps.toMap)

  }

}

class Resolution(reachableDeps: TreeSeqMap[BareDep, Seq[BareDep]], nonbareDeps: Map[BareDep, Dep]) {

  private val reverseDeps: Map[BareDep, Seq[BareDep]] = {  // note that this drops modules with zero dependencies
    reachableDeps.toSeq
      .flatMap((d0, d1s) => d1s.map(_ -> d0))  // reverse dependency relation
      .groupMap(_._1)(_._2)
  }

  /** Since `TreeSeqMap` preserves insertion order, this sequence should contain
    * all reachable dependencies such that, if you take any number from the
    * right, you obtain a set of dependencies that includes all its transitive
    * dependencies (if there are no cycles). In other words, the tails are
    * closed under the operation of taking dependencies.
    */
  val transitiveDependencies: Seq[Dep] = reachableDeps.keysIterator.map(nonbareDeps).toSeq

  /** Compute the direct dependencies. */
  def dependenciesOf(dep: Dep): Set[Dep] = {
    reachableDeps(dep.toBareDep).map(nonbareDeps).toSet
  }

  /** Compute the direct reverse dependencies. */
  def dependentsOf(dependencies: Set[Dep]): Set[Dep] = {
    dependencies.map(_.toBareDep).flatMap(d => reverseDeps.get(d).getOrElse(Seq.empty)).map(nonbareDeps)
  }

  /** Download artifacts of a subset of the dependency set of the resolution, or
    * take files from cache in case they are still up-to-date.
    */
  def fetchArtifactsOf(subset: Seq[Dep])(using context: ResolutionContext): Task[Seq[(DepAsset, Artifact, java.io.File)]] = {
    val assetsArtifacts = subset.collect{ case d: DepAsset => (d, MetadataRepository.createArtifact(d.url, d.lastModified)) }
    val fetchTask =
      ZIO.foreachPar(assetsArtifacts) { (dep, art) =>
        context.cache.file(art).run.absolve.map(file => (dep, art, file))
      }
      // By scheduling the above downloads on the Coursier `cache.pool`, we use
      // max 2 downloads in parallel (this requires that the previous tasks are
      // not already on the `ZIO.blocking` pool, which would start to download
      // EVERYTHING in parallel).
      .onExecutionContext(scala.concurrent.ExecutionContext.fromExecutorService(context.cache.pool))
      .catchSome { case e: coursier.error.FetchError.DownloadingArtifacts =>
        ZIO.fail(new Sc4pacIoException("Failed to download some assets. " +
          f"You may have reached your daily download quota (Simtropolis: 20 files per day) or the file exchange server is currently unavailable.%n$e"))
      }

    import CoursierZio.*  // implicit coursier-zio interop
    Resolution.deleteStaleCachedFiles(assetsArtifacts, context.cache)
      .zipRight(context.logger.using(fetchTask))
  }
}
