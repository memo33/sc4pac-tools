package io.github.memo33
package sc4pac

import coursier.core.{Repository, Module, Publication, ArtifactSource, Versions, Version,
  Dependency, Project, Classifier, Extension, Type, Configuration, ModuleName, Organization, Info}
import upickle.default.{read, macroRW, ReadWriter, Reader, writeTo, writeToByteArray, readwriter}
import java.nio.file.{Path as NioPath}
import zio.{ZIO, IO, Task}
import java.util.regex.Pattern

import sc4pac.error.Sc4pacIoException
import sc4pac.Resolution.{Dep, DepModule, DepAsset, BareModule}

/** Contains data types for JSON serialization. */
object Data {

  implicit val instantRw: ReadWriter[java.time.Instant] =
    readwriter[String].bimap[java.time.Instant](_.toString(), Option(_).map(java.time.Instant.parse).orNull)

  implicit val pathRw: ReadWriter[NioPath] = readwriter[String].bimap[NioPath](_.toString(), java.nio.file.Paths.get(_))

  implicit val osSubPathRw: ReadWriter[os.SubPath] = readwriter[String].bimap[os.SubPath](_.toString(), os.SubPath(_))

  implicit val uriRw: ReadWriter[java.net.URI] = readwriter[String].bimap[java.net.URI](_.toString(), MetadataRepository.parseChannelUrl(_))

  implicit val bareModuleRw: ReadWriter[BareModule] = readwriter[String].bimap[BareModule](_.orgName, { (s: String) =>
    Sc4pac.parseModules(Seq(s)) match {
      case Right(Seq(mod)) => mod
      case Left(err) => throw new IllegalArgumentException(err)
      case _ => throw new AssertionError
    }
  })

  case class DependencyData(group: String, name: String, version: String) derives ReadWriter {
    private[Data] def toDependency = Dependency(Module(Organization(group), ModuleName(name), attributes = Map.empty), version = version)
  }

  case class AssetReference(
    assetId: String,
    include: Seq[String] = Seq.empty,
    exclude: Seq[String] = Seq.empty
  ) derives ReadWriter {
    private[Data] def toDependency = Dependency(Module(Constants.sc4pacAssetOrg, ModuleName(assetId), attributes = Map.empty), version = Constants.versionLatestRelease)
  }

  case class AssetData(
    assetId: String,
    version: String,
    url: String,
    lastModified: java.time.Instant = null
  ) derives ReadWriter {
    def attributes: Map[String, String] = {
      val m = Map(Constants.urlKey -> url)
      if (lastModified != null) {
        m + (Constants.lastModifiedKey -> lastModified.toString)
      } else {
        m
      }
    }

    // private[Data] def toDependency = Dependency(Module(Constants.sc4pacAssetOrg, ModuleName(assetId), attributes = attributes), version = version)
    def toDepAsset = DepAsset(assetId = ModuleName(assetId), version = version, url = url, lastModified = Option(lastModified))

    /** Create a `Project` (contains metadata) for this asset from scratch on the fly. */
    def toProject: Project = {
      Project(
        module = Module(
          Constants.sc4pacAssetOrg,
          ModuleName(assetId),
          attributes = attributes),  // includes url and lastModified
        version = version,
        dependencies = Seq.empty,
        configurations = Map.empty,  // TODO
          // Map(
          //   Configuration.compile -> Seq.empty,
          //   Constants.link -> Seq(Configuration.compile)),
        parent = None,
        dependencyManagement = Seq.empty,  // ? TODO
        properties = Seq.empty,  // TODO
        profiles = Seq.empty,
        versions = None,  // TODO
        snapshotVersioning = None,  // TODO
        packagingOpt = None,  // Option[Type],
        relocated = false,
        actualVersionOpt = None,
        publications = Seq.empty,  // Seq[(Configuration, Publication)],
        info = Info.empty)
    }
  }
  object AssetData {
    def parseLastModified(lastModified: String): Option[java.time.Instant] = {
      Option(lastModified).map(java.time.Instant.parse)  // throws java.time.format.DateTimeParseException
    }
  }

  case class VariantData(
    variant: Variant,
    dependencies: Seq[DependencyData] = Seq.empty,
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

  case class PackageData(
    group: String,
    name: String,
    version: String,
    subfolder: os.SubPath,
    info: InfoData = InfoData.empty,
    variants: Seq[VariantData],
    variantDescriptions: Map[String, Map[String, String]] = Map.empty  // variantKey -> variantValue -> description
  ) derives ReadWriter {
    /** Create a `Project` from the package metadata, usually read from json file. */
    def toProject(globalVariant: Variant): Either[ErrStr, Project] = {
      variants.find(data => isSubMap(data.variant, globalVariant)) match {
        case None =>
          Left(s"no variant found for $group:$name matching [${VariantData.variantString(globalVariant)}]")
        case Some(matchingVariant) =>
          Right(Project(
            module = Module(
              Organization(group),
              ModuleName(name),
              attributes = VariantData.variantToAttributes(matchingVariant.variant)),  // TODO add variants to attributes or properties?
            version = version,
            dependencies = matchingVariant.dependencies.map(dep => (Configuration.compile, dep.toDependency))
              ++ matchingVariant.assets.map(a => (Constants.link, a.toDependency /*.withConfiguration(Constants.link)*/)),
            configurations = Map(
              Configuration.compile -> Seq.empty,
              Constants.link -> Seq(Configuration.compile)),
            parent = None,
            dependencyManagement = Seq.empty,  // ? TODO
            properties = Seq.empty,  // TODO
            profiles = Seq.empty,
            versions = None,  // TODO
            snapshotVersioning = None,  // TODO
            packagingOpt = None,  // Option[Type],
            relocated = false,
            actualVersionOpt = None,
            publications = Seq.empty,  // Seq[(Configuration, Publication)],
            info = Info.empty))
      }
    }

    def unknownVariants(globalVariant: Variant): Map[String, Seq[String]] = {
      val unknownKeys: Set[String] = Set.concat(variants.map(_.variant.keySet) *) &~ globalVariant.keySet
      unknownKeys.iterator.map(k => (k, variants.flatMap(vd => vd.variant.get(k)).distinct)).toMap
    }
  }

  case class InfoData(
    summary: String = "",
    warning: String = "",
    description: String = "",
    images: Seq[String] = Seq.empty,
    website: String = ""
  ) derives ReadWriter
  object InfoData {
    val empty = InfoData()
  }

  case class ChannelItemData(group: String, name: String, versions: Seq[String], summary: String = "") derives ReadWriter {
    def isSc4pacAsset: Boolean = group == Constants.sc4pacAssetOrg.value
    private[sc4pac] def toSearchString: String = s"$group:$name $summary"
  }

  case class ChannelData(contents: Seq[ChannelItemData]) derives ReadWriter {
    lazy val versions: Map[Module, Seq[String]] =
      contents.map(item => Module(Organization(item.group), ModuleName(item.name), attributes = Map.empty) -> item.versions).toMap
  }

  case class ConfigData(
    pluginsRoot: NioPath,
    cacheRoot: NioPath,
    tempRoot: NioPath,
    variant: Variant,
    channels: Seq[java.net.URI]
  ) derives ReadWriter
  object ConfigData {
    def subRelativize(path: os.Path): NioPath = {
      try {
        val sub: os.SubPath = path.subRelativeTo(os.pwd)
        sub.toNIO
      } catch {
        case _: IllegalArgumentException => path.toNIO
      }
    }
  }

  case class PluginsData(config: ConfigData, explicit: Seq[BareModule]) derives ReadWriter {
    val pluginsRootAbs: os.Path = os.Path(config.pluginsRoot, base = os.pwd)  // converts to absolute if not absolute already
  }
  object PluginsData {
    val path: os.Path = os.pwd / "sc4pac-plugins.json"

    def init(): Task[PluginsData] = {
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
      } yield PluginsData(
        config = ConfigData(
          pluginsRoot = ConfigData.subRelativize(pluginsRoot),
          cacheRoot = ConfigData.subRelativize(cacheRoot),
          tempRoot = ConfigData.subRelativize(tempRoot),
          variant = Map.empty,
          channels = Constants.defaultChannelUrls),
        explicit = Seq.empty)
      Prompt.ifInteractive(
        onTrue = task,
        onFalse = ZIO.fail(new error.Sc4pacNotInteractive("Path to plugins folder cannot be configured non-interactively (yet).")))  // TODO fallback
    }

    /** Read PluginsData from file if it exists, else create it and write it to file. */
    val readOrInit: Task[PluginsData] = {
      ZIO.ifZIO(ZIO.attemptBlocking(os.exists(PluginsData.path)))(
        onTrue = Data.readJsonIo[PluginsData](PluginsData.path),
        onFalse = for {
          data <- PluginsData.init()
          _    <- Data.writeJsonIo(PluginsData.path, data, None)(ZIO.succeed(()))
        } yield data
      )
    }
  }

  case class InstalledData(group: String, name: String, variant: Variant, version: String, files: Seq[os.SubPath]) derives ReadWriter {
    def moduleWithoutAttributes = Module(Organization(group), ModuleName(name), attributes=Map.empty)
    def moduleWithAttributes = Module(Organization(group), ModuleName(name), attributes=VariantData.variantToAttributes(variant))
    // def toDependency = DepVariant.fromDependency(Dependency(moduleWithAttributes, version))  // TODO remove?
    def toDepModule = DepModule(Organization(group), ModuleName(name), version = version, variant = variant)
  }

  case class PluginsLockData(installed: Seq[InstalledData], assets: Seq[AssetData]) derives ReadWriter {
    def dependenciesWithAssets: Set[Resolution.Dep] =
      (installed.map(_.toDepModule) ++ assets.map(_.toDepAsset)).toSet

    def updateTo(plan: Sc4pac.UpdatePlan, filesStaged: Map[DepModule, Seq[os.SubPath]]): PluginsLockData = {
      val orig = dependenciesWithAssets
      val next = plan.toInstall | (orig &~ plan.toRemove)
      val previousPkgs: Map[DepModule, InstalledData] = installed.map(i => (i.toDepModule, i)).toMap
      val (arts, insts) = next.toSeq.partitionMap {
        case a: DepAsset => Left(a)
        case m: DepModule => Right(m)
      }
      PluginsLockData(
        installed = insts.map(dep => InstalledData(
          group = dep.group.value,
          name = dep.name.value,
          variant = dep.variant,
          version = dep.version,
          files = filesStaged.get(dep).getOrElse(previousPkgs(dep).files)
        )),
        assets = arts.map(dep => AssetData(
          assetId = dep.assetId.value,
          version = dep.version,
          url = dep.url,
          lastModified = dep.lastModified.getOrElse(null)
        ))
      )
    }
  }
  object PluginsLockData {
    val path: os.Path = os.pwd / "sc4pac-plugins-lock.json"

    /** Read PluginsLockData from file if it exists, else create it and write it to file. */
    val readOrInit: Task[PluginsLockData] = {
      ZIO.ifZIO(ZIO.attemptBlocking(os.exists(PluginsLockData.path)))(
        onTrue = Data.readJsonIo[PluginsLockData](PluginsLockData.path),
        onFalse = {
          val data = PluginsLockData(Seq.empty, Seq.empty)
          Data.writeJsonIo(PluginsLockData.path, data, None)(ZIO.succeed(data))
        }
      )
    }

    val listInstalled: Task[Seq[DepModule]] = {
      ZIO.ifZIO(ZIO.attemptBlocking(os.exists(PluginsLockData.path)))(
        onTrue = Data.readJsonIo[PluginsLockData](PluginsLockData.path).map(_.installed.map(_.toDepModule)),
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



  private[sc4pac] def readJson[A : Reader](jsonPath: os.Path): Either[ErrStr, A] = {
    readJson(os.read.stream(jsonPath), errMsg = jsonPath.toString())
  }

  private[sc4pac] def readJson[A : Reader](jsonStr: String): Either[ErrStr, A] = {
    readJson(jsonStr, errMsg = jsonStr)
  }

  private[sc4pac] def readJson[A : Reader](pathOrString: ujson.Readable, errMsg: => ErrStr): Either[ErrStr, A] = try {
    Right(read[A](pathOrString))
  } catch {
    case e @ (_: upickle.core.AbortException
            | _: ujson.ParseException
            | _: java.nio.file.NoSuchFileException
            | _: IllegalArgumentException
            | _: java.time.format.DateTimeParseException) =>
      Left(s"failed to read $errMsg: ${e.getMessage()}")
  }

  private[sc4pac] def readJsonIo[A : Reader](jsonPath: os.Path): zio.Task[A] = {
    ZIO.attemptBlocking(os.read.stream(jsonPath))
      .flatMap(readJsonIo(_, errMsg = jsonPath.toString()))
  }

  private[sc4pac] def readJsonIo[A : Reader](jsonStr: String): zio.Task[A] = {
    readJsonIo(jsonStr, errMsg = jsonStr)
  }

  private[sc4pac] def readJsonIo[A : Reader](pathOrString: ujson.Readable, errMsg: => ErrStr): zio.Task[A] = {
    ZIO.attemptBlocking(readJson[A](pathOrString, errMsg).left.map(new Sc4pacIoException(_))).absolve
  }

  // steps:
  // - lock file for writing
  // - optionally read json and compare with previous json `origState` to ensure file is up-to-date
  // - do some potentially destructive action
  // - if action successful, write json file

  private val utf8 = java.nio.charset.Charset.forName("UTF-8")

  def writeJsonIo[S : ReadWriter, A](jsonPath: os.Path, newState: S, origState: Option[S])(action: zio.Task[A]): zio.Task[A] = {
    import java.nio.file.StandardOpenOption
    import zio.nio.channels.AsynchronousFileChannel

    def write(channel: AsynchronousFileChannel): ZIO[zio.Scope, Throwable, Unit] = {
      for {
        _      <- channel.truncate(size = 0)
        arr    =  writeToByteArray[S](newState, indent = 2)
        unit   <- channel.writeChunk(zio.Chunk.fromArray(arr), position = 0)
      } yield unit
    }

    def read(channel: AsynchronousFileChannel): ZIO[zio.Scope, Throwable, S] = {
      for {
        chunk <- channel.stream(position = 0).runCollect
        state <- readJsonIo[S](chunk.asString(utf8): String)
      } yield state
    }

    // the file channel used for locking
    val scopedChannel: ZIO[zio.Scope, java.io.IOException, AsynchronousFileChannel] = AsynchronousFileChannel.open(
      zio.nio.file.Path.fromJava(jsonPath.toNIO),
      StandardOpenOption.READ,
      StandardOpenOption.WRITE,
      StandardOpenOption.CREATE)  // TODO decide what to do if file does not exist

    val releaseLock = (lock: zio.nio.channels.FileLock) => if (lock != null) lock.release.ignore else ZIO.succeed(())

    // Acquire file lock, check if file was locked, otherwise perform read-write:
    // First read, then write only if the read result is still the original state.
    // Be careful to just read and write from the channel, as the channel is holding the lock.
    ZIO.scoped {
      for {
        channel <- scopedChannel
        lock    <- ZIO.acquireRelease(channel.tryLock(shared = false))(releaseLock)
                      .filterOrFail(lock => lock != null)(new Sc4pacIoException(s"json file $jsonPath is locked by another program and cannot be modified; if the problem persists, close any relevant program and release the file lock in your OS"))
        _       <- if (origState.isEmpty) ZIO.succeed(())
                   else read(channel).filterOrFail(_ == origState.get)(new Sc4pacIoException(s"cannot write data since json file has been modified: $jsonPath"))
        result  <- action
        _       <- write(channel)
      } yield result
    }  // Finally the lock is released and channel is closed when leaving the scope.
  }
}
