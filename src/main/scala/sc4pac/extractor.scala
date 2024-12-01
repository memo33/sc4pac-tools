package io.github.memo33
package sc4pac

import org.apache.commons.compress.archivers.zip.{ZipFile, ZipArchiveEntry}
import org.apache.commons.compress.archivers.sevenz.{SevenZFile, SevenZArchiveEntry}
import org.apache.commons.compress.utils.IOUtils
import java.nio.file.StandardOpenOption
import java.util.regex.Pattern

import sc4pac.JsonData as JD
import sc4pac.error.{ExtractionFailed, ChecksumError, NotADbpfFile}

object Extractor {

  def acceptNestedArchive(p: os.BasePath): Option[Validator] = {
    val name = p.last.toLowerCase(java.util.Locale.ENGLISH)
    if (name.endsWith(".jar") || name.endsWith(".zip") || name.endsWith(".7z") || name.endsWith(".exe")) {
      Some(NestedArchiveNoopValidator)
    } else {
      None
    }
  }

  private def tryExtractClickteam(file: java.io.File, targetDir: os.Path, logger: Logger, clickteamVersion: String) = {
    val args = Seq(file.getPath, targetDir.toString)
    logger.debug(s"""Attempting to extract v$clickteamVersion Clickteam exe installer "${args(0)}" to "${args(1)}"""")
    if (clickteamVersion.toIntOption.isDefined) {  // we validate the version to guard against malicous code injection
      val options = Seq("-v", clickteamVersion)
      val result =
        try {
          os.proc(Constants.cicdecCommand ++ options ++ args).call(
            cwd = os.pwd,
            check = false,
            mergeErrIntoOut = true,
            timeout = Constants.interactivePromptTimeout.toMillis
          )
        } catch { case e: java.io.IOException =>
          throw new ExtractionFailed("""Failed to run "cicdec.exe" for Clickteam exe-installer extraction. """ +
            """On macOS and Linux, make sure that "mono" is installed on your system: https://www.mono-project.com/docs/getting-started/install/""",
            e.getMessage)
        }
      if (result.exitCode == 0) {
        new WrappedFolder(targetDir)
      } else {
        throw new ExtractionFailed(s"""Failed to extract v$clickteamVersion Clickteam exe-installer "${file.getName}".""", result.out.text())
      }
    } else {
      throw new ExtractionFailed(s"""Failed to extract Clickteam exe-installer "${file.getName}".""", s"""Unsupported version: "$clickteamVersion"""")
    }
  }

  private[sc4pac] object WrappedArchive {
    def apply(file: java.io.File, fallbackFilename: Option[String], stagingRoot: os.Path, logger: Logger, hints: Option[JD.ArchiveType]): WrappedArchive[?] = {
      val lcNames: Seq[String] = Seq(file.getName.toLowerCase(java.util.Locale.ENGLISH)) ++ fallbackFilename.map(_.toLowerCase(java.util.Locale.ENGLISH))
      if (lcNames.exists(_.endsWith(".exe")) && hints.exists(_.format.equalsIgnoreCase(JD.ArchiveType.clickteamFormat))) {
        /* Clickteam installer */
        val tempExtractionDir = os.temp.dir(stagingRoot, prefix = "exe", deleteOnExit = false)  // the parent stagingRoot is already temporary and will be deleted
        tryExtractClickteam(file, tempExtractionDir, logger, clickteamVersion = hints.get.version)
      } else if (lcNames.exists(_.endsWith(".exe")) || lcNames.exists(_.endsWith(".rar"))) {
        /* NSIS installer or rar file (extractable with native 7zip) */
        try {
          import net.sf.sevenzipjbinding as SZ
          native.Wrapped7zNative(file)
        } catch {
          case e: java.lang.UnsatisfiedLinkError =>  // some platforms may be unsupported, e.g. Apple arm
            throw new ExtractionFailed(s"Failed to load native 7z library.", e.toString)
        }
      } else if (lcNames.exists(_.endsWith(".7z"))) {
        /* pure 7zip file (using portable 7zip implementation for extraction) */
        new Wrapped7z(new SevenZFile(file))
      } else {
        /* single file or zip or jar file */
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

  private[sc4pac] sealed trait WrappedArchive[A] extends AutoCloseable {
    /** Enumerate all entries contained in the archive. */
    def iterateEntries: Iterator[A]
    /** The stringified subpath of the entry within the archive. */
    def getEntryPath(entry: A): String
    def isDirectory(entry: A): Boolean
    def isUnixSymlink(entry: A): Boolean
    /** Perform the actual extraction of the selected archive entries.
      *
      * Here, `entries` consists of a sequence of:
      *   - an entry selected for extraction,
      *   - the corresponding full target path for this entry.
      *   - a validator verifying DLL checksums or DBPF file integrity (throws exception)
      * The target paths are already mapped to discard redundant top-level
      * directories, so can differ from what `getEntryPath` returned.
      */
    def extractSelected(entries: Seq[(A, os.Path, Validator)], overwrite: Boolean): Unit

    /** Extract the zip archive: filter the entries by a predicate, strip the
      * common prefix from all paths for a more flattened folder structure, and
      * optionally overwrite existing files.
      */
    def extractByPredicate(
      destination: os.Path,
      predicate: Extractor.Predicate,
      overwrite: Boolean,
      flatten: Boolean,
      logger: Logger,
    ): Seq[os.Path] = {
      // first we read the acceptable file names contained in the zip file
      val entries: Seq[(A, os.SubPath, Validator)] = iterateEntries
        .flatMap { entry =>
          val relativePathString = getEntryPath(entry)
          val subPathOpt =  // sanitized entry path
            if (relativePathString.isEmpty || relativePathString.startsWith("/") || relativePathString.startsWith("""\""")) {
              None
            } else if (isUnixSymlink(entry)) {  // skip symlinks as precaution
              None
            } else try {
              Some(os.SubPath(relativePathString))
            } catch { case _: IllegalArgumentException =>
              None
            }
          if (!subPathOpt.isDefined) {
            logger.debug(s"""Ignoring disallowed archive entry path "$relativePathString".""")
          }
          subPathOpt.map(entry -> _)
        }
        .flatMap { (entry, path) =>
          if (isDirectory(entry)) {
            None
          } else {
            val validatorOpt = predicate(path)  // TODO in case of WrappedNonarchive, validate against archive checksum instead of IncludeWithChecksum
            logger.extractingArchiveEntry(path, validatorOpt.isDefined)
            validatorOpt.map((entry, path, _))
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
        val extracted: Seq[os.Path] = entries.map((_, subpath, _) => destination / mapper(subpath))
        val selected = entries.zip(extracted).flatMap { case ((entry, _, validator), target) =>
            if (!overwrite && os.exists(target))
              assert(validator == NestedArchiveNoopValidator)  // important since we do not validate these
              None  // do nothing, as file has already been extracted previously (this avoids re-extracting large nested archives)
            else
              Some(entry, target, validator)
          }
        extractSelected(selected, overwrite)
        extracted  // Note that this includes some pre-existing files not in `selected`, but only files accepted by predicate
      }
    }
  }

  // Wrapper for reading from normal folders. This could be useful if we're
  // reading from the local filesystem, or if we're using third party tools -
  // such as cicdec - for unzipping unknown archives.
  private[sc4pac] class WrappedFolder(folder: os.Path) extends WrappedArchive[os.Path] {
    def close(): Unit = {}
    def iterateEntries = os.walk(folder).iterator
    def getEntryPath(entry: os.Path) = entry.subRelativeTo(folder).toString
    def isDirectory(entry: os.Path) = os.isDir(entry)
    def isUnixSymlink(entry: os.Path) = os.isLink(entry)
    def extractSelected(entries: Seq[(os.Path, os.Path, Validator)], overwrite: Boolean): Unit =
      for ((src, target, validator) <- entries) {
        os.makeDir.all(target / os.up)
        os.copy.over(src, target, replaceExisting = overwrite)
        validator.validateOrThrow(target)
      }
  }

  private[sc4pac] class WrappedZip(archive: ZipFile) extends WrappedArchive[ZipArchiveEntry] {
    export archive.close
    import scala.jdk.CollectionConverters.*
    def iterateEntries = archive.getEntries.asScala
    def getEntryPath(entry: ZipArchiveEntry): String = entry.getName
    def isDirectory(entry: ZipArchiveEntry): Boolean = entry.isDirectory
    def isUnixSymlink(entry: ZipArchiveEntry) = entry.isUnixSymlink
    def extractSelected(entries: Seq[(ZipArchiveEntry, os.Path, Validator)], overwrite: Boolean): Unit =
      for ((entry, target, validator) <- entries) {
        extractEntryCommons(archive.getInputStream(entry), target, overwrite)
        validator.validateOrThrow(target)
      }
  }

  // A single .dat/.dll/.sc4* file that can just be copied to the target with a
  // new name. The name of `file` itself is not meaningful as it comes from the URL in the file cache.
  private[sc4pac] class WrappedNonarchive(file: os.Path, filename: String) extends WrappedArchive[os.Path] {
    def close(): Unit = {}
    def iterateEntries = Iterator(file)
    def getEntryPath(entry: os.Path) = filename  // entry.last has no meaningful relevance
    def isDirectory(entry: os.Path) = false
    def isUnixSymlink(entry: os.Path) = false
    def extractSelected(entries: Seq[(os.Path, os.Path, Validator)], overwrite: Boolean): Unit =
      for ((src, target, validator) <- entries) {
        os.copy.over(src, target, replaceExisting = overwrite)
        validator.validateOrThrow(target)
      }
  }

  private[sc4pac] class Wrapped7z(archive: SevenZFile) extends WrappedArchive[SevenZArchiveEntry] {
    export archive.close
    import scala.jdk.CollectionConverters.*
    def iterateEntries = archive.getEntries.iterator.asScala
    def getEntryPath(entry: SevenZArchiveEntry): String = entry.getName
    def isDirectory(entry: SevenZArchiveEntry): Boolean = entry.isDirectory
    def isUnixSymlink(entry: SevenZArchiveEntry) = false
    def extractSelected(entries: Seq[(SevenZArchiveEntry, os.Path, Validator)], overwrite: Boolean): Unit =
      for ((entry, target, validator) <- entries) {
        extractEntryCommons(archive.getInputStream(entry), target, overwrite)
        validator.validateOrThrow(target)
      }
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

  private[sc4pac] object native {
    import net.sf.sevenzipjbinding as SZ

    object Wrapped7zNative {
      def apply(file: java.io.File): Wrapped7zNative = {
        val raf = new java.io.RandomAccessFile(file, "r")
        val inArchive = SZ.SevenZip.openInArchive(null /*autodetect format*/, new SZ.impl.RandomAccessFileInStream(raf))
        new native.Wrapped7zNative(raf, inArchive)
      }
    }
    class Wrapped7zNative(raf: java.io.RandomAccessFile, archive: SZ.IInArchive) extends WrappedArchive[Int] {
      def close() =
        try {
          archive.close()  // might not close raf, so manually close raf here
        } finally {
          raf.close()
        }

      def iterateEntries = (0 until archive.getNumberOfItems()).iterator
      def getEntryPath(entry: Int) = archive.getProperty(entry, SZ.PropID.PATH).asInstanceOf[String]  // assumes that paths in archive are not null
      def isDirectory(entry: Int) = archive.getProperty(entry, SZ.PropID.IS_FOLDER).asInstanceOf[Boolean]
      def isUnixSymlink(entry: Int) = archive.getProperty(entry, SZ.PropID.SYM_LINK).asInstanceOf[Boolean]

      def extractSelected(entries: Seq[(Int, os.Path, Validator)], overwrite: Boolean) = try {
        val entriesMap: Map[Int, (Int, os.Path, Validator)] = entries.iterator.map(t => t._1 -> t).toMap  // TODO this does not have linear complexity
        val getPath: Int => os.Path = entriesMap(_)._2

        archive.extract(entries.toArray.map(_._1), false, new SZ.IArchiveExtractCallback {
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
            val (_, target, validator) = entriesMap(index)
            validator.validateOrThrow(target)
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
    def fromUrl[F[_]](archiveUrl: String, jarsRoot: os.Path): JarExtraction = {
      // we hash the the URL to find a deterministic and short storage path
      val hash = JD.Checksum.bytesToString(
        java.security.MessageDigest.getInstance("SHA-1")
          .digest(archiveUrl.getBytes("UTF-8"))
          .take(4).to(collection.immutable.ArraySeq)  // 4 bytes = 8 hex characters
      )
      JarExtraction(jarsRoot / hash)
    }
  }

  private def commonPrefix(p: os.SubPath, q: os.SubPath): os.SubPath = {
    var i = 0
    while (i < p.segments0.length && i < q.segments0.length && p.segments0(i) == q.segments0(i)) {
      i += 1
    }
    os.SubPath(p.segments0.take(i).toIndexedSeq)
  }

  type Predicate = os.SubPath => Option[Validator]
  type ExtractionValidationError = ChecksumError | NotADbpfFile

  sealed trait Validator {
    def validate(path: os.Path): Either[ExtractionValidationError, Unit]
    def validateOrThrow(path: os.Path): Unit = validate(path).left.foreach(throw _)
  }

  class ChecksumValidator(includeWithChecksum: JD.IncludeWithChecksum) extends Validator {
    def validate(path: os.Path): Either[ExtractionValidationError, Unit] = {
      val sha256Actual = Downloader.computeChecksum(path.toIO)
      if (includeWithChecksum.sha256 == sha256Actual) Right(())
      else Left(ChecksumError("Extracted file has wrong sha256 checksum. " +
        "Usually, this means the uploaded file was modified after the channel metadata was last updated, " +
        "so the integrity of the file cannot be verified by sc4pac. " +
        "Report this to the maintainers of the metadata.",
        s"File: $path, got: ${JD.Checksum.bytesToString(sha256Actual)}, expected: ${JD.Checksum.bytesToString(includeWithChecksum.sha256)}"))
    }
  }

  object DbpfValidator extends Validator {
    private def isDbpf(path: os.Path): Boolean = ???
    def validate(path: os.Path): Either[ExtractionValidationError, Unit] = {
      if (isDbpf(path)) Right(())
      else Left(NotADbpfFile("Extracted file is not a DBPF file. " +
        "If this error is caused by a malformed DBPF file, report this to the original author of the file. " +
        "Otherwise, if it is a DLL file, the channel metadata must include a checksum for verifying file integrity. " +
        "Report this to the maintainers of the metadata.",
        s"File: $path"))
    }
  }

  // Nested archives currently do not use checksums for validation. If desired, the outer archive should use a checksum instead.
  object NestedArchiveNoopValidator extends Validator {
    def validate(path: os.Path): Either[ExtractionValidationError, Unit] = Right(())
  }

}

class Extractor(logger: Logger) {

  /** Extracts files from the archive that match the inclusion/exclusion recipe, including one level of nested archives.
    * Returns the patterns that have matched. */
  def extract(
    archive: java.io.File,
    fallbackFilename: Option[String],
    destination: os.Path,
    recipe: JD.InstallRecipe,
    jarExtractionOpt: Option[Extractor.JarExtraction],
    hints: Option[JD.ArchiveType],
    stagingRoot: os.Path,
  ): Set[Pattern] = try {
    val (usedPatternsBuilder, predicate) = recipe.makeAcceptancePredicate()  // tracks used patterns to warn about unused patterns
    scala.util.Using.resource(Extractor.WrappedArchive(archive, fallbackFilename, stagingRoot, logger, hints)) { wrappedArchive =>
      // first extract just the main files
      wrappedArchive.extractByPredicate(destination, predicate, overwrite = true, flatten = false, logger)
      // additionally, extract jar files/nested archives contained in the zip file to a temporary location
      for (Extractor.JarExtraction(jarsDir) <- jarExtractionOpt) {
        logger.debug(s"Searching for nested archives:")
        val jarFiles = wrappedArchive.extractByPredicate(jarsDir, Extractor.acceptNestedArchive, overwrite = false, flatten = true, logger)  // overwrite=false, as we want to extract any given jar only once per staging process
        // finally extract the jar files themselves (without recursively extracting jars contained inside)
        for (jarFile <- jarFiles if Extractor.acceptNestedArchive(jarFile).isDefined) {
          logger.debug(s"Extracting nested archive ${jarFile.last}")
          usedPatternsBuilder ++=
            extract(jarFile.toIO, fallbackFilename = None, destination, recipe, jarExtractionOpt = None, hints, stagingRoot = stagingRoot)
        }
      }
    }
    usedPatternsBuilder.result()
  } catch {
    case e: ExtractionFailed => throw e
    case e: Extractor.ExtractionValidationError => throw new ExtractionFailed(s"Failed to extract $archive.", e.getMessage)
    case e: java.io.IOException => logger.debugPrintStackTrace(e); throw new ExtractionFailed(s"Failed to extract $archive.", e.getMessage)
  }
}
