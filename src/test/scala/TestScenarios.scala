// package upperbound

// import fs2.{concurrent, time, Pipe, Stream}
// import fs2.util.Async
// import fs2.interop.scalaz._

// import scala.concurrent.duration.FiniteDuration
// import scala.concurrent.ExecutionContext.Implicits.global

// import scalaz.concurrent.Task
// import scalaz.syntax.monad._

// import org.specs2.specification.BeforeAll

// trait TestScenarios extends BeforeAll {

//   import pools._

//   type F[A] = Task[A]
//   val F = Async[F]

//   def samplingWindow: FiniteDuration
//   def description: String

//   def beforeAll = println {
//     s"""
//     |=============
//     | $description tests running. Sampling window: $samplingWindow
//     |=============
//    """.stripMargin('|')
//   }

//   case class TestingConditions(
//       desiredRate: Rate,
//       backOff: FiniteDuration => FiniteDuration,
//       productionRate: Rate,
//       producers: Int,
//       jobsPerProducer: Int,
//       jobCompletion: FiniteDuration,
//       backPressure: BackPressure.Ack[Int],
//       samplingWindow: FiniteDuration
//   )

//   case class Metric(diffs: Vector[Long],
//                     mean: Double,
//                     stdDeviation: Double,
//                     overshoot: Double,
//                     undershoot: Double)
//   object Metric {
//     def from(samples: Vector[Long]): Metric = {
//       def diffs =
//         Stream
//           .emits(samples)
//           .sliding(2)
//           .map { case Vector(x, y) => math.abs(x - y) }
//           .toVector
//       def mean = diffs.sum.toDouble / diffs.size
//       def variance =
//         diffs.foldRight(0: Double)((x, tot) => tot + math.pow(x - mean, 2))
//       def std = math.sqrt(variance)
//       def overshoot = diffs.max
//       def undershoot = diffs.min

//       Metric(diffs, mean, std, overshoot, undershoot)
//     }
//   }

//   case class Result(
//       producerMetrics: Metric,
//       jobExecutionMetrics: Metric
//   )

//   def mkScenario(t: TestingConditions): F[Result] =
//     F.refOf(Vector.empty[Long]) flatMap { submissionTimes =>
//       F.refOf(Vector.empty[Long]) flatMap { startTimes =>
//         Limiter.start(t.desiredRate, t.backOff) flatMap { limiter =>
//           //
//           def record(destination: Async.Ref[F, Vector[Long]]): F[Unit] =
//             F.delay(System.currentTimeMillis) flatMap { time =>
//               destination.modify(times => time +: times).void
//             }

//           def job(i: Int) =
//             record(startTimes) >> time.sleep(t.jobCompletion).run >> i.pure[F]

//           def producer: Stream[F, Unit] =
//             Stream.range(0, t.jobsPerProducer).map(job) evalMap { x =>
//               record(submissionTimes) >> limiter.worker.submit(job = x,
//                                                                priority = 0,
//                                                                ack =
//                                                                  t.backPressure)
//             }

//           def pulse[B]: Pipe[F, B, B] =
//             in =>
//               time
//                 .awakeEvery(t.productionRate.period)
//                 .zip(in)
//                 .map(_._2)

//           def concurrentProducers: Stream[F, Unit] =
//             concurrent.join(t.producers)(
//               Stream(producer through pulse).repeat.take(t.producers))

//           for {
//             _ <- F.start(concurrentProducers.run)
//             _ <- time.sleep_(t.samplingWindow).run >> limiter.shutDown
//             p <- submissionTimes.get
//             j <- startTimes.get
//           } yield
//             Result(
//               producerMetrics = Metric.from(p.sorted),
//               jobExecutionMetrics = Metric.from(j.sorted)
//             )
//         }
//       }
//     }
// }
