object Q {
  import cats.effect._
  import scala.concurrent.duration._
  import cats.effect.syntax.all._
  import cats.syntax.all._
  import cats.effect.unsafe.implicits.global

  def p = {
    IO.sleep(1.second) >> IO.println("first")
  }.raceOutcome {
      IO.sleep(3.seconds) >> IO.println("Can't stop me now")
    }
    .unsafeRunSync()

  def p2 = {
    IO.sleep(1.second) >> IO.println("first") <* IO.raiseError(
      new Exception("boom")
    )
  }.raceOutcome {
      IO.sleep(3.seconds) >> IO.println("Can't stop me now")
    }
    .unsafeRunSync()

  def p3 =
    (IO.sleep(3.seconds) >> IO.println("hello")).start
      .flatMap { fib =>
        IO.sleep(1.second) >> fib.cancel >> fib.join
      }
      .unsafeRunSync()
}
