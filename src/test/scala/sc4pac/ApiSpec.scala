package io.github.memo33.sc4pac

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import upickle.default as UP
import zio.http.{Method, Path}
import api.AuthScope

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

  "API endpoints" should {
    "have correct permission scopes" in {
      val routes = api.Api(cli.Commands.ServerOptions()).tokenRoutes0
      val realScope = routes.map{ case (route, scope) => route.routePattern -> scope }.toMap

      val regexEndpoint = raw"^([\*†‡])(GET|POST)\s+/([^?\s]+).*".r
      val expectedScope =
        os.read.lines.stream(os.pwd / "api.md").dropWhile(!_.startsWith("```")).drop(1).takeWhile(!_.startsWith("```")).toSeq
          .collect { case regexEndpoint(scope, method, path) =>
            val s = scope match { case "*" => AuthScope.write; case "†" => AuthScope.read; case "‡" => AuthScope.open; case _ => throw new AssertionError }
            val m = method match { case "GET" => Method.GET; case "POST" => Method.POST; case _ => throw new AssertionError }
            (m / path).asInstanceOf[zio.http.RoutePattern[?]] -> s
          }.toMap

      (routes.length > 30).shouldBe(true)

      for ((route, scope) <- expectedScope) {
        withClue(route.toString) {
          realScope(route).shouldBe(scope)
        }
      }
      // conversely
      for ((route, scope) <- realScope) {
        withClue(route.toString) {
          expectedScope(route).shouldBe(scope)
        }
      }
    }
  }

}
