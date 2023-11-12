package io.github.memo33
package sc4pac

import sc4pac.CoursierZio.*  // implicit coursier-zio interop

class ResolutionContext(
  val repositories: Seq[MetadataRepository],
  val cache: FileCache,
  val logger: Logger,
  val scopeRoot: os.Path
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

    val versions = coursier.Versions(cache).withRepositories(repositories)
      // .withModule(coursier.parse.ModuleParser.module("memo:package-d", "").getOrElse(???))
      // .run()
  }

}
