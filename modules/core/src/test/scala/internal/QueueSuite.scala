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

import cats.syntax.all._
import cats.effect._
import fs2.Stream
import scala.concurrent.duration._

import org.scalacheck.effect.PropF.forAllF
import cats.effect.testkit.TestControl

class QueueSuite extends BaseSuite {
  test("dequeue the highest priority elements first") {
    forAllF { (elems: Vector[Int]) =>
      Queue[IO, Int]()
        .map { q =>
          Stream
            .emits(elems)
            .zipWithIndex
            .evalMap { case (e, p) => q.enqueue(e, p.toInt) }
            .drain ++ q.dequeueAll.take(elems.size.toLong)
        }
        .flatMap(_.compile.toVector)
        .assertEquals(elems.reverse)
    }
  }

  test("dequeue elements with the same priority in FIFO order") {
    forAllF { (elems: Vector[Int]) =>
      Queue[IO, Int]()
        .map { q =>
          Stream
            .emits(elems)
            .evalMap(q.enqueue(_))
            .drain ++ q.dequeueAll
            .take(elems.size.toLong)
        }
        .flatMap(_.compile.toVector)
        .assertEquals(elems)
    }
  }

  test("fail an enqueue attempt if the queue is full") {
    Queue[IO, Int](1)
      .flatMap { q =>
        q.enqueue(1) >> q.enqueue(1)
      }
      .intercept[LimitReachedException]
  }

  test("successfully enqueue after dequeueing from a full queue") {
    Queue[IO, Int](1)
      .flatMap { q =>
        q.enqueue(1) >>
          q.enqueue(2).attempt >>
          q.dequeue >>
          q.enqueue(3) >>
          q.dequeue
      }
      .assertEquals(3)
  }

  test("block on an empty queue until an element is available") {
    val prog = Queue[IO, Unit]()
      .flatMap { q =>
        def prod = IO.sleep(1.second) >> q.enqueue(())
        def consumer = q.dequeue.timeout(3.seconds)

        prod.start >> consumer
      }

    TestControl.executeEmbed(prog).assert
  }

  test(
    "If a dequeue gets canceled before an enqueue, no elements are lost in the next dequeue"
  ) {
    val prog = Queue[IO, Unit]().flatMap { q =>
      q.dequeue.timeout(2.second).attempt >>
        q.enqueue(()) >>
        q.dequeue.timeout(1.second)
    }

    TestControl.executeEmbed(prog).assert
  }

  test("Mark an element as deleted") {
    val prog = Queue[IO, Int]().flatMap { q =>
      q.enqueue(1).flatMap { id =>
        q.enqueue(2) >>
          q.delete(id) >>
          (
            q.dequeue,
            q.dequeue.map(_.some).timeoutTo(1.second, None.pure[IO])
          ).tupled
      }
    }

    TestControl.executeEmbed(prog).assertEquals(2 -> None)
  }

  // This test uses real concurrency to maximise racing
  test("Delete returns true <-> element is marked as deleted") {
    // Number of iterations to make potential races repeatable
    val n = 1000

    val prog =
      Queue[IO, Int]().flatMap { q =>
        q.enqueue(1).flatMap { id =>
          q.enqueue(2) >>
            (
              q.delete(id),
              q.dequeue
            ).parTupled
        }
      }

    prog
      .replicateA(n)
      .map { results =>
        results.forall { case (deleted, elem) =>
          if (deleted) elem == 2
          else elem == 1
        }
      }
      .assert
  }
}
