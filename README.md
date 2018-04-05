# upperbound

**upperbound** is a purely functional rate limiter. It
allows you to submit jobs concurrently, which will then be started at
a rate no higher than what you specify.

## Installation
To get **upperbound**, add the following line to your `build.sbt`

``` scala
libraryDependencies += "org.systemfw" %% "upperbound" % "version"
```
Look at the [CHANGELOG.md] for the latest release.

## Purity

**upperbound** is completely pure, which allows for ease of reasoning
and composability. On a practical level, this means that some
familiarity with cats, cats-effect and fs2 is required.

## Usage

**upperbound**'s main datatypes are two: `Worker` and `Limiter`.
`Worker` is the central entity in the library, it's defined as:

``` scala
trait Worker[F[_]] {
  def submit[A](job: F[A], priority: Int = 0): F[Unit]
  def await[A](job: F[A], priority: Int = 0): F[A]
}
```
The `submit` method takes an `F[A]`, which can represent any
program, and returns an `F[Unit]` that represents the action of
submitting it to the limiter, with the given priority. The semantics
of `submit` are fire-and-forget: the returned `F[Unit]` immediately
returns, without waiting for the input `F[A]` to complete its
execution.

The `await` method takes an `F[A]`, which can represent any program,
and returns another `F[A]` that represents the action of submitting
the input `F` to the limiter with the given priority, and waiting
for its result.  The semantics of `await` are blocking: the returned
`F[A]` only completes when the input `F` has finished its
execution. However, the blocking is only semantic, no actual threads
are blocked by the implementation.

Both `Worker.submit` and `Worker.await` are designed to be called
concurrently: every concurrent call submits a job to `Limiter`, and
they are then started (in order of priority) at a rate which is
no higher then the maximum rate you specify on construction.
A higher number indicates higher priority, and FIFO order is used in
case there are multiple jobs with the same priority being throttled.

Naturally, you need to ensure that all the places in your code that
need rate limiting share the same instance of `Worker`. It might be
not obvious at first how to do this purely functionally, but it's in
fact very easy: just pass a `Worker` as a parameter. For example, if
you have a class with two methods that need rate limiting, have the
constructor of your class accept a `Worker`.

Following this approach, _your whole program_ will end up having type
`Worker => F[Whatever]`, and all you need now is creating a
`Worker`. This is what `Limiter` is for:

``` scala
import cats.effect.Effect

trait Limiter[F[_]] {
  def worker: Worker[F]
  def shutDown: F[Unit])
}

object Limiter {
  def start[F[_]: Effect](maxRate: Rate)(implicit ec: ExecutionContext): F[Limiter]
  def stream[F[_]: Effect](maxRate: Rate)(implicit ec: ExecutionContext): Stream[F, Limiter[F]]
}
```
You should only need `Limiter` at the end of your program, to assemble
all the parts together. Imagine your program is defined as:

``` scala
import upperbound._
import cats.effect.IO

case class YourWholeProgram(w: Worker[IO]) {
  def doStuff: IO[Unit] = {
     def yourLogic: IO[Whatever] = ???
     w.submit(yourLogic)
  }
}
```
you can then do:

``` scala
import upperbound._, syntax.rate._
import fs2.StreamApp
import cats.effect.IO
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

object Main extends StreamApp {
  def stream(args: List[String], requestShutdown: IO[Unit]) : Stream[IO, ExitCode] =
    for {
      limiter <- Limiter.stream[IO](100 every 1.minute)
      res <- YourWholeProgram(limiter.worker).doStuff
    } yield res
}
```

Note: the `every` syntax for declaring `Rate`s requires

``` scala
import upperbound.syntax.rate._
import scala.concurrent.duration._
```

## Testing
One further advantage of the architecture outlined above is testability.
In particular, you normally don't care about rate limiting in unit
tests, but the logic you are testing might require a `Worker` when
it's actually running. In this case, it's enough to pass in a stub
implementation of `Worker` that contains whatever logic is needed for
your tests. In particular, you can use `upperbound.testWorker` to get an
instance that does no rate limiting.

## Backpressure

`upperbound` gives you a mechanism for applying backpressure to the
`Limiter` based on the result of a specific job submitted by the
corresponding `Worker` (e.g. a REST call that got rejected upstream).
In particular, both `submit` and `await` take an extra (optional)
argument:

``` scala
def submit[A](job: F[A], priority: Int, ack: BackPressure.Ack[A])
def await[A](job: F[A], priority: Int, ack: BackPressure.Ack[A])
```

`BackPressure.Ack[A]` is an alias for `Either[Throwable, A] => BackPressure`,
where `case class BackPressure(slowDown: Boolean)` is used to assert
that backpressure is needed based on a specific result (or error) of
the submitted job. You can write your own `Ack`s, but `upperbound` provides
some for you:

- `BackPressure.never`: never signal backpressure. If you don't
  specify an `ack`, this is passed as a default.
- `BackPressure.onAllErrors`: signal backpressure every time a job
  fails with any error.
- `BackPressure.onError[E <: Throwable]`: signal backpressure if a job
  fails with a specific type of exception. Meant to be used with
  explicit type application, e.g. `BackPressure.onError[MyException]`.
- `BackPressure.onResult(cond: A => Boolean)`: signal backpressure
  when the result of a job satisfies the given condition.

To deal with backpressure, `Limiter.start` takes extra optional parameters:

``` scala
def start(maxRate: Rate,
          backOff: FiniteDuration => FiniteDuration = identity,
          n: Int = Int.MaxValue): F[Limiter]
```

Every time a job signals backpressure is needed, the Limiter will
adjust its current rate by applying `backOff` to it. This means the
rate will be adjusted by calling `backOff` repeatedly whenever
multiple consecutive jobs signal for backpressure, and reset to its
original value when a job signals backpressure is no longer needed.

Note that since jobs submitted to the Limiter are processed
asynchronously, rate changes will not propagate instantly when the
rate is smaller than the job completion time. However, the rate will
eventually converge to its most up-to-date value.

Similarly, `n` allows you to place a bound on the maximum number of
jobs allowed to queue up while waiting for execution. Once this number
is reached, the `F` returned by any call to the corresponding
`Worker` will immediately fail with a `LimitReachedException`, so
that you can in turn signal for backpressure downstream. Processing
restarts as soon as the number of jobs waiting goes below `n` again.

Please be aware that the backpressure support doesn't interfere with
your own error handling, nor does any error handling (e.g. retrying)
for you. This is an application specific concern and should be handled
in application code.
