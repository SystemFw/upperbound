package upperbound

import cats.syntax.all._
import cats.effect._, concurrent.Ref
import scala.concurrent.duration._

import syntax.rate._

class SubmissionSemanticsSpec extends BaseSpec {
  import DefaultEnv._

  "A worker" - {

    "when using fire-and-forget semantics should" - {

      "continue execution immediately" in {
        def prog =
          for {
            complete <- Ref.of[IO, Boolean](false)
            _ <- Limiter.start[IO](1 every 10.seconds).use { limiter =>
              limiter submit complete.set(true)
            }
            res <- complete.get
          } yield res

        val res = prog.unsafeRunSync

        assert(res === false)
      }
    }

    "when using await semantics should" - {

      "complete when the result of the submitted job is ready" in {
        def prog =
          for {
            complete <- Ref.of[IO, Boolean](false)
            res <- Limiter.start[IO](1 every 1.seconds).use { limiter =>
              limiter await complete.set(true).as("done")
            }
            state <- complete.get
          } yield res -> state

        val (res, state) = prog.unsafeRunSync

        assert(res === "done")
        assert(state === true)
      }

      "report the original error if execution of the submitted job fails" in {
        case class MyError() extends Exception
        def prog = Limiter.start[IO](1 every 1.seconds).use { limiter =>
          limiter await IO.raiseError[Int](new MyError)
        }

        assertThrows[MyError](prog.unsafeRunSync)
      }
    }

    "when too many jobs have been submitted should" - {
      "reject new jobs immediately" in {
        def prog = Limiter.start[IO](1 every 10.seconds, n = 0).use { limiter =>
          limiter await IO.unit
        }

        def prog2 = Limiter.start[IO](1 every 10.seconds, n = 0).use {
          limiter =>
            limiter submit IO.unit
        }

        assertThrows[LimitReachedException](prog.unsafeRunSync)
        assertThrows[LimitReachedException](prog2.unsafeRunSync)
      }
    }
  }

}
