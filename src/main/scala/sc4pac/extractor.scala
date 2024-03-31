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

  def acceptNestedArchive(p: os.BasePath) = {
    val name = p.last.toLowerCase
    name.endsWith(".jar") || name.endsWith(".zip") || name.endsWith(".7z") || name.endsWith(".exe")
  }

  private object WrappedArchive {
    def apply(file: java.io.File, fallbackFilename: Option[String]): WrappedArchive[?] = {
      val lcNames: Seq[String] = Seq(file.getName.toLowerCase) ++ fallbackFilename.map(_.toLowerCase)
      if (lcNames.exists(_.endsWith(".exe")) ||  // assume NSIS installer (ClickTeam installer is not supported)
          lcNames.exists(_.endsWith(".rar")))
        try {
          import net.sf.sevenzipjbinding as SZ
          val raf = new java.io.RandomAccessFile(file, "r")
          val inArchive = SZ.SevenZip.openInArchive(null /*autodetect format*/, new SZ.impl.RandomAccessFileInStream(raf))
          new native.Wrapped7zNative(raf, inArchive)
        } catch {
          case e: java.lang.UnsatisfiedLinkError =>  // some platforms may be unsupported, e.g. Apple arm
            throw new ExtractionFailed(s"Failed to load native 7z library.", e.toString)
        }
      else if (lcNames.exists(_.endsWith(".7z")))
        new Wrapped7z(new SevenZFile(file))
      else {
        val remoteOrLocalFallback: Option[String] =
          if (fallbackFilename.exists(filename => Constants.sc4fileTypePattern.matcher(filename).find()))
            fallbackFilename  // For remote files, fallbackFilename is extracted from HTTP header.
          else if (Constants.sc4fileTypePattern.matcher(file.getName).find())
            Some(file.getName)  // For local `file://` files, we rely on correct file extensions instead.
          else None

        if (remoteOrLocalFallback.isDefined)
          new WrappedNonarchive(os.Path(file.getAbsolutePath()), remoteOrLocalFallback.get)  // .dat/.sc4*/.dll
        else  // zip or jar (default case in case file extension is not known)
          new WrappedZip(new ZipFile(file))
      }
    }
  }

  private sealed trait WrappedArchive[A] extends AutoCloseable {
    /** Enumerate all entries contained in the archive. */
    def getEntries: Iterator[A]
    /** The stringified subpath of the entry within the archive. */
    def getEntryPath(entry: A): String
    def isDirectory(entry: A): Boolean
    def isUnixSymlink(entry: A): Boolean
    /** Perform the actual extraction of the selected archive entries.
      *
      * Here, `entries` consists of a sequence of:
      *   - an entry selected for extraction,
      *   - the corresponding full target path for this entry.
      * The target paths are already mapped to discard redundant top-level
      * directories, so can differ from the what `getEntryPath` returned.
      */
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

  // A single .dat/.dll/.sc4* file that can just be copied to the target with a
  // new name. The name of `file` itself is not meaningful as it comes from the URL in the file cache.
  private class WrappedNonarchive(file: os.Path, filename: String) extends WrappedArchive[os.Path] {
    def close(): Unit = {}
    def getEntries = Iterator(file)
    def getEntryPath(entry: os.Path) = filename  // entry.last has no meaningful relevance
    def isDirectory(entry: os.Path) = false
    def isUnixSymlink(entry: os.Path) = false
    def extractSelected(entries: Seq[(os.Path, os.Path)], overwrite: Boolean): Unit =
      for ((src, target) <- entries) {
        os.copy.over(src, target, replaceExisting = overwrite)
      }
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
              os.makeDir.all(getPath(index) / os.up)  // prepares extraction (we know that entry refers to a file, not a directory)
              val out = java.nio.file.Files.newOutputStream(getPath(index).toNIO, options(overwrite)*)
              this.index = index
              this.out = out
              new SZ.ISequentialOutStream {
                def write(data: Array[Byte]) = { out.write(data); data.length }
              }
            }

          def prepareOperation(extractAskMode: SZ.ExtractAskMode): Unit = {}

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
        case e: SZ.SevenZipException => throw new ExtractionFailed("7z-extraction failed.", e.getMessage)
      }
    }
  }

  /** This class contains information on how to extract a jar contained in a zip file.
    * The jarsDir is a temp directory unique to the containing zip file url.
    */
  case class JarExtraction(jarsDir: os.Path)
  object JarExtraction {
    def fromUrl[F[_]](archiveUrl: String, cache: FileCache, jarsRoot: os.Path, scopeRoot: os.Path): JarExtraction = {
      // we use cache to find a consistent archiveSubPath based on the url
      val archivePath = os.Path(cache.localFile(archiveUrl), scopeRoot)
      val cachePath = os.Path(cache.location, scopeRoot)
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
  private def extractByPredicate(
    archive: java.io.File,
    destination: os.Path,
    predicate: os.SubPath => Boolean,
    overwrite: Boolean,
    flatten: Boolean,
    fallbackFilename: Option[String]
  ): Seq[os.Path] = {
    scala.util.Using.resource(Extractor.WrappedArchive(archive, fallbackFilename)) { zip =>
      // first we read the acceptable file names contained in the zip file
      val entries /*: Seq[(zip.A, os.SubPath)]*/ = zip.getEntries
        .flatMap { e =>
          val relativePathString = zip.getEntryPath(e)
          val subPathOpt =  // sanitized entry path
            if (relativePathString.isEmpty || relativePathString.startsWith("/") || relativePathString.startsWith("""\""")) {
              None
            } else if (zip.isUnixSymlink(e)) {  // skip symlinks as precaution
              None
            } else try {
              Some(os.SubPath(relativePathString))
            } catch { case _: IllegalArgumentException =>
              None
            }
          if (!subPathOpt.isDefined) {
            logger.debug(s"""Ignoring disallowed archive entry path "$relativePathString".""")
          }
          subPathOpt.map(e -> _)
        }
        .filter { (e, p) =>
          if (zip.isDirectory(e)) {
            false
          } else {
            val include = predicate(p)
            logger.extractingArchiveEntry(p, include)
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

  def extract(archive: java.io.File, fallbackFilename: Option[String], destination: os.Path, recipe: InstallRecipe, jarExtractionOpt: Option[Extractor.JarExtraction]): Unit = try {
    // first extract just the main files
    extractByPredicate(archive, destination, recipe.accepts, overwrite = true, flatten = false, fallbackFilename)
    // additionally, extract jar files contained in the zip file to a temporary location
    for (Extractor.JarExtraction(jarsDir) <- jarExtractionOpt) {
      logger.debug(s"Searching for nested archives:")
      val jarFiles = extractByPredicate(archive, jarsDir, Extractor.acceptNestedArchive, overwrite = false, flatten = true, fallbackFilename)  // overwrite=false, as we want to extract any given jar only once per staging process
      // finally extract the jar files themselves (without recursively extracting jars contained inside)
      for (jarFile <- jarFiles if Extractor.acceptNestedArchive(jarFile)) {
        logger.debug(s"Extracting nested archive ${jarFile.last}")
        extract(jarFile.toIO, fallbackFilename = None, destination, recipe, jarExtractionOpt = None)
      }
    }
  } catch {
    case e: ExtractionFailed => throw e
    case e: java.io.IOException => logger.debugPrintStackTrace(e); throw new ExtractionFailed(s"Failed to extract $archive.", e.getMessage)
  }
}
