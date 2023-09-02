package io.github.memo33
package sc4pac

import coursier.core.{Repository, Module, Publication, ArtifactSource, Versions, Version,
  Dependency, Project, Classifier, Extension, Type, Configuration, ModuleName, Organization, Info}
import coursier.util.{Artifact, EitherT, Monad, Task}
import upickle.default.{read, macroRW, ReadWriter, Reader, writeTo}
import zio.{ZIO, IO}

import sc4pac.error.*
import sc4pac.Constants.isSc4pacAsset
import sc4pac.Data.*

case class MetadataRepository(baseUri: String, channelData: ChannelData, globalVariant: Variant) extends Repository {

  /** Reads the repository's channel contents to obtain all available versions
    * of modules.
    */
  override def fetchVersions[F[_] : Monad](module: Module, fetch: Repository.Fetch[F]): EitherT[F, ErrStr, (Versions, String)] = {
    EitherT.fromEither {
      channelData.versions.get(module.withAttributes(Map.empty)) match {
        case Some(rawVersions) if rawVersions.nonEmpty =>
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
                 MetadataRepository.channelContentsUrl(baseUri)))
        case _ => Left(s"no versions of $module found in repository $baseUri")
      }
    }
  }

  /** For a module (no asset, no variant) of a given version, fetch the
    * corresponding `PackageData` contained in its json file.
    *
    * A = PackageData or AssetData
    */
  def fetchModuleJson[F[_] : Monad, A : Reader](module: Module, version: String, fetch: Repository.Fetch[F]): EitherT[F, ErrStr, A] = {
    // TODO use flatter directory (remove version folder, rename maven folder)
    val remoteUrl = s"$baseUri/metadata/" + MetadataRepository.jsonSubPath(module.organization.value, module.name.value, version).segments0.mkString("/")
    // We have complete control over the json metadata files, so for a fixed
    // version, they never change and therefore can be cached indefinitely
    val jsonArtifact = Artifact(remoteUrl).withChanging(false)

    fetch(jsonArtifact).flatMap((jsonStr: String) => EitherT.fromEither {
      Data.readJson[A](jsonStr, errMsg = remoteUrl)
    })
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
      fetchModuleJson[F, AssetData](module, version, fetch)
        .flatMap { data => EitherT.point((this, data.toProject)) }
    } else {
      fetchModuleJson[F, PackageData](module, version, fetch)
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
      val lastModifiedOpt: Option[java.time.Instant] = mod.attributes.get(Constants.lastModifiedKey).flatMap(AssetData.parseLastModified)
      Seq((pub, MetadataRepository.createArtifact(url, lastModifiedOpt)))
    }

  }
}

object MetadataRepository {
  val channelContentsFilename = "sc4pac-channel-contents.json"
  def channelContentsUrl(baseUri: String) = s"$baseUri/$channelContentsFilename"

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
