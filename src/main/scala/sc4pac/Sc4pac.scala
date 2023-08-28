package io.github.memo33
package sc4pac

import scala.collection.immutable.{Set, Seq}
import coursier.{Type, Resolve, Fetch}
import coursier.core.{Module, Organization, ModuleName, Dependency, Publication, Configuration}
import coursier.util.{Artifact, EitherT, Gather}
import coursier.cache.FileCache
import upickle.default.{ReadWriter, readwriter, macroRW, read}
import java.nio.file.Path
import zio.{IO, ZIO, UIO, Task}

import sc4pac.error.*
import sc4pac.Constants.isSc4pacAsset
import sc4pac.Data.*
import sc4pac.Sc4pac.{StageResult, UpdatePlan}
import sc4pac.Resolution.{Dep, DepModule, DepAsset}

/** A plain Coursier logger, since Coursier's RefreshLogger results in dropped
  * or invisible messages, hiding the downloading activity.
  */
class Logger private (useColor: Boolean) extends coursier.cache.CacheLogger {

  private def cyan(msg: String): String = if (useColor) Console.CYAN + msg + Console.RESET else msg
  private def yellowBold(msg: String): String = if (useColor) Console.YELLOW + Console.BOLD + msg + Console.RESET else msg

  override def downloadingArtifact(url: String, artifact: coursier.util.Artifact) =
    println("  " + cyan(s"> Downloading $url"))
  override def downloadedArtifact(url: String, success: Boolean) = {
    if (!success) println("  " + cyan(s"  Download of $url unsuccessful"))
  }

  def log(msg: String): Unit = println(msg)
  def warn(msg: String): Unit = println(yellowBold("Warning:") + " " + msg)
}
object Logger {
  private def isWindows: Boolean = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("windows")
  private lazy val supportsColor: Boolean = io.github.alexarchambault.windowsansi.WindowsAnsi.setup()  // lazy val, since setup is only needed once
  def apply(useColor: Boolean): Logger = {
    if (useColor && isWindows && !supportsColor) new Logger(useColor = false) else new Logger(useColor)
  }
}

// TODO Use `Runtime#reportFatal` or `Runtime.setReportFatal` to log fatal errors like stack overflow

class Sc4pac(val repositories: Seq[MetadataRepository], val cache: FileCache[Task], val tempRoot: os.Path, val logger: Logger) extends UpdateService {  // TODO defaults

  given context: ResolutionContext = new ResolutionContext(repositories, cache, logger)

  import CoursierZio.*  // implicit coursier-zio interop

  // TODO check resolution.conflicts

  /** Add modules to the json file containing the explicitly installed modules
    * and return the new full list of explicitly installed modules.
    */
  def add(modules: Seq[ModuleNoAssetNoVar]): Task[Seq[ModuleNoAssetNoVar]] = {
    for {
      pluginsData  <- Data.readJsonIo[PluginsData](PluginsData.path)  // at this point, file should already exist
      modsOrig     <- Sc4pac.parseModules(pluginsData.explicit)
                        .mapError((err: String) => new Sc4pacIoException(s"format errors in json ${PluginsData.path}: $err"))
      modsNext     =  (modsOrig ++ modules).distinct
      _            <- ZIO.unless(modsNext == modsOrig) {
                        val pluginsDataNext = pluginsData.copy(explicit = modsNext.map(_.module.orgName).sorted)
                        // we do not check whether file was modified as this entire operation is synchronous and fast (no network calls, no cache usage)
                        Data.writeJsonIo(PluginsData.path, pluginsDataNext, None)(ZIO.succeed(()))
                      }
    } yield modsNext
  }

}


trait UpdateService { this: Sc4pac =>

  import CoursierZio.*  // implicit coursier-zio interop

  private def modulesToDependencies(modules: Seq[ModuleNoAssetNoVar], globalVariant: Variant): Task[Seq[DepModule]] = {
    // TODO bulk-query MetadataRepository for versions of all modules for efficiency
    ZIO.collectAllPar {
      modules.map { mod =>
        val version = Constants.versionLatestRelease
        for {
          // vsResult         <- coursierApi.versions.withModule(mod.module).result()
          // version          =  vsResult.versions.latest
          (_, variantData) <- Find.matchingVariant(mod, version, globalVariant)
        } yield DepModule(group = mod.module.organization, name = mod.module.name, version = version, variant = variantData.variant)
      }
    }
  }

  private def packageFolderName(dependency: DepModule): String = {
    val variantTokens = dependency.variant.toSeq.sortBy(_._1).map(_._2)
    val variantLabel = if (variantTokens.isEmpty) "" else variantTokens.mkString("-", "-", "")
    // we avoid the colons since they would need escaping in shells
    s"${dependency.group.value}-${dependency.name.value}$variantLabel-${dependency.version}.sc4pac"
  }

  /** Stage a single package into the temp plugins folder and return a list of
    * files or folders containing the files belonging to the package.
    * Moreover, return whether there was a warning.
    */
  private def stage(
    tempPluginsRoot: os.Path,
    dependency: DepModule,
    artifactsById: Map[(Organization, ModuleName), (Artifact, java.io.File)],
    jarsRoot: os.Path,
    progress: Sc4pac.Progress
  ): Task[(Seq[os.SubPath], Boolean)] = {
    def extract(assetData: AssetReference, pkgFolder: os.SubPath): Task[Unit] = ZIO.attempt {
      // Given an AssetReference, we look up the corresponding artifact file
      // by ID. This relies on the 1-to-1-correspondence between sc4pacAssets
      // and artifact files.
      val id = (Constants.sc4pacAssetOrg, ModuleName(assetData.assetId))
      artifactsById.get(id) match {
        case None =>
          logger.warn(s"skipping missing artifact, so it must be installed manually: ${id._1.value}:${id._2.value}")
        case Some(art, archive) =>
          // logger.log(s"  ==> $archive")  // TODO logging debug info
          val recipe = InstallRecipe.fromAssetReference(assetData)
          // ??? TODO extraction not implemented
          // TODO skip symlinks as a precaution

          // TODO check if archive type is zip
          val extractor = new ZipExtractor()
          extractor.extract(
            archive,
            tempPluginsRoot / pkgFolder,
            recipe,
            Some(ZipExtractor.JarExtraction.fromUrl(art.url, cache, jarsRoot)))
          // TODO catch IOExceptions
      }
    }

    // Since dependency is of type DepModule, we have already looked up the
    // variant successfully, but have lost the PackageData, so we reconstruct it
    // here a second time.
    val mod = ModuleNoAssetNoVar.fromDepModule(dependency)
    for {
      (pkgData, variantData) <- Find.matchingVariant(mod, dependency.version, dependency.variant)
      pkgFolder              =  pkgData.subfolder / packageFolderName(dependency)
      _                      <- ZIO.attempt(logger.log(f"$progress Extracting ${dependency.orgName} ${dependency.version}"))
      warnings               <- if (pkgData.info.warning.nonEmpty) ZIO.attempt { logger.warn(pkgData.info.warning); true } else ZIO.succeed(false)
      _                      <- ZIO.attempt(os.makeDir.all(tempPluginsRoot / pkgFolder))  // create folder even if package does not have any assets or files
      _                      <- ZIO.foreachDiscard(variantData.assets)(extract(_, pkgFolder))
    } yield (Seq(pkgFolder), warnings)  // for now, everything is installed into this folder only, so we do not need to list individual files
  }

  private def remove(toRemove: Set[Dep], installed: Seq[InstalledData], pluginsRoot: os.Path): Task[Unit] = {
    // removing files is synchronous but can be blocking a while, so we wrap it in Task (TODO should use zio blocking)
    val files = installed
      .filter(item => toRemove.contains(item.toDepModule))
      .flatMap(_.files)  // all files of packages to remove
    ZIO.foreachDiscard(files) { (sub: os.SubPath) =>  // this runs sequentially
      val path = pluginsRoot / sub
      ZIO.attempt {
        if (os.exists(path)) {
          os.remove.all(path)
        } else {
          logger.warn(s"removal failed as file did not exist: $path")
        }
      }
    }
  }

  private def logPackages(msg: String, dependencies: Iterable[Dep]): Unit = {
    logger.log(msg + dependencies.iterator.map(_.displayString).toSeq.sorted.mkString(f"%n"+" "*4, f"%n"+" "*4, f"%n"))
  }

  /** Update all installed packages from modules (the list of explicitly added packages). */
  def update(modules: Seq[ModuleNoAssetNoVar], globalVariant0: Variant, pluginsRoot: os.Path): Task[Boolean] = {

    def logPlan(plan: Sc4pac.UpdatePlan): UIO[Unit] = ZIO.succeed {
      if (plan.toRemove.nonEmpty) logPackages(f"The following packages will be removed:%n", plan.toRemove)
      if (plan.toReinstall.nonEmpty) logPackages(f"The following packages will be reinstalled:%n", plan.toReinstall)
      if (plan.toInstall.nonEmpty) logPackages(f"The following packages will be installed:%n", plan.toInstall)
      if (plan.isUpToDate) logger.log("Everything is up-to-date.")
    }

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
    def stageAll(deps: Seq[DepModule], artifactsById: Map[(Organization, ModuleName), (Artifact, java.io.File)]): Task[StageResult] = {

      val makeTempStagingDir = ZIO.attempt {
        os.makeDir.all(tempRoot)
        os.temp.dir(tempRoot, prefix = "staging-process", deleteOnExit = true)
      }

      for {
        stagingRoot             <- makeTempStagingDir
        tempPluginsRoot         =  stagingRoot / "plugins"
        _                       <- ZIO.attempt(os.makeDir(tempPluginsRoot))
        jarsRoot                =  stagingRoot / "jars"
        numDeps                 =  deps.length
        (stagedFiles, warnings) <- ZIO.foreach(deps.zipWithIndex) { case (dep, idx) =>   // sequentially stages each package
                                     stage(tempPluginsRoot, dep, artifactsById, jarsRoot, Sc4pac.Progress(idx+1, numDeps))
                                   }.map(_.unzip)
        _                       <- if (warnings.forall(_ == false)) ZIO.succeed(true)
                                   else Prompt.yesNo("Continue despite warnings?").filterOrFail(_ == true)(Sc4pacAbort())
      } yield StageResult(tempPluginsRoot, deps.zip(stagedFiles), stagingRoot)
    }

    /** Moves staged files from temp plugins to actual plugins. This effect has
      * no expected failures, but only potentially unexpected defects.
      */
    def movePackagesToPlugins(staged: StageResult): IO[Nothing, Unit] = {
      ZIO.validateDiscard(staged.files) { case (dep, pkgFiles) =>
        ZIO.foreachDiscard(pkgFiles) { subPath =>
          ZIO.attempt {
            os.move.over(staged.tempPluginsRoot / subPath, pluginsRoot / subPath, replaceExisting = true, createFolders = true)
          } catchSome { case _: java.nio.file.DirectoryNotEmptyException => ZIO.attempt {
            // moving a directory fails if its children require moving as well
            // (e.g. moving between two devices), so fall back to copying
            os.copy.over(staged.tempPluginsRoot / subPath, pluginsRoot / subPath, replaceExisting = true, createFolders = true)
          }}
        } refineOrDie { case e: java.io.IOException =>  // TODO this potentially dies on unexpected errors (defects) that should maybe be handled further up top
          logger.warn(e.toString)
          s"${dep.orgName}"  // failed to move some staged files of this package to plugins
        }
      }.fold(
        failure = { (failedPkgs: ::[ErrStr]) =>
          logger.warn(s"Failed to correctly install the following packages (manual intervention needed): ${failedPkgs.mkString(" ")}")
          // TODO further handling?
        },
        success = { _ =>
          logger.log("Done.")  // TODO
        }
      )
    }

    // TODO make sure that staging is completed once we execute this
    /** Remove old files from plugins and move staged files and folders into
      * plugins folder. Also update the json database of installed files.
      */
    def publishToPlugins(staged: StageResult, pluginsLockData: PluginsLockData, plan: UpdatePlan): Task[Boolean] = {
      // - lock the json database using file lock
      // - remove old packages
      // - move new packages into plugins folder
      // - write json database and release lock
      Data.writeJsonIo(PluginsLockData.path, pluginsLockData.updateTo(plan, staged.files.toMap), Some(pluginsLockData)) {
        for {
          _ <- remove(plan.toRemove, pluginsLockData.installed, pluginsRoot)
                 // .catchAll(???)  // TODO catch exceptions
          _ <- movePackagesToPlugins(staged)
        } yield true  // TODO return result
      }
    }

    def doPromptingForVariant[A](globalVariant: Variant)(task: Variant => Task[A]): Task[(A, Variant)] = {
      ZIO.iterate(Left(globalVariant): Either[Variant, (A, Variant)])(_.isLeft) {
        case Right(_) => throw new AssertionError
        case Left(globalVariant) =>
          def handler(pkgData: PackageData) = {
            val unknownVariants = pkgData.unknownVariants(globalVariant)
            val mod = Module(Organization(pkgData.group), ModuleName(pkgData.name), attributes = Map.empty)
            val choose: Task[Seq[(String, String)]] = ZIO.foreach(unknownVariants.toSeq) { (key, values) =>
              Prompt.numbered(s"""Choose a "$key" variant for $mod:""", values).map(v => (key, v))
            }
            choose.map(additionalChoices => Left(globalVariant ++ additionalChoices))
          }
          task(globalVariant)
            .map(x => Right((x, globalVariant)))
            .catchSome { case e: Sc4pacMissingVariant => handler(e.packageData) }
            .catchSomeDefect { case e: Sc4pacMissingVariant => handler(e.packageData) }  // Since Repository works with EitherT[Task, ErrStr, _], Sc4pacMissingVariant is a `defect` rather than a regular `error`
      }.map(_.toOption.get)
    }

    def tryResolve(globalVariant: Variant): Task[Resolution] = for {
      initialDeps <- modulesToDependencies(modules, globalVariant)  // including variants
      resolution  <- Resolution.resolve(initialDeps, globalVariant)
    } yield resolution

    def storeGlobalVariant(globalVariant: Variant): Task[Unit] = for {
      pluginsData <- Data.readJsonIo[PluginsData](PluginsData.path)  // json file should exist already
      _           <- Data.writeJsonIo(PluginsData.path, pluginsData.copy(config = pluginsData.config.copy(variant = globalVariant)), None)(ZIO.succeed(()))
    } yield ()

    // TODO catch coursier.error.ResolutionError$CantDownloadModule (e.g. when json files have syntax issues)
    for {
      pluginsLockData <- PluginsLockData.readOrInit
      (resolution, globalVariant) <- doPromptingForVariant(globalVariant0)(tryResolve)
      // _               <- ZIO.attempt(logger.log(s"resolution:\n${resolution.dependencySet.mkString("\n")}"))
      plan            =  UpdatePlan.fromResolution(resolution, installed = pluginsLockData.dependenciesWithAssets)
      _               <- logPlan(plan)
      _               <- if (plan.isUpToDate) ZIO.succeed(true)
                         else Prompt.yesNo("Continue?").filterOrFail(_ == true)(Sc4pacAbort())
      _               <- ZIO.unless(globalVariant == globalVariant0)(storeGlobalVariant(globalVariant))  // only store something after confirmation
      assetsToInstall <- resolution.fetchArtifactsOf(plan.toInstall)
      // TODO if some artifacts fail to be fetched, fall back to installing remaining packages (maybe not(?), as this leads to missing dependencies,
      // but there needs to be a manual workaround in case of permanently missing artifacts)
      depsToStage     =  plan.toInstall.collect{ case d: DepModule => d }.toSeq  // keep only non-assets
      artifactsById   =  assetsToInstall.map((dep, pub, art, file) => (dep.module.organization, dep.module.name) -> (art, file)).toMap
      _               =  require(artifactsById.size == assetsToInstall.size, s"artifactsById is not 1-to-1: $assetsToInstall")
      stageResult     <- stageAll(depsToStage, artifactsById)
      // _               <- ZIO.attempt(logger.log(s"stage result: $stageResult"))
      flag            <- publishToPlugins(stageResult, pluginsLockData, plan)
      _               <- ZIO.attempt(os.remove.all(stageResult.stagingRoot))  // deleteOnExit does not seem to work reliably, so explicitly delete temp folder  TODO catch and ignore TODO finally
    } yield flag  // TODO decide what flag means

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

      val wanted: Set[Dep] = resolution.dependencySet
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


  private def fetchChannelData(repoUri: String, cache: FileCache[Task], channelContentsTtl: Option[scala.concurrent.duration.Duration]): IO[ErrStr, MetadataRepository] = {
    import CoursierZio.*  // implicit coursier-zio interop
    val contentsUrl = MetadataRepository.channelContentsUrl(repoUri)
    val artifact = Artifact(contentsUrl).withChanging(true)  // changing as the remote file is updated whenever any remote package is added or updated
    cache
      .withTtl(channelContentsTtl.orElse(cache.ttl))
      .fetch(artifact)  // requires initialized logger
      .toZioRefine { case e: coursier.error.FetchError.DownloadingArtifacts =>  // TODO don't die on everything else
        ZIO.fail(f"could not access remote channel $repoUri%n$e")
      }  // dies on all throwables; TODO catch which Exceptions?
      .flatMap((jsonStr: String) => ZIO.fromEither {
        Data.readJson[ChannelData](jsonStr, errMsg = contentsUrl)  // TODO does this really give the jsonStr directly instead of a path to a jsonFile?
          .map(channelData => MetadataRepository(repoUri, channelData, globalVariant = Map.empty))
      })
  }

  private[sc4pac] def initializeRepositories(repoUris: Seq[String], cache: FileCache[Task], channelContentsTtl: Option[scala.concurrent.duration.Duration]): Task[Seq[MetadataRepository]] = {
    val task: Task[Seq[MetadataRepository]] = ZIO.collectPar(repoUris) { url =>
      fetchChannelData(url, cache, channelContentsTtl)
        .mapError((err: ErrStr) => { System.err.println(s"Failed to read channel data: $err"); None })
    }.filterOrFail(_.nonEmpty)(Sc4pacAbort(s"No channels available: $repoUris"))
    // TODO for long running processes, we might need a way to refresh the channel
    // data occasionally (but for now this is good enough)
    import CoursierZio.*  // implicit coursier-zio interop
    cache.logger.using(task)  // properly initializes logger (avoids Uninitialized TermDisplay)
  }

  def init(config: ConfigData): Task[Sc4pac] = {
    import CoursierZio.*  // implicit coursier-zio interop
    // val refreshLogger = coursier.cache.loggers.RefreshLogger.create(System.err)  // TODO System.err seems to cause less collisions between refreshing progress and ordinary log messages
    val logger = Logger(config.color)
    val cache = FileCache[zio.Task]().withLocation(config.cacheRoot.resolve("coursier").toFile()).withLogger(logger)
      // .withCachePolicies(Seq(coursier.cache.CachePolicy.ForceDownload))  // TODO cache policy
      // .withPool(pool)  // TODO thread pool
      // .withTtl(1.hour)  // TODO time-to-live
    val tempRoot = os.Path(config.tempRoot, os.pwd)
    for (repos <- initializeRepositories(config.channels, cache, channelContentsTtl = None)) yield Sc4pac(repos, cache, tempRoot, logger)
  }

  def parseModules(modules: Seq[String]): IO[ErrStr, Seq[ModuleNoAssetNoVar]] = {
    ZIO.fromEither {
      coursier.parse.ModuleParser
        .modules(modules, defaultScalaVersion = "")
        .flatMap { modules =>
          (new coursier.util.Traverse.TraverseOps(modules)).validationNelTraverse(m => coursier.util.ValidationNel.fromEither(ModuleNoAssetNoVar.fromModule(m)))
        }
        .either
    } mapError { (errs: List[ErrStr]) =>
      errs.mkString(", ")  // malformed module: a, malformed module: b
    }
  }

}
