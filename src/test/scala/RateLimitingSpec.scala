package upperbound

import syntax.rate._

import cats.effect._
import scala.concurrent.duration._

class RateLimitingSpec extends BaseSpec {
  val samplingWindow = 10.seconds
  import TestScenarios._

  def within(a: Long, b: Long, threshold: Long): Boolean =
    scala.math.abs(a - b) < threshold

  "Limiter" - {
    "multiple fast producers, fast non-failing jobs" in {
      val E = new Env
      import E._

      val conditions = TestingConditions(
        desiredRate = 1 every 200.millis,
        backOff = _ => 0.millis,
        productionRate = 1 every 1.millis,
        producers = 4,
        jobsPerProducer = 100,
        backPressure = BackPressure.never,
        jobCompletion = 0.seconds,
        samplingWindow = samplingWindow
      )

      val res = mkScenario[IO](conditions).unsafeToFuture()

      res.map { r =>
        assert(
          r.jobExecutionMetrics.diffs.forall(within(_, 200L, 10L))
        )
      }
    }

    "slow producer, no unnecessary delays" in {
      val E = new Env
      import E._

      val conditions = TestingConditions(
        desiredRate = 1 every 200.millis,
        backOff = _ => 0.millis,
        productionRate = 1 every 300.millis,
        producers = 1,
        jobsPerProducer = 100,
        backPressure = BackPressure.never,
        jobCompletion = 0.seconds,
        samplingWindow = samplingWindow
      )

      val res = mkScenario[IO](conditions).unsafeToFuture()

      res.map { r =>
        assert(
          r.jobExecutionMetrics.diffs.forall(within(_, 300L, 10L))
        )
      }
    }
  }
}
