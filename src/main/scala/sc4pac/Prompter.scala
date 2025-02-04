package io.github.memo33
package sc4pac

import zio.{ZIO, Task, UIO}
import sc4pac.Resolution.DepModule
import JsonData as JD

trait Prompter {

  /** Returns the selected variant value. */
  def promptForVariant(module: BareModule, label: String, values: Seq[String], info: JD.VariantInfo): Task[String]

  def confirmUpdatePlan(plan: Sc4pac.UpdatePlan): Task[Boolean]

  def confirmInstallationWarnings(warnings: Seq[(BareModule, Seq[String])]): Task[Boolean]
}

class CliPrompter(logger: CliLogger, autoYes: Boolean) extends Prompter {

  def withAutoYes(yes: Boolean): CliPrompter = CliPrompter(logger, yes)

  def promptForVariant(module: BareModule, label: String, values: Seq[String], info: JD.VariantInfo): Task[String] = {
    val prefix = s"${label} = "
    val columnWidth = values.map(_.length).max + 8  // including some whitespace for separation, excluding prefix
    def renderDesc(value: String): String = info.valueDescriptions.get(value) match {
      case Some(desc) if desc.nonEmpty => prefix + value + (" " * ((columnWidth - value.length) max 0)) + desc
      case _ => prefix + value
    }
    val pretext = if (info.description.nonEmpty) f"%n%n${logger.applyMarkdown(info.description)}" else ""
    Prompt.ifInteractive(
      onTrue = Prompt.numbered(s"""Choose a variant for ${module.formattedDisplayString(logger.gray, logger.bold)}:$pretext""", values, render = renderDesc),
      onFalse = ZIO.fail(new error.Sc4pacNotInteractive(s"""Configure a "${label}" variant for ${module.orgName}: ${values.mkString(", ")}""")))
  }

  private def logPackages(msg: String, dependencies: Iterable[DepModule]): Unit = {
    logger.log(msg + dependencies.iterator.map(_.formattedDisplayString(logger.gray)).toSeq.sorted.mkString(f"%n"+" "*4, f"%n"+" "*4, f"%n"))
  }

  private def logPlan(plan: Sc4pac.UpdatePlan): UIO[Unit] = ZIO.succeed {
    if (plan.toRemove.nonEmpty) logPackages(f"The following packages will be removed:%n", plan.toRemove.collect{ case d: DepModule => d })
    // if (plan.toReinstall.nonEmpty) logPackages(f"The following packages will be reinstalled:%n", plan.toReinstall.collect{ case d: DepModule => d })
    if (plan.toInstall.nonEmpty) logPackages(f"The following packages will be installed:%n", plan.toInstall.collect{ case d: DepModule => d })
    if (plan.isUpToDate) logger.log("Everything is up-to-date.")
  }

  def confirmUpdatePlan(plan: Sc4pac.UpdatePlan): Task[Boolean] = {
    logPlan(plan).zipRight {
      if (plan.isUpToDate || autoYes) ZIO.succeed(true)  // if --yes flag is present, always continue
      else Prompt.ifInteractive(
        onTrue = Prompt.yesNo("Continue?"),
        onFalse = ZIO.succeed(true))  // in non-interactive mode, always continue
    }
  }

  def confirmInstallationWarnings(warnings: Seq[(BareModule, Seq[String])]): Task[Boolean] = {
    if (warnings.isEmpty || autoYes) ZIO.succeed(true)  // if --yes flag is present, always continue
    else Prompt.ifInteractive(
      onTrue = Prompt.yesNo("Continue despite warnings?"),
      onFalse = ZIO.succeed(true))  // in non-interactive mode, we continue despite warnings
  }
}
