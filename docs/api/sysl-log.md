---
title: sysl.log
layout: api-module
headingShift: 0
slugStyle: github
module: sysl.log
summary: "Structured logging: a level, a message, a few named fields, and somewhere for them to go."
---

**Every server wants one and every package writes one**, which is the argument for it being here
rather than in a package: a program that depends on three libraries should not link three loggers
and configure them separately. Go, Python and Rust all ship the interface in the standard library
for the same reason, and leave the interesting sinks outside it.

## The shape

A **`Record`** is what happened: when, how bad, what about, and a handful of named strings. A
**`Sink`** is where it goes -- one method, taking the record by pointer. **`text`** and **`json`**
are two renderings, each writing into a `Writer`, and a sink is what chooses between them;
**`message_text`** is the third, for a destination that stamps its own time and level. That is
the whole of the module; everything else people want from a logger is a sink somebody writes.

```
info("listening", [("port", "8080"), ("tls", "on")])
```

## What it costs, and where

**A record holds strings the caller rendered**, which is the one decision here that shows up at
every call site. The alternative -- a list of `&Display` -- would put a trait object in every
record and an allocation behind every field. What a caller writes instead is
`("port", str(port))`, which does allocate, and the cost is paid where the value is
actually wanted.

**A filtered call still evaluates its arguments, and that is the one thing to know before putting
a `debug` in a loop.** sysl evaluates a call's arguments before the call, so the `str` above
runs whether or not the threshold admits the record -- `log` compares the level and returns
without building a `Record`, and it never sees the work that was done to reach it. `enabled` is
the guard for that case and is why it exists:

```
if enabled(Level.Debug) then debug("state", [("queue", str(queue.len()))])
```

A call whose fields are literals, or one with no fields at all, costs nothing when filtered.

**The time is an `Instant`**, which is a count of microseconds and nothing else. Rendering it as a
date is the calendar's job and `text` is the only thing here that asks for one, so a program whose
sink calls `json` links no calendar code at all -- the millisecond count is arithmetic.

**`log_at` takes the instant and `log` reads the clock**, which is what makes the module usable on
a board with no clock at all: `sysl.time.now` reaches a seam the application supplies, and a
program that has nothing to supply calls `log_at` with whatever its own counter says.

## What is deliberately not here

**Child loggers, contexts, rotation, colours, asynchronous delivery.** Each is a sink, and each is
a policy somebody disagrees with. A sink is one method; writing the one you want is a dozen lines
and does not require this module to have guessed.

**A formatting language.** The message is a string and the fields are strings; a caller wanting
interpolation has `f"..."` and knows what it costs.

## Index

[`clear_sink`](#clear_sink) [`debug`](#debug) [`enabled`](#enabled) [`error`](#error) [`info`](#info) [`json`](#json) [`json_sink`](#json_sink) [`level`](#level) [`log`](#log) [`log_at`](#log_at) [`message_text`](#message_text) [`set_level`](#set_level) [`set_sink`](#set_sink) [`stderr_sink`](#stderr_sink) [`text`](#text) [`warn`](#warn) [`writer_sink`](#writer_sink) [`Level`](#level-1) [`Record`](#record) [`WriterSink`](#writersink) [`Field`](#field) [`Sink`](#sink) [Sink for WriterSink](#sink-for-writersink)

## Functions

### `clear_sink`

```sysl
clear_sink()
```

Records go back to where they went before anything was installed -- standard error on a hosted
target, and nowhere at all on one with no operating system.

### `debug`

```sysl
debug(message: string, fields: []const Field)
```

What a program is doing, in detail nobody wants by default.

### `enabled`

```sysl
enabled(l: Level) -> bool
```

Whether a record at `l` would be written, which is the guard a caller in a hot path puts in front
of the work of rendering its fields.

```
if enabled(Level.Debug) then debug("state", [("queue", str(queue.len()))])
```

### `error`

```sysl
error(message: string, fields: []const Field)
```

Something that did not work.

### `info`

```sysl
info(message: string, fields: []const Field)
```

Something worth knowing that is not a problem.

### `json`

```sysl
json(r: *Record, out: *Writer)
```

One record, one JSON object, meant for a machine.

```
{"time":1757000551000,"level":"info","message":"listening","fields":{"port":"8080"}}
```

**The time is epoch milliseconds**, which is what every log pipeline reads and is arithmetic
rather than a calendar -- so this rendering pulls in no date code at all. The fields are an object
rather than an array of pairs, because that is what a query language expects; a repeated key is
therefore the caller's problem, exactly as it is in every other logger.

The level is lowercase, which is the convention every JSON logger follows.

### `json_sink`

```sysl
json_sink(out: *Writer) -> WriterSink
```

A sink writing the JSON rendering into `out`, which is what a program feeding a log pipeline
installs.

### `level`

```sysl
level() -> Level
```

What the threshold is now.

### `log`

```sysl
log(l: Level, message: string, fields: []const Field)
```

A record at `l`, timestamped now.

### `log_at`

```sysl
log_at(t: Instant, l: Level, message: string, fields: []const Field)
```

A record at `l`, timestamped `t`, delivered if the threshold admits it.

**This is the one to call where there is no clock**, and `log` is this with `sysl.time.now()` in
front of it. A freestanding program that has not supplied the clock seam links this and nothing
from `sysl.time` but the `Instant` type.

### `message_text`

```sysl
message_text(r: *Record, out: *Writer)
```

The message and its fields alone, quoted exactly as `text` quotes them, with no time, no level
and no newline.

```
underrun frames=512
```

**This is the rendering for a destination that stamps its own time and level** -- Android's
logcat, syslog, the systemd journal -- where `text` would write both a second time inside an
entry that already shows them. Such a destination frames each entry itself, which is why there is
no newline either.

Nothing here reaches the calendar, so a sink that calls only this links no date code. An empty
message with fields starts at the first field rather than at a space.

### `set_level`

```sysl
set_level(l: Level)
```

Records below this level are discarded before anything is built. `Level.Info` to begin with,
which is what a program that has said nothing about it wants.

**The threshold is an atomic rather than something the lock protects**, which is the one place
this module cares about the difference. A filtered call reads it and returns, so putting it under
the lock would make a `debug` in a hot loop -- the exact call the threshold exists to make cheap
-- cost an uncontended lock and, worse, serialize against every other thread doing the same. A
relaxed load is one instruction and the only thing a program gives up is knowing whether a call
already in flight when `set_level` ran used the old value or the new one, which nothing can
usefully depend on.

It is held as the level's position rather than as a `Level`, since that is what an atomic
operates on and the ordering of the enum is the ordering being compared.

### `set_sink`

```sysl
set_sink(s: *Sink)
```

Where records go from now on.

**The pointer is borrowed and the caller keeps the sink alive.** That is a raw pointer rather than
a counted box on purpose: a box needs an allocator, and this module is meant to work where there
is none. The sink a program installs is nearly always module storage or something that lives as
long as `main`, which is exactly the case where a count would be paid for nothing.

Passing a pointer to something that then goes out of scope is the one way to misuse this, and it
is the same shape as any other borrowed pointer in the language.

### `stderr_sink`

```sysl
stderr_sink() -> WriterSink
```

The default, as a sink a program can install explicitly -- which is what it wants after having
installed something else and wanting the default back with a different rendering.

`clear_sink` reaches the same output without going through a sink at all, so a program that only
wants the default never names this.

### `text`

```sysl
text(r: *Record, out: *Writer)
```

One record, one line, meant for a person: the time, the level, the message, then the fields.

```
2026-09-04 11:02:31 Z INFO  listening port=8080 tls=on
```

**This is the rendering that reaches the calendar**, through `sysl.time`'s own `Display` for an
`Instant`. A sink that only ever calls `json` links none of it.

A value with a space in it is quoted, and one without is not -- which keeps the common line
readable and keeps a value with a space in it parseable, which is the whole of what a logfmt-like
format promises.

### `warn`

```sysl
warn(message: string, fields: []const Field)
```

Something that will be a problem if it keeps happening.

### `writer_sink`

```sysl
writer_sink(out: *Writer) -> WriterSink
```

A sink writing the human rendering into `out`.

## Types

### `Level`

```sysl
enum Level
    Debug
    Info
    Warn
    Error
```

How bad it is. The order is the severity order, which is what the threshold compares on -- so a
level added between two of these would change what an existing threshold admits.

### `Record`

```sysl
struct Record
    time: Instant
    level: Level
    message: string
    fields: []const Field
```

Something that happened, as the sink is handed it.

**The fields are a view rather than a copy**, so a caller may build them in an array on its own
stack and the record borrows them for the length of the call. A sink that wants to keep a record
has to copy what it needs, which is the same promise `Writer.write` makes about its bytes and for
the same reason.

### `WriterSink`

```sysl
struct WriterSink
    private out: *Writer
    private as_json: bool
```

A sink over any `Writer`, rendering each record with one of the two renderings.

**This is the sink nearly every program wants, and it is here because it is the one that composes
with everything else the library already has**: a file is a `Writer`, standard error is a
`Writer`, a buffer is a `Writer`, and a UART on a board is a `Writer` the moment somebody has
written one. What is left for a program to write itself is a sink that does something other than
render a record and hand it to a stream -- batching, filtering, sending it somewhere.

**It holds a borrowed pointer**, so whatever it writes into has to outlive it, exactly as
`set_sink` says of the sink itself.

## Aliases

### `Field`

```sysl
type Field = (string, string)
```

One named string on a record. A pair rather than a struct, because that is all it is and a tuple
of two strings already compares, orders, hashes and renders.

## Traits

### `Sink`

```sysl
trait Sink
    write(*self, r: *Record)
```

Where a record goes.

One method, taking the record by pointer so that nothing is copied and a sink may be written
under `no alloc`. A UART sink is a dozen lines:

```
struct Uart
end Uart

impl Sink for Uart
    write(*self, r: *Record) = json(r, uart_writer())
```

| Member | Signature | Description |
|---|---|---|
| `write` | `write(*self, r: *Record)` |  |

## Implementations

### Sink for WriterSink

```sysl
impl Sink for WriterSink
```
