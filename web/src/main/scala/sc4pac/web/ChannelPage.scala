package io.github.memo33.sc4pac
package web

import scala.scalajs.js
import org.scalajs.dom
import org.scalajs.dom.document

import scala.concurrent.Future
import concurrent.ExecutionContext.Implicits.global

import upickle.default as UP

import sttp.client4.{basicRequest, Request, UriContext, Response, ResponseException}
import sttp.client4.upicklejson.asJson

import scalatags.JsDom.all as H  // html tags
import scalatags.JsDom.all.{stringFrag, stringAttr, SeqFrag, intPixelStyle, stringStyle}

object JsonData extends SharedData {
  opaque type Instant = String
  val instantRw = UP.readwriter[String]
  opaque type SubPath = String
  val subPathRw = UP.readwriter[String]
}

object ChannelPage {

  // val channelUrl = "http://localhost:8090/channel/"
  val channelUrl = ""  // relative to current host
  val sc4pacUrl = "https://github.com/memo33/sc4pac-tools#sc4pac"
  val issueUrl = "https://github.com/memo33/sc4pac/issues"
  val yamlUrl = "https://github.com/memo33/sc4pac/tree/main/src/yaml/"

  lazy val backend = sttp.client4.fetch.FetchBackend()

  def main(args: Array[String]): Unit = {
    document.addEventListener("DOMContentLoaded", (e: dom.Event) => setupUI())
  }

  val regexModule = """([^:\s]+):([^:\s]+)""".r
  def parseModule(pkgName: String): Either[String, BareModule] =
    pkgName match {
      case regexModule(group, name) => Right(BareModule(group = Organization(group), name = ModuleName(name)))
      case _ => Left("Malformed package name: <group>:<name>")
    }

  def fetchPackage(module: BareModule): Future[Option[JsonData.Package]] = {
    val url = sttp.model.Uri(java.net.URI.create(s"${channelUrl}${JsonRepoUtil.packageSubPath(module, version = "latest")}"))
    for {
      response <- basicRequest.get(url).response(asJson[JsonData.Package]).send(backend)
    } yield {
      if (!response.is200) None
      else response.body.toOption
    }
  }

  def fetchChannel(): Future[Option[JsonData.Channel]] = {
    val url = sttp.model.Uri(java.net.URI.create(s"${channelUrl}${JsonRepoUtil.channelContentsFilename}"))
    for {
      response <- basicRequest.get(url).response(asJson[JsonData.Channel]).send(backend)
    } yield {
      if (!response.is200) None
      else response.body.toOption
    }
  }

  def variantFrag(variant: JsonData.Variant, variantDescriptions: Map[String, Map[String, String]]) =
    variant.toSeq.sorted.map { (k, v) =>
      variantDescriptions.get(k).flatMap(_.get(v)) match {
        case None => H.code(s"$k = $v")
        case Some(tooltipText) => H.code(H.div(H.cls := "tooltip")(s"$k = $v", H.span(H.cls := "tooltiptext")(tooltipText)))
      }
    }.zipWithIndex.flatMap((elem, idx) => if (idx == 0) Seq[H.Frag](elem) else Seq[H.Frag](", ", elem))

  // TODO make all selectable
  def pkgNameFrag(module: BareModule, link: Boolean = true) =
    if (link) H.a(H.href := s"?pkg=${module.orgName}")(H.code(module.orgName))
    else H.code(module.orgName)

  def pkgInfoFrag(pkg: JsonData.Package) = {
    val module = pkg.toBareDep
    val b = Seq.newBuilder[H.Frag]
    def add(label: String, child: H.Frag): Unit =
      b += H.tr(H.th(label), H.td(child))
    def mkPar(text: String) = text.trim.linesIterator.map(H.p(_)).toSeq

    // add("Name", pkg.name)
    // add("Group", pkg.group)
    add("Version", pkg.version)
    add("Summary", if (pkg.info.summary.nonEmpty) pkg.info.summary else "-")
    if (pkg.info.description.nonEmpty)
      add("Description", mkPar(pkg.info.description))
    if (pkg.info.warning.nonEmpty)
      add("Warning", mkPar(pkg.info.warning))
    add("Conflicts", if (pkg.info.conflicts.isEmpty) "None" else mkPar(pkg.info.conflicts))
    if (pkg.info.author.nonEmpty)
      add("Author", pkg.info.author)
    if (pkg.info.website.nonEmpty) {
      var url = uri"${pkg.info.website}"
      if (url.scheme.isEmpty)
        url = url.scheme("https")
      add("Website", H.a(H.href := url.toString)(pkg.info.website))
    }
    add("Subfolder", H.code(pkg.subfolder.toString))

    add("Variants",
      if (pkg.variants.length == 1 && pkg.variants.head.variant.isEmpty)
        "None"
      else
        H.ul(
          pkg.variants.map { vd =>
            H.li(variantFrag(vd.variant, pkg.variantDescriptions))
          }
        )
    )

    // TODO add info about which dependencies belong to which variant
    val deps = pkg.variants
      .flatMap(vd => vd.bareDependencies.collect{ case m: BareModule => m })
      .distinct
    add("Dependencies",
      if (deps.isEmpty) "None"
      else H.ul(deps.map(dep => H.li(pkgNameFrag(dep))))
    )

    H.div(
      H.div(H.float := "right")(
        pkg.metadataSource.toSeq.map(yamlSubPath => H.a(H.cls := "btn", H.href := s"$yamlUrl$yamlSubPath")("Edit metadata"))
        :+ H.a(H.cls := "btn", H.href := issueUrl)("Report a problem")
      ),
      H.h2(H.clear := "right")(module.orgName),
      H.table(H.id := "pkginfo")(H.tbody(b.result())),
      H.p("Install this package with ", H.a(H.href := sc4pacUrl)(H.code("sc4pac")), ":"),
      H.pre(H.cls := "codebox")(s"sc4pac add ${module.orgName}\nsc4pac update")
    )
  }

  def channelContentsFrag(items: Seq[JsonData.ChannelItem]) = {
    H.table(H.id := "channelcontents")(H.tbody(items.flatMap { item =>
      item.toBareDep match
        case mod: BareModule => Some(H.tr(H.td(pkgNameFrag(mod, link = true)), H.td(item.summary)))
        case _: BareAsset => None
    }))
  }

  def setupUI(): Unit = {
    val urlParams = new dom.URLSearchParams(dom.window.location.search)
    val pkgName = urlParams.get("pkg")
    if (pkgName == null) {
      val output = H.p("Loading channel packages…").render
      document.body.appendChild(output)
      fetchChannel() foreach {
        case None =>
          document.body.appendChild(H.p("Failed to load channel contents.").render)
        case Some(channel) =>
          output.replaceWith(channelContentsFrag(channel.contents).render)
      }
    } else parseModule(pkgName) match {
      case Left(err) =>
        document.body.appendChild(H.p(err).render)
      case Right(module) =>
        val metaDescription = H.meta(H.name := "description", H.content := s"Package ${module.orgName}").render
        document.head.appendChild(metaDescription)
        val output = H.p("Loading package ", pkgNameFrag(module, link = false), "…").render
        document.body.appendChild(output)
        fetchPackage(module) foreach {
          case None =>
            document.body.appendChild(H.p("Package not found.").render)
          case Some(pkg) =>
            output.replaceWith(pkgInfoFrag(pkg).render)
        }
    }
  }
}
