// import fs2.interop.scalaz._
// import scala.concurrent.ExecutionContext
// import scala.concurrent.duration._
// import scalaz.concurrent.Task

// package object upperbound {

//   type Rate = model.Rate
//   val Rate = model.Rate

//   /**
//     * Syntactic sugar to create rates.
//     *
//     * Example (note the underscores):
//     * {{{
//     * import upperbound.syntax.rate._
//     * import scala.concurrent.duration._
//     *
//     * val r = 100 every 1.minute
//     * }}}
//     */
//   object syntax {
//     object rate extends Rate.Syntax
//   }

//   type LimitReachedException = model.LimitReachedException
//   val LimitReachedException = model.LimitReachedException

//   type BackPressure = model.BackPressure
//   val BackPressure = model.BackPressure

//   type Worker = core.Worker[Task]

//   type Limiter = core.Limiter[Task]
//   object Limiter {
//     import pools._

//     /**
//       * See [[core.Limiter.start]]
//       */
//     def start(
//         maxRate: Rate,
//         backOff: FiniteDuration => FiniteDuration = identity,
//         n: Int = Int.MaxValue)(implicit ec: ExecutionContext): Task[Limiter] =
//       core.Limiter.start[Task](maxRate.period, backOff, n)
//   }

//   /**
//     * See [[core.Worker.noOp]]
//     */
//   def testWorker: Worker = core.Worker.noOp
// }
