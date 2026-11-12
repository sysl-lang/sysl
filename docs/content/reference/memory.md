---
title: Memory
summary: The kinds of name, the block lifetimes the compiler checks without being told, the annotations, destructors, crossing a domain, places and `ref`, and the raw tier.
weight: 70
---

Every value in a sysl program is named in one of a few ways, and the declaration that names it says
which. This page is the whole of the model: the kinds of name, the lifetimes the compiler works out
from blocks with nothing written, the few annotations that exist for code it cannot see, what a
destructor guarantees, and where the guarantees stop.

**If you do not write `*T`, you cannot segfault.** The safe subset — values, references, weak
references, arrays and views — has no use-after-free, no null dereference, no out-of-bounds access
and no dangling pointer. The one unsafe primitive over memory is the raw pointer, and it is
**greppable**: a program's exposure to C's hazards is the lines with a `*` in the type, the
`import sysl.raw.ptr_cast` lines, and the subscripts it asked by name not to check —
`get_unchecked`, `set_unchecked` and an [`@unchecked`](/reference/arrays/#unchecked-access) block.

## The kinds of name

| kind | what it is | who frees it | needs an allocator |
|---|---|---|---|
| `T` | a value — in a register, a stack slot, or inline in something bigger; copied | nobody; it goes with its block or its container | no |
| `&T` | a counted reference to a heap object | the compiler, when the last reference goes | to **make** one |
| `weak T` | a reference that does not keep its object alive, read as an `Option` | — | no |
| `&sync T` | a `&T` whose count is atomic: the one kind that may cross a domain | the compiler | to make one |
| `*T` | a machine address | you | no |
| `[]T`, `[]const T` | a view: owner word, address, length | its owner | no |

`ref x = place` is a [declaration](#ref-a-name-for-a-place), not a kind. `Buf`, `Frozen`, `Map` and
the other collections are library types built from these. **There is no other kind.**

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

**The choice is per declaration, not per type.** The same `struct Point` is a value, a heap object
and a pointer's target above. This is C's arrangement rather than Swift's or Scala's, where a type is
`struct` or `class` once and for all: a page-table entry, a temporary in a loop and a shared
configuration object may be one type held three ways.

**The value is the unmarked default, because it is the only kind a kernel can use.** A module that
has given up the allocator cannot *make* a reference, so a reference default would make the unmarked
spelling the illegal one exactly where value semantics matter most. With `T` the default, `T` works
in both worlds, `*T` covers pointers, and `&T` appears in allocator-free code only where something
else handed one over.

### `T` — values

A value lives inline. **Assignment and argument passing copy it**, as in C:

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

**There is no move semantics and no use-after-move**: `a` is a whole `Counter` after `b = a`, and
always will be. A value holding a `&T` field is still a value, and copying it **retains** that field,
so the copy is independently safe. Where a copy is a local's last use the compiler hands the value
over instead ([below](#a-count-that-would-be-taken-and-given-straight-back-is-not-taken)), which
changes how many counts are taken and nothing a program can observe.

### `&T` — counted references

A `&T` refers to a heap object managed by **ARC**: the compiler emits the retains and releases, and
the object is destroyed the moment the last strong reference goes. It is **shared and freely
aliased**, **mutable through any alias** (there is no borrow checker and no exclusivity rule),
**non-null**, and **automatically managed**:

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

The same two lines as above, and the declared kind is the only difference: `b = a` copied a
`Counter` there and made a second reference here. Two live mutable aliases are what Rust exists to
prevent; permitting them is the deliberate trade, paid for at run time by counting, and it buys a
language with no lifetimes written and nothing to prove to a checker.

**There is no allocation keyword.** A construction written **where a `&T` is expected** puts the
object on the heap, and with no expectation a construction is a value:

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

The positions that fix the expectation are the ones that already state a type: a declared local, a
parameter, a return type, a struct field, an enum variant's payload, an element of an array or a
slice, a part of a tuple, a generic argument and a parameter of a callable type. **The rule is about
types, not syntax**: a `T` where a `&T` is expected goes on the heap, whatever produced it. An `if`,
a `match` and a loop yield through their branches, so **the expectation reaches each branch on its
own** — a value branch and an already-reference branch meet at `&Point`, and what is already a
reference passes through untouched. A scalar is referenced the same way:

```sysl
var n: &int = 41

print(*n + 1)
```

```output
42
```

**A reference is never null.** There is no null in the safe subset; something that may be absent is
an `Option[&T]`, and a `match` is the only route to it. A box is made from what the allocator answers,
and a null answer **traps** before anything is stored, exactly as a failed array allocation does. The
one `&T` that reads as null is one in storage the language did not lay out and the program zeroed
([below](#storage-the-compiler-did-not-lay-out-may-not-hold-a-counted-value)).

### References compare through their referent

**`==` and `!=` on two `&T`s (or two `&sync T`s) compare the objects as `T` does wherever `T` has an
`Eq`**, written, derived or a built-in's, and a `&T` hashes as `T` does wherever `T` has a `Hash`.
That is Rust's `&T` rule:

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

**`===` and `!==` are identity**, always, and take two references or two pointers of one type and
nothing else — an integer has no identity apart from its value, and a trait object is two words:

```sysl
main()
    print(1 === 1)
```

```error
'===' compares the identity of two references or pointers, and int is neither — '==' compares its value
```

- **Where `T` has no `Eq`, a reference is its own identity**: `==` asks whether the two name one
  object, and the hash is the address's, so a `Map[&Node, V]` keys by identity — what a
  hash-consing table wants.
- **A bound gets the same answer**: inside a `[K: Hash + Eq]` body at `&Node`, `k.hash()` and
  `k.eq(o)` are `Node`'s where it has them and the address's where it does not. A member call written
  on a reference reaches the referent's own (`r.hash()`, `r.eq(*s)`). A struct deriving `Eq` or
  `Hash` over a `&T` field, and a tuple of references, compare and hash each reference the same way.
- **A method the referent declares itself wins at a member call, and only there.** An inherent `hash`
  or `eq` with no `impl Hash` or `impl Eq` is what `r.hash()` calls on a `&Node`, `&sync Node` or
  `*Node`, but `==`, a `Map` key and a bounded body still see a `Node` with neither, and use identity.
- **A referent with an `Eq` and no `Hash` makes a reference that is no key**, and is refused rather
  than keyed wrongly. To key by identity where the referent defines equality, wrap the reference in
  `ByAddress`, whose `Eq` is `===` and whose `Hash` is the address's.
- **A reference to a reference** compares as the inner reference does. **A trait object** (`&Shape`)
  has no `==`. **A pointer compares and hashes by address**, whatever `T` defines. There is no ordering
  on a reference or a pointer. Its other bounds a reference meets through its referent
  ([generics](/reference/generics/#a-reference-meets-its-referent-s-bounds)).

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

### `weak T` — breaking cycles

A cycle of strong references keeps itself alive — a parent holding its children while each child holds
its parent. **A `weak T` does not keep its referent alive**: when the last strong reference goes the
object is destroyed and every weak reference to it becomes empty.

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

- **It is a type, not an operator.** A `&T` becomes a `weak T` wherever a `weak T` is what the context
  asked for, and never the other way: becoming a `&T` is the operation that can fail.
- **Reading one is a question, `get()`**, answering `Option[&T]` with a count taken for the caller.
  No field, method or `==` is reached through a weak reference directly.
- **An empty one is written `None`** — the weak reference's own empty value, not an `Option` — and it
  is the **zero value** of `weak T`, so a struct with a weak field still has one. `None` is also the
  only default a `weak T` parameter can have: anything naming an object would have to outlive every
  frame.
- **`weak &T` says the mode twice**, and **`weak sync T` is refused**: upgrading an atomic weak
  reference is a compare-and-swap loop against a count another thread may be driving to zero.
- Everything a `&T` may point at — a struct, a scalar, an array, a generic instance, a trait object, a
  type parameter — may be held weakly.

```sysl
struct Node
    v: int

var r: &Node = Node(1)
var w: weak &Node = r
```

```error
'weak Node' is already a weak edge to a counted Node, so the '&' says the mode a second time — write 'weak Node'
```

**A weak reference may not be made from a value with nowhere else to live**: boxing a construction
straight into a weak position would leave the weak edge its only holder, and the object dead before
the statement ended.

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

### `&sync T`, `*T` and `[]T`

**`&sync T` is a `&T` with an atomic count**, a distinct type with no conversion either way, chosen
where the object is allocated. It is the only kind that may cross a concurrency domain
([below](#crossing-a-concurrency-domain)).

**`*T` is C's pointer**: no length, no count, manual lifetime. The address of the frame's own storage
is checked by the lifetime rules below; past that it is the [raw tier](#the-raw-tier), which carries
C's whole surface because a subset would only mean a kernel that could not be written here.

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

**A view is three words**: `{ owner, ptr, len }`, 24 bytes on a 64-bit target. `ptr` and `len` name
the range and **`owner` keeps the bytes alive**, since sysl has no collector to find an object from an
interior pointer. Taking a view of heap storage retains, dropping one releases, slicing stays O(1),
and a view of heap storage survives the buffer growing or being dropped:

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

**`owner` is null when there is nothing to keep alive** — a view of static data, of a `*T` region, or
of a local fixed array — and retaining or releasing such a view is a no-op, so allocator-free code
slicing a static or stack buffer pays nothing. A view of a local array is the case the compiler checks
([escape analysis](#escape-analysis)). A `string` is exactly an **immutable, validated `[]u8`**, with
one representation and one implementation.

### Library types: a `Buf` is a shared handle

`Buf[T]` is a handle to a counted block, so **assigning a `Buf` makes a second name for the same
buffer**, as assigning a `&T` does, and a `push` through either is seen through both:

```sysl
import sysl.buf.{Buf, buf}

var a: Buf[int] = buf()
val b = a

b.push(1)

print(a.len(), b.len())
```

```output
1 1
```

A `Map` and a `Deque` are handles the same way. A buffer crosses a domain
only [frozen](#a-frozen-buffer-crosses).

## Lifetimes are blocks

A `*T` is a type, so once one exists it can be carried anywhere — which is wrong for the address of a
local, good only until its block closes. Nothing in a type says how long storage lives. What the
compiler can see is what each body **does** with each address and view, so it works that out, and
**nothing is written for any of it**: no lifetime parameter, no `@escaping`, no "this result views
that argument" marker. The rules are twelve, and each section below is one of them.

### A block's local lives as long as its block

**Frame storage ends with its block.** Frame storage is a local, a by-value parameter or `(self)`, or
a field or element of one; its block is the innermost one declaring it — a function body, an `if` or
`else` arm, a `match` arm, a `for`, `while` or `loop` body, a nested block. A loop body's local is a
new variable every turn. **A temporary ends with its statement.**

So the address of a block's local may not be kept anywhere still there after the block — a local of an
enclosing block, a field of one, a buffer an enclosing block holds, a closure stored in one — however
it gets there: through a local, a struct or variant built around it, a closure that captures it, a
call that answers it, a callee that stores it into what another argument points at, or a block or
`break` that yields it.

```sysl
run()
    var spare = ""
    var q = &spare

    if true
        var s = "abc".copy()

        q = &s

    print(*q)
```

```error
'&s' is the address of 's', a local of the 'if' block, and it is kept in 'q', which outlives that block — keep the value itself, or declare 's' where 'q' is declared
```

**What a block lets go of when it closes is what its locals hold** — a `Buf`'s elements, the object a
`&T` names, a value with a `Drop` — so a pointer into that storage is refused where it is read after
the block:

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

**Inside the block, and into it, the address is free**: a local of the same block or a deeper one may
hold it, a borrowing callee may be handed it, and an enclosing block's local may be pointed at from
inside. What leaves the block by value is a copy, and is never refused.

### The entry file's statements are the outermost block

**The top-level statements of the file a program starts in are the program's outermost block**, and
their locals live until the program exits: that frame is laid down before anything else runs and given
back only as it ends. So it is the one body the outliving rules pass over — its locals' addresses may
be stored in module storage, and a plain `spawn` may borrow them — and every other frame is held to
them:

```sysl
static var held: *int = null

var n = 41

held = &n
print(*held + 1)
```

```output
42
```

### A borrowed pointer cannot escape

**An address or a view of frame storage outlives its block when it is:**

- **returned**, directly or inside whatever carries it back;
- **stored anywhere the block cannot show is its own** — module storage, the heap, a field reached
  through a pointer it did not make, a buffer's slot;
- **handed to a parameter that keeps it**;
- **captured by a kept closure**;
- **held by a task not awaited, or a thread not joined, inside the block** — a call of an `async`
  function is accepted only where its task is awaited or `block_on`'d before the lending frame ends
  ([a task holds its arguments](/reference/async/#a-task-holds-its-arguments-until-it-ends)), and a
  thread that borrows a frame is started [on a scope](#a-thread-borrows-a-frame-only-inside-a-scope).

**Unknown means foreign**, and outliving is refused, except that a frame array or a closure box moves
to the heap instead ([escape analysis](#escape-analysis)). The rule, asked at the call:

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

A function that reads and writes through what it is handed, and passes it only to callees that only
use it, keeps nothing, and the address of anything may be passed to it — **every `*self` method called
on a local is checked this way**, and almost all of them keep nothing.

**Returning the frame's own storage is refused** — whether it is the body's value, a `return`'s, an
arm's tail, or wrapped in `ptr_cast`. `(self)` is a copy in the frame, so a `(self)` method handing out
a field's address is the usual way to write it by accident. Storage reached **through** a pointer or a
reference — `*self`, `&self`, a `*T` or `&T` parameter, an element of a `[]T` — is the caller's to
keep alive, as module storage is, and may be answered. `&make()` makes a slot in the frame
([addressing a value](#addressing-a-value)), so returning it is refused the same way.

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

**Storing through an out-parameter is the caller's to judge**, but the address of a function's own
local written through any out-parameter — or through a pointer read out of one — outlives it:

```sysl
leak(out: **int)
    var n = 1
    *out = &n
```

```error
'&n' is the address of a local of 'leak', and it is stored in '*out', which outlives 'leak' — store the value itself, or storage that lasts
```

**Storage the frame cannot show is its own is foreign.** A place reached through what a call answered
— `current().p`, a chain of calls, a trait object's answer, a local bound to any of them, an element of
a `Buf` — may be held by someone else long after the frame is gone. A call that answers its argument is
no exception: what else it may answer is not something its signature says. The frame's own places stay
open — a local, a field or element of one, and what a local bound once to such an address, or to an
object the frame made, points at — and an address that lasts (module storage's, or one a parameter
brought) may go anywhere.

```sysl
struct Slot
    p: *int

var the_slot = Slot(null)

current() -> *Slot = &the_slot

remember()
    var n = 1
    current().p = &n
```

```error
'&n' is the address of a local of 'remember', and it is stored in 'current().p', which 'remember' cannot show is its own storage — store the value itself, or storage that lasts
```

#### An address held inside what is handed over

**A function may let out what it reads through a pointer as well as the pointer itself.** So the
address of a local stored in a struct, an array or a view whose address goes to a callee is refused
when the callee lets out what it reads there — stores it, pushes it, puts it in a heap object, hands
it to a callee that does, or answers with it:

```sysl
var last: *int = null

struct Holder
    p: *int

keep(h: *Holder)
    last = h.p

run()
    var n = 5
    var h = Holder(&n)
    keep(&h)
```

```error
'keep' lets what it reads through its parameter 'h' outlive the call (it is stored in 'last'), and what is read through '&h' holds the address of a local of 'run'
```

A callee that only reads through it, copies out a number rather than an address, or lets out only what
it reads a step further — through the address the struct holds, which is not this frame's storage — is
accepted. **How the address got there does not matter**: copied out of a field, read through a pointer,
chosen by a branch, stored after the struct was made, or answered by a call, it is followed into
whatever is handed over at whatever depth, and asked of what the callee lets out of what it reads that
many steps through its parameter. **The fields are told apart**: what the callee lets out is asked of
the field the address sits in (a field of a field; an array field's elements all alike; a union's
members are one field), and a read the callee cannot place by field — through a copy of the pointer,
or the pointee whole — counts for every field.

### Keeping is read off bodies

**A parameter is kept if its body lets it, or a field path of what it points at, outlive the call**,
and the answer is followed into every callee, computed callees first, and settled over recursion at a
fixed point: a recursive function keeps a parameter when one of its paths does.

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

#### Taken back before it returns

**A store into foreign storage that the same body overwrites on every way out is not a keep** —
written over in the body, in a `defer`, or by a callee whose own body writes it on every way out. That
is how a sink is lent to a module for the length of a call:

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

The place may be reached through a pointer, spelled out (`s.vm.con`) or through a local holding it
(`val vm = s.vm`), so long as the store and the overwrite are known to reach the same storage: a local
bound once holds one pointer, and a pointer read out of memory is the same one until a call, or a
store of a pointer of its type, may have changed it. A way out that skips the overwrite — an early
`return`, a `?`, a call that never comes back, a `break` past it, one `match` arm without it — leaves
the pointer held, and writing a *different* place (another global, another field, the same field of
another instance) takes nothing back:

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

### Handing it back is not keeping it

**An answer made of arguments is lent for as long as those arguments live in the caller.**
`field(s: *S) -> *int = &s.y` keeps nothing; the caller follows the answer as it follows the argument,
so keeping the answer is keeping the argument, and a function may not hand back an answer made of its
own frame:

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

A pointer the callee **read out of** what it was handed — `q.first()` answering `self.head` — is
somebody else's address, and is the caller's to keep.

### Only a type that can hold an address carries a loan

**An `int`, a `bool`, a float, a bare enum and a struct with no pointer in it answer nothing about
where their arguments lived**, so `Ok(n)` after a C function filled `&n` is an ordinary value. A C
function's pointer answer is taken to be made of its pointer arguments that could hold what it points
at — one to the same type or to a struct holding it, and bytes from bytes, as `strchr`'s is — and not
of an out-parameter of another type, so a handle a library allocated is not made of the `*i32` it
reported an error code through.

#### An address as an integer leaves the analysis

**An address as an integer is a number**: `usize(&marker)` stored, handed on or handed back keeps
nothing, and outlives nothing. It is how a program measures its stack:

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

**Turning that number back into a pointer is the program's responsibility.** `ptr_cast` of an integer
is unchecked, as an `as usize` round trip is in Rust: the compiler follows no address through an
integer — not through module storage, a field, a parameter, an answer or arithmetic — and refuses
nothing; a `ptr_cast` read after the frame has returned compiles just the same and reads whatever the
slot holds by then. No `unsafe` block is asked for, and `@unchecked` has nothing to do with it: the
`import sysl.raw.ptr_cast` a file must write is where a reader finds every round trip. The address
kept **as a pointer** is still the analysis's:

```sysl
var top: *int = null

mark()
    var marker = 7

    top = &marker

mark()
```

```error
'&marker' is the address of a local of 'mark', and it is stored in 'top', which outlives 'mark'
```

### Where no body is in sight, the call borrows

A call the compiler cannot follow into a body is answered by its **signature**, and the answer is the
one C programs live by — **a pointer handed to a call is borrowed for the call**:

| what is called | what it keeps |
|---|---|
| a sysl function | what its body keeps |
| a method through a trait object | what the trait's declaration of the method says: nothing, unless it says [`@keeps(p)`](/reference/attributes/#keeps-and-answers-on-a-trait-method), or `@keeps(self)` for the object itself; an `async` method's task holds every argument until it is awaited |
| a closure, a `&Fn` | nothing, unless its type marks the parameter [`@keeps`](#a-callable-that-keeps-what-it-is-handed); what is read through one it marks `@lets_out` may be kept |
| a function through a pointer | nothing, unless the pointer type marks the parameter `@keeps` or `@lets_out` |
| an `extern` | nothing, and nothing it reads through a pointer, unless its declaration says [`@keeps(p)`](/reference/ffi/#keeps-a-c-function-that-holds-on-to-a-pointer) |

- **The answer is fresh** — an `extern`'s is storage the library made — unless the declaration says
  [`@answers(p)`](/reference/ffi/#answers-a-c-function-whose-answer-points-into-an-argument), and then
  it is made of what `p` was handed. An inline-assembly output is fresh the same way. The answer of a
  call through an object may be made of the receiver, and of nothing else unless the declaration says
  `@answers(p)` — Rust's elision rule for a method. A callable's type cannot say `@answers`, so a call
  through one may answer with anything it was handed.
- **A body reached through such a type that keeps an unmarked parameter is refused where it is
  written**: an `impl` method keeping what its trait's declaration does not name, a closure keeping its
  parameter, a function whose address is taken keeping one. The declaration of an `extern` is trusted:
  a C function holding a pointer past the call without saying so is a wrong declaration.
- **There is no whole-program answer**: no table of the bodies a call through an object or a pointer
  may land in is consulted, since which one it lands in is decided when the program runs.

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

**A default is a promise, so the body behind it is held to it.** That is what makes a lock's `with`
safe without an annotation: the closure it calls borrows the value's address for the call, and a
closure that keeps it is refused where it keeps it — the one mistake a returned address could not
prevent, using it after the release:

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

**A closure is judged where it is written**, capture by capture, by what its body lets out of each,
and again at every call it is handed to, so it makes no difference whether a call runs it, it is boxed
behind a `&Fn`, or a `@scoped` member runs it later. A capture holding the frame's address that the
body stores in module storage, or into what a parameter of the closure points at (a lock's `with`
hands the body the value a module lock guards), outlives the frame. So does `&capture` written in the
body: the closure holds the capture by value in its own storage, and the refusal points at the closure,
never into the body of whatever runs it. A capture of a heap value or a plain number is stored freely.

```sysl
static var idle = 0
static var kept: *int = &idle

run(f: () -> unit) = f()

closure() -> int
    var n = 5
    val p = &n
    run(() -> kept = p)
    0

print(closure())
```

```error
a closure lets its capture 'p' outlive the call (it is stored in 'kept'), and 'p' holds the address of a local of 'closure' — capture the value itself, or storage that lasts
```

#### A callable that keeps what it is handed

A callback that keeps what it is handed — an interpreter's primitive putting its handle into the term
it builds, a visitor collecting nodes — says so in its type, with
[`@keeps`](/reference/types/#a-parameter-the-callable-may-keep-keeps) on the parameter:
`&Fn(@keeps *Handle) -> Step`, or `*extern(*M, @keeps *isize) -> unit` for a function pointer. A
closure or function taken at that type may keep that parameter, and every other parameter is still
borrowed. **A call through the type is held to the mark the way a call of an `extern` is held to its
`@keeps(p)`**: the address of storage that ends is refused there, and a function handing its own
parameter on there keeps it. A pointer that keeps nothing stands where a marked one is asked for,
never the other way round.

```sysl
import sysl.buf.{Buf, buf}

struct Handle
    n: int

type Visit = &Fn(@keeps *Handle) -> unit

var seen: Buf[*Handle] = buf()

visit_one(f: Visit)
    var mine = Handle(1)
    f(&mine)
```

```error
a call through '&Fn(@keeps *Handle) -> unit' keeps its parameter 1 (its type says '@keeps'), and '&mine' is the address of a local of 'visit_one'
```

Without the mark, the closure is refused where it keeps the parameter, and told the type that would
let it. **An unmarked parameter is answered by the type, never by the closures made at it**: the call
borrows the argument and everything read through it, so a closure that lets out what it reads through
a pointer is refused as well, whatever any caller hands it.

```sysl
import sysl.buf.{Buf, buf}

struct Handle
    n: int

var seen: Buf[*Handle] = buf()

val f: &Fn(*Handle) -> unit = (h) -> seen.push(h)
```

```error
a closure borrows what it is handed for the call, and this one keeps its parameter 'h' (it is passed to 'push', which keeps it) — whoever calls it may hand it the address of their own storage. Copy what this needs into storage of its own, or let it keep it by marking the parameter '@keeps' in the type it is made at: 'Fn(@keeps *Handle) -> unit'
```

**A call through a function pointer borrows every argument**, so a function whose address is taken is
held to that where it is written — even one that only stores what it is handed into what another
parameter points at, such as a primitive writing its stack pointer back into the machine — unless
every type its address is taken at marks the parameter:

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
```

```error
'halt' keeps its parameter 'sp' (it is stored in 'm.sp'), and its address is taken — a call through a function pointer sees no body
```

**A `loop` nothing leaves is a way out of a function whose address is taken**, holding what it holds
at its head. Called where its body is in sight, such a function never returns to its caller, whose
frame therefore outlasts everything it stores; but a pointer may be handed to a starter —
[`Context.start`](/library/fiber/), [`spawn`](/library/threads/) — that returns while the body runs
on, so an entry that stores its argument and then loops is refused as one that returns, and so is an
entry that hands its argument, at any depth, to a function that stores it and loops for ever:

```sysl
import sysl.fiber.{Context, Stack, switch}

struct Job
    home: Context
    me: Context

var held: *Job = null
var job = Job(Context.new(), Context.new())

serve(j: *Job)
    held = j
    loop
        switch(&j.me, &j.home)

entry(j: *Job)
    serve(j)

main()
    val s = Stack.reserve(65536).unwrap()
    job.me = Context.start(s, &entry, &job)
    switch(&job.home, &job.me)
```

```error
'entry' keeps its parameter 'j' (it is passed to 'serve', which keeps it), and its address is taken — a call through a function pointer sees no body
```

**A callable that only lets out what it reads is marked
[`@lets_out`](/reference/types/#what-the-callable-reads-may-outlive-the-call-lets-out)**:
`*extern(@lets_out *Heap) -> unit`. A function or closure taken at the type may let out what it reads
through that parameter, and is still refused where it keeps the parameter itself. A call through the
type borrows the argument — a collector's heap on the caller's stack may be handed there — but what is
reachable through it may be kept anywhere, so storing the address of storage that ends into it before
the call is what is refused.

**A stored callback keeps what it captured, and nothing else.** Storing a closure — in a field, module
storage, a buffer, a trait's implementation — is refused only where it captured an address that is not
the program's to give away; one capturing nothing, a number, a `&T`, a string or a struct by value may
be stored anywhere. A trait's method borrows a view it is handed the same way
([traits](/reference/traits/#a-method-borrows-what-it-is-handed)).

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

### Escape analysis

**A frame array or closure box that would outlive its block moves to the heap, silently.** Its type
stays the same, `--explain-escapes` lists every move, and it is refused only where there is no
allocator. A view of a local fixed array has a null owner and is good only while the frame lives, so
the compiler finds the views for which that matters:

| a view of | `owner` | safe to keep? |
|---|---|---|
| a heap buffer | the buffer | yes — retained |
| static data, a string literal | null (immortal) | yes — never freed |
| a `*T` region | null | the programmer's problem — unless a [`@views(self)`](#a-view-borrowed-from-its-receiver) member hands it out |
| **a local fixed array** | **null** | **only while the frame lives** |

A view of a local array **escapes** when it is returned (directly or inside a struct, an enum, an
`Option`, a tuple or one slot of a multi-result list), stored into anything that outlives the frame,
passed to a parameter the callee keeps, captured by a closure that escapes, or assigned into a local
that escapes (resolved as a fixed point over the function's locals). Indexing, iterating, sub-slicing
into a local that stays local, comparing and passing to a callee that only reads allocate nothing.
Field by field: a callee storing `s.a` lets out the view in `s.a` and not the one in `s.b`, and a
result whose type cannot hold a view is not taken for one. A call with no body — through a trait
object, an `extern`, a `&Fn`, a function pointer, or an `async` call whose task is not awaited where it
is made — lets out what its declaration says, and a view handed to a callable is promoted, since no
type says a view is only borrowed.

**With an allocator, the array is promoted**: allocated as a counted buffer instead of a stack slot,
with the view's `owner` pointing at it, and nothing else changes:

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

- **The array keeps its type, and only its storage moves.** A promoted `[8]u8` is still a `[8]u8` — a
  constant length, a value that copies on assignment, every index emitted as before.
- **Which array moves is the root of the escaping view.** An index step is walked through, since an
  element of a local array of arrays is part of that array; a field step is not. Only arrays both
  viewed and escaped move.
- **A temporary moves too.** An array the frame computed and nobody named — a literal, a call's answer
  such as a `[32]u8` digest sliced straight into module storage, a field of either — is nobody's, so it
  is this frame's, and a view of it that gets out is given storage of its own. A `...T` variadic packs
  its arguments into exactly such a literal. A literal written where a `[]T` is expected and handed to
  a call that keeps nothing of it, and whose answer could not carry a view of it, is laid in the frame
  and allocates nothing.
- **A view that outlives its scope is promoted too**, not only one that leaves the frame: a
  temporary's view kept in a local read on later lines, and a view of an array declared in a loop body
  or an `if` arm kept in an enclosing block. Nothing moves where nothing outlives anything.
- **Module storage is never a root**, whatever the path to it; it outlives every frame where it is.
- **An element of a view or of a `Buf` is not the frame's storage**: `rows[i][..]` and `b[i][..]` carry
  the owner of the storage they live in, and outlive the buffer growing or being dropped. A program's
  own container writes `index_view` to answer its storage's view, and is the same — it is never
  written for it, and a view of part of an element through the trait's default is refused
  ([arrays](/reference/arrays/)); one whose elements are its own bytes is
  the frame's where it is, so a view of part of one may leave a frame only when the container lives in
  module storage or behind a `*T`.

**Two roots have nowhere to be promoted to**, and are refused: an array a caller passed **by value**,
which is the caller's layout, and an array that is a **field** of a **named** struct on the frame,
since a field moves only with its struct. A part of a temporary is not one of them: the whole
temporary moves.

```sysl
first_two(a: [4]int) -> []int = a[0..<2]

print(first_two([1, 2, 3, 4]).len)
```

```error
a slice of an array this frame owns is returned, so it would outlive the array, and the storage is not this body's to move — 'a' is an array this body holds by value without having declared it, such as a parameter, so there is no declaration to move. Declare it as a '[]T', which makes a buffer of its own and owns it, or as a '&[N]T' where the length is fixed
```

**Without an allocator every promotion is a compile error** at the view, and so is every literal
argument a callee keeps. A `@no_alloc` module is told it declared so; a machine whose manifest says
`capabilities { heap = false }` is held to the same line in every module, and the refusal names the
machine (`'thumbv7em-freestanding' provides no allocator`) rather than leaving the link to say
`undefined symbol: malloc`. Growable arrays, escaping closures and `&T` creation are refused there the
same way.

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

#### Promotion is silent, not hidden

**`--explain-escapes`** reports every promotion and the route that forced it — one line per array, in
source order, on stderr, accepted by every subcommand, at the **view that forced the move** rather than
the declaration. This is Go's `-m`. A program that must not allocate says `no alloc`, and the
compiler then enforces it rather than reporting it.

```
$ sysl build --explain-escapes tty.sysl
tty.sysl:31:12: 'buf' is promoted to the heap, because this view of it is returned
```

The idiom worth reaching for first, even with an allocator, is **return a count and let the caller
slice its own buffer** — what `snprintf` does, and what most kernel code wants.

### Views own their heap storage, and a lent view takes no count

**A view of heap storage owns a count of it**, so it survives growth and drop and may escape. **A view
that is only lent** — handed to a call, or held in a local that never outlives its block — needs no
count of its own while its parent's count holds the storage
([what a count costs](#a-view-of-something-the-frame-already-holds-takes-no-count)).

**A view of storage inside a `&sync T` may therefore be lent, but may not escape.** A count taken
through a view is the ordinary one, so such a view takes none at all: `sum(f.data[1..<3])` on a
`&sync Flash` is accepted, and the same view returned, stored past the frame, captured by a kept
closure or held by the callee is refused. The box must be reached through a binding that keeps
holding it, in a body and in a module initializer alike — not a field of another box, a module
`var`, or a local the body stores into
([arrays](/reference/arrays/)) — and the view may not be held in a local declared outside that
binding's block, since the binding, and the box with it, ends there:

```sysl
struct Flash
    data: [8]i32

make(k: i32) -> &sync Flash = Flash([k, k, k, k, k, k, k, k])

main()
    var v: []i32 = []
    if v.len == 0
        val b = make(7)
        v = b.data[0..<2]
    print(v.len)
```
```error
is kept in 'v', a local of the body, which outlives that block
```

#### A view borrowed from its receiver

A null owner is right for static data and wrong for memory **a C library owns**: an object wraps the
library's handle, frees it in its destructor, and hands out a view of what the handle points at, which
nothing keeps alive. **`@views(self)` on the member says so, and the compiler holds every caller to
it:**

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

The view is an ordinary `[]const u8`; nothing changes at run time. The mark adds one lexical fact:
**the view lives no longer than the name it was called through**, and four things are refused:

1. **a receiver that is not a name** — a temporary is freed at the end of its statement;
2. **the view leaving the function that holds its receiver** — returned, or what the body comes to,
   unless the member is itself `@views(self)` and the view is borrowed from its own `self`;
3. **the view stored where it outlives the receiver** — a local bound before the receiver, a field
   reached through a reference or pointer, module storage (a local bound *after* it, or a struct value
   one holds, closes first and is fine);
4. **freeing the receiver while a local holding the view is in scope** — assigning the name, a
   `*self` call on it, or a call that may write an object of its type.

What a view is copied into — a sub-slice, a struct or `Option` around it, a closure capturing it, a
name a pattern binds, a loop variable over a view of views — is borrowed from the same receiver; a
value that cannot hold a view (`v.len`, `v[0]`, a rendered string, a `&sync T`) is borrowed from
nothing. **A call handed the view answers with it only where its body does**: `encode(out.view_mut())`
answering a `Result[Buf[u8], string]` it built is borrowed from nothing, while `id(v) -> []u8 = v`
hands the view straight back. A call through a function value or a trait object names no body, so
its answer is taken to hold everything it was handed.

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

**It is narrow on purpose.** The mark names only `self`, marks only a member whose result is a view
(`[]T`, `[]const T`, `string`), and is in no type: no lifetime parameters, and no borrow tracked
through a struct a member returns. A trait member may carry it, and then a call through a trait object
is held to it; an implementation inherits it, and an implementation may not add it where the trait's
member lacks it. A marked member is called only on a value (`Column.bytes(c)` is refused). A member
whose answer is simply read out of its receiver's storage needs no mark: the compiler works that out
from the body ([below](#a-method-s-answer-borrowed-from-its-receiver)).

### Nothing moves under a live interior name

**While a pointer, a `ref` or a view into relocatable storage is live, what could move or free that
storage is refused**: a `@relocates` call, an assignment to an owning step of its path, and a write
through a second name. The check is decided from types and from what callees' bodies write, and never
asks where the name is *used* — the question that would need lifetimes.

#### The one rule: what may move underneath it

A stored address is only as good as the storage staying where it is. C# has ref locals and needs no
such rule, because its collector keeps the old array alive; sysl has none in this tier:

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
assigned, and no mutating method may be called on a prefix of one.** The discriminator is
**ownership, not indirection** — only a step that *releases* something when overwritten can strand it:

| step | hazard? | why |
|---|---|---|
| a `&T` | **yes** | overwriting it drops what it held, and that release may be the last |
| a view (`[]T`) | **yes** | the same — a view owns its buffer |
| a `*T` | no | a raw pointer owns nothing, so `p = q` frees nothing |
| a field, a fixed array, or an element of one | no | that storage *is* the enclosing object's bytes |

A step is held still when it is an **owning** step the path goes **through**, so the place itself is
never held: `ref r = h.node` names the slot, and `r = other` and `h.node = other` are one statement
written two ways, while `h = other` on a `&Holder` is refused. Writing a value that holds an owning
step by value writes the step too (`h = H(…)`). A program reaching its tables through a `*Self`
receiver has no owning step in its chains and is asked for nothing.

- **A `*self` call on the owner is refused** whether or not that method reassigns anything — a module
  compiles against signatures, and the cost is writing the ref one line later.
- **A call that runs a closure writing an owning step is refused** — the closure handed directly,
  through a local, a `&Fn` or a struct, or called; one writing only storage the ref does not stand
  through is run without complaint.
- **A `@relocates` member moves a container's storage whatever its receiver mode.** A `Buf` is a
  handle, so `push` takes `self` and still reallocates; each member that can move the storage or give
  an element up is marked, and the mark is what the check asks.

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

**A `*T` taken by `&` into the same storage is held to the same rule for as long as it is live** — the
pointer, a copy, or an address computed from it. A pointer has no block, so what counts is its **last
use** on any path, a later turn of a loop and a `defer` (a use on every way out of its block) included;
once the last use is behind it, the storage may move freely. A pointer kept in a local aggregate is
followed through it, and so is one into what a match arm, an `is` test or a `for` bound out of a value
made for it.

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

**A function handed the buffer is asked what its body does with it**: one that calls a `@relocates`
member on a parameter, or hands it on to one that does, may not be handed a pointer into that argument
too, nor may the pointer be used after the call.

**A second name for the same object is held to the rule.** Whether two `&T`s or `Buf`s name one object
is a run-time fact, so the question is asked of **types**: while a ref stands inside storage a value
of type `T` holds, writing storage held in any `T` other than along the ref's own path is refused —
directly, or by a call whose body (or anything it reaches) may write a `T`. A call through a closure
names no body and is refused; a call through a trait object is a call to the method in that slot of
every table the program has for the trait, in a program's own build, and names no body in a library's
([edge cases](#edge-cases)); a function with no body in the program is judged by what it can get hold of. A callee reaching a `@relocates` member on a buffer of the
ref's type writes it too, however it got the buffer.

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

What stays open is everything that cannot be the ref's storage: other fields of the ref's own object
along its path (`h.node.count = 2` beside `ref e = h.node.cell[0]`), values of unrelated types, calls
whose bodies only read, and anything once the ref's block has closed. A view of a buffer is not a
pointer into it: it keeps its storage, so growing the buffer under a view is accepted. **What this does
not make safe is a `*T`**: a ref rooted at a raw pointer, or storage reached through a reinterpreting
cast, is the raw tier's bargain.

### Capture follows keeping

**A borrowed closure refers to its captures, and a kept closure holds copies.** A closure that is
returned, stored where it outlives the block, handed to a parameter that keeps it, built in an `async`
body, or erased into a `&sync Fn` holds a copy of each variable, taken when it is built; otherwise it
holds each variable by reference, and a write in it is a write to the variable. A reference to a local
costs a pointer and takes no count, and a borrowed closure handed on as a `&Fn` is built in the frame:

```sysl
import sysl.buf.{Buf, buf}

each(xs: []int, f: int -> unit)
    for x in xs
        f(x)

var best = 0
var xs = [3, 9, 2]
each(xs[..], n -> if n > best then best = n)

var n = 1
val peek = () -> n
var kept: &Buf[&Fn() -> int] = buf()
kept.push(() -> n)
n = 5

print(best, peek(), kept[0]())
```

```output
9 5 1
```

**A write to a copy that is read after it would be lost, and is refused** — where the closure never
reads the capture back, or the enclosing body uses the variable after the closure
([expressions](/reference/expressions/#a-write-to-a-copy-that-nothing-will-see-is-refused)). **So is a
write to a by-value parameter that nothing reads after it**
([declarations](/reference/declarations/#a-write-to-a-by-value-parameter-nothing-reads-is-lost)):

```sysl
var later: Option[&Fn() -> unit] = None

install(f: &Fn() -> unit) = later = Some(f)

var hit = false
install(() -> hit = true)
print(hit)
```

```error
this closure captures by value, since it is handed to 'install' on line 6, which keeps its parameter 'f' (it is stored in 'later'), so it holds a copy of 'hit' — so assignment would reach that copy rather than the variable it came from, and nothing in the closure reads it back. Where you mean to change 'hit', hold it as a '&bool' and write through it, or have the closure return the value
```

```sysl
struct Counter
    n: int

bump(c: Counter) = c.n += 1

var c = Counter(1)

bump(c)
print(c.n)
```

```error
this '+=' changes 'c', a copy of what the caller passed, and nothing reads 'c' afterwards, so the change is lost — take 'c: *Counter' to change the caller's value, or return the value
```

### A value's own storage is not foreign

**Where it is stored decides, not only that it is.** None of these is a keep:

- a pointer stored into what it points at — a node linked to itself, a struct whose field points into
  its own buffer — which ends exactly when that storage does;
- nodes linked inside one local or one local array;
- a store into what *another* parameter points at, which is the caller's to judge: if that argument
  points into the caller's frame, the pointer stays in the frame too — so an out-parameter writes into
  the caller's storage;
- a value pushed onto a `Buf` the function made itself, held only as long as that buffer — unless the
  buffer gets out (returned, stored, captured by a kept closure, handed to a keeping callee) or
  something read back out of it is kept. A buffer the function was handed, reached through a `ref`,
  or copied from module storage is not its own.

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

## The annotation layer

Every annotation is a promise the compiler **checks against the body where it has one, and trusts
where it has none**. They exist for the code the rules above cannot see into:

| spelling | where | says |
|---|---|---|
| `@keeps(p)` | `extern`, trait method | `p` may be held past the call ([ffi](/reference/ffi/#keeps-a-c-function-that-holds-on-to-a-pointer), [attributes](/reference/attributes/#keeps-and-answers-on-a-trait-method)) |
| `@keeps *T` | a parameter of a callable or function pointer type | the same, for whatever is made at that type |
| `@lets_out *T` | a parameter of a callable or function pointer type | what is read through it may be held past the call; the pointer itself is borrowed |
| `@answers(p)` | `extern`, trait method | the answer is made of `p` |
| `@scoped(p)` | a primitive | `p` is held only while the caller waits — a scope's join, an `await`, a wake |
| `@bounded(f)` | a function, a member | every run of the callable `f` finishes before the call returns, so a closure written there may capture the caller's views and addresses ([attributes](/reference/attributes/#bounded-f-every-run-of-a-callable-finishes-before-the-call-returns)) |
| `@views(self)` | a member | the view it answers is borrowed from the receiver |
| `@relocates` | a member | it may move or give up the receiver's storage |
| `@crossing(p)` | a function, a member | `p` reaches another concurrency domain ([below](#crossing-a-concurrency-domain)) |
| `ptr_cast`, `@unchecked` | expression, block | the unchecked tier; `usize(p)` is a number, and the compiler follows no provenance through integers |

**Released in another context, the primitive says `@scoped`.** A scope's join, the `deliver` that ends
an `await`, a kernel's `wake` hold a pointer past the call and let go of it somewhere the compiler
cannot follow, while the caller is suspended or still inside its scope. The primitive declares it once
with [`@scoped(p)`](/reference/attributes/#scoped-p-on-a-pointer-held-only-while-the-caller-waits) and
is trusted, so `block(&local)` and `await read(&buf)` carry no annotation. Handing `p` on to a callee
that keeps it is refused unless that callee says `@scoped` too.

## Threads

### A thread borrows a frame only inside a scope

**A `*T` that crosses into another domain is kept**: the thread may read it after the starting call
has returned, so `spawn(&work, &local)` is refused whether or not the handle is joined afterwards —
`spawn`'s `arg` is `@crossing`, and the refusal names what works:

```sysl
import sysl.threads.*

struct Tally
    n: int

add(t: *Tally)
    t.n = t.n + 1

count() -> int
    var t = Tally(0)

    spawn(&add, &t).unwrap().join()
    t.n

print(count())
```

```error
'spawn' keeps its parameter 'arg' (it reaches another concurrency domain), and '&t' is the address of a local of 'count' — a thread that borrows the frame of 'count' is started on 'sysl.threads.scope', which joins it before the scope returns; anything else is handed storage that lasts
```

**[`scope`](/library/threads/#scoped-threads) joins every thread started on it before it returns**,
whichever way its body leaves, and its `spawn` is
[`@scoped(f)`](/reference/attributes/#scoped-f-a-member-that-finishes-what-it-starts-before-its-scope-closes),
so a closure started there may hold a view of the caller's array or the address of its local — Rust's
`std::thread::scope`:

```sysl
import sysl.threads.scope

halves() -> int
    var v = [0; 8]
    val all = v[..]
    val half = all.len / 2

    scope((s) ->
        s.spawn(() -> for i in 0..<half do all[i] = 1)
        s.spawn(() -> for i in half..<all.len do all[i] = 2))

    var sum = 0

    for x in v do sum += x

    sum

print(halves())
```

```output
12
```

- **What a scoped thread borrows has to outlive the `scope` call, not only the body**, so the body's
  own locals, a nested block's and its temporaries are refused (Rust's `'env` against `'scope`). A
  helper handed `s`, and a wrapper forwarding to `spawn`, are held the same way. A scope nested in the
  body may borrow the outer body's locals.
- **A fan-out needs no joining loop**: whatever the body leaves unjoined, the scope joins. A handle's
  `join` answers what its closure answered, and `scope` what its body answered. A function handed the
  `*Scope` may start threads with its own caller's storage; one opening a scope of its own keeps
  nothing it is handed. A scope may be opened inside a scoped thread, and its threads may borrow that
  thread's locals. A program with no heap opens [`scope_in`](/library/threads/#scoped-threads), whose
  thread records are slots the caller lays out, and starts threads with `start(&body, arg)`, which
  may be handed a local's address on the same terms.
- **A wrapper keeps what it hands `spawn`**, so a frame address handed to it is refused at the
  wrapper's call, and the refusal still names the scope.
- **A plain `spawn` is for storage that lasts** — a box shared as `&sync`, or module storage — so the
  thread may outlive its caller and never be joined. The entry file's own statements are exempt: their
  frame outlives every thread.

```sysl
import sysl.threads.scope

halves() -> int
    scope((s) ->
        var v = [0; 8]
        val all = v[..]

        s.spawn(() -> all[0] = 1)
        v[0])

print(halves())
```

```error
'() -> all[0] = 1' holds a view of 'v', a local of 'call' — a thread started on a scope may borrow only storage that outlives the 'scope' call, and 'v' does not; declare 'v' before 'scope'
```

## A destructor

**`impl Drop for T` says what a type does when the last reference to one of its values goes.** It
declares one member, `drop(self)`, answering nothing. It is for the resource the language does not
manage — a descriptor, a mapped region, a handle a C library made — said once, beside the type:

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

**`defer` is the other way to say it, and neither replaces the other.** `defer close(f)` covers every
site a program can *name*; under ARC a value can also die where there is no site to write one — an
element of a `[]&Handle` goes when the slice does — and there only a destructor can be written. Where
both work, the destructor is written once and a caller cannot forget it.

**It costs nothing to have.** Release already calls through a per-payload hook, and a destructor is a
call at its top. A destructor is in the program only where the program holds a value of its type, so
a library type's destructor costs a program that never makes one nothing, and on a bare-metal target
the hooks it would call are not asked for.

**What ARC and `Drop` guarantee:**

- **Every heap object is released exactly once, on every path**: a `return`, a `?`, a `break`, a
  `continue`, a `match` arm, a `defer` and an unwound temporary.
- **The order is Rust's**: locals in reverse order of declaration at the end of their block, each
  deferred statement in its written place among them; temporaries at the end of their statement;
  fields and elements in declaration and index order.
- **`drop(self)` runs before the object's own fields are released**, sees `self` intact, and may not
  resurrect it.
- **A type with `Drop` is a reference type**, so only `&T` of it exists.
- **A copy of a value retains its `&T` fields.**
- **Cycles leak unless broken with `weak`.**

### A type with a destructor is a reference type

A value type is copied, and a copy is not a second resource, so a destructor runs only for a value on
the heap. **Building one makes a `&T`, with nothing written** — an inferred local, a temporary, a
branch, a field, an element, an `Ok` — and a position naming the count keeps it (`val t: &sync Thing
= Thing(1)`; a trait object boxes on its way in):

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
drop 3
drop 4
drop 2
drop 1
done
```

**Every spelling that would hold one by value is refused**, with the `&T` to write: a binding, a
parameter, a result, a field, an enum payload, module storage, and any of those inside a `Buf`, an
`Option`, an array, a slice or a tuple — at every level that owns the resource, not only the outermost:

```sysl
struct Encoder
    state: *u8

impl Drop for Encoder
    drop(self) = print("closing")

open() -> Result[Encoder, string] = Ok(Encoder(null))

print("ready")
```

```error
'open' hands back 'Encoder' by value, inside 'Result[Encoder, string]', and 'Encoder' has a 'Drop' — a type with a destructor is a reference type, and building one makes a '&Encoder'. Write '&Encoder' in its place
```

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

**A copy is never made into a second owner.** The one by-value value a program still sees is a copy —
a method's `self`, or `*r` — which may be read and passed to a method, but not boxed:

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

**A generic is asked at the instantiation**: its signature (`id[T](x: T)` called with `*r` binds `T`
by value, and the refusal names the parameter), a generic type by what its fields hold (`Box[T]`
holding a `&T` may be bound to `Thing`; `Buf[Thing]` and `Option[Thing]` may not), and **its body,
once per instance**, naming the instance and the call that asked for it:

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

- **`@consumes` is unchanged**, and there is no use-after-move.
- **A destructor needs an allocator**: a `@no_alloc` module building one is refused with `a reference
  needs an allocator`. Where there is none, `defer` is the tool.
- **An error path needs closing by hand**: where a C call hands back a handle *and* fails, the result
  is an `Err`, no box is ever made, and the handle is closed where it stands.
- **A handle that hands out a second value needs no second type.** A `(self)` method cannot name its
  box, so boxing `self` is refused; [a `&self`
  method](/reference/declarations/#a-self-method-may-keep-what-it-was-called-on) is handed the box,
  and the value it builds takes a share rather than becoming a second owner.
- **It is not called for a value in a reference cycle**, nor **for module storage at exit**: there is
  no exit pass, so a buffered writer held in a static is flushed by the program.

### When a value is dropped

**A reference goes at the end of the block that holds it**, so a local in a loop is dropped at the end
of every trip, and a `continue` or `break` drops it as the trip's end would:

```sysl
struct Res
    name: string

impl Drop for Res
    drop(self) = print(s"drop ${self.name}")

for i in 0..<3
    val r = Res(s"trip $i")

    if i == 1 then continue
    if i == 2 then break
    print(s"body $i")

print("after")
```

```output
body 0
drop trip 0
drop trip 1
drop trip 2
after
```

- **A temporary is dropped at the end of its statement**, a value made only to be handed to a call
  among them.
- **Assigning to a variable drops what it held**, at the assignment.
- **Taking a value out of an enum, or binding it in a `match`, shares it** rather than moving it, and
  a closure holds what it captured until it is itself dropped; the value goes once, with the last share.
- **Every way out of a block drops what it holds** — its end, a `return`, a `?`, a `break`, a
  `continue` — innermost block first. **A trap runs nothing**: no destructor and no `defer`.

### In what order

Within one value the order is the declaration's — a struct's fields as declared, a tuple's by position,
a slice's or a `Buf`'s elements by index — and teardown is depth first, so each field comes apart whole
before the next. Teardown runs from a worklist rather than by recursion, so a million-node list comes
apart in constant stack:

```sysl
import sysl.buf.{Buf, buf}

struct Res
    name: string

impl Drop for Res
    drop(self) = print(s"drop ${self.name}")

struct Inner
    x: &Res
    y: &Res

struct Outer
    a: &Inner
    b: &Res
    c: (&Res, &Res)

hold()
    val o: &Outer = Outer(Inner(Res("a.x"), Res("a.y")), Res("b"), (Res("c.0"), Res("c.1")))
    var all: Buf[&Res] = buf()

    all.push(Res("e0"))
    all.push(Res("e1"))
    print("made")

hold()
```

```output
made
drop e0
drop e1
drop a.x
drop a.y
drop b
drop c.0
drop c.1
```

**A destructor runs before the fields of the value it tears down, and its own locals are dropped at the
end of its body — still before those fields:**

```sysl
struct Res
    name: string

impl Drop for Res
    drop(self) = print(s"drop ${self.name}")

struct Node
    leaf: &Res

impl Drop for Node
    drop(self)
        val note = Res("a local of drop")

        print(s"drop node, ${self.leaf.name} still here")

hold()
    val n = Node(Res("field"))

    print("holding")

hold()
```

```output
holding
drop node, field still here
drop a local of drop
drop field
```

### What a destructor may do

It borrows `self` rather than taking a count — the count is already zero. **It may read every field,
call any function, build and drop values, and keep a share of a field**, which then goes with its last
share rather than with the value. **It may not keep the value itself**: boxing `self` would be a second
owner of what is being torn down. **A `weak` reference to the value already reads as gone** while its
destructor runs, since the count is zero before it is called.

```sysl
struct Node
    n: int

var saved: Option[&Node] = None

impl Drop for Node
    drop(self)
        saved = Some(self)

main()
    print("ready")
```

```error
this makes a '&Node' out of a copy of a 'Node' value, and 'Node' has a 'Drop' — the copy would be a second owner of the resource, and its destructor would run once for each
```

### Where `defer` sits

[`defer`](/reference/statements/#defer) is the answer for what the language does *not* own: a
descriptor, a `FILE*`, a block from `malloc`, a lock. **Leaving a block undoes it in reverse** — each
deferred statement reached and each local declared, last first — so a `defer` runs after every local
declared below it and before every local above it, and every local it names is still alive. **It owns
nothing and allocates nothing**, so it stays available under `no alloc`. **A trap runs nothing.** A
`return`, `break`, `continue` and `?` undo the blocks they leave in the same order; an
[`errdefer`](/reference/statements/#errdefer) runs only when the function is failing. So a lock
`defer`-unlocked stays held while everything made under it is given back:

```sysl
struct Conn
    name: string

impl Drop for Conn
    drop(self) = print(s"close ${self.name}")

serve()
    val a: &Conn = Conn("a")
    print("lock")
    defer print("unlock")
    val b: &Conn = Conn("b")
    defer print("flush")
    val c: &Conn = Conn("c")

    print(s"serve ${a.name}${b.name}${c.name}")

serve()
```

```output
lock
serve abc
close c
flush
close b
unlock
close a
```

## Crossing a concurrency domain

A `&T`'s count is not atomic, so it is safe **within one concurrency domain** and nowhere else: a
`&T` may not leave its domain. **`&sync T`** is the exception, a distinct type with no conversion
either way — a count is atomic or not from the moment the object exists:

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

Taking a share is a relaxed increment; giving one back is a release decrement, and the share reaching
zero acquires before destroying, so the freeing thread sees every other thread's writes (a `fence
acquire`, or an acquire load under `-fsanitize=thread`, which does not model a standalone fence).
**`&sync T` makes the *reference* safe to share, not the object safe to mutate** — that wants a
`Mutex`. A `&T` in `@thread_local` storage belongs to one thread and needs no `&sync`.

### Module storage reached from another domain

A closure erased into a `&sync Fn` is asked about what it captures **and about the module storage it
reaches**, however many calls down. What may be reached is what may be captured: a scalar, an
`Atomic`, a `&sync T`, a raw pointer, `@thread_local` storage, and a `val` laid down as constant data
(a `val` over a slice literal is one; a `val` over a call is not, and a `[]T` without `const` is
writable). An interrupt handler — the `interrupt` convention, or
[`@domain(interrupt)`](/reference/attributes/#domain-interrupt-a-function-entered-on-top-of-whatever-was-running)
— is held to it too; a bare `@export` is not, since nothing says a second domain exists until `@domain`
does.

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

### A guarded value crosses with its lock

**A `Mutex[Map[...]]` may be reached from another domain, whatever it holds.** `Mutex[T]` is
`@guards(inner)` over a `Guarded[SleepLock, T]` that is `@guards(value)`: its members are the only way
to the `T`, each takes the lock first, and a guarded field that is itself guarding is looked through.
**What leaves through `with` or `try_with` is held to the rule a capture is**, at the call: an `int`
passes, and so does a value built fresh inside the closure — a literal, a `.copy()` of a string, a
`Buf` it makes and fills with copies, as long as that local goes nowhere but out through the answer
(stored anywhere else as well — pushed into the guarded value, bound to a second name, handed to a
function, its address taken — it is refused). A `.copy()` of a `Buf` is fresh where its elements hold
no count, and one of strings or `Buf`s copies only the handles. A value read out of the guarded one
still shares its count, and is refused; the guarded value held as a `&sync Mutex[...]` and captured
crosses whole. A struct of the program's
own opts in with `@guards(field)` over a `private` field. **Not checked**: a closure that *stores*
something read out of the guarded value where it outlives the call.

```sysl
import sysl.container.{map, Map}
import sysl.threads.Mutex

static var names: Mutex[Map[string, string]] = Mutex.new(map())

print(names.with((t) -> t.get("a").unwrap_or("none")))
```

```error
'sysl.threads.Mutex.with' answers a value out of the 'value' it guards, and once the guard is let go that value is shared with whoever holds it next, so every count it reaches has to be atomic — but this answers a 'string', which may share its bytes' count with the guarded one. Answer a fresh string instead — '.copy()' inside the closure gives the string an owner of its own, and a literal is immortal
```

### A table whose every record has its own lock

**`sysl.sync.Guarded[L, T]` is one record of a buffer cache or an inode table**: a value, not a box,
with the lock inline beside the `T`, `@guards(value)` as `Mutex` is. The lock is any `Lock` (`acquire`,
`try_acquire`, `release`, each `*self`). **An array is its elements**, so sharing a `[4]Guarded[...]`
shares four locks, and what the records hold is reached only through `with`, which hands the closure
the record in place:

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

The same table of bare `Item`s is refused, naming the `Buf` two domains would share. What leaves
`with` is held as `Mutex.with`'s result is. A slice or a `Buf` of records is asked about its own count.
On a freestanding target the table is constant data: `Guarded.new` is a `const` function, so a kernel
writes it as a module `var` and the image carries it laid out, with nothing run before `main` (a record
whose initializer is not constant is refused there, with the reason).

### A frozen buffer crosses

**`buf.freeze()` takes a buffer's elements and answers a `&sync Frozen[T]`**: one atomic count, and
nothing that writes it. It goes wherever a `&sync` reference goes:

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

- **The buffer is left empty**, through every name: the elements are copied once into storage sized to
  them. A frozen buffer offers `len`, `is_empty`, a checked `f[i]`, `for x in f`, and `view()`, a
  `[]const T` that holds one atomic share through a small box of its own. Nothing writes one.
- **Reading a local after `freeze` is refused**: `freeze` is
  [`@consumes`](/reference/attributes/). A field or module storage is not followed, and reads empty.
- **A frozen buffer shares exactly what its elements share**, so the element type is held to a `&sync`
  struct's rule: a scalar, a `&sync U`, a raw pointer, another frozen buffer, arrays and structs of
  those. A `Buf[string]` or a `Buf[Buf[u8]]` is refused for each element's count.
- **Freezing allocates**, so a `@no_alloc` module may not call it.

```sysl
import sysl.buf.{Buf, buf}

struct Cell
    n: int

var b: Buf[&Cell] = buf()

b.push(Cell(1))

val f = b.freeze()
```

```error
'&sync Frozen[&Cell]' may be reached from two domains at once, so every count inside it has to be atomic — but it holds a '&Cell', whose count is not. Hold it as a '&sync Cell' ('06')
```

### `@crossing` — where the rule is asked

**`@crossing(p)` is written above the function that hands `p` to another domain.**
`sysl.threads.spawn` carries one, and so does a package binding a scheduler. A **`*T` parameter is
looked through** — what crossed is the object at the far end. Nothing is emitted; the refusal is made
at each call, afresh for every set of type arguments:

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

- **Where a body shows the boundary, nobody writes it**: a parameter handed on to a crossing one, or a
  value stored in a `&sync` place, crosses too, however many functions deep. The address of one of its
  fields hands over that field. **A written `@crossing` is for the boundaries no body shows** — an
  `extern`, a function pointer, a closure called through its type, a trait's requirement.
- **A member may carry it**: `@crossing`, `@reads` and `@writes` are about a parameter, and are the only
  annotations a method, a property or an associated function may carry. A field or a variant has no
  parameter for one to name.
- **A parameter that crosses by value is handed a copy of every view it holds**, at every depth, made
  where the callee reads it, so the caller's count never reaches the other domain. The elements are
  still asked about, and a `*T` parameter is looked through and never copied. The copy allocates, so it is refused under `@no_alloc`. `sysl.threads.Channel`'s
  `send` and `try_send` are `@crossing(value)`, which is how a channel
  [takes a view](/library/threads/#a-view-goes-in-as-a-copy).

### `Send` and `Share` — the rule given a name

**`sysl.Send` and `sysl.Share` are the two questions above, as bounds a signature can write.** A type
is **`Send`** where a value of it may be handed to another domain by value — the question a `@crossing`
parameter taken by value asks, every view and string inside it copied on the way. It is **`Share`**
where an object of it may be reached from two domains at once — the question `&sync T` asks, nothing
copied, so a view and a `string` are out. `Share` is `Send` and more.

| type | `Send` | `Share` |
|---|---|---|
| a scalar, a `*T`, a `&sync T`, a `Frozen[T]` of members, a `Mutex[T]` | yes | yes |
| a `string`, a `[]T` of members | yes | no |
| a `&T`, a `weak T`, a `Buf[T]` | no | no |
| an array, a tuple, a struct or an enum | where every part is | where every part is |

**The membership is the compiler's, and it is the crossing check's own answer** — one rule, so a type
that crosses at a `@crossing` parameter meets `Send`, and one that forms a `&sync` meets `Share`.
Nothing implements either. A generic states the bound and its body may hand the value on; the
caller is refused where the call is written, naming the part in the way:

```sysl
struct Cell
    n: int

struct Inner
    cell: &Cell

struct Outer
    b: Inner

post[T: Send](x: T) -> int = 1

val c: &Cell = Cell(1)

print(post(Outer(Inner(c))))
```

```error
'post' requires its type parameter 'T' to implement 'Send', but Outer does not — a value of it handed over reaches another concurrency domain, so every count inside it has to be atomic — but its 'b.cell' reaches a '&Cell', whose count is not. Hold it as a '&sync Cell' ('06')
```

**A type parameter meets the bound only through its own bounds.** Where `@crossing` waits for each
instantiation, a bound is asked once, where it is written, so a parameter that promises nothing is
refused there, and told the bound to write:

```sysl
post[T: Send](x: T) -> int = 1

relay[U](x: U) -> int = post(x)

print(relay(1))
```

```error
'post' requires its type parameter 'T' to implement 'Send', but U does not — a value of it handed over reaches another concurrency domain, so every count inside it has to be atomic — but it is 'U', a type parameter that promises nothing about its counts. Bound it '[U: Send]'
```

**`@unchecked(Send)` and `@unchecked(Share)` above a struct are the program's promise** where the
fields do not show it — a struct whose own members keep a `&T` to one domain at a time. The walk takes
the promise in place of the fields, for every check that asks: a bound, a `&sync T`, a `@crossing`
parameter, a capture. It is held to `@guards`' rule: every field the walk would refuse is `private`,
so the members are the only way to it. A promise the fields already keep is refused, and so is
`impl Send`.

```sysl
import sysl.threads.scope

struct Cell
    n: int

@unchecked(Share)
struct Gate
    private c: &Cell

    get(self) -> int = self.c.n

val g = Gate(Cell(6))

scope((s) -> s.spawn(() -> print(g.get())))
```

```output
6
```

```sysl
struct Cell
    n: int

@unchecked(Send)
struct Leaky
    c: &Cell

print(1)
```

```error
'@unchecked(Send)' promises that 'Leaky''s own members keep its 'c' safe across a concurrency domain, and a field visible beyond this file is a way round them — declare it 'private'
```

## Places

A **place** is something with an address: a local or parameter, a dereference, an **element**, and a
field of any of them. Anything else — a call's result, an arithmetic result, a fresh struct — is a
value with no address of its own.

| operation | what it does |
|---|---|
| `&place` | yields a **`*T`** — C's address-of |
| `&value` | writes the value into a hidden local of this scope, and yields a **`*T`** to that |
| `*p` | reads through a `*T` or a `&T`, and is itself a place |
| `p.f` | selects, dereferencing **one** level automatically on both `*T` and `&T` |

**`&` on a place yields a raw pointer, never a reference**: a place lives in a frame or inside another
object, with no count to share. **Selection is the only implicit dereference**, with the subscript of
an indexable pointee: `p.x` is `(*p).x` (Go's rule, no `->`), `p[i]` on a pointer to an array or a
container is `(*p)[i]`, and matching a reference to an enum is `match *e`. Assignment, compound
assignment and `++`/`--` take a place. **The shorthand stops at one level**, and says how many are
left:

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

**An element has an address whether or not the expression naming it does**: a view's or a pointer's
elements live wherever the storage is, which is what makes `rows(g)[i] = v` write through to the grid.
An array's elements are places exactly when the array is. A string's bytes never are — writing a byte
of UTF-8 is refused as immutability. The one dangle a **single statement** can make is `&f()[i]` where
a temporary view is its buffer's only holder: the buffer goes at the end of the statement, and the
`*T` is the raw tier behaving as advertised.

## Addressing a value

`&` in front of something with no address **makes** the storage: the value is written into a hidden
local of the scope the `&` stands in, and the slot's address comes back:

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

`&Rect(2)` is `var t = Rect(2)` and `&t` with the name left out: the slot is the frame's, its count is
taken as a `var`'s initializer takes one, and it is released where the scope ends. **Any value**, not
only a construction — `&1`, `&(a + b)`, `&make()`. **It is storage, not a copy handed out**, so a write
through it lands, and **the operand is evaluated once**, at the `&`. A pointer carried out of the scope
is stale, as one to a named local would be; returning one is refused (above).

**A name is answered rather than copied**: `&x`, `&t.f`, `&xs[i]` and `&*p` ask for that thing's
address and are refused where it has none. A **property** is why: `t.f` at a property and at a field
are one spelling, and a pointer into a copy would make a write go nowhere while looking like one that
lands. A constant is the same (`&capacity` is refused; `&8` makes a slot).

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

**Module storage has no such scope**: its initializer runs in a prologue whose frame is gone by the
first statement. Name the value with a `static var` and take its address instead.

```sysl
struct Rect
    w: int

static var p: *Rect = &Rect(2)

print(p.w)
```

```error
module storage has no such scope
```

**The `&` in a type is a different thing.** `&T` is a counted box, which owns what is in it; the
operator takes an address, which owns nothing. A box is made by a value arriving where a `&T` is
wanted, with no operator — `var s: &Shape = Rect(2)`:

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

## `ref` — a name for a place

`var t = self.tasks[i]` binds a **copy**, so every access walks the path again; `&self.tasks[i]` gives
up bounds checking, `within` checking, invariant re-checking and the guarantee at the top of this page
in one step. **`ref` binds a name to a place**:

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

- **The place is evaluated once**, where the binding is written: a later `i += 1` leaves `t` naming the
  element it always named (not Scala's by-name `=> T`).
- **It is a declaration, never a type.** A ref cannot be a field, a parameter, an element or a type
  argument, cannot be re-pointed, cannot be captured by a closure, and does not outlive its block. A
  ref of a ref is one more name for the same place. That is what keeps the compiler's knowledge
  complete: a ref never travels, so the analyzer holds its place expression in the same body.
- **At run time it stores an address; at compile time it remembers a place**, so a write through it
  re-runs the `invariant` clauses of every struct the place lies in, a ref into a `val` is read-only, a
  ref to a `within` slot is checked on assignment, and a subscript's bounds check is paid once, at the
  binding. A ref inherits the place's writability and has no modifier of its own.
- **A user type's subscript is a place when the type implements `IndexPlace`**, whose method answers
  the element's address — `b[i]` is `*b.index_place(i)`. `Buf` is one. Through `Index` alone a
  subscript is a call answering a value.
- **A ref to a slot holding a `&T` names the slot**, not the object: binding it takes no count, `r =
  other` is the field assignment, and reading `r` takes a count as reading the field would.
- [The one rule](#the-one-rule-what-may-move-underneath-it) holds what may move under it.

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

**The place must lie in storage something holds past the statement** — a local, a parameter, module
storage, or what a `*T` points at. Bind a call's answer with `val` first:

```sysl
import sysl.buf.{Buf, buf}

fresh() -> Buf[int]
    val b: Buf[int] = buf()

    b.push(5)
    b

ref t = fresh()[0]

t += 1
```

```error
't' would stand on a place inside a value nothing holds — a temporary, released at the end of this statement while the name lives on
```

### A member that answers a place

A method can say its call **is a place**: `-> ref T`. Its body comes to a place reached through a
parameter, and every call is that place in the caller — written through at once, named by a `ref`, or
read, which reads a copy:

```sysl
struct Task
    state: int
    prio: int

    bump(*self) = self.prio += 100

struct Kernel
    tasks: [4]Task

    task(*self, i: usize) -> ref Task = self.tasks[i]

var k = Kernel([Task(0, 7); 4])

k.task(2).state = 2

ref t = k.task(1)

t.prio += 1
k.task(0).bump()

val copy = k.task(2)

k.task(2).state = 9

print(k.tasks[2].state, t.prio, k.tasks[0].prio, copy.state)
```

```output
9 8 107 2
```

**It is the same `ref`, written in the callee**: the body's place is kept as a template over the
parameters, so every hazard of the one rule is refused through a method too. The body's last
expression and nothing else is the answer — a field chain, an element or a dereference from a parameter
or module storage, leaving the parameter's own bytes through a `*self`, a reference, a view, an element
or a `*T`. Refused: a field of a by-value `self` or a local; a value or a place chosen by an `if`; a
`return` or `?` in the body; a place inside a struct with an `invariant`. It is written on a type's own
member, inherent or generic, and not on a free function, in a trait, or on an `impl` member a trait
declares. **The address is never a value**: `&k.task(0)` and `&Kernel.task` are refused.

```sysl
struct Pair
    a: int

    get(self) -> ref int = self.a
```

```error
'Pair.get' would answer a place in storage of its own call — a by-value parameter or a local — which is gone when it returns
```

## The raw tier

**Anything C can do through a pointer, sysl can**, and the pointer is where the guarantees stop.

**`null` is the absent raw pointer**, with no type of its own: it takes the `*T` its context expects.
A generic parameter is such a context and answers late — an argument with no type of its own is set
aside until the others have settled the parameter, in either order — and a call with nothing else to
settle it is refused rather than defaulted:

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

```sysl
one[T](a: *T) -> bool = true

print(one(null))
```

```error
'null' takes its type from its context, and there is none here
```

### Arithmetic: two directions, one spelling each

**`p - q` between two pointers of one pointee is an `isize` counting elements** — C's `ptrdiff_t`, the
inverse of `&p[n]` — which is what turns `memchr`'s or `strstr`'s answer into an index. **Offsetting
stays `&p[n]`**: `p + n` is not spelled, and `p + q` names no address. Two pointee types are refused by
the ordinary matching-types rule, and **a `&T` has no arithmetic at all**. Whether two pointers into
unrelated objects may be subtracted is the programmer's business.

```sysl
var buf: [8]int = [10, 20, 30, 40, 50, 60, 70, 80]
var base = &buf[0]
var third = &buf[2]

print(*third, third - base, base - third, *(&base[3]))
```

```output
30 2 -2 40
```

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

**Bounds safety follows length, not pointers.** Arrays and views carry their length, so indexing is
checked in every context — hosted or allocator-free, over static, stack or heap memory — except where
the program asked by name for [unchecked access](/reference/arrays/#unchecked-access). `*T` carries
none: `p[i]` and `p[0..<n]` are unchecked, as in C. A pointer to something with a subscript of its own
— a `*[N]T`, a `*Buf[T]` — is indexed as that thing, with its check
([arrays](/reference/arrays/#a-raw-pointer-is-indexed-anyway)).

### Reinterpreting storage

| written | direction | tier |
|---|---|---|
| `usize(p)` | a pointer as a number | an ordinary conversion |
| `ptr_cast(n)` | a number as a pointer | unsafe |
| `ptr_cast(p)` | one pointee type as another | unsafe |

**`ptr_cast` is imported from `sysl.raw`**, beside `str_cast` and `str_alias`, so every
reinterpretation is found by its imports; an alias, a wildcard and a module path reach it as they
reach anything. A function or local the program names `ptr_cast` wins, and importing the form under
such a name is refused.

```sysl
var arena: [8]u8 = [0u8; 8]
val p: *u32 = ptr_cast(&arena[0])

print(*p)
```

```error
'ptr_cast' is in sysl.raw: add 'import sysl.raw.ptr_cast'
```

**A pointer becomes an integer by ordinary conversion** — `usize` (or `isize`) holds any address, so
it is total, and the number cannot be dereferenced. The address of a function converts the same way.

**A number becomes a pointer only from an address-width integer**, `usize` or `isize`. The operand of
`ptr_cast` is an address-width position, so a bare literal — or an expression of nothing but bare
literals — is a `usize` at the target's width, written as the datasheet prints it; one too wide for the
target, or negative, is refused as a literal that does not fit. A value that already has a type keeps
it, and is converted where it is meant as an address:

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

```sysl
import sysl.raw.ptr_cast

var base = 0x4000_4000
val uart: *u32 = ptr_cast(base)

print(usize(uart))
```

```error
'ptr_cast' reads an address, and int is not address-width — convert it first, as 'ptr_cast(usize(n))'
```

**The target comes from whatever receives the result**, or is written on the form —
`ptr_cast[*Node](&arena[0])`; with neither, the program is told to annotate. **`ptr_cast` never
produces a `&T`**, a `weak T`, a view or a `string`: a reference is counted and non-null, and the fat
types are wider than an address.

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

### Storage the compiler did not lay out may not hold a counted value

**A `&T`, a `weak T`, a view or a `string` may not be written into memory reached by `ptr_cast`** — nor
a struct or enum carrying one — because an assignment releases what occupied the slot, and the release
would walk whatever the bytes held. **Nothing refuses this**: the compiler does not track where a
pointer came from. Keep an index, a raw pointer or a copy instead — an arena of nodes linked by index
costs nothing a box was buying. A type of scalars, raw pointers and arrays of those may live in storage
you carved, which covers every C ABI struct. `sizeof`, `alignof` and `offsetof` are on
[expressions](/reference/expressions/).

**Zeroed storage is the exception** — what `calloc` or a collector gives. An all-zero counted
reference names nothing, so a `&T` field (or a `Buf`, `Map` or `Deque`) in zeroed storage takes its
first assignment safely. Until then it is the one null `&T`, and **`is_null(r)`** asks, with no share
taken; it answers `false` for any `&T` the language made, and refuses a trait object, a `weak T` and a
non-reference. **`release(place)`** is its other half: it gives back the share the place held, running
the destructor on the last one, and leaves the place empty; a second release gives back nothing. A
container gives its share back through its own `.release()` member, and an `Option` or `weak T` is
emptied by assigning `None`, so `release` refuses those. Both are forms a same-named function or local
overrides.

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

### Slot storage: room that holds no value yet

A growable container needs a spare end that holds **nothing**, not a fill value that would keep what
it names alive. Three forms give the raw tier that, and [`sysl.buf.Buf`](/library/buf/) is written
with them:

- **`slot_storage(n)`** (or `slot_storage[T](n)`) is a `[]T` of `n` slots of which **none is live**;
  the element type comes from the position, and with none it is refused.
- **`slot_place(xs, i, v)`** fills the first slot past the live run without letting go of anything; a
  live slot is an ordinary assignment; a slot further on would leave a gap, and **traps**.
- **`slot_vacate(xs, n)`** lets go of every live slot from `n` on — only where nothing else holds the
  storage; while a view handed out earlier exists it does nothing, and the next vacate catches up.

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

**The live run is the storage's own**: the buffer's box counts the live slots, and its destructor
releases exactly those; the view's `len` is still the whole room. **The caller owes the one check the
compiler cannot make**: nothing reads a slot past the live run. On storage `slot_storage` did not make,
the two writing forms are an assignment and nothing.

### Moving one value through a raw pointer

Storage whose live slots are not a prefix — a ring's run wraps — moves one value at a time, as Rust's
`ptr::read` and `ptr::write` do. **`slot_take(p)`** moves the value out: the slot's share becomes the
result's, **no count is taken or given back**, and the slot is left **empty** (zeroed, over an element
holding a count). **`slot_put(p, v)`** moves `v` into an **empty** slot, giving nothing back. A taken
slot must be live, a put slot empty, and nothing reads an empty slot — the caller's to keep. Both
refuse what is not a `*T`.

### Device memory

An optimizer may assume that reading storage twice yields one value, that a store nobody reads is not
needed, and that two accesses may merge — all false at a device. So sysl has C's qualifier, in the type:

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

> A **`volatile`** place is one whose reads and writes are **effects, not value computations**: the
> compiler emits exactly the accesses the source wrote, once each, in the order written — never adding,
> dropping, merging or moving them.

**It constrains the compiler, not the machine**: no atomicity, no ordering against another core, no
protection from a torn read. Talking to another thread is `&sync T`, `Mutex[T]` and `Atomic[T]`.

**It qualifies storage, and a value read out of storage is an ordinary value**: `regs.status` is a
`u32`. The qualifier goes on a struct field, an element (`[4]volatile u32`), a pointee
(`*volatile u32`) and module storage (`var tick: volatile u64`, which an interrupt handler writes and
the program polls). A local, a parameter, a result and a type argument are values:

```sysl
var x: volatile u32 = 0u32

print(x)
```

```error
'volatile u32' is the type of *storage*, and this is a value — what a read of a volatile place hands back is an ordinary 'u32'. The qualifier goes where the storage is named: a struct field, an element, or the pointee of a '*T', as '*volatile u32'
```

```sysl build=c target=aarch64-freestanding
var ticks: volatile u64 = 0

@export("on_timer")
on_timer()
    ticks += 1

@export("wait_for")
wait_for(n: u64)
    while ticks < n do ()
```

- **`ticks += 1` is a volatile load then a volatile store — not atomic.** Where both sides write, or two
  cores share the word, the tool is `Atomic[T]`.
- **Per field, not per aggregate**: a register block's shadow values (a cached baud, a reserved word)
  stay ordinary memory. `*volatile Uart` and `volatile Counts` are refused, and the refusal points at
  qualifying the fields. A `val` is refused: a constant has nothing writing it.
- **Only a scalar or a raw pointer may be qualified** — one access, one instruction. A counted value, a
  trait object (two words), a constrained subtype (a register holds whatever the device put there;
  convert on arrival) and a data enum are refused; a simple enum and a bitfield (a read-modify-write of
  its container, see [`@packed`](/reference/attributes/)) are admitted. A struct holding a register
  carries no `invariant`.
- **What the compiler does**: `load volatile` / `store volatile`, with no runtime cost. A qualified
  field is reached at its own address, never lifted out of the block; copying a whole block is an
  access to every register in it; `&regs.status` is a `*volatile u32`; a type parameter never binds a
  qualified type.
- **`volatile` is not reserved** — special only before a type, as `sync` is after `&`. `[]const
  volatile u32` is a read-only register view: `const` is the view's property, `volatile` the
  element's.
- **Memory a device reads by DMA is a library type**, [`sysl.dma`'s `Region`](/library/dma/), not a
  qualifier: it needs a device address and a cache-honest handover, neither a property of a place.

## Recursive types

A type may reach itself **through an indirection**, and only through one. The rule is **per cycle**: a
cycle is legal as soon as one edge on it is a `*T`, a `&T` or a `[]T` (a view points at its elements;
a fixed `[4]T` holds them). Mutually recursive types work, in either declaration order.

```sysl
struct Node
    value: int
    next: Node

print(1)
```

```error
type 'Node' contains itself, so it has no finite size
```

**A type argument is not containment**: whether `Wrap[Node]` holds a `Node` is `Wrap`'s business.
`Buf[T]` reaches its elements through a `[]T`, so `enum Json` with `Arr(items: Buf[Json])` is finite,
and so is a cycle `Json` → `Buf[Member]` → `Member` → `Json`. A generic holding its parameter **by
value** (`struct Wrap[T]` with a field `x: T`) closes the cycle, and the refusal names the field.

## What a count costs

None of this section changes what a program prints or when a destructor runs; it is where counts are
**not** taken.

**Every heap object carries three header words**: the strong count, a pointer to the function that
destroys it, and a weak count. Release decrements the strong count and at zero calls through the hook,
which releases the payload and returns the storage to the heap it came from
([one heap for the program](/reference/packages/#one-heap-and-the-package-that-names-it)). The hook
makes release **type-erased** — what a view's owner word needs — so ARC works the same in a module
that never allocates, several heaps coexist, and ownership crosses an allocator-free edge like any
value. Values, fixed arrays and `*T` buffers carry no header.

**Teardown is iterative**: an object reaching zero is pushed onto a per-thread worklist through its
dead count slot, and the first release to hit zero drains it, so any depth comes apart in O(1) stack.
**On a freestanding target the worklist is asked for**: a `&sync` release reaching zero fetches it
through the weak symbol `__sysl_arc_reaper`, answering a `head` pointer and a `draining` flag of the
running task. Define nothing and the single built-in slot serves a machine where nothing schedules; an
RTOS port defines it in C, and a scheduler written in sysl defines it with
[`@export`](/reference/ffi/), as `__sysl_arc_reaper() -> *T` over a struct of the same two fields, and
the compiler then leaves its weak default out.

```c
struct sysl_arc_reaper { void *head; unsigned char draining; };

struct sysl_arc_reaper *__sysl_arc_reaper(void) {
    return pvTaskGetThreadLocalStoragePointer(NULL, 0);
}
```

### Passing a reference to a call usually costs nothing

**An argument is handed over under a guarantee that it stays alive for the call**, and the callee
reads it through the caller's count — Swift's `guaranteed` convention. The caller asks one question:

- a **temporary** is a count the frame holds to the end of the statement: nothing to do;
- a **local or parameter** whose address has not been let out yet keeps its count where the callee
  cannot reach it: nothing to do ("yet" counts a statement at a time; a loop is one statement);
- **anything else** — a field, an element, a global, something behind a `ref` or a `&` — costs a retain
  before and a release after, since the call may write that memory.

A callee still takes a count for **a parameter it assigns to**, and for every parameter of a function
something outside the program may call (`@export`, a `@section`, one whose address is taken).
**A call to an `async` function moves a temporary into its task**, which lets it go at its `return`,
not at the end of the caller's statement:

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

### A parameter the function keeps arrives with its count

**A parameter kept on every way out is owned**: the caller hands over the count at the argument's last
use, or retains, so `b.push(x)` at `x`'s last use costs nothing. The ways out are each `return`, each
failing `?`, and the end; a branch keeps where each arm does; a loop keeps where each `break` has kept
(and, unless it is a `loop` only `break` ends, where the code before it had); a trapping path does not
leave. One kept on some paths only stays borrowed, and the keeping path takes its own count. A call
through a closure, function value, trait object or C reaches a borrowing entry; an `@export`ed,
`async` or `@scoped` function, or one with a contract, keeps the borrowed convention.

**A value whose release can run a destructor is still destroyed where its scope ends**: such a local is
retained for the call, not handed over at its last use, and never handed into an `if`'s arms, since
the call might keep it only in a result that goes at the end of the statement while a view of what it
owns is still being read.

### A count that would be taken and given straight back is not taken

**A retain is cancelled against a later release of the same reference when nothing between can give
back the count that release would** — loads and stores of the frame's slots, arithmetic and other
retains never can; a different value's release may sit between unless the stretch also writes memory
outside those slots; any other call ends it. Releases split by an `if` or `match` are given back at the
end of each arm. **A value's last use hands it over**: a fresh box, a call's result, or a local named
for the last time is taken by its destination with no new count, and a struct or enum built on the
spot is handed over field by field. A local still read later, named in a `defer`, used in a loop it was
declared outside, or reached through an address or a `ref` is copied. Recursive search over `&sync`
shapes loses three of every four atomic operations.

### A view of something the frame already holds takes no count

A slice of a slice, or a `val` copied out of a field, of a **parameter or a `val`** takes no share
where its parent's count already holds the object, across a `for` or the rest of a loop's round —
provided the parent stays put (not a `var` assigned in the loop or whose address is taken), the view
stays in a local, and nothing between may look at a count: only arithmetic, reads, and calls to
functions that release nothing and write only their own locals (`slot_vacate` frees only at a strong
count of one).

**The same goes for a reference read out of storage** — a field through a parameter, a buffer's
element, a matched payload, a key built for a lookup — where nothing between can give a count back,
look at one, or hand the thread to other code. A path that ends the program is held to the same rule,
except a function handed only numbers. A call writing memory is judged by the type of what it writes:
growing a `Buf[Value]` cannot touch a `Machine`. An `await` keeps the count, as does storing,
returning, capturing or keeping the value; a tuple built on the spot for a keeping call is handed over
whole.

### A method's answer borrowed from its receiver

**A function whose answer is read out of a parameter's storage is called so that the caller reads it
there**: a place of a parameter (a field, an element of a fixed array or of a held buffer, a payload a
`match` binds where every other arm ends the program, or another such method's answer), or a value
built on the spot out of references read from it ("+0"). It keeps the ordinary call where some path
answers a reference made there, where the body may release or write what it answers, and where no body
is in sight. So `quad(self) -> Kids = self.kids.unwrap()` costs what `n.kids match Some(q) -> …`
costs: no count at all. A generic is decided per instance. `--explain-borrows`
([the CLI](/getting-started/cli/#explain-borrows)) reports each — here for a `Table` whose `find`
answers `Some(it)` for an element of its `items`, a `look` built on it, and a `drained` that clears
the buffer after asking:

```
$ sysl build --explain-borrows table.sysl
table.sysl:13:5: 'Table.find' answers +0 from 'self': self.items.walk()[_]
table.sysl:19:1: 'look' is not borrowed: its answer is a fresh value
table.sysl:23:1: 'drained' answers a place of 't': t.items.walk()[_].unwrap().value, but its body may write or release what it was handed
```

## Edge cases

What a compiler maintainer needs and a program rarely meets:

- **Deep or cyclic address chains.** An address held inside what is handed over is told apart step by
  step however deep a chain of distinct types goes; where the caller's storage holds a cycle, or the
  pointer's type reaches itself past eight steps, the callee is asked what it lets out of anything it
  reads through the pointer, and is refused if it lets out anything read there.
- **What a callee with no body can run, for the one rule.** A callee's reach is its body, every body it
  calls, and — where one of those can let go of a value that may run a destructor (a type with one, an
  object behind a trait, a closure) — every destructor. A maker such as `blank() -> Buf[u8] =
  buf_filled(n, u8(0))` lets go of nothing, so a destructor elsewhere that calls through a pointer does
  not stop a local it fills from being the only name for its buffer, and a call not handed that local
  may write other `Buf[u8]`s while a view of it is held. A call through a pointer anywhere in that could
  run anything, so every cast and all module storage count. **A call through a trait object, in a
  program's own build** (`sysl build`, `run`, `test`), runs the method in that slot of each table the
  program has for the trait — one per type it put behind the trait, and none where it put none — so
  its reach is those methods' reach, each handed the object as its own type, so a field the trait
  hides (a `Clearer` holding a `&Keep` of owners) counts as an argument would. That holds of a call
  in the frame holding the ref or view as much as of one deep in a callee (a `Display` rendered
  through `*Writer` under `map_err`). A call through a closure names no body even there, since nothing
  in its type says what it captured. **A library's
  build** (`build-lib`, `build-c`) may be handed an object whose table it never saw, so there such a
  call could run anything, as a call through a pointer. A foreign function may call back into whatever
  C can enter — an `@export`, a `@section` function, one whose address is taken, one with a foreign
  convention — so it reaches those and all module storage. The standard library's own `extern`s are
  the exception, since they never call back: a `ptr_cast` elsewhere is no reason to refuse
  `read_bytes`. A library function already compiled is judged by what its arguments, the module
  storage it can name, and the casts it can run can reach. Only the paths that come back count: one
  that ends the program — `unwrap`'s report, a failed bounds check — never resumes the frame holding
  the ref or view, whatever it runs. A view held inside an array or struct of plain values
  (`[Region(base, sram.view_mut())]`) lends its elements as a view held alone does, so a callee writing
  those bytes through the slice it is handed writes no buffer.
- **The one rule is otherwise local.** It sees the `&`, the path after it, and the calls and
  assignments on that path in one body. A second name for a buffer made with `val b2 = b` is a
  `@relocates` call on the same buffer, refused with that message; a callee through a trait object or
  function pointer and an aggregate handed to a call are not followed for a pointer.
- **The `__sysl_arc_reaper` slot on two cores** needs `__atomic_*` the board defines, which on a
  two-core part takes a hardware spinlock.
- **A view or a `Buf` of guarded records** is asked about its own count, not the records', so one
  reached through a `*T` or held as constant data crosses, and one shared through an ordinary count
  does not.
- **A generic instance whose own signature was refused at its call** says nothing more about its body:
  everything inside follows from that one complaint.

A program whose only `Bus` reads may call through one while a view is held, though module storage
holds a `Buf[u8]`; a second `impl Bus` whose `read` wrote that storage would refuse the call, naming
its method:

```sysl
import sysl.buf.{Buf, buf}

static var keep: Buf[u8] = buf()

trait Bus
    read(*self, at: usize) -> u8

struct Ram
    cells: Buf[u8]

impl Bus for Ram
    read(*self, at: usize) -> u8 = self.cells[at]

copy_in(sram: *Buf[u8], d: &Bus) -> u8
    var v = sram.view_mut()
    v[0] = d.read(0)
    v[0]

var cells: Buf[u8] = buf()
cells.push(7)
var sram: Buf[u8] = buf()
sram.push(0)
print(copy_in(&sram, Ram(cells)), keep.len())
```

```output
7 0
```

## Hazard summary

| segfault source | prevented in the safe subset by |
|---|---|
| use-after-free / double-free | ARC on `&T`; `weak` degrades to `Option` and never dangles |
| null dereference | non-null references; nullable is `Option` |
| out-of-bounds | length-carrying arrays and views, checked everywhere |
| a view or address outliving its storage | block lifetimes; the view's `owner` word; escaping frame arrays are promoted |
| refcount race across threads | `&sync T` is atomic; a `&T` may not cross a domain |
| dangling or wild pointer | impossible without `*T` and `ptr_cast` |

One hazard is **not** on that list: racing on the *fields* of an object two threads deliberately share.
Preventing it needs proof of exclusive access, which this language trades away, so it is a `Mutex`'s
job. It takes `&sync` or `*T` to reach, so it is as greppable as the rest.

## The two worlds, one language

- **Application, server, utility:** `T` and `&T`, plus `weak`, views and arrays. Safe and ARC-managed.
- **Kernel, driver, allocator-free:** `T`, `*T`, fixed arrays, views and manual `malloc`/`free`. A
  module that never *creates* a `&T` emits no allocation and no allocator dependency, and stays
  bounds-checked wherever it uses arrays and views.

The boundary is compiler-enforced through the allocator capability, and **it gates allocation, not
ownership**: allocator-free code may hold, pass, copy and drop a `&T` or a heap-backed view something
else made — retain and release are a few instructions, and the free path is the object's own hook. That
is what lets a driver keep the `&Device` its bus manager handed it.

---

Next: [arrays and slices](/reference/arrays/) — the two sequence types, and what a view keeps alive.
