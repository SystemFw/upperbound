package upperbound

import cats._, implicits._
import cats.effect._, concurrent._
import fs2._
import cats.collections.Heap

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
    def naive[F[_], A](implicit F: Concurrent[F]): F[Queue[F, A]] =
      Ref.of[F, IQueue[A]](IQueue.empty[A]) map { qref =>
        new Queue[F, A] {
          def enqueue(a: A, priority: Int): F[Unit] =
            qref.modify { q =>
              q.enqueue(a, priority) -> q
            }.void

          def dequeue: F[A] =
            qref.modify { q =>
              q.dequeued -> q.dequeue
                .map(_.pure[F])
                .getOrElse(F.raiseError(new NoSuchElementException))
            }.flatten

          def size: F[Int] =
            qref.get.map(_.queue.size)
        }
      }

    /**
      * Unbounded size.
      * `dequeue` blocks on empty queue until an element is available.
      */
    def unbounded[F[_], A](implicit F: Concurrent[F]): F[Queue[F, A]] =
      Semaphore(0) flatMap { n =>
        naive[F, A] map { queue =>
          new Queue[F, A] {
            def enqueue(a: A, priority: Int): F[Unit] =
              queue.enqueue(a, priority) *> n.release

            def dequeue: F[A] =
              n.acquire *> queue.dequeue

            def size = queue.size
          }
        }
      }

    /**
      * Bounded size.
      * `dequeue` blocks on empty queue until an element is available.
      * `enqueue` immediately fails if the queue is full.
      */
    def bounded[F[_], A](maxSize: Int)(
        implicit F: Concurrent[F]): F[Queue[F, A]] =
      Semaphore(maxSize.toLong) flatMap { permits =>
        Queue.unbounded[F, A] map { queue =>
          new Queue[F, A] {
            def enqueue(a: A, priority: Int): F[Unit] =
              permits.tryAcquire ifM (
                ifTrue = queue.enqueue(a, priority),
                ifFalse = F.raiseError(LimitReachedException())
              )

            def dequeue: F[A] =
              queue.dequeue <* permits.release

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
      queue add IQueue.Rank(a, priority, nextId),
      nextId + 1
    )

    def dequeue: Option[A] = queue.getMin.map(_.a)

    def dequeued: IQueue[A] = copy(queue = this.queue.remove)
  }

  object IQueue {
    def empty[A] = IQueue(Heap.empty[Rank[A]], 0)

    case class Rank[A](a: A, priority: Int, insertionOrder: Long = 0)

    object Rank {
      implicit def rankOrder[A]: Order[Rank[A]] =
        Order.whenEqual(
          Order.reverse(Order.by(_.priority)),
          Order.by(_.insertionOrder)
        )
    }
  }
}
