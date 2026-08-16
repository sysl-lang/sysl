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
unchanged by the choice: a module `val` counts nothing either way, so an array of references is not
one of these however it is built. An array of raw pointers is, since a pointer counts nothing.

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

It lives in **`sysl.buf`**, with the `ByteSink` written over it, so a program that wants one writes
`import sysl.buf.*`. Nothing in the language reaches it — an array literal makes a `[]T` and a `for`
walks whatever implements `Iterate` — which is the test `13 §8` applies: what a program cannot avoid
needing arrives free, and what it has to ask for it asks for.

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
and above all a **destructor**, since the language had exactly one and no way to write another. That
was wrong. (None of the three is an absence now: `sizeof(T)` may be asked of a type parameter and
`ptr_cast` reaches the elements, both in `03 § Reinterpreting storage`, and `03 § A destructor` is
writable. They were never what was missing here, which is the point of the
correction.) A container does not need a destructor if its storage is a value that already has one,
and `§Storage sized while running` is precisely what makes a `[]T` field into storage a container
sizes for itself and ARC destroys on its behalf. The missing piece was never `Drop`; it was the
ability to ask for storage at a length worked out while running.

The other apparent blocker was `§Not yet`'s own: a generic container cannot make its own storage,
because a repeat needs a value in its value position and no bound promises one. **A `push` arrives
holding one** — so the value being pushed seeds the new storage, and the question never comes up.
(A bound *can* promise one now, through a trait member with no receiver; `§Not yet` says what that
does and does not settle. `Buf` needs none of it, which is why nothing here changed.)

### What an append does to the other views

This was the open question, and the answer is that **sysl does not have to choose**. A growable
array is a struct, so how a push is seen follows from how it is held, which is a choice the language
already makes the author write:

```
import sysl.buf.*

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
widened to **the address width** and compared **unsigned** against the length, which is one
comparison and which rejects a negative index as a very large one — the same trick a bounds check
has always used. (This read *"widened to 64 bits"* until targets of other widths arrived; what it is
widened to is a `usize`, because a length is one.)

**An index already *wider* than an address is narrowed, and asked whether it fits first.** No
storage holds more than `usize` elements, so a value that does not fit names nothing — which makes
it an ordinary out-of-bounds index rather than a program to refuse. The order is what makes that
honest: `2^64 + 5` truncated to 64 bits arrives as 5 and would pass on a six-element array, so the
fit is tested at the index's own width, where the value is still all there. It is read unsigned for
that test exactly as it is for the bounds check, so a negative one fails at both.

That is ordinary rather than exotic on a machine narrower than an `int`. `craft-freestanding`'s
address space is 64 KiB, so `for i in 0..<4 do b[i] …` is precisely this case — and it is the
sentence above that says it must not need a conversion.

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

## An array where a view is asked for

**A `[N]T` standing where a `[]T` is wanted is the whole of it, `a[..]`, written by the position
instead of by hand.** An array is the one value that already knows both halves a view is made of —
where the elements are, and how many — so nothing is taken on trust and no bound is guessed:

```
fill(s: []int)                     // lends its caller's storage, which is how a
    for i in 0..<s.len             // freestanding API is written
        s[i] = 0

var a: [4]int = [0; 4]
fill(a)                            // and this is the whole of the call
```

It is the same conversion at every position that asks for a view rather than merely requiring one:
an argument, a `val` or `var` with the view's type written on it, a `return`, a parameter's default.
A `&[N]T` converts on the same terms, and it is the *reference* the view is taken over — for a heap
array that reference is both where the elements are and what keeps them alive.

**What the conversion does not do is add anything to the storage's own terms.** A view of a `val`
array is a `[]const T` exactly as `a[..]` would give, so a `val` array reaching a `[]T` is refused —
the ability to write is a property of the storage rather than of the handle taken on it. A writable
view is still an alias like any other and is refused over storage a struct's invariant reads
(`16 §6`). And a `&sync [N]T` does not convert, for the reason `a[..]` will not slice one: a view
records nothing about whether its owner's count is atomic.

**A `*[N]T` is left out on purpose.** `a[..]` takes one and this does not — the raw-pointer tier is
written out where it is used (`03`), and a view of pointed-at storage taken silently is the one case
where nothing in the type says the elements are really there.

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

That the two positions mean different things is what lets both be written at once. `[]const volatile
u32` is a read-only view of device registers (`03 § Device memory`): `const` says this handle may not
write, and `volatile` says the elements are a device's, so every read through it is emitted exactly
as the source wrote it. Neither word could stand in for the other.

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

## Vectors

`<N>T` is N lanes of `T` — the same values an `[N]T` holds, in the same order, with the difference
that its operators work on every lane at once.

```
val a: <4>f32 = [1.0, 2.0, 3.0, 4.0]
val b = a * 2.0                          -- one instruction, four multiplications
```

**The two type constructors differ by one bracket pair because a vector is an array that computes
lane-wise, and the spelling should say so.** The angle brackets are free in type position: type
arguments are written `[...]`, so nothing else claims them, and a type is only ever reached after a
`:`, a `->` or another type constructor, where a comparison cannot appear. It also mirrors LLVM's own
`<N x T>`, which is what this lowers to.

The alternative considered and refused was `f32x4`, which is Rust's spelling and C's. It is not
composable — `i32x8`, `u8x16` and `f64x2` are three unrelated names rather than one constructor — and
above all it cannot be written **generically over the width**, which is the whole point of the
feature.

### A lane is a scalar, and the count is part of the type

An integer, a float, `bool` or `char`. LLVM has no `<4 x {float, float}>`, so an aggregate lane has
nothing to lower to; a `volatile` lane is refused too, because it asks for per-lane access ordering
out of a load that is one instruction for the whole register — `volatile <N>T` is the spelling that
means something.

The count has no `[]T`-style form that drops it. A slice may, because it carries a length at run
time; a register's width is settled when the code is generated or it is not a register. So `<>f32`
is refused by the grammar, and a literal that does not fill the lanes is refused with both numbers.

The count is a literal, a `const` name, or any expression in parentheses. **The parentheses are not
decoration**: `>` is a comparison operator, so a bare expression would read `<4>f32` as `4 > f32` and
then want a `>` that had already been consumed. `[N]T` has no such trouble, because `]` is not an
operator.

### The lanes are read by a constant index

`v[0]` is one lane, and the index must be a compile-time constant in range. This is the one subscript
in the language not checked at run time, and the reason is that there is nothing to check against: a
vector has no address, and LLVM's answer to an out-of-range `extractelement` is *poison* — a value
that is not a value, spreading silently through everything computed from it. Refusing the dynamic
form is what keeps a subscript's promise. Anyone needing a computed lane wants the values in an
array, whose checked subscript already answers.

### A comparison yields a mask, and the lane-wise `if` is `select`

`a < b` on two vectors is a `<N>bool`. It is an ordinary value: it can be bound, combined with the
bitwise operators, and reduced.

```
val m  = a < b
val lo = m.select(a, b)                  -- a's lane where m's is true, b's where it is false
if m.any() then ...
```

**`select` is a method rather than a keyword because it cannot be `if`.** `if` branches — it
evaluates one side or the other — and a register has no way to take one branch in two lanes and the
other in the remaining two. Both sides are evaluated and the mask chooses between the results, which
is a different operation and is spelled differently for it.

For the same reason **a comparison chain has no lane-wise form**. `a < b < c` joins its links with
`&&`, which short-circuits, and nothing per lane does. Reading the chain as a lane-wise `&` would
give the scalar spelling a different meaning, so it is refused and the reader writes the `&`.

### Reaching memory

A vector holds the lanes a kernel computes with; an array or a slice holds the data a program has.
The two forms that move a run between them are the receiver's, not the vector's, because what the
move needs is an address and a length and a vector has neither:

```
val v: <4>f32 = xs.load(i)               -- W elements starting at i, into a register
out.store(i, v * 2.0)                    -- the register back into W elements
```

**The width of a load comes from what receives the value, and nothing else.** A slice has whatever
length it has, so it cannot say; guessing would be the one mistake that silently takes the wrong
run. A binding's annotation says it, and so does a parameter or a declared result — which is what
makes the load writable from a `[const W: usize]` body, where `<W>f32` is nameable and no literal
could stand in.

**An operand of an operator is a receiving position too**, so `xs.load(i) * by` takes its lane count
from `by`. That is the literal rule of `01` with a tier in the middle: an operand carrying a type of
its own is read first, a load is read at what that one said, and a bare literal is read last at
whatever the two of them settled — so `xs.load(i) * by + 1.0` needs no annotation anywhere. What is
*not* a receiving position is a place where any width would do. `out.store(i, xs.load(i))` is
refused, and has to be: a store takes whatever it is handed, so every width type-checks and there is
nothing to infer rather than something the compiler declines to look for.

A store is told by the vector handed to it, so the asymmetry is in the language rather than in the
implementation.

**A declared `load` or `store` wins.** These are two ordinary words rather than spellings only the
compiler could have meant — `sysl.sync.Atomic` has had both since before vectors existed, reached
through a `*self` — so the built-in pair answers only where the receiver has no member of that name,
and an `impl` block is the reader's to keep.

**The run is checked, and checked as a run.** `i + W <= xs.len` is not knowable until the program
runs, so this is the one vector operation with a run-time test — the subscript's, widened from one
element to W of them, trapping the same way. A vector is not a hole through which a program reaches
past the end of an array. The test is written as two comparisons rather than an addition, because
`i + W` on a `usize` near the top of the range wraps to a small number and passes.

**A partial run traps, and the scalar tail is the caller's to write.** An array whose length is not
a multiple of the width ends with fewer elements left than there are lanes; a masked load would
answer that in one instruction, and it needs a mask, a value for the lanes it skips, and a decision
about what a masked *store* does to them. None of those has to be settled for the pair above to be
useful, and adding it later takes nothing back.

**The alignment claimed is the element's.** A `[]f32` promises four bytes and says nothing about
where a run begins — a slice of one element is a slice of any of them — so a vector's own alignment
would be a claim the type does not support, and an over-aligned load is undefined behaviour rather
than a slow one. Every machine sysl targets has an unaligned vector load costing what the aligned
one costs on aligned data.

**A run of `volatile` elements is refused rather than quietly widened**, because one access per
element is not one access and LLVM cannot promise per-lane ordering out of a single instruction.
`volatile <N>T` is the shape that can be honoured, and is spelled the other way round. A `*T` is
refused too: no length, so nothing to check against.

### What a vector does not have

**Integer `/` and `%`.** The scalar forms trap on a zero divisor and on the one signed overflow, and
both guards reduce a lane-wise comparison to a single `i1` — so a vector would either drop the guard
or trap for lanes that were fine. No machine sysl targets has an integer vector divide anyway, so
what looks like an omission costs a scalarized loop either way.

**Shuffles and swizzles.** They take constant index lists, which do not generalise over a width
parameter — and that is exactly why a C SIMD kernel is written once per architecture, since the
gather-and-transpose half is what changes between them while the arithmetic does not. Extract and
insert build any shuffle meanwhile, correctly and more slowly — and since `store` and `load` exist,
a gather is a loop over a scratch array rather than a run of hand-written lane writes, which is
better and is still not one instruction. So the "write it once" claim below is strongest for
lane-wise math and weakest for gathers, which is worth saying rather than discovering.

**A place in a C signature.** A vector in an `extern` is refused, in both directions: which register
one arrives in differs by target *and* by which instruction-set extensions the other side was
compiled with, so there is no convention to emit against. Guessing would not fail to link — it would
produce a call that resolves and corrupts its arguments, which is the failure a boundary check exists
to prevent. The shape that does cross is a pointer to the lanes.

### Writing a kernel once, for every width

This is what the feature is for, and it falls out of value parameters (`10 §9`) rather than needing
anything of its own. The lane count binds like an array's length, read off the argument:

```
solve[const W: usize](vn: <W>f32, mass: <W>f32, bias: <W>f32) -> <W>f32 =
    val impulse = (bias - vn) * mass
    (impulse < 0.0).select(0.0, impulse)
```

One body, instantiated per width, with no width written at any call. Box2D's `contact_solver.c`
writes the same algorithm four times — AVX2, NEON, SSE2 and a scalar fallback, selected by `#if` —
and over half that 2120-line file is those four copies.

**And a machine with no vector unit is not a fallback to write.** LLVM legalizes: a vector wider than
the machine becomes several registers, and one on a machine with no vector unit becomes scalars. So
`<4>f32` compiles for a Cortex-M as four ordinary FPU operations, and the only question a program
ever has to ask is how wide to go where it cares about speed — never whether it may write one at all.

**What the claim does not cover, stated here rather than discovered.** It reaches a kernel that
loops over an array and writes its answers back, which is what most real ones do — `guide/simd`
writes the solver once and runs the same body over ten contacts at four lanes and at eight. Two
one thing bounds it: a gather is still a scratch array and a loop rather than one instruction,
because a shuffle takes a constant index list.

**Where the width enters is no longer one of them.** It reads best through a parameter — a SIMD
kernel's constants are broadcast vectors anyway, which is where `guide/simd`'s `batch` gets its `W`
— and a kernel whose every parameter is a slice writes it at the call instead, `add[8](a, b, out)`
(`10 §2`). That signature had no way to be called at all until the list was, which is the case that
closed `10 § Open a`.

**Choosing the width automatically is also not built.** A program picks a number. There are no
conditional-compilation symbols for the vector unit, because `Toolchain` passes no `-march` or
`-mattr` — clang compiles for baseline `x86-64`, so an `avx2` symbol would be false on every target
sysl has. That wants a target-features decision of its own; meanwhile 4 is the natural width
everywhere, NEON and SSE2 both being 128 bits and both being mandatory on their architectures.

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
  parameter.

  Storage **sized while running** was said here to be genuinely out of reach, on the grounds that
  the only form producing one is the repeat, a repeat needs a value in its value position, and no
  bound promises one. **That last clause stopped being true.** A trait may declare a member with no
  receiver (`02 § Reaching a trait's members without a value`), so `[K.blank(); n]` inside a
  `[K: Blank]` generic is ordinary code and widens with the count it was given. What is left is not
  a language gap: nothing sysl *ships* declares such a member for the built-ins, so a container that
  wants to work over every `K` still cannot, and a program writing the trait itself covers whatever
  types it names. `14 §7` carries that half, and `Buf`'s `push` still seeds new storage with the
  value it arrived holding — which needs no bound at all and is the better answer where it applies.
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
