package upperbound

import fs2.{Stream, async}
import fs2.util.Async
import fs2.async.mutable.Semaphore
import scalaz.{Monad, Heap, Order}
import scalaz.syntax.monad._
import scalaz.syntax.semigroup._
import scalaz.std.anyVal._

object queues {

  /**
    * A purely functional, concurrent, mutable priority queue.
    * Operations are all nonblocking in their implementations, but may
    * be 'semantically' blocking
    */
  trait Queue[F[_], A] {

    /**
      * Enqueues an element. A higher number means higher priority
      */
    def enqueue(a: A, priority: Int): F[Unit]

    /**
      * Tries to dequeue the higher priority element.  In case there
      * are multiple elements with the same priority, they are
      * dequeued in FIFO order
      */
    def dequeue: F[A]

    /**
      * Repeatedly calls `dequeue`
      */
    def dequeueAll: Stream[F, A] =
      Stream.repeatEval(dequeue)

    /**
      * Obtains a snapshot of the current number of elements in the
      * queue. May be out of date the instant after it is retrieved.
      */
    def size: F[Int]
  }

  object Queue {

    /**
      * Unbounded size.
      * `dequeue` immediately fails if the queue is empty
      */
    def naive[F[_]: Monad, A](implicit F: Async[F]): F[Queue[F, A]] =
      F.refOf(IQueue.empty[A]) map { qref =>
        new Queue[F, A] {
          def enqueue(a: A, priority: Int): F[Unit] =
            qref.modify(_.enqueue(a, priority)).void

          def dequeue: F[A] =
            qref.modify(_.dequeued) flatMap {
              _.previous.dequeue
                .fold(F.fail[A](new NoSuchElementException))(_.pure[F])
            }

          def size: F[Int] =
            qref.get.map(_.queue.size)
        }
      }

    /**
      * Unbounded size.
      * `dequeue` blocks on empty queue until an element is available.
      */
    def unbounded[F[_]: Monad, A](implicit F: Async[F]): F[Queue[F, A]] =
      async.semaphore(0) flatMap { n =>
        naive[F, A] map { queue =>
          new Queue[F, A] {
            def enqueue(a: A, priority: Int): F[Unit] =
              queue.enqueue(a, priority) >> n.increment

            def dequeue: F[A] =
              n.decrement >> queue.dequeue

            def size = queue.size
          }
        }
      }

    /**
      * Bounded size.
      * `dequeue` blocks on empty queue until an element is available.
      * `enqueue` immediately fails if the queue is full.
      */
    def bounded[F[_]: Monad: Async, A](maxSize: Int): F[Queue[F, A]] =
      Semaphore(maxSize.toLong) flatMap { permits =>
        Queue.unbounded[F, A] map { queue =>
          new Queue[F, A] {
            def enqueue(a: A, priority: Int): F[Unit] =
              permits.tryDecrement ifM (
                ifFalse = Async[F] fail LimitReachedException(),
                ifTrue = queue.enqueue(a, priority)
              )

            def dequeue: F[A] =
              queue.dequeue <* permits.increment

            def size = queue.size
          }
        }
      }
  }

  /**
    * A purely functional, immutable priority queue that breaks ties
    * using FIFO order
    */
  case class IQueue[A](queue: Heap[IQueue.Rank[A]], nextId: Long) {
    def enqueue(a: A, priority: Int): IQueue[A] = IQueue(
      queue insert IQueue.Rank(a, priority, nextId),
      nextId + 1
    )

    def dequeue: Option[A] = queue.minimumO.map(_.a)

    def dequeued: IQueue[A] = copy(queue = this.queue.deleteMin)
  }

  object IQueue {
    def empty[A] = IQueue(Heap.Empty[Rank[A]], 0)

    case class Rank[A](a: A, priority: Int, insertionOrder: Long = 0)

    object Rank {
      import Order.orderBy

      implicit def rankOrder[A]: Order[Rank[A]] =
        orderBy[Rank[A], Int](_.priority).reverseOrder |+| orderBy(
          _.insertionOrder)
    }
  }

}
