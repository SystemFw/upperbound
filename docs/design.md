# Design

**upperbound** is an interval based rate limiter, which means that
jobs submitted to it are started at a _constant rate_. This strategy
prevents spikes in throughput, and makes it a very good fit for client
side limiting, e.g. calling a rate limited API or mitigating load on a
slow system.

It's intended as a simple, minimal library, but with enough control to
be broadly applicable, including:
- Job execution rate
- Maximum concurrency of jobs
- Prioritisation of jobs

**upperbound** is a purely functional library, built on top of
**cats-effect** for state management and performant, well-behaved
concurrency.
