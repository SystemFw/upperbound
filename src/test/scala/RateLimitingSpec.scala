package upperbound

import cats.effect.{ContextShift, IO, Timer}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import org.specs2.mutable.Specification
import org.scalactic.{Tolerance, TripleEquals}

class RateLimitingSpec(implicit val ec: ExecutionContext)
    extends Specification
    with TestScenarios {

  implicit val Timer: Timer[IO] = IO.timer(ec)
  implicit val ContextShift: ContextShift[IO] = IO.contextShift(ec)

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

      import Tolerance._, TripleEquals._

      val res = mkScenario(conditions).unsafeRunSync
      List(
        res.producerMetrics.mean === 3d +- 3d,
        res.jobExecutionMetrics.mean === 205d +- 5d,
        res.jobExecutionMetrics.undershoot >= 200l
      ).forall(x => x)
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

      import Tolerance._, TripleEquals._

      val res = mkScenario(conditions).unsafeRunSync

      val noDelays =
        res.producerMetrics.diffs
          .zip(res.jobExecutionMetrics.diffs)
          .map { case (x, y) => x === y +- 3L }
          .forall(x => x)

      res.producerMetrics.mean === 300d +- 15d && noDelays
    }
  }
}
