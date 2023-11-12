package io.github.memo33
package sc4pac
package api

import zio.http.*
import zio.http.ChannelEvent.Read
import zio.{ZIO, Task, IO}
import upickle.default as UP

import sc4pac.JsonData as JD


class Api(scopeRoot: ScopeRoot, options: sc4pac.cli.Commands.ServerOptions) {

  private def jsonResponse[A : UP.Writer](obj: A): Response = Response.json(UP.write(obj, indent = options.indent))

  /** Sends a 400 ScopeNotInitialized if Plugins cannot be loaded. */
  private def withPluginsOr400(task: JD.Plugins => zio.UIO[Response]): zio.UIO[Response] = {
    JD.Plugins.read
      .provideLayer(zio.ZLayer.succeed(scopeRoot))
      .foldZIO(
        success = task,
        failure = (err: ErrStr) =>
          ZIO.succeed(jsonResponse(ScopeNotInitialized(message = "Scope not initialized", detail = err)).status(Status.BadRequest))
      )
  }

  def routes: Routes[Any, Nothing] = Routes(

    // Test the websocket using Javascript in webbrowser (messages are also logged in network tab):
    //     let ws = new WebSocket('ws://localhost:51515/update/ws'); ws.onmessage = function(e) { console.log(e) };
    //     ws.send('FOO')
    Method.GET / "update" / "ws" -> handler(withPluginsOr400(pluginsData =>
      Handler.webSocket { wsChannel =>
        for {
          pac <- Sc4pac.init(pluginsData.config)
            .provideSomeLayer(zio.ZLayer.succeed(??? : Logger))
            .provideSomeLayer(zio.ZLayer.succeed(??? : Prompter))
            .provideSomeLayer(zio.ZLayer.succeed(scopeRoot : ScopeRoot))
        } yield {
          ???  // TODO
          ()
        }
        // ZIO.unit
      }.toResponse
    )),

    // TODO for testing
    Method.GET / "hello" / string("name") -> handler { (name: String, req: Request) =>
      Response.text(s"Hello, $name.")
    }

  ).handleError(err => jsonResponse(ErrorGeneric(message = "Unhandled error", detail = err)).status(Status.InternalServerError))

}
