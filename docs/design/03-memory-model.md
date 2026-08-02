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

**One hole in that guarantee was open long enough to be worth recording, and what closed it is worth
recording with it.** `s.bytes` reinterprets a string's three words as a `[]u8` without copying
(`04`), so the view's elements are the string's own storage — and a `[]u8` permits writes. Writing
one byte of a **literal's** bytes writes into read-only memory and kills the process, from a program
containing no `*T` at all. Assigning to one where the view is named — `s.bytes[i] = v` — was refused
early, which closes the spelling somebody reaches by accident, but that refusal is written at the
subscript and so cannot see the view once it has been **bound to a name or passed on**. A property
enforced where a value is *made* expires with the expression that made it; a property recorded in
the *type* travels with the value, and that is the whole of the difference.

So `s.bytes` yields a **`[]const u8`** — `07`'s read-only view type, whose elements may not be
written through it. The three spellings that walked around the old refusal now all land on the same
rule: `var b = s.bytes; b[0] = v` is refused at the write, `poke(s.bytes)` where `poke` takes a
`[]u8` is refused at the call, and re-slicing keeps the property rather than dropping it.

**`&` on a read-only view is deliberately still allowed**, whether the view is written out or
carried under a name. Taking an address is entering the raw tier on purpose: `&b[0]` is a `*T` the
moment it is written, and this guarantee is about programs that have none. It is also how a view
reaches a C function that wants a pointer and a length — `printf("%.*s")` is that call, and so is
`memchr`, which is what the library's own `find_byte` is written as. A read-only view that could
not yield an address could not do the job it was added for, and would buy nothing for it: `*T` is
greppable, and a program with none still cannot reach these bytes.

This is not the rule `13` applies to a `val` itself, where `&k[0]` **is** refused, and the
difference is which thing is being addressed. A `val` is storage the program declared read-only, and
the refusal keeps the promise where it was made; a view is a value that has already been handed out,
and what it promises is about writing *through* it. Slicing is how you cross from the one to the
other, and it is written down.

## Why runtime safety, not Rust's compile-time safety

sysl gets memory safety from **ARC (automatic reference counting)** at runtime — a small,
predictable cost — deliberately in exchange for deleting the cognitive burden Rust charges:
**no lifetimes, no borrow checker, no move semantics.** A `&T` behaves like a Scala object
reference (alias it, pass it, mutate through it), except it is refcounted instead of
garbage-collected. That trade — small runtime cost for large simplicity — is the language's
reason to exist. It is *easier than Rust* by construction, because the hard part of Rust is
exactly the part sysl does not have.

There is one place the language asks a question about aliasing anyway, and the shape of it is worth
noting here because it is the counterpart of this decision rather than a retreat from it. A struct's
`invariant` clauses (`16 §6`) are re-checked at every write, and the write is found by walking the
*place* — which a pointer typed below the struct escapes. The answer is a rule about which aliases may
be **created**, which is local and decided from types alone; it never asks where an alias is *used*,
which is the question that needs lifetimes. A program that declares no invariants is not asked
anything at all.

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
here. **It is the only unsafe primitive over data** — the only way to produce a dangling or wild
pointer to a value — and it needs no runtime. It is how a kernel, a driver, or the allocator's own
internals are written. Its counterpart over *code* is `*extern(…)`, an address a call may go through
with nothing checking that the signature is the one the code there was compiled with (`12 §6a`); the
two are spelled with the same sigil because they carry the same kind of promise.

## Places: `&`, `*`, and selection

A **place** is something with an address: a local or parameter, a dereference, an **element**, and
a field of any of them. Everything else — a call result, an arithmetic result, a freshly built
struct — is a value with no address to take.

A **function** is the one thing outside that division. It is not a place, since nothing holds it and
there is no slot to point at, and it is not a value either — but `&f` yields its address all the
same, and the result is a `*extern(…)`, which is code's own address rather than a pointer to
anything readable (`12 §6a`). The `&` is this one; what differs is only what comes back.

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

## `ref` — a name for a place

A place can be deep, and until now the only ways to shorten one were to copy it or to leave the safe
tier. `var t = self.tasks[i]` binds a **copy**, so every read and every write repeats the path from
the table; `&self.tasks[i]` gives the name back and gives up bounds checking, `within` checking,
invariant re-checking and the guarantee at the top of this chapter, all in one step. That is a cliff
rather than a gradient, and `guide/kernel` is the measurement of it: sixty-five occurrences of
`self.tasks[…]`, four predicates demoted to free functions because a by-value receiver would have
copied a task with its whole program in it.

**`ref` binds a name to a place.**

```
ref t = self.tasks[i]
t.state = Ready                // writes through to the table
t.prio  = t.base
```

The place is evaluated **once**, where the binding is written — the index is computed once, the
bounds are checked once — and what the name means afterwards is the storage that was found, not the
expression that found it. So a later `i += 1` leaves `t` naming the element it always named.

### It is a declaration, never a type

This is the whole of why it can exist in a language with no borrow checker. `ref` may be written
only as a local declaration. There is no `ref` type, so a ref cannot be a field, a parameter, a
return type, an element, or a type argument; it cannot be assigned to another ref, and it does not
outlive the block that declares it.

That restriction is what keeps the compiler's knowledge complete. A `*T` is a type, so the moment
one exists it can be carried somewhere the compiler has lost the path it came from — which is
exactly what `16 §6` says of `wreck(&o.a)`: *"inside `wreck` there is no `Outer` to re-read and no
way to learn there ever was one."* A ref never travels, so the analyzer still holds the place
expression, at the point it was written, in the same body. The consequence is the point of the
feature:

**At run time a ref stores an address. At compile time it remembers a place.** Both halves are
load-bearing. The address is what makes the sixty-five path walks one. The remembered place is what
keeps every check that a `*T` would have severed:

- a write through the ref re-runs the `invariant` clauses of every struct the place lies inside,
  found by the same outward walk of `16 §6`, because the walk still has the whole place to walk;
- a ref into a `val`, or into an element of one, is read-only, since reaching into read-only storage
  keeps the property;
- a ref to a `within`-constrained slot is checked on assignment exactly as the slot is;
- and the bounds check that a subscript owes is paid once, at the binding, rather than not at all.

A ref is therefore **not a fourth memory mode**. It introduces no representation, no new type, and
nothing that can be stored; it is a second way to *say* a place that the three modes already
describe. Principle 3 asks that no feature put ceremony in front of the modes, and this one puts a
name in front of a path.

### What may be written

**A ref's initializer must be a place.** `ref x = f()` is refused, and says so: a call result has no
address, so there is nothing for the name to mean.

**A ref inherits the place's writability, and gets no modifier of its own.** `ref t = self.tasks[i]`
under a `*self` receiver may be written; a ref into a `val` may not. The place already carries the
property, so stating it twice would only create the chance to state it wrong. If a read-only alias
of *writable* storage is ever wanted, that is the axis `[]const T` already established (`07`), and
it should arrive as that same word rather than as a second spelling here.

### The one rule: what may move underneath it

A stored address is only as good as the storage staying where it is, and this is where the design
has to say something the languages it borrows from do not. C# has ref locals and needs no such rule,
because a tracing collector keeps the old array alive when the variable is pointed at a new one.
sysl has no collector in this tier, so the same program is a dangle:

```
ref e = self.cell[i]           // self.cell is a []T over an ARC buffer
self.cell = [None; n * 2]      // releases that buffer — `e` now names freed storage
```

**So: while a ref is live, no step of its place that could come to name different storage may be
assigned, and no mutating method may be called on a prefix of one.** The check is local, decided
from types alone, and never asks where the ref is *used* — which is the question that would need
lifetimes.

Which steps those are falls out of the model rather than being a list:

- a **view, reference, or pointer** step can be made to name somewhere else, so it is a hazard, and
  so is every prefix of one;
- a **field, a fixed array, or an element of one** cannot: that storage *is* the enclosing object's
  bytes, and assigning to it overwrites the bytes rather than moving them.

A chain with no indirection step in it therefore has nothing to refuse at all. `guide/kernel`'s
tables are fixed arrays in a struct the kernel owns, so every ref in it is unconditional, and the
rule costs that program exactly nothing — which is the case it was designed for. The rule bites
where the hazard is real, and `Map.grow` above is precisely that case.

The mutating-call half is the conservative one, and deliberately: a `self.grow()` under a live ref
into `self.cell` is refused whether or not that particular `grow` reassigns the view, because
deciding otherwise means reading the callee's body, which is the seam `15` keeps closed. The cost is
one refusal in a program that could have written the ref one line later.

**What this does not do is make a `*T` safe**, and it does not try to. A ref's place may be rooted at
a pointer, and whether *that* points anywhere is the raw tier's ordinary bargain. What the rule buys
is that the storage a ref names cannot be released by the block that named it.

### A ref to a slot that holds a reference

`ref r = self.node`, where `node: &Node`, names the **slot**, not the object in it. So the binding
takes no count — nothing new holds the object — and `r = other` is the assignment `self.node = other`
by another name, releasing what was there and retaining what arrives, in that order. Reading `r`
produces the reference and takes a count for the reader exactly as reading the field would.

The distinction matters because the other reading is available and wrong: if binding retained, a ref
would be a `&T` with extra steps, and the count it took would keep an object alive past the write
that replaced it.

### Where it comes from

The form is old and lives outside this project's usual references, none of which have it — Swift,
Kotlin, Scala and Go are all silent, so principle 2 supplies nothing and the precedent is borrowed
further afield. **Ada's `renames`** is the general case, and evaluates the name once at the renaming
declaration; **Fortran's `ASSOCIATE`** is the closest match in shape, being block-scoped and
deliberately not a type; **C#'s `ref` locals** are the closest in spelling. C# also shows what the
restriction above is worth: having added ref returns and ref fields it spent several releases
building the escape analysis (`scoped`, ref-safety contexts) that keeping the form local avoids
entirely.

Scala's by-name parameter is the thing this is **not**. `x: => T` re-evaluates at every use; a ref
evaluates once and remembers what it found. Re-evaluation would be the wrong answer twice over — it
would save no check, and it would silently make `t` follow a later change to `i`.

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

The four positions named above are where a context asks, not the whole list: an element of an array
or slice, a part of a tuple, a generic argument and a parameter of a callable type all ask the same
way. **A default parameter value is the one position that asks and cannot be answered.** A default
is produced afresh at each call that omits it, in a scope holding none of the caller's locals, so
what it names has to outlive every frame — and every candidate is closed. A construction is refused
by the paragraph above; a top-level `var` is a local of the entry point, so naming one is naming the
caller's locals; and a module-level `val` counts nothing (`13 §7`), which a reference does.
So a `weak T` parameter takes no default, and nothing is special-cased to make that so — it falls
out of what a default may name meeting the one thing a weak reference may not be made from. A `&T`
parameter is unaffected, because the construction a `weak T` is refused there is exactly what serves
it.

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

## Reinterpreting storage

An allocator carves bytes and hands back a typed pointer. That is the whole of what an allocator
does, and until this section it was not expressible: `&arena[0]` is a `*u8`, the caller wants a
`*Node`, and no spelling took one to the other. The same absence stopped a driver taking an MMIO
address the datasheet gives as a number and reaching the register block at it.

Both are cases the section above already committed to: **anything C can do through a pointer, sysl
can**. A language that can index a raw pointer and subtract two of them, and then cannot say which
type the bytes are, has drawn the line in a place no C program respects — and it is exactly the line
an allocator sits on. So storage may be reinterpreted, in the raw tier, written.

**Three directions, two spellings**, because they are not equally dangerous:

```
var n = usize(p)                       // a pointer as a number — an ordinary conversion
var p: *u8    = ptr_cast(addr)         // a number as a pointer
var node: *Node = ptr_cast(raw)        // one pointee type as another
```

**A pointer becomes an integer through the ordinary conversion syntax** (`01`), because it is an
ordinary conversion: `usize` is wide enough to hold any address by definition, so the conversion is
total and loses nothing, and the result is a number that cannot be dereferenced. Nothing unsafe has
happened yet. `isize` takes one too, which is what a program comparing addresses against a signed
offset wants.

**The other two directions produce a pointer, and they are the unsafe ones**, so they share one form
that says what it is doing. `ptr_cast(x)` takes a `*A` or an address-sized integer and produces the
`*B` its context asks for. The target type is **not written in the call** — it comes from whatever
receives the result, the same way `va_arg` (`12 §9`), a bare `None`, and a bare `null` all take
theirs. That is not a shortcut: square brackets in an expression are indexing, and call-site type
arguments are refused language-wide (`10 § Open a`), so a written target would need a syntax nothing
else in the language has. Where nothing says which pointer is wanted, the program is told to annotate
what receives it.

**`ptr_cast` never produces a `&T`.** A reference is a safe-tier value: non-null, refcounted, and
relied on by everything the safe subset promises. An address invented from bytes has no count for ARC
to own and no object to be non-null about, so a `&T` conjured this way would put a value the safe
subset trusts into a state only the unsafe tier can produce — which is the one thing the two-tier
split exists to prevent. `weak T` and the fat types (a slice, a `string`) are refused for the same
reason and one more: they are wider than an address, so there is nothing to reinterpret. What comes
out is a `*T`, and reaching anything else from it is the ordinary route through `*p`.

**Where this meets the aliasing rule.** `16 §6` refuses an alias typed *below* a promise — `&o.a`,
where a clause on `o` reads `a` — and a reinterpretation is another way to arrive at such a pointer.
The two do not conflict, and the reason is worth stating rather than leaving to be discovered. `16
§6` restricts alias creation **from a place**, because a place has a type whose promises the compiler
can see and a read set it can compare against. `ptr_cast` starts from an address, which names no
place and carries no promise, so there is nothing to compare and nothing to refuse. That is the
unsafe tier's bargain, unchanged: `p[i]` past the end is already the programmer's assertion, and a
`*T` aimed at storage some invariant covers is the same assertion about a different thing. The
boundary the compiler *can* police is the one above — that no `&T` comes out — and it polices it.

### `sizeof` and `alignof`

```
var bytes = sizeof(Node)               // usize, a compile-time constant
var a     = alignof(Node)
```

Both take a **type** and yield a `usize` (`00 §7`). Both are written over the whole type grammar —
`sizeof(*Node)`, `sizeof([16]u8)`, `sizeof(int)` — which is why they are forms the parser reads
rather than functions a name lookup finds; a library function's argument list holds values, and these
hold neither a value nor a name.

There is **no value form**. C accepts `sizeof x` as well as `sizeof(T)`, and the two disagree in the
one case that matters — `sizeof arr` against `sizeof ptr` after an array has decayed. sysl has no
decay, so the value form would buy only the confusion.

**Any type may be asked**, and this costs less than an earlier draft assumed. The objection was that
answering would fix every type's layout as part of the language rather than as an implementation
detail — but `15 §1` already did that, deliberately and for its own reasons: fields are laid out in
declaration order and never reordered, every type is C-compatible by construction, and **layout is
part of a module's public interface**, since a caller needs size, alignment and offsets to
stack-allocate a `T` or pass one by value. A change to any type's layout is already a change to its
interface, already hashed as one. `sizeof` promises nothing that was not promised; it makes the
promise *askable*.

Two consequences follow rather than needing rules of their own:

- **The answer is per target.** `sizeof(usize)` is 8 on a 64-bit target and 4 on a 32-bit one,
  exactly as C's is, and a program that needs a width to be the same everywhere says so with a
  `require` on a constant rather than assuming.
- **A type parameter may be asked**, since a generic body is compiled once per instantiation and the
  argument is concrete by then. That is what lets a slab allocator be written over any `T` instead of
  over one hardcoded size, and it closes the absence `07` names in passing.

The one layout still genuinely in motion is the **data-enum tag**, which `09 § Open a` wants to
narrow. Exposing `sizeof` does not decide that: the tag's width is a layout change like any other,
and `15 §1` already made every layout change an interface change. What it does is make the change
visible to a program that asked, which is an argument for taking `09 § Open a` sooner and not for
withholding the operator. The future carve-out already has its home: an `opaque` struct (`15 § Open
a`) withholds its layout entirely, and *no `sizeof`* is on the list of what that costs — the type
opts out, rather than `sizeof` opting in.

## Device memory

`ptr_cast` gets a driver to the register block. It does not get it a *correct* driver, and the
missing half is this: an optimizer is entitled to assume that reading the same storage twice yields
the same value, that a store nobody reads is a store nobody needs, and that two accesses in a row may
be one wider access. Every one of those assumptions is false at a device. `while regs.status == 0 do
()` is a loop that reads a register until the hardware changes it, and a compiler that hoisted the
read out would spin forever on the first value it saw.

So sysl has C's qualifier, spelled the way C spells it — in the type:

```
struct Uart
    status: volatile u32
    data:   volatile u32
    baud:   u32              // a shadow value in RAM, not a register

const UART: usize = 0x1000_0000
val regs: *Uart = ptr_cast(UART)

putc(c: u32) -> unit
    while regs.status & 0x20u32 == 0u32 do ()
    regs.data = c
```

> A **`volatile`** place is one whose reads and writes are **effects, not value computations**. It may
> change without the program changing it, and reading it may itself do something. So the compiler
> emits exactly the accesses the source wrote, exactly once each, in the order written — never
> adding, dropping, merging, or moving them relative to one another.

**Touching it is the point.** That is the whole of the definition and it is worth reading twice,
because the qualifier is so often taken for something it is not.

**It constrains the compiler, not the machine.** No atomicity, no ordering against another core, no
protection from a torn read. C spent two decades learning this and now says the same thing: for
talking to another thread, `06` has `&sync T`, `Mutex[T]`, `Atomic[T]` and explicit orderings, and
none of them is spelled `volatile`. A program that reaches for this word to share a counter has
written a race with a keyword in front of it.

### It qualifies storage, and a value read out of storage is an ordinary value

This is the rule everything else follows from. `regs.status` has type `u32` — not `volatile u32` —
because what a load hands back is a number, and a number is not somewhere a device can write. What is
qualified is the *place*, and the qualifier lives in the three types that name a place somebody else
owns:

```
status: volatile u32           // a field
bank:   [4]volatile u32        // an element — a GPIO bank
p:      *volatile u32          // a pointee — the lone register
```

Everywhere else the type being written is the type of a **value**: what a `var` holds, what a
parameter receives, what a function hands back, what a type argument stands for. `volatile` is
refused in all of them, and the diagnostic says which spelling was wanted — a program that writes
`var x: volatile u32` almost always meant `*volatile u32`.

**Per field, not per aggregate.** C also allows `volatile struct Uart`; sysl does not, and the block
above shows why. `baud` is a shadow value the driver keeps in ordinary memory beside the registers,
and every real device header has one — a reserved word, a cached configuration, a software flag. A
qualifier on the whole struct would sweep it in. Qualifying per field is the same power with the
opt-out, it is what CMSIS and every other vendor header already does (`__IO uint32_t CR1;`), and it
makes the restriction below a check on one scalar instead of a walk of a type.

**Only a scalar or a raw pointer may be qualified.** The promise is about *the* load and *the* store
the source wrote, so it is only meaningful where an access is one instruction. A counted value is
refused outright: a `&T`, a `weak T`, a slice and a `string` come with retains and releases the
compiler places, and a retain that may not be elided is not a request anybody could act on. A trait
object is refused for a plainer reason — it is two words, so touching one is two accesses whatever
the source says, and the table beside the value is this compiler's rather than a device's. Device
memory is reached with a raw pointer, which is the tier this belongs to.

**A constrained subtype is refused too, and this one is about trust rather than instructions.** A
`Level = int within 0..7` is the claim that a value *has been checked* (`16 §4`); a register holds
whatever the device put there. `lvl: volatile Level` would hand that claim back unchecked through a
field selection indistinguishable from any other, with the `ptr_cast` that made the pointer too far
away to read as the licence. So the register is declared at the base and what comes back is
converted — one written conversion, checked, at the point the value arrives. A bare `new` derivation
is admitted, since it changes a type's identity and promises nothing about which values it has.

**A struct that holds a register carries no `invariant`.** The two rules meet here, and the answer is
about the *struct* rather than about the clause because a check is a call taking every field: it
reads the whole block however few fields the clause names, so an invariant written over the shadow
value beside the registers would make writing that shadow an access to the device. There is nothing
to hold the clause true either — a device changes a register between the check and the instruction
after it, with no alias anywhere for `16 §6` to restrict. A register is checked where it is read.

### What the compiler does with it

A qualified access lowers to LLVM's `load volatile` / `store volatile`, which is exactly the barrier
this needs: it stops the reordering, elision and merging above, and stops nothing else. There is no
runtime cost and no runtime component.

**A qualified field is reached at its own address**, not lifted out of the block. An ordinary field
read loads the aggregate and takes the field out of it, which is free after optimization and correct
for ordinary memory. It is neither at a device: reading a `Uart` to find out what is in `status` also
reads `data`, and reading a data register is how a FIFO is popped. So a place walk is what a
qualified field gets — one `getelementptr`, one `load volatile`, and nothing else touched.

**A whole block copied is a copy of every register in it.** `var u = *regs` is one access to each
register, which is as much an effect as one access to one of them, so the aggregate access is marked
too. Whether a driver wants that is the driver's business; what it does not get is a silent
unqualified read of hardware.

**An address taken of a register is the address of a register.** `&regs.status` has type
`*volatile u32`, so a driver may hand one register to a helper and every access the helper makes is
still an access to a device. The qualifier travelling with the address is what makes the type
useful at all — without it, a driver would have to be one function.

**A type parameter never binds to a qualified type.** `first[T](xs: []T)` handed a
`[]volatile u32` solves `T` as `u32`, and the argument then does not agree with the `[]u32` the
instantiation asks for — which is the message worth reading. The reason is not squeamishness: the
loads and stores a generic body emits are *its* accesses, written once and shared by every
instantiation, and it cannot promise to have written the ones a particular caller had in mind.

**It is not reserved.** `volatile` is special only in front of another type, so a program with a
variable, a field, a function or a type of its own by that name still compiles — the same
arrangement `sync` has after `&`.

### What it does not interact with

Two neighbours look as though they should have something to say here and do not, which is worth
writing down so the question is not reopened.

**The aliasing rule (`16 §6`)** refuses an alias typed below a promise, reasoning about *places* and
what a clause reads. A volatile access is still an access to a place with a type, and nothing about
`16 §6` licenses eliding or reordering one — the assumption it does license is about who may *write*
through an alias, not about how many times a read happens. The two are orthogonal, and the one place
they touch is settled above: a struct holding a register carries no clause for the rule to be about.

**`[]const T`** composes with it and means a different thing: `const` is a property of the *view* —
these elements may not be written through this handle — while `volatile` is a property of the
*element*. A read-only device register is `[]const volatile u32`, and both words are doing work.

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

## `defer` — scope exit for what the language does not own

ARC gives back a `&T`, a `string` and a slice's backing without being asked. It knows nothing
about the rest: a descriptor from `open`, a `FILE*` from `fopen`, a block from `malloc`, a
lock taken from a mutex. Those are the C boundary's, and until they are released by hand a
correct program is one that never takes an early exit — which `?` (`11 §3`) makes the *normal*
way to leave a function.

**`defer <statement>` runs that statement on the way out of the block containing it.** The
resource is released beside the call that took it, once, rather than at every exit:

```
read_count(path: string) -> Result[int, string]
    var f = fopen(path, "r")
    if f == null then return Err("cannot open")
    defer fclose(f)

    var n = parse_header(f)?     # fclose runs here on the failure path
    var m = parse_body(f)?       # and here
    n + m                        # and here, after the result is computed
```

### What runs, and when

**A deferred statement belongs to its block** — a loop body, a branch arm, or the function body
itself — and runs when control leaves that block by any ordinary route: falling off the end,
`return`, `break`, `continue`, or a `?` that takes its failure arm. **Several in one block run
last-registered-first**, so they undo in the reverse of the order they were set up, which is the
order that lets a later one depend on an earlier one's resource.

**A `defer` runs only if control reached it.** It is a statement, not a declaration: one after
an early `return` never registered, and one in a branch never taken did not either.

**The whole statement runs at the exit, so everything in it is read there** — not at the `defer`.
A variable the statement names is read at its exit-time value:

```
var n = 1
defer print(n)      # prints 2
n = 2
```

This is the second place Go differs: Go evaluates a deferred call's *arguments* where the `defer`
stands and runs the call later, which means somewhere to keep each captured argument until the
call happens — a slot per deferred statement, sized and laid out per call. Reading everything at
the exit needs nothing kept, which is the same reason block scope was chosen over function scope,
applied to the arguments rather than to the schedule. It is also Zig's rule.

It changes nothing for the form's purpose. `defer fclose(f)` releases the handle bound above it,
and a program that rebinds `f` between the `defer` and the exit has changed which file is open,
so closing the one that is actually open is the behaviour that was wanted.

**A trap runs nothing.** `11 §6` settles that a trap aborts without stack cleanup, and `defer`
does not qualify that: a broken invariant means the program's model of itself is already wrong,
and running cleanup code against that state is how a corrupt program writes its corruption to
disk on the way down. `defer` is for releasing a resource, not for restoring an invariant.

### Why the block and not the function

Go's `defer` runs at **function** exit, and sysl's does not. The difference is invisible for
the common case — a resource taken at the top of a body, released when the body ends, where the
function *is* the block — and shows up in a loop:

```
for i in 0..<n
    var f = fopen(paths[i], "r")
    defer fclose(f)          # this iteration's file, closed this iteration
    consume(f)
```

Block scope closes each file at the end of the iteration that opened it, so the program holds
one at a time. Function scope would hold all `n` open until the function returned, and — the
part that decides it here — would need somewhere to record `n` pending statements, a number no
compiler can bound. That is a per-frame list whose length is discovered while running, which is
precisely the machinery `11 §6` rejects unwinding for: a freestanding target under `no alloc`
(`13 §4`) has nowhere to put it. Block scope needs no runtime state at all — the statement is
emitted at each edge that leaves the block, exactly as ARC's own releases already are.

**Go has two reasons for its choice and sysl has neither.** `recover` only works inside a
deferred call and a panic unwinds one frame at a time, so the frame has to be the unit; and
`defer` mutating a named result is how Go wraps an error on the way out. sysl has no unwinding
and no named results. The languages that came later without `recover` — Zig, Swift — both put
`defer` at the block.

### Where it sits against the rest of the model

**A deferred statement runs before the block's ARC releases**, so every local it names is still
alive when it runs — including the one holding the resource it is closing. Leaving from the
middle unwinds outward: the innermost block runs its deferred statements and then gives up its
counts, then the block outside it does the same, up to the function's own.

**It owns nothing and allocates nothing.** `defer` takes no count, makes no box, and adds no
word to any value; a program that does not use it emits nothing for it. That is what keeps it
available under `no alloc`, where the resources it releases are the only ones there are.

**What it is not** is a destructor. A destructor belongs to a *type* and runs wherever a value
of that type dies; `defer` belongs to one *place in one body* and runs for the resource that
body took. Whether sysl should also have the type-bound form — so a `File` closes itself
wherever it is used — is `§ Open sub-questions`.

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
- **A type-bound destructor**, so a `File` closes its descriptor wherever a value of it dies
  rather than at each place one is taken. `defer` (above) covers the per-site half and is the
  cheaper one; this is the half that would let a resource be wrapped once and used everywhere.
  What has to be decided first is not the syntax but where it may run: ARC destroys through a
  type-erased hook (`§ Who frees it`), so a user destructor is a call the compiler places into
  that hook, and the questions are whether it may fail, whether it may be reached during
  teardown of a cycle, and whether a value type gets one at all or only a `&T`. `07 § Length`'s
  claim that a container would need one to be written **was already withdrawn** — a container
  whose storage is a `[]T` is destroyed by ARC on its behalf — so the customer for this is the
  C-boundary resource, the same one `defer` serves, and it should be weighed against just
  having `defer`.
