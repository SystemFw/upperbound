package upperbound

import cats.effect._
import scala.concurrent.duration._

import syntax.rate._

import org.scalactic.Tolerance

class RateLimitingSpec extends BaseSpec {
  val samplingWindow = 10.seconds
  import TestScenarios._

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

      val res = mkScenario[IO](conditions).unsafeToFuture
      env.tick(samplingWindow)

      res.map { r =>
        assert(r.jobExecutionMetrics.diffs.forall(_  === 200L))
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

      import Tolerance._

      val res = mkScenario[IO](conditions).unsafeToFuture
      env.tick(samplingWindow)

      res.map { r =>
        // TODO why the first two are faster?
        assert(r.jobExecutionMetrics.diffs.drop(2).forall(_ === 300L))
      }
    }
  }
}
