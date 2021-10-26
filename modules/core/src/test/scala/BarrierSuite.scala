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

import cats.effect.testkit.TestControl.{
  executeEmbed => runTC,
  NonTerminationException
}

class BarrierSuite extends BaseSuite {
  def fillBarrier(barrier: Barrier[IO]): IO[Unit] =
    barrier.limit.get
      .flatMap(limit => barrier.enter.replicateA(limit))
      .void

  def timedStart(fa: IO[_]): Resource[IO, IO[FiniteDuration]] =
    Resource.eval(IO.monotonic).flatMap { t0 =>
      val fa_ = fa >> IO.monotonic.map(t => t - t0)
      Resource.make(fa_.start)(_.cancel).map(_.joinWithNever)
    }

  test("enter the barrier immediately if below the limit") {
    val prog = Barrier[IO](10).use(_.enter)

    runTC(prog)
  }

  test("enter blocks when limit is hit") {
    val prog = Barrier[IO](2).use { barrier =>
      fillBarrier(barrier) >> barrier.enter
    }

    runTC(prog).intercept[NonTerminationException]
  }

  test("enter is unblocked by exit") {
    val prog = Barrier[IO](1).use { barrier =>
      fillBarrier(barrier) >> timedStart(barrier.enter).use { getResult =>
        IO.sleep(1.second) >> barrier.exit >> getResult
      }
    }

    runTC(prog).assertEquals(1.second)
  }

  test("enter is unblocked by exit the right amount of times") {
    val prog = Barrier[IO](3)
      .use { barrier =>
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
      .use { barrier =>
        fillBarrier(barrier) >> (barrier.enter, barrier.enter).parTupled
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
      barrier.enter >> barrier.enter >> barrier.exit >> barrier.exit >> barrier.exit
    }

    runTC(prog).intercept[Throwable]
  }

  test("Cannot construct a barrier with a 0 limit") {
    Barrier[IO](0).use_.intercept[Throwable]
  }

  test("Cannot change a limit to zero") {
    Barrier[IO](0).use(_.limit.set(0)).intercept[Throwable]
  }

  test("A blocked enter is immediately unblocked if the limit is expanded") {
    val prog = Barrier[IO](3)
      .use { barrier =>
        fillBarrier(barrier) >>
          timedStart(barrier.enter >> barrier.enter).use { getResult =>
            IO.sleep(1.second) >>
              barrier.limit.set(4) >>
              IO.sleep(1.second) >>
              barrier.limit.set(5) >>
              getResult
          }
      }

    runTC(prog).assertEquals(2.seconds)
  }

  test("A blocked enter is not unblocked prematurely if the limit is shrunk") {
    val prog = Barrier[IO](3)
      .use { barrier =>
        fillBarrier(barrier) >>
          timedStart(barrier.enter).use { getResult =>
            barrier.limit.set(2) >>
              IO.sleep(1.second) >>
              barrier.exit >>
              getResult
          }
      }

    runTC(prog).intercept[NonTerminationException]
  }

  test("Sequential limit changes".only) {
    val prog = Barrier[IO](3)
      .use { barrier =>
        // fillBarrier(barrier) >>
        barrier.enter >> barrier.enter >> barrier.enter >>
          timedStart(barrier.enter).use { _ =>
            barrier.limit.set(5) >>
              barrier.enter >>
              barrier.limit.set(
                2
              ) >> // this returns but the change hasn't propagated yet, so the rest of the code executes at limit: 5 and (incorrectly terminates)
              barrier.exit >>
              barrier.exit >>
              barrier.enter
          }
      }

    runTC(prog).intercept[NonTerminationException]
  }

  // running idle, running: 1, limit: 3
  // running idle, running: 2, limit: 3
  // running idle, running: 3, limit: 3
  // running wait, running: 3, limit: 3
  // limit change wakeup, running: 3, limit 5
  // running idle, running: 4, limit: 5
  // running idle, running: 5, limit: 5
  // exit wakeup, running: 4, limit 5
  // exit wakeup, running: 3, limit 5
  // running idle, running: 4, limit: 5

  // test("Sequential limit changes".only) {
  //   val prog = Barrier[IO](3)
  //     .use { barrier =>
  //       IO.sleep(1.second) >> barrier.limit.set(4) >> barrier.limit.set(5) >> IO
  //         .sleep(1.second)
  //     }

  //   runTC(prog)
  // }

}
