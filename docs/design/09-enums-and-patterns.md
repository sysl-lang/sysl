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
    match n
        0            -> "zero"
        1 | 2 | 3    -> "small"
        4..10        -> "medium"
        else         -> "large"
```

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
| `else` arm | `else -> …` | anything; the catch-all spelling in tail position |
| Literal | `0`, `'a'`, `"hi"`, `true` | a value equal to the literal (see the type rule below) |
| Range | `3..7`, `0..<10`, `'a'..'z'` | a value in the range, inclusive `..` / exclusive `..<` per `00` |
| Bind | `r`, `other` | anything, and binds the value to the name |
| Variant | `Circle(r)`, `Empty` | the named variant, binding each sub-pattern to a field |
| Nested | `Wrap(Val(v))` | a variant whose payload itself matches a sub-pattern |
| Struct, positional | `Point(a, b)` | a struct, binding every field by position |
| Struct, named | `Point{x, y}`, `Point{x: a}` | a struct, binding fields by name; unlisted fields are unconstrained |

**The bare-name rule (settled).** A bare identifier in pattern position is a **nullary-variant
pattern** when it names a nullary variant of the scrutinee's enum, and a **binding** otherwise.
So in a `match` on `Shape`, `Empty` tests the variant, while `other` (not a variant) binds. A
bare name that happens to be a *data* variant is a diagnostic — `variant 'Circle' carries data
— match it as 'Circle(…)'` — rather than a silent binding, which closes the classic trap where
a misspelled or data-carrying variant silently becomes a catch-all binding. This is the same
resolution Rust and Swift reach; sysl makes the data-variant case a hard error rather than a
lint.

**Alternatives (`|`) may not bind (settled).** `1 | 2 | 3` is one arm matching any of three
literals; but `Some(x) | none-arm` binding `x` is rejected, because the body cannot know which
alternative matched and therefore cannot know a binding's origin. This is stricter than Rust
(which permits `A(x) | B(x)` when every alternative binds the same names at the same types) and
is worth revisiting once there is a motivating case (§ Open d); for now the rule is simple and
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

**Struct patterns — positional *and* named-field (settled).** A `struct` is destructured two
ways, and both are supported:

```
match p
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

- **A `match` on a data enum must cover every variant or carry an unguarded catch-all.** A
  missing variant is a compile error that *names the missing variants* (`missing Circle, Rect
  (add an 'else' arm)`), so adding a variant to an enum turns every non-catch-all match on it
  into a checked to-do list — the central payoff of a closed sum type.
- **A variant pattern discharges its variant only when its sub-patterns are irrefutable.**
  `Some(x)` covers `Some`; `Some(0)` does not, because a `Some` holding a non-zero value slips
  through. Coverage is about which values are guaranteed handled, not merely which tags appear.
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
match itself — not a silent fallback to `unit` that surfaces later as a confusing error at the
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

---

## Open (not yet decided)

Recorded so they are not lost; each needs a decision before the relevant feature hardens.

- **a. Data-enum tag width.** §2 settles the underlying type of a *simple* enum (selectable,
  `int` default, any `iN`/`uN`, checked int→enum conversion). Pinning the *tag* width of a data
  enum — `enum E: u8` selecting the discriminant type beside the payload union — is the same
  idea, but it interacts with payload layout (`03`) and is deferred until data-enum
  representation is specified. Also open within simple enums: whether explicit discriminants
  must be distinct, and whether a simple enum is iterable / carries a `::Range`.
- **b. Binding mode through a reference.** When matching `*e` on a `&Enum`, does a payload
  binding copy the field or project a `&`/`*` into the live enum? This is the "match ergonomics"
  question Rust answers with default binding modes; sysl needs an explicit rule.
- **c. Bindings in `|`-alternatives.** Currently forbidden. Reconsider allowing `A(x) | B(x)`
  when every alternative binds the same names at the same types (the Rust rule), if a real case
  motivates it.
- **d. `@` bindings.** Binding the whole value while also destructuring it (`p @ Circle(r)`) is
  not implemented; decide whether it earns its place.
- **e. Unreachable-arm and redundant-pattern lints.** An arm made unreachable by an earlier
  catch-all, or a literal already covered by an earlier arm, is currently accepted silently; a
  lint would catch dead arms.

**Settled while writing this chapter** (were open; now decided above): the selectable
underlying type and checked int→enum conversion for simple enums (§2); `bool` literal patterns
and the restriction of range patterns to numeric-and-`char` (§6); struct patterns in both
positional and named-field form (§6); disagreeing arm types as a diagnostic rather than a
silent `unit` (§9).
