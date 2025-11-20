package io.github.memo33
package sc4pac
package api

import zio.http.*
import zio.http.ChannelEvent.{Read, Unregistered, UserEvent, UserEventTriggered}
import zio.http.codec.PathCodec.literal
import zio.{ZIO, IO, URIO, Ref}
import upickle.default as UP

import sc4pac.JsonData as JD
import JD.{bareModuleRw, uriRw}
import sc4pac.cli.Commands.Server.{ServerFiber, ServerConnection}


class Api(options: sc4pac.cli.Commands.ServerOptions) extends AuthMiddleware {
  private val connectionsSinceLaunch = java.util.concurrent.atomic.AtomicInteger(0)
  private val currentRepairPlan = java.util.concurrent.atomic.AtomicReference(Option.empty[PromptMessage.ConfirmRepairPlan])

  def jsonResponse[A : UP.Writer](obj: A): Response = Response.json(UP.write(obj, indent = options.indent))
  private def jsonFrame[A : UP.Writer](obj: A): WebSocketFrame = WebSocketFrame.Text(UP.write(obj, indent = options.indent))

  // only for use in API, not CLI, in particular to ensure only profile roots constructed by ID can be deleted via API
  private def profileRootFromId(id: String)/*: zio.RLayer[ProfilesDir, ProfileRoot]*/ =  // inferred type is more specific which helps the compiler
    zio.ZLayer.fromFunction(profileRootFromId0(id, _))
  private def profileRootFromId0(id: String, dir: ProfilesDir): ProfileRoot = ProfileRoot(dir.path / id)

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
    case abort: java.lang.IncompatibleClassChangeError => ErrorMessage.ServerError(
      "The Java version installed on your system might be too old. Install a more recent Java version.", abort.getMessage)
    case abort: error.FileOpsFailure => ErrorMessage.FileOperationsFailed(abort.getMessage, "")
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
    case abort: java.lang.IncompatibleClassChangeError => Status.InternalServerError
    case abort: error.FileOpsFailure => Status.InternalServerError
  }

  val jsonOk = jsonResponse(ResultMessage(ok = true))

  private val httpLogger = {
    val cliLogger: Logger = CliLogger()
    zio.ZLayer.succeed(cliLogger)
  }

  private def handleUnhandledError(err: Throwable): zio.UIO[Response] = ZIO.succeed {
    err.printStackTrace()
    jsonResponse(ErrorMessage.ServerError("Unhandled error. This looks like a bug. Please report it.", err.toString)).status(Status.InternalServerError)
  }

  /** Handles some errors and provides http logger (not used for update-websocket). */
  private def wrapHttpEndpoint[R](task: ZIO[R & Logger, Throwable | Response, Response]): ZIO[R, Response, Response] = {
    task.provideSomeLayer(httpLogger)
      .catchAll {
        case response: Response => ZIO.succeed(response)
        case err: cli.Commands.ExpectedFailure => ZIO.succeed(jsonResponse(expectedFailureMessage(err)).status(expectedFailureStatus(err)))
        case t: Throwable => handleUnhandledError(t)
      }
      .catchAllDefect(t => handleUnhandledError(t))
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

  private def getInfoJsonOr404(pac: Sc4pac, module: BareModule): IO[Throwable | Response, JD.Package] =
    pac.infoJson(module).someOrFail(  // TODO avoid decoding/encoding json
      jsonResponse(ErrorMessage.PackageNotFound("Package not found in any of your channels.", module.orgName)).status(Status.NotFound)
    )

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

  // Extract profile ID from URL query parameter and add it to environment.
  // 400 error if "profile" parameter is absent.
  val interceptProfile: HandlerAspect[UserInfo, ProfileRoot] =
    HandlerAspect.interceptIncomingHandler {
      handler { (req: Request) =>
        req.url.queryParams.getAll("profile").headOption match {
          case Some[ProfileId](id) =>
            ZIO.serviceWith[UserInfo] { userInfo =>
              (req, profileRootFromId0(id = id, userInfo.profilesDir))
            }
          case None =>
            ZIO.fail(jsonResponse(ErrorMessage.BadRequest(
              """URL query parameter "profile" is required.""", "Pass the profile ID as query."
            )).status(Status.BadRequest))
        }
      }
    }

  type ProfileRouteEnv[R] = R & service.FileSystem & Ref[Option[FileCache]]

  /** Routes that require a `profile=id` query parameter as part of the URL. */
  def profileRoutes = zio.Chunk[(Route[ProfileRouteEnv[UserInfo], Nothing], AuthScope)](

    // 200, 409, 500
    Method.GET / "profile.read" -> handler { (req: Request) =>
      wrapHttpEndpoint {
        for {
          pluginsSpec <- readPluginsSpecOr409
        } yield jsonResponse(pluginsSpec.config)
      }
    } @@ interceptProfile -> AuthScope.read,

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
    } @@ interceptProfile -> AuthScope.write,

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
    } @@ interceptProfile -> AuthScope.write,

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
    } @@ interceptProfile -> AuthScope.write,

    // 200, 400, 409
    Method.POST / "plugins.reinstall" -> handler((req: Request) => wrapHttpEndpoint {
      val redownload = req.url.queryParams.getAll("redownload").nonEmpty
      for {
        mods        <- parseModulesOr400(req.body)
        pluginsSpec <- readPluginsSpecOr409
        pac         <- Sc4pac.init(pluginsSpec.config)
        _           <- pac.reinstall(mods.toSet, redownload = redownload)  // non-installed packages are ignored (TODO should they raise an error?), but may be redownloaded if they are pending updates
      } yield jsonOk
    }) @@ interceptProfile -> AuthScope.write,

    // 200, 409
    Method.GET / "plugins.repair.scan" -> handler((req: Request) => wrapHttpEndpoint {
      for {
        pluginsSpec <- readPluginsSpecOr409
        pac         <- Sc4pac.init(pluginsSpec.config)
        pluginsRoot <- pluginsSpec.config.pluginsRootAbs
        plan        <- pac.repairScan(pluginsRoot = pluginsRoot)
      } yield {
        val msg = PromptMessage.ConfirmRepairPlan(plan)
        currentRepairPlan.set(Some(msg))
        jsonResponse(msg)
      }
    }) @@ interceptProfile -> AuthScope.read,

    // 200, 400, 404, 409
    Method.POST / "plugins.repair" -> handler((req: Request) => wrapHttpEndpoint {
      for {
        responseMsg  <- parseOr400[ResponseMessage](req.body, ErrorMessage.BadRequest("Malformed repair arguments", "Pass response message from previous call to 'plugins.repair.scan'." ))
        pluginsSpec  <- readPluginsSpecOr409
        pac          <- Sc4pac.init(pluginsSpec.config)
        pluginsRoot  <- pluginsSpec.config.pluginsRootAbs
        promptMsgOpt =  currentRepairPlan.getAndUpdate((promptMsgOpt) => if (promptMsgOpt.exists(_.accept(responseMsg))) None else promptMsgOpt)
        _            <- ZIO.whenDiscard(!promptMsgOpt.exists(_.accept(responseMsg)))(ZIO.fail(jsonResponse(ErrorMessage.BadRequest(
                          """Matching repair plan for current token not found anymore.""", "Pass the latest response message from 'plugins.repair.scan'."
                        )).status(Status.NotFound)))
        updateNeeded <- pac.repair(promptMsgOpt.get.plan, pluginsRoot = pluginsRoot)
      } yield jsonResponse(ResultMessage(ok = !updateNeeded))
    }) @@ interceptProfile -> AuthScope.write,

    // Test the websocket using Javascript in webbrowser (messages are also logged in network tab):
    //     let ws = new WebSocket('ws://localhost:51515/update'); ws.onmessage = function(e) { console.log(e) };
    //     ws.send(JSON.stringify({}))
    Method.GET / "update" -> handler { (req: Request) =>
      readPluginsSpecOr409.foldZIO(
        failure = ZIO.succeed[Response](_),
        success = pluginsSpec =>
          Handler.webSocket { wsChannel =>
            val updateTask: zio.RIO[ProfileRouteEnv[ProfileRoot & WebSocketLogger], Message] =
              for {
                fs           <- ZIO.service[service.FileSystem]
                _            <- fs.injectErrorInTest
                credentials  =  Downloader.Credentials(
                                  simtropolisToken = req.url.queryParams.getAll("simtropolisToken").headOption.orElse(fs.env.simtropolisToken),
                                )
                credentialsDesc = credentials.simtropolisToken.map(t => s"with Simtropolis token: ${t.length} bytes").getOrElse("without Simtropolis token")
                pac          <- Sc4pac.init(pluginsSpec.config, refreshChannels = req.url.queryParams.getAll("refreshChannels").nonEmpty)
                pluginsRoot  <- pluginsSpec.config.pluginsRootAbs
                wsLogger     <- ZIO.service[WebSocketLogger]
                _            <- ZIO.succeed(wsLogger.log(s"Updating... ($credentialsDesc)"))
                flag         <- ZIO.serviceWithZIO[WebSocketPrompter](_.promptForInitialArguments())
                                  .flatMap { args =>
                                    val variantSelection = VariantSelection(
                                      currentSelections = Map.empty,
                                      initialSelections = pluginsSpec.config.variant,
                                      importedSelections = args.importedSelections,
                                    )
                                    pac.update(pluginsSpec.explicit, variantSelection, pluginsRoot = pluginsRoot)
                                  }
                                  .provideSomeLayer(zio.ZLayer.succeedEnvironment(zio.ZEnvironment(
                                    WebSocketPrompter(wsChannel, wsLogger),
                                    credentials,
                                  )))
              } yield ResultMessage(ok = true)

            val wsTask: zio.RIO[ProfileRouteEnv[ProfileRoot & Ref[Option[FileCache]]], Unit] =
              WebSocketLogger.run(send = msg => wsChannel.send(Read(jsonFrame(msg)))) {
                for {
                  finalMsg <- updateTask.catchAll {
                                case err: cli.Commands.ExpectedFailure => ZIO.succeed(expectedFailureMessage(err))
                                case err => ZIO.succeed(ErrorMessage.ServerError("Unexpected error during Update. This looks like a bug. Please report it.", Logger.stackTraceToString(err)))
                              }
                              .catchAllDefect {
                                case err => ZIO.succeed(ErrorMessage.ServerError("Unexpected error during Update (defect). This looks like a bug. Please report it.", Logger.stackTraceToString(err)))
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
            ).provideSomeLayer(httpLogger): zio.RIO[ProfileRouteEnv[ProfileRoot], Unit]

          }.toResponse: zio.URIO[ProfileRouteEnv[ProfileRoot], Response]
      )
    } @@ interceptProfile -> AuthScope.write,

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
          (stats, searchResult) <- pac.search(searchText, threshold, category = category, notCategory = notCategory, channel = channelOpt, skipStats = false)
          createStatus <- installedStatusBuilder(pluginsSpec)
        } yield jsonResponse(PackagesSearchResult(
          packages = searchResult.flatMap { case (pkg, ratio, summaryOpt) =>
            val statusOrNull = createStatus(pkg)
            if (ignoreInstalled && statusOrNull != null && statusOrNull.installed != null)
              None
            else
              Some(PackageSearchResultItem(pkg, relevance = ratio, summary = summaryOpt.getOrElse(""), status = statusOrNull))
          },
          stats = stats.orNull,
        ))
      }
    } @@ interceptProfile -> AuthScope.read,

    Method.POST / "packages.search.id" -> handler((req: Request) => wrapHttpEndpoint {
      for {
        args         <- parseOr400[FindPackagesArgs](req.body, ErrorMessage.BadRequest("Malformed package names", """Pass a "packages" array of strings of the form '<group>:<name>'.""" ))
        pluginsSpec  <- readPluginsSpecOr409
        pac          <- Sc4pac.init(pluginsSpec.config)
        searchResult <- pac.searchById(args.packages, args.externalIds.groupMap(_._1)(_._2).map((p, ids) => (p, ids.toSet)))
        createStatus <- installedStatusBuilder(pluginsSpec)
      } yield jsonResponse(PackagesSearchResult(
        packages = searchResult._1.map { case (pkg, summaryOpt) =>
          val statusOrNull = createStatus(pkg)
          PackageSearchResultItem(pkg, relevance = 100, summary = summaryOpt.getOrElse(""), status = statusOrNull)
        },
        notFoundPackageCount = searchResult._2,
        notFoundExternalIdCount = searchResult._3,
      ))
    }) @@ interceptProfile -> AuthScope.read,

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
    }) @@ interceptProfile -> AuthScope.read,

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
          remoteData    <- getInfoJsonOr404(pac, mod)
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
    } @@ interceptProfile -> AuthScope.read,

    // 200, 409
    Method.GET / "plugins.added.list" -> handler {
      wrapHttpEndpoint {
        for {
          pluginsSpec <- readPluginsSpecOr409
        } yield jsonResponse(pluginsSpec.explicit)
      }
    } @@ interceptProfile -> AuthScope.read,

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
    } @@ interceptProfile -> AuthScope.read,

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
    } @@ interceptProfile -> AuthScope.read,

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
    } @@ interceptProfile -> AuthScope.read,

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
    } @@ interceptProfile -> AuthScope.write,

    // 200, 400, 409
    Method.POST / "variants.set" -> handler((req: Request) => wrapHttpEndpoint {
      for {
        selections   <- parseOr400[Map[String, String]](req.body, ErrorMessage.BadRequest("Malformed variant selections.", "Pass variants as a mapping of strings."))
        pluginsSpec  <- readPluginsSpecOr409
        pluginsSpec2 =  pluginsSpec.copy(config = pluginsSpec.config.copy(
                          variant = pluginsSpec.config.variant ++ selections,
                        ))
        path         <- JD.PluginsSpec.pathURIO
        _            <- JsonIo.write(path, pluginsSpec2, None)(ZIO.unit)
      } yield jsonOk
    }) @@ interceptProfile -> AuthScope.write,

    // 200, 400, 404, 409
    Method.GET / "variants.choices" -> handler((req: Request) => wrapHttpEndpoint {
      for {
        pkg          <- ZIO.fromOption(req.url.queryParams.getAll("pkg").headOption).orElseFail(jsonResponse(ErrorMessage.BadRequest(
                          """Query parameter "pkg" is required.""", "Pass the package identifier as query."
                        )).status(Status.BadRequest))
        variantId    <- ZIO.fromOption(req.url.queryParams.getAll("variantId").headOption).orElseFail(jsonResponse(ErrorMessage.BadRequest(
                          """Query parameter "variantId" is required.""", "Pass the variant identifier as query."
                        )).status(Status.BadRequest))
        mod          <- parseModuleOr400(pkg)
        pluginsSpec  <- readPluginsSpecOr409
        pac          <- Sc4pac.init(pluginsSpec.config)
        msg          <- pac.variantChoices(mod, variantId, pluginsSpec.config.variant)
                          .someOrFail(jsonResponse(ErrorMessage.PackageNotFound(
                            s"Variant ID $variantId not found for package.", mod.orgName,
                          )).status(Status.NotFound))
      } yield jsonResponse(msg.copy(responses = Map.empty))
    }) @@ interceptProfile -> AuthScope.read,

    // 200, 409
    Method.GET / "channels.list" -> handler {
      wrapHttpEndpoint {
        for {
          pluginsSpec <- readPluginsSpecOr409
        } yield jsonResponse(pluginsSpec.config.channels)
      }
    } @@ interceptProfile -> AuthScope.read,

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
          _            <- JsonIo.write(path, pluginsSpec2, None)(ZIO.unit)
        } yield jsonOk
      }
    } @@ interceptProfile -> AuthScope.write,

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
    } @@ interceptProfile -> AuthScope.read,

    // 200, 400, 404, 409
    Method.POST / "plugins.export" -> handler((req: Request) => wrapHttpEndpoint {
      for {
        mods        <- parseModulesOr400(req.body)
        pluginsSpec <- readPluginsSpecOr409
        pac         <- Sc4pac.init(pluginsSpec.config)
        data        <- pac.`export`(mods, pluginsSpec.config.variant, pluginsSpec.config.channels)
      } yield jsonResponse(data)
    }) @@ interceptProfile -> AuthScope.read,

  )

  type GenericRouteEnv[R] = R & service.FileSystem & ServerFiber & Client & Ref[ServerConnection]

  // profile-independent routes
  def genericRoutes = zio.Chunk[(Route[GenericRouteEnv[UserInfo], Nothing], AuthScope)](

    // 200
    Method.GET / "server.status" -> handler {
      wrapHttpEndpoint {
        def getPropSafe(prop: String): String = scala.util.Try(System.getProperty(prop)).fold((e) => e.toString, (value) => value)
        def dedupe(props: String*): String = props.flatMap(p => Option(getPropSafe(p))).distinct.mkString("/")
        ZIO.attempt(jsonResponse(ServerStatus(
          sc4pacVersion = cli.BuildInfo.version,
          osVersion = Seq("os.name", "os.version", "os.arch").map(getPropSafe).mkString(" "),
          javaVersion = s"${Runtime.version.feature()}",
          javaRuntime = Seq(
            s"""${getPropSafe("java.vm.name")} ${dedupe("java.vm.version", "java.version")}""",
            dedupe("java.vm.vendor", "java.vendor"),
            getPropSafe("java.runtime.name"),
            getPropSafe("java.home")
          ).mkString(", "),
        )))
      }
    } -> AuthScope.read,

    // websocket allowing to monitor whether server is alive (supports no particular message exchange)
    Method.GET / "server.connect" -> handler {
      val num = connectionsSinceLaunch.incrementAndGet()
      Handler.webSocket { wsChannel =>
        val wsTask = for {
          logger <- ZIO.service[Logger]
          _      <- ZIO.succeed(logger.log(s"Registered websocket connection $num."))
          _      <- wsChannel.receiveAll {
                      case UserEventTriggered(UserEvent.HandshakeComplete) => ZIO.unit  // ignore expected event
                      case Unregistered =>
                        logger.log(s"Unregistered websocket connection $num.")  // client closed websocket (results in websocket shutdown)
                        ZIO.unit
                      case event =>
                        logger.warn(s"Discarding unexpected websocket event: $event")
                        ZIO.unit  // discard all unexpected messages (and events) and continue receiving
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
    } -> AuthScope.write,

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
    }) -> AuthScope.open,

    // 200, 500
    Method.GET / "profiles.list" -> handler { (req: Request) =>
      val includePlugins = req.url.queryParams.getAll("includePlugins").nonEmpty
      wrapHttpEndpoint {
        for {
          profilesDir <- ZIO.service[ProfilesDir]
          profiles <- JD.Profiles.readOrInit
          profilesData <-
            if (!includePlugins)
              ZIO.succeed(profiles.profiles.map(p => ProfilesList.ProfileData2(id = p.id, name = p.name)))
            else
              ZIO.foreachPar(profiles.profiles) { p =>
                for {
                  pluginsSpecOpt <- JD.PluginsSpec.readMaybe.provideSomeLayer(profileRootFromId(p.id))
                } yield ProfilesList.ProfileData2(id = p.id, name = p.name, pluginsRoot = pluginsSpecOpt.map(_.config.pluginsRoot).orNull)
              }
        } yield jsonResponse(ProfilesList(
          profiles = profilesData,
          currentProfileId = profiles.currentProfileId,
          profilesDir = profilesDir.path.toNIO,
        ))
      }.provideSomeLayer(UserInfo.toProfile)
    } -> AuthScope.read,

    // 200, 400
    Method.POST / "profiles.add" -> handler { (req: Request) =>
      wrapHttpEndpoint {
        for {
          profileName <- parseOr400[ProfileName](req.body, ErrorMessage.BadRequest("Missing profile name.", "Pass the \"name\" of the new profile."))
          ps          <- JD.Profiles.readOrInit
          (ps2, p)    = ps.add(profileName.name)
          jsonPath    <- JD.Profiles.pathURIO
          _           <- ZIO.attemptBlockingIO { os.makeDir.all(jsonPath / os.up) }
          _           <- JsonIo.write(jsonPath, ps2, None)(ZIO.unit)
        } yield jsonResponse(p)
      }.provideSomeLayer(UserInfo.toProfile)
    } -> AuthScope.write,

    // 200, 400, 500
    Method.POST / "profiles.remove" -> handler((req: Request) => wrapHttpEndpoint {
      for {
        arg    <- parseOr400[ProfileIdObj](req.body, ErrorMessage.BadRequest("Missing profile ID.", "Pass the \"id\" for the profile to remove."))
        data   <- JD.Profiles.readOrInit
        idx    <- ZIO.succeed(data.profiles.indexWhere(_.id == arg.id))
                    .filterOrFail(_ != -1)(jsonResponse(
                      ErrorMessage.BadRequest(s"""Profile ID "${arg.id}" does not exist.""", "Make sure to only remove existing profiles.")
                    ).status(Status.BadRequest))
        profileRoot <- ZIO.service[ProfileRoot].provideSomeLayer(profileRootFromId(data.profiles(idx).id))
        logger <- ZIO.service[Logger]
        _      <- ZIO.attemptBlockingIO {
                    if (os.exists(profileRoot.path)) {
                      for (path <- os.list.stream(profileRoot.path).find(os.isDir)) {
                        // sanity check (profileRoot was created by GUI, so should only contain two .json files)
                        throw java.nio.file.AccessDeniedException(s"Failed to remove profile directory as it contains unexpected subdirectories: $path")
                      }
                      logger.log(s"Deleting profile directory: ${profileRoot.path}")
                      Sc4pac.removeAllForcibly(profileRoot.path)
                    }
                  }
        ps2    =  data.profiles.patch(from = idx, Nil, replaced = 1)
        data2  =  data.copy(
                    profiles = ps2,
                    currentProfileId = data.currentProfileId.filter(_ != arg.id).orElse(ps2.lastOption.map(_.id)),
                  )
        _      <- JD.Profiles.pathURIO.flatMap(JsonIo.write(_, data2, origState = Some(data))(ZIO.unit))
      } yield jsonOk
    }.provideSomeLayer(UserInfo.toProfile)) -> AuthScope.write,

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
                  JD.Profiles.pathURIO.flatMap(JsonIo.write(_, ps2, origState = Some(ps))(ZIO.unit))
                }
      } yield jsonOk
    }.provideSomeLayer(UserInfo.toProfile)) -> AuthScope.write,

    // 200, 400
    Method.POST / "profiles.rename" -> handler((req: Request) => wrapHttpEndpoint {
      for {
        arg  <- parseOr400[JD.ProfileData](req.body, ErrorMessage.BadRequest("Missing profile ID or name.", "Pass the \"id\" and \"name\" for the profile."))
        data <- JD.Profiles.readOrInit
                  .filterOrFail(_.profiles.exists(_.id == arg.id))(jsonResponse(
                    ErrorMessage.BadRequest(s"""Profile ID "${arg.id}" does not exist.""", "Make sure to only rename existing profiles.")
                  ).status(Status.BadRequest))
        data2 = data.copy(profiles = data.profiles.map(p => if (p.id == arg.id) p.copy(name = arg.name) else p))
        _    <- JD.Profiles.pathURIO.flatMap(JsonIo.write(_, data2, origState = Some(data))(ZIO.unit))
      } yield jsonOk
    }.provideSomeLayer(UserInfo.toProfile)) -> AuthScope.write,

    // 200
    Method.GET / "settings.all.get" -> handler(wrapHttpEndpoint {
      JD.Profiles.readOrInit.map(profiles => jsonResponse(profiles.settings))
    }.provideSomeLayer(UserInfo.toProfile)) -> AuthScope.write,  // not `AuthScope.read` as this might contain sensitive info like the ST token

    // 200, 400
    Method.POST / "settings.all.set" -> handler((req: Request) => wrapHttpEndpoint {
      for {
        newSettings <- parseOr400[ujson.Value](req.body, ErrorMessage.BadRequest("Missing settings", "Pass the settings as JSON body of the request."))
        ps          <- JD.Profiles.readOrInit
        ps2         =  ps.copy(settings = newSettings)
        jsonPath    <- JD.Profiles.pathURIO
        _           <- JsonIo.write(jsonPath, ps2, None)(ZIO.unit)
      } yield jsonOk
    }.provideSomeLayer(UserInfo.toProfile)) -> AuthScope.write,

  )

  def fetchRoutes = zio.Chunk[(Route[UserInfo & Client, Nothing], AuthScope)](

    // 200, 400
    Method.GET / "image.fetch" -> handler((req: Request) => wrapHttpEndpoint {
      for {
        rawUrl <- ZIO.fromOption(req.url.queryParams.getAll("url").headOption).orElseFail(jsonResponse(ErrorMessage.BadRequest(
                    """Query parameter "url" is required.""", "Pass the remote image url as parameter."
                  )).status(Status.BadRequest))
        url    <- ZIO.fromEither(zio.http.URL.decode(rawUrl).left.map(malformedUrl => jsonResponse(ErrorMessage.BadRequest(
                    "Malformed url", malformedUrl.getMessage
                  )).status(Status.BadRequest)))
        // TODO use credentials.addAuthorizationToMatchingRequest(Request.get(url))
        // TODO handle retrys
        resp   <- zio.http.ZClient.batched(Request.get(url))  // TODO this first downloads the entire response body before forwarding the response
                                                              // Using `ZClient.streaming` instead is blocked on https://github.com/zio/zio-http/issues/3197
      } yield resp
    }) -> AuthScope.write,

  )

  private val websocketRoutePatterns = Set[RoutePattern[?]](Method.GET / "update", Method.GET / "server.connect")
  def tokenRoutes0 = profileRoutes ++ genericRoutes ++ fetchRoutes
  def tokenRoutes =  // these require use of an access token
    Routes(tokenRoutes0.map { case (route, scope) =>
      if (websocketRoutePatterns.contains(route.routePattern))
        route.transform(handler => handler @@ tokenAuth(scope, allowFromQuery = true))
      else
        route.transform(handler => handler @@ tokenAuth(scope))
    })

  def routes(webAppDir: Option[os.Path]): Routes[TokenService & service.FileSystem & ServerFiber & Client & Ref[ServerConnection] & Ref[Option[FileCache]], Nothing] = {
    val authRoutes = literal("auth") / authEndpoints
    (authRoutes ++ tokenRoutes ++ webAppDir.map(staticRoutes).getOrElse(Routes.empty))
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
