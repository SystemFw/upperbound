package upperbound

import cats.effect._
import scala.concurrent.duration._

import upperbound.syntax.rate._

import cats.effect.testkit.TestControl

class RateLimitingSuite extends BaseSuite {
  val samplingWindow = 10.seconds
  import TestScenarios._

  test("await semantics should return the result of the submitted job") {
    IO.ref(false)
      .flatMap { complete =>
        Limiter.start[IO](200.millis).use {
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
      .start[IO](200.millis)
      .use {
        _.await(IO.raiseError[Int](new MyError))
      }
      .intercept[MyError]
  }

  test("multiple fast producers, fast non-failing jobs") {
    val conditions = TestingConditions(
      desiredInterval = 200.millis,
      productionInterval = 1.millis,
      producers = 4,
      jobsPerProducer = 100,
      jobCompletion = 0.seconds,
      samplingWindow = samplingWindow
    )

    TestControl.executeEmbed(mkScenario[IO](conditions)).map { r =>
      // adjust once final details of the TestControl api are finalised
      assert(r.jobExecutionMetrics.diffs.forall(_ == 200L))
    }
  }

  test("slow producer, no unnecessary delays") {
    val conditions = TestingConditions(
      desiredInterval = 200.millis,
      productionInterval = 300.millis,
      producers = 1,
      jobsPerProducer = 100,
      jobCompletion = 0.seconds,
      samplingWindow = samplingWindow
    )

    TestControl.executeEmbed(mkScenario[IO](conditions)).map { r =>
      // adjust once final details of the TestControl api are finalised
      assert(
        r.jobExecutionMetrics.diffs.forall(_ == 300L)
      )
    }
  }
}
