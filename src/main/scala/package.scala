package object upperbound {
  type LimitReachedException = Limiter.LimitReachedException
  val LimitReachedException = Limiter.LimitReachedException

  object syntax {
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
    object rate extends Rate.Syntax

    /**
      * Syntactic sugar to apply backpressure combinators.
      * Example:
      * {{{
      * import upperbound._
      * import upperbound.syntax.backpressure._
      * import scala.concurrent.duration._
      *
      * def ex[F[_]: Limiter](job: F[Int]): F[Unit] =
      *   Limiter[F].submit { job.withBackoff(_ + 1.second, Ack.onAllErrors)
      *
      * }}}
      *
      * See [[BackPressure]]
      *
      */
    object backpressure extends BackPressure.Syntax
  }
}
