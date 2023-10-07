package io.github.memo33.sc4pac
package web

import scala.scalajs.js
// import scala.scalajs.js.annotation.JSExportTopLevel
import scala.scalajs.js.annotation.JSGlobal
import org.scalajs.dom
import org.scalajs.dom.document

import scala.concurrent.Future
import concurrent.ExecutionContext.Implicits.global

import upickle.default as UP

import sttp.client4.{basicRequest, Request, UriContext, Response, ResponseException}
import sttp.client4.upicklejson.asJson

object Hello {

  val channelUrl = "http://localhost:8090/"

  lazy val backend = sttp.client4.fetch.FetchBackend()

  case class Dummy(group: String, name: String, version: String) derives UP.ReadWriter

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

  def fetchPackage(module: BareModule): Future[Option[Dummy]] = {
    val url = sttp.model.Uri(java.net.URI.create(s"${channelUrl}${JsonRepoUtil.packageSubPath(module, version = "latest")}"))
    for {
      response <- basicRequest.get(url).response(asJson[Dummy]).send(backend)
    } yield {
      if (!response.is200)
        None
      else response.body.toOption
    }
  }

  def setupUI(): Unit = {

    // val response: Future[Response[Either[ResponseException[String, Exception], Dummy]]] =
    //   basicRequest
    //     .get(uri"http://localhost:8090/metadata/memo/demo-package/1.0/demo-package-1.0.json")
    //     .response(asJson[Dummy])
    //     .send(backend)

    // response.foreach(resp => {
    //   println(resp.code)
    //   println(resp.body)
    // })

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
      case Right(module) =>
        appendPar(document.body, s"Loading package ${module.orgName}...")
        fetchPackage(module) foreach {
          case None =>
            appendPar(document.body, "Package: not found")
          case Some(pkg) =>
            appendPar(document.body, pkg.toString)
        }
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
