package io.github.memo33
package sc4pac

import org.codehaus.plexus.archiver.zip.ZipUnArchiver
import org.codehaus.plexus.components.io.fileselectors.{FileSelector, FileInfo}
import org.codehaus.plexus.components.io.filemappers.{FileMapper, FlattenFileMapper}
import java.util.zip.{ZipFile, ZipEntry}


import Data.InstallRecipe

trait Extractor {
  // def extract(archive: java.io.File, destination: os.Path, recipe: InstallRecipe): Boolean
}

class ZipExtractor extends Extractor {

  private def commonPrefix(p: os.SubPath, q: os.SubPath): os.SubPath = {
    var i = 0
    while (i < p.segments0.length && i < q.segments0.length && p.segments0(i) == q.segments0(i)) {
      i += 1
    }
    os.SubPath(p.segments0.take(i).toIndexedSeq)
  }

  private def extractByRecipeOnly(archive: java.io.File, destination: os.Path, recipe: InstallRecipe): Unit = {
    // first we read the acceptable file names contained in the zip file
    val zip = new ZipFile(archive)
    import scala.jdk.CollectionConverters.*
    val entries: Seq[os.SubPath] = zip.entries.asScala
      .map(e => os.SubPath(e.getName))
      .filter(recipe.accepts)
      .toSeq.distinct
    // TODO close zip?

    if (entries.isEmpty) {
      ()  // nothing to extract
    } else {
      // next we determine the prefix common to all files, so we can strip it for a more flattened folder structure
      val prefix: os.SubPath = if (entries.lengthCompare(1) == 0) entries.head / os.up else entries.reduceLeft(commonPrefix)
      val prefixStr = prefix.segments0.map(seg => s"$seg${java.io.File.separator}").mkString("")  // the plexus api works with platform-dependent File.separator

      val unzipper = new ZipUnArchiver(archive)  // TODO set logger
      unzipper.setDestDirectory(destination.toIO)
      unzipper.setOverwrite(true)
      unzipper.setFileSelectors(Array(new FileSelector {
        def isSelected(fileInfo: FileInfo): Boolean =
          recipe.accepts(os.SubPath(fileInfo.getName())) && !fileInfo.isSymbolicLink()  // skip symlinks as precaution
      }))
      unzipper.setFileMappers(Array(new FileMapper {
        def getMappedFileName(x: String): String = {
          if (x.startsWith(prefixStr)) {
            x.substring(prefixStr.length)  // strip the common prefix
          } else {
            println(s"zip entry $x did not start with prefix $prefixStr")  // TODO logger
            x
          }
        }
      }))
      unzipper.extract()
    }
  }

  def extract(archive: java.io.File, destination: os.Path, recipe: InstallRecipe, jarExtractionOpt: Option[ZipExtractor.JarExtraction]): Unit = {
    // first extract just the main files
    extractByRecipeOnly(archive, destination, recipe)
    // additionally, extract jar files contained in the zip file to a temporary location
    jarExtractionOpt match {
      case None => ()  // no jars to extract
      case Some(ZipExtractor.JarExtraction(jarsDir)) =>
        val unzipperJar = new ZipUnArchiver(archive)
        os.makeDir.all(jarsDir)  // TODO avoid unnecessary creation if zip does not contain any jars
        unzipperJar.setDestDirectory(jarsDir.toIO)
        unzipperJar.setOverwrite(false)  // we want to extract any given jar only once per staging process
        unzipperJar.setFileSelectors(Array(new FileSelector {
          def isSelected(fileInfo: FileInfo): Boolean = fileInfo.isFile() && fileInfo.getName().endsWith(".jar")
        }))
        unzipperJar.setFileMappers(Array(new FlattenFileMapper()))
        unzipperJar.extract()

        // finally extract the jar files themselves (without recursively extracting jars contained inside)
        for (jarFile <- os.list(jarsDir) if jarFile.last.endsWith(".jar")) {
          // println(s"  ==> $jarFile")  // TODO logger
          extractByRecipeOnly(jarFile.toIO, destination, recipe)
        }
    }
  }
}

object ZipExtractor {
  /** This class contains information on how to extract a jar contained in a zip file.
    * The jarsDir is a temp directory unique to the containing zip file url.
    */
  case class JarExtraction(jarsDir: os.Path)
  object JarExtraction {
    def fromUrl[F[_]](archiveUrl: String, cache: coursier.cache.FileCache[F], jarsRoot: os.Path): JarExtraction = {
      // we use cache to find a consistent archiveSubPath based on the url
      val archivePath = os.Path(cache.localFile(archiveUrl), os.pwd)
      val cachePath = os.Path(cache.location, os.pwd)
      val archiveSubPath = archivePath.subRelativeTo(cachePath)
      JarExtraction(jarsRoot / archiveSubPath)
    }
  }
}
