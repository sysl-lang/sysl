---
title: sysl.signal
layout: api-module
headingShift: 0
slugStyle: github
module: sysl.signal
summary: "Signals as flags: a program asks to be told that one arrived, and asks again later whether it has."
requires: "requires { os }"
---

**No sysl code ever runs inside a signal handler, and that is the whole design.** A handler runs
between two instructions of whatever the program was doing, so a body that allocates, releases a
counted reference or takes a lock is one that can deadlock or corrupt the very thing it interrupted
-- and that is nearly every sysl body. So `watch` installs the *target's* handler, whose one act is
to count the arrival with a lock-free atomic, and the program reads the count where it chooses: at
the top of a loop, between two jobs, before a prompt. It is the shape of Rust's `signal-hook` flag
and Go's `os/signal` without the channel, and it is the one shape that is safe everywhere.

**The numbers are the target's own.** `TSTP`, `CONT`, `CHLD`, `USR1` and `USR2` are different
numbers on Linux and on a BSD, so each constant here is written per platform rather than once; a
program that writes `signal.TSTP` means what the machine it was compiled for means. A target with
no C library takes Linux's numbering, which is the one a kernel written for a POSIX-shaped user
space usually copies; one that numbers differently passes its own integers -- every function here
takes a plain `int`, the type `sysl.process`'s `Child.kill` and `Status.Signalled` speak too.

Everything it asks of the machine goes through the hooks of `sysl.signal.sys`, which the library
answers over `sigaction` on a POSIX host and a program answers itself, with an `@export` per hook,
on a target with no C library.

## Index

[`ALRM`](#alrm) [`CHLD`](#chld) [`CONT`](#cont) [`HUP`](#hup) [`INT`](#int) [`KILL`](#kill) [`PIPE`](#pipe) [`QUIT`](#quit) [`STOP`](#stop) [`TERM`](#term) [`TSTP`](#tstp) [`TTIN`](#ttin) [`TTOU`](#ttou) [`USR1`](#usr1) [`USR2`](#usr2) [`WINCH`](#winch) [`ignore`](#ignore) [`raise`](#raise) [`restore_default`](#restore_default) [`send`](#send) [`watch`](#watch) [`Watch`](#watch-1) [Drop for Watch](#drop-for-watch)

## Constants

### `ALRM`

```sysl
const ALRM: int = 14
```

Alarm: a timer the program set has run out.

### `CHLD`

```sysl
const CHLD: int = 20
```

A child of the program stopped or ended.

### `CONT`

```sysl
const CONT: int = 19
```

Continue: resumes a stopped program.

### `HUP`

```sysl
const HUP: int = 1
```

Hangup: the terminal the program was started from has gone.

### `INT`

```sysl
const INT: int = 2
```

Interrupt: Ctrl-C at the terminal.

### `KILL`

```sysl
const KILL: int = 9
```

Kill: ends the program and cannot be watched, ignored or caught. `send` is the only thing here
that takes it.

### `PIPE`

```sysl
const PIPE: int = 13
```

A write into a pipe or socket nobody is reading any more.

### `QUIT`

```sysl
const QUIT: int = 3
```

Quit: Ctrl-\ at the terminal, which by default also leaves a core file.

### `STOP`

```sysl
const STOP: int = 17
```

Stop: stops the program and, like `KILL`, cannot be watched, ignored or caught.

### `TERM`

```sysl
const TERM: int = 15
```

Terminate: asks the program to stop, which `Child.kill` sends by default.

### `TSTP`

```sysl
const TSTP: int = 18
```

Terminal stop: Ctrl-Z at the terminal.

### `TTIN`

```sysl
const TTIN: int = 21
```

Ttin: a background job tried to read from its terminal.

### `TTOU`

```sysl
const TTOU: int = 22
```

Ttou: a background job tried to write to its terminal, or change it.

### `USR1`

```sysl
const USR1: int = 30
```

User-defined signal 1: no meaning but the one a program gives it.

### `USR2`

```sysl
const USR2: int = 31
```

User-defined signal 2.

### `WINCH`

```sysl
const WINCH: int = 28
```

Window change: the terminal was resized.

## Functions

### `ignore`

```sysl
ignore(sig: int) -> Result[unit, IoError]
```

Makes `sig` do nothing when it arrives -- what a shell does with `TTOU`, `TTIN`, `TSTP` and `INT`
for itself, so that only the job in the foreground hears them.

Refused as `EINVAL` where `watch` would be, and as `IoError.Other(16)` (`EBUSY`) for a signal a
`Watch` is alive for: the watch owns the disposition until it is dropped.

### `raise`

```sysl
raise(sig: int) -> Result[unit, IoError]
```

Sends `sig` to this program itself -- to the thread that calls it -- and, unless the signal is
blocked, has it handled before returning: a watched one is counted, an ignored one discarded, and
one left at its default does what the default does. Refused as `send` is.

### `restore_default`

```sysl
restore_default(sig: int) -> Result[unit, IoError]
```

Gives `sig` back the action the system takes when nobody has said otherwise -- what a shell does
for a child it is about to start, which inherits the parent's ignores. Refused as `ignore` is.

### `send`

```sysl
send(pid: i64, sig: int) -> Result[unit, IoError]
```

Sends `sig` to the process `pid`, read as POSIX's `kill(2)` reads it: zero is this program's
process group, and a negative number the group `-pid`. A `sig` of zero sends nothing and answers
whether one could have been sent.

A pid that names no process is `IoError.Other(3)` (`ESRCH`); one this program may not signal is
`NotPermitted`; a signal number this platform does not have is `EINVAL`, refused before anything
is sent. `Child.kill` is the same call aimed at a child `sysl.process` started.

### `watch`

```sysl
watch(sig: int) -> Result[&Watch, IoError]
```

Starts counting `sig` for this program, answering the `Watch` to ask about it.

Refused as `IoError.Other(22)` (`EINVAL`) for a number this platform does not have, and for `KILL`
and `STOP`, which nothing may catch.

## Types

### `Watch`

```sysl
struct Watch
    private sig: int
    private seen: i64
```

A signal being counted for this program, from `watch`.

**`take` is the whole interface**: it answers whether the signal has arrived since the last `take`
-- or since the watch began -- and marks every arrival so far as seen. Arrivals that come between
two `take`s are one `true`, not several, which is what a flag is; an arrival during a `take` is
never lost, only left for the next one, since what is compared is a count that only goes up.

**Two watches of one signal each see it.** Each keeps the count it last saw, so a library watching
`WINCH` for its own reasons takes nothing from the program that watches it too.

**Dropping the last watch of a signal puts back what was there before the first** -- the default,
an ignore a parent left, or a handler C code installed. It is a counted reference so that the drop
happens once, where the last holder lets go of it.

| Member | Signature | Description |
|---|---|---|
| `signal` | `signal(self) -> int` | The signal this watch counts. |
| `take` | `take(*self) -> bool` | Whether the signal has arrived since the last `take`, marking it seen. |

## Implementations

### Drop for Watch

```sysl
impl Drop for Watch
```
