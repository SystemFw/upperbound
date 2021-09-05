package upperbound

import cats.syntax.all._
import cats.effect._
import scala.concurrent.duration._

import upperbound.syntax.rate._

class SubmissionSemanticsSuite extends BaseSuite {
  test("Fire-and-forget semantics should continue execution immediately") {
    IO.ref(false).flatMap { complete =>
      Limiter.start[IO](1 every 10.seconds).use { limiter =>
        // the first task is picked up when the limiter starts
        // the other exercises the scenario we care about
        limiter.submit(IO.unit) >> limiter.submit(complete.set(true))
      } >> complete.get.assertEquals(false)
    }
  }

  test("await semantics should return the result of the submitted job") {
    IO.ref(false)
      .flatMap { complete =>
        Limiter.start[IO](1 every 1.seconds).use { implicit limiter =>
          Limiter.await(complete.set(true).as("done")).product(complete.get)
        }
      }
      .map {
        case (res, state) =>
          assertEquals(res, "done")
          assertEquals(state, true)
      }
  }

  test("await semantics should report errors of a failed task") {
    case class MyError() extends Exception
    Limiter
      .start[IO](1 every 1.seconds)
      .use { implicit limiter =>
        Limiter.await(IO.raiseError[Int](new MyError))
      }
      .intercept[MyError]
  }
}
