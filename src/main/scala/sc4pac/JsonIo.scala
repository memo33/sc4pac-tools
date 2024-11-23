package io.github.memo33
package sc4pac

import upickle.default as UP
import upickle.default.{ReadWriter, Reader}
import zio.ZIO

import sc4pac.error.Sc4pacIoException

object JsonIo {

  private[sc4pac] def readBlocking[A : Reader](jsonPath: os.Path): Either[ErrStr, A] = {
    readBlocking(os.read.stream(jsonPath), errMsg = jsonPath.toString())
  }

  private[sc4pac] def readBlocking[A : Reader](jsonStr: String): Either[ErrStr, A] = {
    readBlocking(jsonStr, errMsg = jsonStr)
  }

  private[sc4pac] def readBlocking[A : Reader](pathOrString: ujson.Readable, errMsg: => ErrStr): Either[ErrStr, A] = try {
    Right(UP.read[A](pathOrString))
  } catch {
    case e @ (_: upickle.core.AbortException
            | _: upickle.core.Abort
            | _: ujson.ParseException
            | _: java.nio.file.NoSuchFileException
            | _: IllegalArgumentException
            | _: java.time.format.DateTimeParseException) =>
      Left(s"failed to read $errMsg: ${e.getMessage()}")
  }

  private[sc4pac] def read[A : Reader](jsonPath: os.Path): zio.IO[java.io.IOException, A] = {
    ZIO.attemptBlockingIO(os.read.stream(jsonPath))
      .flatMap(read(_, errMsg = jsonPath.toString()))
  }

  private[sc4pac] def read[A : Reader](jsonStr: String): zio.Task[A] = {
    read(jsonStr, errMsg = jsonStr)
  }

  private[sc4pac] def read[A : Reader](pathOrString: ujson.Readable, errMsg: => ErrStr): zio.IO[java.io.IOException, A] = {
    ZIO.attemptBlockingIO(readBlocking[A](pathOrString, errMsg).left.map(new Sc4pacIoException(_))).absolve
  }

  // steps:
  // - lock file for writing
  // - optionally read json and compare with previous json `origState` to ensure file is up-to-date
  // - do some potentially destructive action
  // - if action successful, write json file

  def write[S : ReadWriter, A](jsonPath: os.Path, newState: S, origState: Option[S])(action: zio.Task[A]): zio.Task[A] = {
    import java.nio.file.StandardOpenOption
    import zio.nio.channels.AsynchronousFileChannel

    def write0(channel: AsynchronousFileChannel): ZIO[zio.Scope, Throwable, Unit] = {
      for {
        _      <- channel.truncate(size = 0)
        arr    =  UP.writeToByteArray[S](newState, indent = 2)
        unit   <- channel.writeChunk(zio.Chunk.fromArray(arr), position = 0)
      } yield unit
    }

    def read0(channel: AsynchronousFileChannel): ZIO[zio.Scope, Throwable, S] = {
      for {
        chunk <- channel.stream(position = 0).runCollect
        state <- read[S](chunk.asString(java.nio.charset.StandardCharsets.UTF_8): String)
      } yield state
    }

    // the file channel used for locking
    val scopedChannel: ZIO[zio.Scope, java.io.IOException, AsynchronousFileChannel] = AsynchronousFileChannel.open(
      zio.nio.file.Path.fromJava(jsonPath.toNIO),
      StandardOpenOption.READ,
      StandardOpenOption.WRITE,
      StandardOpenOption.CREATE)  // TODO decide what to do if file does not exist

    val releaseLock = (lock: zio.nio.channels.FileLock) => if (lock != null) lock.release.ignore else ZIO.succeed(())

    // Acquire file lock, check if file was locked, otherwise perform read-write:
    // First read, then write only if the read result is still the original state.
    // Be careful to just read and write from the channel, as the channel is holding the lock.
    ZIO.scoped {
      for {
        channel <- scopedChannel
        lock    <- ZIO.acquireRelease(channel.tryLock(shared = false))(releaseLock)
                      .filterOrFail(lock => lock != null)(new Sc4pacIoException(s"Json file $jsonPath is locked by another program and cannot be modified; if the problem persists, close any relevant program and release the file lock in your OS"))
        _       <- if (origState.isEmpty) ZIO.succeed(())
                   else read0(channel).filterOrFail(_ == origState.get)(new Sc4pacIoException(s"Cannot write data since json file has been modified in the meantime: $jsonPath"))
        result  <- action
        _       <- write0(channel)
      } yield result
    }  // Finally the lock is released and channel is closed when leaving the scope.
  }

}
