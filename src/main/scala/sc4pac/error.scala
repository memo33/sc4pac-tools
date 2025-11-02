package io.github.memo33
package sc4pac
package error

sealed trait Sc4pacErr

final class Sc4pacIoException(msg: String, e: Throwable = null) extends java.io.IOException(msg, e) with Sc4pacErr

final class Sc4pacAbort() extends scala.util.control.ControlThrowable

final class Sc4pacTimeout(msg: String) extends java.io.IOException(msg) with Sc4pacErr

final class Sc4pacNotInteractive(msg: String) extends java.io.IOException(msg) with Sc4pacErr

final class Sc4pacPublishIncomplete(val title: String, val detail: String) extends java.io.IOException(s"$title $detail") with Sc4pacErr

final class Sc4pacAssetNotFound(val title: String, val detail: String) extends java.io.IOException(s"$title $detail") with Sc4pacErr

final class Sc4pacVersionNotFound(val title: String, val detail: String, val dep: BareDep) extends java.io.IOException(s"$title $detail") with Sc4pacErr

final class UnresolvableDependencies(val title: String, val detail: String, val deps: Seq[BareDep]) extends java.io.IOException(s"$title $detail") with Sc4pacErr

final class ConflictingPackages(val title: String, val detail: String, val conflict: (BareModule, BareModule), val explicitPackages1: Seq[BareModule], val explicitPackages2: Seq[BareModule]) extends java.io.IOException(s"$title $detail") with Sc4pacErr

final class Sc4pacMissingVariant(val packageData: JsonData.Package, msg: String) extends scala.util.control.ControlThrowable(msg)

final class UnsatisfiableVariantConstraints(val title: String, val detail: String) extends java.io.IOException(s"$title $detail") with Sc4pacErr

final class ExtractionFailed(val title: String, val detail: String) extends java.io.IOException(s"$title $detail") with Sc4pacErr {
  def withDetail(newDetail: String): ExtractionFailed = ExtractionFailed(title, newDetail)
}

final class DownloadFailed(val title: String, val detail: String, val url: Option[java.net.URI], val promptForSimtropolisToken: Boolean = false) extends java.io.IOException(s"$title $detail") with Sc4pacErr

final class ChecksumError(val title: String, val detail: String) extends java.io.IOException(s"$title $detail") with Sc4pacErr

final class NotADbpfFile(val title: String, val detail: String) extends java.io.IOException(s"$title $detail") with Sc4pacErr

final class ChannelsNotAvailable(val title: String, val detail: String) extends java.io.IOException(s"$title $detail") with Sc4pacErr

final class ReadingProfileFailed(val title: String, val detail: String) extends java.io.IOException(s"$title $detail") with Sc4pacErr

final class SymlinkCreationFailed(msg: String) extends java.io.IOException(msg) with Sc4pacErr

final class FileOpsFailure(msg: String) extends java.io.IOException(msg) with Sc4pacErr

final class YamlFormatIssue(msg: String) extends java.io.IOException(msg) with Sc4pacErr

final class PortOccupied(msg: String) extends java.io.IOException(msg) with Sc4pacErr

final class ObtainingUserDirsFailed(val title: String, val detail: String) extends java.io.IOException(s"$title $detail") with Sc4pacErr

// derived from coursier.cache.ArtifactError
sealed abstract class Artifact2Error(val message: String, val parentOpt: Option[Throwable]) extends Exception(message, parentOpt.orNull)
object Artifact2Error {
  final class RateLimited(val url: String) extends Artifact2Error(s"Rate limited: $url", None)
  final class NotFound(val url: String) extends Artifact2Error(s"Not found: $url", None)
  final class Forbidden(val url: String) extends Artifact2Error(s"Forbidden: $url", None)
  final class Unauthorized(val url: String) extends Artifact2Error(s"Unauthorized: $url", None)
  final class DownloadError(val reason: String, e: Option[Throwable]) extends Artifact2Error(reason, e)
  final class Locked(val file: java.io.File) extends Artifact2Error(s"File is locked: $file", None)
  final class WrongLength(val got: Long, val expected: Long, val file: String) extends Artifact2Error(s"Wrong length: $file (expected $expected B, got $got B)", None)
  final class ChecksumNotFound(val sumType: String, val file: String) extends Artifact2Error(s"Checksum not found: $file", None)
  final class ChecksumFormatError(val sumType: String, val file: String) extends Artifact2Error(s"Checksum format error: $file", None)
  final class WrongChecksum(val sumType: String, val got: String, val expected: String, val file: String, val sumFile: String) extends Artifact2Error(s"Wrong checksum: $file (expected $sumType $expected in $sumFile, got $got)", None)
  final class JsonFormatError(val reason: String, e: Throwable) extends Artifact2Error(s"$reason - ${e.getMessage}", Some(e))
}
