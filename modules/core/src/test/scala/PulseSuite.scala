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

import upperbound.internal.Pulse

import cats.effect.testkit.TestControl

class PulseSuite extends BaseSuite {
  def newPulse(interval: FiniteDuration): IO[(Pulse[IO], FiniteDuration)] =
    Pulse[IO](interval).product(IO.monotonic)

  def elapsedSince(t0: FiniteDuration) =
    IO.monotonic.map(t => t - t0)

  def setup(interval: FiniteDuration): IO[(Pulse[IO], IO[FiniteDuration])] =
    newPulse(interval).flatMap { case (pulse, t0) =>
      (pulse.sleep >> elapsedSince(t0)).start
        .map(_.joinWithNever)
        .tupleLeft(pulse)
    }

  def run[A](io: IO[A]) = TestControl.executeEmbed(io)

  test("behaves like a normal clock if never reset") {
    val prog = setup(1.second).flatMap(_._2)

    run(prog).assertEquals(1.seconds)
  }

  test("sequential resets") {
    val prog = newPulse(1.second).flatMap { case (pulse, t0) =>
      pulse.sleep >>
        elapsedSince(t0).mproduct { t1 =>
          pulse.changeInterval(_ + 1.second) >>
            pulse.sleep >>
            elapsedSince(t1)
        }
    }

    run(prog).assertEquals((1.second, 2.seconds))
  }

  test("reset while sleeping, interval increased") {
    val prog = setup(2.seconds).flatMap { case (pulse, getResult) =>
      IO.sleep(1.second) >>
        pulse.changeInterval(_ => 3.seconds) >>
        getResult
    }

    run(prog).assertEquals(3.seconds)
  }

  test("reset while sleeping, interval decreased but still in the future") {
    val prog = setup(5.seconds).flatMap { case (pulse, getResult) =>
      IO.sleep(1.second) >>
        pulse.changeInterval(_ => 3.seconds) >>
        getResult
    }

    run(prog).assertEquals(3.seconds)
  }

  test("reset while sleeping, interval decreased and has already elapsed") {
    val prog = setup(5.seconds).flatMap { case (pulse, getResult) =>
      IO.sleep(2.second) >>
        pulse.changeInterval(_ => 1.seconds) >>
        getResult
    }

    run(prog).assertEquals(2.seconds)
  }

  test("multiple resets while sleeping, latest wins") {
    val prog = setup(10.seconds).flatMap { case (pulse, getResult) =>
      IO.sleep(1.second) >>
        pulse.changeInterval(_ => 15.seconds) >>
        IO.sleep(3.seconds) >>
        pulse.changeInterval(_ => 8.seconds) >>
        IO.sleep(2.seconds) >>
        pulse.changeInterval(_ => 4.seconds) >>
        getResult
    }

    run(prog).assertEquals(6.seconds)
  }
}
