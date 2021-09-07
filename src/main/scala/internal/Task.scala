package upperbound
package internal

import cats.effect._
import fs2._
import cats.syntax.all._
import cats.effect.syntax.all._

// This will be used if I follow the implementation strategy
// of enqueing tasks directly, and having Limiter's background stream
// execute them.
// Main advantage is that I can use parJoin to limit concurrent execution of tasks _and_
// avoid pulling more tasks until there is space for them to run.
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
  // ok other issue here: if this gets canceled before `task` runs, `result`
  // will not be set to canceled (guaranteeCase doesn't run), so if we then wait
  // on result in waitResult's onCancel logic, we will deadlock, we can probably
  // set result to canceled again after stopSignal.get with >>
  // the other idea is to use racePair or raceOutcome as the sole combinator
  /**
    * Packages `task` for later execution.
    * Cannot fail.
    * Propagates result to `waitResult`.
    * Cancels itself if `waitResult` is canceled.
    */
  def executable: F[Unit] =
    task
      .guaranteeCase(result.complete(_).void)
      .redeem(_ => (), _ => ())
      .race {
        stopSignal.get >>
          // this handles the corner case where `task` was canceled before its
          // finaliser was installed, to make sure `result` is always complete.
          // If `task` has already populated `result`, this becomes a no-op
          result.complete(Outcome.canceled).void
      }
      .void

  // this might be easier to reason about than the state space of the previous version
  // especially as I add potential checking for cancelationRequested
  def executable2: F[Unit] =
    F.uncancelable { _ =>
      // `task` and `get` are started on different fibers:
      // we don't need to `poll` them if we want to cancel them from here
      F.racePair(task, stopSignal.get)
        .flatMap {
          case Left((taskResult, waitForStopSignal)) =>
            waitForStopSignal.cancel >> result.complete(taskResult)
          case Right((runningTask, _)) =>
            runningTask.cancel >> runningTask.join.flatMap(result.complete(_))
        }
        .void
    }

  def cancelationRequested: F[Boolean] =
    stopSignal.tryGet.map(_.isDefined)

  // Ok, interesting question, should waitResult backpressure on canceling `task`?
  // If `task` is running, yes, but if task is still queued, we probably don't want
  // to wait until it gets dequeued. This affects whether after triggering stopSignal we want
  // to wait on result again (for a Canceled outcome) or not
  // How to detect that is an open question, since
  // the whole thing is based on the premise that we can't delete a
  // given task from the priority queue at will.
  // Maybe `executable` should set a flag, which will indicate it's been dequeued and about to execute:
  // mapAsync will make sure that it can never be dequeued if there is no space to run.
  // need to do a race condition analysis though.
  /**
    * Completes when `task` does.
    * Canceling `waitResult` cancels `task`, (respecting
    * cancelation backpressure? see question above)
    * Cancels itself if `task` gets canceled externally. (is this absolutely necessary? generally not supported)
    */
  def waitResult: F[A] =
    result.get
      .onCancel(stopSignal.complete(()).void)
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
