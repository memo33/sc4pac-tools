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
        case abort: sc4pac.error.Sc4pacAbort => { System.err.println(Array("Operation aborted.", abort.msg).mkString(" ")); exit(1) }
        case abort: sc4pac.error.Sc4pacTimeout => { System.err.println(Array("Operation aborted.", abort.getMessage).mkString(" ")); exit(1) }
        case e => { e.printStackTrace(); exit(1) }
      },
      success = _ => exit(0)
    ))
  }

  @ArgsName("packages...")
  @HelpMessage("""
    |Add packages to the list of explicitly installed packages.
    |Run "sc4pac update" for the changes to take effect.
    |
    |Package format: <group>:<package-name>
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

  @ArgsName("packages...")
  @HelpMessage("""
    |Remove packages from the list of explicitly installed packages.
    |Run "sc4pac update" for the changes to take effect.
    |
    |Package format: <group>:<package-name>
    |
    |Examples:
    |  sc4pac remove                        # interactively select packages to remove
    |  sc4pac remove memo:essential-fixes   # remove this package
    |""".stripMargin.trim)
  final case class RemoveOptions() extends Sc4pacCommandOptions

  case object Remove extends Command[RemoveOptions] {
    def run(options: RemoveOptions, args: RemainingArgs): Unit = {
      val task: Task[Unit] = for {
        mods   <- ZIO.fromEither(Sc4pac.parseModules(args.all)).catchAll { (err: ErrStr) =>
                    error(caseapp.core.Error.Other(s"Package format is <group>:<package-name> ($err)"))
                  }
        config <- PluginsData.readOrInit.map(_.config)
        pac    <- Sc4pac.init(config)
        _      <- if (mods.isEmpty) pac.removeSelect() else pac.remove(mods)
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

  @HelpMessage("""
    |Select variants to reset in order to choose a different package variant.
    |
    |For some packages you install, you can choose from a list of package variants that match your preferences. Your choices are stored in a configuration file.
    |
    |After resetting a variant identifier, the next time you run "sc4pac update", you will be asked to choose a new variant.
    |
    |Examples:
    |  sc4pac variant reset      # interactively select variants to reset
    """.stripMargin.trim)
  final case class VariantResetOptions() extends Sc4pacCommandOptions

  case object VariantReset extends Command[VariantResetOptions] {
    override def names = I.List(I.List("variant", "reset"))
    def run(options: VariantResetOptions, args: RemainingArgs): Unit = {
      val task = PluginsData.readOrInit.flatMap { data =>
        if (data.config.variant.isEmpty) {
          ZIO.succeed(println("The list of configured variants is empty. The next time you install a package that comes in variants, you can choose again."))
        } else {
          val variants: Seq[(String, String)] = data.config.variant.toSeq.sorted
          for {
            selected <- Prompt.numberedMultiSelect("Select variants to reset:", variants, (k, v) => s"$k: $v").map(_.map(_._1))
            data2    =  data.copy(config = data.config.copy(variant = data.config.variant -- selected))
            _        <- Data.writeJsonIo(PluginsData.path, data2, None)(ZIO.succeed(()))
          } yield ()
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

  @HelpMessage("Select channels to remove.")
  final case class ChannelRemoveOptions() extends Sc4pacCommandOptions

  case object ChannelRemove extends Command[ChannelRemoveOptions] {
    override def names = I.List(I.List("channel", "remove"))
    def run(options: ChannelRemoveOptions, args: RemainingArgs): Unit = {
      val task = PluginsData.readOrInit.flatMap { data =>
        if (data.config.channels.isEmpty) {
          ZIO.succeed(println("The list of channel URLs is already empty."))
        } else {
          for {
            selectedUrls <- Prompt.numberedMultiSelect("Select channels to remove:", data.config.channels).map(_.toSet)
            data2        =  data.copy(config = data.config.copy(channels = data.config.channels.filterNot(selectedUrls)))
            _            <- Data.writeJsonIo(PluginsData.path, data2, None)(ZIO.succeed(()))
          } yield ()
        }
      }
      runMainExit(task, exit)
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
    Commands.Remove,
    Commands.Search,
    Commands.List,
    Commands.VariantReset,
    Commands.ChannelAdd,
    Commands.ChannelRemove,
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
