# Stdlib Traits, Operators, and Definition-Checked Bounds

**Status:** decided, and **§1–§6 are implemented** — `Self`, the whole catalog, the one dispatch
rule, definition-checked bounds on both method calls and operators, the compiler-provided scalar
memberships, `print`/`str` requiring `Display`, and every renderer honouring the specifier it is
handed. The ten binary arithmetic traits now take the right-hand type as a parameter defaulting to
`Self`, so an operator dispatches on the **pair**, and a type may implement one of them at more than
one argument list (`§7`). `Index`, `IndexSet` and `Iterate` are built too, and without the associated
types `§1` said the first two would need — so `s.chars` walks a string's scalar values and a `for`
takes a cursor. `§8 b`, `§8 d`, and `§8 e` settled on the
way, leaving `§8 a` and `§8 c` — neither of which
is about this chapter's own surface. This is the concrete spec for three things the earlier chapters
*decided* but left unbuilt, because all three turned on the same missing layer:

- **`00 §9` / `01` / `08 §"Interaction with traits"`** — operators are trait methods (`+` is
  `Add`, `<` is `Ord`, `==` is `Eq`), overloaded by `impl Trait for Type`. The *token set* is
  closed; the *meanings* are trait methods. But the traits themselves were never written down.
- **`10 §5`** — a generic's body may assume only what its bounds promise, checked **at the
  definition** (`sum[T: Add]` type-checks `a + b` *because* `T: Add`). The implementation was
  unbounded/template-style; the chapter marked that as scaffolding to tighten.
- **`08` / `tast.scala`** — `print` and `str` on a user type waited for a `Display` trait.

This document supplies the layer those three rest on: the **`Self` type**, the **core trait
catalog** (the operator traits plus `Eq`, `Ord`, `Display`), the **one dispatch rule** that
unifies scalars, user types, and bounded parameters, and the **definition-time checking model**
that turns `10 §5` from a promise into a mechanism. It depends on `02-traits.md` (the trait core),
`08-methods.md` (members and receivers), and `10-generics.md` (bounds and monomorphization).

The through-line is the thesis (`principles.md`): **easier than Rust.** Where Rust splits a
concept for full generality, sysl keeps the single common case and defers the rest — one `Self`
instead of associated output types, two comparison traits instead of four, derived operators
instead of a method per operator. Swift is the DNA match (`principles §2`), and nearly every
decision below has a Swift precedent — the exception being `Display`, which follows Rust in
writing to a sink, because Swift has no allocator-free target to answer to (`§2`).

§4 landed in two parts, and the split was the catalog: a **method** call on a type parameter needs
only the parameter's own bounds, which are traits a program already writes, so it came first; an
**operator** on one needed `Add` and friends to exist. Both are built, and `sum[T](a, b) = a + b`
fails on its own line asking for `T: Add`. `print(x)` on a parameter now does the same, asking for
`T: Display` — which was the last use the abstract pass had to drop for want of a bound to name.

---

## 1. `Self` — the type a trait is being implemented for

A trait describes behaviour without naming the concrete type, so its signatures need a way to
say "the implementing type." That is **`Self`**, a reserved type name legal only inside a
`trait` declaration and an `impl` block:

```
trait Add
    add(self, rhs: Self) -> Self          // both operand and result are the implementing type
```

- In a **trait declaration**, `Self` stands for whatever type later implements the trait. It may
  appear anywhere a type may — a parameter type, a return type.
- In an **`impl Trait for Point`** block, `Self` *is* `Point`. Writing the concrete name and
  writing `Self` are interchangeable there; conformance compares *resolved* types, so
  `add(self, rhs: Self) -> Self` and `add(self, rhs: Point) -> Point` are the same signature.
- In a **generic** context — a generic type's own body, or an `impl[T] Trait for Box[T]` (`02`) —
  `Self` is the type applied to its parameters, and that is not a type until an instantiation says
  what they are. So it is resolved alongside them: `-> Self` and `-> Box[T]` are one signature, and
  each becomes `Box[int]` at a `Box[int]` receiver.

`Self` is distinct from the lowercase receiver `self` (`08`): `self` is the receiver *value*,
`Self` is its *type*. This mirrors Swift (`Self`), Rust (`Self`), and Scala (`this.type`), and it
is the piece `02` and `08` needed before an operator trait could be written at all — an operator
like `+` is homogeneous (`Self + Self -> Self`), and only `Self` can say that in a trait.

**No associated types — and the result did not need one.** Rust's `Add` carries an associated
`type Output`, so `+` may return a type other than the operands'. sysl reaches the same expressiveness
with the mechanism it already has: the result is an **ordinary trait argument defaulting to `Self`**.
`Mul[Rhs = Self, Out = Self]` with `mul(self, rhs: Rhs) -> Out` is homogeneous wherever nothing says
otherwise, and a dot product — which returns neither operand's type — is `impl Mul[Vec, real] for Vec`.
Associated types remain deferred (`02` open item), and neither operator that was supposed to need them
does: `Index` carries the element type as an argument for the same reason, because **the type the block
is written for settles it** (`§7`).

The **operands** were answered by the same move, one parameter earlier. So a binary arithmetic trait
takes two arguments, both defaulting to `Self`, and the four readings are one trait:

| Written | Means | Example |
|---|---|---|
| `impl Mul for V` | `Mul[V, V]` | homogeneous — `V * V -> V` |
| `impl Mul[real] for V` | `Mul[real, V]` | scaling — `V * real -> V` |
| `impl Mul[V, real] for V` | as written | a dot product — `V * V -> real` |
| `impl Mul[V, V] for M` | as written | a transform — `M * V -> V` |

**A result is not a selector.** A use writes the operands and never the result — `a * b` asks to be
*told* what comes back — so the operands choose the implementation and the implementation supplies the
result. Two implementations agreeing on the operands and differing only in what they give back are
refused where they are written, because a use would have nothing to choose between them with. This is
the one rule the extra parameter added, and it is what keeps resolution determined rather than ranked
(`02 § One implementation per argument list`).

The scalars are homogeneous by rule, not by the shape of the trait: `01`'s operator table is what makes
an `int` a member of `Mul[int, int]` and of no other `Mul`, and **no operator promotes** — `u8 + u8` is
a `u8`, never a `u16`. A written argument cannot change that, because a scalar keeps its native
instruction whatever a block elsewhere says (`§5`).

**A compound assignment stays homogeneous, and now it has to be checked.** `a op= b` is `a = a op b`
(`§2`), so an operator whose result is not the left operand's type has nothing to assign back: `v *=
w` through a dot product is refused, naming the result it would have produced, rather than quietly
changing what `v` holds.

**One thing the result being an argument opened.** An operator trait at *written* arguments has no
`Self` left in its signature — `Mul[real, real]` declares `mul(self, rhs: real) -> real` — so it is
**object-safe**, and `&Mul[real, real]` is a formable trait object over types with quite different
multiplications. Written bare it is `Mul[Self, Self]` and still is not, and the diagnostic says which
spelling fixes it. The operator *token* does not reach through an object: dispatch is on a pair of
types and an object has forgotten the left one, so the method is written out.

## 2. The core trait catalog

These traits are **library traits with compiler-known meaning**: the standard library declares
them (or will), the compiler maps each operator token to one, and the built-in scalar types are
given their impls by the compiler (`§5`). A user type opts in with an ordinary `impl`.

### Arithmetic and bitwise — one method each, over a pair of types

| Trait | Method | Operator |
|---|---|---|
| `Add[Rhs = Self, Out = Self]` | `add(self, rhs: Rhs) -> Out` | `+` |
| `Sub[Rhs = Self, Out = Self]` | `sub(self, rhs: Rhs) -> Out` | `-` (binary) |
| `Mul[Rhs = Self, Out = Self]` | `mul(self, rhs: Rhs) -> Out` | `*` |
| `Div[Rhs = Self, Out = Self]` | `div(self, rhs: Rhs) -> Out` | `/` |
| `Rem[Rhs = Self, Out = Self]` | `rem(self, rhs: Rhs) -> Out` | `%` |
| `BitAnd[Rhs = Self, Out = Self]` | `bitand(self, rhs: Rhs) -> Out` | `&` (binary) |
| `BitOr[Rhs = Self, Out = Self]` | `bitor(self, rhs: Rhs) -> Out` | `\|` |
| `BitXor[Rhs = Self, Out = Self]` | `bitxor(self, rhs: Rhs) -> Out` | `^` |
| `Shl[Rhs = Self, Out = Self]` | `shl(self, rhs: Rhs) -> Out` | `<<` |
| `Shr[Rhs = Self, Out = Self]` | `shr(self, rhs: Rhs) -> Out` | `>>` |
| `Neg` | `neg(self) -> Self` | `-` (unary) |
| `Not` | `not(self) -> Self` | `~` |

Both parameters default to `Self` (`10 §3`), so the homogeneous reading is what every one of these
means where nothing says otherwise: `impl Mul for Point` is `impl Mul[Point, Point] for Point`, and
`[T: Mul]` asks for `Mul[T, T]`. Writing an argument is what asks for something else — `§1` has the
four readings side by side, and `§7` the two programs that wanted them.

**The two prefix rows take no result parameter**, and nothing has asked them to: negation and
complement are closed operations on the type they are written for. Adding one later is the same
additive change adding the other two were, and would need a customer first.

The compound-assignment forms (`+=`, `&=`, …) are **not** separate traits: `a += b` is defined
as `a = a + b` and so requires exactly the trait `+` requires. There is no `AddAssign`.

The shifts default like everything else, so a scalar's shift amount still takes the shifted value's
type (`01`) and a heterogeneous shift between scalars is still written with an explicit conversion.
A *user* type may name a different shift-amount type, which is the same latitude the other eight
rows have and costs the scalars nothing.

### Equality and ordering — two traits, the Swift split

| Trait | Method | Operators it provides |
|---|---|---|
| `Eq` | `eq(self, rhs: Self) -> bool` | `==`, `!=` |
| `Ord` | `lt(self, rhs: Self) -> bool` | `<`, `>`, `<=`, `>=` |

This follows Swift's `Equatable` (one requirement, `==`) and `Comparable` (one requirement, `<`,
from which the standard library derives `>`, `<=`, `>=`). sysl derives the same way, in the
compiler:

- `a != b` is `!(a == b)` — `!eq(a, b)`.
- `a > b` is `b < a` — `lt(b, a)`.
- `a <= b` is `!(b < a)` — `!lt(b, a)`.
- `a >= b` is `!(a < b)` — `!lt(a, b)`.

Two of the six **swap their operands** — `a > b` is `lt(b, a)`, and `a <= b` is `!lt(b, a)` — and
the swap is of the two *values*, applied at the call, not of the expressions that produced them.
`a > b` therefore evaluates `a` first and then `b`, exactly as a scalar comparison does, and the
derivation is invisible in evaluation order as well as in the answer. That falls out of dispatching
on values rather than rebuilding the comparison as a call over operand trees (`§3`).

**These derivations govern a type that implements the trait in source. The scalars do not go
through them.** §5's compiler-provided impls supply all six comparisons directly, at the IEEE
semantics `01` already gives them, so a float keeps *ordered* comparison: `NaN <= 1.0` is false,
and so is `NaN >= 1.0` — where negating `lt` would have said true. Deriving by negation is sound
for a total order and unsound for a partial one, and IEEE comparison is partial at `NaN`; routing
the scalars around the derivation is what keeps §3's single dispatch rule from quietly changing
what a float comparison means. The discrepancy is then reachable only by a user type whose own
`lt` is partial, which is something to construct deliberately rather than to stumble into.

So a user type becomes fully comparable by implementing **one** method, `lt`, and fully equatable
by implementing **one**, `eq`. The two are **independent** traits, not a hierarchy: this is the
existing scalar law that *equality reaches further than ordering* (`01` — `bool` and the pointer
modes have `==` but no `<`), lifted intact. A type may be `Eq` without being `Ord`; being `Ord`
does not imply `Eq` (a type wanting both implements both). One trait may require another (`02`) and
this catalog deliberately does not use it, so there is no four-way Rust
`PartialEq`/`Eq`/`PartialOrd`/`Ord` tower — the partial/total
distinction Rust draws for `NaN` is not modelled at the type level here, matching sysl's existing
treatment of float `<` as the plain IEEE comparison.

### Hashing — `Hash`

| Trait | Method |
|---|---|
| `Hash` | `hash(self) -> u64` |

It is in the catalog because its absence was **structural, not stylistic**. `02`'s coherence rule
lets an `impl` live only with its trait's module or its type's, so two libraries that each declared
their own `Hash` could never share a key type's implementation — a program using both would write
the same hash twice, and no amount of care by either author could fix it. One trait in the catalog
is what makes a key type's `impl` mean the same thing to every container. `guide/hashmap` is the
program that found this: it had to declare the trait *and* an `impl` for every key type it wanted
to support.

**The law is `a == b` ⟹ `hash(a) == hash(b)`**, and that law is what picks the memberships in `§5`:
`Eq`'s, minus the two places it is not free. A `Hash` without an `Eq` that agrees with it is not
wrong so much as useless, but the two stay **independent traits** for the reason `Eq` and `Ord` do
— there is no supertrait relation anywhere in this catalog, and a container asks for `[K: Hash +
Eq]`, which says what it needs and reads as what it needs.

**One method returning a number, not a `Hasher` sink.** The obvious alternative is Rust's:
`hash(self, out: *Hasher)`, streaming bytes into a hasher the *container* chose, which composes
better (a string feeds its bytes rather than pre-mixing them) and lets a map pick its algorithm.
The `Display` sink two rows down is the precedent for the shape, and it would work here. It was not
taken because a hash is the inner loop of a container in a way rendering never is: every lookup
would pay a dynamic dispatch per key, and `Display` pays that only when something is printed. The
cost of the choice, recorded so it is not rediscovered: a composite type combines its fields by
mixing already-mixed numbers rather than by streaming, and the algorithm is the *type's* choice
rather than the container's — so a map cannot switch to a keyed hash for denial-of-service
resistance without every key type agreeing. Moving to a sink later is a breaking change to the one
required method, which is the honest statement of what is being committed to.

### Rendering — `Display`

| Trait | Method |
|---|---|
| `Display` | `display(self, out: *Writer, fmt: FormatSpec) -> unit` |

`Display` is what `str` and `print` require of their argument (`§6`). One method, which **writes
the value's textual form into a sink** rather than returning a freshly built `string`. It is the
trait the `tast.scala` note anticipated ("a `Display` trait method replaces it once traits land").

**Why a sink and not `-> string`.** Returning a string means rendering *allocates*, and
`capabilities.md` gates allocating string operations behind `alloc`. A `display(self) -> string`
would therefore make `print(x)` on a user type impossible inside a `no alloc` module — in the
language whose showcase is an operating system, that removes logging from the kernel that most
wants it. Writing into a sink costs nothing there: a kernel passes a UART writer, and it is
`str(x)` — which must materialize a `string` — that carries the `alloc` requirement, exactly where
the allocation actually happens (`§6`).

`Writer` is a **trait**, so `*Writer` is the type-erased trait object of `02`. That is also what the
capability story turns on: `capabilities.md` gates `&Trait` behind `alloc` but not `*Trait`, so a
raw-pointer sink is available in the allocator-free subset. Swift's `CustomStringConvertible`
returns a `String` and Rust's `Display` writes into a formatter; sysl follows Rust here, and for
Rust's reason, since Swift has no allocator-free target to answer to.

**`FormatSpec`** is the second parameter, and it is why `f"${x}%-10s"` can mean something to a type
that renders itself. It carries the three parts of a specifier that survive not knowing what is
being rendered — the minimum field width, the precision, and whether the field is left-justified —
and `print` and `str`, which are written with no specifier at all, hand over the neutral one. It is
a *struct* rather than more parameters precisely so that a fourth part can be added later without
touching a single `impl`.

**A specifier describes the field the whole value occupies**, and every renderer the library
supplies acts on it. They converge on one function, `display_pad`, which puts a finished run of
bytes in that field — six renderers each growing their own padding would be six chances for `%8s`
to mean something slightly different. `display_pad` is public for the same reason: an
implementation that renders *parts* hands each part the neutral specifier and applies `fmt` to its
own complete text, and that is the call that does it. Forwarding `fmt` straight down is right only
where the part being rendered *is* the whole rendering, as in a wrapper around a single field.

What a **precision** means is each renderer's own, because the conversion letter is exactly what
does not cross the boundary: text truncates to it, an integer fills out to it in digits, and a real
reads it as significant digits — printf's meaning under `%s`, `%d`, and `%g` respectively. Width
and precision count **bytes**, as C's do, so a `string` occupies the same field whether it was
rendered by `snprintf` or by a `Display`; the one refinement is that truncation backs off to a
character boundary rather than handing a sink half a codepoint. A sink is a byte sink, and there is
no recovering from an invalid sequence once it has been written.

**The unit is bytes, and that makes the width unusable for laying anything out — decided, and the
answer was a second measurement rather than a change to this one.** `guide/table` is the customer,
and it is the first program in the set to measure text for *display* rather than to copy or compare
it. `café` is five bytes and four columns, so a field padded to a
byte count is short by one — and short by **one per non-ASCII character**, so two cells of one
column are wrong by different amounts and the column comes out ragged rather than merely narrow.
There is no correction to apply afterwards, because the error is not a constant.

So the program hands every cell the neutral specifier, ignores the width entirely, and does its own
padding over a column count it asks `sysl.text` for. The division of labour is right — only the
caller knows where the next border falls — but the one field of `FormatSpec` a table would have
used is precisely the one it cannot, and a program that trusted it would produce output that is
wrong *only* for text that is not ASCII, which is the worst way to be wrong.

The argument for bytes is the equivalence stated above: a `string` occupies the same field whichever
renderer produced it. That is worth something, and it is worth less than it looks, because the thing
it agrees with is C's `%-10s` — which is itself wrong for UTF-8 and is worked around in every
program that formats text for a terminal.

Four ways out were on the table: count **characters** and give up the equivalence with `snprintf`;
keep bytes and add a second pair of fields; leave the specifier alone and say plainly that it is for
`printf`-shaped output and not for layout; or supply the measurement a table actually needs, which
is neither bytes nor characters — the East Asian Width property from UAX #11, since `日本` is two
characters and four columns.

**Settled: the third and the fourth, which turn out to be the same answer.** `FormatSpec` keeps
counting bytes and keeps its equivalence with `snprintf`, because a specifier is for `printf`-shaped
output; and the measurement layout needs lives in `sysl.text` as `char_columns` and `columns`,
where a program asks for it by name. The division is the one `guide/table` had already arrived at in
practice — only the caller knows where the next border falls, so the library owes it a *number*, not
a padded field.

What that leaves is a specifier whose width field is unusable for the one job its name suggests, and
that is a documentation obligation rather than an open question: `§8`'s prose says which one to
reach for.

**The fourth way out was recorded as waiting on module-level constant data (`13 §7`), and it was
never waiting.** Both `const` and module-level `val` had landed the day before `guide/table` was
written, and four other guide programs were already using them — `guide/datetime` carries a
transition table built by a function at module level, which is the shape a width table needs. The
blocker was a habit rather than a fact, and it is worth naming because the cost was invisible: not
a wrong decision, but a paragraph explaining why something could not be done that could.

**Nor do the tables cost a program that does not measure anything.** `04 § Granularity` gives
"tables that must not be in a kernel" as its reason for the byte-and-scalar granularity, which reads
as an argument against a library carrying Unicode data at all. It is not, because a `val` whose
initializer is a literal aggregate has no initializer at run time — it lowers to a `private
constant` and is dropped when nothing reaches it (`15 § What is emitted at all`). The roots rule
there is about *computed* `val`s. A program that imports `sysl.text` and never calls `columns` links
neither table.

### The `Writer` surface, as built (`§8 d`)

| Trait | Methods |
|---|---|
| `Writer` | `write(*self, bytes: []u8) -> unit`, `failed(*self) -> bool = false` |

Three decisions, each following Rust's experience rather than its packaging:

- **One method, on bytes.** Rust splits `core::fmt::Write` (a `&str`) from `std::io::Write` (a
  `&[u8]`); sysl needs one, because a `string` here *is* a validated byte view of identical layout,
  so `s.bytes` costs nothing. Bytes is also the direction that is free: a renderer lays digits into
  a buffer on its own stack and writes a slice of it, where a `string` sink would have made it
  allocate and validate to say the same thing.
- **Latched, not `Result`.** A write returns nothing and `failed` reports a sticky error. Rust's
  `fmt::Error` carries no information at all, precisely because a fallible `Display` taxes every
  implementation for a failure almost no sink has; latching keeps implementations straight-line,
  keeps `print(x)` a statement, and needs no error type designed, which is what decoupled `§6` from
  io-error work that has not started. `failed` **defaults to `false`** (`02`), so a sink that cannot
  fail — a counter, a fixed device — writes nothing about failure at all; one that can overrides it.
- **The bytes are borrowed.** A `Writer` may not keep what it is written — they may be a view of the
  caller's stack. Nothing in the type says so, so it is *checked*: escape analysis rejects an
  implementation whose `write` lets its parameter outlive the call, which is what licenses a
  renderer to pass a stack-backed slice through a trait object at all (`05`).

`*self` on both methods is what makes a writer stateful — a counter, a latch, a buffer — while
staying object-safe for a raw object (`02`), so a sink needs no allocator.

**Which writers the library supplies: two — `Stdout`, in the standard module, and `ByteSink`, which
is `sysl.buf`'s.** The standard-output one used to be the compiler's, for a reason that has since
stopped being true: it holds no state, and there was no struct with no fields to give it. There is
now (`00 §12`), so it is three ordinary declarations in `print.sysl` — the struct, its `impl Writer`,
and a `stdout()` that hands one out — and `print` reaches it by calling that. Its `write` is still
the library's own `putbytes`, which is one of the two seams a freestanding target replaces, the other
being `FdReader.read`, below.

**What that bought is the sink as a value.** A destination the compiler built could be reached from
exactly one place; one the library declares can be passed to a function, held in a struct, and chosen
by a caller, and a program can build its own. Swapping standard output for a serial port is another
`impl Writer`, not an edit to the compiler.

The buffer `str` and an `f"…"` hole render into is still the compiler's, and for the reason that
still holds: a growable byte array is `07`'s *Not yet* at the layer the compiler needs one.

That the sink is imported while `Writer`, `Display`, `FormatSpec` and the renderers are not is the
line `04` draws, applied here: a `print` is a keyword and reaches all four of those for itself, so
they stay free; a program *gathering* into a sink has written the word `byte_sink` and can say where
it comes from. The sink sits with `Buf` rather than in a module of its own because it is a dozen
lines over one, and `13 §6` would refuse the two being split with the dependency running both ways.

The other was `07`'s *Not yet* until a `[]T` could be sized while running, and once `Buf[T]` existed
it was a dozen lines of ordinary sysl:

```
module sysl.buf

struct ByteSink
    bytes: &Buf[u8]

    text(self) -> []u8 = self.bytes.view()
```

It is in the library rather than left to each program because **an implementation that renders more
than one part cannot honour its specifier without it**. A specifier describes the field the whole
value occupies, so a `Complex` rendering `1`, `+`, `2`, `i` must pad what those four came to and not
each of them; padding needs the finished bytes; and the finished bytes need somewhere to land. Every
such implementation would write the same dozen lines, which is the definition of something that
belongs in the library. What a program writes for itself is still an ordinary `impl Writer for
MyThing` — a counter, a device, a bounded buffer that latches — and that remains the case the trait
exists for.

The reason it was not there sooner is worth keeping, because it was not the design: **a member of a
non-generic type used to be emitted whether or not anything called it**, so a library type with
three methods put all three, and everything they reached, into every program. Library members are
now held back by the same reachability their module's free functions already were, and a program
that prints a number carries no more than it did. That rule is no longer the library's alone: a
program's own declarations are filtered the same way, by a pass over the whole typed program that
runs after everything that checks one (`15 §3`).

### The `Reader` surface

**All of it is `sysl.io` rather than `sysl`**, so a program that reads writes `import sysl.io.*` and
one that does not never has these names. That asymmetry with `Writer` is not an inconsistency: `print`
is a keyword and what it desugars onto cannot be behind an import nobody wrote, while nothing in the
language desugars onto reading. `13 §6` is what allows it — the module reaches `print`, `exit` and
`buf` freely and imports `sysl.text` for the validation, because the dependency only has to not run
back.

| Trait | Methods |
|---|---|
| `sysl.io.Reader` | `read(*self, into: []u8) -> []u8`, `failed(*self) -> bool = false` |

`Writer` turned around, and deliberately the same shape: one method on bytes, a latch rather than a
`Result`, `*self` on both so a reader can be stateful and still object-safe. What differs is the
direction the bytes travel, and so the one question the return type has to answer — *how many
arrived*.

**It answers with the filled prefix, not with a count.** A slice already *is* a length and a pointer,
so `got.len` is the count and `got` is what to look at; there is no way to be handed the one and
forget to apply it to the other, which is the mistake `read(2)`'s own signature invites every time it
is called. It costs nothing over returning a `usize` — a view is three words either way.

Reading empty says **end of input** and says only that. Whether input ended *badly* is what `failed`
answers, and the two are separate for the reason the latch exists at all: a caller who does not care
should not have to ask, and a caller who does should not have to unwrap a `Result` at every read to
find out. That mapping is also `read(2)`'s own — zero for the end, `-1` for a failure — so nothing is
being conflated to make the surface tidy.

**Which readers the library supplies: one, `FdReader`**, over `read(2)`, with `stdin()` naming the
descriptor a program is started with so a caller does not have to know it is zero. It is the mirror
of `ByteSink`, and it means the seams a freestanding target replaces are exactly two: `putbytes`'
body and this one's.

Above it sits **`Lines`**, the cursor `getline` belongs to, and `impl Iterate[string] for Lines`, so
`for line in lines(&r)` is an ordinary `for`. Three things about it are worth writing down, because
each was a decision and not a default:

- **It borrows its reader rather than owning it.** A `for` iterates a *copy* of its iterator, so a
  cursor holding an `FdReader` by value would latch a failure onto a copy the caller cannot reach,
  and `failed` would be decorative. Borrowing leaves the reader named in the caller's scope, which is
  what makes `r.failed()` answerable *after* the loop — the only moment the question matters. The
  price is one line at the call site.
- **It scans the slice `read` returned, not the one it offered.** Those are the same memory for a
  reader that fills what it was given, so the distinction is free — and it is what lets a reader hand
  back a view of a buffer of its own, never touching the offered one, and still be read correctly.
  Taking the *length* from the answer and the *bytes* from the offer is how the two could disagree.
- **A line is found with `memchr`, through `find_byte`.** libc's scans a word at a time where sysl's
  loop could not, and `find_byte` is what pointer difference is for: `memchr` answers *where* with an
  address, and an index is that address minus the first. A line that fits in one read is never
  copied; only one spanning two reads is gathered, into a `Buf[u8]` that is then reused.

`getline` yields a `string`, so bytes are validated where they arrive (`04`) and ill-formed input
stops the program as an ill-formed argument does. That severity is affordable *because* the layer
underneath is public: a caller who would rather inspect than trap reads bytes through `Reader` and
validates them itself, which is what having two layers is for. A trailing `\r` leaves with the `\n`,
so text written on either system reads the same — `bufio.Scanner`'s choice rather than C `getline`'s.

## 3. One dispatch rule for operators

An operator expression is a **trait-method call**, resolved the same way in every context — the
difference between a scalar, a user type, and a bounded type parameter is only *where the impl
comes from*, never the rule:

`a ⊕ b`, for an operator `⊕` mapped to trait `Op` with method `m`, means `Op::m(a, b)`, and it
type-checks iff the type of `a` satisfies `Op` **at the type of `b`** — `Op[B]` for a trait that
takes a right-hand type, and `Op` for the two comparisons and the two prefix operators, which take
none and so require the two sides to be the one type. Then:

- **Built-in scalar (`int`, `f64`, …).** The scalars satisfy the operator traits by
  compiler-provided impl (`§5`), and codegen keeps emitting the **native machine instruction** —
  `add`, `icmp`, and so on. No call, no vtable; the trait membership exists so the *type system*
  agrees a scalar satisfies `Add`, which is what lets a scalar be passed where `[T: Add]` is
  wanted. This is zero-cost and unchanged from today's lowering.
- **User type with `impl Op for S`.** `a ⊕ b` lowers to the member function the `impl` produced
  (`S.m`, via the member-lowering path of `02`/`08`). Overloading an operator is exactly
  implementing its trait; there is no separate operator-method syntax (`08`).
- **Bounded type parameter `[T: Op]`.** Inside the generic body, `a ⊕ b` on values of type `T`
  resolves to `Op::m` abstractly at the definition (`§4`), and monomorphization binds it to the
  concrete `<type>.m` — a scalar's native instruction or a user type's member — per instantiation.

The comparison and rendering builtins fold in the same way: `==` needs `Eq`, `<` needs `Ord`,
`print`/`str` need `Display`, whether the operand is a scalar, a user type, or a bounded `T`.

### The dispatch travels as a name, not as a call

In most positions "lowers to the member function" can be taken literally: the analyzer replaces the
operator node with the call. Two positions cannot, and they are the reason the rule is stated as
*which method*, rather than as a rewrite.

A **comparison chain** compares each middle operand against both its neighbours, and evaluates it
**once** — that is what `01` promises, and a chain that evaluated the middle twice would be a
different language for anything with a side effect. A **compound assignment** updates the place it
just read. In both, one value is used twice, and codegen already holds it in a register.

So a trait-supplied operator in those positions carries the **name of the method** on the node the
operator already lowers to, and codegen applies it to the values it is holding. A call rebuilt over
the operand's own expression would evaluate that operand a second time; naming the method instead
costs nothing and keeps every guarantee the scalar lowering makes — single evaluation, left-to-right
order, and short-circuit at the first comparison that fails — true of a user type as well.

The same carrier holds the two derivations of `§2`, which is why `a > b` calls `lt` with the values
exchanged while still evaluating `a` before `b`.

## 4. Definition-checked bounds

This is the mechanism `10 §5` promised. Today a generic body is checked only when it is
instantiated (template-style): a misuse of a parameter surfaces at some call site, or not at all
if the parameter happens to support the operation. The design commits instead to checking the
body **once, at the definition, against the bounds alone.**

### The abstract parameter

A generic function is analyzed one extra time with each type parameter treated as an **opaque
abstract type** carrying its bounds — call it `T` with bound set `{Add, Ord}`. In that pass a
value of type `T`:

- **May be** copied, assigned, passed, returned, and stored in a struct field, enum payload,
  array, or slice, and may be the pointee of a `*T`/`&T` that is dereferenced. These are the
  operations *every* sysl value has (`10 §5`, and the memory model's every-value-is-copyable
  rule), so they need no bound. This is why `id`, `pair`, `Box`, `unbox`, and `peek` need no
  bound — they move a `T` around without inspecting it.
- **May, additionally, do exactly what its bounds promise** — call a method a bound's trait
  declares, or use an operator whose trait is among its bounds. `x.show()` type-checks iff some
  bound declares `show`; `a + b` type-checks iff `Add` is a bound.
- **May do nothing else.** Any other operation — an operator with no matching bound, a field
  access, an index, a `print` of an un-`Display`-bounded `T` — is an **error reported at the
  definition**, naming the parameter and the bound that would license the operation
  (`'+' needs a bound 'T: Add'`).

Method and operator calls on a `T` resolve through the union of its bounds' trait methods; the
result type is read from the trait signature with `Self` bound to `T` (so `Add::add` yields `T`,
`Ord::lt` yields `bool`). This is the pass that makes `sum[T: Add](a, b) = a + b` legal and
`sum[T](a, b) = a + b` fail *on its own line*, which is the whole payoff over the template model:
the error lands on the definition that is wrong, not on a caller three files away.

### Monomorphization is unchanged, and is the second line of defence

The per-instantiation lowering of `10 §7` stays exactly as it is — it is what emits code. The
abstract pass does not replace it; it runs *before* it and is the authority for *diagnostics*.
Because the abstract pass has already proven the body uses only what the bounds guarantee, and a
concrete type satisfying the bound supplies each of those, instantiation cannot fail a check the
definition passed. (An instantiation may still fail for a reason unrelated to the parameter, as
any concrete code can.)

**That parenthetical is what the abstract pass leans on when it drops a complaint, and it holds for
everything except a name that names nothing.** A body's other mistakes depend on what the parameters
turn out to be, so reporting them from a pass where the parameters stand for themselves would blame
a body no call site may ever ask for — they are left to the instantiation, which is where they can be
said in concrete terms. A name that resolves to nothing has no such second chance: it is wrong at
every instantiation and wrong at none, so **an undefined name and an unknown type are reported at the
definition, whatever the pass**. Otherwise a generic nothing instantiates is checked nowhere, which
is the one shape in which `f[T](y: T) = nosuchthing` used to compile while the same line one word
away from being generic did not.

### What this changes about existing generics

A generic function that today relies on the template model to use an *unbounded* parameter for
something structural must now state the bound. The known case is a function that **prints** a
parameter: `countdown[T](n: int, x: T)` doing `print(x)` becomes `countdown[T: Display](...)`,
because `print` needs `Display` (`§6`) and an unbounded `T` no longer silently qualifies at the
one instantiation that happened to be a scalar. This is the design working as intended — the cost
a parameter's use imposes is now written in its bound — and it is an additive requirement, not a
change to any *bounded* generic already written.

## 5. Compiler-provided scalar impls

The built-in scalar and primitive types have no source body to write an `impl` in, so their trait
memberships are **provided by the compiler**, exactly as their members already are (`08 §"Built-in
members"`). The mapping is the existing operator semantics of `01`, restated as trait membership:

- Every numeric type (integers and floats) is `Add`, `Sub`, `Mul`, `Div`, `Eq`, and `Ord`. Every
  integer is additionally `Integer`, which is the family the blanket `impl` of `Display` is written
  over and promises nothing else. The signed integers and the floats are additionally `Neg`. Every
  **integer** is also
  `Hash`; a **float** deliberately is not, for the reason Rust leaves it out — `NaN != NaN` breaks
  the reflexivity a table lookup assumes, and `-0.0 == 0.0` holds between two different bit
  patterns, so a hash over the bits would contradict the equality unless it normalized first. A
  program that means to key on a float writes the normalization it means.
- The pointer modes are **not** `Hash`, though they are `Eq`. Their equality is address equality,
  so a hash of one would be a hash of where the allocator happened to put something — and an
  address is not a number this language lets a program compute with, which is where the matter
  rests until something asks.
- Every integer type is `Rem`, `BitAnd`, `BitOr`, `BitXor`, `Shl`, `Shr`, and `Not`. `Rem` is here
  rather than with the numeric types because `%` is integer-only in `01`'s operator table: there is
  no float remainder to lower, and a membership wider than the table would promise a bounded generic
  an operation that fails at the instantiation the bound was supposed to have proven.
- `char` is `Eq`, `Ord`, and `Hash`, and has **no** arithmetic or bitwise membership
  (`01` — `char` has equality and ordering only).
- `bool` is `Eq` and `Hash`, and is **not** `Ord` (`01` — `bool` has equality, no ordering).
- `string` is `Add` (concatenation, the one string operator — `04`), `Eq`, `Ord`, and `Hash`.
- **`Display` is not in this list for any of them, and that is the point of the next section:**
  every built-in reaches it through an `impl` — the closed families through blocks written per type,
  the integers through one blanket block over `Integer`. No type has `Display` by rule.
- A **simple** enum — every variant dataless — is `Eq`, and is nothing else. It is the one type in
  this list a *program* declares, and it is here for the reason the open integer family is: the
  value is its discriminant (`09 §2`), so equality has one possible meaning, and the family has no
  finite list a library could have written blocks over. It is deliberately **not `Ord`** — the
  declaration order is an order and not a claim, and `Srgb < Linear` is not one anybody wants made
  on their behalf — and deliberately not `Hash`, which is a separate promise a program can make
  with an `impl` if it means it. A **data** enum is not a member: structural equality over payloads
  needs every payload type to be `Eq` itself, which is a different feature, and `simple` is the same
  line the lowering already draws.
- The pointer modes `*T`/`&T` are `Eq` only (address equality; no ordering — `01`), and
  deliberately **not `Display`**: an address renders differently on every run, so a program that
  wants one in its output asks for it explicitly rather than getting it from `print(p)`. This is a
  narrowing if `print` accepts a pointer today, and the intended one — the diagnostic becomes the
  ordinary "`*T` is not `Display`."

These memberships change no codegen: a scalar operator still lowers to its native instruction
(`§3`). Their sole job is to make the type system agree that a scalar satisfies the bound a
generic asks for, so `sum(3, 4)` and `sum(3.0, 4.0)` both instantiate `sum[T: Add]`.

**A membership is a rule only where the family is open, and `Display` is where that line is worth
drawing.** A rule cannot fill a slot in a method table — a table holds function pointers, and what a
rule provides is an instruction or a rendering spliced in — so a type belonging to a trait by rule
cannot be erased to an object over it (`02`, *Object safety*). For the operator catalog that costs
nothing, since `add(self, rhs: Self) -> Self` is not object-safe anyway. For `Display` it costs the
most useful erasure in the language, and there is no workaround: coherence puts `impl Display for
bool` in the library or nowhere.

So the closed families are **written out**. `bool`, `char`, `string`, `real` and `f32` are five
types, and `lib/sysl/display.sysl` gives each an ordinary one-line `impl` forwarding to the same
`display_*` function the rule used to name. They are now `Display` the way any program's struct is,
and a `*Display` carries one.

### A blanket `impl` over a closed bound

The open families cannot be written out and are not left to a rule either. What covers them is one
block written over a **bound** rather than over a type:

```sysl
trait Integer: Div + Rem + Ord + Eq + Sub

impl[T: Integer] Display for T
    display(self, out: *Writer, fmt: FormatSpec)
        var buf: [sizeof(T) * 3 + 2]u8
        …
```

`Integer` names the family and declares no member of its own — a type is one of the integers or it
is not. What it carries is the operators the body needs, exactly as `sysl.math`'s `Float` carries
the ones its two widths share. **A trait may leave its members out only where it requires another**;
one with neither says nothing at all, and a body indented wrongly would silently become one.

The buffer is what makes a single block possible, and it could not have been written before an
array's length could be worked out from a type parameter: three decimal digits per byte is the bound
at every width, so `[sizeof(T) * 3 + 2]u8` is right at `u8` and at `i256` and wasteful at neither.
The two extra bytes are the sign and the rounding.

**The bound must be one nothing outside the compiler can join, and that single requirement is what
makes the relaxation sound.** A blanket stands for every type meeting its bound, so coherence needs
that set fixed: a program able to join the family later would acquire an implementation written for
something else, with no block naming either. `Integer` is therefore **closed** — an `impl` of it is
refused, there being nothing for one to supply. The same fact is what stops the lookup circling,
since membership is asked of the compiler directly rather than through the conformance question that
reached it. Widening this past a closed bound needs both arguments made again.

`Bits` and `Signed` are **not** closed and stay ordinary traits whose members the compiler provides
for the built-ins: a program's own type declaring `count_ones` and joining is meaningful, where a
claim to *be* an integer is not. And `Integer` is not `Bits` renamed — `Bits` is named for what it
provides, `Integer` for what it ranges over, and a future bitset or lane mask would want the first
without being the second.

**What this buys** is that every built-in erases. A heterogeneous `[]*Display` holding an `int`, a
`u8`, a `u256`, a `string` and a float is ordinary code — the shape `12 § Open i`'s safe variadic
wanted and could not have — and rendering costs no allocation at any width, where the values above
128 bits used to fall back through a `string` and so were exactly the ones a `@no_alloc` module
could not print.

**A compiler-provided membership is not always an operator, and `sysl.math`'s integer half is the
case that shows why the mechanism is needed rather than convenient.** `Signed`'s `abs` and `signum`,
and `Bits`' population counts, runs, reversal and rotations, are ordinary members with ordinary
names, and the reason they cannot be a library `impl` is the one that put the memberships here in
the first place: the `iN` / `uN` families are **open**, so `i5` and `i128` have no block anybody
could have written. The floating-point half of the same module *is* a written trait with an `impl`
per width, because there are two widths — which is the whole difference, and it is a fact about how
many types there are rather than about what the members do.

Such a trait is declared in library source like any other, and what the compiler holds is the
membership rule and the lowering. Every one of these members reads its operand more than once — a
magnitude compares it and negates it, a rotation shifts it both ways — so each is **one node over
one evaluation** rather than the tree of operators it is equivalent to, and `f().abs()` calls `f`
once.

**Every member of such a trait must be total over every type the membership covers**, and this is
the constraint that shapes the surface rather than a remark about it. A `[T: Bits]` body is written
once and instantiated later, so a member that answered at `u32` and not at `u24` would turn a bound
that was supposed to have *proven* an operation into a failure at somebody else's instantiation —
the same reason `Rem` is an integer membership and not a numeric one. `Bits` therefore has no
`swap_bytes`: reversing byte order needs a whole number of bytes and at least two, and the open
family has widths that are neither.

**Membership does not put a member in scope.** `13 §2` decides which files may write a member and
this section decides which types have one; they are different questions and both are asked. `Add`
and `Display` reach everywhere because they are declared in the standard module, which every file
auto-imports — not because the compiler supplies their memberships. `Signed` is in `sysl.math`, so a
program imports it exactly as it imports `Float`, and a program that would rather define an `abs` of
its own still may.

**A member the compiler supplies is not written twice.** `Signed` is the signed integers only. The
floats have an `abs` already — `Float`'s, bound to libm's `fabs` so that it can see the sign bit,
which is what makes `(-0.0).abs()` answer `0.0` where a comparison against zero cannot. Two members
of one name that disagree about a zero would be worse than one of them not existing.

**The operator memberships are homogeneous**, which is `01`'s rule and not a limitation of the
mechanism: an `int` is `Mul[int]` and no other `Mul`, because no scalar operator promotes. A scalar
therefore keeps its instruction whatever an `impl` written elsewhere might say — `impl Mul[Complex]
for f64` does not make `2.0 * c` mean anything, and `c * 2.0` is how a program writes it. A
**transparent** subtype is its base's member the same way it is its base's operand, so an `Age` over
`int` is `Mul[Age]`.

That is a rule about **operators**, and the members above are where the difference shows: `Bits`
answers a count of bits as a `u32` whatever it counted, and takes a rotation amount as a `u32`
whatever it rotates. Neither is a promotion — how far to rotate is a count of bit positions rather
than a value of the type being rotated, and a `u8` that could only state amounts up to 255 would be
the *narrow* case that homogeneity made awkward rather than the safe one. What the compiler reads is
the trait's own declaration, so the signature is stated once, in the library source, where a reader
can see it.

Because these impls are compiler-provided, they raise no coherence question: the orphan rule of
`02` asks that an `impl` live in the module declaring the trait or the one declaring the type, and
a built-in scalar has no module of its own. Whichever way `§8 a` settles, a scalar's memberships
come from the compiler, and a *user* type's `impl Add for Mine` is licensed by its own module.

## 6. `print` and `str` require `Display`

`str(x)` and `print(x)` are the rendering builtins, and their contract becomes uniform: **the
argument's type must be `Display`.** A scalar satisfies it by `§5`; a user type by `impl Display
for T`; a bounded parameter by `[T: Display]`.

They differ in *where* the rendering goes, and that difference is what splits their capability
requirements:

- **`print(x)` writes to the output sink and allocates nothing.** It calls
  `Display::display(x, out, fmt)` with the standard output writer, so it is available in a
  `no alloc` module — a kernel supplies a UART writer and logs with the same builtin application
  code uses.
- **`str(x)` materializes a `string`**, so it renders into a growable buffer and returns it.
  Building that buffer is an allocating string operation, so **`str` requires `alloc`** while
  `print` does not. The requirement lands on the operation that actually allocates rather than on
  the trait, which is the point of the sink signature (`§2`).

This removes the special case where `str`/`print` accepted only a fixed set of scalar types and
rejected everything else with "cannot make a string of a …". That message is now a missing-impl or
missing-bound diagnostic like any other, and it names what to write: `cannot print a Q value — write
an 'impl Display for Q' to say how it renders`. A user type prints once it has `impl Display`, which
is the capability `08`/`tast.scala` were waiting on.

**How the three forms differ, as built.** A **scalar** keeps its direct path in `print` — `§8 b`'s
answer, since the two renderings are identical and the one that does not build a sink is the one to
emit — and keeps its own `str`, which allocates the string directly. Everything else reaches
`display`: `print(x)` supplies the standard-output sink, and `str(x)` supplies a growable buffer
whose bytes become the string. An `f"…"` hole is `str` with the written specifier instead of the
neutral one, and only for a type that renders itself: `%s` on an integer stays the mistake it was,
rather than quietly becoming a rendering that drops the width the programmer asked for.

`x.display(out, fmt)` works on a **built-in** too, and has to: a `Display` for a struct renders the
struct's own fields, and if `self.x.display(out, fmt)` on an `int` did not resolve, every such
implementation would have to leave the allocation-free path to render a number. A built-in has no
`impl` block, so what the call lowers to is the library renderer its membership provides — `5.add(3)`
exactly (`§5`), one row further down the catalog.

## 7. What stays deferred

- **~~`Index` and user-defined `[]`.~~ Built, and the associated type it was waiting on turned out
  not to be needed.** The reasoning that filed it here was that a subscript wants the element type
  and the index type, neither of which is `Self`, so the trait would have to *derive* the element
  from the container. That is one way to write it and not the only one: the element type can be an
  ordinary trait argument, because the type the block is written for settles it.

  ```
  trait Index[I, E]
      index(self, i: I) -> E

  trait IndexSet[I, E]
      index_set(*self, i: I, v: E)
  ```

  `impl[T] Index[usize, T] for Buf[T]` says a `Buf` of anything is read by a `usize` and gives back
  whatever it holds. That is **one promise per instantiation** — a `Buf[int]` implements
  `Index[usize, int]` and nothing else — which is exactly what a defaulted argument list on a
  generic subject already meant (`§3`). Nothing had to be added for it: a generic block's parameters
  are already required to be the arguments of the type it is for, each appearing once, so a
  parameter an argument can name is one the subject settles. What made this impossible before was a
  single refusal in the hoist that read every parameter in an argument list as an open one.

  **The index is not held to being an integer**, which the built-in subscript is: what a container
  is read by is the trait's own argument, so `e["answer"]` on a type that implements
  `Index[string, int]` is ordinary. A type may implement `Index` at more than one index type, and
  the written index says which (`02 § One implementation per argument list`).
  Built-in indexing of arrays, slices and strings is compiler-provided and unaffected; nothing a
  program writes competes with it.

  **Writing is a call, and the compound forms are refused.** A trait's method gives back a value and
  never an address, so a container's element is not a place — `b[i] = v` is `IndexSet`'s method, and
  `b[i] += v` would have to read the element and write it back, evaluating the receiver and the
  index twice. Written out as `b[i] = b[i] + v`, the program says that itself. **Slicing through the
  trait is not built**: `b[a..b]` would want a range as the index argument, and a range is not yet a
  type a program can name.

  **The customer this did not reach is reached now, and by the first shape considered.** `guide/png`
  reads a pixel as `img.at(x, y)` — two indices, which a subscript taking one argument cannot
  spell. Three shapes were weighed: a tuple, which sysl did not then have; a variadic index, which
  an index list cannot be; and a second trait per arity. **Tuples arrived (`00 §13`) and answered it
  with nothing added here**, because the index is the trait's own argument: `impl Index[(int, int),
  E]` reads by a pair, `IndexSet` writes by one, and `img[(x, y)]` is ordinary. The other two shapes
  are not wanted — a per-arity trait is the tuple's arity written into a name, which is the trade
  `00 §13` already made once and lost, and which `10 §10` has since bought back for the catalog's
  own rows.

- **~~The iteration protocol.~~ Built, and `s.chars` is what decided its shape.**

  ```
  trait Iterate[E]
      next(*self) -> Option[E]
  ```

  A `for` accepts a value of a type implementing it as a fourth thing, after a range, an array or a
  slice, and a string's two granularities. The loop evaluates the expression once into a slot of its
  own and calls `next` on that slot's address, so the cursor advances in place while what the
  program wrote stays a value like every other — draining `for c in it` leaves an `it` the program
  declared exactly where it was, which is the ordinary copy semantics rather than a rule of the
  loop's. Running out is normal completion, so an `else` runs; `continue` goes back to the test,
  because advancing is what `next` already did.

  **A container is not an iterator, and that is the design decision here.** There is no `IntoIterate`
  and no second trait: `for x in b.view()` walks a `Buf` by index with no call per element, so a
  cursor for it would be a slower way to do something that already works. The protocol exists for
  sequences whose elements have to be **computed**, and a container's never are. Nothing is lost by
  waiting: a trait that turns a value into a cursor is additive, and can be added the day something
  wants a container to be walked without naming its view.

  **`s.chars` is the customer that had no workaround**, and the reason the protocol is a value rather
  than a rule `for` knows. `04`'s granularity table specifies a string's Unicode scalar values, and a
  string cannot hand out a slice of them the way a container hands out a view of its storage —
  the decoding is what makes them. So there has to be something that carries a position and answers
  "the next one", and once there is, `for` accepting it is a smaller change than teaching `for` about
  strings. It is compiler-provided (`08`), lowering to the library's `Chars` over the bytes; the
  string is well-formed by construction, so the decoding validates nothing that was already checked
  at the door.

  What indexing settled applies here as it stood: `impl Iterate[char] for Chars` writes the element
  type as an ordinary trait argument, settled by the subject. The one place iteration differs is
  selection — every other trait's implementations are told apart by a call's arguments, and `next`
  takes none, so a type implementing `Iterate` twice leaves a `for` nothing to decide with. That is
  reported at the loop rather than at the call, because the sentence a program needs names the loop.

  **A `for` walks an erased cursor too.** `next` takes `*self` and mentions `Self` nowhere else, so
  `Iterate` has an object, and the loop's question — what may be *called* on this value — is what a
  table answers (`10 §5`). The element type comes out of the trait the object was erased to, read
  from the same requirement closure the table was laid out from, so the loop cannot disagree with the
  table about which member it is calling; and a trait *requiring* `Iterate` is walked for that
  reason. The ambiguity above cannot arise here, since an object names one trait at one argument
  list. What this buys is a function returning a cursor without naming which one it built.

- **~~A heterogeneous *operand*.~~ The catalog change is built; what it turned out to wait on is
  something else.** `§1` argues that `Self`-homogeneity costs nothing, because the scalars already
  obey it — `u8 + u8` is a `u8` and no operator promotes. That is true of every pair of scalars and
  does not reach the shape a vector space is made of: `Complex * f64`.

  The ten binary arithmetic and bitwise traits now take the right-hand type: `Mul` is
  `Mul[Rhs = Self, Out = Self]` with `mul(self, rhs: Rhs) -> Out`, and the operator dispatches on the
  **pair**. The defaults are what kept the change from touching anything already written — `impl Mul
  for Point` still means `Mul[Point, Point]`, and `[T: Mul]` still asks for `Mul[T, T]` — so
  `impl Mul[f64] for Vec3` and `v * 2.0` are ordinary code. `Eq` and `Ord` stay homogeneous: a
  comparison across two
  types raises questions about reflexivity and transitivity that nothing has asked, and the two
  traits provide six derived operators whose laws would all have to be restated.

  **~~What is deliberately not here: an `Output`.~~ Built, and by the same mechanism** — a second
  ordinary argument, `Out = Self`, not an associated type. The reasoning that left it out was that
  `Rhs` covered `Complex * f64` and `Vec3 * f64`; what it did not cover is a **dot product**, which
  returns neither operand's type, and `guide/matrix` is the program that could not be written without
  one. A matrix applied to a vector is the same shape: `impl Mul[Vec, Vec] for Mat`.

  The rule it added is that **the result does not select** — see `§1`. Everything else about the
  catalog is unchanged, because both parameters default to `Self` and a use that writes neither means
  what it always meant.

  **A built-in's membership is homogeneous, and that is `01`'s rule rather than a limitation.** An
  `int` is `Mul[int]` and is `Mul` at nothing else, because no scalar operator promotes; `2.0 * c`
  is therefore refused rather than reaching an `impl Mul[Complex] for f64`, since a scalar keeps its
  native instruction whatever a block elsewhere says (`§5`). Where a bare literal sits beside a
  **type parameter**, the parameter's own bounds decide what the literal is: `x - 1` in a `[T: Sub]`
  body is `T`'s own subtraction, `x * 2.0` in a `[T: Mul[f64]]` body is the `real` that bound names,
  and a parameter carrying no bound for the operator is left homogeneous so the diagnostic can ask
  for the bare bound.

- **One trait at more than one argument list — the half the catalog change did not cover, now
  shipped too.** The parameter on `Mul` did not by itself free the program that motivated it. A
  transform needs **both** `Complex * Complex`, the butterfly, and `Complex * real`, the scaling an
  inverse does to every sample — and those are `Mul[Complex]` and `Mul[real]`, two argument lists for
  one trait on one type, which `02`'s coherence rule refused. Both are in `sysl.math.complex` now,
  which writes each of its four arithmetic operators twice for exactly that reason, and `scale` is
  gone; what tells them apart is what was always going to tell them apart, the type of the right
  operand.

  It is **not** general member overloading, and that is the whole reason it was affordable. What is
  chosen among is the implementations of *one* parameterized trait, told apart by the argument list
  that declares them to be different — and every use carries one already: the operator has its pair
  of operands, a bound names the arguments, an object was formed at written ones, a call passes
  values of those types. The resolution is determined rather than preferred, so a call answering to
  none of the candidates or to more than one is reported instead of ranked. The rules and the two
  places they stop — a property, which has no argument to select with, and the shape boundary, where
  the implementations would be in two namespaces — are in `02 § One implementation per argument
  list` and `08 § One name, one member`.
- **A bound that promises a *value*.** Every trait in the catalog promises behaviour, which is what
  a trait is for, and three separate programs have now wanted one that promises a value instead.
  A generic container cannot declare `[16]K` for any `K` (`07 § Not yet`); a growable one cannot
  hold capacity that is not yet values, for the same reason; and `total[T: Add](xs: []T) -> T`
  cannot start its accumulation, so the most ordinary function there is has to seed from `xs[0]`
  and put an `Option` in its signature to cope with an empty input. The shape wanted is Rust's
  `Default` or a `Zero`, and what makes it a real decision rather than an obvious addition is that
  a value is not behaviour: a trait whose one member is an associated *function* returning `Self`
  is a different kind of promise from every other row of `§2`, and it interacts with the
  no-associated-types rule of `§1`. Not designed here; recorded with its customers so the next one
  does not open it again.

  **The half about having nowhere to ask is answered; the half about what the catalog declares is
  not.** A trait may declare a member with no receiver and it is reached through the type —
  `T.zero()` inside a bounded body, `real.zero()` from outside (`02 § Reaching a trait's members
  without a value`) — and `guide/sha2`, the fourth customer, is written that way now: the width, the
  round count and the two constant tables are asked of `T` and no longer of a word nobody reads.
  `sysl.math`'s `Float` declares seven such members, which is what lets its `signum` and `recip` be
  defaults and what lets a routine generic over the width start a running minimum at `T.infinity()`.

  What is left is smaller again, and the membership question it used to turn on has an answer.
  **`Integer` is that membership** — it names the open `iN` / `uN` family, it is the compiler's and
  not something a program can join, and `display.sysl` is written over it. So a sum over the
  built-in widths needs no zero promised to it at all: a literal takes its type from where it sits,
  and `total[T: Integer + Add](xs: []T) -> T` starts at `var acc: T = 0`.

  **No trait in the catalog declares a zero** still, and what that now costs is one case rather than
  the general one: a body serving the integers *and* a type of somebody else's — `guide/fft`'s sum
  over a width and a `Complex` — cannot reach both through `Integer`, so it declares a trait of its
  own and writes an `impl` per type. Whether the catalog should carry the identity that would join
  them is the open question; the families themselves are covered.

- **An enum cannot render its own variant names — and most of what this asked for was already
  answered one chapter over.** `str` on an enum is refused and the diagnostic is a good one: it names
  the `impl Display` to write. What this item then argued was that for the commonest case the impl's
  contents are already in the source — a match from each variant to the word it is spelled with —
  and that the compiler is holding that fact.

  It is, and it hands it over: **`T::Image(v)` is a simple enum's variant name as a `string`**
  (`09 §2`), sitting in the same attribute set as `First`, `Pos` and `Succ` and out of the member
  namespace for the same reason. `guide/scheduler`'s six-state `State` was this item's worked
  example, with a six-line renderer; that renderer is gone and its checks read `State::Image`.
  The library's `weekday_name` is one attribute read for the same reason. This item was written as
  though the fact were unreachable, and it never was.

  **Two things are left, and they are narrower than what was recorded here.** A **data** enum has no
  `Image` — refused in as many words, because a value there is a variant plus a payload and a name
  answers for half of it — which is also why the `describe` functions the guide programs write are a
  different ask: a fault's message is built out of the payload and only a person can write it. And
  `str` on an enum is still not the name, so a program that wants the value to *render* as its name
  asks for the name and prints that.

  Whether the second should close — a derived `Display` for the name-only case — is the question that
  stands, and it is the one this item should always have been about. It arrives with the whole
  deriving question attached, since sysl has **no deriving mechanism at all**: automatic for every
  enum, and therefore a member a program cannot replace without shadowing it, or asked for by
  something written at the declaration. Nothing here decides that. What has changed is the stake —
  with `Image` reachable, a derived `Display` saves the *asking*, not the table.

  **Half of the machinery exists now, and the deciding question is untouched.** `10 §10`'s `for
  const` is a loop the compiler unrolls, whose body is type-checked once per copy — which is exactly
  what doing something per part of a declaration needs, and it is what let the library's tuple rows
  become one block each instead of one per arity. What it has nothing to walk *here* is the list: a
  tuple's parts are reached by position, and an enum's variants and a struct's fields have names, so
  what is missing is a way to name one of those lists. That is a smaller gap than it was. It is not
  progress on this item's actual question, though — automatic against asked-for is a choice about
  what a program can replace, and no mechanism decides it.
- **An `Option` is not equatable, whatever it holds.** The catalog's memberships are written one
  type at a time and the library's own generic enums have none, so `a == b` over two `Option[u8]`s
  is refused and "is this the same link" is written by taking both sides apart in a `match`.
  `impl[T: Eq] Eq for Option[T]` is a *writable* implementation and would answer it, which is what
  makes this a catalog omission rather than a language gap — but it would be the first row of the
  catalog that a **generic** type carries, and the same question then arrives for `Result`, for
  `Ord`, and for `Hash`. Decide the family, not the one row. `guide/kernel`, whose every link is an
  `Option` of a slot number, is the customer.
- **Associated types** generally (`02` open item) — deferred, and with them any trait whose
  method mentions a type derived from `Self` rather than `Self` itself.
- **Deriving an operator through a default body.** Default method bodies are built (`02`), but the
  derived operators (`>` from `lt`, `!=` from `eq`) remain **compiler desugaring** rather than
  defaults a catalog trait writes. A user still implements the one required method per trait, and
  the derivations work as they always did.
- **`From`-style `?` conversion** (`11 §4`). Now unblocked in principle — it is a conversion
  trait plus a desugaring in `?` — but it is its own feature and is specified in `11`, not here.
- **A `Hasher` sink for `Hash`.** The streaming form (`§2`) composes better and would let a
  container pick its own algorithm, at the cost of a dynamic dispatch on every lookup. Recorded
  there with what taking it later would break.
- **~~Supertraits / trait hierarchies.~~ Shipped — `02 § A trait may require another trait`.** A
  trait may now require others (`trait Word: Add + BitXor`), and both customers were outside this
  catalog: `guide/sha2`'s nine-trait bound, and `guide/shapes`, where `trait Shape: Display` is what
  makes an erased value printable at all. **The catalog stays flat**, and deliberately: `Eq`, `Ord`
  and `Hash` are independent for the reasons above, and making `Ord` require `Eq` would be a
  behaviour change for every existing `impl Ord` rather than a tidying. The mechanism now exists if
  a reason ever appears; nothing here is one.

## 8. Open (not yet decided)

- **a. ~~Whether the core traits are stdlib source or pure compiler intrinsics.~~ Settled:
  library-declared, with compiler-known identity.** `Add`, `Ord`, `Eq` and the rest are ordinary
  trait declarations a program can read, and their methods are callable by name — `5.add(3)` is
  legal and is the machine's `add`. What the compiler holds is the identity: which operator each
  trait's one method *is*, and which built-in types are members. The memberships could not have been
  source `impl`s in any case, because the `iN` / `uN` families are open and have no finite list of
  types to write one for. This matches Swift's library protocols the compiler privileges. What is
  still open is the *module* question `13` owns — which module the catalog lives in once there is
  more than one — not whether it is source.
- **b. ~~Whether `str`/`print` desugar through the *same* `Display::display` for scalars.~~
  Settled: the scalar keeps its own path.** `print(5)` still calls the library renderer for its
  width and `str(5)` still builds the string directly, so a program that prints only numbers builds
  no sink and carries no method table. A scalar's `display` exists all the same, and is what
  `x.display(out, fmt)` reaches — the two agree to the byte, which is what made the choice free.
- **c. `Ord` totality and `NaN`.** `Ord` on a float uses IEEE `<`, which is not a total order at
  `NaN`. §2 keeps the scalars on their native ordered comparisons rather than on the derived ones
  precisely so this stays today's behaviour; whether a separate total-order facility is ever wanted
  (for sort keys) is deferred, not decided here. **The neighbouring defect this item used to record
  is fixed**: `!=` on floats lowered to `fcmp one`, making `NaN != NaN` false where IEEE says true.
  It is `fcmp une` now — IEEE makes `!=` the negation of `==` rather than the ordered comparison the
  other three are, so exactly one of the six comparisons answers true at a `NaN`. `01` states it.
- **d. ~~The `Writer` sink surface.~~ Settled — see `§2`.** One method on `[]u8`, a latched
  `failed` rather than a returned error, and the format specifier passed alongside the sink rather
  than folded into it. The last of those is Rust's `Formatter` capability without Rust's packaging:
  a `Formatter` carries the spec *and* is the sink, which is the only reason `{:>10}` works on a
  user type there; separating them gets the same reach and keeps the sink a plain trait a kernel
  can implement.

  Nothing remains open under it. The renderers **honour the specifier**, through the one
  `display_pad` the whole family ends at, so `f"${p}%8s"` puts a type's own text in a field of eight
  exactly as `f"${5}%8d"` does — see `§2`. What a precision means is left to each renderer, since
  the conversion letter is the part of a specifier that cannot cross a boundary drawn at "some
  value renders itself".
- **e. ~~Sharing an operand across a trait-dispatched operator.~~ Settled — see `§3`.** A chained
  comparison and a compound assignment each use one operand twice from a single evaluation, and both
  now work on a trait-dispatched type. What settled it was noticing that codegen already holds the
  shared operand in a register for the scalar lowering, so what a dispatched operator needs from the
  analyzer is a **method to apply to that value** rather than a call tree over the operand's own
  expression. No synthesized bindings, and no new evaluation-order question — the answer to the one
  §2 used to have is that there is nothing to reorder.
