# upperbound

[![Build Status](https://travis-ci.org/SystemFw/upperbound.svg?branch=master)](https://travis-ci.org/SystemFw/upperbound)
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/org.systemfw/upperbound_2.12/badge.svg)](https://maven-badges.herokuapp.com/maven-central/org.systemfw/upperbound_2.12)

**upperbound** is a purely functional rate limiter. It
allows you to submit jobs concurrently, which will then be started at
a rate no higher than what you specify.

## Installation
To get **upperbound**, add the following line to your `build.sbt`

``` scala
libraryDependencies += "org.systemfw" %% "upperbound" % "version"
```

You can find the latest version in the [releases](https://github.com/SystemFw/upperbound/releases) tab.  
**upperbound** depends on `fs2`, `cats`, `cats-effect` and `cats-collections`.

**Note:**

For the time being binary compatibility is **not**
guaranteed. This is not a problem for usage in applications (which is
where you would mostly use a rate limiter anyway), but risky if used
in libraries. Binary compatibility will be guaranteed in the future.

## Design principles

**upperbound** is an interval based limiter, which means jobs are
started at a _constant rate_. This strategy prevents spikes in
throughput, and makes it a very good fit for client side limiting,
e.g. calling a rate limited API.  
**upperbound** is completely pure, which allows for ease of reasoning
and composability. On a practical level, this means that some
familiarity with cats, cats-effect and fs2 is required.

## Usage

### Limiter

The main entity of the library is a `Limiter`, which is defined as:

``` scala
trait Limiter[F[_]] {
  def submit[A](job: F[A], priority: Int = 0): F[Unit]

  def interval: SignallingRef[F, FiniteDuration]

  def initial: FiniteDuration

  def pending: F[Int]
}
```

The `submit` method takes an `F[A]`, which can represent any
program, and returns an `F[Unit]` that represents the action of
submitting it to the limiter, with the given priority. The semantics
of `submit` are fire-and-forget: the returned `F[Unit]` immediately
returns, without waiting for the input `F[A]` to complete its
execution.  
`Limiter.submit` is designed to be called concurrently: every
concurrent call submits a job to `Limiter`, and they are then started
(in order of priority) at a rate which is no higher then the maximum
rate you specify on construction.  A higher number indicates higher
priority, and FIFO order is used in case there are multiple jobs with
the same priority being throttled.  
`interval` is an `fs2.concurrent.SignallingRef` that allows you to
sample, change or react to changes to the current interval between two
tasks. Finally, `initial` and `pending` return the initial interval
specified on creation, and the number of jobs that are queued up
waiting to start, respectively.

The `Limiter` algebra is the basic building block of the library,
additional functionality is expressed as combinators over it.

### Program construction

To create a `Limiter`, use the `start` method:

``` scala
case class Rate(n: Int, t: FiniteDuration)

object Limiter {
  def start[F[_]: Concurrent: Timer](maxRate: Rate, n: Int = Int.MaxValue): Resource[F, Limiter[F]]
}
```

`start` creates a new `Limiter` and starts processing the jobs
submitted so it, which are started at a rate no higher than `maxRate`.
`import upperbound.syntax.rate._` exposes the `every` syntax for creating `Rate`s:

``` scala
import upperbound.syntax.rate._
import scala.concurrent.duration._

Limiter.start[F](100 every 1.minute)
```

Additionally, `n` enforces a bound on the maximum number of jobs
allowed to queue up while waiting for execution. Once this number is
reached, calling `submit` will fail the resulting `F[Unit]` with a
`LimitReachedException`, so that you can in turn signal for
backpressure downstream. Processing restarts as soon as the number of
jobs waiting goes below `n` again.

The reason `start` returns a `cats.effect.Resource` is so that
processing can be stopped gracefully when the `Limiter`'s lifetime is
over.
To assemble your program, all the places that need limiting at the
same rate should take a `Limiter` as an argument, which is then
created at the end of a region of sharing (typically `main`) and
injected via `Limiter.start(...).use` or
`Stream.resource(Limiter.start(...)).flatMap`. If this sentence didn't
make sense to you, it's recommended to watch [this talk](https://github.com/SystemFw/scala-italy-201).


**Note:**

It's up to you whether you want to pass the `Limiter` algebra
implicitly (as an `F[_]: Limiter` bound) or explicitly.  
My position is that it's ok to pass algebras implicitly _as long as
the instance is made implicit at call site_, as close as possible to
where it's actually injected. This avoids any problems related to
mixing things up, and is essentially equivalent to having an instance
of your algebra for a newtype over Kleisli.

Reasonable people might disagree however, and I myself pass algebras
around both ways, in different codebases.  
**upperbound** is slightly skewed towards the `F[_]: Limiter` style:
internal combinators are expressed that way, and `Limiter` has a
summoner method to allow `Limiter[F].submit`

### Await

As mentioned above, `submit` has fire-and-forget semantics.
When this is not sufficient, you can use `await`:

``` scala
object Limiter {
 def await[F[_]: Concurrent: Limiter, A](job: F[A], priority: Int = 0): F[A]
}
```

`await` looks very similar to `submit`, except its semantics are
blocking: the returned `F[A]` only completes when `job` has
finished its execution. Note however, that the blocking is only semantic,
no actual threads are blocked by the implementation.

### Backpressure

`Limiter[F].interval` offers flexible control over the rate, which can
be used as a mechanism for applying backpressure based on the result
of a specific job (e.g. a REST call that got rejected upstream).  
Although this can be implemented entirely in user land, **upperbound**
provides some backpressure helpers and combinators out of the box.

``` scala
class BackPressure[F[_]: Limiter, A](job: F[A]) {
  def withBackoff(
    backOff: FiniteDuration => FiniteDuration,
    ack: BackPressure.Ack[A]
  ): F[A]
}
object BackPressure {
  case class Ack[-A](slowDown: Either[Throwable, A] => Boolean)
}
```

`withBackoff` enriches an `F[A]` with a `Limiter` constraint with the ability to apply backpressure to the `Limiter`:  
Every time a job signals backpressure is needed through `ack`, the `Limiter` will
adjust its current rate by applying `backOff` to it. This means the
rate will be adjusted by calling `backOff` repeatedly whenever
multiple consecutive jobs signal for backpressure, and reset to its
original value when a job signals backpressure is no longer needed.

Note that since jobs submitted to the Limiter are processed
asynchronously, rate changes will not propagate instantly when the
rate is smaller than the job completion time. However, the rate will
eventually converge to its most up-to-date value.

`BackPressure.Ack[A]` is a wrapper over  `Either[Throwable, A] => Boolean`,
and it's used to assert
that backpressure is needed based on a specific result (or error) of
the submitted job. You can write your own `Ack`s, but the library provides
some for you, including:

- `BackPressure.onAllErrors`: signal backpressure every time a job
  fails with any error.
- `BackPressure.onError[E <: Throwable]`: signal backpressure if a job
  fails with a specific type of exception. Meant to be used with
  explicit type application, e.g. `BackPressure.onError[MyException]`.
- `BackPressure.onResult(cond: A => Boolean)`: signal backpressure
  when the result of a job satisfies the given condition.

Note that `withBackoff` only transforms the input job, you still need
to actually `submit` or `await` yourself. This is done to allow
further combinators to operate on a job as a chain of `F[A] => F[A]`
functions before actually submitting to the `Limiter`.  
It's also available as syntax:
  
``` scala
import scala.concurrent.duration._
import upperbound._
import upperbound.syntax.backpressure._


def prog[F[_]: Limiter, A](fa: F[A]): F[Unit] = 
  Limiter[F].submit {
    fa.withBackoff(_ + 1.second, Backpressure.onAllErrors)
  }
```


Finally, please be aware that the backpressure support doesn't interfere with
your own error handling, nor does any error handling (e.g. retrying)
for you.


### Test limiter

If you need to satisfy a `Limiter` constraint in test code, where you
don't actally care about rate limiting, you can use `Limiter.noOp`,
which gives you a stub `Limiter` with no actual rate limiting and a
synchronous `submit` method.
