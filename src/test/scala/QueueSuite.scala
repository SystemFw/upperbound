package upperbound

import fs2.Stream
import cats.effect._
import cats.syntax.all._
import scala.concurrent.duration._

import upperbound.internal.Queue

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
            .drain ++ q.dequeueAll.take(elems.size)
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
            .take(elems.size)
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
    TestControl.executeEmbed {
      Queue[IO, Unit]()
        .flatMap { q =>
          def prod = IO.sleep(1.second) >> q.enqueue(())
          def consumer = q.dequeue.timeout(3.seconds)

          prod.start >> consumer
        }
    }.void
  }

  test(
    "If a dequeue gets canceled before an enqueue, no elements are lost in the next dequeue"
  ) {
    TestControl.executeEmbed {
      Queue[IO, Unit]().flatMap { q =>
        q.dequeue.timeout(2.second).attempt >>
          q.enqueue(()) >>
          q.dequeue.timeout(1.second)
      }
    }
  }

  test("Mark an element as deleted") {
    TestControl
      .executeEmbed {
        Queue[IO, Int]().flatMap { q =>
          q.enqueue(1).flatMap { id =>
            q.enqueue(2) >>
              q.delete(id) >>
              (
                q.dequeue,
                q.dequeue.map(_.some).timeoutTo(1.second, None.pure[IO])
              ).tupled
          }
        }
      }
      .assertEquals(2 -> None)
  }
  // check that is never the case that delete returns true but the element gets dequeued?
}
