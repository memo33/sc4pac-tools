package io.github.memo33
package sc4pac
package error

sealed trait Sc4pacErr

final class Sc4pacIoException(msg: String, e: Throwable = null) extends java.io.IOException(msg, e) with Sc4pacErr

class Sc4pacAbort(val msg: String = "") extends scala.util.control.ControlThrowable

final class Sc4pacMissingVariant(val packageData: Data.PackageData, msg: String) extends scala.util.control.ControlThrowable(msg)
