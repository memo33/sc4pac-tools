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
  opaque type Checksum = Map[String, String]
  val checksumRw = UP.readwriter[Map[String, String]]
  protected def emptyChecksum = Map.empty
  opaque type IncludeWithChecksum = Map[String, String]
  val includeWithChecksumRw = UP.readwriter[Map[String, String]]

  private val regexModule = """([^:\s]+):([^:\s]+)""".r
  def parseModule(pkgName: String): Either[String, BareModule] =
    pkgName match {
      case regexModule(group, name) => Right(BareModule(group = Organization(group), name = ModuleName(name)))
      case _ => Left("Malformed package name: <group>:<name>")
    }

  val bareModuleRw: UP.ReadWriter[BareModule] = UP.readwriter[String].bimap[BareModule](_.orgName,
    parseModule(_).left.map(e => throw new IllegalArgumentException(e)).merge)
}

@js.native
@js.annotation.JSGlobal
object DOMPurify extends js.Object {
  def sanitize(text: String): String = js.native
}

@js.native
@js.annotation.JSGlobal("marked")
object Marked extends js.Object {
  def parse(text: String): String = js.native
  def use(extensions: js.Any*): Unit = js.native
}

@js.native
trait TokensCodespan extends js.Object {  // see https://github.com/markedjs/marked/blob/7e4e3435eaca2b0f48f5aedb53d67a36170086f3/src/Tokens.ts#L57
  val `type`: String  // "codespan"
  val raw: String  // with quotes
  val text: String  // without quotes
}

object ChannelPage {

  // val channelUrl = "http://localhost:8090/channel/"
  val channelUrl = ""  // relative to current host
  // val sc4pacUrl = "https://github.com/memo33/sc4pac-tools#sc4pac"
  val sc4pacUrl = "https://memo33.github.io/sc4pac/#/"
  val issueUrl = "https://github.com/memo33/sc4pac/issues"
  val yamlUrl = "https://github.com/memo33/sc4pac/tree/main/src/yaml/"

  lazy val backend = sttp.client4.fetch.FetchBackend()

  def main(args: Array[String]): Unit = {
    document.addEventListener("DOMContentLoaded", (e: dom.Event) => setupUI())
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
    if (link) H.frag(
      H.code(H.cls := "code-left")(s"${module.group.value}:"),
      H.a(H.href := s"?pkg=${module.orgName}")(H.code(H.cls := "code-right")(module.name.value))
    )
    else H.code(module.orgName)

  /** Highlights syntax `pkg=group:name` in metadata description text. */
  def renderCodespan(codespan: TokensCodespan): String = codespan.raw match
    case BareModule.pkgMarkdownRegex(group, name) =>
      val module = BareModule(Organization(group), ModuleName(name))
      // a raw re-implementation of pkgNameFrag
      s"""<code class="code-left">${module.group.value}:</code><a href="?pkg=${module.orgName}"><code class="code-right">${module.name.value}</code></a>"""
    case _ => s"<code>${codespan.text}</code>"

  def markdownFrag(text: String): H.Frag = {
    H.raw(DOMPurify.sanitize(Marked.parse(text)))
  }

  def pkgInfoFrag(pkg: JsonData.Package) = {
    val module = pkg.toBareDep
    val b = Seq.newBuilder[H.Frag]
    def add(label: String, child: H.Frag): Unit =
      b += H.tr(H.th(label), H.td(child))

    // add("Name", pkg.name)
    // add("Group", pkg.group)
    add("Version", pkg.version)
    add("Summary", if (pkg.info.summary.nonEmpty) markdownFrag(pkg.info.summary) else "-")
    if (pkg.info.description.nonEmpty)
      add("Description", markdownFrag(pkg.info.description))
    if (pkg.info.warning.nonEmpty)
      add("Warning", markdownFrag(pkg.info.warning))
    add("Conflicts", if (pkg.info.conflicts.isEmpty) "None" else markdownFrag(pkg.info.conflicts))
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
        H.ul(H.cls := "unstyled-list")(
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
      else H.ul(H.cls := "unstyled-list")(deps.map(dep => H.li(pkgNameFrag(dep))))
    )

    add("Required By",
      if (pkg.info.requiredBy.isEmpty) "None"
      else H.ul(H.cls := "unstyled-list")(pkg.info.requiredBy.map(dep => H.li(pkgNameFrag(dep))))
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

  def channelContentsFrag(channel: JsonData.Channel, displayCategory: Option[String]) = {
    val items = channel.contents
    val categories: Seq[JsonData.Channel.CategoryItem] = channel.stats.categories
    val displayCategory2 = displayCategory.filter(c => categories.exists(_.category == c))

    def mkOption(cat: String, text: String, selected: Boolean) =
      if (selected) H.option(H.value := cat, H.selected)(text) else H.option(H.value := cat)(text)

    H.frag(
      H.h2("sc4pac Channel"),
      H.p("This is the default channel of ",
        H.a(H.href := sc4pacUrl)(H.code("sc4pac")),
        s". Currently, there are ${items.count(!_.isSc4pacAsset)} packages you can install."
      ),
      H.form(
        H.label(H.`for` := "category")("Category:"),
        H.select(
          H.name := "category",
          H.id := "category",
        )(
          (mkOption("", s"All (${channel.stats.totalPackageCount})", displayCategory2.isEmpty) +:
            categories.map(c => mkOption(c.category, s"${c.category} (${c.count})", displayCategory2.contains(c.category))))*
        ),
        H.input(H.`type` := "submit", H.value := "Submit")
      ),
      H.table(H.id := "channelcontents")(H.tbody(items.flatMap { item =>
        if (displayCategory2.isEmpty || item.category == displayCategory2) {  // either show all or filter by category
          item.toBareDep match
            case mod: BareModule => Some(H.tr(H.td(pkgNameFrag(mod, link = true)), H.td(markdownFrag(item.summary))))
            case _: BareAsset => None
        } else {
          None
        }
      }))
    )
  }

  def setupUI(): Unit = {
    Marked.use(js.Dictionary("renderer" -> js.Dictionary("codespan" -> (renderCodespan: js.Function))))
    val urlParams = new dom.URLSearchParams(dom.window.location.search)
    val pkgName = urlParams.get("pkg")
    if (pkgName == null) {
      val output = H.p("Loading channel packages…").render
      document.body.appendChild(output)
      fetchChannel() foreach {
        case None =>
          document.body.appendChild(H.p("Failed to load channel contents.").render)
        case Some(channel) =>
          val displayCategory = Option(urlParams.get("category"))
          output.replaceWith(channelContentsFrag(channel, displayCategory).render)
      }
    } else JsonData.parseModule(pkgName) match {
      case Left(err) =>
        document.body.appendChild(H.p(err).render)
        ()
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
