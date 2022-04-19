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

import cats._
import cats.syntax.all._
import cats.effect._
import cats.effect.implicits._
import cats.effect.std.Supervisor
import scala.concurrent.duration._

import upperbound.internal.{Queue, Task, Barrier, Timer}

/** A purely functional, interval based rate limiter.
  */
trait Limiter[F[_]] {

  /** Submits `job` to the [[Limiter]] and waits until a result is available.
    *
    * `submit` is designed to be called concurrently: every call submits a job,
    * and they are started at regular intervals up to a maximum number of
    * concurrent jobs, based on the parameters you specify when creating the
    * [[Limiter]].
    *
    * In case of failure, the returned `F[A]` will fail with the same error
    * `job` failed with. Note that in **upperbound** no errors are thrown if a
    * job is rate limited, it simply waits to be executed in a queue. `submit`
    * can however fail with a [[LimitReachedException]] if the number of
    * enqueued jobs is past the limit you specify when creating the [[Limiter]].
    *
    * Cancelation semantics are respected, and cancelling the returned `F[A]`
    * will also cancel the execution of `job`. Two scenarios are possible: if
    * cancelation is triggered whilst `job` is still queued up for execution,
    * `job` will never be executed and the rate of the [[Limiter]] won't be
    * affected. If instead cancelation is triggered while `job` is running,
    * `job` will be interrupted, but that slot will be considered used and the
    * next job will only be executed after the required time interval has
    * elapsed.
    *
    * The `priority` parameter allows you to submit jobs at different
    * priorities, so that higher priority jobs can be executed before lower
    * priority ones. A higher number means a higher priority. The default is 0.
    *
    * Note that any blocking performed by this method is only semantic, no
    * actual threads are blocked by the implementation.
    */
  def submit[A](
      job: F[A],
      priority: Int = 0
  ): F[A]

  /** Obtains a snapshot of the current number of jobs waiting to be executed.
    * May be out of date the instant after it is retrieved.
    */
  def pending: F[Int]

  /** Obtains a snapshot of the current interval.
    *
    * May be out of date the instant after it is retrieved if a call to
    * `setMinInterval`. or `updateMinInterval` happens.
    */
  def minInterval: F[FiniteDuration]

  /** Resets the current interval.
    *
    * If the interval changes while the Limiter is sleeping between tasks, the
    * duration of the sleep is adjusted on the fly, taking into account any
    * elapsed time. This might mean waking up instantly if the entire new
    * interval has already elapsed.
    */
  def setMinInterval(newMinInterval: FiniteDuration): F[Unit]

  /** Updates the current interval.
    *
    * If the interval changes while the Limiter is sleeping between tasks, the
    * duration of the sleep is adjusted on the fly, taking into account any
    * elapsed time. This might mean waking up instantly if the entire new
    * interval has already elapsed.
    */
  def updateMinInterval(update: FiniteDuration => FiniteDuration): F[Unit]

  /** Obtains a snapshot of the current concurrency limit.
    *
    * May be out of date the instant after it is retrieved if a call to
    * `setMaxConcurrent` or `updateMaxConcurrent` happens.
    */
  def maxConcurrent: F[Int]

  /** Resets the task concurrency limit.
    *
    * If `maxConcurrent` gets changed while the Limiter is already blocked
    * waiting for some tasks to finish, the Limiter will then be unblocked as
    * soon as the number of running tasks goes below `newMaxConcurrent`.
    *
    * Note however that if the concurrency limit shrinks the Limiter will not
    * try to interrupt tasks that are already running, so for some time it might
    * be that `runningTasks > maxConcurrent`.
    */
  def setMaxConcurrent(newMaxConcurrent: Int): F[Unit]

  /** Updates the task concurrency limit.
    *
    * If `maxConcurrent` gets changed while the Limiter is already blocked
    * waiting for some tasks to finish, the Limiter will then be unblocked as
    * soon as the number of running tasks goes below `newMaxConcurrent`.
    *
    * Note however that if the concurrency limit shrinks the Limiter will not
    * try to interrupt tasks that are already running, so for some time it might
    * be that `runningTasks > maxConcurrent`.
    */
  def updateMaxConcurrent(update: Int => Int): F[Unit]
}

object Limiter {

  /** Signals that the number of jobs waiting to be executed has reached the
    * maximum allowed number. See [[Limiter.start]]
    */
  case class LimitReachedException() extends Exception

  /** Summoner */
  def apply[F[_]](implicit l: Limiter[F]): Limiter[F] = l

  /** Creates a new [[Limiter]] and starts processing submitted jobs at a
    * regular rate, in priority order.
    *
    * It's recommended to use an explicit type ascription such as
    * `Limiter.start[IO]` or `Limiter.start[F]` when calling `start`, to avoid
    * type inference issues.
    *
    * In order to avoid bursts, jobs submitted to the [[Limiter]] are started at
    * regular intervals, as specified by the `minInterval` parameter. You can
    * pass `minInterval` as a `FiniteDuration`, or using **upperbound**'s rate
    * syntax (note the underscore in the `rate` import):
    * {{{
    * import upperbound._
    * import upperbound.syntax.rate._
    * import scala.concurrent.duration._
    * import cats.effect._
    *
    * Limiter.start[IO](minInterval = 1.second)
    *
    * // or
    *
    * Limiter.start[IO](minInterval = 60 every 1.minute)
    * }}}
    *
    * If the duration of some jobs is longer than `minInterval`, multiple jobs
    * will be started concurrently. You can limit the amount of concurrency with
    * the `maxConcurrent` parameter: upon reaching `maxConcurrent` running jobs,
    * the [[Limiter]] will stop pulling new ones until old ones terminate. Note
    * that this means that the specified interval between jobs is indeed a
    * _minimum_ interval, and it could be longer if the `maxConcurrent` bound
    * gets hit. The default is no limit.
    *
    * Jobs that are waiting to be executed are queued up in memory, and you can
    * control the maximum size of this queue with the `maxQueued` parameter.
    * Once this number is reached, submitting new jobs will immediately fail
    * with a [[LimitReachedException]], so that you can in turn signal for
    * backpressure downstream. Submission is allowed again as soon as the number
    * of jobs waiting goes below `maxQueued`. `maxQueued` must be > 0. The
    * default is no limit.
    *
    * [[Limiter]] accepts jobs at different priorities, with jobs at a higher
    * priority being executed before lower priority ones.
    *
    * Jobs that fail or are interrupted do not affect processing.
    *
    * The lifetime of a [[Limiter]] is bound by the `Resource` returned by this
    * method: make sure all the places that need limiting at the same rate share
    * the same limiter by calling `use` on the returned `Resource` once, and
    * passing the resulting [[Limiter]] as an argument whenever needed. When the
    * `Resource` is finalised, all pending and running jobs are canceled. All
    * outstanding calls to `submit` are also canceled.
    */
  def start[F[_]: Temporal](
      minInterval: FiniteDuration,
      maxConcurrent: Int = Int.MaxValue,
      maxQueued: Int = Int.MaxValue
  ): Resource[F, Limiter[F]] = {
    assert(maxQueued > 0, s"maxQueued must be > 0, was $maxQueued")
    assert(maxConcurrent > 0, s"maxConcurrent must be > 0, was $maxConcurrent")

    val F = Temporal[F]

    val resources =
      (
        Resource.eval(Queue[F, F[Unit]](maxQueued)),
        Resource.eval(Barrier[F](maxConcurrent)),
        Resource.eval(Timer[F](minInterval)),
        Supervisor[F]
      ).tupled

    resources.flatMap { case (queue, barrier, timer, supervisor) =>
      val limiter = new Limiter[F] {
        def submit[A](
            job: F[A],
            priority: Int = 0
        ): F[A] = F.uncancelable { poll =>
          Task.create(job).flatMap { task =>
            queue
              .enqueue(task.executable, priority)
              .flatMap { id =>
                val propagateCancelation =
                  queue.delete(id).flatMap { deleted =>
                    // task has already been dequeued and running
                    task.cancel.whenA(!deleted)
                  }

                poll(task.awaitResult).onCancel(propagateCancelation)
              }
          }
        }

        def pending: F[Int] = queue.size

        def minInterval: F[FiniteDuration] =
          timer.interval
        def setMinInterval(newMinInterval: FiniteDuration): F[Unit] =
          timer.setInterval(newMinInterval)
        def updateMinInterval(
            update: FiniteDuration => FiniteDuration
        ): F[Unit] =
          timer.updateInterval(update)

        def maxConcurrent: F[Int] =
          barrier.limit
        def setMaxConcurrent(newMaxConcurrent: Int): F[Unit] =
          barrier.setLimit(newMaxConcurrent)
        def updateMaxConcurrent(update: Int => Int): F[Unit] =
          barrier.updateLimit(update)
      }

      /* this only gets cancelled if the limiter needs shutting down,
       * no interruption safety needed except canceling running
       * fibers, which happens automatically through supervisor
       */
      def executor: F[Unit] = {
        def go(fa: F[Unit]): F[Unit] = {
          /* F.unit to make sure we exit the barrier even if fa is
           * canceled before getting executed
           */
          val job = (F.unit >> fa).guarantee(barrier.exit)

          supervisor.supervise(job) >>
            // (timer.sleep, barrier.enter).tupled >>
            (timer.sleep, barrier.enter).parTupled >>
            queue.dequeue.flatMap(go)
        }

        /* execute fhe first task immediately */
        barrier.enter >> queue.dequeue.flatMap(go)
      }

      executor.background.as(limiter)
    }
  }

  /** Creates a no-op [[Limiter]], with no rate limiting and a synchronous
    * `submit` method. `pending` is always zero. `interval` is set to zero and
    * changes to it have no effect.
    */
  def noOp[F[_]: Applicative]: Limiter[F] =
    new Limiter[F] {
      def submit[A](job: F[A], priority: Int): F[A] = job
      def pending: F[Int] = 0.pure[F]
      def maxConcurrent: F[Int] = Int.MaxValue.pure[F]
      def minInterval: F[FiniteDuration] = 0.seconds.pure[F]
      def setMaxConcurrent(newMaxConcurrent: Int): F[Unit] = ().pure[F]
      def setMinInterval(newMinInterval: FiniteDuration): F[Unit] = ().pure[F]
      def updateMaxConcurrent(update: Int => Int): F[Unit] = ().pure[F]
      def updateMinInterval(update: FiniteDuration => FiniteDuration): F[Unit] =
        ().pure[F]
    }
}
