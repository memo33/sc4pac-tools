package io.github.memo33
package sc4pac

import coursier.core.{ModuleName, Organization}
import coursier.core as C
import upickle.default.{ReadWriter, readwriter, stringKeyRW}
import java.nio.file.{Path as NioPath}
import zio.{ZIO, IO, RIO, URIO, Task}
import scala.collection.immutable.ArraySeq
import java.time.temporal.ChronoUnit

import sc4pac.Resolution.{Dep, DepModule, DepAsset}

/** Contains data types for JSON serialization. */
object JsonData extends SharedData {

  override type Instant = java.time.Instant
  override type SubPath = os.SubPath
  override type Uri = java.net.URI

  // We use OffsetDateTime.parse instead of Instant.parse for compatibility with Java 8 to 11
  implicit val instantRw: ReadWriter[java.time.Instant] =
    readwriter[String].bimap[java.time.Instant](_.toString(), Option(_).map(s => java.time.OffsetDateTime.parse(s.trim()).toInstant()).orNull)

  implicit val pathRw: ReadWriter[NioPath] = readwriter[String].bimap[NioPath](_.toString(), java.nio.file.Paths.get(_))

  implicit val subPathRw: ReadWriter[os.SubPath] = readwriter[String].bimap[os.SubPath](_.toString(), os.SubPath(_))

  implicit val uriRw: ReadWriter[java.net.URI] = readwriter[String].bimap[java.net.URI](_.toString(), new java.net.URI(_))

  private[sc4pac] def bareModuleRead(s: String) =
    Sc4pac.parseModule(s) match {
      case Right(mod) => mod
      case Left(err) => throw new IllegalArgumentException(err)
    }
  // Wrapping with `stringKeyRW` is important for Api/packages.info, so that
  // BareModule can be serialized as key of JSON dictionary (instead of serializing Maps as arrays of arrays).
  implicit val bareModuleRw: ReadWriter[BareModule] = stringKeyRW(readwriter[String].bimap[BareModule](_.orgName, bareModuleRead))
  implicit val bareDepRw: ReadWriter[BareDep] = stringKeyRW(readwriter[String].bimap[BareDep](_.orgName, { (s: String) =>
    val prefix = Constants.sc4pacAssetOrg.value + ":"
    if (s.startsWith(prefix)) BareAsset(assetId = C.ModuleName(s.substring(prefix.length))) else bareModuleRead(s)
  }))

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

  case class PluginsSpec(config: Config, explicit: Seq[BareModule]) derives ReadWriter
  object PluginsSpec {
    def path(profileRoot: os.Path): os.Path = profileRoot / "sc4pac-plugins.json"

    def pathURIO: URIO[ProfileRoot, os.Path] = ZIO.service[ProfileRoot].map(profileRoot => PluginsSpec.path(profileRoot.path))

    val defaultPluginsRoot: URIO[ProfileRoot, Seq[os.Path]] = ZIO.serviceWith[ProfileRoot](profileRoot => Seq(
      os.home / "Documents" / "SimCity 4" / "Plugins",
      profileRoot.path / "plugins"
    ))

    val defaultCacheRoot: URIO[ProfileRoot & service.FileSystem, Seq[os.Path]] =
      for {
        profileRoot <- ZIO.service[ProfileRoot]
        fs <- ZIO.service[service.FileSystem]
      } yield Seq(
        util.Try(os.Path(java.nio.file.Paths.get(fs.projectCacheDir)))
          .recover {
            case _ if fs.env.localAppDataWindows.isDefined =>  // attempt at workaround for https://github.com/memo33/sc4pac-gui/issues/32
              os.Path(java.nio.file.Paths.get(fs.env.localAppDataWindows.get)) / cli.BuildInfo.organization / cli.BuildInfo.name / "cache"  // absolute only
          }
          .getOrElse(os.home / "sc4pac" / "cache"),  // safe fallback, see https://github.com/memo33/sc4pac-gui/issues/25
        profileRoot.path / "cache",
      )

    val defaultTempRoot: os.FilePath = os.FilePath("temp")

    /** Prompt for pluginsRoot and cacheRoot. This has a `CliPrompter` constraint as we only want to prompt about this using the CLI. */
    val promptForPaths: RIO[ProfileRoot & service.FileSystem & CliPrompter, (os.Path, os.Path)] = {
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

    /** Init and write. Here `tempRoot` may be absolute or relative (to profile
      * root) to allow GUI to use a shared `../temp` folder for all profiles.
      */
    def init(pluginsRoot: os.Path, cacheRoot: os.Path, tempRoot: os.FilePath): RIO[ProfileRoot, PluginsSpec] = {
      for {
        profileRoot  <- ZIO.service[ProfileRoot]
        spec         =  PluginsSpec(
                          config = Config(
                            pluginsRoot = Config.subRelativize(pluginsRoot, profileRoot),
                            cacheRoot = Config.subRelativize(cacheRoot, profileRoot),
                            tempRoot = tempRoot.toNIO,  // may be relative or absolute
                            variant = Map.empty,
                            channels = Constants.defaultChannelUrls),
                          explicit = Seq.empty)
        pluginsPath  <- PluginsSpec.pathURIO
        _            <- JsonIo.write(pluginsPath, spec, None)(ZIO.succeed(()))
      } yield spec
    }

    /** Reads the PluginsSpec JSON file if it exists, otherwise returns None */
    val readMaybe: ZIO[ProfileRoot, error.ReadingProfileFailed, Option[PluginsSpec]] = PluginsSpec.pathURIO.flatMap { pluginsPath =>
      val task: IO[ErrStr | java.io.IOException, Option[PluginsSpec]] =
        ZIO.ifZIO(ZIO.attemptBlockingIO(os.exists(pluginsPath)))(
          onFalse = ZIO.succeed(None),
          onTrue = ZIO.attemptBlockingIO(JsonIo.readBlocking[PluginsSpec](pluginsPath)).absolve.map(Some(_))
        )
      task.mapError(e => error.ReadingProfileFailed(
        s"Failed to read profile JSON file ${pluginsPath.last}.",
        f"Make sure the file is correctly formatted: $pluginsPath.%n$e"),
      )
    }

    /** Read PluginsSpec from file if it exists, else create it and write it to file. */
    val readOrInit: RIO[ProfileRoot & service.FileSystem & CliPrompter, PluginsSpec] = PluginsSpec.pathURIO.flatMap { pluginsPath =>
      ZIO.ifZIO(ZIO.attemptBlocking(os.exists(pluginsPath)))(
        onTrue = JsonIo.read[PluginsSpec](pluginsPath),
        onFalse = for {
          (pluginsRoot, cacheRoot) <- promptForPaths
          spec                     <- PluginsSpec.init(pluginsRoot, cacheRoot, tempRoot = defaultTempRoot)
        } yield spec
      )
      .mapError(e => error.ReadingProfileFailed(
        s"Failed to read profile JSON file ${pluginsPath.last}.",
        f"Make sure the file is correctly formatted: $pluginsPath.%n${e.getMessage}"),
      )
    }
  }

  private val installedAtDummy = java.time.Instant.parse("2024-01-01T00:00:00Z")  // placeholder for upgrading from scheme 1
  case class InstalledData(
    group: String,
    name: String,
    variant: Variant,
    version: String,
    files: Seq[os.SubPath],
    summary: String = "",  // since scheme 2
    category: Option[String] = None,  // since scheme 2
    installedAt: Instant = installedAtDummy,  // since scheme 2
    updatedAt: Instant = installedAtDummy,  // since scheme 2
    reinstall: Boolean = false,  // since scheme 2+
  ) derives ReadWriter {
    def toDepModule = DepModule(Organization(group), ModuleName(name), version = version, variant = variant)
    def toBareModule = BareModule(Organization(group), ModuleName(name))
    private[sc4pac] def toSearchString: String = s"$group:$name $summary".toLowerCase(java.util.Locale.ENGLISH)  // copied from ChannelItem.toSearchString
    def toApiInstalled = api.InstalledStatus.Installed(version = version, variant = variant, installedAt = installedAt, updatedAt = updatedAt, reinstall = reinstall)
  }

  case class PluginsLock(scheme: Int = 1, installed: Seq[InstalledData], assets: Seq[Asset]) derives ReadWriter {
    def dependenciesWithAssets: Set[Resolution.Dep] =
      (installed.map(_.toDepModule) ++ assets.map(DepAsset.fromAsset(_))).toSet
    def packagesToReinstall: Set[Resolution.Dep] =
      installed.iterator.filter(_.reinstall).map(_.toDepModule).toSet

    def updateTo(plan: Sc4pac.UpdatePlan, stagedItems: Seq[Sc4pac.StageResult.Item]): PluginsLock = {
      val now = java.time.Instant.now().truncatedTo(ChronoUnit.SECONDS)
      val stagedItemsMap = stagedItems.iterator.map(item => item.dep -> item).toMap
      val orig = dependenciesWithAssets
      val next = plan.toInstall | (orig &~ plan.toRemove)
      val previousPkgs: Map[BareModule, InstalledData] = installed.map(i => (i.toBareModule, i)).toMap
      val (arts, insts) = next.toSeq.partitionMap {
        case a: DepAsset => Left(a)
        case m: DepModule => Right(m)
      }
      PluginsLock(
        scheme = Constants.pluginsLockScheme,
        installed = insts.map { dep =>
          val stagedItem = stagedItemsMap.get(dep)  // possibly None
          val bareDep = dep.toBareDep  // we ignore variants when looking up previous package data (mainly for preserving `installedAt`)
          InstalledData(
            group = dep.group.value,
            name = dep.name.value,
            variant = dep.variant,
            version = dep.version,
            files = stagedItem.map(_.files).getOrElse(previousPkgs(bareDep).files),
            summary = stagedItem.map(item => item.pkgData.info.summary).getOrElse(previousPkgs(bareDep).summary),
            category = stagedItem.map(item => categoryFromSubPath(item.pkgData.subfolder)).getOrElse(previousPkgs(bareDep).category),
            installedAt = previousPkgs.get(bareDep).map(_.installedAt).getOrElse(now),
            updatedAt = if (stagedItem.isDefined) now else previousPkgs(bareDep).updatedAt,
            reinstall = false,
          )
        },
        assets = arts.map(dep => Asset(
          assetId = dep.assetId.value,
          version = dep.version,
          url = dep.url,
          lastModified = dep.lastModified.getOrElse(null),
          archiveType = dep.archiveType,
          checksum = dep.checksum,
        ))
      )
    }
  }
  object PluginsLock {

    // called during update task, where PluginsLock file is written
    private[sc4pac] def upgradeFromScheme1(data: PluginsLock, iterateAllChannelPackages: Task[Iterator[ChannelItem]], logger: Logger, pluginsRoot: os.Path): Task[PluginsLock] = {
      if (data.scheme != 1) {
        ZIO.succeed(data)
      } else {
        logger.log(s"Upgrading sc4pac-plugins-lock scheme from 1 to ${Constants.pluginsLockScheme}.")
        def modificationTime(inst: InstalledData): Task[Option[Instant]] = ZIO.attemptBlockingIO {
          inst.files.map(subpath => pluginsRoot / subpath)
            .find(path => os.exists(path) && os.isDir(path))
            .map(path => java.time.Instant.ofEpochMilli(os.mtime(path)).truncatedTo(ChronoUnit.SECONDS))
        }
        for {
          channelItems    <- iterateAllChannelPackages
          channelItemsMap =  channelItems.map(item => item.toBareDep -> item).toMap
          installed       <- ZIO.foreach(data.installed) { inst =>
                               channelItemsMap.get(inst.toBareModule) match {
                                 case None => ZIO.succeed(inst)  // package not found for some reason; we ignore this as update would fail anyway which is the bigger issue
                                 case Some(item) =>
                                   modificationTime(inst).map { mtimeOpt => inst.copy(
                                     summary = item.summary,
                                     category = item.category,
                                     installedAt = mtimeOpt.getOrElse(inst.installedAt),
                                     updatedAt = mtimeOpt.getOrElse(inst.updatedAt)
                                   )
                                 }
                               }
                             }
        } yield PluginsLock(scheme = Constants.pluginsLockScheme, installed = installed, assets = data.assets)
      }
    }

    def path(profileRoot: os.Path): os.Path = profileRoot / "sc4pac-plugins-lock.json"

    def pathURIO: URIO[ProfileRoot, os.Path] = ZIO.service[ProfileRoot].map(profileRoot => PluginsLock.path(profileRoot.path))

    /** Read PluginsLock from file if it exists, else create it and write it to file.
      * Does *not* automatically upgrade from scheme 1.*/
    val readOrInit: RIO[ProfileRoot, PluginsLock] = PluginsLock.pathURIO.flatMap { pluginsLockPath =>
      ZIO.ifZIO(ZIO.attemptBlocking(os.exists(pluginsLockPath)))(
        onTrue = JsonIo.read[PluginsLock](pluginsLockPath),
        onFalse = {
          val data = PluginsLock(Constants.pluginsLockScheme, Seq.empty, Seq.empty)
          JsonIo.write(pluginsLockPath, data, None)(ZIO.succeed(data))
        }
      )
      .mapError(e => error.ReadingProfileFailed(
        s"Failed to read profile lock file ${pluginsLockPath.last}.",
        f"Make sure the file is correctly formatted: $pluginsLockPath.%n${e.getMessage}"),
      )
    }

    // does *not* automatically upgrade from scheme 1 (this function is only used for reading, not writing)
    val listInstalled2: RIO[ProfileRoot, Seq[InstalledData]] = PluginsLock.pathURIO.flatMap { pluginsLockPath =>
      ZIO.ifZIO(ZIO.attemptBlocking(os.exists(pluginsLockPath)))(
        onTrue = JsonIo.read[PluginsLock](pluginsLockPath).map(_.installed),
        onFalse = ZIO.succeed(Seq.empty)
      )
    }

    val listInstalled: RIO[ProfileRoot, Seq[DepModule]] = listInstalled2.map(_.map(_.toDepModule))

  }

  case class Checksum(sha256: Option[ArraySeq[Byte]])
  object Checksum {
    val empty = Checksum(sha256 = None)
    def bytesToString(bytes: ArraySeq[Byte]): String = bytes.map("%02x".format(_)).mkString
    def stringToBytes(hexString: String): ArraySeq[Byte] = {
      if (hexString.length % 2 != 0 || hexString.isEmpty) {
        throw new NumberFormatException(s"Checksum requires an even number of hex characters: $hexString")
      } else {
        hexString.grouped(2).map(ss => java.lang.Short.parseShort(ss, 16).toByte).to(ArraySeq)
      }
    }
  }
  protected def emptyChecksum = Checksum.empty

  protected def categoryFromSubPath(subpath: SubPath): Option[String] = subpath.segments0.headOption

  implicit val checksumRw: ReadWriter[Checksum] =
    readwriter[Map[String, String]].bimap[Checksum](
      (checksum: Checksum) => checksum.sha256.map("sha256" -> Checksum.bytesToString(_)).toMap,
      (m: Map[String, String]) => Checksum(sha256 = m.get("sha256").map(Checksum.stringToBytes))
    )

  case class CheckFile(filename: Option[String], checksum: Checksum = Checksum.empty) derives ReadWriter

  case class ProfileData(id: ProfileId, name: String) derives ReadWriter

  // or GuiConfig or GuiSettings
  case class Profiles(profiles: Seq[ProfileData], currentProfileId: Option[ProfileId], settings: ujson.Value = ujson.Obj()) derives ReadWriter {

    private def nextId: ProfileId = {
      val existing = profiles.map(_.id).toSet
      Iterator.from(1).map(_.toString).dropWhile(existing).next
    }

    def add(name: String): (Profiles, ProfileData) = {
      val id = nextId
      val profile = ProfileData(id = id, name = name)
      (copy(profiles = profiles :+ profile, currentProfileId = Some(id)), profile)
    }
  }
  object Profiles {

    def parseProfilesRoot(path: Option[String]): ZIO[service.FileSystem, error.ObtainingUserDirsFailed, os.Path] = {
      ZIO.serviceWithZIO[service.FileSystem] { fs =>
        ZIO.attempt {
          path match {
            case None =>
              if (fs.env.sc4pacProfilesDir.isDefined)  // environment variable
                os.Path(java.nio.file.Paths.get(fs.env.sc4pacProfilesDir.get))  // absolute only
              else {
                val config: os.Path =
                  try {
                    os.Path(java.nio.file.Paths.get(fs.projectConfigDir))  // absolute only
                  } catch {
                    case _ if fs.env.appDataWindows.isDefined =>  // attempt at workaround for https://github.com/memo33/sc4pac-gui/issues/32
                      os.Path(java.nio.file.Paths.get(fs.env.appDataWindows.get)) / cli.BuildInfo.organization / cli.BuildInfo.name / "config"  // absolute only
                  }
                config / "profiles"
              }
            case Some(p) =>
              os.Path(java.nio.file.Paths.get(p), os.pwd)  // relative allowed
          }
        }.mapError(e => error.ObtainingUserDirsFailed(
          "Failed to determine sc4pac profiles directory."
          + " As a workaround, either use the --profiles-dir launch parameter or set the SC4PAC_PROFILES_DIR environment variable –"
          + """ on Windows, usually to the path "C:\Users\YOURUSERNAME\AppData\Roaming\io.github.memo33\sc4pac\config\profiles"""",
          e.getMessage,  // see https://github.com/memo33/sc4pac-gui/issues/25
        ))
      }
    }

    def path(profilesDir: os.Path): os.Path = profilesDir / "sc4pac-profiles.json"

    def pathURIO: URIO[ProfilesDir, os.Path] = ZIO.service[ProfilesDir].map(profilesDir => Profiles.path(profilesDir.path))

    /** Read Profiles from file if it exists, else create it and write it to file. */
    val readOrInit: RIO[ProfilesDir, Profiles] = Profiles.pathURIO.flatMap { jsonPath =>
      ZIO.ifZIO(ZIO.attemptBlocking(os.exists(jsonPath)))(
        onTrue = JsonIo.read[Profiles](jsonPath),
        onFalse = ZIO.succeed(Profiles(Seq.empty, None))
      )
      .mapError(e => error.ReadingProfileFailed(
        s"Failed to read profiles file ${jsonPath.last}.",
        f"Make sure the file is correctly formatted: $jsonPath.%n${e.getMessage}"),
      )
    }
  }

  case class IncludeWithChecksum(include: String, sha256: ArraySeq[Byte])

  implicit val includeWithChecksumRw: ReadWriter[IncludeWithChecksum] =
    readwriter[Map[String, String]].bimap[IncludeWithChecksum](
      (data: IncludeWithChecksum) => Map("include" -> data.include, "sha256" -> Checksum.bytesToString(data.sha256)),
      (m: Map[String, String]) => IncludeWithChecksum(include = m("include"), sha256 = Checksum.stringToBytes(m("sha256"))),
    )

  case class ExportData(
    explicit: Seq[BareModule] = Seq.empty,
    variants: Variant = Map.empty,
    channels: Seq[Uri] = Seq.empty,
  ) derives ReadWriter

  sealed trait Warning { def value: String }
  class InformativeWarning(val value: String) extends Warning
  class UnexpectedWarning(val value: String) extends Warning

}
