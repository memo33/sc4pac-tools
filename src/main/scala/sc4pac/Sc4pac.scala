package io.github.memo33
package sc4pac

import scala.collection.immutable.{Set, Seq}
import coursier.Type
import coursier.core.{Module, Organization, ModuleName}
import zio.{IO, ZIO, Task, Scope, RIO}
import upickle.default as UP

import sc4pac.error.*
import sc4pac.Constants.isDll
import sc4pac.JsonData as JD
import sc4pac.Sc4pac.{StageResult, UpdatePlan}
import sc4pac.Resolution.{Dep, DepModule, DepAsset}

// TODO Use `Runtime#reportFatal` or `Runtime.setReportFatal` to log fatal errors like stack overflow

class Sc4pac(val context: ResolutionContext, val tempRoot: os.Path) extends UpdateService {  // TODO defaults

  val logger = context.logger

  // TODO check resolution.conflicts

  private def modifyExplicitModules[R](modify: Seq[BareModule] => ZIO[R, Throwable, Seq[BareModule]]): ZIO[R, Throwable, Seq[BareModule]] = {
    for {
      pluginsData  <- JsonIo.read[JD.Plugins](JD.Plugins.path(context.profileRoot))  // at this point, file should already exist
      modsOrig     =  pluginsData.explicit
      modsNext     <- modify(modsOrig)
      _            <- ZIO.unless(modsNext == modsOrig) {
                        val pluginsDataNext = pluginsData.copy(explicit = modsNext)
                        // we do not check whether file was modified as this entire operation is synchronous and fast, in most cases
                        JsonIo.write(JD.Plugins.path(context.profileRoot), pluginsDataNext, None)(ZIO.succeed(()))
                      }
    } yield modsNext
  }

  /** Add modules to the json file containing the explicitly installed modules
    * and return the new full list of explicitly installed modules.
    */
  def add(modules: Seq[BareModule]): Task[Seq[BareModule]] = {
    modifyExplicitModules(modsOrig => ZIO.succeed((modsOrig ++ modules).distinct))
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

  val iterateAllChannelContents: Task[Iterator[JD.ChannelItem]] = ZIO.attempt { context.repositories.iterator.flatMap(_.iterateChannelContents) }

  /** Fuzzy-search across all repositories.
    * The selection of results is ordered in descending order and includes the
    * module, the relevance ratio and the description.
    */
  def search(query: String, threshold: Int, category: Option[String]): Task[Seq[(BareModule, Int, Option[String])]] = iterateAllChannelContents.map { itemsIter =>
    val results: Seq[(BareModule, Int, Option[String])] =
      itemsIter.flatMap { item =>
        if (item.isSc4pacAsset) {
          None
        } else if (category.isDefined && item.category != category) {
          None
        } else {
          // TODO reconsider choice of search algorithm
          val ratio =
            if (query.isEmpty && category.isDefined) 100  // return the entire category
            else me.xdrop.fuzzywuzzy.FuzzySearch.tokenSetRatio(query, item.toSearchString)
          if (ratio >= threshold) {
            Some(BareModule(Organization(item.group), ModuleName(item.name)), ratio, Option(item.summary).filter(_.nonEmpty))
          } else None
        }
      }.toSeq
    results.sortBy((mod, ratio, desc) => (-ratio, mod.group.value, mod.name.value)).distinctBy(_._1)
  }

  def infoJson(module: BareModule): Task[Option[JD.Package]] = {
    val mod = Module(module.group, module.name, attributes = Map.empty)
    (for {
      version <- Find.concreteVersion(mod, Constants.versionLatestRelease)
      pkgOpt  <- Find.packageData[JD.Package](mod, version)
    } yield pkgOpt).provideSomeLayer(zio.ZLayer.succeed(context))
  }

  /** Currenty this does not apply full markdown formatting, but just `pkg=…`
    * highlighting.
    */
  def applyMarkdown(text: String, cliLogger: CliLogger): String = {
    BareModule.pkgMarkdownRegex.replaceAllIn(text, matcher =>
      BareModule(Organization(matcher.group(1)), ModuleName(matcher.group(2))).formattedDisplayString(cliLogger.gray, cliLogger.bold)
    )
  }

  def info(module: BareModule): RIO[CliLogger, Option[Seq[(String, String)]]] = {
    for {
      pkgOpt    <- infoJson(module)
      cliLogger <- ZIO.service[CliLogger]
    } yield {
      pkgOpt.map { pkg =>
        val b = Seq.newBuilder[(String, String)]
        b += "Name" -> s"${pkg.group}:${pkg.name}"
        b += "Version" -> pkg.version
        b += "Subfolder" -> pkg.subfolder.toString
        b += "Summary" -> applyMarkdown(pkg.info.summary, cliLogger)
        if (pkg.info.description.nonEmpty)
          b += "Description" -> applyMarkdown(pkg.info.description, cliLogger)
        if (pkg.info.warning.nonEmpty)
          b += "Warning" -> applyMarkdown(pkg.info.warning, cliLogger)
        if (pkg.info.conflicts.nonEmpty)
          b += "Conflicts" -> applyMarkdown(pkg.info.conflicts, cliLogger)
        if (pkg.info.author.nonEmpty)
          b += "Author" -> pkg.info.author
        if (pkg.info.website.nonEmpty)
          b += "Website" -> pkg.info.website

        def mkDeps(vd: JD.VariantData) = {
          val deps = vd.bareDependencies.collect{ case m: BareModule => m.formattedDisplayString(cliLogger.gray, identity) }
          if (deps.isEmpty) "None" else deps.mkString(" ")
        }

        if (pkg.variants.length == 1 && pkg.variants.head.variant.isEmpty) {
          // no variant
          b += "Dependencies" -> mkDeps(pkg.variants.head)
        } else {
          // multiple variants
          for (vd <- pkg.variants) {
            b += "Variant" -> JD.VariantData.variantString(vd.variant)
            b += " Dependencies" -> mkDeps(vd)
          }
        }
        // TODO variant descriptions
        // TODO channel URL
        b.result()
      }
    }
  }

}


trait UpdateService { this: Sc4pac =>

  private def packageFolderName(dependency: DepModule): String = {
    val variantTokens = dependency.variant.toSeq.sortBy(_._1).map(_._2)
    val variantLabel = if (variantTokens.isEmpty) "" else variantTokens.mkString(".", "-", "")
    // we avoid the colons since they would need escaping in shells
    s"${dependency.group.value}.${dependency.name.value}$variantLabel.${dependency.version}.sc4pac"
  }

  /** Stage a single package into the temp plugins folder and return a list of
    * files or folders containing the files belonging to the package,
    * the JD.Package info containing metadata relevant for the lock file,
    * and any warnings.
    */
  private def stage(
    tempPluginsRoot: os.Path,
    dependency: DepModule,
    artifactsById: Map[BareAsset, (Artifact, java.io.File, DepAsset)],
    stagingRoot: os.Path,
    progress: Sc4pac.Progress
  ): RIO[ResolutionContext, StageResult.Item] = {
    def extract(assetData: JD.AssetReference, pkgFolder: os.SubPath): Task[Seq[Warning]] = {
      // Given an AssetReference, we look up the corresponding artifact file
      // by ID. This relies on the 1-to-1-correspondence between sc4pacAssets
      // and artifact files.
      val id = BareAsset(ModuleName(assetData.assetId))
      artifactsById.get(id) match {
        case None =>
          ZIO.succeed(Seq(s"An asset is missing and has been skipped, so it needs to be installed manually: ${id.orgName}. " +
                           "Please report this to the maintainers of the package metadata."))
        case Some(art, archive, depAsset) =>
          val (recipe, regexWarnings) = JD.InstallRecipe.fromAssetReference(assetData)
          val extractor = new Extractor(logger)
          val jarsRoot = stagingRoot / "jars"

          def doExtract(fallbackFilename: Option[String]) =
            extractor.extract(
              archive,
              fallbackFilename,
              tempPluginsRoot / pkgFolder,
              recipe,
              Some(Extractor.JarExtraction.fromUrl(art.url, jarsRoot = jarsRoot)),
              hints = depAsset.archiveType,
              stagingRoot)

          for {
            fallbackFilename <- ZIO.attemptBlockingIO(context.cache.getFallbackFilename(archive))
            archiveSize      <- ZIO.attemptBlockingIO(archive.length())
            usedPatterns     <- if (archiveSize >= Constants.largeArchiveSizeInterruptible) {
                                  logger.debug(s"(Interruptible extraction of ${assetData.assetId})")
                                  // comes at a performance cost, so we only make extraction interruptible for large files
                                  ZIO.attemptBlockingInterrupt(doExtract(fallbackFilename))
                                } else {
                                  ZIO.attemptBlocking(doExtract(fallbackFilename))
                                }
          } yield {
            // TODO catch IOExceptions
            regexWarnings ++ recipe.usedPatternWarnings(usedPatterns)
          }
      }
    }

    /** Creates links for dll files from plugins root folder to package subfolder.
      * If link creation fails, this moves the DLL files from package subfolder to plugins root. */
    def linkDlls(pkgFolder: os.SubPath): Task[Seq[os.SubPath]] = ZIO.attemptBlockingIO {
      os.walk.stream(tempPluginsRoot / pkgFolder)
        .filter(isDll)
        .map { dll =>
          val dllLink = tempPluginsRoot / dll.last
          if (os.isLink(dllLink) || os.exists(dllLink)) {  // (note that `dllLink` may be an actual file on Windows)
            // This should not usually happen as it means two packages contain
            // the same DLL and are installed during the same `update` process.
            // If the DLL already exists in the actual plugins, we don't even catch that.
            val _ = os.remove(dllLink, checkExists = false)  // ignoring result for now
          }
          val linkTarget = dll.subRelativeTo(tempPluginsRoot)
          try {
            os.symlink(dllLink, linkTarget)
          } catch { case _: java.io.IOException =>
            // On Windows, symbolic link creation usually fails without elevated privileges
            // (see documentation of channel-build command), so instead of using a link,
            // we just move the DLL file to the plugins root as a fallback.
            logger.debug(s"Failed to create symbolic link $dllLink -> $linkTarget. Moving file to plugins root instead.")
            os.move(dll, dllLink)
          }
          dllLink.subRelativeTo(tempPluginsRoot)
        }
        .toSeq
    }

    // Since dependency is of type DepModule, we have already looked up the
    // variant successfully, but have lost the JsonData.Package, so we reconstruct it
    // here a second time.
    for {
      (pkgData, variant) <- Find.matchingVariant(dependency.toBareDep, dependency.version, dependency.variant)
      pkgFolder          =  pkgData.subfolder / packageFolderName(dependency)
      artifactWarnings   <- logger.extractingPackage(dependency, progress)(for {
                              _  <- ZIO.attemptBlocking(os.makeDir.all(tempPluginsRoot / pkgFolder))  // create folder even if package does not have any assets or files
                              ws <- ZIO.foreach(variant.assets)(extract(_, pkgFolder))
                            } yield ws.flatten)
      _                  <- ZIO.foreach(artifactWarnings)(w => ZIO.attempt(logger.warn(w)))  // to avoid conflicts with spinner animation, we print warnings after extraction already finished
      dlls               <- linkDlls(pkgFolder)
      warnings           <- if (pkgData.info.warning.nonEmpty) ZIO.attempt { val w = pkgData.info.warning; logger.warn(w); Seq(w) }
                            else ZIO.succeed(Seq.empty)
    } yield StageResult.Item(dependency, Seq(pkgFolder) ++ dlls, pkgData, warnings ++ artifactWarnings)
  }

  private def remove(toRemove: Set[Dep], installed: Seq[JD.InstalledData], pluginsRoot: os.Path): Task[Unit] = {
    // removing files is synchronous but can be blocking a while, so we wrap it in Task (TODO should use zio blocking)
    val files = installed
      .filter(item => toRemove.contains(item.toDepModule))
      .flatMap(_.files)  // all files of packages to remove
    ZIO.foreachDiscard(files) { (sub: os.SubPath) =>  // this runs sequentially
      val path = pluginsRoot / sub
      ZIO.attemptBlocking {
        if (isDll(path) && os.isLink(path)) {
          os.remove(path, checkExists = false)
        } else if (os.exists(path)) {
          os.remove.all(path)
        } else {
          logger.warn(s"removal failed as file did not exist: $path")
        }
      }
    }
  }

  /** Update all installed packages from modules (the list of explicitly added packages). */
  def update(modules: Seq[BareModule], globalVariant0: Variant, pluginsRoot: os.Path): RIO[ProfileRoot & Prompter, Boolean] = {

    // - before starting to remove anything, we download and extract everything
    //   to install into temp folders (staging)
    // - then lock the json database
    // - remove old packages
    // - copy new packages into plugins folder
    // - write the json database

    /** For the list of non-asset packages to install, extract all of them
      * into a temporary staging plugins folder and for each package, return the
      * list of files or folders (to be stored for later uninstallation).
      * If everything is properly extracted, the files are later moved to the
      * actual plugins folder in the publication step.
      */
    def stageAll(deps: Seq[DepModule], artifactsById: Map[BareAsset, (Artifact, java.io.File, DepAsset)]): RIO[Scope & Prompter & ResolutionContext, StageResult] = {

      val makeTempStagingDir: ZIO[Scope, java.io.IOException, os.Path] =
        ZIO.acquireRelease(
          acquire = ZIO.attemptBlockingIO {
            os.makeDir.all(tempRoot)
            val res = os.temp.dir(tempRoot, prefix = "staging-process", deleteOnExit = false)  // deleteOnExit does not seem to work reliably, so explicitly delete temp folder
            logger.debug(s"Creating temp staging dir: $res")
            res
          }
        )(
          release = (stagingRoot: os.Path) => ZIO.attemptBlockingIO {  // TODO not executed in case of interrupt, so consider cleaning up temp dir from previous runs regularly.
            logger.debug(s"Deleting temp staging dir: $stagingRoot")
            os.remove.all(stagingRoot)
          }.catchAll {
            case e => ZIO.succeed(logger.warn(s"Failed to remove temp folder $stagingRoot: ${e.getMessage}"))
          }
        )

      for {
        stagingRoot             <- makeTempStagingDir
        tempPluginsRoot         =  stagingRoot / "plugins"
        _                       <- ZIO.attemptBlocking(os.makeDir(tempPluginsRoot))
        numDeps                 =  deps.length
        stagedItems             <- ZIO.foreach(deps.zipWithIndex) { case (dep, idx) =>   // sequentially stages each package
                                     stage(tempPluginsRoot, dep, artifactsById, stagingRoot, Sc4pac.Progress(idx+1, numDeps))
                                   }
        pkgWarnings             =  stagedItems.collect { case item if item.warnings.nonEmpty => (item.dep.toBareDep, item.warnings) }
        _                       <- ZIO.serviceWithZIO[Prompter](_.confirmInstallationWarnings(pkgWarnings))
                                     .filterOrFail(_ == true)(error.Sc4pacAbort())
      } yield StageResult(tempPluginsRoot, stagedItems, stagingRoot)
    }

    /** Moves staged files from temp plugins to actual plugins. This effect has
      * no expected failures, but only potentially unexpected defects.
      */
    def movePackagesToPlugins(staged: StageResult): IO[Sc4pacPublishWarning, Unit] = {
      ZIO.validateDiscard(staged.items) { item =>
        ZIO.foreachDiscard(item.files) { subPath =>
          ZIO.attemptBlocking {
            os.move.over(staged.tempPluginsRoot / subPath, pluginsRoot / subPath, replaceExisting = true, createFolders = true)
          } catchSome { case _: java.nio.file.DirectoryNotEmptyException => ZIO.attemptBlocking {
            // moving a directory fails if its children require moving as well
            // (e.g. moving between two devices), so fall back to copying
            os.copy.over(staged.tempPluginsRoot / subPath, pluginsRoot / subPath, replaceExisting = true, createFolders = true)
          }}
        } refineOrDie { case e: java.io.IOException =>  // TODO this potentially dies on unexpected errors (defects) that should maybe be handled further up top
          logger.warn(e.toString)
          s"${item.dep.orgName}"  // failed to move some staged files of this package to plugins
        }
      }.mapError((failedPkgs: ::[ErrStr]) =>
        new Sc4pacPublishWarning(s"Failed to correctly install the following packages (manual intervention needed): ${failedPkgs.mkString(" ")}")
      )
    }

    // TODO make sure that staging is completed once we execute this
    /** Remove old files from plugins and move staged files and folders into
      * plugins folder. Also update the json database of installed files.
      */
    def publishToPlugins(staged: StageResult, pluginsLockData: JD.PluginsLock, pluginsLockDataOrig: JD.PluginsLock, plan: UpdatePlan): Task[Boolean] = {
      // - lock the json database using file lock
      // - remove old packages
      // - move new packages into plugins folder
      // - write json database and release lock
      val task = JsonIo.write(JD.PluginsLock.path(context.profileRoot), pluginsLockData.updateTo(plan, staged.items), Some(pluginsLockDataOrig)) {
        for {
          _ <- remove(plan.toRemove, pluginsLockData.installed, pluginsRoot)
                 // .catchAll(???)  // TODO catch exceptions
          _ <- movePackagesToPlugins(staged)
        } yield true  // TODO return result
      }
      // As this task alters the actual plugins, we make it uninterruptible to ensure completion in case the update is canceled.
      // The task is reasonably fast, as it just removes/moves/copies files.
      logger.publishing(removalOnly = plan.toInstall.isEmpty)(task.uninterruptible)
        .catchSome {  // TODO expose publish warnings to clients
          case e: Sc4pacPublishWarning => logger.warn(e.getMessage); ZIO.succeed(true)  // TODO return result
        }
    }

    /** Prompts for missing variant keys, so that the result allows to pick a unique variant of the package. */
    def refineGlobalVariant(globalVariant: Variant, pkgData: JD.Package): RIO[Prompter, Variant] = {
      val mod = BareModule(Organization(pkgData.group), ModuleName(pkgData.name))
      import Sc4pac.{DecisionTree, Node, Empty}
      DecisionTree.fromVariants(pkgData.variants.map(_.variant)) match {
        case Left(err) => ZIO.fail(new error.UnsatisfiableVariantConstraints(
          s"Unable to choose variants as the metadata of ${mod.orgName} seems incomplete", err.toString))
        case Right(decisionTree) =>
          type Key = String; type Value = String
          def choose[T](key: Key, choices: Seq[(Value, T)]): RIO[Prompter, (Value, T)] = {
            globalVariant.get(key) match
              case Some(value) => choices.find(_._1 == value) match
                case Some(choice) => ZIO.succeed(choice)
                case None => ZIO.fail(new error.UnsatisfiableVariantConstraints(
                  s"""None of the variants ${choices.map(_._1).mkString(", ")} of ${mod.orgName} match the configured variant $key=$value.""",
                  s"""The package metadata seems incorrect, but resetting the variant may resolve the problem (command: `sc4pac variant reset "$key"`)."""))
              case None =>  // global variant for key is not set, so choose it interactively
                ZIO.serviceWithZIO[Prompter](_.promptForVariant(
                  module = mod,
                  label = key,
                  values = choices.map(_._1),
                  descriptions = pkgData.variantDescriptions.get(key).getOrElse(Map.empty)
                ).map(value => choices.find(_._1 == value).get))  // prompter is guaranteed to return a matching value
          }

          ZIO.iterate(decisionTree, Seq.newBuilder[(Key, Value)])(_._1 != Empty) {
            case (Node(key, choices), builder) => choose(key, choices).map { case (value, subtree) => (subtree, builder += key -> value) }
            case (Empty, builder) => throw new AssertionError
          }.map(_._2.result())
            .map(additionalChoices => globalVariant ++ additionalChoices)
      }
    }

    def doPromptingForVariant[R, A](globalVariant: Variant)(task: Variant => RIO[R, A]): RIO[Prompter & R, (A, Variant)] = {
      ZIO.iterate(Left(globalVariant): Either[Variant, (A, Variant)])(_.isLeft) {
        case Right(_) => throw new AssertionError
        case Left(globalVariant) =>
          val handler: PartialFunction[Throwable, RIO[Prompter, Either[Variant, (A, Variant)]]] = {
            case e: Sc4pacMissingVariant => refineGlobalVariant(globalVariant, e.packageData).map(Left(_))
          }
          task(globalVariant)
            .map(x => Right((x, globalVariant)))
            .catchSome(handler)
            .catchSomeDefect(handler)  // legacy: Originally Repository used EitherT[Task, ErrStr, _], so Sc4pacMissingVariant was a `defect` rather than a regular `error`
      }.map(_.toOption.get)
    }

    def storeGlobalVariant(globalVariant: Variant): Task[Unit] = for {
      pluginsData <- JsonIo.read[JD.Plugins](JD.Plugins.path(context.profileRoot))  // json file should exist already
      _           <- JsonIo.write(JD.Plugins.path(context.profileRoot), pluginsData.copy(config = pluginsData.config.copy(variant = globalVariant)), None)(ZIO.succeed(()))
    } yield ()

    // TODO catch coursier.error.ResolutionError$CantDownloadModule (e.g. when json files have syntax issues)
    val updateTask = for {
      pluginsLockData1 <- JD.PluginsLock.readOrInit
      pluginsLockData <- JD.PluginsLock.upgradeFromScheme1(pluginsLockData1, iterateAllChannelContents, logger, pluginsRoot)
      (resolution, globalVariant) <- doPromptingForVariant(globalVariant0)(Resolution.resolve(modules, _))
      plan            =  UpdatePlan.fromResolution(resolution, installed = pluginsLockData.dependenciesWithAssets)
      continue        <- ZIO.serviceWithZIO[Prompter](_.confirmUpdatePlan(plan))
                           .filterOrFail(_ == true)(error.Sc4pacAbort())
      _               <- ZIO.unless(!continue || globalVariant == globalVariant0)(storeGlobalVariant(globalVariant))  // only store something after confirmation
      flagOpt         <- ZIO.unless(!continue || plan.isUpToDate)(for {
        assetsToInstall <- resolution.fetchArtifactsOf(resolution.transitiveDependencies.filter(plan.toInstall).reverse)  // we start by fetching artifacts in reverse as those have fewest dependencies of their own
        // TODO if some artifacts fail to be fetched, fall back to installing remaining packages (maybe not(?), as this leads to missing dependencies,
        // but there needs to be a manual workaround in case of permanently missing artifacts)
        depsToStage     =  plan.toInstall.collect{ case d: DepModule => d }.toSeq  // keep only non-assets
        artifactsById   =  assetsToInstall.map((dep, art, file) => dep.toBareDep -> (art, file, dep)).toMap
        _               =  require(artifactsById.size == assetsToInstall.size, s"artifactsById is not 1-to-1: $assetsToInstall")
        flag            <- ZIO.scoped(stageAll(depsToStage, artifactsById)
                                      .flatMap(publishToPlugins(_, pluginsLockData, pluginsLockData1, plan)))
        _               <- ZIO.attempt(logger.log("Done."))
      } yield flag)
    } yield flagOpt.getOrElse(false)  // TODO decide what flag means

    updateTask.provideSomeLayer(zio.ZLayer.succeed(context))
  }

}


object Sc4pac {
  val assetTypes = Set(Constants.sc4pacAssetType)

  case class UpdatePlan(toInstall: Set[Dep], toReinstall: Set[Dep], toRemove: Set[Dep]) {
    def isUpToDate: Boolean = toRemove.isEmpty && toReinstall.isEmpty && toInstall.isEmpty
  }
  object UpdatePlan {
    def fromResolution(resolution: Resolution, installed: Set[Dep]): UpdatePlan = {
      // TODO decide whether we should also look for updates of `changing` artifacts

      val wanted: Set[Dep] = resolution.transitiveDependencies.toSet
      val missing = wanted &~ installed
      val obsolete = installed &~ wanted
      // for assets, we also reinstall the packages that depend on them
      val toReinstall = wanted & installed & resolution.dependentsOf(missing.filter(_.isSc4pacAsset))
      // for packages to install, we also include their assets (so that they get fetched)
      val toInstall = missing | toReinstall
      val assetsToInstall = toInstall.flatMap(dep => resolution.dependenciesOf(dep).filter(_.isSc4pacAsset))
      UpdatePlan(
        toInstall = toInstall | assetsToInstall,
        toReinstall = toReinstall,
        toRemove = obsolete | toReinstall)  // to reinstall a package, it first needs to be removed
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

  case class StageResult(tempPluginsRoot: os.Path, items: Seq[StageResult.Item], stagingRoot: os.Path)
  object StageResult {
    class Item(val dep: DepModule, val files: Seq[os.SubPath], val pkgData: JD.Package, val warnings: Seq[Warning])
  }


  private def fetchChannelData(repoUri: java.net.URI, cache: FileCache, channelContentsTtl: scala.concurrent.duration.Duration): ZIO[ProfileRoot, ErrStr, MetadataRepository] = {
    val contentsUrl = MetadataRepository.channelContentsUrl(repoUri).toString
    val artifact = Artifact(contentsUrl, changing = true)  // changing as the remote file is updated whenever any remote package is added or updated
    for {
      channelContentsFile <- cache
                              .withTtl(Some(channelContentsTtl))
                              .file(artifact)  // requires initialized logger
                              .mapError { case e @ (_: coursier.cache.ArtifactError | scala.util.control.NonFatal(_)) => e.getMessage }
      profileRoot         <- ZIO.service[ProfileRoot]
      repo                <- MetadataRepository.create(os.Path(channelContentsFile: java.io.File, profileRoot.path), repoUri)
    } yield repo
  }

  private def wrapService[R : zio.Tag, E, A](use: ZIO[Any, E, A] => ZIO[Any, E, A], task: ZIO[R, E, A]): ZIO[R, E, A] = {
    for {
      service <- ZIO.service[R]
      task2   =  task.provideLayer(zio.ZLayer.succeed(service))
      result  <- use(task2)
    } yield result
  }

  private[sc4pac] def initializeRepositories(repoUris: Seq[java.net.URI], cache: FileCache, channelContentsTtl: scala.concurrent.duration.Duration): RIO[ProfileRoot, Seq[MetadataRepository]] = {
    val task: RIO[ProfileRoot, Seq[MetadataRepository]] = ZIO.collectPar(repoUris) { url =>
      fetchChannelData(url, cache, channelContentsTtl)
        .mapError((err: ErrStr) => { System.err.println(s"Failed to read channel data: $err"); None })
    }.filterOrFail(_.nonEmpty)(error.NoChannelsAvailable("No channels available", repoUris.toString))
    // TODO for long running processes, we might need a way to refresh the channel
    // data occasionally (but for now this is good enough)
    wrapService(cache.logger.using(_), task)  // properly initializes logger (avoids Uninitialized TermDisplay)
  }

  /** Limits parallel downloads to 2 (ST rejects too many connections). */
  private[sc4pac] def createThreadPool() = coursier.cache.internal.ThreadUtil.fixedThreadPool(size = 2)

  def init(config: JD.Config): RIO[ProfileRoot & Logger, Sc4pac] = {
    // val refreshLogger = coursier.cache.loggers.RefreshLogger.create(System.err)  // TODO System.err seems to cause less collisions between refreshing progress and ordinary log messages
    val coursierPool = createThreadPool()
    for {
      cacheRoot <- config.cacheRootAbs
      logger    <- ZIO.service[Logger]
      cache     =  FileCache(location = (cacheRoot / "coursier").toIO, logger = logger, pool = coursierPool)
        .withTtl(Some(Constants.cacheTtl))  // 12 hours
        // .withCachePolicies(Seq(coursier.cache.CachePolicy.ForceDownload))  // TODO cache policy
      repos     <- initializeRepositories(config.channels, cache, Constants.channelContentsTtl)  // 30 minutes
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


  sealed trait DecisionTree[+A, +B]
  case class Node[+A, +B](key: A, choices: Seq[(B, DecisionTree[A, B])]) extends DecisionTree[A, B] {
    require(choices.nonEmpty, "decision tree must not have empty choices")
  }
  case object Empty extends DecisionTree[Nothing, Nothing]

  object DecisionTree {
    private class NoCommonKeys(val msg: String) extends scala.util.control.ControlThrowable

    def fromVariants[A, B](variants: Seq[Map[A, B]]): Either[ErrStr, DecisionTree[A, B]] = {

      def helper(variants: Seq[Map[A, B]], remainingKeys: Set[A]): DecisionTree[A, B] = {
        remainingKeys.find(key => variants.forall(_.contains(key))) match
          case None => variants match
            case Seq(singleVariant) => Empty  // if there is just a single variant left, all its keys have already been chosen validly
            case _ => throw new NoCommonKeys(s"Variants do not have a key in common: $variants")  // our choices of keys left an ambiguity
          case Some(key) =>  // this key allows partitioning
            val remainingKeys2 = remainingKeys - key  // strictly smaller, so recursion is well-founded
            val parts: Map[B, Seq[Map[A, B]]] = variants.groupBy(_(key))
            val values: Seq[B] = variants.map(_(key)).distinct  // note that this preserves order
            val choices = values.map { value => value -> helper(parts(value), remainingKeys2) }
            Node(key, choices)
      }

      val allKeys = variants.flatMap(_.keysIterator).toSet
      try Right(helper(variants, allKeys)) catch { case e: NoCommonKeys => Left(e.msg) }
    }
  }

}
