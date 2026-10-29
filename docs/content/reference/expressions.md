---
title: Expressions and operators
summary: The precedence ladder, every operator's meaning, evaluation order, and the traits an operator dispatches through.
weight: 30
---

Almost everything in sysl is an expression. An assignment written `:=` yields the value it stored,
`++` yields a value, and `if`, `match` and the loops all yield the branch they took. Only a short list of forms
are *not* expressions — a declaration, a multiple assignment, and `defer` — and each is pointed out
where it comes up.

This page is about how expressions are built and what each operator means. Where an expression is a
control-flow form, [statements and control flow](/reference/statements/) has the details of the form
itself.

## Precedence

Loosest at the top, tightest at the bottom. The set is **closed**: there are no user-defined
operator symbols, and no facility to add one.

| Prec | Operators | Role | Associativity |
|---|---|---|---|
| 1 | `=` `+=` `-=` `*=` `/=` `%=` `&=` `\|=` `^=` `<<=` `>>=` `+%=` `-%=` `*%=` `+\|=` `-\|=` `*\|=`, and `:=` `+:=` … `*\|:=` | assignment | right |
| 2 | `\|\|` | logical or | left |
| 3 | `&&` | logical and | left |
| 4 | `is`, `is not` | pattern test | non-associative |
| 5 | `==` `!=` `===` `!==` `<` `>` `<=` `>=` | comparison | **chained** |
| 6 | `..` `..<` | range | non-associative |
| 7 | `\|` | bitwise or | left |
| 8 | `^` | bitwise xor | left |
| 9 | `&` | bitwise and | left |
| 10 | `+` `-`, `+%` `-%`, `+\|` `-\|` | add, subtract; wrapping, saturating | left |
| 11 | `*` `/` `%` `<<` `>>`, `*%`, `*\|` | multiply, divide, remainder, shift; wrapping, saturating | left |
| 12 | `-` `!` `~` `*` `&` `++` `--` | prefix unary | right |
| 13 | `[]` `.` `()` `::` `with` `?` `++` `--` | postfix | left |
| — | `await` | run a task: prefix, taking the postfix chain up to its first `?` | right |

`*` and `&` appear at two levels each — prefix at 12 (dereference, address-of) and binary at 11 and 9
(multiply, bitwise and). Their position tells them apart.

**`await` sits between the two postfix kinds** rather than on a level of its own: it takes the calls,
member selections and indexes after it, so `await d.read(n)` awaits the call, and stops at the first
`?`, which then applies to the awaited value — `await f()?` is `(await f())?`, and `await f() + 1` is
`(await f()) + 1`. [Where `await` binds](/reference/async/#where-await-binds) has the whole table.

**An `if` may be the operand of any operator, binary or prefix, and its last branch runs to the end
of the expression**, as in Rust, Scala and Kotlin. `1 + if c then 2 else 3 * 4` is
`1 + (if c then 2 else (3 * 4))`, and `-if c then 1 else 2` negates the whole `if`. An `elif` chain
and the indented form are operands the same way. Written first, an `if` is the whole expression, as
it always was: `if c then 1 else 2 + 3` adds in the `else`. A loop (`loop`, `while`, `do`, `for`) is
an operand only inside brackets.

```sysl
val s = 5

print(10 + if s < 6 then 1 else 0)
print(1 + if s > 9 then 2 else 3 * 4)
print(-if s > 9 then 1 else 2 * 3)
print((if s > 4 then 1 else 2) + 3, if s > 4 then 1 else 2 + 3)
```

```output
11
13
-6
4 1
```

An operand `if` with no `else` is a `unit`, which the operator refuses like any other:

```sysl
val c = true
val n = 1 + if c then 2
```

```error
'+' needs matching types, got int and unit
```

**A `match` is the operand of any operator, binary or prefix, once it is in parentheses.** Its keyword
goes after the value and binds more loosely than every operator, so written bare it takes the whole
expression before it: `a + k match …` matches on `a + k`
([patterns](/reference/patterns/)). Parenthesised, it is one operand like any other, on either side:
`a + (k match …) * b` multiplies the match's value by `b` and adds `a`, and `-(k match …)` negates
it. The arms may be one line each or blocks, with guards and payload bindings, and a `match` or an
`if` may nest in an arm. Literal arms take a typed operand's type, as a literal does
([which conversions are implicit](#which-conversions-are-implicit)). The closing bracket may stand on a line of its
own, back at the margin the bracket was opened at:

```sysl
val k = 2
val o: Option[int] = Some(7)
val b: u8 = 250

print(1 + (k match
    2 -> 4
    _ -> 0) * 3)
print(-(o match
    Some(v) if v > 5 -> v
    _ -> 0))
print(b + (k match
    2 -> 3
    _ -> 4
))
```

```output
13
-7
253
```

A `match` used as an operand yields a value, so it must be exhaustive and its arms must agree:

```sysl
val k = 2
val n = 1 + (k match
    1 -> 1
    2 -> 2)
```

```error
a 'match' that yields a value must be exhaustive
```

### Two deliberate corrections to C

Every level above matches C except two, and both are widely regarded as mistakes in C.

**Bitwise binds tighter than comparison.** `x & mask == 0` means `(x & mask) == 0`, as it reads. In C
it means `x & (mask == 0)`, the most frequently cited precedence bug in the language.

**Shift binds like multiplication**, at level 11 alongside `* / %`, rather than looser than addition.
A shift *is* a multiply or divide by a power of two, so it belongs with them. Go makes the same
choice; Rust and Zig follow C.

```sysl
var x = 0b1100
var mask = 0b0100

print(x & mask == 4)
print(1 + 2 << 3)
```

```output
true
17
```

Both of those need parentheses in C, Rust and Zig. In systems code this means
`base + index << shift` and `a << 8 + b` group the way they read.

## Operands

**Both operands of a binary operator have the same type, and an operand converts only to the other
operand's type.** Where one operand's type holds every value of the other's — `long` holds every
`int`, `real` every `int` — the narrower one is widened to it, in either order, exactly as `long(a)`
would have done (§ [Which conversions are implicit](#which-conversions-are-implicit)). Nothing is
promoted to a third type: two `int`s add as `int` wherever the sum goes, so `val x: long = a + a`
overflows at `int`'s width before the sum is widened.

```sysl
var a: int = 1
var b: long = 2
var r = 0.5

print(a + b, b - a, a + r)
```

```output
3 1 1.5
```

**Where neither type holds the other, the expression is a diagnostic asking for the conversion.** A
`u32` and an `int` are both 32 bits: neither holds every value of the other, so the program says which
side it means. (Written `var i = 2`, `i` would be a `u32`: a literal binding takes the type of the
first operand it meets — [declarations](/reference/declarations/#a-literal-binding-takes-its-type-from-its-first-use).)

```sysl
var n: u32 = 1
var i: int = 2

print(n + i)
```

```error
'+' needs matching types, got uint and int
```

**A `char` operand never widens.** A character beside a number is far more often a mistake than
arithmetic, so `c + n` says which is meant — `int(c) + n` — even though a `char` widens to an `int`
everywhere a position asks for one (`val k: int = c`). Two `char`s compare with each other as usual.

```sysl
var c = 'a'
var n = 1

print(c + n)
```

```error
'+' needs matching types, got char and int
```

### A shift takes a count

**A shift is the one exception, and its right operand may be any integer type.** `a + b` combines two
values that have to agree on what they are; `x << n` asks for `x` shifted `n` places, and `n`'s width
has nothing to do with `x`'s. C, Rust, Java, Go and Scala read a shift the same way, and it keeps
casts out of exactly the code — a hash, a bitset, a decoder — where several widths are already in
play.

**The result is the shifted value's own type**, never the count's, so a byte shifted by a 64-bit
count is still a byte and wraps as one.

```sysl
var x: u32 = 1
var n: u8 = 5
var wide: u64 = 3
var b: u8 = 0x81

print(x << n, (x << n) >> n)
print(b << wide)
```

```output
32 1
8
```

**So a literal shifted by a typed count takes its type from where the shift stands** — the
position's type, or the other operand's — exactly as a bare literal does. `1 << bit` over an `int`
bit is a `u32` where a `u32` is wanted and a `u64` beside a `u64`, and a literal the position cannot
hold is refused at the literal.

```sysl
var level: u32 = 0
val bit = 3
val mask: u64 = 1 << 40

level |= 1 << bit
print(level, mask | (1 << bit) | (1 << 4))
```

```output
8 1099511627800
```

**Only where nothing around it gives a type does the count's stand in**: `val top = 1 <<
usize(depth)` is a `usize`, and so is `(1 << usize(depth)) - 1` or an `if` with it in one arm and a
literal in the other.

```sysl
val depth: u8 = 8
val x: usize = 40
val top = if depth == 16 then 65536 else 1 << usize(depth)

print((x * 7 + 3) % top, top)
```

```output
27 256
```

The count must still be an **integer**, and the refusal names it as a count rather than as a
mismatched operand.

```sysl
var x: u8 = 1

print(x << 1.5)
```

```error
'<<' shifts by a count, and real is not an integer
```

**A count wider than the value is clamped before it is narrowed**, so the over-shift rule below
holds: 256 places on a byte is a full shift, not the shift by nothing that truncating first would
give.

```sysl
var x: u8 = 1
var n: u32 = 256

print(x << n)
```

```output
0
```

A **vector** is different: its count is lane-wise and is already the same register type, so the two
sides of `v << w` have to agree.

### What has arithmetic

Arithmetic is defined on the numeric types and nowhere else. `char` has equality and ordering and no
arithmetic at all; `bool` has equality, no ordering, and no arithmetic.

Unary `-` needs a type with a sign, so it applies to the signed integers and the floats; negating an
unsigned value is written as the subtraction it actually is. Unary `~` is defined on every integer
type, signed or not.

Integer overflow of a plain `+`, `-`, `*` or unary `-` is the **build mode's**: a release build
wraps at the declared width, which is defined, and a debug build — `sysl run` and `sysl test` — stops
the program with `integer overflow: int + int (main.sysl:4:9)`; see
[types](/reference/types/#arithmetic-wraps) and [debug and
release](/getting-started/cli/#debug-and-release). A compound assignment (`+=`, `*=`) and `++`/`--`
are the same operation and are checked the same way. Integer division by zero traps in every build. Arithmetic written out of literals alone is the exception to both: it is computed
exactly, a value that does not fit its type is refused, and so is a division by a literal zero —
see [arithmetic over literals](/reference/types/#arithmetic-over-literals-is-exact).

### Wrapping and saturating operators

**`+%` `-%` `*%` wrap and `+|` `-|` `*|` saturate, in every build**, whatever a plain `+` does there.
A wrapping operator's result is the two's-complement result at the operands' width; a saturating
operator's is the nearest value the width holds — its maximum or its minimum. A signed saturating
product saturates toward the side of the true product's sign. Each binds as its base operator does
(`+|` with `+`, `*|` with `*`), takes two operands of one type (unlike its base, nothing
[widens](#which-conversions-are-implicit) to match) — and has a compound form (`+%=` … `*|=`) and a
yielding one (`+%:=` … `*|:=`).

```sysl
val b: u8 = 250
val s: i8 = -100

print(b +% 10, b +| 10, b -| 251, b *| 2)
print(s -% 100, s -| 100, s *| 2, s *| -2)

var level: u8 = 200
level +|= 100
print(level)
```

```output
4 255 0 255
56 -128 -128 127
255
```

**A literal beside a typed operand is a value of that operand's type, and one the type does not hold
is refused.** As in `b + 300` over a `u8`, the 300 of `b +| 300` is a `u8` that does not exist:
saturating to 255 would hide the mistake, and widening to `int` first would leave nothing to
saturate. A literal that fits exactly (`b +| 255`) is taken, on either side, and so is one
the compound and yielding forms add to their place; one that does not fit the place is refused the
same way.

```sysl
val b: u8 = 250
print(b +| 300)
```

```error
the literal 300 does not fit byte, the type of the other operand of '+|' — a wrapping or saturating operator takes two values of one type
```

**The two operands are one type, as in Rust, and neither widens to the other.** Where `a + b` over a
`u8` and a `u16` is a `u16`, `a +| b` is refused, and so is a named constant of another type: `const K
= 300` is an `int`, a typed value, so `y +| K` over a `u8` is refused whatever K holds. Only a
bare literal, or arithmetic over literals alone, takes its neighbour's type. The compound and
yielding forms hold the value to the place's type the same way. Convert one side (`u16(a) +| b`).

```sysl
val a: u8 = 250
val b: u16 = 10
print(a +| b)
```

```error
'+|' takes two values of one type, got byte and ushort
```

They are defined on integers of every width, signed and unsigned — `u12`, `u128` and `i256` included,
an inline expansion past the machine's word — and on [fixed-point](/reference/fixed-point/) values of
one format, where `+|` is the saturating `+` every format has and `+%` is the one a `wrapping` format
has, whichever kind the operands are. A constant written with one is folded at the constant's own
type: `const K: u8 = 250 +| 10` is 255. There is no prefix form, and they are not dispatched to a
trait: an operator method is what a type of your own defines for `+`. A float's arithmetic neither
wraps nor saturates, and is refused:

```sysl
val f = 1.5
print(f +| 1.0)
```

```error
'+|' is not defined for real — floating-point arithmetic neither wraps nor saturates, it rounds and overflows to infinity, so write '+'
```

A register of lanes is refused too (`'+|' is not defined for <4>int`); a lane's saturating `+` is a
fixed-point lane's own `+`.

The third answer to an overflow — that there is no result, and the caller must decide — is a method
rather than an operator: `a.checked_add(b)` and its siblings return an `Option`
([types](/reference/types/#asking-whether-it-fit)).

```sysl
val parts: []byte = [100, 100, 100]
var total: byte = 0

for x in parts
    total = total.checked_add(x) match
        Some(t) -> t
        None ->
            print("overflow")
            break

print(total)
```

```output
overflow
200
```

### A multiply feeding an add is fused where the machine fuses

**A float multiply whose result is added or subtracted in the same expression is one fused
multiply-add, rounded once**, wherever the machine has a fused instruction at that width. The
product is never rounded on its own, so the answer is the more accurate one and costs one instruction
instead of two. The forms are the ones C contracts under clang's default `-ffp-contract=on`:
`a * b + c`, `c + a * b`, `a * b - c`, `c - a * b`, a negated product `-(a * b) - c`, any of them in
parentheses, and the compound assignments `s += a * b` and `s -= a * b`. Of two products added, the
left one is fused and the right one is the addend.

Which machines fuse is what the `#if` symbols `fma` (at `f32`) and `fma_dp` (at `f64`) say
([attributes](/reference/attributes/#the-symbols-are-derived-from-the-target-and-the-set-is-closed)):
AArch64 and a hosted RISC-V at both widths, an Arm unit at `f32`, and the Cortex-M7's
double-precision unit at `f64` as well. A vector of `f32` or `f64` lanes follows its lane's width.
Everywhere else — `f16`, `bf16`, `f128`, fixed point, a machine without the unit — the multiply and
the add are two operations, each rounded.

**A product is kept apart by anything written between it and the sum**: a `val` it is bound to, a
call it is passed through (other than an [arithmetic operator](#an-arithmetic-operator-is-read-as-its-body)),
an explicit conversion. That is how a program asks for two roundings, and
[`x.mul_add(a, b)`](/library/math/#mul-add-a-multiply-and-an-add-rounded-once) — or `llvm.fma`
([ffi](/reference/ffi/)) declared by hand — is how it asks for one on every machine:

```sysl
val x = 1.0 + 1.0 / 1073741824.0
val c = -(1.0 + 1.0 / 536870912.0)
val p = x * x

#if fma_dp
val fused = 1.0
#else
val fused = 0.0
#endif

print((x * x + c) * 1152921504606846976.0 == fused, (p + c) * 1152921504606846976.0)
```

```output
true 0
```

`x * x` is `1 + 2^-29 + 2^-60`, and a double cannot hold the `2^-60` beside the one. Fused, `c`
cancels everything else and the `2^-60` is the answer; rounded first, as the `val` rounds it, it is
gone and the answer is zero.

**A value worked out while compiling is the one the machine would have worked out.** A `const`, a
module `val` laid down as data and a `const` function's result are each computed with one rounding
exactly where the target fuses, and with two where it does not, so an answer never depends on
whether the compiler or the program computed it, nor on the optimization level:

```sysl
const X = 1.0 + 1.0 / 1073741824.0
const C = -(1.0 + 1.0 / 536870912.0)
const positive = X * X + C > 0.0

var x = X
var c = C

print(positive == (x * x + c > 0.0))
```

```output
true
```

The fused instruction is asked for exactly, never left to the optimizer to choose site by site, so a
program prints the same bits at `-O0` and at `-O3`, vectorized loops included.

#### An arithmetic operator is read as its body

**A call of an `Add`, `Sub`, `Mul`, `Div` or `Neg` method whose body is one expression is read, for
fusing, as that expression written where the call is.** On a vector type, `a + d * k` is
`V(a.x + d.x * k, a.y + d.y * k)`: each field is a product meeting a sum, and each is fused as the
same sum written on floats would be. A field of a constructor is the constructor's argument, so
nothing is built only to be taken apart. It holds for a generic type's instances, for an operator
another module or package declares, for a unary minus, and for `+=` and `-=` through an operator:

```sysl
struct V
    x: real
    y: real

impl Add for V
    add(self, rhs: V) -> V = V(self.x + rhs.x, self.y + rhs.y)

impl Mul[real] for V
    mul(self, k: real) -> V = V(self.x * k, self.y * k)

val x = 1.0 + 1.0 / 1073741824.0
val c = -(1.0 + 1.0 / 536870912.0)
val p = V(x, x) * x

#if fma_dp
val fused = 1.0
#else
val fused = 0.0
#endif

print((V(c, c) + V(x, x) * x).y * 1152921504606846976.0 == fused, (V(c, c) + p).y * 1152921504606846976.0)
```

```output
true 0
```

**What the call means is unchanged**: each argument is evaluated once, in the order it is written,
however often the body reads it, and an argument that is a call, an element or anything else that
could trap or write is evaluated before the rest. Only the rounding moves, and only where a product
now meets a sum. A `const` and a module `val` read the operator the same way.

**What still keeps a product apart**, through an operator as on floats: a `val` or `var` the
program binds it to, a call of any function or method that is not such an operator, an explicit
conversion, and an operator whose body is more than one expression (a body of statements). A call
through a trait object is a call too, and so is any operator on a machine that does not fuse at the
width. `s, t += …` through an operator is not read this way.

### Shifting by the width or more

**A shift amount at or past the operand's width is defined**, and gives the result of shifting all
the way: zero for `<<` and for an unsigned `>>`, and the **sign** for a signed `>>`, since an
arithmetic right shift fills from the top. Go and Swift use the same rule.

The width is the **operand's own** rather than the machine's, so a `u8` reaches this case at 8,
where the bare instruction would mask the amount to five or six bits and shift by something else
entirely.

```sysl
var u: usize = 11
var neg = -8
var pos = 8
var b: u8 = 0b1011

print(u >> 64, u << 64, u >> 65)
print(neg >> 64, pos >> 64)
print(b >> 8, b << 8, b >> 200)
```

```output
0 0 0
-1 0
0 0 0
```

This follows from a shift being a bit operation in every build mode, rather than arithmetic that
can overflow: `within` is where checking a shift is opted into, so a plain over-width shift has to be *defined* — a trap
would break the contract of a type that promises none. **A `within`-typed
left shift is the exception and does trap**, on its own terms and not the shift operator's; see
[errors](/reference/errors/#the-operations-are-the-base-s-their-overflow-is-not).

The amount is **not masked**, as it is in C, in Java, and by the bare instruction. Masking makes
`x >> 64` equal `x`, which is almost never what a program intends.

A shift of a **literal** by a literal is a constant. A right one is computed exactly, so `1 >> 40`
is `0` at any width. A left one is a bit operation at the type it is read at, so the bits shifted
past the top are dropped — `1 << 31` is an `int`'s minimum — and its amount must be below that
type's width: `1 << 40` is 1099511627776 as a `long`, and has no value as an `int`.

```sysl
val wide: long = 1 << 40
val narrow: int = 1 << 40
```

```error
a constant shifted by 40 places, at or past the 32 bits of int
```

### Equality reaches further than ordering

`==` and `!=` are defined wherever `<` is, and additionally on `bool` and on the two pointer-shaped
modes: a `*T` compares by address, and a `&T` as its referent does where the referent has an `Eq`,
by address where it has none ([memory](/reference/memory/#references-compare-through-their-referent)).
**`===` and `!==`** compare two references or two pointers by address whatever the referent defines,
and bind as `==` does. **Ordering on an address is not defined** — a bare address has no meaningful
one.

### The one arithmetic two pointers have is `-`

`p - q`, between two `*T`s of the same pointee, is an `isize` counting the **elements** between them
— C's `ptrdiff_t`, and the inverse of `&p[n]`. It is the only operator whose result type is neither
operand's.

Nothing else is defined: `p + q` names no address, `p - n` is what `&p[n]` is for, and a counted
`&T` has no arithmetic at all.

## Comparison chains

`a < b < c` is **one comparison node**, not two comparisons and a `bool`. It means `a < b && b < c`,
short-circuiting — and a middle operand is **evaluated once** and compared twice.

```sysl
bump(n: int) -> int
    print("evaluated", n)
    n

print(0 < bump(5) < 10)
print(9 < bump(5) < bump(6))
```

```output
evaluated 5
true
evaluated 5
false
```

The first line calls `bump` **once** although its value is compared twice. The second stops at the
first comparison that fails, so the `bump(6)` on its right never runs at all.

### A vector comparison is not a chain

Comparing two [vectors](/reference/vectors/) yields a mask rather than a `bool`, and a chain of them
is **refused**. The reason is the short-circuiting above: `a < b < c` joins its links with `&&`, and
one lane of a register cannot short-circuit while another does not. Reading the chain as a lane-wise
`&` would give the same spelling a different meaning, so the `&` is written explicitly.

```sysl
val a: <4>int = [1, 2, 3, 4]

f() -> unit
    val m = 1 < a < 4
```

```error
compare two vectors at a time and combine the masks with '&'
```

### Floats compare by IEEE 754, `NaN` and all

A `NaN` is equal to nothing, itself included. So `==`, `<`, `>`, `<=` and `>=` are **all false** at
one — and `!=` is **true**, because IEEE makes it the negation of `==` rather than a sixth ordered
comparison. Exactly one of the six answers true.

```sysl
var zero = 0.0
var nan = zero / zero

print(nan == nan, nan != nan)
print(nan < 1, nan >= 1)
```

```output
false true
false false
```

This is also why a float is not hashable: a table assumes a reflexive equality, and one `NaN` breaks
it.

### A float operation rounds to nearest unless its function says otherwise

An operation's answer is IEEE 754's for round-to-nearest-even, and the exceptions it raises are not
something a program observes: the compiler may work an operation out while compiling, merge two
identical ones into one, or move one past a call. A function whose floating point has to follow the
rounding mode, flush-to-zero and default-NaN controls in force when it runs, and raise its exceptions
where it is written, is marked
[`@strict_fp`](/reference/attributes/#strict-fp-floating-point-that-follows-the-environment-it-runs-in);
[`sysl.fenv`](/library/fenv/) sets that environment and reads the flags.

## Logical operators

`&&` and `||` take `bool` and short-circuit; `!` is prefix on a `bool`. Nothing coerces to `bool`, so
there is no integer-as-condition rule and no `if (p)` idiom — write `p != null`.

## Ranges

`a..b` is inclusive, `a..<b` is half-open. Either end may be omitted: `a..`, `..b`, `..<b`, and a
bare `..` meaning the whole thing.

Ranges are **non-associative** and sit below arithmetic and above comparison, as in Swift, so
`0..<n + 1` is `0..<(n + 1)` and needs no parentheses.

```sysl
var lo = 1

for i in lo..lo + 2
    print(i)
```

```output
1
2
3
```

The spelling is Kotlin's `..` / `..<` rather than Swift's `...`, because the two forms are visually
parallel and read as "through" and "up to, less than". It lexes unambiguously: a float literal needs
digits after the `.`, so `1..2` is three tokens and not two.

**`..=` is not a spelling sysl has**, and is refused by name wherever a range may be written — it is
Rust's inclusive range, and inclusive here is the bare `..`:

```sysl
val a = 1

for i in a..=3
    print(i)
```

```error
'..=' is not a range — inclusive is 'a..b' and exclusive is 'a..<b'
```

**A range with both ends written is a form in four places and a value everywhere else.** In a `for`
header, a slice index, a `match` pattern and a quantifier the compiler reads the two bounds directly
and no range object exists in what it emits, so the most common loop in the language compiles to a
counter and a comparison, with no struct, no `Option` per step and no call. Anywhere else it
builds a `sysl.Range[T]`: three fields, the two bounds and whether the upper one is included.

```sysl
val r = 0..<4

total(r: Range[int]) -> int
    var acc = 0

    for i in r
        acc += i

    acc

print(total(r), total(r), total(0..3))
```

```output
6 6 6
```

A `Range` is walked by [`Iterate`](/library/core/), so a `for` over one is the ordinary `for` over
anything with a cursor rather than a second kind of loop. The cursor moves, but a `for` walks a
**copy**, so `r` above gives the same total both times rather than being used up, and `0..3` gives
the same total because the inclusive form counts one more.

**Its bounds follow the same two rules as a `for` header's** — they must agree, and they must be
integers — so the value and the loop always agree about what a range is. Two bounds of
different types agree where one holds every value of the other, as two operands do: `a..<b` over an
`int` and a `u8` is an `int` range, and over an `int` and a `u32` it is refused. A bound written as a
literal takes its type from the other end, so `0..<3u8` is a `u8` range rather than a disagreement;
only two typed bounds can disagree. A range of literals standing where a
`Range[T]` is wanted takes `T` the same way, so `total(0..<4)` passes a `Range[usize]` to a
`total(r: Range[usize])`, and `val r: Range[u8] = 0..<3` is a `u8` range.

```sysl
f(a: int, b: u32) -> int
    val r = a..<b
    0

print(f(0, 3))
```

```error
a range needs matching bounds
```

A range of literals where a `Range[usize]` is wanted:

```sysl
total(r: Range[usize]) -> usize
    var acc: usize = 0

    for i in r
        acc += i

    acc

print(total(0..<4), total(1..3))
```

```output
6 6
```

**An open end has no value reading.** `..`, `lo..` and `..hi` mean something only in a slice index,
because what an absent bound *is* depends on what is being indexed. They stay index-only and are
refused elsewhere by name.

```sysl
val r = 1..

print(r)
```

```error
a range with an open end is only allowed in a slice index
```

Because a range is a value, it implements [`Sequence`](/library/seq/), so `(0..<n).map(f)` works as
written. Nothing is materialized to answer a question about one, so `(0..<1_000_000).any(p)` costs
the predicate calls and no storage. An array is a `Sequence` too, at every length, so a question
asked of a table needs no `[..]` first: `RUNS.any(p)` on a `val RUNS = [...]`
([arrays](/reference/arrays/#asking-one-a-question)).

## `is` — testing a value against a pattern

`x is Pat` tests a value against a pattern and yields a `bool`; `x is not Pat` negates it. The right
side is a full pattern, alternatives (`|`) included — the same grammar a `match` arm's left side
uses.

```sysl
enum Shape
    Circle(r: real)
    Rect(w: real, h: real)

var s = Rect(2, 3)

if s is Rect(_, _) && s is not Circle(_)
    print("rectangular")
```

```output
rectangular
```

Its level — between `&&` and the comparisons — makes `a is P && b > 0` a conjunction of two terms
rather than an `is` against a conjunction.

**The right side of `is` is a pattern, and the pattern ends at the first token a pattern cannot
hold.** A comparison is not part of a pattern, so `a is P == b` is `(a is P) == b`: the test's `bool`
compared with `b`, as Kotlin and C# read it.

**Under `||` a test is allowed as long as it binds nothing.** A name bound by `is` is live from its
own `is` rightward and through the branch, and under `||` the branch runs when either side held, so
a name bound on one side would hold nothing when only the other side matched. A test that binds
nothing has no such problem:

```sysl
enum Push
    NoRoom
    Pushed(at: int)

full(e: Push, room: int, limit: int) -> bool
    if e is not NoRoom || room == limit then return true

    false

print(full(NoRoom, 3, 3), full(Pushed(1), 0, 3), full(NoRoom, 0, 3))
```

```output
true true false
```

```sysl
var o: Option[int] = Some(1)

if o is Some(n) || false
    print(n)
```

```error
a pattern under '||' cannot bind a name
```

**Outside a condition, an `is` that binds nothing is an ordinary `bool`.** It can be negated, bound,
passed, returned, made a closure's body or a `match` guard, and folded in a `const`:

```sysl
const LIMIT = 12
const SMALL = LIMIT is 0..15

ready(o: Option[int]) -> bool
    return o is Some(_)

val o: Option[int] = None
val empty = o is None
val some = (x: Option[int]) -> x is Some(_)

print(SMALL, ready(Some(1)), !(o is Some(_)), empty, some(o))
```

```output
true true true true false
```

Its level still applies: `!o is None` is `(!o) is None`, so a negation of the test is written
`!(o is None)` — or `o is not None`.

A pattern that binds a name still stands only in a condition, because the name is live through the
branch the condition guards, and outside a condition there is no branch. Take the value apart with
`if` or `match`, or write `_` for the parts you are not naming:

```sysl
val o: Option[int] = Some(1)
val held = o is Some(n)
```

```error
this pattern binds a name, and an 'is' that binds stands only in the condition of an 'if' or a 'while'
```

Both `is` and `not` are **soft** words: neither is reserved, so both remain usable as ordinary
names, and this is the only place either reads as a keyword.

## Assignment

An assignment written with `=` is a **statement**: it stores, and it yields nothing. Its type is
`unit`, so it stands where a value is nobody's: on a line of its own, or as the last line of a block
that owes none — a block in [statement position](#statement-position-discards-a-block-s-value), one
where `unit` is wanted, or a function or closure body whose result is read off it, which is then
`unit`. Anywhere a value is wanted it is refused, a branch whose value is bound included, and the
message names the spelling that does yield:

```sysl
var a = 0
var b = 0

a = b = 7
```

```error
'=' assigns and yields nothing, so it cannot stand where a value is wanted — write ':=' to assign and use the value
```

**`:=` assigns and yields.** It stores exactly as `=` does, and its value is what reading the place
right after the store gives. It has assignment's precedence — the lowest — and is right-associative,
so a chain stores every place, and the capture idioms read as written:

```sysl
next(n: *int) -> int
    *n -= 1
    *n

var a = 0
var b = 0
var c = 0

a = b := c := 7

var n = 3
var k = 0

while (k := next(&n)) != 0
    print(k)

print(a, b, c, k)
```

```output
2
1
7 7 7 0
```

Keeping the yield to its own spelling is what closes C's `if (x = 0)` mistake for every type, `bool`
included: the one form a reader could take for a comparison is the one with no value.

```sysl
var on = false
val yes = true

if on = yes
    print("set")
```

```error
'=' assigns and yields nothing, so it cannot stand where a value is wanted — write ':=' to assign and use the value
```

The compound forms `+=`, `-=`, `&=` and the rest are **not separate operators with their own
traits**: `a += b` is defined as `a = a + b` and requires exactly what `+` requires. There is no
`AddAssign`. Each has a yielding spelling too — `+:=`, `-:=`, `*:=`, `/:=`, `%:=`, `&:=`, `|:=`,
`^:=`, `<<:=`, `>>:=` — whose value is the place's **new** value:

```sysl
var x = 5

print(x +:= 2, x *:= 3, x >>:= 1, x)
```

```output
7 21 10 10
```

**What `:=` yields is a read of the place, with a read's ownership.** A value type is copied, as
reading any place copies it. A `&T` is a counted share, exactly as reading a `&T` place is: whatever
keeps the value takes a count of its own, so the place and every name holding the yield each own
one, and the object lives until the last of them lets go. The value has the place's type, so one
that arrived narrower is widened by the store:

```sysl
struct Res
    id: int

impl Drop for Res
    drop(self) = print(s"drop ${self.id}")

main()
    var a: &Res = Res(1)
    val b = (a := Res(2))

    a = Res(3)
    print(b.id, a.id)
```

```output
drop 1
2 3
drop 2
drop 3
```

A settable [property](/reference/declarations/#a-property-may-be-settable) yields what its getter
reads back once the setter has run, from a receiver whose address is taken once; a setter always has
its getter beside it, so there is always a read to give. `:=` is refused wherever that read would
be: on an element written through [`IndexSet`](#operator-dispatch), where reading it
back would evaluate the receiver and the index a second time, and on a place a computed receiver
names, which has no address to read twice. An assignment to several places is a statement whatever
its spelling, so `a, b := 1, 2` is refused too.

### Statement position discards a block's value

A block's value is its trailing expression — but where the block's own value is unused, the block has
**none**, and its type is `unit` whatever the last line yields. Without that rule, an `if` whose
branches each perform an action would need those actions' values to agree:

```
if c
    full = true          // bool
else
    len += 1             // usize
```

The rule is about the **position**, not the operator: it propagates into the branches of an `if`, the
arms of a `match`, and a loop's `else`. Statement position starts at a statement, at a loop body, and
at the body of a function that returns nothing.

A block that never completes keeps the type `never` rather than becoming `unit`: that describes
reachability, not a value, and the surrounding code relies on it.

### Several places at once

A comma-separated list of places takes a comma-separated list of values. **The right side is
evaluated in full, into temporaries, before any assignment happens**, so the first line below is a
swap rather than two statements that leave both variables holding `b`.

```sysl
var a = 1
var b = 2

a, b = b, a

var xs = [10, 20, 30]

xs[0], xs[2] = xs[2], xs[0]

print(a, b, xs[0], xs[2])
```

```output
2 1 30 10
```

Compound forms multi-assign too, under the same rule. Every place is located, then every place a compound form touches is **read**, then the whole right
side is produced, and only then does anything land:

```sysl
var a = 1
var b = 2

a, b += b, a

print(a, b)
```

```output
3 3
```

Both sides saw the values the statement started with. Two separate statements would differ:
`a += b` followed by `b += a` folds the new `a` into `b`.

**The places may be written in parentheses**, as a tuple of places: `(a, b) = (b, a)` is the
statement above, with every operator, value list and place the bare list takes. The places do not
nest, in either spelling.

```sysl
pair(n: int) -> (int, int) = (n, n * 2)

var lo = 0
var hi = 0

(lo, hi) = pair(5)
print(lo, hi)

(lo, hi) = (hi, lo)
print(lo, hi)
```

```output
5 10
10 5
```

A place's own subexpressions are evaluated **exactly once**, before the assignments — so in
`xs[f()], xs[g()] = xs[g()], xs[f()]` each of `f` and `g` runs once.

#### `_` discards a part

**A place written `_` takes its part and writes it nowhere**, in either spelling. A value of its own
is still evaluated, in its turn among the others, and a part that holds something counted or with a
`Drop` is let go of when the statement ends:

```sysl
pair(n: int) -> (int, int) = (n, n * 2)

noisy(n: int) -> int
    print(s"noisy $n")
    n

struct Tag
    n: int

impl Drop for Tag
    drop(self)
        print(s"drop ${self.n}")

var a = 0
var b = 0

a, _ = pair(5)
(_, b) = pair(6)
print(a, b)

a, _, b = noisy(1), noisy(2), noisy(3)
print(a, b)

a, _ = 4, Tag(7)
print(a)
```

```output
5 12
noisy 1
noisy 2
noisy 3
1 3
drop 7
4
```

Every place may be `_`, which evaluates the right side for its effects alone — **a single one
included: `_ = f()` runs `f` and drops what it answers**, whatever that is: a `Result`, a reference
with a `Drop` (let go of when the statement ends), `unit`, a tuple. It is how a `Result` is dropped
on purpose, which a bare `f()` [warns about](/reference/errors/#a-dropped-result-is-a-warning).

```sysl
struct Tag
    n: int

impl Drop for Tag
    drop(self)
        print(s"drop ${self.n}")

make(n: int) -> &Tag = Tag(n)

_ = make(1)
print("after")
_ = (make(2), 3)
```

```output
drop 1
after
drop 2
```

Only a **whole** place
may be `_` — one inside a place, as in `xs[_], a = 1, 2`, would stand for a closure's parameter — and
only `=` discards: a compound form reads every place before writing it, and `_` has nothing to read.

```sysl
var a = 0

a, _ += 1, 2
```

```error
'+=' reads every place before writing it, and '_' has nothing to read
```

**A multiple assignment is a statement, not an expression**, which keeps it simple. A single
`:=` yields the value it stored; a multiple one would have to yield several, which could only be a
tuple — a product value built for a form whose purpose is to change several places at once. So it
does not nest, does not appear in a condition, and has no `:=` spelling. A binding takes the same
list: `val a, b = 1, 2`.

A multiple assignment accepts fewer places than a single `=` does: it refuses a place that is really
a **call**, of which there are two kinds — an element reached through a user type's `Index`, where `b[i] = v` calls
`index_set`, and a [settable property](/reference/declarations/), where `p.count = v` calls the
setter. Neither has an address for the locating phase to find, and the call both reads and writes, so
there is nothing to split into the two halves the ordering rule is about.

Written `:=`, the element is refused, since reading it back is a second call through the receiver
and the index; the property yields what its getter reads back.

## `++` and `--`

Both are expressions, prefix and postfix. Prefix yields the new value; postfix yields the old one.
The pointer-walking idioms depend on this: `*p++` only works if `++` is an expression.

```sysl
var i = 5

print(i++, i, ++i, i)
```

```output
5 6 7 7
```

C's `i = i++ + ++i` is undefined because C leaves evaluation order unspecified while allowing
unsequenced mutation of one object. sysl addresses the cause rather than banning the operator:
**evaluation order is strictly left to right**, everywhere — operands of a binary operator, function
arguments, index expressions. With an order defined, the expression above has one meaning. It is
merely hard to read, which is a matter of style rather than of correctness.

Postfix binds tighter than prefix, so `*p++` is `*(p++)` and `-a.b` is `-(a.b)`, exactly as in C.
The one prefix that reaches past a postfix is [`await`](/reference/async/#where-await-binds), which
takes its operand's calls, member selections and indexes but stops at the first `?` — `await f()?`
is `(await f())?` — while `-a?` stays `-(a?)`.

## The postfix tail

Eight things attach to an expression on the right, and they compose left to right.

| tail | what it does |
|---|---|
| `e[i]` | index — an element of an array, a slice, or a type implementing `Index`, reached through one `*T` too; on a pointer to anything else, C's `i`th element along ([Arrays](/reference/arrays/#a-raw-pointer-is-indexed-anyway)) |
| `e.name` | select a field, or call a method |
| `e.0` | select a tuple's part by position |
| `e(…)` | call |
| `e:` and an indented block | call, with the block as an argument — [below](#a-trailing-block) |
| `T::Attr` | a type's attribute rather than a value's — a constrained type's bounds live here |
| `e with { f = v }` | this struct again, with those fields changed — [below](#with-this-value-with-one-field-different) |
| `e?` | try — unwrap or propagate |
| `e++`, `e--` | post-increment, post-decrement |

A tuple index is a field selection: a tuple's fields are named for their positions. `t.0.1` does not
work, because the lexer reads `0.1` as a float before it can be two indexes; write `(t.0).1`, as the
diagnostic suggests.

A dot with **nothing to its left** is a different form — the member of whatever type the context
expects, [below](#a-leading-dot-the-qualifier-the-context-already-knows) — and it is distinguished
from a tail by having no expression in front of it to select from.

### `?`

Postfix on an `Option` or a `Result`, and sugar for the most common `match`: **unwrap the success,
or early-return the failure.**

- On a `Result`: `Ok(v)` evaluates to `v`; `Err(e)` returns `Err(e)` from the enclosing function
  immediately.
- On an `Option`: `Some(v)` evaluates to `v`; `None` returns `None` immediately.

```sysl
half(n: int) -> Result[int, string]
    if n % 2 == 0 then Ok(n / 2) else Err("odd")

quarter(n: int) -> Result[int, string]
    var h = half(n)?

    half(h)

show(r: Result[int, string])
    r match
        Ok(v)  -> print("ok", v)
        Err(e) -> print("err", e)

show(quarter(8))
show(quarter(6))
```

```output
ok 2
err odd
```

Two rules make it well defined. **The enclosing function's return type must carry the failure** — a
`?` on a `Result` is legal only inside a function returning `Result`, and on an `Option` only inside
one returning `Option`. The early return has to have somewhere to go, and the two channels do not
cross. And **the error types must match, or be joined by a `From`**: a function with an error type
of its own writes `impl From[Theirs] for Mine` once, and every `?` across that boundary converts
through it ([errors](/reference/errors/#a-converts-through-from)).

`?` is an expression and composes as one, so its unwrapped value flows into whatever surrounds it.

## `with` — this value, with one field different

`base with { bg = 9 }` is `base` again, with the fields named in the braces changed. Everything else
comes along, and the value written on the left is untouched.

```sysl
struct Style
    fg: int
    bg: int
    pad: int

val theme = Style(1, 2, 3)
val hot = theme with { bg = 9 }

print(theme.fg, theme.bg, theme.pad)
print(hot.fg, hot.bg, hot.pad)
```

```output
1 2 3
1 9 3
```

Several fields at once are separated by commas. A brace suspends the off-side rule until it closes,
so they may be written one per line, and the last one may carry a comma like every other bracketed
list in the language:

```sysl
struct Style
    fg: int
    bg: int
    pad: int

val theme = Style(1, 2, 3)

val hot = theme with {
    bg = 9,
    pad = 8,
}

print(hot.fg, hot.bg, hot.pad)
```

```output
1 9 8
```

### It is a copy followed by assignments

The rule is one desugaring. `base with { bg = 9 }` means:

```
var tmp = base
tmp.bg = 9
tmp
```

so the base is copied first, the fields are assigned to **in the order written**, and the copy is the
value. Everything else follows from that: a struct's `invariant` is re-checked because a field
assignment re-checks it, a field another module keeps `private` is refused because an assignment
refuses it, a literal needs no width suffix because it is read against the field's type, and a
[settable property](/reference/declarations/) runs its setter because `p.count = v` is a call.

```sysl
struct Cell
    v: int

    count -> int = self.v

    set count(x)
        self.v = x * 10

val c = Cell(0)

print((c with { count = 4 }).v)
```

```output
40
```

### It composes

The statements it stands for remain available, but they cannot stand inside a larger expression.
`with` can, so a value can be layered at the point it is used — a theme overridden for one state and
overridden again for the next:

```sysl
struct Style
    fg: int
    bg: int

paint(s: Style)
    print(s.fg, s.bg)

val theme = Style(1, 2)
val pressed = theme with { bg = 9 }

paint(theme)
paint(pressed)
paint(pressed with { fg = 7 })
```

```output
1 2
1 9
7 9
```

The base is evaluated **once**, however many fields change, so a base that is a call is called once.

### The base has to be a struct

A counted reference or a pointer is refused, because the desugaring would change meaning: `var tmp =
p` on a `&Style` binds a second reference to the *same* object, so the writes would reach every
other holder and the form would silently mean the opposite of a copy.

```sysl
struct Style
    bg: int

val base: &Style = Style(2)
val hot = base with { bg = 9 }

print(hot.bg)
```

```error
'with' copies a struct, and this is a counted reference to one
```

The refusal names the spelling that does copy, and its parentheses are required: the clause is a
postfix tail, so `*p with { … }` is `*(p with { … })`.

```sysl
struct Style
    bg: int
    pad: int

var base = Style(2, 3)
val p = &base
val hot = (*p) with { bg = 9 }

print(base.bg, hot.bg, hot.pad)
```

```output
2 9 3
```

A field named twice in one clause is refused too, since the first change could never take effect:

```sysl
struct Style
    bg: int

val base = Style(2)
val hot = base with { bg = 9, bg = 8 }

print(hot.bg)
```

```error
'bg' is changed twice in one 'with'
```

### `with` is not a reserved word

It is read as a keyword only after a value and before a brace, and is an ordinary identifier
everywhere else — including as a field's own name, on both sides of the clause:

```sysl
struct Flags
    with: int

val f = Flags(1)

print((f with { with = 9 }).with)
```

```output
9
```

## A leading dot — the qualifier the context already knows

`.Green` is `Colour.Green` with the qualifier left off, and the type the position expects is what
supplies it. It is a **lookup rather than a second kind of inference**: the expectation names the
type, and everything after that is the resolution the written-out spelling already gets.

```sysl
enum Colour
    Red
    Green
    Blue

code(c: Colour) -> int
    c match
        Red -> 1
        Green -> 2
        Blue -> 3

struct Pen
    tip: Colour

pick() -> Colour = .Blue

var c: Colour = .Red

c = .Green

val pens = [Pen(.Red), Pen(.Blue)]

print(code(.Green), code(c), code(pick()), code(pens[1].tip))
print(c == .Green, .Green == c)
```

```output
2 2 3 3
true true
```

**Every position that already pushes an expected type down supplies one**, so the list is long but
needs no rule of its own: an argument, an argument written by name, a parameter's default, an
annotated binding and the assignment after it, a return and an expression body, a struct's field, an
element of an array or a slice, a part of a tuple, a variant's payload, each branch of an `if` or
a `match` used as a value, and the operand of a prefix `-` or `~`.

**`-` and `~` hand the operand the whole expected type**, because each answers the type it is given:
a number's own, or `Self` from `Neg` and `Not`. So whatever settles `P.make()` at a `P[int]` settles
`-P.make()` there too, and an operator over an operator passes it on. `!` takes and answers a `bool`,
so its operand is expected to be one whatever the position says.

```sysl
struct P[T]
    v: T

    make() -> P[T] = P(T(1))

impl[T: Neg] Neg for P[T]
    neg(self) -> P[T] = P(-self.v)

val a: P[int] = -P.make()
val b: P[real] = -(-P.make())

print(a.v, b.v)
```

```output
-1 1
```

The operator lends the operand a type, never an operation: with nothing expected the call is refused
in its own words, as `P.make()` alone would be, and a type without a `Neg` is refused at the minus.

```sysl
struct P[T]
    v: T

    make() -> P[T] = P(T(1))

impl[T: Neg] Neg for P[T]
    neg(self) -> P[T] = P(-self.v)

val a = -P.make()

print(a.v)
```

```error
cannot infer the type argument 'T' of 'P.make' here
```

**An operand takes it from the operand beside it, and neither side is privileged** — `c == .Green`
and `.Green == c` are the same question. At a *generic* parameter it is held back to the second pass
exactly as `null` is, so the argument that settles the type parameter settles this one too.

**It reaches whatever the qualified form reaches**, and fails where that fails in the same words: a
variant, a variant carrying data, an associated function of an enum, of a struct, or of a
[constrained subtype](/reference/errors/). Visibility is untouched — what the dot leaves off is the
*spelling*, not the check, so a private associated function stays private.

**The type it resolves against need not be nameable where the dot is written.** A variant of another
module's enum needs no import and no path: the parameter already says which type it is.

**The expected type is read as it was written**, and this is the one place a transparent
constrained subtype is *not* interchangeable with its base: the base is not where the subtype's
members are filed. Reducing `Age` to `int` first would look for the member on `int`, which does not
have it.

```sysl
type Age = int within 0..150

trait Fresh
    fresh() -> Self

impl Fresh for Age
    fresh() -> Age = Age(1)

val a: Age = .fresh()

print(int(a))
```

```output
1
```

Two things it does not do. A **type attribute** is written `::`, because it belongs to the type
rather than to a value of it, and the dot does not reach it. A **pattern** is left alone: a bare name
in one already resolves against the scrutinee's enum, so a dot written there is refused by name
rather than left to a message about patterns.

What it refuses is a position that expects nothing:

```sysl
enum Colour
    Red
    Green

print(.Red)
```

```error
'.Red' is a member of whatever type the context expects, and nothing here expects one
```

and a name the expected type does not have, which is the qualified form's own diagnostic:

```sysl
enum Colour
    Red
    Green

val c: Colour = .Rd

print(1)
```

```error
enum 'Colour' has no variant 'Rd'
```

## Conversions are calls

A conversion that can lose or change a value is written, with call syntax — the visible-cost rule the
memory model rests on applies to representation changes too. One that cannot, where every value of
the source type is exactly a value of the target, a position performs by itself (§ [Which
conversions are implicit](#which-conversions-are-implicit)); writing it as a call is still allowed.

| from → to | written | behaviour |
|---|---|---|
| integer → integer | `u16(n)`, `byte(n)` | extends; one that cannot hold the value truncates in a release build and [stops a debug one](#a-conversion-that-cannot-hold-its-value); sign-extends only when the *source* is signed |
| integer → float | `real(n)`, `f32(n)` | rounds to nearest; signed and unsigned sources differ |
| float → integer | `int(x)`, `u8(x)` | truncates toward zero, **saturating** — past either end it is that end; a NaN is `0` |
| float → float | `f32(x)`, `real(x)` | rounds to nearest |
| fixed-point → fixed-point, float or integer, and back | `q31(x)`, `real(q)`, `int(q)` | rounds to nearest and saturates — see [fixed-point numbers](/reference/fixed-point/#conversions) |
| `char` → integer | `u32(c)` | total — every `char` is an integer |
| integer → `char` | `char(u)` | **partial** — traps on a value that is not a Unicode scalar value |
| `char` → `string` | `string(c)` | total — the one character, UTF-8 encoded into a fresh string |
| `*T` → integer | `usize(p)`, `isize(p)` | total — an address is a number |

```sysl
var n = 300
var c = 'A'

print(byte(n & 0xff), real(n), u32(c), int(3.9))
```

```output
44 300 65 3
```

### A conversion that cannot hold its value

**An integer conversion that can lose a value — to a narrower width, or across the sign — keeps the
value where the target holds it.** Where the target does not, a release build keeps the low bits
(`byte(300)` is 44, `u32` of −1 is 4294967295), which is defined, and a debug build stops the
program, as an overflowing `+` does: `integer overflow: byte(int) (main.sysl:3:7)`. To keep the low
bits in every build, say so — with `u8.wrap(n)` below, or with a mask, which makes the value fit
(`byte(n & 0xff)` above).

```sysl
var n = 300

print(i16(n))
print(u8(n))
```

```output trap
300
```

**A conversion of a constant is worked out by the compiler, the same in every build**: `u32(-1)` is
the bit pattern written down, and is 4294967295 in a debug build as in a `const`. A widening, which
holds every value, is never checked.

### A wrapping conversion keeps the bit pattern

**`T.wrap(x)` converts an integer to the integer type `T` by its bit pattern, and is never checked, in
any build.** Where `T` is narrower it keeps the low bits; where it is wider the value is extended as
**the source's** signedness says — a signed source copies its sign bit up, an unsigned one fills with
zeros; at the same width it reads the same bits at the other sign. It is what a hash, a checksum or
a two's-complement trick means by a conversion, where the plain `u64(x)` means "this value, which
fits". Any width and sign go either way — `u12`, `i128` — and the argument is any integer.

```sysl
var n = 300
var k = -1
var s: i8 = -1
var u: u8 = 255

print(u8.wrap(n), u64.wrap(k), i8.wrap(u))
print(u16.wrap(s), i64.wrap(u), u12.wrap(k))
```

```output
44 18446744073709551615 -1
65535 255 4095
```

Over a constant it folds, so `const Mask: u8 = u8.wrap(300)` is 44. In a generic body the target may
be the parameter, `T.wrap(x)` where `T: Integer`, and the row is chosen at the instantiation. The
argument has to be an integer — a float is converted by the plain call, which saturates — and the
name in front has to be an integer type:

```sysl
print(u8.wrap(1.5))
```

```error
'byte.wrap' takes an integer, but the value has type real
```

**A float read as an integer saturates.** The value is truncated toward zero, and where that does not
fit the width it comes to the width's nearest end: anything above `i32::Max` is `i32::Max`, anything
below `i32::Min` is `i32::Min`, and a negative value read as an unsigned type is `0`. **A NaN, which
has no integer value at all, comes to `0`** — for a signed and an unsigned target alike. An infinity
is past either end, so it is that end. The rule is the same at both float widths, for every integer
width and signedness, and for a constant as for a value computed at run time. Nothing traps and
nothing wraps, so `i32(x * 2147483648.0)` is a correct clamp of a sample in
`-1.0 ..= 1.0` into q31.

```sysl
var big = 1.0e30
var z = 0.0
var nan = z / z

print(i32(big), i32(-big), i32(nan))
print(u8(300.0), u8(-1.5), u8(nan), u8(255.9))
print(int(2.9), int(-2.9))
```

```output
2147483647 -2147483648 0
255 0 0 255
2 -2
```

**An integer literal written as the argument is read at the target wherever it fits there**, so a
value too wide for `int` needs no suffix to reach a wider type. One that fits `int` and not the
target is converted from `int`, by the table's own row, and one that fits neither is refused at the
type the line names:

```sysl
print(i128(9223372036854775807), u64(18446744073709551615), i64(-2147483649))
print(u8(300), u64(-1))
```

```output
9223372036854775807 18446744073709551615 -2147483649
44 18446744073709551615
```

```sysl
print(i64(9223372036854775808))
```

```error
the literal 9223372036854775808 does not fit long
```

Everything else is rejected. There is **no conversion to or from `bool`** — `int(true)` is an error
and so is `bool(0)` — and **no number converts to or from a `string`**: `x.to_string()` renders one and the
`strconv` surface parses one, neither of them spelled as a conversion. The `char` → `string` row is
the one exception, and a narrow one: a `char` is a single scalar value, so encoding it is total and
cannot fail.

The pointer row goes only one way. An address *is* a number of `usize`'s width, so reading it as one
loses nothing and produces a value that cannot be dereferenced. The inverse is not a conversion at
all: making a pointer out of an integer is `ptr_cast`, in the raw tier, kept apart from this table
because it is where the language's guarantees stop.

Because these are calls, they parse as postfix at level 13 rather than as an operator of their own.
The name in front may be a **type parameter**, and then the row is chosen at the instantiation.

### Which conversions are implicit

**A value of type `S` converts to `T` without being written exactly when every value of `S` is
exactly a value of `T`.** Nothing is rounded, truncated, saturated or reinterpreted, so there is no
cost to make visible: the conversion is the one `T(x)` performs, done by the position.

| from → to | implicit when | so |
|---|---|---|
| `uM` → `uN`, `iM` → `iN` | `N ≥ M` | `u8` → `u16`, `int` → `long` |
| `uM` → `iN` | `N > M` | `u8` → `i16`, never `u32` → `int` |
| `iM` → `uN` | never | a negative value has no unsigned twin |
| `usize`, `isize` | as `uN`/`iN` at the target's address width | `u32` → `usize` on a 64- and a 32-bit target, not on Craft's 16; `usize` ↔ `isize` never |
| `char` → integer | the integer holds `0x10FFFF` (`u21`+, `i22`+) | `char` → `u32`, `int`, `long`; integer → `char` is always written |
| float → float | wider format | `f16`, `bf16` → `f32`, `f64`, `f128`; `f32` → `f64`; `f16` ↔ `bf16` never |
| `uM` → float, `iM` → float | `M ≤ p` unsigned, `M ≤ p + 1` signed, `p` the significand (`f16` 11, `bf16` 8, `f32` 24, `f64` 53, `f128` 113) | `int` → `real`, `i16` → `f32`; `int` → `f32` never |
| fixed → fixed, integer → fixed | no fraction bit and no integer bit is lost (an integer has no fraction; unsigned → signed needs one more integer bit; signed → unsigned never) | `q15` → `i32q16`, `i8` → `i16q8` |
| fixed → float | the significand holds every bit and the format reaches `2^-F` | `q15` → `f32`, `q31` → `real`; `q31` → `f32` never |
| float → integer or fixed, fixed → integer | never | each rounds |

**Nothing converts through anything else**: `bool`, a pointer (`usize(p)` stays written), a slice, a
reference, an `Option`, a tuple or a function type converts as itself or not at all, so a `[]u8` is not
a `[]int` and a `byte` is not an `Option[int]`.

**The positions that convert** are the ones that know the type they want: a `val` or `var` with a
declared type, an assignment (a compound one converts the right side to the left's type, never the
reverse), an argument (an `extern`'s too), a returned value, each element of a literal whose element
type the context gives, and the branches of an `if` or a `match` used as a value, which meet at the
wider of their types as two operands do. **The bounds of a range and the elements of an unannotated
array literal meet the same way**: `i..<n` over an `int` and a `long` is a `long` range, and `[a, b]`
over an `int` and a `long` is a `[2]long`. A pair neither of whose types holds the other — a `u32` and
an `int` — is refused.

```sysl
struct Sample
    at: long
    level: real

peak(xs: []const long) -> long
    var best: long = 0

    for x in xs
        if x > best then best = x

    best
end peak

widen(b: byte) -> int = b

var b: byte = 200
var s: i8 = -1
var n: int = b
var total: long = s

total += n

val wide: [3]long = [n, b, s]
val one = Sample(n, n)

print(n, total, widen(b), peak(wide[..]), one.at, one.level, if n > 0 then b else n)
```

```output
200 199 200 200 200 200 200
```

```sysl
var i: int = 2147483646
var n: long = 2147483648
var b: byte = 7

for k in i..<n
    print(k)

val ints = [b, i, b]
val longs = [i, n]
val wide: [2]long = longs

print(ints[0] + ints[2], ints[1], wide[1] - wide[0])
```

```output
2147483646
2147483647
14 2147483646 2
```

**An integer literal beside an operand of an integer type is read at that type, fitting or not**, as
Rust reads one: `b + 3` over a `byte` is a byte sum, and `b + 300`, `300 + b`, `b < 300` and `b + -1`
are each refused at the literal for not fitting a byte. What the pair is for does not change it —
`val z: int = b + 300` is the same mistake, and `int(b) + 300` is the sum it meant. The same holds
for range bounds, the elements of an unannotated array literal and the arms of an `if` or a `match`.
Only two operands that each have a type of their own widen to each other (`u8 + u16` is a `u16`).

```sysl
var b: byte = 200

print(b +% 100, int(b) + 100)
```

```output
44 300
```

```sysl
var b: byte = 2

print(b + 300)
```

```error
the literal 300 does not fit byte
```

**A float literal is read at a typed operand's type where it fits there** — `h * 0.5` over an `f32`
is an `f32` product. **Where it does not, it takes its own default, `real`, and the typed operand
widens to that** wherever the table above allows it, so `1.0 / k` over an `int` is a `real`
quotient and `k < 1.5` compares as reals, in either order. Where the operand does not widen to
`real` — a `long` beside `2.0`, a `char` beside anything — the pair is refused.

```sysl
var k = 4
var h: f32 = 1.5

val x: f32 = h * 0.5

print(1.0 / k, k / 2.0, k < 1.5, x)
```

```output
0.25 2 false 0.75
```

**An operand that comes to a literal on every path is a literal here too**: an `if` or a `match`
whose every arm is one, a literal shifted by any count, and arithmetic over them. Each is read at
the typed operand's type, on either side, as a single literal is, so an arm that does not fit is
refused where it is written.

```sysl
val c = true
var b: byte = 16

print((if c then 0x15 else 5) | 7u32, b | (if c then 1 else 2), b | (if c then 255 else 1))
```

```output
23 17 255
```

```sysl
val c = true
var b: byte = 16

print(b | (if c then 300 else 1))
```

```error
the literal 300 does not fit byte
```

```sysl
var q: long = 3

print(q / 2.0)
```

```error
'/' needs matching types, got long and real
```

**An integer literal is the float a position or a float operand asks for**, where the float holds its
value exactly ([Literals § Integers](/reference/lexical/#integers)): the literal is that float, so nothing is
converted. `r / 2` over a `real` divides by two reals, `h + 1` over an `f32` stays an `f32`, and
`half(7)` passes a `real` seven. A literal the float would round — `h + 16777217` over an `f32` — is
refused, and a suffixed literal is an integer value that converts only as the table says.

```sysl
half(x: real) -> real = x / 2

var r: real = 3
var h: f32 = 1.5

r = 2 * r

print(r, half(7), h + 1, h < 2)
```

```output
6 3.5 2.5 true
```

```sysl
var h: f32 = 1.5

print(h + 16777217)
```

```error
the literal 16777217 is not exactly an f32
```

**Where two literals meet and one is a float, the integer literal is that float too** — under the
same operand rule, so in arithmetic, a comparison, the bounds of a range, the elements of an
unannotated array literal and the arms of an `if` or a `match`. `1 + 2.0` is a `real` three,
`[1.5, 2]` a `[2]real`, and `if c then 1 else 2.5` a `real`. A position's type still comes first
(`val h: f32 = 1 + 2.0` is an `f32`); a suffixed integer literal is a typed operand and widens only
as the table says, so `1i64 + 2.0` is refused as `long` beside `real` is; and an integer the float
would round is refused as above.

```sysl
val rs = [1.5, 2]
val h: f32 = 1 + 2.0

print(1 + 2.0, rs[1], 1 < 2.5, h)
```

```output
3 2 true 3
```

```sysl
print(0.5 + 9007199254740993)
```

```error
the literal 9007199254740993 is not exactly a real
```

**A type parameter is never decided by a conversion.** A call solves it from the arguments' own types,
exactly, and only a parameter it has already fixed is widened to — so `max(a, b)` over an `int` and a
`long` is refused in either order, and `max[long](a, b)` is how to say which.

```sysl
import sysl.math.max

var a: int = 1
var b: long = 2

print(max(a, b))
```

```error
'b' of 'sysl.math.max' is int, but long was given
```

**Between overloads, an exact match beats any widening, and among widenings the narrowest wins** —
the candidate each of whose parameters converts to the other's. Where neither is narrower at every
position, the call is ambiguous — except where the candidates are tied only by bare literals, which
then take their default types ([Overloading](/reference/declarations/#overloading)).

```sysl
f(x: int) -> string = "int"
f(x: long) -> string = "long"

var b: byte = 1
var l: long = 1

print(f(b), f(l))
```

```output
int long
```

**A conversion that can lose a value is a diagnostic** unless it is written: a narrowing, a sign
change, a float read as an integer.

```sysl
var l: long = 5
var n: int = l
```

```error
cannot initialize 'n': declared int but the value is long
```

## `sizeof`, `alignof` and `offsetof`

The three forms whose first operand is a **type** rather than a value. All take parentheses, and all
yield a `usize`.

```sysl
struct Pair
    a: int
    b: byte

print(sizeof(int), alignof(int), sizeof(Pair), alignof(Pair))
```

```output
4 4 8 4
```

They have their own grammar rather than being parsed as calls, because a call's arguments are
expressions and `sizeof(*Node)` would otherwise parse as a dereference. There is no form that takes a
value, since it is the value's type that would be measured.

`offsetof` takes a type and then a **field name**, and answers where that field starts in bytes. The
name is a name and not an expression, for the same reason the type is not one: there is no value here
for a `p.x` to select from.

```sysl
struct Header
    tag: u8
    length: u32
    flags: u16

print(offsetof(Header, tag), offsetof(Header, length), offsetof(Header, flags), sizeof(Header))
```

```output
0 4 8 12
```

The padding after `tag` is what puts `length` at 4 — `@packed` lays the same fields end to end and
makes them 0, 1 and 5. Its use is
[checking a mirrored C struct](/reference/attributes/#checking-a-c-struct-s-layout), where a size
alone cannot see two same-width fields transposed. A field the struct does not have is refused by
name, and so is a [bitfield](/reference/attributes/#bitfields-an-in-field-in-exactly-n-bits) — the
answer is in bytes, and a field starting at bit twelve is not at byte one.

## Closures

`x -> x + 1` is a closure literal. It sits at the top of the expression grammar, so its body extends
as far to the right as an expression can: that is a closure over the sum, not a closure over `x`
added to `1`.

Parameters are one bare name, or a parenthesized list — including the empty list `()`. A type
annotation goes inside the parentheses and nowhere else, so there is only one way to write it.

```sysl
apply(f: () -> int) -> int = f() + f()

var n = 10
var twice = apply(() -> n)
var inc = (x: int) -> x + 1

print(twice, inc(41))
```

```output
20 42
```

A closure's parameter declares **no default**. A call reaches a closure through the `Fn` traits,
which carry types and not names, so there would be nothing at the call to fill one from.

### A closure that does not outlive its variables captures them by reference

**A closure that does not outlive the variables it captures holds where each `var` lives**, so a
write in the closure reaches the variable, and a read sees its value at the time of the call — the
variable itself, shared with the code around it and with every other such closure. That is the
closure handed to `each` or to a callback parameter its function only calls, the closure in a local
`var` called in its own block, and any closure every use of which the compiler can follow.

```sysl
call(f: &Fn() -> unit) = f()

each(xs: []int, f: int -> unit)
    for x in xs
        f(x)

var hit = false
call(() -> hit = true)

var best = 0
var xs = [3, 9, 2]
each(xs[..], n -> if n > best then best = n)

var n = 0
var bump = () -> n += 1
bump()
bump()

print(hit, best, n)
```

```output
true 9 2
```

A `*self` method called on a captured struct reaches the variable the same way, as does a write to
a field of one or an element of a captured array:

```sysl
struct Counter
    n: int

    bump(*self) = self.n += 1

call(f: &Fn() -> unit) = f()

var c = Counter(0)
call(() -> c.bump())
print(c.n)
```

```output
1
```

**A closure that may outlive them captures by value**: one returned, stored in module storage or in
a structure that outlives the block, pushed into a buffer, handed to a parameter its function keeps,
kept in a local of a block around the one declaring a captured variable, built in an `async` body, or
converted into a `&sync Fn` for another domain. It carries its own copy, which is state it keeps
between its calls:

```sysl
make() -> &Fn() -> int
    var n = 10

    () ->
        n += 1
        n

val next = make()
print(next(), next())
```

```output
11 12
```

Nothing is written for either: the compiler works it out from the bodies, as it does for every
pointer a function is handed. **A `val` is copied either way**, since nothing can tell a copy of it
from the variable, and so are a pattern's binding and a by-name parameter.

### A `val` may hold a closure that writes only through to its variables

Calling a closure that writes a capture it holds by value writes into the closure itself. That is why
`Fn::call` takes `*self` — and a `val` is written once, so binding such a closure to one and calling
it is refused.

**A closure that only reads its captures may be a `val`**, and so may one that writes only the
variables it holds by reference: the call writes through the closure to them and leaves the closure
as it was. Nothing about the call needs the name to be mutable.

```sysl
var n = 0
val inc = (x: int) -> x + 1
val bump = () ->
    n += 1
    n

print(inc(41), bump(), bump(), n)
```

```output
42 1 2 2
```

A write through what a capture refers to — a `&T`, a `*T`, a slice's element, a `Buf` — reaches
storage the closure shares, and a `val` may hold that closure whatever its mode.

**A closure that holds a written capture by value is a value the call mutates**, so a `val` holding
one is refused, and the refusal says what keeps the closure:

```sysl
make() -> &Fn() -> int
    var n = 0
    val bump = () ->
        n += 1
        n

    print(bump())
    bump

print(make()())
```

```error
'call' takes '*self', so it writes through what it is called on, and a 'val' is written once — this closure captures by value, since it is returned from 'make' on line 8 — write 'var bump' if it is meant to change
```

`&Fn(int) -> int` holds a closure behind a reference, a box — built in the frame where nothing keeps
the closure past it, and on the heap otherwise (below). A reading closure in a `val` is how
freestanding code binds a closure to an immutable name without asking that question.

### A closure handed on as a `&Fn` is boxed in the frame

A closure erased to `&Fn` is a counted reference, so it has a box: the counts, the hook that lets go
of what it holds, and the closure. **Where nothing reaches the closure after the frame that builds it
— nor, in a loop, after the turn that builds it — the box is a slot of that frame**, so handing a
closure to `each`, `sort_by` or a callback that only calls it allocates nothing, `@no_alloc` accepts
it, and so does a target with no allocator. A callee may still take a share and give it back; the
count is the slot's. The question is the one a closure's capture mode asks, about the same flows: a
closure returned, stored where it outlives the frame, handed to a parameter its function keeps, kept
in a local outside the loop body that builds it, built in an `async` body, or erased into a `&sync
Fn` is boxed on the heap, as it always was. Nothing is written for either, and `--explain-escapes`
names each heap box and why:

```sysl
apply(f: &Fn() -> int) -> int = f()

main()
    var gs: [3]&Fn() -> int = [() -> 0, () -> 0, () -> 0]
    var total = 0

    for i in 0..<3
        val k = i * 10

        total += apply(() -> k + 1)
        gs[i] = () -> k

    print(total, gs[0](), gs[1](), gs[2]())
```

```output
33 0 10 20
```

The closure handed to `apply` is boxed in the frame, once per turn; each one kept in `gs` outlives
its turn, so each is boxed on the heap and holds its own `k` — `sysl build --explain-escapes` says
*a closure erased to '&Fn() -> int' is boxed on the heap, because it is kept in 'gs' on line 11,
which outlives the turn of the 'for' body that builds it*.

### A write to a copy that nothing will see is refused

A closure that captures by value holds a copy, so a write to a capture reaches **the copy**, never
the variable the closure was written beside. That copy is state the closure keeps between its calls,
which is what the counter `make` returns above is. But where nothing can read the copy, the write is
lost, and a program that writes it almost always meant the variable outside. Two shapes are refused,
at the write:

- **the closure never reads the capture back** — every mention of it in the closure is the place of a
  write, as in `hit = true` or `count += 1`, so no call of the closure can observe what was written;
- **the enclosing body uses the variable after the closure** — it reads it as though the write had
  reached it, which it never does. Every use counts, a hole of an interpolated string
  (`f"clicked ${n}%d times"`) and an operand or argument as much as a plain `print(n)`.

```sysl
var later: Option[&Fn() -> unit] = None

install(f: &Fn() -> unit) = later = Some(f)

var hit = false
install(() -> hit = true)
print(hit)
```

```error
this closure captures by value, since it is handed to 'install' on line 6, which keeps its parameter 'f' (it is stored in 'later'), so it holds a copy of 'hit' — so assignment would reach that copy rather than the variable it came from, and nothing in the closure reads it back. Where you mean to change 'hit', hold it as a '&bool' and write through it, or have the closure return the value
```

**The refusal names what keeps the closure, and where**, in the words of what the compiler followed
it to: returned from the function (`it is returned from 'make' on line 4`), stored where it outlives
the frame (`it is stored in 'later' on line 6, which outlives 'main'`), handed to a callee that keeps
it — directly, through another parameter, or into a `Buf` with `push` — kept in a local of an
enclosing block (`it is kept in 'g' on line 7, which outlives the block declaring 'hit'`), converted
to a `&sync Fn` (`it becomes a '&sync Fn() -> unit' on line 3, which another thread may run`), handed
to a `@crossing` or `@scoped` parameter, or built in an `async` body or a module value's initializer.
A closure handed on through a name a `match` binds goes where that name goes.

```sysl
make() -> &Fn() -> unit
    var n = 0

    () -> n = 5

make()()
```

```error
this closure captures by value, since it is returned from 'make' on line 4, so it holds a copy of 'n'
```

The rule reads what the closure writes the same way a [pattern binding's](/reference/patterns/) is
read: a field of a captured struct and an element of a captured array are parts of the copy. A write
through what a capture **refers to** is not a write to the copy, so the fix is to hold the variable
as a `&T` — a box both sides share — and write through it:

```sysl
var later: Option[&Fn() -> unit] = None

install(f: &Fn() -> unit)
    later = Some(f)
    f()

val hit: &bool = false
install(() -> *hit = true)
print(*hit)
```

```output
true
```

A `*T` to a local, a field of a captured `&T` and a captured `Buf` reach shared storage the same
way. A closure that reads back what it wrote, with the variable outside left alone, keeps its state
and is accepted: a closure whose body is `n += 1` followed by `n` is a counter of its own.

**A `*self` method called on the copy is a write to it**, exactly as an assignment is: the method is
handed the copy's address to write through. That holds for a captured struct, a field of one, an
element of a captured array, and the first link of a chain such as `c.a().bump()`, and it is asked
the same two questions. A `(self)` or `&self` call only reads, and a `*self` call on a captured `&T`
or `*T` reaches the shared storage.

```sysl
struct Counter
    n: int

    bump(*self) = self.n += 1

var later: Option[&Fn() -> unit] = None

install(f: &Fn() -> unit) = later = Some(f)

var c = Counter(0)
install(() -> c.bump())
```

```error
so it holds a copy of 'c' — so 'bump', which takes '*self', would reach that copy rather than the variable it came from, and nothing in the closure reads it back
```

A call answering a value hands back what it read of the copy, so `() -> it.next()` reads its copy
back and is a generator of its own. Where such a closure holds that capture by value it can no longer
be held in a `val`, for the reason an escaping counter cannot: calling it writes its environment.

### `_` — a parameter with the name left out

A bare `_` in operand position is a closure parameter, and the closure it builds closes at the
nearest of three boundaries: a parenthesized group, an argument, or a statement.

```sysl
apply(f: int -> int, x: int) -> int = f(x)

print(apply(_ + 1, 41), apply(_ * 2, 21))
```

```output
42 42
```

A group is the way to place the boundary when the other two fall in the wrong place. It also limits
the closure: **the closure closes at the group, so anything outside the group applies to the closure
itself**, not to what it computes.

```sysl
apply(f: int -> int, x: int) -> int = f(x)

print(apply((_ + 1) * 2, 20))
```

```error
this '_' has no type here
```

`(_ + 1) * 2` multiplies a *closure* by two rather than closing over the doubled sum — so nothing is
left to say what the placeholder's parameter is, and the diagnostic points at the `_`. Where the
boundary falls in the wrong place, write the arrow form: `x -> (x + 1) * 2`.

An interpolation hole is a boundary too, so a placeholder cannot reach out of a string to close over
the whole of one.

## A trailing block

A call may write its **last argument as an indented block**, after a colon.

```sysl
total(xs: []int) -> int
    var t = 0

    for i in 0..<xs.len
        t += xs[i]

    t

val n = total:
    1
    2
    3

print(n)
```

```output
6
```

The block is a sequence of expressions, one per line, and **what it becomes is decided by the
parameter it fills**:

| the parameter is | the block is |
|---|---|
| a collection — `[]T`, `[N]T` | an **array** whose elements are its lines |
| a callable — `A -> T`, `&Fn(A) -> T`, or a by-name `-> T` | a **closure** whose body is the block |

Nothing else takes one. A block at a parameter that is neither is refused, and the message names
both readings:

```sysl
f(n: int) = n

val x = f:
    1

print(x)
```

```error
a trailing block stands at 'n', which is a 'int'
```

The callable reading is a closure's body rather than a list, so a binding in it is an ordinary
statement and the last line is its value:

```sysl
apply(f: () -> int) -> int = f()

val n = apply:
    val a = 6
    val b = 7
    a * b

print(n)
```

```output
42
```

### The one value it is passed is `it`

A block writes no parameter list, so the callable it stands at is what names one. Where that callable
takes a **single** value, the block binds it as **`it`**.

```sysl
each(xs: []int, f: int -> unit)
    for i in 0..<xs.len
        f(xs[i])

each([1, 2, 3]):
    print(it * 10)
```

```output
10
20
30
```

`it` is an ordinary parameter of the closure the block became, and not a keyword. It is not reserved,
a binding inside the block shadows it, it shadows a name from outside, and a block nested in another
block hides the outer one's:

```sysl
outer(f: &Fn(int) -> int) -> int = f(1)
inner(f: &Fn(int) -> int) -> int = f(2)

val n = outer:
    inner:
        it * 10

print(n)
```

```output
20
```

A block with no use for the value simply never writes it. A callable taking **none** binds nothing at
all, so `it` inside such a block is whatever the surrounding scope already had.

**A block cannot name two.** There is one implicit name and no positional spelling behind it, so a
callable of two parameters or more is written as a closure literal, which names its own:

```sysl
on_drag(f: &Fn(int, int) -> unit) = f(1, 2)

on_drag:
    print(it)
```

```error
binds the one value it is passed as 'it'
```

```sysl
on_drag(f: &Fn(int, int) -> unit) = f(1, 2)

on_drag((dx, dy) -> print(dx + dy))
```

```output
3
```

None of this reaches the **collection** reading. A block filling `[]T` is a list of its lines and
binds nothing, so `it` there is an undefined name like any other:

```sysl
total(xs: []int) -> int = xs[0]

val n = total:
    it

print(n)
```

```error
undefined name 'it'
```

### Where the block stands

**It fills the first parameter no written argument filled.** Where the call writes no names that is
the same rule as "the block is the last argument"; where it does, the block is still the argument
that was not written in the parentheses.

```sysl
total(xs: []int, bonus: int) -> int
    var t = bonus

    for i in 0..<xs.len
        t += xs[i]

    t

val n = total(bonus = 100):
    1
    2

print(n)
```

```output
103
```

A parameter's default does not count as having filled it, so a block still reaches a parameter that
has one — the block was written and the default was not.

### Blocks nest

A call is an expression, so a call carrying a block can be a line of the block above it. This is the
main use of the form: a tree written as indentation rather than as nested brackets.

```sysl
node(name: string, kids: []string) -> string
    var s = name + "("

    for i in 0..<kids.len
        if i > 0 then s += " "

        s += kids[i]

    s + ")"

val tree = node("a"):
    node("b"):
        "c"
        "d"
    "e"

print(tree)
```

```output
a(b(c d) e)
```

### There is no result builder

Swift needs one because a Swift block holds *statements*, so something has to assemble arms of
differing shape into a single value. A sysl block holds **expressions**, and `if` is already an
expression whose arms must agree in type, so a branch is an ordinary line:

```sysl
pick(xs: []int) -> int = xs[0] + xs[1]

val big = true

val n = pick:
    1
    if big then 100 else 2

print(n)
```

```output
101
```

**The limit is loops.** A loop in sysl *is* an expression, so one written as a line of a
collection's block would contribute the single `unit` it evaluates to rather than an element per
iteration. It is refused by name rather than read that way:

```sysl
total(xs: []int) -> int = 0

val n = total:
    for k in 0..<3
        k

print(n)
```

```error
would contribute one element and not one per iteration
```

Build the elements first and pass what you built. For the same reason a **binding** is not a line of
a collection's block either: it declares a name rather than producing an element.

```sysl
total(xs: []int) -> int = 0

val n = total:
    var k = 1
    k

print(n)
```

```error
this one declares a name instead
```

Neither restriction applies to the callable reading, where the block is a body and may hold any
statement.

### A trailing block cannot be written inside brackets

A bracket suspends the off-side rule until it closes, so there is no indentation inside one for a
trailing block to be made of. `print(total:` followed by an indented block is a parse error rather
than a call with a block in it — bind the value first and print the name.

The two forms that *do* open a block inside brackets are `match` and `->`, and
[the layout rules](/reference/lexical/) say why they are the two: each is a token that can only ever
open a block, so a reader always knows whether the line has ended. `:` is not such a token, and a
value built with a trailing block reads more clearly bound to a name first.

## String interpolation

`s"…"` renders each `${…}` hole as `to_string` does and concatenates: `s"a${e}b"` **is**
`"a" + e.to_string() + "b"`,
with no runtime formatting machinery involved. `f"…"` allows a printf specifier after a hole, and
routes that hole through `format` instead, where the analyzer checks the specifier against the
value's type.

```sysl
var name = "world"
var n = 7

print(s"hello ${name}, ${n * 6}")
print(f"[${n}%4d]")
```

```output
hello world, 42
[   7]
```

The embedded source is parsed as an ordinary expression — so a hole may itself interpolate — and as
a source of its own, so a diagnostic inside a hole points into the hole rather than at an unrelated
column of the line the string sits on.

## Operator dispatch

An operator expression is a **trait-method call**, resolved by one rule in every context. For an
operator `⊕` mapped to trait `Op` with method `m`, `a ⊕ b` means `Op::m(a, b)`, and it type-checks
exactly when `a`'s type satisfies `Op` **at `b`'s type**.

What differs between a scalar, a user type and a bounded type parameter is only *where the impl comes
from* — never the rule:

- **A built-in scalar** satisfies the operator traits by a compiler-provided impl, and codegen emits
  the native machine instruction, with no call and no vtable. The membership exists so the type
  system agrees a scalar satisfies `Add`, which lets one be passed where `[T: Add]` is wanted. That
  membership is at **one** argument list — `real` is `Mul[real, real]` — so a block written for a
  scalar at another argument list is reached without disturbing it, which is how a scalar can stand
  on the [left of an operator](/reference/traits/#where-an-impl-may-live).
- **A user type with `impl Op for S`** lowers to the member the impl produced. Overloading an
  operator *is* implementing its trait; there is no separate operator-method syntax.
- **A bounded parameter `[T: Op]`** resolves abstractly at the definition, and monomorphization binds
  it per instantiation.

**The right-hand operand selects the implementation as an argument selects an overload.** One
written at `b`'s own type is meant where there is one; otherwise `b` takes an
[implicit conversion](#which-conversions-are-implicit) to the narrowest right-hand type among the
implementations it widens to, so `c * n` over `impl Mul[real] for Complex` takes an `int` `n` exactly
as `c.mul(n)` does, and `c *= n` does the same. A bare literal settles as a literal argument does
([Overloading](/reference/declarations/#overloading)): an integer literal prefers the narrowest signed
integer at least as wide as `int`, and otherwise the implementation at the literal's default type is
meant. Where that leaves more than one, the operator is refused exactly as `a.m(b)` is. A built-in
scalar's operator has nothing to choose among, and its operands keep their own rule: an operand
widens only to the other operand's type.

```sysl
struct Complex
    re: real
    im: real

impl Mul[real] for Complex
    mul(self, k: real) -> Complex = Complex(self.re * k, self.im * k)

val n = 3
var c = Complex(1.5, -2) * n

c *= n

print(c.re, c.im)
```

```output
13.5 -18
```

Two implementations neither of which is the narrower leave the operand saying nothing about which is
meant. On a 64-bit target `isize` and `i64` each widen to the other, so an `int` reaches both
equally, and `s * n`, `s *= n` and `s.mul(n)` are all refused; an operand at either type exactly
still means that one:

```sysl
struct Scale
    k: int

impl Mul[isize, string] for Scale
    mul(self, n: isize) -> string = "isize"

impl Mul[i64, string] for Scale
    mul(self, n: i64) -> string = "i64"

val n = 2

print(Scale(1) * n)
```

```error
'*' between Scale and int comes from 2 implementations of one trait on Scale, and the operands do not say which was meant
```

**A scalar on the left is chosen by the same rule, read from the other side.** Where the right
operand's type has no machine instruction, the candidates are the scalars with an implementation at
that type — `impl Mul[Point, Point] for real` makes `real` one. A bare literal on the left is read at
whichever of them it fits, exactly for a float, and settles as a literal argument does: the
implementation at its default type, `int` or `real`, where there is one; then an integer literal's
signed widening; then the narrowest. A typed scalar on the left widens to the narrowest of them it
reaches without loss, as a right operand does. A literal or a type that reaches none keeps the
matching-types refusal, and two that the rule cannot choose between are refused as ambiguous:

```sysl
struct Point
    x: real
    y: real

impl Mul[Point, Point] for real
    mul(self, p: Point) -> Point = Point(self * p.x, self * p.y)

val p = Point(1, 2)
val k = 3

print(2 * p, -0.5 * p, k * p)
```

```output
Point(x: 2, y: 4) Point(x: -0.5, y: -1) Point(x: 3, y: 6)
```

A `long` holds values a `real` cannot represent exactly, so it does not widen to one, and `n * p`
names no implementation:

```sysl
struct Point
    x: real
    y: real

impl Mul[Point, Point] for real
    mul(self, p: Point) -> Point = Point(self * p.x, self * p.y)

val n: long = 2

print(n * Point(1, 2))
```

```error
'*' needs matching types, got long and Point
```

| trait | method | operator |
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
| `Eq` | `eq` | `==`, `!=` |
| `Ord` | `lt` | `<`, `>`, `<=`, `>=` |
| `Index` | `index` | `e[i]` read |
| `IndexSet` | `index_set` | `e[i] = v` |

The two prefix rows take no result parameter: negation and complement are closed operations on the
type they are written for. `Eq` and `Ord` stay homogeneous for a different reason: a comparison
across two types would have no defined promise about reflexivity and transitivity.

**Two more traits sit beside these and have no operator**: [`Zero`](/library/math/) and `One`, each
declaring a single member with no receiver — `zero() -> Self` and `one() -> Self`. They are here
rather than in `sysl.math` because they are the identities *of these operators*, and because a trait
outside the standard module is reachable only where a file names it: a generic body writing
`T.zero()` should need no import, and `real.zero()` should work whether or not the caller imported a
trait. What a bound gains from them is the one thing an operator cannot supply — a **value** — so
`[T: Add + Zero]` can start an accumulation where `[T: Add]` has to be handed one.

**Every integer type is a member of both, with no `impl` block written for it.** `sysl.ops` writes an
`impl` for `real` and one for `f32`, and it cannot write one for the integers: `iN` and `uN` are an
[open family](/reference/types/), so a program may name a `u256` no library file ever listed. That
membership is the compiler's, exactly as `Signed`'s and `Bits`' are — and unlike those, the member
it supplies has **no receiver**, so what it lowers to is stated rather than read off a value.
`T.zero()` at an integer is the literal `0`, and the accumulator below costs no call at all.

```sysl
sum[T: Add + Zero](xs: []const T) -> T
    var total = T.zero()

    for i in 0..<xs.len do total += xs[i]

    total

var none: []int = []

print(sum([1, 2, 3]), sum(none), sum([1.5, 2.5]) == 4)
```

```output
6 0 true
```

**A constrained subtype is deliberately not a member, the one place where a value and an operation
are treated differently.** [A derivation](/reference/errors/) gives a subtype every operation its
base has, because narrowing which values a type holds does not narrow what can be done with them. An
identity is a value, not an operation: a range written to exclude zero does not contain one, so the
membership stops at the integer types themselves. A **vector** is left out for a related reason — `<4>i32` has four lanes and
`zero()` names none of them, where every operator above is the lane's operation happening four times
at once.

```sysl
type Age = int within 0..150

first[T: Zero](x: T) -> T = T.zero()

var a: Age = 30

print(first(a))
```

```error
'first' requires its type parameter 'T' to implement 'Zero', but Age does not
```

```sysl
struct Vec2
    x: int
    y: int

impl Add for Vec2
    add(self, rhs: Vec2) -> Vec2 = Vec2(self.x + rhs.x, self.y + rhs.y)

var v = Vec2(1, 2) + Vec2(10, 20)

print(v.x, v.y)
```

```output
11 22
```

### The operand and the result are both trait arguments

Both parameters default to `Self`, so the homogeneous reading is what every arithmetic trait means
where nothing says otherwise: `impl Mul for Point` is `impl Mul[Point, Point] for Point`, and
`[T: Mul]` asks for `Mul[T, T]`. **Writing an argument asks for something else**, and one trait
covers four readings:

| written | means | what it says |
|---|---|---|
| `impl Mul for V` | `Mul[V, V]` | homogeneous — `V * V -> V` |
| `impl Mul[real] for V` | `Mul[real, V]` | scaling — `V * real -> V` |
| `impl Mul[V, real] for V` | as written | a dot product — `V * V -> real` |
| `impl Mul[V, V] for M` | as written | a transform — `M * V -> V` |

**No associated type is involved, by design.** sysl has
[associated types](/reference/traits/#a-trait-may-declare-an-associated-type), and the operator
traits deliberately do not use one: an associated type is **one per type**, so `Out` as an associated
type would let a `Vec2` have exactly one multiplication. Rust's `Add` carries an associated `Output`
and needs a separate implementation per operand type; here the result is an ordinary trait argument
defaulting to `Self`, so one trait covers all four readings above — the same mechanism
[`Index`](#the-postfix-tail) uses to carry the element type of what it reads. A vector space needs
three of the four readings at once, and they are three ordinary blocks:

```sysl
struct Vec2
    x: real
    y: real

struct Mat2
    a: real
    b: real
    c: real
    d: real

impl Mul[Vec2, real] for Vec2
    mul(self, o: Vec2) -> real = self.x * o.x + self.y * o.y

impl Mul[real] for Vec2
    mul(self, k: real) -> Vec2 = Vec2(self.x * k, self.y * k)

impl Mul[Vec2, Vec2] for Mat2
    mul(self, v: Vec2) -> Vec2 = Vec2(self.a * v.x + self.b * v.y, self.c * v.x + self.d * v.y)

var v = Vec2(1, 2)
var scaled = v * 3
var turned = Mat2(0, -1, 1, 0) * v

print(v * v, scaled.x, turned.x, turned.y)
```

```output
5 3 -2 1
```

**A result is not a selector.** A use writes the operands and never the result — `a * b` asks to be
*told* what comes back — so the operands choose the implementation and the implementation supplies
the result. Two implementations agreeing on the operands and differing only in what they give back
are therefore refused where they are written, rather than ranked at the use:

```sysl
struct V
    x: real

impl Mul[V, real] for V
    mul(self, o: V) -> real = self.x * o.x

impl Mul[V, V] for V
    mul(self, o: V) -> V = V(self.x * o.x)

print(1)
```

```error
differs only in what it gives back
```

**A compound assignment stays homogeneous**, so a result that is not the left operand's type cannot
be used there: `a op= b` is `a = a op b`, and the result could not be assigned back.

```sysl
struct V
    x: real

impl Mul[V, real] for V
    mul(self, o: V) -> real = self.x * o.x

var v = V(3)

v *= V(4)

print(v.x)
```

```error
'*=' updates V in place, but '*' between V and V gives real
```

Making the result an argument has one further effect: an operator trait at **written** arguments has no
`Self` left in its signature — `Mul[real, real]` declares `mul(self, rhs: real) -> real` — so it is
object-safe, and `&Mul[real, real]` is a formable [trait object](/reference/traits/#trait-objects)
over types with quite different multiplications. Written bare it is `Mul[Self, Self]` and still is
not.

#### At a generic subject

A generic block writes its **own parameters** as trait arguments, and that is the same reading rather
than an exception to it: the block's parameters are exactly the arguments of the type it is written
for, so an argument built out of them says one thing per instantiation and the subject settles it.
The vector space above is therefore written once over an element type:

```sysl
struct Vec2[T]
    a: T
    b: T

impl[T: Mul + Add] Mul[Vec2[T], T] for Vec2[T]
    mul(self, rhs: Vec2[T]) -> T = self.a * rhs.a + self.b * rhs.b

impl[T: Mul] Mul[T] for Vec2[T]
    mul(self, rhs: T) -> Vec2[T] = Vec2(self.a * rhs, self.b * rhs)

var f = Vec2(1.0, 2.0) * Vec2(3.0, 4.0)
var n = Vec2(1, 2) * Vec2(3, 4)
var s = Vec2(1, 2) * 3

print(f, n, s.a, s.b)
```

```output
11 11 3 6
```

The dot product is the reading with **no other spelling**: trait arguments are positional, so
reaching `Out` means writing `Rhs`, and `Rhs` on a dot product is the subject itself. There is no
`Mul[Out = T]`.

What is refused is an argument naming **one instantiation** of the subject. That one would promise at
that instantiation what a defaulted block promises there, and promise something different everywhere
else — a choice between implementations rather than a lookup:

```sysl
struct Box[T]
    v: T

impl[T] Mul[Box[int]] for Box[T]
    mul(self, rhs: Box[int]) -> Box[T] = self

print(1)
```

```error
whose arguments default names the type it is written for
```

A genuine duplicate on a generic subject is caught by the rules above: a second block at
the same operands by [one implementation per argument
list](/reference/traits/#one-implementation-per-argument-list), and one differing only in its result
by the rule that a result is not a selector.

A type becomes fully comparable by implementing **one** method, `lt`, and fully equatable by
implementing **one**, `eq` — the compiler derives the rest: `a != b` is `!eq(a, b)`, `a > b` is
`lt(b, a)`, `a <= b` is `!lt(b, a)`, `a >= b` is `!lt(a, b)`.

Two of those **swap their operands**, and the swap is of the two *values*, applied at the call — so
`a > b` still evaluates `a` before `b`, and the derivation affects neither evaluation order nor the
answer.

`Eq` and `Ord` are **independent** traits, not a hierarchy, matching the scalars: `bool` and the
pointer modes have `==` and no `<`. There is no four-way `PartialEq`/`Eq`/`PartialOrd`
/`Ord` tower.

The scalars do not go through those derivations — the compiler-provided impls supply all six
comparisons directly at IEEE semantics, which keeps `NaN <= 1.0` and `NaN >= 1.0` both false where
negating `lt` would make one of them true.

### A simple enum is `Eq`, and nothing else

An enum whose variants all carry nothing is a **simple** enum, and its value *is* its discriminant.
Equality on it can mean only one thing, so the compiler supplies it — the same rule that makes every
width of integer `Eq` without a block written per width:

```sysl
enum Colorspace
    Srgb
    Linear

same[T: Eq](a: T, b: T) -> bool = a == b

print(Srgb == Srgb, Srgb == Linear)
print(same(Linear, Linear))
```

```output
true false
true
```

The membership satisfies an `Eq` **bound**, as the second line shows, and not merely the `==` token —
so a simple enum goes into anything written over `[T: Eq]`.

It is **not** `Ord`. Declaration order is an order but not a *meaning*: `Srgb < Linear` asserts
nothing a program should rely on by default, so an enum whose order means something writes the
`impl` that says so. It is not `Hash` either, for a similar reason: a hash is a promise about a
distribution, and a program makes it deliberately.

An enum that **carries data** is not a member. Comparing two of those means comparing their payloads,
which needs every payload type to be `Eq` itself; that is an `impl` a program writes, and
[the core module](/library/core/) shows the shape.

Writing the block by hand for a simple one is refused rather than ignored, because the comparison is
emitted whatever the block says:

```sysl
enum Colorspace
    Srgb
    Linear

impl Eq for Colorspace
    eq(self, rhs: Colorspace) -> bool = int(self) == int(rhs)

print(Srgb == Srgb)
```

```error
'Colorspace' already implements 'Eq' — no variant of it carries anything, so its value is its discriminant and '==' is that comparison. Delete the block; a variant that needs an equality of its own has to carry something for it to be about
```

### Why the token set is closed

Overloading a fixed set is common ground among systems languages: Rust, C++, D and Ada all allow it,
Zig and C allow no overloading at all, and **none** permits a new operator token. Low-level code is
read while reasoning about hardware, and an unfamiliar operator that is really a user function
works against that — the same visible-cost principle the memory model rests on. A closed set also
lexes by longest match against a fixed list, with no operator registration for the parser to keep
in step.

---

Next: [statements and control flow](/reference/statements/).
