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
import sc4pac.Resolution.BareDep

case class MetadataRepository(baseUri: java.net.URI, channelData: JD.Channel, globalVariant: Variant) extends Repository {

  private def getRawVersions(dep: BareDep): Seq[String] = {
    channelData.versions.get(dep).getOrElse(Seq.empty)
  }

  // This only works for concrete versions (so not for "latest.release").
  // The assumption is that all methods of MetadataRepository are only called with concrete versions.
  private def containsVersion(dep: BareDep, version: String): Boolean = {
    getRawVersions(dep).exists(_ == version)
  }

  /** Reads the repository's channel contents to obtain all available versions
    * of modules.
    */
  override def fetchVersions[F[_] : Monad](module: Module, fetch: Repository.Fetch[F]): EitherT[F, ErrStr, (Versions, String)] = {
    EitherT.fromEither {
      val rawVersions = getRawVersions(BareDep.fromModule(module))
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
    *
    * A = JD.Package or JD.Asset
    */
  def fetchModuleJson[F[_] : Monad, A : Reader](module: Module, version: String, fetch: Repository.Fetch[F]): EitherT[F, ErrStr, A] = {
    if (!containsVersion(BareDep.fromModule(module), version)) {
      EitherT.fromEither(Left(s"no versions of $module found in repository $baseUri"))
    } else {
      // TODO use flatter directory (remove version folder, rename maven folder)
      val remoteUrl = baseUri.resolve("metadata/" + MetadataRepository.jsonSubPath(module.organization.value, module.name.value, version).segments0.mkString("/")).toString
      // We have complete control over the json metadata files, so for a fixed
      // version, they never change and therefore can be cached indefinitely
      val jsonArtifact = Artifact(remoteUrl).withChanging(false)

      fetch(jsonArtifact).flatMap((jsonStr: String) => EitherT.fromEither {
        JsonIo.readBlocking[A](jsonStr, errMsg = remoteUrl)
      })
    }
  }

  /** For a module of a given version, find its metadata (parsed from its json
    * file) and return it as a `Project` (Coursier's representation of package
    * metadata, which only mirrors information of Maven pom files, so does not
    * contain all the information we need for installation, but contains enough
    * information for Coursier to do resolutions). An `ArtifactSource` is the
    * corresponding `Repository`.
    */
  def find[F[_] : Monad](module: Module, version: String, fetch: Repository.Fetch[F]): EitherT[F, ErrStr, (ArtifactSource, Project)] = {
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
  }

  def artifacts(dependency: Dependency, project: Project, overrideClassifiers: Option[Seq[Classifier]]): Seq[(Publication, Artifact)] = {
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

  }
}

object MetadataRepository {
  val channelContentsFilename = "sc4pac-channel-contents.json"
  def channelContentsUrl(baseUri: java.net.URI): java.net.URI = baseUri.resolve(channelContentsFilename)

  /** Sanitize URL.
    */
  def parseChannelUrl(url: String): Either[ErrStr, java.net.URI] = try {
    val uri = new java.net.URI(url)
    if (uri.getScheme == null) Left(s"channel URL must start with https://, http:// or file:// ($uri)")  // coursier-cache requirement
    else if (uri.getRawQuery != null) Left(s"channel URL must not have query: $uri")
    else if (uri.getRawFragment != null) Left(s"channel URL must not have fragment: $uri")
    else if (uri.getPath.endsWith("/")) {
      Right(uri)
    } else {
      Right(java.net.URI.create(url + "/"))  // add a slash for proper subdirectory resolution
    }
  } catch {
    case e: java.net.URISyntaxException => Left(e.getMessage)
  }

  def jsonSubPath(group: String, name: String, version: String): os.SubPath = {
    os.SubPath(s"${group}/${name}/${version}/${name}-${version}.json")
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
