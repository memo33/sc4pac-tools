package io.github.memo33
package sc4pac

import coursier.core.{Module, ModuleName, Organization}
import coursier.core as C
import upickle.default.{ReadWriter, readwriter}
import java.nio.file.{Path as NioPath}
import zio.{ZIO, IO, RIO, URIO, Task}
import java.util.regex.Pattern
import scala.collection.mutable.Builder
import scala.collection.immutable.ArraySeq
import java.time.temporal.ChronoUnit

import sc4pac.Resolution.{Dep, DepModule, DepAsset}

/** Contains data types for JSON serialization. */
object JsonData extends SharedData {

  override type Instant = java.time.Instant
  override type SubPath = os.SubPath

  // We use OffsetDateTime.parse instead of Instant.parse for compatibility with Java 8 to 11
  implicit val instantRw: ReadWriter[java.time.Instant] =
    readwriter[String].bimap[java.time.Instant](_.toString(), Option(_).map(s => java.time.OffsetDateTime.parse(s.trim()).toInstant()).orNull)

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
  ) derives ReadWriter {
    def moduleWithoutAttributes = Module(Organization(group), ModuleName(name), attributes=Map.empty)
    def moduleWithAttributes = Module(Organization(group), ModuleName(name), attributes=VariantData.variantToAttributes(variant))
    // def toDependency = DepVariant.fromDependency(C.Dependency(moduleWithAttributes, version))  // TODO remove?
    def toDepModule = DepModule(Organization(group), ModuleName(name), version = version, variant = variant)
    def toBareModule = BareModule(Organization(group), ModuleName(name))
  }

  case class PluginsLock(scheme: Int = 1, installed: Seq[InstalledData], assets: Seq[Asset]) derives ReadWriter {
    def dependenciesWithAssets: Set[Resolution.Dep] =
      (installed.map(_.toDepModule) ++ assets.map(DepAsset.fromAsset(_))).toSet

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
            category = stagedItem.map(item => Some(item.pkgData.subfolder.toString)).getOrElse(previousPkgs(bareDep).category),
            installedAt = previousPkgs.get(bareDep).map(_.installedAt).getOrElse(now),
            updatedAt = if (stagedItem.isDefined) now else previousPkgs(bareDep).updatedAt,
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
    private[sc4pac] def upgradeFromScheme1(data: PluginsLock, iterateAllChannelContents: Task[Iterator[ChannelItem]], logger: Logger, pluginsRoot: os.Path): Task[PluginsLock] = {
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
          channelItems    <- iterateAllChannelContents
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
    }

    // does *not* automatically upgrade from scheme 1 (this function is only used for reading, not writing)
    val listInstalled: RIO[ProfileRoot, Seq[DepModule]] = PluginsLock.pathURIO.flatMap { pluginsLockPath =>
      ZIO.ifZIO(ZIO.attemptBlocking(os.exists(pluginsLockPath)))(
        onTrue = JsonIo.read[PluginsLock](pluginsLockPath).map(_.installed.map(_.toDepModule)),
        onFalse = ZIO.succeed(Seq.empty)
      )
    }
  }

  class InstallRecipe(include: Seq[Pattern], exclude: Seq[Pattern], includeWithChecksum: Seq[(Pattern, IncludeWithChecksum)]) {
    def makeAcceptancePredicate(): (Builder[Pattern, Set[Pattern]], Extractor.Predicate) = {
      val usedPatternsBuilder = {
        val b = Set.newBuilder[Pattern] += Constants.defaultExcludePattern  // default exclude pattern is not required to match anything
        if (includeWithChecksum.nonEmpty)
          b += Constants.defaultIncludePattern  // archives containing DLLs might come without additional DBPF files
        b
      }

      val accepts: Extractor.Predicate = { path =>
        val pathString = path.segments.mkString("/", "/", "")  // paths are checked with leading / and with / as separator
        includeWithChecksum.find(_._1.matcher(pathString).find()) match {
          case Some(matchedPattern, checksum) =>
            usedPatternsBuilder += matchedPattern
            Some(Extractor.ChecksumValidator(checksum))  // as checksum is only valid for a single file, there is no need for evaluating exclude rules
          case None =>
            include.find(_.matcher(pathString).find()) match {
              case None => None
              case Some(matchedPattern) =>
                usedPatternsBuilder += matchedPattern
                exclude.find(_.matcher(pathString).find()) match {
                  case None => Some(Extractor.DbpfValidator)
                  case Some(matchedPattern) =>
                    usedPatternsBuilder += matchedPattern
                    None
                }
            }
        }
      }

      (usedPatternsBuilder, accepts)
    }

    def usedPatternWarnings(usedPatterns: Set[Pattern], asset: BareAsset): Seq[Warning] = {
      val unused: Seq[Pattern] =
        (include.iterator ++ exclude ++ includeWithChecksum.iterator.map(_._1))
        .filter(p => !usedPatterns.contains(p)).toSeq
      if (unused.isEmpty) {
        Seq.empty
      } else {
        Seq(
          "The package metadata seems to be out-of-date, so the package may not have been fully installed. " +
          "Please report this to the maintainers of the package metadata. " +
          s"These inclusion/exclusion patterns did not match any files in the asset ${asset.assetId.value}: " + unused.mkString(" "))
      }
    }
  }
  object InstallRecipe {
    def fromAssetReference(data: AssetReference): (InstallRecipe, Seq[Warning]) = {
      val warnings = Seq.newBuilder[Warning]
      def toRegex(s: String): Option[Pattern] = try {
        Some(Pattern.compile(s, Pattern.CASE_INSENSITIVE))
      } catch {
        case e: java.util.regex.PatternSyntaxException =>
          warnings += s"The package metadata contains a malformed regex: $e"
          None
      }
      val include = data.include.flatMap(toRegex)
      val exclude = data.exclude.flatMap(toRegex)
      val includeWithChecksum = data.withChecksum.flatMap(item => toRegex(item.include).map(_ -> item))
      (InstallRecipe(
        include = if (include.isEmpty) Seq(Constants.defaultIncludePattern) else include,
        exclude = if (exclude.isEmpty) Seq(Constants.defaultExcludePattern) else exclude,
        includeWithChecksum = includeWithChecksum,
      ), warnings.result())
    }
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

  implicit val checksumRw: ReadWriter[Checksum] =
    readwriter[Map[String, String]].bimap[Checksum](
      (checksum: Checksum) => checksum.sha256.map("sha256" -> Checksum.bytesToString(_)).toMap,
      (m: Map[String, String]) => Checksum(sha256 = m.get("sha256").map(Checksum.stringToBytes))
    )

  case class CheckFile(filename: Option[String], checksum: Checksum = Checksum.empty) derives ReadWriter

  case class IncludeWithChecksum(include: String, sha256: ArraySeq[Byte])

  implicit val includeWithChecksumRw: ReadWriter[IncludeWithChecksum] =
    readwriter[Map[String, String]].bimap[IncludeWithChecksum](
      (data: IncludeWithChecksum) => Map("include" -> data.include, "sha256" -> Checksum.bytesToString(data.sha256)),
      (m: Map[String, String]) => IncludeWithChecksum(include = m("include"), sha256 = Checksum.stringToBytes(m("sha256"))),
    )

}
