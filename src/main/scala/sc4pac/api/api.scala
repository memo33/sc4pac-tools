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
  private def withPluginsOr400[R](task: JD.Plugins => zio.RIO[R, Response]): zio.RIO[R & ScopeRoot, Response] = {
    JD.Plugins.read
      .foldZIO(
        success = task,
        failure = (err: ErrStr) =>
          ZIO.succeed(jsonResponse(ErrorMessage.ScopeNotInitialized("Scope not initialized", err)).status(Status.BadRequest))
      )
  }

  def expectedFailureMessage(err: cli.Commands.ExpectedFailure): ErrorMessage = err match {
    case abort: error.Sc4pacVersionNotFound => ErrorMessage.VersionNotFound(abort.title, abort.detail)
    case abort: error.Sc4pacAssetNotFound => ErrorMessage.AssetNotFound(abort.title, abort.detail)
    case abort: error.ExtractionFailed => ErrorMessage.ExtractionFailed(abort.title, abort.detail)
    case abort: error.UnsatisfiableVariantConstraints => ErrorMessage.UnsatisfiableVariantConstraints(abort.title, abort.detail)
    case abort: error.DownloadFailed => ErrorMessage.DownloadFailed(abort.title, abort.detail)
    case abort: error.NoChannelsAvailable => ErrorMessage.NoChannelsAvailable(abort.title, abort.detail)
    case abort: error.Sc4pacAbort => ErrorMessage.Aborted("Operation aborted.", "")
  }

  def expectedFailureStatus(err: cli.Commands.ExpectedFailure): Status = err match {
    case abort: error.Sc4pacVersionNotFound => Status.NotFound
    case abort: error.Sc4pacAssetNotFound => Status.NotFound
    case abort: error.ExtractionFailed => Status.InternalServerError
    case abort: error.UnsatisfiableVariantConstraints => Status.BadRequest
    case abort: error.DownloadFailed => Status.InternalServerError
    case abort: error.NoChannelsAvailable => Status.BadRequest
    case abort: error.Sc4pacAbort => Status.BadRequest
  }

  def routes: Routes[ScopeRoot, Nothing] = Routes(

    // Test the websocket using Javascript in webbrowser (messages are also logged in network tab):
    //     let ws = new WebSocket('ws://localhost:51515/update/ws'); ws.onmessage = function(e) { console.log(e) };
    //     ws.send('FOO')
    Method.GET / "update" / "ws" -> handler(withPluginsOr400(pluginsData =>
      Handler.webSocket { wsChannel =>
        val updateTask: zio.RIO[ScopeRoot & WebSocketLogger, Message] =
          for {
            pac          <- Sc4pac.init(pluginsData.config)
            pluginsRoot  <- pluginsData.config.pluginsRootAbs
            logger       <- ZIO.service[WebSocketLogger]
            flag         <- pac.update(pluginsData.explicit, globalVariant0 = pluginsData.config.variant, pluginsRoot = pluginsRoot)
                              .provideSomeLayer(zio.ZLayer.succeed(WebSocketPrompter(wsChannel, logger)))
          } yield ResultMessage("OK")

        val wsTask: zio.RIO[ScopeRoot, Unit] =
          WebSocketLogger.run(send = msg => wsChannel.send(Read(jsonFrame(msg)))) {
            for {
              finalMsg <- updateTask.catchSome { case err: cli.Commands.ExpectedFailure => ZIO.succeed(expectedFailureMessage(err)) }
              unit     <- ZIO.serviceWithZIO[WebSocketLogger](_.sendMessageAwait(finalMsg))
            } yield unit
          } // logger is shut down here (TODO use resource for safer closing)

        wsTask.zipRight(wsChannel.shutdown).map(_ => System.err.println("Shutting down websocket."))
      }.toResponse
    )),

    Method.GET / "info" / string("pkg") -> handler { (pkg: String, req: Request) =>
      // TODO unquote pkg?
      Sc4pac.parseModule(pkg) match {
        case Left(err) => ZIO.succeed(jsonResponse(ErrorMessage.BadRequest(s"Malformed package name: $pkg", err)).status(Status.BadRequest))
        case Right(mod) => withPluginsOr400(pluginsData =>
          for {
            pac           <- Sc4pac.init(pluginsData.config)
                               .provideSomeLayer(zio.ZLayer.succeed(CliLogger()))
            infoResultOpt <- pac.infoJson(mod)  // TODO avoid decoding/encoding json
          } yield {
            infoResultOpt match {
              case None => jsonResponse(ErrorMessage.PackageNotFound("Package not found.", pkg)).status(Status.NotFound)
              case Some(pkgData) => jsonResponse(pkgData)
            }
          }
        ).catchSome { case err: cli.Commands.ExpectedFailure =>
          ZIO.succeed(jsonResponse(expectedFailureMessage(err)).status(expectedFailureStatus(err)))
        }
      }
    },

  ).handleError(err => jsonResponse(ErrorMessage.ServerError("Unhandled error.", err.getMessage)).status(Status.InternalServerError))

}
