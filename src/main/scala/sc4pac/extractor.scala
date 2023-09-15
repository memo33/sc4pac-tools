package io.github.memo33
package sc4pac

import org.apache.commons.compress.archivers.zip.{ZipFile, ZipArchiveEntry}
import org.apache.commons.compress.utils.IOUtils
import java.nio.file.StandardOpenOption

import JsonData.InstallRecipe

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

  /** Extract the zip archive: filter the entries by a predicate, strip the
    * common prefix from all paths for a more flattened folder structure, and
    * optionally overwrite existing files.
    */
  private def extractByPredicate(archive: java.io.File, destination: os.Path, predicate: os.SubPath => Boolean, overwrite: Boolean, flatten: Boolean): Seq[os.Path] = {
    scala.util.Using.resource(new ZipFile(archive)) { zip =>
      // first we read the acceptable file names contained in the zip file
      import scala.jdk.CollectionConverters.*
      val entries: Seq[(ZipArchiveEntry, os.SubPath)] = zip.getEntries.asScala
        .map(e => e -> os.SubPath(e.getName))
        .filter((e, p) => !e.isDirectory() && !e.isUnixSymlink() && predicate(p))  // skip symlinks as precaution
        .toSeq

      if (entries.isEmpty) {
        Seq.empty  // nothing to extract
      } else {
        // map zip entry names to file names (within destination directory)
        val mapper: os.SubPath => os.SubPath = if (flatten) {
          (subpath) => subpath.subRelativeTo(subpath / os.up)  // keep just filename
        } else {
          // determine the prefix common to all files, so we can strip it for a semi-flattened folder structure
          val prefix: os.SubPath =
            if (entries.lengthCompare(1) == 0) entries.head._2 / os.up
            else entries.iterator.map(_._2).reduceLeft(commonPrefix)
          (subpath) => subpath.subRelativeTo(prefix)
        }

        val options =
          if (overwrite) Seq(StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
          else Seq(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)

        os.makeDir.all(destination)
        for ((entry, subpath) <- entries) yield {
          val path = destination / mapper(subpath)
          if (!overwrite && os.exists(path)) {
            // do nothing, as file has already been extracted previously
          } else {
            os.makeDir.all(path / os.up)  // we know that entry refers to a file, not a directory
            // write entry to file
            scala.util.Using.resources(zip.getInputStream(entry), java.nio.file.Files.newOutputStream(path.toNIO, options*)) { (in, out) =>
              IOUtils.copy(in, out, Constants.bufferSize)
            }
          }
          path
        }
      }
    }
  }

  def extract(archive: java.io.File, destination: os.Path, recipe: InstallRecipe, jarExtractionOpt: Option[ZipExtractor.JarExtraction]): Unit = {
    // first extract just the main files
    extractByPredicate(archive, destination, recipe.accepts, overwrite = true, flatten = false)
    // additionally, extract jar files contained in the zip file to a temporary location
    for (ZipExtractor.JarExtraction(jarsDir) <- jarExtractionOpt) {
      val jarFiles = extractByPredicate(archive, jarsDir, predicate = _.last.endsWith(".jar"), overwrite = false, flatten = true)  // overwrite=false, as we want to extract any given jar only once per staging process
      // finally extract the jar files themselves (without recursively extracting jars contained inside)
      for (jarFile <- jarFiles if jarFile.last.endsWith(".jar")) {
        extract(jarFile.toIO, destination, recipe, jarExtractionOpt = None)
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
