# Limiter

## Submitting jobs

**upperbound** offers a very minimal api, centred around the **Limiter** type:

```scala
trait Limiter[F[_]] {
  def submit[A](job: F[A], priority: Int = 0): F[A]
}
```

The `submit` method submits a job (which can be an arbitrary task) to
the limiter and waits until its execution is complete and a result
is available.

It is designed to be called concurrently: every call submits a job,
and they are started at regular intervals up to a maximum number of
concurrent jobs, based on the parameters you specify when creating the
limiter.

In case of failure, the returned `F[A]` will fail with the same error
`job` failed with. Note that in **upperbound** no errors are thrown if a job is rate limited, it simply waits to be executed in a queue.
`submit` can however fail with a `LimitReachedException` if the number
of enqueued jobs is past the limit you specify when creating the
limiter.

**upperbound** is well behaved with respect to cancelation: if you
cancel the `F[A]` returned by `submit`, the submitted job will be
canceled too. Two scenarios are possible: if cancelation is triggered
whilst the job is still queued up for execution, it will never be
executed and the rate of the limiter won't be affected. If instead
cancelation is triggered while the job is running, it will be
interrupted, but that slot will be considered used and the next job
will only be executed after the required time interval has elapsed.

`submit` also takes a `priority` parameter, which lets you submit jobs
at different priorities, so that higher priority jobs can be executed
before lower priority ones.
A higher number means a higher priority, with 0 being the default.

Note that any blocking performed by `submit` is only semantic, no
actual threads are blocked by the implementation.


## Rate limiting controls

To create a `Limiter`, use the `Limiter.start` method, which creates a
new limiter and starts processing jobs submitted to it.

```scala
object Limiter {
  def start[F[_]: Temporal](
    minInterval: FiniteDuration,
    maxConcurrent: Int = Int.MaxValue,
    maxQueued: Int = Int.MaxValue
  ): Resource[F, Limiter[F]]
}
```

> **Note:**
> It's recommended to use an explicit type ascription such as
> `Limiter.start[IO]` or `Limiter.start[F]` when calling `start`, to
> avoid type inference issues.

In order to avoid bursts, jobs submitted to the limiter are
started at regular intervals, as specified by the `minInterval`
parameter. You can pass `minInterval` as a `FiniteDuration`, or using
**upperbound**'s rate syntax (note the underscores in the imports):
```scala mdoc:compile-only
import upperbound._
import upperbound.syntax.rate._
import scala.concurrent.duration._
import cats.effect._

Limiter.start[IO](minInterval = 1.second)
// or
Limiter.start[IO](minInterval = 60 every 1.minute)
```

If the duration of some jobs is longer than `minInterval`, multiple
jobs will be started concurrently.
You can limit the amount of concurrency with the `maxConcurrent`
parameter: upon reaching `maxConcurrent` running jobs, the
limiter will stop pulling new ones until old ones terminate.
Note that this means that the specified interval between jobs is
indeed a _minimum_ interval, and it could be longer if the
`maxConcurrent` bound gets hit. The default is no limit.

Jobs that are waiting to be executed are queued up in memory, and
you can control the maximum size of this queue with the
`maxQueued` parameter.
Once this number is reached, submitting new jobs will immediately
fail with a `LimitReachedException`, so that you can in turn signal
for backpressure downstream. Submission is allowed again as soon as
the number of jobs waiting goes below `maxQueued`.
`maxQueued` must be **> 0**, and the default is no limit.

> **Notes:**  
> - `Limiter` accepts jobs at different priorities, with jobs at a
higher priority being executed before lower priority ones.
> - Jobs that fail or are interrupted do not affect processing of
>   other jobs.


## Program construction

`Limiter.start` returns a `cats.effect.Resource` so that processing
can be stopped gracefully when the limiter's lifetime is over. When
the `Resource` is finalised, all pending and running jobs are
canceled. All outstanding calls to `submit` are also canceled.

To assemble your program, make sure that all the places that need
limiting at the same rate take `Limiter` as an argument, and create
one at the end of a region of sharing (typically `main`) via a single
call to `Limiter.start(...).use`.

In particular, note that the following code creates two different
limiters:

```scala mdoc:compile-only
import cats.syntax.all._
import upperbound._
import cats.effect._
import scala.concurrent.duration._

val limiter = Limiter.start[IO](1.second)

// example modules, generally classes in real code
def apiCall: IO[Unit] =
  limiter.use { limiter =>
    val call: IO[Unit] = ???
    limiter.submit(call)
  }

def otherApiCall: IO[Unit] = ???
  limiter.use { limiter =>
    val otherCall: IO[Unit] = ???
    limiter.submit(otherCall)
  }

// example logic
(apiCall, otherApiCall).parTupled
```

Instead, you want to ensure the same limiter is passed to both:

```scala mdoc:compile-only
import cats.syntax.all._
import upperbound._
import cats.effect._
import scala.concurrent.duration._

val limiter = Limiter.start[IO](1.second)

// example modules, generally classes in real code
def apiCall(limiter: Limiter[IO]): IO[Unit] = {
  val call: IO[Unit] = ???
  limiter.submit(call)
}

def otherApiCall(limiter: Limiter[IO]): IO[Unit] = {
  val otherCall: IO[Unit] = ???
  limiter.submit(otherCall)
}

// example logic
limiter.use { limiter =>
 (
   apiCall(limiter),
   otherApiCall(limiter)
 ).parTupled
}
```

If you struggled to make sense of the examples in this section, it's
recommended to watch [this talk](https://github.com/SystemFw/scala-italy-2018.)

## Adjusting rate and concurrency

**upperbound** lets you control both the rate of submission and the
maximum concurrency dynamically, through the following methods on
`Limiter`:

```scala
def minInterval: F[FiniteDuration]
def setMinInterval(newMinInterval: FiniteDuration): F[Unit]
def updateMinInterval(update: FiniteDuration => FiniteDuration): F[Unit]

def maxConcurrent: F[Int]
def setMaxConcurrent(newMaxConcurrent: Int): F[Unit]
def updateMaxConcurrent(update: Int => Int): F[Unit]
```

The `*minInterval` methods let you change the rate of submission by
varying the minimum time interval between two tasks. If the interval
changes while the limiter is sleeping between tasks, the duration of
the sleep is adjusted on the fly, taking into account any elapsed
time. This might mean waking up instantly if the entire new interval
has already elapsed.

The `*maxConcurrent` methods let you change the maximum number of
concurrent tasks that can be executing at any given time. If the
concurrency limit gets changed while the limiter is already blocked
waiting for some tasks to finish, the limiter will then be unblocked
as soon as the number of running tasks goes below the new concurrency
limit. Note however that if the limit shrinks the limiter will not try to
interrupt tasks that are already running, so for some time it might be
that `runningTasks > maxConcurrent`.
  
    
## Test limiter

**upperbound** also provides `Limiter.noOp` for testing purposes, which is
a stub `Limiter` with no actual rate limiting and a synchronous
`submit` method.



