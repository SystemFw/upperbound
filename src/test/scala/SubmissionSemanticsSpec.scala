// package upperbound

// import fs2.util.Async
// import fs2.interop.scalaz._

// import scala.concurrent.duration._
// import scala.concurrent.ExecutionContext.Implicits.global

// import scalaz.concurrent.Task
// import scalaz.syntax.monad._

// import org.specs2.mutable.Specification
// import org.specs2.matcher.TaskMatchers

// class SubmissionSemanticsSpec extends Specification with TaskMatchers {

//   import pools._

//   type F[A] = Task[A]
//   val F = Async[Task]

//   import syntax.rate._

//   "A worker" >> {

//     "when using fire-and-forget semantics" should {

//       "continue execution immediately" in {
//         def prog =
//           for {
//             complete <- F.refOf(false)
//             limiter <- Limiter.start(1 every 10.seconds)
//             _ <- limiter.worker submit complete.setPure(true)
//             _ <- limiter.shutDown
//             res <- complete.get
//           } yield res

//         prog must returnValue(beFalse)
//       }
//     }

//     "when using await semantics" should {

//       "complete when the result of the submitted job is ready" in {
//         def prog =
//           for {
//             complete <- F.refOf(false)
//             limiter <- Limiter.start(1 every 1.seconds)
//             // setSyncPure is only available in fs2 0.10, so using modify
//             res <- limiter.worker await complete.modify(_ => true).as("done")
//             _ <- limiter.shutDown
//             state <- complete.get
//           } yield res -> state

//         prog must returnValue { out: (String, Boolean) =>
//           out._1 must beEqualTo("done")
//           out._2 must beTrue
//         }
//       }

//       "report the original error if execution of the submitted job fails" in {
//         case class MyError() extends Exception
//         def prog =
//           for {
//             limiter <- Limiter.start(1 every 1.seconds)
//             res <- limiter.worker await Task.fail(new MyError)
//             _ <- limiter.shutDown
//           } yield res

//         prog must failWith[MyError]
//       }
//     }

//     "when too many jobs have been submitted" should {
//       "reject new jobs immediately" in {
//         def prog =
//           for {
//             limiter <- Limiter.start(1 every 10.seconds, n = 0)
//             res <- limiter.worker await ().pure[Task]
//             _ <- limiter.shutDown
//           } yield res

//         def prog2 =
//           for {
//             limiter <- Limiter.start(1 every 10.seconds, n = 0)
//             _ <- limiter.worker submit ().pure[Task]
//             _ <- limiter.shutDown
//           } yield ()

//         prog must failWith[LimitReachedException]
//         prog2 must failWith[LimitReachedException]
//       }
//     }
//   }

// }
