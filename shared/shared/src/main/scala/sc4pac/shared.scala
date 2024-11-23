package io.github.memo33
package sc4pac

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

  type Checksum
  implicit val checksumRw: ReadWriter[Checksum]
  protected def emptyChecksum: Checksum

  implicit val bareModuleRw: ReadWriter[BareModule]

  type Uri
  implicit val uriRw: ReadWriter[Uri]

  case class Dependency(group: String, name: String, version: String) derives ReadWriter

  case class AssetReference(
    assetId: String,
    include: Seq[String] = Seq.empty,
    exclude: Seq[String] = Seq.empty
  ) derives ReadWriter

  case class VariantData(
    variant: Variant,
    dependencies: Seq[Dependency] = Seq.empty,
    assets: Seq[AssetReference] = Seq.empty
  ) derives ReadWriter {
    def bareDependencies: Seq[BareDep] =
      dependencies.map(d => BareModule(Organization(d.group), ModuleName(d.name)))
        ++ assets.map(a => BareAsset(ModuleName(a.assetId)))
  }
  object VariantData {
    private val variantPrefix = "variant."

    def variantToAttributes(variant: Variant): Map[String, String] = {
      require(variant.keysIterator.forall(k => !k.startsWith(variantPrefix)))
      variant.map((k, v) => (s"${variantPrefix}$k", v))
    }

    def variantFromAttributes(attributes: Map[String, String]): Variant = attributes.collect {
      case (k, v) if k.startsWith(variantPrefix) => (k.substring(variantPrefix.length), v)
    }

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
    url: String,
    lastModified: Instant = null.asInstanceOf[Instant],
    archiveType: Option[ArchiveType] = None,
    requiredBy: Seq[BareModule] = Seq.empty  // optional and only informative (mangles all variants and versions, is limited to one channel,
                                             // can easily become outdated since json files are cached indefinitely)
  ) extends PackageAsset /*derives ReadWriter*/ {
    def attributes: Map[String, String] = {
      val m = Map(Asset.urlKey -> url)
      if (lastModified != null) {
        m + (Asset.lastModifiedKey -> lastModified.toString)
      } else {
        m
      }
    }

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
    variantDescriptions: Map[String, Map[String, String]] = Map.empty,  // variantKey -> variantValue -> description
    metadataSource: Option[SubPath] = None  // path to yaml file
  ) extends PackageAsset {

    def toBareDep: BareModule = BareModule(Organization(group), ModuleName(name))

    // def unknownVariants(globalVariant: Variant): Map[String, Seq[String]] = {
    //   val unknownKeys: Set[String] = Set.concat(variants.map(_.variant.keySet) *) &~ globalVariant.keySet
    //   unknownKeys.iterator.map(k => (k, variants.flatMap(vd => vd.variant.get(k)).distinct)).toMap
    // }
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
    requiredBy: Seq[BareModule] = Seq.empty,  // optional and only informative (mangles all variants and versions, is limited to one channel,
                                              // can easily become outdated since json files are cached indefinitely)
  ) derives ReadWriter
  object Info {
    val empty = Info()
  }

  case class ExternalPackage(
    group: String,
    name: String,
    // channel: Option[String],
    requiredBy: Seq[BareModule],
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
    summary: String = "",
    category: Option[String] = None,
  ) derives ReadWriter {
    def isSc4pacAsset: Boolean = group == JsonRepoUtil.sc4pacAssetOrg.value
    def toBareDep: BareDep = if (isSc4pacAsset) BareAsset(ModuleName(name)) else BareModule(Organization(group), ModuleName(name))
    private[sc4pac] def toSearchString: String = s"$group:$name $summary"
  }

  case class Channel(
    scheme: Int,
    info: Channel.Info = Channel.Info.empty,
    stats: Channel.Stats = null,  // added between scheme 4 and 5 (backward compatible: recomputed for smaller schemes, so never actually null)
    contents: Seq[ChannelItem],
    externalPackages: Seq[Channel.ExtPkg] = Seq.empty,  // default for backward compatibility
    externalAssets: Seq[Channel.ExtAsset] = Seq.empty,  // default for backward compatibility
  ) {
    lazy val versions: Map[BareDep, Seq[(String, Checksum)]] =
      contents.iterator.map(item => item.toBareDep -> item.versions.map(v => v -> item.checksums.getOrElse(v, emptyChecksum))).toMap
  }
  object Channel {

    private val channelRwDefault: ReadWriter[Channel] = macroRW
    implicit val channelRw: ReadWriter[Channel] =
      channelRwDefault.bimap[Channel](identity, c => if (c.stats != null) c else createAddStats(c.scheme, c.info, c.contents, c.externalPackages, c.externalAssets))

    /* recomputes the channel stats */
    def createAddStats(
      scheme: Int,
      info: Channel.Info,
      contents: Seq[ChannelItem],
      externalPackages: Seq[Channel.ExtPkg],
      externalAssets: Seq[Channel.ExtAsset],
    ): Channel = {
      val m = collection.mutable.Map.empty[String, Int]
      for (item <- contents; cat <- item.category) {
        m(cat) = m.getOrElse(cat, 0) + 1
      }
      Channel(scheme, info, Stats.fromMap(m), contents = contents, externalPackages, externalAssets)
    }

    def create(
      scheme: Int,
      info: Channel.Info,
      channelData: Iterable[(BareDep, Iterable[(String, PackageAsset, Checksum)])],  // name -> (version, json, sha)
      externalPackages: Seq[Channel.ExtPkg],
      externalAssets: Seq[Channel.ExtAsset],
    ): Channel = {
      createAddStats(scheme, info, contents = channelData.iterator.collect {
        case (dep, versions) if versions.nonEmpty =>
          val (g, n) = dep match {
            case m: BareModule => (m.group.value, m.name.value)
            case a: BareAsset => (JsonRepoUtil.sc4pacAssetOrg.value, a.assetId.value)
          }
          // we arbitrarily pick the summary of the first item (usually there is just one version anyway)
          val summaryOpt = versions.iterator.collectFirst { case (_, pkg: Package, _) if pkg.info.summary.nonEmpty => pkg.info.summary }
          val catOpt = versions.iterator.collectFirst { case (_, pkg: Package, _) => pkg.subfolder.toString }
          ChannelItem(
            group = g, name = n,
            versions = versions.iterator.map(_._1).toSeq,
            checksums = versions.iterator.map(t => (t._1, t._3)).toMap,
            summary = summaryOpt.getOrElse(""),
            category = catOpt,
          )
      }.toSeq, externalPackages, externalAssets)
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
    ) derives ReadWriter
    object Info {
      val empty = Info(None, None)
    }

    case class ExtPkg(group: String, name: String, checksum: Checksum) derives ReadWriter {
      def toBareDep: BareModule = BareModule(Organization(group), ModuleName(name))
    }

    case class ExtAsset(assetId: String, checksum: Checksum) derives ReadWriter

  }

}
