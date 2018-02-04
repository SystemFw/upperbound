package upperbound

import fs2.{async, Pipe, Scheduler, Stream}
import fs2.async.Ref
import cats.effect.IO

import cats.syntax.functor._
import cats.syntax.apply._
import cats.syntax.applicative._

import scala.collection.immutable.Queue
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.ExecutionContext //.Implicits.global

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
      implicit ec: ExecutionContext): IO[Result] =
    Scheduler.allocate[IO](2) flatMap { s =>
      async.refOf[IO, Vector[Long]](Vector.empty) flatMap { submissionTimes =>
        async.refOf[IO, Vector[Long]](Vector.empty) flatMap { startTimes =>
          Limiter.start[IO](t.desiredRate, t.backOff) flatMap { limiter =>
            val (scheduler, cleanup) = s

            def record(destination: Ref[IO, Vector[Long]]): IO[Unit] =
              IO(System.currentTimeMillis) flatMap { time =>
                destination.modify(times => time +: times).void
              }

            def job(i: Int) =
              record(startTimes) *> scheduler
                .sleep[IO](t.jobCompletion)
                .compile
                .drain *> i.pure[IO]

            def producer: Stream[IO, Unit] =
              Stream.range(0, t.jobsPerProducer).map(job) evalMap { x =>
                record(submissionTimes) *> limiter.worker.submit(
                  job = x,
                  priority = 0,
                  ack = t.backPressure)
              }

            def pulse[B]: Pipe[IO, B, B] =
              in =>
                scheduler
                  .awakeEvery[IO](t.productionRate.period)
                  .zip(in)
                  .map(_._2)

            def concurrentProducers: Stream[IO, Unit] =
              Stream(producer through pulse).repeat
                .take(t.producers)
                .join(t.producers)

            for {
              _ <- async.fork(concurrentProducers.compile.drain)
              _ <- scheduler
                .sleep_[IO](t.samplingWindow)
                .compile
                .drain *> limiter.shutDown
              p <- submissionTimes.get
              j <- startTimes.get
              _ <- cleanup
            } yield
              Result(
                producerMetrics = Metric.from(p.sorted),
                jobExecutionMetrics = Metric.from(j.sorted)
              )
          }
        }
      }
    }
}
