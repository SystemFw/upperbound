package upperbound

import fs2.Stream
import cats.effect._
import cats.syntax.all._

import queues.Queue

class QueueSpec extends BaseSpec {

  import DefaultEnv._

  "An unbounded Queue should" - {

    // Using property based testing for this assertion greatly
    // increases the chance of testing all the concurrent
    // interleavings.
    "block on an empty queue until an element is available" in forAll {
      (fst: Int, snd: Int) =>
        def concurrentProducerConsumer =
          for {
            queue <- Stream.eval(Queue.unbounded[IO, Int])
            consumer = Stream.eval(queue.dequeue)
            producer = Stream.eval(fst.pure[IO] <* queue.enqueue(snd, 0))
            result <- consumer.merge(producer)
          } yield result

      val res = concurrentProducerConsumer.compile.toVector.unsafeRunSync

      assert(res.contains(fst) && res.contains(snd))
    }

    "dequeue the highest priority elements first" in forAll {
      (elems: Vector[Int]) =>
        def input = elems.zipWithIndex
       
        assert(prog(input).unsafeRunSync === elems.reverse)
    }

    "dequeue elements with the same priority in FIFO order" in forAll {
      (elems: Vector[Int]) =>
        def input = elems.map(_ -> 0)

        assert(prog(input).unsafeRunSync === elems)
    }
  }

  "A bounded Queue should" - {
    "fail an enqueue attempt if the queue is full" in {
      def prog =
        for {
          q <- Queue.bounded[IO, Int](1)
          _ <- q.enqueue(1, 0)
          _ <- q.enqueue(1, 0)
        } yield ()

      assertThrows[LimitReachedException](prog.unsafeRunSync)
    }

    "successfully enqueue after dequeueing from a full queue" in {
      def prog =
        for {
          q <- Queue.bounded[IO, Int](1)
          _ <- q.enqueue(1, 0)
          _ <- q.enqueue(2, 0).attempt
          _ <- q.dequeue
          _ <- q.enqueue(3, 0)
          r <- q.dequeue
        } yield r

      assert(prog.unsafeRunSync === 3)
    }
  }

  implicit class QueueOps[A](queue: Queue[IO, Option[A]]) {
    def dequeueAllF: IO[Vector[A]] =
      queue.dequeueAll.unNoneTerminate.compile.toVector

    def enqueueAllF(elems: Vector[(A, Int)]): IO[Unit] =
      Stream
        .emits(elems)
        .noneTerminate
        .evalMap {
          case Some((e, p)) => queue.enqueue(e.some, p)
          case None => queue.enqueue(None, Int.MinValue)
        }
        .compile
        .drain
  }

  def prog[A](input: Vector[(A, Int)]) =
    for {
      queue <- Queue.unbounded[IO, Option[A]]
      _ <- queue enqueueAllF input
      res <- queue.dequeueAllF
    } yield res
}
