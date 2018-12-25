package upperbound

import cats._, implicits._
import cats.effect._
import scala.concurrent.duration._
import scala.reflect.ClassTag

object backpressure {

  /*
   * The `ack` parameter can be used to signal to the
   * [[Limiter]] that backpressure should be applied depending on the
   * result of `job`. Note that this will only change the
   * processing rate: it won't do any error handling for you. Also
   * see [[BackPressure]]

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
   */

  def withBackoff[F[_]: MonadError[?[_], Throwable]: Limiter, A](
      backOff: FiniteDuration => FiniteDuration,
      slowDown: Either[Throwable, A] => Boolean,
      job: F[A]): F[A] =
    job.attempt.flatTap { x =>
      if (slowDown(x))
        Limiter[F].interval.modify(i => backOff(i) -> i).void
      else
        Limiter[F].reset
    }.rethrow

  /**
    * Signals to the [[Limiter]] that it should slow down due to
    * backpressure needed upstream
    */
  /**
    * Decides when to signal backpressure given the result or error
    * of a job
    */
  type Ack[-A] = Either[Throwable, A] => Boolean

  /**
    *  Never backpressure
    */
  def never[A]: Ack[A] = _ => false

  /**
    * Backpressure every time a job fails with any error
    */
  def onAllErrors[A]: Ack[A] = x => x.isLeft

  /**
    * Backpressure when a job fails with a specific exception.
    * Used with explicit type application:
    * {{{
    * onError[MyException]
    * }}}
    */
  def onError[E <: Throwable: ClassTag]: Ack[Any] =
    (_: Either[Throwable, _]) match {
      case Left(_: E) => true
      case _ => false
    }

  /**
    * Backpressure when the result of a job satisfies the given
    * condition
    */
  def onResult[A](cond: A => Boolean): Ack[A] = _ match {
    case Left(_) => false
    case Right(r) => cond(r)
  }
}
