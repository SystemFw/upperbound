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
      control: Limiter[IO] => IO[Unit] = _ => IO.unit
  ): IO[Vector[FiniteDuration]] =
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

      def results =
        runProducers
          .sliding(2)
          .map { sample => sample(1) - sample(0) }
          .compile
          .toVector

      control(limiter).background.surround(results)
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
      jobsPerProducer = if (isJS) 10 else 100,
      jobCompletion = 0.seconds
    )

    TestControl.executeEmbed(prog).map { r =>
      assert(r.forall(_ == 200.millis))
    }
  }

  test("slow producer, no unnecessary delays") {
    val prog = simulation(
      desiredInterval = 200.millis,
      maxConcurrent = Int.MaxValue,
      productionInterval = 300.millis,
      producers = 1,
      jobsPerProducer = if (isJS) 10 else 100,
      jobCompletion = 0.seconds
    )

    TestControl.executeEmbed(prog).map { r =>
      assert(r.forall(_ == 300.millis))
    }
  }

  test("maximum concurrency") {
    val prog = simulation(
      desiredInterval = 50.millis,
      maxConcurrent = 3,
      productionInterval = 1.millis,
      producers = 1,
      jobsPerProducer = 10,
      jobCompletion = 300.millis
    )

    val expected = Vector(
      50, 50, 200, 50, 50, 200, 50, 50, 200
    ).map(_.millis)

    TestControl.executeEmbed(prog).assertEquals(expected)
  }

  test("interval change") {
    val prog = simulation(
      desiredInterval = 200.millis,
      maxConcurrent = Int.MaxValue,
      productionInterval = 1.millis,
      producers = 1,
      jobsPerProducer = 10,
      jobCompletion = 0.seconds,
      control =
        limiter => IO.sleep(1100.millis) >> limiter.setMinInterval(300.millis)
    )

    val expected = Vector(
      200, 200, 200, 200, 200, 300, 300, 300, 300
    ).map(_.millis)

    TestControl.executeEmbed(prog).assertEquals(expected)
  }

  test(
    "descheduling a job while blocked on the time limit should not affect the interval"
  ) {
    val prog = Limiter.start[IO](500.millis).use { limiter =>
      val job = limiter.submit(IO.monotonic)
      val skew = IO.sleep(10.millis) // to ensure we queue jobs as desired

      (
        job,
        skew >> job.timeoutTo(200.millis, IO.unit),
        skew >> skew >> job
      ).parMapN((t1, _, t3) => t3 - t1)
    }

    TestControl.executeEmbed(prog).assertEquals(500.millis)
  }

  test(
    "descheduling a job while blocked on the concurrency limit should not affect the interval"
  ) {
    val prog = Limiter.start[IO](30.millis, maxConcurrent = 1).use { limiter =>
      val job = limiter.submit(IO.monotonic <* IO.sleep(500.millis))
      val skew = IO.sleep(10.millis) // to ensure we queue jobs as desired

      (
        job,
        skew >> job.timeoutTo(200.millis, IO.unit),
        skew >> skew >> job
      ).parMapN((t1, _, t3) => t3 - t1)
    }

    TestControl.executeEmbed(prog).assertEquals(500.millis)
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
      ).parMapN((t1, outcome, t3) => (outcome, t3 - t1))
    }

    TestControl.executeEmbed(prog).assertEquals("canceled" -> 200.millis)
  }

  test("max concurrency shrinks before interval elapses, should be respected") {
    val interval = 500.millis
    val taskDuration = 700.millis // > interval
    val concurrencyShrinksAt = 300.millis // < interval

    val prog =
      Limiter.start[IO](interval, maxConcurrent = 2).use { limiter =>
        val skew = IO.sleep(10.millis)
        (
          limiter.submit(IO.monotonic <* IO.sleep(taskDuration)),
          skew >> limiter.submit(IO.monotonic),
          IO.sleep(concurrencyShrinksAt) >> limiter.setMaxConcurrent(1)
        ).parMapN((t1, t2, _) => t2 - t1)
      }

    TestControl.executeEmbed(prog).assertEquals(taskDuration)
  }

  test("max concurrency shrinks after interval elapses, should be no-op") {
    val interval = 300.millis
    val taskDuration = 700.millis // > interval
    val concurrencyShrinksAt = 500.millis // > interval

    val prog =
      Limiter.start[IO](interval, maxConcurrent = 2).use { limiter =>
        val skew = IO.sleep(10.millis)
        (
          limiter.submit(IO.monotonic <* IO.sleep(taskDuration)),
          skew >> limiter.submit(IO.monotonic),
          IO.sleep(concurrencyShrinksAt) >> limiter.setMaxConcurrent(1)
        ).parMapN((t1, t2, _) => t2 - t1)
      }

    TestControl.executeEmbed(prog).assertEquals(interval)
  }
}
