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
  private val readPluginsOr400: ZIO[ScopeRoot, Response, JD.Plugins] =
    JD.Plugins.read.mapError((err: ErrStr) => jsonResponse(ErrorMessage.ScopeNotInitialized("Scope not initialized", err)).status(Status.BadRequest))

  private def expectedFailureMessage(err: cli.Commands.ExpectedFailure): ErrorMessage = err match {
    case abort: error.Sc4pacVersionNotFound => ErrorMessage.VersionNotFound(abort.title, abort.detail)
    case abort: error.Sc4pacAssetNotFound => ErrorMessage.AssetNotFound(abort.title, abort.detail)
    case abort: error.ExtractionFailed => ErrorMessage.ExtractionFailed(abort.title, abort.detail)
    case abort: error.UnsatisfiableVariantConstraints => ErrorMessage.UnsatisfiableVariantConstraints(abort.title, abort.detail)
    case abort: error.DownloadFailed => ErrorMessage.DownloadFailed(abort.title, abort.detail)
    case abort: error.NoChannelsAvailable => ErrorMessage.NoChannelsAvailable(abort.title, abort.detail)
    case abort: error.Sc4pacAbort => ErrorMessage.Aborted("Operation aborted.", "")
  }

  private def expectedFailureStatus(err: cli.Commands.ExpectedFailure): Status = err match {
    case abort: error.Sc4pacVersionNotFound => Status.NotFound
    case abort: error.Sc4pacAssetNotFound => Status.NotFound
    case abort: error.ExtractionFailed => Status.InternalServerError
    case abort: error.UnsatisfiableVariantConstraints => Status.InternalServerError
    case abort: error.DownloadFailed => Status.BadGateway
    case abort: error.NoChannelsAvailable => Status.BadGateway
    case abort: error.Sc4pacAbort => Status.Ok  // this is not really an error, but the expected control flow
  }

  val jsonOk = jsonResponse(ResultMessage("OK"))

  private val httpLogger = {
    val cliLogger: Logger = CliLogger()
    zio.ZLayer.succeed(cliLogger)
  }

  /** Handles some errors and provides http logger (not used for websocket). */
  private def wrapHttpEndpoint(task: ZIO[ScopeRoot & Logger, Throwable | Response, Response]): ZIO[ScopeRoot, Throwable, Response] = {
    task.provideSomeLayer(httpLogger)
      .catchAll {
        case response: Response => ZIO.succeed(response)
        case err: cli.Commands.ExpectedFailure => ZIO.succeed(jsonResponse(expectedFailureMessage(err)).status(expectedFailureStatus(err)))
        case t: Throwable => ZIO.fail(t)
      }
  }

  private def parseModuleOr400(module: String): IO[Response, BareModule] = {
    ZIO.fromEither(Sc4pac.parseModule(module))
      .mapError(err => jsonResponse(ErrorMessage.BadRequest(s"Malformed package name: $module", err)).status(Status.BadRequest))
  }

  def routes: Routes[ScopeRoot, Nothing] = Routes(

    // 200, 400, 405
    Method.GET / "init" -> handler { (req: Request) =>
      wrapHttpEndpoint {
        def errResp: zio.URIO[ScopeRoot, Response] =
          for {
            defaultPlugins <- JD.Plugins.defaultPluginsRoot
            defaultCache <- JD.Plugins.defaultCacheRoot
          } yield jsonResponse(
            ErrorMessage.BadInit("""Query parameters "plugins" and "cache" are required.""",
              detail = "Pass the locations of the folders as URL-encoded query parameters.",
              platformDefaults = Map("plugins" -> defaultPlugins.map(_.toString), "cache" -> defaultCache.map(_.toString))
            )
          ).status(Status.BadRequest)

        for {
          scopeRoot   <- ZIO.serviceWith[ScopeRoot](_.path)
          _           <- ZIO.attemptBlockingIO(os.exists(JD.Plugins.path(scopeRoot)) || os.exists(JD.PluginsLock.path(scopeRoot)))
                           .filterOrFail(_ == false)(jsonResponse(
                             ErrorMessage.InitNotAllowed("Scope already initialized.",
                               "Manually delete the corresponding .json files if you are sure you want to initialize a new scope.")
                           ).status(Status.MethodNotAllowed))
          pluginsRoot <- ZIO.fromOption(req.url.queryParams.get("plugins").map(p => os.Path(p, scopeRoot))).orElse(errResp.flip)
          cacheRoot   <- ZIO.fromOption(req.url.queryParams.get("cache").map(p => os.Path(p, scopeRoot))).orElse(errResp.flip)
          _           <- ZIO.attemptBlockingIO {
                           os.makeDir.all(pluginsRoot)  // TODO ask for confirmation?
                           os.makeDir.all(cacheRoot)
                         }
          pluginsData <- JD.Plugins.init(pluginsRoot = pluginsRoot, cacheRoot = cacheRoot)
        } yield jsonOk
      }
    },

    // 200, 400
    Method.GET / "add" / string("pkg") -> handler { (pkg: String, req: Request) =>
      wrapHttpEndpoint {
        for {
          mod         <- parseModuleOr400(pkg)
          pluginsData <- readPluginsOr400
          pac         <- Sc4pac.init(pluginsData.config)
          _           <- pac.add(Seq(mod))
        } yield jsonOk
      }
    },

    // 200, 400
    Method.GET / "remove" / string("pkg") -> handler { (pkg: String, req: Request) =>
      wrapHttpEndpoint {
        for {
          mod         <- parseModuleOr400(pkg)
          pluginsData <- readPluginsOr400
          pac         <- Sc4pac.init(pluginsData.config)
          _           <- pac.remove(Seq(mod))
        } yield jsonOk
      }
    },

    // Test the websocket using Javascript in webbrowser (messages are also logged in network tab):
    //     let ws = new WebSocket('ws://localhost:51515/update/ws'); ws.onmessage = function(e) { console.log(e) };
    //     ws.send('FOO')
    Method.GET / "update" / "ws" -> handler {
      readPluginsOr400.foldZIO(
        failure = ZIO.succeed[Response](_),
        success = pluginsData =>
          Handler.webSocket { wsChannel =>
            val updateTask: zio.RIO[ScopeRoot & WebSocketLogger, Message] =
              for {
                pac          <- Sc4pac.init(pluginsData.config)
                pluginsRoot  <- pluginsData.config.pluginsRootAbs
                wsLogger     <- ZIO.service[WebSocketLogger]
                flag         <- pac.update(pluginsData.explicit, globalVariant0 = pluginsData.config.variant, pluginsRoot = pluginsRoot)
                                  .provideSomeLayer(zio.ZLayer.succeed(WebSocketPrompter(wsChannel, wsLogger)))
              } yield ResultMessage("OK")

            val wsTask: zio.RIO[ScopeRoot, Unit] =
              WebSocketLogger.run(send = msg => wsChannel.send(Read(jsonFrame(msg)))) {
                for {
                  finalMsg <- updateTask.catchSome { case err: cli.Commands.ExpectedFailure => ZIO.succeed(expectedFailureMessage(err)) }
                  unit     <- ZIO.serviceWithZIO[WebSocketLogger](_.sendMessageAwait(finalMsg))
                } yield unit
              } // wsLogger is shut down here (TODO use resource for safer closing)

            wsTask.zipRight(wsChannel.shutdown).map(_ => System.err.println("Shutting down websocket."))
          }.toResponse: zio.URIO[ScopeRoot, Response]
      )
    },

    // 200, 400, 404
    Method.GET / "info" / string("pkg") -> handler { (pkg: String, req: Request) =>
      wrapHttpEndpoint {
        for {
          mod           <- parseModuleOr400(pkg)
          pluginsData   <- readPluginsOr400
          pac           <- Sc4pac.init(pluginsData.config)
          infoResultOpt <- pac.infoJson(mod)  // TODO avoid decoding/encoding json
        } yield infoResultOpt match {
          case None => jsonResponse(ErrorMessage.PackageNotFound("Package not found.", pkg)).status(Status.NotFound)
          case Some(pkgData) => jsonResponse(pkgData)
        }
      }
    },

    // 200, 400
    Method.GET / "list" -> handler {
      wrapHttpEndpoint {
        for {
          pluginsData   <- readPluginsOr400
          installedIter <- cli.Commands.List.iterateInstalled(pluginsData)
        } yield {
          jsonResponse(installedIter.map { case (mod, explicit) =>
            InstalledPkg(mod.toBareDep, variant = mod.variant, version = mod.version, explicit = explicit)
          }.toSeq)
        }
      }
    }

  ).handleError(err => jsonResponse(ErrorMessage.ServerError("Unhandled error.", err.getMessage)).status(Status.InternalServerError))

}
