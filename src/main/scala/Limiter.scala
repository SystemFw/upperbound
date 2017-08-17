package upperbound

import fs2._

trait Limiter[F[_], A] {
  def limit: Pipe[F, A, A]
}
