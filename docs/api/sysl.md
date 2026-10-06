---
title: sysl
layout: api-module
headingShift: 0
slugStyle: github
module: sysl
summary: "The core: what every program has without importing anything."
---

`Option` and `Result`, the operator and rendering traits, `print` and the assertions, `exit` for a
program that chooses its own status. Nothing here is asked for by name — a file that declares no
imports at all can still write `Some(3)`, `assert`, `f"..."` and `x + y`, and that is what makes
this module the one a reader meets first.

**It is fourteen files and none of them speaks for the whole**, which is why this one exists and
holds nothing else. `check.sysl` is the assertions, `option.sysl` the optional value,
`ops.sysl` the trait catalog, `print.sysl` the printing surface; each is a good file with a good
header, and any one of them standing in for the module would describe a corner of it as though it
were the middle.

## Index

[`esink`](#esink) [`sink`](#sink) [`assert`](#assert) [`assert_eq`](#assert_eq) [`assert_slice_eq`](#assert_slice_eq) [`cpu`](#cpu) [`cpu_count`](#cpu_count) [`display_bool`](#display_bool) [`display_char`](#display_char) [`display_digits`](#display_digits) [`display_fill`](#display_fill) [`display_int`](#display_int) [`display_pad`](#display_pad) [`display_real`](#display_real) [`display_real_shortest`](#display_real_shortest) [`display_str`](#display_str) [`display_uint`](#display_uint) [`encode_utf8`](#encode_utf8) [`eprints`](#eprints) [`eputbytes`](#eputbytes) [`flush`](#flush) [`hash_bool`](#hash_bool) [`hash_str`](#hash_str) [`hash_u128`](#hash_u128) [`hash_u64`](#hash_u64) [`os`](#os) [`panic`](#panic) [`printb`](#printb) [`printc`](#printc) [`printi`](#printi) [`printr`](#printr) [`prints`](#prints) [`printu`](#printu) [`putbytes`](#putbytes) [`stderr`](#stderr) [`stdout`](#stdout) [`exit`](#exit) [`Counting`](#counting) [`Cpu`](#cpu-1) [`FormatSpec`](#formatspec) [`Option`](#option) [`Os`](#os-1) [`Range`](#range) [`Result`](#result) [`Stderr`](#stderr-1) [`Stdout`](#stdout-1) [`Add`](#add) [`BitAnd`](#bitand) [`BitOr`](#bitor) [`BitXor`](#bitxor) [`Display`](#display) [`Div`](#div) [`Drop`](#drop) [`Eq`](#eq) [`Fallible`](#fallible) [`Fn0`](#fn0) [`Fn1`](#fn1) [`Fn2`](#fn2) [`Fn3`](#fn3) [`Fn4`](#fn4) [`From`](#from) [`Hash`](#hash) [`Index`](#index) [`IndexSet`](#indexset) [`Integer`](#integer) [`Iterate`](#iterate) [`Mul`](#mul) [`Neg`](#neg) [`Not`](#not) [`One`](#one) [`Ord`](#ord) [`Rem`](#rem) [`Shl`](#shl) [`Shr`](#shr) [`Sub`](#sub) [`Writer`](#writer) [`Zero`](#zero) [Display for (..A)](#display-for-a) [Display for []T](#display-for-t) [Display for [N]T](#display-for-nt) [Display for bf16](#display-for-bf16) [Display for bool](#display-for-bool) [Display for char](#display-for-char) [Display for f16](#display-for-f16) [Display for f32](#display-for-f32) [Display for Option[T]](#display-for-optiont) [Display for real](#display-for-real) [Display for Result[T, E]](#display-for-resultt-e) [Display for string](#display-for-string) [Display for T](#display-for-t-1) [Eq for (..A)](#eq-for-a) [Eq for []T](#eq-for-t) [Eq for [N]T](#eq-for-nt) [Eq for Option[T]](#eq-for-optiont) [Eq for Result[T, E]](#eq-for-resultt-e) [Fallible for Counting](#fallible-for-counting) [Fallible for Stderr](#fallible-for-stderr) [Fallible for Stdout](#fallible-for-stdout) [Hash for (..A)](#hash-for-a) [Hash for [N]T](#hash-for-nt) [Hash for bool](#hash-for-bool) [Hash for char](#hash-for-char) [Hash for string](#hash-for-string) [Hash for T](#hash-for-t) [Iterate for Range[T]](#iterate-for-ranget) [One for bf16](#one-for-bf16) [One for f16](#one-for-f16) [One for f32](#one-for-f32) [One for real](#one-for-real) [Ord for (..A)](#ord-for-a) [Ord for []T](#ord-for-t) [Ord for [N]T](#ord-for-nt) [Writer for Counting](#writer-for-counting) [Writer for Stderr](#writer-for-stderr) [Writer for Stdout](#writer-for-stdout) [Zero for bf16](#zero-for-bf16) [Zero for f16](#zero-for-f16) [Zero for f32](#zero-for-f32) [Zero for real](#zero-for-real)

## Values

### `esink`

```sysl
val esink: *Stderr = ptr_cast(0usize)
```

### `sink`

```sysl
val sink: *Stdout = ptr_cast(0usize)
```

## Functions

### `assert`

```sysl
assert(cond: bool, msg: string = "", file: string = __FILE__, line: long = __LINE__)
```

The condition is checked and nothing else is: a false one names where it was written, and the
message if it was given one.

**`file` and `line` are passed on explicitly**, which is the one thing about this that is easy to
get wrong: letting `panic` fill its own defaults would report *this* line, in this file, for every
assertion in every program -- the call that fills them would be the one three lines down rather
than the reader's. Forwarding them is how a location travels through a helper.

### `assert_eq`

```sysl
assert_eq[T: Eq + Display](got: T, want: T, msg: string = "", file: string = __FILE__, line: long = __LINE__)
```

`got` and `want` in that order, which is Go's and the order a failure is read in: what happened,
then what was meant to.

### `assert_slice_eq`

```sysl
assert_slice_eq[T: Eq + Display](got: []const T, want: []const T, msg: string = "", file: string = __FILE__, line: long = __LINE__)
```

The same for a slice, which needs more than `==` to be useful: a report saying two slices differ
sends its reader to find out where, and finding out where is a loop nobody wants to write at each
call. Length first, since a length mismatch explains every index after the shorter one.

### `cpu`

```sysl
cpu() -> Cpu
```

Which processor this program was compiled for, on the same terms as `os` above: a constant the
compiler already holds, reaching an expression rather than a gated line.

A program wanting an address width should ask for one -- `sizeof(usize)` is the question, and it
is right for a processor this list has not got yet.

### `cpu_count`

```sysl
cpu_count() -> usize
```

How many logical processors this machine has online -- the number to size a pool of workers
by, so that a program starting one job per processor neither leaves processors idle nor queues
work behind itself.

**Unlike `cpu` above it is a question, not a constant**, because it is about the machine the
program is *running* on rather than the one it was compiled for: the same binary answers 4 on a
laptop and 64 on a build server. "Online" is the word that matters -- a processor the system has
taken offline is not one a program can schedule work on, so it is not counted.

It is never less than one, since no program runs on zero processors, and a caller dividing work
by it should not have to say so. **It needs an operating system to ask, and says so**: a module
that has given up `os` is refused at the call. Where there is no POSIX `sysconf` to ask -- a
freestanding image, WASI -- the answer is that one.

### `display_bool`

```sysl
display_bool(b: bool, out: *Writer, fmt: FormatSpec)
```

### `display_char`

```sysl
display_char(ch: char, out: *Writer, fmt: FormatSpec)
```

### `display_digits`

```sysl
display_digits(text: []const u8, out: *Writer, fmt: FormatSpec)
```

### `display_fill`

```sysl
display_fill(out: *Writer, b: u8, n: int)
```

### `display_int`

```sysl
display_int(n: long, out: *Writer, fmt: FormatSpec)
```

The two below render a number a program already holds at 64 bits, and they are what a caller
with a `long` in hand reaches directly.

**Nothing in the library routes through them any longer.** Every integer, at every width, renders
from the one blanket `impl Display` in `display.sysl`, whose buffer is measured from the type it
was instantiated at. What that replaced was three ranges of special case: these two below 64
bits, a hand-rolled pair between 64 and 128 where C has no conversion wide enough, and above that
a fall back through `str` that allocated -- so the widest values were the ones a `no alloc`
module could not print, which is exactly backwards. The three wide renderers are gone with the
routing that chose them; these two stay because a `long` is a thing programs have.

`display_digits` above is where all of them ended, and it is where the blanket ends still.

### `display_pad`

```sysl
display_pad(text: []const u8, out: *Writer, fmt: FormatSpec)
```

### `display_real`

```sysl
display_real(x: real, out: *Writer, fmt: FormatSpec)
```

### `display_real_shortest`

```sysl
display_real_shortest(x: real, out: *Writer, fmt: FormatSpec)
```

A `real` written in the fewest digits that read back as the same number, which is what a
serializer needs and what `display_real` cannot give it.

A precision is a count of significant digits, so any fixed one is a choice between losing the
value and printing digits nobody asked for: six -- the default, and the one `str` shows a person
-- turns 3.141592653589793 into 3.14159, and seventeen turns 0.1 into 0.10000000000000001. What a
document wants is neither, and the answer has been the same since Steele and White: **the
shortest text that parses back to the value you started with**.

The digits are JavaScript's and Python's: the fewest that read back; among those, the closest to
the value; and a tie to the even digit. `shortest_real` finds them exactly, with no round trip
through a reader, and `shortest.sysl` says how. The spelling around them is `%g`'s, so `1e+23`,
`0.5`, `-0`, `inf` and `nan` read as they always have. A caller that needs `null` or `Infinity`
written for a non-finite value is writing a format's syntax and owns that decision.

### `display_str`

```sysl
display_str(s: string, out: *Writer, fmt: FormatSpec)
```

### `display_uint`

```sysl
display_uint(n: ulong, out: *Writer, fmt: FormatSpec)
```

### `encode_utf8`

```sysl
encode_utf8(ch: char, into: []u8) -> usize
```

A character's UTF-8 bytes, written into storage the caller owns, answering how many were written.
No character needs more than four, so `var b: [4]u8` is always enough and always on the stack --
which is what keeps this usable where there is no allocator.

The caller supplying the buffer is what makes that true, and it is the same shape every other
place in the library takes when a result has a known bound. A slice with fewer than the four bytes
is a mistake in the program rather than a truncation, and the bounds check says so: how many bytes
are needed is not known until the character has been looked at, so there is no honest way to
answer "it did not fit" that is cheaper than having room.

**It lives in `sysl` rather than beside `from_utf8` in `sysl.text`, and that is forced.** A
submodule may name the standard module freely and `sysl` may not name back (`reference/modules.md
§ The module graph is acyclic`), so an encoder over in `sysl.text` would be one `printc` below
could not call -- and the second encoder that would then have to be written for it is exactly the
hand-rolled copy this function exists to prevent. One encoder in an odd place beats two in tidy
ones.

### `eprints`

```sysl
eprints(s: string)
```

### `eputbytes`

```sysl
eputbytes(b: []const u8)
```

The other stream, which is where a program's diagnostics belong.

**The distinction is not decoration.** A program whose output is being read by another program --
piped, redirected, captured in a variable -- writes its answer to one stream and its complaints to
the other, so that a complaint never lands in the middle of the answer and a redirect that keeps
the answer still lets a person see what went wrong. `sysl.args` puts a usage error here and its
`--help` text on standard output for exactly that reason: the help *was* what was asked for.

It goes through `write` rather than through `putchar`'s neighbour `fputc`, and directly rather
than a byte at a time, because there is no buffer between this and the descriptor: a diagnostic
written just before the program stops has to have left, and C's standard error is unbuffered for
the same reason. That also makes it the one printing path whose output cannot be lost to a trap,
which is worth knowing when a program is being debugged.

A short write is looped over rather than ignored, since a signal may cut one anywhere; a write
that reports failure ends the loop, because there is nowhere left to report *that* to.

### `flush`

```sysl
flush()
```

Push everything `putbytes` has been holding out to wherever standard output is going, right now
rather than waiting for the buffer to fill or the process to exit.

**C fully buffers a stream that is not a terminal.** A live report -- rows streamed to a log as
they complete, output piped into another program, a test runner reading a child's output while it
still runs -- can otherwise sit on everything already printed until the process ends, because
`putbytes` writes through C's buffered `stdout`. This is the call that pushes it out early.

`fflush(NULL)` runs underneath, which flushes every open output stream rather than naming one --
`stdout` is a `#define` on Darwin (`reference/ffi.md § An extern also declares a variable`), so an
`extern` naming it by pointer would not link there, and flushing everything costs nothing a program
would notice, since there is normally only the one stream open to flush.

`eprints` needs no matching call: `eputbytes` writes with a raw `write(2)`, which keeps no buffer
of its own to hold anything back.

### `hash_bool`

```sysl
hash_bool(b: bool) -> u64
```

A `bool` is one bit, and one bit does not widen to a number in this language -- the same reason
`display_bool` exists rather than a widening into the integer renderer.

### `hash_str`

```sysl
hash_str(s: string) -> u64
```

FNV-1a over the bytes, which is what a string is: a validated `[]u8`, so there is nothing to
decode and every byte counts once.

### `hash_u128`

```sysl
hash_u128(v: u128) -> u64
```

Past 64 bits the two halves are mixed separately and combined, because the finalizer above is
defined on a word and a wider integer has no single one.

### `hash_u64`

```sysl
hash_u64(v: u64) -> u64
```

The mixers a built-in's `Hash` membership renders through. A built-in has no `impl` block, so
there is no lowered `int.hash` to call; what it has is one of these, chosen by type, and naming
them is what lets a `Hash` written for a struct mix its own fields.

This is splitmix64's finalizer: it is the one that turns a counter into something a table can
bucket on, since consecutive integers are the input a hash table actually meets and the identity
hash is what makes them collide in a row.

### `os`

```sysl
os() -> Os
```

Which operating system this program was compiled for.

**It is a constant, not a query.** A sysl program is compiled for exactly one target, so the
answer is something the compiler already holds and this costs a program nothing at run time --
there is no table to consult and no environment to read, and a freestanding target pays for it
exactly as much as a hosted one does, which is nothing.

**What it adds over `#if` is expression position.** `#if` gates lines, before the lexer, so a
program could already define a different `val` per platform; what it could not do is pass a
platform to a function, compare one, or `match` on one. This is that half:

```
val leaf = os() match
    MacOS -> "Library/Caches"
    Windows -> "AppData"
    _ -> ".cache"
```

**There is deliberately no `#else` under this**, so an operating system added to the compiler's
registry without a branch here fails to build for that target rather than quietly answering
something else.

### `panic`

```sysl
panic(msg: string, file: string = __FILE__, line: long = __LINE__) -> never
```

The location is **streamed** rather than interpolated, and that is not a style choice: an
f-string builds a string, and building one makes heap storage, which would put `panic` and
everything through it out of reach of a module that declared `@no_alloc` (`reference/modules.md §
Capabilities are a module property`). An assertion is exactly what an allocator-free module wants
most, so it is composed out of `prints` and `printi`, each of which goes straight to `putbytes`.
Caught by the `@no_alloc` test that already existed.

### `printb`

```sysl
printb(b: bool)
```

### `printc`

```sysl
printc(ch: char)
```

A `char` is encoded rather than handed to `snprintf`, which has no conversion that takes a code
point.

### `printi`

```sysl
printi(n: long)
```

The integers render in sysl, through `digits_long` and `digits_ulong` beside `display_int`. The
float still leans on `snprintf`, which is formatting rather than I/O. What it prints is `%g`'s six
significant digits, correctly rounded, and rounding to a precision the caller chose needs exact
arithmetic over the whole value -- a different job from `shortest_real`'s, which finds the fewest
digits and never rounds to a count it was given -- so it waits until there is a reason, such as a
target without a C library.

### `printr`

```sysl
printr(x: real)
```

### `prints`

```sysl
prints(s: string)
```

### `printu`

```sysl
printu(n: ulong)
```

### `putbytes`

```sysl
putbytes(b: []const u8)
```

The printing surface, which lives in the library rather than in the compiler. `print(a, b, c)` is
a desugaring onto these one-value functions, chosen by each argument's static type, so the
compiler knows a handful of *names* and implements no printing of its own.

Everything goes out through the single sink `putbytes`, and that is not incidental: two mechanisms
means two buffers, and output emerging in the wrong order. Where there is a C library it hands the
whole buffer to stdio's `fwrite` in one call -- counted rather than terminated, so an interior NUL
goes out like any other byte, which `puts`, `%s` and even `%.*s` would all stop at. It stays on
stdio rather than a raw `write(1, ...)` because C in the same program writing through `printf`
shares that buffer, and one queue is what keeps the two in order.

A freestanding target has no stdio, so there it writes a byte at a time through the `putchar` the
board supplies. That is also one of the two functions such a target has to replace: swap its body
for a `write` syscall, and `FdReader.read`'s for a `read` one, and the whole surface above both is
unchanged.

A short `fwrite` is looped over, and one that wrote nothing ends the loop -- the stream has failed,
and there is nowhere left to report that to.

### `stderr`

```sysl
stderr() -> *Writer
```

### `stdout`

```sysl
stdout() -> *Writer
```

## Externs

### `exit`

```sysl
extern exit(code: int) -> never
```

The hosted exit, and the one C declaration the library offers rather than keeps.

It takes no link name, which is deliberate where the four in `sysl.sys` all take one: this is the
library's offer of the hosted exit -- what `unwrap` and `expect` stop the program with, a
diagnostic printed and a non-zero status, which is what `11-error-handling.md` says a trap does
under the `os` capability -- and it is the reason those two need no compiler support. A program
stopping itself writes the same thing, so `exit` is a word every program has and the four
platform names are not.

An extern's symbol is not qualified and cannot be, since it names something the linker already
has and the linker knows nothing about sysl's modules. So this is keyed under `sysl` like every
other name here while going on resolving to `exit`.

## Types

### `Counting`

```sysl
struct Counting
    n: usize
```

A sink that keeps the length of what it was given and drops the bytes, which is how a value made
of parts honours a width without gathering: the parts are rendered twice and stored nowhere,
rather than once into a buffer that has to be allocated. `sysl` cannot reach `sysl.buf` -- that
module is built on this one -- so a growable buffer is not available here even if one were wanted.

**Public, because the problem it answers is not the library's.** A specifier describes the field
the *whole* value occupies (`library/core.md § A specifier is the whole value's field`), so any
`Display` rendering more than one part has to know how wide the whole came out before it can pad
once -- and an implementation written outside `sysl` had nowhere to measure into but a buffer.
`[]T`, `Option`, `Result`, `Complex[F]` and the tuple above all honour a width this way, from five
separate files -- and so does **every derived `Display`**, which the compiler writes in this shape
(`reference/traits.md § The compiler writes four of them`).

### `Cpu`

```sysl
enum Cpu
    Aarch64
    X86_64
    Riscv64
    Riscv32
    Thumb
    Wasm32
    X86
    Craft
```

The processor a program was compiled for, named as `#if` and an `asm` arm name it.

**`Thumb` rather than `Arm`, because it is the instruction set that a program gating on a
processor has to be right about** -- a Cortex-M executes Thumb and nothing else. The two widths of
RISC-V are two rows for the same reason a 32-bit and a 64-bit x86 are: an address width is the
thing most code asking this question is asking about.

### `FormatSpec`

```sysl
struct FormatSpec
    width: int
    prec: int
    left: bool
```

What a format string's flags are carried in (`library/core.md § A specifier is the whole value's
field`): the field the whole value occupies, the precision each renderer reads in its own terms,
and which side the padding goes on.

What honours it is the `display_*` family, whose prose is where the three fields are explained.

### `Option`

```sysl
enum Option[T]
    Some(value: T)
    None
```

A value that may be absent. This is an ordinary generic enum rather than anything the analyzer
builds: what the compiler knows is that `?` unwraps it and which variant means which, and it asks
`Library.tryVariants` for that rather than spelling the names.

`unwrap` and `expect` stop the program through `exit`, which is what `11-error-handling.md` says a
trap does under the `os` capability -- so neither needs compiler support of its own.

| Member | Signature | Description |
|---|---|---|
| `is_some` | `is_some(self) -> bool` |  |
| `is_none` | `is_none(self) -> bool` |  |
| `unwrap_or` | `unwrap_or(self, default: T) -> T` |  |
| `unwrap` | `unwrap(self) -> T` |  |
| `expect` | `expect(self, msg: string) -> T` |  |
| `unwrap_or_else` | `unwrap_or_else(self, f: () -> T) -> T` | The default computed rather than passed, for a default that costs something to make. |
| `map` | `map[U](self, f: T -> U) -> Option[U]` | The payload transformed, absence carried through. |
| `and_then` | `and_then[U](self, f: T -> Option[U]) -> Option[U]` | The same for a function that may itself answer nothing, which is what keeps the two from nesting: `map` of an `Option`-returning function is an `Option[Option[U]]` and this is not. |
| `or_else` | `or_else(self, f: () -> Option[T]) -> Option[T]` | Another option where this one is absent -- the first that has something wins. |
| `filter` | `filter(self, pred: T -> bool) -> Option[T]` | Absent unless the payload passes -- `Some(4).filter(even)` is `Some(4)` and `Some(5)` is `None`. |
| `ok_or` | `ok_or[E](self, e: E) -> Result[T, E]` | The crossing into the other enum: absence becomes the error the caller names. |

### `Os`

```sysl
enum Os
    MacOS
    Linux
    Windows
    Freestanding
    Android
    Wasi
```

The operating system a program was compiled for.

**These are the same five machines `#if` gates on**, under the same names a source file writes at
the margin: `#if macos` and `os() == MacOS` ask one question, and the two must never come to
disagree. `Freestanding` is a member because a target with no operating system under it is still a
target with an answer, and a program that has to say what it is running on would otherwise have
one case it could not spell.

`Android` is its own row rather than a kind of `Linux`, for the reason `Conditional.osSymbol`
gives: what a program asking this wants to know is which libc and which conventions it has, and
Bionic is neither glibc's nor its headers'. A program that means either writes
`os() == Linux || os() == Android`.

### `Range`

```sysl
struct Range[T]
    lo: T
    hi: T
    inclusive: bool
```

A range with both ends written, as a value.

`0..<n` and `0..n` are a **form** first: in a `for` header, a slice index, a `match` pattern and a
quantifier the compiler reads the two bounds directly and there is no `Range` anywhere in what it
emits. A counted `for` is still a counter and a comparison, which is what keeps the most ordinary
loop in the language free of a struct, an `Option` a step, and a call.

What this type is for is every *other* position — a range bound to a name, passed to a function,
returned from one, or handed to something generic. `sysl.seq` is the reason it exists: a range is
the one obvious sequence, and `(0..<n).map(f)` had no receiver to be a method on.

**Only a fully-bounded range becomes one.** `..`, `lo..` and `..hi` mean something in a slice
index and nothing on their own — a value would have to say what an absent bound *is*, and the
answer differs by what it is indexing — so they stay index-only and are refused elsewhere by name.

### `Result`

```sysl
enum Result[T, E]
    Ok(value: T)
    Err(error: E)
```

An answer that may have failed, carrying why. The error is a type parameter rather than a fixed
one so that a module can answer with an enum of its own failure cases and have them matched
exhaustively -- which is what the library's own conversions do (`from_utf8` answers with a
`Utf8Error`).

Like `Option` this is an ordinary generic enum; `?` reaches it through `Library.tryVariants`.

| Member | Signature | Description |
|---|---|---|
| `is_ok` | `is_ok(self) -> bool` |  |
| `is_err` | `is_err(self) -> bool` |  |
| `unwrap_or` | `unwrap_or(self, default: T) -> T` |  |
| `unwrap` | `unwrap(self) -> T` |  |
| `expect` | `expect(self, msg: string) -> T` |  |
| `unwrap_err` | `unwrap_err(self) -> E` |  |
| `unwrap_or_else` | `unwrap_or_else(self, f: E -> T) -> T` | The failure computed rather than passed, for a default that costs something to make. |
| `map` | `map[U](self, f: T -> U) -> Result[U, E]` | The success transformed, the failure carried through. |
| `map_err` | `map_err[F](self, f: E -> F) -> Result[T, F]` | The failure transformed, the success carried through -- which is how one layer's error type becomes the next one's at a boundary the caller chose. |
| `and_then` | `and_then[U](self, f: T -> Result[U, E]) -> Result[U, E]` | The same as `map` for a function that may itself fail, which is what keeps the two from nesting: `map` of a `Result`-returning function is a `Result[Result[U, E], E]` and this is not. |
| `or_else` | `or_else[F](self, f: E -> Result[T, F]) -> Result[T, F]` | Another attempt where this one failed, with the failure in hand to decide by. |
| `ok` | `ok(self) -> Option[T]` | The success as an `Option`, dropping the failure -- the crossing back from the enum that carries a reason to the one that does not. |
| `err` | `err(self) -> Option[E]` | And the failure as an `Option`, dropping the success. |
| `expect_err` | `expect_err(self, msg: string) -> E` |  |

### `Stderr`

```sysl
struct Stderr
end Stderr
```

Standard error as a `Writer`, so a value renders itself onto the diagnostic stream through the
same `Display` it uses for standard output. It carries no state for the reason `Stdout` does not.

### `Stdout`

```sysl
struct Stdout
end Stdout
```

Standard output as a `Writer`, which is what a value writes itself into when it renders through
its own `Display` rather than through one of the `print*` functions above.

It is an ordinary sink written in ordinary sysl, and it is worth saying why that is possible at
all: a destination fixed at compile time keeps no state, so the type has no fields -- and a struct
may have none. Everything the compiler used to supply here by hand is now three declarations a
reader can check, and the sink they end in is still `putbytes`, so a freestanding target replaces
the same one function it always did.

## Traits

### `Add`

```sysl
trait Add[Rhs = Self, Out = Self]
    add(self, rhs: Rhs) -> Out
```

| Member | Signature | Description |
|---|---|---|
| `add` | `add(self, rhs: Rhs) -> Out` |  |

### `BitAnd`

```sysl
trait BitAnd[Rhs = Self, Out = Self]
    bitand(self, rhs: Rhs) -> Out
```

| Member | Signature | Description |
|---|---|---|
| `bitand` | `bitand(self, rhs: Rhs) -> Out` |  |

### `BitOr`

```sysl
trait BitOr[Rhs = Self, Out = Self]
    bitor(self, rhs: Rhs) -> Out
```

| Member | Signature | Description |
|---|---|---|
| `bitor` | `bitor(self, rhs: Rhs) -> Out` |  |

### `BitXor`

```sysl
trait BitXor[Rhs = Self, Out = Self]
    bitxor(self, rhs: Rhs) -> Out
```

| Member | Signature | Description |
|---|---|---|
| `bitxor` | `bitxor(self, rhs: Rhs) -> Out` |  |

### `Display`

```sysl
trait Display
    display(self, out: *Writer, fmt: FormatSpec)
```

How a value renders itself.

A `Display` writes its value's text into a sink rather than returning a fresh `string`, so
rendering costs no allocation and a `no alloc` module can still log. The sink is a `*Writer`,
which is the trait object of `02` -- and which this file does not declare: a module's members are
one set however many files they came from, so `Writer` is reached here with nothing to import.

| Member | Signature | Description |
|---|---|---|
| `display` | `display(self, out: *Writer, fmt: FormatSpec)` |  |

### `Div`

```sysl
trait Div[Rhs = Self, Out = Self]
    div(self, rhs: Rhs) -> Out
```

| Member | Signature | Description |
|---|---|---|
| `div` | `div(self, rhs: Rhs) -> Out` |  |

### `Drop`

```sysl
trait Drop
    drop(self)
```

What a type does when the last reference to one of its values goes (`reference/memory.md § A
destructor`).

**It is for a resource the language does not manage.** ARC returns the storage and releases
whatever the value holds; what it cannot do is close a descriptor, unmap a region, or hand a
handle back to the C library that made it -- those are the far end of a `*T` or an integer, and
nothing about them says they are owned. A `Drop` is where that is said, once, next to the type.

**`defer` is the other way to say it and neither replaces the other.** `defer close(f)` covers
every site a program can *name*; a value can also die where there is no site to write one -- a
`File` inside a `[]File` that goes out of scope, a resource inside a struct inside a container.
Those are the cases this exists for, and they are why the two are not redundant. Where both would
work, prefer the destructor: it is written once, and a caller cannot forget it.

**It runs when a box's strong count reaches zero, and that is the whole of the rule.**

- It runs *before* the value's own references are released, so `self` is intact and a field may
be read to close what it names.
- It runs for a value held behind `&T`. A value that never reached the heap has no single point
of death -- it is copied, and a copy is not a second resource -- so the destructor is not
called for one. `reference/memory.md § A destructor` states the whole limit.
- It **does not** run for a value in a reference cycle, which never reaches zero. Its storage is
not returned either; that is the cost of counting rather than collecting, and `weak T` is what
breaks a cycle.
- It **does not** run for a value in module storage at the end of the program. Storage that
lasts the whole run is never let go of (`reference/modules.md § static`), and a process
exiting is what returns what it held.

**It answers nothing, and cannot fail.** It runs at an arbitrary point in a teardown with no
caller to receive an error and no `?` to carry one out. A close whose failure the program cares
about is called explicitly, where there is somewhere to report it; the destructor is the backstop
for the paths that did not.

| Member | Signature | Description |
|---|---|---|
| `drop` | `drop(self)` |  |

### `Eq`

```sysl
trait Eq
    eq(self, rhs: Self) -> bool
```

| Member | Signature | Description |
|---|---|---|
| `eq` | `eq(self, rhs: Self) -> bool` |  |

### `Fallible`

```sysl
trait Fallible
    failed(*self) -> bool
```

Whether a stream has gone wrong, which is the one thing reading and writing were always going to
have to agree about.

It is a trait of its own, required by both `Writer` and `Reader`, because of what a type that is
**both** runs into otherwise. Two traits may each declare a member of one name for one type, and
a call says which by naming the trait (`reference/traits.md § Reaching a member through its
trait`) -- but `failed` takes no arguments, and a program that reads and writes a file has both
traits in scope by definition, so nothing about the call could say which was meant and it is
refused where it is written. The two halves of the byte surface were built a module apart and
each grew a `failed` of its own; an open file is the first thing that had to be both, and one
required trait is what makes the question go away rather than move. A diamond needs no rule of
its own: the walk takes each trait the first time it reaches it, so a reader that is also a
writer carries these members once.

The default is `false`, which is the library's own use of the mechanism `02` calls for: most
streams cannot fail, and one that cannot should not have to write down that it cannot.

**It latches rather than returning**, and that is the shape everything above it is built on: an
implementation stays straight-line, `print(x)` stays a statement, and a caller asks once after a
run of operations rather than after each. Whether a stream *ended* is a separate question from
whether it ended badly, which is why reading answers the first with an empty result and leaves
this to answer the second.

| Member | Signature | Description |
|---|---|---|
| `failed` | `failed(*self) -> bool` |  |

### `Fn0`

```sysl
trait Fn0[R]
    call(*self) -> R
```

What a value that can be called looks like, one trait per arity.

There is one per arity rather than one variadic trait because a call's argument types are part of
what it promises, and there is no way to write that variadically. `call` takes `*self` so that a
closure may carry mutable state -- a counter that answers differently each time is a thing worth
being able to write.

| Member | Signature | Description |
|---|---|---|
| `call` | `call(*self) -> R` |  |

### `Fn1`

```sysl
trait Fn1[A, R]
    call(*self, a: A) -> R
```

| Member | Signature | Description |
|---|---|---|
| `call` | `call(*self, a: A) -> R` |  |

### `Fn2`

```sysl
trait Fn2[A, B, R]
    call(*self, a: A, b: B) -> R
```

| Member | Signature | Description |
|---|---|---|
| `call` | `call(*self, a: A, b: B) -> R` |  |

### `Fn3`

```sysl
trait Fn3[A, B, C, R]
    call(*self, a: A, b: B, c: C) -> R
```

| Member | Signature | Description |
|---|---|---|
| `call` | `call(*self, a: A, b: B, c: C) -> R` |  |

### `Fn4`

```sysl
trait Fn4[A, B, C, D, R]
    call(*self, a: A, b: B, c: C, d: D) -> R
```

| Member | Signature | Description |
|---|---|---|
| `call` | `call(*self, a: A, b: B, c: C, d: D) -> R` |  |

### `From`

```sysl
trait From[T]
    from(value: T) -> Self
```

A value of one type built from a value of another, without either type being changed to know
about the other (`reference/errors.md § A ? converts through From`).

**It is what `?` converts through.** A function returning `Result[T, Fault]` may write `?` on a
call returning `Result[U, Io]` exactly when `Fault` implements `From[Io]`, so the two layers of
error type are joined once, in the block that says how, rather than at every call that crosses
between them. Nothing else in the language reads it; a program may implement and call it for its
own conversions like any other trait.

**The parameter is the SOURCE and `Self` is the destination**, which is the direction that lets a
type accept conversions from types it does not own: `impl From[Io] for Fault` is written where
`Fault` is, and `Io` need never have heard of it. Written the other way round the block would
have to live with the source, and the orphan rule would put every conversion in whichever module
lost the argument.

A type may implement it several times over — `From[int]` and `From[real]` for one type are two
blocks — and a call says which it means by the argument it wrote.

| Member | Signature | Description |
|---|---|---|
| `from` | `from(value: T) -> Self` |  |

### `Hash`

```sysl
trait Hash
    hash(self) -> u64
```

What a value mixes down to when something needs to key on it (`library/core.md § Hashing`).

A `u64` rather than a `usize`, so a hash means the same thing on every target and a table moved
between two of them does not resize because the word did.

| Member | Signature | Description |
|---|---|---|
| `hash` | `hash(self) -> u64` |  |

### `Index`

```sysl
trait Index[I, E]
    index(self, i: I) -> E
```

What a subscript means on a type that has no elements of its own.

A built-in array or slice is indexed by walking to an address, and that is the compiler's; these
two are what `b[i]` means when the receiver is a type someone wrote. The difference is not
cosmetic -- reading through `Index` is a **call**, so it yields a value rather than a place, which
is why `b[i] += 1` is refused rather than quietly evaluating the receiver and the index twice.

Reading and writing are separate traits because a type may offer one without the other: a view
that computes its elements has an `Index` and no `IndexSet` to give.

| Member | Signature | Description |
|---|---|---|
| `index` | `index(self, i: I) -> E` |  |

### `IndexSet`

```sysl
trait IndexSet[I, E]
    index_set(*self, i: I, v: E)
```

| Member | Signature | Description |
|---|---|---|
| `index_set` | `index_set(*self, i: I, v: E)` |  |

### `Integer`

```sysl
trait Integer: Div + Rem + Ord + Eq + Sub + Mul
```

The integers, which are the family that is **open**: `i5` and `u24` are types a program may
name, so there is no finite list of widths to write the blocks above for.

What stands in for the list is one block written over the whole family at once, and `Integer` is
what names the family. It declares no member of its own -- a type is one of the integers or it is
not, which is the compiler's answer and not something a program can join -- so what it carries is
the operators the block below needs, exactly as `sysl.math`'s `Float` carries the ones its two
widths share.
**`Mul` joined this list for rendering**, which is the one member here that is not about taking a
number apart. Chunked decimal conversion multiplies in two places -- computing the chunk divisor,
and recovering a remainder as `rest - q * chunk` -- and every integer type has `Mul` at every
width, so nothing is excluded by asking for it and the compiler supplies the membership either
way.

### `Iterate`

```sysl
trait Iterate
    type Item
    next(*self) -> Option[Self::Item]
```

What a `for` walks, when what it walks is not a range, an array or a slice.

The cursor is advanced through `*self`, so a loop iterates a *copy* of whatever it was given --
which is why a cursor that means to report something after the walk (a reader's failure latch)
has to borrow what it reports on rather than own it.

`next` answers with an `Option`, so ending and yielding are one question with one answer, and
there is no separate `has_next` for the two to disagree about.

The element is an **associated** type rather than a parameter, because a cursor walks one kind of
thing and the choice is the cursor's. That is what lets a signature be generic over what it walks
-- `count_all[I: Iterate](it: I)` names no element and needs none, where `Iterate[E]` would have
asked for an `E` nothing in the call could say. An object still says which: `*Iterate[string]`.

| Member | Signature | Description |
|---|---|---|
| `next` | `next(*self) -> Option[Self::Item]` |  |

### `Mul`

```sysl
trait Mul[Rhs = Self, Out = Self]
    mul(self, rhs: Rhs) -> Out
```

| Member | Signature | Description |
|---|---|---|
| `mul` | `mul(self, rhs: Rhs) -> Out` |  |

### `Neg`

```sysl
trait Neg
    neg(self) -> Self
```

| Member | Signature | Description |
|---|---|---|
| `neg` | `neg(self) -> Self` |  |

### `Not`

```sysl
trait Not
    not(self) -> Self
```

| Member | Signature | Description |
|---|---|---|
| `not` | `not(self) -> Self` |  |

### `One`

```sysl
trait One
    one() -> Self
```

The value `x * one() == x` holds for.

| Member | Signature | Description |
|---|---|---|
| `one` | `one() -> Self` |  |

### `Ord`

```sysl
trait Ord
    lt(self, rhs: Self) -> bool
```

| Member | Signature | Description |
|---|---|---|
| `lt` | `lt(self, rhs: Self) -> bool` |  |

### `Rem`

```sysl
trait Rem[Rhs = Self, Out = Self]
    rem(self, rhs: Rhs) -> Out
```

| Member | Signature | Description |
|---|---|---|
| `rem` | `rem(self, rhs: Rhs) -> Out` |  |

### `Shl`

```sysl
trait Shl[Rhs = Self, Out = Self]
    shl(self, rhs: Rhs) -> Out
```

| Member | Signature | Description |
|---|---|---|
| `shl` | `shl(self, rhs: Rhs) -> Out` |  |

### `Shr`

```sysl
trait Shr[Rhs = Self, Out = Self]
    shr(self, rhs: Rhs) -> Out
```

| Member | Signature | Description |
|---|---|---|
| `shr` | `shr(self, rhs: Rhs) -> Out` |  |

### `Sub`

```sysl
trait Sub[Rhs = Self, Out = Self]
    sub(self, rhs: Rhs) -> Out
```

| Member | Signature | Description |
|---|---|---|
| `sub` | `sub(self, rhs: Rhs) -> Out` |  |

### `Writer`

```sysl
trait Writer: Fallible
    write(*self, bytes: []const u8)
```

The sink every rendering goes through (`library/core.md § Rendering to a sink`).

It takes bytes rather than a `string` because that is the direction that is free -- a `string`
*is* a validated `[]u8` -- and it reports failure by latching rather than by returning, so an
implementation stays straight-line and `print(x)` stays a statement.

The latch itself is `Fallible`, in the file beside this one, and is **required rather than
declared here**: reading asks the same question, and a type that does both may carry only one
answer to it. A sink that cannot fail writes `impl Fallible` for itself and nothing inside the
block: `failed` has a default and *membership* does not, so the opt-in is still written while the
answer is not (`reference/traits.md § Conformance is explicit, always`). One that can fail -- a
bounded buffer, a device that goes away -- says so once, and nothing about the latch changes.

| Member | Signature | Description |
|---|---|---|
| `write` | `write(*self, bytes: []const u8)` | *The bytes are borrowed for the call and may not be kept**, which is what keeps rendering allocation-free: a renderer writes into a buffer on its own stack and passes a slice of it, and without the promise every such call would put that buffer on the heap. |

### `Zero`

```sysl
trait Zero
    zero() -> Self
```

The value `x + zero() == x` holds for.

| Member | Signature | Description |
|---|---|---|
| `zero` | `zero() -> Self` |  |

## Implementations

### Display for (..A)

```sysl
impl[A: Display] Display for (..A)
```

Rendered as one field: a specifier describes the field the *whole* value occupies
(`library/core.md § A specifier is the whole value's field`), so `%8s` on a pair pads the pair and
not its first element.

**It is measured rather than gathered**, which is what a compound rendering costs when it is done
the obvious way: building the text first meant one string per element plus one per separator, all
of them thrown away as soon as they were written. Running the same writes into a `Counting` sink
answers the same question and stores nothing, and the sink is built only where a width was asked
for -- so an ordinary print of a tuple costs one pass and allocates nothing at all.

### Display for []T

```sysl
impl[T: Display] Display for []T
```

### Display for [N]T

```sysl
impl[const N: usize, T: Display] Display for [N]T
```

Every array of a printable element, at every length -- one block, because a length is a value
parameter (`reference/generics.md § A parameter may stand for a value`) and so an argument to the
shape rather than part of it. Before that, one `impl` per length was the only way to write this
and no library could write them all.

The whole-array view is the elements a slice would have walked, so `Display for []T` renders it
and this one is a delegation rather than a second copy of the padding.

### Display for bf16

```sysl
impl Display for bf16
```

### Display for bool

```sysl
impl Display for bool
```

### Display for char

```sysl
impl Display for char
```

### Display for f16

```sysl
impl Display for f16
```

### Display for f32

```sysl
impl Display for f32
```

The narrower floats widen on the way in, which is what the lowering did for them before: one
renderer per kind rather than one per type, and `%g` is written against a `double`.

Widening is exact at all three widths -- a `real` holds every value any of them can -- so what is
rendered is the value the narrower type actually holds, not an approximation of it. That is why
`f16`'s nearest tenth prints as something other than a tenth: the digits are the ones the width
really has.

### Display for Option[T]

```sysl
impl[T: Display] Display for Option[T]
```

An option renders as it is written -- `Some(3)` or `None` -- which is what makes a failed
`assert_eq` on one readable at all. `Eq` alone would not have been enough: `assert_eq` is bounded
over `Eq + Display`, so a comparable option nothing could render would still have been refused.

The width is honoured by rendering twice into a counter first, exactly as a slice does: a field is
the whole value, so the padding cannot be decided until the length of the whole is known, and
measuring this way costs no allocation.

### Display for real

```sysl
impl Display for real
```

### Display for Result[T, E]

```sysl
impl[T: Display, E: Display] Display for Result[T, E]
```

A result renders as it is written -- `Ok(3)` or `Err(NotFound)` -- which is what makes a failed
`assert_eq` on one readable. `assert_eq` is bounded over `Eq + Display`, so this is not a
convenience beside `Eq for Result[T, E]`: without it a comparable result is still refused, and the
refusal talks about `Display` rather than about the comparison.

The width is honoured by rendering twice into a counter first, exactly as a slice does: a field is
the whole value, so the padding cannot be decided until the length of the whole is known, and
measuring this way costs no allocation.

### Display for string

```sysl
impl Display for string
```

### Display for T

```sysl
impl[T: Integer] Display for T
```

### Eq for (..A)

```sysl
impl[A: Eq] Eq for (..A)
```

### Eq for []T

```sysl
impl[T: Eq] Eq for []T
```

Same length, same elements, same order -- which is the equality a sequence has when its elements
have one, and the reason it is written here rather than left to each caller.

`sysl.slices.equal` answers the same question and stays: it is a free function over `[]const T`
for a caller that has one, and the module it lives in is `@no_alloc` and reachable from a
freestanding target. What the block adds is `==` itself, and with it every generic bounded by
`Eq` -- `assert_eq` above all, which is where the absence was found, since a bare `bool` says a
comparison failed and neither of the two sequences that disagreed.

The length test comes first, so the common refusal costs nothing.

### Eq for [N]T

```sysl
impl[const N: usize, T: Eq] Eq for [N]T
```

Every array of an equatable element, at every length -- the whole-array view is the elements a
slice would have walked, so this is a delegation rather than a second copy of the loop, exactly as
`Display for [N]T` delegates to `Display for []T`.

### Eq for Option[T]

```sysl
impl[T: Eq] Eq for Option[T]
```

Two options are equal when they are absent alike, or present with equal payloads -- which is the
only reading that keeps `==` agreeing with `match`. The bound is on the payload rather than on the
whole, so an `Option` of something incomparable simply is not comparable, and says so at the
comparison rather than at the declaration.

### Eq for Result[T, E]

```sysl
impl[T: Eq, E: Eq] Eq for Result[T, E]
```

Two results are equal when they took the same road and agree at the end of it -- which is the only
reading that keeps `==` agreeing with `match`. An `Ok` is never equal to an `Err` however the two
payloads compare, and they need not even be comparable to each other: the bounds are on the two
parameters separately, so a `Result[int, IoError]` compares exactly when both halves do.

### Fallible for Counting

```sysl
impl Fallible for Counting
```

### Fallible for Stderr

```sysl
impl Fallible for Stderr
```

### Fallible for Stdout

```sysl
impl Fallible for Stdout
```

### Hash for (..A)

```sysl
impl[A: Hash] Hash for (..A)
```

Mixed with the same FNV prime the string hash uses, so that the parts' order matters -- `(1, 2)`
and `(2, 1)` are different keys, and a plain XOR would make them the same one. The fold starts at
the FNV offset basis rather than at the first part's hash, which is what lets one loop cover every
arity rather than treating the first position as the seed.

### Hash for [N]T

```sysl
impl[const N: usize, T: Hash] Hash for [N]T
```

Mixed with the same FNV prime the tuple's hash uses, so that position matters: `[1, 2]` and
`[2, 1]` are different keys, where a plain XOR would make them one.

**An array is hashable and a SLICE IS NOT, which is a decision rather than an omission.** A hash
is a promise about a key, and a slice is a view: putting one in a table leaves the storage behind
it reachable and writable by whoever owns it, so the key can change under the table and the entry
is then unfindable. An array is a value and copies, so it has no such owner to be surprised by.
A caller holding a slice it means as a key hashes something it owns -- a `string`, or an array
built from the elements.

### Hash for bool

```sysl
impl Hash for bool
```

### Hash for char

```sysl
impl Hash for char
```

### Hash for string

```sysl
impl Hash for string
```

### Hash for T

```sysl
impl[T: Integer] Hash for T
```

The integers, which are the family that is **open** -- `i5` and `u24` are types a program may
name, so no finite list of blocks could cover them. One block over the whole family says it
instead, exactly as `Display`'s does.

**The widening is the law, not an implementation detail.** Two integer types compare equal across
widths, so they have to hash equal too: `1` and `1` are the same number and reach the same
mixer having become the same `u64`. Past 64 bits the value is mixed in two halves rather than
truncated, because a 128-bit identifier is the reason to have the width at all and dropping its
top half would bucket every one of them together.

### Iterate for Range[T]

```sysl
impl[T: Integer + Add + One] Iterate for Range[T]
```

Walking one, which is what makes a range value worth having.

**`Integer` does not imply `Add`, which is why the bound reads as long as it does.** The trait is
declared `Integer: Div + Rem + Ord + Eq + Sub + Mul` — the list `Display`'s blanket body happens to
use — so a body bounded by it may divide an integer and may not add one. `Add` and `One` are named
here for that reason rather than for anything about ranges.

**The inclusive end is cleared rather than stepped past**, which is the whole subtlety here. The
obvious spelling advances `lo` after yielding and tests `lo > hi`; at `0..255u8` that advance
wraps to zero and the walk starts again, because there is no `u8` one greater than the last one.
So on yielding the element equal to `hi` this clears `inclusive` and leaves `lo` where it is: the
half-open test then stops the next call, and nothing is ever incremented past the end.

### One for bf16

```sysl
impl One for bf16
```

### One for f16

```sysl
impl One for f16
```

### One for f32

```sysl
impl One for f32
```

### One for real

```sysl
impl One for real
```

### Ord for (..A)

```sysl
impl[A: Ord] Ord for (..A)
```

Lexicographic, and written as a ladder rather than as `<` on each part in turn because deciding a
position takes *two* comparisons: this one is less, or it is greater, or the two agree and the
next position decides. Every position runs the same ladder and all-tied ends `false` -- a tuple is
not less than one it agrees with everywhere. Written per arity this needed a last position that
ended with a bare `<`, having no next position to fall through to; the loop has no such case.

### Ord for []T

```sysl
impl[T: Ord] Ord for []T
```

Lexicographic, first element first, and a shorter sequence ordering before a longer one it is a
prefix of -- which is the rule a string already follows and the one a tuple's `Ord` follows over
its parts.

The ladder is the tuple's and is written for the same reason: deciding a position takes **two**
comparisons, since this one is less, or it is greater, or the two agree and the next position
decides. All-tied then falls through to the lengths, so `[1, 2]` is less than `[1, 2, 3]` and two
equal sequences are not less than each other.

### Ord for [N]T

```sysl
impl[const N: usize, T: Ord] Ord for [N]T
```

Every array of an orderable element, delegating to the slice exactly as `Eq` does. Two arrays of
one type have one length, so the length test the slice ends on can never decide here.

### Writer for Counting

```sysl
impl Writer for Counting
```

### Writer for Stderr

```sysl
impl Writer for Stderr
```

### Writer for Stdout

```sysl
impl Writer for Stdout
```

### Zero for bf16

```sysl
impl Zero for bf16
```

### Zero for f16

```sysl
impl Zero for f16
```

### Zero for f32

```sysl
impl Zero for f32
```

### Zero for real

```sysl
impl Zero for real
```
