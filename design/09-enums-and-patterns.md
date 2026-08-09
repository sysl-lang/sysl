# Design Decisions: Enums and Pattern Matching

**Status:** written against the implementation that already exists (enums, `match`, patterns,
exhaustiveness) and ratifies it where the code made a sound choice; the sections marked *open*
record decisions the code made implicitly, or gaps it left, that still need a ruling. This
chapter is a foundation for two others: `Option` and `Result` are ordinary generic enums
(`10-generics.md`), and the `?` operator is sugar over a `match` on one (`11-error-handling.md`).

The through-line: an enum is either a plain **named-constant set** or a **sum type** whose
variants carry data, and a `match` is the single expression that takes one apart. Everything
about how patterns bind, how a match stays honest about the cases it does not handle, and how
refcounts survive a destructuring falls out of the memory model (`03`) with no new machinery.

---

## 1. Two kinds of enum, one keyword

`enum` introduces either shape; which one is decided by whether any variant carries a payload.

```
enum Color                     // simple: a named set of integer constants
    Red                        //   = 0
    Green                      //   = 1
    Blue = 10                  //   explicit discriminant
    Yellow                     //   = 11 (continues from the last value)

enum Shape                     // sum type: variants carry data
    Circle(radius: int)
    Rect(w: int, h: int)
    Empty                      //   a nullary variant is legal in either kind
```

A **simple enum** is a set of named integer constants, in the C/Go tradition — a type-safe
replacement for a pile of `const` values. A **data enum** (sum type / tagged union) is the
Swift/Rust/Kotlin `sealed` shape: a closed set of variants, each optionally carrying a payload,
that `match` destructures exhaustively. The two are the same declaration form because a simple
enum is just the degenerate case where no variant has a payload — there is no second keyword to
learn, and a nullary variant reads the same in both.

**Why one keyword and not `enum` vs `sealed`/`union`.** The distinction a reader cares about is
"does this variant carry data," which is *visible at each variant*. A second keyword would put
the same information in two places and let them disagree. This follows Rust (`enum` covers both
C-like and data-carrying) over Swift/Kotlin (which reserve `enum` for the simple case and reach
for associated values / `sealed class` for sums) — because sysl's sum type is the more load-
bearing of the two and should not be the one wearing the heavier syntax.

## 2. Simple enums — discriminants and underlying type

A simple enum's variants are integer constants. A bare variant takes the previous variant's
value plus one, starting at `0`; an `= expr` sets an explicit value that the auto-increment
then continues from. This is exactly C's rule, and `Blue = 10; Yellow` making `Yellow == 11`
is the behavior every systems programmer already expects.

- **Distinct type, not an int.** `Color` is its own type; a `Color` and an `int` do not mix
  without an explicit cast, the same no-implicit-promotion discipline the scalars use (`01`).
  This is what makes a simple enum a *type-safe* named-constant set rather than a bag of `int`s.

**The underlying integer type is selectable — the fix for C's worst enum wart.** In C the
underlying type of an enum is implementation-defined, so an enum is unusable for the two things
a systems programmer most wants it for: a struct field of known width and a value read off a
wire. sysl pins it, and lets the author choose it:

```
enum Color: u8                 // one byte, values 0..=255
    Red
    Green
    Blue = 10
    Yellow

enum Pin: u4                   // four bits — an arbitrary-width integer (00 §5)
    A0
    A1
```

The type after the `:` may be **any integer type, including an arbitrary-width `iN`/`uN`**,
which goes past C23 (whose fixed-underlying-type feature admits only the standard integer
types) and turns a simple enum into a first-class tool for packed hardware-register fields.
**Unspecified, the underlying type is `int` (i32)** — the same portable default an unsuffixed
literal takes (`00 §8`). The underlying type governs three things: the enum value's **storage
size**, the **range each explicit discriminant must fit** (an out-of-range discriminant is a
compile error), and **signedness** (a `uN` underlying type rejects a negative discriminant).
The annotation is only meaningful on a non-generic simple enum; a generic or data enum's tag
width is a separate, later concern (§ Open a).

**Two variants may not stand for one value**, however the collision arises — two explicit
discriminants, or an auto-incremented one landing on a number written out further down. The reason is
that a simple enum's value *is* its identity: there is nothing else carried, so two names for one
number are one value with two spellings, and every promise this chapter makes about telling variants
apart stops holding. `Pos` and `Val` stop being inverses; the second variant's `match` arm can never
run, silently; and `Image` has two answers to one question. C permits the collision because a C enum
offers none of those; sysl refuses it, and the diagnostic names both variants and the value they
share. **Deliberately naming one value twice is what a `const` is for** — the aliasing use C reaches
for (`COLOUR_MAX = BLUE`) is a constant, not a variant, and stays writable. A data enum cannot reach
the case at all: its tags are handed out in order and an explicit value on a data-carrying variant is
refused on its own grounds.

**Conversion is checked in the unsafe direction, mirroring `char` (`00 §1`).** Going *to* the
underlying integer — `u8(c)`, `int(c)` — is **total**, every enum value is a valid integer.
Coming *from* an integer has two spellings by how trustworthy the value is: `Color(n)` is a
**checked cast that traps** on an integer that is not a declared discriminant (the fast path
for a value already known good), and `Color.try(n) -> Option[Color]` is the **fallible
constructor** returning `None` for an unknown value (the required path for bytes off a wire).
This closes C's other enum hole — "every int is silently a valid enum value" — the same way the
`char` decision closes it for codepoints.

- **`else` in a value match.** Because a simple enum is backed by an integer whose value set is
  the declared constants, a `match` on one is exhaustive when it covers every named variant *or*
  carries a catch-all — the same rule as a data enum (§8).

**The enum's own name answers a fixed set of `::` attributes**, in the same spelling and for the
same reason a constrained subtype's does (`16 §5`): `::` keeps them out of the member namespace, so
`Color::First` cannot collide with a variant, an associated function or a member an `impl` added.

| written | is | traps |
|---|---|---|
| `T::First` / `T::Last` | the first and last variant | no |
| `T::Pos(v)` | a value's **0-based position** in the declaration | no |
| `T::Val(i)` | the value at position `i` | on a position past the last, or below zero |
| `T::Succ(v)` / `T::Pred(v)` | the neighbouring value | at the last / at the first |
| `T::Image(v)` | the variant's **name**, as a `string` | no |
| `T::Value(s)` | the value a name stands for | on a name no variant has |

**Position is not the discriminant, and that distinction is the reason `Pos` and `Val` exist.**
Discriminants may be explicit, non-contiguous and not zero-based, so an ordinal has to be looked up
rather than computed; `Pos` and `Val` are the two directions of that lookup, and `Succ`/`Pred` walk
the declaration order rather than adding one. Going the other way — a value's *discriminant* — is
the conversion above, `int(c)`.

`Image` and `Value` are the printed-name pair, and they are what makes a simple enum observable:
the type carries no `Display` of its own, so a value is turned into a position or a name to be
looked at.

**It does carry `==`.** Every variant of a simple enum is dataless, so the value *is* its
discriminant and there is exactly one thing equality could mean — which is the same pair of facts
that makes the open `iN` family a compiler-provided member of `Eq` rather than something a library
could write blocks for (`14 §5`). It is `Eq` and not `Ord`: the declaration order is an order and
not a *meaning*, and an enum whose order says something writes the `impl` that says it.

These are for a **simple, non-generic** enum. A data enum's value is a variant plus a payload, so a
position, a name and a neighbour are each questions about only half of it; a generic enum's name
stands for no one value set until it is applied. Asking either says so.

## 3. Data enums — sum types

A data variant names an ordered, typed payload — `Circle(radius: int)`, `Rect(w: int, h: int)`
— and a nullary variant names none. A value of the enum is **one** of its variants plus that
variant's payload; the compiler knows the set is closed, which is what makes exhaustiveness
checkable.

**Construction is calling the variant.** `Circle(3)` builds a `Shape`; `Empty` names the
nullary one. There is no `Shape.Circle` qualification required at the construction site — a
variant name is in scope as a constructor of its enum. Which memory mode the result takes
follows the ordinary rule (`03`): a data enum is a **value** by default (its storage is sized
for the largest variant plus a tag, and it moves by copy like any value), and it lands on the
heap only where a `&Shape` is expected. Nothing about being a sum type changes the three-mode
story — an enum is just a struct-shaped value with a tag.

**The payload really is one region.** A four-variant enum carrying one scalar each is one scalar
wide, not four: the tag is followed by a single area sized for the widest variant and aligned for
the strictest one. So `Step` above — three of whose variants carry an `int` and one a `u8` — is
eight bytes, and a table of two hundred tasks holding eight steps each costs what the paragraph
above says it costs rather than two and a half times that.

The cost of saying so is that a payload cannot be reached with `extractvalue`: an aggregate value
has no operation that reinterprets part of itself as another type, which is exactly what reading a
union is. A construction writes the payload into a stack slot at its own variant's type and reads
the whole enum back out; a match does the reverse. The compiler therefore has to know how wide the
widest variant is *before* LLVM sees the module, since an array length in the emitted text is a
literal — that is what `Layout` is, and it is the only place the compiler models storage. The
language's `sizeof` (`03 § Reinterpreting storage`) is the same measurement asked from the outside,
so the two agree by construction rather than by being kept in step.

**Recursion goes through an indirection, as always (`03`).** A variant that holds the enum by
value would make the type infinitely sized and is rejected; a recursive data type reaches
itself through a `*T` or `&T`, which is how a `List`/`Tree` enum is spelled.

## 4. Generic enums

An enum may be generic in one or more type parameters, which is what lets the two most important
enums in the language — `Option[T]` and `Result[T, E]` — be ordinary library declarations rather
than compiler built-ins:

```
enum Option[T]
    Some(value: T)
    None

enum Result[T, E]
    Ok(value: T)
    Err(error: E)
```

The parameter list, inference at construction, and monomorphization are the subject of
`10-generics.md`; this chapter only fixes that enums are one of the types that take parameters,
and that pattern matching binds a variant's payload at its *instantiated* type (matching
`Some(x)` on an `Option[int]` binds `x: int`).

---

## 5. `match` — the expression that takes an enum apart

`match` is an **expression** (`00` §10): it yields a value in value position and reads as
ordinary control flow in statement position. Its shape is a scrutinee and a sequence of arms,
each an ordered list of patterns, an optional guard, and a body:

```
classify(n: int) -> string
    n match
        0            -> "zero"
        1 | 2 | 3    -> "small"
        4..10        -> "medium"
        else            "large"
```

**The `else` arm carries no `->`.** The arrow separates a *pattern* from what to do when it
matches, and `else` is not a pattern — it is the fallback the arms above did not cover, and it
takes its body the way an `if`'s `else` takes one. Writing `else -> …` would be putting a
separator between a body and nothing, so it is refused by name rather than left to fail as a body
that happens to start with an arrow.

**The keyword goes after the value, as Scala's does.** A match is a transformation of the thing
to its left, and writing it there is what lets one feed another: `x match … match …` reads in the
order the values flow, where a prefix `match` would have made the second wrap the first and put
each block of arms at a different distance from the value it chooses between. The same reasoning
covers the ordinary case with one match in it — a long scrutinee is read once, left to right,
rather than after jumping back over the keyword to find where it starts.

It binds **looser than every operator**, so the scrutinee is the whole expression written before
it: `a < b match` chooses on the comparison, and `x = y match` is an assignment whose right side is
a match. Parentheses are what narrow it, as everywhere else.

Two evaluation guarantees, both settled and tested:

- **The scrutinee is evaluated exactly once.** A side-effecting scrutinee runs one time, and
  arms test against the resulting value; it is not re-evaluated per arm.
- **Arms are tried top to bottom; the first whose pattern matches (and whose guard, if any,
  holds) wins.** This first-match rule is what makes a specific arm above a general one behave
  as written, and it is the basis for the guard-fallthrough in §6.

## 6. Patterns

The pattern forms the implementation accepts, each a decision this chapter ratifies:

| Pattern | Example | Matches |
|---|---|---|
| Wildcard | `_` | anything, binds nothing |
| `else` arm | `else …` | anything; the catch-all spelling in tail position |
| Literal | `0`, `'a'`, `"hi"`, `true` | a value equal to the literal (see the type rule below) |
| Range | `3..7`, `0..<10`, `'a'..'z'` | a value in the range, inclusive `..` / exclusive `..<` per `00` |
| Bind | `r`, `other` | anything, and binds the value to the name |
| Reference | `` `limit` ``, `` sdl.`SCANCODE_A` `` | a value equal to what the name already stands for; binds nothing |
| Variant | `Circle(r)`, `Empty`, `Shape.Empty` | the named variant, binding each sub-pattern to a field |
| Nested | `Wrap(Val(v))` | a variant whose payload itself matches a sub-pattern |
| Struct, positional | `Point(a, b)` | a struct, binding every field by position |
| Struct, named | `Point{x, y}`, `Point{x: a}` | a struct, binding fields by name; unlisted fields are unconstrained |
| Named | `c @ Circle(r)` | what the sub-pattern matches, binding the **whole** value besides |

**`n @ pat` — matching and naming at once (settled).** Destructuring leaves the arm with the parts
and not the value, so an arm wanting both had to test the shape twice or give the destructuring up.
The `@` form is both at once, and it is the spelling Scala, Rust, OCaml and Haskell all settled on.

A binding is **not a test**, which is what keeps this a small rule rather than a second matching
mechanism: a named arm covers what its sub-pattern covers, so exhaustiveness sees straight through
it and every analysis that asks what a pattern *tests for* strips the name first. It nests, since
what follows the `@` is an ordinary pattern; the name has to be one a program could declare, so a
qualified name is refused for the reason the bare-name rule refuses one.

**A name is bound once per pattern (settled).** Two arms may reuse a name — each arm is a scope —
but a repeat *inside* one pattern is refused. `Point(v, v)` used to compile and quietly bind the
second, which reads like a test that the two fields are equal and is not one; no pattern here
compares two parts of a value, and a guard is what does. The rule is the same one Scala, Rust, OCaml
and Haskell apply, and it is a property of the whole pattern rather than of a scope, since a pattern
binds once however deeply it nests.

**The bare-name rule (settled).** A bare identifier in pattern position is a **nullary-variant
pattern** when it names a nullary variant of the scrutinee's enum, and a **binding** otherwise.
So in a `match` on `Shape`, `Empty` tests the variant, while `other` (not a variant) binds. A
bare name that happens to be a *data* variant is a diagnostic — `variant 'Circle' carries data
— match it as 'Circle(…)'` — rather than a silent binding, which closes the classic trap where
a misspelled or data-carrying variant silently becomes a catch-all binding. This is the same
resolution Rust and Swift reach; sysl makes the data-variant case a hard error rather than a
lint.

**A backticked name is a REFERENCE, and never a binding (settled).** The rule above resolves a bare
name against a narrow set — the scrutinee's nullary variants, then the constants — and binds
otherwise. That set is deliberately narrow, and the cost of narrowness is that everything outside it
is unreachable: a `val`, an `extern` variable, a local, a parameter. Each of those is storage read
while the program runs, so there is no value for a *compile-time* pattern to compare against — which
is the reasoning that refused them, and which holds only for as long as the pattern has to be
decided at compile time.

Quoting the name says the test was meant, so the arm becomes an ordinary equality against whatever
the name holds when the match runs:

```
val limit: int = read_setting()

n match
    `limit` -> "at the limit"        -- tests; n == limit
    limit   -> "anything at all"     -- binds; a new local named limit, matching everything
    else "elsewhere"
```

**The two spellings are the whole of the difference, and that is the point.** Rust's documented trap
is that a name in a pattern silently changes meaning with what happens to be in scope; sysl's
narrow resolution avoids it by refusing the ambiguous cases outright, and the backticks reopen them
*with the meaning written at the site*. A reader does not have to know what is in scope to know
which was meant.

**Three consequences follow rather than needing rules of their own.** A quoted name that resolves to
nothing is a diagnostic, not a new local — the reading a qualified name already gets below. A
`const` still folds to its literal, so quoting one changes nothing but the reader's certainty. And a
runtime equality tells exhaustiveness nothing, so an arm written this way never discharges a case
and a catch-all stays required — the same position a literal pattern over a non-`bool` is already
in.

**A pattern is now a place a name is READ, which it never was before.** Every other form either
binds a name or holds a literal, so a walk over an arm could take the patterns for their bindings
and look for reads only in the guard and the body. A quoted name breaks that, and the consequence is
visible in the language rather than only in the compiler: a **closure or a nested function captures
a name it mentions nowhere but in a pattern** (`12 §5a`, `§7`), because that mention is a read of the
enclosing frame like any other.

```
outer() -> string
    val limit = 10

    inner(n: int) -> string
        n match
            `limit` -> "at the limit"    -- captures `limit`, though nothing else names it
            else "elsewhere"

    inner(10)
```

**It cannot stand at an irrefutable binding.** `` val `limit` = 3 `` is refused for the reason every
testing pattern is: a binding has no other arm to take when the value differs. The diagnostic says
to drop the quoting, because at a binding the name is nearly always the name that was wanted.

**A qualified name is a pattern wherever a bare one is.** `isa.Halt` and `Op.Bare` match exactly
as `Halt` and `Bare` do — the prefix is dropped, since the scrutinee's type already settled which
enum is meant, the same reading a variant pattern's qualifier gets. What a qualified name cannot
be is a *binding*: no program declares a name with a dot in it, so where one resolves to neither
a variant nor a constant it is a diagnostic rather than a new local. Without this a nullary
variant was the one form with nowhere to put a qualifier, since it is spelled like a name.

**Alternatives (`|`) may not bind (settled).** `1 | 2 | 3` is one arm matching any of three
literals; but `Some(x) | none-arm` binding `x` is rejected, because the body cannot know which
alternative matched and therefore cannot know a binding's origin. This is stricter than Rust
(which permits `A(x) | B(x)` when every alternative binds the same names at the same types) and
is worth revisiting once there is a motivating case (§ Open c); for now the rule is simple and
unambiguous: an arm with `|` binds nothing.

**Literal patterns match any type with equality; range patterns need a contiguous order.** The
two gates are deliberately different:

- **A literal pattern is allowed on any type a literal can name and `==` can test** — the
  integers, `char`, `string`, and **`bool`** (`true` / `false`). Matching a boolean by pattern
  is the natural spelling (`match b` with `true ->` / `false ->` arms) and it is exhaustive
  when both are present, with no catch-all needed.
- **A range pattern is restricted to the numeric types and `char`** — the types over which a
  contiguous `lo..hi` interval is meaningful. `string` is deliberately excluded: `"a".."z"` has
  no useful meaning, and admitting it was an accident of gating ranges on the same
  ordered-type test as equality. A `string` is matched by literal or by binding, not by range.

**Tuple patterns are struct patterns with the name left off.** `(a, b)` binds both components of a
tuple (`00 §13`), `(a, _)` binds one, and a nested tuple pattern nests — all of it the positional
form below with nothing before the parenthesis, so it needs no machinery of its own. A tuple has one
shape, so a tuple pattern is irrefutable and discharges its column for exhaustiveness exactly as a
struct pattern does.

**Struct patterns — positional *and* named-field (settled).** A `struct` is destructured two
ways, and both are supported:

```
p match
    Point(a, b)   -> …        // positional: bind every field, in declaration order
    Point{x, y}   -> …        // named, shorthand: field x → x, field y → y
    Point{x: a}   -> …        // named, renamed + partial: field x → a, other fields unconstrained
```

The two forms are a deliberate **division of labor**, not two spellings of one thing:

- **Positional (`Point(a, b)`) is total.** It mirrors construction — sysl builds a struct
  positionally (`Point(1, 2)`, `03`), so tearing it apart positionally is symmetric — and it
  **must name every field** (use `_` to skip one). Adding a field to the struct therefore turns
  each positional match into a checked arity error, the way a new enum variant does: this is the
  *handle-everything* tool. It is textually identical to a variant pattern but not *ambiguous* —
  the compiler resolves `Point(a, b)` by what `Point` denotes (a struct type is a struct pattern,
  an enum variant is a variant pattern), the same name-resolution the bare-name rule uses above.
- **Named-field (`Point{x, y}`) is partial by default.** It binds by field *name*, so it is
  **order-independent** (it survives a field reorder that would silently rebind a positional
  pattern), supports **renaming** (`{x: a}` binds field `x` to `a`), and **matches a subset** —
  any field left unlisted is simply unconstrained, needing no marker. Adding a field to the
  struct never breaks a named pattern: this is the *grab-what-I-need* tool. There is no `..`
  token, because positional already covers the total case and an explicit "and the rest" would be
  redundant noise here.

Both forms **compose** with the rest: a struct pattern nests inside a variant pattern and vice
versa (`Some(Point{x})`, `Wrap(Point(a, b))`), and each bound sub-pattern is itself any pattern
in this section. Matching a field twice, an unknown field name, wrong positional arity, or a name
that denotes a different struct are each diagnostics.

## 7. Guards

An arm may carry an `if` guard evaluated **after** its pattern matches. Three settled rules:

- **A guard runs only when its pattern has already matched** — never for an arm that was ruled
  out — so a side-effecting guard fires exactly on the arms whose shape fits.
- **A failed guard falls through to a later overlapping arm.** `1..10 if n > 5` above `1..10`
  means "high when >5, low otherwise" — the first-match rule plus fallthrough, not a partition
  of distinct wildcards.
- **A guarded arm does not count toward exhaustiveness.** Because the compiler cannot prove a
  guard holds, an arm with a guard never discharges a variant's obligation; a `match` covered
  *only* by guarded arms still needs a catch-all. This is the Rust rule, and it is what keeps
  exhaustiveness a real guarantee rather than a formality.

## 8. Exhaustiveness

The rule the analyzer enforces, ratified here as the language's:

- **A `match` on a data enum must cover every value or carry an unguarded catch-all.** A gap is
  a compile error that *names what is missing* (`missing Circle, Rect (add an 'else' arm)`), so
  adding a variant to an enum turns every non-catch-all match on it into a checked to-do list —
  the central payoff of a closed sum type.
- **Coverage is about which values are guaranteed handled, not which tags appear**, and the arms
  answer that question *together*. `Some(Halt)`, `Some(Push)` and `None` cover an `Option[Op]`
  between them even though no one of them covers a variant on its own, and `Some(0)` alone does
  not cover `Some`, because a `Some` holding a non-zero value slips through.
- **What is missing is named at the depth it is missing at.** A gap inside a payload reports as
  `missing Some(Push)` rather than as `missing Some`, and a column no arm narrowed stays a `_`
  standing for all of its values — `Turn(E, _, _)` rather than one line per combination behind it.
  Where the gap is a number's or a string's complement there is no pattern that names it, so the
  complaint says only that the match must be exhaustive.
- **A type is covered by listing its values only when it has a finite, known set of them** — an
  enum's variants, a struct's single shape, `bool`'s two. Everything else is covered by a wildcard
  or a binding and by nothing shorter, which is why `1 -> … 2 -> …` on an `int` still needs an
  `else`.
- **A scalar `match` must be exhaustive only when it is used for a value.** In statement
  position a non-exhaustive scalar match is fine (the unmatched case is a no-op); used for a
  value it must carry a catch-all, since every use of the result needs a value to exist. An
  enum match is exhaustive-checked in *both* positions, because falling off the end of a match
  on a value with no catch-all has no defined result even for effect.

**A catch-all is a wildcard or a bind in tail position** — `_` or `else` or a bare binding —
carried by an unguarded arm. `else` and `_` are currently the same thing to the analyzer (both
lower to a wildcard); `else` is the conventional spelling in tail position, `_` the one that
reads inside a `|` or a nested pattern.

## 9. The value type of a match, and the `&T` context

- **A match used for a value takes the common type of its arms.** When every arm's body yields
  the same non-unit type, that is the match's type; a match whose arms are only run for effect
  is `unit`.
- **An arm that does not finish constrains nothing.** An arm that aborts or returns has type
  `never` (`00 §11`), so it is set aside before the others are compared and the match takes their
  type — which is what makes `None -> exit(1)` beside `Some(v) -> v` an `Option[T]`'s `T` rather
  than a conflict. Exhaustiveness is unaffected: a diverging arm still has to be *reachable* by a
  pattern that covers something.
- **A `&T` context reaches each arm, not the whole match.** Under a `&Point` expectation, an arm
  that yields a bound `&Point` payload and an arm that builds a fresh value `Point` meet at
  `&Point` — the value arm is boxed on its own, the reference arm passes through untouched — the
  same per-branch boxing `if` uses (`03`). Boxing the whole match instead would fail, because an
  arm that is already `&Point` cannot un-become a value.

**Disagreeing arm types are a diagnostic, not a silent `unit`.** When the arms yield *different*
non-unit types and no `&T` context unifies them, the match is a type error reported at the
itself — not a silent fallback to `unit` that surfaces later as a confusing error at the match
use site. The only way distinct arm types are legal is when a common expectation (a `&T`
context, §above) reaches each arm and boxes them to meet.

## 10. Refcounts survive destructuring

Pattern matching obeys the memory model with no special rule, and the obligations are the ones
`03` already implies — recorded here because they are the subtle part of the implementation:

- **Binding a `&T` payload out of an enum retains it.** `Full(p) -> p` hands the bound reference
  past the frame of the enum it came from, so the payload is retained on bind and released once
  when the binding dies — the extracted reference outlives the enum, and the count is exact.
- **A binding under a failed guard is released exactly once.** When `Full(p) if p.x > 100`
  fails and control falls through, the `p` bound for the guard is released before the next arm
  is tried, with no double-free and no leak.

These are not new mechanisms; they are ARC's retain-on-alias and release-at-end-of-scope applied
to the temporary a pattern binding introduces. They are called out because they are exactly
where a hand-written tagged-union in C leaks or double-frees, and where the language earns its
safety claim.

## 11. Matching through a reference

Selection auto-dereferences one level (`03`), but `match` does not: matching a `&Enum` or
`*Enum` against its variants is written **`match *e`**, the same explicit one-level dereference
Go asks for on a type switch. This keeps "am I matching the reference or the thing" a visible
question, and it is the one place a reference to an enum needs the `*`. How a payload binding is
*typed* when matching through a reference — whether `Some(x)` on a `&Option[T]` binds `x` by
value (a copy) or as a `&`-projection into the still-living enum — is not yet pinned (§ Open c).

## 12. `is` — one pattern where a condition is wanted

`match` asks a value to choose between several shapes. Very often a program cares about **one**
shape and has nothing to say about the rest, and §8's rules then make it pay for the arms it did
not want: an enum match is exhaustive-checked in statement position too, so a one-arm match on an
`Option` is *forced* to write a do-nothing catch-all. That is not a style preference a programmer
can decline — it is dead text the language mandates at every such site.

```
if o is Some(n) then
    print(n)
```

**`expr is Pat` tests a pattern and yields a `bool`**, binding whatever the pattern names.
**`expr is not Pat`** is its negation, which is the early-exit guard: `if o is not Some(_) then
return`.

**The right side is an arm's left side**, entire — a literal, a range, a wildcard, a variant, a
struct, a tuple, nested to any depth, and `|`-alternatives (`if s is Idle | Done then …`), which are
held to §6's rule that alternatives may not bind. There is nothing for the `|` to be ambiguous with,
a pattern having no bitwise operator, so the same spelling means the same thing in both places and
neither has to be learned twice.

### Where it may be written, and why the answer is so narrow

**An `is` is a term of an `if`'s or a `while`'s condition and is legal nowhere else.** Not under
`||`, not under `!`, not on the right of an `=`, not as an argument, not in a guard.

The restriction is about the **binding**, not about the boolean. A `bool` may go anywhere; a name
may not. Everywhere else in sysl a name is introduced by a declaration whose scope the reader can
see by looking at the indentation. A binding made by an expression has no such shape, and the
question "on which paths does this name hold something?" then has to be answered by a rule about
control flow rather than by looking. Confining `is` to condition position is what keeps the answer
to one sentence:

> **A binding is live from its own `is` rightward through the rest of the condition, and through
> the branch that condition guards.**

`||` is excluded by that sentence rather than by a separate rule: there is no path through
`a is P(x) || b` on which `x` is known to have been bound, so there is nothing for the sentence to
say. `!` inverts the one edge that would have made the binding. Both are refused with those words
rather than with a parse error.

### Chaining

**Terms chain with `&&`, and only with `&&`.**

```
if lookup(id) is Some(row) && row.active && row.age is 18..65 then
    admit(row)
```

Chaining is not a convenience — without it the form covers only the *unguarded* sliver. §7 already
gives a match arm a guard, so the moment a condition appears an unchainable `is` evaporates and the
reader is back at `match`, writing the three lines the `is` was there to avoid.

`elif` is on the far side of the sentence: it belongs to the `else`, so an `elif`'s condition and
body cannot read what the `if`'s test bound. So does a `while`'s `else`, which runs on the round
that ended the loop — the one round on which nothing was bound.

A `while`'s bindings are **per-iteration**: made by the test, released at the bottom of the body,
made again by the next round's test. That is what makes the drain loop the natural spelling and
what keeps a loop over a million elements holding one round's refcounts rather than a million.

```
while reader.next() is Some(line) do
    print(line)
```

### Two things that are refused

**A pattern under `is not` may not bind.** `x is not Some(n)` would name `n` on the one path where
nothing matched it. `x is not Some(_)` is the form that was wanted, and the diagnostic says so.

**A pattern that cannot fail is refused**, rather than folded away to `true`. Both shapes it takes
are mistakes worth naming: `x is n` is a declaration wearing a test's clothes, and a struct pattern
with no refutable field is a destructuring that belongs inside the branch it was guarding. A variant
pattern stays refutable even on a one-variant enum — the tag is read and compared either way, and an
enum gaining a variant must not change what an existing condition means. Among alternatives it is
*one* of them being irrefutable that decides it, since that one already answers for the rest.

A pattern in a condition is a pattern, so §11 reaches it too: matching a `&Enum` is written
`if *e is Some(n)`, and the same hint says so.

### What this is, and what it is not

The **semantics are Rust's `if let` plus its let-chains**; the **spelling `is` is C#'s**
(`if (x is Point p)`), which is the more readable of the two and reads correctly aloud. Dart's
`if (x case Pat)` and Swift's `if case let` are the same idea spelled worse.

It is **not** a general pattern-test operator, and it is not a second way to write `match`. Where a
program has something to say about more than one shape, `match` says it — with exhaustiveness
checking, which an `is` chain has no way to offer and does not claim.

---

## Open (not yet decided)

Recorded so they are not lost; each needs a decision before the relevant feature hardens.

- **a. Data-enum tag width.** §2 settles the underlying type of a *simple* enum (selectable,
  `int` default, any `iN`/`uN`, checked int→enum conversion). A data enum's tag is an `i32` and
  is not selectable. Now that the payload is a union (§3) the two halves of the layout no longer
  interact, so `enum E: u8` beside a payload region is a decision that can be taken on its own —
  and it is worth taking, since a tag narrower than its payload's alignment is free: an
  `Option[&T]` would be eight bytes rather than sixteen. ~~Also open within simple enums: whether
  explicit discriminants must be distinct~~ — **that half is closed: they must**, and §2 says why.
  It was never a question about what the language should be. The code had already answered it, and
  answered it wrongly: a duplicate was accepted, the losing variant's `match` arm quietly became
  unreachable, and `Image` lowered to a `switch` with a repeated case that the assembler refused —
  so a program was accepted by the compiler and then failed to build, with the reader shown clang's
  complaint about a temporary file. What remains open here is only whether a simple enum is
  **iterable** — a `::Range` over its variants, as a constrained subtype has (`16 §5`).
- **b. Binding mode through a reference.** When matching `*e` on a `&Enum`, does a payload
  binding copy the field or project a `&`/`*` into the live enum? This is the "match ergonomics"
  question Rust answers with default binding modes; sysl needs an explicit rule.
- **c. Bindings in `|`-alternatives.** Currently forbidden. Reconsider allowing `A(x) | B(x)`
  when every alternative binds the same names at the same types (the Rust rule), if a real case
  motivates it.
- ~~**d. `@` bindings.**~~ **Closed: built**, and §6 has the rule. It earned its place on the case
  the question could not see from here — an arm that destructures has the parts and not the value,
  so handing the value on meant testing its shape a second time inside the body. What settled the
  *spelling* was elsewhere: annotations moved to `@` (`attributes.md`), and the two forms turn out
  not to compete, an annotation's being a prefix above a declaration and this one infix inside a
  pattern. Building it also found an older bug — a name bound twice in one pattern silently bound
  the second — which §6 now refuses.
- **e. Unreachable-arm and redundant-pattern lints.** An arm made unreachable by an earlier
  catch-all, or a literal already covered by an earlier arm, is currently accepted silently; a
  lint would catch dead arms.
- **f. `is` in a three-clause `for`'s condition.** §12 confines `is` to an `if` and a `while`, and
  for `||`, `!` and every value position that is a *principle* — there is no branch for the binding
  to be live through. A three-clause `for`'s condition is not one of those: it guards a body exactly
  as a `while`'s does, and a binding there would have exactly the same one-sentence reach. It is
  refused today because the form was built for the two headers that were asked for, and the refusal
  is recorded here rather than left to be discovered so that relaxing it stays a decision somebody
  takes. `for x in seq` is a different question and not this one: its header names an iterator, not
  a condition.

**`sizeof` is settled and has moved.** It was open here because `Layout` is this chapter's, and the
question was whether the *language* could ask what the compiler already measures. It can, over any
type, and the reasoning is in `03 § Reinterpreting storage` — where it belongs, beside the pointer
reinterpretation it exists to serve. The objection recorded here was that answering would fix every
type's layout as part of the language; `15 §1` had already done that, so there was nothing left to
spend.

**Settled while writing this chapter** (were open; now decided above): the selectable
underlying type and checked int→enum conversion for simple enums (§2); `bool` literal patterns
and the restriction of range patterns to numeric-and-`char` (§6); struct patterns in both
positional and named-field form (§6); disagreeing arm types as a diagnostic rather than a
silent `unit` (§9).
