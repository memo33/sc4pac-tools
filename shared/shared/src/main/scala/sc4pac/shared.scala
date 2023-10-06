package io.github.memo33
package sc4pac

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
    s"metadata/${group}/${name}/${version}/${name}-${version}.json"
  }

  val sc4pacAssetOrg = Organization("sc4pacAsset")
}
