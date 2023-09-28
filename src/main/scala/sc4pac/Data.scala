package io.github.memo33
package sc4pac

import coursier.core.{Repository, Module, ModuleName, Organization}
import coursier.core as C
import upickle.default.{ReadWriter, readwriter}
import java.nio.file.{Path as NioPath}
import zio.{ZIO, IO, Task}
import java.util.regex.Pattern

import sc4pac.Resolution.{Dep, DepModule, DepAsset, BareModule, BareDep, BareAsset}

/** Contains data types for JSON serialization. */
object JsonData {

  implicit val instantRw: ReadWriter[java.time.Instant] =
    readwriter[String].bimap[java.time.Instant](_.toString(), Option(_).map(java.time.Instant.parse).orNull)

  implicit val pathRw: ReadWriter[NioPath] = readwriter[String].bimap[NioPath](_.toString(), java.nio.file.Paths.get(_))

  implicit val osSubPathRw: ReadWriter[os.SubPath] = readwriter[String].bimap[os.SubPath](_.toString(), os.SubPath(_))

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

  case class Dependency(group: String, name: String, version: String) derives ReadWriter {
    private[JsonData] def toDependency = C.Dependency(Module(Organization(group), ModuleName(name), attributes = Map.empty), version = version)
  }

  case class AssetReference(
    assetId: String,
    include: Seq[String] = Seq.empty,
    exclude: Seq[String] = Seq.empty
  ) derives ReadWriter {
    private[JsonData] def toDependency = C.Dependency(Module(Constants.sc4pacAssetOrg, ModuleName(assetId), attributes = Map.empty), version = Constants.versionLatestRelease)
  }

  /** Package or Asset */
  sealed trait PackageAsset derives ReadWriter {
    def toBareDep: BareDep
    def version: String
  }

  @upickle.implicits.key("Asset")
  case class Asset(
    assetId: String,
    version: String,
    url: String,
    lastModified: java.time.Instant = null
  ) extends PackageAsset derives ReadWriter {
    def attributes: Map[String, String] = {
      val m = Map(Constants.urlKey -> url)
      if (lastModified != null) {
        m + (Constants.lastModifiedKey -> lastModified.toString)
      } else {
        m
      }
    }

    def toBareDep: BareAsset = BareAsset(assetId = ModuleName(assetId))

    // private[JsonData] def toDependency = C.Dependency(Module(Constants.sc4pacAssetOrg, ModuleName(assetId), attributes = attributes), version = version)
    def toDepAsset = DepAsset(assetId = ModuleName(assetId), version = version, url = url, lastModified = Option(lastModified))

    /** Create a `C.Project` (contains metadata) for this asset from scratch on the fly. */
    def toProject: C.Project = {
      C.Project(
        module = Module(
          Constants.sc4pacAssetOrg,
          ModuleName(assetId),
          attributes = attributes),  // includes url and lastModified
        version = version,
        dependencies = Seq.empty,
        configurations = Map.empty,  // TODO
          // Map(
          //   C.Configuration.compile -> Seq.empty,
          //   Constants.link -> Seq(C.Configuration.compile)),
        parent = None,
        dependencyManagement = Seq.empty,  // ? TODO
        properties = Seq.empty,  // TODO
        profiles = Seq.empty,
        versions = None,  // TODO
        snapshotVersioning = None,  // TODO
        packagingOpt = None,  // Option[C.Type],
        relocated = false,
        actualVersionOpt = None,
        publications = Seq.empty,  // Seq[(C.Configuration, C.Publication)],
        info = C.Info.empty)
    }
  }
  object Asset {
    def parseLastModified(lastModified: String): Option[java.time.Instant] = {
      Option(lastModified).map(java.time.Instant.parse)  // throws java.time.format.DateTimeParseException
    }
  }

  case class VariantData(
    variant: Variant,
    dependencies: Seq[Dependency] = Seq.empty,
    assets: Seq[AssetReference] = Seq.empty
  ) derives ReadWriter {
    def bareDependencies: Seq[Resolution.BareDep] =
      dependencies.map(d => Resolution.BareModule(Organization(d.group), ModuleName(d.name)))
        ++ assets.map(a => Resolution.BareAsset(ModuleName(a.assetId)))
  }
  object VariantData {
    def variantToAttributes(variant: Variant): Map[String, String] = {
      require(variant.keysIterator.forall(k => !k.startsWith(Constants.variantPrefix)))
      variant.map((k, v) => (s"${Constants.variantPrefix}$k", v))
    }

    def variantFromAttributes(attributes: Map[String, String]): Variant = attributes.collect {
      case (k, v) if k.startsWith(Constants.variantPrefix) => (k.substring(Constants.variantPrefix.length), v)
    }

    def variantString(variant: Variant): String = variant.toSeq.sorted.map((k, v) => s"$k=$v").mkString(", ")
  }

  @upickle.implicits.key("Package")
  case class Package(
    group: String,
    name: String,
    version: String,
    subfolder: os.SubPath,
    info: Info = Info.empty,
    variants: Seq[VariantData],
    variantDescriptions: Map[String, Map[String, String]] = Map.empty  // variantKey -> variantValue -> description
  ) extends PackageAsset derives ReadWriter {

    def toBareDep: BareModule = BareModule(Organization(group), ModuleName(name))

    /** Create a `C.Project` from the package metadata, usually read from json file. */
    def toProject(globalVariant: Variant): Either[ErrStr, C.Project] = {
      variants.find(data => isSubMap(data.variant, globalVariant)) match {
        case None =>
          Left(s"no variant found for $group:$name matching [${VariantData.variantString(globalVariant)}]")
        case Some(matchingVariant) =>
          Right(C.Project(
            module = Module(
              Organization(group),
              ModuleName(name),
              attributes = VariantData.variantToAttributes(matchingVariant.variant)),  // TODO add variants to attributes or properties?
            version = version,
            dependencies = matchingVariant.dependencies.map(dep => (C.Configuration.compile, dep.toDependency))
              ++ matchingVariant.assets.map(a => (Constants.link, a.toDependency /*.withConfiguration(Constants.link)*/)),
            configurations = Map(
              C.Configuration.compile -> Seq.empty,
              Constants.link -> Seq(C.Configuration.compile)),
            parent = None,
            dependencyManagement = Seq.empty,  // ? TODO
            properties = Seq.empty,  // TODO
            profiles = Seq.empty,
            versions = None,  // TODO
            snapshotVersioning = None,  // TODO
            packagingOpt = None,  // Option[C.Type],
            relocated = false,
            actualVersionOpt = None,
            publications = Seq.empty,  // Seq[(C.Configuration, C.Publication)],
            info = C.Info.empty))
      }
    }

    def unknownVariants(globalVariant: Variant): Map[String, Seq[String]] = {
      val unknownKeys: Set[String] = Set.concat(variants.map(_.variant.keySet) *) &~ globalVariant.keySet
      unknownKeys.iterator.map(k => (k, variants.flatMap(vd => vd.variant.get(k)).distinct)).toMap
    }
  }

  case class Info(
    summary: String = "",
    warning: String = "",
    description: String = "",
    images: Seq[String] = Seq.empty,
    website: String = ""
  ) derives ReadWriter
  object Info {
    val empty = Info()
  }

  case class ChannelItem(group: String, name: String, versions: Seq[String], summary: String = "") derives ReadWriter {
    def isSc4pacAsset: Boolean = group == Constants.sc4pacAssetOrg.value
    def toBareDep: BareDep = if (isSc4pacAsset) BareAsset(ModuleName(name)) else BareModule(Organization(group), ModuleName(name))
    private[sc4pac] def toSearchString: String = s"$group:$name $summary"
  }

  case class Channel(contents: Seq[ChannelItem]) derives ReadWriter {
    lazy val versions: Map[BareDep, Seq[String]] =
      contents.map(item => item.toBareDep -> item.versions).toMap
  }
  object Channel {
    def create(channelData: Iterable[(BareDep, Iterable[(String, PackageAsset)])]): Channel = {  // name -> version -> json
      Channel(channelData.iterator.collect {
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
      (installed.map(_.toDepModule) ++ assets.map(_.toDepAsset)).toSet

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
