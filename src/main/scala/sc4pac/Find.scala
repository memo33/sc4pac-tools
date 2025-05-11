package io.github.memo33
package sc4pac

import zio.{ZIO, Task, RIO}
import upickle.default.Reader

import sc4pac.JsonData as JD
import sc4pac.error.{Sc4pacVersionNotFound, Sc4pacAssetNotFound, Artifact2Error}

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
    case e: (Artifact2Error.WrongChecksum | Artifact2Error.ChecksumFormatError | Artifact2Error.ChecksumNotFound) =>
      ZIO.fail(new error.ChecksumError(
        s"Checksum verification failed for $orgName. Usually this should not happen and suggests a problem with the channel data.",
        e.getMessage))
    case e: (Artifact2Error.DownloadError | Artifact2Error.WrongLength | Artifact2Error.NotFound) =>
      ZIO.fail(new error.DownloadFailed("Failed to download some metadata files. Check your internet connection.",
        e.getMessage, url = None))
    case e: Artifact2Error =>
      context.logger.debugPrintStackTrace(e)
      ZIO.fail(new error.DownloadFailed("Unexpected download error.", e.getMessage, url = None))
  }

  /** Find the JD.Package or JD.Asset corresponding to module and version,
    * across all repositories. If not found at all, try a second time with the
    * repository channel contents updated. */
  def packageData[A <: JD.Package | JD.Asset : Reader](dep: BareDep, version: String): RIO[ResolutionContext, Option[(A, java.net.URI)]] = {
    def tryAllRepos(repos: Seq[MetadataRepository], context: ResolutionContext): Task[Option[(A, java.net.URI)]] = ZIO.collectFirst(repos) { repo =>
        repo.fetchModuleJson[Logger, A](dep, version, context.cache.fetchJson)
          .uninterruptible  // uninterruptile to avoid incomplete-download error messages when resolving is interrupted to prompt for a variant selection (downloading json should be fairly quick anyway)
          .map(Some(_))
          .catchSome(handleMetadataDownloadError(dep.orgName, context))
          .provideSomeLayer(zio.ZLayer.succeed(context.logger))
    }

    ZIO.service[ResolutionContext].flatMap { context =>
      tryAllRepos(context.repositories, context)
        .flatMap {
          case result: Some[(A, java.net.URI)] => ZIO.succeed(result)
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

  /** Given a module without specific variant, find a variant that matches variantSelection. */
  def matchingVariant(module: BareModule, version: String, variantSelection: VariantSelection): RIO[ResolutionContext, (JD.Package, JD.VariantData, java.net.URI)] = {
    for {
      (pkg, url)   <- concreteVersion(module, version)
                        .flatMap(packageData[JD.Package](module, _))
                        .someOrFail(new Sc4pacVersionNotFound(
                          s"Could not find metadata of ${module.orgName} or suitable variant.",
                          "Either the package name is spelled incorrectly or the metadata stored in the corresponding channel is incorrect or incomplete.",
                          module,
                        ))
      variantData  <- variantSelection.pickVariant(pkg)
    } yield (pkg, variantData, url)
  }

  /** Find all external packages that depend on or conflict with this module. This searches
    * across all repositories and returns the union of all inter-channel
    * dependencies/conflicts on this module.
    * Intra-channel dependencies are not part of the result (since they are
    * not listed as external packages, but are part of the regular JD.Package).
    */
  def requiredByExternal(module: BareModule): RIO[ResolutionContext, Iterable[(java.net.URI, (Seq[BareModule], Seq[BareModule]))]] = {
    for {
      context      <- ZIO.service[ResolutionContext]
      (errs, rels) <- ZIO.partitionPar(context.repositories) { repo =>
                        repo.fetchExternalPackage(module, context.cache.fetchJson)
                          .catchSome(handleMetadataDownloadError(module.orgName, context))
                          .map(extPkgOpt => repo.baseUri -> (extPkgOpt.toSeq.flatMap(_.requiredBy), extPkgOpt.toSeq.flatMap(_.reverseConflictingPackages)))
                      }
                      .provideSomeLayer(zio.ZLayer.succeed(context.logger))
      result       <- if (rels.nonEmpty) {  // some channels could be read successfully (so ignore errors for simplicity, even though result may be incomplete)
                        ZIO.succeed(rels.filter(rel => rel._2._1.nonEmpty || rel._2._2.nonEmpty))
                      } else {
                        ZIO.fail(Sc4pacAssetNotFound(s"Could not find inter-channel reverse dependencies of package ${module.orgName}",
                          s"Most likely you are offline or the channel metadata is not up-to-date: $errs"))
                      }
    } yield result
  }

}
