package io.github.memo33
package sc4pac

import zio.{ZIO, Task, UIO}
import sc4pac.Resolution.DepModule
import JsonData as JD

trait Prompter {

  /** Returns the selected variant value. */
  def promptForVariant(module: BareModule, variantId: String, values: Seq[String], info: JD.VariantInfo, previouslySelectedValue: Option[String], importedValues: Seq[String]): Task[String]

  def confirmRemovingUnresolvableExplicitPackages(modules: Seq[BareModule]): Task[Boolean]

  def chooseToRemoveConflictingExplicitPackages(conflict: (BareModule, BareModule), explicitPackages: (Seq[BareModule], Seq[BareModule])): Task[Option[Seq[BareModule]]]

  def confirmUpdatePlan(plan: Sc4pac.UpdatePlan): Task[Boolean]

  def promptForDownloadMirror(url: String, reason: error.DownloadFailed): Task[Either[Boolean, os.Path]]

  def confirmInstallationWarnings(warnings: Seq[(BareModule, Seq[String])]): Task[Boolean]
}

class CliPrompter(logger: CliLogger, autoYes: Boolean) extends Prompter {

  def withAutoYes(yes: Boolean): CliPrompter = CliPrompter(logger, yes)

  def promptForVariant(module: BareModule, variantId: String, values: Seq[String], info: JD.VariantInfo, previouslySelectedValue: Option[String], importedValues: Seq[String]): Task[String] = {
    val prefix = s"${variantId} = "
    val columnWidth = values.map(_.length).max + 8  // including some whitespace for separation, excluding prefix
    def renderDesc(value: String): String = {
      val desc: String = Seq[Option[String]](
        previouslySelectedValue.filter(_ == value).map(_ => logger.cyanBold("[previous]")),
        Option.when(importedValues.contains(value))(logger.cyanBold("[imported]")),
        info.valueDescriptions.get(value),
      ).flatten.mkString(" ")
      if (desc.nonEmpty) {
        prefix + value + (" " * ((columnWidth - value.length) max 0)) + desc
      } else {
        prefix + value
      }
    }
    val pretext = if (info.description.nonEmpty) f"%n%n${logger.applyMarkdown(info.description)}" else ""
    Prompt.ifInteractive(
      onTrue = Prompt.numbered(
        s"""Choose a variant for ${module.formattedDisplayString(logger.gray, logger.bold)}:$pretext""",
        values,
        render = renderDesc,
        default = info.default,
      ),
      onFalse = ZIO.fail(new error.Sc4pacNotInteractive(s"""Configure a "${variantId}" variant for ${module.orgName}: ${values.mkString(", ")}""")))
  }

  private def logPackages(msg: String, dependencies: Iterable[DepModule | BareModule]): Unit = {
    logger.log(msg + dependencies.iterator.map {
      case m: DepModule => m.formattedDisplayString(logger.gray)
      case m: BareModule => m.formattedDisplayString(logger.gray, logger.bold)
    }.toSeq.sorted.mkString(f"%n"+" "*4, f"%n"+" "*4, f"%n"))
  }

  private def logPlan(plan: Sc4pac.UpdatePlan): UIO[Unit] = ZIO.succeed {
    if (plan.toRemove.nonEmpty) logPackages(f"The following packages will be removed:%n", plan.toRemove.collect{ case d: DepModule => d })
    // if (plan.toReinstall.nonEmpty) logPackages(f"The following packages will be reinstalled:%n", plan.toReinstall.collect{ case d: DepModule => d })
    if (plan.toInstall.nonEmpty) logPackages(f"The following packages will be installed:%n", plan.toInstall.collect{ case d: DepModule => d })
    if (plan.isUpToDate) logger.log("Everything is up-to-date.")
  }

  def confirmRemovingUnresolvableExplicitPackages(modules: Seq[BareModule]): Task[Boolean] = {
    logPackages(f"The following packages could not be resolved. Maybe they have been renamed or deleted from the corresponding channel.%n", modules)
    // we don't use autoYes, as removing explicit packages would be unexpected and should be handled manually
    Prompt.ifInteractive(
      onTrue = Prompt.yesNo("Do you want to remove these unresolvable packages from your Plugins?"),
      onFalse = ZIO.succeed(false),  // in non-interactive mode, error out
    )
  }

  def chooseToRemoveConflictingExplicitPackages(conflict: (BareModule, BareModule), explicitPackages: (Seq[BareModule], Seq[BareModule])): Task[Option[Seq[BareModule]]] = {
    logger.log(
      f"The packages ${conflict._1.formattedDisplayString(logger.gray, logger.bold)} and ${conflict._2.formattedDisplayString(logger.gray, logger.bold)}"
      + " are in conflict with each other and cannot be installed at the same time."
      + f" Decide which of the two packages you want to keep; uninstall the other and all packages that depend on it.%n"
    )
    logPackages("  (1)", explicitPackages._1)
    logPackages("  (2)", explicitPackages._2)
    Prompt.ifInteractive(
      onTrue = Prompt.choice("Do you want to remove these conflicting packages from your Plugins?", Seq("1", "2", "No"), default = Some("No"))
        .map {
          case "1" => Some(explicitPackages._1)
          case "2" => Some(explicitPackages._2)
          case _ => None
        },
      onFalse = ZIO.succeed(None),  // in non-interactive mode, error out
    )
  }

  def confirmUpdatePlan(plan: Sc4pac.UpdatePlan): Task[Boolean] = {
    logPlan(plan).zipRight {
      if (plan.isUpToDate || autoYes) ZIO.succeed(true)  // if --yes flag is present, always continue
      else Prompt.ifInteractive(
        onTrue = Prompt.yesNo("Continue?"),
        onFalse = ZIO.succeed(true))  // in non-interactive mode, always continue
    }
  }

  /** Returns either retry=true/false or a local fallback file. */
  def promptForDownloadMirror(url: String, reason: error.DownloadFailed): Task[Either[Boolean, os.Path]] = {
    logger.warn(reason.getMessage)
    val choices = Seq("Retry", "Select a file from disk to use instead", "Cancel")
    val pretext = f"%n  $url%n%nThe download of this file failed. Choose what to do."
    Prompt.ifInteractive(
      onFalse = ZIO.succeed(Left(false)),  // error out
      onTrue = Prompt.numbered(pretext, choices, default = Some(choices.last))
        .flatMap { s =>
          if (s == choices(1))
            Prompt.pathInput("Enter a path: ")
              .flatMap { path =>
                ZIO.ifZIO(ZIO.attemptBlockingIO(os.exists(path)))(
                  onTrue = ZIO.succeed(Some(path)),
                  onFalse = ZIO.succeed { logger.log("File does not exist."); None },
                )
              }
              .repeatWhile(_.isEmpty)
              .map(pathOpt => Right(pathOpt.get))
          else ZIO.succeed(Left(s == choices(0)))
        },
    )
  }

  def confirmInstallationWarnings(warnings: Seq[(BareModule, Seq[String])]): Task[Boolean] = {
    if (warnings.isEmpty || autoYes) ZIO.succeed(true)  // if --yes flag is present, always continue
    else Prompt.ifInteractive(
      onTrue = Prompt.yesNo("Continue despite warnings?"),
      onFalse = ZIO.succeed(true))  // in non-interactive mode, we continue despite warnings
  }
}
