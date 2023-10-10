package io.github.memo33
package sc4pac

import coursier.core.{Repository, Module, ModuleName, Organization}
import coursier.core as C
import upickle.default.{ReadWriter, readwriter}
import java.nio.file.{Path as NioPath}
import zio.{ZIO, IO, Task}
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

  case class ChannelItem(group: String, name: String, versions: Seq[String], summary: String = "") derives ReadWriter {
    def isSc4pacAsset: Boolean = group == Constants.sc4pacAssetOrg.value
    def toBareDep: BareDep = if (isSc4pacAsset) BareAsset(ModuleName(name)) else BareModule(Organization(group), ModuleName(name))
    private[sc4pac] def toSearchString: String = s"$group:$name $summary"
  }

  case class Channel(scheme: Int, contents: Seq[ChannelItem]) derives ReadWriter {
    lazy val versions: Map[BareDep, Seq[String]] =
      contents.map(item => item.toBareDep -> item.versions).toMap
  }
  object Channel {
    def create(scheme: Int, channelData: Iterable[(BareDep, Iterable[(String, PackageAsset)])]): Channel = {  // name -> version -> json
      Channel(scheme, channelData.iterator.collect {
        case (dep, versions) if versions.nonEmpty =>
          val (g, n) = dep match {
            case m: BareModule => (m.group.value, m.name.value)
            case a: BareAsset => (Constants.sc4pacAssetOrg.value, a.assetId.value)
          }
          // we arbitrarily pick the summary of the first item (usually there is just one version anyway)
          val summaryOpt = versions.iterator.collectFirst { case (_, pkg: Package) if pkg.info.summary.nonEmpty => pkg.info.summary }
          ChannelItem(group = g, name = n, versions = versions.iterator.map(_._1).toSeq, summary = summaryOpt.getOrElse(""))
      }.toSeq)
    }
  }

  case class Config(
    pluginsRoot: NioPath,
    cacheRoot: NioPath,
    tempRoot: NioPath,
    variant: Variant,
    channels: Seq[java.net.URI]
  ) derives ReadWriter
  object Config {
    def subRelativize(path: os.Path): NioPath = {
      try {
        val sub: os.SubPath = path.subRelativeTo(os.pwd)
        sub.toNIO
      } catch {
        case _: IllegalArgumentException => path.toNIO
      }
    }
  }

  case class Plugins(config: Config, explicit: Seq[BareModule]) derives ReadWriter {
    val pluginsRootAbs: os.Path = os.Path(config.pluginsRoot, base = os.pwd)  // converts to absolute if not absolute already
  }
  object Plugins {
    val path: os.Path = os.pwd / "sc4pac-plugins.json"

    def init(): Task[Plugins] = {
      val projDirs = dev.dirs.ProjectDirectories.from("", cli.BuildInfo.organization, cli.BuildInfo.name)  // qualifier, organization, application
      val task = for {
        pluginsRoot  <- Prompt.paths("Choose the location of your Plugins folder. (It is recommended to start with an empty folder.)", Seq(
                          os.home / "Documents" / "SimCity 4" / "Plugins",
                          os.pwd / "plugins"))
        cacheRoot    <- Prompt.paths("Choose a location for the cache folder. (It stores all the downloaded files. " +
                                     "Make sure there is enough disk space available on the corresponding partition. " +
                                     "If you have multiple Plugins folders, use the same cache folder for all of them.)", Seq(
                          os.Path(java.nio.file.Paths.get(projDirs.cacheDir)),
                          os.pwd / "cache"))
        tempRoot     <- ZIO.succeed(os.pwd / "temp")  // customization not needed
      } yield Plugins(
        config = Config(
          pluginsRoot = Config.subRelativize(pluginsRoot),
          cacheRoot = Config.subRelativize(cacheRoot),
          tempRoot = Config.subRelativize(tempRoot),
          variant = Map.empty,
          channels = Constants.defaultChannelUrls),
        explicit = Seq.empty)
      Prompt.ifInteractive(
        onTrue = task,
        onFalse = ZIO.fail(new error.Sc4pacNotInteractive("Path to plugins folder cannot be configured non-interactively (yet).")))  // TODO fallback
    }

    /** Read Plugins from file if it exists, else create it and write it to file. */
    val readOrInit: Task[Plugins] = {
      ZIO.ifZIO(ZIO.attemptBlocking(os.exists(Plugins.path)))(
        onTrue = JsonIo.read[Plugins](Plugins.path),
        onFalse = for {
          data <- Plugins.init()
          _    <- JsonIo.write(Plugins.path, data, None)(ZIO.succeed(()))
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
    val path: os.Path = os.pwd / "sc4pac-plugins-lock.json"

    /** Read PluginsLock from file if it exists, else create it and write it to file. */
    val readOrInit: Task[PluginsLock] = {
      ZIO.ifZIO(ZIO.attemptBlocking(os.exists(PluginsLock.path)))(
        onTrue = JsonIo.read[PluginsLock](PluginsLock.path),
        onFalse = {
          val data = PluginsLock(Seq.empty, Seq.empty)
          JsonIo.write(PluginsLock.path, data, None)(ZIO.succeed(data))
        }
      )
    }

    val listInstalled: Task[Seq[DepModule]] = {
      ZIO.ifZIO(ZIO.attemptBlocking(os.exists(PluginsLock.path)))(
        onTrue = JsonIo.read[PluginsLock](PluginsLock.path).map(_.installed.map(_.toDepModule)),
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
