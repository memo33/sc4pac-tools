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

  case class ChooseVariant(
    `package`: BareModule,
    label: String,
    choices: Seq[String],
    descriptions: Map[String, String],
    token: String = scala.util.Random.nextInt().toHexString,
  ) extends PromptMessage derives UP.ReadWriter

  case class ConfirmInstallation(
    warnings: Map[BareModule, Seq[String]],
    choices: Seq[String], // = Seq("yes", "no"),
    token: String = scala.util.Random.nextInt().toHexString,
  ) extends PromptMessage derives UP.ReadWriter
}

case class ResponseMessage(token: String, body: String) extends Message derives UP.ReadWriter

sealed trait ErrorMessage extends Message derives UP.ReadWriter {
  def message: String
  def detail: String
}
object ErrorMessage {
  case class Generic(message: String, detail: String) extends ErrorMessage derives UP.ReadWriter
  case class ScopeNotInitialized(message: String, detail: String) extends ErrorMessage derives UP.ReadWriter
}

case class ResultMessage(result: String) extends Message
