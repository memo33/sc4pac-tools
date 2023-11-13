package io.github.memo33
package sc4pac
package api

import zio.{ZIO, Task, IO}

import Resolution.DepModule

class WebSocketLogger private (queue: java.util.concurrent.LinkedBlockingQueue[WebSocketLogger.Event]) extends Logger {

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

  def extractingPackage[A](dependency: DepModule, progress: Sc4pac.Progress)(extraction: Task[A]): Task[A] = extraction

  def fetchingAssets[A](fetching: Task[A]): Task[A] = fetching

  def publishing[A](removalOnly: Boolean)(publishing: Task[A]): Task[A] = publishing

  // TODO implement above methods as wells as Coursier Logger methods

  // def discardingUnexpectedMessage(msg: String): Unit = warn(s"Discarding unexpected message: $msg")

}
object WebSocketLogger {

  // def test(): Task[Boolean] = {
  //   val send: Message => Task[Unit] = msg => ZIO.sleep(zio.Duration.fromSeconds(3)).map(_ => println(msg))
  //   val task: zio.URIO[WebSocketLogger, Boolean] = for {
  //     logger <- ZIO.service[api.WebSocketLogger]
  //     u <- logger.sendMessageAwait(api.ResultMessage("OK"))
  //     _ <- ZIO.succeed(println("awaited"))
  //   } yield true
  //   run(send, task)
  // }

  private sealed trait Event
  private object Event {
    case object ShutDown extends Event
    case class Plain(message: Message) extends Event
    case class WithCompletion(message: Message, promise: zio.Promise[Nothing, Unit]) extends Event
  }

  /** Run a task with a logger which allows non-blocking writes of messages.
    * The messages are buffered in a queue and are sent asynchronously from
    * another thread.
    */
  def run[R : zio.Tag, A](send: Message => Task[Unit], task: zio.URIO[R & WebSocketLogger, A]): ZIO[R, Throwable, A] = {
    val queue = new java.util.concurrent.LinkedBlockingQueue[Event]
    val consume: Task[Boolean] =
      ZIO.iterate(true)(identity) { _ =>
        ZIO.attemptBlocking(queue.take()).flatMap(_ match {
          case Event.ShutDown =>
            System.err.println("Shutting down message queue.")
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
        })
      }
    for {
      fiber  <- consume.fork
      logger =  WebSocketLogger(queue)
      result <- ZIO.provideLayer(zio.ZLayer.succeed(logger))(task)
      _      <- ZIO.attempt(queue.offer(Event.ShutDown))
      _      <- fiber.join
    } yield result
  }
}
