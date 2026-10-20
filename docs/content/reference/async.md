---
title: Async and tasks
summary: Async functions, the Task they hand back, await, the executor contract, frames on the heap, cancellation by drop, and when a task may cross a concurrency domain.
weight: 115
---

**Calling a function marked `async` returns a `Task[T]` instead of running the function**, and
**`await t`** runs that task until it produces its `T`. An async function may await another, so a
chain of them reads as straight-line code, while every `await` is a point where the whole chain can
suspend and later resume.

```sysl
async read_sensor(channel: int) -> int = channel * 100 + 17

async average(a: int, b: int) -> int
    val x = await read_sensor(a)
    val y = await read_sensor(b)
    (x + y) / 2

print(block_on(average(1, 3)))
```

```output
217
```

**The language supplies the keywords and the contract, but no scheduler.** `block_on` above runs one
task to completion on the calling thread. Anything that interleaves tasks, such as an event loop, a
run queue or a timer wheel, is an ordinary program written against four builtins
([the executor contract](#the-executor-contract-step-park-and-yield-now)). Neither the language nor
the standard library contains an executor ([where the executors live](#where-the-executors-live)).

## Calling an async function makes a task, and runs nothing

**A task is lazy.** The call copies its arguments into a frame of its own and stops before the first
statement of the body; nothing in the body runs until something drives the task.

```sysl
async read_sensor(channel: int) -> int
    print(s"reading channel $channel")
    channel * 100 + 17

val t = read_sensor(2)

print("task made")
print(block_on(t))
```

```output
task made
reading channel 2
217
```

**`Task[T]` is a type a program may write**, and a task held in a binding is awaited wherever the
body reaches the `await`. An async function with no result returns a `Task[unit]`, and awaiting it
is a statement:

```sysl
async sample() -> int = 21

async log_tick()
    print("tick")

async run() -> int
    val pending: Task[int] = sample()

    await log_tick()

    val beat: Task[unit] = log_tick()

    await beat
    await pending * 2

print(block_on(run()))
```

```output
tick
tick
42
```

`await pending * 2` is `(await pending) * 2` — [`await` binds tighter than arithmetic](#where-await-binds).

### `Task` takes one type argument

The argument is the type the task produces, and there is no second argument for an error: a task
that can fail produces a `Result`.

```sysl
async check(t: Task[int, string]) -> int = 0
```

```error
'Task' takes one type argument — the type the task ends with — and 2 were written
```

### A program's own `Task` is the nearer name

`Task` is a name the compiler supplies, not a reserved word, so a program that declares its own type
called `Task` gets that type wherever it writes the name. A call to an async function still returns
the compiler's task, which the program awaits as usual:

```sysl
struct Task
    id: int
    priority: int

async read_sensor(channel: int) -> int = channel * 100 + 17

async schedule(t: Task) -> int = await read_sensor(t.id) + t.priority

print(block_on(schedule(Task(2, 5))))
```

```output
222
```

## `await` runs a task to its result

`await` is a prefix on an expression whose value is a task. Inside an async function it runs that
task to completion, and its value is the task's result. Each time the awaited task suspends, the
awaiting task suspends with it, so a suspension anywhere in a chain propagates out to whatever is
driving the outermost task.

### Where `await` binds

**`await` takes in the operand's calls, member selections and indexes, and stops at the first `?`**,
so the `?` applies to the awaited result, which is the order a fallible async call needs:

| written | read as |
|---|---|
| `await dev.read(512)?` | `(await dev.read(512))?` |
| `await f()?.x` | `((await f())?).x` |
| `await xs[0]` | `await (xs[0])` |
| `await f() + 1` | `(await f()) + 1` |
| `1 + await f()` | `1 + (await f())` |
| `-await f()` | `-(await f())` |

Expressions without `await` are unaffected: `-a?` is `-(a?)` and `a.b?.c` is `(a.b?).c`.

```sysl
struct Dma
    channel: int

    async read(self, n: int) -> Result[int, string] =
        if n > 512 then Err(s"$n bytes is more than one transfer") else Ok(n)

async fill(d: Dma, first: int, second: int) -> Result[int, string]
    val a = await d.read(first)?
    val b = await d.read(second)? + 1
    Ok(a + b)

show(r: Result[int, string]) -> string = r match
    Ok(n) -> s"moved $n"
    Err(why) -> s"refused: $why"

val dev = Dma(3)

print(show(block_on(fill(dev, 256, 128))))
print(show(block_on(fill(dev, 256, 4096))))
```

```output
moved 385
refused: 4096 bytes is more than one transfer
```

### A task where its value was wanted

**A missing `await` is caught by the type checker**, and where the position expects exactly the type
the task produces, the diagnostic says what was left out:

```sysl
async read_sensor(channel: int) -> int = channel * 100 + 17

async run() -> int
    val raw: int = read_sensor(1)
    raw / 2

print(block_on(run()))
```

```error
cannot initialize 'raw': declared int but the value is Task[int] — calling an 'async' function hands back its task rather than running it, so write 'await' in front to run it and take the int it ends with
```

The same hint is added at an argument, at a `return`, and at a body's last expression:

```sysl
async read_sensor(channel: int) -> int = channel * 100 + 17

celsius(raw: int) -> int = raw / 10

async run() -> int
    celsius(read_sensor(1))

print(block_on(run()))
```

```error
'raw' of 'celsius' is int, but Task[int] was given — calling an 'async' function hands back its task rather than running it, so write 'await' in front to run it and take the int it ends with
```

The fix is the one the message names, at the binding or in the argument itself:

```sysl
async read_sensor(channel: int) -> int = channel * 100 + 17

celsius(raw: int) -> int = raw / 10

async run() -> int
    val raw = await read_sensor(1)
    celsius(raw) + celsius(await read_sensor(2))

print(block_on(run()))
```

```output
32
```

**The hint is given only where `await` would actually fix the error.** A task where some other type
is expected is an ordinary mismatch, and the diagnostic does not mention `await`:

```sysl
async read_sensor(channel: int) -> int = channel * 100 + 17

async run() -> int
    val label: string = read_sensor(1)
    1

print(block_on(run()))
```

```error
cannot initialize 'label': declared string but the value is Task[int]
```

## Where `await` may be written

**Only in the body of an async function.** Suspending requires a task to suspend, and an ordinary
function has no task of its own:

```sysl
async read_sensor(channel: int) -> int = channel * 100 + 17

scaled(channel: int) -> int
    await read_sensor(channel) * 2

print(scaled(1))
```

```error
'await' may only be written in the body of an 'async' function, and the function it stands in is not one — write 'async' in front of its name to make it one
```

**Not in the entry file's statements**, which form the program's `main`: C calls it, and C has no
way to drive a task. That is what `block_on` is for, as in every program on this page:

```sysl
async read_sensor(channel: int) -> int = channel * 100 + 17

print(await read_sensor(1))
```

```error
'await' may only be written in the body of an 'async' function, and this is the entry file's body — the program's 'main', which C calls directly and cannot drive a task through. Move the work into an 'async' function
```

**Not in a closure, even one written inside an async function.** A closure is a function in its own
right and is never async, so it has no task to wait in:

```sysl
async read_sensor(channel: int) -> int = channel * 100 + 17

async sweep() -> int
    val reader = (c: int) -> await read_sensor(c)
    reader(1) + reader(2)

print(block_on(sweep()))
```

```error
'await' cannot be written in a closure: a closure is not itself async, even inside an 'async' function, so there is no task of its own for it to wait in. Await in the enclosing function's body and hand the closure the value
```

Await in the function's body and pass the closure the values it needs:

```sysl
async read_sensor(channel: int) -> int = channel * 100 + 17

async sweep() -> int
    val first = await read_sensor(1)
    val second = await read_sensor(2)
    val offset = (c: int) -> c - 17
    offset(first) + offset(second)

print(block_on(sweep()))
```

```output
300
```

**And only on a task**:

```sysl
async report(label: string) -> int
    await label
    1

print(block_on(report("adc")))
```

```error
'await' needs a Task, which is what calling an 'async' function hands back, but this value is string
```

`await` may appear wherever a value may appear in the async function's body: a binding, an
argument, a branch of an `if`, an arm of a `match`.

## What may be `async`

A function, a generic function, and a method. A generic async function is instantiated at each type
its calls infer, like any generic:

```sysl
async latest[T](readings: []T) -> T
    await yield_now()
    readings[readings.len - 1]

async report() -> string
    val volts = await latest([3.1, 3.3, 3.2])
    val label = await latest(["idle", "sampling"])
    s"$label at $volts V"

print(block_on(report()))
```

```output
sampling at 3.2 V
```

**A function called directly by anything other than sysl cannot be async**, because the caller would
receive a task and have no way to drive it. An `@export`ed function is the first case:

```sysl
async sample() -> int = 7

@export async tick() -> int = 1

print(block_on(sample()))
```

```error
an '@export'ed function cannot be 'async': C calls it directly, and calling an async function only makes its task, which C has no way to drive. Export a plain function and keep the 'async' one behind it
```

The structure the message describes is a plain export that drives the async function:

```sysl
async read_sensor(channel: int) -> int = channel * 100 + 17

@export("sensor_read")
sensor_read(channel: int) -> int = block_on(read_sensor(channel))

print(sensor_read(4))
```

```output
417
```

An `extern` names a C function, and an interrupt handler is called by the hardware:

```sysl
async extern "getpid" c_pid() -> int
```

```error
an 'extern' cannot be 'async': it names a C function, called directly, and calling an async function only makes its task, which C has no way to drive
```

```sysl
async interrupt timer()
    print(1)
```

```error
an interrupt handler cannot be 'async': the hardware calls it directly, and calling an async function only makes its task, which nothing there can drive
```

**`async` marks a declaration, so it cannot precede a statement.** The entry file's statements are
`main`, which is also why `await` cannot appear among them:

```sysl
print("booting")
async print("sampling")
```

```error
'async' marks a function declaration, and what follows it here is a statement. The statements of a body run where they stand — the entry file's are the program's 'main', which C calls directly and cannot drive a task through — so put the work in an 'async' function instead
```

**A property cannot be async**: it is read like a field, and reading a field involves no call that
could return a task.

```sysl
struct Adc
    channel: int

    async reading -> int = self.channel * 100
```

```error
a property cannot be 'async': it is read like a field, with no call to hand a task back from — make it a method, written with '()'
```

Written as a method, it is an ordinary async member, called and awaited through the receiver:

```sysl
struct Adc
    channel: int

    async reading(self) -> int = self.channel * 100

print(block_on(Adc(3).reading()))
```

```output
300
```

## `async` and `await` are contextual

Neither word is [reserved](/reference/lexical/#reserved-words). `async` is a keyword only where a
declaration follows it, and `await` only where an operand follows it, so both remain available as
names, even in a program that also uses them as keywords:

```sysl
var async = 1
var await = 2

print(async + await)
```

```output
3
```

## Running a task

### `block_on`

**`block_on(t)` runs `t` on the calling thread until it ends, and its value is the task's result.**
It is the one driver the compiler supplies, and it is an ordinary call: it may appear in the entry
file, in a plain function, and behind an `@export`.

Nothing but `block_on` can wake the task it drives, since no other thread is running it, so **a
task that parks and is never woken traps** rather than spinning forever:

```sysl
async settle()
    await park((wake) -> ())

block_on(settle())
```

```output trap
```

### The executor contract: `step`, `park` and `yield_now`

Four builtins form the entire interface between a task and whatever schedules it:

| builtin | what it does |
|---|---|
| `block_on(t)` | runs `t` on this thread until it ends; its value is the result |
| `step(t, wake: &Fn() -> unit) -> bool` | resumes `t` once; `true` once `t` has ended |
| `await park(register: &Fn(&Fn() -> unit) -> unit)` | hands `register` a **waker** and suspends until the waker is called |
| `await yield_now()` | wakes the task being stepped and suspends once |

**A waker is a `&Fn() -> unit`.** An executor passes `step` the waker it wants called when the task
can run again. A task's `park` passes its registrar a waker of its own, which the registrar stores
wherever the awaited event will find it: a timer, an interrupt, a completion queue. Calling that
waker calls the executor's.

An executor is therefore a run queue and a loop over `step`. Here is a complete one, with a timer as
the event source:

```sysl
import sysl.buf.{Buf, buf}

// The executor: a run queue of task numbers, stepped in order until it is empty.
static var tasks: [2]Option[Task[unit]] = [None, None]
static var ready: Buf[usize] = buf()

spawn(id: usize, t: Task[unit])
    tasks[id] = Some(t)
    ready.push(id)

run()
    var i: usize = 0

    while i < ready.len()
        val id = ready[i]

        i += 1

        tasks[id] match
            Some(t) -> if step(t, () -> ready.push(id)) then tasks[id] = None
            None -> ()

    ready.clear()
end run

// The event source: one timer, and the waker it will call when it fires.
static var on_timer: Option[&Fn() -> unit] = None

arm(wake: &Fn() -> unit)
    on_timer = Some(wake)

fire_timer()
    on_timer match
        Some(wake) -> wake()
        None -> ()

// The tasks.
async blink(n: int)
    for i in 0..<n
        print(s"led $i")
        await yield_now()

async sample()
    print("sampler waits for the timer")
    await park(arm)
    print("sampler read the adc")

spawn(0, blink(2))
spawn(1, sample())
run()

print("-- timer fires --")
fire_timer()
run()
```

```output
led 0
sampler waits for the timer
led 1
-- timer fires --
sampler read the adc
```

`yield_now` wakes the task before it suspends, which is why `blink` goes back on the queue behind
`sample` each round. `sample` parks with nothing to wake it, so the first `run` ends with it off the
queue; the timer's waker puts it back.

**Stepping a finished task resumes nothing and returns `true` again**, so an executor that steps a
task one time too many is still correct.

### A waker fires at most once

**However many times a waker is called, the executor is notified once**, and a waker whose park has
ended is a no-op, so a stale waker held by an event source cannot wake the task from a *later* park:

```sysl
import sysl.buf.{Buf, buf}

static var kept: Buf[&Fn() -> unit] = buf()
static var heard: int = 0

keep(wake: &Fn() -> unit)
    kept.push(wake)

async adc_ready() -> int
    await park(keep)
    await park(keep)
    4095

val t = adc_ready()
val wake: &Fn() -> unit = () -> heard += 1

print(step(t, wake), heard)

kept[0]()
kept[0]()
print(heard)

print(step(t, wake), heard)

kept[0]()
print(heard)

kept[1]()
print(step(t, wake), heard)
```

```output
false 0
1
false 1
1
true 2
```

A waker called *during* its own registration, for an event that has already happened, still costs
the task one suspension and is delivered once. A task dropped while it is parked disarms its waker as
it is freed, so an event source that calls the waker afterwards wakes nothing.

### A park at any depth wakes the outermost task

An executor steps the task it holds, and that task may be awaiting a task that awaits another. **A
park three awaits down calls the waker of the step in progress**, which belongs to the outermost task,
the only one the executor knows about. A step that arrives while the park has not been woken is
absorbed by the park rather than seen by the task.

```sysl
static var on_irq: Option[&Fn() -> unit] = None

arm(wake: &Fn() -> unit)
    on_irq = Some(wake)

irq()
    on_irq match
        Some(wake) -> wake()
        None -> ()

async uart_byte() -> int
    await park(arm)
    0x41

async uart_line() -> int = await uart_byte() + 1

async console()
    print(s"got ${await uart_line()}")

val t = console()
val wake: &Fn() -> unit = () -> print("the executor hears: console can run")

print(step(t, wake))
irq()
print(step(t, wake))
```

```output
false
the executor hears: console can run
got 66
true
```

### What the contract refuses

`park` is a task and can only be awaited:

```sysl
async wait_for_dma() -> int
    val pending = park((wake) -> wake())
    512
```

```error
'park(…)' is a task that waits on the waker it hands its registrar, and it can only be awaited — write 'await park(…)' in the body of an 'async' function
```

`step` takes both the task and the waker:

```sysl
async blink()
    await yield_now()

poll(t: Task[unit]) -> bool = step(t)
```

```error
'step' takes the task it resumes and the waker its parks are to call, but 1 argument was given
```

```sysl
async blink()
    await yield_now()

poll(t: Task[unit]) -> bool = step(t, 1)
```

```error
'step' needs the waker as a '&Fn() -> unit', the closure a park in the task calls to say the task can run again, but this value is int
```

`block_on`, `step`, `park` and `yield_now` are names the compiler supplies, not reserved words, so a
program's own declaration of one of these names takes precedence and is called as written. **That
includes an import**: `sysl.threads` declares its own `yield_now`, which yields the thread, so a file
that imports the whole module gets that one, and awaiting it means awaiting a `bool`. The diagnostic
says which `yield_now` the call resolved to:

```sysl
import sysl.threads.*

async blink()
    await yield_now()

block_on(blink())
```

```error
'await' needs a Task, which is what calling an 'async' function hands back, but this value is bool — the 'yield_now' called here resolved to 'sysl.threads.yield_now', declared in 'sysl.threads', and a declaration of that name stands in front of the task form 'yield_now(…)', so the task form is shadowed
```

A local of the same name shadows the task form in the same way, and the diagnostic names it as a
local binding. Import what the file uses by name, as in `import sysl.threads.spawn`, and the task's
`yield_now` remains in scope.

## A task's frame is on the heap

**A task's frame (its parameters, its result, and whatever is live across an `await`) is allocated
when the async function is called and freed when the last reference to the task is dropped.** The
storage comes from one pair of functions:

```
sysl_task_alloc(size: usize) -> *u8
sysl_task_free(p: *u8)
```

The compiler defines both over the program's allocator, **weakly**, so a program that defines either
with [`@export`](/reference/ffi/) replaces the default, and every frame, and every waker a `park`
creates, comes from the program's own allocator. Counting the calls shows each frame being returned:

```sysl
extern "malloc" c_malloc(n: usize) -> *u8
extern "free" c_free(p: *u8)

static var frames: int = 0
static var freed: int = 0

@export("sysl_task_alloc")
task_alloc(size: usize) -> *u8
    frames += 1
    c_malloc(size)

@export("sysl_task_free")
task_free(p: *u8)
    freed += 1
    c_free(p)

async read_sensor(channel: int) -> int = channel * 100 + 17

async average() -> int
    await yield_now()
    (await read_sensor(1) + await read_sensor(3)) / 2

print(block_on(average()))
print(frames > 0, frames == freed)
```

```output
217
true true
```

**On a microcontroller, this pair lets frames come from a static pool** instead of a heap. The object
a board build produces contains no coroutine intrinsics, because the compiler has lowered all of them,
and it does not reference `malloc`: its frames come from whatever the program's two definitions call.

```sysl build=c target=thumbv7em-freestanding
// Frames come from a fixed pool the firmware owns, not from a heap.
extern "pool_take" pool_take(n: usize) -> *u8
extern "pool_give" pool_give(p: *u8)

@export("sysl_task_alloc")
task_alloc(size: usize) -> *u8 = pool_take(size)

@export("sysl_task_free")
task_free(p: *u8) = pool_give(p)

async read_sensor(channel: int) -> int
    await yield_now()
    channel * 100 + 17

async average(a: int, b: int) -> int = (await read_sensor(a) + await read_sensor(b)) / 2

@export("sensor_average")
sensor_average(a: int, b: int) -> int = block_on(average(a, b))
```

**A task is a reference-counted value**: a binding, a field or an `Option` holding one keeps a
reference, and the frame is freed when the last reference is dropped. That happens exactly once,
whether the task finished, was suspended partway, or never started.

### A frame never moves, so a borrow may cross an `await`

**A task's frame is allocated once, by the call, and stays at that address until it is freed.** A
task value is the address of the frame, not the frame itself: passing a task to `step`, to another
task that awaits it, to a join, or into a buffer or an `Option` copies that address, and every
resumption reaches the body's locals through it. No sysl code can copy a frame, so, unlike Rust, sysl
needs no `Pin`: a body may lend out a view of its own array, or the address of its own local, and
hold the loan across an `await`.

```sysl
static var staged: [16]u8 = [0; 16]
static var on_irq: Option[&Fn() -> unit] = None

arm(wake: &Fn() -> unit)
    on_irq = Some(wake)

// Holds its caller's view across its own await, and fills it once it is woken.
async read_into(into: []u8)
    await park(arm)

    for i in 0..<into.len
        into[i] = staged[i]

async handle() -> int
    var buf: [1024]u8 = [0; 1024]
    val head = buf[0..<4]

    await read_into(head)
    int(head[0]) + int(head[3]) + int(buf[4])

val t = handle()

print(step(t, () -> ()))

for i in 0..<16
    staged[i] = u8(10 + i)

on_irq.unwrap()()
print(step(t, () -> ()), block_on(t))
```

```output
false
true 23
```

`buf` lives in `handle`'s frame, `head` views it, and `read_into`'s frame holds `head` while both
tasks are parked; the bytes are written through it after the task resumes and are read back from
`buf`. A view passed to something that keeps it beyond the call, such as a device's static, is the
case [`--explain-escapes`](/reference/memory/) reports instead: the array is promoted to the heap,
and the loan is equally safe.

**A local lent across an `await` also keeps its `@align(n)`.** Whatever alignment it requests,
whether on the binding or through a type declared `@align(n)`, its storage in the frame starts on
that boundary at every optimization level:

```sysl
@align(64)
struct Line
    bytes: [64]u8

async fill() -> int
    @align(64)
    var buf: [64]u8 = [0; 64]
    val head = buf[0..<4]
    var line = Line([0; 64])
    val p = &line

    head[0] = 7
    p.bytes[0] = 30
    await yield_now()
    print(usize(&buf) % 64, usize(p) % 64)
    int(head[0]) + int(p.bytes[0])

print(block_on(fill()))
```

```output
0 0
37
```

## Dropping a task cancels it

**There is no cancel operation: a task is cancelled by dropping it.** A task dropped while it is
suspended releases exactly what a `return` at that suspension point would: its pending `defer`s run,
then its locals and parameters are released, in the same order a `return` releases them. A task that
is awaiting another releases the awaited task first. A task dropped before it started releases only
its parameters, since none of its body ran.

```sysl
struct DmaBuffer
    id: int

impl Drop for DmaBuffer
    drop(self)
        print(s"buffer ${self.id} back to the pool")

async transfer(id: int)
    val held: &DmaBuffer = DmaBuffer(id)
    defer print(s"transfer $id closed")
    await yield_now()
    print(s"transfer $id finished")

started_then_dropped()
    val t = transfer(1)

    step(t, () -> ())
    print("giving up on transfer 1")

never_started()
    val t = transfer(2)

    print("transfer 2 made, never stepped")

started_then_dropped()
print("--")
never_started()
print("done")
```

```output
giving up on transfer 1
transfer 1 closed
buffer 1 back to the pool
--
transfer 2 made, never stepped
done
```

`transfer 1 finished` never prints: the task was dropped at its `await`, and the code after it never
runs. Transfer 2 never reached its first statement, so it created no buffer to release.

**A task passed to an async function as a temporary belongs to the task it was passed to.** A
temporary argument of an async call [moves into the callee's frame](/reference/memory/), so when the
callee returns without finishing the task it was given, that task is dropped, and so cancelled,
immediately, before the caller does anything with the result:

```sysl
async parked()
    defer print("parked task cancelled")
    await park((w) -> ())

async give_up(t: Task[unit]) -> Option[int]
    step(t, () -> ())
    None

async caller()
    (await give_up(parked())) match
        None -> print("gave up")
        Some(n) -> print(s"got $n")
    print("after")

block_on(caller())
```

```output
parked task cancelled
gave up
after
```

A timeout written this way, `(await timeout(d, conn.read())) match …`, releases the abandoned read as
soon as the timeout drops it. A task held in a named local belongs to the caller as well as the
callee, and lives until the local's block ends.

## A task crossing a concurrency domain

**A task may be passed to another thread, or anywhere a [`@crossing`](/library/threads/) parameter
reaches, only if everything its frame can hold is safe to share.** For each thing the frame holds,
the compiler applies the same check it applies to a closure's captures when that closure is shared
as a `&sync Fn`:

- its **parameters**;
- its **result**;
- every **binding live across an `await`**: a reference-counted local lives to the end of its block,
  so one read only before the `await` is still held if its block extends past it;
- every **value computed before an `await` in the same statement**: an argument to the left of an
  awaited one, the left operand of an operator whose right operand awaits, a receiver whose call
  awaits in an argument, and the elements of an array or tuple or the text of an interpolation built
  before an awaited part. A temporary consumed in an earlier statement is gone before the `await`,
  and a scalar temporary is always safe;
- every **task it awaits**, followed down the chain; for a task passed in as a parameter, the check
  applies to the argument given at the call.

A task holding only scalars crosses, and runs on the other thread while this thread runs its own:

```sysl
import sysl.threads.spawn

struct Job
    t: Task[int]
    out: int

run(j: *Job)
    j.out = block_on(j.t)

async checksum(n: int) -> int
    var total = 0

    for i in 0..<n
        await yield_now()
        total += i

    total
end checksum

var job = Job(checksum(1000), 0)
val worker = spawn(&run, &job).unwrap()
val mine = block_on(checksum(2000))

worker.join()
print(job.out, mine)
```

```output
499500 1999000
```

The same hand-over is refused once the frame holds a `string`, whose bytes are owned through a
reference count that is not atomic. The diagnostic names the function and the parameter:

```sysl
import sysl.threads.spawn

struct Job
    t: Task[int]
    out: int

run(j: *Job)
    j.out = block_on(j.t)

async checksum(n: int, label: string) -> int
    var total = 0

    for i in 0..<n
        await yield_now()
        total += i

    total
end checksum

var job = Job(checksum(1000, "firmware"), 0)

spawn(&run, &job).unwrap().join()
print(job.out)
```

```error
but its 't' is a task of 'checksum', whose frame holds its parameter 'label', a 'string', which owns its bytes through a count that is not atomic
```

An ordinary `&T` held across an `await` is refused the same way, at the call that makes the task:

```sysl
struct Reading
    value: int

@crossing(t)
hand_off(t: Task[int]) = ()

async filtered(raw: int) -> int
    val r: &Reading = Reading(raw)
    await yield_now()
    r.value / 4

hand_off(filtered(1023))
```

```error
't' of 'hand_off' reaches another concurrency domain, so every count inside it has to be atomic — but it is a task of 'filtered', whose frame holds its local 'r', a '&Reading', whose count is not. Hold it as a '&sync Reading'
```

Held as the message suggests, the task crosses. So does a task whose `&T` is released before its
first `await`, because then the frame never holds it:

```sysl
struct Reading
    value: int

@crossing(t)
hand_off(t: Task[int]) -> int = block_on(t)

// The reading is shared through an atomic count, so the frame may cross.
async filtered(raw: int) -> int
    val r: &sync Reading = Reading(raw)
    await yield_now()
    r.value / 4

// The reading is gone before the first await, so the frame never holds it.
async scaled(raw: int) -> int
    var v = 0

    if raw > 0
        val r: &Reading = Reading(raw)
        v = r.value

    await yield_now()
    v * 2
end scaled

print(hand_off(filtered(1023)), hand_off(scaled(100)))
```

```output
255 200
```

A value a statement has already computed when it reaches an `await` waits in the frame until the
statement finishes, so it is checked too; here it is the label, built before the awaited reading:

```sysl
@crossing(t)
hand_off(t: Task[int]) = ()

async sensor() -> int
    await yield_now()
    7

describe(label: string, n: int) -> int = label.len + n

async report(n: int) -> int
    describe(s"channel $n", await sensor())

hand_off(report(3))
```

```error
but it is a task of 'report', whose frame holds a value computed before an 'await' in the same statement, a 'string', which owns its bytes through a count that is not atomic
```

If the value is computed into a local in an earlier statement and consumed there, or is a scalar,
it is not in the frame at the `await`.

**The check is made where the call that creates the task is visible.** A task that arrives as a
plain parameter could have been created by any async function, so passing it on is refused unless
that parameter is itself marked `@crossing`, which moves the check to the callers. A task held in a
`&sync` structure is checked where the structure is boxed, and one captured by a closure shared as a
`&sync Fn` is checked at the closure. Everything refused here is accepted when the task stays in its
own domain.

### A task that parks stays in its domain

**A task that awaits `park` is never allowed to cross.** Its waker is a `&Fn() -> unit` belonging to
the domain whose executor steps it, so the task belongs to that domain as well:

```sysl
@crossing(t)
hand_off(t: Task[int]) = ()

async wait_for_dma() -> int
    await park((wake) -> wake())
    512

hand_off(wait_for_dma())
```

```error
but it is a task of 'wait_for_dma', whose frame holds the 'park' it awaits — a task that parks is handed a waker, and a waker is a '&Fn() -> unit' belonging to the domain whose executor steps the task, so a task that parks stays in that domain
```

The same rule keeps a waker in its domain from the other direction: a registrar may not capture the
waker it receives in a closure shared as a `&sync Fn`, and `step` does not accept a
`&sync Fn() -> unit` as its waker. No form makes a waker atomic, so the diagnostic says where it
belongs instead:

```sysl
@crossing(f)
later(f: &sync Fn() -> unit) = ()

async waits() -> int
    await park(w -> later(() -> w()))
    1

print(block_on(waits()))
```

```error
a closure shared between two domains may be called from either, so every count it captures has to be atomic — but the 'w' it captures is the waker 'park' handed its registrar, a '&Fn() -> unit' belonging to the domain whose executor steps the task, and no spelling makes it atomic. Keep the waker in that domain: let the other domain — an interrupt, another thread — signal the executor, and have the executor call the waker
```

**In a kernel, the other domain is usually the interrupt that completes a request**, and the obvious
first design is a waker stored in module storage and called from the handler. Marked
[`@domain(interrupt)`](/reference/attributes/#domain-interrupt-a-function-entered-on-top-of-whatever-was-running),
the handler is checked against everything it reaches, and the waker is what it reaches:

```sysl build=c target=aarch64-freestanding
var on_complete: Option[&Fn() -> unit] = None

arm(wake: &Fn() -> unit)
    on_complete = Some(wake)

@domain(interrupt)
@export("disk_irq")
disk_irq()
    on_complete match
        Some(wake) -> wake()
        None -> ()

async read_sector(n: u64) -> u64
    await park(arm)
    n * 2
```

```error
'disk_irq' is entered in the 'interrupt' domain, on top of whatever was running, so every count it reaches has to be atomic — but the module storage 'on_complete' it reaches holds a '&Fn() -> unit' in its 'Some(value)', whose count is not. If it holds a waker 'park' handed its registrar, no spelling makes that atomic: keep the waker in the executor's domain — have this one signal the executor, and the executor call the waker. A callback of the program's own is held as a '&sync Fn() -> unit' ('06')
```

The handler may do what an `Atomic` word or a `&sync` object allows: set a flag the executor reads,
or push the request's number onto a ring the executor drains. The executor, in its own domain, then
calls the waker.

`yield_now` creates no waker, so a task that only yields can cross; `checksum` above is one.

## Where the executors live

**Not in the language and not in the standard library.** The compiler supplies `block_on` and the
contract above; an executor is a package written against the contract in ordinary sysl, exactly like
the run queue on this page.

- **[`kairos`](https://github.com/sysl-lang/kairos)** is an event loop designed for microcontrollers
  first, with numbered sources raised by interrupts and timers on one clock. It is also an executor:
  tasks spawned on the loop sleep on its timers and wait on its sources, with the wakers stored where
  the timers and sources fire them. A board and a host use the same loop; only the driver value
  differs.
- With its **`uv` feature** (`features = [uv]`), kairos runs the same loop over
  **[`libuv`](https://github.com/sysl-lang/libuv)** on a host: sockets and whole-file reads and
  writes are tasks awaited alongside `sleep` and `timeout`. With the feature off, nothing from libuv
  is fetched or linked.

---

Next: [the foreign interface](/reference/ffi/).
