package upperbound

import cats.effect._
import cats.Order
import cats.syntax.all._
import cats.effect.std.PQueue
import cats.effect.unsafe.implicits.global
import fs2._

object Q {
  case class E(name: String, index: Int)
  object E {
    implicit val orderForE: Order[E] = Order.by(_.index)
  }

  def p = {
    val results = List(E("a", 0), E("b", 0), E("c", 0), E("d", 0))

    PQueue
      .unbounded[IO, E]
      .flatMap { q =>
        results.traverse(q.offer) >>
          q.take.replicateA(results.length).map { out =>
            out == results
          }
      }
      .unsafeRunSync()
  }

  def p2 = {
    case class E(name: String, index: Int)
    object E {
      implicit val orderForE: Order[E] = Order.by(_.index)
    }

    val results = List(E("a", 0), E("a", 0), E("b", 0))

    PQueue
      .unbounded[IO, E]
      .flatMap { q =>
        results.traverse(q.offer) >>
          q.take.replicateA(results.length).map { out =>
            println(out)
            out == results
          }
      }
      .unsafeRunSync()

    // scala> Q.p2
    // List(E(b,0), E(a,0), E(a,0))
    // val res0: Boolean = false
  }

  import queues.Queue.Rank

  def prog(elems: Vector[Int]) =
    PQueue
      .unbounded[IO, Rank[Int]]
      .map { q =>
        Stream
          .emits(elems)
          .evalMap(e => q.offer(Rank(e, 0)))
          .drain ++ Stream.repeatEval(q.take).take(elems.size)
      }
      .flatMap(_.compile.toVector)
      .map(_ == elems.map(Rank(_, 0)))
      .unsafeRunSync()

  def p3 = prog(Vector(0, 0, 1))
}
