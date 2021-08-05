package upperbound

import org.scalatest._
import cats.effect._
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import org.scalatest.freespec.AsyncFreeSpec

abstract class BaseSpec
    extends AsyncFreeSpec
    with ScalaCheckPropertyChecks
    with OptionValues
    with EitherValues {
  class Env {
    import cats.effect.unsafe.IORuntime
    implicit val global: IORuntime = IORuntime.global
  }

  object DefaultEnv {
    import cats.effect.unsafe.IORuntime
    implicit val global: IORuntime = IORuntime.global
  }
}
