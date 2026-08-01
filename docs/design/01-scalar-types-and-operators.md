# Scalar Types and Operator Precedence

**Status:** settled reference. Consolidates the scalar/primitive type set and the operator
precedence table from the decisions in `00-types-and-expressions.md` — which holds the
*rationale*. This doc is the *tables*; `00` is the *why*.

Sizes are target-dependent only for the pointer-width types (`isize` / `usize`) and the
fat-pointer `string`; every fixed-width type has the same size on every target. Sizes shown
are for aarch64 (LP64, little-endian, 64-bit pointers).

## Scalar and primitive types

### Integers

| Type | Alias | Size (bytes) | Notes |
|---|---|---|---|
| `i8` | — | 1 | signed 8-bit |
| `i16` | `short` | 2 | signed 16-bit |
| `i32` | `int` | 4 | signed 32-bit |
| `i64` | `long` | 8 | signed 64-bit |
| `u8` | `byte` | 1 | unsigned 8-bit |
| `u16` | `ushort` | 2 | unsigned 16-bit |
| `u32` | `uint` | 4 | unsigned 32-bit |
| `u64` | `ulong` | 8 | unsigned 64-bit |
| `iN` / `uN` | — | see below | arbitrary width, any `N ≥ 1` (`i5`, `u12`, `i128`, …); the back end lowers up to 128 |
| `isize` | — | 8 (pointer width) | signed pointer-width; **distinct type**, not an alias |
| `usize` | — | 8 (pointer width) | unsigned pointer-width; **distinct type**, not an alias |

- Arithmetic wraps at the declared width; **no implicit promotion** (`u8 + u8 → u8`).
- `iN` / `uN` is an open family (`00` §5); the fixed-width aliases are common-width names over
  it. On the LP64 ABI each alias matches its C namesake's width; `isize` / `usize` match
  `size_t` / `ptrdiff_t` on *every* target.
- **A width is stored the way the target stores it**, which for a width that is not a whole number of
  bytes is not `⌈N/8⌉`: the alignment rounds up to that of the smallest width the data layout names,
  and the stride rounds up to that alignment. So `u8`→1, `u12`→2 aligned 2, `u20`→4 aligned 4,
  `u96`→16 aligned 16, `u128`→16 aligned 16.
- **Past 64 bits, two operations change where they happen** — division becomes a compiler-rt call,
  and the decimal rendering becomes the language's own, since C has no `printf` length modifier that
  wide. A `%d` on such a value is refused by name rather than narrowed, and `str` renders it. `Hash`
  mixes the two halves rather than truncating to the low one.

### Floating-point (closed IEEE set)

| Type | Alias | Size (bytes) | IEEE format |
|---|---|---|---|
| `f16` | — | 2 | binary16 |
| `f32` | — | 4 | binary32 |
| `f64` | `real` | 8 | binary64 |
| `f128` | — | 16 | binary128 (quad; soft-float on aarch64) — **specified, not built** |

Not an arbitrary-width family — only these four (`00` §6). `bfloat` deliberately excluded.

Three of the four are lowered. `f128` is refused by name, and what it waits on is rendering rather
than arithmetic: every float reaches the screen through `snprintf`, which has no portable quad
conversion — and on Darwin's arm64 `long double` *is* `double`, so `%Lf` would print a wrong number
rather than fail. See `00` §6.

### Other primitives

| Type | Alias | Size (bytes) | Notes |
|---|---|---|---|
| `bool` | — | 1 | `true` / `false`; no int↔bool coercion |
| `char` | — | 4 | Unicode scalar value `0..=0x10FFFF` minus surrogates; no arithmetic (`00` §1) |
| `unit` | — | 0 | sole value `()` |
| `string` | — | 24 | `{ owner, ptr: *u8, len: usize }` — an owning view of validated UTF-8 (`04`) |

`string` is the one primitive that is not self-contained: it carries a reference to the
buffer holding its bytes, so substrings share and ARC keeps them alive. Its size is three
pointer-width words, so 24 bytes on a 64-bit target and 12 on a 32-bit one — never hard-coded.
Because the length is carried, a NUL is an ordinary byte inside a `string`; nothing about the
type is NUL-terminated. `04-strings.md` has the representation, the validity guarantee, and
the operations.

## Literals

`00` §8 has the numeric literal rules (bases, separators, suffixes, defaults) and their
rationale. The tables below complete them.

### Which types a literal can take

An unsuffixed numeric literal has no type of its own — it takes one from where it appears,
and falls back to `int` (integer literal) or `real` (float literal) when nothing says
otherwise. The positions that fix a literal's type are:

| Position | Example | Literal becomes |
|---|---|---|
| declared type of a `var` | `var x: u8 = 42` | `u8` |
| parameter type at a call | `f(42)` where `f(n: i16)` | `i16` |
| field type at construction | `P(42)` where `x: byte` | `byte` |
| the function's return type | `f() -> u16 = 42` | `u16` |
| assignment target's type | `x = 42` | type of `x` |
| the other operand of a binary operator | `n + 1` | type of `n` |
| the scrutinee's type in a pattern | `match b` … `1..9 ->` | type of `b` |

The operand rule is what keeps `n + 1` working for an `n` of any width without the literal
needing a suffix. It applies only to a literal with no suffix of its own: `1u8 << 2` fixes
both operands from the suffix, and a **suffixed literal never adapts**. Two literals with
nothing else to go on both take the default, so `1 << 2` is `int`.

This is not implicit promotion — the literal *is* that type from the start, and a value that
does not fit it is an error asking for a wider one, never a silent wrap or widening.

**`null` follows the same table.** It is the absent raw pointer and has no type of its own, so
it takes the `*T` its position expects — including the operand rule, which is what makes
`walk != null` work without naming the pointee. Where nothing expects a pointer, `null` is an
error asking for the type. There is no null in the safe subset (`03`).

### Escape sequences

The same table serves character and string literals.

| Escape | Meaning |
|---|---|
| `\n` | line feed, U+000A |
| `\t` | tab, U+0009 |
| `\r` | carriage return, U+000D |
| `\0` | NUL, U+0000 |
| `\\` | backslash |
| `\'` | single quote |
| `\"` | double quote |
| `\u{H…}` | one to six hex digits, any Unicode scalar value |

`\u{…}` is braced rather than fixed-width because a codepoint needs up to six hex digits.
Its value must be a Unicode scalar value: at most `0x10FFFF` and not a surrogate. Any other
escape letter is an error — there is no "unknown escapes pass through" rule.

### Character literals

Single-quoted, exactly one Unicode scalar value: `'a'`, `'\n'`, `'é'`, `'\u{1F600}'`. Source
text is decoded before the literal is formed, so a supplementary character written literally
is one `char` however the host stores it, and an unpaired surrogate in the source is an
error. A character literal may not span a line break.

### String literals

Double-quoted, UTF-8, the escape table above: `"héllo ☃"`. The value is a sequence of bytes
with a known length, so an embedded `\0` is an ordinary byte rather than a terminator. A
one-quote literal may not span a line break, and `//` or `/*` inside one is ordinary text.
Concatenation and interpolation are settled (`00` §7), and so is the multi-line form — the
**text block**, `"""` … `"""`, specified in `04`. A literal's bytes are immortal, which is what
lets allocator-free code use one (`04`).

**The case that wanted the multi-line form was data rather than prose**, and that is why it is
specified the way it is. A program with a file embedded in it — `guide/png` carries ten of them —
writes the bytes as hex, and what such a program needs is not line breaks in its value but the
opposite: one constant, written over as many lines as it takes to read. So a text block drops the
indentation it was written at, and a `\` at the end of a line joins that line to the next. Prose
that happens to be long is the easier half of the problem and falls out of the same form.

## Conversions between scalar types

Every conversion is written, with call syntax (`u32(c)`, `byte(0xFF)`, `real(n)`). None is
inferred, and none is a no-op the reader cannot see — the visible-cost rule the memory model
rests on applies to representation changes too.

| From → to | Written | Behaviour |
|---|---|---|
| integer → integer | `u16(n)`, `byte(n)` | truncates or extends; sign-extends only when the *source* is signed |
| integer → float | `real(n)`, `f32(n)` | rounds to nearest; signed and unsigned sources differ |
| float → integer | `int(x)` | truncates toward zero |
| float → float | `f32(x)`, `real(x)` | rounds to nearest |
| `char` → integer | `u32(c)` | total — every `char` is an integer |
| integer → `char` | `char(u)` | **partial** — traps on a value that is not a Unicode scalar value (`00` §1) |
| `char` → `string` | `string(c)` | total — the one character UTF-8-encoded into a fresh string (`04`) |
| `*T` → integer | `usize(p)`, `isize(p)` | total — an address is a number, and `usize` is wide enough to hold one |

The pointer row goes only this way. An address **is** a number of `usize`'s width, so reading one as
that number loses nothing and produces a value that cannot be dereferenced — nothing unsafe has
happened. The inverse is a different matter and is not a conversion: making a pointer out of an
integer, or out of a pointer to something else, is `ptr_cast` in the raw tier (`03 § Reinterpreting
storage`), spelled apart from this table because it is where the language's guarantees stop.

Everything else is rejected: there is no conversion to or from `bool` (`int(true)` is an error, and so
is `bool(0)`), and **no number converts to or from a `string`** — `str(x)` renders one and
`from_utf8` / the `strconv` surface reads one, neither of them spelled as a conversion. The last row
is the one exception and is a narrow one: a `char` is a single Unicode scalar value, so encoding it is
total and needs nothing said about failure, which is what makes it a conversion rather than a parse.
`usize` / `isize` are distinct types, so moving between them and a fixed-width integer of the same
size is still written — on a target where the widths differ it is a real narrowing, which is exactly
why.

**The name in front may be a type parameter**, and then the row above is chosen once the
instantiation says what the parameter is: `T(b)` inside a `[T]` body converts at `u32` and at `f32`
and is refused at a struct, naming the struct. That is not a second rule — it is the one already in
force in the other direction, since `u8(x)` where `x` is a `T` has always been settled at the
instantiation rather than at the definition. What the form means is whatever writing the instantiated
type's name there would have meant — so a parameter that turns out to be a **constrained subtype**
takes that subtype's checked cast (`16`) and one that turns out to be a **simple enum** takes its
checked cast from an integer (`09`), rather than this table's rows.

## Operator precedence

Lowest precedence (binds loosest) at the top; highest (binds tightest) at the bottom. The set
is **closed** — no user-defined operators (`00` §9). Built-in operators are overloadable for
user types via traits, but no new symbols are introduced.

| Prec | Operators | Role | Assoc |
|---|---|---|---|
| 1 | `=` `+=` `-=` `*=` `/=` `%=` `&=` `\|=` `^=` `<<=` `>>=` | assignment (expression) | right |
| 2 | `\|\|` | logical or | left |
| 3 | `&&` | logical and | left |
| 4 | `==` `!=` `<` `>` `<=` `>=` | comparison | chained |
| 5 | `..` `..<` | range (inclusive / half-open) | non-assoc |
| 6 | `\|` | bitwise or | left |
| 7 | `^` | bitwise xor | left |
| 8 | `&` | bitwise and | left |
| 9 | `+` `-` | add / subtract | left |
| 10 | `*` `/` `%` `<<` `>>` | multiply / divide / remainder / shift | left |
| 11 | `-` `!` `~` `*` `&` `++` `--` | prefix unary — negate, not, complement, deref, addr-of, pre-inc/dec | right |
| 12 | `[]` `.` `()` `?` `++` `--` | postfix — index, member, call, try, post-inc/dec | left |

Key points:

- **Two corrections to C's precedence** — the cases C is now widely held to have gotten wrong:
  - **Bitwise binds tighter than comparison** (levels 6–8 vs 4), so `x & mask == 0` means
    `(x & mask) == 0`. This is the universally-acknowledged C precedence bug.
  - **Shift binds like multiplication** (level 10, alongside `* / %`), not looser than
    addition as in C. A shift *is* multiply/divide by a power of two, so it groups with
    `*` / `/`. This makes the common systems-code readings parenthesis-free:
    `base + index << shift` = `base + (index << shift)`, and `a << 8 + b` = `(a << 8) + b`
    — both of which C (and Rust and Zig) force you to parenthesize. This follows Go, which
    made exactly this fix; it is a deliberate divergence from Rust/Zig toward Go.

  Every other level matches C, so C muscle memory stays valid.
- **Ranges** (level 5): `a..b` inclusive (closed), `a..<b` half-open (exclusive end),
  one-sided forms (`a..`, `..b`, `..<b`), non-associative. **Precedence and semantics follow
  Swift** — below arithmetic (so `0..<n+1` = `0..<(n+1)`) and above comparison. The
  **spelling deviates from Swift** (`...`) toward the Kotlin convention `..` / `..<` — a
  merit-based deviation (principles §2): the two forms are visually parallel and read as
  "through" / "up to, less than." (`..` is exclusive in Rust but inclusive here, as in
  Kotlin.) Lexes unambiguously: a float literal requires digits after the `.`, so `1..2`
  tokenizes as `1 .. 2`.
- **Both operands of a binary operator have the same type.** There is no implicit promotion,
  so a mixed-width expression is an error asking for a conversion. This includes the **shift
  amount**: `x << 2` takes its `2` from `x`'s type by the literal rule above, and shifting by
  a value of a different type is written `x << u8(k)`.
- **Arithmetic is defined on the numeric types only.** `char` has equality and ordering and
  no arithmetic at all (`00` §1); `bool` has equality but no ordering and no arithmetic. Unary
  `-` needs a type that has a sign, so it is defined on the signed integers and the floats —
  negating an unsigned value is written as the subtraction it actually is. Unary `~` is defined
  on every integer type, signed or not.
- **Equality reaches further than ordering.** `==` and `!=` are defined wherever `<` is, and
  additionally on `bool` and on the two pointer-shaped modes `*T` and `&T`, which compare by
  address. Ordering on an address is not defined — a bare address has no meaningful one.
- **The one arithmetic two pointers have is `-`.** `p - q` between two `*T`s of the same pointee is
  an `isize` counting the **elements** between them, C's `ptrdiff_t` and the inverse of `&p[n]`
  (`03`). It is the only operator in the table whose result is neither operand's type. Nothing else
  is defined: `p + q` names no address, `p - n` is `&p[n]`'s job, and a counted `&T` has no
  arithmetic at all.
- **A float comparison is IEEE 754's, `NaN` and all.** A `NaN` is equal to nothing, itself included,
  so `==`, `<`, `>`, `<=` and `>=` are all false at one — and `!=` is **true**, because IEEE makes it
  the negation of `==` rather than a sixth ordered comparison. Exactly one of the six answers true,
  which is the thing to check a lowering against: `!=` is `fcmp une` where the other five are the
  ordered predicates. This is also why a float is not hashable (`14 §5`): a reflexivity a table
  assumes is one `NaN` breaks.
- **Chained comparisons** (level 4): `a < b < c` means `a < b && b < c`, short-circuiting;
  comparisons do not associate as plain left/right. The `&&` there is about *when* the later
  comparisons happen, not a rewrite — a middle operand is **evaluated once** and compared twice, so
  `1 < f() < 10` calls `f` once. Short-circuiting is what decides whether a later operand is
  evaluated at all: `9 < f() < g()` never calls `g`.
- **Assignment is an expression** (`00` §2): lowest precedence, right-associative
  (`a = b = c` = `a = (b = c)`).
- `*` and `&` appear both as **prefix unary** (deref / addr-of, level 11) and as **binary**
  (multiply level 10 / bitwise-and level 8); position disambiguates. Their operands and results
  are in `03`: `&` takes a place and yields a `*T`, `*` reads through a `*T` or a `&T` and is
  itself a place.
- **Postfix binds tighter than prefix** (12 vs 11), which gives the intended C idioms:
  `*p++` = `*(p++)` (deref the post-incremented pointer), `-a.b` = `-(a.b)`.
- **Casts/conversions are call-syntax** (`u32(c)`, `byte(0xFF)`), so they parse as postfix
  calls (level 12), not a distinct operator.

### Firm vs provisional

- **Firm:** level 1 (assignment) and levels 2–12 (logical / comparison / range / bitwise /
  arithmetic-with-shift / unary / postfix). The shift-at-multiplicative-level placement (fix
  of C's shift-vs-additive ordering) and the ranges (level 5) are both firm.
- **Provisional placement:** only try (`?`, level 12) — placed sensibly but not separately
  ratified; revisit when the error-propagation grammar is specified.
