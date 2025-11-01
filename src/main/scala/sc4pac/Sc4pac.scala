package io.github.memo33
package sc4pac

import scala.collection.immutable.{Set, Seq}
import coursier.core.{Organization, ModuleName}
import zio.{IO, ZIO, Task, Scope, RIO, Ref, Chunk}
import upickle.default as UP

import sc4pac.error.*
import sc4pac.Constants.isDll
import sc4pac.JsonData as JD
import JD.{Warning, bareModuleRw, subPathRw}
import sc4pac.Sc4pac.{StageResult, UpdatePlan, RepairPlan}
import sc4pac.Resolution.{Dep, DepModule, DepAsset}

// TODO Use `Runtime#reportFatal` or `Runtime.setReportFatal` to log fatal errors like stack overflow

class Sc4pac(val context: ResolutionContext, val tempRoot: os.Path) {  // TODO defaults

  val logger = context.logger

  // TODO check resolution.conflicts

  private def modifyExplicitModules[R](modify: Seq[BareModule] => ZIO[R, Throwable, Seq[BareModule]]): ZIO[R, Throwable, Seq[BareModule]] = {
    for {
      pluginsSpec  <- JsonIo.read[JD.PluginsSpec](JD.PluginsSpec.path(context.profileRoot))  // at this point, file should already exist
      modsOrig     =  pluginsSpec.explicit
      modsNext     <- modify(modsOrig)
      _            <- ZIO.unless(modsNext == modsOrig) {
                        val pluginsDataNext = pluginsSpec.copy(explicit = modsNext)
                        // we do not check whether file was modified as this entire operation is synchronous and fast, in most cases
                        JsonIo.write(JD.PluginsSpec.path(context.profileRoot), pluginsDataNext, None)(ZIO.unit)
                      }
    } yield modsNext
  }

  /** Add modules to the json file containing the explicitly installed modules
    * and return the new full list of explicitly installed modules.
    */
  def add(modules: Seq[BareModule]): Task[Seq[BareModule]] = {
    modifyExplicitModules(modsOrig => ZIO.succeed((modsOrig ++ modules).distinct))
  }

  /** Mark modules for reinstallation that have been installed before, either explicitly or implicitly.
    * Returns true if any matching module has been found and marked for reinstallation.
    */
  def reinstall(modules: Set[BareModule], redownload: Boolean): Task[Boolean] = {
    JsonIo.read[JD.PluginsLock](JD.PluginsLock.path(context.profileRoot)).flatMap { pluginsLockData =>  // at this point, file should already exist
      require(pluginsLockData.scheme > 1, "run an Update before trying to reinstall packages, as scheme=1 is not supported anymore")
      var foundAny = false
      val pluginsLockDataNext = pluginsLockData.copy(installed = pluginsLockData.installed.map { i =>
        if (!modules.contains(i.toBareModule)) i
        else {
          foundAny = true
          if (redownload) i.copy(reinstall = true, redownload = true) else i.copy(reinstall = true)
        }
      })
      for {
        _ <- JsonIo.write(JD.PluginsLock.path(context.profileRoot), pluginsLockDataNext, Some(pluginsLockData))(ZIO.unit)
      } yield foundAny
    }
  }

  /** Remove modules from the list of explicitly installed modules.
    */
  def remove(modules: Seq[BareModule]): Task[Seq[BareModule]] = {
    val toRemove = modules.toSet
    modifyExplicitModules(modsOrig => ZIO.succeed(modsOrig.filterNot(toRemove)))
  }

  /** Select modules to remove from list of explicitly installed modules.
    */
  def removeSelect(): ZIO[Prompt.Interactive & CliPrompter & CliLogger, Throwable, Seq[BareModule]] = {
    modifyExplicitModules { modsOrig =>
      if (modsOrig.isEmpty) {
        logger.log("List of explicitly installed packages is already empty.")
        ZIO.succeed(modsOrig)
      } else {
        for {
          cliLogger <- ZIO.service[CliLogger]
          selected <- Prompt.numberedMultiSelect(
                        "Select packages to remove:",
                        modsOrig.sortBy(m => (m.group.value, m.name.value)),
                        _.formattedDisplayString(cliLogger.gray, identity)
                      ).map(_.toSet)
        } yield modsOrig.filterNot(selected)
      }
    }
  }

  /** Lists all packages of all channels or, optionally, just one channel. */
  def iterateAllChannelPackages(channelUrl: Option[String]): Task[Iterator[JD.ChannelItem]] = ZIO.attempt {
    channelUrl match {
      case None => context.repositories.iterator.flatMap(_.iterateChannelPackages)
      case Some(url) => context.repositories.find(_.baseUri.toString == url).iterator.flatMap(_.iterateChannelPackages)
    }
  }

  /** Detect missing/orphan package subfolders inside Plugins. */
  def repairScan(pluginsRoot: os.Path): Task[RepairPlan] = {
    def isPackageSubfolder(path: os.Path): Boolean = path.last.endsWith(".sc4pac") && path != pluginsRoot && os.isDir(path)
    for {
      pluginsLockData <- JsonIo.read[JD.PluginsLock](JD.PluginsLock.path(context.profileRoot))  // at this point, file should already exist
      _ = require(pluginsLockData.scheme > 1, "run an Update before trying to repair plugins, as scheme=1 is not supported anymore")
      incompletePackages <-
        ZIO.attemptBlockingIO {
          pluginsLockData.installed
            .filter(_.files.exists(subpath => !os.exists(pluginsRoot / subpath, followLinks = true)))  // followLinks=true will detect broken symlinks as well
            .map(_.toBareModule)
        }
      expectedPaths = pluginsLockData.installed.iterator.flatMap(_.files).toSet: Set[os.SubPath]
      unexpectedPaths <-
        ZIO.attemptBlockingIO {
          os.walk
            .stream(pluginsRoot,
              skip = (path) => expectedPaths.contains(path.subRelativeTo(pluginsRoot)) || isPackageSubfolder(path / os.up),
              followLinks = false,
              maxDepth = 8,  // currently .sc4pac folders only have depth 3 (NAM) or 2 (everything else)
            )
            .filter(isPackageSubfolder)
            .map(_.subRelativeTo(pluginsRoot))
            .toSeq.sorted
        }
    } yield RepairPlan(incompletePackages = incompletePackages, orphanFiles = unexpectedPaths)
  }

  /** Executes RepairPlan. Returns whether subsequent update is necessary for changes to take full effect. */
  def repair(plan: RepairPlan, pluginsRoot: os.Path): Task[Boolean] = {
    for {
      _        <- ZIO.attemptBlockingIO(plan.orphanFiles.foreach(subpath => Sc4pac.removeAllForcibly(pluginsRoot / subpath)))
      foundAny <- ZIO.when(plan.incompletePackages.nonEmpty)(reinstall(plan.incompletePackages.toSet, redownload = false))
    } yield foundAny.getOrElse(false)
  }

  /** Fuzzy-search across all repositories.
    * The selection of results is ordered in descending order and includes the
    * module, the relevance ratio and the description.
    * If a STEX/SC4E URL is searched, packages with matching external ID are returned.
    * Api.searchPlugins implements a similar function and should use the same algorithm.
    */
  def search(
    query: String,
    threshold: Int,
    category: Set[String],
    notCategory: Set[String],
    channel: Option[String],
    skipStats: Boolean,
  ): Task[(Option[JD.Channel.Stats], Seq[(BareModule, Int, Option[String])])] = iterateAllChannelPackages(channel).map { itemsIter =>
    val externalIdOpt: Option[(String, String)] = JD.Channel.findExternalId(url = query)
    val searchTokens = Sc4pac.fuzzySearchTokenize(query)
    val wholeCategory = searchTokens.isEmpty && category.nonEmpty
    val trackStats = (searchTokens.nonEmpty || externalIdOpt.isDefined) && !skipStats
    val categoryStats = collection.mutable.Map.empty[String, Int]

    /* Determine how well search text matches. */
    def matchRatio(item: JD.ChannelItem): Int = externalIdOpt match {
      case None =>
        if (wholeCategory) 100  // return the entire category
        else Sc4pac.fuzzySearchRatio(searchTokens, item.toSearchString, threshold)
      case Some(exchangeKey -> externalId) =>
        if (item.externalIds.get(exchangeKey).exists(_.contains(externalId))) 100 else 0
    }

    def incrementCategoryCount(item: JD.ChannelItem): Unit =
      for (cat <- item.category) {
        categoryStats(cat) = categoryStats.getOrElse(cat, 0) + 1
      }

    val results: Seq[(BareModule, Int, Option[String])] =
      itemsIter.flatMap { item =>
        if (item.isSc4pacAsset) {
          assert(false, "iteration should not include any assets")  // None
        } else if (category.nonEmpty && !item.category.exists(category)
            || notCategory.nonEmpty && item.category.exists(notCategory)) {
          if (trackStats && matchRatio(item) >= threshold) {
            incrementCategoryCount(item)  // search text matches, so count towards categories, even though category doesn't match
          }
          None
        } else {
          val ratio = matchRatio(item)
          if (ratio >= threshold) {
            if (trackStats) {
              incrementCategoryCount(item)
            }
            Some(BareModule(Organization(item.group), ModuleName(item.name)), ratio, Option(item.summary).filter(_.nonEmpty))
          } else None
        }
      }.toSeq

    (
      Option.when(trackStats)(JD.Channel.Stats.fromMap(categoryStats)),
      results.sortBy((mod, ratio, desc) => (-ratio, mod.group.value, mod.name.value)).distinctBy(_._1)
    )
  }

  def searchById(packages: Seq[BareModule], externalIds: Map[String, Set[String]]): Task[(Seq[(BareModule, Option[String])], Int, Int)] = {
    // TODO avoid iterating all channel items, but look up packages directly instead (and parallelize) for better performance
    iterateAllChannelPackages(channelUrl = None).map { itemsIter =>
      val relevantItems = collection.mutable.SortedMap.from[BareModule, Option[JD.ChannelItem]](packages.iterator.map(_ -> None))
      val foundExternalIds = collection.mutable.Set.empty[(String, String)]
      itemsIter.foreach { item =>
        item.toBareDep match {
          case _: BareAsset =>  // don't care, shouldn't happen
          case mod: BareModule =>
            relevantItems.get(mod) match {
              case Some(None) => relevantItems(mod) = Some(item)
              case Some(_) =>  // item has already been found
              case None =>
                if (!item.externalIds.isEmpty && item.externalIds.exists((provider, ids) => externalIds.get(provider).exists(requested => ids.exists(requested)))) {
                  relevantItems(mod) = Some(item)
                  foundExternalIds.addAll(item.externalIds.iterator.flatMap((provider, ids) => ids.map(provider -> _)))
                } else {
                  // item is irrelevant
                }
            }
        }
      }
      val b = Seq.newBuilder[(BareModule, Option[String])]
      var notFoundPackageCount = 0
      // first handle packages only (to preserve the order)
      for (module <- packages) {
        val foundItemOpt: Option[JD.ChannelItem] = relevantItems.remove(module).flatten
        if (!foundItemOpt.isDefined) {
          notFoundPackageCount += 1
        }
        b += ((module, foundItemOpt.map(_.summary)))  // even if not found, we can display it
      }
      // items that haven't been removed are modules from externalIds
      for ((module, itemOpt) <- relevantItems) {
        b += ((module, itemOpt.map(_.summary)))
      }
      val notFoundExternalIdCount = externalIds.iterator.flatMap((provider, ids) => ids.map(provider -> _)).filterNot(foundExternalIds).length
      (b.result(), notFoundPackageCount, notFoundExternalIdCount)
    }
  }

  def variantChoices(module: BareModule, variantId: String, variants: Variant): Task[Option[api.PromptMessage.ChooseVariant]] = {
    val variantSelection = VariantSelection(currentSelections = Map.empty, initialSelections = variants, importedSelections = Seq.empty)
    for {
      pkgDataOpt <- infoJson(module)
      msgOptOpt  <- ZIO.foreach(pkgDataOpt)(variantSelection.selectMessageFor(_, variantId))
    } yield msgOptOpt.flatten
  }.provideSomeLayer(zio.ZLayer.succeed(context))

  def infoJson(module: BareModule): Task[Option[JD.Package]] = {
    Find.concreteVersion(module, Constants.versionLatestRelease)
      .flatMap(Find.packageData[JD.Package](module, _))
      .zipWithPar(Find.requiredByExternal(module)) {  // In addition to existing intra-channel dependencies, add inter-channel dependency relations to `requiredBy` and `reverseConflictingPackages` fields.
        case (Some((pkg, url)), relations) =>
          Some(pkg.copy(info = pkg.info.copy(
            requiredBy = (pkg.info.requiredBy.iterator ++ relations.iterator.flatMap(_._2._1)).toSeq.distinct.sorted,
            reverseConflictingPackages = (pkg.info.reverseConflictingPackages.iterator ++ relations.iterator.flatMap(_._2._2)).toSeq.distinct.sorted,
          )).upgradeVariantInfo)
        case (None, _) => None
      }
  }.provideSomeLayer(zio.ZLayer.succeed(context))

  def info(module: BareModule): RIO[CliLogger, Option[Seq[(String, String)]]] = {
    for {
      pkgOpt    <- infoJson(module)
      cliLogger <- ZIO.service[CliLogger]
    } yield {
      pkgOpt.map { pkg =>
        val b = Seq.newBuilder[(String, String)]
        b += "Name" -> s"${pkg.group}:${pkg.name}"
        b += "Version" -> pkg.version
        if (pkg.channelLabel.nonEmpty)
          b += "Channel" -> pkg.channelLabel.get
        b += "Subfolder" -> pkg.subfolder.toString
        b += "Summary" -> cliLogger.applyMarkdown(pkg.info.summary)
        if (pkg.info.description.nonEmpty)
          b += "Description" -> cliLogger.applyMarkdown(pkg.info.description)
        if (pkg.info.warning.nonEmpty)
          b += "Warning" -> cliLogger.applyMarkdown(pkg.info.warning)
        if (pkg.info.conflicts.nonEmpty)
          b += "Conflicts" -> cliLogger.applyMarkdown(pkg.info.conflicts)
        if (pkg.info.author.nonEmpty)
          b += "Author" -> pkg.info.author
        if (pkg.info.websites.nonEmpty) {
          b += "Website" -> pkg.info.websites.head
          pkg.info.websites.iterator.drop(1).foreach { url => b += "" -> url }
        }
        if (pkg.metadataSourceUrl.nonEmpty)
          b += "Metadata" -> pkg.metadataSourceUrl.get.toString

        def mkDeps(packages: Seq[BareDep]) = {
          val deps = packages.collect{ case m: BareModule => m.formattedDisplayString(cliLogger.gray, identity) }
          if (deps.isEmpty) "None" else deps.mkString(" ")
        }

        if (pkg.variants.length == 1 && pkg.variants.head.variant.isEmpty) {
          // no variant
          b += "Dependencies" -> mkDeps(pkg.variants.head.bareDependencies)
        } else {
          // multiple variants
          for (vd <- pkg.variants) {
            b += "Variant" -> JD.VariantData.variantString(vd.variant)
            b += " Dependencies" -> mkDeps(vd.bareDependencies)
          }
        }
        val conflicting = (pkg.variants.flatMap(_.conflictingPackages) ++ pkg.info.reverseConflictingPackages).distinct.sorted
        if (conflicting.nonEmpty) {
          b += "Conflicting" -> mkDeps(conflicting)
        }
        if (pkg.info.requiredBy.nonEmpty) {
          b += "Required By" -> mkDeps(pkg.info.requiredBy)
        }
        // TODO variant descriptions
        // TODO channel URL
        b.result()
      }
    }
  }

  private def packageFolderName(dependency: DepModule): String = {
    val variantTokens = dependency.variant.toSeq.sortBy(_._1).map(_._2)
    val variantLabel = if (variantTokens.isEmpty) "" else variantTokens.mkString(".", "-", "")
    // we avoid the colons since they would need escaping in shells
    s"${dependency.group.value}.${dependency.name.value}$variantLabel.${dependency.version}.sc4pac"
  }

  object update {

    /** Stage a single package into the temp plugins folder and return a list of
      * files or folders containing the files belonging to the package,
      * the JD.Package info containing metadata relevant for the lock file,
      * and any warnings.
      */
    private def stage(
      stagingDirs: Sc4pac.StagingDirs,
      dependency: DepModule,
      artifactsById: Map[BareAsset, (Artifact, java.io.File, DepAsset)],
      progress: Sc4pac.Progress,
      resolution: Resolution,
    ): RIO[ResolutionContext, StageResult.Item] = {
      def extract(assetData: JD.AssetReference, pkgFolder: os.SubPath, variant: Variant, debugModule: BareModule): Task[(BareAsset, Chunk[Extractor.ExtractedItem], Seq[Warning])] = {
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
          case Some(art, archive, depAsset) =>
            val (recipe, regexWarnings) = Extractor.InstallRecipe.fromAssetReference(assetData, variant)
            val extractor = new Extractor(logger)

            def doExtract(fallbackFilename: Option[String]) = try {
              extractor.extract(
                archive,
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
                s"- Downloaded and cached file: $archive",
              )).mkString(f"%n"))
            }

            for {
              fallbackFilename <- context.cache.getFallbackFilename(archive).provideSomeLayer(zio.ZLayer.succeed(logger))
              archiveSize      <- ZIO.attemptBlockingIO(archive.length())
              (files, patterns)<- if (archiveSize >= Constants.largeArchiveSizeInterruptible) {
                                    logger.debug(s"(Interruptible extraction of ${assetData.assetId})")
                                    // comes at a performance cost, so we only make extraction interruptible for large files
                                    ZIO.attemptBlockingInterrupt(doExtract(fallbackFilename))
                                  } else {
                                    ZIO.attemptBlocking(doExtract(fallbackFilename))
                                  }
            } yield {
              // TODO catch IOExceptions
              (id, files, regexWarnings ++ recipe.usedPatternWarnings(patterns, id, short = false))
            }
        }
      }

      /** Creates links for dll files from plugins root folder to package subfolder.
        * If link creation fails, this moves the DLL files from package subfolder to plugins root. */
      def linkDll(dll: os.Path): Task[os.SubPath] =
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
              os.symlink(dllLink, linkTarget)
            } catch { case _: java.io.IOException =>
              // On Windows, symbolic link creation usually fails without elevated privileges
              // (see documentation of channel-build command), so instead of using a link,
              // we just move the DLL file to the plugins root as a fallback.
              logger.debug(s"Failed to create symbolic link $dllLink -> $linkTarget. Moving file to plugins root instead.")
              os.move(dll, dllLink)
            }
            dllLink.subRelativeTo(stagingDirs.plugins)
        }

      val module: BareModule = dependency.toBareDep
      // Since dependency is of type DepModule, we have already resolved the variant successfully, so it must already exist.
      val (pkgData, variantData) = resolution.metadata(module).toOption.get
      val pkgFolder = pkgData.subfolder / packageFolderName(dependency)
      for {
        extractions        <- logger.extractingPackage(dependency, progress)(for {
                                _  <- ZIO.attemptBlocking(os.makeDir.all(stagingDirs.plugins / pkgFolder))  // create folder even if package does not have any assets or files
                                extracted <- ZIO.foreach(variantData.assets)(extract(_, pkgFolder, variant = variantData.variant, debugModule = module))
                              } yield extracted)  // (asset, files, warnings)
        artifactWarnings   =  extractions.flatMap(_._3)
        _                  <- ZIO.foreach(artifactWarnings)(w => ZIO.attempt(logger.warn(w.value)))  // to avoid conflicts with spinner animation, we print warnings after extraction already finished
        dlls               <- ZIO.foreach(extractions.flatMap { case (id, files, _) =>
                                files.filter(f => isDll(f.path)).map((id, _))
                              }) {
                                case (id: BareAsset, item: Extractor.ExtractedItem) =>
                                  for {
                                    dll <- linkDll(item.path)
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
      } yield StageResult.Item(dependency, Seq(pkgFolder) ++ dlls.map(_.dll), pkgData, warningOpt.toSeq ++ artifactWarnings, dlls)
    }

    /** For the list of non-asset packages to install, extract all of them
      * into a temporary staging plugins folder and for each package, return the
      * list of files or folders (to be stored for later uninstallation).
      * If everything is properly extracted, the files are later moved to the
      * actual plugins folder in the publication step.
      */
    private def stageAll(stagingDirs: Sc4pac.StagingDirs, deps: Seq[DepModule], assetsToInstall: Seq[(DepAsset, Artifact, java.io.File)], resolution: Resolution): RIO[ResolutionContext, StageResult] = {
      val artifactsById = assetsToInstall.map((dep, art, file) => dep.toBareDep -> (art, file, dep)).toMap
      val numDeps = deps.length
      for {
        _           <- ZIO.attempt(require(artifactsById.size == assetsToInstall.size, s"artifactsById is not 1-to-1: $assetsToInstall"))
        stagedItems <- ZIO.foreach(deps.zipWithIndex) { case (dep, idx) =>   // sequentially stages each package
                         stage(stagingDirs, dep, artifactsById, Sc4pac.Progress(idx+1, numDeps), resolution)
                       }
      } yield StageResult(stagingDirs, stagedItems)
    }

    private def confirmStageResultOrAbort(stageResult: StageResult): RIO[Prompter, Unit] = {
      val pkgWarnings = stageResult.items.collect { case item if item.warnings.nonEmpty => (item.dep.toBareDep, item.warnings) }
      val dllsInstalled = stageResult.items.flatMap(_.dlls)
      for {
        _  <- ZIO.serviceWithZIO[Prompter](_.confirmInstallationWarnings(pkgWarnings))
                .filterOrFail(_ == true)(error.Sc4pacAbort())
        _  <- ZIO.whenDiscard(dllsInstalled.nonEmpty) {
                ZIO.serviceWithZIO[Prompter](_.confirmDllsInstalled(dllsInstalled))
                  .filterOrFail(_ == true)(error.Sc4pacAbort())
              }
      } yield ()
    }

    private def removeInstalledFiles(toRemove: Set[Dep], installed: Seq[JD.InstalledData], pluginsRoot: os.Path): Task[Unit] = {
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

    /** Moves staged files from temp plugins to actual plugins.
      */
    private def movePackagesToPlugins(staged: StageResult, pluginsRoot: os.Path): IO[Sc4pacPublishIncomplete, Unit] = {
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
                os.symlink(dest, relPath)
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

    // TODO make sure that staging is completed once we execute this
    /** Remove old files from plugins and move staged files and folders into
      * plugins folder. Also update the json database of installed files.
      */
    private def publishToPlugins(staged: StageResult, pluginsRoot: os.Path, pluginsLockData: JD.PluginsLock, pluginsLockDataOrig: JD.PluginsLock, plan: UpdatePlan): Task[Boolean] = {
      // - lock the json database using file lock
      // - remove old packages
      // - move new packages into plugins folder
      // - write json database and release lock
      val task = (JsonIo.write(JD.PluginsLock.path(context.profileRoot), pluginsLockData.updateTo(plan, staged.items), Some(pluginsLockDataOrig)) {
        for {
          _ <- removeInstalledFiles(plan.toRemove, pluginsLockData.installed, pluginsRoot)
                 // .catchAll(???)  // TODO catch exceptions
          result <- movePackagesToPlugins(staged, pluginsRoot).map(_ => true).either  // switch to Either so that PluginsLock file can be written despite Sc4pacPublishIncomplete warning
        } yield result
      }).absolve
      // As this task alters the actual plugins, we make it uninterruptible to ensure completion in case the update is canceled.
      // The task is reasonably fast, as it just removes/moves/copies files.
      logger.publishing(removalOnly = plan.toInstall.isEmpty)(task.uninterruptible)
    }

    private def doPromptingForVariant[R, A](variantSelection: VariantSelection)(task: VariantSelection => RIO[R, A]): RIO[Prompter & ResolutionContext & R, (A, VariantSelection)] = {
      ZIO.iterate(Left(variantSelection): Either[VariantSelection, (A, VariantSelection)])(_.isLeft) {
        case Right(_) => throw new AssertionError
        case Left(variantSelection) =>
          val handler: PartialFunction[Throwable, RIO[Prompter & ResolutionContext, Either[VariantSelection, (A, VariantSelection)]]] = {
            case e: Sc4pacMissingVariant => variantSelection.refineFor(e.packageData).map(Left(_))
          }
          task(variantSelection)
            .map(x => Right((x, variantSelection)))
            .catchSome(handler)
            .catchSomeDefect(handler)  // legacy: Originally Repository used EitherT[Task, ErrStr, _], so Sc4pacMissingVariant was a `defect` rather than a regular `error`
      }.map(_.toOption.get)
    }

    private def doResolveHandleUnresolvable(explicitModules: Seq[BareModule], variantSelection: VariantSelection): RIO[Prompter & ResolutionContext, (Resolution, Seq[BareModule], VariantSelection)] = {
      ZIO.iterate(Left(explicitModules): Either[Seq[BareModule], (Resolution, Seq[BareModule], VariantSelection)])(_.isLeft) {
        case Right(_) => throw new AssertionError
        case Left(explicitModules) =>
          doPromptingForVariant(variantSelection)(Resolution.resolve(explicitModules, _))
            .map { case (resolution, variantSelection) => Right((resolution, explicitModules, variantSelection)) }
            .catchSome {
              case e: error.UnresolvableDependencies =>
                val unresolvable = e.deps.toSet
                val (unresolvableExplicitModules, resolvableExplicitModules) = explicitModules.partition(unresolvable)
                if (unresolvableExplicitModules.nonEmpty) {
                  ZIO.ifZIO(ZIO.serviceWithZIO[Prompter](_.confirmRemovingUnresolvableExplicitPackages(unresolvableExplicitModules)))(
                    onTrue = ZIO.succeed(Left(resolvableExplicitModules)),  // retry with strictly smaller set of explicit packages
                    onFalse = ZIO.fail(e),
                  )
                } else {
                  ZIO.fail(e)
                }
            }
            .catchSome {
              case e: error.ConflictingPackages =>
                ZIO.serviceWithZIO[Prompter](_.chooseToRemoveConflictingExplicitPackages(e.conflict, (e.explicitPackages1, e.explicitPackages2)))
                  .flatMap {
                    case None => ZIO.fail(e)
                    case Some(explicitPackages1or2) =>
                      val discard = explicitPackages1or2.toSet
                      ZIO.succeed(Left(explicitModules.filterNot(discard)))  // retry with smaller set of explicit packages
                  }
            }
      }.map(_.toOption.get)
    }

    private def storeUpdatedSpec(selections: Variant, explicitModules: Seq[BareModule]): Task[Unit] = for {
      pluginsSpec <- JsonIo.read[JD.PluginsSpec](JD.PluginsSpec.path(context.profileRoot))  // json file should exist already
      _           <- JsonIo.write(
                       JD.PluginsSpec.path(context.profileRoot),
                       pluginsSpec.copy(config = pluginsSpec.config.copy(variant = selections), explicit = explicitModules),
                       None
                     )(ZIO.unit)
    } yield ()

    private def doDownloadWithMirror(resolution: Resolution, plan: UpdatePlan): RIO[Prompter & ResolutionContext & Downloader.Credentials, Seq[(DepAsset, Artifact, java.io.File)]] = {
      val depsToInstall = resolution.transitiveDependencies.filter(plan.toInstall).reverse  // we start by fetching artifacts in reverse as those have fewest dependencies of their own
      ZIO.iterate(Left(Map.empty): Either[Map[java.net.URI, os.Path], Seq[(DepAsset, Artifact, java.io.File)]])(_.isLeft) {
        case Left(urlFallbacks) =>
          resolution.fetchArtifactsOf(depsToInstall, urlFallbacks, plan.toRedownload)
            .map(Right(_))
            .catchSome { case e: error.DownloadFailed if e.url.isDefined && !urlFallbacks.contains(e.url.get) =>
              ZIO.serviceWithZIO[Prompter](_.promptForDownloadMirror(e.url.get, e))
                .flatMap {
                  case Right(fallback) => ZIO.succeed(Left(urlFallbacks + (e.url.get -> fallback)))
                  case Left(retry) => if (retry) ZIO.succeed(Left(urlFallbacks)) else ZIO.fail(e)
                }
            }
        case Right(_) => throw new AssertionError
      }.map(_.toOption.get)
    }

    /** `def update`: Update all installed packages from modules (the list of explicitly added packages). */
    def apply(modules0: Seq[BareModule], variantSelection0: VariantSelection, pluginsRoot: os.Path): RIO[ProfileRoot & Prompter & Downloader.Credentials, Boolean] = {

      // - before starting to remove anything, we download and extract everything
      //   to install into temp folders (staging)
      // - then lock the json database
      // - remove old packages
      // - copy new packages into plugins folder
      // - write the json database

      // TODO catch coursier.error.ResolutionError$CantDownloadModule (e.g. when json files have syntax issues)
      val updateTask = for {
        pluginsLockData1 <- JD.PluginsLock.readOrInit
        pluginsLockData <- JD.PluginsLock.upgradeFromScheme1(pluginsLockData1, iterateAllChannelPackages(channelUrl = None), logger, pluginsRoot)
        (resolution, modules, variantSelection) <- doResolveHandleUnresolvable(modules0, variantSelection0)
        finalSelections =  variantSelection.buildFinalSelections()
        plan            =  UpdatePlan.fromResolution(resolution,
                             installed = pluginsLockData.dependenciesWithAssets,
                             forceReinstall = pluginsLockData.packagesToReinstall,
                             forceRedownload = pluginsLockData.packagesToRedownload,
                           )
        continue        <- ZIO.serviceWithZIO[Prompter](_.confirmUpdatePlan(plan))
                             .filterOrFail(_ == true)(error.Sc4pacAbort())
        _               <- ZIO.unless(!continue || finalSelections == variantSelection0.initialSelections && modules == modules0)(storeUpdatedSpec(finalSelections, modules))  // only store something after confirmation
        flagOpt         <- ZIO.unless(!continue || plan.isUpToDate)(for {
          assetsToInstall <- doDownloadWithMirror(resolution, plan)
          depsToStage     =  plan.toInstall.collect{ case d: DepModule => d }.toSeq  // keep only non-assets
          flag            <- ZIO.scoped(for {
                                stagingDirs <- Sc4pac.makeStagingDirs(tempRoot, logger)
                                stageResult <- stageAll(stagingDirs, depsToStage, assetsToInstall, resolution)
                                _           <- confirmStageResultOrAbort(stageResult)
                                flag        <- publishToPlugins(stageResult, pluginsRoot, pluginsLockData, pluginsLockData1, plan)
                             } yield flag)
          _               <- ZIO.attempt(logger.log("Done."))
        } yield flag)
      } yield flagOpt.getOrElse(false)  // TODO decide what flag means

      updateTask.provideSomeLayer(zio.ZLayer.succeed(context))
    }

    private def testInstallOne(module: BareModule, pkgData: JD.Package, stagingDirs: Sc4pac.StagingDirs, outputDir: Option[os.Path], quick: Boolean): RIO[Downloader.Credentials, Unit] = {
      (for {
        resolutions  <- ZIO.foreach(VariantSelection.generateTestingVariants(pkgData, quick = quick)) { selections =>
                          val variantSelection = VariantSelection(currentSelections = selections, initialSelections = Map.empty)
                          Resolution.resolveAssetsNoDependencies(module, variantSelection)
                        }.map(_.distinctBy(_.depModuleOf(module)))  // drop duplicates in case of irrelevant variant choices
                          .mapError(e => { logger.log(e.getMessage); e })
        _            <- ZIO.foreachDiscard(resolutions) { resolution =>
                          (for {
                            assetsToInstall <- resolution.fetchArtifactsOf(resolution.transitiveDependencies, urlFallbacks = Map.empty, assetsToRedownload = Set.empty)
                            depsToStage     =  resolution.transitiveDependencies.collect{ case d: DepModule => d }.toSeq  // keep only non-assets
                            staged <- stageAll(stagingDirs, depsToStage, assetsToInstall, resolution)
                            _      <- ZIO.foreachDiscard(outputDir)(movePackagesToPlugins(staged, _))  // moves files only when output dir is defined
                            fatal  =  staged.items.iterator.flatMap(_.warnings).filter {
                                        case _: JD.InformativeWarning => false
                                        case _: JD.UnexpectedWarning => true
                                      }.toSeq
                            _      <- ZIO.whenDiscard(fatal.nonEmpty)(ZIO.fail(error.ExtractionFailed(
                                        f"There were warnings during installation of the package ${module.orgName}, so metadata seems incorrect:%n",
                                        fatal.map(w => f"%n${w.value}").mkString(""),
                                      )))
                          } yield ())
                            .foldZIO(
                              failure = e => ZIO.succeed(logger.logTestStep(resolution.depModuleOf(module), failure = Some(e))).zipRight(ZIO.fail(e)),
                              success = u => ZIO.succeed(logger.logTestStep(resolution.depModuleOf(module), failure = None)).zipRight(ZIO.succeed(u)),
                            )
                        }
      } yield ()).provideSomeLayer(zio.ZLayer.succeed(context))
    }

    /** Returns true if all tests were successful. */
    def testInstall(modules: Seq[BareModule], outputDir: Option[os.Path], quick: Boolean): RIO[Downloader.Credentials & CliPrompter, Boolean] = {
      ZIO.scoped(for {
        pkgs         <- ZIO.validatePar(modules)(module => infoJson(module).someOrFail(module))
                          .mapError { reasons =>
                            val (errs, notFoundMods) = reasons.partitionMap { case mod: BareModule => Right(mod); case err: Throwable => Left(err) }
                            errs.headOption.getOrElse(error.Sc4pacAssetNotFound("Packages could not be found:", notFoundMods.map(_.orgName).mkString(" ")))
                          }
        stagingDirs  <- Sc4pac.makeStagingDirs(tempRoot, logger)  // created here once in order to avoid extracting the same outer nested archive multiple times
        results      <- ZIO.foreach(modules.zip(pkgs)) { (module, pkg) =>
                          testInstallOne(module, pkg, stagingDirs, outputDir = outputDir, quick = quick).either
                        }
        _            <- ZIO.serviceWith[CliPrompter](_.logger.reportTestResults(results, outputDir.getOrElse(stagingDirs.plugins)))
        _            <- ZIO.whenDiscard(outputDir.isEmpty)(ZIO.serviceWithZIO[CliPrompter](_.confirmDeletionOfStagedFiles()))
      } yield results.forall(_.isRight))
    }

  }  // end of update

  /** Trims down variants and channels to what is relevant for the transitive
    * dependencies of passed modules. */
  def `export`(modules: Seq[BareModule], variants: Variant, channels: Seq[java.net.URI]): Task[JD.ExportData] = {
    val variantSelection = VariantSelection(currentSelections = variants, initialSelections = Map.empty, importedSelections = Seq.empty)
    val handler: PartialFunction[Throwable, Task[Nothing]] = {
      case e: Sc4pacMissingVariant => ZIO.fail(error.UnresolvableDependencies(
        title = "Some package variants could not be resolved. Click Update to ensure that your installed packages are up-to-date with concrete variants.",
        detail = e.packageData.toBareDep.orgName,
        deps = Seq(e.packageData.toBareDep),
      ))
    }
    for {
      // TODO Once dependencies are stored in lock file, resolving could be
      // avoided (and thus Sc4pacMissingVariant exceptions could be avoided).
      // The lock file would then be taken as the truth for `export`.
      resolution   <- Resolution.resolve(modules, variantSelection)
                        .catchSome(handler)
                        .provideSomeLayer(zio.ZLayer.succeed(context))
      relevantIds  =  resolution.transitiveDependencies.iterator.flatMap {
                        case d: DepModule => d.variant.keysIterator
                        case _ => Iterator.empty
                      }.toSet
      relevantUrls =  resolution.transitiveDependencies.iterator.flatMap { dep =>
                        val bareDep = dep.toBareDep
                        context.repositories.find(_.getRawVersions(bareDep).contains(dep.version))
                          .map(_.baseUri): Option[java.net.URI]
                      }.toSet
    } yield JD.ExportData(
      explicit = modules,
      variants = variants.view.filterKeys(relevantIds).toMap,
      channels = channels.filter(relevantUrls),  // preserve order of input channels
    )
  }

}


object Sc4pac {

  // Here `toRedownload` are only the assets explicitly requested to be
  // redownloaded. In general, a lot more assets will be downloaded, e.g. when
  // they are updated to a new version which isn't cached yet.
  case class UpdatePlan(toInstall: Set[Dep], toReinstall: Set[Dep], toRemove: Set[Dep], toRedownload: Set[DepAsset]) {
    def isUpToDate: Boolean = toRemove.isEmpty && toReinstall.isEmpty && toInstall.isEmpty
  }
  object UpdatePlan {
    // Construct an UpdatePlan from a full dependency resolution.
    // Here, `forceReinstall` is a subset of `installed` packages that should be
    // reinstalled, unless the resolution updates them to a different version anyway.
    // Likewise, `forceRedownload` is a subset of packages whose assets should be redownloaded.
    def fromResolution(resolution: Resolution, installed: Set[Dep], forceReinstall: Set[DepModule], forceRedownload: Set[DepModule]): UpdatePlan = {
      // TODO decide whether we should also look for updates of `changing` artifacts

      val wanted: Set[Dep] = resolution.transitiveDependencies.toSet
      val missing = wanted &~ installed
      val obsolete = installed &~ wanted
      // Reinstall packages that depend on missing assets or that were forced to be reinstalled explicitly.
      val toReinstall = wanted & installed & (resolution.dependentsOf(missing.filter(_.isSc4pacAsset)) ++ forceReinstall)
      // for packages to install, we also include their assets (so that they get fetched)
      val toInstall = missing | toReinstall
      val assetsToInstall = toInstall.flatMap(dep => resolution.dependenciesOf(dep).collect{ case d: DepAsset => d })
      val assetsToRedownload = assetsToInstall & forceRedownload.flatMap(dep =>
          resolution.dependenciesOf(dep, ignoreUnresolved = true).collect{ case d: DepAsset => d })  // note that dependenciesOf converts to BareModule, so redownloading even works when variants change
      UpdatePlan(
        toInstall = toInstall ++ assetsToInstall,
        toReinstall = toReinstall,
        toRemove = obsolete | toReinstall,  // to reinstall a package, it first needs to be removed
        toRedownload = assetsToRedownload,
      )
      // (-------------wanted--------------)
      //           (-------------installed--------------)
      // (-missing-)                       (--obsolete--)
      //           (-toReinstall-)
      //           (-----toRemove]         [toRemove----)
      // (-------toInstall-------)
    }
  }

  case class Progress(numerator: Int, denominator: Int) derives UP.ReadWriter {
    override def toString = s"($numerator/$denominator)"
  }

  case class StageResult(dirs: Sc4pac.StagingDirs, items: Seq[StageResult.Item])
  object StageResult {
    class Item(val dep: DepModule, val files: Seq[os.SubPath], val pkgData: JD.Package, val warnings: Seq[Warning], val dlls: Seq[DllInstalled])
    class DllInstalled(
      val dll: os.SubPath,
      val module: DepModule,
      val asset: DepAsset,
      val pkgMetadataUrl: java.net.URI,
      val assetMetadataUrl: java.net.URI,
      val validatedSha256: collection.immutable.ArraySeq[Byte],
    )
  }

  private def fetchChannelData(repoUri: java.net.URI, cache: FileCache, channelContentsTtl: scala.concurrent.duration.Duration): ZIO[ProfileRoot & Logger, error.ChannelsNotAvailable, MetadataRepository] = {
    val contentsUrl = MetadataRepository.channelContentsUrl(repoUri)
    val artifact = Artifact(contentsUrl, changing = true)  // changing as the remote file is updated whenever any remote package is added or updated
    for {
      channelContentsFile <- cache
                              .withTtl(Some(channelContentsTtl))
                              .fetchFile(artifact)  // requires initialized logger
                              .provideSomeLayer(Downloader.emptyCredentialsLayer)  // as we do not fetch channel file from Simtropolis, no need for credentials
                              .mapError {
                                case err: error.Artifact2Error =>
                                  error.ChannelsNotAvailable(s"Channel not available. Check your internet connection and that the channel URL is correct: $repoUri", err.getMessage)
                              }
      profileRoot         <- ZIO.service[ProfileRoot]
      repo                <- MetadataRepository.create(os.Path(channelContentsFile: java.io.File, profileRoot.path), repoUri)
                              .mapError {
                                case errStr: ErrStr => error.ChannelsNotAvailable(s"Failed to read channel data: $errStr", "")  // e.g. unsupported scheme
                              }
    } yield repo
  }

  private[sc4pac] def initializeRepositories(repoUris: Seq[java.net.URI], cache: FileCache, channelContentsTtl: scala.concurrent.duration.Duration): RIO[ProfileRoot & Logger, Seq[MetadataRepository]] = {
    ZIO.foreachPar(repoUris) { url =>
      fetchChannelData(url, cache, channelContentsTtl)
    }
    // TODO for long running processes, we might need a way to refresh the channel
    // data occasionally (but for now this is good enough)
  }

  /** Limits parallel downloads to 2 (ST rejects too many connections). */
  private[sc4pac] def createThreadPool() = coursier.cache.internal.ThreadUtil.fixedThreadPool(size = 2)

  def init(config: JD.Config, refreshChannels: Boolean = false): RIO[ProfileRoot & Logger & Ref[Option[FileCache]], Sc4pac] = {
    // val refreshLogger = coursier.cache.loggers.RefreshLogger.create(System.err)  // TODO System.err seems to cause less collisions between refreshing progress and ordinary log messages
    val channelContentsTtl = if (refreshChannels) Constants.channelContentsTtlRefresh else Constants.channelContentsTtl  // 0 or 30 minutes
    for {
      cacheRoot <- config.cacheRootAbs
      location  =  (cacheRoot / "coursier").toIO
      logger    <- ZIO.service[Logger]
      cache     <- ZIO.serviceWithZIO[Ref[Option[FileCache]]](_.modify[FileCache] {
                     // Re-use existing cache or initialize it. This is important so that
                     // concurrent access to the API does not hit a locked cache.
                     // We don't expect concurrent API access for different cache locations,
                     // so it's fine to store just a reference to the most recent cache.
                     case opt @ Some(cache) if cache.location == location => (cache, opt)
                     case _ =>
                       val coursierPool = createThreadPool()
                       val cache = FileCache(location = location, pool = coursierPool)
                                     .withTtl(Some(Constants.cacheTtl))  // 12 hours
                       (cache, Some(cache))
                   })
      repos     <- initializeRepositories(config.channels, cache, channelContentsTtl)
      tempRoot  <- config.tempRootAbs
      profileRoot <- ZIO.service[ProfileRoot]
      context   = new ResolutionContext(repos, cache, logger, profileRoot.path)
    } yield Sc4pac(context, tempRoot)
  }

  def parseModules(modules: Seq[String]): Either[ErrStr, Seq[BareModule]] = {
    coursier.parse.ModuleParser
      .modules(modules, defaultScalaVersion = "")
      .map { modules => modules.map(m => BareModule(m.organization, m.name)) }
      .either
      .left.map { (errs: List[ErrStr]) =>
        errs.mkString(", ")  // malformed module: a, malformed module: b
      }
  }

  def parseModule(module: String): Either[ErrStr, BareModule] = parseModules(Seq(module)).map(_.head)

  private[sc4pac] def fuzzySearchTokenize(searchString: String): IndexedSeq[String] = {
    searchString.toLowerCase(java.util.Locale.ENGLISH).split(' ').toIndexedSeq
  }

  /** This search implementation tries to work around some deficiencies of the
    * fuzzywuzzy library algorithms `tokenSetRatio` and `tokenSetPartialRatio`.
    * (The former does not match partial strings, the latter finds lots of
    * unsuitable matches for "vip terrain mod" for example.)
    */
  private[sc4pac] def fuzzySearchRatio(searchTokens: IndexedSeq[String], text: String, threshold: Int): Int = {
    if (searchTokens.isEmpty) {
      0
    } else {
      var acc = 0
      for (token <- searchTokens) {
        // There is a bug in the fuzzywuzzy library that causes
        // partialRatio("wolf", "ulisse wolf hybrid-railway-subway-converter tunnel portals for hybrid railway (hrw)")
        //                                              ^           ^             ^         ^
        // to output 50 instead of 100, see https://github.com/xdrop/fuzzywuzzy/issues/106
        // so as mitigation we first check containment.
        val ratio = if (text.contains(token)) 100 else me.xdrop.fuzzywuzzy.FuzzySearch.partialRatio(token, text)
        if (ratio >= threshold) {  // this eliminates poor matches for some tokens (however, this leads to inconsistent results for varying thresholds due to double truncation)
          acc += ratio
        }
      }
      math.round(acc.toFloat / searchTokens.length)
    }
  }

  class StagingDirs(
    val root: os.Path,
    val plugins: os.Path,
    val nested: os.Path,  // created only on demand
  )

  private[sc4pac] def makeStagingDirs(tempRoot: os.Path, logger: Logger): ZIO[Scope, java.io.IOException, StagingDirs] =
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

  /** Remove a file or potentially non-empty directory, taking into account that
    * directories on OneDrive are typically marked as read-only.
    */
  private[sc4pac] def removeAllForcibly(target: os.Path): Unit = {
    // re-implementation of os.remove.all, see https://github.com/com-lihaoyi/os-lib/blob/72605235899b65e144ffe48821c63085cb9062ad/os/src/FileOps.scala#L333-L350
    import java.nio.file.{Files, LinkOption}
    require(target.segmentCount != 0, s"Cannot remove a root directory: $target")
    if (Files.exists(target.wrapped, LinkOption.NOFOLLOW_LINKS)) {
      val paths =
        if (Files.isDirectory(target.wrapped, LinkOption.NOFOLLOW_LINKS))
          os.walk.stream(target, preOrder = false) ++ Seq(target)
        else
          geny.Generator[os.Path](target)
      for (p <- paths) {
        try {
          val _ = Files.deleteIfExists(p.wrapped)
        } catch { case e: java.io.IOException =>  // typically java.nio.file.AccessDeniedException when folders are read-only, e.g. on OneDrive on Windows, see https://github.com/memo33/sc4pac-tools/issues/40
          try {
            // Attempt to clear problematic flags. See https://github.com/PowerShell/PowerShell/blob/v7.5.3/src/System.Management.Automation/namespaces/FileSystemProvider.cs#L3221
            val dos = Files.getFileAttributeView(p.wrapped, classOf[java.nio.file.attribute.DosFileAttributeView], LinkOption.NOFOLLOW_LINKS)
            if (dos != null) {
              dos.setReadOnly(false)
              try dos.setHidden(false) catch { case _: Throwable => /*ignore*/ }
              val _ = Files.deleteIfExists(p.wrapped)  // second attempt
            } else {
              throw e
            }
          } catch { case ignore: Throwable => throw e }
        }
      }
    }
  }

  case class RepairPlan(incompletePackages: Seq[BareModule], orphanFiles: Seq[os.SubPath]) derives UP.ReadWriter {
    def isUpToDate: Boolean = incompletePackages.isEmpty && orphanFiles.isEmpty
  }
}
