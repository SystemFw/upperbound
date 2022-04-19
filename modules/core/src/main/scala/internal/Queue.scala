/*
 * Copyright (c) 2017 Fabio Labella
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package upperbound
package internal

import cats._
import cats.syntax.all._
import cats.effect._
import fs2._

import cats.effect.std.PQueue

/** A concurrent priority queue with support for deletion. Reads block on empty
  * queue, writes fail on full queue.
  */
private[upperbound] trait Queue[F[_], A] {
  type Id

  /** Enqueues an element. A higher number means higher priority, with 0 as the
    * default. Fails if the queue is full. Returns an Id that can be used to
    * mark the element as deleted.
    */
  def enqueue(a: A, priority: Int = 0): F[Id]

  /** Marks the element at this Id as deleted. Returns false if the element was
    * not in the queue.
    */
  def delete(id: Id): F[Boolean]

  /** Dequeues the highest priority element. In case there are multiple elements
    * with the same priority, they are dequeued in FIFO order. Semantically
    * blocks if the queue is empty.
    *
    * Elements marked as deleted are removed and skipped, and the next element
    * in the queue gets returned instead, semantically blocking if there is no
    * next element.
    */
  def dequeue: F[A]

  /** Repeatedly calls `dequeue`
    */
  def dequeueAll: Stream[F, A] =
    Stream.repeatEval(dequeue)

  /** Obtains a snapshot of the current number of elements in the queue. May be
    * out of date the instant after it is retrieved.
    */
  def size: F[Int]
}

private[upperbound] object Queue {
  def apply[F[_]: Concurrent, A](
      maxSize: Int = Int.MaxValue
  ): F[Queue[F, A]] =
    (Ref[F].of(0L), PQueue.bounded[F, Rank[F, A]](maxSize)).mapN {
      (lastInsertedAt, q) =>
        new Queue[F, A] {
          type Id = F[Boolean]

          def enqueue(a: A, priority: Int = 0): F[Id] =
            lastInsertedAt.getAndUpdate(_ + 1).flatMap { insertedAt =>
              Rank.create(a, priority, insertedAt).flatMap { rank =>
                q.tryOffer(rank)
                  .flatMap { succeeded =>
                    (new LimitReachedException)
                      .raiseError[F, Unit]
                      .whenA(!succeeded)
                  }
                  .as(rank.markAsDeleted)
              }
            }

          def delete(id: Id): F[Boolean] =
            id

          def dequeue: F[A] = q.take.flatMap {
            _.extract.flatMap {
              case Some(a) => a.pure[F]
              case None => dequeue
            }
          }

          def size: F[Int] = q.size
        }
    }

  case class Rank[F[_]: Concurrent, A](
      a: Ref[F, Option[A]],
      priority: Int,
      insertedAt: Long = 0
  ) {
    def extract: F[Option[A]] =
      a.getAndSet(None)

    def markAsDeleted: F[Boolean] =
      a.getAndSet(None).map(_.isDefined)
  }

  object Rank {
    def create[F[_]: Concurrent, A](
        a: A,
        priority: Int,
        insertedAt: Long
    ): F[Rank[F, A]] =
      Ref[F].of(a.some).map(Rank(_, priority, insertedAt))

    implicit def rankOrder[F[_], A]: Order[Rank[F, A]] =
      Order.whenEqual(
        Order.reverse(Order.by(_.priority)),
        Order.by(_.insertedAt)
      )
  }
}
