package io.github.memo33
package sc4pac

import coursier.core as C
import zio.{ZIO, Task}
import upickle.default.Reader

import sc4pac.Data.{PackageData, VariantData}
import sc4pac.error.{Sc4pacIoException, Sc4pacMissingVariant}
import sc4pac.Resolution.BareModule

object Find {

  /** If version is latest.release, this picks the latest version across all repositories. */
  def concreteVersion(module: C.Module, version: String)(using context: ResolutionContext): Task[String] = {
    // TODO bulk-query MetadataRepository for versions of all modules for efficiency
    if (coursier.core.Latest(version).isEmpty) {
      ZIO.succeed(version)  // version is already concrete
    } else {  // pick latest version
      context.coursierApi.versions.withModule(module).result().map(_.versions.latest)
    }
  }

  /** Find the PackageData or AssetData corresponding to module and version,
    * across all repositories. If not found at all, try a second time with the
    * repository channel contents updated. */
  def packageData[A : Reader](module: C.Module, version: String)(using context: ResolutionContext): Task[Option[A]] = {
    import CoursierZio.*  // implicit coursier-zio interop
    def tryAllRepos(repos: Seq[MetadataRepository], context: ResolutionContext): Task[Option[A]] = ZIO.collectFirst(repos) { repo =>
      val task = {
        repo.fetchModuleJson[Task, A](module, version, context.cache.fetch).run
          .map(_.toOption) // repositories not containing module:version can be ignored
      }
      context.cache.logger.using(task)  // properly initializes logger (avoids Uninitialized TermDisplay)
    }

    tryAllRepos(context.repositories, context)
      .flatMap {
        case result: Some[A] => ZIO.succeed(result)
        case None =>
          // None of the repositories contains the package, so we re-initialize
          // the repositories to make sure the channel-contents are up-to-date.
          // As we do not want to redownload the channel-contents at every
          // package, we use a short time-to-live that is long enough for the
          // current sc4pac command to complete.
          // For simplicity, we do not store the new repositories -- this should
          // only affect few packages that have been updated, anyway.
          import concurrent.duration.DurationInt
          val repoUris = context.repositories.map(_.baseUri)
          context.logger.log(s"Could not find metadata of ${module}. Trying to update channel contents.")
          for {
            repos   <- Sc4pac.initializeRepositories(repoUris, context.cache, channelContentsTtl = Some(30.seconds))
            result  <- tryAllRepos(repos, context)  // 2nd try
          } yield result
      }
  }

  /** Given a module without specific variant, find a variant that matches globalVariant. */
  def matchingVariant(module: BareModule, version: String, globalVariant: Variant)(using ResolutionContext): Task[(PackageData, VariantData)] = {

    def pickVariant(pkgOpt: Option[PackageData]): Task[(PackageData, VariantData)] = pkgOpt match {
      case None => ZIO.fail(new Sc4pacIoException(s"could not find metadata of ${module.orgName} or suitable variant"))
      case Some(pkgData) =>
        pkgData.variants.find(vd => isSubMap(vd.variant, globalVariant)) match {
          case Some(vd) => ZIO.succeed((pkgData, vd))
          case None =>
            ZIO.fail(new Sc4pacMissingVariant(pkgData, s"could not find variant for ${module.orgName} matching [${VariantData.variantString(globalVariant)}]"))
        }
    }

    val mod = C.Module(module.group, module.name, attributes = Map.empty)
    concreteVersion(mod, version)
      .flatMap(packageData[PackageData](mod, _))
      .flatMap(pickVariant)
  }

}
