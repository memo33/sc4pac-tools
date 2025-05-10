package io.github.memo33
package sc4pac

import java.util.regex.Pattern
import upickle.default.{ReadWriter, readwriter, macroRW}

sealed trait BareDep {
  def orgName: String
}
final case class BareModule(group: Organization, name: ModuleName) extends BareDep {  // a dependency without version information, variant data or any other attributes
  def orgName = s"${group.value}:${name.value}"
  def formattedDisplayString(gray: String => String, bold: String => String): String = gray(s"${group.value}:") + bold(name.value)
}
object BareModule {
  val pkgMarkdownRegex = """`pkg=([^`:\s]+):([^`:\s]+)`""".r
  given lexOrdering: Ordering[BareModule] = Ordering.by(module => (module.group.value, module.name.value))
}
final case class BareAsset(assetId: ModuleName) extends BareDep {
  def orgName = s"${JsonRepoUtil.sc4pacAssetOrg.value}:${assetId.value}"
}

object JsonRepoUtil {

  def packageSubPath(dep: BareDep, version: String): String = {
    val (group, name) = dep match {
      case m: BareModule => (m.group.value, m.name.value)
      case a: BareAsset => (sc4pacAssetOrg.value, a.assetId.value)
    }
    s"metadata/${group}/${name}/${version}/pkg.json"
  }

  def extPackageSubPath(dep: BareDep): String = {
    dep match {
      case m: BareModule => s"metadata/_extpkg/${m.group.value}/${m.name.value}/latest/pkg.json"
      case a: BareAsset => s"metadata/_extasset/${a.assetId.value}/latest/pkg.json"
    }
  }

  val sc4pacAssetOrg = Organization("sc4pacAsset")

  val channelContentsFilename = "sc4pac-channel-contents.json"  // only for JSON repositories
}

abstract class SharedData {

  type Variant = Map[String, String]

  type Instant
  implicit val instantRw: ReadWriter[Instant]

  type SubPath
  implicit val subPathRw: ReadWriter[SubPath]
  protected def categoryFromSubPath(subpath: SubPath): Option[String]

  type Checksum
  implicit val checksumRw: ReadWriter[Checksum]
  protected def emptyChecksum: Checksum

  type IncludeWithChecksum
  implicit val includeWithChecksumRw: ReadWriter[IncludeWithChecksum]

  implicit val bareModuleRw: ReadWriter[BareModule]

  type Uri
  implicit val uriRw: ReadWriter[Uri]

  case class Dependency(group: String, name: String, version: String) derives ReadWriter

  case class AssetReference(
    assetId: String,
    include: Seq[String] = Seq.empty,
    exclude: Seq[String] = Seq.empty,
    withChecksum: Seq[IncludeWithChecksum] = Seq.empty,
  ) derives ReadWriter

  case class VariantData(
    variant: Variant,
    dependencies: Seq[Dependency] = Seq.empty,
    assets: Seq[AssetReference] = Seq.empty,
    conflictingPackages: Seq[BareModule] = Seq.empty,
  ) derives ReadWriter {
    def bareModules: Seq[BareModule] = dependencies.map(d => BareModule(Organization(d.group), ModuleName(d.name)))
    def bareDependencies: Seq[BareDep] = bareModules ++ assets.map(a => BareAsset(ModuleName(a.assetId)))
  }
  object VariantData {
    def variantString(variant: Variant): String = variant.toSeq.sorted.map((k, v) => s"$k=$v").mkString(", ")
  }

  /** Package or Asset */
  sealed trait PackageAsset {
    def toBareDep: BareDep
    def version: String
  } /*derives ReadWriter*/  // <-- causes infinite compile loop, so manually define ReadWriter instead
  object PackageAsset {
    implicit val packageAssetRw: ReadWriter[PackageAsset] = ReadWriter.merge(Asset.assetRw, Package.packageRw)
  }

  case class ArchiveType(format: String, version: String) derives ReadWriter
  object ArchiveType {
    val clickteamFormat = "Clickteam"
    // val clickteamVersions = Seq(20, 24, 30, 35, 40)  // supported by cicdec 3.0.1
  }

  @upickle.implicits.key("Asset")
  case class Asset(
    assetId: String,
    version: String,
    url: Uri,
    lastModified: Instant = null.asInstanceOf[Instant],
    archiveType: Option[ArchiveType] = None,
    requiredBy: Seq[BareModule] = Seq.empty,  // optional and only informative (mangles all variants and versions)
    checksum: Checksum = emptyChecksum,
  ) extends PackageAsset /*derives ReadWriter*/ {

    def toBareDep: BareAsset = BareAsset(assetId = ModuleName(assetId))

    // def toDepAsset = DepAsset(assetId = ModuleName(assetId), version = version, url = url, lastModified = Option(lastModified))
  }
  object Asset {
    private val urlKey = "url"
    private val lastModifiedKey = "lastModified"
    // def parseLastModified(lastModified: String): Option[Instant] = {
    //   Option(lastModified).map(java.time.Instant.parse)  // throws java.time.format.DateTimeParseException
    // }

    implicit val assetRw: ReadWriter[Asset] = macroRW
  }

  @upickle.implicits.key("Package")
  case class Package(
    group: String,
    name: String,
    version: String,
    subfolder: SubPath,
    info: Info = Info.empty,
    variants: Seq[VariantData],  // should be non-empty, but can consist of a single empty variant
    @deprecated("use variantInfo instead", since = "0.5.4")
    variantDescriptions: Map[String, Map[String, String]] = Map.empty,  // variantId -> variantValue -> description
    variantInfo: Map[String, VariantInfo] = Map.empty,  // variantId -> variantInfo
    metadataSource: Option[SubPath] = None,  // path to yaml file
    metadataSourceUrl: Option[Uri] = None,  // full URL to yaml file
    metadataIssueUrl: Option[Uri] = None,  // URL to create new issue for this package
    channelLabel: Option[String] = None,
  ) extends PackageAsset {

    def toBareDep: BareModule = BareModule(Organization(group), ModuleName(name))

    def upgradeVariantInfo: Package =
      if (variantInfo.isEmpty && variantDescriptions.nonEmpty)
        copy(
          variantDescriptions = Map.empty,
          variantInfo = variantDescriptions.mapValues { descs =>
            VariantInfo(valueDescriptions = descs)
          }.toMap,
        )
      else this
  }
  object Package {
    implicit val packageRw: ReadWriter[Package] = macroRW
  }

  case class Info(
    summary: String = "",
    warning: String = "",
    conflicts: String = "",
    description: String = "",
    author: String = "",
    images: Seq[String] = Seq.empty,
    website: String = "",
    websites: Seq[String] = Seq.empty,
    requiredBy: Seq[BareModule] = Seq.empty,  // optional and only informative (mangles all variants and versions, is limited to one channel)
    reverseConflictingPackages: Seq[BareModule] = Seq.empty,  // optional and only informative (mangles all variants and versions)
  ) derives ReadWriter {
    def upgradeWebsites: Info =
      if (websites.isEmpty && website.nonEmpty) copy(website = "", websites = Seq(website)) else this
  }
  object Info {
    val empty = Info()
  }

  case class VariantInfo(
    description: String = "",
    valueDescriptions: Map[String, String] = Map.empty,
    default: Option[String] = None,
  ) derives ReadWriter
  object VariantInfo {
    val empty = VariantInfo()
  }

  case class ExternalPackage(
    group: String,
    name: String,
    // channel: Option[String],
    requiredBy: Seq[BareModule],
    reverseConflictingPackages: Seq[BareModule] = Seq.empty,
  ) derives ReadWriter {
    def toBareDep: BareModule = BareModule(Organization(group), ModuleName(name))
  }

  case class ExternalAsset(
    assetId: String,
    // channel: Option[String],
    requiredBy: Seq[BareModule],
  ) derives ReadWriter {
    def toBareDep: BareAsset = BareAsset(ModuleName(assetId))
  }

  case class ChannelItem(
    group: String,
    name: String,
    versions: Seq[String],
    checksums: Map[String, Checksum] = Map.empty,  // version -> checksum (note that Map or Checksum itself could be empty)
    externalIds: Map[String, Seq[String]] = Map.empty,  // stex or sc4e
    summary: String = "",
    category: Option[String] = None,
    tags: Seq[String] = Seq.empty,  // e.g. authors (for searching)
  ) derives ReadWriter {
    def isSc4pacAsset: Boolean = group == JsonRepoUtil.sc4pacAssetOrg.value
    def toBareDep: BareDep = if (isSc4pacAsset) BareAsset(ModuleName(name)) else BareModule(Organization(group), ModuleName(name))
    private[sc4pac] def toSearchString: String = (s"$group:$name $summary" + tags.mkString(" ", " ", "")).toLowerCase(java.util.Locale.ENGLISH)
  }

  case class Channel(
    scheme: Int,
    info: Channel.Info = Channel.Info.empty,
    stats: Channel.Stats = null,  // added between scheme 4 and 5 (backward compatible: recomputed for smaller schemes, so never actually null)
    packages: Seq[ChannelItem] = Seq.empty,  // since scheme 5
    assets: Seq[ChannelItem] = Seq.empty,  // since scheme 5
    externalPackages: Seq[Channel.ExtPkg] = Seq.empty,  // default for backward compatibility
    externalAssets: Seq[Channel.ExtAsset] = Seq.empty,  // default for backward compatibility
    @deprecated("use packages or assets instead", since = "0.5.0")
    contents: Seq[ChannelItem] = Seq.empty,  // TODO remove after deprecation, scheme <= 4
  ) {
    lazy val versions: Map[BareDep, Seq[(String, Checksum)]] =
      (packages.iterator ++ assets).map(item => item.toBareDep -> item.versions.map(v => v -> item.checksums.getOrElse(v, emptyChecksum))).toMap
  }
  object Channel {

    private val channelRwDefault: ReadWriter[Channel] = macroRW
    implicit val channelRw: ReadWriter[Channel] =
      channelRwDefault.bimap[Channel](identity, { c0 =>
        var c = c0
        if (c.contents.nonEmpty) {
          val (assets, packages) = c.contents.iterator.partition(_.isSc4pacAsset)
          c = c.copy(contents = Seq.empty, packages = packages.toSeq, assets = assets.toSeq)
        }
        if (c.stats != null) c else createAddStats(c.scheme, c.info, packages = c.packages, assets = c.assets, c.externalPackages, c.externalAssets)
      })

    /* recomputes the channel stats */
    def createAddStats(
      scheme: Int,
      info: Channel.Info,
      packages: Seq[ChannelItem],
      assets: Seq[ChannelItem],
      externalPackages: Seq[Channel.ExtPkg],
      externalAssets: Seq[Channel.ExtAsset],
    ): Channel = {
      val m = collection.mutable.Map.empty[String, Int]
      for (item <- packages; cat <- item.category) {
        m(cat) = m.getOrElse(cat, 0) + 1
      }
      Channel(scheme, info, Stats.fromMap(m), packages = packages, assets = assets, externalPackages, externalAssets)
    }

    val externalIdStex = "stex"
    val externalIdSc4e = "sc4e"

    private val urlIdPatterns = Seq(
      externalIdStex -> Pattern.compile("""simtropolis\.com/files/file/(\d+)-.*?(?:$|[?&]r=(\d+).*$)"""),  // matches ID and optional subfile ID
      externalIdSc4e -> Pattern.compile("""sc4evermore\.com/index.php/downloads/download/(?:\d+-[^/]*/)?(\d+)-.*"""),  // category component is optional
    )

    private def findExternalIds(pkg: Package): Map[String, Seq[String]] = {
      pkg.info.websites.flatMap(findExternalId).groupMap(_._1)(_._2)
    }

    def findExternalId(url: String): Option[(String, String)] = {  // stex/sc4e -> id
      urlIdPatterns.flatMap { (exchangeKey, pattern) =>
        val m = pattern.matcher(url)
        if (m.find()) Some(exchangeKey -> m.group(1)) else None
      }.headOption
    }

    def create(
      scheme: Int,
      info: Channel.Info,
      channelData: Iterable[(BareDep, Iterable[(String, PackageAsset, Checksum)])],  // name -> (version, json, sha)
      externalPackages: Seq[Channel.ExtPkg],
      externalAssets: Seq[Channel.ExtAsset],
    ): Channel = {
      val (assets, packages) = channelData.iterator.collect {
        case (dep, versions) if versions.nonEmpty =>
          val (g, n) = dep match {
            case m: BareModule => (m.group.value, m.name.value)
            case a: BareAsset => (JsonRepoUtil.sc4pacAssetOrg.value, a.assetId.value)
          }
          // we arbitrarily pick the summary of the first item (usually there is just one version anyway)
          val summaryOpt = versions.iterator.collectFirst { case (_, pkg: Package, _) if pkg.info.summary.nonEmpty => pkg.info.summary }
          val catOpt = versions.iterator.collectFirst { case (_, pkg: Package, _) => categoryFromSubPath(pkg.subfolder) }.flatten
          val tags = versions.iterator.collectFirst { case (_, pkg: Package, _) =>
            pkg.info.author.split("[/,;]|\\band\\b").iterator
              .map(_.trim())
              .filter(_.nonEmpty)
              .filterNot { author =>
                // if author is already contained in one of the other searchable fields, we can ignore it
                val p = Pattern.compile(Pattern.quote(author.replaceAll("_|\\s+", "-")), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)
                p.matcher(pkg.group).find() || p.matcher(pkg.name).find() || pkg.info.summary.nonEmpty && p.matcher(pkg.info.summary).find()
              }.toSeq
          }.getOrElse(Seq.empty)
          ChannelItem(
            group = g, name = n,
            versions = versions.iterator.map(_._1).toSeq,
            checksums = versions.iterator.map(t => (t._1, t._3)).toMap,
            externalIds = versions.iterator.collectFirst { case (_, pkg: Package, _) => findExternalIds(pkg) }.getOrElse(Map.empty),
            summary = summaryOpt.getOrElse(""),
            category = catOpt,
            tags = tags,
          )
      }.partition(_.isSc4pacAsset)
      createAddStats(scheme, info, packages = packages.toSeq, assets = assets.toSeq, externalPackages, externalAssets)
    }

    case class CategoryItem(category: String, count: Int) derives ReadWriter
    case class Stats(totalPackageCount: Int, categories: Seq[CategoryItem]) derives ReadWriter
    object Stats {

      def fromMap(categoriesMap: collection.Map[String, Int]): Stats = {
        val categories = categoriesMap.iterator.map(t => CategoryItem(t._1, t._2)).toSeq.sortBy(_.category)
        Stats(
          totalPackageCount = categories.foldLeft(0)(_ + _.count),
          categories = categories,
        )
      }

      def aggregate(stats: Seq[Stats]): Stats = {
        val m = collection.mutable.Map.empty[String, Int]
        for (stat <- stats.iterator; c <- stat.categories) {
          m(c.category) = m.getOrElse(c.category, 0) + c.count
        }
        Stats.fromMap(m)
      }
    }

    case class Info(
      channelLabel: Option[String],
      metadataSourceUrl: Option[Uri],
      metadataIssueUrl: Option[Uri] = None,
    ) derives ReadWriter
    object Info {
      val empty = Info(None, None, None)
    }

    case class ExtPkg(group: String, name: String, checksum: Checksum) derives ReadWriter {
      def toBareDep: BareModule = BareModule(Organization(group), ModuleName(name))
    }

    case class ExtAsset(assetId: String, checksum: Checksum) derives ReadWriter

  }

}
