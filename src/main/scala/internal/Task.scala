package upperbound
package internal

import cats.effect._
import fs2._
import cats.syntax.all._
import cats.effect.syntax.all._

/**
  * Packages `fa` to be queued for later execution,
  * and controls propagation of the result from executor to client,
  * and propagation of cancelation from client to executor.
  */
private[upperbound] case class Task[F[_]: Concurrent, A](
    task: F[A],
    result: Deferred[F, Outcome[F, Throwable, A]],
    stopSignal: Deferred[F, Unit]
) {
  private val F = Concurrent[F]

  /**
    * Packages `task` for later execution.
    * Cannot fail.
    * Propagates result to `waitResult`.
    */
  def executable: F[Unit] =
    F.uncancelable { _ =>
      // `task` and `get` are started on different fibers:
      // we don't need to `poll` them if we want to cancel them from here
      //
      // TODO should we `poll`? in case Limiter itself gets interrupted
      F.racePair(task, stopSignal.get)
        .flatMap {
          case Left((taskResult, waitForStopSignal)) =>
            waitForStopSignal.cancel >> result.complete(taskResult)
          case Right((runningTask, _)) =>
            runningTask.cancel >> runningTask.join.flatMap(result.complete(_))
        }
        .void
    }

  /**
    * Cancels the running task, backpressuring on finalisers
    */
  def cancel: F[Unit] =
    (stopSignal.complete(()) >> result.get.void).uncancelable

  /**
    * Completes when `executable` does, canceling itself if
    * `executable` gets canceled.
    * However, canceling `waitResult` does not cancel `executable`
    * automatically, `cancel` needs to be called manually.
    */
  def waitResult: F[A] =
    result.get
      .flatMap {
        _.embed(onCancel = F.canceled >> F.never)
      }
}
