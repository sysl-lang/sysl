---
title: Async and tasks
summary: Async functions, the Task they hand back, await, the executor contract, frames on the heap, cancellation by drop, and when a task may cross a concurrency domain.
weight: 115
---

**`async` written on a function makes calling it hand back a `Task[T]` instead of running it**, and
**`await t`** runs that task to the `T` it ends with. An async function may await another, so a chain
of them reads as straight-line code while every `await` is a place the whole chain can stop and be
picked up again later.

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

**The language supplies the words and the contract, and nothing that schedules.** `block_on` above
runs one task to its end on the calling thread; anything that interleaves tasks — an event loop, a
run queue, a timer wheel — is an ordinary program written against four builtins
([the executor contract](#the-executor-contract-step-park-and-yield-now)). No executor lives in the
language or the standard library ([where the executors live](#where-the-executors-live)).

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

**`Task[T]` is a type a program may write**, and a task held in a binding is awaited whenever the
body gets to it. An async function with no result is a `Task[unit]`, and its `await` is a statement:

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

The argument is the type the task ends with, and there is no second one for an error: a task that
can fail ends with a `Result`.

```sysl
async check(t: Task[int, string]) -> int = 0
```

```error
'Task' takes one type argument — the type the task ends with — and 2 were written
```

### A program's own `Task` is the nearer name

`Task` is a name the compiler supplies, not a reserved word, so a program that declares a type called
`Task` gets its own wherever it writes the word — and an async function's call still hands back the
compiler's task, which the program then awaits as usual:

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
task until it ends and is the value it ended with; each time the awaited task stops partway, the
awaiting one stops with it, so a stop anywhere in a chain travels out to whatever is driving the
outermost task.

### Where `await` binds

**`await` takes the operand's calls, member selections and indexes, and stops at the first `?`** — so
the `?` applies to what was awaited, which is the order a fallible async call wants:

| written | read as |
|---|---|
| `await dev.read(512)?` | `(await dev.read(512))?` |
| `await f()?.x` | `((await f())?).x` |
| `await xs[0]` | `await (xs[0])` |
| `await f() + 1` | `(await f()) + 1` |
| `1 + await f()` | `1 + (await f())` |
| `-await f()` | `-(await f())` |

Nothing written without `await` reads differently for it: `-a?` is still `-(a?)` and `a.b?.c` is
still `(a.b?).c`.

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

**Leaving out `await` is the mistake the type system sees first**, and where the position wanted the
very type the task ends with, the refusal says what was left out:

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

The same sentence is added at an argument, at a `return`, and at a body's last expression:

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

The repair is the one the message names, at the binding or in the argument itself:

```sysl
async read_sensor(channel: int) -> int = channel * 100 + 17

celsius(raw: int) -> int = raw / 10

async run() -> int
    val raw: int = await read_sensor(1)
    celsius(raw) + celsius(await read_sensor(2))

print(block_on(run()))
```

```output
32
```

**The hint is given only where `await` would actually fix it.** A task where some other type was
wanted is an ordinary mismatch, and says nothing about `await`:

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

**Only in the body of an async function.** A stop has to have somewhere to stop *to*, and an ordinary
function has no task of its own to suspend:

```sysl
async read_sensor(channel: int) -> int = channel * 100 + 17

scaled(channel: int) -> int
    await read_sensor(channel) * 2

print(scaled(1))
```

```error
'await' may only be written in the body of an 'async' function, and the function it stands in is not one — write 'async' in front of its name to make it one
```

**Not in the entry file's statements**, which are the program's `main` — C calls it, and C has no
way to drive a task. That is what `block_on` is for, as in every program on this page:

```sysl
async read_sensor(channel: int) -> int = channel * 100 + 17

print(await read_sensor(1))
```

```error
'await' may only be written in the body of an 'async' function, and this is the entry file's body — the program's 'main', which C calls directly and cannot drive a task through. Move the work into an 'async' function
```

**Not in a closure, even one written inside an async function.** A closure is a function of its own
and is never async, so there is no task for it to wait in:

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

Awaiting in the body and handing the closure what it needs:

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

`await` stands wherever a value of the async function's body may — a binding, an argument, an `if`'s
branch, a `match`'s arm.

## What may be `async`

A function, a generic function, and a method. A generic async function runs at each type its calls
solved, as any generic does:

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

**Nothing that something other than sysl calls directly may be async**, because the caller would
receive a task and have no way to drive it. An `@export`ed function is the first of those:

```sysl
async sample() -> int = 7

@export async tick() -> int = 1

print(block_on(sample()))
```

```error
an '@export'ed function cannot be 'async': C calls it directly, and calling an async function only makes its task, which C has no way to drive. Export a plain function and keep the 'async' one behind it
```

The shape the message names — a plain export that drives the async function:

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

**`async` marks a declaration, so it cannot stand in front of a statement** — the entry file's
statements are `main`, for the same reason `await` cannot stand among them:

```sysl
print("booting")
async print("sampling")
```

```error
'async' marks a function declaration, and what follows it here is a statement. The statements of a body run where they stand — the entry file's are the program's 'main', which C calls directly and cannot drive a task through — so put the work in an 'async' function instead
```

**A property cannot be async**: it is read like a field, and a field read has no call to hand a task
back from.

```sysl
struct Adc
    channel: int

    async reading -> int = self.channel * 100
```

```error
a property cannot be 'async': it is read like a field, with no call to hand a task back from — make it a method, written with '()'
```

As a method it is an ordinary async member, called and awaited through the receiver:

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
declaration follows it, and `await` only where an operand does, so both stay available as names —
including in a program that also uses them as keywords:

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

**`block_on(t)` runs `t` on the calling thread until it ends, and is its result.** It is the one
driver the compiler supplies, and it is an ordinary call: it may stand in the entry file, in a plain
function, and behind an `@export`.

`block_on` is the only thing that could ever wake the task it drives — there is no other thread
running it — so **a task that parks and is never woken traps** rather than spinning for ever:

```sysl
async settle()
    await park((wake) -> ())

block_on(settle())
```

That program builds and stops with a trap. (A block on this page can only claim a program that runs
to the end or one the compiler refuses, so this one is shown without a claim.)

### The executor contract: `step`, `park` and `yield_now`

Four builtins are the whole interface between a task and whatever schedules it:

| builtin | what it does |
|---|---|
| `block_on(t)` | runs `t` on this thread until it ends; its value is the result |
| `step(t, wake: &Fn() -> unit) -> bool` | resumes `t` once; `true` once `t` has ended |
| `await park(register: &Fn(&Fn() -> unit) -> unit)` | hands `register` a **waker** and suspends until the waker is called |
| `await yield_now()` | wakes the task being stepped and suspends once |

**A waker is a `&Fn() -> unit`.** An executor hands `step` the one it wants called when the task can
run again; a task's `park` hands its registrar a waker of its own, which the registrar keeps wherever
the event it waits for will find it — a timer, an interrupt, a completion queue. Calling that waker
calls the executor's.

So an executor is a run queue and a loop over `step`. Here is a whole one, with a timer as the event
source:

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

**Stepping a finished task resumes nothing and answers `true` again**, so an executor that steps a
task one time too many has done nothing wrong.

### A waker fires at most once

**However many times a waker is called, the executor hears of it once** — and a waker whose park is
over is a no-op, so a stale one held by an event source cannot wake the task out of a *later* park:

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

A waker called *during* its own registration — an event that has already happened — still costs the
task one suspension and is heard once. A task dropped while it is parked disarms its waker on the
way out, so an event source that calls it afterwards wakes nothing.

### A park at any depth wakes the outermost task

An executor steps the task it holds, and that task may be awaiting a task that awaits another. **A
park three awaits down calls the waker of the step in progress**, which is the outermost task's — the
only one the executor knows about. A step nobody asked for, while the park is still unwoken, is
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

`step` takes the task and the waker, both:

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
program's own declaration of one is the nearer name and is called as written. **That includes an
import**: `sysl.posix.threads` declares a `yield_now` of its own — the thread's — so a file that
imports that module whole is handed the thread's, and awaiting it is awaiting a `bool`. The refusal
says whose `yield_now` the call became:

```sysl
import sysl.posix.threads.*

async blink()
    await yield_now()

block_on(blink())
```

```error
'await' needs a Task, which is what calling an 'async' function hands back, but this value is bool — the 'yield_now' called here resolved to 'sysl.posix.threads.yield_now', declared in 'sysl.posix.threads', and a declaration of that name stands in front of the task form 'yield_now(…)', so the task form is shadowed
```

A local of the same name shadows the task form the same way, and is named as a local binding.
Import what the file uses by name — `import sysl.posix.threads.spawn` — and the task's `yield_now` is
the one in scope.

## A task's frame is on the heap

**A task's frame — its parameters, its result, and whatever is alive across an `await` — is
allocated when the async function is called and freed when the last share of the task goes.** The
storage comes from one pair of functions:

```
sysl_task_alloc(size: usize) -> *u8
sysl_task_free(p: *u8)
```

The compiler defines both over the program's allocator, **weakly**, so a program that defines either
with [`@export`](/reference/ffi/) replaces the default, and every frame — and every waker a `park`
makes — comes from its own. Counting them shows each frame given back:

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

**On a microcontroller the pair is how frames come from a static pool** instead of a heap. The object
a board build produces holds no coroutine intrinsic — the compiler has lowered every one — and
reaches no `malloc`: its frames come from whatever the program's two definitions call.

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

**A task is a counted value**: a binding, a field or an `Option` holding one keeps a share, and the
frame goes when the last share does — once, whether the task finished, stopped partway, or never
started.

## Dropping a task cancels it

**There is no cancel operation: a task is cancelled by letting go of it.** A task dropped while it is
suspended gives back exactly what a `return` written at that suspension would — its pending `defer`s
run, then its locals and parameters are released, in the same order a `return` releases them — and a
task that awaits another gives back the awaited task first. A task dropped before it ever started
gives back its parameters and nothing else, since none of its body ran.

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

`transfer 1 finished` never prints: the task was let go of at its `await`, and everything after it is
code that does not run. Transfer 2 never reached its first statement, so it made no buffer to give
back.

## A task crossing a concurrency domain

**A task may be handed to another thread — or anywhere a [`@crossing`](/library/threads/) parameter
reaches — only if everything its frame can hold is safe to share.** The compiler asks, of each thing
the frame holds, the question it asks of what a closure captures when that closure is shared into a
`&sync Fn`:

- its **parameters**;
- its **result**;
- every **binding alive across an `await`** — a counted local lives to the end of its block, so one
  read only before the `await` is still held if its block outlasts it;
- every **value computed before an `await` in the same statement** — an argument to the left of an
  awaited one, the left operand of an operator whose right one awaits, a receiver whose call awaits
  in an argument, the elements of an array or tuple and the text of an interpolation built before an
  awaited part. A temporary used up in an earlier statement is gone before the `await`, and a scalar
  one crosses;
- every **task it awaits**, followed down the chain — and a task handed in as a parameter is
  answered by the argument its call was given.

A task of scalars crosses, and runs on the other thread while this one runs its own:

```sysl
import sysl.posix.threads.spawn

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

The same hand-over is refused once the frame holds a `string`, whose bytes are owned through a count
that is not atomic. The refusal names the function and the parameter:

```sysl
import sysl.posix.threads.spawn

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

Held as the message says, the task crosses — and so does one whose `&T` is let go of before its first
`await`, because then the frame never holds it:

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
statement finishes, so it is asked about too — here the label, built before the awaited reading:

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

Computed into a local of its own in an earlier statement and used up there, or kept as a scalar, it
is not in the frame at the `await`.

**The question is answered where the call that makes the task can be seen.** A task that arrives as
a plain parameter could have been made by any async function, so handing it on is refused unless that
parameter is itself marked `@crossing` — which moves the question to the callers. A task held in a
`&sync` structure is asked about where the structure is boxed, and one captured by a closure shared
into a `&sync Fn` is asked about at the closure. Everything refused here is accepted where the task
stays in its own domain.

### A task that parks stays in its domain

**A task that awaits `park` is never allowed to cross.** Its waker is a `&Fn() -> unit` belonging to
the domain whose executor steps it, so the task belongs to that domain too:

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

The same rule keeps a waker at home from the other side: a registrar may not capture the waker it is
handed into a closure shared into a `&sync Fn`, and `step` will not take a `&sync Fn() -> unit` as its
waker. No spelling makes a waker atomic, so the refusal says where it belongs instead:

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

**The interrupt that completes the request is the other domain a kernel meets**, and the shape it
writes first is a waker parked in module storage and called from the handler. Marked
[`@domain(interrupt)`](/reference/attributes/#domain-interrupt-a-function-entered-on-top-of-whatever-was-running),
the handler is held to what it reaches, and the waker is what it reaches:

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

What the handler may do is what an `Atomic` word or a `&sync` object allows: set a flag the executor
reads, or push the request's number onto a ring the executor drains — and the executor, back in its
own domain, calls the waker.

`yield_now` makes no waker, so a task that only yields crosses — `checksum` above is one.

## Where the executors live

**Not in the language and not in the standard library.** The compiler supplies `block_on` and the
contract above; an executor is a package, written against the contract in ordinary sysl, exactly as
the run queue on this page is.

- **[`kairos`](https://github.com/sysl-lang/kairos)** is an event loop written for a microcontroller
  first — numbered sources an interrupt raises, timers on one clock. Its async face, wakers kept
  where its sources fire them, is planned.
- An executor over **[`libuv`](https://github.com/sysl-lang/libuv)**, for a host, is planned.

---

Next: [the foreign interface](/reference/ffi/).
