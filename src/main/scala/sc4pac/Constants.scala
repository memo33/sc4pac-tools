package io.github.memo33
package sc4pac

import java.util.regex.Pattern
import scala.concurrent.duration.DurationInt

// Some constants have been moved to service.FileSystem
object Constants {
  export JsonRepoUtil.sc4pacAssetOrg  // val sc4pacAssetOrg = Organization("sc4pacAsset")
  val defaultIncludePattern = Pattern.compile("""(?<=\.dat|\.sc4model|\.sc4lot|\.sc4desc|\.sc4)$""", Pattern.CASE_INSENSITIVE)  // includes only plugin files (without dll)
  val defaultExcludePattern = Pattern.compile("""(?<!\.dat|\.sc4model|\.sc4lot|\.sc4desc|\.sc4)$""", Pattern.CASE_INSENSITIVE)  // excludes files with other file types
  val sc4fileTypePattern = Pattern.compile("""\.dat|\.sc4model|\.sc4lot|\.sc4desc|\.sc4|\.dll$""", Pattern.CASE_INSENSITIVE)
  val versionLatestRelease = "latest.release"
  val defaultChannelUrls = Seq(
    MetadataRepository.parseChannelUrl("https://memo33.github.io/sc4pac/channel/").toOption.get,
    MetadataRepository.parseChannelUrl("https://sc4pac.simtropolis.com/").toOption.get,
    MetadataRepository.parseChannelUrl("https://sc4evermore.github.io/sc4pac-channel/channel/").toOption.get,
  )

  /** Channels are built with the maximum version.
    * When incrementing the maximum, older sc4pac clients become incompatible
    * with newer channels, forcing users to upgrade their clients.
    *
    * When incrementing the minimum, older channels become incompatible with
    * newer sc4pac clients, forcing channel maintainers to rebuild the channels.
    * The minimum must be incremented when introducing backward-incompatible changes.
    *
    * Scheme version history:
    * - 1: initial version
    * - 2: DLL support and single-file assets
    * - 3: rar support
    * - 4: Clickteam installer support
    * - 5: channel stats, external packages, replacement of `contents`
    * - 6: variantInfo
    * - 7: conditional variants
    */
  val channelSchemeVersions: Range = 1 to 7  // supported versions

  val pluginsLockScheme = 2
  val bufferSizeExtract = 64 * 1024  // 64 kiB, bounded by disk speed
  val bufferSizeDownload = 1024 * 1024  // 1 MiB, bounded by download speed
  val bufferSizeDownloadOverlap = 4 * 1024  // for file validity check when resuming partial download
  val downloadProgressQuantization = 512 * 1024 // 0.5 MiB, controls average frequency of progress messages in API
  val largeArchiveSizeInterruptible = 50L * 1024 * 1024  // 50 MiB, extraction of larger files can be interrupted
  val maxRedirectionsOpt = Some(20)
  val sslRetryCount = 3  // Coursier legacy
  val resumeIncompleteDownloadAttemps = 4
  val fuzzySearchThreshold = 80  // 0..100
  val cacheTtl = 12.hours
  val channelContentsTtl = 30.minutes
  val channelContentsTtlRefresh = 0.minutes
  val channelContentsTtlShort = 60.seconds
  val interactivePromptTimeout = java.time.Duration.ofSeconds(240)
  val urlConnectTimeout = java.time.Duration.ofSeconds(60)
  val urlReadTimeout = java.time.Duration.ofSeconds(60)  // timeout in case of internet outage while downloading a file
  val serverShutdownDelay = java.time.Duration.ofSeconds(2)  // defer shutdown to accept new connection in case of page refresh
  val defaultPort = 51515
  val sc4pacGuiClientId = "sc4pacGUIxDI5NjY4MzE2MDQ5OTQ0"  // public info
  val accessTokenCookieName = "__Host-access_token"  // see https://datatracker.ietf.org/doc/html/draft-ietf-oauth-browser-based-apps#section-6.1.3.2
  val cookieExpirationTime = 5.days

  lazy val userAgent = {
    val majMinVersion = cli.BuildInfo.version.split("\\.", 3).take(2).mkString(".")
    s"${cli.BuildInfo.name}/$majMinVersion"
  }

  lazy val debugMode: Boolean = System.getenv("SC4PAC_DEBUG") match { case null | "" => false; case _ => true }

  lazy val noColor: Boolean = (System.getenv("NO_COLOR") match { case null | "" => false; case _ => true }) ||
                              (System.getenv("SC4PAC_NO_COLOR") match { case null | "" => false; case _ => true })

  /* On non-Windows platforms, cicdec is invoked by `mono cicdec [args]`, but on Windows Mono is not required: `cicdec [args]`.
   * We choose reasonable defaults, but allow customizing these two commands via environment variables (which are set in the launch scripts).
   * Note that these commands must consist of a single command (not multiple space-separated commands).
   * If `cicdec` is in your path, these environment variables are not needed.
   */
  lazy val monoCommand: Option[String] = Option(System.getenv("SC4PAC_MONO_CMD")).filter(_.nonEmpty)
  lazy val cicdecCommand: Seq[String] =
    monoCommand.toSeq ++ Seq(Option(System.getenv("SC4PAC_CICDEC_CMD")).filter(_.nonEmpty).getOrElse("cicdec"))

  lazy val isInteractive: Boolean = try {
    import org.fusesource.jansi.{AnsiConsole, AnsiType}
    val ttype = AnsiConsole.out().getType
    ttype != AnsiType.Redirected && ttype != AnsiType.Unsupported
  } catch {
    case e: java.lang.UnsatisfiedLinkError =>  // in case something goes really wrong and no suitable jansi native library is included
    System.err.println("Falling back to interactive mode.")  // TODO use --no-prompt to force non-interactive mode (once implemented)
    true
  }

  def isDll(path: os.Path): Boolean = path.last.toLowerCase(java.util.Locale.ENGLISH).endsWith(".dll")

  lazy val isPosix = java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
}
