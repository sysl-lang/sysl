---
title: sysl.io.wait
layout: api-module
headingShift: 0
slugStyle: github
module: sysl.io.wait
summary: "Waiting for a descriptor with a time limit: whether a read, or a write, would go ahead without waiting, asked of the system and answered before the time runs out."
---

It is what a program reading one stream and also keeping time needs -- a server that coalesces
edits arriving close together, a terminal program that tells a lone `Escape` from the start of a
key sequence, a loop that reads when there is something and does its own work when there is not.
Reading itself is unchanged: `ready` answers whether to call `read`, and `read` still does the
reading.

**One descriptor and one direction, deliberately.** It is `poll(2)` on a single `pollfd`, the shape
mio's `Interest` gives a registration, without a set to manage: a program waiting on many
descriptors at once wants `sysl.io.poll`, the kernel's readiness set, which takes the same
`Interest`.

**A module of its own rather than part of `sysl.io`**, because what a wait fails with is
`sysl.fs.IoError`, and `sysl.fs` is built on `sysl.io`: the reading surface cannot name the
filesystem's error without the two depending on each other.

## Index

[`ready`](#ready) [`Interest`](#interest) [`Wait`](#wait) [Wait for FdReader](#wait-for-fdreader) [Wait for File](#wait-for-file)

## Functions

### `ready`

```sysl
ready(fd: int, interest: Interest, timeout: Duration) -> Result[bool, IoError]
```

Whether `fd` is ready for `interest` within `timeout`: `true` as soon as it is, `false` once
`timeout` has passed and it is not yet.

    if ready(0, Readable, 50.ms)? then serve(&input) else tick()

**It never answers `false` early.** The time is rounded up to the system's unit, and a signal
arriving during the wait does not cut it short: the wait is asked for again with whatever of
`timeout` is still owed, so an interrupted call ends when an uninterrupted one would have. A
`timeout` of zero, or less, asks once and does not wait.

A descriptor that is not open is `Err(NotOpen)`; a target whose system has no way to ask answers
`Err` of the error `sysl.fs.unsupported()` stands for. It needs an operating system: a
freestanding program reaching it answers the `sysl_io_wait` hook itself.

## Types

### `Interest`

```sysl
enum Interest
    Readable
    Writable
```

What a wait on a descriptor is for.

## Traits

### `Wait`

```sysl
trait Wait
    readable(self, timeout: Duration) -> Result[bool, IoError]
    writable(self, timeout: Duration) -> Result[bool, IoError]
```

`ready` on what a program already holds: standard input's reader, or an open `File` -- a pipe's
end, a terminal, a socket file -- asked of its own descriptor.

    import sysl.io.wait.Wait

    var input = stdin()

    while !input.readable(200.ms)?
        spin()

A `File` that has been closed answers `Err(NotOpen)`, as its other calls do. **Bytes a `File` has
read ahead are not on the descriptor any more**, so a file the program has been reading through may
hold a line `readable` cannot see; it answers for what the system still has.

| Member | Signature | Description |
|---|---|---|
| `readable` | `readable(self, timeout: Duration) -> Result[bool, IoError]` | Whether a read would go ahead within `timeout`. |
| `writable` | `writable(self, timeout: Duration) -> Result[bool, IoError]` | Whether a write would go ahead within `timeout`. |

## Implementations

### Wait for FdReader

```sysl
impl Wait for FdReader
```

### Wait for File

```sysl
impl Wait for File
```
