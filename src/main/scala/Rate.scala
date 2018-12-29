package upperbound

import scala.concurrent.duration.FiniteDuration

/**
  * Models the job processing rate
  * Also see [[syntax.rate]]
  */
case class Rate(n: Int, t: FiniteDuration) {
  def period = t / n.toLong
}
object Rate {
  class Ops(n: Int) {
    def every(t: FiniteDuration) = Rate(n, t)
  }
  trait Syntax {
    implicit def upperboundSyntaxEvery(n: Int): Rate.Ops = new Ops(n)
  }
}
