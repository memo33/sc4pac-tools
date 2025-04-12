package io.github.memo33
package sc4pac
package service


trait FileSystem {

  private lazy val projDirs = dev.dirs.ProjectDirectories.from("", cli.BuildInfo.organization, cli.BuildInfo.name)  // qualifier, organization, application
  def projectConfigDir: String = projDirs.configDir
  def projectCacheDir: String = projDirs.cacheDir

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
  }

}
object FileSystem {
  val live: zio.ULayer[FileSystem] = zio.ZLayer.succeed(new FileSystem {})
}
