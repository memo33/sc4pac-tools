package io.github.memo33
package sc4pac

import coursier.core as C
import zio.{ZIO, Task, RIO}
import upickle.default.Reader

import sc4pac.JsonData as JD
import sc4pac.error.{Sc4pacVersionNotFound, Sc4pacMissingVariant}

object Find {

  /** If version is latest.release, this picks the latest version across all repositories. */
  def concreteVersion(module: C.Module, version: String): RIO[ResolutionContext, String] = {
    // TODO bulk-query MetadataRepository for versions of all modules for efficiency
    if (coursier.core.Latest(version).isEmpty) {
      ZIO.succeed(version)  // version is already concrete
    } else {  // pick latest version
      for {
        context <- ZIO.service[ResolutionContext]
        version <- context.coursierApi.versionsOf(module).map(_.latest)
      } yield version
    }
  }

  /** Find the JD.Package or JD.Asset corresponding to module and version,
    * across all repositories. If not found at all, try a second time with the
    * repository channel contents updated. */
  def packageData[A <: JD.Package | JD.Asset : Reader](module: C.Module, version: String): RIO[ResolutionContext, Option[A]] = {
    import CoursierZio.*  // implicit coursier-zio interop
    def tryAllRepos(repos: Seq[MetadataRepository], context: ResolutionContext): Task[Option[A]] = ZIO.collectFirst(repos) { repo =>
      val task = {
        repo.fetchModuleJson[Task, A](module, version, context.cache.fetch).run
          .map(_.toOption) // repositories not containing module:version can be ignored
      }
      context.cache.logger.using(task)  // properly initializes logger (avoids Uninitialized TermDisplay)
    }

    ZIO.service[ResolutionContext].flatMap { context =>
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
              repos   <- Sc4pac.initializeRepositories(repoUris, context.cache, Constants.channelContentsTtlShort)  // 60 seconds
                          .provideLayer(zio.ZLayer.succeed(ProfileRoot(context.profileRoot)))
              result  <- tryAllRepos(repos, context)  // 2nd try
            } yield result
        }
    }
  }

  /** Given a module without specific variant, find a variant that matches globalVariant. */
  def matchingVariant(module: BareModule, version: String, globalVariant: Variant): RIO[ResolutionContext, (JD.Package, JD.VariantData)] = {

    def pickVariant(pkgOpt: Option[JD.Package]): Task[(JD.Package, JD.VariantData)] = pkgOpt match {
      case None => ZIO.fail(new Sc4pacVersionNotFound(s"Could not find metadata of ${module.orgName} or suitable variant.",
                                                      "Either the package name is spelled incorrectly or the metadata stored in the corresponding channel is incorrect or incomplete."))
      case Some(pkgData) =>
        pkgData.variants.find(vd => isSubMap(vd.variant, globalVariant)) match {
          case Some(vd) => ZIO.succeed((pkgData, vd))
          case None =>
            ZIO.fail(new Sc4pacMissingVariant(pkgData, s"could not find variant for ${module.orgName} matching [${JD.VariantData.variantString(globalVariant)}]"))
        }
    }

    val mod = C.Module(module.group, module.name, attributes = Map.empty)
    concreteVersion(mod, version)
      .flatMap(packageData[JD.Package](mod, _))
      .flatMap(pickVariant)
  }

}
