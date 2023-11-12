package io.github.memo33
package sc4pac

import coursier.core.{Repository, Module, ModuleName, Organization}
import coursier.core as C
import upickle.default.{ReadWriter, readwriter}
import java.nio.file.{Path as NioPath}
import zio.{ZIO, IO, Task, RIO, URIO}
import java.util.regex.Pattern

import sc4pac.Resolution.{Dep, DepModule, DepAsset}

/** Contains data types for JSON serialization. */
object JsonData extends SharedData {

  override type Instant = java.time.Instant
  override type SubPath = os.SubPath

  implicit val instantRw: ReadWriter[java.time.Instant] =
    readwriter[String].bimap[java.time.Instant](_.toString(), Option(_).map(java.time.Instant.parse).orNull)

  implicit val pathRw: ReadWriter[NioPath] = readwriter[String].bimap[NioPath](_.toString(), java.nio.file.Paths.get(_))

  implicit val subPathRw: ReadWriter[os.SubPath] = readwriter[String].bimap[os.SubPath](_.toString(), os.SubPath(_))

  implicit val uriRw: ReadWriter[java.net.URI] = readwriter[String].bimap[java.net.URI](_.toString(),
    MetadataRepository.parseChannelUrl(_).left.map(new IllegalArgumentException(_)).toTry.get)

  private def bareModuleRead(s: String) =
    Sc4pac.parseModules(Seq(s)) match {
      case Right(Seq(mod)) => mod
      case Left(err) => throw new IllegalArgumentException(err)
      case _ => throw new AssertionError
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
    val pluginsRootAbs: RIO[ScopeRoot, os.Path] = ZIO.service[ScopeRoot].map(scopeRoot => os.Path(pluginsRoot, scopeRoot.path))
    val cacheRootAbs: RIO[ScopeRoot, os.Path] = ZIO.service[ScopeRoot].map(scopeRoot => os.Path(cacheRoot, scopeRoot.path))
    val tempRootAbs: RIO[ScopeRoot, os.Path] = ZIO.service[ScopeRoot].map(scopeRoot => os.Path(tempRoot, scopeRoot.path))
  }
  object Config {
    /** Turns an absolute path into a relative one if it is a subpath of scopeRoot, otherwise returns an absolute path. */
    def subRelativize(path: os.Path, scopeRoot: ScopeRoot): NioPath = {
      try {
        val sub: os.SubPath = path.subRelativeTo(scopeRoot.path)
        sub.toNIO
      } catch {
        case _: IllegalArgumentException => path.toNIO
      }
    }
  }

  case class Plugins(config: Config, explicit: Seq[BareModule]) derives ReadWriter
  object Plugins {
    def path(scopeRoot: os.Path): os.Path = scopeRoot / "sc4pac-plugins.json"

    def pathURIO: URIO[ScopeRoot, os.Path] = ZIO.service[ScopeRoot].map(scopeRoot => Plugins.path(scopeRoot.path))

    /** Prompt for pluginsRoot and cacheRoot. This has a `CliPrompter` constraint as we only want to prompt about this using the CLI. */
    val promptForPaths: RIO[ScopeRoot & CliPrompter, (os.Path, os.Path)] = {
      val projDirs = dev.dirs.ProjectDirectories.from("", cli.BuildInfo.organization, cli.BuildInfo.name)  // qualifier, organization, application
      val task = for {
        scopeRoot    <- ZIO.service[ScopeRoot]
        pluginsRoot  <- Prompt.paths("Choose the location of your Plugins folder. (It is recommended to start with an empty folder.)", Seq(
                          os.home / "Documents" / "SimCity 4" / "Plugins",
                          scopeRoot.path / "plugins"))
        cacheRoot    <- Prompt.paths("Choose a location for the cache folder. (It stores all the downloaded files. " +
                                     "Make sure there is enough disk space available on the corresponding partition. " +
                                     "If you have multiple Plugins folders, use the same cache folder for all of them.)", Seq(
                          os.Path(java.nio.file.Paths.get(projDirs.cacheDir)),
                          scopeRoot.path / "cache"))
      } yield (pluginsRoot, cacheRoot)
      Prompt.ifInteractive(
        onTrue = task,
        onFalse = ZIO.fail(new error.Sc4pacNotInteractive("Path to plugins folder cannot be configured non-interactively (yet).")))  // TODO fallback
    }

    /** Init and write. */
    def init(pluginsRoot: os.Path, cacheRoot: os.Path): RIO[ScopeRoot, Plugins] = {
      for {
        scopeRoot    <- ZIO.service[ScopeRoot]
        tempRoot     <- ZIO.succeed(scopeRoot.path / "temp")  // customization not needed
        data         =  Plugins(
                          config = Config(
                            pluginsRoot = Config.subRelativize(pluginsRoot, scopeRoot),
                            cacheRoot = Config.subRelativize(cacheRoot, scopeRoot),
                            tempRoot = Config.subRelativize(tempRoot, scopeRoot),
                            variant = Map.empty,
                            channels = Constants.defaultChannelUrls),
                          explicit = Seq.empty)
        pluginsPath  <- Plugins.pathURIO
        _            <- JsonIo.write(pluginsPath, data, None)(ZIO.succeed(()))
      } yield data
    }

    // // TODO check existence?
    // val read: RIO[ScopeRoot, Plugins] = Plugins.pathURIO.flatMap { pluginsPath =>
    //   JsonIo.read[Plugins](pluginsPath)
    //   // ZIO.ifZIO(ZIO.attemptBlocking(os.exists(pluginsPath)))(
    //   //   onTrue = JsonIo.read[Plugins](pluginsPath),
    //   //   onFalse = ZIO.fail("Scope not initialized.")
    // }

    /** Read Plugins from file if it exists, else create it and write it to file. */
    val readOrInit: RIO[ScopeRoot & CliPrompter, Plugins] = Plugins.pathURIO.flatMap { pluginsPath =>
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
          lastModified = dep.lastModified.getOrElse(null)
        ))
      )
    }
  }
  object PluginsLock {
    def path(scopeRoot: os.Path): os.Path = scopeRoot / "sc4pac-plugins-lock.json"

    def pathURIO: URIO[ScopeRoot, os.Path] = ZIO.service[ScopeRoot].map(scopeRoot => PluginsLock.path(scopeRoot.path))

    /** Read PluginsLock from file if it exists, else create it and write it to file. */
    val readOrInit: RIO[ScopeRoot, PluginsLock] = PluginsLock.pathURIO.flatMap { pluginsLockPath =>
      ZIO.ifZIO(ZIO.attemptBlocking(os.exists(pluginsLockPath)))(
        onTrue = JsonIo.read[PluginsLock](pluginsLockPath),
        onFalse = {
          val data = PluginsLock(Seq.empty, Seq.empty)
          JsonIo.write(pluginsLockPath, data, None)(ZIO.succeed(data))
        }
      )
    }

    val listInstalled: RIO[ScopeRoot, Seq[DepModule]] = PluginsLock.pathURIO.flatMap { pluginsLockPath =>
      ZIO.ifZIO(ZIO.attemptBlocking(os.exists(pluginsLockPath)))(
        onTrue = JsonIo.read[PluginsLock](pluginsLockPath).map(_.installed.map(_.toDepModule)),
        onFalse = ZIO.succeed(Seq.empty)
      )
    }
  }

  case class InstallRecipe(include: Seq[Pattern], exclude: Seq[Pattern]) {
    def accepts(path: String): Boolean = {
      include.exists(_.matcher(path).find()) && !exclude.exists(_.matcher(path).find())
    }
    def accepts(path: os.SubPath): Boolean = accepts(path.segments.mkString("/", "/", ""))  // paths are checked with leading / and with / as separator
  }
  object InstallRecipe {
    def fromAssetReference(data: AssetReference): InstallRecipe = {
      val mkPattern = Pattern.compile(_, Pattern.CASE_INSENSITIVE)
      def toRegex(s: String): Option[Pattern] = try {
        Some(mkPattern(s))
      } catch {
        case e: java.util.regex.PatternSyntaxException =>
          println(s"include/exclude pattern contains invalid regex: $e")  // TODO logger
          None
      }
      val include = data.include.flatMap(toRegex)
      val exclude = data.exclude.flatMap(toRegex)
      InstallRecipe(
        include = if (include.isEmpty) Seq(mkPattern(Constants.defaultInclude)) else include,
        exclude = if (exclude.isEmpty) Seq(mkPattern(Constants.defaultExclude)) else exclude)
    }
  }

}
