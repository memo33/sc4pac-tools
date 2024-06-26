package io.github.memo33
package sc4pac

import coursier.core.{Repository, Module, Publication, ArtifactSource, Versions, Version,
  Dependency, Project, Classifier, Extension, Type, Configuration, ModuleName, Organization, Info}
import coursier.util.{Artifact, EitherT, Monad, Task}
import upickle.default.{read, macroRW, ReadWriter, Reader, writeTo}
import zio.{ZIO, IO}

import sc4pac.error.*
import sc4pac.Constants.isSc4pacAsset
import sc4pac.JsonData as JD

sealed abstract class MetadataRepository(val baseUri: java.net.URI /*, globalVariant: Variant*/) extends Repository {

  /** Obtain the raw version strings of `dep` contained in this repository. */
  def getRawVersions(dep: BareDep): Seq[String]

  /** Reads the repository's channel contents to obtain all available versions
    * of modules.
    */
  override def fetchVersions[F[_] : Monad](module: Module, fetch: Repository.Fetch[F]): EitherT[F, ErrStr, (Versions, String)] = {
    EitherT.fromEither {
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
  def fetchModuleJson[F[_] : Monad, A <: JD.PackageAsset : Reader](module: Module, version: String, fetch: Repository.Fetch[F]): EitherT[F, ErrStr, A]

  /** For a module of a given version, find its metadata (parsed from its json
    * file) and return it as a `Project` (Coursier's representation of package
    * metadata, which only mirrors information of Maven pom files, so does not
    * contain all the information we need for installation, but contains enough
    * information for Coursier to do resolutions). An `ArtifactSource` is the
    * corresponding `Repository`.
    */
  def find[F[_] : Monad](module: Module, version: String, fetch: Repository.Fetch[F]): EitherT[F, ErrStr, (ArtifactSource, Project)] = {
    ???  // TODO unused implementation
    /*
    if (isSc4pacAsset(module)) {
      // EitherT.fromEither(Right((this, MetadataRepository.sc4pacAssetProject(module, version))))
      fetchModuleJson[F, JD.Asset](module, version, fetch)
        .flatMap { data => EitherT.point((this, data.toProject)) }
    } else {
      fetchModuleJson[F, JD.Package](module, version, fetch)
        .flatMap { data =>
          data.toProject(globalVariant) match {
            case Left(err) => throw new Sc4pacMissingVariant(data, msg = err)
            case Right(proj) => EitherT.point((this, proj))
          }
        }
        // TODO in case of error, we should not cache faulty file
    }
    */
  }

  def artifacts(dependency: Dependency, project: Project, overrideClassifiers: Option[Seq[Classifier]]): Seq[(Publication, Artifact)] = {
    ???  // TODO unused implementation
    /*
    if (overrideClassifiers.nonEmpty) ???  // TODO

    if (!isSc4pacAsset(dependency.module)) {
      // here project.module contains variants as attributes, unlike dependency.module
      Seq.empty  // module corresponds to metadata json and does not provide any files itself
    } else {
      // Here dependency is an sc4pacAsset. As these are specified as bare
      // dependencies using just the assetId (without url etc.), it is
      // expected that dependency.module does not contain url attribute, but
      // project.module does (since the project is obtained by parsing the json
      // file of the sc4pacAsset).
      require(dependency.module == project.module.withAttributes(Map.empty), s"unexpected asset inconsistency: ${dependency.module} vs ${project.module}")  // TODO check this
      require(project.module.attributes.contains(Constants.urlKey), s"asset should contain url: ${project.module}")
      val mod = project.module
      val url = mod.attributes(Constants.urlKey)

      val ext = Extension.empty  // Extension(dep.publication.`type`.value)  // TODO determine extension
      val typ = /*dep.publication.`type`*/ Constants.sc4pacAssetType
      val pub = Publication(mod.name.value, typ, ext, Classifier.empty /*Classifier(s"file$idx")*/)  // TODO classifier not needed?
      require(isSc4pacAsset(mod))  // TODO
      val lastModifiedOpt: Option[java.time.Instant] = mod.attributes.get(Constants.lastModifiedKey).flatMap(JD.Asset.parseLastModified)
      Seq((pub, MetadataRepository.createArtifact(url, lastModifiedOpt)))
    }
    */
  }

  def iterateChannelContents: Iterator[JD.ChannelItem]
}

object MetadataRepository {
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

  def create(channelContentsFile: os.Path, baseUri: java.net.URI /*, globalVariant: Variant*/): IO[ErrStr, MetadataRepository] = {
    if (baseUri.getPath.endsWith(".yaml")) {  // yaml repository
      for {
        contents <- ChannelUtil.readAndParsePkgData(channelContentsFile, root = None)
      } yield {
        val channelData: Map[BareDep, Map[String, JD.PackageAsset]] =
          contents.groupMap(_.toBareDep)(data => data.version -> data).view.mapValues(_.toMap).toMap
        new YamlRepository(baseUri, channelData /*, globalVariant*/)
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
        channel <- ZIO.attemptBlockingIO(JsonIo.readBlocking[JD.Channel](jsonVal, errMsg = contentsUrl))
                     .mapError(e => s"Failed to read channel contents: $e")
                     .absolve
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

  def createArtifact(url: String, lastModifiedOpt: Option[java.time.Instant]): Artifact = {
    val changing = if (lastModifiedOpt.isEmpty) {
      // We do not know when the remote artifact is updated, so we set the artifact
      // to be `changing` so that the cache can look for updates from time to time.
      true
    } else {
      // We use the lastModified timestamp to determine when the remote
      // artifact is newer than a locally cached file.
      // This is implemented in deleteStaleCachedFiles.
      false
      // TODO Consider version string as well.
    }
    Artifact(url).withChanging(changing)
  }
}

/** This repository operates on a hierarchy of JSON files. The JSON files are loaded on demand.
  */
private class JsonRepository(
  baseUri: java.net.URI,
  channelData: JD.Channel
  // globalVariant: Variant
) extends MetadataRepository(baseUri/*, globalVariant*/) {

  /** Obtain the raw version strings of `dep` contained in this repository. */
  def getRawVersions(dep: BareDep): Seq[String] = channelData.versions.get(dep).getOrElse(Seq.empty)

  // This only works for concrete versions (so not for "latest.release").
  // The assumption is that all methods of MetadataRepository are only called with concrete versions.
  private def containsVersion(dep: BareDep, version: String): Boolean = {
    getRawVersions(dep).exists(_ == version)
  }

  /** For a module (no asset, no variant) of a given version, fetch the
    * corresponding `JD.Package` contained in its json file.
    */
  def fetchModuleJson[F[_] : Monad, A <: JD.PackageAsset : Reader](module: Module, version: String, fetch: Repository.Fetch[F]): EitherT[F, ErrStr, A] = {
    val dep = CoursierUtil.bareDepFromModule(module)
    if (!containsVersion(dep, version)) {
      EitherT.fromEither(Left(s"no versions of $module found in repository $baseUri"))
    } else {
      // TODO use flatter directory (remove version folder, rename maven folder)
      val remoteUrl = baseUri.resolve(MetadataRepository.jsonSubPath(dep, version).segments0.mkString("/")).toString
      // We have complete control over the json metadata files, so for a fixed
      // version, they never change and therefore can be cached indefinitely
      val jsonArtifact = Artifact(remoteUrl).withChanging(false)

      fetch(jsonArtifact).flatMap((jsonStr: String) => EitherT.fromEither {
        JsonIo.readBlocking[A](jsonStr, errMsg = remoteUrl)
      })
    }
  }

  def iterateChannelContents: Iterator[JD.ChannelItem] = channelData.contents.iterator
}

/** This repository is read from a single YAML file. All its data is held in memory.
  */
private class YamlRepository(
  baseUri: java.net.URI,
  channelData: Map[BareDep, Map[String, JD.PackageAsset]]  // name -> version -> json
  // globalVariant: Variant
) extends MetadataRepository(baseUri/*, globalVariant*/) {

  def getRawVersions(dep: BareDep): Seq[String] = {
    channelData.get(dep).map(_.keys.toSeq).getOrElse(Seq.empty)
  }

  def fetchModuleJson[F[_] : Monad, A <: JD.PackageAsset : Reader](module: Module, version: String, fetch: Repository.Fetch[F]): EitherT[F, ErrStr, A] = {
    val dep = CoursierUtil.bareDepFromModule(module)
    channelData.get(dep).flatMap(_.get(version)) match {
      case None => EitherT.fromEither(Left(s"no versions of $module found in repository $baseUri"))
      case Some(pkgData: JD.PackageAsset) =>
        EitherT.point(pkgData.asInstanceOf[A])  // as long as we do not mix up Assets and Packages, casting should not be an issue (could be fixed using type classes)
    }
  }

  // We pick the latest scheme version as we do not have any other input to work with.
  // If the scheme has been updated, then the yaml files have probably already failed to parse.
  lazy private val channel = JD.Channel.create(scheme = Constants.channelSchemeVersions.max, channelData)

  def iterateChannelContents: Iterator[JD.ChannelItem] = channel.contents.iterator
}
