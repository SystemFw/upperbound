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

import cats.effect.IO
import cats.syntax.all._
import scala.concurrent.duration._

import upperbound.internal.Timer

import cats.effect.testkit.TestControl

class TimerSuite extends BaseSuite {
  def newTimer(interval: FiniteDuration): IO[(Timer[IO], FiniteDuration)] =
    Timer[IO](interval).product(IO.monotonic)

  def elapsedSince(t0: FiniteDuration) =
    IO.monotonic.map(t => t - t0)

  def setup(interval: FiniteDuration): IO[(Timer[IO], IO[FiniteDuration])] =
    newTimer(interval).flatMap { case (timer, t0) =>
      (timer.sleep >> elapsedSince(t0)).start
        .map(_.joinWithNever)
        .tupleLeft(timer)
    }

  def run[A](io: IO[A]) = TestControl.executeEmbed(io)

  test("behaves like a normal clock if never reset") {
    val prog = setup(1.second).flatMap(_._2)

    run(prog).assertEquals(1.seconds)
  }

  test("sequential resets") {
    val prog = newTimer(1.second).flatMap { case (timer, t0) =>
      timer.sleep >>
        elapsedSince(t0).mproduct { t1 =>
          timer.interval.update(_ + 1.second) >>
            timer.sleep >>
            elapsedSince(t1)
        }
    }

    run(prog).assertEquals((1.second, 2.seconds))
  }

  test("reset while sleeping, interval increased") {
    val prog = setup(2.seconds).flatMap { case (timer, getResult) =>
      IO.sleep(1.second) >>
        timer.interval.set(3.seconds) >>
        getResult
    }

    run(prog).assertEquals(3.seconds)
  }

  test("reset while sleeping, interval decreased but still in the future") {
    val prog = setup(5.seconds).flatMap { case (timer, getResult) =>
      IO.sleep(1.second) >>
        timer.interval.set(3.seconds) >>
        getResult
    }

    run(prog).assertEquals(3.seconds)
  }

  test("reset while sleeping, interval decreased and has already elapsed") {
    val prog = setup(5.seconds).flatMap { case (timer, getResult) =>
      IO.sleep(2.second) >>
        timer.interval.set(1.seconds) >>
        getResult
    }

    run(prog).assertEquals(2.seconds)
  }

  test("multiple resets while sleeping, latest wins") {
    val prog = setup(10.seconds).flatMap { case (timer, getResult) =>
      IO.sleep(1.second) >>
        timer.interval.set(15.seconds) >>
        IO.sleep(3.seconds) >>
        timer.interval.set(8.seconds) >>
        IO.sleep(2.seconds) >>
        timer.interval.set(4.seconds) >>
        getResult
    }

    run(prog).assertEquals(6.seconds)
  }
}
