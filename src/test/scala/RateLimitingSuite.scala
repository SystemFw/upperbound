package upperbound

import cats.effect._
import scala.concurrent.duration._

import upperbound.syntax.rate._

class RateLimitingSuite extends BaseSuite {
  val samplingWindow = 10.seconds
  import TestScenarios._

  def within(a: Long, b: Long, threshold: Long): Boolean =
    scala.math.abs(a - b) < threshold

  test("multiple fast producers, fast non-failing jobs") {
    val conditions = TestingConditions(
      desiredRate = 1 every 200.millis,
      backOff = _ => 0.millis,
      productionRate = 1 every 1.millis,
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
      desiredRate = 1 every 200.millis,
      backOff = _ => 0.millis,
      productionRate = 1 every 300.millis,
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
