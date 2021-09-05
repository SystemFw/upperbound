package upperbound

import cats._, implicits._
import cats.effect._
import cats.effect.implicits._
import fs2._, fs2.concurrent.SignallingRef

import scala.concurrent.duration._
import upperbound.Queue

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
    */
  def submit[A](job: F[A], priority: Int = 0): F[Unit]

  /**
    * Obtains a snapshot of the current number of jobs waiting to be
    * executed. May be out of date the instant after it is
    * retrieved.
    */
  def pending: F[Int]
}

object Limiter {
  /**
    * Signals that the number of jobs waiting to be executed has
    * reached the maximum allowed number. See [[Limiter.start]]
    */
  case class LimitReachedException() extends Exception

  /** Summoner */
  def apply[F[_]](implicit l: Limiter[F]): Limiter[F] = l

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
    */
  // TODO do I want to allow cancelation here?
  def await[F[_]: Concurrent: Limiter, A](
      job: F[A],
      priority: Int = 0
  ): F[A] =
    Deferred[F, Either[Throwable, A]] flatMap { p =>
      Limiter[F].submit(
        job.attempt
          .flatTap(p.complete)
          .rethrow, // TODO does it make sense to rethrow here?
        priority
      ) *> p.get.rethrow
    }

  /**
    * Creates a new [[Limiter]] and starts processing the jobs
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
      maxRate: Rate,
      n: Int = Int.MaxValue
  ): Resource[F, Limiter[F]] = {
    assert(n > 0, s"n must be > 0, was $n")

    Resource {
      (
        Queue[F, F[Unit]](n),
        Deferred[F, Unit],
        SignallingRef[F, FiniteDuration](maxRate.period)
      ).mapN {
        case (queue, stop, interval_) =>
          def limiter = new Limiter[F] {
            def submit[A](job: F[A], priority: Int): F[Unit] =
              queue.enqueue(
                job.void,
                priority
              )

            def pending: F[Int] = queue.size
          }

          // `job` needs to be executed asynchronously so that long
          // running jobs don't interfere with the frequency of pulling
          // from the queue. It also means that a failed `job` doesn't
          // cause the overall processing to fail
          def exec(job: F[Unit]): F[Unit] = job.start.void

          def executor: Stream[F, Unit] =
            queue.dequeueAll
              .zipLeft(Stream.fixedDelay(maxRate.period))
              .evalMap(exec)
              .interruptWhen(stop.get.attempt)

          executor.compile.drain.start.void
            .as(limiter -> stop.complete(()).void)
      }.flatten
    }
  }

  /**
    * Creates a no-op [[Limiter]], with no rate limiting and a synchronous
    * `submit` method. `pending` is always zero.
    * `interval` is set to zero and changes to it have no effect.
    */
  def noOp[F[_]: Concurrent]: F[Limiter[F]] =
    SignallingRef[F, FiniteDuration](0.seconds).map { interval_ =>
      new Limiter[F] {
        def submit[A](job: F[A], priority: Int): F[Unit] = job.void
        def pending: F[Int] = 0.pure[F]
      }
    }
}
