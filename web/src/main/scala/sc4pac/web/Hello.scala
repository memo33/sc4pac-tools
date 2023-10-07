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

import scalatags.JsDom.all as H  // html tags
import scalatags.JsDom.all.stringFrag
import scalatags.JsDom.all.stringAttr
import scalatags.JsDom.all.SeqFrag

object JsonData extends SharedData {
  opaque type Instant = String
  val instantRw = UP.readwriter[String]
  opaque type SubPath = String
  val subPathRw = UP.readwriter[String]
}

object Hello {

  val channelUrl = "http://localhost:8090/channel/"
  val sc4pacUrl = "https://github.com/memo33/sc4pac-tools#sc4pac"

  lazy val backend = sttp.client4.fetch.FetchBackend()

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

  def fetchPackage(module: BareModule): Future[Option[JsonData.Package]] = {
    val url = sttp.model.Uri(java.net.URI.create(s"${channelUrl}${JsonRepoUtil.packageSubPath(module, version = "latest")}"))
    for {
      response <- basicRequest.get(url).response(asJson[JsonData.Package]).send(backend)
    } yield {
      if (!response.is200)
        None
      else response.body.toOption
    }
  }

  // TODO make all selectable
  def pkgNameFrag(module: BareModule, link: Boolean = true) =
    if (link) H.a(H.href := s"?pkg=${module.orgName}")(H.code(module.orgName))
    else H.code(module.orgName)

  def pkgInfoFrag(pkg: JsonData.Package) = {
    val b = Seq.newBuilder[H.Frag]

    def mkPar(text: String) = text.trim.linesIterator.map(H.p(_)).toSeq

    b += H.tr(H.th("Name:"), H.td(pkg.name))
    b += H.tr(H.th("Group:"), H.td(pkg.group))
    b += H.tr(H.th("Version:"), H.td(pkg.version))
    b += H.tr(H.th("Summary:"), H.td(if (pkg.info.summary.nonEmpty) pkg.info.summary else "-"))
    if (pkg.info.description.nonEmpty)
      b += H.tr(H.th("Description:"))(H.td(mkPar(pkg.info.description)))
    if (pkg.info.warning.nonEmpty)
      b += H.tr(H.th("Warning:"))(H.td(mkPar(pkg.info.warning)))
    if (pkg.info.conflicts.nonEmpty)
      b += H.tr(H.th("Conflicts:"))(H.td(mkPar(pkg.info.conflicts)))
    if (pkg.info.author.nonEmpty)
      b += H.tr(H.th("Author:"))(H.td(pkg.info.author))
    if (pkg.info.website.nonEmpty) {
      var url = uri"${pkg.info.website}"
      if (url.scheme.isEmpty)
        url = url.scheme("https")
      b += H.tr(H.th("Website:"))(H.td(H.a(H.href := url.toString)(pkg.info.website)))
    }
    b += H.tr(H.th("Subfolder:"), H.td(H.code(pkg.subfolder.toString)))

    b += H.tr(H.th("Variants:"), H.td(
      if (pkg.variants.length == 1 && pkg.variants.head.variant.isEmpty)
        "None"
      else
        H.ul(
          pkg.variants.map { vd =>
            H.li(JsonData.VariantData.variantString(vd.variant))
          }
        )
    ))

    // TODO add info about which dependencies belong to which variant
    val deps = pkg.variants
      .flatMap(vd => vd.bareDependencies.collect{ case m: BareModule => m })
      .distinct
    b += H.tr(H.th("Dependencies:"), H.td(
      if (deps.isEmpty) "None"
      else H.ul(deps.map(dep => H.li(pkgNameFrag(dep))))
    ))

    H.div(
      H.table(H.id := "pkginfo")(H.tbody(b.result())),
      H.p("Install this package with ", H.a(H.href := sc4pacUrl)(H.code("sc4pac")), ":"),
      H.pre(H.cls := "codebox")(s"sc4pac add ${pkg.toBareDep.orgName}\nsc4pac update")
    )
  }

  def setupUI(): Unit = {

    // val response: Future[Response[Either[ResponseException[String, Exception], JsonData.Package]]] =
    //   basicRequest
    //     .get(uri"http://localhost:8090/metadata/memo/demo-package/1.0/demo-package-1.0.json")
    //     .response(asJson[JsonData.Package])
    //     .send(backend)

    // response.foreach(resp => {
    //   println(resp.code)
    //   println(resp.body)
    // })

    // val button = document.createElement("button")
    // button.textContent = "Click me!"
    // button.addEventListener("click", (e: dom.MouseEvent) => addClickedMessage())
    // document.body.appendChild(button)

    // appendPar(document.body, "Hello World")

    val urlParams = new dom.URLSearchParams(dom.window.location.search)
    val pkgName = urlParams.get("pkg")
    if (pkgName == null) {
      appendPar(document.body, s"Pass query ?pkg=<group>:<name>")
    } else parseModule(pkgName) match {
      case Left(err) => appendPar(document.body, s"Package: $err")
      case Right(module) =>
        document.body.appendChild(H.p("Loading package ", pkgNameFrag(module, link = false), "…").render)
        fetchPackage(module) foreach {
          case None =>
            appendPar(document.body, "Package: not found")
          case Some(pkg) =>
            document.body.appendChild(pkgInfoFrag(pkg).render)
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
