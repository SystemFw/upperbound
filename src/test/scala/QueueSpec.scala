package upperbound

import fs2.Stream
import cats.effect.{ConcurrentEffect, IO, Timer}
import cats.syntax.apply._
import cats.syntax.applicative._
import cats.syntax.option._

import scala.concurrent.ExecutionContext
import queues.Queue
import org.specs2.mutable.Specification
import org.specs2.ScalaCheck

class QueueSpec(implicit ec: ExecutionContext)
    extends Specification
    with ScalaCheck {

  implicit val Timer: Timer[IO] = IO.timer(ec)
  implicit val ConcurrentEffect: ConcurrentEffect[IO] = IO.ioConcurrentEffect(IO.contextShift(ec))

  "An unbounded Queue" should {

    // Using property based testing for this assertion greatly
    // increases the chance of testing all the concurrent
    // interleavings.
    "block on an empty queue until an element is available" in prop {
      (fst: Int, snd: Int) =>
        def concurrentProducerConsumer =
          for {
            queue <- Stream.eval(Queue.unbounded[IO, Int])
            consumer = Stream.eval(queue.dequeue)
            producer = Stream.eval(fst.pure[IO] <* queue.enqueue(snd, 0))
            result <- consumer.merge(producer)
          } yield result

        concurrentProducerConsumer.compile.toVector.unsafeRunSync must contain(
          fst,
          snd)
    }

    "dequeue the highest priority elements first" in prop {
      (elems: Vector[Int]) =>
        def input = elems.zipWithIndex

        prog(input).unsafeRunSync must beEqualTo(elems.reverse)
    }

    "dequeue elements with the same priority in FIFO order" in prop {
      (elems: Vector[Int]) =>
        def input = elems.map(_ -> 0)

        prog(input).unsafeRunSync must beEqualTo(elems)
    }
  }

  "A bounded Queue" should {
    "fail an enqueue attempt if the queue is full" in {
      def prog =
        for {
          q <- Queue.bounded[IO, Int](1)
          _ <- q.enqueue(1, 0)
          _ <- q.enqueue(1, 0)
        } yield ()

      prog.unsafeRunSync must throwA[LimitReachedException]
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

      prog.unsafeRunSync must beEqualTo(3)
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
