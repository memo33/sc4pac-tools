package io.github.memo33
package sc4pac
package api

import upickle.default as UP

import sc4pac.JsonData as JD
import JD.bareModuleRw

sealed trait Message derives UP.ReadWriter

sealed trait PromptMessage extends Message derives UP.ReadWriter {
  def token: String
}
object PromptMessage {

  @upickle.implicits.key("/prompt/choice/update/variant")
  case class ChooseVariant(
    `package`: BareModule,
    label: String,
    choices: Seq[String],
    descriptions: Map[String, String],
    token: String = scala.util.Random.nextInt().toHexString
  ) extends PromptMessage derives UP.ReadWriter

  val yesNo = Seq("Yes", "No")
  val yes = yesNo.head

  @upickle.implicits.key("/prompt/confirmation/update/plan")
  case class ConfirmUpdatePlan(
    toRemove: Seq[ConfirmUpdatePlan.Pkg],
    toInstall: Seq[ConfirmUpdatePlan.Pkg],
    choices: Seq[String], // = yesNo,
    token: String = scala.util.Random.nextInt().toHexString
  ) extends PromptMessage derives UP.ReadWriter
  object ConfirmUpdatePlan {
    case class Pkg(`package`: BareModule, version: String, variant: Variant)
    given pkgRw: UP.ReadWriter[Pkg] = UP.stringKeyRW(UP.macroRW)  // serializes Variant as dictionary instead of array
  }

  @upickle.implicits.key("/prompt/confirmation/update/warnings")
  case class ConfirmInstallation(
    warnings: Map[BareModule, Seq[String]],
    choices: Seq[String], // = yesNo,
    token: String = scala.util.Random.nextInt().toHexString
  ) extends PromptMessage derives UP.ReadWriter
}

@upickle.implicits.key("/prompt/response")
case class ResponseMessage(token: String, body: String) extends Message derives UP.ReadWriter

sealed trait ErrorMessage extends Message derives UP.ReadWriter {
  def message: String
  def detail: String
}
object ErrorMessage {
  @upickle.implicits.key("/error/generic")
  case class Generic(message: String, detail: String) extends ErrorMessage derives UP.ReadWriter
  @upickle.implicits.key("/error/scope-not-initialized")
  case class ScopeNotInitialized(message: String, detail: String) extends ErrorMessage derives UP.ReadWriter
}

@upickle.implicits.key("/result")
case class ResultMessage(result: String) extends Message
