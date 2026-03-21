package io.github.memo33
package sc4pac
package api

import zio.{ZIO, IO, Task, RIO}
import sc4pac.{JsonData => JD}


/** A repository service for profiles */
class ProfileRepo(profileStorage: MultiProfileStorage) {

  def initProfile(profile: Profile, args: InitArgs): IO[ErrorMessage.InitNotAllowed | Throwable, JD.Config] = {
    for {
      _           <- profileStorage.profileFilesExist(profile)
                       .filterOrFail(_ == false)(
                         ErrorMessage.InitNotAllowed("Profile already initialized.",
                           "Manually delete the corresponding .json files if you are sure you want to initialize a new profile.")
                       )
      pluginsRoot =  os.Path(args.plugins, profile.root)
      cacheRoot   =  os.Path(args.cache, profile.root)
      tempRoot    =  os.FilePath(args.temp)
      _           <- profileStorage.makeProfileFolders(profile, pluginsRoot = pluginsRoot, cacheRoot = cacheRoot)
      pluginsSpec =  JD.PluginsSpec(profile, pluginsRoot = pluginsRoot, cacheRoot = cacheRoot, tempRoot = tempRoot)
      _           <- profileStorage.saveSpec(profile, pluginsSpec)
    } yield pluginsSpec.config
  }

  // only for use in API, not CLI, in particular to ensure only profile roots constructed by ID can be deleted via API
  def profileFromId(id: String): zio.UIO[Profile] = profileStorage.profileFromId(id)

  def listProfiles(includePlugins: Boolean): RIO[ProfilesDir, ProfilesList] =
    for {
      profiles     <- profileStorage.loadProfilesOrInit
      profilesData <- if (!includePlugins)
                        ZIO.succeed(profiles.profiles.map(p => ProfilesList.ProfileData2(id = p.id, name = p.name)))
                      else
                        ZIO.foreachPar(profiles.profiles) { p =>
                          for {
                            profile <- profileFromId(p.id)
                            pluginsSpecOpt <- profileStorage.loadSpecMaybe(profile)
                          } yield ProfilesList.ProfileData2(id = p.id, name = p.name, pluginsRoot = pluginsSpecOpt.map(_.config.pluginsRoot).orNull)
                        }
      profilesDir  <- ZIO.service[ProfilesDir]  // (ideally, remove this from environment of the service)
    } yield ProfilesList(
      profiles = profilesData,
      currentProfileId = profiles.currentProfileId,
      profilesDir = profilesDir.path.toNIO,
    )

  def add(name: String): Task[JD.ProfileData] =
    for {
      ps        <- profileStorage.loadProfilesOrInit
      (ps2, p)  = ps.add(name)
      _         <- profileStorage.saveProfiles(ps2)
    } yield p

  def removeMaybe(id: String): Task[Option[Unit]] =
    for {
      data   <- profileStorage.loadProfilesOrInit
      idx    <- ZIO.succeed(data.profiles.indexWhere(_.id == id))
      found  <- ZIO.when(idx != -1)(for {
                  profile <- profileFromId(data.profiles(idx).id)
                  _      <- profileStorage.deleteProfileFolder(profile)
                  ps2    =  data.profiles.patch(from = idx, Nil, replaced = 1)
                  data2  =  data.copy(
                              profiles = ps2,
                              currentProfileId = data.currentProfileId.filter(_ != id).orElse(ps2.lastOption.map(_.id)),
                            )
                  _      <- profileStorage.updateProfiles(next = data2, previous = data)
                } yield ())
    } yield found

  def switchMaybe(id: String): Task[Option[Unit]] =
    for {
      data   <- profileStorage.loadProfilesOrInit
      found  <- ZIO.when(data.profiles.exists(_.id == id)) {
                  ZIO.unlessDiscard(data.currentProfileId.contains(id)) {
                    val ps2 = data.copy(currentProfileId = Some(id))
                    profileStorage.updateProfiles(next = ps2, previous = data)
                  }
                }
    } yield found

  def renameMaybe(id: String, newName: String): Task[Option[Unit]] =
    for {
      data   <- profileStorage.loadProfilesOrInit
      found  <- ZIO.when(data.profiles.exists(_.id == id)) {
                  val data2 = data.copy(profiles = data.profiles.map(p => if (p.id == id) p.copy(name = newName) else p))
                  profileStorage.updateProfiles(next = data2, previous = data)
                }
    } yield found

  def loadSettings: Task[ujson.Value] =
    profileStorage.loadProfilesOrInit.map(_.settings)

  def saveSettings(newSettings: ujson.Value): Task[Unit] =
    for {
      ps  <- profileStorage.loadProfilesOrInit
      _   <- profileStorage.saveProfiles(ps.copy(settings = newSettings))
    } yield ()

}

object ProfileRepo {
  val live: zio.URLayer[MultiProfileStorage, ProfileRepo] = zio.ZLayer.fromFunction(ProfileRepo.apply)
}
