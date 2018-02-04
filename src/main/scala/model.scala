package upperbound

import scala.concurrent.duration.FiniteDuration
import scala.reflect.ClassTag

object model {

  /**
    * Signals that the number of jobs waiting to be executed has
    * reached the maximum allowed number. See [[Limiter.start]]
    */
  case class LimitReachedException() extends Exception

  /**
    * Models the job processing rate
    * Also see [[syntax]]
    */
  case class Rate(n: Int, t: FiniteDuration) {
    def period = t / n.toLong
  }
  object Rate {
    class Ops(n: Int) {
      def every(t: FiniteDuration) = Rate(n, t)
    }
    trait Syntax {
      implicit def boundSyntaxEvery(n: Int): Rate.Ops = new Ops(n)
    }
  }

  /**
    * Signals to the [[Limiter]] that it should slow down due to
    * backpressure needed upstream
    */
  case class BackPressure(slowDown: Boolean)
  object BackPressure {

    /**
      * Decides when to signal backpressure given the result or error
      * of a job
      */
    type Ack[-A] = Either[Throwable, A] => BackPressure

    /**
      *  Never backpressure
      */
    def never[A]: Ack[A] = _ => BackPressure(false)

    /**
      * Backpressure every time a job fails with any error
      */
    def onAllErrors[A]: Ack[A] = x => BackPressure(x.isLeft)

    /**
      * Backpressure when a job fails with a specific exception.
      * Used with explicit type application:
      * {{{
      * onError[MyException]
      * }}}
      */
    def onError[E <: Throwable: ClassTag]: Ack[Any] =
      (_: Either[Throwable, _]) match {
        case Left(_: E) => BackPressure(true)
        case _ => BackPressure(false)
      }

    /**
      * Backpressure when the result of a job satisfies the given
      * condition
      */
    def onResult[A](cond: A => Boolean): Ack[A] = _ match {
      case Left(_) => BackPressure(false)
      case Right(r) => BackPressure(cond(r))
    }
  }
}
