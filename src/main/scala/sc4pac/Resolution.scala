package io.github.memo33
package sc4pac

import coursier.core as C
import zio.{ZIO, RIO}
import scala.collection.immutable.TreeSeqMap

import sc4pac.JsonData as JD
import sc4pac.Constants.isSc4pacAsset
import sc4pac.error.Sc4pacAssetNotFound
import Resolution.{Dep, DepAsset}

object CoursierUtil {
  def bareDepFromModule(module: C.Module): BareDep =
    if (isSc4pacAsset(module)) BareAsset(module.name) else BareModule(module.organization, module.name)
}

/** Wrapper around Coursier's resolution mechanism with more stringent types for
  * our purposes.
  */
object Resolution {

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
    private[Resolution] def fromBareDependency(dependency: BareDep, globalVariant: Variant): RIO[ResolutionContext, Dep] = dependency match {
      case BareAsset(assetId) =>
        // assets do not have variants
        val mod = C.Module(Constants.sc4pacAssetOrg, assetId, attributes = Map.empty)
        Find.concreteVersion(mod, Constants.versionLatestRelease)
          .flatMap(Find.packageData[JD.Asset](mod, _))
          .flatMap {
            case None => ZIO.fail(new Sc4pacAssetNotFound(s"Could not find metadata of asset ${assetId.value}.",
              "Most likely this is due to incorrect or incomplete metadata in the corresponding channel."))
            case Some(data) => ZIO.succeed(DepAsset.fromAsset(data))
          }
      case bareMod @ BareModule(group, name) =>
        val mod = C.Module(group, name, attributes = Map.empty)
        for {
          concreteVersion  <- Find.concreteVersion(mod, Constants.versionLatestRelease)
          (_, variantData) <- Find.matchingVariant(bareMod, concreteVersion, globalVariant)
        } yield DepModule(group = group, name = name, version = concreteVersion, variant = variantData.variant)
    }
  }

  /** An sc4pac asset dependency: a leaf in the dependency tree.
    * This class contains the functionally relevant internal data.
    * In contrast, `JD.Asset` is merely used for JSON-serialization.
    */
  final case class DepAsset(
    assetId: C.ModuleName,
    version: String,
    url: String,
    lastModified: Option[java.time.Instant],
    archiveType: Option[JD.ArchiveType],
  ) extends Dep {
    def isSc4pacAsset: Boolean = true
    def toBareDep: BareAsset = BareAsset(assetId)
  }
  object DepAsset {
    def fromAsset(asset: JD.Asset): DepAsset =
      DepAsset(assetId = ModuleName(asset.assetId), version = asset.version, url = asset.url,
        lastModified = Option(asset.lastModified), archiveType = asset.archiveType)
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
          val s = JD.VariantData.variantString(variant)
          if (s.nonEmpty) s" [$s]" else ""
      }
      gray(s"${group.value}:") + name.value + " " + gray(version + variantStr)
    }
  }

  /** We resolve dependencies without concrete version information, but
    * implicitly always take the latest version. That way, we do not need to
    * worry about reconciliation strategies or dependency cycles.
    */
  def resolve(initialDependencies: Seq[BareDep], globalVariant: Variant): RIO[ResolutionContext, Resolution] = {

    // TODO avoid looking up variants and packageData multiple times
    def lookupDependencies(dep: BareDep): RIO[ResolutionContext, Seq[BareDep]] = dep match {
      case dep: BareAsset => ZIO.succeed(Seq.empty)
      case mod: BareModule => {
        Find.matchingVariant(mod, Constants.versionLatestRelease, globalVariant)
          .map { (pkgData, variantData) => variantData.bareDependencies }
      }
    }

    // Here we iteratively compute transitive dependencies.
    // The keys contain all reachable dependencies, the values may be empty sequences.
    // The TreeSeqMap preserves insertion order.
    val computeReachableDependencies: RIO[ResolutionContext, TreeSeqMap[BareDep, Seq[BareDep]]] =
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
  def fetchArtifactsOf(subset: Seq[Dep]): RIO[ResolutionContext, Seq[(DepAsset, Artifact, java.io.File)]] = {
    val assetsArtifacts = subset.collect{ case d: DepAsset =>
      (d, Artifact(d.url, changing = d.lastModified.isEmpty, lastModified = d.lastModified))  // non-changing assets should have lastModified defined and vice versa
    }
    def fetchTask(context: ResolutionContext) =
      ZIO.foreachPar(assetsArtifacts) { (dep, art) =>
        context.cache.file(art).map(file => (dep, art, file))
      }
      .catchSome { case e: (coursier.error.FetchError.DownloadingArtifacts
                          | coursier.cache.ArtifactError.DownloadError
                          | coursier.cache.ArtifactError.WrongLength
                          | coursier.cache.ArtifactError.NotFound) =>
        ZIO.fail(new error.DownloadFailed("Failed to download some assets. Try again later. " +
          "You may have reached your daily download quota (Simtropolis: 20 files per day) or the file exchange server is currently unavailable.",
          e.getMessage))
      }

    for {
      context <- ZIO.service[ResolutionContext]
      result  <- context.logger.using(context.logger.fetchingAssets(fetchTask(context)))
    } yield result
  }
}
