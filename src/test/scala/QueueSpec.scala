// package upperbound

// import fs2.Stream
// import fs2.util.Async
// import fs2.interop.scalaz._

// import scala.concurrent.ExecutionContext.Implicits.global
// import scalaz.concurrent.Task
// import scalaz.syntax.applicative._
// import scalaz.syntax.std.option._

// import queues.Queue

// import org.specs2.mutable.Specification
// import org.specs2.matcher.TaskMatchers
// import org.specs2.ScalaCheck

// class QueueSpec extends Specification with TaskMatchers with ScalaCheck {

//   import pools._

//   type F[A] = Task[A]
//   val F = Async[Task]

//   "An unbounded Queue" should {

//     // Using property based testing for this assertion greatly
//     // increases the chance of testing all the concurrent
//     // interleavings.
//     "block on an empty queue until an element is available" in prop {
//       (fst: Int, snd: Int) =>
//         def concurrentProducerConsumer =
//           for {
//             queue <- Stream.eval(Queue.unbounded[F, Int])
//             consumer = Stream.eval(queue.dequeue)
//             producer = Stream.eval(fst.pure[F] <* queue.enqueue(snd, 0))
//             result <- consumer.merge(producer)
//           } yield result

//         concurrentProducerConsumer.runLog should returnValue(contain(fst, snd))
//     }

//     "dequeue the highest priority elements first" in prop {
//       (elems: Vector[Int]) =>
//         def input = elems.zipWithIndex

//         prog(input) must returnValue(elems.reverse)
//     }

//     "dequeue elements with the same priority in FIFO order" in prop {
//       (elems: Vector[Int]) =>
//         def input = elems.map(_ -> 0)

//         prog(input) must returnValue(elems)
//     }
//   }

//   "A bounded Queue" should {
//     "fail an enqueue attempt if the queue is full" in {
//       def prog =
//         for {
//           q <- Queue.bounded[F, Int](1)
//           _ <- q.enqueue(1, 0)
//           _ <- q.enqueue(1, 0)
//         } yield ()

//       prog must failWith[LimitReachedException]
//     }

//     "successfully enqueue after dequeueing from a full queue" in {
//       def prog =
//         for {
//           q <- Queue.bounded[F, Int](1)
//           _ <- q.enqueue(1, 0)
//           _ <- q.enqueue(2, 0).attempt
//           _ <- q.dequeue
//           _ <- q.enqueue(3, 0)
//           r <- q.dequeue
//         } yield r

//       prog must returnValue(3)
//     }
//   }

//   implicit class QueueOps[A](queue: Queue[F, Option[A]]) {
//     def dequeueAllF: F[Vector[A]] =
//       queue.dequeueAll.unNoneTerminate.runLog

//     def enqueueAllF(elems: Vector[(A, Int)]): F[Unit] =
//       Stream
//         .emits(elems)
//         .noneTerminate
//         .evalMap {
//           case Some((e, p)) => queue.enqueue(e.some, p)
//           case None         => queue.enqueue(None, Int.MinValue)
//         }
//         .run
//   }

//   def prog[A](input: Vector[(A, Int)]) =
//     for {
//       queue <- Queue.unbounded[F, Option[A]]
//       _ <- queue enqueueAllF input
//       res <- queue.dequeueAllF
//     } yield res
// }
