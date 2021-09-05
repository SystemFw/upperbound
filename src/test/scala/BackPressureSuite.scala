package upperbound

import cats.effect.IO
import fs2.Stream
import scala.concurrent.duration._

import upperbound.syntax.rate._

class BackPressureSuite extends BaseSuite {
  val samplingWindow = 5.seconds
  import TestScenarios._

  // Due to its asynchronous/concurrent nature, the backoff
  // functionality cannot guarantee instantaneous propagation of rate
  // changes. The parameters in the test are tuned to guarantee that
  // changes consistently propagate exactly one job later, so that we
  // can make the test assertion deterministic.
  val T = 200
  val backOffConditions = TestingConditions(
    backOff = x => x,
    backPressure = BackPressure.never,
    desiredRate = 1 every T.millis,
    productionRate = 1 every 1.millis,
    producers = 1,
    jobsPerProducer = 100,
    jobCompletion = 25.millis,
    samplingWindow = samplingWindow
  )

  test("backpressure follows the provided backOff function") {
    def linearBackOff: FiniteDuration => FiniteDuration = _ + T.millis
    def everyJob: Either[Throwable, Int] => Boolean = _ => true

    val conditions = backOffConditions.copy(
      backOff = linearBackOff,
      backPressure = BackPressure.Ack(_ => true)
    )

    mkScenario[IO](conditions).map { r =>
      val measuredBackOff = r.jobExecutionMetrics.diffs.map(_ / T)
      val linear =
        Stream.iterate(1L)(_ + 1).take(measuredBackOff.length).toVector

      assertEquals(measuredBackOff, linear)
    }
  }

  test("backpressure only applies when jobs are signalling for it") {
    def constantBackOff: FiniteDuration => FiniteDuration = _ => T.millis * 2
    def everyOtherJob: Either[Throwable, Int] => Boolean =
      _.toOption.get % 2 == 0

    val conditions = backOffConditions.copy(
      backOff = constantBackOff,
      backPressure = BackPressure.Ack(everyOtherJob)
    )

    mkScenario[IO](conditions).map { r =>
      val measuredBackOff = r.jobExecutionMetrics.diffs.map(_ / T)
      val alternating =
        Stream(1L, 2).repeat.take(measuredBackOff.length).toVector

      assertEquals(measuredBackOff, alternating)
    }
  }
}
