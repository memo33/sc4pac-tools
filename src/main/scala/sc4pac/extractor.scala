package io.github.memo33
package sc4pac

import org.apache.commons.compress.archivers.ArchiveEntry
import org.apache.commons.compress.archivers.zip.{ZipFile, ZipArchiveEntry}
import org.apache.commons.compress.archivers.sevenz.{SevenZFile, SevenZArchiveEntry}
import org.apache.commons.compress.utils.IOUtils
import java.nio.file.StandardOpenOption

import JsonData.InstallRecipe

object Extractor {

  private sealed trait WrappedArchive[A <: ArchiveEntry] extends AutoCloseable {
    def getEntries: Iterator[A]
    def getInputStream(entry: A): java.io.InputStream
    def isUnixSymlink(entry: A): Boolean
  }

  private object WrappedArchive {
    def apply(file: java.io.File): WrappedArchive[?] = {
      if (file.getName.endsWith(".7z"))  // does not work for NSIS installer yet
        new Wrapped7z(new SevenZFile(file))
      else  // zip or jar
        new WrappedZip(new ZipFile(file))
    }
  }

  def acceptNestedArchive(p: os.BasePath) = p.last.endsWith(".jar") || p.last.endsWith(".zip") || p.last.endsWith(".7z")

  private class WrappedZip(archive: ZipFile) extends WrappedArchive[ZipArchiveEntry] {
    export archive.{close, getInputStream}
    import scala.jdk.CollectionConverters.*
    def getEntries = archive.getEntries.asScala
    def isUnixSymlink(entry: ZipArchiveEntry) = entry.isUnixSymlink
  }

  private class Wrapped7z(archive: SevenZFile) extends WrappedArchive[SevenZArchiveEntry] {
    export archive.{close, getInputStream}
    import scala.jdk.CollectionConverters.*
    def getEntries = archive.getEntries.iterator.asScala
    def isUnixSymlink(entry: SevenZArchiveEntry) = false
  }

  /** This class contains information on how to extract a jar contained in a zip file.
    * The jarsDir is a temp directory unique to the containing zip file url.
    */
  case class JarExtraction(jarsDir: os.Path)
  object JarExtraction {
    def fromUrl[F[_]](archiveUrl: String, cache: FileCache, jarsRoot: os.Path): JarExtraction = {
      // we use cache to find a consistent archiveSubPath based on the url
      val archivePath = os.Path(cache.localFile(archiveUrl), os.pwd)
      val cachePath = os.Path(cache.location, os.pwd)
      val archiveSubPath = archivePath.subRelativeTo(cachePath)
      JarExtraction(jarsRoot / archiveSubPath)
    }
  }

  private def commonPrefix(p: os.SubPath, q: os.SubPath): os.SubPath = {
    var i = 0
    while (i < p.segments0.length && i < q.segments0.length && p.segments0(i) == q.segments0(i)) {
      i += 1
    }
    os.SubPath(p.segments0.take(i).toIndexedSeq)
  }
}

class Extractor(logger: Logger) {

  /** Extract the zip archive: filter the entries by a predicate, strip the
    * common prefix from all paths for a more flattened folder structure, and
    * optionally overwrite existing files.
    */
  private def extractByPredicate(archive: java.io.File, destination: os.Path, predicate: os.SubPath => Boolean, overwrite: Boolean, flatten: Boolean): Seq[os.Path] = {
    scala.util.Using.resource(Extractor.WrappedArchive(archive)) { zip =>
      // first we read the acceptable file names contained in the zip file
      import scala.jdk.CollectionConverters.*
      val entries /*: Seq[(zip.A, os.SubPath)]*/ = zip.getEntries
        .map(e => e -> os.SubPath(e.getName))
        .filter { (e, p) =>
          if (e.isDirectory() || zip.isUnixSymlink(e)) {  // skip symlinks as precaution
            false
          } else {
            val include = predicate(p)
            logger.extractArchiveEntry(p, include)
            include
          }
        }
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
            else entries.iterator.map(_._2).reduceLeft(Extractor.commonPrefix)
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
              IOUtils.copy(in, out, Constants.bufferSizeExtract)
            }
          }
          path
        }
      }
    }
  }

  def extract(archive: java.io.File, destination: os.Path, recipe: InstallRecipe, jarExtractionOpt: Option[Extractor.JarExtraction]): Unit = {
    // first extract just the main files
    extractByPredicate(archive, destination, recipe.accepts, overwrite = true, flatten = false)
    // additionally, extract jar files contained in the zip file to a temporary location
    for (Extractor.JarExtraction(jarsDir) <- jarExtractionOpt) {
      val jarFiles = extractByPredicate(archive, jarsDir, Extractor.acceptNestedArchive, overwrite = false, flatten = true)  // overwrite=false, as we want to extract any given jar only once per staging process
      // finally extract the jar files themselves (without recursively extracting jars contained inside)
      for (jarFile <- jarFiles if Extractor.acceptNestedArchive(jarFile)) {
        extract(jarFile.toIO, destination, recipe, jarExtractionOpt = None)
      }
    }
  }
}
