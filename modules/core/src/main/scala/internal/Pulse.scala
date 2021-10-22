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

import fs2._
import cats.effect._
import cats.effect.implicits._
import cats.effect.std.Supervisor
import fs2.concurrent.SignallingRef
import cats.syntax.all._
import scala.concurrent.duration._
import java.util.ConcurrentModificationException

// single consumer waiting, the sleeping is pull based
// interval can be changed my multiple fibers
// changes take effect asap
// changing interval, various cases
// idle: do nothing
// while waiting:
// - add time, don't complete
// - reduce time, complete (cancel, don't start new one)
// - reduce time, don't complete (cancel, start new one)
trait Pulse[F[_]] {
  def changeInterval(f: FiniteDuration => FiniteDuration): F[Unit]
  def sleep: F[Unit]
}
object Pulse {
  def apply[F[_]: Temporal](initialInterval: FiniteDuration) = {
    val F = Temporal[F]
    SignallingRef[F, FiniteDuration](initialInterval).map { interval =>
      new Pulse[F] {
        def changeInterval(f: FiniteDuration => FiniteDuration): F[Unit] =
          interval.update(f)

        def sleep: F[Unit] =
          F.monotonic.flatMap { start =>
            interval.discrete
              .switchMap { interval =>
                val action =
                  F.monotonic.flatMap { now =>
                    val elapsed = now - start
                    val toSleep = interval - start
                    F.sleep(toSleep).whenA(toSleep > 0.nanos)
                  }
                Stream.eval(action)
              }
              .take(1) // as soon as one inner stream completes, I'm done?
              .compile
              .drain
          }
      }
    }
  }
}
