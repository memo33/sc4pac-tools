package io.github.memo33.sc4pac

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import upickle.default as UP

class DownloaderSpec extends AnyWordSpec with Matchers {

  "Downloads" should {
    "run in parallel (2 max) and be interruptible" in {
      val pool = Sc4pac.createThreadPool()
      val deltaMs = 100
      val count = java.util.concurrent.atomic.AtomicInteger(0)

      def singleDownload() =
        Downloader.attemptCancelableOnPool(pool, isCanceled => {
          // a BLOCKING computation
          var i = 0
          while (i < 20 && !isCanceled.get()) {
            Thread.sleep(deltaMs)
            count.getAndIncrement()
            ()
          }
          Right(())
        })

      val manyDownloads = zio.ZIO.foreachPar(1 to 6)(_ => singleDownload())

      // After 10 ticks (of 20), `manyDownloads` will be interrupted.
      // By then, only 2 (of 6) downloads launched on the pool, so we expect
      // `count` to be about 2 * 10.
      unsafeRun(
        manyDownloads.race(zio.ZIO.sleep(zio.Duration.fromMillis(deltaMs * 10 + deltaMs/2)))
      )

      count.get() shouldBe (20 +- 10)  // large tolerance for robustness of test
      // (If cancelation did not work, we would expect count == 40.
      // If pool size did not work, we would expect count == 60.)
    }
  }
}
