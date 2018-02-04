package upperbound

import fs2.async
import cats.effect.IO

import cats.syntax.functor._

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext

import org.specs2.mutable.Specification

class SubmissionSemanticsSpec(implicit val ec: ExecutionContext)
    extends Specification {

  import syntax.rate._

  "A worker" >> {

    "when using fire-and-forget semantics" should {

      "continue execution immediately" in {
        def prog =
          for {
            complete <- async.refOf[IO, Boolean](false)
            limiter <- Limiter.start[IO](1 every 10.seconds)
            _ <- limiter.worker submit complete.setSync(true)
            _ <- limiter.shutDown
            res <- complete.get
          } yield res

        prog.unsafeRunSync must beFalse
      }
    }

    "when using await semantics" should {

      "complete when the result of the submitted job is ready" in {
        def prog =
          for {
            complete <- async.refOf[IO, Boolean](false)
            limiter <- Limiter.start[IO](1 every 1.seconds)
            res <- limiter.worker await complete.setSync(true).as("done")
            _ <- limiter.shutDown
            state <- complete.get
          } yield res -> state

        val out = prog.unsafeRunSync
        out._1 must beEqualTo("done")
        out._2 must beTrue
      }
    }

    "report the original error if execution of the submitted job fails" in {
      case class MyError() extends Exception
      def prog =
        for {
          limiter <- Limiter.start[IO](1 every 1.seconds)
          res <- limiter.worker await IO.raiseError[Int](new MyError)
          _ <- limiter.shutDown
        } yield res

      prog.unsafeRunSync must throwA[MyError]
    }

    "when too many jobs have been submitted" should {
      "reject new jobs immediately" in {
        def prog =
          for {
            limiter <- Limiter.start[IO](1 every 10.seconds, n = 0)
            res <- limiter.worker await IO.unit
            _ <- limiter.shutDown
          } yield res

        def prog2 =
          for {
            limiter <- Limiter.start[IO](1 every 10.seconds, n = 0)
            _ <- limiter.worker submit IO.unit
            _ <- limiter.shutDown
          } yield ()

        prog.unsafeRunSync must throwA[LimitReachedException]
        prog2.unsafeRunSync must throwA[LimitReachedException]
      }
    }
  }

}
