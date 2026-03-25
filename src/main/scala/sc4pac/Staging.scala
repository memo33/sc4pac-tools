package io.github.memo33
package sc4pac

import zio.{ZIO, IO, Task, RIO, Scope, Chunk}
import sc4pac.Resolution.{Dep, DepModule, DepAsset}
import sc4pac.Staging.{StagingDirs, StageResult}
import sc4pac.{JsonData => JD}
import sc4pac.Constants.isDll
import sc4pac.Sc4pac.UpdatePlan
import sc4pac.error.Sc4pacPublishIncomplete
import sc4pac.Fetcher.Blob

class Staging(logger: Logger, fileSys: service.FileSystem) {

  def makeStagingDirs(tempRoot: os.Path): ZIO[Scope, java.io.IOException, StagingDirs] =
    ZIO.acquireRelease(
      acquire = ZIO.attemptBlockingIO {
        os.makeDir.all(tempRoot)
        val stagingRoot = os.temp.dir(tempRoot, prefix = "staging-process", deleteOnExit = false)  // deleteOnExit does not seem to work reliably, so explicitly delete temp folder
        logger.debug(s"Creating temp staging dir: $stagingRoot")
        val plugins = stagingRoot / "plugins"
        os.makeDir.all(plugins)
        StagingDirs(root = stagingRoot, plugins = plugins, nested = stagingRoot / "nested")
      }
    )(
      release = (stagingDirs: StagingDirs) => ZIO.attemptBlockingIO {  // TODO not executed in case of interrupt, so consider cleaning up temp dir from previous runs regularly.
        logger.debug(s"Deleting temp staging dir: ${stagingDirs.root}")
        Sc4pac.removeAllForcibly(stagingDirs.root)
      }.catchAll {
        case e => ZIO.succeed(logger.warn(s"Failed to remove temp folder ${stagingDirs.root}: ${e.getMessage}"))
      }
    )

  private def packageFolderName(dependency: DepModule): String = {
    val variantTokens = dependency.variant.toSeq.sortBy(_._1).map(_._2)
    val variantLabel = if (variantTokens.isEmpty) "" else variantTokens.mkString(".", "-", "")
    // we avoid the colons since they would need escaping in shells
    s"${dependency.group.value}.${dependency.name.value}$variantLabel.${dependency.version}.sc4pac"
  }

  /** Creates links for dll files from plugins root folder to package subfolder.
    * If link creation fails, this moves the DLL files from package subfolder to plugins root. */
  private def linkDll(stagingDirs: StagingDirs, dll: os.Path): Task[os.SubPath] =
    ZIO.attemptBlockingIO {
      val dllLink = stagingDirs.plugins / dll.last
      if (os.isLink(dllLink) || os.exists(dllLink)) {  // (note that `dllLink` may be an actual file on Windows)
        // This should not usually happen as it means two packages contain
        // the same DLL and are installed during the same `update` process.
        // If the DLL already exists in the actual plugins, we don't even catch that.
        val _ = os.remove(dllLink, checkExists = false)  // ignoring result for now
      }
      val linkTarget = dll.subRelativeTo(stagingDirs.plugins)
      try {
        fileSys.createSymLink(dllLink, linkTarget)
      } catch { case _: java.io.IOException =>
        // On Windows, symbolic link creation usually fails without elevated privileges
        // (see documentation of channel-build command), so instead of using a link,
        // we just move the DLL file to the plugins root as a fallback.
        logger.debug(s"Failed to create symbolic link $dllLink -> $linkTarget. Moving file to plugins root instead.")
        os.move(dll, dllLink)
      }
      dllLink.subRelativeTo(stagingDirs.plugins)
    }

  /** Stage a single package into the temp plugins folder and return a list of
    * files or folders containing the files belonging to the package,
    * the JD.Package info containing metadata relevant for the lock file,
    * and any warnings.
    */
  private def stageOne(
    stagingDirs: StagingDirs,
    dependency: DepModule,
    artifactsById: Map[BareAsset, (Artifact, Blob, DepAsset)],
    progress: Sc4pac.Progress,
    resolution: Resolution,
    pathLimit: Option[Int],
  ): Task[StageResult.Item] = {
    def extract(assetData: JD.AssetReference, pkgFolder: os.SubPath, variant: Variant, debugModule: BareModule): Task[(BareAsset, Chunk[Extractor.ExtractedItem], Seq[JD.Warning])] = {
      // Given an AssetReference, we look up the corresponding artifact file
      // by ID. This relies on the 1-to-1-correspondence between sc4pacAssets
      // and artifact files.
      val id = BareAsset(ModuleName(assetData.assetId))
      artifactsById.get(id) match {
        case None =>
          ZIO.succeed((
            id,
            Chunk.empty,
            Seq(JD.UnexpectedWarning(
              s"An asset is missing and has been skipped, so it needs to be installed manually: ${id.orgName}. " +
              "Please report this to the maintainers of the package metadata."
            )),
          ))
        case Some(art, blob, depAsset) =>
          val (recipe, regexWarnings) = Extractor.InstallRecipe.fromAssetReference(assetData, variant)
          val extractor = new Extractor(logger)

          def doExtract(fallbackFilename: Option[String]) = try {
            extractor.extract(
              blob.path.toIO,
              fallbackFilename,
              stagingDirs.plugins / pkgFolder,
              recipe,
              Some(Extractor.JarExtraction.fromUrl(art.url, jarsRoot = stagingDirs.nested)),
              hints = depAsset.archiveType,
              stagingDirs.root,
              validate = true,
            )
          } catch { case e: error.ExtractionFailed =>
            throw e.withDetail((Seq(
              e.detail, "",
              "Redownloading the package might resolve the problem in case of issues with the downloaded file:",
              s"- Package: ${debugModule.orgName}",
              s"- Asset ID: ${assetData.assetId}",
              // s"- Package folder: $pkgFolder",
            ) ++ Option.when(variant.nonEmpty)(
              s"- Variant: ${JD.VariantData.variantString(variant)}",
            ) ++ Seq(
              s"- Downloaded and cached file: ${blob.path}",
            )).mkString(f"%n"))
          }

          for {
            fallbackFilename <- blob.fallbackFilename
            blobSize         <- ZIO.attemptBlockingIO(os.size(blob.path))
            (files, patterns)<- if (blobSize >= Constants.largeArchiveSizeInterruptible) {
                                  logger.debug(s"(Interruptible extraction of ${assetData.assetId})")
                                  // comes at a performance cost, so we only make extraction interruptible for large files
                                  ZIO.attemptBlockingInterrupt(doExtract(fallbackFilename))
                                } else {
                                  ZIO.attemptBlocking(doExtract(fallbackFilename))
                                }
          } yield {
            val pathLimitWarningOpt =
              for (limit <- pathLimit if files.exists(_.path.toString.length > limit))
              yield JD.UnexpectedWarning(
                f"The file paths of some of the extracted plugin files are too long. They exceed the character limit of your operating system, so the game may have issues loading these files.%n%n" +
                f"To solve this, install ${Constants.startupPerformanceOptimizationDll.markdown}."
              )
            // TODO catch IOExceptions
            (id, files, regexWarnings ++ recipe.usedPatternWarnings(patterns, id, short = false) ++ pathLimitWarningOpt)
          }
      }
    }

    val module: BareModule = dependency.toBareDep
    // Since dependency is of type DepModule, we have already resolved the variant successfully, so it must already exist.
    val (pkgData, variantData) = resolution.metadata(module).toOption.get
    val pkgFolder = pkgData.subfolder / packageFolderName(dependency)
    for {
      extractions        <- logger.extractingPackage(dependency, progress)(for {
                              _         <- ZIO.attemptBlocking(os.makeDir.all(stagingDirs.plugins / pkgFolder))  // create folder even if package does not have any assets or files
                              extracted <- ZIO.foreach(variantData.assets)(extract(_, pkgFolder, variant = variantData.variant, debugModule = module))
                            } yield extracted)  // (asset, files, warnings)
      artifactWarnings   =  extractions.flatMap(_._3)
      _                  <- ZIO.foreach(artifactWarnings)(w => ZIO.attempt(logger.warn(w.value)))  // to avoid conflicts with spinner animation, we print warnings after extraction already finished
      luaScripts         <- ZIO.attemptBlockingIO(extractions.flatMap { case (id, files, _) =>
                              files.filter(f => Extractor.DbpfValidator.hasDbpfSignatureSync(f.path)).map((id, _))  // this check must run before `linkDll` as the latter can move the DLL to a different location
                            })
                            .flatMap(ZIO.foreach(_) {
                              case (id: BareAsset, item: Extractor.ExtractedItem) =>
                                val subpath = item.path.subRelativeTo(stagingDirs.plugins)
                                val asset = artifactsById(id)._3
                                for {
                                  unsafeTgis <- ScriptCheck.collectUnsafeTgis(item.path)
                                                  .mapError { (e: java.io.IOException) =>
                                                    error.ExtractionFailed(
                                                      s"""Failed to install package "${module.orgName}" as it seems to contain a corrupted DBPF file.""",
                                                      Seq(
                                                        "This file does not seem to be a valid DBPF file. Please inform its author about the issue.",
                                                        s"- Corrupted file: $subpath",
                                                        s"- Asset ID: ${id.assetId.value}",
                                                        s"- Downloaded from: ${asset.url}",
                                                        s"- Cause: $e",
                                                      ).mkString(f"%n")
                                                    )
                                                  }
                                } yield Option.unless(unsafeTgis.isEmpty) {
                                  StageResult.LuaInstalled(
                                    subpath, dependency, asset,
                                    pkgMetadataUrl = resolution.metadataUrls(module),
                                    assetMetadataUrl = resolution.metadataUrls(id),
                                    tgis = unsafeTgis.map(_._1),
                                  )
                                }
                            })
                            .map(_.flatten)
      dlls               <- ZIO.foreach(extractions.flatMap { case (id, files, _) =>
                              files.filter(f => isDll(f.path)).map((id, _))
                            }) {
                              case (id: BareAsset, item: Extractor.ExtractedItem) =>
                                for {
                                  dll <- linkDll(stagingDirs, item.path)  // this potentially moves the DLL away from its original location
                                } yield {
                                  assert(item.validatedSha256.isDefined, s"Checksum of DLL ${item.path} (${id.assetId}) should have been verified")
                                  StageResult.DllInstalled(
                                    dll, dependency, artifactsById(id)._3,
                                    pkgMetadataUrl = resolution.metadataUrls(module),
                                    assetMetadataUrl = resolution.metadataUrls(id),
                                    validatedSha256 = item.validatedSha256.get,
                                  )
                                }
                            }
      warningOpt         <- ZIO.when(pkgData.info.warning.nonEmpty)(ZIO.attempt { val w = pkgData.info.warning; logger.warn(w); JD.InformativeWarning(w) })
    } yield StageResult.Item(dependency, Seq(pkgFolder) ++ dlls.map(_.dll), pkgData, warningOpt.toSeq ++ artifactWarnings, dlls, luaScripts)
  }

  /** For the list of non-asset packages to install, extract all of them
    * into a temporary staging plugins folder and for each package, return the
    * list of files or folders (to be stored for later uninstallation).
    * If everything is properly extracted, the files are later moved to the
    * actual plugins folder in the publication step.
    */
  def stageAll(stagingDirs: StagingDirs, deps: Seq[DepModule], fetchedAssets: Seq[(DepAsset, Artifact, Blob)], resolution: Resolution, pathLimit: Option[Int]): Task[StageResult] = {
    val artifactsById = fetchedAssets.map((dep, art, blob) => dep.toBareDep -> (art, blob, dep)).toMap
    val numDeps = deps.length
    for {
      _           <- ZIO.attempt(require(artifactsById.size == fetchedAssets.size, s"artifactsById is not 1-to-1: $fetchedAssets"))
      stagedItems <- ZIO.foreach(deps.zipWithIndex) { case (dep, idx) =>   // sequentially stages each package
                       stageOne(stagingDirs, dep, artifactsById, Sc4pac.Progress(idx+1, numDeps), resolution, pathLimit = pathLimit)
                     }
    } yield StageResult(stagingDirs, stagedItems, luaSandboxInstalled = resolution.metadata.contains(Constants.luaSandboxDll))  // TODO ensure sandbox is not overwritten by other channel, or that dll is present
  }

  /** Removes installed files from plugins */
  def removeInstalledFiles(toRemove: Set[Dep], installed: Seq[JD.InstalledData], pluginsRoot: os.Path): Task[Unit] = {
    // removing files is synchronous but can be blocking a while, so we wrap it in Task (TODO should use zio blocking)
    val files = installed
      .filter(item => toRemove.contains(item.toDepModule))
      .flatMap(_.files)  // all files of packages to remove
    ZIO.foreachDiscard(files) { (sub: os.SubPath) =>  // this runs sequentially
      val path = pluginsRoot / sub
      ZIO.attemptBlocking {
        require(path != pluginsRoot, "subpath must not be empty")  // sanity check to avoid accidental deletion of entire plugins folder
        if (isDll(path) && os.isLink(path)) {
          os.remove(path, checkExists = false)
        } else if (os.exists(path)) {
          Sc4pac.removeAllForcibly(path)
        } else {
          logger.warn(s"removal failed as file did not exist: $path")
        }
      }
    }
  }

  /** Moves staged files from temp plugins to actual plugins, unconditionally.
    */
  def movePackagesToPlugins(staged: StageResult, pluginsRoot: os.Path): IO[Sc4pacPublishIncomplete, Unit] = {
    ZIO.validateDiscard(staged.items) { item =>
      ZIO.foreachDiscard(item.files) { subPath =>
        val src = staged.dirs.plugins / subPath
        val dest = pluginsRoot / subPath
        ZIO.attemptBlockingIO {
          os.move.over(src, dest, replaceExisting = true, createFolders = true)
        } catchSome { case _: java.io.IOException => ZIO.attemptBlockingIO {
          // DirectoryNotEmptyException:
          // Moving a directory fails if its children require moving as well
          // (e.g. moving between two devices), so fall back to copying.
          // FileSystemException: Moving symlinks can fail on Windows without admin permissions.
          // os.copy.over(src, dest, replaceExisting = true, createFolders = true, followLinks = false)  // does not work for symlinks on Windows despite followLinks=false
          val relPathOpt: Option[os.SubPath | os.RelPath] =
            if (os.isLink(src)) Option(os.readLink(src)).collect { case r: (os.SubPath | os.RelPath) => r }
            else None
          relPathOpt match {
            case Some(relPath) =>
              logger.debug(s"Recreating symlink $dest -> $relPath")
              fileSys.createSymLink(dest, relPath)
            case None =>  // e.g. moving directories between devices
              os.copy.over(src, dest, replaceExisting = true, createFolders = true, followLinks = true)
          }
        }} catchSome { case e: java.io.IOException => ZIO.attemptBlockingIO {
          // Creating symlinks can fail on Windows, so once again fall back to copying.
          logger.debug(s"Unexpected exception while copying $src to $dest: $e")
          os.copy.over(src, dest, replaceExisting = true, createFolders = true, followLinks = true)
        }}
      } refineOrDie { case e: java.io.IOException =>
        logger.warn(e.toString)
        s"${item.dep.orgName}"  // failed to move some staged files of this package to plugins
      }
    }.mapError((failedPkgs: ::[ErrStr]) =>
      new Sc4pacPublishIncomplete(s"Failed to correctly install the following packages (manual intervention needed): ${failedPkgs.mkString(" ")}.",
        "Some files could not be copied into your Plugins folder." +
        " This might be caused by file permission issues or other reasons." +
        " You may try to install the affected files manually instead." +
        " Report the problem if it persists.")
    )
  }

}

object Staging {
  val live: zio.URLayer[Logger & service.FileSystem, Staging] = zio.ZLayer.fromFunction(Staging.apply)

  class StagingDirs(
    val root: os.Path,
    val plugins: os.Path,
    val nested: os.Path,  // created only on demand
  )

  case class StageResult(dirs: StagingDirs, items: Seq[StageResult.Item], luaSandboxInstalled: Boolean)
  object StageResult {
    class Item(val dep: DepModule, val files: Seq[os.SubPath], val pkgData: JD.Package, val warnings: Seq[JD.Warning], val dlls: Seq[DllInstalled], val scripts: Seq[LuaInstalled])
    class DllInstalled(
      val dll: os.SubPath,
      val module: DepModule,
      val asset: DepAsset,
      val pkgMetadataUrl: java.net.URI,
      val assetMetadataUrl: java.net.URI,
      val validatedSha256: collection.immutable.ArraySeq[Byte],
    )
    class LuaInstalled(
      val dbpfFile: os.SubPath,
      val module: DepModule,
      val asset: DepAsset,
      val pkgMetadataUrl: java.net.URI,
      val assetMetadataUrl: java.net.URI,
      val tgis: Seq[scdbpf.Tgi],
    )
  }

}
