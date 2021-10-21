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

// single consumer waiting
// interval can be changed my multiple fibers
// changes take effect asap
trait Pulse[F[_]] {
  def changeInterval(f: FiniteDuration => FiniteDuration): F[Unit]
  def await: F[Unit]
}
object Pulse {
  def apply[F[_]: Temporal](
      initialInterval: FiniteDuration,
      supervisor: Supervisor[F]
  ) = {
    val F = Temporal[F]

    Resource
      .eval {
        (
          F.ref(Option.empty[Deferred[F, Unit]]),
          F.ref(0.nanos),
          SignallingRef[F, FiniteDuration](initialInterval)
        ).tupled
      }
      .flatMap { case (waiting, elapsed, interval) =>
        val singleConsumerViolation =
          new ConcurrentModificationException(
            "Only one fiber can block on the pulse at a time"
          )

        val pulse = new Pulse[F] {
          def changeInterval(f: FiniteDuration => FiniteDuration): F[Unit] =
            interval.update(f)

          def await: F[Unit] =
            F.uncancelable { poll =>
              F.deferred[Unit].flatMap { wait =>
                waiting.modify {
                  case s @ Some(_) =>
                    s -> F.raiseError[Unit](singleConsumerViolation)
                  case None =>
                    Some(wait) -> poll(wait.get).onCancel {
                      // race on elapsed with worker
                      // might set it to 0 here and then worker records elapsed again
                      // will probably need unique ids to separate "phases"
                      waiting.set(None) >> elapsed.set(0.nanos)
                    }
                }.flatten
              }
            }
        }

        val wakeUp =
          waiting
            .getAndSet(None)
            .flatMap(_.traverse_(_.complete(())))

        val worker =
          interval.discrete
            .switchMap { newInterval =>
              Stream.eval {
                F.uncancelable { poll =>
                  elapsed.getAndSet(0.nanos).flatMap { elapsedTime =>
                    val toSleep = newInterval - elapsedTime
                    if (toSleep <= 0.nanos) wakeUp
                    else
                      F.monotonic.flatMap { startTime =>
                        poll(F.sleep(toSleep)).onCancel {
                          F.monotonic.map(startTime - _).flatMap(elapsed.set)
                        } >> wakeUp
                      }
                  }
                }
              }
            }

        Stream.emit(pulse).concurrently(worker).compile.resource.lastOrError
      }
  }

  // // complex without correlation ids
  // Queue[F, Unit]
  // Queue[F, FiniteDuration]
  // Ref[F, (StartTime, Interval)]

  // sealed trait State
  // case class Idle(interval: FiniteDuration) extends State
  // case class Awaiting(
  //   interval: FiniteDuration,
  //   startTime: FiniteDuration,
  //   cancelSleeper: Deferred[F, F[Unit]],
  //   wakeUp: Deferred[F, Unit]
  // ) extends State

  // val singleConsumerViolation =
  //   new ConcurrentModificationException(
  //     "Only one fiber can block on the pulse at a time"
  //   )

  // F.ref(Idle(initialInterval)).map { state =>
  //   new Pulse[F] {
  //     def await: F[Unit] =
  //       F.uncancelable { poll =>
  //         ( Deferred[F, Unit],
  //           Deferred[F, F[Unit]],
  //           F.monotonic
  //         ).tupled.flatMap {
  //           case (wait, cancelSleeper, now) =>
  //             state.modify {
  //               case s @ Awaiting(_, _, _, _) =>
  //                 s -> F.raiseError[Unit](singleConsumerViolation)
  //               case Idle(interval) =>
  //                 val newState = Awaiting(interval, now, cancelSleeper, wait)

  //                 val startSleeper =
  //                   supervisor
  //                     .supervise(F.sleep(interval))
  //                     .flatMap(fiber => cancelSleeper.complete(fiber.cancel))

  //                 val wait =
  //                   poll(wait.get).onCancel {
  //                     state.modify {
  //                       case s @ Idle(_) => s
  //                       case Awaiting(interval, _, cancelSleeper, _) =>
  //                         Idle(interval) -> cancelSleeper.get.flatten
  //                     }.flatten.uncancelable
  //                   }

  //             }.flatten
  //         }
  //       }
  //   }
  // }

  // what if:
  // use background so that cancelling wait cancels sleeper
  // wait gets cancelled from outside, done
  // when pulse wants to change wait time, it completes wait with either done or extra time,
  // wait then does the necessary extra sleeping

  // sealed trait State
  // case class Idle(interval: FiniteDuration) extends State
  // case class Awaiting(
  //   interval: FiniteDuration,
  //   startTime: FiniteDuration,
  //   cancelSleeper: Deferred[F, F[Unit]],
  //   wakeUp: Deferred[F, Unit]
  // ) extends State

//     Ref(elapsed)
//     SignallingRef(interval)
//     wakeUp Ref[Option[Deferred]]
// //
//       .discrete
//         .switchMap { newInterval =>
//           if (newInterval == 0) F.unit
//           else uncancelable { poll =>
//             elapsed.getAndSet(0)
//             val toSleep = interval - elapsed
//             if (toSleep < 0) wakeup
//             else
//               clock.monotonic -> start
//             poll(sleep(newInterval)).onCancel(
//               monotonic -> now
//                 elapsed.set(start - now)
//             ) >>
//             wakeUpComplete
//           }
//         }

//     wait = setDeferred.onCancel(setNone >> setElapsed(0))
//     changeInterval = interval.update

//  }
}
