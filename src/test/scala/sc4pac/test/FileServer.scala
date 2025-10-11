package io.github.memo33.sc4pac
package test

import zio.ZIO
import zio.http.*

final class FileServer private (val port: Int)

object FileServer {

  def routes(staticDir: os.Path): Routes[Any, Nothing] = Routes(

    Method.GET / trailing -> Handler.fromFunctionHandler[(zio.http.Path, Request)] { case (path: zio.http.Path, _: Request) =>
      val fileZio =
        for {
          _ <- ZIO.sleep(zio.Duration.fromMillis(10))  // an attempt to avoid the http race conditions in zio-http/netty, see https://github.com/zio/zio-http/issues/3395
          subpath <- ZIO.fromTry(scala.util.Try(os.SubPath("." + path.encode)))
        } yield (staticDir / subpath).toIO
      Handler.fromFileZIO(fileZio).orElse(Handler.notFound).contramap(_._2)
    },

  ).sandbox // @@ HandlerAspect.requestLogging()

  def serve(staticDir: os.Path, port: Int): zio.ZLayer[Any, Nothing, FileServer] =
    zio.ZLayer.scoped {
      for {
        _      <- ZIO.succeed(println(s"Launching test file server on port $port"))
        fiber  <- Server.install(routes(staticDir))
                    .zipRight(ZIO.never)
                    .provideSome(
                      Server.defaultWith(_.port(port).requestStreaming(Server.RequestStreaming.Enabled))
                    )
                    .forkScoped
        _      <- ZIO.addFinalizer(ZIO.succeed {
                    println(s"Test file server on port $port has been shut down")
                  })
      } yield FileServer(port = port)
    }

}
