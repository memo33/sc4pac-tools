package io.github.memo33
package sc4pac
package cli

import scala.collection.immutable as I
import caseapp.{RemainingArgs, ArgsName, HelpMessage, ExtraName, ValueDescription, Group, Tag}
import zio.{ZIO, Task, Ref, RIO}

import sc4pac.error.Sc4pacNotInteractive
import sc4pac.JsonData as JD
import sc4pac.Resolution.DepModule

// see https://github.com/coursier/coursier/blob/main/modules/cli/src/main/scala/coursier/cli/Coursier.scala
// and related files

trait Command[A] extends caseapp.Command[A] {

  def commandName: String = names.headOption.map(parts => (BuildInfo.name :: parts).mkString(" ")).getOrElse(BuildInfo.name + " <COMMAND>")

  override def helpFormat = super.helpFormat.copy(sortGroups = Commands.sortHelpLast)
}

object Commands {

  sealed abstract class Sc4pacCommandOptions extends Product with Serializable

  val cliEnvironment = {
    val logger = CliLogger()
    zio.ZEnvironment(ProfileRoot(os.pwd), logger, CliPrompter(logger, autoYes = false))
  }
  val cliLayer =
    zio.ZLayer(Ref.make(Option.empty[FileCache]))
      .map(_.union(cliEnvironment))

  // TODO strip escape sequences if jansi failed with a link error
  private[sc4pac] def gray(msg: String): String = s"${27.toChar}[90m" + msg + Console.RESET  // aka bright black
  private[sc4pac] def emph(msg: String): String = Console.BOLD + msg + Console.RESET

  private[sc4pac] val sortHelpLast: Option[Seq[String] => Seq[String]] =
    Some(groups => groups.partition(_ == "Help") match { case (help, nonHelp) => nonHelp ++ help })

  // failures that are expected with both the CLI and the API
  type ExpectedFailure = error.Sc4pacAbort | error.DownloadFailed | error.ChannelsNotAvailable
    | error.Sc4pacVersionNotFound | error.Sc4pacAssetNotFound | error.ExtractionFailed
    | error.UnsatisfiableVariantConstraints | error.ChecksumError | error.ReadingProfileFailed
    | error.Sc4pacPublishIncomplete | error.UnresolvableDependencies
    | java.nio.file.AccessDeniedException

  private def handleExpectedFailures(abort: ExpectedFailure, exit: Int => Nothing): Nothing = abort match {
    case abort: error.Sc4pacAbort => { System.err.println("Operation aborted."); exit(1) }
    case abort: java.nio.file.AccessDeniedException => { System.err.println(s"Operation aborted. File access denied. Check your permissions to access the file or directory: ${abort.getMessage}"); exit(ExitCodes.AccessDenied) }  // any command that creates directories or files
    case abort: error.Sc4pacPublishIncomplete => { System.err.println(s"Operation finished with warnings. ${abort.getMessage}"); exit(ExitCodes.PublishIncomplete) }  // update (final step)
    case abort => { System.err.println(s"Operation aborted. ${abort.getMessage}"); exit(1) }
  }

  object ExitCodes {
    val JavaNotFound = 55  // see `sc4pac` and `sc4pac.bat` scripts
    val PortOccupied = 56
    val AccessDenied = 57
    val PublishIncomplete = 58
  }

  private def runMainExit(task: Task[Unit], exit: Int => Nothing): Nothing = {
    unsafeRun(task.fold(
      failure = {
        case abort: ExpectedFailure => handleExpectedFailures(abort, exit)  // we do not need the trace for expected failures
        case abort: error.Sc4pacTimeout => { System.err.println(Array("Operation aborted.", abort.getMessage).mkString(" ")); exit(1) }
        case abort: error.Sc4pacNotInteractive => { System.err.println(s"Operation aborted as terminal is non-interactive: ${abort.getMessage}"); exit(1) }
        case abort: error.SymlinkCreationFailed => { System.err.println(s"Operation aborted. ${abort.getMessage}"); exit(1) }  // channel-build command
        case abort: error.FileOpsFailure => { System.err.println(s"Operation aborted. ${abort.getMessage}"); exit(1) }  // channel-build command
        case abort: error.YamlFormatIssue => { System.err.println(s"Operation aborted. ${abort.getMessage}"); exit(1) }  // channel-build command
        case abort: error.PortOccupied => { System.err.println(abort.getMessage); exit(ExitCodes.PortOccupied) }  // server command
        case e => { e.printStackTrace(); exit(2) }
      },
      success = _ => exit(0)
    ))
  }

  @ArgsName("packages...")
  @HelpMessage(s"""
    |Add new packages to install explicitly.
    |
    |Afterwards, run ${emph("sc4pac update")} for the changes to take effect.
    |
    |Example:
    |  sc4pac add memo:essential-fixes   ${gray("# packages of the form <group>:<package-name>")}
    """.stripMargin.trim)
  final case class AddOptions(
    // @Group("foo")
    // @Tag("foo")
    // @ExtraName("V")
    // @ValueDescription("stages")
    // @HelpMessage("Set a value")
    // aa: String
  ) extends Sc4pacCommandOptions

  case object Add extends Command[AddOptions] {
    def run(options: AddOptions, args: RemainingArgs): Unit = {
      if (args.all.isEmpty) {
        fullHelpAsked(commandName)
      }
      val task = for {
        mods   <- ZIO.fromEither(Sc4pac.parseModules(args.all)).catchAll { (err: ErrStr) =>
                    error(caseapp.core.Error.Other(s"Package format is <group>:<package-name> ($err)"))
                  }
        config <- JD.PluginsSpec.readOrInit.map(_.config)
        pac    <- Sc4pac.init(config)
        _      <- pac.add(mods)
      } yield ()
      runMainExit(task.provideLayer(cliLayer), exit)
    }
  }

  @HelpMessage(s"""
    |Update all installed packages to their latest version and install any missing packages.
    |
    |In particular, this installs the explicitly added packages and, implicitly, all their dependencies.
    |
    |Example:
    |  sc4pac update
    """.stripMargin.trim)
  final case class UpdateOptions(
    @ExtraName("y") @HelpMessage("""Accept some default answers without asking, usually "yes"""") @Group("Main") @Tag("Main")
    yes: Boolean = false
  ) extends Sc4pacCommandOptions

  case object Update extends Command[UpdateOptions] {
    def run(options: UpdateOptions, args: RemainingArgs): Unit = {
      val task = for {
        pluginsSpec  <- JD.PluginsSpec.readOrInit
        pac          <- Sc4pac.init(pluginsSpec.config)
        pluginsRoot  <- pluginsSpec.config.pluginsRootAbs
        flag         <- pac.update(pluginsSpec.explicit, globalVariant0 = pluginsSpec.config.variant, pluginsRoot = pluginsRoot)
                          .provideSomeLayer(zio.ZLayer.succeed(Downloader.Cookies(simtropolisCookie = Constants.simtropolisCookie, simtropolisToken = Constants.simtropolisToken)))
      } yield ()
      runMainExit(task.provideLayer(cliLayer.map(_.update((_: CliPrompter).withAutoYes(options.yes)))), exit)
    }
  }

  @ArgsName("packages...")
  @HelpMessage(s"""
    |Remove packages that have been installed explicitly.
    |
    |Afterwards, run ${emph("sc4pac update")} for the changes to take effect.
    |
    |Examples:
    |  sc4pac remove --interactive          ${gray("# Interactively select packages to remove.")}
    |  sc4pac remove memo:essential-fixes   ${gray("# Remove package <group>:<package-name>.")}
    |""".stripMargin.trim)
  final case class RemoveOptions(
    @ExtraName("i") @HelpMessage("Interactively select packages to remove") @Group("Main") @Tag("Main")
    interactive: Boolean = false
  ) extends Sc4pacCommandOptions

  case object Remove extends Command[RemoveOptions] {
    def run(options: RemoveOptions, args: RemainingArgs): Unit = {
      if (!options.interactive && args.all.isEmpty) {
        fullHelpAsked(commandName)
      } else {
        val task = for {
          mods   <- ZIO.fromEither(Sc4pac.parseModules(args.all)).catchAll { (err: ErrStr) =>
                      error(caseapp.core.Error.Other(s"Package format is <group>:<package-name> ($err)"))
                    }
          config <- JD.PluginsSpec.readOrInit.map(_.config)
          pac    <- Sc4pac.init(config)
          _      <- if (options.interactive) {
                      Prompt.ifInteractive(
                        onTrue = pac.removeSelect(),
                        onFalse = ZIO.fail(new Sc4pacNotInteractive(s"Pass packages to remove as arguments, non-interactively.")))
                    } else {
                      pac.remove(mods)
                    }
        } yield ()
        runMainExit(task.provideLayer(cliLayer), exit)
      }
    }
  }

  @ArgsName("search text...")
  @HelpMessage(s"""
    |Search for the name of a package.
    |The results are ordered such that the best match is displayed at the bottom.
    |
    |Examples:
    |
    |  sc4pac search "Pause border"
    |  ${gray(">>>")} (1) smp:yellow-pause-thingy-remover
    |  ${gray(">>>")}         Remove the yellow border from the UI when the game is paused
    |
    |  sc4pac search --threshold 20 "Pause border"    ${gray("# Decrease threshold for more results.")}
    |  ${gray(">>>")} ...
    |
    |You can search for a URL of a STEX entry or SC4Evermore download page to find any corresponding packages:
    |
    |  sc4pac search "https://community.simtropolis.com/files/file/32812-save-warning/"
    |  ${gray(">>>")} ...
    |
    |  sc4pac search "https://www.sc4evermore.com/index.php/downloads/download/26-gameplay-mods/26-bsc-no-maxis"
    |  ${gray(">>>")} ...
    |
    """.stripMargin.trim)
  final case class SearchOptions(
    @ValueDescription("number") @Group("Search") @Tag("Search")
    @HelpMessage(s"Fuziness (0..100, default=${Constants.fuzzySearchThreshold}): Smaller numbers lead to more results.")
    threshold: Int = Constants.fuzzySearchThreshold  // 0..100, default 80
  ) extends Sc4pacCommandOptions

  case object Search extends Command[SearchOptions] {
    def run(options: SearchOptions, args: RemainingArgs): Unit = {
      if (args.all.isEmpty) {
        fullHelpAsked(commandName)
      } else {
        val task = for {
          pluginsSpec  <- JD.PluginsSpec.readOrInit
          pac          <- Sc4pac.init(pluginsSpec.config)
          query        =  args.all.mkString(" ")
          searchResult <- pac.search(query, options.threshold, category = Set.empty, notCategory = Set.empty, channel = None)
          installed    <- JD.PluginsLock.listInstalled.map(_.map(_.toBareDep).toSet)
          logger       <- ZIO.service[CliLogger]
        } yield {
          if (searchResult.isEmpty) {
            error(caseapp.core.Error.Other("No packages found. Try to lower the `--threshold` parameter."))
          } else {
            for (((mod, ratio, description), idx) <- searchResult.zipWithIndex.reverse) {
              logger.logSearchResult(idx, mod, description, installed(mod))
            }
          }
        }
        runMainExit(task.provideLayer(cliLayer), exit)
      }
    }
  }

  @ArgsName("packages")
  @HelpMessage(s"""
    |Display more information about a package.
    |
    |Examples:
    |  sc4pac info memo:essential-fixes
    """.stripMargin.trim)
  final case class InfoOptions() extends Sc4pacCommandOptions

  case object Info extends Command[InfoOptions] {
    def run(options: InfoOptions, args: RemainingArgs): Unit = {
      args.all match {
        case Nil => fullHelpAsked(commandName)
        case pkgNames =>
          val task = for {
            mods         <- ZIO.fromEither(Sc4pac.parseModules(pkgNames)).catchAll { (err: ErrStr) =>
                              error(caseapp.core.Error.Other(s"Package format is <group>:<package-name> ($err)"))
                            }
            pluginsSpec  <- JD.PluginsSpec.readOrInit
            pac          <- Sc4pac.init(pluginsSpec.config)
            infoResults  <- ZIO.foreachPar(mods)(pac.info)
            logger       <- ZIO.service[CliLogger]
          } yield {
            val (found, notFound) = infoResults.zip(mods).partition(_._1.isDefined)
            if (notFound.nonEmpty) {
              error(caseapp.core.Error.Other("Package not found in any of your channels: " + notFound.map(_._2.orgName).mkString(" ")))
            } else {
              for ((infoResultOpt, idx) <- found.zipWithIndex) {
                if (idx > 0) logger.log("")
                logger.logInfoResult(infoResultOpt._1.get)
              }
            }
          }
          runMainExit(task.provideLayer(cliLayer), exit)
      }
    }
  }

  @HelpMessage("List all installed packages.")
  final case class ListOptions() extends Sc4pacCommandOptions

  case object List extends Command[ListOptions] {
    def run(options: ListOptions, args: RemainingArgs): Unit = {
      val task = for {
        pluginsSpec  <- JD.PluginsSpec.readOrInit
        iter         <- iterateInstalled(pluginsSpec)
        logger       <- ZIO.service[CliLogger]
      } yield {
        for ((mod, explicit) <- iter) logger.logInstalled(mod, explicit)
      }
      runMainExit(task.provideLayer(cliLayer), exit)
    }

    def iterateInstalled(pluginsSpec: JD.PluginsSpec): zio.RIO[ProfileRoot, Iterator[(DepModule, Boolean)]] = {
      for (installed <- JD.PluginsLock.listInstalled) yield {
        val sorted = installed.sortBy(mod => (mod.group.value, mod.name.value))
        val explicit: Set[BareModule] = pluginsSpec.explicit.toSet
        sorted.iterator.map(mod => (mod, explicit(mod.toBareDep)))
      }
    }
  }

  @ArgsName("variants...")
  @HelpMessage(s"""
    |Select variants to reset in order to choose a different package variant.
    |
    |For some packages you install, you can choose from a list of package variants that match your preferences. Your choices are stored in a configuration file.
    |
    |After resetting a variant identifier, the next time you run ${emph("sc4pac update")}, you will be asked to choose a new variant.
    |
    |Examples:
    |  sc4pac variant reset --interactive    ${gray("# Interactively select variants to reset.")}
    |  sc4pac variant reset "driveside"      ${gray("# Reset the \"driveside\" variant.")}
    """.stripMargin.trim)
  final case class VariantResetOptions(
    @ExtraName("i") @HelpMessage("Interactively select variants to reset") @Group("Main") @Tag("Main")
    interactive: Boolean = false
  ) extends Sc4pacCommandOptions

  case object VariantReset extends Command[VariantResetOptions] {
    override def names = I.List(I.List("variant", "reset"))
    def run(options: VariantResetOptions, args: RemainingArgs): Unit = {
      if (!options.interactive && args.all.isEmpty) {
        fullHelpAsked(commandName)
      } else {
        val task = JD.PluginsSpec.readOrInit.flatMap { data =>
          if (data.config.variant.isEmpty) {
            ZIO.succeed(println("The list of configured variants is empty. The next time you install a package that comes in variants, you can choose again."))
          } else {
            val variants: Seq[(String, String)] = data.config.variant.toSeq.sorted
            val select: Task[Seq[String]] =
              if (!options.interactive) {
                ZIO.succeed(args.all)
              } else {
                Prompt.ifInteractive(
                  onTrue = Prompt.numberedMultiSelect("Select variants to reset:", variants, (k, v) => s"$k = $v").map(_.map(_._1)),
                  onFalse = ZIO.fail(new Sc4pacNotInteractive(s"Pass variants to remove as arguments, non-interactively."))
                )
              }
            select.flatMap(removeAndWrite(data, _))
          }
        }
        runMainExit(task.provideLayer(cliLayer), exit)
      }
    }

    def removeAndWrite(data: JD.PluginsSpec, selected: Seq[String]): zio.RIO[ProfileRoot, Unit] = {
      val data2 = data.copy(config = data.config.copy(variant = data.config.variant -- selected))
      for {
        path <- JD.PluginsSpec.pathURIO
        _    <- JsonIo.write(path, data2, None)(ZIO.succeed(()))
      } yield ()
    }
  }

  @ArgsName("channel-URL")
  @HelpMessage(s"""
    |Add a channel to fetch package metadata from.
    |
    |Examples:
    |  sc4pac channel add "${Constants.defaultChannelUrls.head}"
    |  sc4pac channel add "file:///C:/absolute/path/to/local/channel/json/"
    |
    |The URL in the examples above points to a directory structure consisting of JSON files created by the ${emph("sc4pac channel build")} command.
    |
    |For convenience, the channel URL may also point to a single YAML file instead, which skips the ${emph("sc4pac channel build")} step. This is mainly intended for testing purposes.
    |
    |  sc4pac channel add "file:///C:/Users/Dumbledore/Desktop/hogwarts-castle.yaml"
    |  sc4pac channel add "https://raw.githubusercontent.com/memo33/sc4pac/main/docs/hogwarts-castle.yaml"
    """.stripMargin.trim)
  final case class ChannelAddOptions() extends Sc4pacCommandOptions

  case object ChannelAdd extends Command[ChannelAddOptions] {
    override def names = I.List(I.List("channel", "add"))
    def run(options: ChannelAddOptions, args: RemainingArgs): Unit = {
      args.all match {
        case Seq[String](text) =>
          MetadataRepository.parseChannelUrl(text) match {
            case Left(err) => error(caseapp.core.Error.Other(s"Malformed URL: $err"))
            case Right(uri) =>
              if (uri.getScheme == "file" && !java.io.File(uri).exists()) {
                error(caseapp.core.Error.Other(s"Local channel file does not exist: $uri"))
              } else {
                val task = for {
                  data  <- JD.PluginsSpec.readOrInit
                  data2 =  data.copy(config = data.config.copy(channels = (data.config.channels :+ uri).distinct))
                  path  <- JD.PluginsSpec.pathURIO
                  _     <- JsonIo.write(path, data2, None)(ZIO.succeed(()))
                  count =  data2.config.channels.length - data.config.channels.length
                  _     <- ZIO.succeed{ println(if (count == 0) "Channel already exists." else s"Added 1 channel.") }
                } yield ()
                runMainExit(task.provideLayer(cliLayer), exit)
              }
          }
        case Nil => fullHelpAsked(commandName)
        case _ => error(caseapp.core.Error.Other("A single argument is needed: channel-URL"))
      }
    }
  }

  @ArgsName("URL-patterns")
  @HelpMessage(s"""
    |Select channels to remove.
    |
    |Examples:
    |  sc4pac channel remove --interactive     ${gray("# Interactively select channels to remove.")}
    |  sc4pac channel remove "github.com"      ${gray("# Remove channel URLs containing \"github.com\".")}
    """.stripMargin.trim)
  final case class ChannelRemoveOptions(
    @ExtraName("i") @HelpMessage("Interactively select channels to remove") @Group("Main") @Tag("Main")
    interactive: Boolean = false
  ) extends Sc4pacCommandOptions

  case object ChannelRemove extends Command[ChannelRemoveOptions] {
    override def names = I.List(I.List("channel", "remove"))
    def run(options: ChannelRemoveOptions, args: RemainingArgs): Unit = {
      if (!options.interactive && args.all.isEmpty) {
        fullHelpAsked(commandName)
      } else {
        val task = JD.PluginsSpec.readOrInit.flatMap { data =>
          if (data.config.channels.isEmpty) {
            ZIO.succeed(println("The list of channel URLs is already empty."))
          } else {
            for {
              isSelected   <- if (options.interactive) {
                                Prompt.ifInteractive(
                                  onTrue = Prompt.numberedMultiSelect("Select channels to remove:", data.config.channels).map(_.toSet),
                                  onFalse = ZIO.fail(new Sc4pacNotInteractive(s"Pass channel URL patterns as arguments, non-interactively."))
                                )
                              } else {
                                ZIO.succeed((url: java.net.URI) => args.all.exists(pattern => url.toString.contains(pattern)))
                              }
              (drop, keep) =  data.config.channels.partition(isSelected)
              _            <- ZIO.succeed {
                                if (drop.nonEmpty)
                                  println(("The following channels have been removed:" +: drop).mkString(f"%n"))
                                else
                                  println("No matching channel found, so none of the channels have been removed.")
                              }
              data2        =  data.copy(config = data.config.copy(channels = keep))
              path         <- JD.PluginsSpec.pathURIO
              _            <- JsonIo.write(path, data2, None)(ZIO.succeed(()))
            } yield ()
          }
        }
        runMainExit(task.provideLayer(cliLayer), exit)
      }
    }
  }


  @HelpMessage(f"List the channel URLs.%nThe first channel has the highest priority when resolving dependencies.")
  final case class ChannelListOptions() extends Sc4pacCommandOptions

  case object ChannelList extends Command[ChannelListOptions] {
    override def names = I.List(I.List("channel", "list"))
    def run(options: ChannelListOptions, args: RemainingArgs): Unit = {
      val task = for {
        pluginsSpec <- JD.PluginsSpec.readOrInit
      } yield {
        for (url <- pluginsSpec.config.channels) {
          println(url)
        }
      }
      runMainExit(task.provideLayer(cliLayer), exit)
    }
  }

  @ArgsName("YAML-input-directories...")
  @HelpMessage(s"""
    |Build a channel locally by converting YAML files to JSON.
    |
    |On Windows, this command may require special privileges to run.
    |To resolve this, either run the command in a shell with administrator privileges, or use Java 13+ and enable Windows Developer Mode on your device.
    |
    |Examples:
    |  sc4pac channel build --output "channel/json/" "channel/yaml/"
    |  sc4pac channel build --label Local --metadata-source-url https://github.com/memo33/sc4pac/blob/main/src/yaml/ -o channel/json channel/yaml
    |
    |Use the options ${emph("--label")} and ${emph("--metadata-source-url")} particularly for building publicly accessible channels.
    """.stripMargin.trim)
  final case class ChannelBuildOptions(
    @ExtraName("o") @ValueDescription("dir") @HelpMessage("Output directory for JSON files") @Group("Main") @Tag("Main")
    output: String,
    @ValueDescription("str") @HelpMessage("Optional short channel name for display in the UI") @Group("Main") @Tag("Main")
    label: String = null,
    @ValueDescription("url") @HelpMessage("Optional base URL linking to the online YAML source files (for Edit Metadata button)") @Group("Main") @Tag("Main")
    metadataSourceUrl: String = null,
  ) extends Sc4pacCommandOptions

  /** For internal use, convert yaml files to json.
    * Usage: `./sc4pac build-channel ./channel-testing/`
    */
  case object ChannelBuild extends Command[ChannelBuildOptions] {
    override def names = I.List(I.List("channel", "build"))
    def run(options: ChannelBuildOptions, args: RemainingArgs): Unit = {
      args.all match {
        case Nil => error(caseapp.core.Error.Other("An argument is needed: YAML input directory"))
        case inputs =>
          val metadataSourceUrl = Option(options.metadataSourceUrl).filter(_.nonEmpty)
            .map(MetadataRepository.parseChannelUrl)
            .map {
              case Left(err) => error(caseapp.core.Error.Other(s"Malformed metadata source URL: $err"))
              case Right(uri) => uri
            }
          val ghUrl = "^https://github.com/([^/]+/[^/]+)/.*".r  // matches repo
          val info = JD.Channel.Info(
            channelLabel = Option(options.label).filter(_.nonEmpty),
            metadataSourceUrl = metadataSourceUrl,
            metadataIssueUrl = metadataSourceUrl.flatMap(_.toString match {
              case ghUrl(repo) => Some(java.net.URI.create(s"https://github.com/$repo/issues"))
              case _ => None
            }),
          )
          val task =
            ChannelUtil.convertYamlToJson(inputs.map(os.Path(_, os.pwd)), os.Path(options.output, os.pwd))
              .provideSomeLayer(zio.ZLayer.succeed(info))
          runMainExit(task, exit)
      }
    }
  }

  @HelpMessage(s"""
    |Start a local server to use the HTTP API.
    |
    |Examples:
    |  sc4pac server --profiles-dir profiles --indent 1
    |  sc4pac server --profiles-dir profiles --web-app-dir build/web --launch-browser  ${gray("# used by GUI web")}
    |  sc4pac server --profiles-dir profiles --auto-shutdown --startup-tag [READY]     ${gray("# used by GUI desktop")}
    """.stripMargin.trim)
  final case class ServerOptions(
    @ValueDescription("number") @Group("Server") @Tag("Server")
    @HelpMessage(s"(default: ${Constants.defaultPort})")
    port: Int = Constants.defaultPort,
    @ValueDescription("path") @Group("Server") @Tag("Server")
    @HelpMessage(s"""directory containing the sc4pac-profiles.json file and profile sub-directories (platform-dependent default: "${JD.Profiles.defaultProfilesRoot}"), newly created if necessary""")
    profilesDir: String = "",
    @ValueDescription("path") @Group("Server") @Tag("Server")
    @HelpMessage(s"optional directory containing statically served webapp files (default: no static files)")
    webAppDir: String = "",
    @ValueDescription("bool") @Group("Server") @Tag("Server")
    @HelpMessage(s"automatically open the web browser when using the --web-app-dir option (default: --launch-browser=false)")
    launchBrowser: Boolean = false,
    @ValueDescription("bool") @Group("Server") @Tag("Server")
    @HelpMessage("automatically shut down the server when client closes connection to /server.connect (default: --auto-shutdown=false). This is used by the desktop GUI to ensure the port is cleared when the GUI exits.")
    autoShutdown: Boolean = false,
    @ValueDescription("string") @Group("Server") @Tag("Server")
    @HelpMessage(s"optional tag to print once server has started and is listening")
    startupTag: String = "",
    @ValueDescription("number") @Group("Server") @Tag("Server")
    @HelpMessage(s"indentation of JSON responses (default: -1, no indentation)")
    indent: Int = -1,
  ) extends Sc4pacCommandOptions

  case object Server extends Command[ServerOptions] {

    private def followRedirects = zio.http.ZClientAspect.followRedirects(Constants.maxRedirectionsOpt.get)(onRedirectError = { (resp, message) =>
      ZIO.logInfo(message).as(resp)
    })

    def serve(options: ServerOptions, profilesDir: os.Path, webAppDir: Option[os.Path]): RIO[zio.Scope, zio.Fiber[Throwable, Nothing]] = {
      val api = sc4pac.api.Api(options)
      // Enabling CORS is important so that web browsers do not block the
      // request response for lack of the following response header:
      //     access-control-allow-origin: http://localhost:12345
      // (e.g. when Flutter-web is hosted on port 12345)
      val app = api.routes(webAppDir) @@ zio.http.Middleware.cors

      val serverTask: RIO[ServerFiber, Nothing] =
        zio.http.Server.install(app)
          .catchSomeDefect {
            // usually: "bind(..) failed: Address already in use"
            case e: io.netty.channel.unix.Errors.NativeIoException if e.getMessage.contains("bind") =>
              ZIO.fail(sc4pac.error.PortOccupied(s"Failed to run sc4pac server on port ${options.port}. ${e.getMessage}"))
          }
          .zipRight(ZIO.succeed {
            if (options.startupTag.nonEmpty)
              println(options.startupTag)
            println(s"Sc4pac server is listening on port ${options.port}...")
          })
          .zipRight(
            ZIO.whenDiscard(webAppDir.isDefined) {
              val url = java.net.URI.create(s"http://localhost:${options.port}/webapp/")
              println(f"%nTo start the sc4pac-gui web-app, open the following URL in your web browser if it does not launch automatically:%n%n  ${url}%n")
              ZIO.whenDiscard(options.launchBrowser) {
                DesktopOps.openUrl(url).catchAll(_ => ZIO.succeed(()))  // errors can be ignored
              }
            }
          )
          .zipRight(
            ZIO.whenDiscard(options.autoShutdown) {
              // shut down server if nothing connected after a timeout interval
              // (to prevent detached old background processes blocking the port)
              ZIO.sleep(zio.Duration.fromSeconds(if (webAppDir.isDefined) 60 else 20))
              .zipRight(api.shutdownServerIfNoConnections(
                remainingConnections = None,  // irrelevant for timeout
                reason = "Timeout: No connection to server has been established.",
              ))
            }
          )
          .zipRight(ZIO.never)  // keep server running indefinitely unless interrupted
          .provideSome[ServerFiber](
            zio.http.Server.defaultWithPort(options.port)
              .mapError { e =>  // usually: "bind(..) failed: Address already in use"
                // This branch does not seem to usually catch the error anymore.
                // Instead it is caught in Server.install(app).catchSomeDefect(...) above.
                // We defensively keep this branch in case of future zio-http/netty changes.
                sc4pac.error.PortOccupied(s"Failed to run sc4pac server on port ${options.port}. ${e.getMessage}")
              },
            zio.http.Client.default  // for /image.fetch
              .map(_.update[zio.http.Client](_.updateHeaders(_.addHeader("User-Agent", Constants.userAgent)) @@ followRedirects)),
            zio.ZLayer.succeed(ProfilesDir(profilesDir)),
            zio.ZLayer(zio.Ref.make(ServerConnection(numConnections = 0, currentChannel = None))),
            zio.ZLayer(zio.Ref.make(Option.empty[FileCache])),
          )

      for {
        promise  <- zio.Promise.make[Nothing, zio.Fiber[Throwable, Nothing]]
        fiber    <- serverTask
                      .provide(zio.ZLayer.succeed(ServerFiber(promise)))
                      .forkScoped
                      // Forking is used to allow interrupting the server to shut it down;
                      // forkScoped (instead of fork or acquireRelease) is mainly needed for tests (to avoid uninterruptible hanging),
                      // see https://stackoverflow.com/questions/77631198/how-to-properly-interrupt-a-fiber-in-zio-test
        _        <- promise.succeed(fiber)
      } yield fiber
    }

    def run(options: ServerOptions, args: RemainingArgs): Unit = {
      if (options.indent < -1)
        error(caseapp.core.Error.Other(s"Indentation must be -1 or larger."))
      val profilesDir: os.Path =
        if (options.profilesDir.isEmpty) JD.Profiles.defaultProfilesRoot else os.Path(java.nio.file.Paths.get(options.profilesDir), os.pwd)
      val webAppDir: Option[os.Path] =
        if (options.webAppDir.isEmpty) None else Some(os.Path(java.nio.file.Paths.get(options.webAppDir), os.pwd))
      val task: Task[Unit] = {
        for {
          _  <- ZIO.whenZIODiscard(ZIO.attemptBlockingIO(webAppDir.isDefined && !os.exists(webAppDir.get)))(
                  error(caseapp.core.Error.Other(s"Webapp directory does not exist: ${webAppDir.get}"))
                )
          _  <- ZIO.attemptBlockingIO(if (!os.exists(profilesDir)) {
                  println(s"Creating sc4pac profiles directory: $profilesDir")
                  os.makeDir.all(profilesDir)
                })
          _  <- ZIO.scoped {
                  for {
                    fiber    <- serve(options, profilesDir = profilesDir, webAppDir = webAppDir)
                    exitVal  <- fiber.await
                    _        <- exitVal match {
                                  case zio.Exit.Failure(cause) =>
                                    if (cause.isInterruptedOnly) {
                                      ZIO.succeed(())  // interrupt is expected following autoShutdown after /server.connect
                                    } else cause.failureOrCause match {
                                      case Left(err) => ZIO.fail(err)  // converts PortOccupied failure cause to plain error
                                      case Right(cause2) => ZIO.refailCause(cause2)
                                    }
                                  case zio.Exit.Success[Nothing](nothing) => nothing
                              }
                  } yield ()
                }
        } yield ()
      }
      runMainExit(task, exit)
    }

    class ServerFiber(val promise: zio.Promise[Nothing, zio.Fiber[Throwable, Nothing]])
    class ServerConnection(val numConnections: Int, val currentChannel: Option[zio.http.WebSocketChannel])
  }

}

object CliMain extends caseapp.core.app.CommandsEntryPoint {
  import Commands.gray

  val commands = Seq(
    Commands.Add,
    Commands.Update,
    Commands.Remove,
    Commands.Search,
    Commands.Info,
    Commands.List,
    Commands.VariantReset,
    Commands.ChannelAdd,
    Commands.ChannelRemove,
    Commands.ChannelList,
    Commands.ChannelBuild,
    Commands.Server)

  val progName = BuildInfo.name

  override val description = s"""
    |A package manager for SimCity 4 plugins (Version ${BuildInfo.version}).
    |
    |Examples:
    |
    |  sc4pac add memo:essential-fixes    ${gray("# Add new package to install.")}
    |  sc4pac update                      ${gray("# Download and install everything as needed.")}
    |  sc4pac add --help                  ${gray("# Display more information about a command.")}
    |
    """.stripMargin.trim

  override def main(args: Array[String]): Unit = {
    if (args.length == 1 && (args(0) == "--version" || args(0) == "-v")) {
      println(BuildInfo.version)
    } else {
      try {
        // First of all, we install ansi-aware streams, so that colors are
        // interpreted correctly on Windows (for example for the help text).
        org.fusesource.jansi.AnsiConsole.systemInstall()  // this alters System.out and System.err
        if (Constants.noColor) {
          org.fusesource.jansi.AnsiConsole.out().setMode(org.fusesource.jansi.AnsiMode.Strip)
          org.fusesource.jansi.AnsiConsole.err().setMode(org.fusesource.jansi.AnsiMode.Strip)
        }
      } catch {
        case e: java.lang.UnsatisfiedLinkError =>  // in case something goes really wrong and no suitable jansi native library is included
      }
      super.main(args)
    }
  }
}
