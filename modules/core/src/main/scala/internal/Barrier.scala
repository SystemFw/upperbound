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

import java.util.ConcurrentModificationException

// single process acquiring
// ^^ means I can get away with a single deferred?
//
// multiple processes releasing
// acquire blocks when limit reached
// limit changes dynamically!
// it can shrink
// it can grow

trait Barrier[F[_]] {
  def changeLimit(update: Int => Int): F[Unit]
  def enter: F[Unit]
  def exit: F[Unit]
}
object Barrier {
  def apply[F[_]: Concurrent](initialLimit: Int): F[Barrier[F]] = {
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

    F.ref(State(0, initialLimit, None)).map { state =>
      new Barrier[F] {
        def enter: F[Unit] =
          F.deferred[Unit].flatMap { wait =>
            state.modify {
              case s @ State(_, _, Some(waiting @ _)) =>
                s -> F.raiseError[Unit](singleProducerViolation)
              case State(running, limit, None) =>
                if (running < limit)
                  State(running + 1, limit, None) -> F.unit
                else State(running, limit, Some(wait)) -> (wait.get >> enter)
            }.flatten
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

        def changeLimit(update: Int => Int): F[Unit] =
          state
            .modify { case State(running, limit, waiting) =>
              val newLimit = update(limit)
              if (running < newLimit)
                State(running, newLimit, None) -> wakeUp(waiting)
              else State(running, newLimit, waiting) -> F.unit
            }
            .flatten
            .uncancelable
      }
    }

  }
}
