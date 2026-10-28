---
title: Memory
summary: The three modes, ARC, weak references, places and `ref`, escape analysis, the raw tier, and device memory.
weight: 70
---

Every value in a sysl program lives in one of three ways, and the declaration that names it is what
says which. This page is the whole of that model: what each mode costs, what it guarantees, how a
reference is made, when storage moves without being asked, and where the guarantees stop.

**If you do not write `*T`, you cannot segfault.** The safe subset — values, references, weak
references, arrays, and slices — has no use-after-free, no null dereference, no out-of-bounds access,
and no dangling pointer. The single unsafe primitive is the raw pointer, and it is **greppable**: a
program's exposure to C's hazards is exactly the lines with a `*` in the type. A driver has them and
an application does not, and you can tell which by looking.

## The three modes

| mode | what it is | who frees it | needs an allocator |
|---|---|---|---|
| `T` | a value — on the stack, in a register, or inline in something bigger | nobody; it goes when its frame or its container does | no |
| `&T` | a counted reference to a heap object | the compiler, when the last reference goes | to **make** one |
| `*T` | a raw machine pointer | you | no |

```sysl
struct Point
    x: int
    y: int

var here = Point(1, 2)
var shared: &Point = Point(3, 4)
var raw: *Point = &here

print(here.x, shared.x, raw.x)
```

```output
1 3 1
```

**The choice is per declaration, not per type.** The same `struct Point` is a value in the first
line, a heap object in the second, and a pointer's target in the third. This is C's arrangement
rather than Swift's or Scala's, where a type is `struct` or `class` once and for all, and systems
code depends on it: a page-table entry, a temporary in a loop, and a shared configuration object may
all be the same type held three ways.

**Value is the unmarked default, and the kernel is why.** A module that has given up the allocator
cannot *make* a reference. If the reference were the bare default, the unmarked spelling would be
the one that is illegal exactly where value semantics matter most — every kernel struct would need a
mark, and would hit "references need an allocator" on its most natural form. With value as the
default, `T` is usable in both worlds, `*T` covers pointers, and `&T` appears in allocator-free code
only where something else handed one over.

## `T` — values

A value lives inline: in a stack slot, in a register, or embedded in the struct or array that
contains it. **Assignment and argument passing copy it**, exactly as in C.

```sysl
struct Counter
    n: int

var a = Counter(1)
var b = a

b.n = 99

print(a.n, b.n)
```

```output
1 99
```

There is **no move semantics and no use-after-move**. `a` is still a whole `Counter` after `b = a`,
and it always will be — the concept of a value that has been given away does not exist here, so
neither does the class of error built on it. Where a copy is a local's last use, the compiler hands
the value over instead of copying it ([below](#a-count-that-would-be-taken-and-given-straight-back-is-not-taken)) —
which changes how many counts are taken, and nothing a program can observe.

A value containing a `&T` field is still a value: copying it **retains** that field, so the copy is
independently safe and the original is untouched. That is the one thing a copy does beyond moving
bytes, and it is what makes "values are simple" true rather than approximately true.

Values need no allocator and are always safe.

## `&T` — counted references

A `&T` is a reference to a heap object managed by **ARC**: the compiler emits the retains and
releases, and the object is destroyed the moment the last strong reference to it goes away. It is:

- **shared and freely aliased** — many references may point at one object;
- **mutable through any alias** — there is no borrow checker and no exclusivity rule;
- **non-null** — a `&T` always points at a live object;
- **automatically managed** — no `free`, and no place to forget one.

```sysl
struct Counter
    n: int

var a: &Counter = Counter(1)
var b = a

b.n = 99

print(a.n, b.n, a == b)
```

```output
99 99 true
```

Compare that with the value example above: **the same two lines, and the declared mode is the only
difference.** `b = a` copied a `Counter` there and made a second reference here.

Two live mutable aliases is what Rust exists to prevent, and permitting them is the deliberate
trade. Reference counting pays for it at run time — a retain here, a release there — and buys a
language with no lifetimes, no borrow checker, and nothing to prove to a checker as you write.

### References compare through their referent

**A reference respects its referent's `Eq` and `Hash`.** `==` and `!=` on two `&T`s (or two
`&sync T`s) compare the objects as `T` does wherever `T` has an `Eq` — written, derived, or a
built-in's — and a `&T` hashes as `T` does wherever `T` has a `Hash`. That is what a reader expects
of a `&Point` whose `Point` says what equal points are, and it is the rule of Rust's `&T`, and of
every language whose objects are references until their type defines equality:

```sysl
struct Point derives Eq, Hash
    x: int
    y: int

main()
    val a: &Point = Point(1, 2)
    val b: &Point = Point(1, 2)

    print(a == b, a != b, a === b, a === a)
```

```output
true false false true
```

**`===` and `!==` are identity**, always: whether two references or two pointers name the same
object, whatever the referent's `==` says. They take two references or two pointers of one type, and
nothing else — an integer has no identity apart from its value, and a trait object is two words
rather than one address:

```sysl
main()
    print(1 === 1)
```

```error
'===' compares the identity of two references or pointers, and int is neither — '==' compares its value
```

**Where `T` has no `Eq`, a reference is its own identity**: `==` asks whether the two name the
same object, and a `&T` hashes by the address it holds — so the two still agree. A table keyed on such
references keys by **identity**. Two distinct objects holding equal values are two keys; the same
object twice is one. That is what a hash-consing table wants, where a node is made once per distinct
pair of children:

```sysl
import sysl.container.{Map, map}

struct Node
    level: int

main()
    val leaf: &Node = Node(0)
    val twin: &Node = Node(0)
    val made = map[(&Node, &Node), &Node]()

    for k in [(leaf, leaf), (leaf, twin), (leaf, leaf)]
        if !made.has(k) then made.put(k, Node(k.0.level + k.1.level + 1))

    print(made.len(), made.get((leaf, leaf)).unwrap() == made.get((leaf, leaf)).unwrap())
```

```output
2 true
```

A **bound** gets the same answer: inside a `[K: Hash + Eq]` body standing at `&Node`, `k.hash()`
and `k.eq(other)` are `Node`'s own where `Node` has them and the address's where it does not, as
`k == other` is — so a `Map[&Node, V]` keys by value or by identity exactly as `==` on two `&Node`s
compares. A member call written on a reference you named reaches the referent's own (`r.hash()`,
`r.eq(*s)`), the one level a member call sees through. A struct deriving `Eq` or `Hash` over a
`&T` field, and a tuple of references, compare and hash each reference the same way.

**A method the referent declares itself wins over the rule.** Where `Node` has an inherent `hash`
or `eq` and no `Hash` or `Eq` implementation, `r.hash()` written on a `&Node` (or a `&sync Node`, or
a `*Node`) calls that method, as any member call on a reference reaches its referent's. But an
inherent method of that name implements nothing, so `==`, a `Map` key and a `[K: Hash + Eq]` body
still see a `Node` with no `Eq` or `Hash`, and compare and hash the reference by its identity. Only
an `impl Eq` or `impl Hash` (written or derived) changes what those answer.

**A referent with an `Eq` and no `Hash` makes a reference that is no key**: its `==` is the
referent's, and a hash of the address would give two equal references two hashes. It is refused
rather than keyed wrongly:

```sysl
import sysl.container.{Map, map}

struct Tag derives Eq
    id: int

main()
    val tags = map[&Tag, int]()

    print(tags.len())
```

```error
Tag defines 'Eq' but not 'Hash', so a reference to it compares by its value and has no hash that agrees
```

**To key by identity when the referent defines equality**, wrap the reference in `ByAddress`, whose
`Eq` is `===` and whose `Hash` is the address's:

```sysl
import sysl.container.{Set, set}

struct Point derives Eq, Hash
    x: int
    y: int

main()
    val a: &Point = Point(1, 2)
    val b: &Point = Point(1, 2)
    val seen = set[ByAddress[Point]]()

    seen.add(ByAddress(a))
    print(seen.has(ByAddress(a)), seen.has(ByAddress(b)), a == b)
```

```output
true false true
```

**A reference to a reference compares as the inner reference does**: two `& &Point`s are equal when
the `&Point`s they reach are, which is the points' `==` — and the identity of the inner references
where `Point` has no `Eq`. **A trait object** (`&Shape`) has no `==`: it has forgotten which type it
holds, and with it what equal would mean.

**A pointer compares by address.** `==` and `!=` on two `*T`s ask whether they hold one address, and
a `*T` hashes by it, whatever `T` defines: a pointer is the unchecked tier's address, which is what
`p == null` and a `p != end` walk over an array ask about, and a comparison through it would read
storage a null or a one-past-the-end pointer does not have. There is no ordering on a reference or
a pointer.

**Its other bounds a reference meets through its referent**, wherever a member call on the
reference would reach the referent's members: a `[C: Index[usize, int]]` body takes a `&Buf[int]`
and reads the buffer ([generics](/reference/generics/#a-reference-meets-its-referent-s-bounds)).

### There is no allocation keyword

A reference is made by writing an ordinary construction **where a `&T` is expected**. The expectation
is what puts the object on the heap:

```sysl
struct Process
    id: int
    priority: int

spawn(id: int) -> &Process = Process(id, 0)

var p = spawn(7)
var q: &Process = Process(8, 1)
var v = Process(9, 2)

print(p.id, q.id, v.id)
```

```output
7 8 9
```

`Process(id, 0)` in the body allocates because the return type says `&Process`. `Process(8, 1)`
allocates because the annotation says so. `Process(9, 2)` does not, because nothing asked — with no
expectation at all, a construction is a value.

No `new`, no `malloc`, no `Rc::new`, no `.clone()`, and no wrapper type. The positions that fix the
expectation are the ones that already state a type for generic inference:

| position | example |
|---|---|
| a declared local | `var p: &Point = Point(1, 2)` |
| a parameter | `f(p: &Point)`, called as `f(Point(1, 2))` |
| a return type | `-> &Point`, with a construction as the result |
| a struct field | `Item("a", Node(1))` where the field is `&Node` |
| an enum variant's payload | `Some(Node(1))` where the payload is `&Node` |

An element of an array or slice, a part of a tuple, a generic argument, and a parameter of a callable
type all ask the same way. **The rule is about the types and not about the syntax**: a `T` written
where a `&T` is expected goes on the heap, whatever produced it.

A prefix `&` on a construction was considered as an explicit mark and not taken — it would collide
with address-of, which is a different operation producing a different type.

### The expectation reaches each branch

An `if`, a `match`, and a loop yield their value through their branches, so a `&T` expectation
reaches **each branch on its own** rather than the expression as a whole:

```sysl
struct Point
    x: int
    y: int

var origin: &Point = Point(0, 0)
var far = true
var p: &Point = if far then Point(9, 9) else origin

print(p.x, p.y)
```

```output
9 9
```

That is what lets a value branch and an already-reference branch meet at `&Point`. Boxing the whole
expression instead would fail, because a branch that is already a `&Point` cannot un-become a value —
something that is already a reference passes through untouched.

It is also what lets a scalar be referenced without `int` needing a constructor of its own:

```sysl
var n: &int = 41

print(*n + 1)
```

```output
42
```

### A reference is never null

There is no null in the safe subset and no way to make one, so there is no null dereference to
diagnose. Something that may be absent is an `Option[&T]`, and getting at it is a `match`:

```sysl
struct Item
    label: string

show(o: Option[&Item]) -> string
    o match
        Some(i) -> i.label
        None    -> "(none)"

var it: &Item = Item("a")

print(show(Some(it)), show(None))
```

```output
a (none)
```

The `match` is not ceremony around a null check. It is the only route to the item, so the absent case
cannot be forgotten rather than merely being unwise to forget.

The one place a `&T` reads as null is storage the language did not lay out and the program zeroed —
[below](#storage-the-compiler-did-not-lay-out-may-not-hold-a-counted-value), with `is_null`, the raw
tier's question for it, and `release`, the way such a field is emptied again.

### A box whose allocation fails traps

The heap can refuse. A box is made from whatever the program's allocator answers, and the answer is
checked before anything is stored through it: a null traps, exactly as a failed array allocation does
([arrays](arrays.md)). So a `&T` is never built over a null, which is what lets the section above say
a reference is never null; the allocator-failure case is a trap, not a value the program must handle.

### What a heap object costs

Every ARC object carries **three header words**: the strong count, a pointer to the function that
destroys it, and a weak count. Release decrements the strong count; at zero it calls through the
hook, which releases whatever the payload holds and returns the storage to the heap the object came
from — [one heap for the whole program](/reference/packages/#one-heap-and-the-package-that-names-it),
libc's unless a package named another pair.

Putting the destructor behind a hook rather than inline at each release site is what makes letting go
of a reference **type-erased** — one instruction sequence, no static type. Slices need exactly that,
since a `[]T` gives no clue what type of object its owner word points at.

One word per object buys three things:

- **ARC works the same everywhere.** A module that never allocates can still retain and release,
  because the free path calls back into the heap the object came from. The operations are a few
  instructions and depend on no runtime.
- **Several heaps coexist.** A kernel heap, a server's heap, and an arena are different allocators,
  and an object frees itself into the one that made it, wherever it is dropped.
- **There is no boundary rule to learn.** Ownership crosses an allocator-free edge like any other
  value.

**Teardown is iterative, so depth is bounded.** Destroying the head of a long chain of references
would recurse one frame per node if a destructor called the next destructor. It does not: an object
whose count reaches zero is pushed onto a worklist, reusing its now-dead refcount slot as the link,
and the first release to hit zero drains the list in a loop. A structure of any depth comes apart in
O(1) stack. The worklist is **per thread**, because it is scratch space a drain uses rather than
state anything shares.

**On a freestanding target the worklist is asked for rather than assumed**, since there is no thread
pointer to key it on. A `&sync` release that reaches zero fetches it through one weak symbol,
`__sysl_arc_reaper`, which answers with a `head` pointer and a `draining` flag belonging to the
running task:

```c
struct sysl_arc_reaper { void *head; unsigned char draining; };

/* FreeRTOS, say: whatever this runtime keeps per task. The storage is the port's to give out and
   must last as long as the task — set when the task is created, never shared with another:

       static struct sysl_arc_reaper slots[MAX_TASKS];
       vTaskSetThreadLocalStoragePointer(task, 0, &slots[n]);            */
struct sysl_arc_reaper *__sysl_arc_reaper(void) {
    return pvTaskGetThreadLocalStoragePointer(NULL, 0);
}
```

Define nothing and the program uses the single slot it carries, which is right for a machine where
nothing schedules — the overwhelming case, and why the symbol is weak. Define it in an RTOS port and
`&sync T` means on that target what it means everywhere else. This is the arrangement `&sync`'s
counts are already under there: with no `ldrex`/`strex` beneath it an atomic increment becomes a call
to an `__atomic_*` the board defines, and on a two-core part that needs a hardware spinlock.

**A scheduler written in sysl defines the hook with [`@export`](/reference/ffi/)**, as
`__sysl_arc_reaper() -> *T` where `T` is a struct of the same two fields. The program's definition is
then the only one: the compiler leaves its weak default out, and every drain calls the program's.

```sysl build=c target=aarch64-freestanding
struct Reaper
    head: *u8
    draining: u8

// Whatever the scheduler keeps per task.
extern "current_task_reaper" current_task_reaper() -> *Reaper

@export("__sysl_arc_reaper")
arc_reaper() -> *Reaper = current_task_reaper()

struct Job
    n: int

@export("run_job")
run_job(n: int) -> int
    val job: &sync Job = Job(n)
    job.n
```

The header is the same three words for every object whether or not anything weakly references it,
which is what keeps the type-erased release path expressible at all. The cost is eight bytes on an
allocation that already cost a `malloc`, and it lands only where the feature is used: values, fixed
arrays, and `*T` buffers have no header, so allocator-free code pays nothing for a mechanism it never
touches.

### Passing a reference to a call usually costs nothing

The obvious way to count a by-value argument is for the function to take a share of it on the way in
and give it back on the way out, so that a parameter is a thing the callee owns. sysl does not do
that, because the caller is still standing there: **an argument is handed over under a guarantee that
it stays alive for the length of the call**, and the callee reads it through the count the caller is
already holding. That is Swift's `guaranteed` convention and Rust's `&self` borrow, arrived at the
same way — it is the caller, not the callee, that knows where the value came from.

What the caller has to look at is one question with three answers:

- a **temporary** — a call's result, a newly built object, a value out of an `if` — is a count this
  frame already took and does not give back until the statement ends, which is after the call
  returns. Nothing to do.
- a **local or a parameter** whose address the caller never let out keeps its count in a slot nothing
  the callee can reach has a name for. Nothing to do.
- **anything else** — a field, an element, a global, something behind a `ref` or a `&` the caller
  handed out — keeps its count in memory the call itself may write. That one costs a retain before
  the call and a release after it, and it is the only shape that does.

**A call to an [async function](/reference/async/) is the one place the first answer changes.** Its
task keeps a count of every parameter of its own, since its body runs after the call has returned, so
a temporary handed to it **moves into the task**: the caller gives its count back as soon as the call
returns instead of holding it to the end of the statement. What the task was handed goes when the
task lets go of it — at its `return` — and not when the statement that made the call has run to its
end. A local or a parameter handed to an async call is still the caller's, and keeps its count where
it always does; a plain call keeps its temporaries to the end of the statement, as above.

```sysl
struct Noisy
    id: int

impl Drop for Noisy
    drop(self)
        print(s"dropped ${self.id}")

async keep(n: &Noisy) -> int = n.id

plain(n: &Noisy) -> int = n.id

async run()
    print(s"async ${await keep(Noisy(1))}")
    print(s"plain ${plain(Noisy(2))}")

    val named: &Noisy = Noisy(3)

    print(s"named ${await keep(named)}")
    print("end of run")

block_on(run())
```

```output
dropped 1
async 1
plain 2
dropped 2
named 3
end of run
dropped 3
```

The third case is not a technicality. `remember` below is handed a field of module storage and then
assigns to that very field, so the count the argument was resting on is given back **while the callee
is still running** — and the read on the next line would be a read of freed storage if nobody else
were holding one:

```sysl
struct Node
    v: int

struct Holder
    r: &Node

var latest: Holder = Holder(Node(0))

remember(h: Holder) -> int
    latest = h
    h.r.v

var first: &Node = Node(1)
var box = Holder(first)

print(remember(box), latest.r.v)
```

```output
1 1
```

`box` is a local nobody took the address of, so this particular call is free at both ends; written
`remember(latest)` it would cost the pair, for the reason above.

**Nothing about this is something to write down or reason about while programming.** It is not a
mode, it is not spelled in a signature, and two implementations of one trait method may disagree
about it without anything noticing — the obligation is the caller's and is the same whether the
callee is a name, a vtable slot or a function pointer. What it is worth knowing is the shape it
rewards: a hot loop that builds values and pushes them into a container passes temporaries and
locals, and pays no reference traffic at all for the handover.

Two things a callee still takes a count for, and both are the same reason — it is about to give one
back:

- **a parameter it assigns to.** `h = other` releases whatever was in the slot, and under a borrow
  that would be the caller's count, so a body that rebinds a parameter owns it instead.
- **a parameter of a function something outside the program may call** — `@export`, a `@section`, or
  one whose address is taken for a `*fn`. There is no sysl caller on the other side of those to make
  the guarantee, so the function makes it for itself.

Storing a parameter is the third, and it goes the other way round: the callee does not take a count
for it, it is **handed** one.

### A parameter the function keeps arrives with its count

A function that keeps a counted parameter — stores it in a field, a global, a box, a closure or a
container's element, returns it, or hands it on to another function that keeps it — would otherwise
take a count at the store for a value whose caller is, as often as not, about to give its own back.
So **such a parameter is owned**: the compiler reads each function's body (and the answers of the
functions it calls, to a fixed point over recursion) and decides which parameters it keeps, and
those arrive with a count the callee is responsible for. The caller supplies it the cheap way:

- an argument at its **last use** — a temporary, or a local the statement names for the last time —
  hands over the count the frame already holds, so the call costs nothing at either end;
- any other argument is retained before the call, which is the count the store would have taken.

`b.push(x)` and `Label(x)` returned through a helper are therefore free at `x`'s last use, and a loop
that builds a value and pushes it pays no reference traffic at all. A parameter only a part of which
is kept, and every parameter of a function that does not keep it, stays borrowed as above.

Nothing about it is spelled in a signature either. A call through a closure, a function value or a
trait object, and a call from C, reaches a borrowing entry that takes the count itself and then runs
the owned body, so the convention is a matter between a function and its direct callers. A function
that is `@export`ed, `async`, `@scoped` or carries a contract keeps the borrowed convention
throughout. A library and the program built against it read the same bodies, so they agree on which
parameters are owned.

**A value whose release can run a destructor is destroyed where its scope ends**, exactly as before
owned parameters existed. A local of a `Drop` type, or of anything holding one in a field, an element
or a payload, is not handed to an owned parameter at its last use: the call might keep it only in its
result, which goes at the end of the statement, while a view of what the value owns — a slice made
through `ptr_cast` of storage a C library frees in the destructor — is still being read. Such a local
is retained for the call instead, given back at its scope's end, and never handed into the arms of
an `if`. Only a value nothing observable is released with — no destructor anywhere inside it — goes
at its last use, which no program can tell apart:

```sysl
struct Tag
    id: int

impl Drop for Tag
    drop(self) = print("drop", self.id)

struct Label
    tag: &Tag

label(t: &Tag) -> Label = Label(t)

show()
    val t: &Tag = Tag(1)

    print("label", label(t).tag.id)
    print("end of show")

show()
```

```output
label 1
end of show
drop 1
```

`t` is at its last use in `label(t)`, and `label` keeps it in the label it returns, which goes at the
end of the statement. The tag still goes at the end of `show`, because `t` holds its own count to
there. A value moved somewhere that lasts — bound to a `val`, stored in a field or a global, returned
— is destroyed by its new owner.

### A count that would be taken and given straight back is not taken

Binding a call's result to a `val`, or returning a local through an `if` or a `match`, moves a value
from one owner in the frame to another: the new owner takes a share and the old one gives its share
back at the end of the statement or scope. On a `&sync T` each of those is an atomic operation on a
count every thread shares, so a value copied up a recursive search would pay two contended atomics
per level for a count that ends where it began. **The compiler drops such a pair.** A retain is
cancelled against a later release of the same reference when nothing between them can give back the
count that release would have given back:

- loads and stores of the frame's own slots, arithmetic, and other retains never can;
- the release of a different value may run a destructor, which is arbitrary code, but it cannot reach
  a slot of this frame whose address never left it. So it may sit between the pair, unless the stretch
  between them also writes to memory outside those slots;
- any other call ends the stretch.

Where the pair would be split by the arms of an `if` or a `match`, the releases at the end of the scope
are given back at the end of each arm instead, so that the arm which returns a local takes no count
for it. A count therefore never reaches zero at a different point, and **no destructor runs earlier,
later or in a different order** than it would with every count taken. Nothing about it is visible
except the time it saves: on a recursive search over a tree of `&sync` shapes it removes three of every
four atomic operations.

**A value's last use hands it over.** Where what is stored, bound, returned or made a branch's value
is a count the frame was about to give back — a fresh box, a call's result, or a local the statement
names for the last time in its scope — the destination takes that count instead of a new one, and
the give-back is never emitted. A struct or enum built on the spot is handed over field by field, so
`Some(Node(n))` and `Pair(x, 1)` at `x`'s last use cost no count at all. A local still read later,
named in a `defer`, used inside a loop it was declared outside of, or reached through an address or a
`ref` is copied as before. One whose life ends in an `if` with an `else` is handed into each arm, so
an arm that uses it last moves it and the others give it back where they end — unless its release
can run a destructor, which keeps it to its scope's end (see
[owned parameters](#a-parameter-the-function-keeps-arrives-with-its-count)).

```sysl
struct Tag
    id: int

impl Drop for Tag
    drop(self) = print("drop", self.id)

struct Hit
    t: int
    tag: &sync Tag

probe(t: int, tag: &sync Tag) -> Option[Hit] = Some(Hit(t, tag))

pick(near: bool) -> Option[Hit]
    val x = probe(1, Tag(1))
    val y = probe(2, Tag(2))
    if near then x else y

show(near: bool)
    val h = pick(near)
    print("got", h.unwrap().t)

show(true)
show(false)
```

```output
drop 2
got 1
drop 1
drop 1
got 2
drop 2
```

### A view of something the frame already holds takes no count

A slice of a slice retains the owner, and a `val` copied out of a field retains what it refers to.
Where the value it was read from is a **parameter or a `val`** — written once, in a slot nothing
outside the function can reach — that value's own count already keeps the object alive, and **a
share taken from it and given back before it is given back its own is dropped**, however far apart
the two are: across a `for` that walks the view, or the rest of a loop's round. A view taken every
time round a loop then costs nothing:

- the parent must stay put: a `var` assigned in the loop, or one whose address is taken, keeps every
  count;
- a view that leaves the function, or is stored anywhere but a local, keeps its count;
- **a count can be looked at**, so nothing between the share and its return may look: storage is let
  go of by `slot_vacate` only where the strong count is one, and a view whose share had been dropped
  would leave it free to give back slots the view still reads. So the stretch may hold arithmetic,
  reads of locals and of fields and elements, and calls to functions that release nothing and write
  nothing but their own locals; any other call, and any other release (a destructor is code too),
  keeps the count.

The parent's count holds the object from before the share is taken until after it would have been
given back, so the object's count never reaches zero inside, nothing inside reads it, and **no
destructor moves**. Below, `sums` takes no count at all; `label`'s `print` is a call that could, so
`t` keeps its share there.

```sysl
import sysl.buf.{Buf, buf}

struct Tag
    id: int

impl Drop for Tag
    drop(self) = print("drop", self.id)

struct Shelf
    tag: &Tag

sums(src: []const u8, most: usize) -> u32
    var t: u32 = 0
    var i: usize = 0

    while i + most <= src.len
        val here = src[i..<i + most]

        for x in here
            t += u32(x)

        i += most

    t

label(s: Shelf, rounds: int)
    for k in 0..<rounds
        val t = s.tag
        print("round", k, t.id)

var b: Buf[u8] = buf()

for k in 0..<10
    b.push(u8(k))

print(sums(b.view(), 5))
label(Shelf(Tag(7)), 2)
print("done")
```

```output
45
round 0 7
round 1 7
drop 7
done
```

## `weak T` — breaking cycles

Reference counting has one honest weakness: a cycle of strong references keeps itself alive. A parent
holding its children while each child holds its parent is the shape that does it, and it is common
enough in systems code — a back-link in an intrusive list, a process's pointer to its parent — to
have a purpose-built answer.

A **`weak T` does not keep its referent alive.** When the last strong reference goes, the object is
destroyed and every weak reference to it becomes empty.

```sysl
struct Node
    label: string
    parent: weak Node

parent_of(n: &Node) -> string
    n.parent.get() match
        Some(p) -> p.label
        None    -> "(none)"

var root: &Node = Node("root", None)
var kid: &Node = Node("kid", root)

print(parent_of(root), parent_of(kid))
```

```output
(none) root
```

**`weak T` is a type, not an operator.** There is nothing that makes one; a `&T` becomes a `weak T`
wherever a `weak T` is what the context asked for — the struct field above, an argument, a declared
local, a returned value. That is the same rule that makes a `&T` out of a `T`, so both `&` and `weak`
live at the boundaries a program annotates and neither appears in a body.

The conversion goes one way. A `weak T` is not a `&T` and never silently becomes one, because
becoming one is the operation that can fail.

**The `&` is in the mode already, so `weak &T` is the word said twice** — and it is refused rather
than read as a weak edge to a box holding a reference, which is not a thing sysl has:

```sysl
struct Node
    v: int

var r: &Node = Node(1)
var w: weak &Node = r
```

```error
'weak Node' is already a weak edge to a counted Node, so the '&' says the mode a second time — write 'weak Node'
```

**Reading one is a question, and it is `get()`.** The answer is `Option[&T]`: a live strong reference
with a count taken for the caller, or `None`. Nothing else may be done to a weak reference — no field
selection, no method call, no `==`:

```sysl
struct Node
    label: string
    parent: weak Node

var root: &Node = Node("root", None)
var kid: &Node = Node("kid", root)

print(kid.parent.label)
```

```error
may be gone, so nothing is read off one directly
```

Every road to the object goes through the `Option`, which is what makes "a weak reference never
dangles" a fact about the language rather than a promise about the programmer.

The parentheses are not decoration. `.len` is a **property** — a fact about the value, the same answer
every time it is asked — while `get()` is a **question about the world**: two calls a moment apart may
disagree, and the answer costs a count. sysl puts the parentheses on the second kind.

**An empty weak reference is written `None`**, and it is the same state whether the object is gone or
there never was one. This `None` is not an `Option` — it is the empty value of the weak reference
itself, chosen because it is exactly what `get()` will hand back for it.

It is also the **zero value** of `weak T`, which is the one place `weak T` parts company with `&T`:
there is no such thing as a reference to nothing, so `&T` has no zero. A struct with a weak field
therefore still has one, and an uninitialized declaration of it is still a declaration:

```sysl
struct Node
    label: string
    parent: weak Node

var n: Node

n.parent.get() match
    Some(p) -> print("held")
    None    -> print("empty")
```

```output
empty
```

**A weak reference may not be made from a value with nowhere else to live.** Constructing into a weak
position would box the value and then weaken it, leaving the weak edge as the object's only holder —
and the object dead before the statement ended:

```sysl
struct Node
    label: string
    parent: weak Node

var kid: &Node = Node("kid", Node("root", None))

print(kid.label)
```

```error
a weak reference does not keep Node alive, and nothing else here holds this one
```

**The only default a `weak T` parameter can have is `None`**, and that falls out rather than being
decided. A default is produced afresh at each call that omits it, in a scope holding none of the
caller's locals, so what it names would have to outlive every frame — and every candidate that names
an *object* is closed off. A construction is refused by the rule above; a top-level `var` is a local
of the entry point; and a module-level `val` counts nothing, which a reference does. What is left is
the one value that holds nothing:

```sysl
struct Node
    label: string
    parent: weak Node

adopt(child: string, parent: weak Node = None) -> string
    parent.get() match
        Some(p) -> child + " of " + p.label
        None    -> child + " of nobody"

var root: &Node = Node("root", None)

print(adopt("a"), adopt("b", root))
```

```output
a of nobody b of root
```

**`weak sync T` is refused**, naming the concurrency chapter. Upgrading an atomic weak reference is a
compare-and-swap loop against a count another thread may be driving to zero underneath it, and that
is written when there is something to race with.

Everything a `&T` may point at, a `weak T` may be taken of: a struct, a scalar, an array, a generic
instantiation, a trait object, and a type parameter.

## `*T` — the raw pointer

`*T` is a bare machine pointer, exactly like C's: no length, no count, no checks, manual lifetime. It
is the only unsafe primitive over data, it needs no runtime, and it is how a kernel, a driver, or an
allocator's own internals are written.

```sysl
bump(n: *int)
    *n += 1

var counter = 41

bump(&counter)

print(counter)
```

```output
42
```

**Anything C can do through a pointer, sysl can.** The pointer is where the language's guarantees
stop, so it carries C's whole surface rather than a safer subset of it — a subset would only mean a
kernel that could not be written here.

### `null` exists, and only here

`null` is the absent raw pointer. It has no type of its own and takes the `*T` its context expects,
the way a bare `None` takes its type argument:

```sysl
struct Node
    value: int
    next: *Node

var third = Node(3, null)
var second = Node(2, &third)
var first = Node(1, &second)

var walk = &first
var total = 0

while walk != null
    total += walk.value
    walk = walk.next

print(total)
```

```output
6
```

That program is also the answer to "how does a type reach itself" — see [recursive
types](#recursive-types) below.

**A generic parameter is a context like any other, and it answers late.** A callee still being
solved has no type at that position yet, so an argument with none of its own is set aside until the
ones that have have been read, and is then taken against the parameter it turned out to stand at:

```sysl
two[T](a: *T, b: *T) -> bool = a == b

var x: int = 3

print(two(&x, null), two(null, &x))
```

```output
false false
```

Either order answers alike, because waiting is not queueing. Setting an argument aside cannot lose
the solution — one with no type of its own has nothing to contribute to it — so the parameter is
settled by the other arguments or by nothing at all. **Nothing at all is refused rather than
defaulted**: a call with no other argument to read is asked what the pointer points at.

```sysl
one[T](a: *T) -> bool = true

print(one(null))
```

```error
'null' takes its type from its context, and there is none here
```

### Arithmetic: two directions, one spelling each

```sysl
var buf: [8]int = [10, 20, 30, 40, 50, 60, 70, 80]
var base = &buf[0]
var third = &buf[2]

print(*third, third - base, base - third, *(&base[3]))
```

```output
30 2 -2 40
```

**`p - q` between two pointers of the same pointee is an `isize`, and it counts elements**, not bytes
— C's `ptrdiff_t`. It is signed because the order of the operands is the programmer's, and it is the
exact inverse of `&p[n]`: indexing takes an address and a count to an address, and the difference
takes two addresses back to a count, both striding by the pointee.

It is here because the interior-pointer half of libc needs it. `memchr`, `strchr`, `strrchr`,
`strstr` and `memmem` all hand back a pointer *into* a buffer the caller owns, and without a
difference every one of them is callable and useless, because nothing could turn the answer into an
index.

**Offsetting stays `&p[n]`.** `p + n` is not spelled, deliberately: indexing already exists and
already strides by the pointee, and a second spelling for one address would be a second thing to keep
in step. `p + q` names no address and is refused too.

**Two pointers of different pointee types have no shared element to count**, and are refused by the
ordinary matching-types rule rather than by one of this operator's own:

```sysl
var n = 1
var c = 'x'
var pn = &n
var pc = &c

print(pn - pc)
```

```error
'-' needs matching types, got *int and *char
```

**A counted `&T` has no arithmetic at all**, keeping the equality it always had and nothing more:

```sysl
struct Point
    x: int

var a: &Point = Point(1)
var b: &Point = Point(2)

print(a - b)
```

```error
operator '-' is not defined for &Point
```

Arithmetic is a property of the unsafe mode, not of holding an address.

Whether two pointers into unrelated objects may be subtracted is the programmer's business, as `p[i]`
past the end already is.

### Bounds safety follows length, not pointers

The out-of-bounds hazard is governed by whether a value **carries its length**, independently of
where it was allocated:

- **arrays and slices carry their length**, so indexing is bounds-checked in every context — hosted
  or allocator-free, over static, stack, or heap memory;
- **`*T` carries no length**, so it is the one unchecked primitive. `p[i]` reads the `i`th element
  and `p[0..<n]` views `n` of them, both exactly as C does and both unchecked, because there is no
  length to check against and supplying one is the programmer's assertion. A pointer to something
  with a subscript of its own — a `*[N]T`, or a `*Buf[T]` and any other type indexed through a trait
  — is indexed as that thing, with its check
  ([Arrays](/reference/arrays/#a-raw-pointer-is-indexed-anyway)).

So even low-level, allocator-free code stays bounds-safe by *choosing* slices, and reaches for `*T`
where it must — an MMIO window, a page table, a buffer a C function filled. That choice is the point.

## Places

A **place** is something with an address: a local or parameter, a dereference, an **element**, and a
field of any of them. Everything else — a call result, an arithmetic result, a freshly built struct —
is a value with no address of its own, and `&` in front of one *makes* it a place rather than
refusing it; [Addressing a value](#addressing-a-value) below is that rule.

| operation | what it does |
|---|---|
| `&place` | yields a **`*T`** — C's address-of, with C's result |
| `&value` | writes the value into a hidden local of this scope, and yields a **`*T`** to that |
| `*p` | reads through a `*T` or a `&T`, and is itself a place |
| `p.f` | selects, dereferencing **one** level automatically on both `*T` and `&T` |

**`&` on a place yields a raw pointer, never a reference.** A place lives in a frame or inside
another object, so there is no count to take a share of. Reaching a `&T` means being handed one or
constructing one, and taking the address of a local is therefore inherently in the unsafe tier —
which is right, because it can dangle and nothing promotes it.

**Selection is the only implicit dereference, with the subscript of an indexable pointee.** `p.x` is
`(*p).x` and `p.x = 9` writes through the pointer — Go's rule, with no `->` — and `p[i]` on a pointer
to an array or a container is `(*p)[i]`. Matching a reference to an enum against its variants is `match *e`.

**The shorthand stops at one level**, so reaching through a `**T` is written:

```sysl
struct Point
    x: int
    y: int

var p = Point(1, 2)
var pp = &p
var ppp = &pp

print(pp.x, (*ppp).x)
```

```output
1 1
```

Leaving the step out is a diagnostic rather than a second implicit dereference, and it says how many
levels are left:

```sysl
struct Point
    x: int
    y: int

var p = Point(1, 2)
var pp = &p
var ppp = &pp

print(ppp.x)
```

```error
selection reaches through one level of indirection and **Point has more
```

Assignment, compound assignment, and `++`/`--` all take a place, so the same three forms work on a
variable, on a field, and through a pointer with nothing special said about any of them.

**An element carries one wrinkle the other three do not.** A slice's elements and a pointer's live
wherever the storage is, which is somewhere the expression naming them is not, so they have an
address whether or not the expression naming them does. That is what makes `rows(g)[i] = v` write
through to the grid rather than into the view the call handed back. An **array's** elements *are* the
array, so they are places exactly when the array is. A string's bytes are never one: writing a byte
of UTF-8 is how a string stops being UTF-8, and that is refused as immutability rather than as the
absence of an address.

The hazard on the other side of that rule is the one dangle a **single statement** can produce. If a
temporary view is the only holder of its buffer, the buffer is released at the end of the statement,
and `&f()[i]` is a pointer to freed storage before the next line runs. That is the unsafe tier
behaving as advertised — `&` yields a raw pointer, a raw pointer can dangle, nothing promotes it —
and it is called out because every other dangle needs the pointer to be carried somewhere first.

## Addressing a value

`&` in front of something with no address of its own does not refuse — it **makes** the storage. The
value is written into a hidden local of the scope the `&` stands in, and what comes back is that
slot's address:

```sysl
trait Shape
    area(self) -> int

struct Rect
    w: int

impl Shape for Rect
    area(self) -> int = self.w

var s: *Shape = &Rect(2)

print(s.area())
```

```output
2
```

So `&Rect(2)` is `var t = Rect(2)` and `&t` with the name left out, and the name is the only thing
it leaves out: the slot is laid down for the frame, the count a counted value carries is taken
exactly as a `var`'s initializer takes one, and it is released where the scope ends.

**Any value, not only a construction.** `Rect(2)` and `make()` are the same expression as far as the
language is concerned — a call — so a rule that took one and refused the other could only be stated
as "you may address a constructor call but not a function call". A literal and an arithmetic result
are the same case again:

```sysl
mid(p: *int) -> int = *p

var a = 3
var b = 4

print(mid(&1), mid(&(a + b)))
```

```output
1 7
```

**It is storage rather than a copy handed out to be read**, so a program may write through it, and
the write is there when the pointer is read again:

```sysl
var p: *int = &1

*p = 9

print(*p)
```

```output
9
```

**The operand is evaluated once**, at the `&`, and not again per use of the pointer:

```sysl
var calls = 0

next() -> int
    calls += 1
    7

var p: *int = &next()

print(*p, *p, calls)
```

```output
7 7 1
```

**The scope is the one the `&` was written in**, so a pointer carried out of that scope is stale —
which is the raw tier behaving as advertised rather than a hole this form opens. The same pointer can
be had by naming the local yourself and taking its address, and neither is checked in general — what
a `*T` promises is spelled out above, and it is not lifetime. The one route that is checked is the
function's own answer, below.

**A name is answered rather than copied.** `&x`, `&t.f`, `&xs[i]` and `&*p` are asking for *that
thing's* address, so they go on meaning what they always did — and go on being refused where what
they name has no address. A **property** is the case that makes this a rule rather than a detail:
`t.f` at a property and `t.f` at a field are one spelling, and handing the first a pointer into a
copy would make a write through it go nowhere while looking exactly like a write that lands.

```sysl
struct Temp
    c: int

    f -> int = self.c * 9 / 5 + 32

var t = Temp(0)
var p = &t.f

print(*p)
```

```error
something with an address
```

A constant is the same shape and the same answer: `&capacity` reads as storage and there is none,
while `&8` — the value it stands for, written out — makes a slot like any other value does.

**Module storage has no such scope, and is refused.** An initializer there runs in a prologue, so the
slot would be that prologue's frame and the pointer would be stale by the program's first statement:

```sysl
struct Rect
    w: int

static var p: *Rect = &Rect(2)

print(p.w)
```

```error
module storage has no such scope
```

Give the value a name that lasts the run instead, and take the address of that:

```sysl
struct Rect
    w: int

static var r: Rect = Rect(2)
static var p: *Rect = &r

print(p.w)
```

```output
2
```

### An address of the frame's own storage is not an answer

A parameter passed by value — `(self)` among them — and a local `var` live in the function's frame,
and the frame is gone the moment the function returns. So **the address of one, or of a field or an
element of one, may not be what a function returns**: whether it is the body's value, a `return`'s,
the tail of an `if` or `match` arm, or wrapped in `ptr_cast`. A `(self)` method handing out a field is
the usual way to write it by accident, since `self` reads like the caller's value and is a copy:

```sysl
import sysl.raw.ptr_cast

struct Rec
    a: int
    b: int

struct Ev
    rec: Rec

    key(self) -> *u8 = ptr_cast(&self.rec)
```

```error
the address of 'self' is returned, but '(self)' is a copy of the receiver in this frame, and its storage ends when the function returns — return the value itself, or take '*self', so the address is of the caller's value
```

Storage reached **through** a pointer or a reference is wherever the address points, which is the
caller's to keep alive, so `*self`, `&self`, a `*T` or `&T` parameter and an element of a `[]T` are all
fine — as is module storage, which outlives every frame:

```sysl
struct Rec
    a: int
    b: int

struct Ev
    rec: Rec

    key(*self) -> *int = &self.rec.b

var e = Ev(Rec(1, 2))
val p = e.key()

*p = 9
print(e.rec.b)
```

```output
9
```

`&make()` makes a slot in the frame (§ Addressing a value), so returning it is refused the same way.
What is checked is the result only: a pointer to a local stored into module storage or into a struct
somewhere else is still the raw tier's bargain.

**Inside the function, a local's own slot lasts the whole frame**, not only its block, so a pointer
to a plain local of an inner block may be read after the block closes:

```sysl
run(c: bool) -> int
    var m = 1
    var p = &m

    if c
        var n = 41

        p = &n

    *p + 1

print(run(true))
```

```output
42
```

**What a block lets go of when it closes is what its locals hold**: a `Buf`'s elements, the object a
`&T` names, a value with a `Drop`. A pointer into that storage is refused where it is read after the
block, since the storage may be freed by then:

```sysl
import sysl.buf.{Buf, buf}

run() -> int
    var n = 0
    var p = &n

    if true
        val b: Buf[int] = buf()

        b.push(41)
        p = &b[0]

    *p + 1

print(run())
```

```error
'p' points into storage 'b' holds, and 'b' was let go of where its block ended — so this would read freed memory
```

**The `&` in a type is a different thing, and neither is a way of writing the other.** `&T` is a
counted box, which owns what is in it; the operator takes an address, which owns nothing. So a box
is not made by writing the operator in front of a value — it is made by the value arriving where a
`&T` is wanted, with no operator at all:

```sysl
trait Shape
    area(self) -> int

struct Rect
    w: int

impl Shape for Rect
    area(self) -> int = self.w

var s: &Shape = &Rect(2)

print(s.area())
```

```error
an address is not a way of making a box
```

Which is the whole of the fix — the same program with the operator dropped:

```sysl
trait Shape
    area(self) -> int

struct Rect
    w: int

impl Shape for Rect
    area(self) -> int = self.w

var s: &Shape = Rect(2)

print(s.area())
```

```output
2
```

## `ref` — a name for a place

A place can be deep, and until `ref` the two ways to shorten one both cost something.
`var t = self.tasks[i]` binds a **copy**, so every read and every write walks the path from the table
again; `&self.tasks[i]` gives the name back and gives up bounds checking, `within` checking,
invariant re-checking, and the guarantee at the top of this page, all in one step. That is a cliff
rather than a gradient.

**`ref` binds a name to a place.**

```sysl
struct Task
    state: int
    prio: int

struct Kernel
    tasks: [4]Task

    advance(*self, i: usize)
        ref t = self.tasks[i]

        t.state = 2
        t.prio = t.prio + 1
end Kernel

var k = Kernel([Task(0, 7); 4])

k.advance(2)

print(k.tasks[2].state, k.tasks[2].prio, k.tasks[0].prio)
```

```output
2 8 7
```

**The place is evaluated once**, where the binding is written — the index is computed once, the bounds
are checked once — and what the name means afterwards is the storage that was found, not the
expression that found it. A later `i += 1` leaves `t` naming the element it always named. Scala's
by-name parameter is the thing this is *not*: `x: => T` re-evaluates at every use, which would save
no check and would silently make `t` follow a later change to `i`.

### It is a declaration, never a type

This is the whole of why it can exist in a language with no borrow checker. `ref` may be written
**only as a local declaration**. There is no `ref` type, so a ref cannot be a field, a parameter, a
return type, an element, or a type argument; it cannot be re-pointed at a second place once bound; it
cannot be captured by a closure; and it does not outlive the block that declares it.

A ref *of* a ref is not a re-pointing and is ordinary — one more name for the place the first one
stands for, with the walks following through it:

```sysl
var xs: [3]int = [1, 2, 3]

ref a = xs[1]
ref b = a

b = 42

print(xs[0], xs[1], xs[2])
```

```output
1 42 3
```

That restriction is what keeps the compiler's knowledge complete. A `*T` is a type, so the moment one
exists it can be carried somewhere the compiler has lost the path it came from. A ref never travels,
so the analyzer still holds the place expression, at the point it was written, in the same body.

**At run time a ref stores an address. At compile time it remembers a place.** Both halves are
load-bearing. The address is what makes sixty-five path walks one. The remembered place is what keeps
every check a `*T` would have severed:

- a write through the ref re-runs the `invariant` clauses of every struct the place lies inside,
  found by the same outward walk, because the walk still has the whole place to walk;
- a ref into a `val`, or into an element of one, is read-only, since reaching into read-only storage
  keeps the property;
- a ref to a `within`-constrained slot is checked on assignment exactly as the slot is;
- and the bounds check a subscript owes is paid once, at the binding, rather than not at all.

A ref is therefore **not a fourth memory mode**. It introduces no representation, no new type, and
nothing that can be stored: it is a second way to *say* a place the three modes already describe.

### What may be written

**A ref's initializer must be a place**, and a call result has no address for the name to mean:

```sysl
make() -> int = 7

demo()
    ref x = make()

    print(x)

demo()
```

```error
'ref' names a place — a local, a field, an element, or a dereference — and this expression has no address for 'x' to name
```

**A ref inherits the place's writability and gets no modifier of its own.** A ref into a `val` may be
read and not written, exactly as the `val` may. Stating it twice would only create the chance to
state it wrong.

**A user type's subscript is a place when the type implements `IndexPlace`**, whose method answers
the element's address: `b[i]` is `*b.index_place(i)`, a dereference, so a ref names the element
where it lives. `Buf` is one ([arrays](/reference/arrays/)). Through `Index` alone the subscript is a
call answering a value, and there is nothing there for a name to mean.

```sysl
import sysl.buf.{Buf, buf}

struct Item
    block: u64
    data: Buf[u8]

var items: Buf[Item] = buf()

items.push(Item(1, buf()))

ref t = items[0]

t.block = 41
t.block += 1

print(items[0].block)
```

```output
42
```

### The one rule: what may move underneath it

A stored address is only as good as the storage staying where it is. C# has ref locals and needs no
rule like this, because a tracing collector keeps the old array alive when the variable is pointed at
a new one. sysl has no collector in this tier, so the same program would dangle:

```sysl
struct Table
    cell: []int

    swap(*self)
        ref e = self.cell[0]

        self.cell = [0; 8]

        print(e)
end Table

var t = Table([1, 2, 3])

t.swap()
```

```error
'e' is a 'ref' standing on storage this assignment would release, so the name would be left pointing at freed memory
```

**While a ref is live, no step of its place that could come to name different storage may be
assigned, and no mutating method may be called on a prefix of one.** The check is decided from types
(and, for a call, from what the callee's body writes), and never asks where the ref is *used* —
which is the question that would need lifetimes.

Which steps are hazards falls out of the model rather than being a list, and **the discriminator is
ownership, not indirection.** Only a step that *releases* something when it is overwritten can strand
a ref:

| step | hazard? | why |
|---|---|---|
| a `&T` | **yes** | overwriting it drops what it held, and that release may be the last |
| a view (`[]T`) | **yes** | the same — a view owns its buffer |
| a `*T` | no | a raw pointer owns nothing, so `p = q` frees nothing |
| a field, a fixed array, or an element of one | no | that storage *is* the enclosing object's bytes |

Two words carry the rule, and between them they keep the set small. A step is held still when it is
an **owning** step that the path goes **through** — so the place itself is never in the set, because
an assignment to it is what writing through the ref *is*. `ref r = h.node` names the slot, so
`r = other` and `h.node = other` are one statement written two ways and neither is refused, while
`h = other` on a `&Holder` is.

The `*T` exclusion is not a concession. A program that reaches its tables through a `*Self` receiver
has no owning step anywhere in its chains, so it is asked for nothing at all — which is exactly the
`advance` above, where the rule costs the program nothing.

The mutating-call half is the conservative one, deliberately. A `*self` method may write any part of
its receiver, so a live ref into that receiver is refused the call whether or not that particular
method reassigns anything:

```sysl
struct Table
    cell: []int

    grow(*self)
        self.cell = [0; 8]

var t = Table([1, 2, 3])

ref e = t.cell[0]

t.grow()

e = 5
```

```error
'e' is a 'ref' standing on storage this call could release, since a '*self' method may write its receiver
```

Deciding otherwise would mean reading the callee's body, and a module compiles against its imports'
signatures and never their bodies. The cost is one refusal in a program that could have written the
ref one line later.

**A container's storage moves under a `@relocates` member, whatever its receiver mode.** A `Buf`
is a handle, so its `push` takes `self` and still reallocates what the handle points at. Each member
that can move the storage or give an element up is marked, and the mark is what the check asks:

```sysl
import sysl.buf.{Buf, buf}

var b: Buf[int] = buf()

b.push(1)

ref first = b[0]

b.push(2)
first = 5
```

```error
'first' is a 'ref' standing on an element whose storage 'push' could move or give up, since it is '@relocates'
```

**A `*T` taken by `&` into the same storage is held to the same rule, for as long as it is live.**
`&b[0]` is the address `ref first = b[0]` stands on, so a `@relocates` member, a `*self` call on the
owner, and an assignment that replaces the owner are refused while the pointer — or a copy of it, or
an address computed from it — is still to be used. A pointer has no block of its own, so what counts
is its **last use** on any path the body can take, a later turn of a loop and a `defer` included:

```sysl
import sysl.buf.{Buf, buf}

run() -> int
    val b: Buf[int] = buf()

    b.push(41)

    val p = &b[0]

    for i in 0..<100 do b.push(i)

    *p + 1

print(run())
```

```error
'p' is a pointer to an element whose storage 'push' could move or give up, since it is '@relocates', and it is used after this call
```

Once the last use is behind it, the storage may move freely:

```sysl
import sysl.buf.{Buf, buf}

run() -> int
    val b: Buf[int] = buf()

    b.push(41)

    val p = &b[0]
    val first = *p

    b.push(first + 1)
    b[1]

print(run())
```

```output
42
```

A `defer` that reads the pointer is a use on every way out of its block — the end, a `break`, a
`continue`, a `return` and a `?`. A pointer kept in a local aggregate — a struct, a tuple, an
`Option`, an array, a `Buf` of pointers — is followed through it and refused where it is taken out
and used; so is one into what a match arm, an `is` test or a `for` loop bound out of a value made
for it, which is let go of where the arm or the turn ends.

**A function handed the buffer is asked what its body does with it.** One that calls a
`@relocates` member on a parameter — or hands the parameter on to a function that does — may move
what it is handed there, so a pointer into that argument may be neither handed to the same call
nor used after it:

```sysl
import sysl.buf.{Buf, buf}

grow_and_read(p: *int, b: Buf[int]) -> int
    for i in 0..<100 do b.push(i)

    *p

run() -> int
    val b: Buf[int] = buf()

    b.push(41)
    grow_and_read(&b[0], b)

print(run())
```

```error
this pointer is a pointer into storage this call also hands 'grow_and_read', which could move or give it up, since it calls '@relocates' 'push' on its parameter 'b'
```

**The check is otherwise local, for a pointer as for a ref's path**: it sees the `&`, the path
written after it, and the calls and assignments on that path in the same body. A second name for
the buffer (`val b2 = b`, then `b2.push(…)`, or a binding out of a place matched on), a callee
reached through a trait object or a function pointer, and an aggregate handed to a call are not
followed. A view of the buffer is not a pointer into it: the view keeps the storage it was made
from, so a `*T` into `b.view()` outlives a push. A ref's place may also be rooted at a pointer, and
whether *that* points anywhere is the raw tier's ordinary bargain.

**Writing a value that holds an owning step by value writes the step too.** With `h` a local `H`,
`h = H(…)` replaces `h.node` as surely as `h.node = …` does, so it is refused on the same terms.

**A second name for the same object is held to the rule as well.** A `&T` or a `Buf` may be held
under many names, and whether two of them name one object is a run-time fact, so the question is
asked of **types**: while a ref stands inside storage that a value of type `T` holds, writing storage
held in any `T` other than along the ref's own path is refused. Here `n` is a second name for
`h.node`:

```sysl
struct Node
    cell: []int

struct H
    node: &Node

var h = H(Node([1, 2, 3]))

ref e = h.node.cell[0]

val n = h.node

n.cell = [7; 8]
e = 5
```

```error
'e' is a 'ref' standing on storage held in a 'Node', and this assignment writes one that may be the same object by another name
```

**A call is asked what its callee's body can write.** One that may write a value of such a type —
directly, or through anything it calls — is refused while the ref is live; one that only reads, or
writes only its own locals and types unrelated to the ref's storage, is not. A call through a closure
or a trait object names no body to ask, so it is refused. A function with no body in the program — a
library's already compiled, or a foreign one — was compiled without the program's types, so it is
judged by its arguments: it is refused when one of them can reach such a value.

```sysl
struct Node
    cell: []int

struct H
    node: &Node

alias(n: &Node) = n.cell = [0; 8]

var h = H(Node([1, 2, 3]))

ref e = h.node.cell[0]

alias(h.node)
e = 5
```

```error
'e' is a 'ref' standing on storage held in a 'Node', and 'alias' may write a 'Node'
```

A second handle on a `Buf` is the same case: `val b2 = b` makes `b2.push(…)` a `@relocates` call on
the buffer a `ref r = b[0]` stands in, and it is refused with the `@relocates` message.

What stays open is everything that cannot be the ref's storage: other fields of the ref's own object
reached along its path (`h.node.count = 2` beside `ref e = h.node.cell[0]`), values of unrelated
types, calls whose bodies only read, and anything at all once the ref's block has closed:

```sysl
struct Node
    cell: []int
    count: int

struct H
    node: &Node

look(n: &Node) -> int = n.count + 1

var h = H(Node([1, 2, 3], 0))

if true
    ref e = h.node.cell[1]

    h.node.count = 2
    e = look(h.node)

val n = h.node

n.cell = [7; 2]
print(n.cell, h.node.count)
```

```output
[7, 7] 2
```

**What this does not make safe is a `*T`**, as above: a ref rooted at a raw pointer, or storage
reached through a reinterpreting cast, is the raw tier's ordinary bargain.

### A ref to a slot that holds a reference

`ref r = self.node`, where `node` is a `&Node`, names the **slot**, not the object in it. So the
binding takes no count — nothing new holds the object — and `r = other` is the assignment
`self.node = other` by another name, releasing what was there and retaining what arrives, in that
order. Reading `r` produces the reference and takes a count for the reader exactly as reading the
field would.

The distinction matters because the other reading is available and wrong: if binding retained, a ref
would be a `&T` with extra steps, and the count it took would keep an object alive past the write
that replaced it.

### Where it comes from

The form is old and lives outside this language's usual references, none of which have it. **Ada's
`renames`** is the general case and evaluates the name once at the declaration; **Fortran's
`ASSOCIATE`** is the closest in shape, being block-scoped and deliberately not a type; **C#'s `ref`
locals** are the closest in spelling. C# also shows what the restriction is worth: having added ref
returns and ref fields, it spent several releases building the escape analysis that keeping the form
local avoids entirely.

## Recursive types

A type may reach itself **through an indirection**, and only through one. `next: *Node` is
pointer-sized and legal, as is `next: Option[&Node]`; a struct holding itself by value has no finite
size:

```sysl
struct Node
    value: int
    next: Node

print(1)
```

```error
type 'Node' contains itself, so it has no finite size
```

The rule is **per cycle rather than per field**: a cycle is legal as soon as one edge on it is a `*T`,
a `&T` or a `[]T`, so mutually recursive types work as long as the loop passes through one of the
three somewhere. A slice is on that list because it is a pointer and a length — it *points at* its
elements rather than holding them — while a fixed array `[4]T` holds them and is not.

```sysl
struct Node
    tag: int
    kids: []Node

var n = Node(1, [])

print(n.tag)
```

```output
1
```

### A type argument is not containment

Whether `Wrap[Node]` contains a `Node` is **`Wrap`'s** business, not something the argument list can
say. `Buf[T]` reaches its elements through a `[]T`, so a node whose children are a growable sequence
of itself is finite — and it is the shape a syntax tree has:

```sysl
import sysl.buf.{Buf, buf}

enum Json
    Num(v: int)
    Arr(items: Buf[Json])

total(j: Json) -> int
    j match
        Num(v) -> v
        Arr(items) ->
            var sum = 0

            for i in 0..<items.len()
                sum += total(items.at(i))

            sum
end total

var xs: Buf[Json] = buf()

xs.push(Num(1))
xs.push(Num(41))

print(total(Arr(xs)))
```

```output
42
```

**Two types reaching each other is the same rule, and the loop may pass through a third type's
argument list.** A real document has objects in it, so `Json` needs a member — and a member holds a
`Json` by value, which closes a cycle that goes `Json` → `Buf[Member]` → `[]Member` → `Member` →
`Json`. The `[]Member` inside `Buf` is the edge that makes it finite, exactly as the `[]Json` above
was. **Which of the two is written first decides nothing**: declaration order has never meant
anything in sysl and it does not start meaning something here.

```sysl
import sysl.buf.{Buf, buf}

struct Member
    key: string
    value: Json

enum Json
    Null
    Num(v: int)
    Obj(members: Buf[Member])

var members: Buf[Member] = buf()

members.push(Member("answer", Num(42)))
print(members.at(0).key)
```

```output
answer
```

A generic that holds its parameter **by value** is the other answer, from the same argument list —
and the refusal names the field that holds it rather than the argument that was passed:

```sysl
struct Wrap[T]
    x: T

struct Node
    w: Wrap[Node]

var n: Node

print(1)
```

```error
type 'Node' contains itself, so it has no finite size
```

## Slices keep their backing alive

A slice is **three words**, not two:

```
[]T = { owner: *Buf, ptr: *T, len: usize }        // 24 bytes on a 64-bit target
```

`ptr` and `len` name the range; **`owner` keeps the bytes alive.** A bare `{ptr, len}` view can
outlive the buffer it views — Go gets away with it because its collector finds the object from an
interior pointer, and sysl has no collector — so the slice carries the owning reference itself.
Taking a slice retains, dropping one releases, slicing stays O(1) and allocation-free, and "a slice
never dangles" becomes true rather than aspirational.

```sysl
tail() -> []int
    var xs: []int = [1, 2, 3, 4]

    xs[2..]

var t = tail()

print(t.len, t[0], t[1])
```

```output
2 3 4
```

The buffer `xs` named is gone as a name before `print` runs, and the slice is still valid, because
the slice is one of its owners.

**`owner` is null when there is nothing to keep alive** — a slice of static data, of a `*T` buffer, or
of a fixed array whose storage outlives every view of it. Retain and release on such a slice are
no-ops, so allocator-free code slicing a static or stack buffer pays nothing at all. This is the same
immortality rule string literals use, generalized.

A `string` is then exactly an **immutable, validated `[]u8`**, and the two share one representation
and one implementation.

### A view borrowed from its receiver

A null owner is exactly right for static data and exactly wrong for memory **a C library owns**: an
object wraps the library's handle, frees it in its destructor (below), and hands out a view
of what the handle points at. Nothing in that view keeps the object alive, so the view can be read
after the destructor has run. **`@views(self)` on the member says so, and the compiler holds every
caller to it:**

```sysl
extern "malloc" c_malloc(n: usize) -> *u8
extern "free" c_free(p: *u8)

struct Column
    p: *u8
    n: usize

    @views(self)
    bytes(&self) -> []const u8 = self.p[0..<self.n]

impl Drop for Column
    drop(self)
        print("freed")
        c_free(self.p)

open(n: usize) -> &Column
    val p = c_malloc(n)

    for i in 0..<n do p[i] = u8(65 + i)

    Column(p, n)

sum(xs: []const u8) -> int
    var t = 0

    for x in xs do t += int(x)

    t

run()
    val c = open(3)
    val v = c.bytes()

    print(v.len, v[0], sum(v[1..<3]))

run()
```

```output
3 65 133
freed
```

The view is an ordinary `[]const u8` — no extra word, no count, nothing at run time. What the mark
adds is a fact the compiler keeps about the names in one body: **the view lives no longer than the
name it was called through**, because that name holds the count that keeps the object alive. The
check is lexical, the same one a `ref` gets (above), and it refuses four things.

**A receiver that is not a name.** A temporary is freed at the end of its statement, so a view of it
is refused at the call, naming it:

```sysl
extern "free" c_free(p: *u8)

struct Column
    p: *u8
    n: usize

    @views(self)
    bytes(&self) -> []const u8 = self.p[0..<self.n]

impl Drop for Column
    drop(self) = c_free(self.p)

open() -> &Column = Column(null, 0)

print(open().bytes().len)
```

```error
'bytes' answers a view borrowed from what it is called on, and that is the value 'open' answers — a temporary, freed at the end of this statement
```

**A view leaving the function that holds its receiver.** A `return`, or what the body comes to:

```sysl
extern "free" c_free(p: *u8)

struct Column
    p: *u8
    n: usize

    @views(self)
    bytes(&self) -> []const u8 = self.p[0..<self.n]

impl Drop for Column
    drop(self) = c_free(self.p)

first() -> []const u8
    val c: &Column = Column(null, 0)

    c.bytes()
```

```error
this returns a view borrowed from 'c', and nothing keeps 'c' alive once this function returns
```

Only a member that is itself marked `@views(self)` hands one back, and only one borrowed from
`self` — which is how a wrapper passes the borrow on to its own caller. A free function handed the
receiver as a parameter does not: the caller has no way to know its result is tied to that argument.

**A view stored where it outlives the receiver.** It may go into a local bound *after* the receiver,
or into a struct value held by one, since those close first. A local bound before it, a field
reached through a reference or a pointer, and module storage are refused:

```sysl
extern "free" c_free(p: *u8)

struct Column
    p: *u8
    n: usize

    @views(self)
    bytes(&self) -> []const u8 = self.p[0..<self.n]

impl Drop for Column
    drop(self) = c_free(self.p)

run()
    var keep: []const u8 = []
    val c: &Column = Column(null, 0)

    keep = c.bytes()
```

```error
'keep' is bound before 'c', so it outlives it, and this stores in it a view borrowed from 'c'
```

What a view is copied into is borrowed from the same receiver — a sub-slice, a struct or `Option`
built around it, a closure that captures it, a name a pattern binds out of it, a loop variable
walking a view of views — so each of those is held to the same places. A value that cannot hold a
view (`v.len`, `v[0]`, a string rendered from it, a byte a loop takes out of it) is not borrowed from
anything:

```sysl
struct Table
    rows: []const []const u8

    @views(self)
    all(&self) -> []const []const u8 = self.rows

run()
    var keep: []const u8 = []
    val t: &Table = Table(["ab".bytes, "cde".bytes])

    for row in t.all() do keep = row
```

```error
'keep' is bound before 't', so it outlives it, and this stores in it a view borrowed from 't'
```

A trait member may carry the mark too, and then a call through a trait object (`&Viewer`) is held
to it exactly as a call on the type is. An implementation of a marked member inherits the mark, so
it need not repeat it. The other way round is refused — a call through the object reads only the
trait's member, so a mark the trait does not carry would hold nobody to anything:

```sysl
trait Viewer
    look(&self) -> []const u8

struct S
    n: int

impl Viewer for S
    @views(self)
    look(&self) -> []const u8 = []
```

```error
'look' of 'impl Viewer for S' is marked '@views(self)', but trait 'Viewer' declares 'look' without it
```

A marked member is only ever called on a value: `Column.bytes(c)` is refused, as every method
reached through its type is.

**Freeing the receiver while the view is in scope.** Assigning the name it was called through, a
`*self` call on it, and a call that may write an object of its type — which a member that frees the
memory does, and a call through a closure or a trait object might, naming no body to ask — are
refused for as long as a local holding the view is in scope:

```sysl
extern "free" c_free(p: *u8)

struct Column
    p: *u8
    n: usize

    @views(self)
    bytes(&self) -> []const u8 = self.p[0..<self.n]

impl Drop for Column
    drop(self) = c_free(self.p)

run()
    var c: &Column = Column(null, 0)
    val v = c.bytes()

    c = Column(null, 0)
    print(v.len)
```

```error
'v' is a view borrowed from 'c', and this assignment could free what it views
```

Put the view in a block that closes first, and the name is free again once it has.

**This is the whole of it, and it is narrow on purpose.** The mark names only `self`, it marks only
a member whose result is a view (`[]T`, `[]const T`, `string`), and nothing about the borrow is in a
type: there are no lifetime parameters, and no borrow is tracked through a struct a member returns.
A view of a `*T` region that no member marked stays the programmer's problem, as every `*T` is.

## Escape analysis

The `owner` word answers "who keeps this alive" for every slice except one: a slice of a **local
fixed array**. That storage is a stack slot, valid until the frame returns and no longer.

| slice of | `owner` | safe to keep? |
|---|---|---|
| a heap buffer | the buffer | yes — retained |
| static data, a string literal | null (immortal) | yes — never freed |
| a `*T` region | null | the programmer's problem, like every `*T` — unless a `@views(self)` member hands it out, which ties it to the receiver (above) |
| **a local fixed array** | **null** | **only while the frame lives** |

The compiler finds the slices for which that matters, and **the programmer writes nothing**. There is
no `@escaping`, no lifetime parameter, and no "this result views that argument" marker anywhere in a
signature. Go is the precedent; Rust's lifetimes and Swift's `@escaping` are the alternative, and both
charge the programmer for what a compiler can work out. (`@views(self)`, above, is about memory no
frame owns — a C library's — which is exactly what the compiler cannot work out from a body.)

### What escapes

A slice whose origin is a local array escapes if it can be reached after the frame returns — that is,
if it is:

1. **returned**, directly or nested inside whatever carries it back: a struct, an enum, an `Option`,
   a tuple, or one slot of a multi-result list;
2. **stored into anything that outlives the frame** — a global, a field reached through a `&T`, or an
   aggregate that is itself stored somewhere that outlives the frame;
3. **passed as an argument the callee keeps**;
4. **captured by a closure that itself escapes**;
5. **assigned into a local that escapes** — the rule is transitive, resolved as a fixpoint over the
   function's own locals.

Everything else needs no allocation: indexing, iterating, sub-slicing into a local that stays local,
comparing, and passing to a callee that only reads.

### Crossing a call

Two facts about a function are enough to keep the caller's analysis local and exact, and both are
**inferred from the body** rather than written:

- **per parameter** — does the callee let this argument outlive the call?
- **for the result** — which parameters may the returned value be a view of?

They are computed bottom-up over the call graph and recorded in module metadata. Recursion starts
optimistic and iterates to a fixpoint, so a self- or mutually-recursive function converges on the
truth rather than on the conservative answer.

Two cases get the pessimistic answer instead — every parameter kept, the result viewing everything:

- **a call through a trait object**, because which body it reaches is a word read at run time, so
  there is no one summary to consult. Reading through a *concrete* type or through a bounded type
  parameter is unaffected, since monomorphization turns the second into a direct call too;
- **a function whose body is not available** — an `extern`, which is the declaration form for exactly
  that. The foreign side may retain what it was given, and nothing here can tell.

### What happens when a slice escapes

**With an allocator, the array is promoted.** It is allocated as an ARC buffer instead of a stack
slot, the slice's `owner` points at it, and nothing else about the program changes:

```sysl
render() -> []u8
    var line: [8]u8

    line[0] = 104u8
    line[1] = 105u8

    line[0..<2]

var l = render()

print(l.len, l[0], l[1])
```

```output
2 104 105
```

**The array keeps its type, and only its storage moves.** A promoted `[8]u8` is still a `[8]u8`: its
length is still a compile-time constant, it is still a value that copies on assignment, and every
index and store is emitted exactly as an unpromoted one's is. What changes is where the name's
address points, and that a view of it carries that buffer as its owner. Rewriting the declaration
into a `[]u8` would have been the other way to do it and is wrong — it changes the type at every use,
and an array is a value type where a view is not.

**Which array moves** is decided by the *root* of the escaping view. An index step is walked through,
because an element of a local array of arrays is part of that array's storage; a **field** step is
not, because that storage belongs to a struct. Only arrays that are *both* sliced *and* escaped are
promoted.

**A TEMPORARY is promoted too, and it has no declaration to promote.** A whole array the frame
computed and nobody named — an array literal, a call's answer, a field of either — is a temporary of
the frame that made it, which is exactly what promotion exists for, so a view of it that gets out is
given storage of its own: the same storage a literal written where a `[]T` is expected already gets.

What makes something a temporary is having **no address of its own**. That is the same line the
paragraph above draws from the other side: a local array, a field of one, an element of one and a
parameter are all storage that is somewhere, and the question for each is whose it is to move. A
temporary is nowhere yet, so it is nobody's, so it is this frame's:

```sysl
trait Sink
    take(self, xs: []const int) -> int

struct Adder
    base: int

impl Sink for Adder
    take(self, xs: []const int) -> int = self.base + int(xs.len)

var a = Adder(10)
val d: &Sink = a

print(d.take([4, 5, 6][..]))
```

```output
13
```

This is the case a **variadic** call meets without trying: `xs: ...int` packs a call's trailing
arguments into exactly `[a, b, c][..]`, so a `...T` member reached through a trait object is a
literal that has to outlive the frame. It costs an allocation there and nothing at a call whose slice
stays put.

**A literal written where a `[]T` is expected is the same temporary**, and it is laid in the frame
on the same terms: handed straight to a call that keeps nothing of it, and whose answer could not
carry a view of it out, it is the array `val xs: [N]T = [...]` would have been, viewed whole, and
allocates nothing. Its elements may be anything the frame computes:

```sysl
sum(xs: []const int) -> int
    var s = 0

    for x in xs
        s += x

    s

val g = 3

print(sum([g, g * 2, sum([g, 1])]))
```

```output
13
```

Everywhere else it is a buffer of its own, as before — bound to a name, returned, stored, handed to a
callee that keeps it, to an `async` function (whose task holds its arguments until it runs), or to a
call whose answer may be a view of it, such as `first([a, b])` answering `[]const int`. Those can
outlive the statement that made them, and a slot cannot.

A **call's answer** is the same temporary written a different way, and it is the one a reader meets
first: a digest is a `[32]u8`, so slicing one straight into something that keeps it is a view of an
array with no declaration behind it.

```sysl
import sysl.crypto.sha256

static var fingerprint: []const u8 = []

record(data: []const u8)
    fingerprint = sha256(data)[..]

record("abc".bytes)

print(fingerprint.len, fingerprint[0], fingerprint[31])
```

```output
32 186 173
```

**Binding it to a name first would have worked all along**, and that is the point: `val d =
sha256(data)` then `d[..]` is a declared array, promoted by the paragraph above. The two spellings are
one frame slot, so refusing the shorter one bought nothing.

Two roots have nowhere to be promoted to, and are diagnostics rather than promotions — an array a
caller passed **by value**, which is the caller's layout, and an array that is a **field** of a
**named** struct on the frame, where moving the field alone or the struct with it is the choice this
reference leaves unspecified. A **part** of a temporary is not one of them: the whole temporary
moves, and the view is narrowed over the buffer it moved into.

```sysl
first_two(a: [4]int) -> []int = a[0..<2]

print(first_two([1, 2, 3, 4]).len)
```

```error
a slice of an array this frame owns is returned, so it would outlive the array, and the storage is not this body's to move — it is a field of a value or an array a caller passed by value, which has no declaration to move. Declare it as a '[]T', which makes a buffer of its own and owns it, or as a '&[N]T' where the length is fixed
```

**Module storage is never a root, whatever the path to it.** It lasts the whole run, so a view of it
outlives every frame without moving anywhere: a field of a module array's element is as static as the
array, and a slice of it goes through a trait object, or anywhere else, with nothing promoted.

```sysl
trait Sink
    take(*self, words: []const u32)

struct Count
    n: usize

impl Sink for Count
    take(*self, words: []const u32) = self.n += words.len

struct Row
    words: [4]u32
    len: usize

var rows: [2]Row = [Row([0; 4], 0); 2]
var sink: Count = Count(0)

feed(s: *Sink, i: usize)
    s.take(rows[i].words[0..<rows[i].len])

rows[1].len = 3
feed(&sink, 1)
print(sink.n)
```

```output
3
```

**Without an allocator it is always a compile error.** Under `no alloc` there is nothing to promote
into, so every promotion becomes a refusal reported at the view that leaves the frame — and so does
every literal argument a callee keeps. A machine whose manifest took the heap away (`capabilities {
heap = false }`) is held to the same line in every module it builds, without a clause written
anywhere: the refusal names the machine, `'thumbv7em-freestanding' provides no allocator`, rather
than leaving the link to say `undefined symbol: malloc`. That is how
every other allocation-gated feature behaves in the allocator-free subset — growable arrays, escaping
closures, and `&T` creation are all compile errors there — so it introduces no new rule.

### A view that outlives its scope is promoted too

Leaving the frame is the widest way for a view to outlive its storage, and not the only one. The
rule is the same at every width: **storage whose view outlives the storage's scope is promoted**.

A **temporary**'s scope is its statement. What it holds is let go of at the end of it, and the slot
it sat in is reused, by the next statement or by the same one on the next round of a loop. A view of
it **kept in a local** is read on later lines, so the temporary moves to the heap and the local reads
what was written:

```sysl
id(xs: []string) -> []string = xs

val s = id([7.to_string() + "y"][..])

print(s[0])

first() -> int
    var keep: []int = []

    for i in 0..<3
        if i == 0 then keep = [i * 10][..]

    keep[0]

print(first())
```

```output
7y
0
```

A **named array**'s scope is the block that declares it. One declared in a loop's body or an `if`'s
branch is gone when that block closes, so a view of it kept in a local of an enclosing block moves it
the same way. A part of either is the same case: the whole array moves, and the view names its part
of the buffer.

```sysl
first() -> int
    var keep: []int = []

    for i in 0..<3
        var row = [i * 10, i]

        if i == 0 then keep = row[..]

    keep[0]

tail() -> []string
    var keep: []string = []

    if true
        keep = [1.to_string(), 2.to_string() + "b", "c"][1..<2]

    keep

print(first(), tail()[0])
```

```output
0 2b
```

Nothing moves where nothing outlives anything: a view used within its own statement, and a view of
a named array kept while that array is still in scope, stay where they are.

```sysl
id(xs: []string) -> []string = xs

print(id([7.to_string() + "y"][..])[0])

val a = [7.to_string() + "y"]
val s = a[..]

print(s[0])
```

```output
7y
7y
```

Without an allocator, each of these promotions is a compile error at the view, as an escape from the
frame is. A machine whose manifest says `heap = false` is told that it provides no allocator, and a
module that declared `@no_alloc` is told that it did:

```sysl
@no_alloc

first() -> int
    var keep: []int = []

    for i in 0..<3
        if i == 0 then keep = [i * 10][..]

    keep[0]

print(first())
```

```error
this view of an array literal is kept in 'keep', which outlives it, so the array would move to the heap to outlive its statement — and this module declared '@no_alloc', so there is nothing to move it into
```

### Promotion is silent, not hidden

Silent promotion earns the obvious objection: an allocation appears that nothing in the source asked
for. The answer is discoverability rather than ceremony — **`--explain-escapes`** reports every
promotion the compiler made and the route that forced it:

```
$ sysl build --explain-escapes tty.sysl
tty.sysl:31:12: 'buf' is promoted to the heap, because this view of it is returned
```

One line per array, in source order, on stderr, accepted by every subcommand. The position is the
**view that forced the move** rather than the declaration, because that is the half a reader cannot
work out for themselves. This is Go's `-m`, and it is the right shape: the common case costs no
reading, and "why did this allocate?" always has an answer. A program that must not allocate says so
with `no alloc`, and then the compiler enforces it rather than reporting it.

The idiom worth reaching for first, even where an allocator exists, is the last one: **return a count
and let the caller slice its own buffer.** That is what `snprintf` does, what Rust's buffer writers
do, and what most kernel code wants.

## Reinterpreting storage

An allocator carves bytes and hands back a typed pointer — that is the whole of what an allocator
does. A driver takes an address the datasheet gives as a number and reaches the register block at it.
Both need a way to say which type some bytes are, and both are cases the raw tier already committed
to.

**Three directions, two spellings**, because they are not equally dangerous:

| written | direction | tier |
|---|---|---|
| `usize(p)` | a pointer as a number | an ordinary conversion |
| `ptr_cast(n)` | a number as a pointer | unsafe |
| `ptr_cast(p)` | one pointee type as another | unsafe |

**`ptr_cast` is imported, from `sysl.raw`** — `import sysl.raw.ptr_cast` — beside `str_cast` and
`str_alias` (`strings.md`). The compiler still answers it, since its target comes from the position
its value fills and no signature can say that, but its name is not in scope everywhere: a file names
the raw-tier casts it makes, so every reinterpretation in a program is found by its imports. An
alias (`import sysl.raw.ptr_cast as pc`), a wildcard (`import sysl.raw.*`) and a path through the
module (`raw.ptr_cast(p)` after `import sysl.raw`) reach it as they reach anything else. **The
import is required.** Written bare without one, the form is refused, and the error names the line
to add:

```sysl
var arena: [8]u8 = [0u8; 8]
val p: *u32 = ptr_cast(&arena[0])

print(*p)
```

```error
'ptr_cast' is in sysl.raw: add 'import sysl.raw.ptr_cast'
```

A function or a local the reader names `ptr_cast` is what a call reaches, imported or not; importing
the form under the name of one is refused, since the name would mean two things.

**A pointer becomes an integer through the ordinary conversion syntax**, because it is an ordinary
conversion: `usize` is wide enough to hold any address by definition, so it is total and loses
nothing, and the result is a number that cannot be dereferenced. `isize` takes one too, which is what
a program comparing addresses against a signed offset wants.

```sysl
import sysl.raw.ptr_cast

struct Node
    value: int

var arena: [64]u8 = [0u8; 64]
var n: *Node = ptr_cast(&arena[0])

n.value = 42

print(n.value, sizeof(Node), usize(n) == usize(&arena[0]))
```

```output
42 4 true
```

**The address of a function converts as well**, and for the same reason: `&f` is an address, it is a
pointer's width, and the number that comes out cannot be dereferenced any more than the one above
can. It is what a binding reaches for when a C library wants a callback registered through something
that takes a number, and it is the same row of the table rather than a special case.

**A number becomes a pointer only from an address-width integer**, `usize` or `isize`. A fixed width
is not an address's width on every target, and reading a narrower one would invent the address's top
bits. **The operand of `ptr_cast` is an address-width position**, as the initializer of a
`val a: usize` is: a bare integer literal, or an expression made of nothing but bare integer
literals, is a `usize` at the target's width. So the address a datasheet gives is written as it is
printed there, and so is a base plus an offset or a bit shifted into place:

```sysl
import sysl.raw.ptr_cast

struct Uart
    data: u32
    status: u32

val uart: *Uart = ptr_cast(0x4000_4000)
val status: *u32 = ptr_cast(0x4000_4000 + 4)
val bit: *u8 = ptr_cast(1 << 20)
val none: *Uart = ptr_cast(0)

print(usize(uart), usize(status), usize(bit), usize(none))
```

```output
1073758208 1073758212 1048576 0
```

Each literal has to fit: an address too wide for the target's `usize` — above `0xFFFF` on a 16-bit
machine, above `0xFFFF_FFFF` on a 32-bit one — or a negative one is refused as any literal that does
not fit its type is, and the arithmetic over them wraps at that width as integer arithmetic does. A
value that already has a type keeps it, so an `int`, a suffixed literal such as `16u32`, or an
expression with either inside it is still refused, and is converted where it is meant as an address:

```sysl
import sysl.raw.ptr_cast

var base = 0x4000_4000
val uart: *u32 = ptr_cast(base)

print(usize(uart))
```

```error
'ptr_cast' reads an address, and int is not address-width — convert it first, as 'ptr_cast(usize(n))'
```

**The target type comes from whatever receives the result** — the same way `va_arg`, a bare `None`,
and a bare `null` all take theirs. Where that is not where a reader wants to say it, it may be
written on the form: `ptr_cast[*Node](&arena[0])` is
[the written type-argument list](/reference/generics/) at a special form. Where neither says which
pointer is wanted, the program is told to annotate what receives it:

```sysl
import sysl.raw.ptr_cast

var arena: [64]u8 = [0u8; 64]
var p = ptr_cast(&arena[0])

print(1)
```

```error
'ptr_cast' reads an address as a pointer to some type, and nothing here says which
```

**`ptr_cast` never produces a `&T`.** A reference is a safe-tier value — non-null, refcounted, and
relied on by everything the safe subset promises — and an address invented from bytes has no count for
ARC to own and no object to be non-null about:

```sysl
import sysl.raw.ptr_cast

struct Node
    value: int

var arena: [64]u8 = [0u8; 64]
var n: &Node = ptr_cast(&arena[0])

print(n.value)
```

```error
'ptr_cast' never produces a reference: a '&T' is counted and non-null, and an address read out of bytes carries no count for anything to own — read it as a '*T'
```

A `weak T` is refused for the same reason, and the fat types — a slice, a `string` — for that reason
and one more: they are wider than an address, so there is nothing to reinterpret. What comes out is a
`*T`, and reaching anything else from it is the ordinary route through `*p`.

### Storage the compiler did not lay out may not hold a counted value

`ptr_cast` says which type some bytes are. It does not make those bytes into storage the language
manages, and the difference has one consequence worth stating on its own: **a `&T`, a `weak T`, a
slice or a `string` may not be written into memory reached that way** — nor may a struct or an enum
that carries one.

The reason is what an assignment to such a field *does*. It releases whatever occupied the slot
before writing the new value, so the release walks whatever the bytes happened to look like.

**Nothing refuses this**, and that is why it is written here. The compiler knows the field's type but
not where the pointer came from: `p.next = other` through a `p` that is `&local` writes a field it
laid out, whose previous occupant is a real box, and the release is correct; through a `p` that is
`ptr_cast` over an arena the same line walks raw bytes. Telling the two apart needs provenance the
front end does not track.

**Zeroed storage is the exception, and it is the one a collector or a `calloc` gives.** A counted
reference that is all zero bits names nothing, and giving back a share of nothing is nothing — so a
`&T` field (or a `Buf`, `Map` or `Deque`, each of which is one) in storage zeroed before its first use
takes its first assignment safely, and `string` and slice fields already did. Until it is assigned it
is the one `&T` that is null, and **`is_null(r)` is how a program asks**: one compare of the address
with null, no share taken. An ordinary `&T` — one the language made — is never null, and `is_null`
answers `false` for it; a trait object and a `weak T` are refused, the latter answering through
`.get()`, and anything that is not a reference is refused naming its type.

```sysl
import sysl.raw.ptr_cast

extern "calloc" c_calloc(n: usize, size: usize) -> *u8
extern "free" c_free(p: *u8)

struct Item
    label: string

struct Slot
    item: &Item

val s: *Slot = ptr_cast(c_calloc(1, sizeof(Slot)))

print(is_null(s.item))

s.item = Item("a")

print(is_null(s.item), s.item.label)

release(s.item)

print(is_null(s.item))

c_free(ptr_cast(s))
```

```output
true
false a
true
```

The storage is still the program's to free, and freeing it releases nothing: a counted field there is
given back before the block goes, and **`release(place)` is how** — `is_null`'s other half. It gives
back the share the `&T` place held, running the destructor when that was the last one, and leaves the
place naming nothing, exactly as the zeroed storage started; nothing is allocated, which is why a
finalizer calls it rather than assigning a fresh value it would only lose. A place released twice gives
back nothing the second time, and one released may be assigned again. A container gives its share back
through its own `release()` member, and an `Option` or a `weak T` is emptied by assigning `None`, so
`release` is refused on those — and on anything that is not a place, or not a counted reference at all:

```sysl
import sysl.buf.{Buf, buf}

struct Holder
    names: Buf[string]

val h = Holder(buf())

release(h.names)
```

```error
'release' gives back what a '&T' place holds, and sysl.buf.Buf[string] gives its share back through its own member — call '.release()' on it
```

Like `is_null`, it is a form the compiler answers only where nothing else claims the name: a function
or a local called `release` is what a call reaches.

**Keep an index, a raw pointer, or a copy instead.** An arena of nodes linked by index is the
ordinary answer, and it costs nothing a box was buying:

```sysl
struct Node
    value: int
    next: usize          // an index into the arena, not a '&Node'

const nowhere: usize = 999

main()
    var arena: [4]Node = [Node(0, nowhere); 4]

    arena[0] = Node(10, 1)
    arena[1] = Node(20, 2)
    arena[2] = Node(30, nowhere)

    var at: usize = 0
    var total = 0

    while at != nowhere
        total += arena[at].value
        at = arena[at].next

    print(total)
```

```output
60
```

The same rule read from the other side: a type whose fields are all scalars, raw pointers and arrays
of those is free to live in storage you carved. That covers every C ABI struct, which is what the raw
tier is mostly for.

`sizeof`, `alignof` and `offsetof`, which measure what is being carved, are on the
[expressions](/reference/expressions/) page.

## Slot storage: room that holds no value yet

A growable container needs storage whose spare end holds **nothing** — not a fill value repeated into
every slot, which for a counted element would keep whatever it names alive until the slot is
overwritten. Three forms give the raw tier exactly that, and they are what
[`sysl.buf.Buf`](/library/buf/) is written with:

- **`slot_storage(n)`** (or `slot_storage[T](n)`) is a `[]T` of `n` slots of which **none is live**.
  The element type comes from the position, the way `ptr_cast`'s does.
- **`slot_place(xs, i, v)`** puts `v` in slot `i`. The first slot past the live ones is filled
  without letting go of anything, and the live run grows by one; a live slot is an ordinary
  assignment; a slot further on would leave a gap, so it **traps**.
- **`slot_vacate(xs, n)`** lets go of every live slot from `n` on and shortens the live run to `n` —
  but only where nothing else holds the storage. A view handed out earlier still reads those slots,
  so while one exists the form does nothing, and the next vacate after the sharing ends catches up.

```sysl
struct Node
    n: int

var xs: []&Node = slot_storage(4)

slot_place(xs, 0, Node(1))
slot_place(xs, 1, Node(2))

print(xs.len, xs[0].n + xs[1].n)

slot_vacate(xs, 1)
slot_place(xs, 1, Node(5))

print(xs[0].n + xs[1].n)
```

```output
4 3
6
```

**The live run is the storage's own, not the slice's**: the buffer's box carries the count of live
slots, and its destructor releases exactly those, so storage dropped with two of four slots placed
releases two. The slice's `len` is still the whole room. **What the compiler cannot check is the one
thing the caller owes**: nothing reads a slot past the live run, whose bytes are whatever the
allocator left. That is the raw tier's bargain, as it is for `ptr_cast` — the forms are greppable, and
`Buf` is what writes them so that a program does not have to.

A form with no element type to make is refused at the call:

```sysl
var xs = slot_storage(4)
```

```error
'slot_storage' makes storage for elements of some type, and nothing here says which
```

On storage that `slot_storage` did not make — an array, a frame, a view of a struct's field — the
two writing forms are an ordinary assignment and nothing at all, since there is no live run to move.

### Moving one value through a raw pointer

Storage whose live slots are not a prefix — a ring's run wraps, so a vacated slot can sit between two
live ones — cannot use the three forms above, which count a run. Two more move a single value through
a raw pointer, one slot at a time:

- **`slot_take(p)`** moves the value out of the slot `p` points at. The share the slot held becomes the
  result's, so **no count is taken or given back**, and the slot is left **empty**. Over an element
  that holds a count the slot is zeroed, which is the state zeroed storage starts in; over a plain
  value it is only read.
- **`slot_put(p, v)`** moves `v` into an **empty** slot. Nothing that was there is given back, so a
  slot that was not empty keeps its old share for ever.

**This is the raw tier's bargain again, and the caller owes the compiler's missing check**: a taken
slot must be live, a put slot must be empty, and nothing reads or lets go of a slot while it is empty.
Rust's `ptr::read` and `ptr::write` are the same pair. A container written with them gives a value up
at the moment it hands it over — `Ring`'s `pop_front` returns the value with its destructor still to
run in the caller, rather than leaving a second share behind in the slot for the next push to release.

```sysl
var x = 5
val p = &x

print(slot_take(p))
slot_put(p, 9)
print(x)
```

```output
5
9
```

Both refuse what is not a pointer:

```sysl
val n = 3

print(slot_take(n))
```

```error
'slot_take' moves a value through a raw pointer, which is a '*T' — this is int
```

## Device memory

`ptr_cast` gets a driver to the register block. It does not get it a *correct* driver, and the missing
half is this: an optimizer is entitled to assume that reading the same storage twice yields the same
value, that a store nobody reads is a store nobody needs, and that two accesses in a row may be one
wider access. Every one of those assumptions is false at a device. `while regs.status == 0u32 do ()`
is a loop that reads a register until the hardware changes it, and a compiler that hoisted the read
out would spin forever on the first value it saw.

So sysl has C's qualifier, spelled the way C spells it — in the type:

```sysl
struct Uart
    status: volatile u32
    data:   volatile u32
    baud:   u32

var block = Uart(1, 0, 115200)
var regs: *Uart = &block

regs.data = 65

print(regs.status, regs.data, regs.baud)
```

```output
1 65 115200
```

> A **`volatile`** place is one whose reads and writes are **effects, not value computations**. It
> may change without the program changing it, and reading it may itself do something. So the compiler
> emits exactly the accesses the source wrote, exactly once each, in the order written — never adding,
> dropping, merging, or moving them relative to one another.

**It constrains the compiler, not the machine.** No atomicity, no ordering against another core, no
protection from a torn read. C spent two decades learning this; for talking to another thread the
tools are `&sync T`, `Mutex[T]`, `Atomic[T]` and explicit orderings, and none of them is spelled
`volatile`. A program that reaches for this word to share a counter has written a race with a keyword
in front of it.

### It qualifies storage, and a value read out of storage is an ordinary value

This is the rule everything else follows from. `regs.status` above has type `u32` — not
`volatile u32` — because what a load hands back is a number, and a number is not somewhere a device
can write. What is qualified is the **place**, and the qualifier lives in the three types that name a
place somebody else owns, and on the one declaration that names storage where it stands:

| written | what is qualified |
|---|---|
| `status: volatile u32` | a struct field |
| `bank: [4]volatile u32` | an element — a GPIO bank |
| `p: *volatile u32` | a pointee — the lone register |
| `var tick: volatile u64` | a module's own storage — a word an interrupt handler writes |

Everywhere else the type being written is the type of a **value**: what a local `var` holds, what a
parameter receives, what a function hands back, what a type argument stands for.

```sysl
var x: volatile u32 = 0u32

print(x)
```

```error
'volatile u32' is the type of *storage*, and this is a value — what a read of a volatile place hands back is an ordinary 'u32'. The qualifier goes where the storage is named: a struct field, an element, or the pointee of a '*T', as '*volatile u32'
```

The diagnostic says which spelling was wanted, because a program that writes `var x: volatile u32`
in a body almost always meant `*volatile u32`.

### Module storage shared with an interrupt handler

A module `var` — or a `static var` in the file a program starts in — **is** storage, named where it
is declared, so it may be `volatile`. It is how a kernel or a bare-metal program shares a word with
an interrupt handler before anything heavier exists: the handler writes it, the program polls it, and
the poll has to read memory every time round.

```sysl build=c target=aarch64-freestanding
var ticks: volatile u64 = 0

@export("on_timer")
on_timer()
    ticks += 1

@export("wait_for")
wait_for(n: u64)
    while ticks < n do ()
```

Every read of `ticks` is one volatile load and every write one volatile store, of the whole width.
At `-O2` the loop in `wait_for` stays a loop that loads `ticks` each time round; with a plain `var`
the optimizer is entitled to load it once and branch to itself for ever, since nothing in the program
it can see writes it. What a read hands back is an ordinary `u64`, and `&ticks` is a
`*volatile u64`, exactly as `&` of a volatile field is.

**`ticks += 1` is a volatile load and then a volatile store — it is NOT atomic.** An interrupt taken
between the two, whose handler writes `ticks` as well, loses that write. That is fine where only the
handler writes and the program only reads, which is the shape above; where both sides write, or two
cores share the word, the tool is `Atomic[T]` from `sysl.sync`, and the qualifier says nothing about
ordering against another core.

The type is held to what a field is held to — a scalar or a raw pointer — since the promise is one
access of the whole width. A struct is refused as a whole, and the refusal points at the spelling
that works, a struct whose fields are volatile:

```sysl target=aarch64-freestanding
struct Counts
    ticks: u64
    irqs: u64

static var counts: volatile Counts
```

```error
'volatile Counts' cannot be the type of 'counts': a volatile access is one load or one store of the whole width, and 'Counts' is a struct. Write 'volatile' on its fields instead, as 'n: volatile u64', and declare the storage as a plain 'Counts'
```

A `val` is refused: it is a constant, so nothing can write it behind the program's back.

```sysl target=aarch64-freestanding
val limit: volatile u64 = 3
```

```error
'limit' is a 'val', and a 'val' is a constant: nothing writes it once it starts, so no access to it can be one the program did not make, and 'volatile' has nothing to say. Storage an interrupt handler or a device writes is a 'var'
```

**Per field, not per aggregate.** C also allows `volatile struct Uart`; sysl does not, and the block
above shows why. `baud` is a shadow value the driver keeps in ordinary memory beside the registers,
and every real device header has one — a reserved word, a cached configuration, a software flag. A
qualifier on the whole struct would sweep it in:

```sysl
struct Uart
    status: u32

var p: *volatile Uart = null

print(1)
```

```error
a register block is qualified one field at a time
```

Qualifying per field is the same power with the opt-out, it is what CMSIS and every other vendor
header already does, and it makes the restriction below a check on one scalar instead of a walk of a
type.

**Only a scalar or a raw pointer may be qualified.** The promise is about *the* load and *the* store
the source wrote, so it is only meaningful where an access is one instruction:

- **a counted value is refused outright** — a `&T`, a `weak T`, a slice and a `string` come with
  retains and releases the compiler places, and a retain that may not be elided is not a request
  anybody could act on;
- **a trait object is refused** for a plainer reason: it is two words, so touching one is two accesses
  whatever the source says, and the table beside the value is this program's rather than a device's;
- **a constrained subtype is refused**, and this one is about trust rather than instructions. A
  `Level = int within 0..7` is the claim that a value *has been checked*; a register holds whatever
  the device put there. So the register is declared at the base type and what comes back is converted
  — one written conversion, checked, at the point the value arrives;
- **a data enum is refused** — a tag beside a payload is more than one access however it is written.

A **simple enum** is admitted, since it *is* its underlying integer: it is one load, and it is the
spelling a register's mode field wants. So is a **bitfield**, which is the one case where the access
reaches more than the field named — the qualifier applies to the container the field is bits of, so a
write is a read-modify-write of the whole of it. `@packed` on
[attributes](/reference/attributes/) states that rule and what it costs a driver.

**A struct that holds a register carries no `invariant`.** A check is a call taking every field, so it
reads the whole block however few fields the clause names — an invariant written over the shadow value
beside the registers would make writing that shadow an access to the device. There is nothing to hold
the clause true either: a device changes a register between the check and the instruction after it. A
register is checked where it is read.

### What the compiler does with it

A qualified access lowers to LLVM's `load volatile` / `store volatile`, which is exactly the barrier
this needs: it stops the reordering, elision and merging above, and stops nothing else. There is no
runtime cost and no runtime component.

- **A qualified field is reached at its own address**, not lifted out of the block. Reading a `Uart`
  to find out what is in `status` would also read `data`, and reading a data register is how a FIFO is
  popped — so a qualified field gets a place walk, one `getelementptr` and one `load volatile`, with
  nothing else touched.
- **A whole block copied is a copy of every register in it.** `var u = *regs` is one access to each,
  which is as much an effect as one access to one of them, so the aggregate access is marked too.
  Whether a driver wants that is the driver's business; what it does not get is a silent unqualified
  read of hardware.
- **An address taken of a register is the address of a register.** `&regs.status` has type
  `*volatile u32`, so a driver may hand one register to a helper and every access the helper makes is
  still an access to a device. Without that, a driver would have to be one function.
- **A type parameter never binds to a qualified type.** The loads and stores a generic body emits are
  *its* accesses, written once and shared by every instantiation, so it cannot promise to have written
  the ones a particular caller had in mind.

**`volatile` is not reserved.** It is special only in front of another type, so a program with a
variable, a field, a function, or a type of its own by that name still compiles — the same arrangement
`sync` has after `&`:

```sysl
var volatile = 7

print(volatile + 1)
```

```output
8
```

**`[]const T` composes with it and means a different thing.** `const` is a property of the *view* —
these elements may not be written through this handle — while `volatile` is a property of the
*element*. A read-only device register is `[]const volatile u32`, and both words are doing work.

### Memory a device reads and writes is a library type, not a qualifier

`volatile` is about the device's **registers**. The memory a device reaches by DMA — a buffer, a
descriptor ring — is ordinary memory the processor reads and writes normally; what it needs is an
address the *device* can use and a handover that keeps the caches honest, and neither is a property
of a place. So it is [`sysl.dma`'s `Region`](/library/dma/): it carries the device's address of its
first byte, is made only by `adopt` from memory somebody knows to be device-reachable, and an ordinary
`[]u8` is not one. No qualifier on `*T` or `[]T` says where storage is, because a slice's type cannot
know whether it was cut from a stack, a heap block or a frame — the region's maker does.

## Where `defer` sits

[`defer`](/reference/statements/#defer) is the model's answer for what the language does *not* own: a
descriptor from `open`, a `FILE*` from `fopen`, a block from `malloc`, a lock taken from a mutex. ARC
gives back a reference, a string, and a slice's backing without being asked, and knows nothing about
those.

Three facts place it against everything above:

- **A deferred statement runs before the block's ARC releases**, so every local it names is still
  alive when it runs — including the one holding the resource it is closing. Leaving from the middle
  unwinds outward: the innermost block runs its deferred statements and then gives up its counts, then
  the block outside it does the same.
- **It owns nothing and allocates nothing.** `defer` takes no count, makes no box, and adds no word to
  any value; a program that does not use it emits nothing for it. That is what keeps it available
  under `no alloc`, where the resources it releases are the only ones there are.
- **A trap runs nothing.** A trap aborts without stack cleanup, and `defer` does not qualify that: a
  broken invariant means the program's model of itself is already wrong, and running cleanup against
  that state is how a corrupt program writes its corruption to disk on the way down.

**What it is not is a destructor.** A destructor belongs to a *type* and runs wherever a value of that
type dies; `defer` belongs to one place in one body and runs for the resource that body took. Both
exist, and the next section is why neither replaces the other.

## A destructor

**`impl Drop for T` says what a type does when the last reference to one of its values goes.** It
declares one member, `drop(self)`, and it answers nothing:

```sysl
struct Handle
    id: int

impl Drop for Handle
    drop(self) = print("closing", self.id)

hold()
    val h = Handle(7)
    print("working")

hold()
print("done")
```

```output
working
closing 7
done
```

**It is for the resource the language does not manage.** ARC returns the storage and releases
whatever the value holds; what it cannot do is close a descriptor, unmap a region, or hand a handle
back to the C library that made it. Those live at the far end of a `*T` or behind an integer, and
nothing about either says it is owned. The destructor is where that is said, once, beside the type.

**`defer` is the other way to say it, and neither replaces the other.** `defer close(f)` covers every
site a program can *name*. Under ARC a value can also die where there is no site to write one — and
that case is the whole argument for this being a capability rather than a shorthand:

```sysl
struct Handle
    id: int

impl Drop for Handle
    drop(self) = print("closing", self.id)

hold()
    var xs: []&Handle = [Handle(1), Handle(2)]
    print("holding", xs.len)

hold()
print("done")
```

```output
holding 2
closing 2
closing 1
done
```

Nothing in that program names the moment either element dies. The slice goes, and its elements go
with it — so no `defer` could have been written, and a leak there would not be something the author
could have prevented. Where both would work, the destructor is the better one: it is written once,
and a caller cannot forget it.

**It costs nothing to have.** Release already calls through a per-payload hook, and a destructor is a
call at the top of that hook. A type without one produces exactly the hook it always did. And a
destructor is in the program only where the program holds a value of its type — made, stored, carried
in a field, a container, an enum payload or a closure — so a library type's destructor costs a program
that never makes one nothing at all: one that calls `sysl.process.pid` carries no `Child.drop`, and on
a bare-metal target is not asked to answer the hooks that destructor would call.

### The four limits, each a consequence of where it runs

**It runs before the value's own references are released**, so it is handed `self` intact and a field
may be read to close what it names. It borrows rather than taking a count — the count is already
zero, and taking one would resurrect the object into a second teardown.

**It runs only for a value on the heap, so a type with a destructor is a reference type.** A value
type is copied, and a copy is not a second resource: there is no single point of death to hook, and
running it per copy would close one descriptor several times. So the language never lets a program
hold one by value — the next section is what that means to write.

### A type with a destructor is a reference type

**Building one makes a `&T`, with nothing written.** `Thing(1)` is a `&Thing` wherever it stands — an
inferred local, a temporary, a branch, a field, a container's element, an `Ok` — so the destructor
always has a count to hang off:

```sysl
import sysl.buf.{Buf, buf}

struct Thing
    n: int

impl Drop for Thing
    drop(self) = print("drop", self.n)

struct Holder
    inner: &Thing

open(n: int) -> Result[&Thing, string] = Ok(Thing(n))

hold()
    val a = Thing(1)
    val h = Holder(Thing(2))
    var all: Buf[&Thing] = buf()

    all.push(Thing(3))
    all.push(open(4).unwrap())
    print("holding", a.n, h.inner.n, all.len())

hold()
print("done")
```

```output
holding 1 2 2
drop 4
drop 3
drop 2
drop 1
done
```

A position that names the count keeps it: `val t: &sync Thing = Thing(1)` builds an atomically counted
one, and a trait object — `val s: &Shape = Thing(1)` — boxes on its way into the table, as it does for
any value.

**Every spelling that would hold one by value is refused**, with the `&T` to write in its place: a
binding, a parameter, a result, a field, an enum payload, module storage, and any of those holding it
inside a `Buf`, an `Option`, an array, a slice or a tuple. The constructor that hands one back is the
case that looks most innocent, and is told even when nothing calls it yet:

```sysl
struct Encoder
    state: *u8

impl Drop for Encoder
    drop(self) = print("closing")

open() -> Result[Encoder, string] = Ok(Encoder(null))

print("ready")
```

```error
'open' hands back 'Encoder' by value, inside 'sysl.Result[Encoder, string]', and 'Encoder' has a 'Drop' — a type with a destructor is a reference type, and building one makes a '&Encoder'. Write '&Encoder' in its place
```

**The rule is the type at every level that owns a resource, not the outermost one**, so a field is
asked as surely as a result is:

```sysl
struct Thing
    n: int

impl Drop for Thing
    drop(self) = print("drop", self.n)

struct ByValue
    inner: Thing

print("ready")
```

```error
field 'inner' of 'ByValue' holds 'Thing' by value and 'Thing' has a 'Drop' — a type with a destructor is a reference type, and building one makes a '&Thing'. Write '&Thing' in its place
```

**A copy is never made into a second owner.** The one by-value value of the type a program still
sees is a copy — a method's `self`, or `*r` read out of a reference. It may be read, and a method may
take it, but it may not be boxed: a `&Thing` made from it would put the one resource in two boxes,
and its destructor would run once for each:

```sysl
struct Thing
    n: int

impl Drop for Thing
    drop(self) = print("drop", self.n)

hold(r: &Thing)
    val c: &Thing = *r
    print(c.n)

hold(Thing(1))
```

```error
this makes a '&Thing' out of a copy of a 'Thing' value, and 'Thing' has a 'Drop' — the copy would be a second owner of the resource, and its destructor would run once for each. Only building one makes a '&Thing': hand over the reference that already holds it
```

**A generic's signature is asked at the instantiation.** `id[T](x: T) -> T` called with `*r` binds
`T` to the type by value, and the call is refused naming the parameter — `'id' binds its 'T' to
'Thing' by value…` — rather than anything inside `id`, which the caller did not write. Called with
the reference, `T` is `&Thing` and nothing is asked; and a signature that reaches `T` only through a
reference — `keep[T](x: &T) -> &T` — may bind it to `Thing` itself, since nothing it declares holds
one by value. A generic type is asked the same way, by what its fields hold: a `struct Box[T]` whose
field is a `&T` holds a share, so `Box[Thing]` may be bound, while `Buf[Thing]` and `Option[Thing]`
hold the value itself and are refused.

**Its body is asked too, once per instance, at the types it was made at** — a library's generic
bodies and a trait's default read at the implementing type among them. A signature that reaches `T`
only through a reference still lets the body copy one out, and at `T = Thing` that copy is a second
owner of the handle. The line the refusal points at is the same for every instance, so the message
says which instance it is and the call that asked for it, one link per generic in between:

```sysl
struct Thing
    n: int

impl Drop for Thing
    drop(self) = print("drop", self.n)

peek[T](r: &T) -> int
    val copy = *r
    0

print(peek(&int(1)))
print(peek(Thing(2)))
```

```error
'copy' holds 'Thing' by value and 'Thing' has a 'Drop' — a type with a destructor is a reference type, and building one makes a '&Thing'. Write '&Thing' in its place (in 'peek[Thing]', called from
```

At `T = int` the same body is fine. An instance whose own signature was refused at its call says
nothing more: everything inside it follows from that one complaint.

**`@consumes` is unchanged**, and there is no use-after-move: a `&T` is shared and counted exactly as
every other reference is.

**A destructor needs an allocator**, since building one is an allocation: a `@no_alloc` module that
builds one is refused with `a reference needs an allocator`, and on a bare-metal target the box comes
from the target's `malloc` like any other. Where there is no allocator, `defer` is the tool.

**An error path needs closing by hand.** Where a C call hands back a handle *and* fails — a refused
connection whose connection object still has to be freed — the `Result` is an `Err`, no value ever
owns the handle, and no box is ever made for a destructor to hang off. Read what you need from it and
close it where it stands.

**A handle that hands out a second value does NOT need a second type, and reaching for one is the
mistake this paragraph exists to head off.** A method taking `self` has a copy and cannot name the box
it was called through, so building a value around the same resource there would hand out a second
*owner* and the destructor would run twice — boxing `self` itself is refused for that reason. The obvious escape is to split the type — a private
handle carrying the `Drop`, and a copyable value holding a `&` to it — and it works, and it is
unnecessary: [a `&self` method](/reference/declarations/#a-self-method-may-keep-what-it-was-called-on)
is handed the box itself, so the value it constructs takes a share rather than a second owner. One
type, one destructor, and the resource outlives everything that kept a share of it. Two of the org's
own bindings carried the split before anybody checked.

**It is not called for a value in a reference cycle**, whose count never reaches zero. That is not a
new consequence of this feature but the existing cost of counting rather than collecting — the
*storage* already leaks there. A `weak T` is what breaks a cycle.

**It is not called for module storage when the program ends.** Storage that lasts the whole run is
never let go of, so its count never reaches zero. There is no exit pass and there will not be one: a
process exiting is what returns what it held, and running destructors at exit is the feature C++ has
spent decades regretting, because the order two statics come apart in has no good answer. What it
costs is that a buffered writer held in a static is not flushed at exit; the program flushes it.

**No order is promised among siblings.** Two values that die together — the elements of a slice, the
fields of a struct — come apart in an order that follows the teardown worklist, which today makes it
last-to-first. A program that needs one before another has to say so.

## Crossing a concurrency domain

A `&T` permits aliasing and mutation through any reference, which is safe **within one concurrency
domain** and nowhere else: its refcount is non-atomic, and two threads touching it would race. So a
`&T` may not leave its domain, and crossing one **copies** by default — which is what process IPC does
anyway, and what keeps the ordinary path free of atomics.

The exception is **`&sync T`**, whose refcount is atomic. It is a distinct type from `&T` with no
conversion either way, and which one an object is is chosen where it is allocated:

```sysl
struct Cell
    n: int

send(c: &sync Cell) -> int = c.n

var c: &Cell = Cell(1)

print(send(c))
```

```error
'&Cell' and '&sync Cell' are distinct types, and neither converts to the other: a count is atomic or it is not from the moment the object is allocated, and a conversion would put an ordinary retain beside an atomic one. Allocate Cell as a '&sync Cell' where it is constructed
```

Taking a share of a `&sync T` is a relaxed increment, since a count you already hold cannot reach
zero underneath you. Giving one back is a decrement with release ordering, and the share that takes
the count to zero acquires before destroying the object, so the thread that frees it sees every
other thread's writes to it. The acquire is a `fence acquire` — except in a build whose
`SYSL_EXTRA_CFLAGS` ask for `-fsanitize=thread`, where it is an acquire load of the count instead.
The two order the same things, but ThreadSanitizer does not model a standalone fence, and with it
every `&sync` object freed on another thread than its last user's would be reported as a race.

A conversion would put an ordinary retain beside an atomic one, so the count is atomic or it is not
from the moment the object exists. **`&sync T` makes the *reference* safe to share, not the object
safe to mutate** — that still wants a `Mutex`.

A `&T` held in a `@thread_local` is *not* a domain crossing and needs no `&sync`, the storage
belonging to one thread by construction.

### Module storage reached from another domain

A closure erased into a `&sync Fn` is asked about what it captures — and equally about the **module
storage it reaches**, however many calls down. A module `var` is one object the whole program shares,
so a count in it that is not atomic is the race a capture would have been:

```sysl
struct Cell
    n: int

static var shared: &Cell = Cell(1)

bump()
    shared.n += 1

val job: &sync Fn() -> unit = () -> bump()

job()
print(shared.n)
```

```error
a closure shared between two domains may be called from either, so every count it reaches has to be atomic — but the module storage 'shared' it reaches, which 'bump' reads, is a '&Cell', whose count is not. Hold it as a '&sync Cell' ('06')
```

Hold it as a `&sync Cell` and the program prints `2`. What may be reached is what may be captured: a
scalar, an `Atomic`, a `&sync T`, a raw pointer, and anything in `@thread_local` storage, which is
one copy per thread; a `val` laid down as constant data is not asked either. Two kinds of body are
held to it — whatever is erased into a `&sync` object (a closure, a named function read into a
`&sync Fn`), and an interrupt handler, which the processor enters on top of whatever was running —
one written in the `interrupt` convention, or an ordinary function marked
[`@domain(interrupt)`](/reference/attributes/#domain-interrupt-a-function-entered-on-top-of-whatever-was-running),
the shape a vector table written in assembly calls. **A bare `@export` is not**: it is how C enters
sysl at all, almost always on the program's one thread, and nothing says a second domain exists until
`@domain` does:

```sysl build=c target=aarch64-freestanding
struct Cell
    n: int

var shared: &Cell = Cell(1)

@domain(interrupt)
@export("on_timer")
on_timer()
    shared.n += 1
```

```error
'on_timer' is entered in the 'interrupt' domain, on top of whatever was running, so every count it reaches has to be atomic — but the module storage 'shared' it reaches is a '&Cell', whose count is not. Hold it as a '&sync Cell' ('06')
```

Without the annotation the same archive builds, and the race it describes is the reader's to know
about.

**A `val` over a slice literal is constant data**, so it crosses. Its elements are laid down as an
array in the object file and the view over them has no owner, exactly as a string literal's view has
none — there is no count in it to race on, and nothing runs at startup to build it:

```sysl
static val colors: []const string = ["red", "green"]

known(c: string) -> bool
    for x in colors
        if x == c then return true

    false

val job: &sync Fn() -> unit = () -> print(known("green"))

job()
```

```output
true
```

The elements have to be laid down themselves — literals, and the arrays, structs and views built
from them. A `val` over a call is filled at startup with a counted owner like any other, and is
refused as a `var` would be. A `[]T` without `const` can be written through, so its array goes in
writable data, and a closure shared between domains may reach it only where its elements hold no
count: a store into a `[]string` puts a heap string there.

### A guarded value crosses with its lock

A `Map` shared through a count that is not atomic may not be reached from another domain — **but a
`Mutex[Map[...]]` may**, whatever it holds. `Mutex[T]` is declared `@guards(inner)`, over a
`Guarded[SleepLock, T]` that is itself `@guards(value)`: its own members are the only way to the `T`,
and each takes the lock first, so sharing the lock does not share the value. A guarded field that is
itself a guarding struct is looked through, so what leaves `Mutex.with` is held to the rule the `T`
inside sets:

```sysl
import sysl.container.{map, Map}
import sysl.threads.Mutex

static var names: Mutex[Map[string, int]] = Mutex.new(map())

lookup(s: string) -> int = names.with((t) -> t.get(s).unwrap_or(0))

val job: &sync Fn() -> unit = () -> print(lookup("a"))

job()
```

```output
0
```

**What leaves through `with` or `try_with` is what is held instead**, at the call: once the lock is
let go, the value it answered belongs to whoever holds the lock next as much as to the caller, so it
is held to the rule a capture is. An `int` or a `bool` passes on its type, and so does a value built
fresh inside the closure — a string literal, which is immortal, or a `.copy()` of a string, which
gives the bytes an owner of their own. A string read out of the map still shares its count with the
one inside, and is refused:

```sysl
import sysl.container.{map, Map}
import sysl.threads.Mutex

static var names: Mutex[Map[string, string]] = Mutex.new(map())

print(names.with((t) -> t.get("a").unwrap_or("none")))
```

```error
'sysl.threads.Mutex.with' answers a value out of the 'value' it guards, and once the guard is let go that value is shared with whoever holds it next, so every count it reaches has to be atomic — but this answers a 'string', which may share its bytes' count with the guarded one. Answer a fresh string instead — '.copy()' inside the closure gives the string an owner of its own, and a literal is immortal
```

`t.get("a").unwrap_or("none").copy()` is accepted, and so is the `Map` itself held as a `&sync
Mutex[...]` and captured.

**A value the closure builds itself is fresh too** — a `Buf` it makes and fills with copies of what
it guards, a struct or a variant holding one, or a call handed nothing that shares a count. A local
stays fresh while everything written into it is fresh and it goes nowhere but out through the answer:
calling the `Buf`'s own members on it, reading a length or an element out of it, and looping over it
are all it may meet. A `.copy()` of a `Buf` is fresh where its elements hold no count; one of strings
or of `Buf`s copies only the handles, and is refused:

```sysl
import sysl.buf.{Buf, buf}
import sysl.threads.Mutex

static var log: Mutex[Buf[u8]] = Mutex.new(buf())

snapshot() -> Buf[u8] = log.with((b) ->
    var copy: Buf[u8] = buf()
    copy.extend(b.view())
    copy)

log.with((b) -> b.extend([1, 2, 3]))
print(snapshot().view())
```

```output
[1, 2, 3]
```

**The same local stored anywhere else as well is refused**, since the guarded value, or whatever it
was handed to, would then share it — pushed into the guarded value, bound to a second name, handed to
a function, its address taken, or written over with something captured:

```sysl
import sysl.buf.{Buf, buf}
import sysl.threads.Mutex

static var nest: Mutex[Buf[Buf[u8]]] = Mutex.new(buf())

print(nest.with((b) ->
    var fresh: Buf[u8] = buf()
    b.push(fresh)
    fresh).len())
```

```error
but this answers a 'sysl.buf.Buf[byte]', whose 'core' reaches a '&sysl.buf.BufCore[byte]', whose count is not atomic. Answer what the caller needs from it — a count, a flag, a '.copy()' of a string, a value built fresh inside the closure — rather than the value itself
``` A struct of a program's own opts in the same way — `@guards(items)` above
it names a field that is `private`, and every member that answers something is held to the rule at
its calls. **What is not checked** is a closure that *stores* something read out of the guarded
value where it outlives the call — through a pointer it captured, say: nothing follows a counted
value from the field to the store, so that one is the reader's to know about.

### A table whose every record has its own lock

A buffer cache, an inode table, a page cache: each record is locked on its own, so two processors
touch two records at once. **`sysl.sync.Guarded[L, T]` is that record** — a value, not a box, with
the lock laid inline beside the `T` — and it is declared `@guards(value)` exactly as `Mutex[T]` is.
The lock is any type implementing the `Lock` trait (`acquire`, `try_acquire`, `release`, each taking
`*self`): a `SpinLock`, a `sysl.threads.SleepLock`, or a lock a package writes for itself.

**Nothing new decides that the table crosses: an array is its elements**, so sharing a
`[4]Guarded[...]` shares four locks, and sharing a lock does not share what it guards. The records
may hold a `Buf`, a string, a `&T` — whatever they hold is reached only through `with`:

```sysl
import sysl.buf.{Buf, buf}
import sysl.sync.{Guarded, SpinLock}

struct Item
    hits: int
    log: Buf[int]

static var table: [4]Guarded[SpinLock, Item] = [
    Guarded.new(SpinLock(0), Item(0, buf())), Guarded.new(SpinLock(0), Item(0, buf())),
    Guarded.new(SpinLock(0), Item(0, buf())), Guarded.new(SpinLock(0), Item(0, buf()))]

touch(i: int) -> int = table[i].with((r) ->
    r.hits += 1
    r.log.push(i)
    r.hits)

val job: &sync Fn() -> unit = () -> print(touch(2))

job()
job()
```

```output
1
2
```

The same table of bare `Item`s is refused, naming the `Buf` that two domains would share:

```sysl
import sysl.buf.{Buf, buf}

struct Item
    hits: int
    log: Buf[int]

static var table: [2]Item = [Item(0, buf()), Item(0, buf())]

touch(i: int) = table[i].log.push(i)

val job: &sync Fn() -> unit = () -> touch(1)

job()
```

```error
the module storage 'table' it reaches, which 'touch' reads, holds a 'sysl.buf.Buf[int]' in its 'log', which is shared through a count that is not atomic
```

**`with` hands the closure the record in place** — its address, lent for the call — so a field
written inside it is a store into the table: the record is not copied and nothing it counts is
retained. What leaves `with` (and `try_with`) is held to the rule `Mutex.with`'s result is, at the
call, so the `Buf` itself may not come out:

```sysl
import sysl.buf.{Buf, buf}
import sysl.sync.{Guarded, SpinLock}

struct Item
    log: Buf[int]

var cell = Guarded.new(SpinLock(0), Item(buf()))

val b = cell.with((r) -> r.log)
```

```error
'sysl.sync.Guarded.with' answers a value out of the 'value' it guards, and once the guard is let go that value is shared with whoever holds it next, so every count it reaches has to be atomic — but this answers a 'sysl.buf.Buf[int]'
```

A slice or a `Buf` of guarded records is asked the same way: what is in the way is the view's or the
`Buf`'s own count, not the records, so one reached through a `*T` or held where it is constant data
crosses, and one shared through its own ordinary count does not.

**On a freestanding target the table is constant data**: `Guarded.new` is a `const` function, and
an instance of a generic `const` function is run while compiling like any other, so a kernel writes
the table as a module `var` and the image carries it laid out — every lock free, every record its
initial value — with nothing run before `main`:

```sysl
import sysl.sync.{Guarded, SpinLock}

struct Inode
    refs: int
    mode: u16

var inodes: [64]Guarded[SpinLock, Inode] = [Guarded.new(SpinLock(0), Inode(0, 0)); 64]

@export("iget")
iget(i: int) -> int = inodes[i].with((n) ->
    n.refs += 1
    n.refs)
```

A record whose initializer is not constant — a `buf()` the heap fills, say — is still refused there,
with the reason the call could not be run.

### A frozen buffer crosses

A `Buf` and a view are shared through a count that is not atomic, so neither may sit in a `&sync`
object — which leaves read-only heap data, decoded pixels or a list of shapes, behind a lock it does
not need. **`buf.freeze()` takes a buffer's elements and answers a `&sync Frozen[T]`**: one atomic
count on the whole buffer, and nothing that writes it. It goes wherever a `&sync` reference goes — a
field of a `&sync` struct, a capture of a `&sync Fn`, the state handed to `spawn`:

```sysl
import sysl.buf.{Buf, buf, Frozen}

struct Texture
    width: usize
    pixels: &sync Frozen[u8]

var b: Buf[u8] = buf()

for i in 0..<6
    b.push(u8(i * 40))

val tex: &sync Texture = Texture(3, b.freeze())

val job: &sync Fn() -> unit = () ->
    var sum = 0

    for p in tex.pixels
        sum += int(p)

    print(tex.pixels[4], tex.pixels.len(), sum)

job()
print(b.len())
```

```output
160 6 600
0
```

**The buffer is left empty**, through this name and every other name of it: the elements are copied
once into storage sized to them, so nothing that still holds the buffer — or a view taken of it
earlier — can reach the frozen storage. What a frozen buffer offers is `len`, `is_empty`, `f[i]`
(checked, as a `Buf`'s is), `for x in f`, and `view()`, a `[]const T`. A view keeps the buffer alive
through a small box of its own, counted the ordinary way, which holds one atomic share — so it is kept
and handed on like any other view, and costs one allocation where `f[i]` and `for` cost none. Nothing
writes one:

```sysl
import sysl.buf.{Buf, buf, Frozen}

var b: Buf[int] = buf()

b.push(1)

val f = b.freeze()

f[0] = 2
```

```error
assignment needs a variable, a field, an element, or a dereference — something with an address
```

`f.push(2)` is refused as a method `Frozen` does not have, and a store through `view()` is refused as
a store through any `[]const T`.

**Reading a local after `freeze` is refused**, since all it can find is the empty buffer: `freeze`
is [`@consumes`](/reference/attributes/), so on every path after the call the local may not be read
until it is assigned a new value. A field, or module storage like `b` above, is not followed, and
reads as empty:

```sysl
import sysl.buf.{Buf, buf}

pixels() -> usize
    var b: Buf[u8] = buf()

    b.push(1)

    val f = b.freeze()

    f.len() + b.len()

print(pixels())
```

```error
'b' is read here after 'b.freeze()' took everything it held
```

**A frozen buffer shares exactly what its elements share.** Every thread reading one reads its
elements, so the element type is held to the rule a `&sync` struct's fields are: a scalar, a `&sync
U` (a `&sync` trait object among them), a raw pointer, another frozen buffer, and arrays and structs
of those. Freezing a buffer of plain references is refused at the call, naming the element:

```sysl
import sysl.buf.{Buf, buf}

struct Cell
    n: int

var b: Buf[&Cell] = buf()

b.push(Cell(1))

val f = b.freeze()
```

```error
'&sync sysl.buf.Frozen[&Cell]' may be reached from two domains at once, so every count inside it has to be atomic — but it holds a '&Cell', whose count is not. Hold it as a '&sync Cell' ('06')
```

Held as a `Buf[&sync Cell]`, it freezes. A `Buf[string]` or a `Buf[Buf[u8]]` is refused the same way,
for the count each element carries. **Freezing allocates** — the storage and the box — so a
`@no_alloc` module may not call it, and a freestanding program freezes where it has a heap.

### `@crossing` — where the rule is asked

The rule above says *what* may cross. **`@crossing` says where**: it is the annotation a facility
writes above the function that hands a value to another domain, naming the parameters it hands it
through. `sysl.threads.spawn` is declared with one, and so is any package binding a scheduler
of its own:

```sysl
struct Cell
    n: int

struct State
    cell: &Cell

@crossing(s)
start(s: *State) -> int = s.cell.n

var c: &Cell = Cell(1)
var st = State(c)

print(start(&st))
```

```error
what 's' of 'start' points at reaches another concurrency domain, so every count inside it has to be atomic
```

A **`*T` parameter is looked through**, and that is the whole of what the annotation buys. A raw
pointer carries no refcount *of its own*, which says nothing about the object at the far end — and
the object at the far end is what crossed. Everything else is asked about the parameter's own type.
Hold the cell as a `&sync Cell` and the same program compiles.

It costs nothing at run time: no code is emitted, no signature moves, and a program that satisfies it
is the program it would have been without the line. What it adds is the refusal, made at each call —
so a generic facility is asked afresh for every set of type arguments, exactly as a `&sync Box[T]` is.

**Where a body shows the boundary, nobody writes the annotation.** A parameter whose function hands
it to another one that crosses — `spawn`'s `arg`, a channel's `send`, a `@scoped` member's callable —
crosses too, and so does a value (not a `*T`, which a `&sync` place holds as itself) it stores in a
`&sync` place. Every call of that function is held to the rule exactly as a written `@crossing`
holds it, however many functions deep the boundary is. What crosses is the parameter itself, its
address, an element or a view of it — the address of one of its *fields* hands over that field, whose
own type the inner call asks:

```sysl
import sysl.threads.*

struct Cell
    n: int

struct State
    cell: &Cell

work(s: *State)
    print(s.cell.n)

start(s: *State) -> Option[Thread] = spawn(&work, s)

var st = State(Cell(1))

start(&st).unwrap().join()
```

```error
what 's' of 'start' points at reaches another concurrency domain, so every count inside it has to be atomic
```

**A written `@crossing` is for the boundaries no body shows**: an `extern`, a function pointer, a
closure called through its type, a trait's requirement. `sysl.threads.spawn` is one, since its body
hands `arg` to C; a sysl function wrapping a C scheduler says `@crossing` the same way, and a
function wrapping *that* one says nothing.

**A member may carry it, and so may the two frames.** `@crossing`, `@reads` and `@writes` are the
annotations that are about a **parameter**, which a member has exactly as a free function does — so
they are the three a method, a property or an associated function may be written above, and the only
three:

```sysl
struct Chan
    open: bool

    @crossing(v)
    send(*self, v: *int) -> int = v[0]

var c = Chan(true)
var n = 7

print(c.send(&n))
```

```output
7
```

Everything else says something about a free function or about a type — what `sysl test` calls, what
recurses, what a symbol names, how fields are laid out — and none of that is something a member
supplies:

```sysl
struct P
    v: int

    @tailrec
    take(self, n: int) -> int = n

print(P(1).take(2))
```

```error
the only annotations a member may carry are the ones about a parameter
```

The three are refused above a **field** and above a **variant** for a different reason, which the
sentence says: neither has parameters for one of them to name.

### A view crosses as a copy

**A parameter that crosses by value is handed a copy of every view it holds**, at every depth: a
`[]T`, a `string`, a `[][]u8`, a struct or an enum with views in it. The copy is
storage with a count of its own, made where the callee reads the parameter, so the caller's count
never reaches the other domain and the caller may go on writing to what it handed over, or drop it.
What the rule then asks is what is left once the views are copied — the elements are still the
elements, so a view of `&T`s is refused as a `&T` is, and a view of `&sync T`s crosses:

```sysl
@crossing(grid)
first(grid: [][]u8) -> u8 = grid[1][0]

var a: [1]u8 = [7]
var b: [1]u8 = [8]
var g: [2][]u8 = [a[..], b[..]]

print(first(g[..]))
```

```output
8
```

A `*T` parameter is looked through as before and never copied: what it points at is the caller's,
and the thread reads it there. **The copy allocates**, so a call that hands a view to a parameter
crossing by value is refused in a module that declared `@no_alloc`:

```sysl
@no_alloc

@crossing(v)
take(v: []u8) -> usize = v.len

var b: [2]u8 = [1, 2]

print(take(b[..]))
```

```error
handing 'v' of 'take' a copy of every view in it needs an allocator, and this module declared '@no_alloc'
```

`sysl.threads.Channel[T]`'s `send` and `try_send` are `@crossing(value)`, which is how a channel
[takes a view](/library/threads/#a-view-goes-in-as-a-copy): the channel's own two lines, so no code
that sends ever writes one. A function that hands its own parameter on to `send` is inferred to cross
by value, and copies as `send` does.

`sysl.threads.Channel[T]` is what this changed. Its `send` and `try_send` were free functions
taking the channel by address, because the annotation had to be written on a wrapper a caller went
through; they are methods now, so a channel's transfers and its queries read alike.

## A borrowed pointer cannot escape

A `*T` is a type, so once one exists it can be carried anywhere — which is exactly wrong for the
address of a local, which is good only until its function returns. Nothing in the type says how long
the storage it points at lives. What the compiler can see instead is what every function **does** with
each pointer it is handed, so it works that out, and nothing is written for it.

**A function keeps a `*T` parameter** when it lets it outlive the call:

- it stores it somewhere the call does not own — a field reached through a pointer, module storage, a
  buffer's slot, or a box or a closure that is stored there;
- it passes it to another function's parameter that keeps it.

**The address as an integer is the address**: `usize(p)` handed back directly, and a call's answer
that is one, is followed as `p` would be. `usize(p)` stored somewhere the call does not own, or
handed to an integer parameter that may store it so, is a number **until a `ptr_cast` may turn it
back into a pointer** — the rule a frame's own address as an integer is held to (below), and the
refusal names the function holding that `ptr_cast`. An integer anything more is done with — put in a
local, converted again, `usize(p) + 8` — is a number, and keeps nothing.

The answer is read out of the body, followed into every callee, and settled over recursion: a
recursive function keeps a parameter when one of its paths does. Then **one rule, at the call**:

> The address of storage that is not the program's to give away — a local, a parameter's place, a
> field of a value in the frame, a value made in place — may not be passed to a parameter that keeps it.

```sysl
struct Registry
    last: *int

    remember(*self, p: *int)
        self.last = p

run(r: *Registry)
    var n = 5
    r.remember(&n)
```

```error
'remember' keeps its parameter 'p' (it is stored in 'self.last'), and '&n' is the address of a local of 'run'
```

A function that reads and writes through what it is handed, and passes it to callees that only use it,
keeps nothing, and the address of anything may be passed to it — **every `*self` method called on a
local is checked this way**:

```sysl
struct Account
    balance: int
    edits: int

    deposit(*self, n: int)
        self.balance += n
        self.edits += 1

add_ten(n: *int)
    *n += 10

twice(p: *int)
    add_ten(p)
    add_ten(p)

run() -> int
    var acct = Account(100, 0)
    var n = 1

    acct.deposit(5)
    twice(&n)
    twice(&acct.balance)
    acct.balance + n + acct.edits

print(run())
```

```output
147
```

```sysl
static var last: *Node = null

struct Node
    next: *Node

    remember(*self)
        last = self

run()
    var node = Node(null)
    node.remember()
```

```error
'remember' keeps its parameter 'self' (it is stored in 'last'), and 'node' is a local of 'run'
```

**Where it is stored decides, not only that it is.** A pointer stored into what it points at — a node
linked to itself, a struct whose field points into its own buffer — ends exactly when that storage
does, so it is not kept — and so is the address stored as an integer, `m.src = usize(&m.buf[0])`,
which is held exactly where the pointer would be. One stored into what *another* parameter points at is the caller's to judge:
if that argument points into the caller's own frame, the pointer stays in the frame too. That is what
makes an out-parameter, a pair of linked nodes or a list built in a local array work as written:

```sysl
struct Node
    next: *Node
    v: int

link(a: *Node, b: *Node)
    a.next = b

put(out: **Node, v: *Node)
    *out = v

run() -> int
    var nodes: [3]Node = [Node(null, 1), Node(null, 2), Node(null, 3)]
    var last: *Node = null

    link(&nodes[0], &nodes[1])
    link(&nodes[1], &nodes[2])
    put(&last, &nodes[2])
    nodes[0].next.next.v + last.v

print(run())
```

```output
6
```

The same address written through an out-parameter that points into module storage is kept, and so
is the address of a function's own local written through any out-parameter at all — or through a
pointer read out of one, `s.vm.con = &local`, since that is the caller's storage, not the frame's:

```sysl
leak(out: **int)
    var n = 1
    *out = &n
```

```error
'&n' is the address of a local of 'leak', and it is stored in '*out', which outlives 'leak' — store the value itself, or storage that lasts
```

### Taken back before it returns

**A store undone on every way out is not kept.** A place written over with something else before the
function returns — in the body, in a `defer`, or by a callee whose own body writes it on every way out
— no longer holds the pointer once the call is over. This is how a sink is lent to a module for the
length of a call:

```sysl
static var spare = 0
static var held: *int = &spare

store(p: *int)
    held = p

clear()
    held = &spare

run() -> int
    var n = 41
    store(&n)
    val seen = *held + 1
    clear()
    seen

print(run())
```

```output
42
```

**The place may be reached through a pointer**, spelled out (`s.vm.con`) or through a local that
holds it (`val vm = s.vm`), so long as the store and the overwrite are known to reach the same
storage: a local bound once and never written holds one pointer, and a pointer read out of memory is
the same one until a call, or a store of a pointer of its type, may have changed it. A console lent
to a machine for one evaluation is put back that way:

```sysl
struct Out
    n: int

struct Con
    out: *Out

struct M
    con: *Con

struct Session
    vm: *M
    home: *Con

static var sink = Out(0)
static var home = Con(&sink)
static var m = M(&home)

eval(s: *Session, out: *Out)
    val vm = s.vm
    var con = Con(out)

    vm.con = &con
    vm.con.out.n += 1
    vm.con = s.home

run(s: *Session) -> int
    var o = Out(0)

    eval(s, &o)
    o.n

var s = Session(&m, &home)

print(run(&s))
```

```output
1
```

A way out that skips the clear leaves it held: an early `return`, a `?`, a call that never comes back
such as `panic`, a `break` past it, or one arm of a `match` without it. Writing a *different* place —
another global, another field, the same field of another instance — takes nothing back:

```sysl
static var spare = 0
static var held: *int = &spare

store(p: *int)
    held = p

clear()
    held = &spare

run(c: bool)
    var n = 1
    store(&n)
    if c then return
    clear()
```

```error
'store' keeps its parameter 'p' (it is stored in 'held'), and '&n' is the address of a local of 'run'
```

Each way of keeping is found wherever it is, however many calls down:

```sysl
import sysl.buf.{Buf, buf}

static var all: Buf[*int] = buf()

collect(p: *int)
    all.push(p)

relay(p: *int) = collect(p)

run()
    var n = 1
    relay(&n)
```

```error
'relay' keeps its parameter 'p' (it is passed to 'collect', which keeps it), and '&n' is the address of a local of 'run'
```

Storage that outlives the call — module storage, a box's — is the program's to give away, and so is
storage reached through a pointer the function was handed. **The entry file's own statements** are
the one body passed over: their frame is the program's, laid down before anything else runs and given
back only as it ends.

### Handing it back is not keeping it

A function may answer the pointer it was handed, or the address of something inside what it points
at: `field(s: *S) -> *int = &s.y` keeps nothing, and **its answer is lent for as long as the argument
is**. The caller follows the answer as it follows the argument — keeping it is keeping the argument,
and a function may not hand back an answer made of its own frame:

```sysl
struct S
    x: int
    y: int

field(s: *S) -> *int = &s.y

run() -> int
    var s = S(1, 2)
    val p = field(&s)

    *p += 10
    s.y

print(run())
```

```output
12
```

```sysl
struct S
    x: int
    y: int

field(s: *S) -> *int = &s.y

run() -> *int
    var s = S(1, 2)

    field(&s)
```

```error
'field(&s)' is returned, but it holds the address of a local of 'run', whose storage ends when 'run' returns
```

**Only a value that can hold an address is made of one.** An `int`, a `bool`, a float, a bare enum
or a struct with no pointer in it answers nothing about where its argument lived, so `Ok(n)` after a
C function filled `&n` is an ordinary value. A C function's pointer answer is taken to be made of its
pointer arguments that could hold what it points at — one to the same type or to a struct holding
it, and bytes from bytes, as `strchr`'s is — and not of an out-parameter of another type, so a handle
a C library allocated is not made of the `*i32` it reported an error code through, nor of a context
it was handed as bytes. **An integer that is the address is made of it**: `address(p: *int) -> usize
= usize(p)` answers its argument as an integer, and `ptr_cast(usize(p))` is still `p`. An integer
computed from one (`ptr_cast(usize(p) + 8)`) is not followed: that is the raw tier, and writing it
takes a `ptr_cast`.

**A frame's own address as an integer is a number until it is turned back into a pointer.**
`usize(&marker)` remembered in module storage, or handed back, is how a program measures its stack,
and as a number it outlives nothing:

```sysl
var top: usize = 0

mark()
    var marker = 0

    top = usize(&marker)

here() -> usize
    var marker = 0

    usize(&marker)

main()
    mark()
    print(here() > 0 && top > 0)
```

```output
true
```

What would reach a frame that has ended is that number made a pointer again. So every integer the
program moves is followed through the places it is stored — a module variable, a field of a struct of
its type, what a pointer to its type points at, a local, a parameter, an answer — to every `ptr_cast`
of an integer, and the frame's address leaving it is refused where a `ptr_cast` may turn it back.
The refusal names that `ptr_cast`'s function; a store taken back on every way out is not kept, as
for a pointer:

```sysl
import sysl.raw.ptr_cast

var top: usize = 0

mark()
    var marker = 7

    top = usize(&marker)

peek() -> int
    val p: *int = ptr_cast(top)

    *p

main()
    mark()
    print(peek())
```

```error
'usize(&marker)' holds the address of a local of 'mark', and it is stored in 'top', which outlives 'mark' and 'peek' turns back into a pointer with 'ptr_cast' — store the value itself, or storage that lasts
```

**What a call answers is made of the integers its body answers, and of nothing else it was handed.**
`pass(n: usize) -> usize = n` answers its argument, so the caller's address comes back out of the
call and is followed on from there — to that caller, not to every caller of `pass`. A parameter the
body never answers stays inside the call, and arithmetic makes a number: `n + 8` is not the address,
as `usize(&marker) + 8` is not. Recursion is followed to where it settles. An `extern`'s answer is
made of only what its `@answers` names, and an inline-assembly output of
[nothing it was handed](/reference/inline-assembly/#what-the-compiler-owns) — so a system call's
answer is the kernel's number, even where another call to the same wrapper was handed a local's
address:

```sysl
import sysl.raw.ptr_cast

var store = 41
var top: usize = 0

pass(n: usize) -> usize = n

mark()
    var marker = 7
    val here = pass(usize(&marker))

    print(here > 0)

peek() -> int
    val p: *int = ptr_cast(top)

    *p

main()
    mark()
    top = pass(usize(&store))
    print(peek())
```

```output
true
41
```

Followed this far and no further: a field, a pointee or an element is one place for every value of
its type, which says more than most programs do, and the refusal names the `ptr_cast` it found. An
integer handed through a function pointer, a call through an object, or an enum's payload is not
followed.

A pointer the callee **read out of** what it was handed is somebody else's address, and is the
caller's to keep:

```sysl
struct Node
    v: int

struct Queue
    head: *Node

    first(*self) -> *Node = self.head

static var n = Node(7)
static var kept: *Node = &n

keep(p: *Node)
    kept = p

run()
    var q = Queue(&n)
    keep(q.first())

run()
print(kept.v)
```

```output
7
```

### Where no body is in sight, the call borrows

A call the compiler cannot follow into a body needs an answer all the same, and the answer is the one
C programs already live by — **a pointer handed to a call is borrowed for the call**:

| what is called | what it keeps |
|---|---|
| a sysl function | what its body keeps |
| a method through a trait object | no pointer it is handed — an implementation reached through an object that keeps one is refused where it is written; an object it is handed, a callback among them, is kept wherever any implementation keeps it |
| a closure | nothing — a closure that keeps what it is handed is refused where it is written |
| a function through a pointer | what any function whose address is taken at that pointer's type holds in what another of its parameters points at — one that keeps a pointer anywhere else is refused where it is written, and so is every one whose pointer type C can reach |
| an `extern` | nothing, unless its declaration says [`@keeps(p)`](/reference/ffi/#keeps-a-c-function-that-holds-on-to-a-pointer) |

**What a call with no body answers is asked the same way.** An `extern`'s answer is fresh — storage
the library made, which is made of none of its arguments — unless its declaration says
[`@answers(p)`](/reference/ffi/#answers-a-c-function-whose-answer-points-into-an-argument), and then
it is made of what `p` was handed, as a sysl function's answer is made of what it hands back. An
inline-assembly output is fresh the same way: the compiler does not read the instructions, so what
they leave in a register is made of none of the operands handed in. So an
`opendir` handle may be carried out of the frame that passed it a path on its stack, and a `strchr`
answer into a local's bytes may not:

```sysl
extern "opendir" c_opendir(dir: *u8) -> *u8
extern "closedir" c_closedir(d: *u8) -> int

static var spare: u8 = 0
static var stream: *u8 = &spare

open() -> bool
    var dot: [2]u8 = [u8('.'), 0]

    stream = c_opendir(&dot[0])
    stream != null

if open() then print(c_closedir(stream))
```

```output
0
```

```sysl
@answers(s)
extern "strchr" c_strchr(s: *u8, c: int) -> *u8

static var spare: u8 = 0
static var held: *u8 = &spare

find()
    var text: [3]u8 = [104, 105, 0]

    held = c_strchr(&text[0], 105)
```

```error
'c_strchr(&text[0], 105)' holds the address of a field of 'text', a local of 'find', and it is stored in 'held', which outlives 'find'
```

A default is a promise, so the body behind it is held to it. That is what makes a lock's `with`
safe without an annotation: the closure it calls borrows the address of the value for the call, and a
closure that keeps it is refused where it keeps it, which is the one mistake a returned address could
not prevent — using it after the release:

```sysl
struct Cell
    v: int

    with[R](*self, body: *int -> R) -> R = body(&self.v)

static var spare = 0
static var kept: *int = &spare

var c = Cell(1)

c.with((v) -> *v += 1)
print(c.v)
c.with((v) -> kept = v)
```

```error
a closure borrows what it is handed for the call, and this one keeps its parameter 'v' (it is stored in 'kept')
```

```sysl
trait Bump
    bump(*self, n: *int)

struct Keeper
    held: *int

impl Bump for Keeper
    bump(*self, n: *int)
        self.held = n
```

```error
'bump' keeps its parameter 'n' (it is stored in 'self.held'), but a method of 'Bump' borrows its parameters for the call
```

**Every call through a function pointer is in the program, unless C can reach the pointer.** So a
function whose address is taken may hold what it is handed in what another of its parameters points
at — an interpreter's primitive writing its stack pointer back into the machine — and each call
through a pointer of that type is held to it, as a direct call is: the machine it hands over says
what the stack pointer may point at.

```sysl
struct M
    sp: *isize

type Code = *extern(*M, *isize) -> unit

halt(m: *M, sp: *isize)
    m.sp = sp

var stack: [8]isize
var m: M

m.sp = &stack[0]
val f: Code = &halt

f(&m, m.sp)
print(usize(m.sp) == usize(&stack[0]))
```

```output
true
```

```sysl
struct M
    sp: *isize

type Code = *extern(*M, *isize) -> unit

halt(m: *M, sp: *isize)
    m.sp = sp

var m: M

run(f: Code)
    var local: isize = 0

    f(&m, &local)

run(&halt)
```

```error
a call through '*extern(*M, *isize) -> unit' may reach 'halt', which keeps its parameter 'sp' (it is stored in 'm.sp'), and '&local' is the address of a local of 'run'
```

A pointer type handed to C — an argument to an `extern`, a field of a struct one is handed, a
variable an `extern` declares, or a parameter or answer of an `@export` function — may be called with
anything, so every function whose address is taken at that type is held to the default. **A cast is
not handing it to C**: a function pointer turned into an integer and back, or into another pointer
type, is the raw tier's bargain — it is called through the type its address was taken at, where every
call is still in sight and held to what the function keeps.

**A stored callback keeps what it captured, and nothing else.** A closure is an object, and storing
one — in a field, module storage, a buffer, a trait's implementation — is refused only where the
closure captured an address that is not the program's to give away. One capturing nothing, a number,
a `&T`, a string or a struct by value may be stored anywhere:

```sysl
static var later: &Fn() -> int = () -> 0

install(f: &Fn() -> int)
    later = f

run()
    val k = 2
    install(() -> k + 40)

run()
print(later())
```

```output
42
```

```sysl
static var later: &Fn() -> int = () -> 0

install(f: &Fn() -> int)
    later = f

run()
    var n = 7
    val p = &n
    install(() -> *p)
```

```error
'install' keeps its parameter 'f' (it is stored in 'later'), and '() -> *p' holds the address of a local of 'run'
```

A trait's method borrows a view it is handed the same way, so a view of a local array passed through
an object stays in the frame ([`traits.md § A method borrows what it is handed`](/reference/traits/#a-method-borrows-what-it-is-handed)).

### Released in another context: the primitive says `@scoped`

Some pointers are held past the call and let go of somewhere the compiler cannot follow: a scope's
join, the `deliver` that ends an `await`, a kernel's `wake`. The caller is suspended or still inside
its scope the whole time, so handing such a primitive the address of a local is safe — but the body
stores it, and the rule above would refuse it. **The primitive that gives the guarantee declares it
once, with [`@scoped(p)`](/reference/attributes/#scoped-p-on-a-pointer-held-only-while-the-caller-waits)
on the pointer parameter**, and the compiler trusts it: a call of it keeps nothing it is handed at
`p`, so code calling `block(&local)` or `await read(&buf)` carries no annotation at all. The promise
is the primitive's own — handing `p` on to a callee that keeps it is refused unless that callee says
`@scoped` too.

### A thread holds what it is handed until it is joined

**A `*T` that crosses into another domain is kept**: the thread may go on reading it after the call
that started it has returned. `spawn(&work, &local)` therefore holds `&local` — but only until the
handle `spawn` answers is joined, and `spawn` says so once, with `@joined(arg)`. So a function may
hand a thread the address of its own local **when it joins that thread on every way out**, and nothing
at the call is written:

```sysl
import sysl.threads.*

struct Tally
    n: int

add(t: *Tally)
    t.n = t.n + 1

count() -> int
    var t = Tally(0)

    spawn(&add, &t) match
        Some(h) -> h.join()
        None -> return 0

    t.n

print(count())
```

```output
1
```

The join may be written on the call (`spawn(&add, &t).unwrap().join()`), on a local bound to the
handle, in a `defer`, or in the `Some` arm of a `match` or an `is` test — the `None` way holds no
thread. A function that hands its own parameter to `spawn` and answers the handle passes the join on
to its caller, and one that joins inside keeps nothing at all. **A way out that skips the join is
refused**: an early `return`, a `?`, a `break`, a `panic`, a `match` arm, or a handle thrown away:

```sysl
import sysl.threads.*

struct Tally
    n: int

add(t: *Tally)
    t.n = t.n + 1

count(stop: bool) -> int
    var t = Tally(0)
    val h = spawn(&add, &t).unwrap()

    if stop then return 0

    h.join()
    t.n

print(count(false))
```

```error
'spawn' keeps its parameter 'arg' (the thread it starts holds it until that thread is joined), and '&t' is the address of a local of 'count' — join that thread on every way out of 'count', or hand it storage that lasts
```

**A fan-out is joined by a loop that joins every element.** Handles kept in a local array, a slice
local, a `Buf`, or a field of a local — one at the counter of each turn of a counting loop, or pushed
— are let go of together by a loop over the same collection that joins each element:

```sysl
import sysl.threads.*
import sysl.sync.Atomic

struct Tally
    n: Atomic[int]

add(t: *Tally)
    t.n.add(1)

count(workers: int) -> int
    var t = Tally(Atomic(0))
    var pool: []Option[Thread] = [None; workers]

    for i in 0..<workers
        pool[i] = spawn(&add, &t)

    for h in pool
        if h is Some(th) then th.join()

    t.n.load()

print(count(4))
```

```output
4
```

The joining loop walks the whole collection (`for h in pool`, `for i in 0..<pool.len`) or the same
range as the loop that stored the handles, and joins the element — or the same field of it — on
every turn: directly, or through a `match` or an `is` test whose `None` way holds no thread. A
function that answers the collection passes the joins on to its caller, as it does for one handle.
**A loop that may skip an element is not a release**, and the refusal says why: a `break`, a
`continue`, a `return`, a `?` or a `panic` inside it, a join under a condition, a narrower range, no
such loop at all, a handle stored at an index other than the counter of the loop it is in, or the
collection stored into again before its handles are joined. **A store into an element loses the
handle it held**, and so does one through another name for the same storage — a slice of the
collection, a slice of that, `&pool`, a reference copied, or a second slice parameter that may be the
first — so a join is matched only to the collection the handles were stored through:

```sysl
import sysl.threads.*

struct Tally
    n: int

add(t: *Tally)
    t.n = t.n + 1

count() -> int
    var t = Tally(0)
    var pool: [4]Option[Thread] = [None; 4]

    for i in 0..<4
        pool[i] = spawn(&add, &t)

    for i in 0..<3
        pool[i].unwrap().join()

    t.n

print(count())
```

```error
join that thread on every way out of 'count', or hand it storage that lasts; the loop over 'pool' that joins its elements does not reach every element they were stored in
```

A handle the body cannot name again — one built into a struct literal, one stored through a pointer
or into a heap object — is never matched to its join, so the address of a local handed to it is
refused however the handles are joined later; hand those threads storage that lasts, such as a box
(`&*b`) or module storage. The program's own statements are exempt, as they are from every rule
here: their frame outlives every thread.

## Hazard summary

| segfault source | prevented in the safe subset by |
|---|---|
| use-after-free / double-free | ARC on `&T`; `weak` degrades to `Option` and never dangles |
| null dereference | non-null references; nullable is `Option` |
| out-of-bounds | length-carrying arrays and slices, checked everywhere |
| slice outliving its buffer | the slice's `owner` word retains it; escaping locals are promoted |
| refcount race across threads | `&sync T` is atomic; a `&T` may not cross a domain, checked at a `@crossing` parameter |
| dangling or wild pointer | impossible without `*T` |

Only `*T` opts out, and it opts out visibly.

One hazard is **not** on that list: racing on the *fields* of an object two threads deliberately
share. Preventing that needs proof of exclusive access, which is the thing this language trades away,
so it is answered by a `Mutex` and by convention rather than by the type checker. It takes `&sync` or
`*T` to reach the situation at all, so it is at least as greppable as everything else here.

## The two worlds, one language

- **Application, server, utility:** `T` and `&T`, plus `weak`, slices, and arrays. Safe, pleasant,
  ARC-managed.
- **Kernel, driver, allocator-free:** `T`, `*T`, fixed arrays, slices, and manual `malloc`/`free`. A
  module that never *creates* a `&T` emits no allocation and no allocator dependency, exactly like C,
  and stays bounds-checked wherever it uses arrays and slices.

The boundary is not a convention — it is compiler-enforced through the allocator capability. **What it
gates is allocation, not ownership.** Allocator-free code may hold, pass, copy, and drop a `&T` or a
heap-backed slice that something else created: retain and release are a few instructions, and the free
path goes through the object's own deallocation hook. What it may not do is *make* one. That is what
lets a driver keep the `&Device` its bus manager handed it, and a `no alloc` parser read a heap-backed
slice it was given — neither of which is expressible if ownership stops at the boundary.

---

Next: [arrays and slices](/reference/arrays/) — the two sequence types, and what a view keeps alive.
