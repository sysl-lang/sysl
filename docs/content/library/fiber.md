---
title: sysl.fiber
summary: Stacks a program makes for itself, and the switch from one to another — what a runtime builds lightweight threads on, over the operating system's threads that carry them.
weight: 81
---

**Every declaration in `sysl.fiber`, with its signature:** [the generated API page](/api/sysl-fiber/#index). This page is the argument — what the module is for, and how its pieces fit; that one is the list.

A thread from [`sysl.threads`](/library/threads/) is the operating system's: it is scheduled by the
kernel, it costs a kernel object and a stack the system sizes, and stopping one to run another is a
system call. A runtime that wants a hundred thousand lightweight threads — goroutines, green threads,
actors — runs them as **fibers** over a few such threads, its **carriers**, and decides itself which
fiber runs next. That needs exactly two things the language does not otherwise have: a stack that is
not a thread's, and a way to move onto it and back. This module is those two things and nothing else.
It schedules nothing.

```
module sysl.fiber
@requires(posix)
```

It runs on aarch64 (macOS and Linux) and x86_64.

## A stack, a context and a switch

```sysl
import sysl.fiber.{Context, Stack, switch}

struct Pair
    home: Context
    fiber: Context
    said: int

count(p: *Pair)
    loop
        p.said += 1
        print(s"fiber says ${p.said}")
        switch(&p.fiber, &p.home)

main()
    val stack = Stack.reserve(64 * 1024).unwrap()
    var p = Pair(Context.new(), Context.new(), 0)

    p.fiber = Context.start(stack, &count, &p)

    for _ in 0..<3
        switch(&p.home, &p.fiber)
        print("main again")
```

```output
fiber says 1
main again
fiber says 2
main again
fiber says 3
main again
```

- **`Stack.reserve(size)`** maps a stack and answers a `&Stack`; dropping the last reference unmaps it.
- **`Context.start(stack, entry, arg)`** prepares a context that begins at `entry(arg)` on that stack
  the first time something switches to it. `entry` has the shape a [thread's body](/library/threads/)
  has, `*extern(@lets_out *T) -> unit`, and `T` is inferred from it. The
  [`@lets_out`](/reference/types/#what-the-callable-reads-may-outlive-the-call-lets-out) says the entry
  may hand on what it reads through its argument, as a scheduler's entry does when it unlinks the job
  it runs; an entry that does not, like `count`, stands there all the same.
- **`Context.new()`** is a context for the stack running now. It holds nothing until a switch leaves
  through it.
- **`switch(&from, &to)`** saves the running stack in `from` and resumes `to`. To the code that called
  it, it is an ordinary call that returns when something switches back to `from`.

A context is a value, and **the one a switch saved into is the one to resume**: hand its address
around, as `Pair` does above, rather than a copy taken before the switch.

## What a switch costs

**A call of `switch` is the compiler's own.** It is emitted in place, not called: a few instructions
that write where to resume into `from` — the stack pointer, the instruction after the switch, the
frame and base pointers and the floating-point control — take up `to`'s, and jump. Every other
register is declared clobbered, so the function around the switch saves only the values it has live
across it, wherever it chose to keep them, rather than a fixed set on every switch:

| machine | carried in the context | control |
|---|---|---|
| aarch64 | sp, the resume address, x29 (frame), x19 (base) | FPCR |
| x86_64 | rsp, the resume address, rbp (frame), rbx (base) | MXCSR's control bits, the x87 control word |

Everything the platform's calling convention says a callee keeps — on aarch64 x19–x30 and d8–d15, on
x86_64 rbx, rbp and r12–r15 — still holds across the switch, as it does across any call. A call
through a function value (`val go: &Fn(*Context, *Context) -> unit = switch`) reaches the library's
definition, which does the same out of line.

There is **no system call and no signal mask** in it, which is what makes it a few nanoseconds where
`swapcontext` takes a microsecond. A fiber runs with whatever signal mask its carrier has.

Because the floating-point control register is saved, **the rounding mode is each fiber's own**. A
fiber starts with the mode of the code that called `Context.start`:

```sysl
import sysl.fenv.{RoundingMode, rounding, set_rounding}
import sysl.fiber.{Context, Stack, switch}

struct Modes
    home: Context
    fiber: Context

upward(m: *Modes)
    print(s"fiber starts in ${rounding()}")
    set_rounding(Upward)
    switch(&m.fiber, &m.home)
    print(s"fiber still in ${rounding()}")
    switch(&m.fiber, &m.home)

main()
    val stack = Stack.reserve(64 * 1024).unwrap()
    var m = Modes(Context.new(), Context.new())

    set_rounding(TowardZero)
    m.fiber = Context.start(stack, &upward, &m)
    switch(&m.home, &m.fiber)
    print(s"main still in ${rounding()}")
    switch(&m.home, &m.fiber)
    set_rounding(NearestEven)
```

```output
fiber starts in RoundingMode.TowardZero
main still in RoundingMode.TowardZero
fiber still in RoundingMode.Upward
```

## A stack costs what is touched

`Stack.reserve` asks for address space, not memory: the system backs a page the first time something
touches it. **Reserving a megabyte for each of ten thousand fibers costs ten gigabytes of address
space and nothing else**, until they run. `Stack.committed()` says how much of a stack is resident
(`Stack.low()` and `Stack.high()` give the addresses a fiber may use, for a collector scanning a
parked stack or a bounds check):

```sysl
import sysl.fiber.{Context, Stack, switch}

struct Dig
    home: Context
    fiber: Context

// About a KiB of stack per frame, every byte of it written.
dig(n: int) -> int
    var pad: [1024]u8 = [0; 1024]

    for i in 0..<pad.len do pad[i] = u8(i % 7)

    if n == 0 then int(pad[1]) else dig(n - 1) + int(pad[n % 1024])

run(d: *Dig)
    print(dig(500))
    switch(&d.fiber, &d.home)

main()
    val stack = Stack.reserve(16 * 1024 * 1024).unwrap()
    var d = Dig(Context.new(), Context.new())

    d.fiber = Context.start(stack, &run, &d)
    switch(&d.home, &d.fiber)

    val kib = stack.committed() / 1024

    print(s"reserved ${stack.len() / 1024 / 1024} MiB, committed about half a MiB: ${kib > 400 && kib < 2048}")
```

```output
1498
reserved 16 MiB, committed about half a MiB: true
```

A stack holds at least the size asked for — the mapping is whole pages — and a size of zero is
refused as `Other(22)` (`EINVAL`).

## An overflow says it is one

Below every stack is a guard of at least 64 KiB that nothing may touch — wider than a page, so a frame
larger than a page cannot step over it. A fiber that recurses into it is reported exactly as the main
thread's overflow is, rather than as a bare segmentation fault:

```
sysl: this program has overflowed its stack -- a recursion with no base case, or a walk over a structure that contains itself
```

and the program ends with status 139, its printed output flushed. The guard is registered with the
process when the stack is reserved, and the first `switch` on a thread gives that thread the signal
stack the report is written from — so the report comes from whichever carrier the overflow happens
on.

## An entry never returns

There is no caller on a fiber's stack to return to. **A fiber ends by switching away for the last
time**, and whatever owns its stack drops it once nothing will switch to it again. An entry that does
return traps, and says so:

```sysl
import sysl.fiber.{Context, Stack, switch}

struct Once
    home: Context
    fiber: Context

forgetful(o: *Once)
    print("ran, and fell off the end")

main()
    val stack = Stack.reserve(64 * 1024).unwrap()
    var o = Once(Context.new(), Context.new())

    o.fiber = Context.start(stack, &forgetful, &o)
    switch(&o.home, &o.fiber)
```

```output trap
ran, and fell off the end
```

with this on standard error:

```
sysl: a fiber's entry function returned -- an entry must switch away for good, since there is no caller on its stack to return to
```

## A fiber may move between threads

A context saved on one thread may be resumed on another — that is what lets a runtime balance fibers
over its carriers. The stack and every register move with it. **What does not move is anything kept
per thread, which belongs to the carrier**:

- **`@thread_local` storage is the carrier's.** A fiber resumed on another thread reads and writes
  that thread's copy — in the very function that called `switch`, in a helper inlined into it, and in
  a loop around the switch. The compiler works a thread-local's address out again after every
  `switch`, at every optimization level, rather than letting the optimizer reuse the one from before
  it, which would be the old carrier's. That holds wherever the program names `switch` — a call, its
  address in a `*extern` value, a `&Fn` made of it — in any of its own modules or its packages'.
  (An address taken with `&` and kept in a variable across a switch is an ordinary pointer, and still
  names the copy it was taken from.) The standard library's own functions are compiled once for
  every program, without this: none of them switches, but one that calls back into code that does
  may go on, for the rest of that call, counting into the error trace of the carrier it began on.
- **[`sysl.threads.current()`](/library/threads/) answers the carrier** — the thread running now.
- **The [error return trace](/library/sys/) is the carrier's.** The sites it records are constants,
  never addresses in a frame, so a fiber that moves leaves nothing dangling, but a failure a fiber
  records lands in its carrier's trace among whatever else ran there. A runtime that wants each
  fiber's trace whole gives each fiber an `ErrorTraceStore` and makes it current around running it,
  on the carrier that runs it: `use_error_trace(&f.trace)` before the switch to the fiber, and
  `use_error_trace(null)` once it has switched back.
- **The overflow report needs nothing per thread**, as above.

A context made with `Context.new()` for a thread's **own** stack is resumed only on that thread: the
stack is the thread's, and the thread may end.

```sysl
import sysl.fiber.{Context, Stack, switch}
import sysl.threads.{ScopeSlot, scope_in}

@thread_local
var carrier: int = 0

struct Hop
    home: Context
    away: Context
    fiber: Context

hop(h: *Hop)
    print(s"first on carrier $carrier")
    switch(&h.fiber, &h.home)
    print(s"then on carrier $carrier")
    switch(&h.fiber, &h.away)

elsewhere(h: *Hop)
    carrier = 2
    switch(&h.away, &h.fiber)

main()
    carrier = 1

    val stack = Stack.reserve(64 * 1024).unwrap()
    var h = Hop(Context.new(), Context.new(), Context.new())

    h.fiber = Context.start(stack, &hop, &h)
    switch(&h.home, &h.fiber)

    val ph = &h
    var slots: [1]ScopeSlot = [ScopeSlot.new(); 1]

    scope_in(slots[..], (s) -> s.start(&elsewhere, ph))
    print(s"main on carrier $carrier")
```

```output
first on carrier 1
then on carrier 2
main on carrier 1
```

## Backtraces, debuggers and sanitizers

The frame a fiber's entry is called from has a **zero frame pointer**, and its return address is
marked undefined in the unwind tables, so `backtrace()`, a sampler, a crash report or a debugger walks
a fiber's stack from where it is down to its entry and stops there, rather than wandering off the top
of the mapping. In lldb a fiber's `bt` ends at the entry, with `sysl_fiber_boot` — the library's
few instructions that call it — as the last frame.

Under AddressSanitizer (`SYSL_EXTRA_CFLAGS="-fsanitize=address -g"`) every switch tells the sanitizer
which stack it is moving to, with `__sanitizer_start_switch_fiber` and
`__sanitizer_finish_switch_fiber`, so a fiber's stack is checked like any other and a switch is not
mistaken for a stack-buffer overflow.
