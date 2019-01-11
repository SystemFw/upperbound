package upperbound

import cats._, implicits._
import cats.effect._, concurrent._
import cats.effect.implicits._
import fs2._
import cats.collections.Heap

private[upperbound] object queues {

  /**
    * Non-blocking, concurrent, MPSC priority queue.
    */
  trait Queue[F[_], A] {

    /**
      * Enqueues an element. A higher number means higher priority.
      * Fails if the queue is full.
      */
    def enqueue(a: A, priority: Int): F[Unit]

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

  object Queue {
    type State[F[_], A] = Either[Deferred[F, A], IQueue[A]]

    def apply[F[_]: Concurrent, A](maxSize: Int): F[Queue[F, A]] =
      Ref.of[F, State[F, A]](IQueue.empty.asRight).map { state =>
        new Queue[F, A] {
          def enqueue(a: A, priority: Int): F[Unit] =
            state
              .modify {
                case Right(queue) =>
                  if (queue.size < maxSize)
                    queue.enqueue(a, priority).asRight -> ().pure[F]
                  else
                    queue.asRight -> Sync[F].raiseError[Unit](
                      new LimitReachedException)
                case Left(consumerWaiting) =>
                  IQueue.empty.asRight -> consumerWaiting.complete(a)
              }
              .flatten
              .uncancelable

          def dequeue: F[A] =
            Deferred[F, A].bracketCase(wait =>
              state.modify {
                case Right(queue) =>
                  queue.newDequeue match {
                    case None => wait.asLeft -> wait.get
                    case Some((v, tail)) => tail.asRight -> v.pure[F]
                  }
                case st @ Left(consumerWaiting) =>
                  val error =
                    "Protocol violation: concurrent consumers in a MPSC queue"
                  st -> Sync[F].raiseError[A](new IllegalStateException(error))
              }.flatten
            //
            ) {
              case (_, ExitCase.Completed | ExitCase.Error(_)) => ().pure[F]
              case (_, ExitCase.Canceled) =>
                state.update {
                  case s @ Right(_) => s
                  case l @ Left(waiting) => IQueue.empty[A].asRight
                }
            }

          def size = state.get.map {
            case Left(_) => 0
            case Right(v) => v.size
          }
        }
      }

//             Deferred[F, A].bracketCase(wait => // TODO bracket on interruption
//  // .flatMap(_ => promise.get) -- interrupted before or during the get
//             ){
//             }

//           def size: F[Int] = ???
// //          state.get.map(_.size)
//         ){

//         }
//       }

    /**
      * Unbounded size.
      * `dequeue` immediately fails if the queue is empty
      */
    def naive[F[_], A](implicit F: Concurrent[F]): F[Queue[F, A]] =
      Ref[F].of(IQueue.empty[A]).map { qref =>
        new Queue[F, A] {
          def enqueue(a: A, priority: Int): F[Unit] =
            qref.update { _.enqueue(a, priority) }

          def dequeue: F[A] =
            qref.modify { q =>
              q.dequeued -> F.delay(q.dequeue.get)
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
      Semaphore(0).flatMap { n =>
        naive[F, A].map { q =>
          new Queue[F, A] {
            def enqueue(a: A, priority: Int): F[Unit] =
              q.enqueue(a, priority).guarantee(n.release)

            // This isn't safe wrt interruption in general, but we can
            // get away with it because there is a single consumer, which
            // only interrupts a dequeue when it is interrupted itself.
            // TODO rewrite this with Ref + Deferred in any case, or ideally PubSub
            def dequeue: F[A] =
              n.acquire *> q.dequeue

            def size = q.size
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
      Semaphore(maxSize.toLong).flatMap { permits =>
        Queue.unbounded[F, A].map { queue =>
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

    def newDequeue: Option[(A, IQueue[A])] = queue.getMin.map { r =>
      r.a -> copy(queue = this.queue.remove)
    }

    def size: Int = queue.size
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
