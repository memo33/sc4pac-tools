package io.github.memo33
package sc4pac

import io.circe.{ParsingFailure, Json}
import upickle.default.{Reader, ReadWriter, writeTo}
import zio.{ZIO, IO}

import sc4pac.Data.{osSubPathRw, InfoData}

object ChannelUtil {

  case class YamlVariantData(
    variant: Variant,
    dependencies: Seq[String] = Seq.empty,
    assets: Seq[Data.AssetReference] = Seq.empty
  ) derives ReadWriter {
    def toVariantData = Data.VariantData(
      variant = variant,
      dependencies = coursier.parse.ModuleParser.modules(dependencies, defaultScalaVersion = "").either match {
        case Left(errs) => throw new IllegalArgumentException(s"format error in dependencies: ${errs.mkString(", ")}")  // TODO error reporting
        case Right(modules) => modules.map(mod => Data.DependencyData(group = mod.organization.value, name = mod.name.value, version = "latest.release"))
      },
      assets = assets)
  }

  case class YamlPackageDataVariants(
    group: String,
    name: String,
    version: String,
    subfolder: os.SubPath,
    info: InfoData = InfoData.empty,
    variants: Seq[YamlVariantData]
  ) derives ReadWriter {
    def toPackageData = Data.PackageData(
      group = group, name = name, version = version, subfolder = subfolder,
      info = info, variants = variants.map(_.toVariantData))
  }

  case class YamlPackageDataBasic(
    group: String,
    name: String,
    version: String,
    subfolder: os.SubPath,
    info: InfoData = InfoData.empty,
    dependencies: Seq[String] = Seq.empty,
    assets: Seq[Data.AssetReference] = Seq.empty
  ) derives ReadWriter {
    def toVariants = YamlPackageDataVariants(
      group = group, name = name, version = version, subfolder = subfolder, info = info,
      variants = Seq(YamlVariantData(variant = Map.empty, dependencies = dependencies, assets = assets)))
  }

  private def parseCirceJson[A : Reader](j: Json): IO[upickle.core.Abort | IllegalArgumentException, A] = {
    ZIO.attempt(ujson.circe.CirceJson.transform(j, upickle.default.reader[A])).refineToOrDie
  }

  private def parsePkgData(j: Json): IO[ErrStr, Data.PackageData | Data.AssetData] = {
    ZIO.validateFirst(Seq(  // we use ZIO validate for error accumulation
      parseCirceJson[YamlPackageDataVariants](_: Json).map(_.toPackageData),
      parseCirceJson[YamlPackageDataBasic](_: Json).map(_.toVariants.toPackageData),  // if `variants` is absent, try YamlPackageDataBasic
      parseCirceJson[Data.AssetData](_: Json)
    ))(parse => parse(j))
      .mapError(errs => errs.mkString("(", " | ", ")"))
  }

  private def readAndParsePkgData(path: os.Path): IndexedSeq[Data.PackageData | Data.AssetData] = {
    val docs: IndexedSeq[Either[ParsingFailure, Json]] =
      scala.util.Using.resource(new java.io.FileReader(path.toIO))(io.circe.yaml.parser.parseDocuments(_).toIndexedSeq)
    val task: IO[ErrStr, IndexedSeq[Data.PackageData | Data.AssetData]] = ZIO.validatePar(docs) { doc =>
      ZIO.fromEither(doc).flatMap(parsePkgData)
    }.mapError(errs => s"format error in $path: ${errs.mkString(", ")}")
    unsafeRun(task)  // TODO unsafe
  }

  /** This function (for internal use) reads the yaml package metadata and writes
    * it as json files in the format that is used by MetadataRepository.
    *
    * The advantages of the yaml format are:
    * - comments
    * - terser notation, so easier to write manually
    * - files do not include the version number which avoids the need for renaming
    * - files can include multiple package definitions
    */
  def convertYamlToJson(channelDir: os.Path): Unit = {
    // val channelDir = os.pwd / "channel"
    val tempJsonDir = os.temp.dir(channelDir, prefix = "json", deleteOnExit = true)
    try {
      val packages: Map[(String, String), Seq[String]] = os.walk.stream(channelDir / "yaml")
        .filter(_.last.endsWith(".yaml"))
        .flatMap(readAndParsePkgData)
        .map { pkgData =>
          val (g, n, v) = pkgData match {
            case data: Data.PackageData => (data.group, data.name, data.version)
            case data: Data.AssetData => (Constants.sc4pacAssetOrg.value, data.assetId, data.version)
          }
          val subpath = MetadataRepository.jsonSubPath(g, n, v)
          val target = tempJsonDir / "metadata" / subpath
          os.makeDir.all(target / os.up)
          scala.util.Using.resource(java.nio.file.Files.newBufferedWriter(target.toNIO)) { out =>
            pkgData match {
              case data: Data.PackageData => writeTo(data, out, indent=2)  // writes package json file
              case data: Data.AssetData => writeTo(data, out, indent=2)  // writes asset json file
            }
          }
          ((g, n), v)
        }.toSeq.groupMap(_._1)(_._2)

      val contents = Data.ChannelData(contents = packages.toSeq.map {
        case ((group, name), versions) => Data.ChannelItemData(group = group, name = name, versions = versions)
      })

      scala.util.Using.resource(java.nio.file.Files.newBufferedWriter((tempJsonDir / MetadataRepository.channelContentsFilename).toNIO)) { out =>
        writeTo(contents, out, indent=2)  // writes channel contents json file
      }

      // Finally, we are sure that everything was formatted correctly, so we can
      // move the temp folder to its final destination.
      os.move.over(tempJsonDir / "metadata", channelDir / "json" / "metadata", createFolders = true)
      os.move.over(tempJsonDir / MetadataRepository.channelContentsFilename, channelDir / "json" / MetadataRepository.channelContentsFilename, createFolders = true)
      println(s"Successfully wrote channel contents of ${packages.size} packages.")
    } finally {
      os.remove.all(tempJsonDir)  // deleteOnExit does not seem to work reliably, so explicitly delete temp folder
    }
  }

}
