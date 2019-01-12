package upperbound

import fs2.Stream
import cats.effect._
import cats.syntax.all._
import cats.instances.vector._
import scala.concurrent.duration._

import queues.Queue

class QueueSpec extends BaseSpec {

  "A Queue should" - {

    import DefaultEnv._
    // Using property based testing for this assertion greatly
    // increases the chance of testing all the concurrent
    // interleavings.
    "block on an empty queue until an element is available" in forAll {
      (fst: Int, snd: Int) =>
        def concurrentProducerConsumer =
          for {
            queue <- Stream.eval(Queue[IO, Int]())

            consumer = Stream.eval(queue.dequeue)
            producer = Stream.eval(fst.pure[IO] <* queue.enqueue(snd))
            result <- consumer.merge(producer)
          } yield result

        val res = concurrentProducerConsumer.compile.toVector.unsafeRunSync

        assert(res.contains(fst) && res.contains(snd))
    }

    "dequeue the highest priority elements first" in forAll {
      (elems: Vector[Int]) =>
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

        assert(prog.unsafeRunSync === elems.reverse)
    }

    "dequeue elements with the same priority in FIFO order" in forAll {
      (elems: Vector[Int]) =>
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

        assert(prog.unsafeRunSync === elems)
    }

    "fail an enqueue attempt if the queue is full" in {
      def prog =
        for {
          q <- Queue[IO, Int](1)
          _ <- q.enqueue(1, 0)
          _ <- q.enqueue(1, 0)
        } yield ()

      assertThrows[LimitReachedException](prog.unsafeRunSync)
    }

    "successfully enqueue after dequeueing from a full queue" in {
      def prog =
        for {
          q <- Queue[IO, Int](1)
          _ <- q.enqueue(1, 0)
          _ <- q.enqueue(2, 0).attempt
          _ <- q.dequeue
          _ <- q.enqueue(3, 0)
          r <- q.dequeue
        } yield r

      assert(prog.unsafeRunSync === 3)
    }
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

    val res = prog.unsafeToFuture
    env.tick(4.seconds)
    res.map(r => assert(r))
  }
}
