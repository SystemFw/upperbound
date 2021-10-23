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

import cats.syntax.all._
import fs2.Stream
import cats.effect.Temporal
import fs2.concurrent.SignallingRef
import scala.concurrent.duration._

/** Resettable timer.
  * If the interval gets reset while a sleep is happening, the
  * duration of the sleep is adjusted on the fly, taking into account
  * any elapsed time.
  * This might mean waking up instantly if the entire new interval has
  * already elapsed.
  */
trait Timer[F[_]] {
  def interval: SignallingRef[F, FiniteDuration]
  def sleep: F[Unit]
}
object Timer {
  def apply[F[_]: Temporal](initialInterval: FiniteDuration) = {
    val F = Temporal[F]
    SignallingRef[F, FiniteDuration](initialInterval).map { interval_ =>
      new Timer[F] {
        def interval: SignallingRef[F, FiniteDuration] = interval_
        def sleep: F[Unit] =
          F.monotonic.flatMap { start =>
            interval.discrete
              .switchMap { interval =>
                val action =
                  F.monotonic.flatMap { now =>
                    val elapsed = now - start
                    val toSleep = interval - elapsed

                    F.sleep(toSleep).whenA(toSleep > 0.nanos)
                  }
                Stream.eval(action)
              }
              .take(1)
              .compile
              .drain
          }
      }
    }
  }
}
