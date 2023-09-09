package io.github.memo33
package sc4pac

import coursier.core.{Configuration, Organization, Type, Module}

object Constants {
  val compile = Configuration.compile  // includes only metadata as dependencies
  val link = new Configuration("link")  // extends `compile` with actual assets (TODO rename to `install`?)
  val sc4pacAssetOrg = Organization("sc4pacAsset")
  val sc4pacAssetType = Type("sc4pac-resource")  // TODO
  val urlKey = "url"
  val lastModifiedKey = "lastModified"
  val variantPrefix = "variant."
  val defaultInclude = """."""  // includes everything
  val defaultExclude = """(?<!\.dat|\.sc4model|\.sc4lot|\.sc4desc|\.sc4)$"""  // excludes files with other file types
  val versionLatestRelease = "latest.release"
  // val defaultChannelUrls = Seq("http://localhost:8090")  // for testing
  val defaultChannelUrls = Seq(MetadataRepository.parseChannelUrl("https://raw.githubusercontent.com/memo33/sc4pac-tools/main/channel/json"))  // temporary
  val bufferSize = 64 * 1024  // 64 kiB
  val fuzzySearchThreshold = 50  // 0..100

  def isSc4pacAsset(module: Module): Boolean = module.organization == Constants.sc4pacAssetOrg
}
