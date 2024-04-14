package io.github.memo33
package object sc4pac {
  type ErrStr = String
  type Warning = String
  type Variant = Map[String, String]

  object CoursierZio {
    // from https://github.com/kitlangton/scala-update/blob/2249cd613c6490142201a0cfd9cbcf7b2ecbda36/src/main/scala/update/versions/ZioSyncInstance.scala

    import coursier.util.Sync
    import zio.{Task, ZIO, IO}

    import scala.concurrent.{ExecutionContext, ExecutionContextExecutorService}
    import java.util.concurrent.{ExecutorService}

    implicit lazy val taskSync: Sync[Task] = new Sync[Task] {
      override def delay[A](a: => A): Task[A] = ZIO.attempt(a)

      override def handle[A](a: Task[A])(f: PartialFunction[Throwable, A]): Task[A] =
        a.catchSome { case f(e) => ZIO.succeed(e) }

      override def fromAttempt[A](a: Either[Throwable, A]): Task[A] =
        ZIO.fromEither(a)

      override def gather[A](elems: Seq[Task[A]]): Task[Seq[A]] =
        ZIO.collectAllPar(elems)

      override def point[A](a: A): Task[A] = ZIO.succeed(a)

      override def bind[A, B](elem: Task[A])(f: A => Task[B]): Task[B] =
        elem.flatMap(f)

      override def schedule[A](pool: ExecutorService)(f: => A): Task[A] = {
        // following https://github.com/coursier/coursier/blob/3e212b42d3bda5d80453b4e7804670ccf75d4197/modules/interop/cats/jvm/src/main/scala/coursier/interop/PlatformCatsImplicits.scala#L26-L34
        val ec0 = pool match {
          case eces: ExecutionContextExecutorService => eces
          case _                                     =>
            // FIXME Is this instantiation costly? Cache it?
            ExecutionContext.fromExecutorService(pool)
        }
        ZIO.attempt(f).onExecutionContext(ExecutionContext.fromExecutor(ec0))
      }
    }

    implicit class EitherTZioOps[L, R](val either: coursier.util.EitherT[Task, L, R]) extends AnyVal {
      def toZio(handle: Throwable => IO[L, R]): IO[L, R] = {
        either.run                                    // : IO[Throwable, Either[L, R]]
          .catchAll { t => handle(t).map(Right(_)) }  // : IO[L, Either[L, R]]
          .absolve                                    // : IO[L, R]
      }

      // catch some errors like IOException, otherwise die irrecoverably
      def toZioRefine(handle: PartialFunction[Throwable, IO[L, R]]): IO[L, R] = {
        either.run                                                   // : IO[Throwable, Either[L, R]]
          .refineOrDie(handle)                                       // : IO[IO[L, R], Either[L, R]]
          .foldZIO(failure = identity, success = ZIO.fromEither(_))  // : IO[L, R]
      }
    }
  }

  // TODO find better place
  private[sc4pac] def isSubMap[A, B](small: Map[A, B], large: Map[A, B]): Boolean = small.keysIterator.forall(a => small.get(a) == large.get(a))

  def unsafeRun[E, A](effect: zio.IO[E, A]): A = zio.Unsafe.unsafe { implicit unsafe =>
    zio.Runtime.default.unsafe.run(effect).getOrThrowFiberFailure()
  }

  class ProfileRoot(val path: os.Path)
}
