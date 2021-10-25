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
import cats.syntax.all._
import scala.concurrent.duration._

import internal.Barrier

import cats.effect.testkit.TestControl.{executeEmbed => runTC}

class BarrierSuite extends BaseSuite {
  test("enter the barrier immediately if below the limit") {
    val prog = Barrier[IO](10).use(_.enter)

    runTC(prog)
  }

  test("enter blocks when limit is hit") {
    val prog = Barrier[IO](2).use { barrier =>
      val complete = barrier.enter.as(true).timeoutTo(2.seconds, false.pure[IO])
      (complete, complete, complete).tupled
    }

    runTC(prog).assertEquals((true, true, false))
  }

  test("enter is unblocked by exit") {
    val prog = Barrier[IO](1)
      .use { barrier =>
        barrier.enter >> IO.monotonic.flatMap { start =>
          (barrier.enter >> IO.monotonic.map(_ - start)).start.flatMap {
            fiber =>
              IO.sleep(1.second) >>
                barrier.exit >>
                fiber.joinWithNever
          }
        }
      }

    runTC(prog).assertEquals(1.second)
  }

  test("enter is unblocked by exit the right amount of times") {
    val prog = Barrier[IO](3)
      .use { barrier =>
        barrier.enter >> barrier.enter >> barrier.enter >>
          IO.monotonic.flatMap { start =>
            (barrier.enter >> barrier.enter >> IO.monotonic.map(
              _ - start
            )).start
              .flatMap { fiber =>
                IO.sleep(1.second) >> barrier.exit >>
                  IO.sleep(1.second) >> barrier.exit >>
                  fiber.joinWithNever
              }
          }
      }

    runTC(prog).assertEquals(2.seconds)
  }

  test("Only one fiber can block on enter at the same time") {
    val prog = Barrier[IO](1)
      .use { barrier =>
        barrier.enter >> (barrier.enter, barrier.enter).parTupled
      }

    runTC(prog).intercept[Throwable]
  }

  test("Cannot call exit without entering") {
    val prog = Barrier[IO](5).use { barrier =>
      barrier.exit >> barrier.enter
    }

    runTC(prog).intercept[Throwable]
  }

  test("Calls to exit cannot outnumber calls to enter") {
    val prog = Barrier[IO](2).use { barrier =>
      (barrier.enter >> barrier.enter >> IO.sleep(3.seconds)).background
        .surround {
          IO.sleep(1.second) >> barrier.exit >> barrier.exit >> barrier.exit
        }
    }

    runTC(prog).intercept[Throwable]
  }

  test("Cannot construct a barrier with a 0 limit") {
    Barrier[IO](0).use_.intercept[Throwable]
  }

  test("Cannot change a limit to zero") {
    Barrier[IO](0).use(_.limit.set(0)).intercept[Throwable]
  }

  // limit expanded while enter blocked, immediate unblock
  test("A blocked enter is immediately unblocked if the limit is expanded") {
    val prog = Barrier[IO](3)
      .use { barrier =>
        barrier.enter >> barrier.enter >> barrier.enter >>
          IO.monotonic.flatMap { start =>
            (barrier.enter >> barrier.enter >> IO.monotonic.map(
              _ - start
            )).start
              .flatMap { fiber =>
                IO.sleep(1.second) >> barrier.limit.set(4) >>
                  IO.sleep(1.second) >> barrier.limit.set(5) >>
                  fiber.joinWithNever
              }
          }
      }

    runTC(prog).assertEquals(2.seconds)
  }
  // limit restricted while enter blocked, no premature unblocking
  test("") {}
  // test limit changes when idle
  test("") {}

}