package io.github.memo33
package sc4pac

import scala.collection.immutable.{Set, Seq}
import coursier.{Type, Resolve, Fetch}
import coursier.core.{Module, Organization, ModuleName, Dependency, Publication, Configuration}
import coursier.util.{Artifact, EitherT, Gather}
import java.nio.file.Path
import zio.{IO, ZIO, UIO, Task, Scope, RIO}

import sc4pac.error.*
import sc4pac.Constants.isSc4pacAsset
import sc4pac.JsonData as JD
import sc4pac.Sc4pac.{StageResult, UpdatePlan}
import sc4pac.Resolution.{Dep, DepModule, DepAsset}

// TODO Use `Runtime#reportFatal` or `Runtime.setReportFatal` to log fatal errors like stack overflow

class Sc4pac(val repositories: Seq[MetadataRepository], val cache: FileCache, val tempRoot: os.Path, val logger: Logger, val prompter: Prompter, val scopeRoot: os.Path) extends UpdateService {  // TODO defaults

  given context: ResolutionContext = new ResolutionContext(repositories, cache, logger, scopeRoot)

  import CoursierZio.*  // implicit coursier-zio interop

  // TODO check resolution.conflicts

  private def modifyExplicitModules[R](modify: Seq[BareModule] => ZIO[R, Throwable, Seq[BareModule]]): ZIO[R, Throwable, Seq[BareModule]] = {
    for {
      pluginsData  <- JsonIo.read[JD.Plugins](JD.Plugins.path(scopeRoot))  // at this point, file should already exist
      modsOrig     =  pluginsData.explicit
      modsNext     <- modify(modsOrig)
      _            <- ZIO.unless(modsNext == modsOrig) {
                        val pluginsDataNext = pluginsData.copy(explicit = modsNext)
                        // we do not check whether file was modified as this entire operation is synchronous and fast, in most cases
                        JsonIo.write(JD.Plugins.path(scopeRoot), pluginsDataNext, None)(ZIO.succeed(()))
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
          logger   <- ZIO.service[CliLogger]
          selected <- Prompt.numberedMultiSelect(
                        "Select packages to remove:",
                        modsOrig.sortBy(m => (m.group.value, m.name.value)),
                        _.formattedDisplayString(logger.gray, identity)
                      ).map(_.toSet)
        } yield modsOrig.filterNot(selected)
      }
    }
  }

  /** Fuzzy-search across all repositories.
    * The selection of results is ordered in descending order and includes the
    * module, the relevance ratio and the description.
    */
  def search(query: String, threshold: Int): Task[Seq[(BareModule, Int, Option[String])]] = ZIO.attempt {
    val results: Seq[(BareModule, Int, Option[String])] =
      repositories.flatMap { repo =>
        repo.iterateChannelContents.flatMap { item =>
          if (item.isSc4pacAsset) {
            None
          } else {
            // TODO reconsider choice of search algorithm
            val ratio = me.xdrop.fuzzywuzzy.FuzzySearch.tokenSetRatio(query, item.toSearchString)
            if (ratio >= threshold) {
              Some(BareModule(Organization(item.group), ModuleName(item.name)), ratio, Option(item.summary).filter(_.nonEmpty))
            } else None
          }
        }
      }
    results.sortBy((mod, ratio, desc) => (-ratio, mod.group.value, mod.name.value)).distinctBy(_._1)
  }

  def info(module: BareModule): RIO[CliLogger, Option[Seq[(String, String)]]] = {
    val mod = Module(module.group, module.name, attributes = Map.empty)
    for {
      version <- Find.concreteVersion(mod, Constants.versionLatestRelease)
      pkgOpt  <- Find.packageData[JD.Package](mod, version)
      logger  <- ZIO.service[CliLogger]
    } yield {
      pkgOpt.map { pkg =>
        val b = Seq.newBuilder[(String, String)]
        b += "Name" -> s"${pkg.group}:${pkg.name}"
        b += "Version" -> pkg.version
        b += "Subfolder" -> pkg.subfolder.toString
        b += "Summary" -> pkg.info.summary
        if (pkg.info.description.nonEmpty)
          b += "Description" -> pkg.info.description
        if (pkg.info.warning.nonEmpty)
          b += "Warning" -> pkg.info.warning
        if (pkg.info.conflicts.nonEmpty)
          b += "Conflicts" -> pkg.info.conflicts
        if (pkg.info.author.nonEmpty)
          b += "Author" -> pkg.info.author
        if (pkg.info.website.nonEmpty)
          b += "Website" -> pkg.info.website

        def mkDeps(vd: JD.VariantData) = {
          val deps = vd.bareDependencies.collect{ case m: BareModule => m.formattedDisplayString(logger.gray, identity) }
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

  import CoursierZio.*  // implicit coursier-zio interop

  private def packageFolderName(dependency: DepModule): String = {
    val variantTokens = dependency.variant.toSeq.sortBy(_._1).map(_._2)
    val variantLabel = if (variantTokens.isEmpty) "" else variantTokens.mkString(".", "-", "")
    // we avoid the colons since they would need escaping in shells
    s"${dependency.group.value}.${dependency.name.value}$variantLabel.${dependency.version}.sc4pac"
  }

  /** Stage a single package into the temp plugins folder and return a list of
    * files or folders containing the files belonging to the package.
    * Moreover, return whether there was a warning.
    */
  private def stage(
    tempPluginsRoot: os.Path,
    dependency: DepModule,
    artifactsById: Map[BareAsset, (Artifact, java.io.File)],
    jarsRoot: os.Path,
    progress: Sc4pac.Progress
  ): Task[(Seq[os.SubPath], Seq[Warning])] = {
    def extract(assetData: JD.AssetReference, pkgFolder: os.SubPath): Task[Seq[Warning]] = ZIO.attemptBlocking {
      // Given an AssetReference, we look up the corresponding artifact file
      // by ID. This relies on the 1-to-1-correspondence between sc4pacAssets
      // and artifact files.
      val id = BareAsset(ModuleName(assetData.assetId))
      artifactsById.get(id) match {
        case None =>
          val w = s"An artifact is missing and has been skipped, so it must be installed manually: ${id.orgName}"
          logger.warn(w)
          Seq(w)
        case Some(art, archive) =>
          // logger.log(s"  ==> $archive")  // TODO logging debug info
          val recipe = JD.InstallRecipe.fromAssetReference(assetData)
          // ??? TODO extraction not implemented
          // TODO skip symlinks as a precaution

          // TODO check if archive type is zip
          val extractor = new Extractor(logger)
          extractor.extract(
            archive,
            tempPluginsRoot / pkgFolder,
            recipe,
            Some(Extractor.JarExtraction.fromUrl(art.url, cache, jarsRoot = jarsRoot, scopeRoot = scopeRoot)))
          // TODO catch IOExceptions
          Seq.empty
      }
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
      warnings           <- if (pkgData.info.warning.nonEmpty) ZIO.attempt { val w = pkgData.info.warning; logger.warn(w); Seq(w) }
                            else ZIO.succeed(Seq.empty)
    } yield (Seq(pkgFolder), warnings ++ artifactWarnings)  // for now, everything is installed into this folder only, so we do not need to list individual files
  }

  private def remove(toRemove: Set[Dep], installed: Seq[JD.InstalledData], pluginsRoot: os.Path): Task[Unit] = {
    // removing files is synchronous but can be blocking a while, so we wrap it in Task (TODO should use zio blocking)
    val files = installed
      .filter(item => toRemove.contains(item.toDepModule))
      .flatMap(_.files)  // all files of packages to remove
    ZIO.foreachDiscard(files) { (sub: os.SubPath) =>  // this runs sequentially
      val path = pluginsRoot / sub
      ZIO.attemptBlocking {
        if (os.exists(path)) {
          os.remove.all(path)
        } else {
          logger.warn(s"removal failed as file did not exist: $path")
        }
      }
    }
  }

  /** Update all installed packages from modules (the list of explicitly added packages). */
  def update(modules: Seq[BareModule], globalVariant0: Variant, pluginsRoot: os.Path): RIO[ScopeRoot, Boolean] = {

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
    def stageAll(deps: Seq[DepModule], artifactsById: Map[BareAsset, (Artifact, java.io.File)]): ZIO[Scope, Throwable, StageResult] = {

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
        jarsRoot                =  stagingRoot / "jars"
        numDeps                 =  deps.length
        (stagedFiles, warnings) <- ZIO.foreach(deps.zipWithIndex) { case (dep, idx) =>   // sequentially stages each package
                                     stage(tempPluginsRoot, dep, artifactsById, jarsRoot, Sc4pac.Progress(idx+1, numDeps))
                                   }.map(_.unzip)
        pkgWarnings             =  deps.zip(warnings).collect { case (dep, ws) if ws.nonEmpty => (dep.toBareDep, ws) }
        _                       <- prompter.confirmInstallationWarnings(pkgWarnings)
      } yield StageResult(tempPluginsRoot, deps.zip(stagedFiles), stagingRoot)
    }

    /** Moves staged files from temp plugins to actual plugins. This effect has
      * no expected failures, but only potentially unexpected defects.
      */
    def movePackagesToPlugins(staged: StageResult): IO[Sc4pacPublishWarning, Unit] = {
      ZIO.validateDiscard(staged.files) { case (dep, pkgFiles) =>
        ZIO.foreachDiscard(pkgFiles) { subPath =>
          ZIO.attemptBlocking {
            os.move.over(staged.tempPluginsRoot / subPath, pluginsRoot / subPath, replaceExisting = true, createFolders = true)
          } catchSome { case _: java.nio.file.DirectoryNotEmptyException => ZIO.attemptBlocking {
            // moving a directory fails if its children require moving as well
            // (e.g. moving between two devices), so fall back to copying
            os.copy.over(staged.tempPluginsRoot / subPath, pluginsRoot / subPath, replaceExisting = true, createFolders = true)
          }}
        } refineOrDie { case e: java.io.IOException =>  // TODO this potentially dies on unexpected errors (defects) that should maybe be handled further up top
          logger.warn(e.toString)
          s"${dep.orgName}"  // failed to move some staged files of this package to plugins
        }
      }.mapError((failedPkgs: ::[ErrStr]) =>
        new Sc4pacPublishWarning(s"Failed to correctly install the following packages (manual intervention needed): ${failedPkgs.mkString(" ")}")
      )
    }

    // TODO make sure that staging is completed once we execute this
    /** Remove old files from plugins and move staged files and folders into
      * plugins folder. Also update the json database of installed files.
      */
    def publishToPlugins(staged: StageResult, pluginsLockData: JD.PluginsLock, plan: UpdatePlan): Task[Boolean] = {
      // - lock the json database using file lock
      // - remove old packages
      // - move new packages into plugins folder
      // - write json database and release lock
      val task = JsonIo.write(JD.PluginsLock.path(scopeRoot), pluginsLockData.updateTo(plan, staged.files.toMap), Some(pluginsLockData)) {
        for {
          _ <- remove(plan.toRemove, pluginsLockData.installed, pluginsRoot)
                 // .catchAll(???)  // TODO catch exceptions
          _ <- movePackagesToPlugins(staged)
        } yield true  // TODO return result
      }
      logger.publishing(removalOnly = plan.toInstall.isEmpty)(task)
        .catchSome {  // TODO expose publish warnings to clients
          case e: Sc4pacPublishWarning => logger.warn(e.getMessage); ZIO.succeed(true)  // TODO return result
        }
    }

    /** Prompts for missing variant keys, so that the result allows to pick a unique variant of the package. */
    def refineGlobalVariant(globalVariant: Variant, pkgData: JD.Package): Task[Variant] = {
      val mod = BareModule(Organization(pkgData.group), ModuleName(pkgData.name))
      import Sc4pac.{DecisionTree, Node, Empty}
      DecisionTree.fromVariants(pkgData.variants.map(_.variant)) match {
        case Left(err) => ZIO.fail(new error.UnsatisfiableVariantConstraints(
          s"Unable to choose variants as the metadata of ${mod.orgName} seems incomplete: $err"))
        case Right(decisionTree) =>
          type Key = String; type Value = String
          def choose[T](key: Key, choices: Seq[(Value, T)]): Task[(Value, T)] = {
            globalVariant.get(key) match
              case Some(value) => choices.find(_._1 == value) match
                case Some(choice) => ZIO.succeed(choice)
                case None => ZIO.fail(new error.UnsatisfiableVariantConstraints(
                  s"""None of the variants ${choices.map(_._1).mkString(", ")} of ${mod.orgName} match the configured variant $key=$value. """ +
                  s"""The package metadata seems incorrect, but resetting the variant may resolve the problem (command: `sc4pac variant reset "$key"`)."""))
              case None =>  // global variant for key is not set, so choose it interactively
                prompter.promptForVariant(
                  module = mod,
                  label = key,
                  values = choices.map(_._1),
                  descriptions = pkgData.variantDescriptions.get(key).getOrElse(Map.empty)
                ).map(value => choices.find(_._1 == value).get)  // prompter is guaranteed to return a matching value
          }

          ZIO.iterate(decisionTree, Seq.newBuilder[(Key, Value)])(_._1 != Empty) {
            case (Node(key, choices), builder) => choose(key, choices).map { case (value, subtree) => (subtree, builder += key -> value) }
            case (Empty, builder) => throw new AssertionError
          }.map(_._2.result())
            .map(additionalChoices => globalVariant ++ additionalChoices)
      }
    }

    def doPromptingForVariant[A](globalVariant: Variant)(task: Variant => Task[A]): Task[(A, Variant)] = {
      ZIO.iterate(Left(globalVariant): Either[Variant, (A, Variant)])(_.isLeft) {
        case Right(_) => throw new AssertionError
        case Left(globalVariant) =>
          val handler: PartialFunction[Throwable, Task[Either[Variant, (A, Variant)]]] = {
            case e: Sc4pacMissingVariant => refineGlobalVariant(globalVariant, e.packageData).map(Left(_))
          }
          task(globalVariant)
            .map(x => Right((x, globalVariant)))
            .catchSome(handler)
            .catchSomeDefect(handler)  // Since Repository works with EitherT[Task, ErrStr, _], Sc4pacMissingVariant is a `defect` rather than a regular `error`
      }.map(_.toOption.get)
    }

    def storeGlobalVariant(globalVariant: Variant): Task[Unit] = for {
      pluginsData <- JsonIo.read[JD.Plugins](JD.Plugins.path(scopeRoot))  // json file should exist already
      _           <- JsonIo.write(JD.Plugins.path(scopeRoot), pluginsData.copy(config = pluginsData.config.copy(variant = globalVariant)), None)(ZIO.succeed(()))
    } yield ()

    // TODO catch coursier.error.ResolutionError$CantDownloadModule (e.g. when json files have syntax issues)
    for {
      pluginsLockData <- JD.PluginsLock.readOrInit
      (resolution, globalVariant) <- doPromptingForVariant(globalVariant0)(Resolution.resolve(modules, _))
      plan            =  UpdatePlan.fromResolution(resolution, installed = pluginsLockData.dependenciesWithAssets)
      continue        <- prompter.confirmUpdatePlan(plan)
      flagOpt         <- ZIO.unless(plan.isUpToDate || !continue)(for {
        _               <- ZIO.unless(globalVariant == globalVariant0)(storeGlobalVariant(globalVariant))  // only store something after confirmation
        assetsToInstall <- resolution.fetchArtifactsOf(resolution.transitiveDependencies.filter(plan.toInstall).reverse)  // we start by fetching artifacts in reverse as those have fewest dependencies of their own
        // TODO if some artifacts fail to be fetched, fall back to installing remaining packages (maybe not(?), as this leads to missing dependencies,
        // but there needs to be a manual workaround in case of permanently missing artifacts)
        depsToStage     =  plan.toInstall.collect{ case d: DepModule => d }.toSeq  // keep only non-assets
        artifactsById   =  assetsToInstall.map((dep, art, file) => dep.toBareDep -> (art, file)).toMap
        _               =  require(artifactsById.size == assetsToInstall.size, s"artifactsById is not 1-to-1: $assetsToInstall")
        flag            <- ZIO.scoped(stageAll(depsToStage, artifactsById)
                                      .flatMap(publishToPlugins(_, pluginsLockData, plan)))
        _               <- ZIO.attempt(logger.log("Done."))
      } yield flag)
    } yield flagOpt.getOrElse(false)  // TODO decide what flag means

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

  class Progress(numerator: Int, denominator: Int) {
    override def toString = s"($numerator/$denominator)"
  }

  case class StageResult(tempPluginsRoot: os.Path, files: Seq[(DepModule, Seq[os.SubPath])], stagingRoot: os.Path)


  private def fetchChannelData(repoUri: java.net.URI, cache: FileCache, channelContentsTtl: Option[scala.concurrent.duration.Duration]): ZIO[ScopeRoot, ErrStr, MetadataRepository] = {
    import CoursierZio.*  // implicit coursier-zio interop
    val contentsUrl = MetadataRepository.channelContentsUrl(repoUri).toString
    val artifact = Artifact(contentsUrl).withChanging(true)  // changing as the remote file is updated whenever any remote package is added or updated
    for {
      channelContentsFile <- cache
                              .withTtl(channelContentsTtl.orElse(cache.ttl))
                              .file(artifact)  // requires initialized logger
                              .run.absolve
                              .mapError { case e @ (_: coursier.cache.ArtifactError | scala.util.control.NonFatal(_)) => e.getMessage }
      scopeRoot           <- ZIO.service[ScopeRoot]
      repo                <- MetadataRepository.create(os.Path(channelContentsFile: java.io.File, scopeRoot.path), repoUri)
    } yield repo
  }

  private def wrapService[R : zio.Tag, E, A](use: ZIO[Any, E, A] => ZIO[Any, E, A], task: ZIO[R, E, A]): ZIO[R, E, A] = {
    for {
      service <- ZIO.service[R]
      task2   =  task.provideLayer(zio.ZLayer.succeed(service))
      result  <- use(task2)
    } yield result
  }

  private[sc4pac] def initializeRepositories(repoUris: Seq[java.net.URI], cache: FileCache, channelContentsTtl: Option[scala.concurrent.duration.Duration]): RIO[ScopeRoot, Seq[MetadataRepository]] = {
    val task: RIO[ScopeRoot, Seq[MetadataRepository]] = ZIO.collectPar(repoUris) { url =>
      fetchChannelData(url, cache, channelContentsTtl)
        .mapError((err: ErrStr) => { System.err.println(s"Failed to read channel data: $err"); None })
    }.filterOrFail(_.nonEmpty)(Sc4pacAbort(s"No channels available: $repoUris"))
    // TODO for long running processes, we might need a way to refresh the channel
    // data occasionally (but for now this is good enough)
    import CoursierZio.*  // implicit coursier-zio interop
    wrapService(cache.logger.using(_), task)  // properly initializes logger (avoids Uninitialized TermDisplay)
  }

  def init(config: JD.Config): RIO[ScopeRoot & CliLogger & Prompter, Sc4pac] = {
    import CoursierZio.*  // implicit coursier-zio interop
    // val refreshLogger = coursier.cache.loggers.RefreshLogger.create(System.err)  // TODO System.err seems to cause less collisions between refreshing progress and ordinary log messages
    val coursierPool = coursier.cache.internal.ThreadUtil.fixedThreadPool(size = 2)  // limit parallel downloads to 2 (ST rejects too many connections)
    for {
      cacheRoot <- config.cacheRootAbs
      logger <- ZIO.service[CliLogger]
      prompter <- ZIO.service[Prompter]
      cache = FileCache(location = (cacheRoot / "coursier").toIO, logger = logger, pool = coursierPool)
        // .withCachePolicies(Seq(coursier.cache.CachePolicy.ForceDownload))  // TODO cache policy
        // .withTtl(1.hour)  // TODO time-to-live
      repos     <- initializeRepositories(config.channels, cache, channelContentsTtl = None)
      tempRoot <- config.tempRootAbs
      scopeRoot <- ZIO.service[ScopeRoot]
    } yield Sc4pac(repos, cache, tempRoot, logger, prompter, scopeRoot.path)
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
