package upperbound

import scala.concurrent.duration._

import org.specs2.mutable.Specification
import org.specs2.matcher.TaskMatchers
import org.scalactic.{Tolerance, TripleEquals}

class RateLimitingSpec
    extends Specification
    with TestScenarios
    with TaskMatchers {

  val samplingWindow = 5.seconds
  val description = "Rate limiting"

  import syntax.rate._

  "Limiter" >> {
    "multiple fast producers, fast non-failing jobs" >> {
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

      mkScenario(conditions) must returnValue { (res: Result) =>
        import Tolerance._, TripleEquals._

        List(
          res.producerMetrics.mean === 3d +- 3d,
          res.jobExecutionMetrics.mean === 205d +- 5d,
          res.jobExecutionMetrics.undershoot >= 200l
        ).forall(x => x)
      }
    }

    "slow producer, no unnecessary delays" >> {
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

      mkScenario(conditions) must returnValue { (res: Result) =>
        import Tolerance._, TripleEquals._

        val noDelays =
          res.producerMetrics.diffs
            .zip(res.jobExecutionMetrics.diffs)
            .map { case (x, y) => x === y +- 3L }
            .forall(x => x)

        res.producerMetrics.mean === 300d +- 15d && noDelays
      }
    }
  }
}
