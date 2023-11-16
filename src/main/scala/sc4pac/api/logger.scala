package io.github.memo33
package sc4pac
package api

import zio.{ZIO, Task, IO}
import zio.http.WebSocketFrame
import zio.http.ChannelEvent.{Read, UserEvent, UserEventTriggered}

import Resolution.DepModule
import PromptMessage.{yesNo, yes}

class WebSocketLogger private (private[api] val queue: java.util.concurrent.LinkedBlockingQueue[WebSocketLogger.Event]) extends Logger {

  def log(msg: String): Unit = println(s"[info] $msg")
  def warn(msg: String): Unit = println(s"[warn] $msg")
  def debug(msg: String): Unit = if (Constants.debugMode) println(s"[debug] $msg")

  def sendMessageAsync(message: Message): Unit = { queue.offer(WebSocketLogger.Event.Plain(message)); () }

  def sendMessageAwait(message: Message): zio.UIO[Unit] =
    for {
      p <- zio.Promise.make[Nothing, Unit]
      _ <- ZIO.succeed(queue.offer(WebSocketLogger.Event.WithCompletion(message, p)))
      u <- p.await
    } yield u

  def extractingArchiveEntry(entry: os.SubPath, include: Boolean): Unit = ()

  def extractingPackage[A](dependency: DepModule, progress: Sc4pac.Progress)(extraction: Task[A]): Task[A] = {
    ZIO.succeed(sendMessageAsync(ProgressMessage.Extraction(dependency.toBareDep, progress))).zipRight(extraction)
  }

  def fetchingAssets[A](fetching: Task[A]): Task[A] = fetching  // no message needed

  def publishing[A](removalOnly: Boolean)(publishing: Task[A]): Task[A] = publishing  // no message needed

  def discardingUnexpectedMessage(msg: String): Unit = warn(s"Discarding unexpected message: $msg")

  override def downloadingArtifact(url: String, artifact: coursier.util.Artifact): Unit =
    sendMessageAsync(ProgressMessage.DownloadStarted(url))

  override def downloadLength(url: String, len: Long, currentLen: Long, watching: Boolean): Unit =
    sendMessageAsync(ProgressMessage.DownloadLength(url, length = len))

  override def downloadProgress(url: String, downloaded: Long): Unit =
    sendMessageAsync(ProgressMessage.DownloadDownloaded(url, downloaded = downloaded))

  override def downloadedArtifact(url: String, success: Boolean): Unit =
    sendMessageAsync(ProgressMessage.DownloadFinished(url, success))
}
object WebSocketLogger {

  // def test(): Task[Boolean] = {
  //   val send: Message => Task[Unit] = msg => ZIO.sleep(zio.Duration.fromSeconds(3)).map(_ => println(msg))
  //   val task: zio.URIO[WebSocketLogger, Boolean] = for {
  //     logger <- ZIO.service[api.WebSocketLogger]
  //     u <- logger.sendMessageAwait(api.ResultMessage("OK"))
  //     _ <- ZIO.succeed(println("awaited"))
  //   } yield true
  //   run(send)(task)
  // }

  private[api] sealed trait Event
  private[api] object Event {
    case object ShutDown extends Event
    case class Plain(message: Message) extends Event
    case class WithCompletion(message: Message, promise: zio.Promise[Nothing, Unit]) extends Event
    case class WithResponse(
      message: PromptMessage,
      promise: zio.Promise[Throwable, ResponseMessage],
      receiveMatchingResponse: Task[ResponseMessage]
    ) extends Event
  }

  /** Run a task with a logger which allows non-blocking writes of messages.
    * The messages are buffered in a queue and are sent asynchronously from
    * another thread.
    */
  def run[R : zio.Tag, A](send: Message => Task[Unit])(task: zio.RIO[R & WebSocketLogger, A]): ZIO[R, Throwable, A] = {
    val queue = new java.util.concurrent.LinkedBlockingQueue[Event]
    val logger = WebSocketLogger(queue)
    val consume: Task[Boolean] =
      ZIO.iterate(true)(identity) { _ =>
        ZIO.attemptBlocking(queue.take()).flatMap(_ match {
          case Event.ShutDown =>
            logger.log("Shutting down message queue.")
            ZIO.succeed(false)
          case Event.Plain(msg) =>
            for {
              _ <- send(msg)
            } yield true
          case Event.WithCompletion(msg, promise) =>
            for {
              _ <- send(msg)
              _ <- promise.completeWith(ZIO.succeed(()))
            } yield true
          case Event.WithResponse(msg, promise, receiveMatchingResponse) =>
            for {
              _ <- send(msg)
              _ <- promise.complete(receiveMatchingResponse)
            } yield true
        })
      }
    (for {
      fiber  <- consume.fork
      result <- ZIO.provideLayer(zio.ZLayer.succeed(logger))(task).either
      _      <- ZIO.attempt(queue.offer(Event.ShutDown))
      _      <- fiber.join
    } yield result).absolve
  }
}


class WebSocketPrompter(wsChannel: zio.http.WebSocketChannel, logger: WebSocketLogger) extends Prompter {

  /** Send a prompt to the client and wait for a matching response, discarding all other responses. */
  private def sendPrompt(message: PromptMessage): Task[ResponseMessage] = {

    val receiveMatchingResponse: Task[ResponseMessage] =
      ZIO.iterate(Option.empty[ResponseMessage])(_.isEmpty) { _ =>
        wsChannel.receive.flatMap {
          case UserEventTriggered(UserEvent.HandshakeComplete) => ZIO.succeed(None)  // ignore expected event
          case Read(WebSocketFrame.Text(raw)) =>
            JsonIo.read[ResponseMessage](raw).option
              .map(_.filter(message.accept(_)))  // accept only responses with matching token and valid body
              .map { opt =>
                if (opt.isEmpty) logger.discardingUnexpectedMessage(raw)
                opt
              }
          case event =>
            logger.discardingUnexpectedMessage(event.toString)
            ZIO.succeed(None)  // discard all unexpected messages (and events) and continue receiving
        }
      }.map(_.get)

    for {
      promise  <- zio.Promise.make[Throwable, ResponseMessage]
      _        <- ZIO.succeed(logger.queue.offer(WebSocketLogger.Event.WithResponse(message, promise, receiveMatchingResponse)))
      response <- promise.await
    } yield response
  }

  private def promptUntil(message: PromptMessage, acceptResponse: String => Boolean): Task[String] = {
    ZIO.iterate(Option.empty[String])(_.isEmpty) { _ =>
      for (response <- sendPrompt(message)) yield {
        if (acceptResponse(response.body)) Some(response.body)
        else None
      }
    }.map(_.get)
  }

  def promptForVariant(module: BareModule, label: String, values: Seq[String], descriptions: Map[String, String]): Task[String] = {
    sendPrompt(PromptMessage.ChooseVariant(module, label, values, descriptions)).map(_.body)
  }

  def confirmUpdatePlan(plan: Sc4pac.UpdatePlan): zio.Task[Boolean] = {
    val toRemove = plan.toRemove.toSeq.collect { case dep: DepModule => PromptMessage.ConfirmUpdatePlan.Pkg(dep.toBareDep, dep.version, dep.variant) }
    val toInstall = plan.toInstall.toSeq.collect { case dep: DepModule => PromptMessage.ConfirmUpdatePlan.Pkg(dep.toBareDep, dep.version, dep.variant) }
    sendPrompt(PromptMessage.ConfirmUpdatePlan(toRemove = toRemove, toInstall = toInstall)).map(_.body == yes)
  }

  def confirmInstallationWarnings(warnings: Seq[(BareModule, Seq[String])]): zio.Task[Boolean] = {
    sendPrompt(PromptMessage.ConfirmInstallation(warnings.toMap)).map(_.body == yes)
  }

}
