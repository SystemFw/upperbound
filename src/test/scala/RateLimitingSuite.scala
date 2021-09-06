package upperbound

import cats.effect._
import scala.concurrent.duration._

import upperbound.syntax.rate._

class RateLimitingSuite extends BaseSuite {
  val samplingWindow = 10.seconds
  import TestScenarios._

  def within(a: Long, b: Long, threshold: Long): Boolean =
    scala.math.abs(a - b) < threshold

  test("await semantics should return the result of the submitted job") {
    IO.ref(false)
      .flatMap { complete =>
        Limiter.start[IO](1 every 1.seconds).use {
          _.await(complete.set(true).as("done")).product(complete.get)
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
      .use {
        _.await(IO.raiseError[Int](new MyError))
      }
      .intercept[MyError]
  }

  test("multiple fast producers, fast non-failing jobs") {
    val conditions = TestingConditions(
      desiredInterval = 1 every 200.millis,
      productionInterval = 1 every 1.millis,
      producers = 4,
      jobsPerProducer = 100,
      jobCompletion = 0.seconds,
      samplingWindow = samplingWindow
    )

    mkScenario[IO](conditions).map { r =>
      assert(r.jobExecutionMetrics.diffs.forall(within(_, 200L, 10L)))
    }
  }

  test("slow producer, no unnecessary delays") {
    val conditions = TestingConditions(
      desiredInterval = 1 every 200.millis,
      productionInterval = 1 every 300.millis,
      producers = 1,
      jobsPerProducer = 100,
      jobCompletion = 0.seconds,
      samplingWindow = samplingWindow
    )

    mkScenario[IO](conditions).map { r =>
      assert(r.jobExecutionMetrics.diffs.forall(within(_, 300L, 10L)))
    }
  }
}
