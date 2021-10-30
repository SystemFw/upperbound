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

import cats.effect.testkit.TestControl.{executeEmbed => runTC}

class TimerSuite extends BaseSuite {
  def timedSleep(timer: Timer[IO]): Resource[IO, IO[FiniteDuration]] =
    timer.sleep.timed.background
      .map { _.flatMap(_.embedNever).map(_._1) }

  test("behaves like a normal clock if never reset") {
    val prog = Timer[IO](1.second).flatMap(timedSleep(_).use(x => x))

    runTC(prog).assertEquals(1.seconds)
  }

  test("sequential resets") {
    val prog = Timer[IO](1.second).flatMap { timer =>
      (
        timedSleep(timer).use(x => x),
        timer.updateInterval(_ + 1.second),
        timedSleep(timer).use(x => x)
      ).mapN((x, _, y) => (x, y))
    }

    runTC(prog).assertEquals((1.second, 2.seconds))
  }

  test("reset while sleeping, interval increased") {
    val prog = Timer[IO](2.seconds).flatMap { timer =>
      timedSleep(timer).use { getResult =>
        IO.sleep(1.second) >>
          timer.setInterval(3.seconds) >>
          getResult
      }
    }

    runTC(prog).assertEquals(3.seconds)
  }

  test("reset while sleeping, interval decreased but still in the future") {
    val prog = Timer[IO](5.seconds).flatMap { timer =>
      timedSleep(timer).use { getResult =>
        IO.sleep(1.second) >>
          timer.setInterval(3.seconds) >>
          getResult
      }
    }

    runTC(prog).assertEquals(3.seconds)
  }

  test("reset while sleeping, interval decreased and has already elapsed") {
    val prog = Timer[IO](5.seconds).flatMap { timer =>
      timedSleep(timer).use { getResult =>
        IO.sleep(2.second) >>
          timer.setInterval(1.seconds) >>
          getResult
      }
    }

    runTC(prog).assertEquals(2.seconds)
  }

  test("multiple resets while sleeping, latest wins") {
    val prog = Timer[IO](10.seconds).flatMap { timer =>
      timedSleep(timer).use { getResult =>
        IO.sleep(1.second) >>
          timer.setInterval(15.seconds) >>
          IO.sleep(3.seconds) >>
          timer.setInterval(8.seconds) >>
          IO.sleep(2.seconds) >>
          timer.setInterval(4.seconds) >>
          getResult
      }
    }

    runTC(prog).assertEquals(6.seconds)
  }
}
