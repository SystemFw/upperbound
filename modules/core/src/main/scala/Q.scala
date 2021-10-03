/*
 * Copyright (c) 2017 Fabio Labella
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

object Q {
  import cats.effect._
  import scala.concurrent.duration._
  import cats.syntax.all._
  import cats.effect.unsafe.implicits.global

  def p = {
    IO.sleep(1.second) >> IO.println("first")
  }.raceOutcome {
    IO.sleep(3.seconds) >> IO.println("Can't stop me now")
  }.unsafeRunSync()

  def p2 = {
    IO.sleep(1.second) >> IO.println("first") <* IO.raiseError(
      new Exception("boom")
    )
  }.raceOutcome {
    IO.sleep(3.seconds) >> IO.println("Can't stop me now")
  }.unsafeRunSync()

  def p3 =
    (IO.sleep(3.seconds) >> IO.println("hello")).start
      .flatMap { fib =>
        IO.sleep(1.second) >> fib.cancel >> fib.join
      }
      .unsafeRunSync()
}
