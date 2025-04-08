package io.github.memo33
package sc4pac

import zio.*
import zio.test.*
import zio.http.{Client, Body, URL, Response, Request, Handler, ChannelEvent, WebSocketFrame}
import upickle.default as UP
import ujson.Obj

import JsonData as JD
import cli.Commands.ServerOptions
import sc4pac.test.FileServer

object ApiSpecZIO extends ZIOSpecDefault {

  /** Creates a temporary profiles directory which is removed when the Scope is closed. */
  val profilesDirLayer: ZLayer[Any, Throwable, ProfilesDir] =
    ZLayer.scoped(
      ZIO.acquireRelease(ZIO.attemptBlockingIO {
        ProfilesDir(os.temp.dir(os.pwd / "target", prefix = "test-tmp"))
      })(profilesDir => ZIO.attemptBlockingIO {
        os.remove.all(profilesDir.path)
      }.orDie)
    )

  /** Launches an API server for the duration of the Scope. */
  val serverLayer: zio.ZLayer[ProfilesDir & service.FileSystem, Throwable, ServerOptions] =
    zio.ZLayer.scoped(
      for {
        profilesDir  <- ZIO.serviceWith[ProfilesDir](_.path)
        options      =  ServerOptions(
                          port = scala.util.Random.between(50000, 60000),
                          profilesDir = profilesDir.toString,
                          autoShutdown = false,
                          indent = 1,
                        )
        fiber        <- cli.Commands.Server.serve(options, profilesDir = profilesDir, webAppDir = None)
        _            <- ZIO.addFinalizerExit(exitVal => ZIO.succeed {
                          println(s"...Sc4pac server on port ${options.port} has been shut down: $exitVal")
                        })
      } yield options
    )

  val fileServerLayer: ZLayer[Any, Nothing, FileServer] =
    FileServer.serve(os.pwd / "channel-testing" / "json", port = 8090)  // TODO port is hardcoded in ./channel-testing/ files

  val setFileServerChannel: ZIO[FileServer & JD.ProfileData & ServerOptions & Client, Throwable, Unit] =
    for {
      port      <- ZIO.serviceWith[FileServer](_.port)
      profileId <- ZIO.serviceWith[JD.ProfileData](_.id)
      resp      <- postEndpoint(s"channels.set?profile=$profileId", jsonBody(Seq(s"http://localhost:$port/")))
      _         <- ZIO.whenDiscard(resp.status.code != 200)(ZIO.fail(AssertionError(s"/channels.set responded with ${resp.status.code}")))
    } yield ()

  val currentProfileLayer: zio.ZLayer[ServerOptions & Client, Throwable, JD.ProfileData] =
    ZLayer(
      for {
        profilesList <- getEndpoint("profiles.list").flatMap(resp => parseBody[api.ProfilesList](resp.body))
        id <- ZIO.getOrFail(profilesList.currentProfileId)
        profile <- ZIO.getOrFail(profilesList.profiles.find(_.id == id))
      } yield profile
    )

  def withTestResultRef[R](test: RIO[R & Ref[TestResult], Unit]): RIO[R, TestResult] = {
    val emptyTestResultRefLayer: ZLayer[Any, Nothing, Ref[TestResult]] = ZLayer(Ref.make(TestResult.allSuccesses(Nil)))
    test.foldZIO(
      // In case of exception, check if preliminary assertions already failed.
      // If so, return the partial test result ("fail early"), as the output is
      // likely more useful than the err.
      failure = (err: Throwable) =>
        ZIO.serviceWithZIO[Ref[TestResult]](_.get).flatMap(partialResult =>
          if (partialResult.isFailure) ZIO.succeed(partialResult) else ZIO.fail(err)
        ),
      success = (_: Unit) => ZIO.serviceWithZIO[Ref[TestResult]](_.get),
    ).provideSomeLayer(emptyTestResultRefLayer)
  }

  /** Adds an assert to the mutable reference */
  def addTestResult(assert: TestResult): ZIO[Ref[TestResult], Nothing, Unit] =
    ZIO.serviceWithZIO[Ref[TestResult]](_.update(_ && assert))

  def jsonBody[A : UP.Writer](data: A): Body =
    Body.fromChunk(zio.Chunk.fromArray(UP.writeToByteArray(data)), zio.http.MediaType.application.json)

  def parseBody[A : UP.Reader](body: Body) = body.asArray.flatMap(bytes => ZIO.attempt { UP.read[A](bytes) })

  // Asserts that response is 200 (stored in Ref) and parses the body. */
  def getBody200[A : UP.Reader](response: Response): RIO[Ref[TestResult], A] =
    for {
      _    <- addTestResult(assertTrue(response.status.code == 200))
      data <- parseBody[A](response.body)
    } yield data

  def isOk200(response: Response): RIO[Ref[TestResult], Unit] =
    for {
      data <- getBody200[ujson.Value](response)
      _    <- addTestResult(assertTrue(data == jsonOk))
    } yield ()

  def endpoint(path: String): RIO[ServerOptions, URL] =
    ZIO.serviceWith[ServerOptions](options => URL.empty.absolute(zio.http.Scheme.HTTP, "localhost", options.port) / path)

  def postEndpoint(path: String, body: Body): RIO[ServerOptions & Client, Response] =
    for {
      url <- endpoint(path)
      resp <- Client.batched(Request.post(url, body))
    } yield resp

  def getEndpoint(path: String): RIO[ServerOptions & Client, Response] =
    for {
      url <- endpoint(path)
      resp <- Client.batched(Request.get(url))
    } yield resp

  val jsonOk = Obj("$type" -> "/result", "ok" -> true)

  // def makeChannel(packages: Seq[JD.PackageAsset]) =
  //   for {
  //     channel <- YamlChannelBuilder().result(packages).provideSomeLayer(ZLayer.succeed(JD.Channel.Info(channelLabel = Some("Test-Channel"), metadataSourceUrl = None)))
  //   } yield channel

  val removeAll: RIO[JD.ProfileData & ServerOptions & Client, Unit] =
    for {
      id   <- ZIO.serviceWith[JD.ProfileData](_.id)
      resp <- getEndpoint(s"plugins.added.list?profile=$id")
      pkgs <- parseBody[Seq[String]](resp.body)
      resp <- postEndpoint(s"plugins.remove?profile=$id", jsonBody(pkgs))
      _    <- ZIO.whenDiscard(resp.status.code != 200)(ZIO.fail(AssertionError(s"/plugins.remove responded with ${resp.status.code}")))
    } yield ()

  def takeUntil[A](queue: Queue[A])(predicate: A => Boolean): UIO[(Seq[A], A)] =
    ZIO.iterate(List.empty[A])(cont = !_.headOption.exists(predicate)) {
      buf => queue.take.map(_ :: buf)
    }
    .map { buf => (buf.tail.reverse, buf.head) }

  class MsgFrame(val msg: api.Message, val json: ujson.Value)

  /** Calls a websocket endpoint, allowing to verify messages against
    * expectations:
    * - `respond`: handle prompt messages with desired responses
    * - `filter`: ignore some messages before passing them to `checkMessages`
    * - `checkMessages`: an effect to verify all the messages after filtering satisfy expectations.
    *
    * If this hangs, then `checkMessages` might wait for taking too many messages from the queue.
    */
  def wsEndpoint(
    path: String,
    respond: PartialFunction[api.PromptMessage, RIO[Ref[TestResult], api.ResponseMessage]],
    filter: MsgFrame => Boolean = _ => true,
    checkMessages: Queue[MsgFrame] => ZIO[Scope & Ref[TestResult], Throwable, Unit] = _ => ZIO.unit,
  ): ZIO[ServerOptions & Client & Ref[TestResult] & Scope, Throwable, Response] =
    for {
      url      <- endpoint(path).map(_.scheme(zio.http.Scheme.WS))
      promise  <- Promise.make[Throwable, Unit]  // abnormal exit of websocket channel
      queue    <- Queue.unbounded[MsgFrame]
      // _        <- ZIO.addFinalizerExit(exitVal => ZIO.succeed(println(s"websocket: scope closed: $exitVal")))
      resp     <- Handler.webSocket { channel =>
                    channel.receiveAll {
                      case ChannelEvent.Read(WebSocketFrame.Text(text)) =>
                        for {
                          json <- JsonIo.read[ujson.Value](text)
                          msg  <- JsonIo.read[api.Message](text)
                          frame = MsgFrame(msg, json)
                          _    <- ZIO.whenDiscard(filter(frame))(queue.offer(frame))
                          _    <- msg match {
                                    case msg: api.PromptMessage =>
                                      if (respond.isDefinedAt(msg)) {
                                        respond(msg).flatMap { response =>
                                          channel.send(ChannelEvent.Read(WebSocketFrame.Text(UP.write(response))))
                                        }
                                      } else {
                                        val errMsg = s"Partial function `respond` is not defined for prompt message: $msg"
                                        Console.printLine(errMsg).zipRight(ZIO.fail(AssertionError(errMsg)))
                                      }
                                    case _ => ZIO.unit
                                  }
                        } yield ()
                      case event => ZIO.unit  // Console.printLine(s"websocket: unexpected event: $event")
                    }.zipRight(channel.shutdown)
                      .onExit {
                        case zio.Exit.Failure(cause) => promise.failCause(cause)
                        case zio.Exit.Success(_) => promise.succeed(())
                      }
                  }.connect(url, zio.http.Headers.empty)
      _        <- addTestResult(assertTrue(resp.status.code == 101))  // switching protocol: upgrade to websocket
      _        <- promise.await.raceWith(checkMessages(queue))(
                    leftDone = (exitVal, fiberRight) => exitVal match {
                      case Exit.Success(_) =>
                        fiberRight.await.timeoutFail
                          (AssertionError("Timeout in checkMessages: possibly waiting for too many websocket messages"))
                          (5.seconds)
                        .zipLeft(fiberRight.interrupt)
                      case Exit.Failure(cause) => fiberRight.interrupt.zipRight(ZIO.refailCause(cause))
                    },
                    rightDone = (exitVal, fiberLeft) => exitVal match {
                      case Exit.Success(_) =>
                        fiberLeft.await.timeoutFail
                          (AssertionError("Timeout in websocket respond: possibly not answering some websocket prompt messages"))
                          (5.seconds)
                        .zipRight(fiberLeft.interrupt)
                      case Exit.Failure(cause) => fiberLeft.interrupt.zipRight(ZIO.refailCause(cause))
                    },
                  ).zipRight(queue.shutdown)
    } yield resp

  def spec = suite("ApiSpecZIO")(
    test("/server.status") {
      for {
        resp <- getEndpoint("server.status")
        data <- parseBody[api.ServerStatus](resp.body)
      } yield assertTrue(resp.status.code == 200, data.sc4pacVersion == cli.BuildInfo.version)
    },

    suite("settings") {
      Chunk(
        test("/settings.all.get") {
          for {
            resp <- getEndpoint("settings.all.get")
            data <- parseBody[ujson.Value](resp.body)
          } yield assertTrue(resp.status.code == 200, data == Obj())
        },
        test("/settings.all.set") {
          val dummySettings = Obj("a" -> 1, "b" -> Obj("c" -> 3, "d" -> Seq(4, 5)))
          withTestResultRef(for {
            _    <- postEndpoint("settings.all.set", jsonBody(dummySettings)).flatMap(isOk200)
            data <- getEndpoint("settings.all.get").flatMap(getBody200[ujson.Value])
            _    <- addTestResult(assertTrue(data == dummySettings))
          } yield ())
        },
      )
    },

    suite("profiles")(
      test("/profiles.list") {
        for {
          resp <- getEndpoint("profiles.list")
          data <- parseBody[api.ProfilesList](resp.body)
          profilesDir <- ZIO.service[ProfilesDir]
        } yield assertTrue(
          resp.status.code == 200,
          data.profiles.isEmpty,
          data.currentProfileId == None,
          data.profilesDir == profilesDir.path.toNIO,
        )
      },
      test("/profiles.add") {
        val name = s"Test-Profile 🐛 ${scala.util.Random.between(1000, 10000)}"
        withTestResultRef(for {
          profile  <- postEndpoint("profiles.add", jsonBody(Obj("name"-> name))).flatMap(getBody200[JD.ProfileData])
          _        <- addTestResult(assertTrue(profile.id == "1", profile.name == name))
          data     <- getEndpoint("profiles.list").flatMap(getBody200[api.ProfilesList])
          _        <- addTestResult(assertTrue(data.profiles == Seq(profile), data.currentProfileId.is(_.some) == profile.id))
          profile2 <- postEndpoint("profiles.add", jsonBody(Obj("name"-> s"$name~"))).flatMap(getBody200[JD.ProfileData])
          _        <- addTestResult(assertTrue(profile2.id == "2", profile2.name == s"$name~"))
          data     <- getEndpoint("profiles.list").flatMap(getBody200[api.ProfilesList])
          _        <- addTestResult(assertTrue(data.profiles == Seq(profile, profile2), data.currentProfileId.is(_.some) == profile2.id))
        } yield ())
      },
      test("/profiles.switch") {
        withTestResultRef(for {
          data   <- getEndpoint("profiles.list").flatMap(getBody200[api.ProfilesList])
          _      <- addTestResult(assertTrue(
                      data.profiles.exists(_.id == "1"),
                      !data.profiles.exists(_.id == "42"),
                      data.currentProfileId.is(_.some) != "1",
                    ))
          resp   <- postEndpoint("profiles.switch", jsonBody(Obj("id" -> "42")))
          _      <- addTestResult(assertTrue(resp.status.code == 400))
          data2  <- getEndpoint("profiles.list").flatMap(getBody200[api.ProfilesList])
          _      <- addTestResult(assertTrue(data2 == data))
          _      <- postEndpoint("profiles.switch", jsonBody(Obj("id" -> "1"))).flatMap(isOk200)
          data   <- getEndpoint("profiles.list").flatMap(getBody200[api.ProfilesList])
          _      <- addTestResult(assertTrue(data.currentProfileId.is(_.some) == "1"))
        } yield ())
      },
    ),

    suite("profile")(ZIO.serviceWith[JD.ProfileData](_.id).map { (profileId: String) =>
      Chunk(
        test("/profile.read") {
          withTestResultRef(for {
            resp   <- getEndpoint(s"profile.read?profile=$profileId")
            _      <- addTestResult(assertTrue(resp.status.code == 409))  // not initialized
            data   <- parseBody[api.ErrorMessage.ProfileNotInitialized](resp.body)
            _      <- addTestResult(assertTrue(data.platformDefaults.contains("plugins"), data.platformDefaults.contains("cache"), data.platformDefaults.contains("temp")))
          } yield ())
        },
        test("/profile.init") {
          withTestResultRef(for {
            config <- ZIO.serviceWith[ServerOptions](options => Obj(
                        "plugins" -> (os.Path(options.profilesDir) / profileId / "plugins").toString,
                        "cache" -> (os.Path(options.profilesDir) / profileId / "cache").toString,
                        "temp" -> "../temp",
                      ))
            data   <- postEndpoint(s"profile.init?profile=$profileId", jsonBody(config)).flatMap(getBody200[ujson.Value])
            _      <- addTestResult(assertTrue(data == Obj(
                        "pluginsRoot" -> "plugins", "cacheRoot" -> "cache", "tempRoot" -> "../temp",  // relative paths only when inside profiles directory
                        "variant" -> Obj(), "channels" -> Constants.defaultChannelUrls.map(_.toString),
                      )))
            resp   <- postEndpoint(s"profile.init?profile=$profileId", jsonBody(config))
            _      <- addTestResult(assertTrue(resp.status.code == 409))  // already initialized
            data2  <- getEndpoint(s"profile.read?profile=$profileId").flatMap(getBody200[ujson.Value])
            _      <- addTestResult(assertTrue(data2 == data))
          } yield ())
        },

        suite("channels")(
          test("/channels.list") {
            withTestResultRef(for {
              data <- getEndpoint(s"channels.list?profile=$profileId").flatMap(getBody200[Seq[String]])
              _    <- addTestResult(assertTrue(data == Constants.defaultChannelUrls.map(_.toString)))
            } yield ())
          },
          test("/channels.set") {
            val dummyChannels = Seq(
              "https://example.org/channel1",  // trailing slash is added
              "https://example.org/channel2/",
              "https://example.org/channel3.yaml",
              "https://example.org/channel3.yaml",  // duplicates are removed
              "file:///local/channel4/",
              "file:///C:/local/channel5.yaml",
            )
            withTestResultRef(for {
              resp <- postEndpoint(s"channels.set?profile=$profileId", jsonBody(Seq(":::")))
              _    <- addTestResult(assertTrue(resp.status.code == 400))
              _    <- postEndpoint(s"channels.set?profile=$profileId", jsonBody(dummyChannels)).flatMap(isOk200)
              data <- getEndpoint(s"channels.list?profile=$profileId").flatMap(getBody200[Seq[String]])
              _    <- addTestResult(assertTrue(data == dummyChannels.updated(0, s"${dummyChannels(0)}/").distinct))
              _    <- postEndpoint(s"channels.set?profile=$profileId", jsonBody(Seq.empty[String])).flatMap(isOk200)
              data <- getEndpoint(s"channels.list?profile=$profileId").flatMap(getBody200[Seq[String]])
              _    <- addTestResult(assertTrue(data == Constants.defaultChannelUrls.map(_.toString)))
            } yield ())
          },
          test("/channels.stats") {
            withTestResultRef[FileServer & JD.ProfileData & ServerOptions & Client](for {
              _    <- setFileServerChannel
              data <- getEndpoint(s"channels.stats?profile=$profileId").flatMap(getBody200[api.ChannelStatsAll])
              _    <- addTestResult(assertTrue(
                        data.combined.totalPackageCount == 3,
                        data.channels.length == 1,
                        data.channels(0).url.startsWith("http://localhost:"),
                      ))
            } yield ())
          },
        ),

        suite("plugins")(
          test("/plugins.added.list") {
            withTestResultRef(for {
              data <- getEndpoint(s"plugins.added.list?profile=$profileId").flatMap(getBody200[Seq[String]])
              _    <- addTestResult(assertTrue(data == Seq.empty[String]))
            } yield ())
          },
          test("/plugins.add") {
            withTestResultRef(for {
              _    <- postEndpoint(s"plugins.add?profile=$profileId", jsonBody(Seq("memo:demo-package"))).flatMap(isOk200)
              _    <- postEndpoint(s"plugins.add?profile=$profileId", jsonBody(Seq("memo:demo-package"))).flatMap(isOk200)  // idempotent
              data <- getEndpoint(s"plugins.added.list?profile=$profileId").flatMap(getBody200[Seq[String]])
              _    <- addTestResult(assertTrue(data == Seq("memo:demo-package")))
            } yield ())
          },
          test("/plugins.remove") {
            withTestResultRef(for {
              orig <- getEndpoint(s"plugins.added.list?profile=$profileId").flatMap(getBody200[Seq[String]])
              _    <- postEndpoint(s"plugins.add?profile=$profileId", jsonBody(Seq("test:nonexistent"))).flatMap(isOk200)
              data <- getEndpoint(s"plugins.added.list?profile=$profileId").flatMap(getBody200[Seq[String]])
              _    <- addTestResult(assertTrue(data == orig ++ Seq("test:nonexistent")))
              _    <- postEndpoint(s"plugins.remove?profile=$profileId", jsonBody(Seq("test:nonexistent"))).flatMap(isOk200)
              data <- getEndpoint(s"plugins.added.list?profile=$profileId").flatMap(getBody200[Seq[String]])
              _    <- addTestResult(assertTrue(data == orig))
              resp <- postEndpoint(s"plugins.remove?profile=$profileId", jsonBody(Seq("test:nonexistent")))
              _    <- addTestResult(assertTrue(resp.status.code == 400))
            } yield ())
          }
        ),

        suite("update")(
          test("/update (0 packages, abort update)") {
            withTestResultRef(ZIO.scoped {
              for {
                _ <- removeAll
                _ <- wsEndpoint(s"update?profile=$profileId",
                       respond = {
                         case msg: api.PromptMessage.ConfirmUpdatePlan =>
                           for {
                             _  <- addTestResult(assertTrue(
                                     msg.toRemove.isEmpty,
                                     msg.toInstall.isEmpty,
                                     msg.choices == Seq("Yes", "No"),
                                   ))
                           } yield msg.responses("No")
                       },
                       checkMessages = queue => (for {
                         _ <- queue.take.flatMap(f => addTestResult(assertTrue(f.json("$type").str == "/prompt/confirmation/update/plan")))
                         _ <- queue.take.flatMap(f => addTestResult(assertTrue(f.json("$type").str == "/error/aborted")))
                       } yield ()),
                     )
              } yield ()
            })
          },
          test("/update (0 packages, everything up-to-date)") {
            withTestResultRef(ZIO.scoped {
              for {
                _  <- removeAll
                _  <- wsEndpoint(s"update?profile=$profileId",
                        respond = {
                        case msg: api.PromptMessage.ConfirmUpdatePlan =>
                          for {
                            _  <- addTestResult(assertTrue(
                                    msg.toRemove.isEmpty,
                                    msg.toInstall.isEmpty,
                                    msg.choices == Seq("Yes", "No"),
                                  ))
                          } yield msg.responses("Yes")
                      },
                      checkMessages = queue => (for {
                        _  <- queue.take.flatMap(f => addTestResult(assertTrue(f.json("$type").str == "/prompt/confirmation/update/plan")))
                        _  <- queue.take.flatMap(f => addTestResult(assertTrue(f.json == jsonOk)))
                      } yield ()),
                    )
              } yield ()
            })
          },
          test("/update (1 package with 2 dependencies, with variant, with download progress)") {
            withTestResultRef(ZIO.scoped {
              for {
                _ <- removeAll
                _ <- postEndpoint(s"plugins.add?profile=$profileId", jsonBody(Seq("memo:demo-package"))).flatMap(isOk200)
                _ <- wsEndpoint(s"update?profile=$profileId",
                        respond = {
                        case msg: api.PromptMessage.ChooseVariant =>
                          for {
                            _  <- addTestResult(assertTrue(
                                    msg.`package`.orgName == "memo:package-template-variants",
                                    msg.variantId == "driveside",
                                    msg.choices == Seq("right", "left"),
                                  ))
                          } yield msg.responses("right")
                        case msg: api.PromptMessage.ConfirmUpdatePlan =>
                          for {
                            _  <- addTestResult(assertTrue(
                                    msg.toRemove.isEmpty,
                                    msg.toInstall.length == 3,
                                    msg.choices == Seq("Yes", "No"),
                                  ))
                          } yield msg.responses("Yes")
                        case msg: api.PromptMessage.ConfirmInstallation =>
                          for {
                            _  <- addTestResult(assertTrue(
                                    msg.warnings.size == 1,
                                    msg.warnings("memo:package-template-basic").size == 2,
                                    msg.choices == Seq("Yes", "No"),
                                  ))
                          } yield msg.responses("Yes")
                      },
                      checkMessages = queue => (for {
                        (msgs, f) <- takeUntil(queue)(!_.json("$type").str.startsWith("/progress/download/"))
                        _    <- addTestResult(assertTrue(msgs.map(_.json("$type").str).groupMapReduce(identity)(_ => 1)(_ + _) == Map(
                                  "/progress/download/length" -> 3,
                                  "/progress/download/intermediate" -> 3,
                                  "/progress/download/finished" -> 3,
                                  "/progress/download/started" -> 3,
                                )))
                        _    <- addTestResult(assertTrue(f.json("$type").str == "/prompt/choice/update/variant"))
                        (msgs, f) <- takeUntil(queue)(!_.json("$type").str.startsWith("/progress/download/"))
                        _    <- addTestResult(assertTrue(msgs.map(_.json("$type").str).groupMapReduce(identity)(_ => 1)(_ + _) == Map(
                                  "/progress/download/length" -> 6,
                                  "/progress/download/intermediate" -> 6,
                                  "/progress/download/finished" -> 6,
                                  "/progress/download/started" -> 6,
                                )))
                        _    <- addTestResult(assertTrue(f.json("$type").str == "/prompt/confirmation/update/plan"))
                        // from here on, two downloads in parallel
                        maxDownloads = 2
                        (msgs0, f) <- takeUntil(queue)(!_.json("$type").str.startsWith("/progress/download/"))
                        msgs = msgs0.map(_.json("$type").str)
                        _    <- ZIO.foldLeft(msgs)(0) { (numActive, progType) =>
                                  for {
                                    _ <- addTestResult(assertTrue(numActive >= 0, numActive <= maxDownloads))
                                  } yield progType match {
                                    case "/progress/download/started" => numActive + 1
                                    case "/progress/download/finished" => numActive - 1
                                    case _ => numActive
                                  }
                                }
                        _    <- addTestResult(assertTrue(msgs.groupMapReduce(identity)(_ => 1)(_ + _) == Map(
                                  "/progress/download/length" -> 6,
                                  "/progress/download/intermediate" -> 6,
                                  "/progress/download/finished" -> 6,
                                  "/progress/download/started" -> 6,
                                )))
                        _    <- addTestResult(assertTrue(f.json("$type").str == "/progress/update/extraction"))
                        _    <- queue.take.flatMap(f => addTestResult(assertTrue(f.json("$type").str == "/progress/update/extraction")))
                        _    <- queue.take.flatMap(f => addTestResult(assertTrue(f.json("$type").str == "/progress/update/extraction")))
                        _    <- queue.take.flatMap(f => addTestResult(assertTrue(f.json("$type").str == "/prompt/confirmation/update/warnings")))
                        _    <- queue.take.flatMap(f => addTestResult(assertTrue(f.json == jsonOk)))
                      } yield ()),
                    )
              } yield ()
            })
          },
          test("/plugins.installed.list") {
            withTestResultRef(for {
              data <- getEndpoint(s"plugins.installed.list?profile=$profileId").flatMap(getBody200[Seq[ujson.Value]])
              _    <- addTestResult(assertTrue(
                        data.arr.length == 3,
                        data.arr.toSet == Set(
                          Obj("package" -> "memo:demo-package", "version" -> "1.0", "variant" -> Obj(), "explicit" -> true),
                          Obj("package" -> "memo:package-template-basic", "version" -> "2.0", "variant" -> Obj(), "explicit" -> false),
                          Obj("package" -> "memo:package-template-variants", "version" -> "1.0", "variant" -> Obj("driveside" -> "right"), "explicit" -> false),
                        ),
                      ))
            } yield ())
          },
          test("/variants.list") {
            withTestResultRef(for {
              data <- getEndpoint(s"variants.list?profile=$profileId").flatMap(getBody200[ujson.Value])
              _    <- addTestResult(assertTrue(data == Obj("variants" -> Obj("driveside" -> Obj("value" -> "right", "unused" -> false)))))
            } yield ())
          },
          test("/variants.reset") {
            withTestResultRef(for {
              _    <- postEndpoint(s"variants.reset?profile=$profileId", jsonBody(Seq("driveside"))).flatMap(isOk200)
              resp <- postEndpoint(s"variants.reset?profile=$profileId", jsonBody(Seq("driveside")))
              _    <- addTestResult(assertTrue(resp.status.code == 400))
              data <- getEndpoint(s"variants.list?profile=$profileId").flatMap(getBody200[ujson.Value])
              _    <- addTestResult(assertTrue(data == Obj("variants" -> Obj())))
            } yield ())
          },
          test("/update (switching variant)") {
            withTestResultRef(ZIO.scoped {
              for {
                pkgs   <- getEndpoint(s"plugins.installed.list?profile=$profileId").flatMap(getBody200[Seq[api.InstalledPkg]])
                _      <- addTestResult(assertTrue(pkgs.exists(pkg => pkg.variant.get("driveside").contains("right"))))
                _      <- wsEndpoint(s"update?profile=$profileId",
                            respond = {
                              case msg: api.PromptMessage.ChooseVariant =>
                                for {
                                  _  <- addTestResult(assertTrue(
                                          msg.variantId == "driveside",
                                          msg.choices == Seq("right", "left"),
                                        ))
                                } yield msg.responses("left")
                              case msg: api.PromptMessage.ConfirmUpdatePlan =>
                                for {
                                  _  <- addTestResult(assertTrue(
                                          msg.toRemove.length == 1,
                                          msg.toInstall.length == 1,
                                          msg.toInstall(0).`package` == msg.toRemove(0).`package`,
                                          msg.toInstall(0).version == msg.toRemove(0).version,
                                          msg.toInstall(0).variant != msg.toRemove(0).variant,
                                          msg.choices == Seq("Yes", "No"),
                                        ))
                                } yield msg.responses("Yes")
                              case msg: api.PromptMessage.ConfirmInstallation =>
                                for {
                                  _  <- addTestResult(assertTrue(
                                          msg.warnings.size == 0,
                                          msg.choices == Seq("Yes", "No"),
                                        ))
                                } yield msg.responses("Yes")
                            },
                            filter = msg => !msg.json("$type").str.startsWith("/progress/download"),
                            checkMessages = queue => (for {
                              _ <- queue.take.flatMap(f => addTestResult(assertTrue(f.json("$type").str == "/prompt/choice/update/variant")))
                              _ <- queue.take.flatMap(f => addTestResult(assertTrue(f.json("$type").str == "/prompt/confirmation/update/plan")))
                              _ <- queue.take.flatMap(f => addTestResult(assertTrue(f.json("$type").str == "/progress/update/extraction")))
                              _ <- queue.take.flatMap(f => addTestResult(assertTrue(f.json("$type").str == "/prompt/confirmation/update/warnings")))
                              _ <- queue.take.flatMap(f => addTestResult(assertTrue(f.json == jsonOk)))
                            } yield ()),
                          )
              } yield ()
            })
          },
          test("/update (unresolvable)") {
            withTestResultRef(ZIO.scoped {
              def respond(choice: String): PartialFunction[api.PromptMessage, RIO[Ref[TestResult], api.ResponseMessage]] = {
                case msg: api.PromptMessage.ConfirmRemoveUnresolvablePackages =>
                  for {
                    _ <- addTestResult(assertTrue(msg.packages.map(_.orgName) == Seq("test:nonexistent")))
                  } yield msg.responses(choice)
                case msg: api.PromptMessage.ConfirmUpdatePlan =>
                  for {
                    _ <- addTestResult(assertTrue(msg.toRemove.length == 0, msg.toInstall.length == 0))
                  } yield msg.responses("Yes")
              }
              for {
                _    <- postEndpoint(s"plugins.add?profile=$profileId", jsonBody(Seq("test:nonexistent"))).flatMap(isOk200)
                _    <- wsEndpoint(s"update?profile=$profileId",
                          respond = respond("No"),
                          filter = msg => !msg.json("$type").str.startsWith("/progress/download"),
                          checkMessages = queue => (for {
                            _ <- queue.take.flatMap(f => addTestResult(assertTrue(f.json("$type").str == "/prompt/confirmation/update/remove-unresolvable-packages")))
                            _ <- queue.take.flatMap(f => addTestResult(assertTrue(f.json("$type").str == "/error/unresolvable-dependencies")))
                          } yield ()),
                        )
                data <- getEndpoint(s"plugins.added.list?profile=$profileId").flatMap(getBody200[Seq[String]])
                _    <- addTestResult(assertTrue(data.contains("test:nonexistent")))
                _    <- wsEndpoint(s"update?profile=$profileId",
                          respond = respond("Yes"),
                          filter = msg => !msg.json("$type").str.startsWith("/progress/download"),
                          checkMessages = queue => (for {
                            _ <- queue.take.flatMap(f => addTestResult(assertTrue(f.json("$type").str == "/prompt/confirmation/update/remove-unresolvable-packages")))
                            _ <- queue.take.flatMap(f => addTestResult(assertTrue(f.json("$type").str == "/prompt/confirmation/update/plan")))
                            _ <- queue.take.flatMap(f => addTestResult(assertTrue(f.json == jsonOk)))
                          } yield ()),
                        )
                data <- getEndpoint(s"plugins.added.list?profile=$profileId").flatMap(getBody200[Seq[String]])
                _    <- addTestResult(assertTrue(!data.contains("test:nonexistent")))
              } yield ()
            })
          },
          test("/update (uninstall all)") {
            withTestResultRef(ZIO.scoped {
              for {
                _    <- removeAll
                _    <- wsEndpoint(s"update?profile=$profileId",
                          respond = {
                            case msg: api.PromptMessage.ConfirmUpdatePlan => ZIO.succeed(msg.responses("Yes"))
                            case msg: api.PromptMessage.ConfirmInstallation => ZIO.succeed(msg.responses("Yes"))
                          },
                          filter = msg => msg.json("$type").str.startsWith("/result"),
                          checkMessages = queue => (for {
                            _ <- queue.take.flatMap(f => addTestResult(assertTrue(f.json == jsonOk)))
                          } yield ()),
                        )
                data <- getEndpoint(s"plugins.added.list?profile=$profileId").flatMap(getBody200[Seq[String]])
                _    <- addTestResult(assertTrue(data == Nil))
                data <- getEndpoint(s"variants.list?profile=$profileId").flatMap(getBody200[ujson.Value])
                _    <- addTestResult(assertTrue(data("variants")("driveside")("unused").bool == true))
                _    <- addTestResult(assertTrue(data("variants").obj.forall(_._2("unused").bool) == true))
              } yield ()
            })
          },
        ),

      )
    }).provideSomeLayerShared(fileServerLayer)  // shared across all tests
      .provideSomeLayer(currentProfileLayer),

  ).provideShared(profilesDirLayer, service.FileSystem.live, serverLayer, Client.default)  // shared across all tests
    @@ TestAspect.sequential @@ TestAspect.withLiveClock @@ TestAspect.timeout(30.seconds)

}
