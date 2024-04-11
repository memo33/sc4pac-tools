package io.github.memo33
package sc4pac

import coursier.core.{Configuration, Organization, Type, Module}
import java.util.regex.Pattern
import scala.concurrent.duration.DurationInt

object Constants {
  val compile = Configuration.compile  // includes only metadata as dependencies
  val link = new Configuration("link")  // extends `compile` with actual assets (TODO rename to `install`?)
  export JsonRepoUtil.sc4pacAssetOrg  // val sc4pacAssetOrg = Organization("sc4pacAsset")
  val sc4pacAssetType = Type("sc4pac-resource")  // TODO
  val defaultInclude = """."""  // includes everything
  val defaultExclude = """(?<!\.dat|\.sc4model|\.sc4lot|\.sc4desc|\.sc4|\.dll)$"""  // excludes files with other file types
  val sc4fileTypePattern = Pattern.compile("""\.dat|\.sc4model|\.sc4lot|\.sc4desc|\.sc4|\.dll$""", Pattern.CASE_INSENSITIVE)
  val versionLatestRelease = "latest.release"
  val defaultChannelUrls = Seq(MetadataRepository.parseChannelUrl("https://memo33.github.io/sc4pac/channel/").toOption.get)

  // Channels are built with the maximum version.
  // When incrementing the maximum, older sc4pac clients become incompatible
  // with newer channels, forcing users to upgrade their clients.
  //
  // When incrementing the minimum, older channels become incompatible with
  // newer sc4pac clients, forcing channel maintainers to rebuild the channels.
  // The minimum must be incremented when introducing backward-incompatible changes.
  //
  // Scheme version history:
  // 1: initial version
  // 2: DLL support and single-file assets
  // 3: rar support
  val channelSchemeVersions: Range = 1 to 3  // supported versions

  val bufferSizeExtract = 64 * 1024  // 64 kiB, bounded by disk speed
  val bufferSizeDownload = 1024 * 1024  // 1 MiB, bounded by download speed
  val bufferSizeDownloadOverlap = 4 * 1024  // for file validity check when resuming partial download
  val downloadProgressQuantization = 512 * 1024 // 0.5 MiB, controls average frequency of progress messages in API
  val maxRedirectionsOpt = Some(20)
  val sslRetryCount = 3  // Coursier legacy
  val resumeIncompleteDownloadAttemps = 4
  val fuzzySearchThreshold = 50  // 0..100
  val cacheTtl = 12.hours
  val channelContentsTtl = 30.minutes
  val channelContentsTtlShort = 60.seconds
  val interactivePromptTimeout = java.time.Duration.ofSeconds(240)
  val urlConnectTimeout = java.time.Duration.ofSeconds(60)
  val urlReadTimeout = java.time.Duration.ofSeconds(60)  // timeout in case of internet outage while downloading a file
  val defaultPort = 51515

  lazy val userAgent = {
    val majMinVersion = cli.BuildInfo.version.split("\\.", 3).take(2).mkString(".")
    s"${cli.BuildInfo.name}/$majMinVersion"
  }

  lazy val debugMode: Boolean = System.getenv("SC4PAC_DEBUG") match { case null | "" => false; case _ => true }

  lazy val noColor: Boolean = (System.getenv("NO_COLOR") match { case null | "" => false; case _ => true }) ||
                              (System.getenv("SC4PAC_NO_COLOR") match { case null | "" => false; case _ => true })

  /** Basic support for authentication to Simtropolis. To use this:
    *
    * - Use your web browser to sign in to Simtropolis.
    * - Inspect the cookies by opening the browser Dev Tools:
    *   - in Firefox: Storage > Cookies
    *   - in Chrome: Application > Storage > Cookies
    * - Set the environment variable `SC4PAC_SIMTROPOLIS_COOKIE` to the value of
    *   the session cookie during downloads, e.g.:
    *
    *     SC4PAC_SIMTROPOLIS_COOKIE="ips4_IPSSessionFront=<value>" ./sc4pac update
    *
    * - The above works for the duration of the browser session. For longer-lived
    *   access, sign in to Simtropolis with the "remember me" option and use the
    *   following cookies (taking note of their expiration dates):
    *
    *     SC4PAC_SIMTROPOLIS_COOKIE="ips4_device_key=<value>; ips4_member_id=<value>; ips4_login_key=<value>" ./sc4pac update
    *
    */
  lazy val simtropolisCookie: Option[String] = Option(System.getenv("SC4PAC_SIMTROPOLIS_COOKIE")).filter(_.nonEmpty)

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

  def isDll(path: os.Path): Boolean = path.last.toLowerCase.endsWith(".dll")
}
