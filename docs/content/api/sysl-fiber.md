---
title: sysl.fiber
layout: api-module
headingShift: 0
slugStyle: github
module: sysl.fiber
summary: "Stacks of a program's own, and the switch from one to another: what a runtime builds lightweight threads on, over the operating system's threads that carry them."
requires: "requires { posix }"
---

A `Stack` is a mapping committed only as it is touched, with an inaccessible guard below it. A
`Context` is a place to come back to: either a stack prepared to begin at an entry function
(`Context.start`), or one that `switch` saved while leaving it. `switch(&here, &there)` saves the
running stack in `here` and resumes `there` -- an ordinary call that returns when something
switches back to `here`, a few nanoseconds later or on another thread.

Nothing here schedules: which context runs next, and on which thread, is the runtime's choice.

## What is saved

Everything the platform's calling convention promises a callee keeps: on aarch64 x19--x30, the
stack pointer, d8--d15 and the floating-point control register (FPCR, so the rounding mode is the
fiber's own); on x86_64 rbx, rbp, r12--r15, the stack pointer, MXCSR's control bits and the x87
control word. The switch itself stores only the stack, frame and base pointers, where to resume
and the control word; the other registers are declared clobbered, so the code around the switch
saves the ones it has live and no more. There is no system call and no signal mask in a switch: a
fiber runs with whatever mask its carrier thread has. The supported machines are aarch64 (macOS
and Linux) and x86_64 (Linux and macOS).

## A fiber may move between threads, and what is the carrier's

A context saved on one thread may be resumed on another. The stack and every register move with it;
what does not is anything kept **per thread**, which is the carrier's, not the fiber's:

- **`@thread_local` storage is the carrier's.** A fiber reading one after it was resumed elsewhere
  reads the new carrier's copy -- in the very function that switched, in anything inlined into it,
  and in a loop around the switch: the compiler computes a thread-local's address again after
  every `switch`, rather than letting the optimizer keep the one from before it. An address taken
  with `&` and held in a variable across a switch is a pointer like any other, and still names the
  copy it was taken from.
- **The error return trace is the carrier's** (`sysl.debug`). The sites it records are constants,
  never addresses in a frame, so a fiber that moves leaves nothing dangling -- but a failure a fiber
  records lands in whichever carrier's trace is current, interleaved with whatever else ran there. A
  runtime that wants each fiber's trace whole gives each an `ErrorTraceStore` and makes it current
  around the switch to the fiber, on the carrier that is about to run it:
  `use_error_trace(&f.trace)` before `switch`, `use_error_trace(null)` once it is back.
- **`sysl.threads.current()` answers the carrier**, which is what it is: the thread running now.
- **The stack-overflow report needs nothing per thread.** A stack's guard is registered with the
  process when it is reserved, and the first `switch` on a thread gives that thread the signal
  stack the report is written from, so an overflow is reported on whichever carrier it happens.

A context for a thread's **own** stack -- a `Context.new()` that `switch` saved -- is resumed only
on that thread: the stack is the thread's, and the thread may end.

## Backtraces and debuggers

The frame a fiber's entry is called from has a zero frame pointer, and its return address is marked
undefined in the unwind tables, so a debugger, a sampler or a crash report walks a fiber's stack from
where it stopped down to its entry and no further.

## Index

[`switch`](#switch) [`Context`](#context) [`Stack`](#stack) [Drop for Stack](#drop-for-stack)

## Functions

### `switch`

```sysl
switch(from: *Context, to: *Context)
```

Saves the running stack in `from` and resumes `to`, which must hold a stack that is not running:
one `start` prepared, or one a `switch` saved. Comes back when something switches to `from`.

It is a few nanoseconds, with no system call and no signal mask. **A call of it is the compiler's
own**: it is emitted in place, as assembly that saves the stack, frame and base pointers and the
floating-point control, and declares every other register clobbered -- so the function calling it
keeps only the values live across the switch, wherever it chose to -- and every thread-local
address the function uses afterwards is computed again, on whichever thread it came back on. A
call through a function pointer reaches this definition, which does the same out of line.

## Types

### `Context`

```sysl
struct Context
    private sp: *u8
    private pc: *u8
    private fp: *u8
    private bp: *u8
    private ctl: u64
    private lo: *u8
    private len: usize
    private fake: *u8
```

Where `switch` goes, or comes back to: the stack, instruction, frame and base pointers to resume
at, and the floating-point control. Make one with `new()` for the stack running now -- `switch`
fills it in when it leaves -- or with `start` for a new fiber.

A context is a value, and the one `switch` saved into is the one to resume: hand its address
around, not a copy taken before the switch.

| Member | Signature | Description |
|---|---|---|
| `new` | `new() -> Context` | A context for the running stack, which holds nothing until `switch` leaves through it. |
| `start` | `start[T](stack: &Stack, entry: *extern(*T) -> unit, arg: *T) -> Context` | A context that begins at `entry(arg)` on `stack` the first time something switches to it. |
| `is_ready` | `is_ready(self) -> bool` | Whether a context has somewhere to go: `false` for a `new()` that has not been left yet. |

### `Stack`

```sysl
struct Stack
    private at: *u8
    private n: usize
    private guard: usize
```

A stack for a fiber, reserved but not yet committed: the operating system backs a page the first
time it is touched, so reserving a megabyte for each of ten thousand fibers costs address space,
not memory.

Below it is a guard of at least 64 KiB that nothing may touch. A fiber that recurses into it is
reported exactly as a thread's overflow is -- "this program has overflowed its stack" -- rather than
as a bare segmentation fault.

**Dropping the last reference unmaps it**, so it is held as a `&Stack`. It must outlive every
context started on it, and must not be dropped while a fiber is running on it.

| Member | Signature | Description |
|---|---|---|
| `reserve` | `reserve(size: usize) -> Result[&Stack, IoError]` | Reserves a stack of at least `size` bytes, in a mapping of whole pages, with its guard below. |
| `len` | `len(self) -> usize` | How many bytes the stack holds: at least what was asked for, since the mapping is whole pages. |
| `committed` | `committed(self) -> usize` | How many of the stack's bytes are resident: the pages a fiber has touched so far, which is what the stack costs in memory. |

## Implementations

### Drop for Stack

```sysl
impl Drop for Stack
```
