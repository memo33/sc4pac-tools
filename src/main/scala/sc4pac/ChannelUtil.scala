package io.github.memo33
package sc4pac

import io.circe.{ParsingFailure, Json}
import upickle.default.{Reader, ReadWriter, writeTo}
import zio.{ZIO, IO, Task}
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
    def toPackageData(metadataSource: Option[os.SubPath]): IO[ErrStr, JD.Package] = {
      val variants2 = (if (variants.isEmpty) Seq(YamlVariantData(Map.empty)) else variants).map(_.toVariantData(dependencies, assets))
      // validate that variants form a DecisionTree
      Sc4pac.DecisionTree.fromVariants(variants2.map(_.variant)) match {
        case Left(errStr) => ZIO.fail(errStr)
        case Right(_) => ZIO.succeed(JD.Package(
          group = group, name = name, version = version, subfolder = subfolder, info = info,
          variants = variants2,
          variantDescriptions = variantDescriptions, metadataSource = metadataSource))
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

  private def parsePkgData(j: Json, metadataSource: Option[os.SubPath]): IO[ErrStr, JD.PackageAsset] = {
    ZIO.validateFirst(Seq(  // we use ZIO validate for error accumulation
      parseCirceJson[YamlPackageData](_: Json).flatMap(_.toPackageData(metadataSource)),
      parseCirceJson[YamlAsset](_: Json).map(_.toAsset)
    ))(parse => parse(j))
      .mapError(errs => errs.mkString("(", " | ", ")"))
  }

  def readAndParsePkgData(path: os.Path, root: Option[os.Path]): IO[ErrStr, IndexedSeq[JD.PackageAsset]] = {
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

  private def writePackageJsonBlocking(pkgData: JD.PackageAsset, tempJsonDir: os.Path): JD.Checksum = {
    val dep = pkgData.toBareDep
    val target = tempJsonDir / MetadataRepository.jsonSubPath(dep, pkgData.version)
    os.makeDir.all(target / os.up)
    scala.util.Using.resource(java.nio.file.Files.newBufferedWriter(target.toNIO)) { out =>
      pkgData match {
        case data: JD.Package => writeTo(data, out, indent=1)  // writes package json file
        case data: JD.Asset => writeTo(data, out, indent=1)  // writes asset json file
      }
    }
    JD.Checksum(sha256 = Some(Downloader.computeChecksum(target.toIO)))
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
  def convertYamlToJson(inputDirs: Seq[os.Path], outputDir: os.Path): Task[Unit] = {

    def processPackages(packages: Seq[JD.PackageAsset], tempJsonDir: os.Path): Task[Unit] = ZIO.attemptBlockingIO {
      // compute dependents (reverse dependencies)
      val dependents: collection.Map[BareDep, Set[BareModule]] = {
        val m = collection.mutable.Map.empty[BareDep, Set[BareModule]]
        packages.foreach {
          case _: JD.Asset => // assets do not depend on anything
          case pkgData: JD.Package =>
            val mod = pkgData.toBareDep
            pkgData.variants.foreach(_.bareDependencies.foreach { dep =>
              m(dep) = m.getOrElse(dep, Set.empty) + mod  // mod depends on dep
            })
        }
        m
      }

      // add dependents (requiredBy) and write package json files
      val packagesMap: Map[BareDep, Seq[(String, JD.PackageAsset, JD.Checksum)]] =
        packages.map { pkgData =>
          val computed = dependents.getOrElse(pkgData.toBareDep, Set.empty)
          // this joins the computed dependents and the dependents explicitly specified in yaml
          val pkgData2 = pkgData match {
            case data: JD.Asset =>
              data.copy(requiredBy =
                (computed ++ data.requiredBy).toSeq.sortBy(i => (i.group, i.name)))
            case data: JD.Package =>
              data.copy(info = data.info.copy(requiredBy =
                (computed ++ data.info.requiredBy).toSeq.sortBy(i => (i.group, i.name))))
          }
          val checksum = writePackageJsonBlocking(pkgData2, tempJsonDir)    // writing json as side effect
          pkgData2.toBareDep -> (pkgData2.version, pkgData2, checksum)
        }.groupMap(_._1)(_._2)

      // write channel contents
      val channel = {
        val c = JD.Channel.create(scheme = Constants.channelSchemeVersions.max, packagesMap)
        c.copy(contents = c.contents.sortBy(item => (item.group, item.name)))
      }
      scala.util.Using.resource(java.nio.file.Files.newBufferedWriter((tempJsonDir / JsonRepoUtil.channelContentsFilename).toNIO)) { out =>
        writeTo(channel, out, indent=1)  // writes channel contents json file
      }

      // create symlinks for latest versions
      channel.contents.foreach { item =>
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
      System.err.println(s"Successfully wrote channel contents of ${packagesMap.size} packages and assets.")
    }

    val packagesTask: Task[Seq[JD.PackageAsset]] =
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
        packagesTask.flatMap(processPackages(_, tempJsonDir))
      }
    }
  }

}
