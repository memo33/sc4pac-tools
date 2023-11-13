package io.github.memo33
package sc4pac

import org.apache.commons.compress.archivers.ArchiveEntry
import org.apache.commons.compress.archivers.zip.{ZipFile, ZipArchiveEntry}
import org.apache.commons.compress.archivers.sevenz.{SevenZFile, SevenZArchiveEntry}
import org.apache.commons.compress.utils.IOUtils
import java.nio.file.StandardOpenOption

import JsonData.InstallRecipe
import sc4pac.error.ExtractionFailed

object Extractor {

  def acceptNestedArchive(p: os.BasePath) =
    p.last.endsWith(".jar") || p.last.endsWith(".zip") || p.last.endsWith(".7z") || p.last.endsWith(".exe")

  private object WrappedArchive {
    def apply(file: java.io.File): WrappedArchive[?] = {
      if (file.getName.endsWith(".exe"))  // assume NSIS installer (ClickTeam installer is not supported)
        try {
          import net.sf.sevenzipjbinding as SZ
          val raf = new java.io.RandomAccessFile(file, "r")
          val inArchive = SZ.SevenZip.openInArchive(null /*autodetect format*/, new SZ.impl.RandomAccessFileInStream(raf))
          new native.Wrapped7zNative(raf, inArchive)
        } catch {
          case e: java.lang.UnsatisfiedLinkError =>  // some platforms may be unsupported, e.g. Apple arm
            throw new ExtractionFailed(s"Failed to load native 7z library: $e")
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
    def extractSelected(entries: Seq[(A, os.Path)], overwrite: Boolean): Unit
  }

  private class WrappedZip(archive: ZipFile) extends WrappedArchive[ZipArchiveEntry] {
    export archive.close
    import scala.jdk.CollectionConverters.*
    def getEntries = archive.getEntries.asScala
    def getEntryPath(entry: ZipArchiveEntry): String = entry.getName
    def isDirectory(entry: ZipArchiveEntry): Boolean = entry.isDirectory
    def isUnixSymlink(entry: ZipArchiveEntry) = entry.isUnixSymlink
    def extractSelected(entries: Seq[(ZipArchiveEntry, os.Path)], overwrite: Boolean): Unit =
      entries.foreach((entry, target) => extractEntryCommons(archive.getInputStream(entry), target, overwrite))
  }

  private class Wrapped7z(archive: SevenZFile) extends WrappedArchive[SevenZArchiveEntry] {
    export archive.close
    import scala.jdk.CollectionConverters.*
    def getEntries = archive.getEntries.iterator.asScala
    def getEntryPath(entry: SevenZArchiveEntry): String = entry.getName
    def isDirectory(entry: SevenZArchiveEntry): Boolean = entry.isDirectory
    def isUnixSymlink(entry: SevenZArchiveEntry) = false
    def extractSelected(entries: Seq[(SevenZArchiveEntry, os.Path)], overwrite: Boolean): Unit =
      entries.foreach((entry, target) => extractEntryCommons(archive.getInputStream(entry), target, overwrite))
  }

  def options(overwrite: Boolean) =
    if (overwrite) Seq(StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
    else Seq(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)

  private def extractEntryCommons(entryIn: java.io.InputStream, target: os.Path, overwrite: Boolean): Unit = {
    os.makeDir.all(target / os.up)  // prepares extraction (we know that entry refers to a file, not a directory)
    scala.util.Using.resources(entryIn, java.nio.file.Files.newOutputStream(target.toNIO, options(overwrite)*)) { (in, out) =>
      IOUtils.copy(in, out, Constants.bufferSizeExtract)
      ()
    }
  }

  private object native {
    import net.sf.sevenzipjbinding as SZ

    class Wrapped7zNative(raf: java.io.RandomAccessFile, archive: SZ.IInArchive) extends WrappedArchive[Int] {
      def close() =
        try {
          archive.close()  // might not close raf, so manually close raf here
        } finally {
          raf.close()
        }

      def getEntries = (0 until archive.getNumberOfItems()).iterator
      def getEntryPath(entry: Int) = archive.getProperty(entry, SZ.PropID.PATH).asInstanceOf[String]  // assumes that paths in archive are not null
      def isDirectory(entry: Int) = archive.getProperty(entry, SZ.PropID.IS_FOLDER).asInstanceOf[Boolean]
      def isUnixSymlink(entry: Int) = archive.getProperty(entry, SZ.PropID.SYM_LINK).asInstanceOf[Boolean]

      def extractSelected(entries: Seq[(Int, os.Path)], overwrite: Boolean) = try {
        archive.extract(entries.toArray.map(_._1), false, new SZ.IArchiveExtractCallback {

          val getPath: Int => os.Path = entries.toMap  // TODO this does not have linear complexity
          var index: Int = 0
          var out: java.io.OutputStream = null

          def getStream(index: Int, extractAskMode: SZ.ExtractAskMode) =
            if (extractAskMode != SZ.ExtractAskMode.EXTRACT)
              null
            else {
              val out = java.nio.file.Files.newOutputStream(getPath(index).toNIO, options(overwrite)*)
              this.index = index
              this.out = out
              new SZ.ISequentialOutStream {
                def write(data: Array[Byte]) = { out.write(data); data.length }
              }
            }

          def prepareOperation(extractAskMode: SZ.ExtractAskMode): Unit = {
            os.makeDir.all(getPath(index) / os.up)  // prepares extraction (we know that entry refers to a file, not a directory)
          }

          def setOperationResult(extractOperationResult: SZ.ExtractOperationResult): Unit = {
            var success = true
            try {
              if (out != null) out.close()
            } catch {
              case e: java.io.IOException =>
                System.err.println(s"Failed to close extracted archive entry file: ${getPath(index)}.")
                success = false
            }
            if (!success || extractOperationResult != SZ.ExtractOperationResult.OK) {
              throw new SZ.SevenZipException(s"Extraction of archive entry failed: ${getPath(index)}.")
            }
          }

          def setCompleted(complete: Long): Unit = {}  // TODO logging of progress
          def setTotal(total: Long): Unit = {}

        })
      } catch {
        case e: SZ.SevenZipException => throw new ExtractionFailed(s"Extraction failed: ${e.getMessage}")
      }
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
        val extracted: Seq[os.Path] = entries.map((_, subpath) => destination / mapper(subpath))
        val selected = entries.zip(extracted).flatMap { case ((entry, _), target) =>
            if (!overwrite && os.exists(target))
              None  // do nothing, as file has already been extracted previously (this avoids re-extracting large nested archives)
            else
              Some(entry, target)
          }
        zip.extractSelected(selected, overwrite)
        extracted  // Note that this includes some pre-existing files not in `selected`, but only files accepted by predicate
      }
    }
  }

  def extract(archive: java.io.File, destination: os.Path, recipe: InstallRecipe, jarExtractionOpt: Option[Extractor.JarExtraction]): Unit = try {
    // first extract just the main files
    extractByPredicate(archive, destination, recipe.accepts, overwrite = true, flatten = false)
    // additionally, extract jar files contained in the zip file to a temporary location
    for (Extractor.JarExtraction(jarsDir) <- jarExtractionOpt) {
      val jarFiles = extractByPredicate(archive, jarsDir, Extractor.acceptNestedArchive, overwrite = false, flatten = true)  // overwrite=false, as we want to extract any given jar only once per staging process
      // finally extract the jar files themselves (without recursively extracting jars contained inside)
      for (jarFile <- jarFiles if Extractor.acceptNestedArchive(jarFile)) {
        logger.debug(s"Extracting nested archive ${jarFile.last}")
        extract(jarFile.toIO, destination, recipe, jarExtractionOpt = None)
      }
    }
  } catch {
    case e: ExtractionFailed => throw e
    case e: java.io.IOException => throw new ExtractionFailed(s"Failed to extract $archive: ${e.getMessage}")
  }
}
