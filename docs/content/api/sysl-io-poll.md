---
title: sysl.io.poll
layout: api-module
headingShift: 0
slugStyle: github
module: sysl.io.poll
summary: "Waiting on many descriptors at once: a set of them registered with the kernel -- kqueue on Darwin and the BSDs, epoll on Linux -- and one call that answers which are ready."
---

It is the readiness primitive under a server that holds thousands of connections on a few threads:
register each socket with a token of the program's choosing, `wait`, and act on the tokens that come
back. **It is not an event loop.** Nothing here reads, writes, schedules or calls back; `wait`
answers which descriptors would not block, and the program does the rest, as `sysl.io.wait.ready`
answers it for one.

    val p = Poller.open()?
    var got = [Event.none; 64]

    p.register(listener.fd()?, [Readable], 0)?

    loop
        for e in got[0..<p.wait(got[..], None)?]
            serve(e.token)

**A wait allocates nothing**: the events are written into the slice the caller hands it, which the
kernel also uses as its scratch, so a server's loop runs without the heap.

**One poller is shared between threads.** Every copy of a `Poller` is a share of one set, held as a
`&sync`, and `register`, `modify` and `deregister` may be called from any thread while another sits
in `wait` -- both kernels allow it,
and a registration made during a wait is seen by that wait. A `Waker` ends a wait from anywhere.
Two threads waiting on one poller at once is allowed and each gets some of the events, but which is
the kernel's choice.

A target with neither kqueue nor epoll answers every call with the error `sysl.fs.unsupported()`
stands for, as `sysl.io.wait` does for a system without `poll`. It needs an operating system: a
freestanding program reaching it answers the `sysl_io_poll_*` hooks itself (`sysl.io.sys`).

## Index

[`Event`](#event) [`Mode`](#mode) [`Poller`](#poller) [`Waker`](#waker) [Drop for PollerState](#drop-for-pollerstate)

## Types

### `Event`

```sysl
struct Event
    token: u64
    readable: bool
    writable: bool
    hangup: bool
    error: bool
    private spare: [20]u8
```

One descriptor found ready by `wait`: the token it was registered with, and how it is ready.

**A descriptor ready both ways may come back as one event or as two** -- epoll reports a descriptor
once with both, kqueue reports each direction on its own -- so a program acts on each flag rather
than on the count. `hangup` is the peer having gone (the read then answers the end of the input),
and `error` a failure pending on the descriptor, which the next call on it reports.

It is 32 bytes laid out for the hook that fills it (`sysl.io.sys`); `Event.none` is the value a
buffer of them starts as.

| Member | Signature | Description |
|---|---|---|
| `none` | `static none -> Event` | An event with no token and no readiness: what a buffer is filled with before a `wait`. |

### `Mode`

```sysl
enum Mode
    Level
    OneShot
    Edge
```

How a registration reports readiness.

### `Poller`

```sysl
struct Poller
    private[poll] state: &sync PollerState
```

A kernel readiness set.

**It closes itself**: every copy shares one set, which is closed when the last copy -- and the last
`Waker` -- goes. A copy is what another thread is handed; `register`, `modify` and `deregister` may
be called through one while another thread waits through another.

| Member | Signature | Description |
|---|---|---|
| `open` | `open() -> Result[Poller, IoError]` | A new, empty set. |
| `register` | `register(self, fd: int, interest: []const Interest, token: u64, mode: Mode = Level) -> Result[unit, IoError]` | Watch `fd` for each of `interest`, reporting it with `token`. |
| `modify` | `modify(self, fd: int, interest: []const Interest, token: u64, mode: Mode = Level) -> Result[unit, IoError]` | Replace what `fd` is watched for, its token and its mode -- and re-arm a `OneShot` that has fired. |
| `deregister` | `deregister(self, fd: int) -> Result[unit, IoError]` | Stop watching `fd`; no event for it is reported by a `wait` that begins afterwards. |
| `wait` | `wait(self, events: []Event, timeout: Option[Duration]) -> Result[usize, IoError]` | Wait at most `timeout` -- for ever where it is `None` -- for something registered to be ready, writing what is into `events` and answering how many. |
| `waker` | `waker(self) -> Waker` | Something that ends this poller's `wait` from any thread: a share of the poller, which stays open while a waker lives. |

### `Waker`

```sysl
struct Waker
    private[poll] state: &sync PollerState
```

Ends a `wait` on its poller, from any thread.

**Wakes do not queue.** Several before a wait begins end that one wait, and a wake with nobody
waiting ends the next one at once -- so a program checks its own state after every wait rather than
counting wakes.

| Member | Signature | Description |
|---|---|---|
| `wake` | `wake(self) -> Result[unit, IoError]` | End the poller's current or next `wait`. |

## Implementations

### Drop for PollerState

```sysl
impl Drop for PollerState
```

Both descriptors closed. A failure is not reported, there being nobody to report it to
(`sysl.Drop`).
