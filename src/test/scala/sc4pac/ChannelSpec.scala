package io.github.memo33.sc4pac

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import JsonData as JD

class ChannelSpec extends AnyWordSpec with Matchers {

  def withTempDir(testCode: (os.Path) => Any): Any = {
    val tmpDir = os.temp.dir(os.pwd / "target", prefix = "test-tmp")
    try testCode(tmpDir)
    finally os.remove.all(tmpDir)
  }

  def createYamlFiles(tmpDir: os.Path): Seq[os.Path] = {
    val texts = Seq(
"""
group: test
name: pkg1
version: "1"
subfolder: dummy
dependencies:
- test:pkg2
- ext:pkg101
- ext:pkg102
assets:
- assetId: asset1
- assetId: ext-asset101
---
group: test
name: pkg2
version: "2"
subfolder: dummy
dependencies:
- ext:pkg102
assets:
- assetId: asset2
- assetId: ext-asset102
---
assetId: asset1
version: "1"
url: dummy
---
assetId: asset2
version: "2"
url: dummy
""",
"""
group: ext
name: pkg101
version: "1"
subfolder: dummy
assets:
- assetId: ext-asset101
---
group: ext
name: pkg102
subfolder: dummy
version: "2"
---
assetId: ext-asset101
version: "1"
url: dummy
"""
    )
    for ((text, idx) <- texts.zipWithIndex) yield {
      val target = tmpDir / s"$idx.yaml"
      os.write(target, text)
      target
    }
  }

  "Channel" should {
    "list inter-channel external package dependencies" in withTempDir { (tmpDir: os.Path) =>
      val yamlFiles = createYamlFiles(tmpDir)
      val repos = yamlFiles.map(path =>
        unsafeRun(MetadataRepository.create(path, path.toNIO.toUri())))

      repos(0).channel.contents
        .map(_.toBareDep.orgName)
        .toSet
        .shouldBe(Set(
          "test:pkg1",
          "test:pkg2",
          s"${Constants.sc4pacAssetOrg.value}:asset1",
          s"${Constants.sc4pacAssetOrg.value}:asset2",
        ))

      repos(0).channel.externalPackages
        .map(p => s"${p.group}:${p.name}")
        .shouldBe(Seq("ext:pkg101", "ext:pkg102"))

      repos(0).channel.externalAssets
        .map(_.assetId)
        .shouldBe(Seq("ext-asset101", "ext-asset102"))

      repos(1).channel.externalPackages should have length 0
      repos(1).channel.externalAssets should have length 0

      val logger = CliLogger()
      val cache = FileCache((tmpDir / "cache").toIO, logger, Sc4pac.createThreadPool())
      val context = new ResolutionContext(repos, cache, logger, tmpDir / "profile")
      val layer = zio.ZLayer.succeed(context)
      import JsonData.bareModuleRw

      unsafeRun(
        Find.requiredByExternal(JD.bareModuleRead("ext:pkg101"))
          .provideSomeLayer(layer)
      ).head._2
        .map(_.orgName).toSet.shouldBe(Set("test:pkg1"))

      unsafeRun(
        Find.requiredByExternal(JD.bareModuleRead("ext:pkg102"))
          .provideSomeLayer(layer)
      ).head._2
        .map(_.orgName).toSet.shouldBe(Set("test:pkg1", "test:pkg2"))
    }
  }

}
