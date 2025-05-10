package io.github.memo33
package sc4pac
package api

import upickle.default as UP

import sc4pac.JsonData as JD
import JD.{bareModuleRw, instantRw, pathRw, uriRw}

sealed trait Message derives UP.ReadWriter

sealed trait PromptMessage extends Message derives UP.ReadWriter {
  def token: String
  def choices: Seq[String]
  def accept(response: ResponseMessage): Boolean = response.token == token && choices.contains(response.body.str)
}
object PromptMessage {

  private def responsesFromChoices(choices: Seq[String], token: String): Map[String, ResponseMessage] = {
    choices.map(s => s -> ResponseMessage(token, s)).toMap
  }

  @upickle.implicits.key("/prompt/choice/update/variant")
  case class ChooseVariant(
    `package`: BareModule,
    variantId: String,
    choices: Seq[String],
    info: JD.VariantInfo,
    previouslySelectedValue: Option[String],
    importedValues: Seq[String],
    token: String,
    responses: Map[String, ResponseMessage]
  ) extends PromptMessage
  object ChooseVariant {
    def apply(`package`: BareModule, variantId: String, choices: Seq[String], info: JD.VariantInfo, previouslySelectedValue: Option[String], importedValues: Seq[String]): ChooseVariant = {
      val token = scala.util.Random.nextInt().toHexString
      ChooseVariant(`package`, variantId, choices, info, previouslySelectedValue, importedValues, token, responsesFromChoices(choices, token))
    }
    given chooseVariantRw: UP.ReadWriter[ChooseVariant] = UP.stringKeyRW(UP.macroRW)
  }

  val yesNo = Seq("Yes", "No")
  val yes = yesNo.head
  val oneTwoCancel = Seq("1", "2", "Cancel")

  @upickle.implicits.key("/prompt/confirmation/update/remove-unresolvable-packages")
  case class ConfirmRemoveUnresolvablePackages(
    packages: Seq[BareModule],
    choices: Seq[String], // = yesNo,
    token: String,
    responses: Map[String, ResponseMessage]
  ) extends PromptMessage derives UP.ReadWriter
  object ConfirmRemoveUnresolvablePackages {
    def apply(packages: Seq[BareModule]): ConfirmRemoveUnresolvablePackages = {
      val token = scala.util.Random.nextInt().toHexString
      ConfirmRemoveUnresolvablePackages(packages = packages, choices = yesNo, token = token, responsesFromChoices(yesNo, token))
    }
  }

  @upickle.implicits.key("/prompt/choice/update/remove-conflicting-packages")
  case class ChooseToRemoveConflictingPackages(
    conflict: (BareModule, BareModule),
    explicitPackages: (Seq[BareModule], Seq[BareModule]),
    choices: Seq[String],
    token: String,
    responses: Map[String, ResponseMessage],
  ) extends PromptMessage derives UP.ReadWriter
  object ChooseToRemoveConflictingPackages {
    def apply(conflict: (BareModule, BareModule), explicitPackages: (Seq[BareModule], Seq[BareModule])): ChooseToRemoveConflictingPackages = {
      val token = scala.util.Random.nextInt().toHexString
      ChooseToRemoveConflictingPackages(conflict, explicitPackages, choices = oneTwoCancel, token = token, responsesFromChoices(oneTwoCancel, token))
    }
  }

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

  @upickle.implicits.key("/prompt/json/update/initial-arguments")
  case class InitialArgumentsForUpdate(
    choices: Seq[String],  // = "Default"
    token: String,
    responses: Map[String, ResponseMessage],
  ) extends PromptMessage {
    override def accept(response: ResponseMessage): Boolean = response.token == token
  }
  object InitialArgumentsForUpdate {
    def apply(): InitialArgumentsForUpdate = {
      val token = scala.util.Random.nextInt().toHexString
      InitialArgumentsForUpdate(
        choices = Seq("Default"),
        token = token,
        responses = Map("Default" -> ResponseMessage(token, ujson.Obj() /* empty Args() as json */)),
      )
    }

    case class Args(
      importedSelections: Seq[Variant] = Seq.empty,
    ) derives UP.ReadWriter
  }

  @upickle.implicits.key("/prompt/json/update/download-failed-select-mirror")
  case class DownloadFailedSelectMirror(
    url: java.net.URI,
    reason: ErrorMessage.DownloadFailed,
    choices: Seq[String],
    token: String,
    responses: Map[String, ResponseMessage],
  ) extends PromptMessage {
    override def accept(response: ResponseMessage): Boolean = response.token == token
  }
  object DownloadFailedSelectMirror {
    def apply(url: java.net.URI, reason: ErrorMessage.DownloadFailed): DownloadFailedSelectMirror = {
      val token = scala.util.Random.nextInt().toHexString
      DownloadFailedSelectMirror(
        url = url,
        reason = reason,
        choices = Seq("Retry", "Cancel"),
        token = token,
        responses = Map(
          "Retry" -> ResponseMessage(token, ujson.Obj("retry" -> true, "localMirror" -> ujson.Arr())),
          // Select mirror -> retry=true, localMirror=[path]  (must be constructed manually)
          "Cancel" -> ResponseMessage(token, ujson.Obj("retry" -> false)),
        ),
      )
    }

    case class ResponseData(
      retry: Boolean,
      localMirror: Option[java.nio.file.Path] = None,
    ) derives UP.ReadWriter
  }

}

@upickle.implicits.key("/prompt/response")
case class ResponseMessage(token: String, body: ujson.Value) extends Message derives UP.ReadWriter

// externalIds are not really needed for this interface, as there's no limit on
// the number of packages (unlike the limit for custom sc4pac:// URLs)
@upickle.implicits.key("/prompt/open/package")
case class OpenPackageMessage(packages: Seq[OpenPackageMessage.Item]) extends Message derives UP.ReadWriter
object OpenPackageMessage {
  case class Item(`package`: BareModule, channelUrl: String) derives UP.ReadWriter
}

sealed trait ErrorMessage extends Message derives UP.ReadWriter {
  def title: String
  def detail: String
}
object ErrorMessage {
  @upickle.implicits.key("/error/server-error")
  case class ServerError(title: String, detail: String) extends ErrorMessage derives UP.ReadWriter
  @upickle.implicits.key("/error/profile-not-initialized")
  case class ProfileNotInitialized(title: String, detail: String, platformDefaults: Map[String, Seq[String]]) extends ErrorMessage derives UP.ReadWriter
  @upickle.implicits.key("/error/profile-read-error")
  case class ReadingProfileFailed(title: String, detail: String) extends ErrorMessage derives UP.ReadWriter
  @upickle.implicits.key("/error/version-not-found")
  case class VersionNotFound(title: String, detail: String) extends ErrorMessage derives UP.ReadWriter
  @upickle.implicits.key("/error/unresolvable-dependencies")
  case class UnresolvableDependencies(title: String, detail: String) extends ErrorMessage derives UP.ReadWriter
  @upickle.implicits.key("/error/conflicting-packages")
  case class ConflictingPackages(title: String, detail: String, conflict: (BareModule, BareModule)) extends ErrorMessage derives UP.ReadWriter
  @upickle.implicits.key("/error/asset-not-found")
  case class AssetNotFound(title: String, detail: String) extends ErrorMessage derives UP.ReadWriter
  @upickle.implicits.key("/error/extraction-failed")
  case class ExtractionFailed(title: String, detail: String) extends ErrorMessage derives UP.ReadWriter
  @upickle.implicits.key("/error/variant-constraints-not-satisfiable")
  case class UnsatisfiableVariantConstraints(title: String, detail: String) extends ErrorMessage derives UP.ReadWriter
  @upickle.implicits.key("/error/download-failed")
  case class DownloadFailed(title: String, detail: String) extends ErrorMessage derives UP.ReadWriter
  @upickle.implicits.key("/error/channels-not-available")
  case class ChannelsNotAvailable(title: String, detail: String) extends ErrorMessage derives UP.ReadWriter
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
  @upickle.implicits.key("/error/file-access-denied")
  case class FileAccessDenied(title: String, detail: String) extends ErrorMessage derives UP.ReadWriter
  @upickle.implicits.key("/error/published-files-incomplete")
  case class PublishedFilesIncomplete(title: String, detail: String) extends ErrorMessage derives UP.ReadWriter
  @upickle.implicits.key("/error/obtaining-user-dirs-failed")
  case class ObtainingUserDirsFailed(title: String, detail: String) extends ErrorMessage derives UP.ReadWriter
}

@upickle.implicits.key("/result")
case class ResultMessage(ok: Boolean) extends Message

sealed trait ProgressMessage extends Message derives UP.ReadWriter
object ProgressMessage {
  @upickle.implicits.key("/progress/update/extraction")
  case class Extraction(`package`: BareModule, progress: Sc4pac.Progress) extends ProgressMessage derives UP.ReadWriter

  @upickle.implicits.key("/progress/download/started")
  case class DownloadStarted(url: String) extends ProgressMessage derives UP.ReadWriter

  @upickle.implicits.key("/progress/download/length")
  case class DownloadLength(url: String, length: Long) extends ProgressMessage derives UP.ReadWriter

  @upickle.implicits.key("/progress/download/intermediate")
  case class DownloadIntermediate(url: String, downloaded: Long) extends ProgressMessage derives UP.ReadWriter

  @upickle.implicits.key("/progress/download/finished")
  case class DownloadFinished(url: String, success: Boolean) extends ProgressMessage derives UP.ReadWriter
}

case class InstalledPkg(`package`: BareModule, version: String, variant: Variant, explicit: Boolean)  // for endpoint `list`
object InstalledPkg {
  given installedPkgRw: UP.ReadWriter[InstalledPkg] = UP.stringKeyRW(UP.macroRW)
}

// `installed` may be null
case class InstalledStatus(explicit: Boolean, installed: InstalledStatus.Installed = null) derives UP.ReadWriter
object InstalledStatus {
  case class Installed(version: String, variant: Variant, installedAt: java.time.Instant, updatedAt: java.time.Instant)
  given installedRw: UP.ReadWriter[Installed] = UP.stringKeyRW(UP.macroRW)
}

case class FindPackagesArgs(packages: Seq[BareModule], externalIds: Seq[(String, String)] = Seq.empty) derives UP.ReadWriter

case class PluginsSearchResult(stats: JD.Channel.Stats, packages: Seq[PluginsSearchResultItem]) derives UP.ReadWriter

// `status` is not null
case class PluginsSearchResultItem(`package`: BareModule, relevance: Int, summary: String, status: InstalledStatus) derives UP.ReadWriter

// `status` may be null
case class PackageSearchResultItem(`package`: BareModule, relevance: Int, summary: String, status: InstalledStatus = null) derives UP.ReadWriter

case class PackagesSearchResult(packages: Seq[PackageSearchResultItem], notFoundPackageCount: Int = 0, notFoundExternalIdCount: Int = 0, stats: JD.Channel.Stats = null) derives UP.ReadWriter

case class PackageInfo(local: PackageInfo.Local, remote: JD.Package) derives UP.ReadWriter
object PackageInfo {
  case class Local(statuses: Map[BareModule, InstalledStatus]) derives UP.ReadWriter
}

case class ChannelContentsItem(`package`: BareModule, version: String, summary: String, category: Option[String]) derives UP.ReadWriter

// channelLabel may be null
case class ChannelStatsItem(url: String, channelLabel: String, stats: JD.Channel.Stats) derives UP.ReadWriter
case class ChannelStatsAll(combined: JD.Channel.Stats, channels: Seq[ChannelStatsItem]) derives UP.ReadWriter

case class InitArgs(plugins: String, cache: String, temp: String) derives UP.ReadWriter

case class ServerStatus(sc4pacVersion: String) derives UP.ReadWriter

case class ProfileName(name: String) derives UP.ReadWriter
case class ProfileIdObj(id: ProfileId) derives UP.ReadWriter
case class ProfilesList(profiles: Seq[JD.ProfileData], currentProfileId: Option[ProfileId], profilesDir: java.nio.file.Path) derives UP.ReadWriter

case class VariantsList(variants: Map[String, VariantsList.Item])
object VariantsList {
  given variantsListRw: UP.ReadWriter[VariantsList] = UP.stringKeyRW(UP.macroRW)
  case class Item(value: String, unused: Boolean) derives UP.ReadWriter
}
