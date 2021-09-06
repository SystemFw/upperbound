package upperbound
package internal

import cats._
import cats.syntax.all._
import cats.effect._
import cats.effect.syntax.all._
import fs2._

import cats.effect.std.PQueue

private[upperbound] trait Queue[F[_], A] {
  /**
    * Enqueues an element. A higher number means higher priority,
    * with 0 as the default. Fails if the queue is full.
    */
  def enqueue(a: A, priority: Int = 0): F[Unit]

  /**
    * Dequeues the highest priority element. In case there
    * are multiple elements with the same priority, they are
    * dequeued in FIFO order.
    * Semantically blocks if the queue is empty.
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

private[upperbound] object Queue {
  def apply[F[_]: Concurrent, A](
      maxSize: Int = Int.MaxValue
  ): F[Queue[F, A]] =
    Ref[F].of(0L).product(PQueue.bounded[F, Rank[A]](maxSize)).map {
      case (lastInsertedAt, q) =>
        new Queue[F, A] {
          def enqueue(a: A, priority: Int = 0): F[Unit] =
            lastInsertedAt.getAndUpdate(_ + 1).flatMap { insertedAt =>
              q.tryOffer(Rank(a, priority, insertedAt)).flatMap { succeeded =>
                (new LimitReachedException)
                  .raiseError[F, Unit]
                  .whenA(!succeeded)
              }
            }

          def dequeue: F[A] = q.take.map(_.a)

          def size: F[Int] = q.size
        }
    }

  case class Rank[A](a: A, priority: Int, insertionOrder: Long = 0)

  object Rank {
    implicit def rankOrder[A]: Order[Rank[A]] =
      Order.whenEqual(
        Order.reverse(Order.by(_.priority)),
        Order.by(_.insertionOrder)
      )
  }
}
