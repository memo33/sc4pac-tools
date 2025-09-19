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
            | _: ujson.ParsingFailedException
            | _: java.nio.file.NoSuchFileException
            | _: IllegalArgumentException
            | _: java.net.URISyntaxException
            | _: java.time.format.DateTimeParseException
            | scala.util.control.NonFatal(_)) =>  // catch all
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
  // - optionally read json and compare with previous json `origState` to ensure file is up-to-date
  // - do some potentially destructive action
  // - if action successful, write json file
  // The json file is not locked, but the modification timestamps are checked
  // just before over-writing to guard against concurrent modifications.
  def write[S : ReadWriter, A](jsonPath: os.Path, newState: S, origState: Option[S])(action: zio.Task[A]): zio.Task[A] = {
    val preparationSteps =
      ZIO.attemptBlocking {
        val mtimeOpt =
          try Some(os.mtime(jsonPath))
          catch { case _: java.nio.file.NoSuchFileException => None }

        // check that file has expected contents
        if (origState.isDefined) {
          if (mtimeOpt.isEmpty) {
            throw new Sc4pacIoException(s"Cannot write data since, unexpectedly, original json file does not exist: $jsonPath")
          }
          readBlocking[S](jsonPath) match
            case Left(errStr) => throw new Sc4pacIoException(errStr)
            case Right(data) =>
              if (data != origState.get) {
                throw new Sc4pacIoException(s"Cannot write data since existing json file has unexpected contents: $jsonPath")
              }
        }
        mtimeOpt
      }

    for {
      arr      <- ZIO.attempt(UP.writeToByteArray[S](newState, indent = 2))  // encode before touching the file to avoid data loss on errors
      mtimeOpt <- preparationSteps
      result   <- action
      _        <- ZIO.attemptBlocking {
                    // write to temporary file as pre-caution against file corruption
                    val prefix = s"${jsonPath.last}."
                    val perms: os.PermSet = if (!Constants.isPosix) null else 6<<6 | 4<<3 | 4  // 644 octal
                    val jsonPathTmp =
                      try os.temp(contents = arr, dir = jsonPath / os.up, prefix = prefix, perms = perms)
                      catch { case _: UnsupportedOperationException if perms != null =>
                        os.temp(contents = arr, dir = jsonPath / os.up, prefix = prefix)  // retry with default permissions in case file system containing jsonPath is not posix
                      }
                    // (a) Ensure that file hasn't been modified concurrently and (b) replace existing file.
                    // The combination of both steps is not atomic, but delay should be negligible.
                    if (mtimeOpt.isEmpty && os.exists(jsonPath, followLinks = false)) {
                      throw new Sc4pacIoException(s"Cannot write data since json file has been concurrently created in the meantime: $jsonPath")
                    } else if (mtimeOpt.exists(_ != os.mtime(jsonPath))) {
                      throw new Sc4pacIoException(s"Cannot write data since json file has been concurrently modified in the meantime: $jsonPath")
                    } else {
                      os.move.over(jsonPathTmp, jsonPath)
                    }
                  }
    } yield result
  }

}
