package io.github.memo33
package sc4pac
package api

import upickle.default as UP

import sc4pac.JsonData as JD
import JD.bareModuleRw

sealed trait Message derives UP.ReadWriter

sealed trait PromptMessage extends Message derives UP.ReadWriter {
  def token: String
  def choices: Seq[String]
  def accept(response: ResponseMessage): Boolean = response.token == token && choices.contains(response.body)
}
object PromptMessage {

  private def responsesFromChoices(choices: Seq[String], token: String): Map[String, ResponseMessage] = {
    choices.map(s => s -> ResponseMessage(token, s)).toMap
  }

  @upickle.implicits.key("/prompt/choice/update/variant")
  case class ChooseVariant(
    `package`: BareModule,
    label: String,
    choices: Seq[String],
    descriptions: Map[String, String],
    token: String,
    responses: Map[String, ResponseMessage]
  ) extends PromptMessage
  object ChooseVariant {
    def apply(`package`: BareModule, label: String, choices: Seq[String], descriptions: Map[String, String]): ChooseVariant = {
      val token = scala.util.Random.nextInt().toHexString
      ChooseVariant(`package`, label, choices, descriptions, token, responsesFromChoices(choices, token))
    }
    given chooseVariantRw: UP.ReadWriter[ChooseVariant] = UP.stringKeyRW(UP.macroRW)
  }

  val yesNo = Seq("Yes", "No")
  val yes = yesNo.head

  @upickle.implicits.key("/prompt/confirmation/update/plan")
  case class ConfirmUpdatePlan(
    toRemove: Seq[ConfirmUpdatePlan.Pkg],
    toInstall: Seq[ConfirmUpdatePlan.Pkg],
    choices: Seq[String], // = yesNo,
    token: String,
    responses: Map[String, ResponseMessage]
  ) extends PromptMessage
  object ConfirmUpdatePlan {
    def apply(toRemove: Seq[Pkg], toInstall: Seq[Pkg]): ConfirmUpdatePlan = {
      val token = scala.util.Random.nextInt().toHexString
      ConfirmUpdatePlan(toRemove = toRemove, toInstall = toInstall, choices = yesNo, token = token, responsesFromChoices(yesNo, token))
    }
    given confirmUpdatePlanRw: UP.ReadWriter[ConfirmUpdatePlan] = UP.stringKeyRW(UP.macroRW)
    case class Pkg(`package`: BareModule, version: String, variant: Variant)
    given pkgRw: UP.ReadWriter[Pkg] = UP.stringKeyRW(UP.macroRW)  // serializes Variant as dictionary instead of array
  }

  @upickle.implicits.key("/prompt/confirmation/update/warnings")
  case class ConfirmInstallation(
    warnings: Map[String, Seq[String]],  // String instead of BareModule as keys to facilitate serialization to dictionary instead of array
    choices: Seq[String], // = yesNo,
    token: String,
    responses: Map[String, ResponseMessage]
  ) extends PromptMessage
  object ConfirmInstallation {
    def apply(warnings: Map[BareModule, Seq[String]]): ConfirmInstallation = {
      val token = scala.util.Random.nextInt().toHexString
      ConfirmInstallation(warnings.map((k, v) => (k.orgName, v)), choices = yesNo, token, responses = responsesFromChoices(yesNo, token))
    }
    given confirmInstallation: UP.ReadWriter[ConfirmInstallation] = UP.stringKeyRW(UP.macroRW)
  }
}

@upickle.implicits.key("/prompt/response")
case class ResponseMessage(token: String, body: String) extends Message derives UP.ReadWriter

sealed trait ErrorMessage extends Message derives UP.ReadWriter {
  def title: String
  def detail: String
}
object ErrorMessage {
  @upickle.implicits.key("/error/server-error")
  case class ServerError(title: String, detail: String) extends ErrorMessage derives UP.ReadWriter
  @upickle.implicits.key("/error/scope-not-initialized")
  case class ScopeNotInitialized(title: String, detail: String) extends ErrorMessage derives UP.ReadWriter
  @upickle.implicits.key("/error/version-not-found")
  case class VersionNotFound(title: String, detail: String) extends ErrorMessage derives UP.ReadWriter
  @upickle.implicits.key("/error/asset-not-found")
  case class AssetNotFound(title: String, detail: String) extends ErrorMessage derives UP.ReadWriter
  @upickle.implicits.key("/error/extraction-failed")
  case class ExtractionFailed(title: String, detail: String) extends ErrorMessage derives UP.ReadWriter
  @upickle.implicits.key("/error/variant-constraints-not-satisfiable")
  case class UnsatisfiableVariantConstraints(title: String, detail: String) extends ErrorMessage derives UP.ReadWriter
  @upickle.implicits.key("/error/download-failed")
  case class DownloadFailed(title: String, detail: String) extends ErrorMessage derives UP.ReadWriter
  @upickle.implicits.key("/error/channels-not-available")
  case class NoChannelsAvailable(title: String, detail: String) extends ErrorMessage derives UP.ReadWriter
  @upickle.implicits.key("/error/aborted")
  case class Aborted(title: String, detail: String) extends ErrorMessage derives UP.ReadWriter
  @upickle.implicits.key("/error/bad-request")
  case class BadRequest(title: String, detail: String) extends ErrorMessage derives UP.ReadWriter
  @upickle.implicits.key("/error/package-not-found")
  case class PackageNotFound(title: String, detail: String) extends ErrorMessage derives UP.ReadWriter
  @upickle.implicits.key("/error/init/bad-request")
  case class BadInit(title: String, detail: String, platformDefaults: Map[String, Seq[String]]) extends ErrorMessage
  given badInitRw: UP.ReadWriter[BadInit] = UP.stringKeyRW(UP.macroRW)
  @upickle.implicits.key("/error/init/not-allowed")
  case class InitNotAllowed(title: String, detail: String) extends ErrorMessage derives UP.ReadWriter
}

@upickle.implicits.key("/result")
case class ResultMessage(body: String) extends Message

sealed trait ProgressMessage extends Message derives UP.ReadWriter
object ProgressMessage {
  @upickle.implicits.key("/progress/update/extraction")
  case class Extraction(`package`: BareModule, progress: Sc4pac.Progress) extends ProgressMessage derives UP.ReadWriter

  @upickle.implicits.key("/progress/download/started")
  case class DownloadStarted(url: String) extends ProgressMessage derives UP.ReadWriter

  @upickle.implicits.key("/progress/download/length")
  case class DownloadLength(url: String, length: Long) extends ProgressMessage derives UP.ReadWriter

  @upickle.implicits.key("/progress/download/downloaded")
  case class DownloadDownloaded(url: String, downloaded: Long) extends ProgressMessage derives UP.ReadWriter

  @upickle.implicits.key("/progress/download/finished")
  case class DownloadFinished(url: String, success: Boolean) extends ProgressMessage derives UP.ReadWriter
}

case class InstalledPkg(`package`: BareModule, version: String, variant: Variant, explicit: Boolean)  // for endpoint `list`
object InstalledPkg {
  given installedPkgRw: UP.ReadWriter[InstalledPkg] = UP.stringKeyRW(UP.macroRW)
}

case class SearchResultItem(`package`: BareModule, relevance: Int, description: String) derives UP.ReadWriter
