import cats.effect.Effect
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

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

  type Worker[F[_]] = core.Worker[F]

  type Limiter[F[_]] = core.Limiter[F]
  object Limiter {

    /**
      * See [[core.Limiter.start]]
      */
    def start[F[_]: Effect](
        maxRate: Rate,
        backOff: FiniteDuration => FiniteDuration = identity,
        n: Int = Int.MaxValue)(implicit ec: ExecutionContext): F[Limiter[F]] =
      core.Limiter.start[F](maxRate.period, backOff, n)
  }

  /**
    * See [[core.Worker.noOp]]
    */
  def testWorker[F[_]: Effect](implicit ec: ExecutionContext): Worker[F] =
    core.Worker.noOp
}
