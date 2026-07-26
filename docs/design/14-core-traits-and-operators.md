# Core Traits, Operators, and Definition-Checked Bounds

**Status:** decided, and **§1–§6 are implemented** — `Self`, the whole catalog, the one dispatch
rule, definition-checked bounds on both method calls and operators, the compiler-provided scalar
memberships, and `print`/`str` requiring `Display`. `§8 b` and `§8 d` settled on the way; what is
left open is listed in `§8`. This is the concrete spec for three things the earlier chapters
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

`Self` is distinct from the lowercase receiver `self` (`08`): `self` is the receiver *value*,
`Self` is its *type*. This mirrors Swift (`Self`), Rust (`Self`), and Scala (`this.type`), and it
is the piece `02` and `08` needed before an operator trait could be written at all — an operator
like `+` is homogeneous (`Self + Self -> Self`), and only `Self` can say that in a trait.

**No associated types.** Rust's `Add` carries an associated `type Output`, so `+` may return a
type other than the operands'. sysl does not: an operator trait is `Self`-homogeneous, and its
result type *is* `Self`. This is not a loss the target code feels — the existing scalar rule is
already exactly this: **both operands of a binary operator have the same type, and no operator
promotes** (`01`). `u8 + u8 -> u8`, never `-> u16`. Making the operator traits `Self`-only is
therefore not a simplification the language pays for later; it is the *same rule the scalars
already obey*, lifted to user types. Associated types remain deferred (`02` open item); the one
operator that genuinely needs a second type — `Index`, whose element differs from the container —
waits on them (`§7`).

## 2. The core trait catalog

These traits are **library traits with compiler-known meaning**: the standard library declares
them (or will), the compiler maps each operator token to one, and the built-in scalar types are
given their impls by the compiler (`§5`). A user type opts in with an ordinary `impl`.

### Arithmetic and bitwise — `Self`-homogeneous, one method each

| Trait | Method | Operator |
|---|---|---|
| `Add` | `add(self, rhs: Self) -> Self` | `+` |
| `Sub` | `sub(self, rhs: Self) -> Self` | `-` (binary) |
| `Mul` | `mul(self, rhs: Self) -> Self` | `*` |
| `Div` | `div(self, rhs: Self) -> Self` | `/` |
| `Rem` | `rem(self, rhs: Self) -> Self` | `%` |
| `BitAnd` | `bitand(self, rhs: Self) -> Self` | `&` (binary) |
| `BitOr` | `bitor(self, rhs: Self) -> Self` | `\|` |
| `BitXor` | `bitxor(self, rhs: Self) -> Self` | `^` |
| `Shl` | `shl(self, rhs: Self) -> Self` | `<<` |
| `Shr` | `shr(self, rhs: Self) -> Self` | `>>` |
| `Neg` | `neg(self) -> Self` | `-` (unary) |
| `Not` | `not(self) -> Self` | `~` |

The compound-assignment forms (`+=`, `&=`, …) are **not** separate traits: `a += b` is defined
as `a = a + b` and so requires exactly the trait `+` requires. There is no `AddAssign`.

The shift traits keep the `Self` right-hand type for symmetry with the scalar rule that the shift
amount takes the shifted value's type (`01`). A heterogeneous shift is still written with an
explicit conversion, exactly as it is for scalars.

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
does not imply `Eq` (a type wanting both implements both). There is no supertrait relation to
introduce, and no four-way Rust `PartialEq`/`Eq`/`PartialOrd`/`Ord` tower — the partial/total
distinction Rust draws for `NaN` is not modelled at the type level here, matching sysl's existing
treatment of float `<` as the plain IEEE comparison.

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

**Which writers the prelude supplies: neither, and that is deliberate.** The two that `print` and
`str` themselves need are the compiler's, because neither has a *type* sysl can give it — a writer
over standard output holds no state and there is no struct with no fields, and a growable byte
buffer is `07`'s *Not yet*. What a program writes for itself is an ordinary `impl Writer for
MyThing`, which is the case the trait exists for. The standard-output writer's `write` is the
prelude's own `putbytes`, so the one function a freestanding target replaces is still that one.

## 3. One dispatch rule for operators

An operator expression is a **trait-method call**, resolved the same way in every context — the
difference between a scalar, a user type, and a bounded type parameter is only *where the impl
comes from*, never the rule:

`a ⊕ b`, for an operator `⊕` mapped to trait `Op` with method `m`, means `Op::m(a, b)`, and it
type-checks iff the type of `a` (which must equal the type of `b`) satisfies `Op`. Then:

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

- Every numeric type (integers and floats) is `Add`, `Sub`, `Mul`, `Div`, `Eq`, `Ord`, and
  `Display`. The signed integers and the floats are additionally `Neg`.
- Every integer type is `Rem`, `BitAnd`, `BitOr`, `BitXor`, `Shl`, `Shr`, and `Not`. `Rem` is here
  rather than with the numeric types because `%` is integer-only in `01`'s operator table: there is
  no float remainder to lower, and a membership wider than the table would promise a bounded generic
  an operation that fails at the instantiation the bound was supposed to have proven.
- `char` is `Eq`, `Ord`, and `Display`, and has **no** arithmetic or bitwise membership (`01` —
  `char` has equality and ordering only).
- `bool` is `Eq` and `Display`, and is **not** `Ord` (`01` — `bool` has equality, no ordering).
- `string` is `Add` (concatenation, the one string operator — `04`), `Eq`, `Ord`, and `Display`.
- The pointer modes `*T`/`&T` are `Eq` only (address equality; no ordering — `01`), and
  deliberately **not `Display`**: an address renders differently on every run, so a program that
  wants one in its output asks for it explicitly rather than getting it from `print(p)`. This is a
  narrowing if `print` accepts a pointer today, and the intended one — the diagnostic becomes the
  ordinary "`*T` is not `Display`."

These memberships change no codegen: a scalar operator still lowers to its native instruction
(`§3`). Their sole job is to make the type system agree that a scalar satisfies the bound a
generic asks for, so `sum(3, 4)` and `sum(3.0, 4.0)` both instantiate `sum[T: Add]`.

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
`impl` block, so what the call lowers to is the prelude renderer its membership provides — `5.add(3)`
exactly (`§5`), one row further down the catalog.

## 7. What stays deferred

- **`Index` and user-defined `[]`.** Overloading indexing needs the element type and the index
  type, which differ from `Self` — the one operator that genuinely wants an associated type. It
  waits on associated types (`§1`). Built-in indexing of arrays, slices, and strings is
  compiler-provided and unaffected.
- **Associated types** generally (`02` open item) — deferred, and with them any trait whose
  method mentions a type derived from `Self` rather than `Self` itself.
- **Default method bodies** (`02` open item). The derived operators (`>` from `lt`, `!=` from
  `eq`) are **compiler desugaring**, not user-visible default methods, so this feature can stay
  deferred while the derivations still work. A user still implements the one required method per
  trait.
- **`From`-style `?` conversion** (`11 §4`). Now unblocked in principle — it is a conversion
  trait plus a desugaring in `?` — but it is its own feature and is specified in `11`, not here.
- **Supertraits / trait hierarchies.** Not needed by anything above (`Eq` and `Ord` are
  deliberately independent), so not introduced.
- **Operator traits as bounds on struct/enum parameters** (`10` open b/c) — a `struct
  SortedList[T: Ord]` — waits on the generic-member and type-parameter-bound work; `§4` specifies
  bounds on **function** parameters, which is where they are exercised.

## 8. Open (not yet decided)

- **a. ~~Whether the core traits are stdlib source or pure compiler intrinsics.~~ Settled:
  prelude-declared, with compiler-known identity.** `Add`, `Ord`, `Eq` and the rest are ordinary
  trait declarations a program can read, and their methods are callable by name — `5.add(3)` is
  legal and is the machine's `add`. What the compiler holds is the identity: which operator each
  trait's one method *is*, and which built-in types are members. The memberships could not have been
  source `impl`s in any case, because the `iN` / `uN` families are open and have no finite list of
  types to write one for. This matches Swift's library protocols the compiler privileges. What is
  still open is the *module* question `13` owns — which module the catalog lives in once there is
  more than one — not whether it is source.
- **b. ~~Whether `str`/`print` desugar through the *same* `Display::display` for scalars.~~
  Settled: the scalar keeps its own path.** `print(5)` still calls the prelude renderer for its
  width and `str(5)` still builds the string directly, so a program that prints only numbers builds
  no sink and carries no method table. A scalar's `display` exists all the same, and is what
  `x.display(out, fmt)` reaches — the two agree to the byte, which is what made the choice free.
- **c. `Ord` totality and `NaN`.** `Ord` on a float uses IEEE `<`, which is not a total order at
  `NaN`. §2 keeps the scalars on their native ordered comparisons rather than on the derived ones
  precisely so this stays today's behaviour; whether a separate total-order facility is ever wanted
  (for sort keys) is deferred, not decided here. A neighbouring question this chapter does *not*
  answer: `!=` on floats lowers to `fcmp one`, so `NaN != NaN` is false where IEEE says true. That
  is a pre-existing scalar-semantics question for `01`, not a consequence of anything here.
- **d. ~~The `Writer` sink surface.~~ Settled — see `§2`.** One method on `[]u8`, a latched
  `failed` rather than a returned error, and the format specifier passed alongside the sink rather
  than folded into it. The last of those is Rust's `Formatter` capability without Rust's packaging:
  a `Formatter` carries the spec *and* is the sink, which is the only reason `{:>10}` works on a
  user type there; separating them gets the same reach and keeps the sink a plain trait a kernel
  can implement.

  What remains open under it is smaller than it was: **no writer honours a `FormatSpec` yet.** The
  spec reaches every implementation, and a type that wants to act on it can, but the prelude's own
  renderers ignore it — so `f"${5}%8d"` pads through `snprintf` while `f"${p}%8s"` pads only if `p`
  says how. Closing that means padding in the renderers, which wants a scratch buffer and is worth
  doing once rather than six times.
- **e. ~~Sharing an operand across a trait-dispatched operator.~~ Settled — see `§3`.** A chained
  comparison and a compound assignment each use one operand twice from a single evaluation, and both
  now work on a trait-dispatched type. What settled it was noticing that codegen already holds the
  shared operand in a register for the scalar lowering, so what a dispatched operator needs from the
  analyzer is a **method to apply to that value** rather than a call tree over the operand's own
  expression. No synthesized bindings, and no new evaluation-order question — the answer to the one
  §2 used to have is that there is nothing to reorder.
