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
import cats.effect.implicits._
import cats.syntax.all._
import fs2.concurrent.SignallingRef

import java.util.ConcurrentModificationException

/** A dynamic barrier which is meant to be used in conjunction with a
  * task executor.
  * As such, it assumes there is only a single fiber entering the
  * barrier (the executor), but multiple ones exiting it (the tasks).
  */
trait Barrier[F[_]] {

  /** Controls the current limit on running tasks. */
  def limit: SignallingRef[F, Int]

  /** Tries to enter the barrier, semantically blocking if the number
    * of running task is at or past the limit.
    * The limit can change dynamically while `enter` is blocked, in
    * which case `enter` will be unblocked as soon as the number of
    * running tasks goes beyond the new limit.
    * Note however that the Barrier does not try to interrupt tasks
    * that are already running if the limit dynamically shrinks, so
    * for some time if might be that runningTasks > limit.
    *
    * Fails with a ConcurrentModificatioException if two fibers block
    * on `enter` at the same time.
    */
  def enter: F[Unit]

  /** Called by tasks when exiting the barrier, and will unblock
    * `enter` when the number of running tasks goes beyond the limit.
    * Can be called concurrently.
    */
  def exit: F[Unit]
}
object Barrier {
  def apply[F[_]: Concurrent](initialLimit: Int): Resource[F, Barrier[F]] = {
    val F = Concurrent[F]

    case class State(
        running: Int,
        limit: Int,
        waiting: Option[Deferred[F, Unit]]
    )

    val singleProducerViolation =
      new ConcurrentModificationException(
        "Only one fiber can block on the barrier at a time"
      )

    def wakeUp(waiting: Option[Deferred[F, Unit]]) =
      waiting.traverse_(_.complete(()))

    val resources = Resource.eval {
      (
        F.ref(State(0, initialLimit, None)),
        SignallingRef[F, Int](initialLimit)
      ).tupled
    }

    resources.flatMap { case (state, limit_) =>
      val barrier = new Barrier[F] {
        def enter: F[Unit] =
          F.uncancelable { poll =>
            F.deferred[Unit].flatMap { wait =>
              val waitForChanges = poll(wait.get).onCancel {
                state.update(s => State(s.running, s.limit, None))
              }

              state.modify {
                case s @ State(_, _, Some(waiting @ _)) =>
                  s -> F.raiseError[Unit](singleProducerViolation)
                case State(running, limit, None) =>
                  if (running < limit)
                    State(running + 1, limit, None) -> F.unit
                  else
                    State(
                      running,
                      limit,
                      Some(wait)
                    ) -> (waitForChanges >> enter)
              }.flatten
            }
          }

        def exit: F[Unit] =
          state
            .modify { case State(running, limit, waiting) =>
              val runningNow = running - 1
              if (runningNow < limit)
                State(runningNow, limit, None) -> wakeUp(waiting)
              else State(runningNow, limit, waiting) -> F.unit
            }
            .flatten
            .uncancelable

        val limit = limit_
      }

      val limitChanges =
        barrier.limit.discrete
          .evalMap { newLimit =>
            state
              .modify { case State(running, _, waiting) =>
                if (running < newLimit)
                  State(running, newLimit, None) -> wakeUp(waiting)
                else State(running, newLimit, waiting) -> F.unit
              }
              .flatten
              .uncancelable
          }
          .compile
          .drain

      limitChanges.background.as(barrier)
    }
  }

}
