package io.github.memo33
package sc4pac

import zio.*
import zio.test.*
import upickle.default as UP
import ujson.Obj

import JsonData as JD

object JsonDataSpecZIO extends ZIOSpecDefault {

  val profileLayer = zio.ZLayer.succeed(ProfileRoot(os.pwd / "target" / "profiles" / "1"))

  def dummyFileSystem(overrideDirs: Boolean, env: Map[String, String] = Map.empty) = {
    val env0 = env
    zio.ZLayer.succeed(new service.FileSystem {
      override def projectCacheDir = if (overrideDirs) "null/cache" else super.projectCacheDir
      override def projectConfigDir = if (overrideDirs) "null/config" else super.projectConfigDir
      override def readEnvVar(name: String) = env0.get(name)
    })
  }

  val customEnv = Map("SC4PAC_PROFILES_DIR" -> (os.pwd / "target" / "profiles2").toString)

  def spec = suite("JsonDataSpecZIO")(

    suite("project directories")(
      test("profiles dir") {
        for {
          p1 <- JD.Profiles.parseProfilesRoot(Some("target/profiles"))
          p2 <- JD.Profiles.parseProfilesRoot(Some("target/profiles"))
            .provideSomeLayer(dummyFileSystem(overrideDirs = false, env = customEnv))
          p3 <- JD.Profiles.parseProfilesRoot(None)
            .provideSomeLayer(dummyFileSystem(overrideDirs = false, env = customEnv))
          p4 <- JD.Profiles.parseProfilesRoot(None)
        } yield assertTrue(
          p1 == os.pwd / "target" / "profiles",
          p2 == os.pwd / "target" / "profiles",
          p3 == os.pwd / "target" / "profiles2",
          p4.isInstanceOf[os.Path], p4.last == "profiles",
        )
      },
      test("cache dir") {
        for {
          p1 <- JD.PluginsSpec.defaultCacheRoot.map(_.head)
        } yield assertTrue(
          p1.isInstanceOf[os.Path],
          p1 != os.home / "sc4pac" / "cache",
        )
      }.provideSomeLayer(profileLayer),
    ).provideSomeLayer(dummyFileSystem(overrideDirs = false)),

    // see https://github.com/memo33/sc4pac-gui/issues/25
    suite("project directories (fault injection)")(
      test("profiles dir") {
        for {
          p1 <- JD.Profiles.parseProfilesRoot(Some("target/profiles"))
          p2 <- JD.Profiles.parseProfilesRoot(Some("target/profiles"))
            .provideSomeLayer(dummyFileSystem(overrideDirs = true, env = customEnv))
          p3 <- JD.Profiles.parseProfilesRoot(None)
            .provideSomeLayer(dummyFileSystem(overrideDirs = true, env = customEnv))
          p4 <- JD.Profiles.parseProfilesRoot(None).either
          p5 <- JD.Profiles.parseProfilesRoot(None)
            .provideSomeLayer(dummyFileSystem(overrideDirs = true, env = Map("APPDATA" -> (os.pwd / "target" / "appdata-roaming").toString)))
        } yield assertTrue(
          p1 == os.pwd / "target" / "profiles",
          p2 == os.pwd / "target" / "profiles",
          p3 == os.pwd / "target" / "profiles2",
          p4.isLeft, p4.left.toOption.exists(_.isInstanceOf[error.ObtainingUserDirsFailed]),
          p5 == os.pwd / "target" / "appdata-roaming" / "io.github.memo33" / "sc4pac" / "config" / "profiles",
        )
      },
      test("cache dir") {
        for {
          p1 <- JD.PluginsSpec.defaultCacheRoot.map(_.head)
          p2 <- JD.PluginsSpec.defaultCacheRoot.map(_.head)
            .provideSomeLayer(dummyFileSystem(overrideDirs = true, env = Map("LOCALAPPDATA" -> (os.pwd / "target" / "appdata-local").toString)))
        } yield assertTrue(
          p1.isInstanceOf[os.Path],
          p1 == os.home / "sc4pac" / "cache",
          p2 == os.pwd / "target" / "appdata-local" / "io.github.memo33" / "sc4pac" / "cache",
        )
      }.provideSomeLayer(profileLayer),
    ).provideSomeLayer(dummyFileSystem(overrideDirs = true)),

  ) @@ TestAspect.sequential @@ TestAspect.withLiveClock @@ TestAspect.timeout(30.seconds)
}
