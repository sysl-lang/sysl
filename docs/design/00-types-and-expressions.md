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

## Target scope (governs every decision in this document)

aarch64 is the **main target and the first one wired up**, but sysl is designed to be **fully
multi-target** — a serious systems language for the desktop host *and* the microcontrollers
real projects use (the intended audio project targets an audio-class STM32, a Cortex-M part).

- **Multi-target and single-backend are not in tension.** There is exactly one code
  generator: sysl emits LLVM IR, and a target is just a different LLVM *triple* + data layout
  + calling convention + runtime — aarch64, ARM Cortex-M (`thumbv6m`/`thumbv7em`/`thumbv8m`,
  for STM32 and Pico), `riscv32` (the RP2350's RISC-V cores), `x86_64`, `wasm32`. Adding a
  target extends a table; it does not add a backend. This is the crucial distinction: the old
  sysl's cost came from multiple *backends* — a tree-walking interpreter, a hand-rolled TRISC
  codegen, an SVM bytecode emitter — that each re-implemented the language's semantics and
  drifted. One LLVM backend over many triples carries none of that tax. Multi-target is the
  *reward* for choosing LLVM, not a cost.
- **Develop fully against aarch64 first; branch out only once the design is wart-free.** Two
  different things hide in "single target first," and only one belongs at day one:
  - **Portable language *semantics* — required from day one, and cheap.** A width or
    endianness assumption baked into *language-observable* behavior becomes an unremovable
    wart, because programs would come to depend on it. This is why `int` = i32 (not i64),
    `.len`/`sizeof` = `usize`, `isize`/`usize` are distinct, and endianness is not assumed.
  - **Multi-target *machinery* (a `Target` descriptor threaded through codegen) — deliberately
    deferred.** A good target abstraction is designed against a real *second* target, not
    speculatively against one; building it now would risk the wrong seams. RISC-V (`riscv32`,
    rising fast in embedded — the RP2350 ships RISC-V cores) is the natural early second
    target, and different enough from both aarch64 and ARM to flush out the real seams.

  The line: **the first implementation may assume aarch64; the language spec may not.**
  Implementation shortcuts (codegen emitting 64-bit pointer math directly, hardcoded backend
  layout) are fine and retrofittable — they are not observable. Only the spec must stay
  width/endian-neutral.
- **No decision may assume a specific pointer width, size width, or endianness.** Pointer and
  size/index widths use `isize`/`usize` (§7); fat-pointer length fields, `sizeof`, and `.len`
  are `usize`-width; multi-byte memory access routes byte order through the target rather than
  assuming little-endian (every current intended target is little-endian, but the design does
  not hard-code it).
- **FPU support is per-chip, so float defaults are guidance, not law.** RP2040 (Cortex-M0+)
  has no FPU — even `f32` is soft-float; Cortex-M4F is single-precision only; but audio-class
  STM32 parts (Cortex-M7) have a hardware FPU, double-precision included, so `real` (f64) can
  be hardware there. `real` stays the host default; the embedded float choice follows the
  chip's FPU, with fixed-point attractive for DSP throughput regardless (deferred note).

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

- Single-quoted, exactly one scalar value: `'a'`, `'\n'`, `'\t'`, `'\\'`, `'\''`. The
  complete escape table is in `01`.
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
(`long` is exactly `i64`, always, on every target). On the aarch64 LP64 ABI every kept
integer alias also matches its C namesake's width (`byte`=1, `short`=2, `int`=4, `long`=8),
which keeps them safe in `extern` and struct-layout code on the host.

**This C-match is LP64-specific, not a universal guarantee.** On a 32-bit ILP32 target
(Cortex-M) C's `long` is 32-bit, so sysl's `long` = `i64` would *diverge* from C `long`
there — sysl fixes each alias width by definition regardless of target, which is the
anti-ambiguity feature, not an ABI promise. **Precise FFI should therefore use the
explicit-width names** (`i32`/`i64`, …): those match C's `stdint` types (`int32_t`,
`int64_t`) on *every* target. `i8` has no alias (there is no settled C-style name for a
signed byte worth adopting). Floating-point keeps a single alias — `real` = `f64`; see §6.

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
types for sizes, counts, indices, and pointer differences. They are **distinct types whose
width is the target's pointer width**: 64-bit on aarch64, 32-bit on the intended Cortex-M
targets. They are **not** aliases for `i64`/`u64`.

This is required by the target scope. Aliasing `usize` = `u64` would be safe only if 64-bit
were the sole target forever; but 32-bit ARM embedded is an explicit intended use, and an
alias would silently bake 64-bit sizes into every index and pointer difference — the exact
bug that breaks on a Cortex-M. Distinctness is the correct discipline: it keeps size/index
code target-agnostic, and the cast friction that makes distinct `usize` annoying in desktop
Rust is minimal on embedded, where `usize` dominates and `u64` is rarely mixed in.

- **No implicit conversion** to/from the fixed-width integers — an explicit cast is required
  (`u64(n)`, `usize(k)`), consistent with the no-implicit-promotion rule. On aarch64 that
  cast is representationally a no-op; on a 32-bit target it is a genuine narrowing/widening,
  which is exactly why it must be written.
- They match C's `size_t` and `ptrdiff_t`/`ssize_t` **on every target** (both are
  pointer-width by definition), so they stay FFI-safe across the 64-bit host and the 32-bit
  MCUs — unlike a fixed `i64` alias, which would match `size_t` only on LP64.
- `sizeof` yields `usize`, and `.len` on a slice or string is `usize`.

## 8. Integer and numeric literals

**Bases.** Decimal (`42`), hex (`0xFF`), binary (`0b1010`), octal (`0o17`). Prefixes are
lowercase; hex digits are case-insensitive (`0xff` = `0xFF`). There is **no C-style
leading-zero octal** — `010` is decimal 10, not 8. Octal is always `0o`.

**Digit separators.** A single `_` may appear between digits (and immediately after a base
prefix): `1_000_000`, `0xFF_FF`, `0b1010_0101`. It may not lead or trail the digit run.

**Type suffixes.** A canonical primitive type name may be appended with no space: the
systematic `iN` / `uN` / `fN` forms plus the two pointer-width primitives `usize` / `isize`
— `42u8`, `7i5`, `100u12`, `0xFFu16`, `3.0f32`, `10usize`. The suffix is restricted to these
canonical primitives so the lexer stays unambiguous; the friendly *aliases* (`int`, `byte`,
`real`, `long`, …) are **not** valid suffixes — give a literal an alias type via a type
context or a cast (`byte(0xFF)`).

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

## 9. Operators are a fixed set — no custom operator symbols

sysl has a **fixed, closed** set of operators (the precedence table). Users **cannot define
new operator symbols** — there is no Nim/Swift/Scala-style custom-operator facility (`|>`,
`<~>`, `>>>`, …). The built-in operators **are** overloadable for user types via trait
`impl`s (Rust-style — `+`, `-`, `*`, `[]`, the comparisons, …), but the *token set itself* is
closed.

**Why.** This is the bare-metal consensus. Rust, C++, D, and Ada allow overloading only of
the fixed set; Zig and C allow no operator overloading at all; **none** permit new operator
tokens. Custom operators appear only in outliers with different priorities (Nim, Swift).
Low-level code is read while reasoning about hardware, and a mystery operator that is secretly
a user function fights that — the same "visible cost" value the memory model rests on. A
closed set is also more teachable and yields sharper error messages and simpler tooling.

**Consequence for the front end.** Because the operator set is finite and known, operators
tokenize by **longest-match against a fixed list** — no greedy operator "muncher", no
context-sensitive stop rules, no separate parser-vocabulary registration. The old lexer's
operator-munching complexity (and its double-registration) existed *solely* to support custom
operators; with those gone, operator lexing is trivial, and even a library lexer stays clean.

---

## 10. Control flow is expression-oriented — `if`, `match`, and loops yield values

`if`, `match`, `while`, and `for` are **expressions**, not statements. Each yields a value, so
it can initialize a binding, be a function's body, or feed a branch of another:

```
var label = if n % 2 == 0 then "even" else "odd"
fee(t: Tier) -> int = match t
    Bronze -> 0
    Silver -> 10
    Gold   -> 25
```

In statement position the value is simply unused, so the same forms read as ordinary control
flow. A branch or arm yields its block's **trailing expression**; a branch that only performs
effects yields `unit`. An `if` used for a value needs an `else` (a missing one leaves the
open branch at `unit`); a `match` used for a value must be exhaustive. A branch that does not
finish at all is the one alternative that constrains nothing — see `never` (§11).

**Loops carry a value out through `break`.** `break expr` leaves the nearest loop and makes
`expr` the loop's value; `continue` skips to the next iteration. An optional **`else` block**
(after the body, as in Python) runs when the loop finishes *normally* — the condition turned
false, or the range ran out, with no `break` — and its trailing expression is the loop's value
on that path:

```
var found = for x in xs
    if pred(x) then break x
else -1                        // the value when nothing matched
```

With no `else`, normal completion yields `unit`, so a value-carrying `break` needs an `else` to
supply the matching value when the loop finishes on its own; every `break` value and the `else`
value share one type, which becomes the loop's. A bare `break` with no `else` is the ordinary
statement loop, of type `unit`.

**A loop may be labeled, so `break` and `continue` can reach an outer one.** A `'label` written
before the loop keyword names that loop, and `break 'label` / `continue 'label` then act on it
rather than on the nearest enclosing loop — the one way to leave or restart an outer loop from
inside a nested one:

```
'outer for i in 0..<rows
    for j in 0..<cols
        if grid[i][j] == target then break 'outer
```

A labeled `break` carries a value exactly as a bare one does: `break 'scan x` makes `x` the value
of the loop named `scan`, meeting that loop's other breaks and its `else` at one type. `continue`
takes a label but never a value.

```
var hit = 'scan for i in 0..<n
    if a[i] == found then break 'scan i
else -1
```

The sigil is a leading apostrophe, as in Rust, and deliberately not the `:`-suffix label some
languages use: a bare `break outer` would be ambiguous with `break expr` carrying a value named
`outer`, and the apostrophe keeps a label and a value textually distinct. It does not collide with
a character literal — `'a'` closes its quote and is a character, while `'a` does not and is a
label. A label is in scope only inside its own loop's body; naming a loop that does not enclose the
`break`/`continue`, or reusing a label already in scope, is an error rather than a silent miss.

**Why.** An expression-oriented core removes the statement/expression split that forces a
temporary-and-reassign dance in C (`int label; if (…) label = …; else label = …;`). It is the
Rust/Scala/Kotlin consensus, and it makes the last-expression-is-the-value rule uniform across
functions, branches, and loops. Value-carrying `break` in particular turns the most common
reason to leave a loop early — *search for the first element that satisfies a predicate* — into
a single expression whose type states, through the mandatory `else`, what happens when nothing
is found. That is the same "make the absent case impossible to ignore" discipline that gives
references no null and errors a `Result`: the loop cannot silently fall through to an undefined
value. Because the value flows through the branches (or a loop's `break`s and `else`), a `&T`
context reaches each one on its own — a value branch and an already-`&T` branch meet at `&T`
(see `03-memory-model.md`), with no aggregate coercion.

## 11. `never` — the type of an expression that does not finish

`never` is the **bottom type**: the type of an expression that transfers control away instead of
producing a value. It is what a call to something declared not to return has, and it exists for
one reason — so that a branch which aborts can sit beside one that yields:

```
unwrap(self) -> T = match self
    Some(v) -> v
    None ->
        print("panic: unwrap of a None value")
        exit(1)                                // this arm's type is never
```

**The whole of its behavior is one rule: a `never` stands where any type was asked for.** Control
does not reach the place the value would have been used, so there is nothing to be wrong about.
That makes the `match` above type `T` rather than a conflict between `T` and something else, and it
is what lets a guard clause be an `if` with no `else`:

```
check(n: int) -> int
    if n < 0 then exit(1)                      // the open branch yields never, not unit
    n * 2
```

The rule runs in **one direction only**. A `never` may stand for a `T`; a `T` may never stand for a
`never`. So a function declared `-> never` whose body could return is an error — the declaration is
a promise the body has to keep — while `f(exit(1))` and `return exit(1)` are accepted as the dead
code they are.

**A block that ends in a jump has type `never` too.** `return`, `break`, and `continue` are not
expressions (`12 §3`), so a block ending in one has no trailing expression to be its value — and it
does not fall out the bottom either. Its type is therefore `never` rather than `unit`, which is what
makes the branch that leaves usable where a value is expected:

```
halve(n: int) -> int
    var h = if n % 2 == 0 then n / 2 else return -1     // the else branch is never
    h * 10
```

The jump itself is checked exactly as it was: its value against the enclosing function's result,
its label against the loops in scope. Only the *type of the block around it* is new.

**There are no values of `never`, so it cannot be a value's type.** It may be written in exactly
one position: a **result type** — a function's, a member's, or an `extern`'s. `var x: never`, a
field of type `never`, an element type, `Option[never]`, a parameter — all are errors, and so is
binding a diverging expression to a name (`var x = exit(1)`), printing one, or putting one in an
array. Each of those needs a value to *arrive*, and none ever does.

This is Rust's `!` under a name that reads as prose, and it is deliberately **not** the analyzer's
internal "type I could not work out": that one exists to suppress the noise after a mistake and
never survives a clean compile, while `never` is a real type a correct program declares and reasons
about.

**Divergence is not the same as a trap.** A trap (`11 §6`) is the *runtime* response to a broken
invariant, emitted by the compiler around a check it inserted. `never` is the *static* fact that
control does not come back, and the two meet only in that the usual way to reach one is to call
something that traps or exits.

## Open at the basics level (not yet decided)

Recorded so they are not lost; each still needs a decision before the relevant lexer/parser
work:

- **Arbitrary-width integer details** (§5): storage size and alignment of a standalone
  odd-width value (e.g. does an `i5` variable round up to a byte? to the next power of two?);
  the maximum permitted `N`; whether `i1`/`u1` are allowed and how they relate to `bool`;
  and whether packed structs lay out an `i5` field in exactly 5 bits (the bitfield / hardware
  register payoff).
- **Statement/block grammar:** which keywords open indented blocks (`then` / `do` / `=`), and
  the exact trailing-continuation operator set. The *lexing* mechanics are settled by adopting
  `IndentationLexical` (see `front-end.md`); these remaining pieces are grammar decisions.
- ~~Final scalar-type table and operator-precedence table~~ — **done**, see
  `01-scalar-types-and-operators.md`.
