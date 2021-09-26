/*
 * Copyright (c) 2017 Fabio Labella
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package upperbound

import fs2._
import cats.effect._
import cats.syntax.all._
import cats.effect.syntax.all._

import scala.concurrent.duration._

object TestScenarios {
  case class TestingConditions(
      desiredInterval: FiniteDuration,
      productionInterval: FiniteDuration,
      producers: Int,
      jobsPerProducer: Int,
      jobCompletion: FiniteDuration,
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
          .map { chunk =>
            math.abs(chunk(1) - chunk(0))
          }
          .toVector
      def mean = diffs.sum.toDouble / diffs.size
      def variance =
        diffs.foldRight(0: Double)((x, tot) => tot + math.pow(x - mean, 2))
      def std = math.sqrt(variance)
      def overshoot = diffs.max
      def undershoot = diffs.min

      Metric(diffs, mean, std, overshoot.toDouble, undershoot.toDouble)
    }
  }

  case class Result(
      producerMetrics: Metric,
      jobExecutionMetrics: Metric
  )

  def vector[F[_]: Concurrent] = Ref[F].of(Vector.empty[Long])

  def mkScenario[F[_]: Temporal](t: TestingConditions): F[Result] =
    (vector[F], vector[F]).mapN { case (submissionTimes, startTimes) =>
      def record(destination: Ref[F, Vector[Long]]): F[Unit] =
        Clock[F].monotonic flatMap { time =>
          destination.update(times => time.toMillis +: times)
        }

      def job(i: Int) =
        record(startTimes) >> Temporal[F].sleep(t.jobCompletion).as(i)

      def pulse = Stream.fixedRate[F](t.productionInterval)

      def concurrentProducers: Pipe[F, Unit, Unit] =
        producer =>
          Stream(producer zipLeft pulse).repeat
            .take(t.producers)
            .parJoin(t.producers)

      def experiment =
        Limiter.start[F](t.desiredInterval).use { implicit limiter =>
          def producer: Stream[F, Unit] =
            Stream
              .range(0, t.jobsPerProducer)
              .map(job(_))
              .evalMap { x =>
                record(submissionTimes) *> limiter
                  .await(job = x, priority = 0)
                  .start
                  .void
              }

          Stream
            .sleep[F](t.samplingWindow)
            .concurrently(producer.through(concurrentProducers))
            .compile
            .drain
        }

      def collectResults = (submissionTimes.get, startTimes.get).mapN {
        (prods, jobs) =>
          Result(
            producerMetrics = Metric.from(prods.sorted),
            jobExecutionMetrics = Metric.from(jobs.sorted)
          )
      }

      experiment >> collectResults
    }.flatten
}
