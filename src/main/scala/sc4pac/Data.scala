package io.github.memo33
package sc4pac

import coursier.core.{Repository, Module, ModuleName, Organization}
import coursier.core as C
import upickle.default.{ReadWriter, readwriter, stringKeyRW, macroRW}
import java.nio.file.{Path as NioPath}
import zio.{ZIO, IO, Task, RIO, URIO}
import java.util.regex.Pattern
import scala.collection.mutable.Builder

import sc4pac.Resolution.{Dep, DepModule, DepAsset}

/** Contains data types for JSON serialization. */
object JsonData extends SharedData {

  override type Instant = java.time.Instant
  override type SubPath = os.SubPath

  // We use OffsetDateTime.parse instead of Instant.parse for compatibility with Java 8 to 11
  implicit val instantRw: ReadWriter[java.time.Instant] =
    readwriter[String].bimap[java.time.Instant](_.toString(), Option(_).map(java.time.OffsetDateTime.parse(_).toInstant()).orNull)

  implicit val pathRw: ReadWriter[NioPath] = readwriter[String].bimap[NioPath](_.toString(), java.nio.file.Paths.get(_))

  implicit val subPathRw: ReadWriter[os.SubPath] = readwriter[String].bimap[os.SubPath](_.toString(), os.SubPath(_))

  implicit val uriRw: ReadWriter[java.net.URI] = readwriter[String].bimap[java.net.URI](_.toString(),
    MetadataRepository.parseChannelUrl(_).left.map(new IllegalArgumentException(_)).toTry.get)

  private def bareModuleRead(s: String) =
    Sc4pac.parseModule(s) match {
      case Right(mod) => mod
      case Left(err) => throw new IllegalArgumentException(err)
    }
  implicit val bareModuleRw: ReadWriter[BareModule] = readwriter[String].bimap[BareModule](_.orgName, bareModuleRead)
  implicit val bareDepRw: ReadWriter[BareDep] = readwriter[String].bimap[BareDep](_.orgName, { (s: String) =>
    val prefix = Constants.sc4pacAssetOrg.value + ":"
    if (s.startsWith(prefix)) BareAsset(assetId = C.ModuleName(s.substring(prefix.length))) else bareModuleRead(s)
  })

  case class Config(
    pluginsRoot: NioPath,
    cacheRoot: NioPath,
    tempRoot: NioPath,
    variant: Variant,
    channels: Seq[java.net.URI]
  ) derives ReadWriter {
    val pluginsRootAbs: RIO[ProfileRoot, os.Path] = ZIO.service[ProfileRoot].map(profileRoot => os.Path(pluginsRoot, profileRoot.path))
    val cacheRootAbs: RIO[ProfileRoot, os.Path] = ZIO.service[ProfileRoot].map(profileRoot => os.Path(cacheRoot, profileRoot.path))
    val tempRootAbs: RIO[ProfileRoot, os.Path] = ZIO.service[ProfileRoot].map(profileRoot => os.Path(tempRoot, profileRoot.path))
  }
  object Config {
    /** Turns an absolute path into a relative one if it is a subpath of profileRoot, otherwise returns an absolute path. */
    def subRelativize(path: os.Path, profileRoot: ProfileRoot): NioPath = {
      try {
        val sub: os.SubPath = path.subRelativeTo(profileRoot.path)
        sub.toNIO
      } catch {
        case _: IllegalArgumentException => path.toNIO
      }
    }
  }

  case class Plugins(config: Config, explicit: Seq[BareModule]) derives ReadWriter
  object Plugins {
    def path(profileRoot: os.Path): os.Path = profileRoot / "sc4pac-plugins.json"

    def pathURIO: URIO[ProfileRoot, os.Path] = ZIO.service[ProfileRoot].map(profileRoot => Plugins.path(profileRoot.path))

    private val projDirs = dev.dirs.ProjectDirectories.from("", cli.BuildInfo.organization, cli.BuildInfo.name)  // qualifier, organization, application

    val defaultPluginsRoot: URIO[ProfileRoot, Seq[os.Path]] = ZIO.serviceWith[ProfileRoot](profileRoot => Seq(
      os.home / "Documents" / "SimCity 4" / "Plugins",
      profileRoot.path / "plugins"
    ))

    val defaultCacheRoot: URIO[ProfileRoot, Seq[os.Path]] = ZIO.serviceWith[ProfileRoot](profileRoot => Seq(
      os.Path(java.nio.file.Paths.get(projDirs.cacheDir)),
      profileRoot.path / "cache"
    ))

    /** Prompt for pluginsRoot and cacheRoot. This has a `CliPrompter` constraint as we only want to prompt about this using the CLI. */
    val promptForPaths: RIO[ProfileRoot & CliPrompter, (os.Path, os.Path)] = {
      val task = for {
        defaultPlugins <- defaultPluginsRoot
        pluginsRoot    <- Prompt.paths("Choose the location of your Plugins folder. (It is recommended to start with an empty folder.)", defaultPlugins)
        defaultCache   <- defaultCacheRoot
        cacheRoot      <- Prompt.paths("Choose a location for the cache folder. (It stores all the downloaded files. " +
                                       "Make sure there is enough disk space available on the corresponding partition. " +
                                       "If you have multiple Plugins folders, use the same cache folder for all of them.)", defaultCache)
      } yield (pluginsRoot, cacheRoot)
      Prompt.ifInteractive(
        onTrue = task,
        onFalse = ZIO.fail(new error.Sc4pacNotInteractive("Path to plugins folder cannot be configured non-interactively (yet).")))  // TODO fallback
    }

    /** Init and write. */
    def init(pluginsRoot: os.Path, cacheRoot: os.Path): RIO[ProfileRoot, Plugins] = {
      for {
        profileRoot  <- ZIO.service[ProfileRoot]
        tempRoot     <- ZIO.succeed(profileRoot.path / "temp")  // customization not needed
        data         =  Plugins(
                          config = Config(
                            pluginsRoot = Config.subRelativize(pluginsRoot, profileRoot),
                            cacheRoot = Config.subRelativize(cacheRoot, profileRoot),
                            tempRoot = Config.subRelativize(tempRoot, profileRoot),
                            variant = Map.empty,
                            channels = Constants.defaultChannelUrls),
                          explicit = Seq.empty)
        pluginsPath  <- Plugins.pathURIO
        _            <- JsonIo.write(pluginsPath, data, None)(ZIO.succeed(()))
      } yield data
    }

    val read: ZIO[ProfileRoot, ErrStr, Plugins] = Plugins.pathURIO.flatMap { pluginsPath =>
      val task: IO[ErrStr | java.io.IOException, Plugins] =
        ZIO.ifZIO(ZIO.attemptBlockingIO(os.exists(pluginsPath)))(
          onFalse = ZIO.fail(s"Configuration file does not exist: $pluginsPath"),
          onTrue = ZIO.attemptBlockingIO(JsonIo.readBlocking[Plugins](pluginsPath)).absolve
        )
      task.mapError(_.toString)
    }

    /** Read Plugins from file if it exists, else create it and write it to file. */
    val readOrInit: RIO[ProfileRoot & CliPrompter, Plugins] = Plugins.pathURIO.flatMap { pluginsPath =>
      ZIO.ifZIO(ZIO.attemptBlocking(os.exists(pluginsPath)))(
        onTrue = JsonIo.read[Plugins](pluginsPath),
        onFalse = for {
          (pluginsRoot, cacheRoot) <- promptForPaths
          data                     <- Plugins.init(pluginsRoot, cacheRoot)
        } yield data
      )
    }
  }

  case class InstalledData(group: String, name: String, variant: Variant, version: String, files: Seq[os.SubPath]) derives ReadWriter {
    def moduleWithoutAttributes = Module(Organization(group), ModuleName(name), attributes=Map.empty)
    def moduleWithAttributes = Module(Organization(group), ModuleName(name), attributes=VariantData.variantToAttributes(variant))
    // def toDependency = DepVariant.fromDependency(C.Dependency(moduleWithAttributes, version))  // TODO remove?
    def toDepModule = DepModule(Organization(group), ModuleName(name), version = version, variant = variant)
  }

  case class PluginsLock(installed: Seq[InstalledData], assets: Seq[Asset]) derives ReadWriter {
    def dependenciesWithAssets: Set[Resolution.Dep] =
      (installed.map(_.toDepModule) ++ assets.map(DepAsset.fromAsset(_))).toSet

    def updateTo(plan: Sc4pac.UpdatePlan, filesStaged: Map[DepModule, Seq[os.SubPath]]): PluginsLock = {
      val orig = dependenciesWithAssets
      val next = plan.toInstall | (orig &~ plan.toRemove)
      val previousPkgs: Map[DepModule, InstalledData] = installed.map(i => (i.toDepModule, i)).toMap
      val (arts, insts) = next.toSeq.partitionMap {
        case a: DepAsset => Left(a)
        case m: DepModule => Right(m)
      }
      PluginsLock(
        installed = insts.map(dep => InstalledData(
          group = dep.group.value,
          name = dep.name.value,
          variant = dep.variant,
          version = dep.version,
          files = filesStaged.get(dep).getOrElse(previousPkgs(dep).files)
        )),
        assets = arts.map(dep => Asset(
          assetId = dep.assetId.value,
          version = dep.version,
          url = dep.url,
          lastModified = dep.lastModified.getOrElse(null),
          archiveType = None  // not needed in lock file, as only version info is needed to determine if previously installed files are outdated
        ))
      )
    }
  }
  object PluginsLock {
    def path(profileRoot: os.Path): os.Path = profileRoot / "sc4pac-plugins-lock.json"

    def pathURIO: URIO[ProfileRoot, os.Path] = ZIO.service[ProfileRoot].map(profileRoot => PluginsLock.path(profileRoot.path))

    /** Read PluginsLock from file if it exists, else create it and write it to file. */
    val readOrInit: RIO[ProfileRoot, PluginsLock] = PluginsLock.pathURIO.flatMap { pluginsLockPath =>
      ZIO.ifZIO(ZIO.attemptBlocking(os.exists(pluginsLockPath)))(
        onTrue = JsonIo.read[PluginsLock](pluginsLockPath),
        onFalse = {
          val data = PluginsLock(Seq.empty, Seq.empty)
          JsonIo.write(pluginsLockPath, data, None)(ZIO.succeed(data))
        }
      )
    }

    val listInstalled: RIO[ProfileRoot, Seq[DepModule]] = PluginsLock.pathURIO.flatMap { pluginsLockPath =>
      ZIO.ifZIO(ZIO.attemptBlocking(os.exists(pluginsLockPath)))(
        onTrue = JsonIo.read[PluginsLock](pluginsLockPath).map(_.installed.map(_.toDepModule)),
        onFalse = ZIO.succeed(Seq.empty)
      )
    }
  }

  case class InstallRecipe(include: Seq[Pattern], exclude: Seq[Pattern]) {
    def makeAcceptancePredicate(): (Builder[Pattern, Set[Pattern]], os.SubPath => Boolean) = {
      val usedPatternsBuilder = Set.newBuilder[Pattern] += InstallRecipe.defaultExcludePattern  // default exclude pattern is not required to match anything

      val accepts: os.SubPath => Boolean = { path =>
        val pathString = path.segments.mkString("/", "/", "")  // paths are checked with leading / and with / as separator
        include.find(_.matcher(pathString).find()) match {
          case None => false
          case Some(matchedPattern) =>
            usedPatternsBuilder += matchedPattern
            exclude.find(_.matcher(pathString).find()) match {
              case None => true
              case Some(matchedPattern) =>
                usedPatternsBuilder += matchedPattern
                false
            }
        }
      }

      (usedPatternsBuilder, accepts)
    }

    def usedPatternWarnings(usedPatterns: Set[Pattern]): Seq[Warning] = {
      val unused: Seq[Pattern] = (include.iterator ++ exclude).filter(p => !usedPatterns.contains(p)).toSeq
      if (unused.isEmpty) {
        Seq.empty
      } else {
        Seq(
          "The package metadata seems to be out-of-date, so the package may not have been fully installed. " +
          "Please report this to the maintainers of the package metadata. " +
          "These inclusion/exclusion patterns did not match any files in the asset: " + unused.mkString(" "))
      }
    }
  }
  object InstallRecipe {
    private val mkPattern = Pattern.compile(_, Pattern.CASE_INSENSITIVE)
    private val defaultIncludePattern = mkPattern(Constants.defaultInclude)
    private val defaultExcludePattern = mkPattern(Constants.defaultExclude)

    def fromAssetReference(data: AssetReference): (InstallRecipe, Seq[Warning]) = {
      val warnings = Seq.newBuilder[Warning]
      def toRegex(s: String): Option[Pattern] = try {
        Some(mkPattern(s))
      } catch {
        case e: java.util.regex.PatternSyntaxException =>
          warnings += s"The package metadata contains a malformed regex: $e"
          None
      }
      val include = data.include.flatMap(toRegex)
      val exclude = data.exclude.flatMap(toRegex)
      (InstallRecipe(
        include = if (include.isEmpty) Seq(defaultIncludePattern) else include,
        exclude = if (exclude.isEmpty) Seq(defaultExcludePattern) else exclude), warnings.result())
    }
  }

  case class CheckFile(filename: Option[String]) derives ReadWriter

}
