---
title: Expressions and operators
summary: The precedence ladder, every operator's meaning, evaluation order, and the traits an operator dispatches through.
weight: 30
---

Almost everything in sysl is an expression. Assignment yields the value assigned, `++` yields a
value, and `if`, `match` and the loops all yield the branch they took. What is *not* an expression is
a short list — a declaration, a multiple assignment, and `defer` — and each is named where it comes
up rather than left to be discovered.

This page is about how expressions are built and what each operator means. Where an expression is a
control-flow form, [statements and control flow](/reference/statements/) has the details of the form
itself.

## Precedence

Loosest at the top, tightest at the bottom. The set is **closed**: there are no user-defined
operator symbols, and no facility to add one.

| Prec | Operators | Role | Associativity |
|---|---|---|---|
| 1 | `=` `+=` `-=` `*=` `/=` `%=` `&=` `\|=` `^=` `<<=` `>>=` | assignment | right |
| 2 | `\|\|` | logical or | left |
| 3 | `&&` | logical and | left |
| 4 | `is`, `is not` | pattern test | non-associative |
| 5 | `==` `!=` `<` `>` `<=` `>=` | comparison | **chained** |
| 6 | `..` `..<` | range | non-associative |
| 7 | `\|` | bitwise or | left |
| 8 | `^` | bitwise xor | left |
| 9 | `&` | bitwise and | left |
| 10 | `+` `-` | add, subtract | left |
| 11 | `*` `/` `%` `<<` `>>` | multiply, divide, remainder, shift | left |
| 12 | `-` `!` `~` `*` `&` `++` `--` | prefix unary | right |
| 13 | `[]` `.` `()` `::` `?` `++` `--` | postfix | left |

`*` and `&` appear at two levels each — prefix at 12 (dereference, address-of) and binary at 11 and 9
(multiply, bitwise and). Position tells them apart, and nothing else has to.

### Two deliberate corrections to C

Every level above matches C except two, and both are cases C is now widely held to have gotten wrong.

**Bitwise binds tighter than comparison.** `x & mask == 0` means `(x & mask) == 0`, which is what it
looks like. In C it means `x & (mask == 0)`, the single most-cited precedence bug in the language.

**Shift binds like multiplication**, at level 11 alongside `* / %`, rather than looser than addition.
A shift *is* a multiply or divide by a power of two, so it belongs with them. This is Go's fix, and a
deliberate divergence from Rust and Zig toward it.

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

Both of those need parentheses in C, Rust and Zig. The payoff in systems code is that
`base + index << shift` and `a << 8 + b` group the way they read.

## Operands

**Both operands of a binary operator have the same type.** There is no implicit promotion anywhere,
so a mixed-width expression is a diagnostic asking for the conversion rather than a silent widening.

```sysl
var a: int = 1
var b: long = 2

print(a + b)
```

```error
'+' needs matching types, got int and long
```

This includes the **shift amount**: in `x << 2` the literal `2` takes `x`'s type by the literal rule,
and shifting by a value of some other type is written `x << u8(k)`.

### What has arithmetic

Arithmetic is defined on the numeric types and nowhere else. `char` has equality and ordering and no
arithmetic at all; `bool` has equality, no ordering, and no arithmetic.

Unary `-` needs a type with a sign, so it is the signed integers and the floats — negating an
unsigned value is written as the subtraction it actually is. Unary `~` is defined on every integer
type, signed or not.

Integer arithmetic **wraps** at the declared width; see [types](/reference/types/). Integer division
by zero traps.

### Equality reaches further than ordering

`==` and `!=` are defined wherever `<` is, and additionally on `bool` and on the two pointer-shaped
modes `*T` and `&T`, which compare by address. **Ordering on an address is not defined** — a bare
address has no meaningful one.

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

### Floats compare by IEEE 754, `NaN` and all

A `NaN` is equal to nothing, itself included. So `==`, `<`, `>`, `<=` and `>=` are **all false** at
one — and `!=` is **true**, because IEEE makes it the negation of `==` rather than a sixth ordered
comparison. Exactly one of the six answers true.

```sysl
var zero = 0.0
var nan = zero / zero

print(nan == nan, nan != nan)
print(nan < 1.0, nan >= 1.0)
```

```output
false true
false false
```

This is also why a float is not hashable: a table assumes a reflexive equality, and one `NaN` breaks
it.

## Logical operators

`&&` and `||` take `bool` and short-circuit; `!` is prefix on a `bool`. Nothing coerces to `bool`, so
there is no integer-as-condition rule and no `if (p)` idiom — write `p != null`.

## Ranges

`a..b` is inclusive, `a..<b` is half-open. Either end may be omitted: `a..`, `..b`, `..<b`, and a
bare `..` meaning the whole thing.

Ranges are **non-associative** and sit below arithmetic and above comparison, which is Swift's
placement — so `0..<n + 1` is `0..<(n + 1)` and needs no parentheses.

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

The spelling deviates from Swift's `...` toward Kotlin's `..` / `..<`, because the two forms are
visually parallel and read as "through" and "up to, less than". It lexes unambiguously: a float
literal needs digits after the `.`, so `1..2` is three tokens and not two.

## `is` — a pattern where a condition is wanted

`x is Pat` tests a value against a pattern and yields a `bool`; `x is not Pat` negates it. The right
side is a full pattern, alternatives (`|`) included — the same grammar a `match` arm's left side
uses, so it is not a second thing to learn.

```sysl
enum Shape
    Circle(r: real)
    Rect(w: real, h: real)

var s = Rect(2.0, 3.0)

if s is Rect(_, _) && s is not Circle(_)
    print("rectangular")
```

```output
rectangular
```

Its level — between `&&` and the comparisons — is what makes `a is P && b > 0` a chain of two terms
rather than an `is` against a conjunction. Both `is` and `not` are **soft** words: neither is
reserved, so both remain usable as ordinary names, and this is the only place either reads as a
keyword.

## Assignment

Assignment is an **expression** yielding the value assigned, lowest precedence and
right-associative.

```sysl
var a = 0
var b = 0
var c = 0

a = b = c = 7

print(a, b, c)
```

```output
7 7 7
```

That is what makes the capture idioms available — `while (c = next()) != 0`,
`if (p = find(k)) != null`. C's classic `if (x = 0)` bug is not caused by this; it is caused by C
additionally letting any integer serve as a truth value. sysl has a distinct `bool` with no such
coercion, so the mistake is already a type error and needs no grammar ban:

```sysl
var x = 1

if x = 0
    print("never")
```

```error
condition must be bool, got int
```

The compound forms `+=`, `-=`, `&=` and the rest are **not separate operators with their own
traits**: `a += b` is defined as `a = a + b` and requires exactly what `+` requires. There is no
`AddAssign`.

### Statement position discards a block's value

A block's value is its trailing expression — but where the block's own value is unused, the block has
**none**, and its type is `unit` whatever the last line yields. Without that rule an `if` whose
branches merely each did something would be forced to make those somethings agree:

```
if c
    full = true          // bool
else
    len += 1             // usize
```

The rule is about the **position**, not the operator: it propagates into the branches of an `if`, the
arms of a `match`, and a loop's `else`. Statement position starts at a statement, at a loop body, and
at the body of a function that returns nothing.

A block that does not *arrive* keeps `never` rather than collapsing to `unit` — that is
reachability, not a value, and the code around it is entitled to know.

### Several places at once

A comma-separated list of places takes a comma-separated list of values. **The right side is
evaluated in full, into temporaries, before any assignment happens** — which is the entire content of
the feature, and what makes the first line below a swap rather than two statements that leave both
variables holding `b`.

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

Compound forms multi-assign too, and need no rule of their own — only the one above, read carefully.
Every place is located, then every place a compound form touches is **read**, then the whole right
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

Both arms saw the values the statement started with. Written as two statements it would not work:
`a += b` followed by `b += a` folds the new `a` into `b`, and nothing on the page says so.

A place's own subexpressions are evaluated **exactly once**, before the assignments — so in
`xs[f()], xs[g()] = xs[g()], xs[f()]` each of `f` and `g` runs once.

**A multiple assignment is a statement, not an expression**, and that is what keeps it small. A
single assignment yields the value assigned; a multiple one would have to yield several, and the only
thing that could be is a tuple — allocating a product type for a form whose whole point is that
several places change at once. So it does not nest, does not appear in a condition, and has no value
to discard. A binding takes the same list: `val a, b = 1, 2`.

An element reached through a user type's `Index` is the one place the accepted set is *smaller* than
a single `=`'s, because `b[i] = v` there is a call rather than a store, and there is nothing to split
into the two halves the ordering rule is about.

## `++` and `--`

Expressions, both prefix and postfix. Prefix yields the new value; postfix yields the old one. This
is what the pointer-walking idioms need — `*p++` only works if `++` is an expression.

```sysl
var i = 5

print(i++, i, ++i, i)
```

```output
5 6 7 7
```

C's `i = i++ + ++i` is undefined because C leaves evaluation order unspecified while allowing
unsequenced mutation of one object. sysl fixes the root cause rather than banning the operator:
**evaluation order is strictly left to right**, everywhere — operands of a binary operator, function
arguments, index expressions. With an order defined, the expression above has one meaning. It is
merely hard to read, which makes it a lint candidate and not a footgun.

Postfix binds tighter than prefix, so `*p++` is `*(p++)` and `-a.b` is `-(a.b)`, exactly as in C.

## The postfix tail

Six things attach to an expression on the right, and they compose left to right.

| tail | what it does |
|---|---|
| `e[i]` | index — an element of an array, a slice, or a type implementing `Index` |
| `e.name` | select a field, or call a method |
| `e.0` | select a tuple's part by position |
| `e(…)` | call |
| `T::Attr` | a type's attribute rather than a value's — a constrained type's bounds live here |
| `e?` | try — unwrap or propagate |
| `e++`, `e--` | post-increment, post-decrement |

A tuple index is a `Field` selection because it *is* one: a tuple's fields are named for their
positions. Note that `t.0.1` does not work, because the lexer reads `0.1` as a float before it is two
indices — write `(t.0).1`, which is what the diagnostic says.

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
cross. And **the error types must match exactly**; there is no implicit widening, so a function with
its own error type converts a callee's explicitly.

`?` is an expression and composes as one, so its unwrapped value flows into whatever surrounds it.

## Conversions are calls

Every conversion is written, with call syntax, and none is inferred — the visible-cost rule the
memory model rests on applies to representation changes too.

| from → to | written | behaviour |
|---|---|---|
| integer → integer | `u16(n)`, `byte(n)` | truncates or extends; sign-extends only when the *source* is signed |
| integer → float | `real(n)`, `f32(n)` | rounds to nearest; signed and unsigned sources differ |
| float → integer | `int(x)` | truncates toward zero |
| float → float | `f32(x)`, `real(x)` | rounds to nearest |
| `char` → integer | `u32(c)` | total — every `char` is an integer |
| integer → `char` | `char(u)` | **partial** — traps on a value that is not a Unicode scalar value |
| `char` → `string` | `string(c)` | total — the one character, UTF-8 encoded into a fresh string |
| `*T` → integer | `usize(p)`, `isize(p)` | total — an address is a number |

```sysl
var n = 300
var c = 'A'

print(byte(n), real(n), u32(c), int(3.9))
```

```output
44 300 65 3
```

Everything else is rejected. There is **no conversion to or from `bool`** — `int(true)` is an error
and so is `bool(0)` — and **no number converts to or from a `string`**: `str(x)` renders one and the
`strconv` surface parses one, neither of them spelled as a conversion. The `char` → `string` row is
the one exception, and a narrow one: a `char` is a single scalar value, so encoding it is total and
has nothing to say about failure.

The pointer row goes only one way. An address *is* a number of `usize`'s width, so reading it as one
loses nothing and produces a value that cannot be dereferenced. The inverse is not a conversion at
all — making a pointer out of an integer is `ptr_cast`, in the raw tier, spelled apart from this
table because it is where the language's guarantees stop.

Because these are calls, they parse as postfix at level 13 rather than as an operator of their own.
The name in front may be a **type parameter**, and then the row is chosen at the instantiation.

## `sizeof` and `alignof`

The two forms whose operand is a **type** rather than a value. Both take parentheses, and both yield
a `usize`.

```sysl
struct Pair
    a: int
    b: byte

print(sizeof(int), alignof(int), sizeof(Pair), alignof(Pair))
```

```output
4 4 8 4
```

They are read as their own grammar rather than left to look like calls, because a call's arguments
are expressions and `sizeof(*Node)` would otherwise parse as a dereference. There is no form that
takes a value — a value's type is what would be measured anyway.

## Closures

`x -> x + 1` is a closure literal. It sits at the top of the expression grammar, so its body extends
as far to the right as an expression can: that is a closure over the sum, not a closure over `x`
added to `1`.

Parameters are one bare name, or a parenthesized list — including the empty list, which is the one
arity with nowhere else to be written. A type annotation goes inside the parentheses and nowhere
else, so there is no second spelling to disagree with the first.

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

The group is what a program reaches for when the other two boundaries fall in the wrong place. But
it cuts both ways, and this is the one thing to know about the form: **the closure closes at the
group, so anything outside the group applies to the closure itself**, not to what it computes.

```sysl
apply(f: int -> int, x: int) -> int = f(x)

print(apply((_ + 1) * 2, 20))
```

```error
this '_' has no type here
```

`(_ + 1) * 2` multiplies a *closure* by two rather than closing over the doubled sum — so nothing is
left to say what the placeholder's parameter is, and the diagnostic points at the `_`. When the
boundary is not where you want it, write the arrow form: `x -> (x + 1) * 2`.

An interpolation hole is a boundary too, so a placeholder cannot reach out of a string to close over
the whole of one.

## String interpolation

`s"…"` renders each `${…}` hole through `str` and concatenates: `s"a${e}b"` **is** `"a" + str(e) + "b"`,
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

The embedded source is parsed as an ordinary expression — so a hole may itself interpolate — and it
is parsed as its own little source, so a diagnostic inside a hole points into the hole rather than at
an unrelated column of the line the string sits on.

## Operator dispatch

An operator expression is a **trait-method call**, resolved by one rule in every context. For an
operator `⊕` mapped to trait `Op` with method `m`, `a ⊕ b` means `Op::m(a, b)`, and it type-checks
exactly when `a`'s type satisfies `Op` **at `b`'s type**.

What differs between a scalar, a user type and a bounded type parameter is only *where the impl comes
from* — never the rule:

- **A built-in scalar** satisfies the operator traits by a compiler-provided impl, and codegen keeps
  emitting the native machine instruction. No call, no vtable. The membership exists so the type
  system agrees a scalar satisfies `Add`, which is what lets one be passed where `[T: Add]` is wanted.
- **A user type with `impl Op for S`** lowers to the member the impl produced. Overloading an
  operator *is* implementing its trait; there is no separate operator-method syntax.
- **A bounded parameter `[T: Op]`** resolves abstractly at the definition, and monomorphization binds
  it per instantiation.

| trait | method | operator |
|---|---|---|
| `Add` | `add` | `+` |
| `Sub` | `sub` | `-` (binary) |
| `Mul` | `mul` | `*` |
| `Div` | `div` | `/` |
| `Rem` | `rem` | `%` |
| `BitAnd` | `bitand` | `&` (binary) |
| `BitOr` | `bitor` | `\|` |
| `BitXor` | `bitxor` | `^` |
| `Shl` | `shl` | `<<` |
| `Shr` | `shr` | `>>` |
| `Neg` | `neg` | `-` (unary) |
| `Not` | `not` | `~` |
| `Eq` | `eq` | `==`, `!=` |
| `Ord` | `lt` | `<`, `>`, `<=`, `>=` |
| `Index` | `index` | `e[i]` read |
| `IndexSet` | `index_set` | `e[i] = v` |

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

A type becomes fully comparable by implementing **one** method, `lt`, and fully equatable by
implementing **one**, `eq` — the compiler derives the rest: `a != b` is `!eq(a, b)`, `a > b` is
`lt(b, a)`, `a <= b` is `!lt(b, a)`, `a >= b` is `!lt(a, b)`.

Two of those **swap their operands**, and the swap is of the two *values*, applied at the call — so
`a > b` still evaluates `a` before `b`, and the derivation is invisible in evaluation order as well
as in the answer.

`Eq` and `Ord` are **independent** traits, not a hierarchy. That is the scalar law lifted intact:
`bool` and the pointer modes have `==` and no `<`. There is no four-way `PartialEq`/`Eq`/`PartialOrd`
/`Ord` tower.

The scalars do not go through those derivations — the compiler-provided impls supply all six
comparisons directly at IEEE semantics, which is what keeps `NaN <= 1.0` and `NaN >= 1.0` both false
where negating `lt` would have made one true.

### Why the token set is closed

Overloading the fixed set is the bare-metal consensus: Rust, C++, D and Ada all allow it, Zig and C
allow no overloading at all, and **none** permits a new operator token. Low-level code is read while
reasoning about hardware, and a mystery operator that is secretly a user function fights that — the
same visible-cost value the memory model rests on. A closed set also lexes by longest match against a
fixed list, with no operator "muncher" and no parser-vocabulary registration to keep in step.

---

Next: [statements and control flow](/reference/statements/).
