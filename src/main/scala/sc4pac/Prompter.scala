package io.github.memo33
package sc4pac

import zio.{ZIO, Task, UIO}
import sc4pac.Resolution.DepModule

trait Prompter {

  /** Returns the selected variant value. */
  def promptForVariant(module: BareModule, label: String, values: Seq[String], descriptions: Map[String, String]): Task[String]

  def confirmUpdatePlan(plan: Sc4pac.UpdatePlan): Task[Boolean]

  def confirmInstallationWarnings(warnings: Seq[(BareModule, Seq[String])]): Task[Boolean]
}

class CliPrompter(logger: CliLogger) extends Prompter {

  def promptForVariant(module: BareModule, label: String, values: Seq[String], descriptions: Map[String, String]): Task[String] = {
    val prefix = s"${label} = "
    val columnWidth = values.map(_.length).max + 8  // including some whitespace for separation, excluding prefix
    def renderDesc(value: String): String = descriptions.get(value) match {
      case None => prefix + value
      case Some(desc) => prefix + value + (" " * ((columnWidth - value.length) max 0)) + desc
    }
    Prompt.ifInteractive(
      onTrue = Prompt.numbered(s"""Choose a variant for ${module.orgName}:""", values, render = renderDesc),
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
      if (plan.isUpToDate) ZIO.succeed(true)
      else Prompt.ifInteractive(
        onTrue = Prompt.yesNo("Continue?"),
        onFalse = ZIO.succeed(true))  // in non-interactive mode, always continue
    }
  }

  def confirmInstallationWarnings(warnings: Seq[(BareModule, Seq[String])]): Task[Boolean] = {
    if (warnings.isEmpty) ZIO.succeed(true)
    else Prompt.ifInteractive(
      onTrue = Prompt.yesNo("Continue despite warnings?"),
      onFalse = ZIO.succeed(true))  // in non-interactive mode, we continue despite warnings
  }
}
