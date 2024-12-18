package io.github.memo33
package sc4pac
package api

import zio.http.*
import zio.http.ChannelEvent.{Read, Unregistered, UserEvent, UserEventTriggered}
import zio.{ZIO, IO, URIO, Ref}
import upickle.default as UP

import sc4pac.JsonData as JD
import JD.{bareModuleRw, uriRw}
import sc4pac.cli.Commands.Server.{ServerFiber, ServerConnection}


class Api(options: sc4pac.cli.Commands.ServerOptions) {
  private val connectionCount = java.util.concurrent.atomic.AtomicInteger(0)

  private def jsonResponse[A : UP.Writer](obj: A): Response = Response.json(UP.write(obj, indent = options.indent))
  private def jsonFrame[A : UP.Writer](obj: A): WebSocketFrame = WebSocketFrame.Text(UP.write(obj, indent = options.indent))

  private val makePlatformDefaults: URIO[ProfileRoot, Map[String, Seq[String]]] =
    for {
      defPlugins  <- JD.Plugins.defaultPluginsRoot
      defCache    <- JD.Plugins.defaultCacheRoot
    } yield Map(
      "plugins" -> defPlugins.map(_.toString),
      "cache" -> defCache.map(_.toString),
      "temp" -> Seq(JD.Plugins.defaultTempRoot.toString),
    )

  /** Sends a 409 ProfileNotInitialized if Plugins cannot be loaded. */
  private val readPluginsOr409: ZIO[ProfileRoot, Response, JD.Plugins] =
    JD.Plugins.read.flatMapError { (err: ErrStr) =>
      for {
        defaults <- makePlatformDefaults
      } yield jsonResponse(ErrorMessage.ProfileNotInitialized("Profile not initialized", err, platformDefaults = defaults)).status(Status.Conflict)
    }

  private def expectedFailureMessage(err: cli.Commands.ExpectedFailure): ErrorMessage = err match {
    case abort: error.Sc4pacVersionNotFound => ErrorMessage.VersionNotFound(abort.title, abort.detail)
    case abort: error.Sc4pacAssetNotFound => ErrorMessage.AssetNotFound(abort.title, abort.detail)
    case abort: error.ExtractionFailed => ErrorMessage.ExtractionFailed(abort.title, abort.detail)
    case abort: error.UnsatisfiableVariantConstraints => ErrorMessage.UnsatisfiableVariantConstraints(abort.title, abort.detail)
    case abort: error.DownloadFailed => ErrorMessage.DownloadFailed(abort.title, abort.detail)
    case abort: error.ChecksumError => ErrorMessage.DownloadFailed(abort.title, abort.detail)
    case abort: error.ChannelsNotAvailable => ErrorMessage.ChannelsNotAvailable(abort.title, abort.detail)
    case abort: error.Sc4pacAbort => ErrorMessage.Aborted("Operation aborted.", "")
  }

  private def expectedFailureStatus(err: cli.Commands.ExpectedFailure): Status = err match {
    case abort: error.Sc4pacVersionNotFound => Status.NotFound
    case abort: error.Sc4pacAssetNotFound => Status.NotFound
    case abort: error.ExtractionFailed => Status.InternalServerError
    case abort: error.UnsatisfiableVariantConstraints => Status.InternalServerError
    case abort: error.DownloadFailed => Status.BadGateway
    case abort: error.ChecksumError => Status.BadGateway
    case abort: error.ChannelsNotAvailable => Status.BadGateway
    case abort: error.Sc4pacAbort => Status.Ok  // this is not really an error, but the expected control flow
  }

  val jsonOk = jsonResponse(ResultMessage(ok = true))

  private val httpLogger = {
    val cliLogger: Logger = CliLogger()
    zio.ZLayer.succeed(cliLogger)
  }

  /** Handles some errors and provides http logger (not used for update-websocket). */
  private def wrapHttpEndpoint[R](task: ZIO[R & Logger, Throwable | Response, Response]): ZIO[R, Throwable, Response] = {
    task.provideSomeLayer(httpLogger)
      .catchAll {
        case response: Response => ZIO.succeed(response)
        case err: cli.Commands.ExpectedFailure => ZIO.succeed(jsonResponse(expectedFailureMessage(err)).status(expectedFailureStatus(err)))
        case t: Throwable => ZIO.fail(t)
      }
      .catchAllDefect(t => ZIO.fail(t))
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

  private def searchParams(req: Request): IO[Response, (String, Int)] = for {
    searchText <- ZIO.fromOption(req.url.queryParams.getAll("q").headOption).orElseFail(jsonResponse(ErrorMessage.BadRequest(
                    """Query parameter "q" is required.""", "Pass the search string as query."
                  )).status(Status.BadRequest))
    threshold  <- req.url.queryParams.getAll("threshold").headOption match {
                    case None => ZIO.succeed(Constants.fuzzySearchThreshold)  // the default
                    case Some(s) => ZIO.fromOption(implicitly[Numeric[Int]].parseString(s).filter(i => 0 <= i && i <= 100))
                      .orElseFail(jsonResponse(ErrorMessage.BadRequest(
                        "Invalid threshold", "Threshold must be a number between 0 and 100."
                      )).status(Status.BadRequest))
                  }
  } yield (searchText, threshold)

  /** Fuzzy-search across all installed packages.
    * The selection of results is ordered in descending order and includes the
    * module, the relevance ratio and the description.
    * This does not support searching for STEX/SC4E URLs, as the external IDs are not stored in the local lock file.
    * Sc4pac.search implements a similar function and should use a similar algorithm.
    */
  def searchPlugins(query: String, threshold: Int, category: Option[String], items: Seq[JD.InstalledData]): (JD.Channel.Stats, Seq[(JD.InstalledData, Int)]) = {
    val categoryStats = collection.mutable.Map.empty[String, Int]
    val searchTokens = Sc4pac.fuzzySearchTokenize(query)
    val results: Seq[(JD.InstalledData, Int)] =
      items.flatMap { item =>
        // TODO reconsider choice of search algorithm
        val ratio =
          if (searchTokens.isEmpty) 100  // return the entire category (or everything if there is no filter category)
          else Sc4pac.fuzzySearchRatio(searchTokens, item.toSearchString, threshold)
        if (ratio < threshold) {
          None
        } else {
          // search text matches, so count towards categories
          for (cat <- item.category) {
            categoryStats(cat) = categoryStats.getOrElse(cat, 0) + 1
          }
          if (category.isDefined && item.category != category) {
            None
          } else {
            Some((item, ratio))
          }
        }
      }
    (JD.Channel.Stats.fromMap(categoryStats), results.sortBy((item, ratio) => (-ratio, item.group, item.name)))
  }

  /** Routes that require a `profile=id` query parameter as part of the URL. */
  def profileRoutes: Routes[ProfileRoot, Throwable] = Routes(

    // 200, 409
    Method.GET / "profile.read" -> handler { (req: Request) =>
      wrapHttpEndpoint {
        for {
          pluginsData <- readPluginsOr409
        } yield jsonResponse(pluginsData.config)
      }
    },

    // 200, 400, 409
    Method.POST / "profile.init" -> handler { (req: Request) =>
      wrapHttpEndpoint {
        for {
          profileRoot <- ZIO.serviceWith[ProfileRoot](_.path)
          _           <- ZIO.attemptBlockingIO(os.exists(JD.Plugins.path(profileRoot)) || os.exists(JD.PluginsLock.path(profileRoot)))
                           .filterOrFail(_ == false)(jsonResponse(
                             ErrorMessage.InitNotAllowed("Profile already initialized.",
                               "Manually delete the corresponding .json files if you are sure you want to initialize a new profile.")
                           ).status(Status.Conflict))
          defaults    <- makePlatformDefaults
          initArgs    <- parseOr400[InitArgs](req.body, ErrorMessage.BadInit(
                           """Parameters "plugins", "cache" and "temp" are required.""",
                           "Pass the locations of the folders as JSON dictionary: {plugins: <path>, cache: <path>, temp: <path>}.",
                           platformDefaults = defaults,
                         ))
          pluginsRoot =  os.Path(initArgs.plugins, profileRoot)
          cacheRoot   =  os.Path(initArgs.cache, profileRoot)
          tempRoot    =  os.FilePath(initArgs.temp)
          _           <- ZIO.attemptBlockingIO {
                           os.makeDir.all(profileRoot)
                           os.makeDir.all(pluginsRoot)  // TODO ask for confirmation?
                           os.makeDir.all(cacheRoot)
                         }
          pluginsData <- JD.Plugins.init(pluginsRoot = pluginsRoot, cacheRoot = cacheRoot, tempRoot = tempRoot)
        } yield jsonResponse(pluginsData.config)
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
    Method.GET / "update" -> handler { (req: Request) =>
      readPluginsOr409.foldZIO(
        failure = ZIO.succeed[Response](_),
        success = pluginsData =>
          Handler.webSocket { wsChannel =>
            val updateTask: zio.RIO[ProfileRoot & WebSocketLogger, Message] =
              val cookies = Downloader.Cookies(req.url.queryParams.getAll("simtropolisCookie").headOption.orElse(Constants.simtropolisCookie))
              val cookieDesc = cookies.simtropolisCookie.map(c => s"with cookie: ${c.length} bytes").getOrElse("without cookie")
              for {
                pac          <- Sc4pac.init(pluginsData.config)
                pluginsRoot  <- pluginsData.config.pluginsRootAbs
                wsLogger     <- ZIO.service[WebSocketLogger]
                _            <- ZIO.succeed(wsLogger.log(s"Updating... ($cookieDesc)"))
                flag         <- pac.update(pluginsData.explicit, globalVariant0 = pluginsData.config.variant, pluginsRoot = pluginsRoot)
                                  .provideSomeLayer(zio.ZLayer.succeedEnvironment(zio.ZEnvironment(
                                    WebSocketPrompter(wsChannel, wsLogger),
                                    cookies,
                                  )))
              } yield ResultMessage(ok = true)

            val wsTask: zio.RIO[ProfileRoot, Unit] =
              WebSocketLogger.run(send = msg => wsChannel.send(Read(jsonFrame(msg)))) {
                for {
                  finalMsg <- updateTask.catchSome { case err: cli.Commands.ExpectedFailure => ZIO.succeed(expectedFailureMessage(err)) }
                  unit     <- ZIO.serviceWithZIO[WebSocketLogger](_.sendMessageAwait(finalMsg))
                } yield unit
              } // wsLogger is shut down here (TODO use resource for safer closing)
              // wsChannel needs to be explicitly shutdown afterwards

            // We run the update task and wait for shutdown of the channel in parallel.
            // If client closes websocket connection before update task is completed, we cancel the update task by interrupting it.
            // Otherwise, the websocket is shut down normally.
            wsTask.raceWith(wsChannel.awaitShutdown: zio.RIO[Any, Unit])(
              leftDone = (result, fiberRight) =>  // normal shutdown
                wsChannel.shutdown
                  .zipRight(fiberRight.await)
                  .zipRight(ZIO.serviceWith[Logger](_.log("Update task completed.")))
                  .zipRight(result),
              rightDone = (result, fiberLeft) =>  // cancel the update
                fiberLeft.interruptFork  // forking is important here to be able to interrupt a blocked fiber
                  .zipRight(ZIO.serviceWith[Logger](_.log("Update task was canceled.")))
                  .zipRight(result)
            ).provideSomeLayer(httpLogger): zio.RIO[ProfileRoot, Unit]

          }.toResponse: zio.URIO[ProfileRoot, Response]
      )
    },

    // 200, 400, 409
    Method.GET / "packages.search" -> handler { (req: Request) =>
      wrapHttpEndpoint {
        for {
          (searchText, threshold) <- searchParams(req)
          categoryOpt  =  req.url.queryParams.getAll("category").headOption
          pluginsData  <- readPluginsOr409
          pac          <- Sc4pac.init(pluginsData.config)
          searchResult <- pac.search(searchText, threshold, category = categoryOpt)
          explicit     =  pluginsData.explicit.toSet
          installed    <- JD.PluginsLock.listInstalled2.map(mods => mods.iterator.map(m => m.toBareModule -> m).toMap)
        } yield jsonResponse(searchResult.map { case (pkg, ratio, summaryOpt) =>
          val status = InstalledStatus(
            explicit = explicit.contains(pkg),
            installed = installed.get(pkg).map(_.toApiInstalled).orNull,
          )
          val statusOrNull = if (status.explicit || status.installed != null) status else null
          PackageSearchResultItem(pkg, relevance = ratio, summary = summaryOpt.getOrElse(""), status = statusOrNull)
        })
      }
    },

    Method.GET / "plugins.search" -> handler((req: Request) => wrapHttpEndpoint {
      for {
        (searchText, threshold) <- searchParams(req)
        categoryOpt  =  req.url.queryParams.getAll("category").headOption
        pluginsData  <- readPluginsOr409
        installed    <- JD.PluginsLock.listInstalled2
        (stats, searchResult) =  searchPlugins(searchText, threshold, categoryOpt, installed)
        explicit     =  pluginsData.explicit.toSet
      } yield {
        val items = searchResult.map { case (item, ratio) =>
          val mod = item.toBareModule
          val status = InstalledStatus(explicit = explicit.contains(mod), installed = item.toApiInstalled)
          PluginsSearchResultItem(mod, relevance = ratio, summary = item.summary, status = status)
        }
        jsonResponse(PluginsSearchResult(stats, items))
      }
    }),

    // 200, 400, 404, 409
    Method.GET / "packages.info" -> handler { (req: Request) =>
      wrapHttpEndpoint {
        for {
          pkg          <- ZIO.fromOption(req.url.queryParams.getAll("pkg").headOption).orElseFail(jsonResponse(ErrorMessage.BadRequest(
                            """Query parameter "pkg" is required.""", "Pass the package identifier as query."
                          )).status(Status.BadRequest))
          mod           <- parseModuleOr400(pkg)
          pluginsData   <- readPluginsOr409
          pac           <- Sc4pac.init(pluginsData.config)
          remoteData    <- pac.infoJson(mod).someOrFail(  // TODO avoid decoding/encoding json
                             jsonResponse(ErrorMessage.PackageNotFound("Package not found in any of your channels.", pkg)).status(Status.NotFound)
                           )
          explicit      =  pluginsData.explicit.toSet
          installed     <- JD.PluginsLock.listInstalled2
        } yield {
          val targetPkgs = collection.mutable.Set[BareModule](mod)  // original package and direct dependencies
            ++= remoteData.variants.iterator.flatMap(_.bareModules)
            ++= remoteData.info.requiredBy
          val statuses = collection.mutable.Map.empty[BareModule, InstalledStatus]
          // first check all installed packages for matches
          for (inst <- installed) {
            val instMod = inst.toBareModule
            if (targetPkgs.contains(instMod)) {
              statuses(instMod) = InstalledStatus(explicit = explicit.contains(instMod), installed = inst.toApiInstalled)
            }
          }
          // next check explicitly added packages that are not installed yet for matches (pending updates)
          for (depMod <- targetPkgs) {
            if (!statuses.contains(depMod) && explicit.contains(depMod)) {
              statuses(depMod) = InstalledStatus(explicit = true, installed = null)
            }
          }
          jsonResponse(PackageInfo(local = PackageInfo.Local(statuses = statuses.toMap), remote = remoteData))
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
          itemsIter   <- pac.iterateAllChannelPackages
        } yield jsonResponse(itemsIter.flatMap(item => item.toBareDep match {
          case mod: BareModule => item.versions.map { version => ChannelContentsItem(mod, version = version, summary = item.summary, category = item.category) }
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

    // 200, 409
    Method.GET / "channels.list" -> handler {
      wrapHttpEndpoint {
        for {
          pluginsData <- readPluginsOr409
        } yield jsonResponse(pluginsData.config.channels)
      }
    },

    // 200, 400, 409
    Method.POST / "channels.set" -> handler { (req: Request) =>
      wrapHttpEndpoint {
        for {
          urls         <- parseOr400[Seq[java.net.URI]](req.body, ErrorMessage.BadRequest("Malformed channel URLs.", "Pass channels as an array of strings."))
          pluginsData  <- readPluginsOr409
          pluginsData2 =  pluginsData.copy(config = pluginsData.config.copy(channels =
                            if (urls.nonEmpty) urls.distinct else Constants.defaultChannelUrls
                          ))
          path         <- JD.Plugins.pathURIO
          _            <- JsonIo.write(path, pluginsData2, None)(ZIO.succeed(()))
        } yield jsonOk
      }
    },

    // 200, 409
    Method.GET / "channels.stats" -> handler {
      wrapHttpEndpoint {
        for {
          pluginsData   <- readPluginsOr409
          pac           <- Sc4pac.init(pluginsData.config)
          combinedStats =  JD.Channel.Stats.aggregate(pac.context.repositories.map(_.channel.stats))
        } yield jsonResponse(combinedStats)
      }
    },

  )

  def routes(webAppDir: Option[os.Path]): Routes[ProfilesDir & ServerFiber & Client & Ref[ServerConnection], Nothing] = {
    // Extract profile ID from URL query parameter and add it to environment.
    // 400 error if "profile" parameter is absent.
    val profileRoutes2 =
      profileRoutes.transform((handler0) => handler { (req: Request) =>
        req.url.queryParams.getAll("profile").headOption match {
          case Some[ProfileId](id) =>
            handler0(req).provideSomeLayer(zio.ZLayer.fromFunction((dir: ProfilesDir) => ProfileRoot(dir.path / id)))
          case None =>
            ZIO.fail(jsonResponse(ErrorMessage.BadRequest(
              """URL query parameter "profile" is required.""", "Pass the profile ID as query."
            )).status(Status.BadRequest))
        }
      })

    // profile-independent routes
    val genericRoutes = Routes[ProfilesDir & ServerFiber & Client & Ref[ServerConnection], Throwable](

      // 200
      Method.GET / "server.status" -> handler {
        wrapHttpEndpoint {
          ZIO.succeed(jsonResponse(ServerStatus(sc4pacVersion = cli.BuildInfo.version)))
        }
      },

      // websocket allowing to monitor whether server is alive (supports no particular message exchange)
      Method.GET / "server.connect" -> handler {
        val num = connectionCount.incrementAndGet()
        Handler.webSocket { wsChannel =>
          val wsTask = for {
            logger <- ZIO.service[Logger]
            _      <- ZIO.succeed(logger.log(s"Registered websocket connection $num."))
            _      <- wsChannel.receiveAll {
                        case UserEventTriggered(UserEvent.HandshakeComplete) => ZIO.succeed(())  // ignore expected event
                        case Unregistered =>
                          logger.log(s"Unregistered websocket connection $num.")  // client closed websocket (results in websocket shutdown)
                          ZIO.succeed(())
                        case event =>
                          logger.warn(s"Discarding unexpected websocket event: $event")
                          ZIO.succeed(())  // discard all unexpected messages (and events) and continue receiving
                      }
            _      <- wsChannel.shutdown: zio.UIO[Unit]  // may be redundant
            _      <- ZIO.succeed(logger.log(s"Shut down websocket connection $num."))
            server <- ZIO.service[ServerFiber]
            _      <- if (!options.autoShutdown) ZIO.succeed(())
                      else for {
                        fiber  <- server.promise.await
                        _      <- ZIO.succeed(logger.log(s"Connection from client to server was closed. Shutting down server."))
                        _      <- fiber.interrupt.fork
                      } yield ()
          } yield ()

          val serverConnection = ServerConnection(currentChannel = Some(wsChannel))
          ZIO.acquireReleaseWith
            (acquire = ZIO.serviceWithZIO[Ref[ServerConnection]](_.set(serverConnection)))
            (release = (_) => ZIO.serviceWithZIO[Ref[ServerConnection]](_.updateSome { case s if s == serverConnection => ServerConnection(currentChannel = None) }))
            (use = (_) => wsTask)
        }.provideSomeLayer(httpLogger).toResponse
      },

      // 200, 400, 503
      Method.POST / "packages.open" -> handler((req: Request) => wrapHttpEndpoint {
        for {
          packages   <- parseOr400[Seq[OpenPackageMessage.Item]](req.body, ErrorMessage.BadRequest("Malformed package list.", "Pass an array of package items."))
          wsChannel  <- ZIO.serviceWithZIO[Ref[ServerConnection]](_.get.map(_.currentChannel))
                          .someOrFail(jsonResponse(ErrorMessage.ServerError(
                            "The sc4pac GUI is not opened. Make sure the GUI is running correctly.",
                            "Connection between API server and GUI client is not available."
                          )).status(Status.ServiceUnavailable))
          _          <- wsChannel.send(Read(jsonFrame(OpenPackageMessage(packages))))
        } yield jsonOk
      }),

      // 200
      Method.GET / "profiles.list" -> handler {
        wrapHttpEndpoint {
          JD.Profiles.readOrInit.map(profiles => jsonResponse(profiles.copy(settings = ujson.Obj())))
        }
      },

      // 200, 400
      Method.POST / "profiles.add" -> handler { (req: Request) =>
        wrapHttpEndpoint {
          for {
            profileName <- parseOr400[ProfileName](req.body, ErrorMessage.BadRequest("Missing profile name.", "Pass the \"name\" of the new profile."))
            ps          <- JD.Profiles.readOrInit
            (ps2, p)    = ps.add(profileName.name)
            jsonPath    <- JD.Profiles.pathURIO
            _           <- ZIO.attemptBlockingIO { os.makeDir.all(jsonPath / os.up) }
            _           <- JsonIo.write(jsonPath, ps2, None)(ZIO.succeed(()))
          } yield jsonResponse(p)
        }
      },

      // 200
      Method.GET / "settings.all.get" -> handler(wrapHttpEndpoint {
        JD.Profiles.readOrInit.map(profiles => jsonResponse(profiles.settings))
      }),

      // 200, 400
      Method.POST / "settings.all.set" -> handler((req: Request) => wrapHttpEndpoint {
        for {
          newSettings <- parseOr400[ujson.Value](req.body, ErrorMessage.BadRequest("Missing settings", "Pass the settings as JSON body of the request."))
          ps          <- JD.Profiles.readOrInit
          ps2         =  ps.copy(settings = newSettings)
          jsonPath    <- JD.Profiles.pathURIO
          _           <- JsonIo.write(jsonPath, ps2, None)(ZIO.succeed(()))
        } yield jsonOk
      }),

    )

    val fetchRoutes = Routes[Client, Throwable](

      // 200, 400
      Method.GET / "image.fetch" -> handler((req: Request) => wrapHttpEndpoint {
        for {
          rawUrl <- ZIO.fromOption(req.url.queryParams.getAll("url").headOption).orElseFail(jsonResponse(ErrorMessage.BadRequest(
                      """Query parameter "url" is required.""", "Pass the remote image url as parameter."
                    )).status(Status.BadRequest))
          url    <- ZIO.fromEither(zio.http.URL.decode(rawUrl).left.map(malformedUrl => jsonResponse(ErrorMessage.BadRequest(
                      "Malformed url", malformedUrl.getMessage
                    )).status(Status.BadRequest)))
          // TODO handle retrys
          resp   <- zio.http.ZClient.batched(Request.get(url))  // TODO this first downloads the entire response body before forwarding the response
                                                                // Using `ZClient.streaming` instead is blocked on https://github.com/zio/zio-http/issues/3197
        } yield resp
      }),

    )

    (profileRoutes2 ++ genericRoutes ++ fetchRoutes ++ webAppDir.map(staticRoutes).getOrElse(Routes.empty))
      .handleError { err =>
        err.printStackTrace()
        jsonResponse(ErrorMessage.ServerError("Unhandled error.", err.toString)).status(Status.InternalServerError)
      }
  }

  def staticFileHandler(webAppDir: os.Path, path: zio.http.Path): Handler[Any, Nothing, Request, Response] = {
    val fileZio =
      for {
        subpath <- ZIO.fromTry(scala.util.Try(os.SubPath(path.encode match {
          case "" | "/" => "index.html"  // show default document
          case s => s
        })))
      } yield (webAppDir / subpath).toIO
    Handler.fromFileZIO(fileZio).orElse(Handler.notFound)
  }

  // 200, (308), 404
  def staticRoutes(webAppDir: os.Path): Routes[Any, Nothing] = Routes(
    Method.GET / "/" -> handler {
      Response.redirect(zio.http.URL(zio.http.Path("webapp/")), isPermanent = true)  // 308
    },
    Method.GET / "webapp" / trailing ->
      Handler.fromFunctionHandler[(zio.http.Path, Request)] { case (path: zio.http.Path, _: Request) =>
        staticFileHandler(webAppDir, path).contramap[(zio.http.Path, Request)](_._2)
      },
  ).sandbox  // @@ HandlerAspect.requestLogging()  // sandbox converts errors to suitable responses

}
