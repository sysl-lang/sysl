---
title: The poll module
summary: "`sysl.io.poll` — the kernel's readiness set: `Poller.open`, `register`, `modify` and `deregister` with a token and a `Mode`, a `wait` that fills the caller's slice and allocates nothing, and a `Waker` that ends a wait from any thread. kqueue on Darwin and the BSDs, epoll on Linux."
weight: 77
---

`sysl.io.poll` waits on many descriptors at once. A program registers each one with a **token** of
its own choosing, and `wait` answers which are ready, by token:

```sysl
import sysl.io.poll.{Event, Poller}
import sysl.io.wait.{Readable, Writable}
import sysl.process.pipe
import sysl.time.millis

val p = Poller.open().unwrap()
val (r, w) = pipe().unwrap()
var got = [Event.none; 8]

p.register(r.fd().unwrap(), [Readable], 1).unwrap()
p.register(w.fd().unwrap(), [Writable], 2).unwrap()

val n = p.wait(got[..], Some(millis(1000))).unwrap()

print(n, got[0].token, got[0].writable)
```

```output
1 2 true
```

The read end has nothing in it, so only the write end is ready. It is the primitive under a server
that holds thousands of connections on a few threads — kqueue on Darwin and the BSDs, epoll on Linux
— and **it is not an event loop**: nothing here reads, writes, schedules or calls back. `wait` answers
which descriptors would not block, and the program does the rest, as
[`sysl.io.wait.ready`](/library/io/#waiting-with-a-time-limit-sysl-io-wait) answers it for one.

```sysl
struct Poller
    open() -> Result[Poller, IoError]
    register(self, fd: int, interest: []const Interest, token: u64, mode: Mode = Level) -> Result[unit, IoError]
    modify(self, fd: int, interest: []const Interest, token: u64, mode: Mode = Level) -> Result[unit, IoError]
    deregister(self, fd: int) -> Result[unit, IoError]
    wait(self, events: []Event, timeout: Option[Duration]) -> Result[usize, IoError]
    waker(self) -> Waker

struct Event
    token: u64
    readable: bool
    writable: bool
    hangup: bool
    error: bool
    static none -> Event

enum Mode
    Level
    OneShot
    Edge

struct Waker
    wake(self) -> Result[unit, IoError]
```

`Interest` is [`sysl.io.wait`](/library/io/#waiting-with-a-time-limit-sysl-io-wait)'s, `Readable` or
`Writable`, and a registration takes a list of them — `[Readable]`, or `[Readable, Writable]` for
both. A literal list there is laid out in the caller's frame, so registering allocates nothing either.

## A wait fills the caller's slice and allocates nothing

`wait` writes at most `events.len` events into the slice it is handed and answers how many. The kernel
uses the same storage as its scratch before the events are written into it, so **a server's loop runs
without the heap** — and the compiler holds `wait` to that: it is declared
[`@no_alloc`](/reference/attributes/#no-alloc-on-a-function-one-entry-point-that-makes-no-heap-storage),
so a caller that promises the same may call it:

```sysl
import sysl.io.poll.{Event, Poller}
import sysl.io.wait.Writable
import sysl.process.pipe
import sysl.time.millis

@no_alloc
turn(p: Poller) -> usize
    var got = [Event.none; 16]

    p.wait(got[..], Some(millis(0))).unwrap()

val p = Poller.open().unwrap()
val (r, w) = pipe().unwrap()

p.register(w.fd().unwrap(), [Writable], 7).unwrap()

print(turn(p), r.fd().is_ok())
```

```output
1 true
```

- **More ready than there are slots** is not a loss: the rest stay ready for the next wait.
- **A descriptor ready both ways may come back as one event or two** — epoll reports it once with both
  flags, kqueue each direction on its own — so a program acts on each flag rather than on the count.
- **`hangup` is the peer gone** (a read then answers the end of the input) and comes with `readable`;
  **`error` is a failure pending on the descriptor**, which the next call on it reports.
- **A timeout of `None` waits for ever**; zero asks once and does not wait. A signal arriving during a
  wait does not end it — the wait is asked for again with whatever of the time is still owed, as
  `ready` does. **An empty `events` answers zero at once**, there being nowhere to report anything.

## Three modes

A registration's `Mode` says how often a ready descriptor is reported:

| `Mode` | reported | kqueue | epoll |
|---|---|---|---|
| `Level` (the default) | at every wait, for as long as it is ready | — | — |
| `OneShot` | once, then not again until `modify` re-arms it | `EV_DISPATCH` | `EPOLLONESHOT` |
| `Edge` | once per change from not ready to ready | `EV_CLEAR` | `EPOLLET` |

`Level` is the one that cannot lose an event, and the place to start. `Edge` reads, or writes, until
the call answers [`would_block`](/library/net/) — an edge that is not drained is not reported again:

```sysl
import sysl.io.poll.{Edge, Event, Poller}
import sysl.io.wait.Readable
import sysl.process.pipe
import sysl.time.millis

val p = Poller.open().unwrap()
val (r, w) = pipe().unwrap()
var g = w
var got = [Event.none; 4]

g.write("a".bytes)
g.flush().unwrap()
p.register(r.fd().unwrap(), [Readable], 1, Edge).unwrap()

print(p.wait(got[..], Some(millis(1000))))
print(p.wait(got[..], Some(millis(10))))

g.write("b".bytes)
g.flush().unwrap()

print(p.wait(got[..], Some(millis(1000))))
```

```output
Ok(1)
Ok(0)
Ok(1)
```

**A one-shot registration stays registered** after it fires — the kqueue flag is `EV_DISPATCH`
rather than `EV_ONESHOT` for exactly that, so the two kernels agree — which is what lets `modify`
re-arm it and `deregister` remove it. It is the form for handing a ready descriptor to exactly one of
several threads waiting on one poller.

## What a registration refuses

- A descriptor that is not open, or is negative, is `Err(NotOpen)` — never a trap.
- An empty `interest`, and the token `u64::Max`, which is the poller's own for a wake, are the error of
  an invalid argument (code 22).
- `modify` and `deregister` of a descriptor that is not registered are `Err(NotFound)`.
- **A descriptor is registered once.** A second `register` is `AlreadyExists` under epoll and replaces
  the first under kqueue, so a program that means to change one calls `modify`.
- **A descriptor closed while registered leaves the set by itself**, under both kernels.

## Sharing a poller, and waking it

**Every copy of a `Poller` is a share of one kernel set**, held as a
[`&sync`](/reference/memory/#crossing-a-concurrency-domain), and the set is closed when the last copy
— and the last `Waker` — goes. A copy is what another thread is handed, and **`register`, `modify` and
`deregister` may be called from any thread while another sits in `wait`**: both kernels allow it, and
a registration made during a wait is seen by that wait. Two threads may wait on one poller at once;
which of them gets an event is the kernel's choice.

`waker()` answers a `Waker`, and its `wake` ends the poller's current wait — or, with nobody waiting,
the next one — from any thread. **A wake is never itself an event**: the wait it ends answers what
else was ready, which may be nothing, so a program that wakes its poller to say "look at your queue"
looks at its queue whatever the count. Wakes do not queue: several before a wait end that one wait.

```sysl
import sysl.io.poll.{Event, Poller}
import sysl.time.millis

val p = Poller.open().unwrap()
var got = [Event.none; 4]

p.waker().wake().unwrap()

print(p.wait(got[..], None))
print(p.wait(got[..], Some(millis(10))))
```

```output
Ok(0)
Ok(0)
```

The first wait has no time limit and still answers at once; the second waits its ten milliseconds,
the wake having been spent. Under kqueue the wake is an `EVFILT_USER` event on the set itself; under
epoll it is an `eventfd` registered in the set, drained by the wait that sees it.

## Where there is no kqueue or epoll

A hosted POSIX target with neither answers every call with the error
[`sysl.fs.unsupported()`](/library/fs/) stands for, as `sysl.io.wait` does where there is no `poll`.
Each call is `@needs(os)`. On a target with no C library the module reaches five hooks in
`sysl.io.sys` — `sysl_io_poll_open`, `sysl_io_poll_control`, `sysl_io_poll_wait`, `sysl_io_poll_wake`
and `sysl_io_poll_close` — which the program answers with an `@export` of its own, or is refused by
name when it is compiled.
