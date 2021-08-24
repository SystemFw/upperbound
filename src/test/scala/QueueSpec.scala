package upperbound

import fs2.Stream
import cats.effect._
import cats.syntax.flatMap._
import scala.concurrent.duration._

import queues.Queue

class QueueSpec extends BaseSpec {
  "A Queue should" - {
    "dequeue the highest priority elements first" in forAll {
      (elems: Vector[Int]) =>
        import DefaultEnv._

        def prog =
          Queue[IO, Int]()
            .map { q =>
              Stream
                .emits(elems)
                .zipWithIndex
                .evalMap { case (e, p) => q.enqueue(e, p.toInt) }
                .drain ++ q.dequeueAll.take(elems.size)
            }
            .flatMap(_.compile.toVector)

        assert(prog.unsafeRunSync() === elems.reverse)
    }

    "dequeue elements with the same priority in FIFO order" in forAll {
      (elems: Vector[Int]) =>
        import DefaultEnv._

        def prog =
          Queue[IO, Int]()
            .map { q =>
              Stream
                .emits(elems)
                .evalMap(q.enqueue(_))
                .drain ++ q.dequeueAll
                .take(elems.size)
            }
            .flatMap(_.compile.toVector)

        assert(prog.unsafeRunSync() === elems)
    }

    "fail an enqueue attempt if the queue is full" in {
      import DefaultEnv._

      def prog =
        for {
          q <- Queue[IO, Int](1)
          _ <- q.enqueue(1)
          _ <- q.enqueue(1)
        } yield ()

      assertThrows[LimitReachedException](prog.unsafeRunSync())
    }

    "successfully enqueue after dequeueing from a full queue" in {
      import DefaultEnv._

      def prog =
        for {
          q <- Queue[IO, Int](1)
          _ <- q.enqueue(1)
          _ <- q.enqueue(2).attempt
          _ <- q.dequeue
          _ <- q.enqueue(3)
          r <- q.dequeue
        } yield r

      assert(prog.unsafeRunSync() === 3)
    }
  }

  "block on an empty queue until an element is available" in {
    val E = new Env
    import E._

    def prog =
      Queue[IO, Unit]()
        .flatMap { q =>
          def prod = IO.sleep(1.second) >> q.enqueue(())
          def consumer = q.dequeue.timeout(3.seconds)

          prod.start >> consumer.as(true)
        }

    val res = prog.unsafeToFuture()
    res.map(r => assert(r))
  }

  "If a dequeue gets canceled before an enqueue, no elements are lost in the next dequeue" in {
    val E = new Env
    import E._

    def prog =
      for {
        q <- Queue[IO, Unit]()
        _ <- q.dequeue.timeout(2.second).attempt
        _ <- q.enqueue(())
        _ <- q.dequeue.timeout(1.second)
      } yield true

    val res = prog.unsafeToFuture()
    res.map(r => assert(r))
  }
}
