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

package upperbound
package internal

import cats.effect._
import cats.syntax.all._
import scala.concurrent.duration._

import cats.effect.testkit.TestControl
import java.util.concurrent.CancellationException

class TaskSuite extends BaseSuite {

  class MyException extends Throwable

  def execute[A](task: IO[A]): IO[(IO[A], IO[Unit])] =
    Task.create(task).flatMap { task =>
      task.executable.start.as(task.awaitResult -> task.cancel)
    }

  def executeAndWait[A](task: IO[A]): IO[A] =
    execute(task).flatMap(_._1)

  test("The Task executable cannot fail") {
    val prog =
      Task
        .create { IO.raiseError[Unit](new MyException) }
        .flatMap(_.executable)

    TestControl.executeEmbed(prog).assert
  }

  test("Task propagates results") {
    val prog = executeAndWait { IO.sleep(1.second).as(42) }

    TestControl.executeEmbed(prog).assertEquals(42)
  }

  test("Task propagates errors") {
    val prog = executeAndWait { IO.raiseError[Unit](new MyException) }

    TestControl.executeEmbed(prog).intercept[MyException]
  }

  test("Task propagates cancellation") {
    val prog = executeAndWait { IO.sleep(1.second) >> IO.canceled }

    /* TestControl reports cancelation and nontermination with different
     * exceptions, a deadlock in Task.awaitResult would make this test fail
     */
    TestControl.executeEmbed(prog).intercept[CancellationException]
  }

  test("cancel cancels the Task executable") {
    val prog =
      execute { IO.never[Unit] }
        .flatMap { case (wait, cancel) => cancel >> wait }

    TestControl.executeEmbed(prog).intercept[CancellationException]
  }

  test("cancel backpressures on finalisers") {
    val prog =
      execute { IO.never[Unit].onCancel(IO.sleep(1.second)) }
        .flatMap { case (_, cancel) =>
          IO.sleep(10.millis) >> cancel.timed.map(_._1)
        }

    TestControl.executeEmbed(prog).assertEquals(1.second)
  }
}
