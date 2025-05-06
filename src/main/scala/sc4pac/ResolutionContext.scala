package io.github.memo33
package sc4pac

class ResolutionContext(
  val repositories: Seq[MetadataRepository],
  val cache: FileCache,
  val logger: Logger,
  val profileRoot: os.Path
) {

  object coursierApi {
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

    private def versionsOfByRepo(dep: BareDep): zio.UIO[Seq[(MetadataRepository, coursier.core.Versions)]] =
      zio.ZIO.collectAllSuccessesPar(  // repositories not containing module can be ignored
        repositories.map { repo =>
          repo.fetchVersions(dep).map((repo, _))
        }
      )

    // gather available versions of module across all repositories (in parallel)
    def versionsOf(dep: BareDep): zio.Task[coursier.core.Versions] = {
      versionsOfByRepo(dep).map { results =>
        mergeVersions(results.map(_._2).toVector)
      }
    }

  }

}
