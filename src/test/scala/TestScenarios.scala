package upperbound

import fs2._
import cats.effect._
import cats.effect.concurrent.Ref
import cats.effect.implicits._
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
      undershoot: Double)
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

  def mkScenario[F[_]: Concurrent](t: TestingConditions)(
      implicit T: Timer[F]): F[Result] =
    Ref[F].of(Vector.empty[Long]) flatMap { submissionTimes =>
      Ref[F].of(Vector.empty[Long]) flatMap { startTimes =>
        Limiter.start[F](t.desiredRate, t.backOff) flatMap { limiter =>
          def record(destination: Ref[F, Vector[Long]]): F[Unit] =
            T.clock.monotonic(MILLISECONDS) flatMap { time =>
              destination.update(times => time +: times)
            }

          def job(i: Int) =
            record(startTimes) *> T.sleep(t.jobCompletion).as(i)

          def producer: Stream[F, Unit] =
            Stream.range(0, t.jobsPerProducer).map(job) evalMap { x =>
              record(submissionTimes) *> limiter.submit(
                job = x,
                priority = 0,
                ack = t.backPressure)
            }

          def pulse = Stream.fixedRate[F](t.productionRate.period)

          def concurrentProducers: Stream[F, Unit] =
            Stream(producer zipLeft pulse).repeat
              .take(t.producers)
              .parJoin(t.producers)

          for {
            _ <- concurrentProducers.compile.drain.start.void
            _ <- T.sleep(t.samplingWindow) // *> limiter.shutDown TODO
            p <- submissionTimes.get
            j <- startTimes.get
          } yield
            Result(
              producerMetrics = Metric.from(p.sorted),
              jobExecutionMetrics = Metric.from(j.sorted)
            )
        }
      }
    }
}
