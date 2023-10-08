package io.github.memo33
package sc4pac

import coursier.core.{Configuration, Organization, Type, Module}

object Constants {
  val compile = Configuration.compile  // includes only metadata as dependencies
  val link = new Configuration("link")  // extends `compile` with actual assets (TODO rename to `install`?)
  export JsonRepoUtil.sc4pacAssetOrg  // val sc4pacAssetOrg = Organization("sc4pacAsset")
  val sc4pacAssetType = Type("sc4pac-resource")  // TODO
  val defaultInclude = """."""  // includes everything
  val defaultExclude = """(?<!\.dat|\.sc4model|\.sc4lot|\.sc4desc|\.sc4)$"""  // excludes files with other file types
  val versionLatestRelease = "latest.release"
  val defaultChannelUrls = Seq(MetadataRepository.parseChannelUrl("https://memo33.github.io/sc4pac/channel/").toOption.get)
  val bufferSizeExtract = 64 * 1024  // 64 kiB, bounded by disk speed
  val bufferSizeDownload = 1024 * 1024  // 1 MiB, bounded by download speed
  val bufferSizeDownloadOverlap = 4 * 1024  // for file validity check when resuming partial download
  val maxRedirectionsOpt = Some(20)
  val sslRetryCount = 3  // Coursier legacy
  val resumeIncompleteDownloadAttemps = 4
  val fuzzySearchThreshold = 50  // 0..100
  val interactivePromptTimeout = java.time.Duration.ofSeconds(240)
  val urlConnectTimeout = java.time.Duration.ofSeconds(60)
  val urlReadTimeout = java.time.Duration.ofSeconds(60)  // timeout in case of internet outage while downloading a file

  lazy val userAgent = {
    val majMinVersion = cli.BuildInfo.version.split("\\.", 3).take(2).mkString(".")
    s"${cli.BuildInfo.name}/$majMinVersion"
  }

  lazy val debugMode: Boolean = System.getenv("SC4PAC_DEBUG") match { case null | "" => false; case _ => true }

  lazy val noColor: Boolean = (System.getenv("NO_COLOR") match { case null | "" => false; case _ => true }) ||
                              (System.getenv("SC4PAC_NO_COLOR") match { case null | "" => false; case _ => true })

  def isSc4pacAsset(module: Module): Boolean = module.organization == Constants.sc4pacAssetOrg

  lazy val isInteractive: Boolean = try {
    import org.fusesource.jansi.{AnsiConsole, AnsiType}
    val ttype = AnsiConsole.out().getType
    ttype != AnsiType.Redirected && ttype != AnsiType.Unsupported
  } catch {
    case e: java.lang.UnsatisfiedLinkError =>  // in case something goes really wrong and no suitable jansi native library is included
    System.err.println("Falling back to interactive mode.")  // TODO use --no-prompt to force non-interactive mode (once implemented)
    true
  }
}
