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
    val prog = Barrier[IO](10).use(_.enter).timeout(2.seconds)

    runTC(prog)
  }

  test("enter blocks when limit is hit") {
    val prog = Barrier[IO](2).use { barrier =>
      val complete = barrier.enter.as(true).timeoutTo(2.seconds, false.pure[IO])
      (complete, complete, complete).tupled
    }

    runTC(prog).assertEquals((true, true, false))
  }

  test("enter blocks when limit is hit, unblocked by exit") {
    val prog = Barrier[IO](2).use { barrier =>
      barrier.enter >> barrier.enter >>
        IO.monotonic.flatMap { start =>
          (barrier.enter >> IO.monotonic.map(_ - start)).start.flatMap {
            fiber =>
              IO.sleep(2.seconds) >>
                barrier.exit >>
                fiber.joinWithNever.timeout(2.seconds)
          }
        }
    }

    runTC(prog).assertEquals(2.seconds)
  }

  // right amount of unblocking via exit calls
  test("") {}
  // make sure running cannot go below zero?
  test("") {}
  // ^ corollary, 0 -> exit -> enter doesn't allow entering
  test("") {}
  // ^^ can I do this in a more realistic non zero case?
  test("") {}
  // limit expanded while enter blocked, immediate unblock
  test("") {}
  // limit restricted while enter blocked, no premature unblocking
  test("") {}
  // test limit changes when idle
  test("") {}
  // enter no concurrency
}
