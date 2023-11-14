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
  private def jsonFrame[A : UP.Writer](obj: A): WebSocketFrame = WebSocketFrame.Text(UP.write(obj, indent = options.indent))

  /** Sends a 400 ScopeNotInitialized if Plugins cannot be loaded. */
  private def withPluginsOr400[R](task: JD.Plugins => zio.URIO[R, Response]): zio.URIO[R & ScopeRoot, Response] = {
    JD.Plugins.read
      .foldZIO(
        success = task,
        failure = (err: ErrStr) =>
          ZIO.succeed(jsonResponse(ErrorMessage.ScopeNotInitialized(message = "Scope not initialized", detail = err)).status(Status.BadRequest))
      )
  }

  def expectedFailureMessage(err: cli.Commands.ExpectedFailure): Message = ???

  def routes: Routes[ScopeRoot, Nothing] = Routes(

    // Test the websocket using Javascript in webbrowser (messages are also logged in network tab):
    //     let ws = new WebSocket('ws://localhost:51515/update/ws'); ws.onmessage = function(e) { console.log(e) };
    //     ws.send('FOO')
    Method.GET / "update" / "ws" -> handler(withPluginsOr400(pluginsData =>
      Handler.webSocket { wsChannel =>
        WebSocketLogger.run(send = msg => wsChannel.send(Read(jsonFrame(msg)))) {
          val updateTask: zio.RIO[ScopeRoot & WebSocketLogger, Message] =
            for {
              pac          <- Sc4pac.init(pluginsData.config)
              pluginsRoot  <- pluginsData.config.pluginsRootAbs
              logger       <- ZIO.service[WebSocketLogger]
              flag         <- pac.update(pluginsData.explicit, globalVariant0 = pluginsData.config.variant, pluginsRoot = pluginsRoot)
                                .provideSomeLayer(zio.ZLayer.succeed(WebSocketPrompter(wsChannel, logger)))
            } yield ResultMessage("OK")

          for {
            finalMsg <- updateTask.catchSome { case err: cli.Commands.ExpectedFailure => ZIO.succeed(expectedFailureMessage(err)) }
            unit     <- ZIO.serviceWithZIO[WebSocketLogger](_.sendMessageAwait(finalMsg))
          } yield unit: Unit
        } // logger is shut down here (TODO use resource for safer closing)
        // TODO manually shut down websocket?
      }.toResponse
    )),

    // TODO for testing
    Method.GET / "hello" / string("name") -> handler { (name: String, req: Request) =>
      Response.text(s"Hello, $name.")
    }

  ).handleError(err => jsonResponse(ErrorMessage.Generic(message = "Unhandled error", detail = err)).status(Status.InternalServerError))

}
