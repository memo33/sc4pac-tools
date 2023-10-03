package io.github.memo33
package sc4pac

import org.apache.commons.compress.archivers.ArchiveEntry
import org.apache.commons.compress.archivers.zip.{ZipFile, ZipArchiveEntry}
import org.apache.commons.compress.archivers.sevenz.{SevenZFile, SevenZArchiveEntry}
import org.apache.commons.compress.utils.IOUtils
import java.nio.file.StandardOpenOption

import JsonData.InstallRecipe

object Extractor {

  def acceptNestedArchive(p: os.BasePath) =
    p.last.endsWith(".jar") || p.last.endsWith(".zip") || p.last.endsWith(".7z") || p.last.endsWith(".exe")

  private object WrappedArchive {
    def apply(file: java.io.File): WrappedArchive[?] = {
      if (file.getName.endsWith(".exe"))  // assume NSIS installer (ClickTeam installer is not supported)
        // try
        {
          import net.sf.sevenzipjbinding as SZ
          val raf = new java.io.RandomAccessFile(file, "r")
          val inArchive = SZ.SevenZip.openInArchive(null /*autodetect format*/, new SZ.impl.RandomAccessFileInStream(raf))
          new native.Wrapped7zNative(raf, inArchive.getSimpleInterface())
        // } catch {
        //   case e: java.lang.UnsatisfiedLinkError => throw e  // TODO catch this for unsupported platforms, e.g. Apple arm
        }
      else if (file.getName.endsWith(".7z"))
        new Wrapped7z(new SevenZFile(file))
      else  // zip or jar
        new WrappedZip(new ZipFile(file))
    }
  }

  private sealed trait WrappedArchive[A] extends AutoCloseable {
    def getEntries: Iterator[A]
    def getEntryPath(entry: A): String
    def isDirectory(entry: A): Boolean
    def isUnixSymlink(entry: A): Boolean
    def extractTo(entry: A, target: os.Path, overwrite: Boolean): Unit
  }

  private class WrappedZip(archive: ZipFile) extends WrappedArchive[ZipArchiveEntry] {
    export archive.close
    import scala.jdk.CollectionConverters.*
    def getEntries = archive.getEntries.asScala
    def getEntryPath(entry: ZipArchiveEntry): String = entry.getName
    def isDirectory(entry: ZipArchiveEntry): Boolean = entry.isDirectory
    def isUnixSymlink(entry: ZipArchiveEntry) = entry.isUnixSymlink
    def extractTo(entry: ZipArchiveEntry, target: os.Path, overwrite: Boolean) =
      extractToImpl(archive.getInputStream(entry), target, overwrite)
  }

  private class Wrapped7z(archive: SevenZFile) extends WrappedArchive[SevenZArchiveEntry] {
    export archive.close
    import scala.jdk.CollectionConverters.*
    def getEntries = archive.getEntries.iterator.asScala
    def getEntryPath(entry: SevenZArchiveEntry): String = entry.getName
    def isDirectory(entry: SevenZArchiveEntry): Boolean = entry.isDirectory
    def isUnixSymlink(entry: SevenZArchiveEntry) = false
    def extractTo(entry: SevenZArchiveEntry, target: os.Path, overwrite: Boolean) =
      extractToImpl(archive.getInputStream(entry), target, overwrite)
  }

  private def extractToImpl(entryIn: java.io.InputStream, target: os.Path, overwrite: Boolean): Unit = {
    val options =
      if (overwrite) Seq(StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
      else Seq(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
    scala.util.Using.resources(entryIn, java.nio.file.Files.newOutputStream(target.toNIO, options*)) { (in, out) =>
      IOUtils.copy(in, out, Constants.bufferSizeExtract)
    }
  }

  private object native {
    import net.sf.sevenzipjbinding.simple.{ISimpleInArchive, ISimpleInArchiveItem}
    import net.sf.sevenzipjbinding.impl.RandomAccessFileOutStream
    import net.sf.sevenzipjbinding.PropID

    given releasableRAFOS: scala.util.Using.Releasable[RandomAccessFileOutStream] =
      new scala.util.Using.Releasable[RandomAccessFileOutStream] {
        def release(resource: RandomAccessFileOutStream) = resource.close()
      }

    class Wrapped7zNative(raf: java.io.RandomAccessFile, archive: ISimpleInArchive) extends WrappedArchive[ISimpleInArchiveItem] {
      def close() = {
        archive.close()  // might not close raf, so manually close raf here
        raf.close()
      }
      def getEntries = archive.getArchiveItems.iterator
      def getEntryPath(entry: ISimpleInArchiveItem) = entry.getPath
      def isDirectory(entry: ISimpleInArchiveItem) = entry.isFolder
      def isUnixSymlink(entry: ISimpleInArchiveItem) = false
      def extractTo(entry: ISimpleInArchiveItem, target: os.Path, overwrite: Boolean): Unit =
        scala.util.Using.resource(new java.io.RandomAccessFile(target.toIO, "rw")): raf =>
          if (overwrite)
            raf.setLength(0)  // first truncate existing
          scala.util.Using.resource(new RandomAccessFileOutStream(raf)): out =>
            entry.extractSlow(out)  // TODO use standard interface if this is too slow
    }
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
        .map(e => e -> os.SubPath(zip.getEntryPath(e)))
        .filter { (e, p) =>
          if (zip.isDirectory(e) || zip.isUnixSymlink(e)) {  // skip symlinks as precaution
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

        os.makeDir.all(destination)
        for ((entry, subpath) <- entries) yield {
          val path = destination / mapper(subpath)
          if (!overwrite && os.exists(path)) {
            // do nothing, as file has already been extracted previously
          } else {
            os.makeDir.all(path / os.up)  // we know that entry refers to a file, not a directory
            // write entry to file
            zip.extractTo(entry, path, overwrite)
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
