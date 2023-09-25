package io.github.memo33
package sc4pac

import scala.collection.immutable.{Set, Seq}
import coursier.{Type, Resolve, Fetch}
import coursier.core.{Module, Organization, ModuleName, Dependency, Publication, Configuration}
import coursier.util.{Artifact, EitherT, Gather}
import upickle.default.{ReadWriter, readwriter, macroRW, read}
import java.nio.file.Path
import zio.{IO, ZIO, UIO, Task}
import org.fusesource.jansi.Ansi

import sc4pac.error.*
import sc4pac.Constants.isSc4pacAsset
import sc4pac.JsonData as JD
import sc4pac.Sc4pac.{StageResult, UpdatePlan}
import sc4pac.Resolution.{Dep, DepModule, DepAsset, BareModule, BareAsset, BareDep}

/** A plain Coursier logger, since Coursier's RefreshLogger results in dropped
  * or invisible messages, hiding the downloading activity.
  */
class Logger private (out: java.io.PrintStream, useColor: Boolean, isInteractive: Boolean) extends coursier.cache.CacheLogger {

  private def cyan(msg: String): String = if (useColor) Console.CYAN + msg + Console.RESET else msg
  private def cyanBold(msg: String): String = if (useColor) Console.CYAN + Console.BOLD + msg + Console.RESET else msg
  private def yellowBold(msg: String): String = if (useColor) Console.YELLOW + Console.BOLD + msg + Console.RESET else msg
  private def bold(msg: String): String = if (useColor) Console.BOLD + msg + Console.RESET else msg
  def gray(msg: String): String = if (useColor) s"${27.toChar}[90m" + msg + Console.RESET else msg  // aka bright black

  override def downloadingArtifact(url: String, artifact: coursier.util.Artifact) =
    out.println("  " + cyan(s"> Downloading $url"))
  override def downloadedArtifact(url: String, success: Boolean) =
    if (!success)
      out.println("  " + cyan(s"  Download of $url unsuccessful"))
    else if (Constants.debugMode)
      out.println("  " + gray(s"  Downloaded $url"))
  override def downloadLength(url: String, len: Long, currentLen: Long, watching: Boolean): Unit =
    if (Constants.debugMode)
      out.println(gray(s"--> downloadLength=$currentLen/$len: $url"))
  override def gettingLength(url: String): Unit =
    if (Constants.debugMode)
      out.println(gray(s"--> gettingLength $url"))
  override def gettingLengthResult(url: String, length: Option[Long]): Unit =
    if (Constants.debugMode)
      out.println(gray(s"--> gettingLengthResult=$length: $url"))

  def log(msg: String): Unit = out.println(msg)
  def warn(msg: String): Unit = out.println(yellowBold("Warning:") + " " + msg)

  def logSearchResult(idx: Int, module: BareModule, description: Option[String], installed: Boolean): Unit = {
    val mod = module.formattedDisplayString(gray, bold) + (if (installed) " " + cyanBold("[installed]") else "")
    log((Array(s"(${idx+1}) $mod") ++ description).mkString(f"%n" + " "*8))
  }

  def logInfoResult(infoResult: Seq[(String, String)]): Unit = {
    val columnWidth = infoResult.map(_._1.length).maxOption.getOrElse(0)
    for ((label, description) <- infoResult) {
      log(bold(label + " " * (columnWidth - label.length) + " :") + s" $description")
    }
  }

  def logInstalled(module: DepModule, explicit: Boolean): Unit = {
    log(module.formattedDisplayString(gray) + (if (explicit) " " + cyanBold("[explicit]") else ""))
  }

  // private val spinnerSymbols = collection.immutable.ArraySeq("⡿", "⣟", "⣯", "⣷", "⣾", "⣽", "⣻", "⢿").reverse
  private val spinnerSymbols = {
    val n = 6
    val xs = collection.immutable.ArraySeq.tabulate(n+1)(i => "▪"*i + "▫"*(n-i))  // supported by Windows default font Consolas
    xs.dropRight(1) ++ xs.drop(1).map(_.reverse).reverse
  }

  /** Print a message, followed by a spinning animation, while running a task.
    * The task should not print anything, unless sameLine is true.
    */
  def withSpinner[A](msg: Option[String], sameLine: Boolean, cyan: Boolean = false, duration: java.time.Duration = java.time.Duration.ofMillis(100))(task: Task[A]): Task[A] = {
    if (msg.nonEmpty) {
      out.println(msg.get)
    }
    if (!useColor || !isInteractive) {
      task
    } else {
      val coloredSymbols = if (!cyan) spinnerSymbols else spinnerSymbols.map(this.cyan)
      val spin: String => Unit = if (!sameLine) {
        val col = msg.map(_.length + 2).getOrElse(1)
        (symbol) => out.print(Ansi.ansi().saveCursorPosition().cursorUpLine().cursorToColumn(col).a(symbol).restoreCursorPosition())
      } else {
        (symbol) => out.print(Ansi.ansi().saveCursorPosition().cursorRight(2).a(symbol).restoreCursorPosition())
      }
      val spinner = ZIO.iterate(0)(_ => true) { i =>
        for (_ <- ZIO.sleep(duration)) yield {  // TODO use zio.Schedule instead?
          spin(coloredSymbols(i))
          (i+1) % coloredSymbols.length
        }
      }
      // run task and spinner in parallel and interrupt spinner once task completes or fails
      for (result <- task.map(Right(_)).raceFirst(spinner.map(Left(_)))) yield {
        spin(" " * spinnerSymbols.head.length)  // clear animation
        result.toOption.get  // spinner/Left will never complete, so we get A from Right
      }
    }
  }
}

object Logger {
  def apply(): Logger = {
    val isInteractive = Constants.isInteractive
    try {
      val useColor = org.fusesource.jansi.AnsiConsole.out().getMode() != org.fusesource.jansi.AnsiMode.Strip
      // the streams have been installed in `main` (installation is required since jansi 2.1.0)
      new Logger(org.fusesource.jansi.AnsiConsole.out(), useColor, isInteractive)  // this PrintStream uses color only if it is supported (so not on uncolored terminals and not when outputting to a file)
    } catch {
      case e: java.lang.UnsatisfiedLinkError =>  // in case something goes really wrong and no suitable jansi native library is included
        System.err.println(s"Using colorless output as fallback due to $e")
      new Logger(System.out, useColor = false, isInteractive)
    }
  }
}

// TODO Use `Runtime#reportFatal` or `Runtime.setReportFatal` to log fatal errors like stack overflow

class Sc4pac(val repositories: Seq[MetadataRepository], val cache: FileCache, val tempRoot: os.Path, val logger: Logger) extends UpdateService {  // TODO defaults

  given context: ResolutionContext = new ResolutionContext(repositories, cache, logger)

  import CoursierZio.*  // implicit coursier-zio interop

  // TODO check resolution.conflicts

  private def modifyExplicitModules[R](modify: Seq[BareModule] => ZIO[R, Throwable, Seq[BareModule]]): ZIO[R, Throwable, Seq[BareModule]] = {
    for {
      pluginsData  <- JsonIo.read[JD.Plugins](JD.Plugins.path)  // at this point, file should already exist
      modsOrig     =  pluginsData.explicit
      modsNext     <- modify(modsOrig)
      _            <- ZIO.unless(modsNext == modsOrig) {
                        val pluginsDataNext = pluginsData.copy(explicit = modsNext)
                        // we do not check whether file was modified as this entire operation is synchronous and fast, in most cases
                        JsonIo.write(JD.Plugins.path, pluginsDataNext, None)(ZIO.succeed(()))
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
  def removeSelect(): ZIO[Prompt.Interactive, Throwable, Seq[BareModule]] = {
    modifyExplicitModules { modsOrig =>
      if (modsOrig.isEmpty) {
        logger.log("List of explicitly installed packages is already empty.")
        ZIO.succeed(modsOrig)
      } else {
        for {
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
        repo.channelData.contents.iterator.flatMap { item =>
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
    results.sortBy(_._2)(Ordering.Int.reverse).distinctBy(_._1)
  }

  def info(module: BareModule): Task[Option[Seq[(String, String)]]] = {
    val mod = Module(module.group, module.name, attributes = Map.empty)
    for {
      version <- Find.concreteVersion(mod, Constants.versionLatestRelease)
      pkgOpt  <- Find.packageData[JD.Package](mod, version)
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
  ): Task[(Seq[os.SubPath], Boolean)] = {
    def extract(assetData: JD.AssetReference, pkgFolder: os.SubPath): Task[Unit] = ZIO.attemptBlocking {
      // Given an AssetReference, we look up the corresponding artifact file
      // by ID. This relies on the 1-to-1-correspondence between sc4pacAssets
      // and artifact files.
      val id = BareAsset(ModuleName(assetData.assetId))
      artifactsById.get(id) match {
        case None =>
          logger.warn(s"skipping missing artifact, so it must be installed manually: ${id.orgName}")
        case Some(art, archive) =>
          // logger.log(s"  ==> $archive")  // TODO logging debug info
          val recipe = JD.InstallRecipe.fromAssetReference(assetData)
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
    // variant successfully, but have lost the JsonData.Package, so we reconstruct it
    // here a second time.
    for {
      (pkgData, variant) <- Find.matchingVariant(dependency.toBareDep, dependency.version, dependency.variant)
      pkgFolder          =  pkgData.subfolder / packageFolderName(dependency)
      _                  <- logger.withSpinner(Some(s"$progress Extracting ${dependency.orgName} ${dependency.version}"), sameLine = false) {
                              for {
                                _ <- ZIO.attemptBlocking(os.makeDir.all(tempPluginsRoot / pkgFolder))  // create folder even if package does not have any assets or files
                                _ <- ZIO.foreachDiscard(variant.assets)(extract(_, pkgFolder))
                              } yield ()
                            }
      warnings           <- if (pkgData.info.warning.nonEmpty) ZIO.attempt { logger.warn(pkgData.info.warning); true } else ZIO.succeed(false)
    } yield (Seq(pkgFolder), warnings)  // for now, everything is installed into this folder only, so we do not need to list individual files
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

  private def logPackages(msg: String, dependencies: Iterable[DepModule]): Unit = {
    logger.log(msg + dependencies.iterator.map(_.formattedDisplayString(logger.gray)).toSeq.sorted.mkString(f"%n"+" "*4, f"%n"+" "*4, f"%n"))
  }

  /** Update all installed packages from modules (the list of explicitly added packages). */
  def update(modules: Seq[BareModule], globalVariant0: Variant, pluginsRoot: os.Path): Task[Boolean] = {

    def logPlan(plan: Sc4pac.UpdatePlan): UIO[Unit] = ZIO.succeed {
      if (plan.toRemove.nonEmpty) logPackages(f"The following packages will be removed:%n", plan.toRemove.collect{ case d: DepModule => d })
      // if (plan.toReinstall.nonEmpty) logPackages(f"The following packages will be reinstalled:%n", plan.toReinstall.collect{ case d: DepModule => d })
      if (plan.toInstall.nonEmpty) logPackages(f"The following packages will be installed:%n", plan.toInstall.collect{ case d: DepModule => d })
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
    def stageAll(deps: Seq[DepModule], artifactsById: Map[BareAsset, (Artifact, java.io.File)]): Task[StageResult] = {

      val makeTempStagingDir = ZIO.attemptBlocking {
        os.makeDir.all(tempRoot)
        os.temp.dir(tempRoot, prefix = "staging-process", deleteOnExit = true)
      }

      for {
        stagingRoot             <- makeTempStagingDir
        tempPluginsRoot         =  stagingRoot / "plugins"
        _                       <- ZIO.attemptBlocking(os.makeDir(tempPluginsRoot))
        jarsRoot                =  stagingRoot / "jars"
        numDeps                 =  deps.length
        (stagedFiles, warnings) <- ZIO.foreach(deps.zipWithIndex) { case (dep, idx) =>   // sequentially stages each package
                                     stage(tempPluginsRoot, dep, artifactsById, jarsRoot, Sc4pac.Progress(idx+1, numDeps))
                                   }.map(_.unzip)
        _                       <- if (warnings.forall(_ == false)) ZIO.succeed(true)
                                   else Prompt.ifInteractive(
                                     onTrue = Prompt.yesNo("Continue despite warnings?").filterOrFail(_ == true)(Sc4pacAbort()),
                                     onFalse = ZIO.succeed(true))  // in non-interactive mode, we continue despite warnings
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
      }.fold(
        failure = { (failedPkgs: ::[ErrStr]) =>
          new Sc4pacPublishWarning(s"Failed to correctly install the following packages (manual intervention needed): ${failedPkgs.mkString(" ")}")
          // TODO further handling?
        },
        success = identity
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
      val task = JsonIo.write(JD.PluginsLock.path, pluginsLockData.updateTo(plan, staged.files.toMap), Some(pluginsLockData)) {
        for {
          _ <- remove(plan.toRemove, pluginsLockData.installed, pluginsRoot)
                 // .catchAll(???)  // TODO catch exceptions
          _ <- movePackagesToPlugins(staged)
        } yield true  // TODO return result
      }
      val msg = if (plan.toInstall.nonEmpty) "Moving extracted files to plugins folder." else "Removing files from plugins folder."
      logger.withSpinner(Some(msg), sameLine = false)(task)
        .catchSome {
          case e: Sc4pacPublishWarning => logger.warn(e.getMessage); ZIO.succeed(true)  // TODO return result
        }
    }

    def doPromptingForVariant[A](globalVariant: Variant)(task: Variant => Task[A]): Task[(A, Variant)] = {
      ZIO.iterate(Left(globalVariant): Either[Variant, (A, Variant)])(_.isLeft) {
        case Right(_) => throw new AssertionError
        case Left(globalVariant) =>
          def handler(pkgData: JD.Package) = {
            val unknownVariants = pkgData.unknownVariants(globalVariant)
            val mod = BareModule(Organization(pkgData.group), ModuleName(pkgData.name))
            val choose: Task[Seq[(String, String)]] = ZIO.foreach(unknownVariants.toSeq) { (key, values) =>
              val prefix = s"$key = "
              val columnWidth = values.map(_.length).max + 8  // including some whitespace for separation, excluding prefix
              def renderDesc(value: String): String = pkgData.variantDescriptions.get(key).flatMap(_.get(value)) match {
                case None => prefix + value
                case Some(desc) => prefix + value + (" " * ((columnWidth - value.length) max 0)) + desc
              }
              Prompt.ifInteractive(
                onTrue = Prompt.numbered(s"""Choose a variant for ${mod.orgName}:""", values, render = renderDesc).map(v => (key, v)),
                onFalse = ZIO.fail(new Sc4pacNotInteractive(s"""Configure a "$key" variant for ${mod.orgName} in ${JD.Plugins.path.last}: ${values.mkString(", ")}""")))
            }
            choose.map(additionalChoices => Left(globalVariant ++ additionalChoices))
          }
          task(globalVariant)
            .map(x => Right((x, globalVariant)))
            .catchSome { case e: Sc4pacMissingVariant => handler(e.packageData) }
            .catchSomeDefect { case e: Sc4pacMissingVariant => handler(e.packageData) }  // Since Repository works with EitherT[Task, ErrStr, _], Sc4pacMissingVariant is a `defect` rather than a regular `error`
      }.map(_.toOption.get)
    }

    def storeGlobalVariant(globalVariant: Variant): Task[Unit] = for {
      pluginsData <- JsonIo.read[JD.Plugins](JD.Plugins.path)  // json file should exist already
      _           <- JsonIo.write(JD.Plugins.path, pluginsData.copy(config = pluginsData.config.copy(variant = globalVariant)), None)(ZIO.succeed(()))
    } yield ()

    // TODO catch coursier.error.ResolutionError$CantDownloadModule (e.g. when json files have syntax issues)
    for {
      pluginsLockData <- JD.PluginsLock.readOrInit
      (resolution, globalVariant) <- doPromptingForVariant(globalVariant0)(Resolution.resolve(modules, _))
      plan            =  UpdatePlan.fromResolution(resolution, installed = pluginsLockData.dependenciesWithAssets)
      _               <- logPlan(plan)
      flagOpt         <- ZIO.unless(plan.isUpToDate)(for {
        _               <- Prompt.ifInteractive(
                             onTrue = Prompt.yesNo("Continue?").filterOrFail(_ == true)(Sc4pacAbort()),
                             onFalse = ZIO.succeed(true))  // in non-interactive mode, always continue
        _               <- ZIO.unless(globalVariant == globalVariant0)(storeGlobalVariant(globalVariant))  // only store something after confirmation
        assetsToInstall <- resolution.fetchArtifactsOf(resolution.transitiveDependencies.filter(plan.toInstall).reverse)  // we start by fetching artifacts in reverse as those have fewest dependencies of their own
        // TODO if some artifacts fail to be fetched, fall back to installing remaining packages (maybe not(?), as this leads to missing dependencies,
        // but there needs to be a manual workaround in case of permanently missing artifacts)
        depsToStage     =  plan.toInstall.collect{ case d: DepModule => d }.toSeq  // keep only non-assets
        artifactsById   =  assetsToInstall.map((dep, art, file) => dep.toBareDep -> (art, file)).toMap
        _               =  require(artifactsById.size == assetsToInstall.size, s"artifactsById is not 1-to-1: $assetsToInstall")
        stageResult     <- stageAll(depsToStage, artifactsById)
        flag            <- publishToPlugins(stageResult, pluginsLockData, plan)
        _               <- ZIO.attemptBlocking(os.remove.all(stageResult.stagingRoot))  // deleteOnExit does not seem to work reliably, so explicitly delete temp folder  TODO catch and ignore TODO finally
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


  private def fetchChannelData(repoUri: java.net.URI, cache: FileCache, channelContentsTtl: Option[scala.concurrent.duration.Duration]): IO[ErrStr, MetadataRepository] = {
    import CoursierZio.*  // implicit coursier-zio interop
    val contentsUrl = MetadataRepository.channelContentsUrl(repoUri).toString
    val artifact = Artifact(contentsUrl).withChanging(true)  // changing as the remote file is updated whenever any remote package is added or updated
    cache
      .withTtl(channelContentsTtl.orElse(cache.ttl))
      .fetch(artifact)  // requires initialized logger
      .toZioRefine { case e: coursier.error.FetchError.DownloadingArtifacts =>  // TODO don't die on everything else
        ZIO.fail(f"could not access remote channel $repoUri%n$e")
      }  // dies on all throwables; TODO catch which Exceptions?
      .flatMap((jsonStr: String) => ZIO.fromEither {
        JsonIo.readBlocking[JD.Channel](jsonStr, errMsg = contentsUrl)  // TODO does this really give the jsonStr directly instead of a path to a jsonFile?
          .map(channelData => MetadataRepository(repoUri, channelData, globalVariant = Map.empty))
      })
  }

  private[sc4pac] def initializeRepositories(repoUris: Seq[java.net.URI], cache: FileCache, channelContentsTtl: Option[scala.concurrent.duration.Duration]): Task[Seq[MetadataRepository]] = {
    val task: Task[Seq[MetadataRepository]] = ZIO.collectPar(repoUris) { url =>
      fetchChannelData(url, cache, channelContentsTtl)
        .mapError((err: ErrStr) => { System.err.println(s"Failed to read channel data: $err"); None })
    }.filterOrFail(_.nonEmpty)(Sc4pacAbort(s"No channels available: $repoUris"))
    // TODO for long running processes, we might need a way to refresh the channel
    // data occasionally (but for now this is good enough)
    import CoursierZio.*  // implicit coursier-zio interop
    cache.logger.using(task)  // properly initializes logger (avoids Uninitialized TermDisplay)
  }

  def init(config: JD.Config): Task[Sc4pac] = {
    import CoursierZio.*  // implicit coursier-zio interop
    // val refreshLogger = coursier.cache.loggers.RefreshLogger.create(System.err)  // TODO System.err seems to cause less collisions between refreshing progress and ordinary log messages
    val logger = Logger()
    val coursierPool = coursier.cache.internal.ThreadUtil.fixedThreadPool(size = 2)  // limit parallel downloads to 2 (ST rejects too many connections)
    val cache = FileCache(location = config.cacheRoot.resolve("coursier").toFile(), logger = logger, pool = coursierPool)
      // .withCachePolicies(Seq(coursier.cache.CachePolicy.ForceDownload))  // TODO cache policy
      // .withTtl(1.hour)  // TODO time-to-live
    val tempRoot = os.Path(config.tempRoot, os.pwd)
    for (repos <- initializeRepositories(config.channels, cache, channelContentsTtl = None)) yield Sc4pac(repos, cache, tempRoot, logger)
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

}
