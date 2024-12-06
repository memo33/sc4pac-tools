package io.github.memo33
package sc4pac

import coursier.core.{Module, Versions, Version}
import coursier.cache as CC
import upickle.default.Reader
import zio.{ZIO, IO}

import sc4pac.error.*
import sc4pac.JsonData as JD

sealed abstract class MetadataRepository(val baseUri: java.net.URI) {

  /** Obtain the raw version strings of `dep` contained in this repository. */
  def getRawVersions(dep: BareDep): Seq[String]

  /** Reads the repository's channel contents to obtain all available versions
    * of modules.
    */
  def fetchVersions(module: Module): IO[ErrStr, (Versions, String)] = {
    ZIO.fromEither {
      val rawVersions = getRawVersions(CoursierUtil.bareDepFromModule(module))
      if (rawVersions.nonEmpty) {
        val parsedVersions = rawVersions.map(Version(_))
        val latest = parsedVersions.max
        // val nonPreVersions = parsedVersions.filter(_.items.forall {
        //   case t: Version.Tag => !t.isPreRelease
        //   case _ => true
        // })
        // val release = if (nonPreVersions.nonEmpty) nonPreVersions.max else latest
        val release = latest  // We do not bother with detecting prerelease versions,
                              // as our version numbers are not predictable enough
                              // and we only care about the current release anyway.
        Right((Versions(latest.repr, release.repr, available = parsedVersions.map(_.repr).toList, lastUpdated = None),  // TODO lastUpdated = lastModified?
               MetadataRepository.channelContentsUrl(baseUri).toString))
      } else {
        Left(s"no versions of $module found in repository $baseUri")
      }
    }
  }

  /** For a module (no asset, no variant) of a given version, fetch the
    * corresponding `JD.Package` contained in its json file.
    */
  def fetchModuleJson[A <: JD.PackageAsset : Reader](module: Module, version: String, fetch: MetadataRepository.Fetch): zio.Task[A]

  def iterateChannelContents: Iterator[JD.ChannelItem]
}

object MetadataRepository {

  type Fetch = Artifact => IO[CC.ArtifactError, String]

  def channelContentsUrl(baseUri: java.net.URI): java.net.URI =
    if (baseUri.getPath.endsWith(".yaml")) baseUri else baseUri.resolve(JsonRepoUtil.channelContentsFilename)

  /** Sanitize URL.
    */
  def parseChannelUrl(url: String): Either[ErrStr, java.net.URI] = try {
    val uri = new java.net.URI(url)
    if (uri.getScheme == null) Left(s"channel URL must start with https://, http:// or file:/// ($uri)")  // coursier-cache requirement
    else if (uri.getRawQuery != null) Left(s"channel URL must not have query: $uri")
    else if (uri.getRawFragment != null) Left(s"channel URL must not have fragment: $uri")
    else if (uri.getPath.endsWith("/") || uri.getPath.endsWith(".yaml")) {
      Right(uri)
    } else {
      Right(java.net.URI.create(url + "/"))  // add a slash for proper subdirectory resolution
    }
  } catch {
    case e: java.net.URISyntaxException => Left(e.getMessage)
  }

  def create(channelContentsFile: os.Path, baseUri: java.net.URI): IO[ErrStr, MetadataRepository] = {
    if (baseUri.getPath.endsWith(".yaml")) {  // yaml repository
      for {
        contents <- ChannelUtil.readAndParsePkgData(channelContentsFile, root = None)
      } yield {
        val channelData: Map[BareDep, Map[String, JD.PackageAsset]] =
          contents.groupMap(_.toBareDep)(data => data.version -> data).view.mapValues(_.toMap).toMap
        new YamlRepository(baseUri, channelData)
      }
    } else {  // json repository
      val contentsUrl = channelContentsUrl(baseUri).toString
      for {
        jsonVal <- ZIO.attemptBlockingIO(ujson.read(channelContentsFile.toNIO))
                     .mapError(e => s"Failed to read channel contents: $e")
        scheme  =  jsonVal.objOpt.flatMap(_.get("scheme")).map(_.num.toInt).getOrElse(0)
        _       <- ZIO.when(scheme < Constants.channelSchemeVersions.min)(ZIO.fail(
                     s"The channel $contentsUrl uses an old scheme ($scheme) that is not supported anymore by your version of sc4pac." +
                     " Contact the maintainer of the channel."
                   ))
        _       <- ZIO.when(scheme > Constants.channelSchemeVersions.max)(ZIO.fail(
                     s"The channel $contentsUrl has a newer scheme ($scheme) than supported " +
                     s"by your installation (${Constants.channelSchemeVersions}). Please update to the latest version of sc4pac."
                   ))
        channel <- JsonIo.read[JD.Channel](jsonVal, errMsg = contentsUrl)
                     .mapError(e => s"Failed to read channel contents: $e")
      } yield new JsonRepository(baseUri, channel)
    }
  }

  def jsonSubPath(dep: BareDep, version: String): os.SubPath = {
    os.SubPath(JsonRepoUtil.packageSubPath(dep, version))
  }

  // use jsonSubPath(group, name, "latest") for the json file
  def latestSubPath(group: String, name: String): os.SubPath = {
    os.SubPath(s"metadata/$group/$name/latest")
  }
}

/** This repository operates on a hierarchy of JSON files. The JSON files are loaded on demand.
  */
private class JsonRepository(
  baseUri: java.net.URI,
  channelData: JD.Channel
) extends MetadataRepository(baseUri) {

  /** Obtain the raw version strings of `dep` contained in this repository. */
  def getRawVersions(dep: BareDep): Seq[String] = channelData.versions.getOrElse(dep, Seq.empty).map(_._1)

  // TODO Avoid fetching the same json file multiple times during a single
  // update command. This could lead to races during an update. (As the
  // functionally relevant properties do not change for a fixed version of a
  // json file, this is mainly a performance concern for now.)

  /** For a module (no asset, no variant) of a given version, fetch the
    * corresponding `JD.Package` contained in its json file.
    */
  def fetchModuleJson[A <: JD.PackageAsset : Reader](module: Module, version: String, fetch: MetadataRepository.Fetch): zio.Task[A] = {
    val dep = CoursierUtil.bareDepFromModule(module)
    channelData.versions.get(dep).flatMap(_.find(_._1 == version)) match
      // This only works for concrete versions (so not for "latest.release").
      // The assumption is that all methods of MetadataRepository are only called with concrete versions.
      case None =>
        ZIO.fail(new Sc4pacVersionNotFound(s"No versions of ${module.orgName} found in repository $baseUri.",
          "Either the package name is spelled incorrectly or the metadata stored in the corresponding channel is incorrect or incomplete."))
      case Some((_, checksum)) =>
        val remoteUrl = baseUri.resolve(MetadataRepository.jsonSubPath(dep, version).segments0.mkString("/")).toString
        // We have complete control over the json metadata files. Usually, they
        // do not functionally change for a fixed version, but info fields like
        // `requiredBy` can change, so we redownload them once the checksum stops matching.
        // (For local channels, there's no need to verify checksums as the local
        // channel files are always up-to-date and Downloader .checked files do not exist.)
        val jsonArtifact = Artifact(remoteUrl, changing = false, checksum = checksum, redownloadOnChecksumError = true)

        fetch(jsonArtifact)
          .flatMap((jsonStr: String) => JsonIo.read[A](jsonStr, errMsg = remoteUrl))
  }

  def iterateChannelContents: Iterator[JD.ChannelItem] = channelData.contents.iterator
}

/** This repository is read from a single YAML file. All its data is held in memory.
  */
private class YamlRepository(
  baseUri: java.net.URI,
  channelData: Map[BareDep, Map[String, JD.PackageAsset]]  // name -> version -> json
) extends MetadataRepository(baseUri) {

  def getRawVersions(dep: BareDep): Seq[String] = {
    channelData.get(dep).map(_.keys.toSeq).getOrElse(Seq.empty)
  }

  def fetchModuleJson[A <: JD.PackageAsset : Reader](module: Module, version: String, fetch: MetadataRepository.Fetch): zio.Task[A] = {
    val dep = CoursierUtil.bareDepFromModule(module)
    channelData.get(dep).flatMap(_.get(version)) match {
      case None =>
        ZIO.fail(new Sc4pacVersionNotFound(s"No versions of ${module.orgName} found in repository $baseUri.",
          "Either the package name is spelled incorrectly or the metadata stored in the corresponding channel is incorrect or incomplete."))
      case Some(pkgData: JD.PackageAsset) =>
        ZIO.succeed(pkgData.asInstanceOf[A])  // as long as we do not mix up Assets and Packages, casting should not be an issue (could be fixed using type classes)
    }
  }

  // We pick the latest scheme version as we do not have any other input to work with.
  // If the scheme has been updated, then the yaml files have probably already failed to parse.
  lazy private val channel = JD.Channel.create(
    scheme = Constants.channelSchemeVersions.max,
    channelData.view.mapValues(_.view.map { case (version, pkgData) => (version, pkgData, JD.Checksum.empty) })
  )

  def iterateChannelContents: Iterator[JD.ChannelItem] = channel.contents.iterator
}
