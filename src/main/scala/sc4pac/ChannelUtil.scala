package io.github.memo33
package sc4pac

import io.circe.{ParsingFailure, Json}
import upickle.default.{Reader, ReadWriter, writeTo}
import zio.{ZIO, IO}
import coursier.core as C

import sc4pac.JsonData as JD
import sc4pac.JsonData.{osSubPathRw, instantRw}

object ChannelUtil {

  case class YamlVariantData(
    variant: Variant,
    dependencies: Seq[String] = Seq.empty,
    assets: Seq[JD.AssetReference] = Seq.empty
  ) derives ReadWriter {
    def toVariantData = JD.VariantData(
      variant = variant,
      dependencies = coursier.parse.ModuleParser.modules(dependencies, defaultScalaVersion = "").either match {
        case Left(errs) => throw new IllegalArgumentException(s"format error in dependencies: ${errs.mkString(", ")}")  // TODO error reporting
        case Right(modules) => modules.map(mod => JD.Dependency(group = mod.organization.value, name = mod.name.value, version = "latest.release"))
      },
      assets = assets)
  }

  case class YamlPackageDataVariants(
    group: String,
    name: String,
    version: String,
    subfolder: os.SubPath,
    info: JD.Info = JD.Info.empty,
    variants: Seq[YamlVariantData],
    variantDescriptions: Map[String, Map[String, String]] = Map.empty  // variantKey -> variantValue -> description
  ) derives ReadWriter {
    def toPackageData = JD.Package(
      group = group, name = name, version = version, subfolder = subfolder,
      info = info, variants = variants.map(_.toVariantData), variantDescriptions = variantDescriptions)
  }

  case class YamlPackageDataBasic(
    group: String,
    name: String,
    version: String,
    subfolder: os.SubPath,
    info: JD.Info = JD.Info.empty,
    dependencies: Seq[String] = Seq.empty,
    assets: Seq[JD.AssetReference] = Seq.empty
  ) derives ReadWriter {
    def toVariants = YamlPackageDataVariants(
      group = group, name = name, version = version, subfolder = subfolder, info = info,
      variants = Seq(YamlVariantData(variant = Map.empty, dependencies = dependencies, assets = assets)))
  }

  case class YamlAsset(
    assetId: String,
    version: String,
    url: String,
    lastModified: java.time.Instant = null
  ) derives ReadWriter {  // the difference to JD.Asset is that JD.Asset is part of a sealed trait requiring a `$type` field
    def toAsset = JD.Asset(assetId = assetId, version = version, url = url, lastModified = lastModified)
  }

  private def parseCirceJson[A : Reader](j: Json): IO[upickle.core.Abort | IllegalArgumentException, A] = {
    ZIO.attempt(ujson.circe.CirceJson.transform(j, upickle.default.reader[A])).refineToOrDie
  }

  private def parsePkgData(j: Json): IO[ErrStr, JD.PackageAsset] = {
    ZIO.validateFirst(Seq(  // we use ZIO validate for error accumulation
      parseCirceJson[YamlPackageDataVariants](_: Json).map(_.toPackageData),
      parseCirceJson[YamlPackageDataBasic](_: Json).map(_.toVariants.toPackageData),  // if `variants` is absent, try YamlPackageDataBasic
      parseCirceJson[YamlAsset](_: Json).map(_.toAsset)
    ))(parse => parse(j))
      .mapError(errs => errs.mkString("(", " | ", ")"))
  }

  def readAndParsePkgData(path: os.Path): IO[ErrStr, IndexedSeq[JD.PackageAsset]] = {
    val docs: IndexedSeq[Either[ParsingFailure, Json]] =
      scala.util.Using.resource(new java.io.FileReader(path.toIO))(io.circe.yaml.parser.parseDocuments(_).toIndexedSeq)
    ZIO.validatePar(docs) { doc =>
      ZIO.fromEither(doc).flatMap(parsePkgData)
    }.mapError(errs => s"format error in $path: ${errs.mkString(", ")}")
  }

  /** This function reads the yaml package metadata and writes
    * it as json files in the format that is used by MetadataRepository.
    *
    * The advantages of the yaml format are:
    * - comments
    * - terser notation, so easier to write manually
    * - files do not include the version number which avoids the need for renaming
    * - files can include multiple package definitions
    */
  def convertYamlToJson(inputDirs: Seq[os.Path], outputDir: os.Path): Unit = {
    os.makeDir.all(outputDir)
    val tempJsonDir = os.temp.dir(outputDir, prefix = "json", deleteOnExit = true)
    try {
      val packages: Map[BareDep, Seq[(String, JD.PackageAsset)]] = inputDirs.flatMap { inputDir =>
        os.walk.stream(inputDir)
          .filter(_.last.endsWith(".yaml"))
          .flatMap(path => unsafeRun(readAndParsePkgData(path)): IndexedSeq[JD.PackageAsset])  // TODO unsafe
          .map { pkgData =>
            val dep = pkgData.toBareDep
            val target = tempJsonDir / MetadataRepository.jsonSubPath(dep, pkgData.version)
            os.makeDir.all(target / os.up)
            scala.util.Using.resource(java.nio.file.Files.newBufferedWriter(target.toNIO)) { out =>
              pkgData match {
                case data: JD.Package => writeTo(data, out, indent=2)  // writes package json file
                case data: JD.Asset => writeTo(data, out, indent=2)  // writes asset json file
              }
            }
            (dep, (pkgData.version, pkgData))
          }.toSeq
      }.groupMap(_._1)(_._2)

      val channel = {
        val c = JD.Channel.create(packages)
        c.copy(contents = c.contents.sortBy(item => (item.group, item.name)))
      }

      scala.util.Using.resource(java.nio.file.Files.newBufferedWriter((tempJsonDir / MetadataRepository.channelContentsFilename).toNIO)) { out =>
        writeTo(channel, out, indent=2)  // writes channel contents json file
      }

      // create symlinks for latest versions
      channel.contents.foreach { item =>
        val latest = item.versions.map(coursier.core.Version(_)).max
        os.symlink(tempJsonDir / MetadataRepository.latestSubPath(item.group, item.name), os.rel / latest.repr)
      }

      // Finally, we are sure that everything was formatted correctly, so we can
      // move the temp folder to its final destination.
      os.move.over(tempJsonDir / "metadata", outputDir / "metadata", createFolders = true)
      os.move.over(tempJsonDir / MetadataRepository.channelContentsFilename, outputDir / MetadataRepository.channelContentsFilename, createFolders = true)
      println(s"Successfully wrote channel contents of ${packages.size} packages.")
    } finally {
      os.remove.all(tempJsonDir)  // deleteOnExit does not seem to work reliably, so explicitly delete temp folder
    }
  }

}
