package upperbound

import org.scalatest._
import cats.effect._, laws.util.TestContext
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import org.scalatest.freespec.AsyncFreeSpec

abstract class BaseSpec
    extends AsyncFreeSpec
    with ScalaCheckPropertyChecks
    with OptionValues
    with EitherValues {
  class Env {
    val env = TestContext()
    implicit val ctx: ContextShift[IO] = env.contextShift[IO]
    implicit val timer: Timer[IO] = env.timer[IO]
  }

  object DefaultEnv {
    val defaultEc = scala.concurrent.ExecutionContext.global
    implicit val Timer: Timer[IO] = IO.timer(defaultEc)
    implicit val ContextShift: ContextShift[IO] = IO.contextShift(defaultEc)
  }
}
