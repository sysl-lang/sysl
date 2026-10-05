---
title: The log module
summary: "`sysl.log` — levels, a sink trait, and three renderings: a line for a person, an object for a machine, and the bare message for a sink that stamps its own."
weight: 92
---

**Every declaration in `sysl.log`, with its signature:** [the generated API page](/api/sysl-log/#index). This page is the argument — what the module is for, and how its pieces fit; that one is the list.

Every server wants a logger and every package writes one, which is the argument for this being in the
library rather than fetched: a program that depends on three libraries should not link three loggers
and configure them separately. What is here is the part everybody has to agree on — a record, a
level, and somewhere for it to go. The interesting sinks are outside it, and are a dozen lines each.

```sysl
import sysl.log.{info, warn, set_level, Level}

set_level(Level.Info)

info("listening", [("port", "8080"), ("tls", "on")])
warn("slow request", [("ms", "912")])
```

The two lines land on standard error, which is where a program that has said nothing about a sink
gets them:

```text
2026-09-04 15:42:31 Z INFO  listening port=8080 tls=on
2026-09-04 15:42:31 Z WARN  slow request ms=912
```

That block is an illustration rather than a checked one: the time is `now()` and the stream is
standard error, so there is nothing a page could assert. Every other program below is run and its
output compared.

## A record is what happened, and a sink is where it goes

Four pieces, and the whole module is these:

| | |
|---|---|
| `Record` | when, how bad, what about, and a handful of named strings |
| `Sink` | one method — `write(*self, r: *Record)` |
| `text`, `json` and `message_text` | three renderings, each writing into a `Writer` — `message_text` is the message and fields alone, for a sink that stamps its own time and level |
| the threshold | `set_level`, `level`, `enabled` |

A `Sink` is a trait with one member, so writing one is a struct and four lines. `WriterSink` is here
because it composes with everything the library already has — a file is a `Writer`, standard error is
a `Writer`, a buffer is a `Writer`, and a UART on a board is a `Writer` the moment somebody has
written one:

```sysl
import sysl.buf.byte_sink
import sysl.log.{Level, json_sink, log_at, set_sink, clear_sink}
import sysl.text.from_utf8_unchecked
import sysl.time.Instant

var buffer = byte_sink()
var w: *Writer = &buffer
var sink = json_sink(w)

set_sink(&sink)
log_at(Instant(1757000551000 * 1000), Level.Error, "gave up", [("after", "3")])
clear_sink()

print(from_utf8_unchecked(buffer.text()))
```

```output
{"time":1757000551000,"level":"error","message":"gave up","fields":{"after":"3"}}

```

**The sink is a borrowed pointer and the caller keeps it alive.** That is a raw pointer rather than a
counted box on purpose: a box needs an allocator, and this module is meant to work where there is
none. What a program installs is nearly always module storage or something that lives as long as
`main`, which is exactly the case where a count would be paid for nothing.

## Fields are strings the caller rendered

That is the one decision here that shows up at every call site, and it is worth knowing why. The
alternative — a list of `&Display` — would put a trait object in every record and an allocation
behind every field. So a caller writes `("port", str(port))`, and the cost is paid where the
value is actually wanted.

**A filtered call still evaluates its arguments.** sysl evaluates a call's arguments before the call,
so that `str` runs whether or not the threshold admits the record: `log` compares the level and
returns without building a `Record`, and it never sees the work that was done to reach it. `enabled`
is the guard for that, and is why it exists:

```sysl
import sysl.log.{Level, debug, enabled, set_level}

set_level(Level.Warn)

// The `str` inside this would have run either way; the `if` is what stops it.
if enabled(Level.Debug) then debug("state", [("queue", str(41))])

print(enabled(Level.Debug), enabled(Level.Error))
```

```output
false true
```

A call whose fields are literals, or one with no fields at all, costs nothing when filtered.

## The threshold is an atomic and the sink is under a lock

Two different answers to the same question, and the difference is what each one is for.

**The threshold is read on every call, including the ones that are about to be discarded** — so
putting it under the lock would make a `debug` in a hot loop, the exact call the threshold exists to
make cheap, pay for an uncontended lock and serialize against every other thread doing the same. It
is a relaxed atomic load. What a program gives up is knowing whether a call already in flight when
`set_level` ran used the old value or the new one, which nothing can usefully depend on.

**The sink is taken under a lock held across the write**, because two threads whose lines interleave
produce output that is worse than either line missing, and a sink is not required to be atomic — a
line may reach its descriptor in several writes. What that costs is that a slow sink serializes the threads
logging to it, which is the trade every logger makes and is why an asynchronous sink is a thing
somebody writes.

It is a [`SpinLock`](/library/sync/) rather than a `Mutex` because this module asks for no
capability: a mutex blocks, blocking needs a scheduler, and a freestanding target has not got one.

## The time is an instant, and rendering it as a date is the sink's business

A `Record` carries an `Instant`, which is a count of microseconds and nothing else. `text` renders it
through [`sysl.time`](/library/time/)'s calendar and `json` renders it as epoch milliseconds — so a
program whose sink only ever calls `json` links no date code at all, the millisecond count being
arithmetic.

**`log_at` takes the instant and `log` reads the clock**, which is what makes the module usable on a
board with no clock: `sysl.time.now` reaches a seam the application supplies, and a program that has
nothing to supply calls `log_at` with whatever its own counter says.

```sysl
import sysl.buf.byte_sink
import sysl.log.{Level, log_at, writer_sink, set_sink, clear_sink}
import sysl.text.from_utf8_unchecked
import sysl.time.Instant

var buffer = byte_sink()
var w: *Writer = &buffer
var sink = writer_sink(w)

set_sink(&sink)
log_at(Instant(1757000551000 * 1000), Level.Warn, "careful", [])
clear_sink()

print(from_utf8_unchecked(buffer.text()))
```

```output
2025-09-04 15:42:31 Z WARN  careful

```

The level is padded to five characters in the human rendering so that a column of them lines up, and
is not padded in the JSON one — a value in a machine-read format is the value.

## What a bare-metal program does

The module itself is a record, an enum, two render functions and no operating system. What it needs
from a program with no `stderr` is a sink, and a sink is a struct and one method:

```sysl
import sysl.log.{Record, Sink, json}

struct Uart
end Uart

impl Sink for Uart
    write(*self, r: *Record) = json(r, uart())

// Whatever the board's serial port is, as a `Writer`.
uart() -> *Writer = stdout()

print("a sink is four lines")
```

```output
a sink is four lines
```

Nothing here allocates on the path a filtered call takes, the module state is three slots laid
straight into the image rather than filled by an initializer, and a program that never calls any of
it links none of it.

## What is deliberately not here

**Child loggers, contexts, rotation, colours, asynchronous delivery.** Each is a sink, and each is a
policy somebody disagrees with. A sink is one method; writing the one you want is a dozen lines and
does not require this module to have guessed.

**A formatting language.** The message is a string and the fields are strings; a caller wanting
interpolation has `f"…"` and knows what it costs.
