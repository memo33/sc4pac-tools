package io.github.memo33
package sc4pac
package cli

import scala.collection.immutable as I
import caseapp.{Command, RemainingArgs, ArgsName, HelpMessage, ExtraName, ValueDescription}
import zio.{ZIO, Task}

import sc4pac.Data.{PluginsData, PluginsLockData}
import sc4pac.Resolution.BareModule

// see https://github.com/coursier/coursier/blob/main/modules/cli/src/main/scala/coursier/cli/Coursier.scala
// and related files

object Commands {

  sealed abstract class Sc4pacCommandOptions extends Product with Serializable

  private def runMainExit(task: Task[Unit], exit: Int => Nothing): Nothing = {
    unsafeRun(task.fold(
      failure = {
        case abort: sc4pac.error.Sc4pacAbort => { System.err.println(Seq("Operation aborted.", abort.msg).mkString(" ")); exit(1) }
        case e => { e.printStackTrace(); exit(1) }
      },
      success = _ => exit(0)
    ))
  }

  @ArgsName("packages...")
  @HelpMessage(f"Add packages to the list of explicitly installed packages.%nPackage format: <group>:<package-name>")
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
        error(caseapp.core.Error.Other("Argument missing: add one or more packages of the form <group>:<package-name>"))
      }
      val task: Task[Unit] = for {
        mods   <- ZIO.fromEither(Sc4pac.parseModules(args.all)).catchAll { (err: ErrStr) =>
                    error(caseapp.core.Error.Other(s"Package format is <group>:<package-name> ($err)"))
                  }
        config <- PluginsData.readOrInit.map(_.config)
        pac    <- Sc4pac.init(config)
        _      <- pac.add(mods)
      } yield ()
      runMainExit(task, exit)
    }
  }

  // @ArgsName("packages")
  @HelpMessage("Update all installed packages to their latest version and install any missing packages.")
  final case class UpdateOptions() extends Sc4pacCommandOptions

  case object Update extends Command[UpdateOptions] {
    def run(options: UpdateOptions, args: RemainingArgs): Unit = {
      val task = for {
        pluginsData  <- PluginsData.readOrInit
        pac          <- Sc4pac.init(pluginsData.config)
        flag         <- pac.update(pluginsData.explicit, globalVariant0 = pluginsData.config.variant, pluginsRoot = pluginsData.pluginsRootAbs)
      } yield ()
      runMainExit(task, exit)
    }
  }

  @ArgsName("search text...")
  @HelpMessage("Search for the name of a package.")
  final case class SearchOptions() extends Sc4pacCommandOptions

  case object Search extends Command[SearchOptions] {
    def run(options: SearchOptions, args: RemainingArgs): Unit = {
      val task: Task[Unit] = for {
        pluginsData  <- PluginsData.readOrInit
        pac          <- Sc4pac.init(pluginsData.config)
        query        =  args.all.mkString(" ")
        searchResult <- pac.search(query)
        installed    <- PluginsLockData.listInstalled.map(_.map(_.toBareDep).toSet)
      } yield {
        for ((mod, ratio, description) <- searchResult) {
          pac.logger.logSearchResult(mod, description, installed(mod))
        }
      }
      runMainExit(task, exit)
    }
  }

  @HelpMessage("List all installed packages.")
  final case class ListOptions() extends Sc4pacCommandOptions

  case object List extends Command[ListOptions] {
    def run(options: ListOptions, args: RemainingArgs): Unit = {
      val task: Task[Unit] = for {
        pluginsData  <- PluginsData.readOrInit
        pac          <- Sc4pac.init(pluginsData.config)  // only used for logging
        installed    <- PluginsLockData.listInstalled
      } yield {
        val sorted = installed.sortBy(mod => (mod.group.value, mod.name.value))
        val explicit: Set[BareModule] = pluginsData.explicit.toSet
        for (mod <- sorted) {
          pac.logger.logInstalled(mod, explicit(mod.toBareDep))
        }
      }
      runMainExit(task, exit)
    }
  }

  @ArgsName("channel-URL")
  @HelpMessage(s"""
    |Add a channel to fetch package metadata from.
    |
    |Examples:
    |  sc4pac channel add "${Constants.defaultChannelUrls.head}"
    |  sc4pac channel add "file://absolute/local/path/to/channel/json/"
    """.stripMargin.trim)
  final case class ChannelAddOptions() extends Sc4pacCommandOptions

  case object ChannelAdd extends Command[ChannelAddOptions] {
    override def names = I.List(I.List("channel", "add"))
    def run(options: ChannelAddOptions, args: RemainingArgs): Unit = {
      args.all match {
        case Seq[String](channelUrl) =>
          val uri = MetadataRepository.parseChannelUrl(channelUrl)
          val task: Task[Unit] = for {
            data  <- PluginsData.readOrInit
            data2 =  data.copy(config = data.config.copy(channels = (data.config.channels :+ uri).distinct))
            _     <- Data.writeJsonIo(PluginsData.path, data2, None)(ZIO.succeed(()))
          } yield ()
          runMainExit(task, exit)
        case _ => error(caseapp.core.Error.Other("A single argument is needed: channel-URL"))
      }
    }
  }

  @HelpMessage(f"List the channel URLs.%nThe first channel has the highest priority when resolving dependencies.")
  final case class ChannelListOptions() extends Sc4pacCommandOptions

  case object ChannelList extends Command[ChannelListOptions] {
    override def names = I.List(I.List("channel", "list"))
    def run(options: ChannelListOptions, args: RemainingArgs): Unit = {
      val task = for {
        pluginsData <- PluginsData.readOrInit
      } yield {
        for (url <- pluginsData.config.channels) {
          println(url)
        }
      }
      runMainExit(task, exit)
    }
  }

  @ArgsName("YAML-input-directories...")
  @HelpMessage("""
    |Build a channel locally by converting YAML files to JSON.
    |
    |Examples:
    |  sc4pac channel build --output channel/json/ channel/yaml/
    """.stripMargin.trim)
  final case class ChannelBuildOptions(
    @ExtraName("o") @ValueDescription("directory") @HelpMessage("Output directory for JSON files")
    output: String
  ) extends Sc4pacCommandOptions

  /** For internal use, convert yaml files to json.
    * Usage: `./sc4pac build-channel ./channel-testing/`
    */
  case object ChannelBuild extends Command[ChannelBuildOptions] {
    override def names = I.List(I.List("channel", "build"))
    def run(options: ChannelBuildOptions, args: RemainingArgs): Unit = {
      args.all match {
        case Nil => error(caseapp.core.Error.Other("An argument is needed: YAML input directory"))
        case inputs => ChannelUtil.convertYamlToJson(inputs.map(os.Path(_, os.pwd)), os.Path(options.output, os.pwd))
      }
    }
  }

}

object CliMain extends caseapp.core.app.CommandsEntryPoint {
  val commands = Seq(
    Commands.Add,
    Commands.Update,
    Commands.Search,
    Commands.List,
    Commands.ChannelAdd,
    Commands.ChannelList,
    Commands.ChannelBuild)
  val progName = BuildInfo.name
  override val description = s"  A package manager for SimCity 4 plugins. Version ${BuildInfo.version}."

  override def main(args: Array[String]): Unit = {
    try {
      // First of all, we install ansi-aware streams, so that colors are
      // interpreted correctly on Windows (for example for the help text).
      org.fusesource.jansi.AnsiConsole.systemInstall()  // this alters System.out and System.err
    } catch {
      case e: java.lang.UnsatisfiedLinkError =>  // in case something goes really wrong and no suitable jansi native library is included
    }
    super.main(args)
  }
}
