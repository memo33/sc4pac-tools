package io.github.memo33
package sc4pac

import zio.{ZIO, Task, RIO}
import sc4pac.Resolution.DepModule
import sc4pac.JsonData as JD
import org.fusesource.jansi.Ansi


trait Logger {
  /** For generic messages to the console, not user-facing. */
  def log(msg: String): Unit
  /** For generic messages to the console, not user-facing. */
  def warn(msg: String): Unit
  /** For generic messages to the console, not user-facing. */
  def debug(msg: => String): Unit

  def checkingArtifact(url: String, artifact: Artifact): Unit = {}
  def downloadingArtifact(url: String, artifact: Artifact): Unit = {}
  def downloadedArtifact(url: String, success: Boolean): Unit = {}
  def downloadLength(url: String, len: Long, currentLen: Long, watching: Boolean): Unit = {}
  def downloadProgress(url: String, downloaded: Long): Unit = {}
  def gettingLength(url: String): Unit = {}
  def gettingLengthResult(url: String, length: Option[Long]): Unit = {}

  def concurrentCacheAccess(url: java.net.URI): Unit = debug(s"concurrentCacheAccess $url")

  def extractingArchiveEntry(entry: os.SubPath, include: Boolean): Unit

  def extractingPackage[A](dependency: DepModule, progress: Sc4pac.Progress)(extraction: Task[A]): Task[A]

  def publishing[A](removalOnly: Boolean)(publishing: Task[A]): Task[A]

  def fetchingAssets[R, A](fetching: RIO[R, A]): RIO[R, A]

  def debugPrintStackTrace(exception: Throwable): Unit = debug({
    val sw = java.io.StringWriter()
    exception.printStackTrace(new java.io.PrintWriter(sw))
    sw.toString()
  })
}

/** A plain Coursier logger, since Coursier's RefreshLogger results in dropped
  * or invisible messages, hiding the downloading activity.
  */
class CliLogger private (out: java.io.PrintStream, useColor: Boolean, isInteractive: Boolean) extends Logger {

  private def green(msg: String): String = if (useColor) Console.GREEN + msg + Console.RESET else msg
  private def cyan(msg: String): String = if (useColor) Console.CYAN + msg + Console.RESET else msg
  def cyanBold(msg: String): String = if (useColor) Console.CYAN + Console.BOLD + msg + Console.RESET else msg
  private def yellowBold(msg: String): String = if (useColor) Console.YELLOW + Console.BOLD + msg + Console.RESET else msg
  def bold(msg: String): String = if (useColor) Console.BOLD + msg + Console.RESET else msg
  def gray(msg: String): String = if (useColor) grayEscape + msg + Console.RESET else msg  // aka bright black
  private val grayEscape = s"${27.toChar}[90m"

  /** Currenty this does not apply full markdown formatting, but just `pkg=…`
    * highlighting.
    */
  def applyMarkdown(text: String): String = {
    BareModule.pkgMarkdownRegex.replaceAllIn(text, matcher =>
      BareModule(Organization(matcher.group(1)), ModuleName(matcher.group(2))).formattedDisplayString(gray, bold)
    )
  }

  override def downloadingArtifact(url: String, artifact: Artifact) =
    out.println("  " + cyan(s"> Downloading $url"))
  override def downloadedArtifact(url: String, success: Boolean) =
    if (!success)
      out.println("  " + cyan(s"  Download of $url unsuccessful"))
    else if (Constants.debugMode)
      out.println("  " + gray(s"  Downloaded $url"))
  override def downloadLength(url: String, len: Long, currentLen: Long, watching: Boolean): Unit =
    debug(s"downloadLength=$currentLen/$len: $url")
  override def gettingLength(url: String): Unit =
    debug(s"gettingLength $url")
  override def gettingLengthResult(url: String, length: Option[Long]): Unit =
    debug(s"gettingLengthResult=$length: $url")
  def extractingArchiveEntry(entry: os.SubPath, include: Boolean): Unit =
    debug(s"[${if (include) Console.GREEN + "include" + grayEscape else "exclude"}] $entry")

  def log(msg: String): Unit = out.println(msg)
  def warn(msg: String): Unit = out.println(yellowBold("Warning:") + " " + msg)
  def debug(msg: => String): Unit = if (Constants.debugMode) out.println(gray(s"--> $msg"))

  def logSearchResult(idx: Int, module: BareModule, description: Option[String], installed: Boolean): Unit = {
    val mod = module.formattedDisplayString(gray, bold) + (if (installed) " " + cyanBold("[installed]") else "")
    log((Array(s"(${idx+1}) $mod") ++ description).mkString(f"%n" + " "*8))
  }

  def logInfoResult(infoResult: Seq[(String, String)]): Unit = {
    val columnWidth = infoResult.map(_._1.length).maxOption.getOrElse(0)
    for ((label, description) <- infoResult) {
      log(bold(label + " " * (columnWidth - label.length) + " :") + s" $description")
    }
  }

  def logInstalled(module: DepModule, explicit: Boolean): Unit = {
    log(module.formattedDisplayString(gray) + (if (explicit) " " + cyanBold("[explicit]") else ""))
  }

  def logDllsInstalled(dllsInstalled: Seq[Sc4pac.StageResult.DllInstalled]): Unit = {
    for ((dll, idx) <- dllsInstalled.zipWithIndex) {
      val bullet = s"(${idx+1})"
      val indent = " " * bullet.length
      if (idx != 0) log("")
      log(s"$bullet DLL file: ${bold(dll.dll.toString)}")
      log(s"$indent SHA-256 is valid: ${green(JD.Checksum.bytesToString(dll.validatedSha256))}")
      log(s"$indent Downloaded from: ${cyan(dll.asset.url.toString)}")
      log(s"$indent Installed by: ${dll.module.formattedDisplayString(gray)}")
      log(s"$indent DLL metadata from: ${gray(dll.assetMetadataUrl.toString)}")
      log(s"$indent Package metadata from: ${gray(dll.pkgMetadataUrl.toString)}")
    }
  }

  // private val spinnerSymbols = collection.immutable.ArraySeq("⡿", "⣟", "⣯", "⣷", "⣾", "⣽", "⣻", "⢿").reverse
  private val spinnerSymbols = {
    val n = 6
    val xs = collection.immutable.ArraySeq.tabulate(n+1) { i =>
      // "▪"*i + "▫"*(n-i)  // supported by Windows default font Consolas, but not Windows default code page
      "#"*i + "."*(n-i)
    }
    xs.dropRight(1) ++ xs.drop(1).map(_.reverse).reverse
  }

  /** Print a message, followed by a spinning animation, while running a task.
    * The task should not print anything, unless sameLine is true.
    */
  def withSpinner[R, A](msg: Option[String], sameLine: Boolean, cyan: Boolean = false, duration: java.time.Duration = java.time.Duration.ofMillis(100))(task: RIO[R, A]): RIO[R, A] = {
    if (msg.nonEmpty) {
      out.println(msg.get)
    }
    if (!useColor || !isInteractive) {
      task
    } else {
      val coloredSymbols = if (!cyan) spinnerSymbols else spinnerSymbols.map(this.cyan)
      val spin: String => Unit = if (!sameLine) {
        val col = msg.map(_.length + 2).getOrElse(1)
        // There are two different cursor position standards, see https://github.com/fusesource/jansi/issues/226
        // TODO For macOS Term.app, we may need to use DEC instead of SCO.
        (symbol) => out.print(Ansi.ansi().saveCursorPositionSCO().cursorUpLine().cursorToColumn(col).a(symbol).restoreCursorPositionSCO())
      } else {
        (symbol) => out.print(Ansi.ansi().saveCursorPositionSCO().cursorRight(2).a(symbol).restoreCursorPositionSCO())
      }
      val spinner = ZIO.iterate(0)(_ => true) { i =>
        for (_ <- ZIO.sleep(duration)) yield {  // TODO use zio.Schedule instead?
          spin(coloredSymbols(i))
          (i+1) % coloredSymbols.length
        }
      }
      // run task and spinner in parallel and interrupt spinner once task completes or fails
      for (result <- task.map(Right(_)).raceFirst(spinner.map(Left(_)))) yield {
        spin(" " * spinnerSymbols.head.length)  // clear animation
        result.toOption.get  // spinner/Left will never complete, so we get A from Right
      }
    }
  }

  def extractingPackage[A](dependency: DepModule, progress: Sc4pac.Progress)(extraction: Task[A]): Task[A] = {
    val msg = s"$progress Extracting ${dependency.orgName} ${dependency.version}"
    withSpinner(Some(msg), sameLine = Constants.debugMode)(extraction)  // sameLine due to debug output
  }

  def publishing[A](removalOnly: Boolean)(publishing: Task[A]): Task[A] = {
    val msg = if (!removalOnly) "Moving extracted files to plugins folder." else "Removing files from plugins folder."
    withSpinner(Some(msg), sameLine = false)(publishing)
  }

  def fetchingAssets[R, A](fetching: RIO[R, A]): RIO[R, A] = {
    withSpinner(None, sameLine = true, cyan = true, duration = java.time.Duration.ofMillis(160))(fetching)
  }
}

object CliLogger {
  def apply(): CliLogger = {
    val isInteractive = Constants.isInteractive
    try {
      val useColor = org.fusesource.jansi.AnsiConsole.out().getMode() != org.fusesource.jansi.AnsiMode.Strip
      // the streams have been installed in `main` (installation is required since jansi 2.1.0)
      new CliLogger(org.fusesource.jansi.AnsiConsole.out(), useColor, isInteractive)  // this PrintStream uses color only if it is supported (so not on uncolored terminals and not when outputting to a file)
    } catch {
      case e: java.lang.UnsatisfiedLinkError =>  // in case something goes really wrong and no suitable jansi native library is included
        System.err.println(s"Using colorless output as fallback due to $e")
      new CliLogger(System.out, useColor = false, isInteractive)
    }
  }
}

