import cats.Applicative
import cats.effect.{Concurrent, Timer, Resource}

import scala.concurrent.duration._
import fs2.Stream

package object upperbound {

  type Rate = model.Rate
  val Rate = model.Rate

  /**
    * Syntactic sugar to create rates.
    *
    * Example (note the underscores):
    * {{{
    * import upperbound.syntax.rate._
    * import scala.concurrent.duration._
    *
    * val r = 100 every 1.minute
    * }}}
    */
  object syntax {
    object rate extends Rate.Syntax
  }

  type LimitReachedException = model.LimitReachedException
  val LimitReachedException = model.LimitReachedException

  type BackPressure = model.BackPressure
  val BackPressure = model.BackPressure

  type Limiter[F[_]] = core.Limiter[F]
  object Limiter {
    // TODO just reexport the whole object from core

    def await[F[_]: Concurrent: Limiter, A](
        job: F[A],
        priority: Int = 0,
        ack: BackPressure.Ack[A] = BackPressure.never[A]): F[A] =
      core.Limiter.await(job, priority, ack)

    /**
      * See [[core.Limiter.start]]
      */
    def start[F[_]: Concurrent: Timer](
        maxRate: Rate,
        backOff: FiniteDuration => FiniteDuration = identity,
        n: Int = Int.MaxValue): Resource[F, Limiter[F]] =
      core.Limiter.start[F](maxRate.period, backOff, n)
  }

  /** Summoner */
  def apply[F[_]](implicit l: Limiter[F]): Limiter[F] = l

  /**
    * See [[core.Worker.noOp]]
    */
  def testLimiter[F[_]: Concurrent]: F[Limiter[F]] =
    core.Limiter.noOp
}
