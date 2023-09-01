name := "sc4pac"

organization := "io.github.memo33"

version := "0.1.3-SNAPSHOT"

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

// To avoid the following merge failures, we explicitly handle those respective
// files from the uber-jar. Other files in META-INF must be preserved though, in
// particular native libraries such as jansi.dll/libjansi.so in META-INF/native/.
//
//     Deduplicate found different file contents in the following:
//       Jar name = slf4j-api-2.0.7.jar, jar org = org.slf4j, entry target = META-INF/versions/9/module-info.class
//       Jar name = slf4j-nop-2.0.7.jar, jar org = org.slf4j, entry target = META-INF/versions/9/module-info.class
//       Jar name = xz-1.9.jar, jar org = org.tukaani, entry target = META-INF/versions/9/module-info.class
//     Deduplicate found different file contents in the following:
//       Jar name = plexus-archiver-4.8.0.jar, jar org = org.codehaus.plexus, entry target = META-INF/sisu/javax.inject.Named
//       Jar name = plexus-io-3.4.1.jar, jar org = org.codehaus.plexus, entry target = META-INF/sisu/javax.inject.Named
//
assembly / assemblyMergeStrategy := {
  case p0 @ PathList("META-INF", xs @ _*) =>
    (xs.map(_.toLowerCase)) match {
      case p1 if p1.last == "module-info.class" => MergeStrategy.discard
      case p1 if p1.last == "javax.inject.named" => MergeStrategy.filterDistinctLines
      case _ => MergeStrategy.defaultMergeStrategy(p0)
    }
  case p0 => MergeStrategy.defaultMergeStrategy(p0)
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

libraryDependencies += "org.fusesource.jansi" % "jansi" % "2.4.0"  // color support
