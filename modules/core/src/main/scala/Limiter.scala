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
import cats.effect.syntax.all._
import fs2._
import scala.concurrent.duration._

import upperbound.internal.{Queue, Task}

/** A purely functional, interval based rate limiter.
  */
trait Limiter[F[_]] {

  /**
    * Submits `job` to the [[Limiter]] and waits until a result is available.
    *
    * `await` is designed to be called concurrently: every call submits a
    * job, and they are started at regular intervals up to a maximum
    * number of concurrent jobs, based on the parameters you specify when
    * creating the [[Limiter]].
    *
    * In case of failure, the returned `F[A]` will fail with the same
    * error `job` failed with.
    * It can also fail with a [[LimitReachedException]] if the number of
    * enqueued jobs is past the limit you specify when creating the
    * [[Limiter]].
    *
    * Cancelation semantics are respected, and cancelling the returned
    * `F[A]` will also cancel the execution of `job`.
    * Two scenarios are possible: if cancelation is triggered whilst `job`
    * is still queued up for execution, `job` will never be executed and the
    * rate of the [[Limiter]] won't be affected.
    * If instead cancelation is triggered while `job` is running, `job`
    * will be interrupted, but that slot will be considered used and the
    * next job will only be executed after the required time interval has
    * elapsed.
    *
    * `await` allows to submit jobs at different priorities, so that
    * higher priority jobs can be executed before lower priority ones. A
    * higher number means a higher priority. The default is 0.
    *
    * Note that any blocking performed by this method is only semantic, no
    * actual threads are blocked by the implementation.
    */
  def await[A](
      job: F[A],
      priority: Int = 0
  ): F[A]

  /** Obtains a snapshot of the current number of jobs waiting to be
    * executed. May be out of date the instant after it is
    * retrieved.
    */
  def pending: F[Int]
}

object Limiter {

  /** Signals that the number of jobs waiting to be executed has
    * reached the maximum allowed number. See [[Limiter.start]]
    */
  case class LimitReachedException() extends Exception

  /** Summoner */
  def apply[F[_]](implicit l: Limiter[F]): Limiter[F] = l

  /** Creates a new [[Limiter]] and starts processing the jobs
    * submitted so it, which are started at a rate no higher
    * than `maxRate`.
    *
    * Additionally, `n` allows you to place a bound on the maximum
    * number of jobs allowed to queue up while waiting for
    * execution. Once this number is reached, the `F` returned by
    * any call to the [[Limiter]] will immediately fail with a
    * [[LimitReachedException]], so that you can in turn signal for
    * backpressure downstream. Processing restarts as soon as the
    * number of jobs waiting goes below `n` again.
    * `n` defaults to `Int.MaxValue` if not specified. Must be > 0.
    */
  def start[F[_]: Temporal](
      minInterval: FiniteDuration,
      maxQueued: Int = Int.MaxValue,
      maxConcurrent: Int = Int.MaxValue
  ): Resource[F, Limiter[F]] = {
    assert(maxQueued > 0, s"n must be > 0, was $maxQueued")
    assert(maxConcurrent > 0, s"n must be > 0, was $maxConcurrent")

    Resource.eval(Queue[F, F[Unit]](maxQueued)).flatMap { queue =>
      val limiter = new Limiter[F] {
        def await[A](
            job: F[A],
            priority: Int = 0
        ): F[A] =
          Task.create(job).flatMap { task =>
            queue
              .enqueue(task.executable, priority)
              .flatMap { id =>
                val propagateCancelation =
                  queue.delete(id).flatMap { deleted =>
                    // task has already been dequeued and running
                    task.cancel.whenA(!deleted)
                  }

                task.awaitResult.onCancel(propagateCancelation)
              }
          }

        def pending: F[Int] = queue.size
      }

      // we want a fixed delay rather than fixed rate, so that when
      // waking up after waiting for `maxConcurrent` to lower, there are
      // no bursts
      val executor: Stream[F, Unit] =
        queue.dequeueAll
          .zipLeft(Stream.fixedDelay(minInterval))
          .mapAsyncUnordered(maxConcurrent)(task => task)

      Stream
        .emit(limiter)
        .concurrently(executor)
        .compile
        .resource
        .lastOrError
    }
  }

  /** Creates a no-op [[Limiter]], with no rate limiting and a synchronous
    * `submit` method. `pending` is always zero.
    * `interval` is set to zero and changes to it have no effect.
    */
  def noOp[F[_]: Applicative]: Limiter[F] =
    new Limiter[F] {
      def await[A](job: F[A], priority: Int): F[A] = job
      def pending: F[Int] = 0.pure[F]
    }
}
