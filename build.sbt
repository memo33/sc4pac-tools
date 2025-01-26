name := "sc4pac"

ThisBuild / organization := "io.github.memo33"

ThisBuild / version := "0.5.3"

// ThisBuild / versionScheme := Some("early-semver")

description := "Package manager for SimCity 4 plugins"

ThisBuild / licenses += ("GPL-3.0-only", url("https://spdx.org/licenses/GPL-3.0-only.html"))

ThisBuild / scalaVersion := "3.4.2"

ThisBuild / scalacOptions ++= Seq(
  // "-Wunused:imports",
  "-unchecked",
  "-deprecation",
  "-feature",
  // "-opt-warnings:at-inline-failed-summary",
  // "-opt:l:inline", "-opt-inline-from:<sources>",
  "-Wvalue-discard",
  "-source:future",
  "-encoding", "UTF-8",
  "-release:11")

ThisBuild / javacOptions ++= Seq("--release", "11")

console / initialCommands := """
import io.github.memo33.sc4pac.*
lazy val pacman = unsafeRun(JsonData.Plugins.readOrInit.flatMap(data => Sc4pac.init(data.config)).provideLayer(cli.Commands.cliLayer))
import zio.{ZIO, IO, Task}
"""

// make build info available in source files
lazy val root = (project in file("."))
  .dependsOn(shared.jvm)
  .enablePlugins(BuildInfoPlugin)
  .settings(
    buildInfoKeys := Seq[BuildInfoKey](name, organization, version, scalaVersion, sbtVersion, licenses),
    buildInfoPackage := "io.github.memo33.sc4pac.cli"
  )


Compile / run / fork := true
Test / test / fork := true  // necessary for class loading of native 7z library, see https://stackoverflow.com/questions/44483924/how-to-properly-load-a-native-library-for-sbt-tests

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
    (xs.map(_.toLowerCase(java.util.Locale.ENGLISH))) match {
      case p1 if p1.last == "module-info.class" => MergeStrategy.discard
      case p1 if p1.last == "javax.inject.named" => MergeStrategy.filterDistinctLines
      case p1 if p1.last == "io.netty.versions.properties" => MergeStrategy.filterDistinctLines
      case _ => MergeStrategy.defaultMergeStrategy(p0)
    }
  case p0 => MergeStrategy.defaultMergeStrategy(p0)
}


val coursierVersion = "2.1.2"
libraryDependencies += "io.get-coursier" %% "coursier" % coursierVersion cross CrossVersion.for3Use2_13  // resolver, not yet available for scala 3

libraryDependencies += "dev.zio" %% "zio-nio" % "2.0.2" exclude("org.scala-lang.modules", "scala-collection-compat_3")  // solves version conflict with coursier (compat package is empty in both 2.13 and 3 anyway)

libraryDependencies += "dev.zio" %% "zio" % "2.1.12"  // IO

libraryDependencies += "com.lihaoyi" %% "os-lib" % "0.11.1"  // file system utilities

libraryDependencies += "com.lihaoyi" %% "upickle" % "3.1.2"  // json serialization

libraryDependencies += "com.lihaoyi" %% "ujson-circe" % "3.1.2"  // conversion from circe-yaml/circe-json to ujson

libraryDependencies += "io.circe" %% "circe-yaml" % "0.14.2"  // or circle-yaml-v12 for yaml 1.2?

libraryDependencies += "org.apache.commons" % "commons-compress" % "1.23.0"  // zip extraction

libraryDependencies += "com.github.alexarchambault" %% "case-app" % "2.1.0-M25"  // command-line app helper

libraryDependencies += "dev.dirs" % "directories" % "26"  // platform-specific location of cache, temp, config, etc.

libraryDependencies += "org.fusesource.jansi" % "jansi" % "2.4.1"  // color support

libraryDependencies += "me.xdrop" % "fuzzywuzzy" % "1.4.0"  // fuzzy search

libraryDependencies += "net.sf.sevenzipjbinding" % "sevenzipjbinding" % "16.02-2.01"  // native 7z for NSIS extraction

// libraryDependencies += "net.sf.sevenzipjbinding" % "sevenzipjbinding-all-platforms" % "16.02-2.01"  // native 7z for NSIS extraction

// https://www.mucommander.com/maven/com/mucommander/sevenzipjbinding-all-platforms/16.02-2.01/sevenzipjbinding-all-platforms-16.02-2.01.pom
// https://www.mucommander.com/maven/com/mucommander/sevenzipjbinding-all-platforms/16.02-2.01/sevenzipjbinding-all-platforms-16.02-2.01.jar
// resolvers += "mucommander internal Maven repository" at "https://www.mucommander.com/maven/"
libraryDependencies += ("com.mucommander" % "sevenzipjbinding-all-platforms" % "16.02-2.01" // patched by muCommander for Apple M1 support
  from "https://www.mucommander.com/maven/com/mucommander/sevenzipjbinding-all-platforms/16.02-2.01/sevenzipjbinding-all-platforms-16.02-2.01.jar")

libraryDependencies += "dev.zio" %% "zio-http" % "3.0.1" exclude("org.scala-lang.modules", "scala-collection-compat_3")  // server

libraryDependencies += "org.slf4j" % "slf4j-nop" % "2.0.7"  // ignore logging in zio-http/netty

libraryDependencies += "org.scalatest" %% "scalatest" % "3.2.17" % Test exclude("org.scala-lang.modules", "scala-xml_3")


lazy val shared = (crossProject(JSPlatform, JVMPlatform) in file("shared"))
  .settings(
    name := "sc4pac-shared",
    libraryDependencies ++= Seq(
      "com.lihaoyi" %%% "upickle" % "3.1.2"  // json serialization
    )
  )
  .jvmSettings(
    libraryDependencies ++= Seq(
      "io.get-coursier" %% "coursier" % coursierVersion cross CrossVersion.for3Use2_13  // for definition of Organization and ModuleName
    )
  )
  .jsSettings(
    scalaJSUseMainModuleInitializer := false
  )

lazy val web = (project in file("web"))
  .dependsOn(shared.js)
  .enablePlugins(ScalaJSPlugin)
  .settings(
    name := "sc4pac-web",
    scalaJSUseMainModuleInitializer := true,
    libraryDependencies ++= Seq(
      // "dev.zio" %%% "zio" % "2.0.15",  // IO
      // "io.github.cquiroz" %%% "scala-java-time" % "2.5.0",  // for zio
      // "io.github.cquiroz" %%% "scala-java-time-tzdb" % "2.5.0",  // for zio
      "com.softwaremill.sttp.client4" %%% "core" % "4.0.0-M5",  // http requests
      "com.softwaremill.sttp.client4" %%% "upickle" % "4.0.0-M5",  // parsing json
      "com.lihaoyi" %%% "scalatags" % "0.12.0",  // html templating
      "org.scala-js" %%% "scalajs-dom" % "2.8.0"  // facades for the DOM
    )
  )
