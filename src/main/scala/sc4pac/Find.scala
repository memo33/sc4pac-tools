package io.github.memo33
package sc4pac

import coursier.cache.ArtifactError
import zio.{ZIO, Task, RIO}
import upickle.default.Reader

import sc4pac.JsonData as JD
import sc4pac.error.{Sc4pacVersionNotFound, Sc4pacMissingVariant, Sc4pacAssetNotFound}

object Find {

  /** If version is latest.release, this picks the latest version across all repositories. */
  def concreteVersion(dep: BareDep, version: String): RIO[ResolutionContext, String] = {
    // TODO bulk-query MetadataRepository for versions of all modules for efficiency
    if (coursier.core.Latest(version).isEmpty) {
      ZIO.succeed(version)  // version is already concrete
    } else {  // pick latest version
      for {
        context <- ZIO.service[ResolutionContext]
        version <- context.coursierApi.versionsOf(dep).map(_.latest)
      } yield version
    }
  }

  // See also download-error handling in Resolution.
  private def handleMetadataDownloadError(orgName: => String, context: ResolutionContext): PartialFunction[Throwable, Task[Option[Nothing]]] = {
    case _: error.Sc4pacVersionNotFound => ZIO.succeed(None)  // repositories not containing module:version can be ignored
    case e: (ArtifactError.WrongChecksum | ArtifactError.ChecksumFormatError | ArtifactError.ChecksumNotFound) =>
      ZIO.fail(new error.ChecksumError(
        s"Checksum verification failed for $orgName. Usually this should not happen and suggests a problem with the channel data.",
        e.getMessage))
    case e: (ArtifactError.DownloadError | ArtifactError.WrongLength | ArtifactError.NotFound) =>
      ZIO.fail(new error.DownloadFailed("Failed to download some metadata files. Check your internet connection.",
        e.getMessage))
    case e: ArtifactError =>
      context.logger.debugPrintStackTrace(e)
      ZIO.fail(new error.DownloadFailed("Unexpected download error.", e.getMessage))
  }

  /** Find the JD.Package or JD.Asset corresponding to module and version,
    * across all repositories. If not found at all, try a second time with the
    * repository channel contents updated. */
  def packageData[A <: JD.Package | JD.Asset : Reader](dep: BareDep, version: String): RIO[ResolutionContext, Option[A]] = {
    def tryAllRepos(repos: Seq[MetadataRepository], context: ResolutionContext): Task[Option[A]] = ZIO.collectFirst(repos) { repo =>
        repo.fetchModuleJson[Logger, A](dep, version, context.cache.fetchText)
          .uninterruptible  // uninterruptile to avoid incomplete-download error messages when resolving is interrupted to prompt for a variant selection (downloading json should be fairly quick anyway)
          .map(Some(_))
          .catchSome(handleMetadataDownloadError(dep.orgName, context))
          .provideSomeLayer(zio.ZLayer.succeed(context.logger))
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
            val repoUris = context.repositories.map(_.baseUri)
            context.logger.log(s"Could not find metadata of ${dep.orgName}. Trying to update channel contents.")
            for {
              repos   <- Sc4pac.initializeRepositories(repoUris, context.cache, Constants.channelContentsTtlShort)  // 60 seconds
                          .provideSomeLayer(zio.ZLayer.succeed(ProfileRoot(context.profileRoot)))
                          .provideSomeLayer(zio.ZLayer.succeed(context.logger))
              result  <- tryAllRepos(repos, context)  // 2nd try
            } yield result
        }
    }
  }

  private[sc4pac] def isSubMap[A, B](small: Map[A, B], large: Map[A, B]): Boolean = small.keysIterator.forall(a => small.get(a) == large.get(a))

  /** Given a module without specific variant, find a variant that matches globalVariant. */
  def matchingVariant(module: BareModule, version: String, globalVariant: Variant): RIO[ResolutionContext, (JD.Package, JD.VariantData)] = {

    def pickVariant(pkgOpt: Option[JD.Package]): Task[(JD.Package, JD.VariantData)] = pkgOpt match {
      case None => ZIO.fail(new Sc4pacVersionNotFound(s"Could not find metadata of ${module.orgName} or suitable variant.",
                                                      "Either the package name is spelled incorrectly or the metadata stored in the corresponding channel is incorrect or incomplete.", module))
      case Some(pkgData) =>
        pkgData.variants.find(vd => isSubMap(vd.variant, globalVariant)) match {
          case Some(vd) => ZIO.succeed((pkgData, vd))
          case None =>
            ZIO.fail(new Sc4pacMissingVariant(pkgData, s"could not find variant for ${module.orgName} matching [${JD.VariantData.variantString(globalVariant)}]"))
        }
    }

    concreteVersion(module, version)
      .flatMap(packageData[JD.Package](module, _))
      .flatMap(pickVariant)
  }

  /** Find all external packages that depend on this module. This searches
    * across all repositories and returns the union of all inter-channel
    * dependencies on this module.
    * Intra-channel dependencies are not part of the result (since they are
    * not listed as external packages, but are part of the regular JD.Package).
    */
  def requiredByExternal(module: BareModule): RIO[ResolutionContext, Iterable[(java.net.URI, Seq[BareModule])]] = {
    for {
      context      <- ZIO.service[ResolutionContext]
      (errs, rels) <- ZIO.partitionPar(context.repositories) { repo =>
                        repo.fetchExternalPackage(module, context.cache.fetchText)
                          .catchSome(handleMetadataDownloadError(module.orgName, context))
                          .map(extPkgOpt => repo.baseUri -> extPkgOpt.toSeq.flatMap(_.requiredBy))
                      }
                      .provideSomeLayer(zio.ZLayer.succeed(context.logger))
      result       <- if (rels.nonEmpty) {  // some channels could be read successfully (so ignore errors for simplicity, even though result may be incomplete)
                        ZIO.succeed(rels.filter(_._2.nonEmpty))
                      } else {
                        ZIO.fail(Sc4pacAssetNotFound(s"Could not find inter-channel reverse dependencies of package ${module.orgName}",
                          s"Most likely you are offline or the channel metadata is not up-to-date: $errs"))
                      }
    } yield result
  }

}
