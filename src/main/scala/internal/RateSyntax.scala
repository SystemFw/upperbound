package upperbound
package internal

import scala.concurrent.duration.FiniteDuration

trait RateSyntax {
  implicit def rateOps(n: Int): RateOps =
    new RateOps(n)
}

final class RateOps private[internal] (private[internal] val n: Int)
    extends AnyVal {
  def every(t: FiniteDuration): FiniteDuration = t / n.toLong
}
