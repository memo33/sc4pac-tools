package io.github.memo33
package sc4pac

import io.circe.{ParsingFailure, Json}
import upickle.default.{Reader, ReadWriter, writeTo}
import zio.{ZIO, IO, Task, RIO}

import sc4pac.JsonData as JD
import sc4pac.JsonData.{subPathRw, instantRw, bareModuleRw, uriRw}
import sc4pac.MetadataRepository.resolveUriWithSubPath

object ChannelUtil {

  case class YamlVariantData(
    variant: Variant,
    dependencies: Seq[BareModule] = Seq.empty,
    assets: Seq[JD.AssetReference] = Seq.empty,
    conflicting: Seq[BareModule] = Seq.empty,
  ) derives ReadWriter {
    def toVariantData(sharedDependencies: Seq[BareModule], sharedAssets: Seq[JD.AssetReference], sharedConflicting: Seq[BareModule]) = JD.VariantData(
      variant = variant,
      dependencies = (sharedDependencies ++ dependencies).map { mod =>
        JD.Dependency(group = mod.group.value, name = mod.name.value, version = Constants.versionLatestRelease)
      },
      assets = sharedAssets ++ assets,
      conflictingPackages = sharedConflicting ++ conflicting,
    )
  }

  case class YamlVariantInfo(
    variantId: String,
    description: String = "",
    values: Seq[YamlVariantInfo.Value] = Seq.empty,
  ) derives ReadWriter {
    def toVariantInfo = variantId -> JD.VariantInfo(
      description = description,
      valueDescriptions = values.iterator.map(v => (v.value, v.description)).toMap,
      default = values.find(_.default == true).map(_.value),
    )
  }
  object YamlVariantInfo {
    case class Value(
      value: String,
      description: String = "",
      default: Boolean = false,
    ) derives ReadWriter
  }

  // `packages` does not have a default to avoid matching on empty json files or on single packages
  case class YamlPackageDataArray(packages: Seq[YamlPackageData], assets: Seq[YamlAsset] = Seq.empty) derives ReadWriter

  case class YamlPackageData(
    group: String,
    name: String,
    version: String,
    subfolder: os.SubPath,
    info: JD.Info = JD.Info.empty,
    dependencies: Seq[BareModule] = Seq.empty,  // shared between variants
    assets: Seq[JD.AssetReference] = Seq.empty,  // shared between variants
    conflicting: Seq[BareModule] = Seq.empty,  // shared between variants
    variants: Seq[YamlVariantData] = Seq.empty,
    @deprecated("use variantDescriptions instead", since = "0.5.4")
    variantDescriptions: Map[String, Map[String, String]] = Map.empty,  // variantKey -> variantValue -> description
    variantInfo: Seq[YamlVariantInfo] = Seq.empty,
  ) derives ReadWriter {
    def toPackageData(metadataSource: Option[os.SubPath]): ZIO[JD.Channel.Info, ErrStr, JD.Package] = {
      val variants2 = (if (variants.isEmpty) Seq(YamlVariantData(Map.empty)) else variants).map(_.toVariantData(dependencies, assets, conflicting))
      // validate that variants form a DecisionTree
      VariantSelection.DecisionTree.fromVariants(variants2.map(_.variant).zipWithIndex) match {
        case Left(errStr) => ZIO.fail(errStr)
        case Right(_) => ZIO.serviceWith[JD.Channel.Info] { channelInfo =>
          val metadataSourceUrl =
            for {
              baseUri <- channelInfo.metadataSourceUrl
              path    <- metadataSource
            } yield resolveUriWithSubPath(baseUri, path)
          JD.Package(
            group = group, name = name, version = version, subfolder = subfolder, info = info.upgradeWebsites,
            variants = variants2,
            variantDescriptions = variantDescriptions, variantInfo = variantInfo.iterator.map(_.toVariantInfo).toMap,
            variantChoices = JD.Package.buildVariantChoices(variants2),
            metadataSource = metadataSource,  // kept for backward compatibility
            metadataSourceUrl = metadataSourceUrl,
            metadataIssueUrl = channelInfo.metadataIssueUrl.filter(_.getHost == "github.com").map(newIssueUrl(_, metadataSourceUrl)),
            channelLabel = channelInfo.channelLabel,
          ).upgradeVariantInfo
        }
      }
    }

    private def newIssueUrl(metadataIssueUrl: java.net.URI, metadataSourceUrl: Option[java.net.URI]): java.net.URI = {
      val module = BareModule(Organization(group), ModuleName(name)).orgName
      val link = metadataSourceUrl.map(u => s"[`$module`]($u)").getOrElse(s"`$module`")
      val website: String = info.websites.headOption.getOrElse(info.website)
      val websiteLink = if (website.nonEmpty) s" from ${website}" else ""
      val q = zio.http.QueryParams(
        "title" -> s"[$module] New bug report",
        "body" -> s"Package: $link$websiteLink\n\nDescribe the problem here…",
      ).encode(java.nio.charset.StandardCharsets.UTF_8)
      metadataIssueUrl.resolve(s"issues/new$q")
    }
  }

  case class YamlAsset(
    assetId: String,
    version: String,
    url: java.net.URI,
    lastModified: java.time.Instant = null,
    archiveType: JD.ArchiveType = null,
    checksum: JD.Checksum = JD.Checksum.empty,
  ) derives ReadWriter {  // the difference to JD.Asset is that JD.Asset is part of a sealed trait requiring a `$type` field
    def toAsset = JD.Asset(assetId = assetId, version = version, url = url, lastModified = lastModified,
      archiveType = Option(archiveType), requiredBy = Nil, checksum = checksum)
  }

  private def parseCirceJson[A : Reader](json: Json): IO[upickle.core.Abort | IllegalArgumentException, A] = {
    ZIO.attempt(ujson.circe.CirceJson.transform(json, upickle.default.reader[A])).refineToOrDie
  }

  private def parsePkgData(json: Json, metadataSource: Option[os.SubPath]): ZIO[JD.Channel.Info, ErrStr, Seq[JD.PackageAsset]] = {
    ZIO.validateFirst(Seq(  // we use ZIO validate for error accumulation
      parseCirceJson[YamlPackageData](_: Json).flatMap(_.toPackageData(metadataSource).map(_ :: Nil)),
      parseCirceJson[YamlAsset](_: Json).map(_.toAsset :: Nil),
      parseCirceJson[YamlPackageDataArray](_: Json).flatMap { data =>
        for {
          pkgs <- ZIO.foreach(data.packages)(_.toPackageData(metadataSource))
          assets = data.assets.map(_.toAsset)
        } yield pkgs ++ assets
      },
    ))(parse => parse(json))
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
      .map(_.flatten)
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
      try {
        if (packages.nonEmpty) {  // otherwise metadata folder does not exist (we still want channel contents file though)
          os.move.over(tempJsonDir / "metadata", outputDir / "metadata", createFolders = true)
        }
      } catch {
        case e: java.nio.file.DirectoryNotEmptyException =>
          throw new error.FileOpsFailure(
            s"""Failed to overwrite folder ${outputDir / "metadata"}. If the problem persists, delete it manually and try again.""")
      }
      os.move.over(tempJsonDir / JsonRepoUtil.channelContentsFilename, outputDir / JsonRepoUtil.channelContentsFilename, createFolders = true)
      System.err.println(s"Successfully wrote channel contents of ${channel.packages.size} packages and ${channel.assets.size} assets.")
    })

    val packagesTask: RIO[JD.Channel.Info, Seq[JD.PackageAsset]] =
      ZIO.foreach(inputDirs) { inputDir =>
        ZIO.ifZIO(ZIO.attemptBlockingIO(os.exists(inputDir)))(
          onFalse = ZIO.fail(new error.FileOpsFailure(s"$inputDir does not exist.")),
          onTrue = ZIO.foreach(os.walk.stream(inputDir).filter(_.last.endsWith(".yaml")).toSeq) { path =>
            readAndParsePkgData(path, root = Some(inputDir))
          }.map(_.flatten)
        )
      }.map(_.flatten).mapError {
        case errStr: String => error.YamlFormatIssue(errStr)
        case e: java.io.IOException => e
      }

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
    // compute reverse dependencies (for each asset/package, the set of modules
    // that depend on it) and conflicts
    val reverseLinksTask: IO[E, collection.Map[BareDep, (Set[BareModule], Set[BareModule])]] = {
      import scala.jdk.CollectionConverters.*
      for {
        map  <- ZIO.succeed((new java.util.concurrent.ConcurrentHashMap[BareDep, (Set[BareModule], Set[BareModule])]()).asScala)  // concurrent for thread-safe access later on
        _    <- ZIO.foreachParDiscard(packages) {
                  case _: JD.Asset => ZIO.succeed(()) // assets do not depend on or conflict with anything
                  case pkgData: JD.Package =>
                    val mod = pkgData.toBareDep
                    val addDeps =
                      ZIO.foreachDiscard(pkgData.variants.flatMap(_.bareDependencies).distinct) { dep => ZIO.succeed {
                        map.updateWith(dep) {  // mod depends on dep
                          case Some((deps, conflicts)) => Some((deps + mod, conflicts))
                          case None => Some((Set(mod), Set.empty))
                        }
                      }}
                    val addConflicts =
                      ZIO.foreachDiscard(pkgData.variants.flatMap(_.conflictingPackages).distinct) { dep => ZIO.succeed {
                        map.updateWith(dep) {  // mod conflicts with dep
                          case Some((deps, conflicts)) => Some((deps, conflicts + mod))
                          case None => Some((Set.empty, Set(mod)))
                        }
                      }}
                    addDeps.zipRight(addConflicts)
                }
      } yield map
    }

    // add reverseDependencies (requiredBy) and reverseConflictingPackages to each asset/package and write package json files
    def packagesMapTask(reverseLinks: collection.Map[BareDep, (Set[BareModule], Set[BareModule])]): IO[E, Map[BareDep, Seq[(String, JD.PackageAsset, JD.Checksum)]]] =
      ZIO.foreachPar(packages) { pkgData =>
        val dep = pkgData.toBareDep
        val computedRevLinks = reverseLinks.getOrElse(dep, (Set.empty, Set.empty))  // dependencies and conflicts
        // this joins the computed reverseDependencies and those explicitly specified in yaml
        pkgData match {
          case data: JD.Asset =>
            val data2 = data.copy(
              requiredBy = (computedRevLinks._1 ++ data.requiredBy).toSeq.sorted,
            )
            assert(computedRevLinks._2.isEmpty, s"assets cannot have conflicts: $data")
            storeAsset(data2)
              .map(checksum => dep -> (data2.version, data2, checksum))
          case data: JD.Package =>
            val data2 = data.copy(info = data.info.copy(
              requiredBy = (computedRevLinks._1 ++ data.info.requiredBy).toSeq.sorted,
              reverseConflictingPackages = (computedRevLinks._2 ++ data.info.reverseConflictingPackages).toSeq.sorted,
            ))
            storePackage(data2)
              .map(checksum => dep -> (data2.version, data2, checksum))
        }
      }
      .map(_.groupMap(_._1)(_._2))

    def externalTask(
      reverseLinks: collection.Map[BareDep, (Set[BareModule], Set[BareModule])],
      packagesMap: Map[BareDep, Seq[(String, JD.PackageAsset, JD.Checksum)]],
    ): IO[E, (Seq[JD.Channel.ExtPkg], Seq[JD.Channel.ExtAsset])] = {
      ZIO.foreachPar(reverseLinks.iterator.filter(item => !packagesMap.contains(item._1)).toSeq){
        case (module: BareModule, (requiredBy, conflicts)) =>
          val data = JD.ExternalPackage(group = module.group.value, name = module.name.value,
            requiredBy = requiredBy.toSeq.sorted,
            reverseConflictingPackages = conflicts.toSeq.sorted,
          )
          for {
            checksum <- storeExtPackage(data)
          } yield Left(JD.Channel.ExtPkg(group = module.group.value, name = module.name.value, checksum))
        case (asset: BareAsset, (requiredBy, conflicts)) =>
          assert(conflicts.isEmpty, s"assets cannot have conflicts: $asset")
          val data = JD.ExternalAsset(assetId = asset.assetId.value,
            requiredBy = requiredBy.toSeq.sorted)
          for {
            checksum <- storeExtAsset(data)
          } yield Right(JD.Channel.ExtAsset(assetId = asset.assetId.value, checksum = checksum))
      }
      .map(_.partitionMap(identity))
    }

    for {
      reverseLinks         <- reverseLinksTask
      packagesMap          <- packagesMapTask(reverseLinks)
      (extPkgs, extAssets) <- externalTask(reverseLinks, packagesMap)
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
