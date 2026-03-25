package io.github.memo33
package sc4pac

import zio.{ZIO, IO, Task, RIO}
import sc4pac.{JsonData => JD}


/** A persistence service for profiles */
trait ProfileStorage {
  def loadSpec(profile: Profile): IO[error.ReadingProfileFailed, JD.PluginsSpec]

  /** Reads the PluginsSpec JSON file if it exists, otherwise returns None */
  def loadSpecMaybe(profile: Profile): IO[error.ReadingProfileFailed, Option[JD.PluginsSpec]]

  /** Read PluginsSpec from file if it exists, else create it and write it to file. */
  def loadSpecOrInitWith[R](profile: Profile)(init: RIO[R, JD.PluginsSpec]): ZIO[R, error.ReadingProfileFailed, JD.PluginsSpec]

  def saveSpec(profile: Profile, spec: JD.PluginsSpec): Task[Unit]

  def modifySpecSync(profile: Profile)(f: JD.PluginsSpec => JD.PluginsSpec): Task[Unit] =
    loadSpec(profile).flatMap(spec => saveSpec(profile, f(spec)))

  def loadLock(profile: Profile): IO[error.ReadingProfileFailed, JD.PluginsLock]

  def loadLockMaybe(profile: Profile): IO[error.ReadingProfileFailed, Option[JD.PluginsLock]]

  /** Read PluginsLock from file if it exists, else create it and write it to file.
    * Does *not* automatically upgrade from scheme 1.*/
  def loadLockOrInit(profile: Profile): IO[error.ReadingProfileFailed, JD.PluginsLock]

  /** `previous` is expected state before writing */
  def updateLock[R, A](profile: Profile, next: JD.PluginsLock, previous: JD.PluginsLock)(action: RIO[R, A]): RIO[R, A]
}

object ProfileStorage {
  val live: zio.ULayer[ProfileStorage] = zio.ZLayer.succeed(Impl())  // for cli

  class Impl extends ProfileStorage {
    def specPath(profile: Profile): os.Path = profile.root / "sc4pac-plugins.json"
    def lockPath(profile: Profile): os.Path = profile.root / "sc4pac-plugins-lock.json"

    override def loadSpec(profile: Profile) = {
      val pluginsPath = specPath(profile)
      JsonIo.read[JD.PluginsSpec](pluginsPath)
        .mapError(e => error.ReadingProfileFailed(
          s"Failed to read profile JSON file ${pluginsPath.last}.",
          f"Make sure the file is correctly formatted: $pluginsPath.%n$e"),
        )
    }

    override def loadSpecMaybe(profile: Profile): IO[error.ReadingProfileFailed, Option[JD.PluginsSpec]] = {
      val pluginsPath = specPath(profile)
      val task: IO[ErrStr | java.io.IOException, Option[JD.PluginsSpec]] =
        ZIO.ifZIO(ZIO.attemptBlockingIO(os.exists(pluginsPath)))(
          onFalse = ZIO.succeed(None),
          onTrue = ZIO.attemptBlockingIO(JsonIo.readBlocking[JD.PluginsSpec](pluginsPath)).absolve.map(Some(_))
        )
      task.mapError(e => error.ReadingProfileFailed(
        s"Failed to read profile JSON file ${pluginsPath.last}.",
        f"Make sure the file is correctly formatted: $pluginsPath.%n$e"),
      )
    }

    override def loadSpecOrInitWith[R](profile: Profile)(init: RIO[R, JD.PluginsSpec]) = {
      val pluginsPath = specPath(profile)
      ZIO.ifZIO(ZIO.attemptBlocking(os.exists(pluginsPath)))(
        onTrue = JsonIo.read[JD.PluginsSpec](pluginsPath),
        onFalse = for {
          spec <- init
          _    <- JsonIo.write(pluginsPath, spec, None)(ZIO.unit)
        } yield spec
      )
      .mapError(e => error.ReadingProfileFailed(
        s"Failed to read profile JSON file ${pluginsPath.last}.",
        f"Make sure the file is correctly formatted: $pluginsPath.%n${e.getMessage}"),
      )
    }

    override def saveSpec(profile: Profile, spec: JD.PluginsSpec) =
      JsonIo.write(specPath(profile), spec, None)(ZIO.unit)

    override def loadLock(profile: Profile) = {
      val pluginsLockPath = lockPath(profile)
      JsonIo.read[JD.PluginsLock](pluginsLockPath)
        .mapError(e => error.ReadingProfileFailed(
          s"Failed to read profile lock file ${pluginsLockPath.last}.",
          f"Make sure the file is correctly formatted: $pluginsLockPath.%n${e.getMessage}"),
        )
    }

    override def loadLockMaybe(profile: Profile) = {
      val pluginsLockPath = lockPath(profile)
      val task: IO[ErrStr | java.io.IOException, Option[JD.PluginsLock]] =
        ZIO.ifZIO(ZIO.attemptBlockingIO(os.exists(pluginsLockPath)))(
          onFalse = ZIO.succeed(None),
          onTrue = ZIO.attemptBlockingIO(JsonIo.readBlocking[JD.PluginsLock](pluginsLockPath)).absolve.map(Some(_))
        )
      task.mapError(e => error.ReadingProfileFailed(
        s"Failed to read profile lock file ${pluginsLockPath.last}.",
        f"Make sure the file is correctly formatted: $pluginsLockPath.%n$e"),
      )
    }

    override def loadLockOrInit(profile: Profile): IO[error.ReadingProfileFailed, JD.PluginsLock] = {
      val pluginsLockPath = lockPath(profile)
      ZIO.ifZIO(ZIO.attemptBlocking(os.exists(pluginsLockPath)))(
        onTrue = JsonIo.read[JD.PluginsLock](pluginsLockPath),
        onFalse = {
          val data = JD.PluginsLock(Constants.pluginsLockScheme, Seq.empty, Seq.empty)
          JsonIo.write(pluginsLockPath, data, None)(ZIO.succeed(data))
        }
      )
      .mapError(e => error.ReadingProfileFailed(
        s"Failed to read profile lock file ${pluginsLockPath.last}.",
        f"Make sure the file is correctly formatted: $pluginsLockPath.%n${e.getMessage}"),
      )
    }

    override def updateLock[R, A](profile: Profile, next: JD.PluginsLock, previous: JD.PluginsLock)(action: RIO[R, A]) =
      JsonIo.write(lockPath(profile), next, Some(previous))(action)
  }
}

package api {

  trait MultiProfileStorage extends ProfileStorage {
    def loadProfilesOrInit: IO[error.ReadingProfileFailed, JD.Profiles]
    def saveProfiles(profiles: JD.Profiles): Task[Unit]
    def updateProfiles(next: JD.Profiles, previous: JD.Profiles): Task[Unit]
    def deleteProfileFolder(profile: Profile): IO[java.io.IOException, Unit]
    def profileFilesExist(profile: Profile): IO[java.io.IOException, Boolean]
    def makeProfileFolders(profile: Profile, pluginsRoot: os.Path, cacheRoot: os.Path): IO[java.io.IOException, Unit]
    def profileFromId(id: String): zio.UIO[Profile]  // only for use in API, not CLI, in particular to ensure only profile roots constructed by ID can be deleted via API
  }

  object MultiProfileStorage {
    val live: zio.URLayer[ProfilesDir, MultiProfileStorage] = zio.ZLayer.fromFunction(Impl.apply)  // for api

    class Impl(profilesDir: ProfilesDir) extends ProfileStorage.Impl with MultiProfileStorage {
      lazy val profilesJsonPath: os.Path = profilesDir.path / "sc4pac-profiles.json"

      override def loadProfilesOrInit = {
        val jsonPath = profilesJsonPath
        ZIO.ifZIO(ZIO.attemptBlocking(os.exists(jsonPath)))(
          onTrue = JsonIo.read[JD.Profiles](jsonPath),
          onFalse = ZIO.succeed(JD.Profiles(Seq.empty, None))
        )
        .mapError(e => error.ReadingProfileFailed(
          s"Failed to read profiles file ${jsonPath.last}.",
          f"Make sure the file is correctly formatted: $jsonPath.%n${e.getMessage}"),
        )
      }

      private def ensureDirExists(path: os.Path): Task[Unit] =
        ZIO.attemptBlockingIO { if (!os.exists(path)) os.makeDir.all(path) }

      override def saveProfiles(profiles: JD.Profiles): Task[Unit] = {
        val jsonPath = profilesJsonPath
        ensureDirExists(jsonPath / os.up) *>
        JsonIo.write(jsonPath, profiles, None)(ZIO.unit)
      }

      override def updateProfiles(next: JD.Profiles, previous: JD.Profiles): Task[Unit] =
        JsonIo.write(profilesJsonPath, next, Some(previous))(ZIO.unit)

      override def deleteProfileFolder(profile: Profile) =
        ZIO.whenZIODiscard(ZIO.attemptBlockingIO(os.exists(profile.root))) {
          // logger.logZIO(s"Deleting profile directory: ${profile.root}") *>  // TODO add Logger dependency
          ZIO.attemptBlockingIO {
            for (path <- os.list.stream(profile.root).find(os.isDir)) {
              // sanity check (profile was created by GUI, so should only contain two .json files)
              throw java.nio.file.AccessDeniedException(s"Failed to remove profile directory as it contains unexpected subdirectories: $path")
            }
            Sc4pac.removeAllForcibly(profile.root)
          }
        }

      override def profileFilesExist(profile: Profile) =
        ZIO.attemptBlockingIO(os.exists(specPath(profile)) || os.exists(lockPath(profile)))

      override def makeProfileFolders(profile: Profile, pluginsRoot: os.Path, cacheRoot: os.Path) =
        ZIO.attemptBlockingIO {
          os.makeDir.all(profile.root)
          os.makeDir.all(pluginsRoot)  // TODO ask for confirmation?
          os.makeDir.all(cacheRoot)
        }

      override def profileFromId(id: String) = ZIO.succeed(Profile(profilesDir.path / id))
    }
  }
}
