package io.github.memo33
package sc4pac
package error

sealed trait Sc4pacErr

final class Sc4pacIoException(msg: String, e: Throwable = null) extends java.io.IOException(msg, e) with Sc4pacErr

final class Sc4pacAbort() extends scala.util.control.ControlThrowable

final class Sc4pacTimeout(msg: String) extends java.io.IOException(msg) with Sc4pacErr

final class Sc4pacNotInteractive(msg: String) extends java.io.IOException(msg) with Sc4pacErr

final class Sc4pacPublishWarning(msg: String) extends java.io.IOException(msg) with Sc4pacErr

final class Sc4pacAssetNotFound(val title: String, val detail: String) extends java.io.IOException(s"$title $detail") with Sc4pacErr

final class Sc4pacVersionNotFound(val title: String, val detail: String) extends java.io.IOException(s"$title $detail") with Sc4pacErr

final class Sc4pacMissingVariant(val packageData: JsonData.Package, msg: String) extends scala.util.control.ControlThrowable(msg)

final class UnsatisfiableVariantConstraints(val title: String, val detail: String) extends java.io.IOException(s"$title $detail") with Sc4pacErr

final class ExtractionFailed(val title: String, val detail: String) extends java.io.IOException(s"$title $detail") with Sc4pacErr

final class DownloadFailed(val title: String, val detail: String) extends java.io.IOException(s"$title $detail") with Sc4pacErr

final class ChecksumError(val title: String, val detail: String) extends java.io.IOException(s"$title $detail") with Sc4pacErr

final class NoChannelsAvailable(val title: String, val detail: String) extends java.io.IOException(s"$title $detail") with Sc4pacErr

final class SymlinkCreationFailed(msg: String) extends java.io.IOException(msg) with Sc4pacErr

final class FileOpsFailure(msg: String) extends java.io.IOException(msg) with Sc4pacErr

final class YamlFormatIssue(msg: String) extends java.io.IOException(msg) with Sc4pacErr

final class PortOccupied(msg: String) extends java.io.IOException(msg) with Sc4pacErr
