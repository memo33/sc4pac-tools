package io.github.memo33
package sc4pac

import coursier.core as C
import zio.{ZIO, RIO}
import scala.collection.immutable.TreeSeqMap

import sc4pac.JsonData as JD
import sc4pac.error.{Sc4pacAssetNotFound, Artifact2Error}
import Resolution.{Dep, DepAsset, Links}

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
    /** Given a package dependency without specified variant, use variantSelection
      * information to pick a concrete variant for the dependency, if dependency
      * supports multiple variants;
      * Given an asset reference dependency, look up its url and lastModified
      * attributes.
      */
    private[Resolution] def fromBareDependency(dependency: BareDep, variantSelection: VariantSelection): RIO[ResolutionContext, Dep] = dependency match {
      case bareAsset: BareAsset =>
        // assets do not have variants
        Find.concreteVersion(bareAsset, Constants.versionLatestRelease)
          .flatMap(Find.packageData[JD.Asset](bareAsset, _))
          .flatMap {
            case None => ZIO.fail(new Sc4pacAssetNotFound(s"Could not find metadata of asset ${bareAsset.assetId.value}.",
              "Most likely this is due to incorrect or incomplete metadata in the corresponding channel."))
            case Some((data, url)) => ZIO.succeed(DepAsset.fromAsset(data))
          }
      case bareMod @ BareModule(group, name) =>
        for {
          concreteVersion        <- Find.concreteVersion(bareMod, Constants.versionLatestRelease)
          (_, variantData, url)  <- Find.matchingVariant(bareMod, concreteVersion, variantSelection)
        } yield DepModule(group = group, name = name, version = concreteVersion, variant = variantData.variant)
    }
  }

  /** An sc4pac asset dependency: a leaf in the dependency tree.
    * This class contains the functionally relevant internal data.
    * In contrast, `JD.Asset` is merely used for JSON-serialization.
    *
    * When any of the fields changes, this should trigger a redownload of the
    * asset. (There might be a delay by Constants.channelContentsTtl until the
    * channel is updated.)
    */
  final case class DepAsset(
    assetId: C.ModuleName,
    version: String,
    url: java.net.URI,
    lastModified: Option[java.time.Instant],
    archiveType: Option[JD.ArchiveType],
    checksum: JD.Checksum,
  ) extends Dep {
    def isSc4pacAsset: Boolean = true
    def toBareDep: BareAsset = BareAsset(assetId)
  }
  object DepAsset {
    def fromAsset(asset: JD.Asset): DepAsset =
      DepAsset(assetId = ModuleName(asset.assetId), version = asset.version, url = asset.url,
        lastModified = Option(asset.lastModified), archiveType = asset.archiveType, checksum = asset.checksum)
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

  /** Dependency links or conflicts to other packages. */
  class Links(val dependencies: Seq[BareDep], val conflicts: Seq[BareModule])

  /** We resolve dependencies without concrete version information, but
    * implicitly always take the latest version. That way, we do not need to
    * worry about reconciliation strategies or dependency cycles.
    */
  def resolve(initialDependencies: Seq[BareDep], variantSelection: VariantSelection): RIO[ResolutionContext, Resolution] = {

    // TODO avoid looking up variants and packageData multiple times
    def lookupDependencyLinks(dep: BareDep): RIO[ResolutionContext, Links] = dep match {
      case dep: BareAsset => ZIO.succeed(Links(Seq.empty, Seq.empty))
      case mod: BareModule => {
        Find.matchingVariant(mod, Constants.versionLatestRelease, variantSelection)
          .map { (pkgData, variantData, _) => Links(dependencies = variantData.bareDependencies, conflicts = variantData.conflictingPackages) }
      }
    }

    // Here we iteratively compute transitive dependencies.
    // The keys contain all reachable dependencies, the values may be empty sequences.
    // The TreeSeqMap preserves insertion order.
    val computeReachableDependencies: RIO[ResolutionContext, TreeSeqMap[BareDep, Links]] =
      ZIO.iterate((TreeSeqMap.empty[BareDep, Links], initialDependencies))(_._2.nonEmpty) { (seen, remaining) =>
        for {
          seen2 <-  ZIO.validatePar(remaining.filterNot(seen.contains)) { d =>
                      lookupDependencyLinks(d).map(links => (d, links))
                    }
                    .mapError { errs =>
                      errs.find(!_.isInstanceOf[error.Sc4pacVersionNotFound]) match {
                        case Some(e) => e
                        case None =>
                          val deps = errs.map(_.asInstanceOf[error.Sc4pacVersionNotFound].dep).distinct
                          error.UnresolvableDependencies(
                            title = "Some packages could not be resolved. Maybe they have been renamed or deleted from the corresponding channel.",
                            detail = deps.map(_.orgName).mkString(f"%n"),
                            deps,
                          )
                      }
                    }
          deps2 = seen2.flatMap(_._2.dependencies).distinct
        } yield (seen ++ seen2, deps2)
      }.map(_._1)

    // note that this drops modules with zero dependencies
    def computeReverseDependencies(reachableDeps: TreeSeqMap[BareDep, Links]): Map[BareDep, Seq[BareDep]] =
      reachableDeps.toSeq
        .flatMap((d0, links) => links.dependencies.map(_ -> d0))  // reverse dependency relation
        .groupMap(_._1)(_._2)

    def computeExplicitPackagesTransitivelyDependingOn(mod: BareModule, reverseDeps: Map[BareDep, Seq[BareDep]]): Seq[BareModule] = {
      val seen = collection.mutable.Set[BareDep](mod)
      var remaining = reverseDeps.getOrElse(mod, Nil).filterNot(seen)
      while (remaining.nonEmpty) {
        seen.addAll(remaining)
        remaining = remaining.flatMap(d => reverseDeps.getOrElse(d, Nil)).filterNot(seen)
      }
      initialDependencies.collect { case d: BareModule if seen(d) => d }
    }

    for {
      reachableDeps  <- computeReachableDependencies
      reverseDeps    =  computeReverseDependencies(reachableDeps)
      conflictOpt    =  reachableDeps.iterator.collect { case (dep: BareModule, links) =>  // assets can't have conflicts
                          links.conflicts.find(reachableDeps.contains).map(dep -> _)
                        }.flatten.nextOption(): Option[(BareModule, BareModule)]
      _              <- ZIO.noneOrFailWith(conflictOpt) { conflict =>
                          val pkgs1 = computeExplicitPackagesTransitivelyDependingOn(conflict._1, reverseDeps)
                          val pkgs2 = computeExplicitPackagesTransitivelyDependingOn(conflict._2, reverseDeps)
                          val hint = f"""Either uninstall:%n%n${pkgs1.map(m => s"    ${m.orgName}").mkString(f"%n")}%n%nOr uninstall:%n%n${pkgs2.map(m => s"    ${m.orgName}").mkString(f"%n")}"""
                          error.ConflictingPackages(
                            title = s"The packages ${conflict._1.orgName} and ${conflict._2.orgName} are in conflict with each other and cannot be installed at the same time.",
                            detail = "Decide which of the two packages you want to keep; uninstall the other and all packages that depend on it."
                              + f" Sometimes, choosing different package variants can resolve the conflict, as well.%n$hint",
                            conflict = conflict,
                            explicitPackages1 = pkgs1,
                            explicitPackages2 = pkgs2,
                          )
                        }
      nonbareDeps    <- ZIO.foreach(reachableDeps.keySet) { d => Dep.fromBareDependency(d, variantSelection).map(d -> _) }
    } yield Resolution(reachableDeps, nonbareDeps.toMap, reverseDeps)

  }

}

class Resolution(reachableDeps: TreeSeqMap[BareDep, Links], nonbareDeps: Map[BareDep, Dep], reverseDeps: Map[BareDep, Seq[BareDep]]) {

  /** Since `TreeSeqMap` preserves insertion order, this sequence should contain
    * all reachable dependencies such that, if you take any number from the
    * right, you obtain a set of dependencies that includes all its transitive
    * dependencies (if there are no cycles). In other words, the tails are
    * closed under the operation of taking dependencies.
    */
  val transitiveDependencies: Seq[Dep] = reachableDeps.keysIterator.map(nonbareDeps).toSeq

  /** Compute the direct dependencies. */
  def dependenciesOf(dep: Dep): Set[Dep] = {
    reachableDeps(dep.toBareDep).dependencies.map(nonbareDeps).toSet
  }

  /** Compute the direct reverse dependencies. */
  def dependentsOf(dependencies: Set[Dep]): Set[Dep] = {
    dependencies.map(_.toBareDep).flatMap(d => reverseDeps.get(d).getOrElse(Seq.empty)).map(nonbareDeps)
  }

  /** Download artifacts of a subset of the dependency set of the resolution, or
    * take files from cache in case they are still up-to-date.
    */
  def fetchArtifactsOf(subset: Seq[Dep], urlFallbacks: Map[java.net.URI, os.Path]): RIO[ResolutionContext & Downloader.Credentials, Seq[(DepAsset, Artifact, java.io.File)]] = {
    val assetsArtifacts = subset.collect{ case d: DepAsset =>
      (d, Artifact(
        d.url,
        changing = d.lastModified.isEmpty,
        lastModified = d.lastModified,  // non-changing assets should have lastModified defined and vice versa
        checksum = d.checksum,
        redownloadOnChecksumError = false,
        localMirror = urlFallbacks.get(d.url),
      ))
    }
    def fetchTask(context: ResolutionContext) =
      ZIO.foreachPar(assetsArtifacts) { (dep, art) =>
        context.cache.fetchFile(art).map(file => (dep, art, file))
          .catchAll {
            // See also download-error handling in Find.
            case e: (Artifact2Error.WrongChecksum | Artifact2Error.ChecksumFormatError | Artifact2Error.ChecksumNotFound) =>
              ZIO.fail(new error.ChecksumError(
                f"Checksum verification failed for a downloaded asset.%n" +
                f"- Either, this means the downloaded file is incomplete: Delete the file to try downloading it again.%n" +
                "- Otherwise, this means the uploaded file was modified after the channel metadata was last updated, " +
                "so the integrity of the file cannot be verified by sc4pac: Report this to the maintainers of the metadata.",
                e.getMessage))
            case e: Artifact2Error.Unauthorized =>  // 401
              ZIO.serviceWithZIO[Downloader.Credentials] { credentials =>
                val msg = if (!art.isFromSimtropolis) {
                  "Failed to download some assets due to lack of authorization. This should not normally happen. Please report this problem and mention which file or URL is affected."
                } else if (credentials.simtropolisToken.isDefined) {
                  "Failed to download some assets from Simtropolis. Your personal Simtropolis authentication token seems to be incorrect."
                } else {
                  "Failed to download some assets from Simtropolis due to lack of authorization. Set up a personal Simtropolis authentication token and try again."
                }
                ZIO.fail(new error.DownloadFailed(msg, e.getMessage, url = Some(art.url)))
              }
            case e: Artifact2Error.RateLimited =>  // 429
              ZIO.serviceWithZIO[Downloader.Credentials] { credentials =>
                val msg = if (art.isFromSimtropolis && !credentials.simtropolisToken.isDefined) {
                  "Failed to download some assets from Simtropolis (rate-limited). " +
                  "You have reached your daily download limit (20 files per day for guests on Simtropolis). " +
                  "Go to Settings to set up a personal Simtropolis authentication token and try again."
                } else {
                  "Failed to download some assets (rate-limited). " +
                  "The file exchange server has blocked your download, as you have sent too many download requests in a short time."
                }
                ZIO.fail(new error.DownloadFailed(msg, e.getMessage, url = Some(art.url)))
              }
            case e: Artifact2Error.Forbidden =>  // 403
              ZIO.serviceWithZIO[Downloader.Credentials] { credentials =>
                val msg = if (art.isFromSimtropolis && !credentials.simtropolisToken.isDefined) {
                  "Failed to download some assets from Simtropolis (forbidden). " +
                  "Your download request has been blocked by Simtropolis or by Cloudflare. " +
                  "Setting up a personal Simtropolis authentication token might resolve the problem (see Settings)."
                } else {
                  "Failed to download some assets (forbidden). " +
                  "Your download request has been blocked by the file exchange server. " +
                  "For example, this can happen when using a public VPN or a suspicious IP address, or when a file has been locked."
                }
                ZIO.fail(new error.DownloadFailed(msg, e.getMessage, url = Some(art.url)))
              }
            case e: (Artifact2Error.DownloadError | Artifact2Error.WrongLength | Artifact2Error.NotFound) =>  // e.g. 500, 404 or other issues
              val msg = "Failed to download some assets. Maybe the file exchange server is currently unavailable. Also check your internet connection."
              ZIO.fail(new error.DownloadFailed(msg, e.getMessage, url = Some(art.url)))
            case e: Artifact2Error =>  // e.g. 500 or other issues
              context.logger.debugPrintStackTrace(e)
              ZIO.fail(new error.DownloadFailed("Unexpected download error.", e.getMessage, url = Some(art.url)))
          }
      }
      .provideSomeLayer(zio.ZLayer.succeed(context.logger))

    for {
      context <- ZIO.service[ResolutionContext]
      result  <- context.logger.fetchingAssets(fetchTask(context))
    } yield result
  }
}
