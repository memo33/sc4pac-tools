package io.github.memo33
package sc4pac
package cli

import caseapp.{Command, RemainingArgs, ArgsName, HelpMessage}
import zio.{ZIO, Task}

import sc4pac.Data.{PluginsData, PluginsLockData}

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
        mods   <- Sc4pac.parseModules(args.all).catchAll { (err: String) =>
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
        explicitMods <- pac.add(Seq.empty)  // retrieves explicitly installed modules
        flag         <- pac.update(explicitMods, globalVariant0 = pluginsData.config.variant, pluginsRoot = pluginsData.pluginsRootAbs)
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

  @ArgsName("channel-directory")
  @HelpMessage("Build the channel by converting all the yaml files to json.")
  final case class BuildChannelOptions() extends Sc4pacCommandOptions

  /** For internal use, convert yaml files to json.
    * Usage: `./sc4pac build-channel ./channel-testing/`
    */
  case object BuildChannel extends Command[BuildChannelOptions] {
    override def hidden = true  // for internal use
    def run(options: BuildChannelOptions, args: RemainingArgs): Unit = {
      args.all match {
        case Seq[String](channelDir) => ChannelUtil.convertYamlToJson(os.Path(channelDir, os.pwd))
        case _ => error(caseapp.core.Error.Other("A single argument is needed: channel-directory"))
      }

    }
  }
}

object CliMain extends caseapp.core.app.CommandsEntryPoint {
  val commands = Seq(Commands.Add, Commands.Update, Commands.Search, Commands.BuildChannel)
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
