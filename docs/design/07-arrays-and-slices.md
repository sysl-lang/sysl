# Arrays and Slices

**Status:** decided. `03-memory-model.md` names the two types and settles who owns the storage
a slice views; `05-escape-analysis.md` settles what happens when a view outlives it. What was
left open — design gap #3 — is the **expression surface**: how an array is written, how it is
indexed, how a slice is taken, and how a length is asked for. That is what this document
decides.

## The two types

```
[N]T        // fixed array: N elements of T, a value type, N a compile-time constant
[]T         // slice: a view of some elements of T, { owner, ptr, len }
```

An array **is** its elements — copying one copies all of them, and it has no header. A slice
is three words that *name* elements someone else owns (`03`). The pair is Go's, and for Go's
reason: one type for storage, one for a view, so a function that only reads takes the view and
never has to care where the bytes live.

Both carry a length, so **every index is checked** — that is the whole point of preferring
them to `*T` in allocator-free code (`03`).

A `string` is the second of these with one thing added and one taken away: its bytes are
guaranteed well-formed UTF-8, and nothing may write through it (`04`). Everything below about
indexing, slicing, length, and ownership is therefore true of a string as well, and the places
it is not are exactly the two the guarantee accounts for — a substring is checked for landing
between characters, and `s[i] = v` does not exist.

## Writing one down

**A literal** lists its elements and fixes the length from how many there are:

```
var primes = [2, 3, 5, 7]              // [4]int
var row: [3]f64 = [1.0, 0.0, 0.0]
```

**A declaration with no initializer** starts at the type's zero value, which is what a scratch
buffer wants:

```
var buf: [64]u8                        // 64 zero bytes
var counters: [8]int                   // eight zeros
```

This form is not array-specific — it is a `var` with a declared type and no `=`, and it means
"the zero value of this type" for any type that has one. A type that contains a `&T` does
**not** have one, since a reference always points at a live object, so declaring one without an
initializer is an error asking for the initializer. That keeps the non-null guarantee of `03`
true without a special case for arrays.

An empty literal `[]` has no element type of its own and takes it from the context, exactly as
a bare `None` does:

```
var empty: [0]int = []
```

**A repeat** `[value; count]` fills every element with one value, and is the form for an array whose
element type has no zero, or has one that is not the wanted starting value:

```
var slots: [16]Slot = [Empty; 16]      // an enum has no zero at all
var ones = [1; 8]                      // [8]int
var grid = [[0; 3]; 3]                 // [3][3]int
const n: usize = 64
var window = [0u8; n]                  // a const may give the count
```

The **count is a compile-time constant** — a literal, a `const`, or an expression over those — for
the same reason an array bound is one: it *is* the array's bound, and the type is not known without
it. The element type comes from the value, or from the context where the value is a bare literal.

The **value is evaluated once** and copied into every element. `[tick(); 3]` is one call whose
result lands in three places, not three calls, which is what makes the form a construction rather
than shorthand for writing the value out `count` times. Where the elements contain references, each
copy is a share of its own: an array of `n` copies of a `&T` holds `n` counts, released as the array
is (`§Ownership`).

**A `val`** is the same three forms written at the top of a file rather than inside one, and it is
what a table of numbers somebody else fixed wants:

```
val order: [19]usize = [16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15]
```

The difference from a `const` is an **address** (`13 §7`): a constant is folded into every use and
has none, so it can size an array but cannot *be* one; a `val` is storage, so it may be indexed at a
value only known while running, iterated, and reached into. What it may not be is written — see
`§Ownership` below for the one thing that costs today.

A table nobody wrote down is the same declaration with a call on the right of it, and it is filled
before the program's own statements run:

```
private val crc_table: [256]u32 = build_crc_table()
```

`13 §7` is where the order those run in is settled. What matters here is that the element type is
unchanged by the choice: a `val` holds plain data either way, so an array of references is not one of
these however it is built.

## Storage sized while running

Every form above fixes its length in the type, and that is the one thing a program reading a file
cannot do: the size is in the header, and the header is read by the code that needs the buffer. The
*heap* is not what is missing — `var p: &[64]u8 = [0; 64]` already puts elements on the heap, and
`p[..]` views them with the box as owner (`§Ownership`). What is missing is a length the program
**computes**.

A length that is not in the type is exactly what `[]T` is, so nothing here needs a new spelling. The
**expected type decides**, which is `03`'s rule for `&T` applied to the forms above — a `T` written
where a `&T` is wanted goes on the heap, and an array written where a `[]T` is wanted does the same:

```
var buf: [64]u8 = [0; 64]              // an array: the count is part of the type, so constant
var raw: []u8   = [0; n]               // a view of fresh storage: n is any expression
var xs:  []int  = [1, 2, 3]            // likewise, with the elements written out
```

The declaration carries the choice, as it does everywhere else in the language, and the two readings
of `[0; n]` are told apart by what is being asked for rather than by whether `n` happens to be
constant. Under a `[N]T` a non-constant count is still the error it was; under a `[]T` a constant
one is simply the easy case.

**The storage is the view's own**, and that is the whole of the mechanism: the `owner` word is a
reference to the elements, so everything in `§Ownership` is already true of it. A sub-slice retains
it, the last view to go releases it, and the deallocation hook destroys the elements before
returning the bytes. Nothing about indexing, slicing, `.len`, or iterating distinguishes a `[]T`
that owns its elements from one that views someone else's — which is the point, and is why a second
type would have been a second type for nothing.

What it adds that the fixed forms cannot is **leaving the frame**. A function may build one and
return it, so a decoder that learns its size from what it is decoding takes one argument and returns
a result, instead of asking its caller to size buffers whose sizes are in a header the caller has
not read.

**Three things are checked**, because a computed length is where the arithmetic goes wrong. The
count is widened to 64 bits and read unsigned, so a negative one arrives as a very large one; the
byte size is computed with an overflow-checked multiply and add, so a count that would wrap cannot
allocate a small buffer that is then written past; and a failed allocation traps rather than handing
back a null the elements are stored through. All three are `§Indexing`'s trap, for `§Indexing`'s
reason.

## Growing one

`Buf[T]` is the growable array, and it is **ordinary sysl in the library** rather than a type the
compiler knows: a `[]T` field for the storage, a count of how much of it is live, and `push`, `pop`,
`remove`, `truncate`, `at`, `set`, `view`, `len`, `cap`, `is_empty`, `clear`. That it can be written at all is
the interesting part, and it is what the section above bought.

**Three of those shorten it, and they are one operation.** `truncate(n)` lowers the count to `n`,
and does nothing where `n` is a length the buffer does not have — a length past the end names no
element, so unlike an index there is nothing for it to read and nothing to stop the program about.
`clear()` is `truncate(0)`. `remove(i)` shifts the survivors down over element `i`, hands that
element back, and ends at `truncate` for the length; an `i` that names no element is the panic `at`
gives, for `at`'s reason. What none of the three does is give storage back: the elements above the
count are still values in a `[]T` that ARC owns, which is `§ Not yet`'s capacity-that-is-not-values
seen from the shortening side — and it is also why a **copy** of a `Buf` taken before a removal
reads the shifted elements at the length it was copied at.

**Correcting what this document said.** The claim here was that a library could not reach a growable
array — that it would need `sizeof` over a type parameter, a cast to reach the elements through it,
and above all a **destructor**, since the language has exactly one and no way to write another. That
was wrong. (The first two are no longer absences either: `sizeof(T)` may be asked of a type parameter
and `ptr_cast` reaches the elements, both in `03 § Reinterpreting storage`. They were never what was
missing here, which is the point of the correction.) A container does not need a destructor if its storage is a value that already has one,
and `§Storage sized while running` is precisely what makes a `[]T` field into storage a container
sizes for itself and ARC destroys on its behalf. The missing piece was never `Drop`; it was the
ability to ask for storage at a length worked out while running.

The other apparent blocker was `§Not yet`'s own: a generic container cannot make its own storage,
because a repeat needs a value in its value position and no bound promises one. **A `push` arrives
holding one** — so the value being pushed seeds the new storage, and the question never comes up.

### What an append does to the other views

This was the open question, and the answer is that **sysl does not have to choose**. A growable
array is a struct, so how a push is seen follows from how it is held, which is a choice the language
already makes the author write:

```
var p: &Buf[int] = buf()
var q = p                       // one buffer, two names: q sees every push through p
var c = *p                      // a copy, because copying a struct is what that means
```

Go's confusion — two slices that agree until one of them grows — comes from having one
representation and therefore one behaviour. Held by reference, a `Buf` behaves the way `03` says a
`&T` behaves: shared, mutable through any alias, visible to all of them. Held by value it is a
value. Neither is a rule about growable arrays; both are rules that were already there.

### A view taken before a growth stays valid

An append can move the elements, and the storage they move out of is an ARC buffer like any other,
so a view made before the move **keeps that storage alive** and goes on showing what it was made
from. It does not dangle, which is the half of Go's behaviour sysl could not have reproduced even
if it had wanted to: the guarantee in `03` is that a program with no `*T` in it cannot fault.

What such a view does *not* do is grow with the buffer — it is a view of some elements, and it has
the length it was made with. Take it again to see more.

### What it costs

The spare capacity holds **copies of the value that seeded the growth**, because there is no way to
have storage without values in it. They are harmless and bounded, but they are real: a `Buf[&T]`
that grew to a capacity of 1024 holding one element is holding 1024 references to it, and that
element stays alive until the slots are overwritten. What a container actually wants here is
capacity that is storage without being values yet — see `§Not yet`.

## Indexing

`a[i]` reads the element, and it is a **place** — so `a[i] = v`, `a[i] += 1`, `a[i]++`, and
`&a[i]` all follow from the place machinery that assignment and address-of already use, with
nothing said about arrays in particular.

**The index may be any integer type.** Requiring `usize` would make `for i in 0..<10 do
a[i] …` need a conversion for no benefit, since the check has to happen anyway. The index is
widened to 64 bits and compared **unsigned** against the length, which is one comparison and
which rejects a negative index as a very large one — the same trick a bounds check has always
used.

A failed check **traps**. It is the same runtime-safety category as the partial `char(u)`
conversion, and it gets the same treatment.

**A raw pointer is the one receiver with nothing to check against, and it is indexed anyway.**
`p[i]` on a `*T` is C's subscript — the address arithmetic, unchecked — and `p[0..<n]` views a run
of the region it points into, with the end written because nothing in the type can supply one. The
resulting view owns nothing, since a `*T` region has nothing to keep alive (`05`). This is `03`'s
unchecked primitive behaving like one: the check is a property of the *type*, so a `*[N]T`, whose
length is in its type, keeps every check an array has. Reaching for a slice is how a program stays
safe; reaching for a pointer is how it talks to hardware and to C, and the language supplies both
rather than withholding the second.

**A type with no elements of its own is indexed through a trait**, and everything above is about the
built-in subscript rather than about `[]` as a token. A user type — the library's `Buf[T]`, a
lookup table, anything a program writes — implements `Index` and is read with the same syntax
(`14 §7`). Two differences follow from its being a call rather than a walk to an address: the index
is whatever the implementation takes and not necessarily an integer, and the element is **not** a
place, so `b[i] = v` is a second trait's method and `b[i] += v` is refused rather than reading and
writing back. Nothing a program writes competes with the built-in subscript: an array, a slice and a
string are indexed by the compiler.

## Slicing

A slice expression is an index whose subscript is a range, and the two range operators keep the
meanings they have everywhere else in the language:

```
a[2..5]        // inclusive: elements 2, 3, 4, 5
a[2..<5]       // exclusive: elements 2, 3, 4
a[2..]         // from 2 through the last
a[..<5]        // the first five
a[..]          // the whole thing, as a slice
```

The inclusive `..` is the odd one against C-family habit, but the alternative is worse:
`for i in 0..<n` and `a[0..<n]` **must** mean the same thing, and a language that has already
chosen two range operators does not get to give them different meanings in a subscript. So "the
first `n`" is `a[0..<n]`, matching the loop that walks it.

Both ends are optional. An omitted low end is 0 and an omitted high end is the last element —
and because "through the last" is not a question of including or excluding anything, the
open-ended form is written `a[lo..]`; `a[lo..<]` is rejected rather than quietly meaning the
same.

The check is on the half-open interval the slice ends up naming: with `s` the first index and
`e` one past the last, `s <= e` and `e <= len` must hold, and the inclusive form additionally
requires that its named high element exist. An empty slice is legal, including at the very end
(`a[a.len..]`).

**What can be sliced:** a fixed array, a slice, or an array reached through a `&[N]T` or a
`*[N]T` — field selection's one-level auto-deref (`03`) applies to a subscript too, so
`buf[0..<n]` reads the same whether `buf` is the array or a reference to it.

## A view that may not be written

`[]const T` is a view whose elements may not be written **through it**:

```
total(xs: []const int) -> int      // reads the elements, and says so
    var s = 0
    for x in xs do s += x
    s
```

The `const` sits after the brackets, where `sync` sits after the `&`, and for the same reason: it
is a property of the *view*, not of the element type. `[N]const T` is not a type — an array is
storage rather than a view of it, and storage written once is what `val` declares.

**It is one type with a bit, not two types.** Both forms are the same three words, reach through
the same instructions, and keep the same thing alive; what the bit changes is only what may be
*done* with the view. So a `[]T` is accepted wherever a `[]const T` is wanted:

```
var buf: [3]int = [1, 2, 3]
val table: [4]int = [10, 20, 30, 40]

print(total(buf[0..]))             // a writable view, widened
print(total(table[0..]))           // a `val`'s view, already read-only
```

and never the other way round. Giving up the ability to write is a promise the caller keeps and
the callee does not need; inventing one is the whole of what the type exists to stop.

**What produces one.** Slicing a `val` — read-only storage gives a read-only view, which is what
makes `val` sliceable at all. `s.bytes`, whose elements are a string's own storage and may be a
literal's (`03`, `04`). Re-slicing one, because a bit that a second subscript dropped would make
`xs[0..]` the way around `xs`. And a buffer literal written where one is wanted, since storage an
expression makes has no other holder to disagree with it.

**What it refuses.** Writing through an element: `xs[i] = v`, `xs[i] += 1`, `xs[i]++`.

**What it does not refuse: `&`.** `&xs[0]` is a `*T` the moment it is written, which is the tier
`03` says the guarantees stop at, and it is how a view reaches a C function taking a pointer and a
length. The library's own `find_byte` is `memchr` over exactly this, and `printf("%.*s")` is the same
shape. A read-only view that could not yield an address could not do the job it was added for. Note
this is *not* the rule for a `val` itself, where `&k[0]` is refused (`13`): a `val` is storage whose
promise is kept where it was made, and a view is a value whose promise is about writing through it.

**What it does not record.** Whose elements these are, whether they outlive the program, and
whether their owner's count is atomic. Those are properties of the *owner*, and a view can only
report on them; see `§ Not yet`.

## Length

`a.len` is the number of elements, as a `usize`. On a `[N]T` it is the constant `N` and costs
nothing; on a `[]T` it is the third word.

It is a **property** (`08-methods.md`) — a member read without parentheses — because a length is
a projection of what is already there rather than a computation over it. On the built-in array
and slice types it is compiler-provided, so `a.len` reads the same whether `a` is a fixed array
whose length is a constant or a slice whose length is its third word.

## Iterating

```
for b in buf do …              // each element, by value
for i in 0..<buf.len do …      // each index
```

`for x in seq` over an array or a slice binds a **copy** of each element, which is what value
semantics mean; mutating the sequence goes through the index form. The loop evaluates its
sequence once, so a slice temporary lives for the whole loop.

**Storage is walked by index, and that is why a container is not an iterator.** A `for` also
accepts a cursor — a value implementing `Iterate` (`14 §7`) — but nothing here implements it, and
`Buf` deliberately does not: `for x in b.view()` reads elements that are already sitting in memory,
which costs an index where a cursor would cost a call apiece. The protocol is for sequences whose
elements have to be *computed*, which a container's never are.

## Ownership

Taking a slice **retains the owner**, dropping one releases it — that is `03`'s rule, and it is
what makes "a slice never dangles" a fact rather than a hope. Two consequences for the
implementation:

- **The owner word is null** whenever there is nothing to keep alive, so retain and release on
  a slice must tolerate null. The `&T` path must *not* pay for that check: a reference is
  non-null by construction, so nullability is the slice's problem alone and gets its own pair
  of runtime helpers.
- **Release cannot be per payload type.** A `[]u8` may hold a view of a 64-byte buffer today
  and a 4096-byte one tomorrow, so the static type of the slice does not name the type of the
  object its owner points at. Giving back a count therefore has to be type-erased, which is
  what the **deallocation hook** in the box header is for: the hook destroys the object
  completely — releasing whatever its payload holds and then returning the storage — so
  releasing a slice's owner is the same instruction sequence as releasing any other reference,
  with no static type in sight.

## Not yet

- **Capacity that is not yet values.** `Buf`'s spare slots hold copies of whatever seeded the last
  growth (`§What it costs`), because every element of a `[]T` is a value and there is no way to have
  storage that is merely reserved. Every container wants that: it is the difference between a
  capacity and a length. What it needs is either a bound that promises a value (`14 §7`) or a way
  to hold storage whose elements are not
  live yet, which is a question about the view types here — a length and a capacity as two separate
  facts about one allocation.
- **A generic container still cannot declare storage it has no value for — but this named the wrong
  construct, and the one it named works.** `[None; n]` works whatever the parameters are, because
  `None` needs nothing of them. A `[16]K` was said here to be unwritable for any `K`, and it is not:
  it is the zero-value declaration of `§Writing one down`, so `var storage: [16]K` inside a generic
  compiles, holds sixteen zeroed elements, and is refused per **instantiation** rather than at the
  definition — at a `K` with no zero value, naming the instantiated `[16]Node` rather than the
  parameter. What is genuinely out of reach is storage **sized while running**: the only form that
  produces one is the repeat, a repeat needs a value in its value position, and no bound promises
  one — so `var storage: []K` is the empty slice and nothing widens it. That is the shape of what
  `Buf` does instead, which is why its `push` seeds the new storage with the value it arrived
  holding. The remedy is `14`'s decision rather than this chapter's — see its `§7` entry on a bound
  that promises a value, which the bullet above wants for the same reason.
- ~~**Promotion of an escaping local array**~~ (`05`) — **built.** A view that would outlive its
  array now moves the array to a buffer instead of being refused, so a program that means to return
  one writes the ordinary `var buf: [64]u8` and says nothing. What is still refused is storage the
  body did not declare: an array a caller passed by value, and an array that is a field of a value.
  `--explain-escapes` became meaningful with it and is built too, so what `05` has left is its two
  sanctioned deferrals rather than a piece of this.
- ~~**Slicing a fixed array inside a `&Struct`**~~ (`05`) — **built.** It was never an escape: the
  storage is on the heap the moment the struct is, and the view names the box the walk to the field
  went through as its owner. A field of a struct on the *frame* is still refused, which is the
  unspecified aggregate-promotion question and not this one.
- ~~**Slicing a `val`, and a string's `.bytes`**~~ — **built**, as `[]const T`. See
  `§ A view that may not be written`. What these two wanted was one thing and they got it: a view
  whose *type* records that its elements may not be written through it, so the property travels
  wherever the view is bound or passed instead of expiring with the expression that made it.
  Slicing a `val` yields one, `.bytes` yields one, and `03`'s soundness hole closes with them —
  which is the difference between the two customers, since the `val` case was a *refusal* (nothing
  unsound was built, only nothing written) and `.bytes` was a view the language already made.

  The cost it lifts had a name: **a static table can be read and not passed.** `guide/sha2` has
  eighty round constants per width and reached them only through a trait method whose whole body is
  one index, because the alternative — a `[80]T` parameter — copies the table at every call. A table
  small enough to copy escaped it, which is why the same program hands its eight initial values over
  as a `[8]T` and thinks nothing of it.

  It was additive, as promised: a `[]T` is accepted wherever a `[]const T` is wanted, so no program
  that could be written before means anything different now.
- **Slicing a `&sync` buffer.** A `[]T` does not record whether its owner's count is atomic, so it
  cannot carry an owner that needs the atomic path. Still rejected with a diagnostic — and it is
  worth saying why the item above did not fix it, because the two were filed here as one missing
  type and they are not one.

  **Read-only-ness is a property of the view; which count discipline an owner uses is a property of
  the owner.** A `[]const T` can be *made* out of a writable view by giving something up, which is
  why widening is safe and why the bit costs nothing at run time. An atomic count cannot be made out
  of a non-atomic one at all: it is fixed when the object is allocated (`06 § &sync T`), and a view
  claiming it would be reporting on somebody else's storage rather than describing itself. `06` half
  concedes this about its own neighbouring case — an *immortal* view would be safe to share, but
  which kind a string holds is "decided at run time by whether the owner word is null (`03`)… so the
  rule as drafted could not be applied to a *type*."

  Rust reaches the same three answers with three mechanisms, and the split is the same one: `&[T]`
  carries writability in the **reference**, `Rc<[T]>` versus `Arc<[T]>` carries count discipline in
  the **owner's type**, and `&'static [u8]` carries lifetime. So what is left here is not one more
  bit on the view; it is a question about what an owner is, and it belongs with `06`.
- ~~**A `Buf` grows and shrinks at its end and nowhere else.**~~ **Built** — see `§ Growing one`.
  `remove(i)` and `truncate(n)`, the second being the one the first is written in terms of, and
  `clear` now written in terms of it too. What made the item worth doing was the shape of the code
  that went without them: `guide/scheduler` keeps three lists somebody *leaves* — a lock's waiters,
  the locks a task holds, the tasks that are asleep — and took an element out of each by compacting
  the survivors down and then popping the tail off one at a time, which is two loops for what is one
  operation, and the second loop is easy to forget (leaving the last element in the buffer twice).
  Neither operation was a language question, which is why this item was mis-filed here: it asked
  whether the library was complete, not what the language should be.
- **An unchecked-index escape hatch** for hot loops, listed as likely-yes and deferred in `03`.
- **Multi-dimensional shorthand.** `[3][3]f64` already works as an array of arrays; a distinct
  rectangular type is not planned.
