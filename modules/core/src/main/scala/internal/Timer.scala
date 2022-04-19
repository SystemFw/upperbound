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

/** Resettable timer. */
private[upperbound] trait Timer[F[_]] {

  /** Obtains a snapshot of the current interval. May be out of date the instant
    * after it is retrieved.
    */
  def interval: F[FiniteDuration]

  /** Resets the current interval */
  def setInterval(t: FiniteDuration): F[Unit]

  /** Updates the current interval */
  def updateInterval(f: FiniteDuration => FiniteDuration): F[Unit]

  /** Sleeps for the duration of the current interval.
    *
    * If the interval changes while a sleep is happening, the duration of the
    * sleep is adjusted on the fly, taking into account any elapsed time. This
    * might mean waking up instantly if the entire new interval has already
    * elapsed.
    */
  def sleep: F[Unit]
}
private[upperbound] object Timer {
  def apply[F[_]: Temporal](initialInterval: FiniteDuration) = {
    val F = Temporal[F]
    SignallingRef[F, FiniteDuration](initialInterval).map { intervalState =>
      new Timer[F] {
        def interval: F[FiniteDuration] =
          intervalState.get

        def setInterval(t: FiniteDuration): F[Unit] =
          intervalState.set(t)

        def updateInterval(f: FiniteDuration => FiniteDuration): F[Unit] =
          intervalState.update(f)

        def sleep: F[Unit] =
          F.monotonic.flatMap { start =>
            intervalState.discrete
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
