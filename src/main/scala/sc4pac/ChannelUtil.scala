package io.github.memo33
package sc4pac

import io.circe.{ParsingFailure, Json}
import upickle.default.{Reader, ReadWriter, writeTo}
import zio.{ZIO, IO, Task, RIO}
import coursier.core as C

import sc4pac.JsonData as JD
import sc4pac.JsonData.{subPathRw, instantRw, bareModuleRw}

object ChannelUtil {

  case class YamlVariantData(
    variant: Variant,
    dependencies: Seq[BareModule] = Seq.empty,
    assets: Seq[JD.AssetReference] = Seq.empty
  ) derives ReadWriter {
    def toVariantData(sharedDependencies: Seq[BareModule], sharedAssets: Seq[JD.AssetReference]) = JD.VariantData(
      variant = variant,
      dependencies = (sharedDependencies ++ dependencies).map { mod =>
        JD.Dependency(group = mod.group.value, name = mod.name.value, version = Constants.versionLatestRelease)
      },
      assets = sharedAssets ++ assets
    )
  }

  case class YamlPackageData(
    group: String,
    name: String,
    version: String,
    subfolder: os.SubPath,
    info: JD.Info = JD.Info.empty,
    dependencies: Seq[BareModule] = Seq.empty,  // shared between variants
    assets: Seq[JD.AssetReference] = Seq.empty,  // shared between variants
    variants: Seq[YamlVariantData] = Seq.empty,
    variantDescriptions: Map[String, Map[String, String]] = Map.empty  // variantKey -> variantValue -> description
  ) derives ReadWriter {
    def toPackageData(metadataSource: Option[os.SubPath]): ZIO[JD.Channel.Info, ErrStr, JD.Package] = {
      val variants2 = (if (variants.isEmpty) Seq(YamlVariantData(Map.empty)) else variants).map(_.toVariantData(dependencies, assets))
      // validate that variants form a DecisionTree
      Sc4pac.DecisionTree.fromVariants(variants2.map(_.variant)) match {
        case Left(errStr) => ZIO.fail(errStr)
        case Right(_) => ZIO.serviceWith[JD.Channel.Info] { channelInfo => JD.Package(
          group = group, name = name, version = version, subfolder = subfolder, info = info,
          variants = variants2,
          variantDescriptions = variantDescriptions,
          metadataSource = metadataSource,  // kept for backward compatibility
          metadataSourceUrl =
            for {
              baseUri <- channelInfo.metadataSourceUrl
              path    <- metadataSource
            } yield baseUri.resolve(path.segments0.mkString("/")),
          channelLabel = channelInfo.channelLabel,
        )}
      }
    }
  }

  case class YamlAsset(
    assetId: String,
    version: String,
    url: String,
    lastModified: java.time.Instant = null,
    archiveType: JD.ArchiveType = null,
  ) derives ReadWriter {  // the difference to JD.Asset is that JD.Asset is part of a sealed trait requiring a `$type` field
    def toAsset = JD.Asset(assetId = assetId, version = version, url = url, lastModified = lastModified, archiveType = Option(archiveType), requiredBy = Nil)
  }

  private def parseCirceJson[A : Reader](j: Json): IO[upickle.core.Abort | IllegalArgumentException, A] = {
    ZIO.attempt(ujson.circe.CirceJson.transform(j, upickle.default.reader[A])).refineToOrDie
  }

  private def parsePkgData(j: Json, metadataSource: Option[os.SubPath]): ZIO[JD.Channel.Info, ErrStr, JD.PackageAsset] = {
    ZIO.validateFirst(Seq(  // we use ZIO validate for error accumulation
      parseCirceJson[YamlPackageData](_: Json).flatMap(_.toPackageData(metadataSource)),
      parseCirceJson[YamlAsset](_: Json).map(_.toAsset)
    ))(parse => parse(j))
      .mapError(errs => errs.mkString("(", " | ", ")"))
  }

  def readAndParsePkgData(path: os.Path, root: Option[os.Path]): ZIO[JD.Channel.Info, ErrStr, IndexedSeq[JD.PackageAsset]] = {
    val metadataSource = root.map(path.subRelativeTo)
    val docs: IndexedSeq[Either[ParsingFailure | org.yaml.snakeyaml.scanner.ScannerException | org.yaml.snakeyaml.parser.ParserException, Json]] =
      scala.util.Using.resource(new java.io.FileReader(path.toIO)){ reader =>
        try {
          io.circe.yaml.parser.parseDocuments(reader).toIndexedSeq
            .filter(either => !either.exists(_.isNull))  // this allows empty documents
        } catch {  // apparently io.circe does not catch these
          case e: (org.yaml.snakeyaml.scanner.ScannerException | org.yaml.snakeyaml.parser.ParserException) =>
            IndexedSeq(Left(e))
        }
      }
    ZIO.validatePar(docs) { doc =>
      ZIO.fromEither(doc).flatMap(parsePkgData(_, metadataSource))
    }.mapError(errs => s"Format error in $path: ${errs.mkString(", ")}")
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
  def convertYamlToJson(inputDirs: Seq[os.Path], outputDir: os.Path): RIO[JD.Channel.Info, Unit] = {

    def writeChannel(
      packages: Seq[JD.PackageAsset],
      tempJsonDir: os.Path,
    ): RIO[JD.Channel.Info, Unit] = JsonChannelBuilder(tempJsonDir).result(packages).flatMap(channel => ZIO.attemptBlockingIO {
      // write channel contents
      scala.util.Using.resource(java.nio.file.Files.newBufferedWriter((tempJsonDir / JsonRepoUtil.channelContentsFilename).toNIO)) { out =>
        writeTo(channel, out, indent=1)  // writes channel contents json file
      }

      // create symlinks for latest versions
      (channel.packages.iterator ++ channel.assets).foreach { item =>
        val latest = item.versions.map(coursier.core.Version(_)).max
        val link = tempJsonDir / MetadataRepository.latestSubPath(item.group, item.name)
        val linkTarget = os.rel / latest.repr
        try {
          os.symlink(link, linkTarget)
        } catch {
          case e: java.io.IOException =>
            if (Constants.debugMode) {
              e.printStackTrace()
            }
            // support for (b) was added in Java 13 by https://bugs.openjdk.org/browse/JDK-8218418
            throw new error.SymlinkCreationFailed(s"""Failed to create symbolic link $link -> $linkTarget.
              |On Windows, use one of these workarounds:
              |
              |  (a) Rerun the command in a shell with administrator privileges.
              |  (b) Use Java 13+ and enable Windows Developer Mode on your device. See:
              |      https://learn.microsoft.com/en-us/windows/apps/get-started/enable-your-device-for-development
              |  (c) Enable the privilege "SeCreateSymbolicLinkPrivilege" for your user account. See:
              |      https://learn.microsoft.com/en-us/previous-versions/windows/it-pro/windows-10/security/threat-protection/security-policy-settings/create-symbolic-links
              |""".stripMargin)
        }
      }

      // Finally, we are sure that everything was formatted correctly, so we can
      // move the temp folder to its final destination.
      os.move.over(tempJsonDir / "metadata", outputDir / "metadata", createFolders = true)
      os.move.over(tempJsonDir / JsonRepoUtil.channelContentsFilename, outputDir / JsonRepoUtil.channelContentsFilename, createFolders = true)
      System.err.println(s"Successfully wrote channel contents of ${channel.packages.size} packages and ${channel.assets.size} assets.")
    })

    val packagesTask: RIO[JD.Channel.Info, Seq[JD.PackageAsset]] =
      ZIO.foreach(inputDirs) { inputDir =>
        ZIO.foreach(os.walk.stream(inputDir).filter(_.last.endsWith(".yaml")).toSeq) { path =>
          readAndParsePkgData(path, root = Some(inputDir))
        }.map(_.flatten)
      }.map(_.flatten)
      .mapError(error.YamlFormatIssue(_))

    // result
    ZIO.blocking {
      ZIO.acquireReleaseWith(  /*acquire*/
        ZIO.attemptBlockingIO {
          os.makeDir.all(outputDir)
          os.temp.dir(outputDir, prefix = "json", deleteOnExit = true)  // tempJsonDir
        }
      )(  /*release*/  // deleteOnExit does not seem to work reliably, so explicitly delete temp folder after use
        tempJsonDir => ZIO.succeed(os.remove.all(tempJsonDir))
      ){ tempJsonDir =>  /*use*/
        packagesTask.flatMap(writeChannel(_, tempJsonDir))
      }
    }
  }

}

trait ChannelBuilder[+E] {
  def storePackage(data: JD.Package): IO[E, JD.Checksum]
  def storeAsset(data: JD.Asset): IO[E, JD.Checksum]
  def storeExtPackage(data: JD.ExternalPackage): IO[E, JD.Checksum]
  def storeExtAsset(data: JD.ExternalAsset): IO[E, JD.Checksum]

  def result(packages: Seq[JD.PackageAsset]): ZIO[JD.Channel.Info, E, JD.Channel] = {
    // compute reverse dependencies (for each asset/package, the set of modules that depend on it)
    val reverseDependenciesTask: IO[E, collection.Map[BareDep, Set[BareModule]]] = {
      import scala.jdk.CollectionConverters.*
      for {
        map  <- ZIO.succeed((new java.util.concurrent.ConcurrentHashMap[BareDep, Set[BareModule]]()).asScala)  // concurrent for thread-safe access later on
        _    <- ZIO.foreachParDiscard(packages) {
                  case _: JD.Asset => ZIO.succeed(()) // assets do not depend on anything
                  case pkgData: JD.Package =>
                    val mod = pkgData.toBareDep
                    ZIO.foreachDiscard(pkgData.variants.flatMap(_.bareDependencies).distinct) { dep => ZIO.succeed {
                      map.updateWith(dep) {  // mod depends on dep
                        case Some(mods) => Some(mods + mod)
                        case None => Some(Set(mod))
                      }
                    }}
                }
      } yield map
    }

    // add reverseDependencies (requiredBy) to each asset/package and write package json files
    def packagesMapTask(reverseDependencies: collection.Map[BareDep, Set[BareModule]]): IO[E, Map[BareDep, Seq[(String, JD.PackageAsset, JD.Checksum)]]] =
      ZIO.foreachPar(packages) { pkgData =>
        val computed = reverseDependencies.getOrElse(pkgData.toBareDep, Set.empty)
        // this joins the computed reverseDependencies and those explicitly specified in yaml
        pkgData match {
          case data: JD.Asset =>
            val data2 = data.copy(requiredBy =
              (computed ++ data.requiredBy).toSeq.sorted)
            storeAsset(data2)
              .map(checksum => data2.toBareDep -> (data2.version, data2, checksum))
          case data: JD.Package =>
            val data2 = data.copy(info = data.info.copy(requiredBy =
              (computed ++ data.info.requiredBy).toSeq.sorted))
            storePackage(data2)
              .map(checksum => data2.toBareDep -> (data2.version, data2, checksum))
        }
      }
      .map(_.groupMap(_._1)(_._2))

    def externalTask(
      reverseDependencies: collection.Map[BareDep, Set[BareModule]],
      packagesMap: Map[BareDep, Seq[(String, JD.PackageAsset, JD.Checksum)]],
    ): IO[E, (Seq[JD.Channel.ExtPkg], Seq[JD.Channel.ExtAsset])] = {
      ZIO.foreachPar(reverseDependencies.iterator.filter(item => !packagesMap.contains(item._1)).toSeq){
        case (module: BareModule, requiredBy) =>
          val data = JD.ExternalPackage(group = module.group.value, name = module.name.value,
            requiredBy = requiredBy.toSeq.sorted)
          for {
            checksum <- storeExtPackage(data)
          } yield Left(JD.Channel.ExtPkg(group = module.group.value, name = module.name.value, checksum))
        case (asset: BareAsset, requiredBy) =>
          val data = JD.ExternalAsset(assetId = asset.assetId.value,
            requiredBy = requiredBy.toSeq.sorted)
          for {
            checksum <- storeExtAsset(data)
          } yield Right(JD.Channel.ExtAsset(assetId = asset.assetId.value, checksum = checksum))
      }
      .map(_.partitionMap(identity))
    }

    for {
      reverseDependencies  <- reverseDependenciesTask
      packagesMap          <- packagesMapTask(reverseDependencies)
      (extPkgs, extAssets) <- externalTask(reverseDependencies, packagesMap)
      info                 <- ZIO.service[JD.Channel.Info]
    } yield {
      val channel = JD.Channel.create(
        scheme = Constants.channelSchemeVersions.max,
        info,
        packagesMap,
        extPkgs.sortBy(i => (i.group, i.name)),
        extAssets.sortBy(i => i.assetId),
      )
      channel.copy(
        packages = channel.packages.sortBy(item => (item.group, item.name)),
        assets = channel.assets.sortBy(item => (item.group, item.name)),
      )
    }
  }
}

class JsonChannelBuilder(tempJsonDir: os.Path) extends ChannelBuilder[Throwable] {

  private def writePackageJsonBlocking[A : ReadWriter](pkgData: A, target: os.Path): JD.Checksum = {
    os.makeDir.all(target / os.up)
    scala.util.Using.resource(java.nio.file.Files.newBufferedWriter(target.toNIO)) { out =>
      writeTo(pkgData, out, indent = 1)  // writes package/asset json file
    }
    JD.Checksum(sha256 = Some(Downloader.computeChecksum(target.toIO)))
  }

  def storePackage(data: JD.Package): Task[JD.Checksum] = ZIO.attemptBlockingIO {
    val target = tempJsonDir / MetadataRepository.jsonSubPath(data.toBareDep, data.version)
    writePackageJsonBlocking(data, target)
  }

  def storeAsset(data: JD.Asset): Task[JD.Checksum] = ZIO.attemptBlockingIO {
    val target = tempJsonDir / MetadataRepository.jsonSubPath(data.toBareDep, data.version)
    writePackageJsonBlocking(data, target)
  }

  def storeExtPackage(data: JD.ExternalPackage): Task[JD.Checksum] = ZIO.attemptBlockingIO {
    val target = tempJsonDir / MetadataRepository.extPkgJsonSubPath(data.toBareDep)
    writePackageJsonBlocking(data, target)
  }

  def storeExtAsset(data: JD.ExternalAsset): Task[JD.Checksum] = ZIO.attemptBlockingIO {
    val target = tempJsonDir / MetadataRepository.extPkgJsonSubPath(data.toBareDep)
    writePackageJsonBlocking(data, target)
  }
}
