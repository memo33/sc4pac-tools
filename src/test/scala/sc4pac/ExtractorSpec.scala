package io.github.memo33.sc4pac

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import net.sf.sevenzipjbinding as SZ

import Extractor.{WrappedArchive, WrappedFolder, Wrapped7z, native, WrappedZip, WrappedNonarchive}
import JsonData as JD

class ExtractorSpec extends AnyWordSpec with Matchers {

  /** For testing, create archive file using native SZ library. */
  def createArchive(in: os.Path, archiveFile: os.Path, format: SZ.ArchiveFormat): Unit = {
    val files: IndexedSeq[os.Path] = os.walk(in)

    class MyCreateCallback extends SZ.IOutCreateCallback[SZ.IOutItemAllFormats] {
      def setOperationResult(operationResultOk: Boolean): Unit = {}
      def setTotal(total: Long): Unit = {}
      def setCompleted(complete: Long): Unit = {}

      def getItemInformation(i: Int, outItemFactory: SZ.impl.OutItemFactory[SZ.IOutItemAllFormats]): SZ.IOutItemAllFormats = {
        val item = outItemFactory.createOutItem()
        if (os.isDir(files(i))) {
          item.setPropertyIsDir(true)
        } else {
          item.setDataSize(os.size(files(i)))
        }
        item.setPropertyPath(files(i).subRelativeTo(in).toString())
        item
      }

      def getStream(i: Int): SZ.ISequentialInStream = {
        if (!os.isFile(files(i))) {
          null
        } else {
          new SZ.impl.InputStreamSequentialInStream(os.read.inputStream(files(i)))
        }
      }
    }

    scala.util.Using.resource(SZ.SevenZip.openOutArchive(format)) { outArchive =>
      if (outArchive.isInstanceOf[SZ.IOutFeatureSetLevel]) {
        outArchive.asInstanceOf[SZ.IOutFeatureSetLevel].setLevel(5)
      }
      scala.util.Using.resource(new java.io.RandomAccessFile(archiveFile.toIO, "rw")) { raf =>
        outArchive.createArchive(new SZ.impl.RandomAccessFileOutStream(raf), files.size, new MyCreateCallback())
      }
    }
  }

  def withTempDir(testCode: (os.Path) => Any): Any = {
    val tmpDir = os.temp.dir(os.pwd / "target", prefix = "test-tmp")
    try testCode(tmpDir)
    finally os.remove.all(tmpDir)
  }

  def createSampleFiles(in: os.Path): Seq[os.Path] = {
    val files = Seq(
      in / "common" / "prefix" / "a.SC4Model" -> "DBPF dummy",
      in / "common" / "prefix" / "b" / "dollars$$ and spaces.SC4Lot" -> "DBPF dummy",
      in / "common" / "prefix" / "c" / "c" / "c.SC4Lot" -> "DBPF dummy",
      in / "common" / "prefix" / "c" / "d" / "d.SC4Lot" -> "DBPF dummy",
      in / "common" / "prefix" / "c" / "e.SC4Model" -> "DBPF dummy",
      in / "common" / "prefix" / "c" / "f.SC4Desc" -> "DBPF dummy",
      in / "common" / "prefix" / "c" / "g.dat" -> "DBPF dummy",
      in / "common" / "prefix" / "c" / "h.dll" -> "MZ dll dummy",
    )
    val ignoredFiles = Seq(
      in / "readme.txt" -> "dummy",
      in / "common" / "readme.md" -> "dummy",
      in / "common" / "prefix" / "license" -> "dummy",
    )
    for ((f, content) <- (files ++ ignoredFiles)) {
      os.makeDir.all(f / os.up)
      os.write(f, content)
    }
    files.map(_._1)
  }

  def withSampleFiles(testCode: (os.Path, os.Path, Seq[os.Path]) => Any): Any = withTempDir { (tmpDir) =>
    val out = tmpDir / "out"
    val in = tmpDir / "in"
    val files = createSampleFiles(in)
    testCode(in, out, files)
  }

  val assetRef = JD.AssetReference("dummy", withChecksum = Seq(JD.IncludeWithChecksum("h.dll", JD.Checksum.stringToBytes("07a3036cfb4e1705969b4fa8ccf5e28eac0fa6df598b81e020592d6a6041a9fd"))))

  def createPredicate() = {
    val (recipe, warnings) = JD.InstallRecipe.fromAssetReference(assetRef)
    val (usedPatternsBuilder, predicate) = recipe.makeAcceptancePredicate()
    predicate
  }

  "Extraction" should {
    "support mixed levels of subfolders (Clickteam/folder)" in withSampleFiles { (in, out, files) =>
      val wrappedArchive = new WrappedFolder(in)  // Clickteam installers are first fully extracted into a folder which is then wrapped
      wrappedArchive.extractByPredicate(out, createPredicate(), overwrite = true, flatten = false, CliLogger())
      os.exists(out).shouldBe(true)
      os.walk(out).filter(os.isFile(_)).map(_.subRelativeTo(out)).sorted
        .shouldBe(files.map(_.subRelativeTo(in / "common" / "prefix")).sorted)
    }

    "support mixed levels of subfolders (7z)" in withSampleFiles { (in, out, files) =>
      val archiveFile = in / os.up / "in.7z"
      createArchive(in, archiveFile, SZ.ArchiveFormat.SEVEN_ZIP)
      val wrappedArchive = new Wrapped7z(new org.apache.commons.compress.archivers.sevenz.SevenZFile(archiveFile.toIO))
      wrappedArchive.extractByPredicate(out, createPredicate(), overwrite = true, flatten = false, CliLogger())
      os.exists(out).shouldBe(true)
      os.walk(out).filter(os.isFile(_)).map(_.subRelativeTo(out)).sorted
        .shouldBe(files.map(_.subRelativeTo(in / "common" / "prefix")).sorted)
    }

    for ((format, suffix) <- Seq(SZ.ArchiveFormat.SEVEN_ZIP -> "7z", SZ.ArchiveFormat.TAR -> "tar")) {
      s"support mixed levels of subfolders ($suffix native)" in withSampleFiles { (in, out, files) =>
        val archiveFile = in / os.up / s"in.$suffix"
        createArchive(in, archiveFile, format)
        val wrappedArchive = native.Wrapped7zNative(archiveFile.toIO)
        wrappedArchive.extractByPredicate(out, createPredicate(), overwrite = true, flatten = false, CliLogger())
        os.exists(out).shouldBe(true)
        os.walk(out).filter(os.isFile(_)).map(_.subRelativeTo(out)).sorted
          .shouldBe(files.map(_.subRelativeTo(in / "common" / "prefix")).sorted)
      }
    }

    "support mixed levels of subfolders (zip)" in withSampleFiles { (in, out, files) =>
      val archiveFile = in / os.up / "in.zip"
      os.zip(archiveFile, Seq(in))  // using SZ would fail inexplicably
      val wrappedArchive = new WrappedZip(new org.apache.commons.compress.archivers.zip.ZipFile(archiveFile.toIO))
      wrappedArchive.extractByPredicate(out, createPredicate(), overwrite = true, flatten = false, CliLogger())
      os.exists(out).shouldBe(true)
      os.walk(out).filter(os.isFile(_)).map(_.subRelativeTo(out)).sorted
        .shouldBe(files.map(_.subRelativeTo(in / "common" / "prefix")).sorted)
    }

    "support plain files" in withSampleFiles { (in, out, files) =>
      for (f <- files) {
        val wrappedArchive = new WrappedNonarchive(f, f.last)
        wrappedArchive.extractByPredicate(out, createPredicate(), overwrite = true, flatten = false, CliLogger())
      }
      os.exists(out).shouldBe(true)
      os.walk(out).filter(os.isFile(_)).map(_.subRelativeTo(out)).sorted
        .shouldBe(files.map(f => f.subRelativeTo(f / os.up)).sorted)
    }

    for (suffix <- Seq("jar", "ZIP")) {
      s"support nested archives ($suffix)" in withSampleFiles { (in, out, files) =>
        val nestedFile = in / os.up / s"in.$suffix"
        os.zip(nestedFile, Seq(in))
        val archiveFile = in / os.up / s"in.$suffix.zip"
        os.zip(archiveFile, Seq(nestedFile))
        val jarsDir = in / os.up / "jars"
        os.makeDir.all(jarsDir)

        val recipe = JD.InstallRecipe.fromAssetReference(assetRef)._1
        Extractor(CliLogger())
          .extract(archiveFile.toIO, fallbackFilename = None, out, recipe, Some(Extractor.JarExtraction(jarsDir)), hints = None, stagingRoot = out)

        os.exists(out).shouldBe(true)
        os.walk(out).filter(os.isFile(_)).map(_.subRelativeTo(out)).sorted
          .shouldBe(files.map(_.subRelativeTo(in / "common" / "prefix")).sorted)
        os.walk(jarsDir).filter(os.isFile(_)).map(_.subRelativeTo(jarsDir)).sorted
          .shouldBe(Seq(os.SubPath(nestedFile.last)))
      }
    }

    s"not support more than 1 level of nesting" in withSampleFiles { (in, out, files) =>
      val nestedFile = in / os.up / s"in.zip"
      os.zip(nestedFile, Seq(in))
      val nestedFile2 = in / os.up / s"in.zip.jar"
      os.zip(nestedFile2, Seq(nestedFile))
      val archiveFile = in / os.up / s"in.zip.jar.zip"
      os.zip(archiveFile, Seq(nestedFile2))
      val jarsDir = in / os.up / "jars"
      os.makeDir.all(jarsDir)

      val recipe = JD.InstallRecipe.fromAssetReference(assetRef)._1
      Extractor(CliLogger())
        .extract(archiveFile.toIO, fallbackFilename = None, out, recipe, Some(Extractor.JarExtraction(jarsDir)), hints = None, stagingRoot = out)

      os.exists(out).shouldBe(false)
    }

  }
}
