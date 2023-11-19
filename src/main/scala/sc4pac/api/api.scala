package io.github.memo33
package sc4pac
package api

import zio.http.*
import zio.http.ChannelEvent.Read
import zio.{ZIO, Task, IO}
import upickle.default as UP

import sc4pac.JsonData as JD
import JD.bareModuleRw


class Api(options: sc4pac.cli.Commands.ServerOptions) {

  private def jsonResponse[A : UP.Writer](obj: A): Response = Response.json(UP.write(obj, indent = options.indent))
  private def jsonFrame[A : UP.Writer](obj: A): WebSocketFrame = WebSocketFrame.Text(UP.write(obj, indent = options.indent))

  /** Sends a 409 ScopeNotInitialized if Plugins cannot be loaded. */
  private val readPluginsOr409: ZIO[ScopeRoot, Response, JD.Plugins] =
    JD.Plugins.read.mapError((err: ErrStr) => jsonResponse(ErrorMessage.ScopeNotInitialized("Scope not initialized", err)).status(Status.Conflict))

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

  val jsonOk = jsonResponse(ResultMessage(ok = true))

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

  private def parseModulesOr400(body: Body): IO[Response, Seq[BareModule]] =
    parseOr400[Seq[BareModule]](body, ErrorMessage.BadRequest(
      "Malformed package names", """Pass an array of strings of the form '<group>:<name>'."""
    ))

  private def parseOr400[A : UP.Reader](body: Body, errMsg: => ErrorMessage): IO[Response, A] = {
    body.asArray
      .flatMap(bytes => ZIO.attempt { UP.read[A](bytes) })
      .mapError(err => jsonResponse(errMsg).status(Status.BadRequest))
  }

  private def validateOr400[A](labels: Seq[A])(p: A => Boolean)(errMsg: Seq[A] => ErrorMessage): IO[Response, Unit] = {
    ZIO.validateParDiscard(labels)(label => if (p(label)) ZIO.unit else ZIO.fail(label))
      .mapError(failedLabels => jsonResponse(errMsg(failedLabels)).status(Status.BadRequest))
  }

  def routes: Routes[ScopeRoot, Nothing] = Routes(

    // 200, 400, 409
    Method.POST / "init" -> handler { (req: Request) =>
      wrapHttpEndpoint {
        for {
          scopeRoot   <- ZIO.serviceWith[ScopeRoot](_.path)
          _           <- ZIO.attemptBlockingIO(os.exists(JD.Plugins.path(scopeRoot)) || os.exists(JD.PluginsLock.path(scopeRoot)))
                           .filterOrFail(_ == false)(jsonResponse(
                             ErrorMessage.InitNotAllowed("Scope already initialized.",
                               "Manually delete the corresponding .json files if you are sure you want to initialize a new scope.")
                           ).status(Status.Conflict))
          defPlugins  <- JD.Plugins.defaultPluginsRoot
          defCache    <- JD.Plugins.defaultCacheRoot
          initArgs    <- parseOr400[InitArgs](req.body, ErrorMessage.BadInit(
                           """Parameters "plugins" and "cache" are required.""",
                           "Pass the locations of the folders as JSON dictionary: {plugins: <path>, cache: <path>}.",
                           platformDefaults = Map("plugins" -> defPlugins.map(_.toString), "cache" -> defCache.map(_.toString))
                         ))
          pluginsRoot =  os.Path(initArgs.plugins, scopeRoot)
          cacheRoot   =  os.Path(initArgs.cache, scopeRoot)
          _           <- ZIO.attemptBlockingIO {
                           os.makeDir.all(pluginsRoot)  // TODO ask for confirmation?
                           os.makeDir.all(cacheRoot)
                         }
          pluginsData <- JD.Plugins.init(pluginsRoot = pluginsRoot, cacheRoot = cacheRoot)
        } yield jsonOk
      }
    },

    // 200, 400, 409
    Method.POST / "plugins.add" -> handler { (req: Request) =>
      wrapHttpEndpoint {
        for {
          mods        <- parseModulesOr400(req.body)
          pluginsData <- readPluginsOr409
          pac         <- Sc4pac.init(pluginsData.config)
          _           <- pac.add(mods)
        } yield jsonOk
      }
    },

    // 200, 400, 409
    Method.POST / "plugins.remove" -> handler { (req: Request) =>
      wrapHttpEndpoint {
        for {
          mods        <- parseModulesOr400(req.body)
          pluginsData <- readPluginsOr409
          added       =  pluginsData.explicit.toSet
          _           <- validateOr400(mods)(added.contains(_))(failedMods => ErrorMessage.BadRequest(
                           s"Package is not among explicitly added plugins: ${failedMods.map(_.orgName).mkString(", ")}",
                           "Get /plugins.added.list for the removable packages."
                         ))
          pac         <- Sc4pac.init(pluginsData.config)
          _           <- pac.remove(mods)
        } yield jsonOk
      }
    },

    // Test the websocket using Javascript in webbrowser (messages are also logged in network tab):
    //     let ws = new WebSocket('ws://localhost:51515/update'); ws.onmessage = function(e) { console.log(e) };
    //     ws.send(JSON.stringify({}))
    Method.GET / "update" -> handler {
      readPluginsOr409.foldZIO(
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
              } yield ResultMessage(ok = true)

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

    // 200, 400, 409
    Method.GET / "packages.search" -> handler { (req: Request) =>
      wrapHttpEndpoint {
        for {
          searchText   <- ZIO.fromOption(req.url.queryParams.get("q")).orElseFail(jsonResponse(ErrorMessage.BadRequest(
                            """Query parameter "q" is required.""", "Pass the search string as query."
                          )).status(Status.BadRequest))
          threshold    <- req.url.queryParams.get("threshold") match {
                            case None => ZIO.succeed(Constants.fuzzySearchThreshold)  // the default
                            case Some(s) => ZIO.fromOption(implicitly[Numeric[Int]].parseString(s).filter(i => 0 <= i && i <= 100))
                              .orElseFail(jsonResponse(ErrorMessage.BadRequest(
                                "Invalid threshold", "Threshold must be a number between 0 and 100."
                              )).status(Status.BadRequest))
                          }
          pluginsData  <- readPluginsOr409
          pac          <- Sc4pac.init(pluginsData.config)
          searchResult <- pac.search(searchText, threshold)
        } yield jsonResponse(searchResult.map { case (pkg, ratio, summaryOpt) =>
          SearchResultItem(pkg, relevance = ratio, summary = summaryOpt.getOrElse(""))
        })
      }
    },

    // 200, 400, 404, 409
    Method.GET / "packages.info" -> handler { (req: Request) =>
      wrapHttpEndpoint {
        for {
          pkg          <- ZIO.fromOption(req.url.queryParams.get("pkg")).orElseFail(jsonResponse(ErrorMessage.BadRequest(
                            """Query parameter "pkg" is required.""", "Pass the package identifier as query."
                          )).status(Status.BadRequest))
          mod           <- parseModuleOr400(pkg)
          pluginsData   <- readPluginsOr409
          pac           <- Sc4pac.init(pluginsData.config)
          infoResultOpt <- pac.infoJson(mod)  // TODO avoid decoding/encoding json
        } yield infoResultOpt match {
          case None => jsonResponse(ErrorMessage.PackageNotFound("Package not found.", pkg)).status(Status.NotFound)
          case Some(pkgData) => jsonResponse(pkgData)
        }
      }
    },

    // 200, 409
    Method.GET / "plugins.added.list" -> handler {
      wrapHttpEndpoint {
        for {
          pluginsData <- readPluginsOr409
        } yield jsonResponse(pluginsData.explicit)
      }
    },

    // 200, 409
    Method.GET / "plugins.installed.list" -> handler {
      wrapHttpEndpoint {
        for {
          pluginsData   <- readPluginsOr409
          installedIter <- cli.Commands.List.iterateInstalled(pluginsData)
        } yield {
          jsonResponse(installedIter.map { case (mod, explicit) =>
            InstalledPkg(mod.toBareDep, variant = mod.variant, version = mod.version, explicit = explicit)
          }.toSeq)
        }
      }
    },

    // 200, 409
    Method.GET / "packages.list" -> handler {
      wrapHttpEndpoint {
        for {
          pluginsData <- readPluginsOr409
          pac         <- Sc4pac.init(pluginsData.config)
          itemsIter   <- pac.iterateAllChannelContents
        } yield jsonResponse(itemsIter.flatMap(item => item.toBareDep match {
          case mod: BareModule => item.versions.map { version => ChannelContentsItem(mod, version = version, summary = item.summary) }
          case _: BareAsset => Nil
        }).toSeq)
      }
    },

    // 200, 409
    Method.GET / "variants.list" -> handler {
      wrapHttpEndpoint {
        for {
          pluginsData <- readPluginsOr409
        } yield jsonResponse(pluginsData.config.variant)(using UP.stringKeyW(implicitly[UP.Writer[Variant]]))
      }
    },

    // 200, 400, 409
    Method.POST / "variants.reset" -> handler { (req: Request) =>
      wrapHttpEndpoint {
        for {
          labels      <- parseOr400[Seq[String]](req.body, ErrorMessage.BadRequest("Malformed variant labels.", "Pass variants as an array of strings."))
          pluginsData <- readPluginsOr409
          _           <- validateOr400(labels)(pluginsData.config.variant.contains(_))(failedLabels => ErrorMessage.BadRequest(
                           s"Variant does not exist: ${failedLabels.mkString(", ")}", "Get /variants.list for the currently configured variants."
                         ))
          _           <- cli.Commands.VariantReset.removeAndWrite(pluginsData, labels)
        } yield jsonOk
      }
    },

  ).handleError(err => jsonResponse(ErrorMessage.ServerError("Unhandled error.", err.getMessage)).status(Status.InternalServerError))

}
