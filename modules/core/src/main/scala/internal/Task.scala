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
import cats.effect.syntax.all._

/** Packages `fa` to be queued for later execution,
  * and controls propagation of the result from executor to client,
  * and propagation of cancelation from client to executor.
  */
private[upperbound] case class Task[F[_]: Concurrent, A](
    task: F[A],
    result: Deferred[F, Outcome[F, Throwable, A]],
    stopSignal: Deferred[F, Unit]
) {
  private val F = Concurrent[F]

  /** Packages `task` for later execution.
    * Cannot fail.
    * Propagates result to `waitResult`, including cancelation.
    */
  def executable: F[Unit] =
    F.uncancelable { poll =>
      /* `poll(..).onCancel` handles direct cancelation of `executable`,
       * which happens when Limiter itself gets shutdown.
       *
       * `racePair(..).flatMap` propagates cancelation triggered by
       * `cancel`from the client to `executable`.
       */
      poll(F.racePair(task, stopSignal.get))
        .onCancel(result.complete(Outcome.canceled).void)
        .flatMap {
          case Left((taskResult, waitForStopSignal)) =>
            waitForStopSignal.cancel >> result.complete(taskResult)
          case Right((runningTask, _)) =>
            runningTask.cancel >> runningTask.join.flatMap(result.complete(_))
        }
        .void
    }

  /** Cancels the running task, backpressuring on finalisers
    */
  def cancel: F[Unit] =
    (stopSignal.complete(()) >> result.get.void).uncancelable

  /** Completes when `executable` does, canceling itself if
    * `executable` gets canceled.
    * However, canceling `waitResult` does not cancel `executable`
    * automatically, `cancel` needs to be called manually.
    */
  def awaitResult: F[A] =
    result.get
      .flatMap {
        _.embed(onCancel = F.canceled >> F.never)
      }
}

private[upperbound] object Task {
  def create[F[_]: Concurrent, A](fa: F[A]): F[Task[F, A]] =
    (
      Deferred[F, Outcome[F, Throwable, A]],
      Deferred[F, Unit]
    ).mapN(Task(fa, _, _))
}
