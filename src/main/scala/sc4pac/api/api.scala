package io.github.memo33
package sc4pac
package api

import zio.http.*
import zio.http.ChannelEvent.Read
import zio.{ZIO, Task, IO}
import upickle.default as UP

import sc4pac.JsonData as JD


class Api(options: sc4pac.cli.Commands.ServerOptions) {

  private def jsonResponse[A : UP.Writer](obj: A): Response = Response.json(UP.write(obj, indent = options.indent))

  /** Sends a 400 ScopeNotInitialized if Plugins cannot be loaded. */
  private def withPluginsOr400[R](task: JD.Plugins => zio.URIO[R, Response]): zio.URIO[R & ScopeRoot, Response] = {
    JD.Plugins.read
      .foldZIO(
        success = task,
        failure = (err: ErrStr) =>
          ZIO.succeed(jsonResponse(ScopeNotInitialized(message = "Scope not initialized", detail = err)).status(Status.BadRequest))
      )
  }

  def expectedFailureMessage(err: cli.Commands.ExpectedFailure): Message = ???

  def routes: Routes[ScopeRoot, Nothing] = Routes(

    // Test the websocket using Javascript in webbrowser (messages are also logged in network tab):
    //     let ws = new WebSocket('ws://localhost:51515/update/ws'); ws.onmessage = function(e) { console.log(e) };
    //     ws.send('FOO')
    Method.GET / "update" / "ws" -> handler(withPluginsOr400(pluginsData =>
      Handler.webSocket { wsChannel =>
        val logger = (??? : WebSocketLogger)  // TODO shutdown logger
        val task: zio.RIO[ScopeRoot, Message] =
          for {
            pac          <- Sc4pac.init(pluginsData.config)
                              .provideSomeLayer(zio.ZLayer.succeed(logger))
            pluginsRoot  <- pluginsData.config.pluginsRootAbs
            flag         <- pac.update(pluginsData.explicit, globalVariant0 = pluginsData.config.variant, pluginsRoot = pluginsRoot)
                              .provideSomeLayer(zio.ZLayer.succeed(??? : Prompter))
          } yield ResultMessage("OK")

        task
          .catchSome { case err: cli.Commands.ExpectedFailure => ZIO.succeed(expectedFailureMessage(err)) }
          .flatMap(logger.sendMessageAwait(_)): zio.RIO[ScopeRoot, Unit]
        // TODO manually shut down websocket?
      }.toResponse
    )),

    // TODO for testing
    Method.GET / "hello" / string("name") -> handler { (name: String, req: Request) =>
      Response.text(s"Hello, $name.")
    }

  ).handleError(err => jsonResponse(ErrorGeneric(message = "Unhandled error", detail = err)).status(Status.InternalServerError))

}
