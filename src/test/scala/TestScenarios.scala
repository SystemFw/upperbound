package upperbound

import syntax.backpressure._

import fs2._
import cats.effect._
import cats.effect.concurrent.Ref
import cats.syntax.all._

import scala.collection.immutable.Queue
import scala.concurrent.duration._

object TestScenarios {
  case class TestingConditions(
      desiredRate: Rate,
      backOff: FiniteDuration => FiniteDuration,
      productionRate: Rate,
      producers: Int,
      jobsPerProducer: Int,
      jobCompletion: FiniteDuration,
      backPressure: BackPressure.Ack[Int],
      samplingWindow: FiniteDuration
  )

  case class Metric(
      diffs: Vector[Long],
      mean: Double,
      stdDeviation: Double,
      overshoot: Double,
      undershoot: Double
  )
  object Metric {
    def from(samples: Vector[Long]): Metric = {
      def diffs =
        Stream
          .emits(samples)
          .sliding(2)
          .map { case Queue(x, y) => math.abs(x - y) }
          .toVector
      def mean = diffs.sum.toDouble / diffs.size
      def variance =
        diffs.foldRight(0: Double)((x, tot) => tot + math.pow(x - mean, 2))
      def std = math.sqrt(variance)
      def overshoot = diffs.max
      def undershoot = diffs.min

      Metric(diffs, mean, std, overshoot, undershoot)
    }
  }

  case class Result(
      producerMetrics: Metric,
      jobExecutionMetrics: Metric
  )

  def vector[F[_]: Concurrent] = Ref[F].of(Vector.empty[Long])

  def mkScenario[F[_]: Concurrent: Timer](t: TestingConditions): F[Result] =
    (vector[F], vector[F]).mapN {
      case (submissionTimes, startTimes) =>
        def record(destination: Ref[F, Vector[Long]]): F[Unit] =
          Timer[F].clock.monotonic(MILLISECONDS).flatMap { time =>
            destination.update(times => time +: times)
          }

        def job(i: Int) =
          record(startTimes) >> Timer[F].sleep(t.jobCompletion).as(i)

        def pulse = Stream.fixedRate[F](t.productionRate.period)

        def concurrentProducers: Pipe[F, Unit, Unit] =
          producer =>
            Stream(producer.zipLeft(pulse)).repeat
              .take(t.producers)
              .parJoin(t.producers)

        def experiment = Limiter.start[F](t.desiredRate).use { implicit limiter =>
          def producer: Stream[F, Unit] =
            Stream
              .range(0, t.jobsPerProducer)
              .map(job(_).withBackoff(t.backOff, t.backPressure))
              .evalMap { x =>
                record(submissionTimes) *> limiter
                  .submit(job = x, priority = 0)
              }

          Stream
            .sleep[F](t.samplingWindow)
            .concurrently(producer.through(concurrentProducers))
            .compile
            .drain
        }

        def collectResults = (submissionTimes.get, startTimes.get).mapN { (prods, jobs) =>
          Result(
            producerMetrics = Metric.from(prods.sorted),
            jobExecutionMetrics = Metric.from(jobs.sorted)
          )
        }

        experiment >> collectResults
    }.flatten
}
