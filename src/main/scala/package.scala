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
  }
}
