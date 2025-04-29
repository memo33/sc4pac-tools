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
  private val connectionsSinceLaunch = java.util.concurrent.atomic.AtomicInteger(0)

  private def jsonResponse[A : UP.Writer](obj: A): Response = Response.json(UP.write(obj, indent = options.indent))
  private def jsonFrame[A : UP.Writer](obj: A): WebSocketFrame = WebSocketFrame.Text(UP.write(obj, indent = options.indent))

  private val makePlatformDefaults: URIO[ProfileRoot & service.FileSystem, Map[String, Seq[String]]] =
    for {
      defPlugins  <- JD.PluginsSpec.defaultPluginsRoot
      defCache    <- JD.PluginsSpec.defaultCacheRoot
    } yield Map(
      "plugins" -> defPlugins.map(_.toString),
      "cache" -> defCache.map(_.toString),
      "temp" -> Seq(JD.PluginsSpec.defaultTempRoot.toString),
    )

  /** Sends a 409 ProfileNotInitialized or 500 ReadingProfileFailed if PluginsSpec cannot be loaded. */
  private val readPluginsSpecOr409: ZIO[ProfileRoot & service.FileSystem, Response, JD.PluginsSpec] =
    JD.PluginsSpec.readMaybe
      .mapError((e: error.ReadingProfileFailed) => jsonResponse(expectedFailureMessage(e)).status(expectedFailureStatus(e)))
      .someOrElseZIO((  // PluginsSpec file does not exist
        for {
          defaults <- makePlatformDefaults
          msg      =  ErrorMessage.ProfileNotInitialized("Profile not initialized.", "Profile JSON file does not exist.", platformDefaults = defaults)
        } yield jsonResponse(msg).status(Status.Conflict)
      ).flip)

  private def expectedFailureMessage(err: cli.Commands.ExpectedFailure): ErrorMessage = err match {
    case abort: error.Sc4pacVersionNotFound => ErrorMessage.VersionNotFound(abort.title, abort.detail)
    case abort: error.UnresolvableDependencies => ErrorMessage.UnresolvableDependencies(abort.title, abort.detail)
    case abort: error.ConflictingPackages => ErrorMessage.ConflictingPackages(abort.title, abort.detail, abort.conflict)
    case abort: error.Sc4pacAssetNotFound => ErrorMessage.AssetNotFound(abort.title, abort.detail)
    case abort: error.ExtractionFailed => ErrorMessage.ExtractionFailed(abort.title, abort.detail)
    case abort: error.UnsatisfiableVariantConstraints => ErrorMessage.UnsatisfiableVariantConstraints(abort.title, abort.detail)
    case abort: error.DownloadFailed => ErrorMessage.DownloadFailed(abort.title, abort.detail)
    case abort: error.ChecksumError => ErrorMessage.DownloadFailed(abort.title, abort.detail)
    case abort: error.ChannelsNotAvailable => ErrorMessage.ChannelsNotAvailable(abort.title, abort.detail)
    case abort: error.ReadingProfileFailed => ErrorMessage.ReadingProfileFailed(abort.title, abort.detail)
    case abort: error.Sc4pacPublishIncomplete => ErrorMessage.PublishedFilesIncomplete(abort.title, abort.detail)
    case abort: error.ObtainingUserDirsFailed => ErrorMessage.ObtainingUserDirsFailed(abort.title, abort.detail)
    case abort: error.Sc4pacAbort => ErrorMessage.Aborted("Operation aborted.", "")
    case abort: java.nio.file.AccessDeniedException => ErrorMessage.FileAccessDenied(
      "File access denied. Check that you have permissions to access the file or directory.", abort.getMessage)
  }

  private def expectedFailureStatus(err: cli.Commands.ExpectedFailure): Status = err match {
    case abort: error.Sc4pacVersionNotFound => Status.NotFound
    case abort: error.UnresolvableDependencies => Status.NotFound
    case abort: error.ConflictingPackages => Status.BadRequest
    case abort: error.Sc4pacAssetNotFound => Status.NotFound
    case abort: error.ExtractionFailed => Status.InternalServerError
    case abort: error.UnsatisfiableVariantConstraints => Status.InternalServerError
    case abort: error.DownloadFailed => Status.BadGateway
    case abort: error.ChecksumError => Status.BadGateway
    case abort: error.ChannelsNotAvailable => Status.BadGateway
    case abort: error.ReadingProfileFailed => Status.InternalServerError
    case abort: error.Sc4pacPublishIncomplete => Status.InternalServerError
    case abort: error.ObtainingUserDirsFailed => Status.InternalServerError
    case abort: error.Sc4pacAbort => Status.Ok  // this is not really an error, but the expected control flow
    case abort: java.nio.file.AccessDeniedException => Status.InternalServerError
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
    * This does not support filtering by channel URL, as those are not stored in the local lock file.
    * Sc4pac.search implements a similar function and should use a similar algorithm.
    */
  def searchPlugins(query: String, threshold: Int, category: Option[String], items: Seq[JD.InstalledData]): (JD.Channel.Stats, Seq[(JD.InstalledData, Int)]) = {
    val categoryStats = collection.mutable.Map.empty[String, Int]
    val searchTokens = Sc4pac.fuzzySearchTokenize(query)
    val results: Seq[(JD.InstalledData, Int)] =
      items.flatMap { item =>
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

  def installedStatusBuilder(pluginsSpec: JD.PluginsSpec): zio.RIO[ProfileRoot, BareModule => InstalledStatus] = {
    val explicit = pluginsSpec.explicit.toSet
    for {
      installed <- JD.PluginsLock.listInstalled2.map(mods => mods.iterator.map(m => m.toBareModule -> m).toMap)
    } yield { (pkg: BareModule) =>
      val status = InstalledStatus(
        explicit = explicit.contains(pkg),
        installed = installed.get(pkg).map(_.toApiInstalled).orNull,
      )
      val statusOrNull = if (status.explicit || status.installed != null) status else null
      statusOrNull
    }
  }

  /** Routes that require a `profile=id` query parameter as part of the URL. */
  def profileRoutes: Routes[ProfileRoot & service.FileSystem & Ref[Option[FileCache]], Throwable] = Routes(

    // 200, 409, 500
    Method.GET / "profile.read" -> handler { (req: Request) =>
      wrapHttpEndpoint {
        for {
          pluginsSpec <- readPluginsSpecOr409
        } yield jsonResponse(pluginsSpec.config)
      }
    },

    // 200, 400, 409
    Method.POST / "profile.init" -> handler { (req: Request) =>
      wrapHttpEndpoint {
        for {
          profileRoot <- ZIO.serviceWith[ProfileRoot](_.path)
          _           <- ZIO.attemptBlockingIO(os.exists(JD.PluginsSpec.path(profileRoot)) || os.exists(JD.PluginsLock.path(profileRoot)))
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
          pluginsSpec <- JD.PluginsSpec.init(pluginsRoot = pluginsRoot, cacheRoot = cacheRoot, tempRoot = tempRoot)
        } yield jsonResponse(pluginsSpec.config)
      }
    },

    // 200, 400, 409
    Method.POST / "plugins.add" -> handler { (req: Request) =>
      wrapHttpEndpoint {
        for {
          mods        <- parseModulesOr400(req.body)
          pluginsSpec <- readPluginsSpecOr409
          pac         <- Sc4pac.init(pluginsSpec.config)
          _           <- pac.add(mods)
        } yield jsonOk
      }
    },

    // 200, 400, 409
    Method.POST / "plugins.remove" -> handler { (req: Request) =>
      wrapHttpEndpoint {
        for {
          mods        <- parseModulesOr400(req.body)
          pluginsSpec <- readPluginsSpecOr409
          added       =  pluginsSpec.explicit.toSet
          _           <- validateOr400(mods)(added.contains(_))(failedMods => ErrorMessage.BadRequest(
                           s"Package is not among explicitly added plugins: ${failedMods.map(_.orgName).mkString(", ")}",
                           "Get /plugins.added.list for the removable packages."
                         ))
          pac         <- Sc4pac.init(pluginsSpec.config)
          _           <- pac.remove(mods)
        } yield jsonOk
      }
    },

    // Test the websocket using Javascript in webbrowser (messages are also logged in network tab):
    //     let ws = new WebSocket('ws://localhost:51515/update'); ws.onmessage = function(e) { console.log(e) };
    //     ws.send(JSON.stringify({}))
    Method.GET / "update" -> handler { (req: Request) =>
      readPluginsSpecOr409.foldZIO(
        failure = ZIO.succeed[Response](_),
        success = pluginsSpec =>
          Handler.webSocket { wsChannel =>
            val updateTask: zio.RIO[ProfileRoot & service.FileSystem & Ref[Option[FileCache]] & WebSocketLogger, Message] =
              for {
                fs           <- ZIO.service[service.FileSystem]
                credentials  =  Downloader.Credentials(
                                  simtropolisToken = req.url.queryParams.getAll("simtropolisToken").headOption.orElse(fs.env.simtropolisToken),
                                )
                credentialsDesc = credentials.simtropolisToken.map(t => s"with token: ${t.length} bytes").getOrElse("without token")
                pac          <- Sc4pac.init(pluginsSpec.config, refreshChannels = req.url.queryParams.getAll("refreshChannels").nonEmpty)
                pluginsRoot  <- pluginsSpec.config.pluginsRootAbs
                wsLogger     <- ZIO.service[WebSocketLogger]
                _            <- ZIO.succeed(wsLogger.log(s"Updating... ($credentialsDesc)"))
                flag         <- pac.update(pluginsSpec.explicit, globalVariant0 = pluginsSpec.config.variant, pluginsRoot = pluginsRoot)
                                  .provideSomeLayer(zio.ZLayer.succeedEnvironment(zio.ZEnvironment(
                                    WebSocketPrompter(wsChannel, wsLogger),
                                    credentials,
                                  )))
              } yield ResultMessage(ok = true)

            val wsTask: zio.RIO[ProfileRoot & service.FileSystem & Ref[Option[FileCache]], Unit] =
              WebSocketLogger.run(send = msg => wsChannel.send(Read(jsonFrame(msg)))) {
                for {
                  finalMsg <- updateTask.catchAll {
                                case err: cli.Commands.ExpectedFailure => ZIO.succeed(expectedFailureMessage(err))
                                case err => ZIO.succeed(ErrorMessage.ServerError("Unexpected error during Update. This looks like a bug. Please report it.", err.toString))
                              }
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
            ).provideSomeLayer(httpLogger): zio.RIO[ProfileRoot & service.FileSystem & Ref[Option[FileCache]], Unit]

          }.toResponse: zio.URIO[ProfileRoot & service.FileSystem & Ref[Option[FileCache]], Response]
      )
    },

    // 200, 400, 409
    Method.GET / "packages.search" -> handler { (req: Request) =>
      wrapHttpEndpoint {
        for {
          (searchText, threshold) <- searchParams(req)
          category     =  req.url.queryParams.getAll("category").toSet
          notCategory  =  req.url.queryParams.getAll("notCategory").toSet
          channelOpt   =  req.url.queryParams.getAll("channel").headOption
          ignoreInstalled = req.url.queryParams.getAll("ignoreInstalled").nonEmpty
          pluginsSpec  <- readPluginsSpecOr409
          pac          <- Sc4pac.init(pluginsSpec.config)
          searchResult <- pac.search(searchText, threshold, category = category, notCategory = notCategory, channel = channelOpt)
          createStatus <- installedStatusBuilder(pluginsSpec)
        } yield jsonResponse(searchResult.flatMap { case (pkg, ratio, summaryOpt) =>
          val statusOrNull = createStatus(pkg)
          if (ignoreInstalled && statusOrNull != null && statusOrNull.installed != null)
            None
          else
            Some(PackageSearchResultItem(pkg, relevance = ratio, summary = summaryOpt.getOrElse(""), status = statusOrNull))
        })
      }
    },

    Method.POST / "packages.search.id" -> handler((req: Request) => wrapHttpEndpoint {
      for {
        args         <- parseOr400[FindPackagesArgs](req.body, ErrorMessage.BadRequest("Malformed package names", """Pass a "packages" array of strings of the form '<group>:<name>'."""))
        pluginsSpec  <- readPluginsSpecOr409
        pac          <- Sc4pac.init(pluginsSpec.config)
        searchResult <- pac.searchById(args.packages, args.externalIds.groupMap(_._1)(_._2).map((p, ids) => (p, ids.toSet)))
        createStatus <- installedStatusBuilder(pluginsSpec)
      } yield jsonResponse(searchResult.map { case (pkg, summaryOpt) =>
        val statusOrNull = createStatus(pkg)
        PackageSearchResultItem(pkg, relevance = 100, summary = summaryOpt.getOrElse(""), status = statusOrNull)
      })
    }),

    Method.GET / "plugins.search" -> handler((req: Request) => wrapHttpEndpoint {
      for {
        (searchText, threshold) <- searchParams(req)
        categoryOpt  =  req.url.queryParams.getAll("category").headOption
        pluginsSpec  <- readPluginsSpecOr409
        installed    <- JD.PluginsLock.listInstalled2
        (stats, searchResult) =  searchPlugins(searchText, threshold, categoryOpt, installed)
        explicit     =  pluginsSpec.explicit.toSet
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
          pluginsSpec   <- readPluginsSpecOr409
          pac           <- Sc4pac.init(pluginsSpec.config)
          remoteData    <- pac.infoJson(mod).someOrFail(  // TODO avoid decoding/encoding json
                             jsonResponse(ErrorMessage.PackageNotFound("Package not found in any of your channels.", pkg)).status(Status.NotFound)
                           )
          explicit      =  pluginsSpec.explicit.toSet
          installed     <- JD.PluginsLock.listInstalled2
        } yield {
          val targetPkgs = collection.mutable.Set[BareModule](mod)  // original package and direct dependencies
            ++= remoteData.variants.iterator.flatMap(_.bareModules)
            ++= remoteData.info.requiredBy
            ++= remoteData.variants.iterator.flatMap(_.conflictingPackages)
            ++= remoteData.info.reverseConflictingPackages
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
          pluginsSpec <- readPluginsSpecOr409
        } yield jsonResponse(pluginsSpec.explicit)
      }
    },

    // 200, 409
    Method.GET / "plugins.installed.list" -> handler {
      wrapHttpEndpoint {
        for {
          pluginsSpec   <- readPluginsSpecOr409
          installedIter <- cli.Commands.List.iterateInstalled(pluginsSpec)
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
          pluginsSpec <- readPluginsSpecOr409
          pac         <- Sc4pac.init(pluginsSpec.config)
          itemsIter   <- pac.iterateAllChannelPackages(channelUrl = None)
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
          pluginsSpec  <- readPluginsSpecOr409
          installed    <- JD.PluginsLock.listInstalled2
          used         =  installed.iterator.flatMap(_.variant.keysIterator).toSet[String]
        } yield jsonResponse(VariantsList(pluginsSpec.config.variant.map { (id, value) =>
          id -> VariantsList.Item(value = value, unused = !used(id))
        }))
      }
    },

    // 200, 400, 409
    Method.POST / "variants.reset" -> handler { (req: Request) =>
      wrapHttpEndpoint {
        for {
          labels      <- parseOr400[Seq[String]](req.body, ErrorMessage.BadRequest("Malformed variant labels.", "Pass variants as an array of strings."))
          pluginsSpec <- readPluginsSpecOr409
          _           <- validateOr400(labels)(pluginsSpec.config.variant.contains(_))(failedLabels => ErrorMessage.BadRequest(
                           s"Variant does not exist: ${failedLabels.mkString(", ")}", "Get /variants.list for the currently configured variants."
                         ))
          _           <- cli.Commands.VariantReset.removeAndWrite(pluginsSpec, labels)
        } yield jsonOk
      }
    },

    // 200, 409
    Method.GET / "channels.list" -> handler {
      wrapHttpEndpoint {
        for {
          pluginsSpec <- readPluginsSpecOr409
        } yield jsonResponse(pluginsSpec.config.channels)
      }
    },

    // 200, 400, 409
    Method.POST / "channels.set" -> handler { (req: Request) =>
      wrapHttpEndpoint {
        for {
          urls         <- parseOr400[Seq[java.net.URI]](req.body, ErrorMessage.BadRequest("Malformed channel URLs.", "Pass channels as an array of strings."))
          urls2        <- ZIO.foreach(urls)(url => ZIO.fromEither(
                            MetadataRepository.parseChannelUrl(url.toString)  // sanitization
                              .left.map(err => jsonResponse(
                                ErrorMessage.BadRequest("Malformed channel URL.", err)
                              ).status(Status.BadRequest)
                            )
                          ))
          pluginsSpec  <- readPluginsSpecOr409
          pluginsSpec2 =  pluginsSpec.copy(config = pluginsSpec.config.copy(channels =
                            if (urls2.nonEmpty) urls2.distinct else Constants.defaultChannelUrls
                          ))
          path         <- JD.PluginsSpec.pathURIO
          _            <- JsonIo.write(path, pluginsSpec2, None)(ZIO.succeed(()))
        } yield jsonOk
      }
    },

    // 200, 409
    Method.GET / "channels.stats" -> handler {
      wrapHttpEndpoint {
        for {
          pluginsSpec   <- readPluginsSpecOr409
          pac           <- Sc4pac.init(pluginsSpec.config)
          combinedStats =  JD.Channel.Stats.aggregate(pac.context.repositories.map(_.channel.stats))
          statsItems    =  pac.context.repositories.map(r => ChannelStatsItem(
                             url = r.baseUri.toString,
                             channelLabel = r.channel.info.channelLabel.orNull,
                             r.channel.stats,
                           ))
        } yield jsonResponse(ChannelStatsAll(combinedStats, statsItems))
      }
    },

  )

  def routes(webAppDir: Option[os.Path]): Routes[ProfilesDir & service.FileSystem & ServerFiber & Client & Ref[ServerConnection] & Ref[Option[FileCache]], Nothing] = {
    // Extract profile ID from URL query parameter and add it to environment.
    // 400 error if "profile" parameter is absent.
    val profileRoutes2 =
      profileRoutes.transform((handler0) => handler { (req: Request) =>
        req.url.queryParams.getAll("profile").headOption match {
          case Some[ProfileId](id) =>
            handler0(req)
              .provideSomeLayer[ProfilesDir & service.FileSystem & Ref[Option[FileCache]]](zio.ZLayer.fromFunction(
                (dir: ProfilesDir) => ProfileRoot(dir.path / id)
              ))
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
        val num = connectionsSinceLaunch.incrementAndGet()
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
          } yield ()

          // keeps track of number of open connections
          ZIO.acquireReleaseWith
            (acquire = ZIO.serviceWithZIO[Ref[ServerConnection]](_.update { s =>
              ServerConnection(numConnections = s.numConnections + 1, currentChannel = Some(wsChannel))
            }))
            (release = (_) => ZIO.serviceWithZIO[Ref[ServerConnection]](_.modify { s =>
              val remainingConnections = s.numConnections - 1
              (Some(remainingConnections), ServerConnection(numConnections = remainingConnections, currentChannel = s.currentChannel.filter(_ != wsChannel)))
            }).flatMap(shutdownServerIfNoConnections(_, reason = "All connections from client to server are closed.")))
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

      // 200, 500
      Method.GET / "profiles.list" -> handler {
        wrapHttpEndpoint {
          for {
            profilesDir <- ZIO.service[ProfilesDir]
            profiles <- JD.Profiles.readOrInit
          } yield jsonResponse(ProfilesList(
            profiles = profiles.profiles,
            currentProfileId = profiles.currentProfileId,
            profilesDir = profilesDir.path.toNIO,
          ))
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

      // 200, 400
      Method.POST / "profiles.switch" -> handler((req: Request) => wrapHttpEndpoint {
        for {
          arg  <- parseOr400[ProfileIdObj](req.body, ErrorMessage.BadRequest("Missing profile ID.", "Pass the \"id\" for the profile to switch to."))
          ps   <- JD.Profiles.readOrInit
                    .filterOrFail(_.profiles.exists(_.id == arg.id))(jsonResponse(
                      ErrorMessage.BadRequest(s"""Profile ID "${arg.id}" does not exist.""", "Make sure to only switch to existing profiles.")
                    ).status(Status.BadRequest))
          _    <- ZIO.unlessDiscard(ps.currentProfileId.contains(arg.id)) {
                    val ps2 = ps.copy(currentProfileId = Some(arg.id))
                    JD.Profiles.pathURIO.flatMap(JsonIo.write(_, ps2, origState = Some(ps))(ZIO.succeed(())))
                  }
        } yield jsonOk
      }),

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
        jsonResponse(ErrorMessage.ServerError("Unhandled error. This looks like a bug. Please report it.", err.toString)).status(Status.InternalServerError)
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

  // If the last remaining connection was closed and no new connection was opened after a short delay, then shutdown the server
  def shutdownServerIfNoConnections(remainingConnections: Option[Int], reason: String): URIO[Ref[ServerConnection] & ServerFiber, Unit] =
    ZIO.unlessDiscard(!options.autoShutdown || remainingConnections.exists(_ > 0))(for {
      _      <- ZIO.sleep(Constants.serverShutdownDelay)  // defer shutdown to accept new connection in case of page refresh
      count  <- ZIO.serviceWithZIO[Ref[ServerConnection]](_.get.map(_.numConnections))
      _      <- ZIO.unlessDiscard(count > 0)(for {
                  server <- ZIO.service[ServerFiber]
                  fiber  <- server.promise.await
                  _      <- ZIO.serviceWith[Logger](_.log(s"$reason Shutting down server."))
                  _      <- fiber.interrupt.fork
                } yield ())
    } yield ()).provideSomeLayer(httpLogger)

}
