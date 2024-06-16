package io.github.memo33
package sc4pac

import java.io.IOException
import zio.ZIO
import sc4pac.error.Sc4pacTimeout

object Prompt {

  sealed trait Interactive
  private object InteractiveLive extends Interactive

  def ifInteractive[R, E, A](onTrue: ZIO[Interactive & R, E, A], onFalse: ZIO[R, E, A]): ZIO[R, E, A] = {
    if (Constants.isInteractive) {
      onTrue.provideSomeLayer(zio.ZLayer.succeed(InteractiveLive))
    } else {
      onFalse
    }
  }

  /** Calls readLine with a timeout. In case of a timeout, System.in seems to
    * remain blocked or out-of-sync, so the program should terminate.
    */
  private val readLineTimeout: ZIO[Interactive, IOException, String] = {  // See also https://github.com/zio/zio/pull/6205#issue-1088016373
    // Since Console.readLine is not reliably interruptible on every OS
    // (well-known Java issue), we race two fibers and interrupt the slower one.
    val sleep = ZIO.sleep(Constants.interactivePromptTimeout)
    val readLine =
      ZIO.attemptBlockingIO {  // first clears old input (non-blocking), then blocks for new input
        val count = System.in.available()
        if (count > 0) { System.in.read(new Array[Byte](count)); () }  // discard (up to) `count` bytes
      }.zipRight(zio.Console.readLine)
    sleep.raceWith(readLine)(
      leftDone = (result, fiberRight) =>  // The forking (interruptFork) is important in order not to wait indefinitely for the blocking non-interruptible readLine.
        fiberRight.interruptFork.zipRight(ZIO.fail(new Sc4pacTimeout("Timeout at prompt."))),  // exit the program
      rightDone = (result, fiberLeft) =>
        fiberLeft.interrupt.zipRight(result)
    )
  }

  /** Prompts for input until a valid option is chosen.
    */
  def choice(question: String, options: Seq[String], default: Option[String], optionsShort: Option[String] = None): ZIO[Interactive, IOException, String] = {
    require(default.forall(options.contains), s"default option $default must be contained in options $options")

    val readOption: ZIO[Interactive, IOException, Option[String]] = for {
      _     <- zio.Console.print(s"$question [${optionsShort.getOrElse(options.mkString("/"))}]: ")
      input <- readLineTimeout.map(_.trim)
      // _     <- zio.Console.printLine("")
    } yield {
      if (input.isEmpty) default
      else {
        val matches: Seq[String] = options.filter(_.toLowerCase().startsWith(input.toLowerCase()))
        matches match {
          case Seq(unique) => Some(unique)
          case _ => None
        }
      }
    }

    ZIO.iterate(None: Option[String])(_.isEmpty)(_ => readOption)  // repeatedly read input while empty
      .map(_.get)
  }

  def yesNo(question: String): ZIO[Interactive, IOException, Boolean] = Prompt.choice(question, Seq("Yes", "no"), default = Some("Yes")).map(_ == "Yes")

  /** Parse space-separated numeric ranges like "1 3 5-7".
    * Only meant for reasonably small ranges.
    */
  private def parseRanges(text: String): Option[Set[Int]] = {
    val regexInt = raw"(\d+)".r
    val regexRange = raw"(\d+)-(\d+)".r
    val tokens = text.split(raw"\s+")
    val ranges: Array[Option[Seq[Int]]] = tokens.map(_ match {
      case "" => Some(Seq.empty)
      case regexInt(num) => Some(Seq(num.toInt))
      case regexRange(start, end) => Some(start.toInt to end.toInt)
      case _ => None
    })
    if (ranges.exists(_.isEmpty)) None  // syntax error
    else Some(ranges.flatMap(_.get).toSet)
  }

  /** Select a single option by number. */
  def numbered[A](pretext: String, options: Seq[A], render: A => String = (_: A).toString): ZIO[Interactive, IOException, A] = {
    val indexes = (1 to options.length).map(_.toString)
    val default = indexes match { case Seq(one) => Some(one); case _ => None }
    for {
      _      <- zio.Console.printLine(f"$pretext%n%n" + indexes.zip(options).map((i, o) => s"  ($i) ${render(o)}").mkString(f"%n") + f"%n")
      abbrev =  if (indexes.length <= 2) None else Some(s"${indexes.head}-${indexes.last}")
      num    <- Prompt.choice("Enter a number", indexes, default, optionsShort = abbrev)
    } yield options(num.toInt - 1)
  }

  /** Select multiple options by number. */
  def numberedMultiSelect[A](pretext: String, options: Seq[A], render: A => String = (_: A).toString): ZIO[Interactive, IOException, Seq[A]] = {
    val indexed = (1 to options.length).zip(options)
    val promptRanges: ZIO[Interactive, IOException, Option[Set[Int]]] = for {
      _ <- zio.Console.print("""Enter numbers [e.g. "1 2 3", "1-3"]: """)
      s <- readLineTimeout.map(_.trim)
    } yield parseRanges(s)
    for {
      _        <- zio.Console.printLine(f"$pretext%n%n" + indexed.map((i, o) => s"  ($i) ${render(o)}").mkString(f"%n") + f"%n")
      selected <- ZIO.iterate(None: Option[Set[Int]])(_.isEmpty)(_ => promptRanges).map(_.get)
    } yield indexed.collect { case (i, o) if selected(i) => o }
  }

  /** Prompts until user inputs a valid path (relative or absolute). */
  def pathInput(pretext: String): ZIO[Interactive, IOException, os.Path] = {
    val readOption: ZIO[Interactive, IOException, Option[os.Path]] = for {
      _ <- zio.Console.print(pretext)
      s <- readLineTimeout.map(_.trim)
    } yield if (s.isEmpty) None else try {
      Some(os.Path(java.nio.file.Paths.get(s), os.pwd))
    } catch { case _: java.nio.file.InvalidPathException => None }
    ZIO.iterate(None: Option[os.Path])(_.isEmpty)(_ => readOption).map(_.get)
  }

  /** Choose a path, with an option to specify a custom location, creating it if necessary. */
  def paths(pretext: String, options: Seq[os.Path]): ZIO[Interactive, IOException, os.Path] = {
    case object OtherLocation { override def toString = "Other location..." }

    val readPath: ZIO[Interactive, IOException, os.Path] = numbered(pretext, options :+ OtherLocation).flatMap {
      case OtherLocation => pathInput("Enter a path: ")
      case p: os.Path => ZIO.succeed(p)
    }

    def createMaybe(path: os.Path): ZIO[Interactive, IOException, Option[os.Path]] = {
      ZIO.ifZIO(ZIO.attemptBlockingIO(os.exists(path)))(
        onTrue = ZIO.succeed(Some(path)),
        onFalse = for {
          create <- yesNo(f"""The directory "$path" does not exist.%nShould it be created?""")
          result <- if (!create) ZIO.succeed(None)
                    else ZIO.attemptBlockingIO(os.makeDir.all(path)).map(_ => Option(path))
        } yield result
      )
    }

    ZIO.iterate(None: Option[os.Path])(_.isEmpty)(_ => readPath.flatMap(createMaybe)).map(_.get)
  }
}
