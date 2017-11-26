package upperbound

import fs2.{Stream, async}
import cats.effect.Effect

import cats.kernel.Order
import cats.kernel.instances.long._
import cats.kernel.instances.int._

import cats.syntax.functor._
import cats.syntax.apply._
import cats.syntax.applicative._
import cats.syntax.flatMap._

import dogs.Heap

import scala.concurrent.ExecutionContext

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
    def naive[F[_], A](implicit F: Effect[F],
                       ec: ExecutionContext): F[Queue[F, A]] =
      async.refOf(IQueue.empty[A]) map { qref =>
        new Queue[F, A] {
          def enqueue(a: A, priority: Int): F[Unit] =
            qref.modify(_.enqueue(a, priority)).void

          def dequeue: F[A] =
            qref.modify(_.dequeued) flatMap {
              _.previous.dequeue
                .fold(F.raiseError[A](new NoSuchElementException))(_.pure[F])
            }

          def size: F[Int] =
            qref.get.map(_.queue.size)
        }
      }

    /**
      * Unbounded size.
      * `dequeue` blocks on empty queue until an element is available.
      */
    def unbounded[F[_], A](implicit F: Effect[F],
                           ec: ExecutionContext): F[Queue[F, A]] =
      async.semaphore(0) flatMap { n =>
        naive[F, A] map { queue =>
          new Queue[F, A] {
            def enqueue(a: A, priority: Int): F[Unit] =
              queue.enqueue(a, priority) *> n.increment

            def dequeue: F[A] =
              n.decrement *> queue.dequeue

            def size = queue.size
          }
        }
      }

    /**
      * Bounded size.
      * `dequeue` blocks on empty queue until an element is available.
      * `enqueue` immediately fails if the queue is full.
      */
    def bounded[F[_], A](maxSize: Int)(implicit F: Effect[F],
                                       ec: ExecutionContext): F[Queue[F, A]] =
      async.semaphore(maxSize.toLong) flatMap { permits =>
        Queue.unbounded[F, A] map { queue =>
          new Queue[F, A] {
            def enqueue(a: A, priority: Int): F[Unit] =
              permits.tryDecrement ifM (
                ifFalse = F raiseError LimitReachedException(),
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
