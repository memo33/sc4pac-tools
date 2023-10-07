package io.github.memo33.sc4pac
package web

import scala.scalajs.js
// import scala.scalajs.js.annotation.JSExportTopLevel
import scala.scalajs.js.annotation.JSGlobal
import org.scalajs.dom
import org.scalajs.dom.document

object Hello {

  def appendPar(targetNode: dom.Node, text: String): Unit = {
    val parNode = document.createElement("p")
    parNode.textContent = text
    targetNode.appendChild(parNode)
  }

  // @JSExportTopLevel("addClickedMessage")
  def addClickedMessage(): Unit = {
    appendPar(document.body, "You clicked the button!")
  }

  def main(args: Array[String]): Unit = {
    document.addEventListener("DOMContentLoaded", (e: dom.Event) => setupUI())
  }

  val regexModule = """([^:\s]+):([^:\s]+)""".r
  def parseModule(pkgName: String): Either[String, BareModule] =
    pkgName match {
      case regexModule(group, name) => Right(BareModule(group = Organization(group), name = ModuleName(name)))
      case _ => Left("malformed package name: <group>:<name>")
    }

  def setupUI(): Unit = {
    val button = document.createElement("button")
    button.textContent = "Click me!"
    button.addEventListener("click", (e: dom.MouseEvent) => addClickedMessage())
    document.body.appendChild(button)

    appendPar(document.body, "Hello World")

    val urlParams = new dom.URLSearchParams(dom.window.location.search)
    val pkgName = urlParams.get("pkg")
    if (pkgName == null) {
      appendPar(document.body, s"Package: pass query pkg=<group>:<name>")
    } else parseModule(pkgName) match {
      case Left(err) => appendPar(document.body, s"Package: $err")
      case Right(mod) => appendPar(document.body, s"Package: $mod")
    }
  }
}

/*
import org.scalajs.dom
import org.scalajs.dom.document
import zio.*
import zio.Clock.*

object Hello extends zio.ZIOAppDefault {

  def run = {
    for {
      _      <- Console.printLine("Starting progress bar demo.")
      target <- ZIO.succeed(document.createElement("pre"))
      _      <- ZIO.succeed(document.body.appendChild(target))
      _      <- update(target).repeat(Schedule.spaced(1.seconds))
    } yield ExitCode.success
  }

  def update(target: dom.Element) = {
    for {
      time   <- currentTime(java.util.concurrent.TimeUnit.SECONDS)
      output <- ZIO.succeed(progress((time % 11).toInt, 10))
      _      <- ZIO.succeed(target.innerHTML = output)
    } yield ()
  }

  def progress(tick: Int, size: Int) = {
    val bar_length = tick
    val empty_length = size - tick
    val bar = "#" * bar_length + " " * empty_length
    s"$bar $bar_length%"
  }
}
*/
