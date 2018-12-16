package upperbound

import cats._, implicits._
import cats.effect._, concurrent._
import cats.effect.implicits._
import fs2._, fs2.concurrent.SignallingRef

import scala.concurrent.duration._
import queues.Queue

object core {

  /**
    * A purely functional, interval based rate limiter.
    */
  trait Limiter[F[_]] {

    /**
      * Returns an `F[Unit]` which represents the action of submitting
      * `job` to the limiter with the given priority. A higher
      * number means a higher priority. The default is 0.
      *
      * The semantics of `submit` are fire-and-forget: the returned
      * `F[Unit]` immediately returns, without waiting for the `F[A]`
      * to complete its execution. Note that this means that the
      * returned `F[Unit]` will be successful regardless of whether
      * `job` fails or not.
      *
      * This method is designed to be called concurrently: every
      * concurrent call submits a job, and they are all started at a
      * rate which is no higher then the maximum rate you specify when
      * constructing a [[Limiter]]. Higher priority jobs take precedence
      * over lower priority ones.
      *
      * The `ack` parameter can be used to signal to the
      * [[Limiter]] that backpressure should be applied depending on the
      * result of `job`. Note that this will only change the
      * processing rate: it won't do any error handling for you. Also
      * see [[BackPressure]]
      */
    def submit[A](
        job: F[A],
        priority: Int = 0,
        ack: BackPressure.Ack[A] = BackPressure.never[A]): F[Unit]

    /**
      * Returns an `F[A]` which represents the action of submitting
      * `fa` to the [[Limiter]] with the given priority, and waiting for
      * its result. A higher number means a higher priority. The
      * default is 0.
      *
      * The semantics of `await` are blocking: the returned `F[A]`
      * only completes when `job` has finished its execution,
      * returning the result of `job` or failing with the same error
      * `job` failed with. However, the blocking is only semantic, no
      * actual threads are blocked by the implementation.
      *
      * This method is designed to be called concurrently: every
      * concurrent call submits a job, and they are all started at a
      * rate which is no higher then the maximum rate you specify when
      * constructing a [[Limiter]].
      *
      * The `ack` parameter can be used to signal to the
      * [[Limiter]] that backpressure should be applied depending on the
      * result of `job`. Note that this will only change the
      * processing rate: it won't do any error handling for you. Also
      * see [[BackPressure]]
      */
    def await[A](
        job: F[A],
        priority: Int = 0,
        ack: BackPressure.Ack[A] = BackPressure.never[A]): F[A]

    /**
      * Obtains a snapshot of the current number of jobs waiting to be
      * executed. May be out of date the instant after it is
      * retrieved.
      */
    def pending: F[Int]

    /**
      * Allows to sample, change or react to changes to the current interval between two tasks.
      */
    def interval: SignallingRef[F, FiniteDuration]

    /**
      * Resets the interval to the one set on creation of this [[Limiter]]
      */
    def reset: F[Unit]
  }

  object Limiter {

    /**
      * Creates a new [[Limiter]] and starts processing the jobs
      * submitted by the it, which are started at a rate no higher
      * than `1 / period`
      *
      * Every time a job signals backpressure is needed, the [[Limiter]]
      * will adjust its current rate by applying `backOff` to it. This
      * means the rate will be adjusted by calling `backOff`
      * repeatedly whenever multiple consecutive jobs signal for
      * backpressure, and reset to its original value when a job
      * signals backpressure is no longer needed.
      *
      * Note that since jobs submitted to the [[Limiter]] are processed
      * asynchronously, rate changes might not propagate instantly when
      * the rate is smaller than the job completion time. However, the
      * rate will eventually converge to its most up-to-date value.
      *
      * Additionally, `n` allows you to place a bound on the maximum
      * number of jobs allowed to queue up while waiting for
      * execution. Once this number is reached, the `F` returned by
      * any call to the [[Limiter]] will immediately fail with a
      * [[LimitReachedException]], so that you can in turn signal for
      * backpressure downstream. Processing restarts as soon as the
      * number of jobs waiting goes below `n` again.
      */
    def start[F[_]: Concurrent: Timer](
        period: FiniteDuration,
        backOff: FiniteDuration => FiniteDuration,
        n: Int): Resource[F, Limiter[F]] = Resource {
      (
        Queue.bounded[F, F[BackPressure]](n),
        Deferred[F, Unit],
        SignallingRef[F, FiniteDuration](period)
      ).mapN {
        case (queue, stop, interval_) =>
          def limiter = new Limiter[F] {
            def submit[A](
                job: F[A],
                priority: Int,
                ack: BackPressure.Ack[A]): F[Unit] =
              queue.enqueue(
                job.attempt map ack,
                priority
              )

            def await[A](
                job: F[A],
                priority: Int,
                ack: BackPressure.Ack[A]): F[A] =
              Deferred[F, Either[Throwable, A]] flatMap { p =>
                queue.enqueue(
                  job.attempt.flatTap(p.complete).map(ack),
                  priority
                ) *> p.get.rethrow
              }

            def pending: F[Int] = queue.size

            def interval: SignallingRef[F, FiniteDuration] = interval_

            def reset: F[Unit] = interval.set(period)
          }

          def backPressure(limiter: Limiter[F]): F[BackPressure] => F[Unit] =
            _.map(_.slowDown) ifM (
              ifTrue = limiter.interval.modify(i => backOff(i) -> i).void,
              ifFalse = limiter.interval.set(period)
            )

          // `job` needs to be executed asynchronously so that long
          // running jobs don't interfere with the frequency of pulling
          // from the queue. It also means that a failed `job` doesn't
          // cause the overall processing to fail
          def exec(job: F[BackPressure]): F[Unit] =
            backPressure(limiter).apply(job).start.void

          def rate: Stream[F, Unit] =
            Stream
              .repeatEval(limiter.interval.get)
              .flatMap(Stream.sleep[F])

          def executor: Stream[F, Unit] =
            queue.dequeueAll
              .zipLeft(rate)
              .evalMap(exec)
              .interruptWhen(stop.get.attempt)

          executor.compile.drain.start.void
            .as(limiter -> stop.complete(()))
      }.flatten
    }

    /**
      * Creates a no-op [[Limiter]], with no rate limiting and a synchronous
      * `submit` method. `pending` is always zero.
      * `rate` is set to zero and changes to it have no effect.
      */
    def noOp[F[_]: Concurrent]: F[Limiter[F]] =
      SignallingRef[F, FiniteDuration](0.seconds).map { interval_ =>
        new Limiter[F] {
          def submit[A](
              job: F[A],
              priority: Int,
              ack: BackPressure.Ack[A]): F[Unit] = job.void

          def await[A](
              job: F[A],
              priority: Int = 0,
              ack: BackPressure.Ack[A]): F[A] = job

          def pending: F[Int] = 0.pure[F]

          def interval: SignallingRef[F, FiniteDuration] = interval_

          def reset: F[Unit] = interval.set(0.seconds)
        }
      }

  }
}
