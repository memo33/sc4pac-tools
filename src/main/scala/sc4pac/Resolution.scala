package io.github.memo33
package sc4pac

import coursier.core as C
import zio.{ZIO, IO, Task}

import sc4pac.Constants.isSc4pacAsset
import sc4pac.error.Sc4pacIoException
import Resolution.Dep

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
    private[Resolution] def toDependency: C.Dependency
    def isSc4pacAsset: Boolean
    def version: String
    def orgName: String
    def displayString: String = s"$orgName $version"
  }

  object Dep {
    /** Given a package dependency without specified variant, use globalVariant
      * information to pick a concrete variant for the dependency, if dependency
      * supports multiple variants;
      * Given an asset reference dependency, look up its url and lastModified
      * attributes.
      */
    private[Resolution] def fromBareDependency(dependency: C.Dependency, globalVariant: Variant)(using ResolutionContext): Task[Dep] = {
      if (isSc4pacAsset(dependency.module)) {
        // assets do not have variants
        require(!dependency.module.attributes.contains(Constants.urlKey), s"asset already contains url: $dependency")
        Find.concreteVersion(dependency.module, dependency.version)
          .flatMap(Find.packageData[Data.AssetData](dependency.module, _))
          .flatMap {
            case None => ZIO.fail(new Sc4pacIoException(s"could not find attribute for ${dependency.module}"))
            case Some(data) => ZIO.succeed(data.toDepAsset)
          }
      } else {
        // if some explicit dependencies contain variants, it can happen that
        // dependency already has variants specified, so we remove the attributes
        require(isSubMap(Data.VariantData.variantFromAttributes(dependency.module.attributes), globalVariant),
          s"conflicting variants $dependency $globalVariant")  // sanity check
        ModuleNoAssetNoVar.fromModule(dependency.module.withAttributes(Map.empty)) match {
          case Left(err) => ZIO.fail(new Sc4pacIoException(err))  // dependency should not have variant in attributes
          case Right(module) =>
            for {
              concreteVersion  <- Find.concreteVersion(module.module, dependency.version)
              (_, variantData) <- Find.matchingVariant(module, concreteVersion, globalVariant)
            } yield {
              DepModule(
                group = module.module.organization,
                name = module.module.name,
                version = concreteVersion,
                variant = variantData.variant)
            }
        }
      }
    }
  }

  /** An sc4pac asset dependency: a leaf in the dependency tree. */
  final case class DepAsset(
    assetId: C.ModuleName,
    version: String,
    url: String,
    lastModified: Option[java.time.Instant]
  ) extends Dep {
    private[Resolution] def toDependency: C.Dependency = {
      val attributes = {
        // TODO unify with Data.AssetData.attributes
        val m = Map(Constants.urlKey -> url)
        // lastModified.foreach(Data.AssetData.parseLastModified)  // only accept valid timestamps (throws java.time.format.DateTimeParseException)
        if (lastModified.isDefined) {
          m + (Constants.lastModifiedKey -> lastModified.get.toString)
        } else {
          m
        }
      }
      C.Dependency(C.Module(Constants.sc4pacAssetOrg, assetId, attributes = attributes), version = version)
    }
    def isSc4pacAsset: Boolean = true
    def orgName = s"${Constants.sc4pacAssetOrg.value}:${assetId.value}"
  }

  /** An sc4pac metadata package dependency. */
  final case class DepModule(
    group: C.Organization,
    name: C.ModuleName,
    version: String,
    variant: Variant
  ) extends Dep {
    private[Resolution] def toDependency: C.Dependency = {
      C.Dependency(C.Module(group, name, attributes = Data.VariantData.variantToAttributes(variant)), version = version)
    }

    def isSc4pacAsset: Boolean = false
    def orgName: String = s"${group.value}:${name.value}"

    override def displayString: String = {
      val variantStr = {
          val s = Data.VariantData.variantString(variant)
          if (s.nonEmpty) s"  [$s]" else ""
      }
      super.displayString + variantStr
    }
  }

  // Copied from coursier internals:
  // https://github.com/coursier/coursier/blob/3e212b42d3bda5d80453b4e7804670ccf75d4197/modules/cache/jvm/src/main/scala/coursier/cache/internal/Downloader.scala#L436
  // TODO add regression test
  private def ttlFile(file: java.io.File) = new java.io.File(file.getParent, s".${file.getName}.checked")

  private def deleteStaleCachedFile(file: java.io.File, lastModified: java.time.Instant, cache: coursier.cache.FileCache[Task]): IO[coursier.cache.ArtifactError, Unit] = {
    ZIO.fromEither(coursier.cache.CacheLocks.withLockFor(cache.location, file) {
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
    })
  }

  /** This transforms the resolution.
    * In case of coursier.cache.ArtifactError,
    * the file was locked, so could not be deleted (hint: if error persists, manually delete the .lock file)
    */
  private def deleteStaleCachedFiles(resolution: coursier.core.Resolution, depsWithVariant: Map[C.Dependency, Dep], cache: coursier.cache.FileCache[Task]): Task[Unit] = {
    ZIO.foreachDiscard(resolution.dependencyArtifacts()) { case (dependency, publication, artifact) =>
      if (artifact.changing) {
        ZIO.succeed(())  // artifact is changing, so cache.ttl (time-to-live) determines how long a file is cached
      } else {
        // dependency is a bare dependency without version and attributes, so we look up lastModified from DepAsset type
        val lastModifiedOpt = depsWithVariant(dependency.withConfiguration(C.Configuration.empty)) match {
          case d: DepAsset => d.lastModified
          case _ => None
        }
        assert(lastModifiedOpt.isDefined, s"non-changing assets should have lastModified defined: $artifact $dependency")
        deleteStaleCachedFile(cache.localFile(artifact.url, user=None), lastModifiedOpt.get, cache)
      }
    }
  }

  def resolve(initialDependencies: Seq[Dep], globalVariant: Variant)(using context: ResolutionContext): Task[Resolution] = {
    context.coursierApi.resolve
      .withRepositories(context.repositories.map(_.copy(globalVariant = globalVariant)))
      // The configuration Constants.link is used to distinguish between assets and regular dependencies.
      // In practice, this is useless as we always resolve with assets included, never without assets.
      .mapResolutionParams(_.withDefaultConfiguration(Constants.link).addProperties(globalVariant.toSeq *))  // as a precaution, add variants to resolution properties, as resolution depends on variants
      .addDependencies(initialDependencies.map(_.toDependency) *)
      // TODO remember to transform resolution (handled in fetchArtifactsOf)
      // .transformResolution(_.flatMap(resolution => deleteStaleCachedFiles(resolution, context.cache).map(_ => resolution)))  // TODO consider making lastModified mandatory, so this is not needed
      .io
      .flatMap(create(_, globalVariant))
  }

  private def create(cResolution: C.Resolution, globalVariant: Variant)(using ResolutionContext): Task[Resolution] = {
    ZIO.foreachPar(cResolution.dependencySet.minimizedSet) { d =>
      Dep.fromBareDependency(d, globalVariant).map(d2 => (d.withConfiguration(C.Configuration.empty), d2))
    }.map(depsWithVariant => new Resolution(cResolution, depsWithVariant.toMap))
  }

}

class Resolution(cResolution: C.Resolution, depsWithVariant: Map[C.Dependency, Dep]) {
  private val depsWithVariantInv: Map[Dep, C.Dependency] = depsWithVariant.map(_.swap)
  assert(depsWithVariant.size == depsWithVariantInv.size, s"dependency mapping should be 1-to-1: $depsWithVariant")

  val dependencySet: Set[Dep] = depsWithVariantInv.keySet

  /** Compute the direct dependencies. */
  def dependenciesOf(dep: Dep): Set[Dep] = {
    val cDeps: Seq[C.Dependency] = cResolution.dependenciesOf(depsWithVariantInv(dep).withConfiguration(Constants.link))
    cDeps.iterator.map(_.withConfiguration(C.Configuration.empty)).map(depsWithVariant).toSet
  }

  /** Compute the direct reverse dependencies. */
  def dependentsOf(dependencies: Set[Dep]): Set[Dep] = {
    val depsVersionlessNoVar: Set[C.Dependency] =  // workaround due to reverseDependencies erasing all the versions
      dependencies.flatMap { dep =>
        cResolution.reverseDependencies
          .get(depsWithVariantInv(dep).withVersion(""))
          .getOrElse(Set.empty)  // if empty, then dep is not a dependency of anything (it must be an explicitly installed package from initialDependencies)
      }
    depsWithVariant.iterator.collect {
      case (dNoVar, dWithVar) if depsVersionlessNoVar.contains(dNoVar.withVersion("").withConfiguration(C.Configuration.empty)) => dWithVar
    }.toSet
  }

  // downloading step
  def fetchArtifactsOf(subset: Set[Dep])(using context: ResolutionContext): Task[Seq[(C.Dependency, C.Publication, coursier.util.Artifact, java.io.File)]] = {
    // Since in addTransformArtifacts, some dependencies may have a non-concrete
    // version like latest.release, we compare by orgName ID only, without
    // version or variant. A resolution should only fetch a single version and
    // variant of any package ID, anyway.
    val subsetIds: Set[(C.Organization, C.ModuleName)] = subset.map {
      case d: Resolution.DepAsset => (Constants.sc4pacAssetOrg, d.assetId)
      case d: Resolution.DepModule => (d.group, d.name)
    }
    val fetchTask = context.coursierApi.artifacts
      .withResolution(cResolution)
      .addTransformArtifacts(_.filter { case (dep, pub, art) => subsetIds.contains((dep.module.organization, dep.module.name)) })
      .ioResult
      .map(_.detailedArtifacts)
      .catchSome { case e: coursier.error.FetchError.DownloadingArtifacts =>
        ZIO.fail(new Sc4pacIoException("Failed to download some assets. " +
          f"You may have reached your daily download quota (Simtropolis: 20 files per day) or the file exchange server is currently unavailable.%n$e"))
      }
      // TODO decide what to do when assets are permanently unobtainable (and test this case)
    Resolution.deleteStaleCachedFiles(cResolution, depsWithVariant, context.cache)  // TODO consider making lastModified mandatory, so this is not needed
      .zipRight(fetchTask)
  }
}
