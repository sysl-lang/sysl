---
title: The signal module
summary: "`sysl.signal` — signals as flags: `watch` and `Watch.take`, `ignore` and `restore_default`, `send` and `raise`, the target's own numbers, and why no sysl code ever runs inside a handler."
weight: 75
---

`sysl.signal` lets a program hear a signal without running anything when it arrives. `watch`
starts counting one; the `Watch` it answers says, each time it is asked, whether the signal has
arrived since the last time:

```sysl
import sysl.signal.{USR1, raise, watch}

val w = watch(USR1).unwrap()

print(w.take())

raise(USR1).unwrap()

print(w.take())
print(w.take())
```

```output
false
true
false
```

`raise` sends a signal to the program itself and has it handled before it returns, which is what
makes the second line `true` rather than "true eventually". A signal from another process arrives
whenever it arrives, and the program finds out at its next `take`: at the top of a loop, between two
jobs, before a prompt.

## Why a flag and not a handler

**No sysl code ever runs inside a signal handler.** A handler runs between two instructions of
whatever the program was doing — halfway through a `Buf.push`, inside the allocator, holding a lock
— so a body that allocates, releases a counted reference that might reach zero, or prints through a
buffered writer can deadlock or corrupt the very thing it interrupted. That rules out nearly every
sysl body, and a rule a reader has to remember for one kind of function is a rule that gets broken.

So the handler `watch` installs is the **target's**: on a POSIX host, four lines of C beside the
library whose one act is to add one to a per-signal count with a lock-free atomic. The program reads
the count where it chooses, which is ordinary code at an ordinary moment. It is the shape of Rust's
`signal-hook` flag and of Go's `os/signal` without the channel, and it is the one shape that is safe
everywhere a signal can land.

**Arrivals between two `take`s are one `true`**, because a flag is what a program usually wants —
"the user pressed Ctrl-C", "the window changed size" — and three resizes since the last redraw are
one redraw:

```sysl
import sysl.signal.{USR2, raise, watch}

val w = watch(USR2).unwrap()

raise(USR2).unwrap()
raise(USR2).unwrap()

print(w.take())
print(w.take())
```

```output
true
false
```

An arrival *during* a `take` is never lost, only left for the next one: what a `Watch` compares is a
count that only goes up, not a bit that has to be cleared at the right moment.

## Two watches each see it

A program and a library inside it may both want `WINCH`. **Each `Watch` keeps the count it last saw**,
so one taking the signal takes nothing from the other:

```sysl
import sysl.signal.{USR1, raise, watch}

val mine = watch(USR1).unwrap()
val theirs = watch(USR1).unwrap()

raise(USR1).unwrap()

print(mine.take())
print(theirs.take())
```

```output
true
true
```

A `Watch` is a counted reference, and **dropping the last one for a signal puts back whatever was
there before the first** — the default, an ignore a parent left, a handler some C code installed. A
program that watches `INT` for the length of one long job and lets the watch go afterwards is back to
a Ctrl-C that ends it.

## Ignoring, and giving the default back

`ignore` makes a signal do nothing at all, and `restore_default` gives it back the action the system
takes when nobody has said otherwise. They are a shell's two moves: it ignores `TTOU`, `TTIN`, `TSTP`
and `INT` for itself, so that only the job in the foreground hears them, and gives a child it is about
to start the defaults back — an ignored signal stays ignored across `exec`, so a child would otherwise
inherit the shell's deafness.

```sysl
import sysl.signal.{USR2, ignore, raise}

ignore(USR2).unwrap()
raise(USR2).unwrap()

print("still here")
```

```output
still here
```

**A signal a `Watch` is alive for cannot be ignored or reset under it** — the watch owns the
disposition until it is dropped, and setting it from elsewhere would leave a `Watch` that never
sees anything again. It answers `EBUSY`:

```sysl
import sysl.signal.{INT, ignore, watch}

val w = watch(INT).unwrap()

print(ignore(INT).unwrap_err().code())
```

```output
16
```

## Sending

`send(pid, sig)` is POSIX's `kill(2)`: a pid above zero is one process, zero is this program's
process group, and a negative number the group `-pid`. A signal of zero sends nothing and answers
whether one could have been sent. `Child.kill` in [`sysl.process`](/library/process/) is the same call
aimed at a child that module started, and keeps working beside this one.

```sysl
import sysl.signal.{TERM, send}

print(send(2147483000, TERM).unwrap_err().code())
```

```output
3
```

That is `ESRCH`, no such process. Every failure is an `IoError` with the platform's code, as
[`sysl.fs`](/library/fs/)'s are.

## The numbers are the target's own

`TSTP`, `CONT`, `CHLD`, `USR1` and `USR2` are different numbers on Linux and on a BSD — `TSTP` is 20
on one and 18 on the other — so each constant is written per platform rather than once, and
`signal.TSTP` means what the machine the program was compiled for means. The module names `HUP`,
`INT`, `QUIT`, `KILL`, `PIPE`, `ALRM`, `TERM`, `CHLD`, `CONT`, `STOP`, `TSTP`, `TTIN`, `TTOU`,
`USR1`, `USR2` and `WINCH`; every function takes a plain `int`, the type `Child.kill` and
`Status.Signalled` speak, so a number the module does not name is still one a program can pass.

A target with no C library takes Linux's numbering, which is the one a kernel written for a
POSIX-shaped user space usually copies. A number this platform does not have is refused before
anything is touched, as `EINVAL`, and so are `KILL` and `STOP` to anything but `send`, since POSIX
lets no program catch or ignore either:

```sysl
import sysl.signal.{KILL, watch}

print(watch(KILL).unwrap_err().code())
print(watch(0).unwrap_err().code())
```

```output
22
22
```

## The hooks underneath

Everything above reaches the machine through `sysl.signal.sys` and through nothing else: five
`extern`s — `sysl_sig_watch` (put a signal in the counting handler, keeping what it replaced),
`sysl_sig_arrivals` (the count so far), `sysl_sig_disposition` (default, ignored, or what `watch`
replaced), `sysl_sig_send` and `sysl_sig_raise`. They keep the contract of every `*.sys` module: zero
or more is success, a negative answer is an `IoError` code negated, and `UNSUPPORTED` is a target that
cannot make the call at all.

On a hosted POSIX target the library answers them over `sigaction`, `kill` and `raise`, under `weak`
exports ([a module may supply another module's extern](/reference/ffi/)). **A kernel answers them
with its own calls**, and its runtime's handler counts exactly as the library's does — the sysl side
never learns which:

```sysl
@export("sysl_sig_arrivals")
k_arrivals(sig: int) -> i64 = 0
```

A hook the program reaches and leaves unanswered is refused when it is compiled, all of them in one
sentence, rather than at the link:

```sysl target=aarch64-freestanding
import sysl.signal.{INT, watch}

watch(INT) match
    Ok(w) ->
        val _ = w.take()
    Err(_) -> ()
```

```error
this program reaches 'sysl.signal', and 'aarch64-freestanding' has no operating system under it for the standard library to answer a signal with, so the program answers it: define 'sysl_sig_arrivals', 'sysl_sig_disposition', 'sysl_sig_watch' with '@export', each taking what its 'extern' in 'sysl.signal.sys' declares and answering a count or zero, or the code of an 'IoError' negated
```

## What is deliberately absent

- **A handler of the program's own.** Not deferred, refused: see [why a flag](#why-a-flag-and-not-a-handler).
  A program that has to *act* on a signal acts on it after the next `take`.
- **Blocking a signal** (`sigprocmask`). A watched signal is counted whenever it lands, so there is no
  window to close; a program that wants one deferred simply does not `take` until it is ready.
- **Waking a blocked call.** The handler is installed with `SA_RESTART`, so a `read` a signal lands
  in is resumed rather than failed: a program that must notice a signal while it waits gives the wait
  a timeout and takes between tries.
- **Process groups and the terminal's foreground group** are about starting a child, and belong to
  [`sysl.process`](/library/process/).
