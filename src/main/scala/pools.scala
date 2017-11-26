// package upperbound

// import fs2.Scheduler
// import scala.concurrent.{Await, ExecutionContext, Future}
// import scala.concurrent.duration.Duration
// import scalaz.concurrent.Strategy

// /**
//   * Needed to use `scalaz.Task` as the `F` in `Stream[F, A]`, by
//   * specifying a `scalaz.Strategy` as an `ExecutionContext`. No
//   * longer necessary in newer versions of fs2, it allows us to have
//   * a forward-compatible API.
//   */
// object pools {
//   // Scheduler handles the timers, once it signals a timeout the
//   // context is switched to Async and the underlying Strategy
//   implicit def scheduler: Scheduler = Scheduler.fromFixedDaemonPool(2)

//   // Implementation adapted from the scalaz default, which uses Java's
//   // ExecutorService instead of the desired ExecutionContext
//   implicit def strategy(implicit ec: ExecutionContext): Strategy =
//     new Strategy {
//       def apply[A](thunk: => A): () => A = {
//         // it's important that this Future is evaluated here,
//         // _before_ someone blocks on it by calling the () => A
//         val fut = Future(thunk)(ec)

//         () =>
//           Await.result(fut, Duration.Inf)
//       }
//     }
// }
