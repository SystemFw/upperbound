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
package internal

import cats.effect._
import cats.syntax.all._
import scala.concurrent.duration._

import cats.effect.testkit.TestControl.{
  executeEmbed => runTC,
  NonTerminationException
}

class BarrierSuite extends BaseSuite {
  def fillBarrier(barrier: Barrier[IO]): IO[Unit] =
    barrier.limit
      .flatMap(limit => barrier.enter.replicateA(limit))
      .void

  def timedStart(fa: IO[_]): Resource[IO, IO[FiniteDuration]] =
    fa.timed.background.map { _.flatMap(_.embedNever).map(_._1) }

  test("enter the barrier immediately if below the limit") {
    val prog = Barrier[IO](10).flatMap(_.enter)

    runTC(prog)
  }

  test("enter blocks when limit is hit") {
    val prog = Barrier[IO](2).flatMap { barrier =>
      fillBarrier(barrier) >> barrier.enter
    }

    runTC(prog).intercept[NonTerminationException]
  }

  test("enter is unblocked by exit") {
    val prog = Barrier[IO](1).flatMap { barrier =>
      fillBarrier(barrier) >> timedStart(barrier.enter).use { getResult =>
        IO.sleep(1.second) >> barrier.exit >> getResult
      }
    }

    runTC(prog).assertEquals(1.second)
  }

  test("enter is unblocked by exit the right amount of times") {
    val prog = Barrier[IO](3)
      .flatMap { barrier =>
        fillBarrier(barrier) >>
          timedStart(barrier.enter >> barrier.enter).use { getResult =>
            IO.sleep(1.second) >>
              barrier.exit >>
              IO.sleep(1.second) >>
              barrier.exit >>
              getResult
          }
      }

    runTC(prog).assertEquals(2.seconds)
  }

  test("Only one fiber can block on enter at the same time") {
    val prog = Barrier[IO](1)
      .flatMap { barrier =>
        fillBarrier(barrier) >> (barrier.enter, barrier.enter).parTupled
      }

    runTC(prog).intercept[Throwable]
  }

  test("Cannot call exit without entering") {
    val prog = Barrier[IO](5).flatMap { barrier =>
      barrier.exit >> barrier.enter
    }

    runTC(prog).intercept[Throwable]
  }

  test("Calls to exit cannot outnumber calls to enter") {
    val prog = Barrier[IO](2).flatMap { barrier =>
      barrier.enter >> barrier.enter >> barrier.exit >> barrier.exit >> barrier.exit
    }

    runTC(prog).intercept[Throwable]
  }

  test("Cannot construct a barrier with a 0 limit") {
    Barrier[IO](0).intercept[Throwable]
  }

  test("Cannot change a limit to zero") {
    Barrier[IO](0).flatMap(_.setLimit(0)).intercept[Throwable]
  }

  test("A blocked enter is immediately unblocked if the limit is expanded") {
    val prog = Barrier[IO](3)
      .flatMap { barrier =>
        fillBarrier(barrier) >>
          timedStart(barrier.enter >> barrier.enter).use { getResult =>
            IO.sleep(1.second) >>
              barrier.setLimit(4) >>
              IO.sleep(1.second) >>
              barrier.setLimit(5) >>
              getResult
          }
      }

    runTC(prog).assertEquals(2.seconds)
  }

  test("A blocked enter is not unblocked prematurely if the limit is shrunk") {
    val prog = Barrier[IO](3)
      .flatMap { barrier =>
        fillBarrier(barrier) >>
          timedStart(barrier.enter).use { getResult =>
            barrier.setLimit(2) >>
              IO.sleep(1.second) >>
              barrier.exit >>
              getResult
          }
      }

    runTC(prog).intercept[NonTerminationException]
  }

  test("Sequential limit changes") {
    val prog = Barrier[IO](3)
      .flatMap { barrier =>
        fillBarrier(barrier) >>
          timedStart(barrier.enter).use { _ =>
            barrier.setLimit(5) >>
              barrier.enter >>
              barrier.setLimit(2) >>
              barrier.exit >>
              barrier.exit >>
              barrier.enter
          }
      }

    runTC(prog).intercept[NonTerminationException]
  }
}
