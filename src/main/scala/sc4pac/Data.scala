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
  override given instantRw: ReadWriter[java.time.Instant] =
    readwriter[String].bimap[java.time.Instant](_.toString(), Option(_).map(s => java.time.OffsetDateTime.parse(s.trim()).toInstant()).orNull)

  given pathRw: ReadWriter[NioPath] = readwriter[String].bimap[NioPath](_.toString(), java.nio.file.Paths.get(_))

  override given subPathRw: ReadWriter[os.SubPath] = readwriter[String].bimap[os.SubPath](_.toString(), os.SubPath(_))

  override given uriRw: ReadWriter[java.net.URI] = readwriter[String].bimap[java.net.URI](_.toString(), new java.net.URI(_))

  private[sc4pac] def bareModuleRead(s: String) =
    Sc4pac.parseModule(s) match {
      case Right(mod) => mod
      case Left(err) => throw new IllegalArgumentException(err)
    }
  // Wrapping with `stringKeyRW` is important for Api/packages.info, so that
  // BareModule can be serialized as key of JSON dictionary (instead of serializing Maps as arrays of arrays).
  override given bareModuleRw: ReadWriter[BareModule] = stringKeyRW(readwriter[String].bimap[BareModule](_.orgName, bareModuleRead))
  given bareDepRw: ReadWriter[BareDep] = stringKeyRW(readwriter[String].bimap[BareDep](_.orgName, { (s: String) =>
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
    val pluginsRootAbs: RIO[Profile, os.Path] = ZIO.service[Profile].map(profile => os.Path(pluginsRoot, profile.root))
    val cacheRootAbs: RIO[Profile, os.Path] = ZIO.service[Profile].map(profile => os.Path(cacheRoot, profile.root))
    val tempRootAbs: RIO[Profile, os.Path] = ZIO.service[Profile].map(profile => os.Path(tempRoot, profile.root))
  }
  object Config {
    /** Turns an absolute path into a relative one if it is a subpath of profile, otherwise returns an absolute path. */
    def subRelativize(path: os.Path, profile: Profile): NioPath = {
      try {
        val sub: os.SubPath = path.subRelativeTo(profile.root)
        sub.toNIO
      } catch {
        case _: IllegalArgumentException => path.toNIO
      }
    }
  }

  case class PluginsSpec(config: Config, explicit: Seq[BareModule]) derives ReadWriter
  object PluginsSpec {

    val defaultPluginsRoot: URIO[Profile, Seq[os.Path]] = ZIO.serviceWith[Profile](profile => Seq(
      os.home / "Documents" / "SimCity 4" / "Plugins",
      profile.root / "plugins"
    ))

    val defaultCacheRoot: URIO[Profile & service.FileSystem, Seq[os.Path]] =
      for {
        profile <- ZIO.service[Profile]
        fs <- ZIO.service[service.FileSystem]
      } yield Seq(
        util.Try(os.Path(java.nio.file.Paths.get(fs.projectCacheDir)))
          .recover {
            case _ if fs.env.localAppDataWindows.isDefined =>  // attempt at workaround for https://github.com/memo33/sc4pac-gui/issues/32
              os.Path(java.nio.file.Paths.get(fs.env.localAppDataWindows.get)) / cli.BuildInfo.organization / cli.BuildInfo.name / "cache"  // absolute only
          }
          .getOrElse(os.home / "sc4pac" / "cache"),  // safe fallback, see https://github.com/memo33/sc4pac-gui/issues/25
        profile.root / "cache",
      )

    val defaultTempRoot: os.FilePath = os.FilePath("temp")

    /** Prompt for pluginsRoot and cacheRoot. This has a `CliPrompter` constraint as we only want to prompt about this using the CLI. */
    val promptForPaths: RIO[Profile & service.FileSystem & CliPrompter, (os.Path, os.Path)] = {
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

    /** Init (without writing). Here `tempRoot` may be absolute or relative (to profile
      * root) to allow GUI to use a shared `../temp` folder for all profiles.
      */
    def initNoWrite(pluginsRoot: os.Path, cacheRoot: os.Path, tempRoot: os.FilePath): RIO[Profile, PluginsSpec] = {
      for {
        profile      <- ZIO.service[Profile]
        spec         =  PluginsSpec(
                          config = Config(
                            pluginsRoot = Config.subRelativize(pluginsRoot, profile),
                            cacheRoot = Config.subRelativize(cacheRoot, profile),
                            tempRoot = tempRoot.toNIO,  // may be relative or absolute
                            variant = Map.empty,
                            channels = Constants.defaultChannelUrls),
                          explicit = Seq.empty)
      } yield spec
    }

    /* Read or initialize (with writing). */
    val readOrInit: RIO[ProfileStorage & Profile & service.FileSystem & CliPrompter, PluginsSpec] =
      ZIO.serviceWithZIO[Profile] { profile =>
        ZIO.serviceWithZIO[ProfileStorage](_.loadSpecOrInitWith(profile)(init = for {
          (pluginsRoot, cacheRoot) <- promptForPaths
          spec <- PluginsSpec.initNoWrite(pluginsRoot, cacheRoot, tempRoot = defaultTempRoot)
        } yield spec))
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

  case class PluginsLock(
    scheme: Int = 1,
    installed: Seq[InstalledData],
    assets: Seq[Asset],
    redownload: Seq[BareModule] = Seq.empty,  // since scheme 2+
  ) derives ReadWriter {
    def dependenciesWithAssets: Set[Dep] =
      (installed.map(_.toDepModule) ++ assets.map(DepAsset.fromAsset(_))).toSet
    def packagesToReinstall: Set[DepModule] =
      installed.iterator.filter(_.reinstall).map(_.toDepModule).toSet
    def packagesToRedownload: Set[BareModule] = redownload.toSet

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
        )),
        redownload = Seq.empty,  // after update is complete, any redownloads will have been processed
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

    // does *not* automatically upgrade from scheme 1 (this function is only used for reading, not writing)
    val listInstalled2: RIO[Profile & ProfileStorage, Seq[InstalledData]] =
      for {
        profile <- ZIO.service[Profile]
        lockOpt <- ZIO.serviceWithZIO[ProfileStorage](_.loadLockMaybe(profile))
      } yield lockOpt.map(_.installed).getOrElse(Seq.empty)

    val listInstalled: RIO[Profile & ProfileStorage, Seq[DepModule]] = listInstalled2.map(_.map(_.toDepModule))

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

  override given checksumRw: ReadWriter[Checksum] =
    readwriter[Map[String, String]].bimap[Checksum](
      (checksum: Checksum) => checksum.sha256.map("sha256" -> Checksum.bytesToString(_)).toMap,
      (m: Map[String, String]) => Checksum(sha256 = m.get("sha256").map(Checksum.stringToBytes))
    )

  given etagRw: ReadWriter[zio.http.Header.ETag] =
    readwriter[String].bimap[zio.http.Header.ETag](
      _.renderedValue,
      s => zio.http.Header.ETag.parse(s).getOrElse(throw new IllegalArgumentException(s"Invalid etag: $s")),
    )

  // `source` is local path or remote url, for debugging purposes, see https://github.com/memo33/sc4pac-tools/issues/43
  case class CheckFile(filename: Option[String], checksum: Checksum = Checksum.empty, source: String = null, etag: Option[zio.http.Header.ETag] = None) derives ReadWriter

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

    def parseProfilesRoot(path: Option[String]): ZIO[service.FileSystem, error.ObtainingUserDirsFailed, ProfilesDir] = {
      ZIO.serviceWithZIO[service.FileSystem] { fs =>
        ZIO.attempt {
          ProfilesDir(path match {
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
          })
        }.mapError(e => error.ObtainingUserDirsFailed(
          "Failed to determine sc4pac profiles directory."
          + " As a workaround, either use the --profiles-dir launch parameter or set the SC4PAC_PROFILES_DIR environment variable –"
          + """ on Windows, usually to the path "C:\Users\YOURUSERNAME\AppData\Roaming\io.github.memo33\sc4pac\config\profiles"""",
          e.getMessage,  // see https://github.com/memo33/sc4pac-gui/issues/25
        ))
      }
    }
  }

  case class IncludeWithChecksum(include: String, sha256: ArraySeq[Byte])

  override given includeWithChecksumRw: ReadWriter[IncludeWithChecksum] =
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
