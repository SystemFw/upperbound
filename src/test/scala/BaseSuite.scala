package upperbound

import scala.concurrent.ExecutionContext
import munit.{CatsEffectSuite, ScalaCheckEffectSuite}

abstract class BaseSuite extends CatsEffectSuite with ScalaCheckEffectSuite {
  override val munitExecutionContext: ExecutionContext = ExecutionContext.global
}
