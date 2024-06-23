package io.github.memo33.sc4pac

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import upickle.default as UP

class ApiSpec extends AnyWordSpec with Matchers {

  "DownloadLength message" should {
    "JSON-encode long value as String if > 2^53" in {
      val msg0 = api.ProgressMessage.DownloadLength(url = "url", length = 42)
      UP.write(msg0) shouldBe """{"$type":"/progress/download/length","url":"url","length":42}"""
      val msg1 = api.ProgressMessage.DownloadLength(url = "url", length = (1L << 53) + 0)
      UP.write(msg1) shouldBe """{"$type":"/progress/download/length","url":"url","length":9007199254740992}"""
      val msg2 = api.ProgressMessage.DownloadLength(url = "url", length = (1L << 53) + 1)
      UP.write(msg2) shouldBe """{"$type":"/progress/download/length","url":"url","length":"9007199254740993"}"""
    }
  }
}
