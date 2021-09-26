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

import cats.effect._
import scala.concurrent.duration._

import cats.effect.testkit.TestControl

class RateLimitingSuite extends BaseSuite {
  val samplingWindow = 10.seconds
  import TestScenarios._

  test("await semantics should return the result of the submitted job") {
    IO.ref(false)
      .flatMap { complete =>
        Limiter.start[IO](200.millis).use {
          _.await(complete.set(true).as("done")).product(complete.get)
        }
      }
      .map { case (res, state) =>
        assertEquals(res, "done")
        assertEquals(state, true)
      }
  }

  test("await semantics should report errors of a failed task") {
    case class MyError() extends Exception
    Limiter
      .start[IO](200.millis)
      .use {
        _.await(IO.raiseError[Int](new MyError))
      }
      .intercept[MyError]
  }

  test("multiple fast producers, fast non-failing jobs") {
    val conditions = TestingConditions(
      desiredInterval = 200.millis,
      productionInterval = 1.millis,
      producers = 4,
      jobsPerProducer = 100,
      jobCompletion = 0.seconds,
      samplingWindow = samplingWindow
    )

    TestControl.executeEmbed(mkScenario[IO](conditions)).map { r =>
      // adjust once final details of the TestControl api are finalised
      assert(r.jobExecutionMetrics.diffs.forall(_ == 200L))
    }
  }

  test("slow producer, no unnecessary delays") {
    val conditions = TestingConditions(
      desiredInterval = 200.millis,
      productionInterval = 300.millis,
      producers = 1,
      jobsPerProducer = 100,
      jobCompletion = 0.seconds,
      samplingWindow = samplingWindow
    )

    TestControl.executeEmbed(mkScenario[IO](conditions)).map { r =>
      // adjust once final details of the TestControl api are finalised
      assert(
        r.jobExecutionMetrics.diffs.forall(_ == 300L)
      )
    }
  }
}
