package io.github.memo33
package sc4pac
package api

import upickle.default as UP

sealed trait Message

trait PromptMessage extends Message {
  def token: String
}
object PromptMessage {
  case class ChooseVariant(
    `package`: BareModule,
    label: String,
    choices: Seq[String],
    descriptions: Map[String, String],
    token: String = scala.util.Random.nextInt().toHexString,
  ) extends PromptMessage

  case class ConfirmInstallation(
    warnings: Map[BareModule, Seq[String]],
    choices: Seq[String], // = Seq("yes", "no"),
    token: String = scala.util.Random.nextInt().toHexString,
  ) extends PromptMessage
}

case class ResponseMessage(token: String, body: String) extends Message
object ResponseMessage {
  // given responseMessageRw: UP.ReadWriter[ResponseMessage] = ???  // UP.macroRW
}

trait ErrorMessage extends Message {
  def message: String
  def detail: String
}

case class ErrorGeneric(message: String, detail: String) extends ErrorMessage derives UP.ReadWriter

case class ScopeNotInitialized(message: String, detail: String) extends ErrorMessage derives UP.ReadWriter
