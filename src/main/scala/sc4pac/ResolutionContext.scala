package io.github.memo33
package sc4pac

import coursier.core as C

class ResolutionContext(
  val repositories: Seq[MetadataRepository],
  val cache: FileCache,
  val logger: Logger,
  val profileRoot: os.Path
) {

  object coursierApi {
    // val resolve = coursier.Resolve(cache)
    //   .withRepositories(repositories)
    //   // .mapResolutionParams(_.addProperties(properties: _*))

    // val fetch = Fetch(cache)
    //   .withArtifactTypes(Sc4pac.assetTypes)
    //   // .withArtifactTypes(Set(Type.all))
    //   .withResolve(resolve)
    //   .transformResolution(_.flatMap(resolution => deleteStaleCachedFiles(resolution).map(_ => resolution)))
    //   // .mapResolutionParams(_.withDefaultConfiguration(Constants.link))

    // // To go from an existing resolution (i.e. without resolve) to fetching artifacts,
    // // one can use (but don't forget to transform the resolution!):
    // val artifacts = coursier.Artifacts(cache)
    //   .withArtifactTypes(Sc4pac.assetTypes)
    //   // .withResolution(resolution)
    //   // .run() or .runResult()

    // val versions = coursier.Versions(cache).withRepositories(repositories)
    //   // .withModule(coursier.parse.ModuleParser.module("memo:package-d", "").getOrElse(???))
    //   // .run()

    // merges available versions from different repositories
    private def mergeVersions(versions: Vector[coursier.core.Versions]): coursier.core.Versions = {
      if (versions.isEmpty)
        coursier.core.Versions("", "", Nil, None)
      else if (versions.lengthCompare(1) == 0)
        versions.head
      else {
        val latest  = versions.map(v => coursier.core.Version(v.latest)).max.repr
        val release = versions.map(v => coursier.core.Version(v.release)).max.repr

        val available = versions
          .flatMap(_.available)
          .distinct
          .map(coursier.core.Version(_))
          .sorted
          .map(_.repr)
          .toList

        val lastUpdated = versions
          .flatMap(_.lastUpdated.toSeq)
          .sorted
          .lastOption

        coursier.core.Versions(latest, release, available, lastUpdated)  // TODO we only need `latest`
      }
    }

    // gather available versions of module across all repositories (in parallel)
    def versionsOf(module: C.Module): zio.Task[coursier.core.Versions] = {

      val t: zio.UIO[Seq[(MetadataRepository, Either[ErrStr, coursier.core.Versions])]] =
        zio.ZIO.foreachPar(repositories) { repo =>
          (repo.fetchVersions(module)).either.map(repo -> _.map(_._1))
        }

      val t0 = cache.logger.using(t: zio.Task[Seq[(MetadataRepository, Either[ErrStr, coursier.core.Versions])]])

      t0.map { results =>
        mergeVersions(results.flatMap(_._2.toSeq).toVector)  // repositories not containing module can be ignored
      }
    }

  }

}
