package upperbound

import org.scalatest._, prop.PropertyChecks
import cats.effect._, laws.util.TestContext

abstract class BaseSpec extends AsyncFreeSpec with PropertyChecks with OptionValues with EitherValues {
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
