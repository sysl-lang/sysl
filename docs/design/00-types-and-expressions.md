# Design Decisions: Types and Expressions

**Status:** living. This is the design-first spec the lexer, parser, and analyzer are
tested against. Decisions here are settled unless explicitly reopened; the reasoning is
recorded so they are not re-litigated. Sibling docs will cover the full scalar-type table,
operator precedence, and the indentation/continuation rules once those are settled.

## Guiding principle for this document

**Do not cargo-cult C's bans when sysl has already removed the root cause.** Several C
features are dangerous only because of a *second* C weakness. When sysl has fixed that
underlying weakness, the derived feature is no longer dangerous, and banning it would be
copying C's symptom while ignoring that we cured the disease. Two decisions below follow
directly from this principle.

---

## 1. `char` is a distinct Unicode-scalar-value type

`char` is a first-class built-in type, **not** an alias for `u32`. It represents a single
Unicode scalar value and earns its name by enforcing what the name promises.

- **Valid set:** `0x0 ..= 0x10FFFF` **excluding** the surrogate range `0xD800 ..= 0xDFFF`.
  The valid values therefore form two disjoint intervals. Membership is one cheap test:
  `u <= 0x10FFFF && !(0xD800 <= u <= 0xDFFF)`. This is exactly the set UTF-8 and UTF-16 are
  defined over.
- **Size and representation:** 4 bytes, stored as the scalar value. Layout-compatible with
  `u32` for FFI, but **not type-compatible** — a `char` cannot be passed where a `u32` is
  expected without an explicit cast, and vice versa.
- **Allowed operations:** equality and ordering only — `==`, `!=`, `<`, `<=`, `>`, `>=`.
  Ordering is by scalar value and is safe; it is what makes `'a' <= c <= 'z'` work, which
  composes with chained comparisons.
- **Rejected at type-check:** all arithmetic (`+ - * / %`), bitwise operators, shifts, and
  `++`/`--`. There is no arithmetic on `char`. To compute with a codepoint, cast to `u32`
  first — that cast is the explicit "I am leaving char-land" marker.

### Why `char` is its own type, not a constrained `u32`

A range-constrained integer would still permit arithmetic. `char` forbids arithmetic
entirely, carries its own literal syntax, and is the decode target for `string`. Those
together make it genuinely more than a constrained scalar, so it is a built-in in its own
right rather than something that falls out of a general `within`-constraint.

### Conversions

- **`char` → `u32`** is **total** — every `char` is a valid `u32`. It is written as an
  explicit cast `u32(c)` (honoring the rule that casts are explicit) but can never fail.
- **`u32` → `char`** is **partial**, and this is the only subtle direction. It has two
  spellings, chosen by how trustworthy the value is:
  1. **`char(u)`** — a **checked cast that traps** on an invalid scalar value, in the same
     runtime-safety category as array-bounds and overflow checks (and strippable the same
     way with `--no-contracts`-style removal). This is the fast path for a value already
     known to be valid.
  2. **`char.try(u) -> Option[char]`** *(constructor name provisional)* — a **fallible
     constructor** returning `None` for an invalid scalar value. This is the required path
     for untrusted input (decoding bytes off a wire, parsing). The caller must handle the
     `None`.

### Literals

- Single-quoted, exactly one scalar value: `'a'`, `'\n'`, `'\t'`, `'\\'`, `'\''`.
- Braced hex escape for arbitrary codepoints: `'\u{1F600}'`. Braced (not fixed-width
  `\uXXXX`) because codepoints exceed four hex digits — `0x1F600` needs five.
- A `char` literal has type `char` and does **not** implicitly coerce to `u32`. Otherwise
  `'a' + 1` would smuggle arithmetic back in.

### Relationship to `string`

`string` stays UTF-8, backed by `u8` bytes. Byte-level iteration yields `u8`; *decoding* a
string yields `char`. So `char` is "what a UTF-8 decoder produces" and `u8` is "a raw
byte" — keeping the two distinct is the reason `char` is aliased to neither.

---

## 2. Assignment is an expression

Assignment (`=`, and the compound forms `+=`, `-=`, …) is an **expression** that yields the
assigned value, permitting `a = b = c = 0` and `while (c = next()) != 0`.

**Why this is safe here, though `if (x = 0)` is a classic C bug:** that bug is not caused by
assignment-being-an-expression. It is caused by C additionally letting *any int serve as a
truth value*, so `x = 0` reads as false. Sysl has a distinct `bool` type with no
int-as-condition coercion, so `if x = 0` is already a **type error** (`if` requires `bool`,
the assignment yields `int`). The compiler catches it with no grammar ban needed. Banning
assignment-expressions would only cost the useful capture idioms — Python did exactly that
and had to reintroduce them via the `:=` walrus operator.

---

## 3. Increment/decrement are expressions, with defined evaluation order

`++` and `--` (prefix and postfix) are **expressions**: prefix yields the new value, postfix
yields the old value. This is required for the pointer-walking idioms the language favors —
`*p++` only works if `++` is an expression.

**Why this is safe here, though `i = i++ + ++i` is undefined in C:** C's undefined behavior
comes from leaving *evaluation order unspecified* while allowing unsequenced mutation of the
same object. Sysl fixes the root cause:

- **Evaluation order is strictly left-to-right** for all subexpressions — operands of binary
  operators, function arguments, index expressions. Side effects (`++`, `--`,
  assignment-in-expression, calls) therefore happen in a single, documented order.

With a defined order, `i++ + ++i` has one well-defined meaning; it is merely hard to read,
not undefined. That makes it a **lint** candidate, not a footgun: the analyzer *may* warn on
multiple mutations of the same lvalue within one expression, but the behavior is always
defined.

---

## 4. Type aliases — kept only when the name over-promises nothing

An alias for a built-in scalar is kept **only when the alias name promises nothing the
underlying type does not already deliver.** This is the same test that rejected `char = u32`
in §1 (that name implied codepoint validity `u32` does not enforce). It is a positive test,
not a blanket ban: a name that is simply a clearer spelling of the exact same guarantees is
good ergonomics, not accretion.

The full set of kept aliases:

| Alias | Type | | Alias | Type |
|-------|------|---|-------|------|
| `byte` | `u8` | | `short` | `i16` |
| `ushort` | `u16` | | `int` | `i32` |
| `uint` | `u32` | | `long` | `i64` |
| `ulong` | `u64` | | `real` | `f64` |

Each passes the test: the name is an unambiguous common-case spelling with no promise the
underlying type does not keep. C's "how wide is `long`?" ambiguity — the usual reason to
distrust these names — **does not apply**, because sysl pins each width *by definition*
(`long` is exactly `i64`, always, on every target). A useful invariant falls out: **every
kept integer alias matches its C namesake's width on the aarch64 LP64 ABI** (`byte`=1,
`short`=2, `int`=4, `long`=8), which is what keeps them safe in `extern` and struct-layout
code. `i8` has no alias (there is no settled C-style name for a signed byte worth adopting).
Floating-point keeps a single alias — `real` = `f64` — chosen specifically to preserve that
FFI-safety property; see §6.

## 5. Integer types are an arbitrary-width family (`iN` / `uN`)

`iN` and `uN` are not a fixed set of four sizes — they are an **open family parameterized by
an arbitrary positive bit width `N`**: `i5`, `u3`, `i128`, `u12`, and so on. LLVM supports
integer types of any width (up to `2^23 − 1` bits) natively, so this is a capability of the
target, not something sysl emulates.

**This is the whole reason the `iN`/`uN` spelling exists, and the reason the aliases in §4
are not redundant.** If the integer types were only `{i8, i16, i32, i64}` and their unsigned
counterparts, then carrying *both* `iN` names and C-style aliases would be two names for
every number type with no benefit — pointless duplication. The arbitrary-width family
resolves that: `iN`/`uN` is the **general mechanism** (any width you need, including odd
ones for registers, bitfields, and packed formats), and the aliases (`byte`, `int`, `long`,
…) are **friendly names for the handful of common widths**. Two layers, each earning its
place.

Semantics generalize the existing integer rules with no special cases:

- **Signedness** is a sysl-level distinction (`iN` vs `uN`) that selects signed vs unsigned
  operations; both map to LLVM's width-`N` integer, exactly as the fixed sizes already do.
- **Wrapping** is at the declared width: `i5` arithmetic wraps mod `2^5`, matching "integer
  arithmetic wraps at the declared type width" already in force for `i8`…`i64`.

Several details that arbitrary width raises are not yet settled — see below.

## 6. Floating-point types are a closed IEEE set (`fN`)

Unlike integers, floats are **not** an arbitrary-width family. LLVM offers only a fixed
enumerated set of floating-point types, so `fN` names only the IEEE widths that actually
exist — there is no `f48` or `f96`. sysl exposes the four IEEE binary formats:

| Type | IEEE format | Bits | Alias |
|------|-------------|------|-------|
| `f16` | binary16 | 16 | — |
| `f32` | binary32 | 32 | — |
| `f64` | binary64 | 64 | `real` |
| `f128` | binary128 (quad) | 128 | — |

Brain-float (`bfloat`) is **deliberately excluded** — it is not an IEEE type. It can be
added later if a machine-learning use case calls for it.

**aarch64 caveats.** `f128` is the standard `long double` on the aarch64 ABI and is fully
supported, but there is essentially no hardware quad-precision arithmetic — LLVM lowers
`f128` add/mul/div to **software routines** (compiler-rt soft-float). It is correct but far
slower than `f64` and pulls in a runtime dependency, so it should not be reached for
expecting `f64`-class speed. `f16` sits at the other end, with only partial hardware support
(the FP16 extension).

**Note on the sole `real` alias.** Only the default width, `f64`, gets a friendly alias, and
it is deliberately named `real` — not `float`. `float` means 32-bit to the C / C++ / Rust /
Go / Java / Swift world as an *ABI fact* (`sizeof(float) == 4`), so `float` = `f64` would be
the one alias in the language that *disagrees* with its C namesake's width — breaking the
§4 invariant that every integer alias matches its C twin on the aarch64 LP64 ABI, and doing
so at exactly the FFI/struct-layout boundary where it corrupts silently. `real` sidesteps
this completely: it has **no C namesake to contradict**, and the name is **width-neutral** —
it says "a real number" (floating-point), making no size claim, so it cannot mislead the way
`float` = f64 would. (`double` = f64 would also be safe and unambiguous, but was set aside as
a less-preferred spelling.) The narrower and wider widths carry no alias: reach for `f32`,
`f16`, or `f128` by their systematic names when you specifically want them.

## 7. Pointer-width integers: `isize` / `usize`

`isize` and `usize` are the **pointer-width** signed and unsigned integers — the canonical
types for sizes, counts, indices, and pointer differences. On the sole target (aarch64,
LP64) that width is 64 bits, so they are **aliases**: `isize` = `i64`, `usize` = `u64`.

They are aliases rather than distinct types on purpose. A distinct pointer-width type (as in
Rust) earns its keep only across targets of differing width, and forces frequent casts
between `usize` and `u64` — one of Rust's most-cited frictions. sysl is committed to a single
64-bit target, so that portability is not being paid for now, and avoiding the cast ceremony
is squarely in the "easier than Rust" goal. As aliases they also match C's `size_t` and
`ptrdiff_t`/`ssize_t` on the LP64 ABI, so they are FFI-safe and satisfy the §4 invariant.

Using `u64`/`i64` directly for a size or index is allowed; `usize`/`isize` exist to document
intent and to match the C size/offset names at FFI boundaries.

**Revisit trigger.** If a target of non-64-bit pointer width is ever added, `isize`/`usize`
must be promoted to *distinct target-width types*. Because size/index/offset code will
already spell them `usize`/`isize`, that is a one-line redefinition rather than a sweep —
which is the practical reason to use these names at those call sites even while they are
aliases.

## 8. Integer and numeric literals

**Bases.** Decimal (`42`), hex (`0xFF`), binary (`0b1010`), octal (`0o17`). Prefixes are
lowercase; hex digits are case-insensitive (`0xff` = `0xFF`). There is **no C-style
leading-zero octal** — `010` is decimal 10, not 8. Octal is always `0o`.

**Digit separators.** A single `_` may appear between digits (and immediately after a base
prefix): `1_000_000`, `0xFF_FF`, `0b1010_0101`. It may not lead or trail the digit run.

**Type suffixes.** A canonical `iN` / `uN` / `fN` type name may be appended with no space:
`42u8`, `7i5`, `100u12`, `0xFFu16`, `3.0f32`. The suffix is restricted to the systematic
forms so the lexer stays unambiguous; the aliases (`int`, `byte`, `real`, `usize`, …) are
**not** valid suffixes — give a literal an alias type via a type context or a cast
(`byte(0xFF)`).

**Default type.** An unsuffixed literal with no type context is `int` (i32). If its value
does not fit `int`, that is a **compile error** requesting an explicit suffix — the default
is never magnitude-dependent. (`5_000_000_000` alone is an error; `5_000_000_000i64` is
fine.)

**Type context wins.** An unsuffixed literal adopts the expected type from its context —
assignment target, parameter, etc. — provided the value fits: `var x: u8 = 42` makes `42` a
`u8`. Out of range is a compile error (`var x: u8 = 300`). This is not implicit integer
promotion: the literal simply *is* that type. The no-promotion rule (§Integer Overflow)
governs runtime *values*, not literal typing.

**Signed-minimum literals.** A decimal literal immediately following a unary minus may reach
the signed type's minimum magnitude, so `-128i8` and `-2_147_483_648` are legal even though
`128` and `2147483648` overflow the corresponding positive range. The type-checker treats
`-<literal>` as a unit for the range check.

**Float literals.** A numeric literal containing a `.` or an exponent is a floating-point
literal: `3.14`, `2.5e10`, `1e-9`. Its default type is `real` (f64); suffixes select another
IEEE width (`1.0f32`, `1.5f16`, `3.0f128`).

## Open at the basics level (not yet decided)

Recorded so they are not lost; each still needs a decision before the relevant lexer/parser
work:

- **Arbitrary-width integer details** (§5): storage size and alignment of a standalone
  odd-width value (e.g. does an `i5` variable round up to a byte? to the next power of two?);
  the maximum permitted `N`; whether `i1`/`u1` are allowed and how they relate to `bool`;
  and whether packed structs lay out an `i5` field in exactly 5 bits (the bitfield / hardware
  register payoff).
- **Indentation mechanics:** INDENT/DEDENT tokenization, block openers (`then` / `do` / `=`),
  and line-continuation rules.
- **Final scalar-type table and operator-precedence table** as their own settled specs.
