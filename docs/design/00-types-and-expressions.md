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

- **`byte` = `u8`** — **kept.** A byte is exactly an unsigned 8-bit unit of memory, which is
  precisely `u8`; the name adds no false guarantee and reads naturally in buffer and I/O
  code.

The remaining candidate aliases (`short`/`int`/`long`/`ushort`/`uint`/`ulong`/`float`/
`double`) are still open — see below.

## Open at the basics level (not yet decided)

Recorded so they are not lost; each still needs a decision before the relevant lexer/parser
work:

- **Decide the remaining integer/float type aliases** (`short`/`int`/`long`/`ushort`/`uint`/
  `ulong`/`float`/`double`), each against the §4 test. (`byte` = `u8` is already decided —
  kept.)
- **Add `isize`/`usize`** (pointer-width integers) as core types for indexing and the
  aarch64 ABI.
- **Integer-literal default type and suffix grammar** (`42u8`, `100i64`, hex/binary,
  underscore digit separators).
- **Indentation mechanics:** INDENT/DEDENT tokenization, block openers (`then` / `do` / `=`),
  and line-continuation rules.
- **Final scalar-type table and operator-precedence table** as their own settled specs.
