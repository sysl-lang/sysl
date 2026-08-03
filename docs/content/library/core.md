---
title: The core module
summary: "`sysl` itself — the names every program has without asking: rendering, hashing, subscripting, iteration, callables, and the two ways a program stops."
weight: 10
---

`sysl` is the one module a program does not import. Every other module in this section — `sysl.buf`,
`sysl.text`, `sysl.io` and the rest — is an offer, reached by name or by an `import`. The core is
what arrives unasked-for, and the reason it does is narrow: **a program cannot avoid needing what the
language desugars onto.** `print(x)` is a call to a library function. `?` unwraps a library enum.
`a + b` is a library trait's method. A language whose own forms reach names a program had to import
first would be a language with a required import, which is a worse thing than an auto-imported
module.

So the rule for what belongs here is not "useful" — a growable sequence is useful and lives in
`sysl.buf`. It is **reached by the language itself**, or so close to that as to make no difference.

```sysl
var maybe: Option[int] = Some(3)

print(maybe.unwrap_or(0), "— and not one import above this line")
```

```output
3 — and not one import above this line
```

## What is in it

| area | names | where it is written up |
|---|---|---|
| absence and failure | `Option`, `Result`, `Fallible` | [errors and contracts](/reference/errors/) |
| stopping | `panic`, `assert`, `exit` | below, and [attributes](/reference/attributes/) for `#test` |
| rendering to standard output | `print`-family: `prints`, `printi`, `printu`, `printr`, `printb`, `printc`, `putbytes`; the sink itself, `Stdout` and `stdout` | below |
| rendering to a sink | `Display`, `FormatSpec`, `Writer`, the `display_*` family | below |
| hashing | `Hash`, `hash_u64`, `hash_u128`, `hash_bool`, `hash_str` | below |
| operators | `Add`, `Sub`, `Mul`, `Div`, `Rem`, `BitAnd`, `BitOr`, `BitXor`, `Shl`, `Shr`, `Neg`, `Not`, `Eq`, `Ord` | [expressions](/reference/expressions/) |
| subscripting and walking | `Index`, `IndexSet`, `Iterate` | below |
| calling | `Fn0` … `Fn4` | below |

**None of it is a language feature.** `Option` is a generic enum, `Display` is a trait, `panic` is a
function that prints and exits. What the compiler knows is a short list of *names* — it asks
`Option` for its variants when it lowers a `?`, and it asks the `print` family which function renders
an `int` — and it implements none of the behaviour behind them. Rewriting `printi` is something a
program can do; rewriting `?` is not.

## Stopping the program

Two functions stop a program on purpose, and both are ordinary sysl:

```sysl
panic(msg: string) -> never
    print("panic:", msg)
    exit(1)

assert(cond: bool, msg: string)
    if !cond then panic(msg)
```

`panic` returns [`never`](/reference/types/), so a call to it is an expression of any type — which
is what lets it sit in one arm of a `match` whose other arms produce values, exactly as `unwrap`
does.

**The message on `assert` is required rather than defaulted, and that is a deliberate cost.** The
condition's *source text* is not available to print: sysl has no `#cond` stringizer and no macro that
could capture one, so an assert with no message could only ever say "assertion failed" — which sends
its reader looking through the file for which one. Being made to write the message is being made to
say what you believed.

```sysl
divide(a: int, b: int) -> int
    assert(b != 0, "divide called with a zero divisor")
    a / b

print(divide(84, 2))
```

```output
42
```

**These are the *runtime* half of the language's checking, and they are not the same half as
[contracts](/reference/errors/).** A `require` clause is a promise about a **call**, checked where
the call arrives. `assert` is a promise about a **moment**, checked where the moment is. A function
whose fifth statement has just computed something it can verify has no contract to hang that on —
the contract was about the arguments, four statements ago.

They also stop the program *differently* from the checks the compiler inserts, and the difference is
visible:

| stopped by | what you see | exit status |
|---|---|---|
| a compiler-inserted check (a bounds test, a broken contract) | nothing at all — a trap instruction, and buffered output is lost | the platform's signal status |
| `panic`, `assert`, `unwrap`, `expect` | `panic: <message>` on stdout, after everything printed before it | 1 |

That is not an inconsistency. A trap is for a check the *language* makes, where the source line
already says everything a message could; `panic` is for a check a *program* makes, about something it
knows and the compiler does not, where the message is the entire point. The full account is on
[errors and contracts](/reference/errors/).

`exit` is the third name here and the only `extern` the core offers rather than keeps:

```sysl
extern exit(code: int) -> never
```

It takes no link name where the four platform externs in [`sysl.sys`](/library/sys/) all take one,
because it is not a platform detail — it is the hosted exit, the thing `panic` and `unwrap` stop
with, and a program stopping itself writes exactly the same call.

## Rendering to standard output

`print(a, b, c)` is **a desugaring, not a variadic function**. Each argument is looked at
individually, its static type chooses a one-argument renderer, and the call becomes that sequence of
calls with a space between and a newline at the end.

| static type | renderer | what it does |
|---|---|---|
| `string` | `prints` | the bytes, as they are |
| any signed integer | `printi` | through `snprintf`'s `%lld` |
| any unsigned integer | `printu` | through `snprintf`'s `%llu` |
| `real`, `f32` | `printr` | through `snprintf`'s `%g` |
| `bool` | `printb` | `true` or `false` |
| `char` | `printc` | UTF-8 encoded in sysl, not by C |
| anything else | its `Display` | via `str`, which renders into a buffer |

```sysl
print(42, 3.5, true, 'é', "text", 7u64)
print(1.0 / 3.0)
```

```output
42 3.5 true é text 7
0.333333
```

`0.333333` rather than `0.3333333333333333` because `%g` is six significant digits by default —
which is C's default and stays C's, since the whole of `printr` is one `snprintf` call.

The renderers are ordinary functions and a program may call them directly. Nothing separates them,
so this is where the space between `print`'s arguments visibly comes from — there isn't one:

```sysl
printi(-7)
prints("|")
printr(2.5)
prints("|")
printb(false)
prints("|")
printc('é')
prints("|")
printu(9u64)
putbytes("|end\n".bytes)
```

```output
-7|2.5|false|é|9|end
```

### Everything goes through one sink, and that is not incidental

`putbytes` is the bottom of the whole surface. Every function above writes through it, including the
ones that went to `snprintf` for their digits — they format into a stack buffer and then hand the
bytes here.

Two mechanisms would mean two buffers, and output emerging in an order the program did not write.
That is the entire reason for the shape.

It also writes **a byte at a time**, which looks like a mistake and is not. A sysl `string` may hold
an interior NUL, and every shortcut through C stops at one: `puts`, `%s`, even `%.*s`. A string that
printed correctly right up until it contained a zero byte is a worse bug than a loop that costs a
call per byte, and the loop is what a target with a real `write` replaces anyway.

```sysl
putbytes(b: []const u8)
    var i = 0usize
    while i < b.len
        sysl_putchar(int(b[i]))
        i += 1usize
end putbytes
```

**`putbytes` is one of exactly two functions a freestanding target has to replace.** Swap its body
for a `write` syscall and [`FdReader.read`](/library/io/)'s for a `read` one, and the entire surface
above both is unchanged — every renderer, every `Display`, every `f"…"` in the program.

## Rendering to a sink

Standard output is one destination. A value that knows how to render itself renders into a
**`Writer`** instead, and that is what `Display` is:

```sysl
struct FormatSpec
    width: int
    prec: int
    left: bool

trait Display
    display(self, out: *Writer, fmt: FormatSpec)

trait Writer: Fallible
    write(*self, bytes: []const u8)
```

Three decisions are packed into those six lines.

**A value writes rather than returns.** `display` produces no `string`, so rendering costs no
allocation and a module under [`no alloc`](/reference/modules/) can still log. `str(x)` is then not a
separate mechanism — it is this same rendering aimed at a buffer.

**The sink is `*Writer`, a trait object.** So a rendering is written once against any sink, and a
kernel that has a UART and no standard output supplies one with an ordinary `impl`.

Standard output itself is one of those ordinary impls, and the library declares it — `Stdout`, a
struct with no fields, because a destination fixed at compile time keeps nothing. `stdout()` hands
one out, and it is what `print` writes a value's own rendering into:

```sysl
struct Marked
    n: int

impl Display for Marked
    display(self, out: *Writer, fmt: FormatSpec)
        display_str("<", out, fmt)
        display_int(long(self.n), out, fmt)
        display_str(">", out, fmt)

show(where: *Writer, m: Marked)
    m.display(where, FormatSpec(0, -1, false))
    where.write("\n".bytes)

var mine = Stdout()

show(stdout(), Marked(1))
show(&mine, Marked(2))

print(Marked(3))
```

```output
<1>
<2>
<3>
```

That the destination is a **value** rather than a fixed global is what lets `show` take it as a
parameter. Nothing above is privileged: a program pointing `show` at a UART writes another
`impl Writer` and passes that instead.

**`Writer` requires `Fallible`** rather than declaring a `failed` of its own, because
[`Reader`](/library/io/) needs the same question answered and an open file is both. Two traits may
each declare a member of one name — a call says which by naming the trait — but `failed` takes no
arguments, so at a call on a file there would be nothing to say which was meant. One required trait
makes the question go away rather than move it. `Fallible` is on [errors and
contracts](/reference/errors/), with the reason a stream **latches** instead of returning.

### The `display_*` family

The built-in types have no `impl` block anywhere — there is no `int.display` to call, because the
`iN`/`uN` families are open and no finite list of impls could cover them. What they have instead is
this family, one function per shape, which the compiler routes a scalar's `display` to:

| function | renders |
|---|---|
| `display_str` | a `string`; precision **truncates** |
| `display_int`, `display_uint` | an integer; precision is a **minimum digit count**, zero-filled |
| `display_wide` | an integer **above 64 bits**, whose digits `snprintf` cannot produce |
| `display_real` | a float; precision is **significant digits**, defaulting to 6 and capped at 40 |
| `display_bool`, `display_char` | `true`/`false`, and a code point encoded to UTF-8 |
| `display_pad` | **where they all end up** — puts finished bytes in the field the spec asked for |
| `display_fill` | writes one byte *n* times, in 16-byte runs off a stack buffer |

A scalar reaches its own through a method call, so a `Display` written for a struct can render its
fields without leaving the allocation-free path:

```sysl
import sysl.buf.byte_sink

var sink = byte_sink()
var out: *Writer = &sink

42.display(out, FormatSpec(6, -1, false))
"x".display(out, FormatSpec(0, -1, false))

putbytes(sink.text())
prints("\n")
```

```output
    42x
```

`FormatSpec(6, -1, false)` is a width of six, **no precision** — which is what `-1` means throughout
the family — and padding on the left. The neutral spec, which is what a plain `print` passes, is
`FormatSpec(0, -1, false)`.

### One padder, and why

Every renderer finishes by handing its bytes to `display_pad`. Six renderers each growing their own
padding would be six chances for `%8s` to mean something slightly different, and the drift would be
invisible until someone lined two columns up.

```sysl
import sysl.buf.byte_sink

var sink = byte_sink()
var out: *Writer = &sink

display_int(42, out, FormatSpec(8, -1, false))
out.write("|".bytes)
display_int(-42, out, FormatSpec(0, 5, false))
out.write("|".bytes)
display_str("héllo", out, FormatSpec(0, 2, false))
out.write("|".bytes)
display_real(1.0 / 3.0, out, FormatSpec(0, 3, false))
out.write("|".bytes)
display_bool(true, out, FormatSpec(8, -1, true))
out.write("|".bytes)
display_char('é', out, FormatSpec(4, -1, false))
out.write("|".bytes)
display_uint(7u64, out, FormatSpec(0, 3, false))

putbytes(sink.text())
prints("\n")
```

```output
      42|-00042|h|0.333|true    |  é|007
```

Read that line slowly, because four rules are visible in it.

**Precision means a different thing per renderer, and matches printf's meaning under the
corresponding conversion.** `-00042` is `%.5d`: five digits minimum, zero-filled, and the sign is
written *before* the zeros rather than being counted among them. `0.333` is `%.3g`: three
significant digits. `007` is the unsigned form of the first. `h` is `%.2s`: truncation.

**Width and precision count bytes, exactly as C's do.** `display_str("héllo", …)` with a precision of
2 was asked for two *bytes*, and `é` is two bytes of which only the first fits — so the answer is
`h`. It backs off to a character boundary rather than handing the sink half of a code point, which is
the one place this family refines C rather than copying it: `%.2s` in C would emit the lone lead
byte.

The same byte-counting is why `é` in a field of four came out with **two** spaces and not three. A
column-aligned table of non-ASCII text is therefore not something `width` gives you for free —
counting display columns is a different problem (combining marks, wide CJK cells) and the library
does not pretend to solve it.

**`left` is the whole of justification.** There is no centring, and no fill character other than a
space for the field or a zero for the digits.

### Writing `Display` for your own type

```sysl
struct Point
    x: int
    y: int

impl Display for Point
    display(self, out: *Writer, fmt: FormatSpec) =
        display_pad(("(" + str(self.x) + ", " + str(self.y) + ")").bytes, out, fmt)

var p = Point(3, 4)

print(p)
print(f"[${p}%10s]")
print(f"[${p}%-10s]")
```

```output
(3, 4)
[    (3, 4)]
[(3, 4)    ]
```

**The parts are gathered before anything is padded, and that is the rule rather than the style.** A
specifier describes the field the *whole value* occupies — so `%10s` on a point pads the point, not
its first number. An implementation that rendered parts and forwarded `fmt` down to each of them
would pad the `3` to ten columns and then the `4`, which is not what anybody asking for `%10s` meant.

Forwarding `fmt` straight down is right in exactly one case: when the part being rendered **is** the
whole rendering, as for a wrapper around a single field.

A type with no `Display` cannot be printed, and the diagnostic says what to write:

```sysl
struct Scale
    k: int

print(Scale(3))
```

```error
cannot print a Scale value — write an 'impl sysl.Display for Scale' to say how it renders
```

## Hashing

```sysl
trait Hash
    hash(self) -> u64
```

**A `u64` rather than a `usize`**, so a hash means the same thing on every target — a table moved
between a 32-bit and a 64-bit machine does not rebucket because the word size changed.

As with rendering, the built-ins have no `impl` and reach a named mixer instead. Those mixers are
public, which is what lets a `Hash` written for a struct mix its own fields:

| mixer | for | what it is |
|---|---|---|
| `hash_u64` | every integer up to 64 bits | splitmix64's finalizer |
| `hash_u128` | every integer above 64 bits | the two halves mixed separately, then combined |
| `hash_bool` | `bool` | `hash_u64` of 1 or 0 |
| `hash_str` | `string` | FNV-1a over the bytes |

**A value wider than 128 bits is truncated to 128 before it reaches `hash_u128`.** That keeps the law
a hash owes — equal values hash equal — and gives up only collision resistance among values that
agree in their low 128 bits. If something ever keys a table on values that wide, mixing in 128-bit
chunks is the fix.

**`hash_u64` is a finalizer and not the identity for a reason.** Consecutive integers are the input a
hash table actually meets — loop counters, row ids, sizes — and an identity hash makes them collide
in a row rather than spreading them. A mixer costs three multiplies and turns a counter into
something a table can bucket on.

A `bool` gets its own mixer because one bit does not widen to a number in this language; a `string`
gets FNV-1a over its bytes because a `string` **is** a validated `[]u8`, so there is nothing to
decode and every byte counts once.

```sysl
struct Key
    name: string
    n: int

impl Hash for Key
    hash(self) -> u64 = hash_u64(hash_str(self.name) * 0x100000001b3u64 ^ hash_u64(u64(self.n)))

print(Key("a", 1).hash() != Key("a", 2).hash())
```

```output
true
```

The multiply-then-xor is the shape to copy: `0x100000001b3` is FNV's prime, and mixing with it before
the xor is what makes **order** matter. A plain xor of the field hashes would give `Key("a", 1)` and
a hypothetical `Key` with the fields swapped the same bucket.

## Subscripting a type of your own

A built-in array or slice is indexed by walking to an address, and that is the compiler's business.
These two traits are what `b[i]` means when the receiver is a type somebody wrote:

```sysl
trait Index[I, E]
    index(self, i: I) -> E

trait IndexSet[I, E]
    index_set(*self, i: I, v: E)
```

Reading and writing are **separate traits** because a type may offer one without the other — a view
that computes its elements has an `Index` and no `IndexSet` to give.

```sysl
struct Grid
    cells: [9]int

impl Index[int, int] for Grid
    index(self, i: int) -> int = self.cells[i]

impl IndexSet[int, int] for Grid
    index_set(*self, i: int, v: int)
        self.cells[i] = v

var g: Grid

g[3] = 7

print(g[3], g[0])
```

```output
7 0
```

**The difference from a built-in subscript is not cosmetic: reading through `Index` is a call, so it
yields a *value* rather than a place.** That is why compound assignment through one is refused rather
than quietly expanding:

```sysl
struct Grid
    cells: [9]int

impl Index[int, int] for Grid
    index(self, i: int) -> int = self.cells[i]

impl IndexSet[int, int] for Grid
    index_set(*self, i: int, v: int)
        self.cells[i] = v

var g: Grid

g[0] += 1
```

```error
'+=' on an element read through 'sysl.Index' would evaluate the receiver and the index twice — write it out as 'b[i] = b[i] + …'
```

The expansion would be `g.index_set(0, g.index(0) + 1)`, which evaluates `g` twice and the index
expression twice. For a `Grid` that is merely wasteful; for a receiver that is a function call, or an
index that advances a cursor, it is wrong. The refusal makes you write the version whose cost you can
see.

This is the [`sysl.buf`](/library/buf/) `Buf[T]` arrangement too, and there it earns something
specific: subscripting reaches the bounds-checked members rather than the storage, so `b[i]` cannot
read a slot past the count that the backing slice still has.

## Walking a type of your own

```sysl
trait Iterate[E]
    next(*self) -> Option[E]
```

This is what a `for` asks of what it walks, when what it walks is not a range, an array or a slice.

**`next` answers with an `Option`**, so ending and yielding are one question with one answer. A
separate `has_next` would be two, and two questions can disagree — the classic iterator bug is a
`has_next` that says yes and a `next` that then has nothing.

```sysl
struct Countdown
    n: int

impl Iterate[int] for Countdown
    next(*self) -> Option[int]
        if self.n <= 0 then return None
        self.n -= 1
        Some(self.n + 1)

var c = Countdown(3)

for x in c do print(x)
```

```output
3
2
1
```

**The cursor is advanced through `*self`, and a loop iterates a copy of what it was given.** `c` above
is untouched by the walk. That matters for a cursor meaning to report something *after* the walk — a
reader's failure latch, say — which is why such a cursor has to borrow what it reports on rather than
own it. [`sysl.io`](/library/io/)'s `lines()` is built that way for exactly this reason.

**A `for` also walks an erased cursor**, since `next` takes `*self` and mentions no `Self` elsewhere
— so `Iterate` has an object, and the loop calls its member through the table. The element type is
whatever the object was erased to, which is why there is nothing to annotate:

```sysl
struct Countdown
    n: int

impl Iterate[int] for Countdown
    next(*self) -> Option[int]
        if self.n <= 0 then return None
        self.n -= 1
        Some(self.n + 1)

var it: &Iterate[int] = Countdown(3)

for x in it do print(x)
```

```output
3
2
1
```

That is the same rule that lets a trait object satisfy a bound — a `for` asks what may be *called* on
the value, and a table is an answer to that. It means a function may hand back a cursor without
saying which one it built.

## Calling a value

```sysl
trait Fn0[R]
    call(*self) -> R

trait Fn1[A, R]
    call(*self, a: A) -> R

trait Fn2[A, B, R]
    call(*self, a: A, b: B) -> R
```

…and `Fn3`, `Fn4`. **One trait per arity**, because a call's argument types are part of what it
promises and there is no way to write that variadically.

`call` takes `*self` so a callable may carry mutable state, which is what makes a counter writable:

```sysl
struct Counter
    n: int

impl Fn0[int] for Counter
    call(*self) -> int
        self.n += 1
        self.n

var c = Counter(0)

print(c.call(), c.call(), c.call())
```

```output
1 2 3
```

**A function type `A -> R` is `Fn1[A, R]` at the use**, so a hand-written callable goes wherever a
closure goes — the parameter does not know or care which it got:

```sysl
struct Scale
    k: int

impl Fn1[int, int] for Scale
    call(*self, a: int) -> int = a * self.k

apply(f: int -> int, x: int) -> int = f(x)

var s = Scale(3)

print(apply(s, 5), apply(n -> n + 1, 5))
```

```output
15 6
```

That is worth knowing when a closure is not enough: a struct can have several members, be printed,
be compared, and still be passed as the function.

## Tuples

A tuple is comparable, hashable and printable **exactly when its parts are**. The memberships are
structural, so nothing has to be written per tuple type:

```sysl
print((1, 2), (1, 2, 3))
print((1, 2) < (1, 3), (1, 2) == (1, 2), (1, 2).hash() != (2, 1).hash())
print(f"[${(3, 4)}%10s]")
```

```output
(1, 2) (1, 2, 3)
true true true
[    (3, 4)]
```

`Ord` on a tuple is lexicographic, and is written as a ladder rather than as `<` on each part in
turn, because deciding a position takes **two** comparisons: this one is less, or it is greater, or
the two agree and the next position decides.

**Only arities 2 and 3 are written.** A tuple's arity is part of its type and there is no variadic
`impl` to write, so each one is a block of source; two and three cover what a program reaches for,
and a wider tuple is a struct with field names, which is the better thing to have written anyway.

## Operators are here, and are documented elsewhere

The fourteen operator traits — `Add` through `Ord` — are declared in this module and are covered in
full under [operator dispatch](/reference/expressions/), because which trait a `+` reaches is a rule
about the *operator* rather than about the trait.

Two things about them belong here, where the declarations are:

**Each trait requires exactly one method.** Implementing comparison means writing `lt` and nothing
else — `a > b` is `lt(b, a)`, `a <= b` is `!lt(b, a)` — so there is no way for four functions to
disagree with each other.

**`Eq` and `Ord` are independent, not a hierarchy.** That is the scalar law lifted intact: `bool` and
the pointer modes have `==` and no `<`. Implementing one does not give you the other:

```sysl
struct M
    v: int

impl Ord for M
    lt(self, rhs: M) -> bool = self.v < rhs.v

print(M(1) < M(2))
print(M(1) == M(2))
```

```error
'==' is not defined for M
```

## Everything else is an import away

A name that is *not* in the core is not in scope, and the diagnostic is the ordinary one for an
undefined function rather than anything that hints at a module:

```sysl
var b = byte_sink()
```

```error
undefined function 'byte_sink'
```

That is the auto-import rule doing its job in the direction that matters. Only the standard module
arrives unasked-for; a submodule is an offer, and taking it up is written down where a reader can see
it.

---

Next: [`sysl.text`](/library/text/) — bytes to text, and back.
