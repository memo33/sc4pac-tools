package io.github.memo33
package sc4pac

import org.apache.commons.compress.archivers.zip.{ZipFile, ZipArchiveEntry}
import org.apache.commons.compress.archivers.sevenz.{SevenZFile, SevenZArchiveEntry}
import org.apache.commons.compress.utils.IOUtils
import java.nio.file.StandardOpenOption
import java.util.regex.Pattern
import scala.collection.mutable.Builder
import zio.Chunk

import sc4pac.JsonData as JD
import sc4pac.error.{ExtractionFailed, ChecksumError, NotADbpfFile}

object Extractor {

  type Predicate = os.SubPath => Option[Validator]

  class InstallRecipe(
    include: Seq[Pattern],
    exclude: Seq[Pattern],
    excludeNested: Seq[Pattern],
    includeWithChecksum: Seq[(Pattern, JD.IncludeWithChecksum)],
  ) {
    def makeAcceptancePredicates(validate: Boolean): (Builder[Pattern, Set[Pattern]], Predicate, Predicate) = {
      val usedPatternsBuilder = {
        val b = Set.newBuilder[Pattern] += Constants.defaultExcludePattern  // default exclude pattern is not required to match anything
        if (includeWithChecksum.nonEmpty)
          b += Constants.defaultIncludePattern  // archives containing DLLs might come without additional DBPF files
        b
      }

      val accepts: Predicate = { path =>
        val pathString = path.segments.mkString("/", "/", "")  // paths are checked with leading / and with / as separator
        includeWithChecksum.find(_._1.matcher(pathString).find()) match {
          case Some(matchedPattern, checksum) =>
            usedPatternsBuilder += matchedPattern
            Some(if (validate) ChecksumValidator(checksum) else NoopValidator)  // as checksum is only valid for a single file, there is no need for evaluating exclude rules
          case None =>
            include.find(_.matcher(pathString).find()) match {
              case None => None
              case Some(matchedPattern) =>
                usedPatternsBuilder += matchedPattern
                exclude.find(_.matcher(pathString).find()) match {
                  case None => Some(if (validate) DbpfValidator else NoopValidator)
                  case Some(matchedPattern) =>
                    usedPatternsBuilder += matchedPattern
                    None
                }
            }
        }
      }

      val acceptsNested: Predicate = { path =>
        val lname = path.last.toLowerCase(java.util.Locale.ENGLISH)
        if (lname.endsWith(".jar") || lname.endsWith(".zip") || lname.endsWith(".7z") || lname.endsWith(".rar") || lname.endsWith(".exe")) {
          val pathString = path.segments.mkString("/", "/", "")  // paths are checked with leading / and with / as separator
          excludeNested.find(_.matcher(pathString).find()) match {
            case None => Some(NoopValidator)  // no validation for nested archives
            case Some(matchedPattern) =>
              usedPatternsBuilder += matchedPattern
              None
          }
        } else {
          None
        }
      }

      (usedPatternsBuilder, accepts, acceptsNested)
    }

    def usedPatternWarnings(usedPatterns: Set[Pattern], asset: BareAsset, short: Boolean): Seq[Warning] = {
      val unused: Seq[Pattern] =
        (include.iterator ++ exclude ++ includeWithChecksum.iterator.map(_._1))
        .filter(p => !usedPatterns.contains(p)).toSeq
      if (unused.isEmpty) {
        Seq.empty
      } else {
        val msg = s"These inclusion/exclusion patterns did not match any files in the asset ${asset.assetId.value}: " + unused.mkString(" ")
        Seq(
          if (!short)
            "The package metadata seems to be out-of-date, so the installed plugin files might be incomplete. " +
            "Please report this to the maintainers of the package metadata. " + msg
          else msg
        )
      }
    }
  }
  object InstallRecipe {
    def fromAssetReference(data: JD.AssetReference, variant: Variant): (InstallRecipe, Seq[Warning]) = {
      val warnings = Seq.newBuilder[Warning]
      def toRegex(s: String): Option[Pattern] = try {
        Some(Pattern.compile(s, Pattern.CASE_INSENSITIVE))
      } catch {
        case e: java.util.regex.PatternSyntaxException =>
          warnings += s"The package metadata contains a malformed regex: $e"
          None
      }
      val matchingConditions =
        data.withConditions.filter(_.ifVariant.forall { (key, value) => variant.get(key) match
          case Some(selectedValue) => selectedValue == value
          case None => throw new AssertionError(s"During extraction, conditional variants should have already been fully selected: $key")
        })
      val include = (data.include ++ matchingConditions.flatMap(_.include)).flatMap(toRegex)
      val exclude = (data.exclude ++ matchingConditions.flatMap(_.exclude)).flatMap(toRegex)
      val includeWithChecksum = (data.withChecksum ++ matchingConditions.flatMap(_.withChecksum)).flatMap(item => toRegex(item.include).map(_ -> item))
      (InstallRecipe(
        include = if (include.isEmpty) Seq(Constants.defaultIncludePattern) else include,
        exclude = if (exclude.isEmpty) Seq(Constants.defaultExcludePattern) else exclude,
        excludeNested = exclude,  // we re-use the exclude patterns, but use a different default
        includeWithChecksum = includeWithChecksum,
      ), warnings.result())
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
      *
      * Usually calls extractEntry as implementation.
      */
    def extractSelectedEntries(entries: Chunk[(A, os.Path, Validator)], overwrite: Boolean): Chunk[ExtractedItem] =
      entries.map { case (entry, target, validator) =>
        extractEntry(entry, target, overwrite = overwrite)
        validator.validate(target)
      }

    protected def extractEntry(entry: A, target: os.Path, overwrite: Boolean): Unit

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
    ): Chunk[ExtractedItem] = {
      // first we read the acceptable file names contained in the zip file
      val entries: Chunk[(A, os.SubPath, Validator)] = iterateEntries
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
            val validatorOpt = predicate(path)
            logger.extractingArchiveEntry(path, validatorOpt.isDefined)
            validatorOpt.map((entry, path, _))
          }
        }
        .to(Chunk)

      if (entries.isEmpty) {
        Chunk.empty  // nothing to extract
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
        val (preexisting, selected) =
          entries.partitionMap { case (entry, subpath, validator) =>
            val target = destination / mapper(subpath)
            if (!overwrite && os.exists(target))
              Left(validator.validate(target))  // file has already been extracted previously (this avoids re-extracting large nested archives)
            else
              Right((entry, target, validator))
          }
        val extracted = extractSelectedEntries(selected, overwrite)
        extracted ++ preexisting
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
    protected def extractEntry(src: os.Path, target: os.Path, overwrite: Boolean): Unit = {
      os.makeDir.all(target / os.up)
      os.copy.over(src, target, replaceExisting = overwrite)
    }
  }

  private[sc4pac] class WrappedZip(archive: ZipFile) extends WrappedArchive[ZipArchiveEntry] {
    export archive.close
    import scala.jdk.CollectionConverters.*
    def iterateEntries = archive.getEntries.asScala
    def getEntryPath(entry: ZipArchiveEntry): String = entry.getName
    def isDirectory(entry: ZipArchiveEntry): Boolean = entry.isDirectory
    def isUnixSymlink(entry: ZipArchiveEntry) = entry.isUnixSymlink
    protected def extractEntry(entry: ZipArchiveEntry, target: os.Path, overwrite: Boolean): Unit =
      extractEntryCommons(archive.getInputStream(entry), target, overwrite)
  }

  // A single .dat/.dll/.sc4* file that can just be copied to the target with a
  // new name. The name of `file` itself is not meaningful as it comes from the URL in the file cache.
  private[sc4pac] class WrappedNonarchive(file: os.Path, filename: String) extends WrappedArchive[os.Path] {
    def close(): Unit = {}
    def iterateEntries = Iterator(file)
    def getEntryPath(entry: os.Path) = filename  // entry.last has no meaningful relevance
    def isDirectory(entry: os.Path) = false
    def isUnixSymlink(entry: os.Path) = false
    protected def extractEntry(src: os.Path, target: os.Path, overwrite: Boolean): Unit =
      os.copy.over(src, target, replaceExisting = overwrite)
  }

  private[sc4pac] class Wrapped7z(archive: SevenZFile) extends WrappedArchive[SevenZArchiveEntry] {
    export archive.close
    import scala.jdk.CollectionConverters.*
    def iterateEntries = archive.getEntries.iterator.asScala
    def getEntryPath(entry: SevenZArchiveEntry): String = entry.getName
    def isDirectory(entry: SevenZArchiveEntry): Boolean = entry.isDirectory
    def isUnixSymlink(entry: SevenZArchiveEntry) = false
    protected def extractEntry(entry: SevenZArchiveEntry, target: os.Path, overwrite: Boolean): Unit =
      extractEntryCommons(archive.getInputStream(entry), target, overwrite)
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
      protected def extractEntry(entry: Int, target: os.Path, overwrite: Boolean): Unit = throw new AssertionError

      override def extractSelectedEntries(entries: Chunk[(Int, os.Path, Validator)], overwrite: Boolean): Chunk[ExtractedItem] = try {
        val entriesMap: Map[Int, (Int, os.Path, Validator)] = entries.iterator.map(t => t._1 -> t).toMap  // TODO this does not have linear complexity
        val getPath: Int => os.Path = entriesMap(_)._2
        val validated = Chunk.newBuilder[ExtractedItem]

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
            validated += validator.validate(target)
          }

          def setCompleted(complete: Long): Unit = {}  // TODO logging of progress
          def setTotal(total: Long): Unit = {}
        })

        validated.result()
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
    def fromUrl[F[_]](archiveUrl: java.net.URI, jarsRoot: os.Path): JarExtraction = {
      // we hash the the URL to find a deterministic and short storage path
      val hash = JD.Checksum.bytesToString(
        java.security.MessageDigest.getInstance("SHA-1")
          .digest(archiveUrl.toString.getBytes("UTF-8"))
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

  /** A certificate to keep track of files whose checksums have been validated. */
  class ExtractedItem private[Extractor] (val path: os.Path, val validatedSha256: Option[collection.immutable.ArraySeq[Byte]])

  sealed trait Validator {
    def validate(path: os.Path): ExtractedItem
  }

  class ChecksumValidator(includeWithChecksum: JD.IncludeWithChecksum) extends Validator {
    def validate(path: os.Path): ExtractedItem = {
      val sha256Actual = Downloader.computeChecksum(path.toIO)
      if (includeWithChecksum.sha256 != sha256Actual) {
        throw ChecksumError("Extracted file has wrong sha256 checksum. " +
          "Usually, this means the uploaded file was modified after the channel metadata was last updated, " +
          "so the integrity of the file cannot be verified by sc4pac. " +
          "Report this to the maintainers of the metadata.",
          s"File: $path, got: ${JD.Checksum.bytesToString(sha256Actual)}, expected: ${JD.Checksum.bytesToString(includeWithChecksum.sha256)}")
      } else {
        ExtractedItem(path, validatedSha256 = Some(sha256Actual))
      }
    }
  }

  object DbpfValidator extends Validator {
    private val dbpfSignature = Array[Byte]('D', 'B', 'P', 'F')

    private def isDbpf(path: os.Path): Boolean = {
      val signature = os.read.bytes(path, offset = 0, count = 4)
      signature.sameElements(dbpfSignature)
    }

    def validate(path: os.Path): ExtractedItem = {
      if (!isDbpf(path))
        throw NotADbpfFile("Extracted file is not a DBPF file. " +
          "If this error is caused by a malformed DBPF file, report this to the original author of the file. " +
          "Otherwise, if it is a DLL file, the channel metadata must include a checksum for verifying file integrity. " +
          "Report this to the maintainers of the metadata.",
          s"File: $path")
      else ExtractedItem(path, validatedSha256 = None)
    }
  }

  // Nested archives currently do not use checksums for validation. If desired, the outer archive should use a checksum instead.
  object NoopValidator extends Validator {
    def validate(path: os.Path): ExtractedItem = ExtractedItem(path, validatedSha256 = None)
  }

}

class Extractor(logger: Logger) {

  /** Extracts files from the archive that match the inclusion/exclusion recipe, including one level of nested archives.
    * Returns the files that have been extracted and the patterns that have matched. */
  def extract(
    archive: java.io.File,
    fallbackFilename: Option[String],
    destination: os.Path,
    recipe: Extractor.InstallRecipe,
    jarExtractionOpt: Option[Extractor.JarExtraction],
    hints: Option[JD.ArchiveType],
    stagingRoot: os.Path,
    validate: Boolean,
  ): (Chunk[Extractor.ExtractedItem], Set[Pattern]) = try {
    val (usedPatternsBuilder, predicate, predicateNested) = recipe.makeAcceptancePredicates(validate = validate)  // tracks used patterns to warn about unused patterns
    val extractedFilesBuilder = Chunk.newBuilder[Extractor.ExtractedItem]
    scala.util.Using.resource(Extractor.WrappedArchive(archive, fallbackFilename, stagingRoot, logger, hints)) { wrappedArchive =>
      // first extract just the main files
      extractedFilesBuilder ++= wrappedArchive.extractByPredicate(destination, predicate, overwrite = true, flatten = false, logger)
      // additionally, extract jar files/nested archives contained in the zip file to a temporary location
      for (Extractor.JarExtraction(jarsDir) <- jarExtractionOpt) {
        logger.debug(s"Searching for nested archives:")
        val jarFiles = wrappedArchive.extractByPredicate(jarsDir, predicateNested, overwrite = false, flatten = true, logger)  // overwrite=false, as we want to extract any given jar only once per staging process
        // finally extract the jar files themselves (without recursively extracting jars contained inside)
        for (jarFile <- jarFiles) {
          logger.debug(s"Extracting nested archive ${jarFile.path.last}")
          val (fs, ps) = extract(jarFile.path.toIO, fallbackFilename = None, destination, recipe, jarExtractionOpt = None, hints, stagingRoot = stagingRoot, validate = validate)
          extractedFilesBuilder ++= fs
          usedPatternsBuilder ++= ps
        }
      }
    }
    (extractedFilesBuilder.result(), usedPatternsBuilder.result())
  } catch {
    case e: ExtractionFailed => throw e
    case e: (NotADbpfFile | ChecksumError) => throw new ExtractionFailed(s"Failed to extract $archive.", e.getMessage)
    case e: java.io.IOException => logger.debugPrintStackTrace(e); throw new ExtractionFailed(s"Failed to extract $archive.", e.getMessage)
  }
}
