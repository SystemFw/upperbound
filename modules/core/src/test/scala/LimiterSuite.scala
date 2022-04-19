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

import cats.syntax.all._
import cats.effect._
import fs2._
import scala.concurrent.duration._

import cats.effect.testkit.TestControl

class LimiterSuite extends BaseSuite {
  def simulation(
      desiredInterval: FiniteDuration,
      maxConcurrent: Int,
      productionInterval: FiniteDuration,
      producers: Int,
      jobsPerProducer: Int,
      jobCompletion: FiniteDuration,
      samplingWindow: FiniteDuration
  ): IO[Vector[Long]] =
    Limiter.start[IO](desiredInterval, maxConcurrent).use { limiter =>
      def job = IO.monotonic <* IO.sleep(jobCompletion)

      def producer =
        Stream(job)
          .repeatN(jobsPerProducer.toLong)
          .covary[IO]
          .meteredStartImmediately(productionInterval)
          .mapAsyncUnordered(Int.MaxValue)(job => limiter.submit(job))

      def runProducers =
        Stream(producer)
          .repeatN(producers.toLong)
          .parJoinUnbounded
          .interruptAfter(samplingWindow)

      def results =
        runProducers
          .sliding(2)
          .map { sample => sample(1).toMillis - sample(0).toMillis }
          .compile
          .toVector

      results
    }

  test("submit semantics should return the result of the submitted job") {
    IO.ref(false)
      .flatMap { complete =>
        Limiter.start[IO](200.millis).use {
          _.submit(complete.set(true).as("done"))
            .product(complete.get)
        }
      }
      .map { case (res, state) =>
        assertEquals(res, "done")
        assertEquals(state, true)
      }
  }

  test("submit semantics should report errors of a failed task") {
    case class MyError() extends Exception
    Limiter
      .start[IO](200.millis)
      .use {
        _.submit(IO.raiseError[Int](new MyError))
      }
      .intercept[MyError]
  }

  test("multiple fast producers, fast non-failing jobs") {
    val prog = simulation(
      desiredInterval = 200.millis,
      maxConcurrent = Int.MaxValue,
      productionInterval = 1.millis,
      producers = 4,
      jobsPerProducer = 100,
      jobCompletion = 0.seconds,
      samplingWindow = 10.seconds
    )

    TestControl.executeEmbed(prog).map { r => assert(r.forall(_ == 200L)) }
  }

  test("slow producer, no unnecessary delays") {
    val prog = simulation(
      desiredInterval = 200.millis,
      maxConcurrent = Int.MaxValue,
      productionInterval = 300.millis,
      producers = 1,
      jobsPerProducer = 100,
      jobCompletion = 0.seconds,
      samplingWindow = 10.seconds
    )

    TestControl.executeEmbed(prog).map { r => assert(r.forall(_ == 300L)) }
  }

  test("maximum concurrency") {
    val prog = simulation(
      desiredInterval = 50.millis,
      maxConcurrent = 3,
      productionInterval = 1.millis,
      producers = 1,
      jobsPerProducer = 10,
      jobCompletion = 300.millis,
      samplingWindow = 10.seconds
    )

    val expected = Vector(
      50L, 50, 200, 50, 50, 200, 50, 50, 200
    )

    TestControl.executeEmbed(prog).assertEquals(expected)
  }

  test("descheduling job, interval unaffected") {
    // because dequeue runs concurrently with the barrier and the timer,
    // the element gets dequeued before its slot arrives, so it's already outside of the queue
    // and the slot gets indeed taken
    val prog = Limiter.start[IO](500.millis).use { limiter =>
      val job = limiter.submit(IO.monotonic)
      val skew = IO.sleep(10.millis) // to ensure we queue jobs as desired

      (
        job,
        skew >> job.timeoutTo(200.millis, IO.unit),
        skew >> skew >> job
      ).parMapN((t1, _, t3) => t3.toMillis - t1.toMillis)
    }

    TestControl.executeEmbed(prog).assertEquals(500L)
  }

  test("cancelling job, interval slot gets taken") {
    val prog = Limiter.start[IO](100.millis).use { limiter =>
      val job = limiter.submit(IO.monotonic)
      val canceledJob = limiter
        .submit(IO.monotonic <* IO.sleep(50.millis))
        .as("done")
        .timeoutTo(125.millis, "canceled".pure[IO])
      val skew = IO.sleep(10.millis) // to ensure we queue jobs as desired

      (
        job,
        skew >> canceledJob,
        skew >> skew >> job
      ).parMapN((t1, outcome, t3) => (outcome, t3.toMillis - t1.toMillis))
    }

    TestControl.executeEmbed(prog).assertEquals("canceled" -> 200L)
  }
}
