---
title: sysl.process
layout: api-module
headingShift: 0
slugStyle: github
module: sysl.process
summary: "Starting another program and waiting for what it does."
requires: "requires { posix }"
---

**It is `sysl.process` rather than `sysl.posix.process`, and the difference is what the module
*is* rather than how it is built.** Starting a child is the same idea on every hosted system --
a program, its arguments, and how it ended -- and only the mechanism underneath differs, which is
what `__posix__` is for. `sysl.posix` is for bindings that are POSIX and have no equivalent
elsewhere: `sysl.posix.tty` is there because `termios` is what it is, and a Windows console is a
different model rather than the same one spelled differently. `sysl.fs` made this same call and
hides `dirent` the same way.

**It requires `posix` and not `os`, and that is a correction rather than a narrowing.** The whole
of the mechanism is `fork` and `execvp` -- `execvp`'s own `PATH` search is what the tests below
assert -- and neither exists outside POSIX. It said `os` until the WASI row arrived and made the
difference visible: preview1 has no way to start a program at all, and Windows was already the
same case with nobody building for it.

**A child can be held while it runs, and that is as far as management goes.** `run` and `capture`
start one program and wait for it; `start` is `capture` with the wait taken out, answering a
`Child` whose `wait` hands back exactly what `capture` would have. That is what a build tool
running several compilers at once needs -- start them all, then collect them in whatever order
suits it. What is still absent is everything a *supervisor* wants: no pid is handed out, no
process group is made, and no signal of the caller's choosing can be sent. A program that wants
those wants a different surface and can have one under `sysl.posix` when something actually
needs it.

**The one thing a call that waits cannot do without is a way to stop waiting.** "Runs a program
and waits for it" says nothing about a program that never ends, and a caller with no bound has no
move left: a child stuck on a socket nobody answers, or on a prompt nobody is there to type at,
holds its parent for as long as the machine is up. So `run` and `capture` take a `timeout`, and a
child that outstays it is stopped and reported as `TimedOut`. That is the whole of the signalling
here -- the caller names a bound and this module keeps it; there is still no way to send a signal
of one's own choosing.

**Nothing here goes through a shell**, which is why the arguments are a list rather than one
string. `system(3)` would hand the text to `/bin/sh`, and then a filename with a space in it is
two arguments and one with a `;` in it is a second command -- so a caller would have to quote,
and quoting correctly for a shell is a thing nobody does right the second time. `execvp` takes
the vector as it is given, so a path is a path whatever is in it.

## Index

[`capture`](#capture) [`run`](#run) [`start`](#start) [`Child`](#child) [`Output`](#output) [`Status`](#status) [`Var`](#var) [Display for Status](#display-for-status) [Drop for Child](#drop-for-child) [Eq for Status](#eq-for-status)

## Functions

### `capture`

```sysl
capture(program: string, args: []const string = [], dir: string = "", env: []const Var = [], stderr: bool = false, timeout: int = 0) -> Result[Output, IoError]
```

Run a program, wait for it, and collect what it wrote to its standard output.

For asking a program a question -- what version it is, where something lives, what devices are
attached. The output is collected through a file rather than a pipe, which is not an
implementation detail worth hiding: a pipe has a buffer, and a parent that waits for a child
while the child waits for the parent to drain that buffer is a deadlock that only appears once
the output gets long enough. Nothing here can deadlock, and adding a second stream does not
change that -- two pipes is where the deadlock gets *easier* to reach, and two files is two
files.

**`stderr` is what a tool reporting a failure needs, and it is off by default.** Without it a
program that ran and exited non-zero says only that it failed: the reason was written to a stream
this call let through to the terminal, where a tool cannot read it and a person may not be
looking. Asking for it puts the message in `Output.err` and takes it *off* the terminal, which is
the trade -- so a call whose output a person is watching should leave it alone, and one whose
answer another program is reading should not.

`timeout` is how many milliseconds the child may take, and zero -- the default -- means it may
take as long as it likes. **What a child wrote before it ran out of time still comes back**: the
files are read whatever the status is, so a program that printed half an answer and then hung
hands over the half, which is usually what says where it stopped.

The files are removed before this returns, whether the child succeeded or not.

It is `start` followed at once by `wait`, and is written as exactly that, so the two can never
come to answer differently.

### `run`

```sysl
run(program: string, args: []const string = [], dir: string = "", env: []const Var = [], timeout: int = 0) -> Result[Status, IoError]
```

Run a program, wait for it, and say how it ended.

**The child shares this program's streams**, so what it prints appears as it prints it and
anything it reads comes from the same place. That is what makes this the call for a build, an
install or anything else whose output a person is watching go by.

`dir` is where the child starts, and an empty one means wherever this program is. The child's
directory is its own -- this program does not move.

`timeout` is how many milliseconds the child may take, and zero -- the default -- means it may
take as long as it likes. A child that outstays it is asked to stop and then made to, and the
answer is `TimedOut`.

The error half is for a child that could not be *started*: a program that is not there reports
`NotFound`, one that is not executable reports `PermissionDenied`. **A program that ran and
failed is `Ok`**, carrying a non-zero `Status`, because it did start and its exit status is an
answer rather than a failure of this call. **A child that ran out of time is `Ok` too**, for the
same reason and with `TimedOut`: this call did what it was asked, and what happened is about the
child.

### `start`

```sysl
start(program: string, args: []const string = [], dir: string = "", env: []const Var = [], stderr: bool = false, timeout: int = 0) -> Result[&Child, IoError]
```

Start a program and answer it as a `Child`, without waiting for it.

It takes what `capture` takes, meaning the same things, and `wait` answers what `capture` answers
-- so `capture(p, args)` and `start(p, args)?.wait()` are the same call with room in the middle.
That room is the point: start several, then wait for them in any order, and they run at the same
time.

**The error half is for a child that could not be started**, exactly as in `capture`: a program
that is not there is `NotFound` here, at the start, rather than later at the wait -- there is no
child to hand back, and no files left behind for one.

`timeout` bounds the child's whole life from this call, but it is kept by `wait`: a child is only
stopped for outstaying it while somebody is waiting for it, or when its `Child` is dropped.

## Types

### `Child`

```sysl
struct Child
    private[process] pid: i32
    private began: i64
    private timeout: int
    private out_path: string
    private err_path: string
    private answer: Option[Result[Output, IoError]]
```

A child that has been started and not yet waited for.

**It owns the child and the files its streams go to**, so it is only ever reached through a
`&Child` -- `start` answers one -- and when the last reference goes, whatever it still holds is
let go of. A child that was waited for has nothing left but its answer. One that was *not* is
ended and reaped there and then, so dropping a `Child` never leaves a zombie behind: a child that
had already finished is reaped, and one still running is asked to stop, made to after a short
grace, and reaped -- the same two steps a `timeout` takes. Stopping it rather than leaving it to
run is not severity for its own sake: its output files are removed in the same breath, so a child
left running would be writing into files nobody can open any more, and a program that genuinely
wanted it to carry on should have waited for it.

**The streams go to files, not pipes, and that is what makes several at once safe.** A pipe has a
buffer, and a child that fills it blocks until somebody reads -- so with pipes, a program waiting
for one child while a second fills its pipe deadlocks, and one waiting for the second while the
first fills its pipe deadlocks the other way. Reading every pipe as it fills would need a loop
polling all of them while also polling for exits. A file never fills, so every child runs to the
end on its own and `wait` reads what it wrote afterwards, in whatever order the caller chooses.

| Member | Signature | Description |
|---|---|---|
| `wait` | `wait(*self) -> Result[Output, IoError]` | Wait for the child to end, and answer what `capture` would have answered for it. |

### `Output`

```sysl
struct Output
    status: Status
    text: string
    err: Option[string]
```

A child's output, and how it ended.

`text` is what the program wrote to its standard output. `err` is what it wrote to its standard
error, and only where the caller asked for it -- by default that stream goes wherever this
program's does, which is what a shell's `$(...)` leaves it doing. A tool asking a program a
question wants its answer without a warning printed into the middle of it, and a warning is still
worth seeing.

**`None` and `Some("")` are different answers and the distinction is the point.** `None` is "this
call did not collect standard error"; `Some("")` is "it was collected and the child wrote
nothing". A `string` alone could not tell a caller which of those it had, so a tool reporting why
a child failed would have had to guess between "it said nothing" and "nobody was listening" --
which is the whole reason this field is an `Option`.

### `Status`

```sysl
enum Status
    Exited(code: int)
    Signalled(signal: int)
    TimedOut
```

How a child ended.

Separate cases rather than one number, because they are not the same kind of answer: an exit
status is something the program chose and a signal is something that happened to it. Collapsing
them -- which is what a shell's `$?` does, reporting `128 + n` for a signal -- makes a program
killed by `SIGKILL` indistinguishable from one that deliberately exited 137.

**`TimedOut` is there for the same reason, one step further out.** A child stopped for running
past its timeout *was* killed by a signal, and reporting that would say "something killed it"
about the one case where the caller knows exactly what did and why. The distinction is what lets
a tool say "it took too long" rather than "it crashed", and retry the one and not the other.

| Member | Signature | Description |
|---|---|---|
| `ok` | `ok(self) -> bool` | Whether this is the answer a caller was hoping for: exited, and exited zero. |

### `Var`

```sysl
struct Var
    name: string
    value: string
```

One environment variable a child is to be started with.

**This is on the call rather than in `sysl.env`, and that module says why**: `setenv` mutates
state a whole process shares and is not safe against a concurrent read, so a program that wants a
child to see something different asks for it here. The variables are *added* to what this program
already has rather than replacing it -- a child that lost `PATH` and `HOME` because its parent
wanted to set one thing is a surprise, and the caller who genuinely wants an empty environment is
rare enough to be told this is not the call for it.

They are set in the child, in the window between the fork and the exec, where the process is
single-threaded by construction and this program's own environment is untouched.

**`PATH` is the one whose effect starts before the child does.** Because the variables are in
place before `execvp` runs, setting it decides where the program itself is looked for -- so a
caller handing a child a `PATH` meant for *its* children should name the program by an absolute
path. Consistent, and surprising exactly once.

## Implementations

### Display for Status

```sysl
impl Display for Status
```

### Drop for Child

```sysl
impl Drop for Child
```

### Eq for Status

```sysl
impl Eq for Status
```
