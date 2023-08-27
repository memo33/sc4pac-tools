name := "sc4pac"

organization := "io.github.memo33"

version := "0.1.0"

// ThisBuild / versionScheme := Some("early-semver")

description := "Package manager for SimCity 4 plugins"

licenses += ("GPL-3.0-only", url("https://spdx.org/licenses/GPL-3.0-only.html"))

scalaVersion := "3.3.0"

scalacOptions ++= Seq(
  "-unchecked",
  "-deprecation",
  "-feature",
  // "-opt-warnings:at-inline-failed-summary",
  // "-opt:l:inline", "-opt-inline-from:<sources>",
  "-source:future",
  "-encoding", "UTF-8",
  "-release:8")

javacOptions ++= Seq("--release", "8")

console / initialCommands := """
import io.github.memo33.sc4pac.*
lazy val pacman = unsafeRun(Data.PluginsData.readOrInit.flatMap(data => Sc4pac.init(data.config)))
import zio.{ZIO, IO, Task}
"""

// make build info available in source files
lazy val root = (project in file(".")).
  enablePlugins(BuildInfoPlugin).
  settings(
    buildInfoKeys := Seq[BuildInfoKey](name, organization, version, scalaVersion, sbtVersion, licenses),
    buildInfoPackage := "io.github.memo33.sc4pac.cli"
  )


Compile / run / fork := true

Compile / mainClass := Some("io.github.memo33.sc4pac.cli.CliMain")

// Create a large executable jar with `sbt assembly`.
assembly / assemblyJarName := s"${name.value}-cli.jar"

// Avoid deduplicate conflict between plexus-archiver-4.8.0.jar and plexus-io-3.4.1.jar
// as well as slf4j-api-2.0.7.jar, slf4j-nop-2.0.7.jar, xz-1.9.jar
assembly / assemblyMergeStrategy := {
  case PathList("META-INF", xs @ _*) =>  // from https://stackoverflow.com/a/74212770
    (xs map {_.toLowerCase}) match {
      case "services" :: xs =>
        MergeStrategy.filterDistinctLines
      case _ => MergeStrategy.discard
    }
  case x => MergeStrategy.first
}


libraryDependencies += "io.get-coursier" %% "coursier" % "2.1.2" cross CrossVersion.for3Use2_13  // resolver, not yet available for scala 3

libraryDependencies += "dev.zio" %% "zio-nio" % "2.0.1" exclude("org.scala-lang.modules", "scala-collection-compat_3")  // solves version conflict with coursier (compat package is empty in both 2.13 and 3 anyway)

libraryDependencies += "dev.zio" %% "zio" % "2.0.15"  // IO

libraryDependencies += "com.lihaoyi" %% "os-lib" % "0.9.1"  // file system utilities

libraryDependencies += "com.lihaoyi" %% "upickle" % "3.1.2"  // json serialization

libraryDependencies += "com.lihaoyi" %% "ujson-circe" % "3.1.2"  // conversion from circe-yaml/circe-json to ujson

libraryDependencies += "io.circe" %% "circe-yaml" % "0.14.2"  // or circle-yaml-v12 for yaml 1.2?

libraryDependencies += "org.codehaus.plexus" % "plexus-archiver" % "4.8.0"  // zip extraction

libraryDependencies += "org.slf4j" % "slf4j-nop" % "2.0.7"  // ignore logging in plexus-archiver

libraryDependencies += "com.github.alexarchambault" %% "case-app" % "2.1.0-M25"  // command-line app helper

libraryDependencies += "dev.dirs" % "directories" % "26"  // platform-specific location of cache, temp, config, etc.
