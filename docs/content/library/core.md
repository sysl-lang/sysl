---
title: The core module
summary: "`sysl` itself — the names every program has without asking: rendering, hashing, subscripting, iteration, callables, and the two ways a program stops."
weight: 10
---

**Every declaration in `sysl`, with its signature:** [the generated API page](/api/sysl/#index). This page is the argument — what the module is for, and how its pieces fit; that one is the list.

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
| stopping | `panic`, `assert`, `exit` | below, and [attributes](/reference/attributes/) for `@test` — whose `skip(why)` is imported from `sysl.testing` |
| rendering to standard output | `print`-family: `prints`, `printi`, `printu`, `printr`, `printb`, `printc`, `putbytes`, `encode_utf8`; the sink itself, `Stdout` and `stdout` | below |
| rendering to standard error | `eprints`, `eputbytes`; the sink itself, `Stderr` and `stderr` | below |
| rendering to a sink | `Display`, `FormatSpec`, `Writer`, `Counting`, the `display_*` family | below |
| hashing | `Hash`, `hash_u64`, `hash_u128`, `hash_bool`, `hash_str` | below |
| destruction | `Drop` | [the memory model](/reference/memory/) |
| operators | `Add`, `Sub`, `Mul`, `Div`, `Rem`, `BitAnd`, `BitOr`, `BitXor`, `Shl`, `Shr`, `Neg`, `Not`, `Eq`, `Ord` | [expressions](/reference/expressions/) |
| their identities | `Zero`, `One` | [expressions](/reference/expressions/), and [math](/library/math/) |
| conversion between two types | `From` | [errors and contracts](/reference/errors/#a-converts-through-from) |
| a range as a value | `Range` | [expressions](/reference/expressions/), and [seq](/library/seq/) |
| subscripting and walking | `Index`, `IndexSet`, `Iterate` | below |
| calling | `Fn0` … `Fn4` | below |
| the platform | `os`, `cpu`, `cpu_count` | below |

**None of it is a language feature.** `Option` is a generic enum, `Display` is a trait, `panic` is a
function that prints and exits. What the compiler knows is a short list of *names* — it asks
`Option` for its variants when it lowers a `?`, and it asks the `print` family which function renders
an `int` — and it implements none of the behaviour behind them. Rewriting `printi` is something a
program can do; rewriting `?` is not.

## `From` — a value of one type built from a value of another

**`From[T]` is how a type accepts a conversion from a type it does not own.** The parameter is the
**source** and `Self` is the destination, which is the direction that matters: the block is written
where the destination is, so a program's own error type can accept a library's without the library
having heard of it. Written the other way round, the orphan rule would put every conversion in
whichever module lost the argument.

```sysl
struct Celsius
    deg: real

struct Fahrenheit
    deg: real

impl From[Celsius] for Fahrenheit
    from(value: Celsius) -> Fahrenheit = Fahrenheit(value.deg * 9.0 / 5.0 + 32.0)

impl From[int] for Fahrenheit
    from(value: int) -> Fahrenheit = Fahrenheit(real(value))

print(Fahrenheit.from(Celsius(100.0)).deg, Fahrenheit.from(70).deg)
```

```output
212 70
```

A type may implement it **several times over** — the two blocks above are one type accepting two
sources — and a call says which it means by the argument it wrote.

**`?` is the one thing in the language that reads it**, and it is what makes two layers of error type
compose; see [errors](/reference/errors/#a-converts-through-from). Everywhere else it is an ordinary
trait a program declares and calls for itself.

## Stopping the program

Two functions stop a program on purpose, and both are ordinary sysl:

```sysl
panic(msg: string, file: string = __FILE__, line: long = __LINE__) -> never
    prints("panic: ")
    prints(msg)
    prints(" (")
    prints(file)
    prints(":")
    printi(line)
    prints(")\n")
    exit(1)

assert(cond: bool, msg: string = "", file: string = __FILE__, line: long = __LINE__)
    if !cond then
        if msg.len == 0 then panic("assertion failed", file, line)
        else panic(msg, file, line)
```

`panic` returns [`never`](/reference/types/), so a call to it is an expression of any type — which
is what lets it sit in one arm of a `match` whose other arms produce values, exactly as `unwrap`
does.

**The message is optional, because a failure names the line it happened on.** `__FILE__` and
`__LINE__` are [reserved identifiers](/reference/lexical/), and a default is evaluated at the call —
so a parameter defaulted to one reports the *caller's* position rather than the library's. Write a
message where it says something the condition does not; leave it out where the condition speaks for
itself.

Two details of that are worth copying if you write a checking function of your own. `assert` passes
`file` and `line` **on** to `panic` rather than letting `panic` fill its own defaults — otherwise
every assertion in every program would report the line inside the library that calls `panic`. And the
location is composed with `prints` and `printi` instead of an interpolated string, because building a
string makes heap storage, which would put `assert` out of reach of a module that declared
[`@no_alloc`](/reference/attributes/) — the module that wants an assertion most.

```sysl
divide(a: int, b: int) -> int
    assert(b != 0)
    a / b

print(divide(84, 2))
```

```output
42
```

## `assert_eq` — the assertion that says what the values were

`assert(a == b)` names the line, so you know *which* check broke. You then run the program again to
find out what the two values actually were — and running it again is exactly what the report could
have saved you.

```sysl
assert_eq[T: Eq + Display](got: T, want: T, msg: string = "", …)
assert_slice_eq[T: Eq + Display](got: []const T, want: []const T, msg: string = "", …)
```

```sysl
sum(xs: []const int) -> int
    var total = 0

    for x in xs
        total = total + x

    total

assert_eq(sum([1, 2, 3]), 6)
assert_eq(sum([1, 2, 3]), 6, "the running total")
print("both held")
```

```output
both held
```

**The bound is `Eq + Display` and not `Eq` alone**, which is what lets the report say what the two
values were rather than only that they differed — so a type reaches `assert_eq` by rendering as well
as by comparing. Both halves are written for an [`Option` and a `Result`](/reference/errors/), so a
function answering with one is asserted about directly:

```sysl
first_even(xs: []const int) -> Option[int]
    for x in xs
        if x % 2 == 0 then return Some(x)

    None

assert_eq(first_even([1, 4, 6]), Some(4))
assert_eq(first_even([1, 3]), None)
print("both held")
```

```output
both held
```

A failure prints the pair, in the order it is read in — what happened, then what was meant to:

```
panic: got 5, want 6 (main.sysl:9)
panic: the running total: got 5, want 6 (main.sysl:10)
```

**Why this is a function and not advice to write the message yourself.** The hand-written form is
`assert(a == b, s"got $a, want $b")`, and it has two costs: it evaluates each side twice, and it
builds a string. Building one makes heap storage, which puts it out of reach of a module that
declared [`@no_alloc`](/reference/attributes/) — the module that wants an assertion most. Rendering
through [`Display`](/library/core/) into the output costs neither.

**One function rather than one per type.** `Eq` says the comparison means something and `Display`
says the value can be shown, which together are the whole of what a report needs.

`assert_slice_eq` earns a name of its own, and it is worth being exact about what for now that
[a slice is `Eq` and `Display`](#a-slice-or-an-array-of-anything-equatable) and so reaches
`assert_eq` like anything else. Both work; they answer differently:

```
panic: got [1, 2, 4], want [1, 2, 3] (main.sysl:9)
panic: got 4, want 3 at index 2 (main.sysl:9)
```

The first is `assert_eq` — the two sequences. The second is `assert_slice_eq` — where they part.
Reach for `assert_eq` where the sequences are short enough to read side by side, and for
`assert_slice_eq` where they are not: it checks the lengths first — a length mismatch explains every
index after the shorter one — and otherwise names the first index the two disagree at, with both
elements at it:

```
panic: got 2 elements, want 3 (main.sysl:4)
panic: got 2, want 5 at index 1 (main.sysl:9)
```

**Floats use a different pair, in [`sysl.math`](/library/math/).** `==` is the wrong question to ask
about a float, so `assert_approx_eq` and `assert_approx_eq_rel` take a tolerance and are built on
`approx_eq` / `approx_eq_rel`, which is where the right question already lives.

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

### Choosing an exit status

`exit` is the third name here and the only `extern` the core offers rather than keeps:

```sysl
extern exit(code: int) -> never
```

It takes no link name where the four platform externs in [`sysl.sys`](/library/sys/) all take one,
because it is not a platform detail — it is the hosted exit, the thing `panic` and `unwrap` stop
with, and a program stopping itself writes exactly the same call.

**It is how a program chooses its own status, and it prints nothing.** That is the pairing worth
naming, because the two halves are asked for together and are answered from opposite directions:
`main` may answer nothing or a `Result[unit, E]` and [nothing else](/reference/modules/#where-a-program-starts)
— a status is one byte and a return type is a value — and `Err(e)` exits non-zero having *also*
written `error: <e>`, which is a second line a program that has already reported its own failure did
not ask for.

```sysl
print("done")
exit(0)
```

```output
done
```

**Nothing the runtime added** — that is the whole of what the call buys, and the status is the
argument. `exit(3)` is the same line with a different number, and a program that has already printed
its own diagnostic ends that way:

```sysl
print("no such file: notes.txt")
exit(3)
```

The program's own message, the status it chose, and no `error:` line. A CLI that renders a caret and
a line number wants exactly that: a message for a person and a status for the parent process, which
are two different things everywhere else too — C's `exit(3)`, Go's `os.Exit`, Rust's
`std::process::exit`, Python's `sys.exit`.

(The second block is shown rather than run: this page's programs are executed and their output
compared, and one that deliberately exits non-zero would be reported as a failure.)

Its result type is `never`, so the call is an expression that never comes back and a function ending
in one owes no other answer.

## Which machine the program was compiled for

`os()` and `cpu()` answer the platform as a value.

```sysl
print(os() == MacOS || os() == Linux || os() == Windows)
print(cpu() == Aarch64 || cpu() == X86_64 || cpu() == Riscv64 || cpu() == X86)

val leaf = os() match
    MacOS -> "Library/Caches"
    Windows -> "AppData"
    _ -> ".cache"

print(leaf.len > 0)
```

```output
true
true
true
```

`Os` is `MacOS`, `Linux`, `Windows`, `Freestanding`, `Android` and `Wasi`; `Cpu` is `Aarch64`, `X86_64`,
`Riscv64`, `Riscv32`, `Thumb`, `Wasm32`, `X86` and `Craft`. Both derive `Display`, so `str(os())` is
the name it is written with.

**They are constants, not queries.** A sysl program is compiled for exactly one target, so the
answer is something the compiler already holds: there is nothing to look up at run time, and a
freestanding target pays exactly as much for them as a hosted one, which is nothing.

**What they add over [`#if`](/reference/attributes/#if-gating-lines-before-the-lexer) is expression
position.** A `#if` gates *lines*, before the lexer, so a program could already define a different
`val` per platform — what it could not do is compare a platform, pass one to a function, or `match`
on one. That is the whole of the difference, and it is why these exist beside a directive that has
been there all along.

**The two vocabularies are the same words on purpose.** `#if macos` and `os() == MacOS` ask one
question, and `Os` has a `Freestanding` member because a target with no operating system under it is
still a target with an answer. `Android` is its own case rather than a kind of `Linux`: what a
program asking this wants to know is which libc and which conventions it has, and Bionic is neither
glibc's. A program that means either writes `os() == Linux || os() == Android`.

`Wasi` is the same distinction on the other side of the same question: `Wasm32` is the processor, and
both wasm [targets](/getting-started/cli/#targets) answer it — what tells them apart is whether there
is a libc and a host underneath, which is `os()`. A module built for `wasm32-unknown-unknown` answers
`Freestanding`.

`Thumb` rather than `Arm` for the same reason — a Cortex-M executes Thumb and nothing else, and it is
the instruction set that a program gating on a processor has to be right about. A program that wants
an **address width** should ask for one: `sizeof(usize)` is that question, and it is right for a
processor this list has not got yet.

**`cpu_count()` is the one question here that is not about the target.** It answers how many logical
processors are online on the machine the program is *running* on — the number to size a pool of
workers by — so the same binary answers differently on a laptop and a build server. It is never less
than one. It needs an operating system to ask, and says so — a module that has given up `os` is
refused at the call — and where there is no POSIX `sysconf` to ask, a freestanding image or WASI, it
answers one.

```sysl
print(cpu_count() >= 1)
```

```output
true
```

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
print(42, 3.5, true, 'é', "text", 7)
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
printu(9)
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

Where there is a C library it hands the **whole buffer** to stdio's `fwrite` in one call. The call is
*counted* rather than terminated, and that is the point of choosing it: a sysl `string` may hold an
interior NUL, and every other shortcut through C stops at one — `puts`, `%s`, even `%.*s`. It stays on
stdio rather than a raw `write(1, …)` because C in the same program printing with `printf` or
`putchar` shares stdio's buffer, and one queue is what keeps the two in the order they were written.

A freestanding target has no stdio, so there it writes a byte at a time through the `putchar` the
board supplies:

```sysl
putbytes(b: []const u8)
#if hosted
    var at: usize = 0

    while at < b.len
        val k = sysl_stdout_write(&b[at], b.len - at)

        if k == 0 then return

        at += k
#else
    for i in 0..<b.len
        sysl_putchar(int(b[i]))
#endif
```

A short `fwrite` is looped over, and one that wrote nothing ends the loop — the stream has failed, and
there is nowhere left to report that to.

**`putbytes` is one of exactly two functions a freestanding target has to replace.** Swap its body
for a `write` syscall and [`FdReader.read`](/library/io/)'s for a `read` one, and the entire surface
above both is unchanged — every renderer, every `Display`, every `f"…"` in the program.

### `encode_utf8` — a character's bytes, without printing them

```sysl
encode_utf8(ch: char, into: []u8) -> usize
```

`printc` encodes rather than handing the character to `snprintf`, which has no conversion that takes
a code point — and what it uses is this, which any program may call. It writes into storage the
caller owns and answers how many bytes it wrote. No character needs more than four, so a `[4]u8` is
always enough and always on the stack, which is what keeps it usable where there is no allocator.

```sysl
var b: [4]u8

print(encode_utf8('A', b[0..<4]), encode_utf8('é', b[0..<4]), encode_utf8('☃', b[0..<4]))

var n = encode_utf8('é', b[0..<4])

print(b[0], b[1], n)
```

```output
1 2 3
195 169 2
```

**It is here in `sysl` rather than beside `from_utf8` in [`sysl.text`](/library/text/), and that
placement is forced.** A submodule may name the standard module freely and the standard module may
not name back, so an encoder over there would be one `printc` could not call — and the second encoder
that would then be written for it is exactly the hand-rolled copy this function exists to prevent.
The library carried four of them before it had this one.

A slice shorter than four bytes is a mistake in the program rather than a truncation, and the bounds
check says so: how many bytes are needed is not known until the character has been looked at, so
there is no honest answer to "it did not fit" that is cheaper than having room.

### The other stream

A program's *answer* and its *complaints* go to different places, so that a run whose output is being
captured does not have the complaint land in the middle of the answer, and a redirect that keeps the
answer still lets a person see what went wrong. `eprints` writes a string to standard error, and
`stderr()` is the same destination as a `Writer`, for a value rendering itself through its own
`Display`:

```sysl
prints("the answer\n")
eprints("something to say about it\n")
```

```output
the answer
```

The page shows only the first line because only the first line went to standard output — which is the
whole point of the distinction, and is what [`sysl.args`](/library/args/) relies on when it puts a
usage error on one stream and its `--help` on the other.

This half goes through `write` directly rather than a byte at a time, because there is no buffer
between it and the descriptor: a diagnostic written just before a program stops has to have left
before it does, which is the same reason C leaves standard error unbuffered. A short write is looped
over, since a signal may cut one anywhere.

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

### A specifier is the whole value's field

**A `FormatSpec` describes the field the *whole value* occupies, not the field each of its parts
does.** `%8s` on a complex number asks for the complex number in eight columns; it does not ask for
an eight-column real part followed by an eight-column imaginary one.

That is a contract on the implementation rather than something the language enforces, and the
`Marked` above quietly breaks it. It forwards its `fmt` to all three of its `display_*` calls, which
is invisible while the spec is neutral — every rendering on this page so far has passed
`FormatSpec(0, -1, false)` — and is not invisible under a width:

```sysl
import sysl.buf.byte_sink

struct Marked
    n: int

impl Display for Marked
    display(self, out: *Writer, fmt: FormatSpec)
        display_str("<", out, fmt)
        display_int(long(self.n), out, fmt)
        display_str(">", out, fmt)

var sink = byte_sink()
var out: *Writer = &sink

Marked(3).display(out, FormatSpec(6, -1, false))

putbytes(sink.text())
prints("|\n")
```

```output
     <     3     >|
```

Eighteen columns where six were asked for, because each part took the field in turn.

**An implementation that renders more than one part therefore has to know what those parts came to
before it can pad**: render them with a neutral spec, find the width they occupied, pad once with the
spec the caller gave, and render for real. The library supplies two ways to find that width, and they
are not interchangeable.

#### `Counting` — the width without the bytes

`Counting` is a `Writer` that keeps the length of what it is handed and drops it. Running the parts
through one answers *how wide did this come out* and stores nothing, so the rendering costs a second
pass and no allocation — which is what every multi-part `Display` in the library does, `[]T`,
`Option`, `Result` and `Complex[F]` alike. `Marked` written properly:

```sysl
struct Marked
    n: int

render_marked(m: Marked, out: *Writer)
    display_str("<", out, FormatSpec(0, -1, false))
    display_int(long(m.n), out, FormatSpec(0, -1, false))
    display_str(">", out, FormatSpec(0, -1, false))

impl Display for Marked
    display(self, out: *Writer, fmt: FormatSpec)
        var pad = 0

        if fmt.width > 0
            var count = Counting(0)
            var sink: *Writer = &count

            render_marked(self, sink)
            pad = fmt.width - int(count.n)

            if pad < 0 then pad = 0

        if !fmt.left then display_fill(out, 32, pad)

        render_marked(self, out)

        if fmt.left then display_fill(out, 32, pad)
    end display

print(Marked(3))
print(f"[${Marked(3)}%6s]")
print(f"[${Marked(3)}%-6s]")
```

```output
<3>
[   <3>]
[<3>   ]
```

**Note what the parts are handed and what they are not.** `render_marked` gives each piece the
neutral `FormatSpec(0, -1, false)`; `fmt` is read by `display` alone, and only for its width and its
justification. The `if fmt.width > 0` is why an ordinary `print` still costs one pass — nothing is
measured when nobody asked for a field.

#### `ByteSink` — when the bytes themselves are wanted

[`sysl.buf`](/library/buf/) carries `ByteSink`, which keeps what is written into it. That is the
answer when the rendering has to go somewhere that is not a stream — handed back as a `string`,
compared, stored. It costs an allocation, so it is not the one to reach for when all that was needed
was a width.

**A single-part rendering has nothing to gather**, and passing `fmt` straight through is right for
it. `Marked` is a three-part rendering wearing a one-part shape, which is the whole of what makes it
the example rather than the counter-example.

### The `display_*` family

Every rendering the language does ends up in this family, one function per shape:

| function | renders |
|---|---|
| `display_str` | a `string`; precision **truncates** |
| `display_int`, `display_uint` | an integer a caller already holds at **64 bits**; precision is a **minimum digit count**, zero-filled |
| `display_real` | a float; precision is **significant digits**, defaulting to 6 and capped at 40 |
| `display_real_shortest` | a float at the **shortest precision that reads back equal**; ignores the precision |
| `display_bool`, `display_char` | `true`/`false`, and a code point encoded to UTF-8 |
| `display_digits` | **where a number ends up** — reads a sign off the front, zero-fills to the precision, then pads |
| `display_pad` | **where the rest end up** — puts finished bytes in the field the spec asked for |
| `display_fill` | writes one byte *n* times, in 16-byte runs off a stack buffer |

**Every built-in reaches its own through an `impl`, and the two halves get there differently.**
`bool`, `char`, `string`, `real` and `f32` are five types, so each has an ordinary `impl Display` in
the library forwarding to the function above. The `iN`/`uN` families are open — `i5` and `u24` are
types you may write — so no finite list of blocks reaches them; what reaches them is a single
**blanket** block, `impl[T: Integer] Display for T`, whose buffer is measured from the width it is
instantiated at.

Either way they are `Display` exactly the way your own struct is, and **a `*Display` can carry any
of them**. That is what the blanket bought: a `[]*Display` holding an `int`, a `u8`, a `string` and
a float is ordinary code, where an integer used to be the one thing a method table could not hold.

`Integer` is a trait you can write a bound over — `f[T: Integer](x: T)` accepts every width and
nothing else — but not one you can implement. It names which types the compiler settles a family as,
so there is nothing for a block to supply.

`display_int` and `display_uint` remain for a caller who already has a `long` in hand. Nothing in
the library routes through them any more.

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

**The allocation-free path reaches every width.** A rendering works its digits out against a
frame-local buffer and hands the sink a slice of it, which is safe because a `Writer` borrows the
bytes it is given rather than keeping them — so a module declaring `@no_alloc` can render anything,
a `u256` included.

It did not always. The buffer has to be sized for the width, and until an array's length could be
written in terms of a type parameter — `[sizeof(T) * 3 + 2]u8`, three decimal digits per byte being
the bound at every width — the widest values fell back through `str`, which is heap storage. So the
values needing the most care were the ones a module without an allocator could not print.

#### The shortest rendering that survives a round trip

A precision is a count of significant digits, so any fixed one is a choice between losing the value
and printing digits nobody asked for:

```sysl
import sysl.buf.byte_sink

var sink = byte_sink()
var out: *Writer = &sink

display_real(3.141592653589793, out, FormatSpec(0, -1, false))
display_str(" ", out, FormatSpec(0, -1, false))
display_real(3.141592653589793, out, FormatSpec(0, 17, false))
display_str(" ", out, FormatSpec(0, -1, false))
display_real_shortest(3.141592653589793, out, FormatSpec(0, -1, false))
display_str("\n", out, FormatSpec(0, -1, false))

display_real(0.1, out, FormatSpec(0, 17, false))
display_str(" ", out, FormatSpec(0, -1, false))
display_real_shortest(0.1, out, FormatSpec(0, -1, false))
display_str("\n", out, FormatSpec(0, -1, false))

display_real(5e-324, out, FormatSpec(0, 17, false))
display_str(" ", out, FormatSpec(0, -1, false))
display_real_shortest(5e-324, out, FormatSpec(0, -1, false))

putbytes(sink.text())
prints("\n")
```

```output
3.14159 3.1415926535897931 3.141592653589793
0.10000000000000001 0.1
4.9406564584124654e-324 5e-324
```

Six is a reasonable thing to show a person and a broken thing to serialize; seventeen keeps every
value and spells `0.1` with noise on the end. **What a document wants is neither**, and the answer
has been the same since Steele and White: the shortest text that parses back to the number you
started with.

The digits are found by Ryu, directly from the value's bit pattern rather than by asking a formatter
for one precision after another. It decomposes `x` into its significand and exponent, works out the
interval of decimal values that round back to `x`, and returns the shortest string inside that
interval — the one closest to `x` when more than one string is equally short, and the one ending in
an even digit on an exact tie. No reader is called and no precision is climbed: the same arithmetic
settles every value in one pass, so a subnormal like `5e-324`, the smallest positive `real`, needs no
different treatment from `0.5` or `3.141592653589793` — it comes out of the same interval
construction, at whatever length is actually shortest rather than a length a fixed search happened to
try first.

The round trip is guaranteed by the arithmetic rather than checked against a reader: the interval is
derived from `x`'s own bits, so every digit string inside it parses back to exactly `x` by
construction. [`sysl.text.parse_real`](/library/text/#reading-a-value-back-the-parsers) is still the
other half of the pair for anyone reading the digits back, but `display_real_shortest` no longer
depends on it to decide when to stop.

**It decides the precision and nothing else.** `%g` chooses the exponent form for a value whose
plain decimal would be long, and `1.0` comes out as `1` with no point in it — writing a number a
format's reader would take as an integer is that format's problem, which is why
[`sysl-lang/json`](https://github.com/sysl-lang/json) adds the point and this does not. A value with
no decimal reading never compares equal to anything, so an infinity or a NaN falls out of both
searches and renders as `%g` spells it at seventeen digits.

**`str` is unchanged and stays at six.** This is the other rendering being available at all, not a
new default: changing `str` would alter the output of every program that prints a number.

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
display_uint(7, out, FormatSpec(0, 3, false))

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

**Padding once is the rule rather than the style.** A specifier describes the field the *whole value*
occupies — so `%10s` on a point pads the point, not its first number. An implementation that rendered
parts and forwarded `fmt` down to each of them would pad the `3` to ten columns and then the `4`,
which is not what anybody asking for `%10s` meant.

Forwarding `fmt` straight down is right in exactly one case: when the part being rendered **is** the
whole rendering, as for a wrapper around a single field.

**Gathering the parts into a string is the shortest way to pad them once and not the cheapest** — it
is four allocations here, three of them thrown away as soon as they are joined. `Counting`, above,
gets the same width by rendering the parts into a sink that keeps their length and drops them — which
is what the library's own multi-part renderings do, and what a `Point` printed in a loop would want.
This recipe is the one to start from; that one is what to reach for when the rendering is hot, or the
module has no allocator.

A type with no `Display` cannot be printed, and the diagnostic says what to write:

```sysl
struct Scale
    k: int

print(Scale(3))
```

```error
cannot print a Scale value — write an 'impl sysl.Display for Scale' to say how it renders
```

### A slice or an array of anything printable

One `impl[T: Display] Display for []T` covers every slice, so the element type only has to render
itself. A slice of a type you wrote works the moment that type does:

```sysl
struct Rect
    w: int
    h: int

impl Display for Rect
    display(self, out: *Writer, fmt: FormatSpec) = display_str("a rect", out, fmt)

var ns = [1, 2, 3]
var rs = [Rect(3, 4), Rect(1, 2)]

print(ns)
print(rs[..])
print(f"[${ns[..]}%14s]")
```

```output
[1, 2, 3]
[a rect, a rect]
[     [1, 2, 3]]
```

**A fixed-size array renders too**, and by one block rather than one per length:
`impl[const N: usize, T: Display] Display for [N]T`. A length is a *value parameter*, so it is an
argument to the array shape rather than part of it, and one implementation covers every array there
is. It delegates to the block above, so an array and its `[..]` view render identically — which is
why `print(ns)` and `print(ns[..])` are the same line of output.

Before value generics a length was part of a type's shape: `[2]T` and `[3]T` were two shapes with no
way to be generic over the difference, so printing a fixed array meant taking the view by hand.

**The elements are written as they are met**, never gathered. Gathering would need a growable buffer,
`sysl.buf` is built *on* this module rather than under it, and an allocation on the printing path is
the one thing printing does not have — so a slice prints under `@no_alloc` exactly as a number does.

That leaves the width, which has to be known before the first byte goes out. It is learned by
rendering once into a sink that adds up what it is given and keeps none of it, so the cost of a
padded slice is a second pass rather than a buffer, and an unpadded one is a single pass.

### A slice or an array of anything equatable

`impl[T: Eq] Eq for []T` is the same shape as the block above, one trait over: same length, same
elements, same order.

```sysl
var a = [1, 2, 3]
var b = [1, 2, 3]
var c = [1, 2, 4]

print(a == b, a == c, a[0..<2] == c[0..<2])
print(a[0..<2] == a[..])
```

```output
true false true
false
```

The length is tested first, so two sequences of different lengths cost one comparison rather than a
walk of the shorter one.

**A fixed-size array compares by one block per shape too** — `impl[const N: usize, T: Eq] Eq for
[N]T`, delegating to `self[..]` exactly as its `Display` does, so `a == b` and `a[..] == b[..]` are
the same question asked twice.

**The element's own `==` is what runs**, and nothing here knows how it decides. A slice of a type you
wrote compares the moment that type does:

```sysl
struct Rect
    w: int
    h: int

impl Eq for Rect
    eq(self, rhs: Rect) -> bool = self.w * self.h == rhs.w * rhs.h

var rs = [Rect(3, 4), Rect(2, 6)]
var ss = [Rect(6, 2), Rect(4, 3)]

print(rs == ss)
```

```output
true
```

**A program wanting its own `==` for a slice of its type says `override`**, exactly as it does for
the rendering — the section below is about `Display` and the bargain is the same one. A blanket is an
implementation, so a second block for a narrower slice is the ordinary duplicate until the keyword
says the replacement was meant:

```sysl
struct N
    v: int

impl Eq for []N
    eq(self, rhs: Self) -> bool = self.len == rhs.len

print(1)
```

```error
every slice already implements 'sysl.Eq', so '[]N' has an implementation and cannot be given a second one — write 'override impl' to say that replacing it for this one type is what was meant
```

Say it and the block is yours, for `[]N` and for nothing else:

```sysl
struct N
    v: int

override impl Eq for []N
    eq(self, rhs: Self) -> bool = self.len == rhs.len

var a = [N(1), N(2)]
var b = [N(3), N(4)]
var c = [N(5)]

print(a[0..] == b[0..])
print(a[0..] == c[0..])
```

```output
true
false
```

`N` itself never became `Eq`, which is the point of the shape: the block is written for the slice,
so it decides what two of them mean without the element having an opinion. The
[section below](#saying-how-a-slice-of-your-type-renders) is the same keyword doing the same job for
the rendering, and says what it deliberately does not let you do.

**A slice and an array both order, and the order is lexicographic.** `impl[T: Ord] Ord for []T` and
`impl[const N: usize, T: Ord] Ord for [N]T` sit beside the `Eq` blocks above: the first differing
element decides, and a sequence that is a prefix of another orders before it.

```sysl
var a = [1, 2, 3]
var b = [1, 2, 4]
var c = [1, 2]

print(a[..] < b[..], b[..] < a[..])
print(c[..] < a[..], a[..] < c[..])
print(a < b, a == a)
```

```output
true false
true false
true true
```

Length decides only once the shared prefix is exhausted, which is the opposite order from `Eq` — that
tests the length first, because two sequences of different lengths cannot be equal and the walk is
wasted. An ordering has no such shortcut: `[1, 2]` and `[1, 2, 3]` differ in length and the shorter
one still has to be read to the end before anything is known.

That is the rule a `string` already follows and the one a tuple's `Ord` follows over its parts, so a
struct wrapping an array derives an ordering that means what a reader expects:

```sysl
struct Id derives Eq, Ord
    raw: [4]u8

var lo = Id([0, 0, 0, 1])
var hi = Id([0, 0, 1, 0])

print(lo < hi)
```

```output
true
```

**An array hashes and a slice does not**, which is the one place the two part company:
`impl[const N: usize, T: Hash] Hash for [N]T` folds the elements' hashes in order, so `[1, 2]` and
`[2, 1]` are different keys. There is no matching block for `[]T`:

```sysl
val xs: [3]u32 = [1, 2, 3]
val view = xs[..]

print(view.hash())
```

```error
type '[]const uint' has no method 'hash'
```

**A hash is a promise about a key, and a slice is a view.** Putting one in a table leaves the storage
reachable and writable by whoever owns it, so the key can change under the table and the entry
becomes unfindable — a failure that surfaces as a lookup quietly missing rather than as anything
going wrong where it was caused. An array is a value and copies, so it has no such owner, and a
caller holding a slice it means as a key hashes something it owns.

[`sysl.slices.equal`](/library/slices/#comparing-and-rearranging) answers the same question as a free function and is not
going anywhere: it takes `[]const T`, it lives in an `@no_alloc` module a freestanding target can
reach, and a caller holding two slices that wants a `bool` still wants it. What `==` adds is every
generic bounded by `Eq` — [`assert_eq`](#assert-eq-the-assertion-that-says-what-the-values-were)
above all, which prints both sequences when they disagree where a bare `bool` prints neither.

### Saying how a slice of *your* type renders

The block above covers every slice, which would ordinarily be the end of the matter: two
implementations for one type are refused, and a program writing `impl Display for []Rect` would be
told the library got there first. Say **`override`** and it is yours:

```sysl
struct Rect
    w: int
    h: int

impl Display for Rect
    display(self, out: *Writer, fmt: FormatSpec) = display_str("a rect", out, fmt)

override impl Display for []Rect
    display(self, out: *Writer, fmt: FormatSpec) =
        display_str(str(self.len) + " rects", out, fmt)

var rs = [Rect(3, 4), Rect(1, 2)]

print(rs[..])
print(rs[0])
```

```output
2 rects
a rect
```

The keyword goes on the **overriding** side, and that is the whole of it — nothing in the library
had to permit this in advance. A more specific implementation is found first, so `[]Rect` reaches
yours and every other slice still reaches the library's.

Two things it deliberately does not do. It does not let you replace an implementation for a type that
is not yours — `override impl Display for []int` is refused, because `[]int` names nothing of your
program's and [coherence](/reference/traits/) puts that block in the library or nowhere. And it does
not silence the ordinary duplicate: leave the keyword off and the second implementation is refused
exactly as it always was, which is how a block written twice by accident still gets found.

## Hashing

```sysl
trait Hash
    hash(self) -> u64
```

**A `u64` rather than a `usize`**, so a hash means the same thing on every target — a table moved
between a 32-bit and a 64-bit machine does not rebucket because the word size changed.

As with rendering, the built-ins reach a named mixer — through written `impl`s, one per closed type
and a single blanket over the integers, which is what makes a built-in erasable to a `&Hash` rather
than merely usable under a `Hash` bound. Those mixers are public, which is what lets a `Hash` written
for a struct mix its own fields:

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
    hash(self) -> u64 = hash_u64(hash_str(self.name) * 0x100000001b3 ^ hash_u64(u64(self.n)))

print(Key("a", 1).hash() != Key("a", 2).hash())
```

```output
true
```

The multiply-then-xor is the shape to copy: `0x100000001b3` is FNV's prime, and mixing with it before
the xor is what makes **order** matter. A plain xor of the field hashes would give `Key("a", 1)` and
a hypothetical `Key` with the fields swapped the same bucket.

Because the memberships are `impl`s, a built-in can be **erased** to a trait object — a method table
holds function pointers, and an `impl` is what supplies one:

```sysl
val xs: [3]&Hash = [7, "abc", true]

for h in xs do print(h.hash() != 0)
```

```output
true
true
true
```

The widening is the law rather than an implementation detail: `1` and `1` are the same number,
they compare equal across widths, and so they hash equal — each becomes the same `u64` before it
reaches the mixer.

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
see:

```sysl
struct Grid
    cells: [9]int

impl Index[int, int] for Grid
    index(self, i: int) -> int = self.cells[i]

impl IndexSet[int, int] for Grid
    index_set(*self, i: int, v: int)
        self.cells[i] = v

var g: Grid

g[0] = g[0] + 1

print(g[0])
```

```output
1
```

This is the [`sysl.buf`](/library/buf/) `Buf[T]` arrangement too, and there it earns something
specific: subscripting reaches the bounds-checked members rather than the storage, so `b[i]` cannot
read a slot past the count that the backing slice still has.

## Walking a type of your own

```sysl
trait Iterate
    type Item
    next(*self) -> Option[Self::Item]
```

This is what a `for` asks of what it walks, when what it walks is not an array, a slice, or a range
written straight into the header — those three the compiler walks directly. A range **value** is not
among them: `sysl.Range[T]` implements this trait like any other type, which is what makes a `for`
over a named range the ordinary loop rather than a second kind of one.

**The element is an [associated type](/reference/traits/#a-trait-may-declare-an-associated-type)
rather than a parameter**, because a cursor walks one kind of thing and the choice is the cursor's.
That is what lets a signature be generic over *what* it walks without naming what comes out:

```sysl
count_all[I: Iterate](it: I) -> usize
    var n: usize = 0
    var cur = it

    loop
        cur.next() match
            Some(_) -> n += 1
            None -> break

    n

print(count_all(0..<4), count_all("hi".chars))
```

```output
4 2
```

Written `Iterate[E]` that signature could not be spelled at all: `E` would appear only in a bound, and
a call has nothing there to solve it from. A body that *does* want the element names it as
`I::Item`.

**`next` answers with an `Option`**, so ending and yielding are one question with one answer. A
separate `has_next` would be two, and two questions can disagree — the classic iterator bug is a
`has_next` that says yes and a `next` that then has nothing.

```sysl
struct Countdown
    n: int

impl Iterate for Countdown
    type Item = int
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
— so `Iterate` has an object, and the loop calls its member through the table. What the object type
has to say is the **element**, since that is the one thing erasure forgot: `&Iterate[int]` is the
short spelling of `&Iterate[Item = int]`, and both are
[the same type](/reference/traits/#an-object-may-fix-the-associated-type).

```sysl
struct Countdown
    n: int

impl Iterate for Countdown
    type Item = int
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

**Every arity, not a list of them.** The four rows are written over a
[type pack](/reference/generics/#a-parameter-may-stand-for-a-list-of-types), so one block each
covers a tuple of any width:

```sysl
impl[..A: Eq] Eq for (..A)
    eq(self, rhs: Self) -> bool
        for const i in 0..<A.len
            if self.i != rhs.i then return false

        true
```

That is the library's own source, quoted for its shape, and it is worth reading as what a pack is
for: `..A` stands for the parts, the bound on it is asked of every one of them, and `for const` is
unrolled so `self.i` is an ordinary field selection at whatever type that part has.

```sysl
print((1, "a", true, 2.5, 'z'))
print((1, 2, 3, 4) == (1, 2, 3, 4), (1, 1, 1, 2) < (1, 1, 1, 3))
```

```output
(1, a, true, 2.5, z)
true true
```

`Ord` on a tuple is lexicographic, and each position runs a ladder rather than a single `<`, because
deciding a position takes **two** comparisons: this one is less, or it is greater, or the two agree
and the next position decides. Every position agreeing is not-less, which is where the loop ends.

A wide tuple is still usually worse code than a struct with field names — `.3` says nothing and
`.hue` says everything. What has changed is that the language is no longer the thing telling you so.

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
