package sc4pac
package cli

import caseapp.{Command, RemainingArgs, ArgsName, HelpMessage}
import zio.{ZIO, Task}

import sc4pac.Data.PluginsData

// see https://github.com/coursier/coursier/blob/main/modules/cli/src/main/scala/coursier/cli/Coursier.scala
// and related files

sealed abstract class Sc4pacCommandOptions extends Product with Serializable

@ArgsName("packages")
@HelpMessage(f"Add packages to the list of explicitly installed packages.%nPackage format: <group>:<package-name>")
final case class AddOptions(
  // @Group("foo")
  // @Tag("foo")
  // @ExtraName("V")
  // @ValueDescription("stages")
  // @HelpMessage("Set a value")
  // aa: String
) extends Sc4pacCommandOptions

// @ArgsName("packages")
@HelpMessage("Update all installed packages to their latest version and install any missing packages.")
final case class UpdateOptions(
  // bb: String
) extends Sc4pacCommandOptions

@ArgsName("channel-directory")
@HelpMessage("Build the channel by converting all the yaml files to json.")
final case class BuildChannelOptions() extends Sc4pacCommandOptions

object Commands {

  private def runMainExit(task: Task[Unit], exit: Int => Nothing): Nothing = {
    unsafeRun(task.fold(
      failure = {
        case abort: sc4pac.error.Sc4pacAbort => { Console.err.println(Seq("Operation aborted.", abort.msg).mkString(" ")); exit(1) }
        case e => { e.printStackTrace(); exit(1) }
      },
      success = _ => exit(0)
    ))
  }

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
  val commands = Seq(Commands.Add, Commands.Update, Commands.BuildChannel)
  val progName = BuildInfo.name
  override val description = s"  A package manager for SimCity 4 plugins. Version ${BuildInfo.version}."
}
