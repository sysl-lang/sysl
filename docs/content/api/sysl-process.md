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

**It requires `posix` and not `os`, and that is a correction rather than a narrowing.** On a
hosted target the whole of the mechanism is `posix_spawn`, or `fork` and `execvp` -- `execvp`'s own
`PATH` search is what the tests below assert -- and neither exists outside POSIX. It said `os` until
the WASI row arrived and made the difference visible: preview1 has no way to start a program at
all, and Windows was already the same case with nobody building for it.

**Everything it asks of the machine goes through the hooks of `sysl.process.sys`** -- start a
program, wait for it, signal it, name a file for a captured stream, and read or change which user
and group the program runs as (`identity.sysl`) -- which the library answers
over POSIX on a hosted target and a program answers itself, with an `@export` per hook, on a
target with no C library: a kernel with its own process calls, whose shell can then be written
with this module.

**A child can be held while it runs, and that is as far as management goes.** `run` and `capture`
start one program and wait for it; `start` is `capture` with the wait taken out, answering a
`Child` whose `wait` hands back exactly what `capture` would have. That is what a build tool
running several compilers at once needs -- start them all, then collect them in whatever order
suits it. A `Child` can be sent a signal, and that is the one piece of a *supervisor* here: no pid
is handed out and no process group is made. A program that wants those wants a different surface
and can have one under `sysl.posix` when something actually needs it.

**A pipeline is `spawn` and `pipe`.** `spawn` starts a program with its three streams wherever the
caller says and neither waits nor captures, answering a `Spawned` whose `wait` answers a `Status`
and reads no file; `pipe` makes the two ends one child writes into and another reads from. Every
stage is started first and every stage waited for after, which is the one order a pipe allows --
and the reason `capture` does not use one.

**The one thing a call that waits cannot do without is a way to stop waiting.** "Runs a program
and waits for it" says nothing about a program that never ends, and a caller with no bound has no
move left: a child stuck on a socket nobody answers, or on a prompt nobody is there to type at,
holds its parent for as long as the machine is up. So `run` and `capture` take a `timeout`, and a
child that outstays it is stopped and reported as `TimedOut` -- the caller names a bound and this
module keeps it.

**Nothing here goes through a shell**, which is why the arguments are a list rather than one
string. `system(3)` would hand the text to `/bin/sh`, and then a filename with a space in it is
two arguments and one with a `;` in it is a second command -- so a caller would have to quote,
and quoting correctly for a shell is a thing nobody does right the second time. `execvp` takes
the vector as it is given, so a path is a path whatever is in it.

## Index

[`capture`](#capture) [`exec`](#exec) [`foreground_group`](#foreground_group) [`group`](#group) [`new_session`](#new_session) [`pipe`](#pipe) [`process_group`](#process_group) [`real_group`](#real_group) [`real_user`](#real_user) [`run`](#run) [`set_foreground_group`](#set_foreground_group) [`set_group`](#set_group) [`set_process_group`](#set_process_group) [`set_user`](#set_user) [`spawn`](#spawn) [`start`](#start) [`user`](#user) [`Child`](#child) [`Group`](#group-1) [`Output`](#output) [`Spawned`](#spawned) [`Status`](#status) [`Stdio`](#stdio) [`Var`](#var) [Display for Status](#display-for-status) [Drop for Child](#drop-for-child) [Drop for Spawned](#drop-for-spawned) [Eq for Status](#eq-for-status)

## Functions

### `capture`

```sysl
capture(program: string, args: []const string = [], dir: string = "", env: []const Var = [], stderr: bool = false, timeout: int = 0, inherit_env: bool = true, stdin: Stdio = Inherit, group: Group = Inherit, foreground: bool = false) -> Result[Output, IoError]
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

`env` and `inherit_env` mean what they mean for `run`: added to this program's environment by
default, the whole of the child's with `inherit_env = false`. So does `stdin`: this program's own
input by default, or a file the child reads (`FromPath`, `FromFile`). And `group` and `foreground`:
the process group the child starts in, and whether it is given the terminal (`Group`).

It is `start` followed at once by `wait`, and is written as exactly that, so the two can never
come to answer differently.

### `exec`

```sysl
exec(program: string, args: []const string = [], env: []const Var = [], inherit_env: bool = true) -> IoError
```

Replaces this program with `program`, run with `args`, and answers only if that failed.

**On success nothing comes back**: the process goes on -- the same id, the same parent, the same
open descriptors, the same working directory -- running the other program from its beginning, and
nothing of this one is left to return to. What a shell's `exec` builtin, a login program and a
wrapper that sets something up and then becomes its target all need.

`program`, `env` and `inherit_env` mean what they mean for `run`: a name with no `/` is looked for
on the `PATH` the new program will have, `env` is added to this program's environment, and with
`inherit_env = false` it is the whole of it. Whatever this program has printed and not yet written
out is written first, since the buffer holding it goes with the image.

**The answer is an `IoError` rather than a `Result`**, there being no success to carry: `NotFound`
for a program that is not there, `PermissionDenied` for one that may not be run, and this program
carries on exactly as it was -- everything that can fail is checked before anything is given up.

**There is no `fork` beside it, deliberately**, as Rust's and Go's standard libraries have none.
A fork copies one thread of a program that may have several, mid-way through whatever the others
held -- an allocator's lock, a half-written buffer -- and every count of every shared value along
with it. Starting another program is `run`, `capture` or `start`; becoming one is this. A kernel's
own runtime, which owns every thread and every count there is, is where a fork belongs.

### `foreground_group`

```sysl
foreground_group() -> Result[int, IoError]
```

The process group the controlling terminal gives its keys to -- the foreground job.

The controlling terminal is the one this program's session belongs to, whatever its standard
streams have been pointed at. **A program with none -- started by a service manager, a test runner,
anything not sitting at a terminal -- is an error**: `Other(6)` on a POSIX host, `ENXIO`.

### `group`

```sysl
group() -> u32
```

The group the program acts as: the effective group id, compared with `sysl.fs.Meta.group`.

### `new_session`

```sysl
new_session() -> Result[int, IoError]
```

This program made the leader of a new session and of a new process group in it, answering the
new session's id -- its own process id, and the new group's number.

**The new session has no controlling terminal**, which is the point: it is what a login program
does in the child it starts a shell in, so that the terminal the shell then opens becomes that
session's. A program that already leads a process group -- a job a shell started with `Group.New`,
or one that called `set_process_group()` -- is `NotPermitted`, and stays where it was.

### `pipe`

```sysl
pipe() -> Result[(File, File), IoError]
```

A pipe: the end its bytes are read from, then the end they are written into.

Both are ordinary `File`s, so this program can read or write one itself as well as hand it to a
child with `FromFile` or `ToFile`. Neither is inherited by a child except as the stream it was
handed as -- each end closes when a program is started, and the child gets its own copy where it was
asked for -- so a child never holds an end of a pipe it was not given.

**Every end has to be closed for a pipeline to finish, this program's copies included.** A reader
sees the end of its input only when no write end is open anywhere, and a writer whose reader has
gone is told so (`SIGPIPE`, or `EPIPE` from the write) only when no read end is: so close the write
end as soon as the stage that writes into it has started, and the read end as soon as the stage that
reads from it has. A write end this program still holds is a reader waiting for ever.

### `process_group`

```sysl
process_group() -> int
```

The process group this program is in. It is a number above zero; **a target with no process
groups answers zero**, as it answers user 0 for `user()`, everything there being one group.

### `real_group`

```sysl
real_group() -> u32
```

The group of the user who started the program: the real group id.

### `real_user`

```sysl
real_user() -> u32
```

The user who started the program: the real user id. It is `user()` unless the program changed
who it acts as -- it is set-user-ID, or it called `set_user`.

### `run`

```sysl
run(program: string, args: []const string = [], dir: string = "", env: []const Var = [], timeout: int = 0, inherit_env: bool = true, stdout: Stdio = Inherit, stderr: Stdio = Inherit, stdin: Stdio = Inherit, group: Group = Inherit, foreground: bool = false) -> Result[Status, IoError]
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

`env` is added to this program's environment, unless `inherit_env` is `false`, in which case it
is the child's whole environment -- see `Var` for which `PATH` the program is then looked for on.
It is the last parameter but three rather than beside `env` so that a call already passing `stderr`
or `timeout` by position keeps meaning what it meant.

`stdout` and `stderr` say where the child's two output streams go -- this program's own by
default, or a file (`Stdio`): `run("ls", ["/"], stdout = ToPath("listing.txt"))` is a shell's
`ls / > listing.txt`. `stdin` says where its input comes from -- `FromPath("names.txt")` is a
shell's `< names.txt`, and `FromFile` a file or a pipe's read end this program has open. It is the
last parameter for the same reason `inherit_env` is not beside `env`. A file this call opened is
closed before it returns, and one that cannot be opened is an error before anything is started.

`group` is the process group the child is started in and `foreground` whether that group is given
the controlling terminal before the child's program runs -- see `Group`, and `foreground_group` for
taking the terminal back. Both come last so that every call written before them keeps its meaning.

It is `spawn` followed at once by `wait`, and is written as exactly that.

The error half is for a child that could not be *started*: a program that is not there reports
`NotFound`, one that is not executable reports `PermissionDenied`. **A program that ran and
failed is `Ok`**, carrying a non-zero `Status`, because it did start and its exit status is an
answer rather than a failure of this call. **A child that ran out of time is `Ok` too**, for the
same reason and with `TimedOut`: this call did what it was asked, and what happened is about the
child.

### `set_foreground_group`

```sysl
set_foreground_group(pgid: int) -> Result[unit, IoError]
```

The controlling terminal made to give its keys to process group `pgid`.

**It works from the background too**, which is what a shell needs: once a foreground job ends or
stops, the shell is a background process of its own terminal, and taking the terminal back is
`set_foreground_group(process_group())`. A group that does not exist, or belongs to another
session, is `NotPermitted`; a program with no controlling terminal is `foreground_group`'s error.

### `set_group`

```sysl
set_group(id: u32) -> Result[unit, IoError]
```

The program made to act as group `id`, as POSIX's `setgid`, with `set_user`'s rule: a program
acting as user 0 may take any group, and any other only one it already has.

### `set_process_group`

```sysl
set_process_group(pgid: int = 0) -> Result[unit, IoError]
```

This program moved into process group `pgid` -- or, with zero, the default, made the leader of a
group of its own, numbered by its process id.

**It is the first thing a job-control shell does**: in a group of its own, a Ctrl-C at its prompt
goes to the shell's group alone rather than to whatever started it, and
`set_foreground_group(process_group())` then gives it the terminal. A program that already leads
its group is left as it is. A group in another session, and a program that leads a session (a login
shell after `new_session`), are `NotPermitted`.

### `set_user`

```sysl
set_user(id: u32) -> Result[unit, IoError]
```

The program made to act as user `id`, as POSIX's `setuid`. **It is privileged**: a program acting
as user 0 becomes `id` for good -- real, effective and saved ids together, so the privilege is
given up rather than set aside -- and any other program may only move between the ids it already
has. Anything else is `NotPermitted`.

A program giving up user 0 for another user calls `set_group` FIRST: once it is no longer user 0
it may not change its group either.

### `spawn`

```sysl
spawn(program: string, args: []const string = [], dir: string = "", env: []const Var = [], timeout: int = 0, inherit_env: bool = true, stdin: Stdio = Inherit, stdout: Stdio = Inherit, stderr: Stdio = Inherit, group: Group = Inherit, foreground: bool = false) -> Result[&Spawned, IoError]
```

Start a program with its three streams where the caller says, and answer it without waiting for it
or collecting anything.

`stdin`, `stdout` and `stderr` are each a `Stdio`: this program's own stream by default, a file named
by its path (`FromPath` for the input, `ToPath` for an output), or a file this program has open
(`FromFile`, `ToFile`) -- a pipe's end among them, which is what makes this the call a pipeline is
built from. `echo hi | tr a-z A-Z` is two of them:

    val (r, w) = pipe()?
    val echo = spawn("echo", ["hi"], stdout = ToFile(w))?
    w.close()
    val tr = spawn("tr", ["a-z", "A-Z"], stdin = FromFile(r))?
    r.close()
    echo.wait()?
    tr.wait()?

**Who closes what.** A file this call opened for a path it closes again before it returns. A file
handed in as `ToFile` or `FromFile` stays open and stays the caller's: the child has a copy of its
own by the time this returns, so the caller's copy is free to close -- and for a pipe it must be, or
the stage reading it never sees its input end (see `pipe`).

`dir`, `env`, `timeout` and `inherit_env` mean what they mean for `run`; `timeout` is kept by `wait`.
`group` and `foreground` say which process group the child starts in and whether that group is
given the terminal before the child's program runs (`Group`); `foreground` with `Group.Inherit` is
refused before anything starts, `Other(22)`, the terminal being this program's group's already.
The error half is for a child that could not be started -- `NotFound` for a program that is not
there, a stream handed the wrong direction refused before anything starts (`Stdio`), a terminal
there is none of -- and a program that ran and failed is a `Status` at the wait.

### `start`

```sysl
start(program: string, args: []const string = [], dir: string = "", env: []const Var = [], stderr: bool = false, timeout: int = 0, inherit_env: bool = true, stdin: Stdio = Inherit, group: Group = Inherit, foreground: bool = false) -> Result[&Child, IoError]
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

### `user`

```sysl
user() -> u32
```

The user the program acts as: the effective user id, the one a filesystem checks a permission
against and makes a new file's owner. Compare it with `sysl.fs.Meta.owner` to ask whether a file is
the program's own.

## Types

### `Child`

```sysl
struct Child
    private[process] id: i32
    private began: i64
    private timeout: int
    private out_path: string
    private err_path: string
    private answer: Option[Result[Output, IoError]]
    private pgid: i64
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
| `group` | `group(self) -> int` | The process group the child was started in: its own pid for `Group.New`, the joined group's number for `Join`, and this program's for `Inherit`. |
| `pid` | `pid(self) -> int` | The process id of this child: the number `kill` and a wait by pid name. |
| `wait` | `wait(*self) -> Result[Output, IoError]` | Wait for the child to end, and answer what `capture` would have answered for it. |
| `try_wait` | `try_wait(*self) -> Result[Option[Output], IoError]` | Whether the child has ended, **without waiting for it**: `Ok(None)` while it runs, and once it has ended, what `wait` would have answered -- the same `Output`, its files read and removed. |
| `kill` | `kill(*self, signal: int = 15) -> Result[unit, IoError]` | Sends the child the signal numbered `signal` -- 15, the default, asks it to stop, and 9 makes it -- without waiting for it; `wait` then answers `Signalled` with the number, if the signal was what ended it. |

### `Group`

```sysl
enum Group
    Inherit
    New
    Join(pgid: int)
```

Which process group a child is started in.

`Inherit`, the default, leaves it in this program's group, so the terminal's keys reach both
alike. `New` makes it the leader of a group of its own, numbered by its process id -- a job. `Join`
puts it in an existing group, `pgid` being that group's number: a later stage of a pipeline joins the
group its first stage leads, so the whole pipeline is one job.

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

### `Spawned`

```sysl
struct Spawned
    private[process] id: i32
    private began: i64
    private timeout: int
    private answer: Option[Result[Status, IoError]]
    private pgid: i64
```

A child that `spawn` started and that nothing has waited for yet.

**It owns the child and nothing else** -- no file, no buffer -- so `wait` answers a `Status` and
reads nothing. What the child wrote went where its `Stdio` said: a file, a pipe this program reads,
or this program's own stream.

Like `Child`, it is reached only through a `&Spawned`, and **dropping one that was never waited for
ends and reaps the child there and then** -- one that had finished is reaped, and one still running
is asked to stop, made to after a short grace, and reaped -- so a pipeline abandoned halfway leaves
no zombie and no stage running on.

| Member | Signature | Description |
|---|---|---|
| `group` | `group(self) -> int` | The process group the child was started in, as `Child.group` answers it: what a later stage of a pipeline `Join`s, and what `set_foreground_group` hands the terminal to. |
| `pid` | `pid(self) -> int` | The process id of this stage: the number `kill` and a wait by pid name. |
| `wait` | `wait(*self) -> Result[Status, IoError]` | Waits for the child to end and says how it ended, as `run` would have. |
| `try_wait` | `try_wait(*self) -> Result[Option[Status], IoError]` | Whether the child has ended, **without waiting for it** -- what a shell asks of every background job before it prints a prompt. |
| `kill` | `kill(*self, signal: int = 15) -> Result[unit, IoError]` | Sends the child the signal numbered `signal`, as `Child.kill` does: 15 asks it to stop and 9 makes it, and a child already waited for is not signalled. |

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

**A child ended for a fault is `Signalled`**, with the number POSIX raises for that fault -- 11
for an address it could not touch, 4 for an instruction it could not run -- which is what a POSIX
host reports, and what a kernel answering `sysl.process.sys` itself reports too.

| Member | Signature | Description |
|---|---|---|
| `ok` | `ok(self) -> bool` | Whether this is the answer a caller was hoping for: exited, and exited zero. |

### `Stdio`

```sysl
enum Stdio
    Inherit
    ToFile(file: File)
    ToPath(path: string)
    FromFile(file: File)
    FromPath(path: string)
```

Where one of a child's streams goes, or comes from.

`Inherit` is where this program's own goes, which is what a build or an install whose output a
person is watching wants. `ToFile` is a file this program already has open -- opened with
`sysl.fs.create` to start it afresh or `sysl.fs.append` to add to it, or a pipe's write end -- and
whatever this program had buffered on its way there is handed over first, so the child's output
follows it rather than landing in the middle of it. `ToPath` is a file named by its path, made empty
first, or made if it is not there: a shell's `>`.

**Standard input reads the other two**: `FromFile` is a file this program has open -- opened with
`sysl.fs.open`, or a pipe's read end -- which the child reads from where this program stopped
reading; what this program had read ahead into its buffer and not yet taken is given back to the file
first, and a pipe, which cannot take it back, is refused rather than losing it. `FromPath` is a file
named by its path, opened for reading: a shell's `<`.

**The direction is part of the name, and a stream handed the wrong one is refused** with `Other(22)`
(`EINVAL`) before anything is started -- `ToPath` as an input would empty the file the child was
meant to read, and `FromPath` as an output would open one it cannot write.

**A `ToFile` or `FromFile` file stays open and stays the caller's to close**, the child having a copy
of its own by the time the call returns; a file this module opened for a path is closed again. Both
output streams may be pointed at one file, and then they share it as a shell's `>f 2>&1` does.

### `Var`

```sysl
struct Var
    name: string
    value: string
```

One environment variable a child is to be started with.

**This is on the call rather than in `sysl.env`, and that module says why**: `setenv` mutates
state a whole process shares and is not safe against a concurrent read, so a program that wants a
child to see something different asks for it here. By default the variables are *added* to what
this program already has rather than replacing it -- a child that lost `PATH` and `HOME` because
its parent wanted to set one thing is a surprise. The caller who genuinely wants a child to see
only what it was given -- an interpreter whose `run` replaces the environment, a test that must
not see its runner's variables -- says so: **`inherit_env = false` hands the child exactly these
variables and nothing else**, an empty list being an empty environment.

Neither way touches this program's own environment: the child's is built for it and handed over
as it starts.

**`PATH` is the one whose effect starts before the child does.** A `PATH` among the variables
decides where the program itself is looked for, as `execvp` in the child would look -- so a caller
handing a child a `PATH` meant for *its* children should name the program by an absolute path.
Consistent, and surprising exactly once. **A replaced environment with no `PATH` in it leaves the
child none**, and a bare program name is then looked for on the system's default search path
(`confstr(_CS_PATH)`: `/usr/bin:/bin:/usr/sbin:/sbin` on macOS, `/bin:/usr/bin` on glibc), which
is where `execvp` looks in a process with no `PATH` -- never on this program's, since the child
was asked to inherit nothing.

## Implementations

### Display for Status

```sysl
impl Display for Status
```

### Drop for Child

```sysl
impl Drop for Child
```

### Drop for Spawned

```sysl
impl Drop for Spawned
```

### Eq for Status

```sysl
impl Eq for Status
```
