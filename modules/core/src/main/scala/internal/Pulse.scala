// /*
//  * Copyright (c) 2017 Fabio Labella
//  *
//  * Permission is hereby granted, free of charge, to any person obtaining a copy of
//  * this software and associated documentation files (the "Software"), to deal in
//  * the Software without restriction, including without limitation the rights to
//  * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
//  * the Software, and to permit persons to whom the Software is furnished to do so,
//  * subject to the following conditions:
//  *
//  * The above copyright notice and this permission notice shall be included in all
//  * copies or substantial portions of the Software.
//  *
//  * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
//  * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
//  * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
//  * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
//  * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
//  * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
//  */

// package upperbound
// package internal

// import fs2._
// import cats.effect._
// import cats.effect.implicits._
// import cats.effect.std.Supervisor
// import fs2.concurrent.SignallingRef
// import cats.syntax.all._
// import scala.concurrent.duration._
// import java.util.ConcurrentModificationException

// // single consumer waiting, the sleeping is pull based
// // interval can be changed my multiple fibers
// // changes take effect asap
// // changing interval, various cases
// // idle: do nothing
// // while waiting:
// // - add time, don't complete
// // - reduce time, complete (cancel, don't start new one)
// // - reduce time, don't complete (cancel, start new one)
// trait Pulse[F[_]] {
//   def changeInterval(f: FiniteDuration => FiniteDuration): F[Unit]
//   def await: F[Unit]
// }
// object Pulse {
//   def apply[F[_]: Temporal](
//       initialInterval: FiniteDuration,
//       supervisor: Supervisor[F]
//   ) = {
//     val F = Temporal[F]

//     val singleConsumerViolation =
//       new ConcurrentModificationException(
//         "Only one fiber can block on the pulse at a time"
//       )

//     Resource
//       .eval {
//         (
//           F.ref(Option.empty[Deferred[F, Unit]]),
//           F.ref(0.nanos),
//           SignallingRef[F, FiniteDuration](initialInterval)
//         ).tupled
//       }
//       .flatMap { case (waiting, elapsed, interval) =>
//         val pulse = new Pulse[F] {
//           def changeInterval(f: FiniteDuration => FiniteDuration): F[Unit] =
//             interval.update(f)

//           def await: F[Unit] =
//             F.uncancelable { poll =>
//               F.deferred[Unit].flatMap { wait =>
//                 waiting.modify {
//                   case s @ Some(_) =>
//                     s -> F.raiseError[Unit](singleConsumerViolation)
//                   case None =>
//                     Some(wait) -> poll(wait.get).onCancel {
//                       // race on elapsed with worker
//                       // might set it to 0 here and then worker records elapsed again
//                       // will probably need unique ids to separate "phases"
//                       waiting.set(None) >> elapsed.set(0.nanos)
//                     }
//                 }.flatten
//               }
//             }
//         }

//         val wakeUp =
//           waiting
//             .getAndSet(None)
//             .flatMap(_.traverse_(_.complete(())))

//         val worker =
//           interval.discrete
//             .switchMap { newInterval =>
//               Stream.eval {
//                 F.uncancelable { poll =>
//                   elapsed.getAndSet(0.nanos).flatMap { elapsedTime =>
//                     val toSleep = newInterval - elapsedTime
//                     if (toSleep <= 0.nanos) wakeUp
//                     else
//                       F.monotonic.flatMap { startTime =>
//                         poll(F.sleep(toSleep)).onCancel {
//                           F.monotonic.map(startTime - _).flatMap(elapsed.set)
//                         } >> wakeUp
//                       }
//                   }
//                 }
//               }
//             }

//         Stream.emit(pulse).concurrently(worker).compile.resource.lastOrError
//       }

//     sealed trait State
//     case class Idle(interval: FiniteDuration) extends State
//     case class Awaiting(
//       interval: FiniteDuration,
//       startTime: FiniteDuration,
//       cancelSleeper: F[Unit],
//       wakeUp: Deferred[F, Unit]
//     ) extends State

//     val singleConsumerViolation =
//       new ConcurrentModificationException(
//         "Only one fiber can block on the pulse at a time"
//       )

//     F.ref(Idle(initialInterval)).map { state =>
//       new Pulse[F] {
//         def await: F[Unit] =
//           F.uncancelable { poll =>
//             def go: F[Unit] = 
//               state.access.flatMap { case (state, set) =>
//                 state match {
//                   case Awaiting(_, _, _, _) =>
//                     F.raiseError[Unit](singleConsumerViolation)
//                   case Idle(interval) =>
//                     F.monotonic.flatMap { now =>
//                       F.deferred[Unit].flatMap { waiting =>
//                         supervisor.supervise(F.sleep(interval) >> waiting.complete(())).flatMap { fiber =>
//                           val newState = Awaiting(interval, now, fiber.cancel, waiting)

//                           set(newState).flatMap { success =>
//                             if (!success) fiber.cancel >> go
//                             else poll(waiting.get).onCancel {
//                               state.modify {
//                                 case s @ Idle(_) => s
//                                 case Awaiting(interval, _, cancelSleeper, _) =>
//                                   Idle(interval) -> cancelSleeper
//                               }.flatten
//                             }
//                           }
//                         }
//                       }
//                     }
//                 }

//                 go
//             }
//             ( Deferred[F, Unit],
//               Deferred[F, F[Unit]],
//               F.monotonic
//             ).tupled.flatMap {
//               case (wait, cancelSleeper, now) =>
//                 state.modify {
//                   case s @ Awaiting(_, _, _, _) =>
//                     s -> F.raiseError[Unit](singleConsumerViolation)
//                   case Idle(interval) =>
//                     val newState = Awaiting(interval, now, cancelSleeper, wait)

//                     val startSleeper =
//                       supervisor
//                         .supervise(F.sleep(interval))
//                         .flatMap(fiber => cancelSleeper.complete(fiber.cancel))

//                     val wait =
//                       poll(wait.get).onCancel {
//                         state.modify {
//                           case s @ Idle(_) => s
//                           case Awaiting(interval, _, cancelSleeper, _) =>
//                             Idle(interval) -> cancelSleeper.get.flatten
//                         }.flatten.uncancelable
//                       }

//                 }.flatten
//             }
//           }
//       }
//     }


//   }

//   // // complex without correlation ids
//   // Queue[F, Unit]
//   // Queue[F, FiniteDuration]
//   // Ref[F, (StartTime, Interval)]

//   // sealed trait State
//   // case class Idle(interval: FiniteDuration) extends State
//   // case class Awaiting(
//   //   interval: FiniteDuration,
//   //   startTime: FiniteDuration,
//   //   cancelSleeper: Deferred[F, F[Unit]],
//   //   wakeUp: Deferred[F, Unit]
//   // ) extends State

//   // val singleConsumerViolation =
//   //   new ConcurrentModificationException(
//   //     "Only one fiber can block on the pulse at a time"
//   //   )

//   // F.ref(Idle(initialInterval)).map { state =>
//   //   new Pulse[F] {
//   //     def await: F[Unit] =
//   //       F.uncancelable { poll =>
//   //         ( Deferred[F, Unit],
//   //           Deferred[F, F[Unit]],
//   //           F.monotonic
//   //         ).tupled.flatMap {
//   //           case (wait, cancelSleeper, now) =>
//   //             state.modify {
//   //               case s @ Awaiting(_, _, _, _) =>
//   //                 s -> F.raiseError[Unit](singleConsumerViolation)
//   //               case Idle(interval) =>
//   //                 val newState = Awaiting(interval, now, cancelSleeper, wait)

//   //                 val startSleeper =
//   //                   supervisor
//   //                     .supervise(F.sleep(interval))
//   //                     .flatMap(fiber => cancelSleeper.complete(fiber.cancel))

//   //                 val wait =
//   //                   poll(wait.get).onCancel {
//   //                     state.modify {
//   //                       case s @ Idle(_) => s
//   //                       case Awaiting(interval, _, cancelSleeper, _) =>
//   //                         Idle(interval) -> cancelSleeper.get.flatten
//   //                     }.flatten.uncancelable
//   //                   }

//   //             }.flatten
//   //         }
//   //       }
//   //   }
//   // }

//   // what if:
//   // use background so that cancelling wait cancels sleeper
//   // wait gets cancelled from outside, done
//   // when pulse wants to change wait time, it completes wait with either done or extra time,
//   // wait then does the necessary extra sleeping

//   // sealed trait State
//   // case class Idle(interval: FiniteDuration) extends State
//   // case class Awaiting(
//   //   interval: FiniteDuration,
//   //   startTime: FiniteDuration,
//   //   cancelSleeper: Deferred[F, F[Unit]],
//   //   wakeUp: Deferred[F, Unit]
//   // ) extends State

// //     Ref(elapsed)
// //     SignallingRef(interval)
// //     wakeUp Ref[Option[Deferred]]
// // //
// //       .discrete
// //         .switchMap { newInterval =>
// //           if (newInterval == 0) F.unit
// //           else uncancelable { poll =>
// //             elapsed.getAndSet(0)
// //             val toSleep = interval - elapsed
// //             if (toSleep < 0) wakeup
// //             else
// //               clock.monotonic -> start
// //             poll(sleep(newInterval)).onCancel(
// //               monotonic -> now
// //                 elapsed.set(start - now)
// //             ) >>
// //             wakeUpComplete
// //           }
// //         }

// //     wait = setDeferred.onCancel(setNone >> setElapsed(0))
// //     changeInterval = interval.update

//   //  }


//   add time, don't complete (cancel, start new one, compute time)
//   reduce time, complete (cancel, don't start new one)
//   reduce time, don't complete (cancel, start new one, compute time)

//   instead of using a signal for all the lifetime, what about one per call
//   no fibers, just use signal directly in wait

//   PhaseState
//   Done
//   Awaiting(startTime, newInterval)

//   State
//   Idle(interval)
//   Awaiting(Signal[State])


//   await = state {
//     Awaiting => error
//     Idle(interval) =>
//     newState(newSignal(Awaiting(startTime, target)))

//     signal.switchMap {
//       case Idle(interval) => F.unit
//       case Awaiting(startTime, newInterval) =>
//         val elapsed = now() - startTime
//         val toSleep = newInterval
//     }
//   }

//   await = {

//     state.access.flatMap  { (state, set) =>
//       state match {
//         awaiting => error
//         idle(interval) =>
//         intervalSignal <- initialInterval
//         set { success =>
//           if !success await
//           else {
//             uncancelable {
//               poll {
//                 start <- now() 
//                 intervalSignal
//                   .discrete
//                   .switchMap { interval =>
//                     elapsed = now() - start
//                     toSleep = interval - elapsed
//                     if(toSleep <= 0) None // if it gets canceled before it can emit, fine, latest interval update wins
//                     else sleep(toSleep).as(Some(()))
//                   }.unNoneTerminate
//                   .compile
//                 .drain
//               }.guarantee(state.set(Idle))
//             }
//           }
//         }
//       }
//     }

//     changeInterval = state.modify {
//       case Idle(interval) => 
//     }
//     ok, need to use access
//     intervalSignal <- initialInterval
//     putSignalInStateRef
//     elapsedRef <- now
//   }

//   SignallingRef(interval) { state =>
//     change = state.update(f)

//     await =
//       start = now()
//       state
//         .discrete
//         .switchMap { interval
//           elapsed = now() - start
//           toSleep = interval - elapsed
//           sleep(toSleep).whenA(toSleep > 0)
//         }.take(1) // as soon as one inner stream completes, I'm done?
//         .compile
//         .drain

//         }

    
//   }
// }

// ----------| target
// --|elapsed
// ------|new target

