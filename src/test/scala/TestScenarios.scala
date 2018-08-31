package upperbound

import fs2.{Pipe, Stream}
import cats.effect.{Concurrent, IO, Timer}
import cats.effect.concurrent.Ref
import cats.syntax.functor._
import cats.syntax.apply._
import cats.syntax.applicative._

import scala.collection.immutable.Queue
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.ExecutionContext
import org.specs2.specification.BeforeAll

trait TestScenarios extends BeforeAll {

  def samplingWindow: FiniteDuration
  def description: String

  def beforeAll = println {
    s"""
    |=============
    | $description tests running. Sampling window: $samplingWindow
    |=============
   """.stripMargin('|')
  }

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

  case class Metric(diffs: Vector[Long],
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

  def mkScenario(t: TestingConditions)(
      implicit Timer: Timer[IO],
      Concurrent: Concurrent[IO],
      ec: ExecutionContext): IO[Result] =
    Ref.of[IO, Vector[Long]](Vector.empty) flatMap { submissionTimes =>
      Ref.of[IO, Vector[Long]](Vector.empty) flatMap { startTimes =>
        Limiter.start[IO](t.desiredRate, t.backOff) flatMap { limiter =>

          def record(destination: Ref[IO, Vector[Long]]): IO[Unit] =
            IO(System.currentTimeMillis) flatMap { time =>
              destination.modify(times => (time +: times) -> times).void
            }

          def job(i: Int) =
            record(startTimes) *> Timer.sleep(t.jobCompletion) *> i.pure[IO]

          def producer: Stream[IO, Unit] =
            Stream.range(0, t.jobsPerProducer).map(job) evalMap { x =>
              record(submissionTimes) *> limiter.worker.submit(
                job = x,
                priority = 0,
                ack = t.backPressure)
            }

          def pulse[B]: Pipe[IO, B, B] =
            in =>
              Stream
                .awakeEvery[IO](t.productionRate.period)
                .zip(in)
                .map(_._2)

          def concurrentProducers: Stream[IO, Unit] =
            Stream(producer through pulse).repeat
              .take(t.producers)
              .parJoin(t.producers)

          for {
            _ <- Concurrent.start(concurrentProducers.compile.drain).void
            _ <- Timer.sleep(t.samplingWindow) *> limiter.shutDown
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
