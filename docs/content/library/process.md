---
title: The process module
summary: "`sysl.process` — starting another program and waiting for it: `run`, `capture`, `start` and `Child`, `Status`, how long a child may take, `exec` to become another program, and why there is no shell or `fork` anywhere in it."
weight: 74
---

**Every declaration in `sysl.process`, with its signature:** [the generated API page](/api/sysl-process/#index). This page is the argument — what the module is for, and how its pieces fit; that one is the list.

`sysl.process` starts another program and waits for what it does. Two functions: `run`, which lets
the child share this program's streams, and `capture`, which collects what it wrote — and a third,
`start`, for when the waiting should come [later](#starting-now-waiting-later).

```sysl
import sysl.process.{run, capture}
import sysl.text.Search

// `true` and `false` are on every hosted system and do exactly what their names say.
print(run("true").unwrap())
print(run("false").unwrap())

val out = capture("echo", ["hello from a child"]).unwrap()

print(out.text.trim())
print(out.status.ok())
```

```output
exited
exited 1
hello from a child
true
```

It requires `posix`. On a hosted target the whole of the mechanism is `posix_spawn`, or `fork` and
`execvp` — the `PATH` search below is `execvp`'s own — and neither exists outside POSIX: a hosted
target that is not POSIX has no way to start a program. WASI preview1 is the case that made that
visible, having files and a clock and no way to spawn at all. **A target with no C library can still
start one, if the program says how** — a kernel with its own process calls answers the module's five
hooks itself, [below](#on-a-target-with-no-c-library).

## A program that fails is not a failure

**`Err` is for a child that could not be *started*.** A program that ran and exited non-zero is
`Ok`, carrying a `Status` that says so — it did start, and its exit status is an answer rather than
a failure of the call.

```sysl
import sysl.process.run

run("sysl-no-such-program") match
    Ok(s) -> print("it ran, and", s)
    Err(e) -> print("it did not start:", e)

run("false") match
    Ok(s) -> print("it ran, and", s)
    Err(e) -> print("it did not start:", e)
```

```output
it did not start: no such file or directory
it ran, and exited 1
```

The error half is [`sysl.fs`](/library/fs/)'s `IoError`, so a missing program is `NotFound` and one
that is not executable is `PermissionDenied` — the same cases, from the same numbers.

**Telling those two apart is not free, and most languages do not.** A child that cannot exec has no
way to return, so the conventional answer is to exit `127` — which is indistinguishable from a
program that ran and chose to exit `127`. This module's child reports the failure through a
close-on-exec pipe instead, so `NotFound` means what it says.

## Nothing goes through a shell

The arguments are a list rather than one string, and the list is handed to the program exactly as
written. There is no quoting to get right because there is nothing to quote for.

```sysl
import sysl.process.capture
import sysl.text.Search

// Under a shell this would be three words and a second command. It is one argument.
val out = capture("echo", ["one; two", "three four"]).unwrap()

print(out.text.trim())
```

```output
one; two three four
```

A filename with a space in it is one argument, and one with a `;` in it is not a second command.

## How a child ended

`Status` keeps its cases apart, because they are not the same kind of answer: an exit status is
something the program chose, a signal is something that happened to it, and `TimedOut` is this
program deciding it had waited long enough.

```sysl
import sysl.process.Status

print(Status.Exited(0))
print(Status.Exited(2))
print(Status.Signalled(9))
print(Status.TimedOut)

print(Status.Exited(0).ok())
print(Status.Exited(137) == Status.Signalled(9))
print(Status.TimedOut == Status.Signalled(9))
```

```output
exited
exited 2
killed by signal 9
timed out
true
false
false
```

**A shell folds the first two together as `128 + n`**, which makes a program killed by `SIGKILL`
indistinguishable from one that deliberately exited `137`. The fifth line is that distinction.

**And the last line is the same argument once more.** A child stopped for running past its deadline
really was killed by a signal, so reporting `Signalled` would be true and useless: the caller set
the bound and knows what stopped it, and what it wants to say is "it took too long" rather than "it
crashed" — one of those is worth retrying and the other is not.

**A child ended for a fault is `Signalled`**, with the number POSIX raises for that fault — 11
(`SIGSEGV`) for an address it could not touch, 4 (`SIGILL`) for an instruction it could not run.
That is what a POSIX host reports, and what a kernel answering the hooks itself is asked to report
too, so `Signalled(11)` means the same thing on both.

**A started child can be sent a signal of your own choosing**: `Child.kill(signal)`, 15 by default,
which asks it to stop, and 9 to make it. Its `wait` then says the signal ended it:

```sysl
import sysl.process.start

val c = start("sleep", ["5"]).unwrap()

c.kill(9).unwrap()
print(c.wait().unwrap().status)
```

```output
killed by signal 9
```

A child already waited for is not signalled — the kernel forgot it at the wait, and its pid may by
then be somebody else's — and `kill` answers `Ok` without doing anything.

## Where it starts, and what it can see

Both calls take a directory and a list of variables. The directory is where the child starts —
**this program does not move** — and an empty one means wherever it already is.

```sysl
import sysl.process.{capture, Var}
import sysl.text.Search

val out = capture("printenv", ["GREETING"], "", [Var("GREETING", "hello")]).unwrap()

print(out.text.trim())
```

```output
hello
```

The variables are **added** to what this program has rather than replacing it, so the child keeps its
`PATH` and its `HOME`. The child's environment is built for it and handed over as it starts, and this
program's own environment is untouched — which is why [`sysl.env`](/library/env/) has no `set` and
does not want one.

**`PATH` is the one whose effect starts before the child does.** Because the variables are in place
before the program is looked up, setting it decides *where the program is looked for*. A caller
handing a child a `PATH` meant for its own children should name the program by an absolute path.

**`inherit_env = false` makes the list the child's whole environment** — nothing of this program's
comes with it, and an empty list is an empty environment. It is what an interpreter's `run` wants, or
a test that must not see the variables of whatever started it. `run`, `capture` and `start` all take
it, as their last parameter:

```sysl
import sysl.process.{capture, Var}
import sysl.text.Search

val out = capture("/usr/bin/env", env = [Var("GREETING", "hello")], inherit_env = false).unwrap()

print(out.text.trim())
```

```output
GREETING=hello
```

A replaced environment with a `PATH` in it is searched as above. **One without a `PATH` leaves the
child none**, and a bare program name is then looked for on the system's default search path
(`confstr(_CS_PATH)` — `/usr/bin:/bin:/usr/sbin:/sbin` on macOS, `/bin:/usr/bin` with glibc), which
is where `execvp` looks in a process that has no `PATH`. It is never this program's `PATH`: the child
was asked to inherit nothing, and where its program is found is part of that.

## Capture goes through a file, not a pipe

Deliberate, and worth knowing rather than hiding: a pipe has a buffer, and a parent that waits for a
child while the child waits for the parent to drain that buffer is a deadlock that only appears once
the output gets long enough. Nothing here can deadlock, and the file is removed before `capture`
returns.

**Standard error is left alone unless it is asked for.** By default it goes wherever this program's
does, which is what a shell's `$(...)` leaves it doing — a tool asking a program a question wants the
answer without a warning mixed into the middle of it, and the warning is still worth seeing.

## Sending a child's output to a file

`run` takes `stdout` and `stderr`, each a `Stdio`: `Inherit` — the default, this program's own
stream — or a file. `ToPath` names one by its path and starts it empty, a shell's `>`; `ToFile`
hands over a file this program already has open, opened with `create` to start it afresh or `append`
to add to it, and stays this program's to close.

```sysl
import sysl.fs.{append, read_text, remove_file}
import sysl.process.{Stdio, run}
import sysl.text.Search

val listing = "process-page-listing.txt"

run("echo", ["first"], stdout = Stdio.ToPath(listing)).unwrap()

// Both streams onto one open file, as a shell's `>> f 2>&1`.
var log = append(listing).unwrap()

log.write("between\n".bytes)
run("sh", ["-c", "echo second; echo third >&2"], stdout = Stdio.ToFile(log),
    stderr = Stdio.ToFile(log)).unwrap()
log.close().unwrap()

print(read_text(listing).unwrap().trim())
remove_file(listing)
```

```output
first
between
second
third
```

**What this program wrote to a `ToFile` file and had not yet handed over is handed over first**, so
the child's output follows it rather than landing in the middle of it — `between` above was still in
the file's buffer when `sh` started. A path that cannot be opened is an error before anything is
started, and nothing is run.

## Starting now, waiting later

`start` is `capture` with the wait taken out. It takes the same arguments meaning the same things, and
answers a `Child`; the child's `wait` answers exactly what `capture` would have — the same `Output`, the
same `TimedOut`. `capture` is in fact written as `start` followed by `wait`, so the two cannot come to
disagree.

The room in the middle is the point. Start several, then collect them in whatever order suits you, and
they run at the same time:

```sysl
import sysl.process.start

val slow = start("sh", ["-c", "sleep 0.2; echo slow"]).unwrap()
val fast = start("sh", ["-c", "echo fast; echo why >&2; exit 3"], stderr = true).unwrap()

val f = fast.wait().unwrap()
val s = slow.wait().unwrap()

print(f.text)
print(f.status)
print(f.err)
print(s.text)
```

```output
fast

exited 3
Some(why
)
slow

```

**A program that is not there fails at the start**, not at the wait — there is no child to hand back:

```sysl
import sysl.process.start

print(start("no-such-program-anywhere").is_err())
```

```output
true
```

**Several at once is where capturing through a file earns its keep.** With pipes, a child that writes
more than a pipe holds stops until somebody reads — and a parent waiting for a *different* child is not
reading. Through files nothing fills: every child runs to its end on its own, and `wait` reads what it
wrote afterwards. A megabyte on each stream from one child, collected after two others, is one of the
module's own tests.

**`timeout` is measured from `start`, and kept by `wait`.** A child that outstays it while somebody is
waiting is stopped and reported as `TimedOut`; one that finished before anybody came to wait for it is
reported as it finished — it did not outstay anything, its parent was busy.

**Waiting twice answers the first answer again**, since by then the kernel has forgotten the child.

**A `Child` owns its child.** It is a `&Child`, and when the last reference goes, a child that was never
waited for is ended — asked to stop, made to after a short grace, the same two steps a timeout takes —
and reaped, so no zombie is left in the process table; one that had already finished is only reaped.
Ending it rather than letting it run on is not severity for its own sake: its output files are removed
at the same moment, so it would be writing into files nobody can open. A program that wants a child's
work should wait for it.

## Reading why a child failed

A child that exits non-zero has usually said why, on the stream `capture` lets through to the
terminal — where a person may not be looking and a program cannot read it at all. `stderr = true`
collects it into `Output.err`, and takes it off the terminal.

```sysl
import sysl.process.capture
import sysl.text.Search

val quiet = capture("sh", ["-c", "echo why >&2; exit 3"]).unwrap()

print(quiet.status, quiet.err.is_none())

val loud = capture("sh", ["-c", "echo why >&2; exit 3"], "", [], true).unwrap()

print(loud.status, loud.err.unwrap().trim(), loud.text == "")
```

```output
exited 3 true
exited 3 why true
```

**`err` is an `Option[string]` and the two empty answers are different.** `None` is "this call did
not collect standard error"; `Some("")` is "it was collected and the child wrote nothing". A bare
string could say only the second, so a tool reporting a failure would have had to guess which it
had.

```sysl
import sysl.process.capture

val asked = capture("true", [], "", [], true).unwrap()

print(asked.err.is_some(), asked.err.unwrap() == "")
print(capture("true").unwrap().err.is_none())
```

```output
true true
true
```

The second stream goes through a second file, on the same mechanism and for the same reason: two
pipes is where the deadlock above gets *easier* to reach, and two files cannot deadlock at all.

## A child that never ends

"Runs a program and waits for it" says nothing about a program that does not finish, and a caller
with no bound has no move left: a child stuck on a socket nobody answers, or on a prompt nobody is
there to type at, holds its parent for as long as the machine is up. **Both calls take a `timeout`
in milliseconds**, last, and zero — the default — means no bound at all.

```sysl
import sysl.process.{run, capture}
import sysl.text.Search

// A second of sleep, allowed a fifth of one.
val s = run("sleep", ["1"], "", [], 200).unwrap()

print(s, s.ok())

// What a child wrote before it was stopped still comes back.
val out = capture("sh", ["-c", "echo half; exec sleep 5"], "", [], false, 300).unwrap()

print(out.status, out.text.trim())
```

```output
timed out false
timed out half
```

**A child that outstays its timeout is asked to stop and then made to**: `SIGTERM` first, so a
program that tidies up on the way out gets to, then `SIGKILL` a fifth of a second later if it is
still there. The grace is deliberately short — the child has already had the whole of its timeout,
and one that ignores the first signal is not going to honour a longer wait for the second.

**The signal goes to the child and to nothing else.** Putting it in a process group of its own
would take it out of the terminal's foreground group, and then a person's own interrupt would stop
reaching it — so a child that forked children of its own leaves them behind. That is one more
reason to run the program rather than a shell that runs it, which is what this module does anyway;
the example above says `exec` for exactly that reason.

A timeout costs nothing to have and is worth setting wherever the child is something other than a
program you wrote: `run` and `capture` with no bound are the right calls for a build you are
watching, and the wrong ones for a tool that has hung on somebody's machine once already.

## Becoming another program

**`exec` replaces this program with another, in the same process**, and answers only if that failed:
the process keeps its id, its parent, its open descriptors and its working directory, and runs the
other program from its beginning. It is what a shell's `exec` builtin is, and what a login program or
a wrapper that sets something up and then becomes its target needs. `program`, `env` and
`inherit_env` mean what they mean for `run`, the `PATH` search included.

```sysl
import sysl.process.exec

print("about to become echo")

val e = exec("echo", ["now I am echo"])

print("still here:", e)
```

```output
about to become echo
now I am echo
```

**What this program printed is written out first.** Its output buffer lives in the image `exec`
throws away, so the line above would otherwise be lost whenever standard output is not a terminal —
which is exactly when somebody is reading a log of it.

**The answer is an `IoError` rather than a `Result`**, a success having nothing to carry and no one
to carry it to. Everything that can fail is checked before anything is given up, so on a failure this
program goes on exactly as it was:

```sysl
import sysl.process.exec

print(exec("sysl-no-such-program"))
print("and this program carries on")
```

```output
no such file or directory
and this program carries on
```

**It is not `@abandons`.** That annotation is for a call after which the *program* goes on without
the frames below it — a thread's exit, a jump back to a saved context — so the counts those frames
hold have to be given back first. `exec` is the other kind: on success nothing of this program goes
on at all, its memory going with its image the way an `exit`'s does, and on failure it returns like
any call. Its result is `IoError` and not `never` for that reason.

## On a target with no C library

Everything the module asks of the machine goes through five hooks in `sysl.process.sys` — start a
program, wait for it, send it a signal, become one in place of this program, name a file for a
captured stream. On a hosted POSIX target
the library answers them; **on a target with no C library the program answers each one it reaches
with an `@export`**, over whatever its kernel provides, and `run`, `capture`, `start`, `Child`
and `exec` work unchanged above them:

| hook | what it answers |
|---|---|
| `sysl_proc_spawn(path, path_len, argv, argc, envp, envc, inherit_env, dir, dir_len, stdin_fd, stdout_fd, stderr_fd, started) -> i64` | the child's process id |
| `sysl_proc_wait(pid, started, timeout_ms, how, value) -> int` | zero, having written how it ended |
| `sysl_proc_kill(pid, signal) -> int` | zero |
| `sysl_proc_temp_path(into, room) -> isize` | the length of the path it wrote |
| `sysl_proc_exec(path, path_len, argv, argc, envp, envc, inherit_env) -> int` | only a failure: a success does not return |

**Each answers zero or more for success and the `IoError` code negated for a failure**, and
`UNSUPPORTED` (`sysl.sys`, -38 on every platform, not the host's `ENOSYS`; `-1` is `EPERM`) for a
call the target cannot make at all — the contract of every `*.sys` module. Text crosses as a pointer and
a length, never a C string: `argv` and `envp` point at runs of `Text`, each a pointer and a length,
`envp`'s entries reading `NAME=VALUE`. A descriptor of `-1` leaves the child the stream this program
has. `wait` writes `0` into `how` for an exit (its code in `value`), `1` for a signal or a fault (its
number), and `2` for a child it stopped at its deadline; a supplier that cannot bound a wait answers
`UNSUPPORTED` when handed a timeout. `sysl.process.sys`'s own comments say the rest.

A program that reaches a hook and leaves it unanswered is refused when it is compiled, naming every
hook it reached:

```
error: this program starts, waits for or becomes a process, and 'aarch64-freestanding' has no C
library under it for the standard library to answer one with, so the program answers it: define
'sysl_proc_kill', 'sysl_proc_spawn', 'sysl_proc_temp_path', 'sysl_proc_wait' with '@export', each
taking what its 'extern' in 'sysl.process.sys' declares and answering a process id, a length or
zero, or the code of an 'IoError' negated
```

**A program that only calls `exec` still answers `sysl_proc_wait`, `sysl_proc_kill` and
`sysl.fs.sys`'s `sysl_fs_unlink`**: `Child`'s destructor is kept wherever the module is reached, and
it waits for a child, stops one, and removes the file its output was captured into. A kernel with
process calls answers those anyway; one without may answer them `UNSUPPORTED`.

## What is not here

**`fork`.** Rust's standard library has none and Go's offers only a fork that execs at once, for the
same reason: a fork copies one thread of a program that may have several, mid-way through whatever
the others held — an allocator's lock, a half-written buffer — together with every count of every
shared value, and a reference-counted runtime has no way to make that copy safe. Starting another
program is `run`, `capture` or `start`; becoming one is `exec`; and the two together cover what a
fork is wanted for on a hosted system. **A kernel's own runtime keeps its fork** — keel's user
runtime has one beside its `execve` — because it owns every thread and every count there is.

**Process *supervision*.** A running child can be held — that is `start` — and sent a signal, but no
pid is handed out and no process group is made. That covers what a build
tool, an installer or a command-line front end does, including one running several compilers at once.
A program that wants to supervise children wants a different surface, and it would belong under
`sysl.posix`, where a binding goes when it *is* POSIX rather than merely implemented with it.

**And that is why this module is `sysl.process` rather than `sysl.posix.process`.** Starting a child
is the same idea on every hosted system — a program, its arguments, and how it ended — and only the
mechanism underneath differs. [`sysl.fs`](/library/fs/) made the same call for the same reason.
