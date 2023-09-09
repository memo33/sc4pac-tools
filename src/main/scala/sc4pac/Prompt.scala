package io.github.memo33
package sc4pac

import zio.{ZIO, IO}

object Prompt {

  /** Prompts for input until a valid option is chosen.
    */
  def apply(question: String, options: Seq[String], default: Option[String], optionsShort: Option[String] = None): IO[java.io.IOException, String] = {
    require(default.forall(options.contains), s"default option $default must be contained in options $options")

    val readOption: IO[java.io.IOException, Option[String]] = for {
      _     <- zio.Console.print(s"$question [${optionsShort.getOrElse(options.mkString("/"))}]: ")
      input <- zio.Console.readLine.map(_.trim)
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

  def yesNo(question: String): IO[java.io.IOException, Boolean] = Prompt(question, Seq("Yes", "no"), default = Some("Yes")).map(_ == "Yes")

  def numbered[A](pretext: String, options: Seq[A], render: A => String = (_: A).toString): IO[java.io.IOException, A] = {
    val indexes = (1 to options.length).map(_.toString)
    val default = indexes match { case Seq(one) => Some(one); case _ => None }
    for {
      _      <- zio.Console.printLine(f"$pretext%n%n" + indexes.zip(options).map((i, o) => s"  ($i) ${render(o)}").mkString(f"%n") + f"%n")
      abbrev =  if (indexes.length <= 2) None else Some(s"${indexes.head}-${indexes.last}")
      num    <- Prompt("Enter a number", indexes, default, optionsShort = abbrev)
    } yield options(num.toInt - 1)
  }

  /** Prompts until user inputs a valid path (relative or absolute). */
  def pathInput(pretext: String): IO[java.io.IOException, os.Path] = {
    val readOption: IO[java.io.IOException, Option[os.Path]] = for {
      _ <- zio.Console.print(pretext)
      s <- zio.Console.readLine.map(_.trim)
    } yield if (s.isEmpty) None else try {
      Some(os.Path(java.nio.file.Paths.get(s), os.pwd))
    } catch { case _: java.nio.file.InvalidPathException => None }
    ZIO.iterate(None: Option[os.Path])(_.isEmpty)(_ => readOption).map(_.get)
  }

  /** Choose a path, with an option to specify a custom location, creating it if necessary. */
  def paths(pretext: String, options: Seq[os.Path]): IO[java.io.IOException, os.Path] = {
    case object OtherLocation { override def toString = "Other location..." }

    val readPath: IO[java.io.IOException, os.Path] = numbered(pretext, options :+ OtherLocation).flatMap {
      case OtherLocation => pathInput("Enter a path: ")
      case p: os.Path => ZIO.succeed(p)
    }

    def createMaybe(path: os.Path): IO[java.io.IOException, Option[os.Path]] = {
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
