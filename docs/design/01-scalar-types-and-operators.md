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
| `iN` / `uN` | — | ⌈N/8⌉ min | arbitrary width, any `N ≥ 1` (`i5`, `u12`, `i128`, …); standalone odd-width storage/alignment is an open item |
| `isize` | — | 8 (pointer width) | signed pointer-width; **distinct type**, not an alias |
| `usize` | — | 8 (pointer width) | unsigned pointer-width; **distinct type**, not an alias |

- Arithmetic wraps at the declared width; **no implicit promotion** (`u8 + u8 → u8`).
- `iN` / `uN` is an open family (`00` §5); the fixed-width aliases are common-width names over
  it. On the LP64 ABI each alias matches its C namesake's width; `isize` / `usize` match
  `size_t` / `ptrdiff_t` on *every* target.

### Floating-point (closed IEEE set)

| Type | Alias | Size (bytes) | IEEE format |
|---|---|---|---|
| `f16` | — | 2 | binary16 |
| `f32` | — | 4 | binary32 |
| `f64` | `real` | 8 | binary64 |
| `f128` | — | 16 | binary128 (quad; soft-float on aarch64) |

Not an arbitrary-width family — only these four (`00` §6). `bfloat` deliberately excluded.

### Other primitives

| Type | Alias | Size (bytes) | Notes |
|---|---|---|---|
| `bool` | — | 1 | `true` / `false`; no int↔bool coercion |
| `char` | — | 4 | Unicode scalar value `0..=0x10FFFF` minus surrogates; no arithmetic (`00` §1) |
| `unit` | — | 0 | sole value `()` |
| `string` | — | 16 | fat pointer `{ ptr: *u8, len: usize }` — UTF-8 bytes |

`string`'s `len` is `usize`, so the fat pointer is 16 bytes on aarch64 and 8 bytes on a
32-bit target — never hard-coded to 64-bit.

## Operator precedence

Lowest precedence (binds loosest) at the top; highest (binds tightest) at the bottom. The set
is **closed** — no user-defined operators (`00` §9). Built-in operators are overloadable for
user types via traits, but no new symbols are introduced.

| Prec | Operators | Role | Assoc |
|---|---|---|---|
| 1 | `=` `+=` `-=` `*=` `/=` `%=` `&=` `\|=` `^=` `<<=` `>>=` | assignment (expression) | right |
| 2 | `..` `..<` | range | non-assoc |
| 3 | `\|\|` | logical or | left |
| 4 | `&&` | logical and | left |
| 5 | `==` `!=` `<` `>` `<=` `>=` | comparison | chained |
| 6 | `\|` | bitwise or | left |
| 7 | `^` | bitwise xor | left |
| 8 | `&` | bitwise and | left |
| 9 | `<<` `>>` | shift | left |
| 10 | `+` `-` | add / subtract | left |
| 11 | `*` `/` `%` | multiply / divide / remainder | left |
| 12 | `-` `!` `~` `*` `&` `++` `--` | prefix unary — negate, not, complement, deref, addr-of, pre-inc/dec | right |
| 13 | `[]` `.` `()` `?` `++` `--` | postfix — index, member, call, try, post-inc/dec | left |

Key points:

- **Bitwise binds tighter than comparison** (levels 6–8 vs 5) — the corrected-C fix, so
  `x & mask == 0` means `(x & mask) == 0`.
- **Chained comparisons** (level 5): `a < b < c` means `a < b && b < c`, short-circuiting;
  comparisons do not associate as plain left/right.
- **Assignment is an expression** (`00` §2): lowest precedence, right-associative
  (`a = b = c` = `a = (b = c)`).
- `*` and `&` appear both as **prefix unary** (deref / addr-of, level 12) and as **binary**
  (multiply level 11 / bitwise-and level 8); position disambiguates.
- **Postfix binds tighter than prefix** (13 vs 12), which gives the intended C idioms:
  `*p++` = `*(p++)` (deref the post-incremented pointer), `-a.b` = `-(a.b)`.
- **Casts/conversions are call-syntax** (`u32(c)`, `byte(0xFF)`), so they parse as postfix
  calls (level 13), not a distinct operator.
- **Shift binds looser than additive** (level 9 vs 10) — kept C-consistent, so
  `1 << 2 + 3` = `1 << 5` = 32, not `(1 << 2) + 3`. This is the one mildly-surprising C
  precedence left unchanged: sysl fixes only the genuinely bug-causing case (bitwise vs
  comparison) and leaves the rest matching C, so C muscle memory stays valid. Parenthesize
  when mixing shift with `+`/`-`.

### Firm vs provisional

- **Firm:** level 1 (assignment) and levels 3–13 (the logical / comparison / bitwise / shift /
  arithmetic / unary / postfix core) — all follow directly from decided items.
- **Provisional placement:** the range operators (`..` / `..<`, level 2) and try (`?`, level
  13) are placed sensibly but their exact precedence has not been separately ratified —
  revisit when the range and error-propagation grammar is specified.
