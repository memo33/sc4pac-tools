package io.github.memo33
package sc4pac
package service


trait FileSystem {

  private lazy val projDirs = scala.util.Try(dev.dirs.ProjectDirectories.from("", cli.BuildInfo.organization, cli.BuildInfo.name))  // qualifier, organization, application
  def projectConfigDir: String = projDirs.get.configDir
  def projectCacheDir: String = projDirs.get.cacheDir

  def readEnvVar(name: String): Option[String] =
    Option(System.getenv(name)).filter(_.nonEmpty)
      .map { s =>
        if (s.startsWith("\"") || s.endsWith("\"")) {
          System.err.println(s"Warning: $name should not be surrounded by quotes. Remove the quotes and try again.")
        }
        s
      }

  object env {
    /** If not specified as command-line argument, the profiles directory can be set as environment variable. */
    lazy val sc4pacProfilesDir: Option[String] = readEnvVar("SC4PAC_PROFILES_DIR")
    /** Personal access token for authenticating to Simtropolis. */
    lazy val simtropolisToken: Option[String] = readEnvVar("SC4PAC_SIMTROPOLIS_TOKEN")
    /** Used in case ProjectDirectories fails to find %appdata% directory. */
    lazy val appDataWindows: Option[String] = readEnvVar("APPDATA")  // TODO use only on Windows
    /** Used in case ProjectDirectories fails to find %localappdata% directory. */
    lazy val localAppDataWindows: Option[String] = readEnvVar("LOCALAPPDATA")  // TODO use only on Windows
  }

  def injectErrorInTest: zio.Task[Unit] = zio.ZIO.unit

}
object FileSystem {
  val live: zio.ULayer[FileSystem] = zio.ZLayer.succeed(new FileSystem {})
}
