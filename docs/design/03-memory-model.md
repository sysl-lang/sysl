# The Memory Model — Three Modes

**Status:** core model decided, including how a reference is created, places and automatic
dereference, recursive types, `null`, slice ownership, what the allocator-free boundary gates,
escape analysis (`05`), and concurrency (`06`). This is the heart of the language; the type,
trait, and standard-library docs all rest on it.

## The guarantee

**If you do not use `*T`, you cannot segfault.** The safe subset — value types, ARC
references, `weak` references, arrays, and slices — is memory-safe: no use-after-free, no null
dereference, no out-of-bounds access, no dangling pointers. The single unsafe primitive is the
raw pointer `*T`, and it is **greppable**: you can audit exactly where a program takes on
C-like risk. In a Minix-style OS that is the kernel's low-level core and the drivers, and
nowhere else — the servers, utilities, and applications never write `*T` and are segfault-proof
by construction.

**One hole in that guarantee is known and open, and it is recorded here rather than left to be
discovered.** `s.bytes` reinterprets a string's three words as a `[]u8` without copying (`04`), so
the view's elements are the string's own storage — and a `[]u8` permits writes. Writing one byte of
a **literal's** bytes writes into read-only memory and kills the process, from a program containing
no `*T` at all. Assigning to one where the view is named — `s.bytes[i] = v` — is refused, which
closes the spelling somebody reaches by accident; `&s.bytes[i]` is left alone, because taking an
address is entering the raw tier on purpose and it is how a string reaches a C function that wants a
pointer and a length. What stays open is the view once it has been **bound to a name or passed on**,
because a `[]T` records nothing about whose elements it views. That missing **read-only view type**
is `07 § Not yet`'s, and
this is its third and sharpest customer — the other two are refusals, where nothing unsound is
built, and this one is a view the language already makes.

## Why runtime safety, not Rust's compile-time safety

sysl gets memory safety from **ARC (automatic reference counting)** at runtime — a small,
predictable cost — deliberately in exchange for deleting the cognitive burden Rust charges:
**no lifetimes, no borrow checker, no move semantics.** A `&T` behaves like a Scala object
reference (alias it, pass it, mutate through it), except it is refcounted instead of
garbage-collected. That trade — small runtime cost for large simplicity — is the language's
reason to exist. It is *easier than Rust* by construction, because the hard part of Rust is
exactly the part sysl does not have.

## The three modes

### `T` — value

A value lives inline: on the stack, in a register, or embedded in an enclosing struct/array.
Assignment and argument passing **copy** the value, exactly like a C struct — there is **no
move semantics and no use-after-move**. If a value contains a `&T` field, copying it increments
that field's refcount, so the copy is independently safe. Value types need no allocator and are
always safe.

### `&T` — reference-counted (ARC)

`&T` is a reference to a heap object managed by ARC. It is:

- **shared and freely aliased** — many `&T` may point at the same object;
- **mutable through any alias** — no borrow checker, no exclusivity rule (Scala/Swift reference
  semantics);
- **non-null** — a `&T` always points at a live object (nullable is `Option[&T]`);
- **automatically managed** — the compiler emits retain/release; the object is freed the moment
  the last strong reference goes away.

**Creating** a `&T` needs a managed allocator (see Capabilities); **holding** one does not.
This is the pleasant default for application, server, and utility code, and — because of the
rule below — it is also what lets a driver hold a reference the bus manager created for it.

### Creating one is ordinary construction

There is **no allocation keyword**. A reference is made by writing the ordinary construction
where a `&T` is expected, and the expected type is what puts the object on the heap:

```
spawn(parent: &Process) -> &Process
    Process(parent, 0)                 // the return type says &Process, so this allocates

var p: &Point = Point(1, 2)            // the annotation says so here
var q = Point(1, 2)                    // no expectation: an ordinary value, on the stack
```

This is Swift, Kotlin, and Scala 3, where `Point(1, 2)` is the whole spelling and the
declaration decides whether the result is a value or an object. sysl chooses per declaration
rather than per type, so the *declaration site* is what carries the choice — but the
construction reads the same either way, which is what principle 3 asks for: no `Rc::new`, no
`.clone()`, no wrapper.

The positions that fix the expectation are the ones that already do so for generic inference:
a declared local type, a parameter, a return type, a struct field, and an enum variant's
payload. Somewhere with no expectation at all, a construction is a value — annotate to say
otherwise. A prefix `&` on a construction was considered as an explicit mark and not taken:
it would collide with address-of, which is a different operation with a different result type.

The rule is about the *types*, not about the syntax: a `T` written where a `&T` is expected
goes on the heap, whatever produced it. An `if`/`match` (and a loop, through its `break`s and
`else`) yields its value through its branches, so the `&T` expectation reaches each branch
rather than the whole expression: every branch is boxed on its own, which is what lets a value
branch and an already-`&T` branch meet at `&T` (`var p: &Point = if c then Point(1, 2) else
q.origin`, where `q.origin` is already a reference).
It is also what lets a scalar be referenced (`var n: &int = 0`) without `int` needing a
constructor of its own. Something that is *already* a `&T` passes through untouched — it is a
reference, not a value looking for a home.

### Who frees it — the deallocation hook

Every ARC heap object carries, alongside its refcount, a **pointer to the function that
destroys it**, installed by whoever allocated it. Release decrements; at zero it calls through
the hook, which releases whatever the payload holds and then returns the storage to the heap
the object came from.

**Teardown is iterative, so depth is bounded.** A destructor releases the references its payload
holds, so destroying the head of a long `&T` chain — a linked list, a degenerate tree — would
recurse one stack frame per node and overflow the stack. It does not: when a count reaches zero
the object is pushed onto a worklist, reusing its now-dead refcount slot as the link, and the
*first* release to hit zero drains the worklist in a loop. Each destructor it runs pushes more
work rather than recursing, so a structure of any depth comes apart in O(1) stack. The worklist
is per-thread in principle; while drops are single-threaded a plain global suffices.

Putting the destructor *behind* the hook rather than inline at each release site is what makes
letting go of a reference **type-erased**: one instruction sequence, no static type. Slices
need exactly that, since a `[]T` gives no clue what type of object its owner points at (`07`).

One word per heap object buys three things:

- **ARC works the same everywhere.** A module that never allocates can still retain and
  release, because the free path calls back into the heap the object came from. Refcount
  operations are a few instructions and depend on no runtime.
- **Several heaps coexist.** A microkernel wants exactly this: the kernel heap, each server's
  heap, and an arena are different allocators, and an object frees itself into the one that
  made it, wherever it is dropped.
- **No boundary rule to learn.** Ownership crosses a `no alloc` edge like any other value.

The cost lands only where the feature is used. Value types, fixed arrays, and `*T` buffers
have no header at all, so kernel code written in the allocator-free subset pays nothing for a
mechanism it never touches.

### `*T` — raw pointer

`*T` is a bare machine pointer, exactly like C: no length, no refcount, no checks, manual
lifetime. Pointer arithmetic, `malloc` / `free`, MMIO addresses, and structure-walking live
here. **It is the only unsafe primitive** — the only way to produce a dangling or wild pointer
— and it needs no runtime. It is how a kernel, a driver, or the allocator's own internals are
written.

## Places: `&`, `*`, and selection

A **place** is something with an address: a local or parameter, a dereference, an **element**, and
a field of any of them. Everything else — a call result, an arithmetic result, a freshly built
struct — is a value with no address to take.

An element carries one wrinkle the other three do not. A slice's elements and a pointer's live
wherever the storage is, which is somewhere the expression naming them is not, so they have an
address whether or not what named them does. That is what makes `rows(g)[i] = v` write through to
the grid rather than into the view the call handed back — the view is a temporary, the buffer it
views is not, and it is the buffer the element is in. An **array's** elements *are* the array, so
they are places exactly when the array is. A string's bytes are never one: writing a byte of UTF-8
is how a string stops being UTF-8, and that is refused as immutability rather than as the absence
of an address.

The hazard on the other side of that rule is worth naming, because it is the one case where the
address outlives what keeps it valid. If the temporary view is the **only** holder of its buffer —
a call that builds a fresh one and hands it straight back — then the buffer is released at the end
of the statement, and `&f()[i]` is a `*T` to freed storage before the next line runs. That is the
unsafe tier behaving as this chapter already says it does: `&` yields a raw pointer, a raw pointer
can dangle, and nothing promotes it (`05`). It is called out here rather than left implicit because
it is the one dangle a *single statement* can produce, where every other one needs the pointer to be
carried somewhere.

- **`&place` yields a `*T`.** A place lives in a frame or inside another object, so there is
  no refcount to take a share of; address-of is C's operator with C's result. Reaching a `&T`
  means being handed one, or constructing one (above). Taking the address of a local is
  therefore inherently in the unsafe tier, which is right: it can dangle, and nothing promotes
  it (`05`).
- **`*p` reads through a `*T` or a `&T`**, and is itself a place, so `*p = v`, `*p += 1`, and
  `(*p)++` all mean what they do in C.
- **Field selection dereferences one level automatically**, on both `*T` and `&T`:
  `p.x` is `(*p).x`, and `p.x = 9` writes through the pointer. This is Go's rule. There is no
  `->`, and reaching through a `**T` is written, since the shorthand stops at one level.
  Selection is the *only* implicit dereference: matching a reference to an enum against its
  variants is `match *e`, the same way Go asks for one on a type switch.

Assignment, compound assignment, and `++`/`--` all take a place, so the same three forms work
on a variable, on a field, and through a pointer with nothing special said about any of them.

## Recursive types

A type may reach itself **through an indirection**, and only through one. `struct Node { value:
int; next: *Node }` is pointer-sized and legal; a struct holding itself by value has no finite
size and is rejected, naming the type. The rule is per cycle rather than per field: a cycle is
legal as soon as one edge on it is a `*T` or a `&T`, so mutually recursive types work as long
as the loop passes through a pointer or a reference somewhere.

## Null

`null` is the absent raw pointer, and it exists **only for `*T`** — there is no null in the
safe subset, where an absent reference is `Option[&T]`. It has no type of its own and takes the
`*T` its context expects, the way a bare `None` takes its type argument:

```
var p: *int = null
var c = Node(3, null)              // the field's type says which pointer
while walk != null do …            // the comparison's other operand says
```

Pointers and references compare with `==` and `!=` — by address, since that is the only
question a bare address can answer — and have no ordering.

## Per-declaration, and why value is the default

Which mode a value uses is chosen **per declaration**, as in C — at each site you write the
value, a pointer, or (new in sysl) a reference. This is deliberately *not* the Swift / Scala /
Kotlin per-*type* split (`struct` value type vs `class` reference type): the per-declaration
form keeps C's per-site flexibility — the same struct type can be held by value in one place,
by reference in another, and by raw pointer in a third — which systems code relies on.

Within that model, **value (`T`) is the unmarked default, and the kernel is why.** A no-alloc
module cannot *make* a reference, so if the *reference* were the bare default, the unmarked
form would be the one spelling that is illegal exactly where value semantics are needed most —
every kernel struct would need a mark and hit "references need an allocator" on its most
natural spelling. Value-as-default keeps the kernel's common case unmarked (`T`), `*T` covers
pointers, and `&T` appears in kernel code only where something else handed it one. It is the
only assignment of the three where the unmarked default is usable in *both* worlds.

**Ergonomics of `&` in application code.** With type inference (Scala-style), `&` appears only
in explicit type positions — function signatures and struct fields — not on locals:

```
val p = spawn(parent)                  // inferred: no annotation, no &
spawn(parent: &Process) -> &Process    // & only at the boundary
```

So references are not scattered through the code; `&` lives at API boundaries, where
`&Process` usefully documents "shared reference, not a copy" — the same place Scala puts its
type annotations. Clean bodies, honest boundaries.

## `weak T` — breaking cycles

ARC cannot reclaim a reference cycle (A holds B, B holds A). The tool for back-references is
**`weak T`**: a non-owning reference that does *not* keep its referent alive. When the last
strong `&T` is gone, the object is destroyed and every `weak T` to it becomes empty. Because a
weak reference may already be gone, **accessing it yields `Option[&T]`** — a live strong reference
or `None` — and the compiler makes you handle the `None`, so a weak reference can never dangle.

Cycles are uncommon, but back-references do occur in systems code — a child's pointer to its
parent, the back-link of an intrusive doubly-linked list, a process's pointer to its parent
process. `weak T` expresses those safely; reach for it only when you have a genuine
back-reference. Needs an allocator.

### A weak reference is made where one is asked for

`weak T` is a **type**, written like the other two modes and in the same places:

```
struct Node
    value: int
    parent: weak Node
    kids: []&Node
end Node
```

There is no operator that makes one, and none is wanted. A `&T` becomes a `weak T` wherever a
`weak T` is what the context asked for — a field of a struct being constructed, an argument, a
declared local, a returned value — which is exactly the rule that already makes a `&T` out of a
`T`. So `&` and `weak` both live at the boundaries a program annotates, and a body stays free of
either.

The conversion goes one way. A `weak T` is not a `&T` and never silently becomes one, because
becoming one is the operation that can fail.

What a weak reference may **not** be made from is a value with nowhere else to live. `weak T` is a
type, so `Node(1)` written where one is expected would be boxed and then weakened — leaving the
weak edge as the object's only holder, and the object dead before the statement ended. That is
refused by name, with the advice to hold it in a `&T` first.

### An empty one is written `None`

A weak reference whose object is gone and one that never had an object are the same state, so they
are spelled the same way:

```
var w: weak Node = None                 // nothing yet
var root: &Node = Node(1, None)         // a node with no parent
k.up = None                             // and a parent forgotten
```

`None` here is not an `Option` — it is the empty value of the weak reference itself, chosen because
it is exactly what `get()` will hand back for one. It is also the **zero value** of `weak T`, so a
struct with a weak field still has one and `var n: Node` is still a declaration. That is the one
place `weak T` parts company with `&T`, which has no zero because there is no such thing as a
reference to nothing.

### Reading one is a question, and it is `get()`

```
match child.parent.get()
    Some(p) -> print(p.value)
    None    -> print("orphaned")
```

`get()` yields `Option[&T]`: a live strong reference, with a count taken for the caller, or
`None` if the object is gone. Nothing else may be done to a `weak T` — no field selection, no
method call, no `==`. Every road to the object goes through the `Option`, which is what makes
"a weak reference never dangles" a fact about the language rather than a promise about the
programmer.

It is spelled with parentheses, unlike `.len` and `.chars`, and the difference is real: a
property is a **fact about the value**, and `len` is the same answer every time it is asked.
`get()` is a **question about the world** — two calls a moment apart may disagree, and the answer
costs a count. sysl puts the parentheses on the second kind.

Because `weak T` is a mode rather than a type of its own, it is not something an `impl` may be
written for — and unlike the other two modes, that refusal has a consequence rather than only a
principle behind it: a member call reaches through a `*T` and a `&T`, and through a weak reference
it reaches nothing, so a member written there could never be called. For the same reason there is
no `==` on weak references and no `Display` for one. Both are written on what `get()` hands back.

Everything a `&T` may point at, a `weak T` may be taken of: a struct, a scalar, an array, one
instantiation of a generic type, a trait object, and a type parameter (`weak T` inside a generic
function, settled by a `&T` argument).

### What it costs, and where

Every heap object carries **three header words** rather than two: the strong count, the
deallocation hook, and a weak count. The weak count is the number of weak references plus one
for the strong references *collectively*, so it reaches zero only after both the last strong
reference and the last weak one are gone. When the strong count reaches zero the object is
destroyed — its payload's references are given back — but the storage is returned only when the
weak count follows it down. That is what a `get()` on a dead object reads: storage that is still
there, with a strong count of zero in it.

The header is the same three words for **every** object, whether or not anything weakly
references it, and that is deliberate: releasing a reference is type-erased — a slice's owner
word has no static type to consult — so one layout is what makes the release path exist at all.
Two layouts would mean a discriminator in the header to tell them apart, which is the word it was
trying to save. The cost is eight bytes on an allocation that already cost a `malloc`.

The alternative was a **side table** keyed by object address, which keeps the header at two words
and pays a hash lookup on every weak operation and on every death. It is the wrong trade for a
language that puts its costs where a reader can see them: the header word is paid once per object
and is visible in the layout, the table would be paid per operation and visible nowhere.

### `weak sync T` waits for concurrency

An atomic-refcount object (`&sync T`) has no weak form yet. Upgrading is a compare-and-swap loop
against a count that another thread may be driving to zero underneath it, and there is nothing
to race with until `06` is built — so `weak sync T` is refused, naming the chapter, rather than
given an implementation nothing can exercise.

## References are never null

References are **non-null**. There is no null pointer in the safe subset, so there is no null
dereference. A maybe-absent reference is `Option[&T]`, matched or unwrapped explicitly. (`*T`
may be null like any C pointer — part of its unsafety.)

## Bounds safety follows length, not pointers

The out-of-bounds hazard is governed by whether a value **carries its length**, independent of
allocation or "pointer-ness":

- **Arrays (`[N]T`) and slices** carry their length, so indexing is **bounds-checked in every
  context** — hosted or allocator-free kernel, over static, stack, or heap memory.
- **`*T` carries no length**, so it is the one unchecked primitive. `p[i]` reads the `i`th element
  from it and `p[0..<n]` views `n` of them, both exactly as C does and both **unchecked** — there is
  no length to check against, and supplying one is the programmer's assertion.

The practical consequence: even low-level, allocator-free code stays bounds-safe by *choosing*
slices, and reaches for `*T` where it must — an MMIO window, a page table, a buffer a C function
filled. That choice is the point. A pointer is where the language's guarantees stop, so it carries
C's whole surface rather than a safer subset of it: anything C can do through a pointer, sysl can.

An earlier draft of this chapter said `*T` was "reserved for genuine address work, not merely for
having an indexable buffer", and refused the subscript on that ground. That was wrong and is
reversed. The argument proves too much — it is an argument for the programmer to prefer a slice,
which they can already do, and not an argument for the language to withhold the operation. A kernel
that cannot write `p[i]` cannot be written.
An array whose length the program computes needs an allocator, and so would a growable one; a fixed
array and a view of one do not.

### Two pointers subtract — C's `ptrdiff_t`

`p - q` between two `*T`s of the **same** pointee is an `isize`, and it counts **elements** rather
than bytes:

```
var at = usize(hit - &buf[0])          // an interior pointer, as an index
```

It is the **inverse of `&p[n]`**: indexing takes an address and a count to an address, and the
difference takes two addresses back to a count, both striding by the pointee. Writing it as bytes
would break that, and would make the answer depend on the pointee in the one direction where C's does
not.

**Why it is here at all**, since a program that only walks a buffer can index it: the
interior-pointer half of libc hands back a pointer *into* a buffer the caller owns — `memchr`,
`strchr`, `strrchr`, `strstr`, `memmem` — and without a difference every one of them is *callable and
useless*, because nothing could turn the answer into an index. That is a whole family of C the
language could not reach, which is exactly the floor `*T` exists to hold.

**Offsetting stays `&p[n]`.** `p + n` is not spelled, and deliberately: indexing already exists, it
already strides by the pointee, and a second spelling for the same address would be a second thing to
keep in step. So the two directions are `&p[n]` and `p - q`, and there is one way to write each.

What it is not: two pointers of **different** pointee types have no shared element to count and are
refused by the ordinary matching-types rule; `p + q` names no address and is refused; and a **counted
`&T` has no arithmetic at all**, keeping the equality it always had and nothing more. Arithmetic is a
property of the unsafe mode, not of holding an address.

Whether two pointers into unrelated objects may be subtracted is the programmer's business, as `p[i]`
past the end already is — this is the unsafe tier, and the difference is as unchecked as the subscript
it inverts.

How the two are written, indexed, and sliced — the literal, the zero-valued declaration, the
range subscript, `.len` — is **`07-arrays-and-slices.md`**.

## Slices keep their backing alive

A slice is **three words**, not two:

```
[]T = { owner: *Buf, ptr: *T, len: usize }        // 24 bytes on a 64-bit target
```

A bare `{ptr, len}` view can outlive the buffer it views — Go gets away with it because its
collector finds the object from an interior pointer, and sysl has no collector. So a slice
carries the owning reference itself: `ptr` and `len` name the range, `owner` keeps the bytes
alive. Taking a slice retains; dropping one releases. Slicing stays O(1) and allocation-free,
and "a slice never dangles" becomes true rather than aspirational.

`owner` is **null when there is nothing to keep alive** — a slice of static data, of a `*T`
buffer, or of a fixed array whose storage outlives every view of it. Retain and release on
such a slice are no-ops, so allocator-free code slicing a static or stack buffer pays nothing
at all. This is the same immortality rule string literals use (`04`), generalized.

A `string` is then exactly an **immutable, validated `[]u8`**, and the two share one
representation and one implementation.

**Slicing something that would not outlive the slice.** If a local fixed array is sliced and
the slice escapes the frame, the array is promoted to an ARC buffer so the slice's `owner` has
something to hold — the same escape analysis Go performs, inferred by the compiler with no
annotation in the source, exactly as an escaping closure is heap-boxed with no marker. Under
`no alloc` there is nothing to promote into, so the escape is a compile error naming the array
and the route by which the slice outlives it. **`05-escape-analysis.md`** specifies which
escapes are detected, how the answer crosses a call boundary, and how a promotion is reported.

## Shared mutability and concurrency

`&T` permits aliasing and mutation through any reference — the deliberate simplification over
Rust's exclusive-mutability rule. It is therefore safe **within one concurrency domain**, and a
`&T` may not leave one: its refcount is non-atomic, and two threads touching it would race.

Crossing a domain **copies** by default, which is what process IPC does anyway and what keeps
the ordinary path free of atomics. The exception is `&sync T`, whose refcount is atomic and
which may be shared — a distinct type from `&T`, with no conversion either way, chosen when the
object is allocated. `&sync T` makes the *reference* safe to share, not the object safe to
mutate; that still wants a `Mutex`. **`06-concurrency.md`** has the model.

## The two worlds, one language

- **App / server / utility:** `T` + `&T` (+ `weak`, slices, arrays). Safe, pleasant,
  ARC-managed. The bulk of an OS.
- **Kernel / driver / allocator-free:** `T` + `*T` + fixed arrays + slices + manual `malloc` /
  `free`. A module that never *creates* a `&T` emits no allocation and no allocator
  dependency, exactly like C, and stays bounds-checked wherever it uses arrays and slices.

The boundary is not a convention — it is compiler-enforced through the allocator capability.

**What the boundary gates is allocation, not ownership.** Allocator-free code may hold, pass,
copy, and drop a `&T` or a heap-backed slice that something else created: retain and release
are a few instructions, and the free path goes through the object's own deallocation hook.
What it may not do is *make* one. This is what allows a driver to keep the `&Device` its bus
manager handed it, and a `no alloc` parser to be handed a heap-backed slice and read it —
neither of which is expressible if ownership stops at the boundary.

## Capabilities (the allocator, and orthogonal environment facts)

The presence of a **managed allocator** is a first-class capability that gates the operations
that *allocate*: creating a `&T`, growing an array, and anything in the library that returns
newly-made storage. A module declared **`no alloc`** makes those a compile error, leaving `T`,
`*T`, fixed arrays, slices, and whatever references it was given — the allocator-free subset.
Holding and releasing an already-allocated object stays legal there (see "The two worlds").

This is **orthogonal** to whether an OS or POSIX layer is present (which gate the
standard-library / syscall surface). Unlike Rust's `no_std` — which bundles "no allocator," "no
OS," and "no conveniences" together and forces embedded-with-a-heap to claw `alloc` back — sysl
keeps them independent switches: bare-metal-with-a-heap is "alloc yes, os no."

The full mechanism — `alloc` as a type-checker-enforced *language* capability vs `os`/`posix`
as import-gated *environment* capabilities, the target-provides / module-narrows two-level
model, and propagation through imports — is specified in **`capabilities.md`**.

## Hazard summary

| Segfault source | Prevented in the safe subset by |
|---|---|
| use-after-free / double-free | ARC on `&T`; `weak` degrades to `Option`, never dangles |
| null dereference | non-null references; nullable is `Option` |
| out-of-bounds | length-carrying arrays/slices, checked everywhere |
| slice outliving its buffer | the slice's `owner` word retains it; escaping locals are promoted |
| refcount race across threads | `&T` cannot cross a domain; `&sync T` is atomic (`06`) |
| dangling / wild pointer | impossible without `*T` |

Only `*T` opts out — visibly.

One hazard on this list is **not** eliminated: racing on the *fields* of an object two threads
deliberately share. Preventing that needs proof of exclusive access, which is the thing this
language trades away, so it is answered by a `Mutex` and by convention rather than by the type
checker (`06`). It takes `&sync` or `*T` to reach the situation at all, so it is at least as
greppable as everything else here.

## Open sub-questions

- ~~Capability mechanism~~ — **done**, see `capabilities.md` (`alloc` type-checker-enforced,
  `os`/`posix` import-gated; target provides + module `no alloc` narrows; propagated through
  imports).
- ~~Slice ownership and the allocator-free boundary~~ — **done**, above: slices carry an
  `owner` word, ARC objects carry a deallocation hook, and `no alloc` gates allocation rather
  than ownership.
- ~~Escape analysis~~ — **done**, see `05-escape-analysis.md` (inferred, never annotated; a
  two-fact summary carries the answer across calls; promotion where an allocator exists, a
  diagnostic under `no alloc`, and `--explain-escapes` to make a promotion discoverable).
- ~~Concurrency~~ — **done**, see `06-concurrency.md` (domains are threads, crossing copies,
  `&sync T` is the atomic-refcount exception, no async runtime in the language; shared *mutable*
  state is discipline plus `Mutex`, since without a borrow checker it cannot be checked).
- ~~Array and slice expression syntax~~ — **done**, see `07-arrays-and-slices.md` (literals and
  zero-valued declarations, any-integer checked indexing, range subscripts keeping the
  language's inclusive/exclusive meanings, `.len` as a field until methods exist).
- **Unchecked-index escape hatch** — an opt-out of bounds checking for hot loops (default
  checked). Likely yes, deferred.
- ~~`weak` runtime~~ — **done**, above: an in-box header word, uniform across every object, with
  the weak count holding one share on behalf of the strong references collectively. The side
  table was refused for putting the cost per-operation and out of sight.
- **`weak sync T`** — an atomic weak reference, which wants the compare-and-swap upgrade and
  something to race with. Waits on `06`.
