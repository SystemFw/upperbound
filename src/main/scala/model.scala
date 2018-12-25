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
}
