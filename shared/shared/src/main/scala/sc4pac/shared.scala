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

  val sc4pacAssetOrg = Organization("sc4pacAsset")
}

abstract class SharedData {

  type Variant = Map[String, String]

  type Instant
  implicit val instantRw: ReadWriter[Instant]

  type SubPath
  implicit val subPathRw: ReadWriter[SubPath]

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

  @upickle.implicits.key("Asset")
  case class Asset(
    assetId: String,
    version: String,
    url: String,
    lastModified: Instant = null.asInstanceOf[Instant]
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

    def unknownVariants(globalVariant: Variant): Map[String, Seq[String]] = {
      val unknownKeys: Set[String] = Set.concat(variants.map(_.variant.keySet) *) &~ globalVariant.keySet
      unknownKeys.iterator.map(k => (k, variants.flatMap(vd => vd.variant.get(k)).distinct)).toMap
    }
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
    website: String = ""
  ) derives ReadWriter
  object Info {
    val empty = Info()
  }


}
