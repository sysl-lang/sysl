---
title: Strings
summary: An immutable, validated `[]u8` — the representation, the guarantee, every form that makes new bytes, and where the allocator line falls.
weight: 78
---

A `string` is a **three-word owning view of validated UTF-8 bytes** — the same shape every
[slice](/reference/arrays/) has, with one thing added and one taken away:

| | |
|---|---|
| `owner` | the counted buffer keeping the bytes alive, or **null** for immortal bytes |
| `ptr` | the first byte of *this* string — an interior pointer into the owner's bytes |
| `len` | the length in **bytes**, not characters |

Added: the bytes are guaranteed well-formed UTF-8. Taken away: nothing may write through it. Every
rule on the [arrays page](/reference/arrays/) about indexing, slicing, length and ownership is
therefore true here too, and this page is the two differences and what follows from them.

**`ptr` is separate from `owner` because a substring must name a range inside its parent's buffer
while still keeping that parent alive.** Two words cannot do both jobs: with only `{ptr, len}`,
release has no way back to the buffer header, so either substrings copy or lifetime goes unchecked.
The third word buys O(1) substring sharing for eight bytes.

That is the trade against the three languages this came from. Go's two words work because a garbage
collector owns the bytes and can find the object from an interior pointer; sysl has no collector, so
those two words leave lifetime unanswered. Swift's packed sixteen hide three representations behind a
discriminator, require an allocator unconditionally, and carry a grapheme-cluster element type whose
break tables cannot be in a kernel. Rust's split — a `&str` view against an owning `String` — is the
honest two-word answer, and the price is that every signature and every programmer chooses between
two types. One type at three words is the trade this language prefers.

## Validity

**Every `string` is well-formed UTF-8.** This follows `char`, which already enforces that its value
is a Unicode scalar value: a `string` that could hold arbitrary bytes would make decoding partial
again, and would hand a decoder a way to produce a `char` that cannot exist.

This is Swift's guarantee rather than Go's. Go strings are arbitrary bytes that are UTF-8 by
convention, so its decoder must substitute U+FFFD and advance one byte on malformed input. Here there
is no repair path, because there is nothing to repair.

| from | spelling | behaviour |
|---|---|---|
| a literal | `"héllo"` | validated while compiling |
| bytes | `from_utf8(b: []const u8)` | validates; the error names the byte offset |
| bytes, trusted | `from_utf8_unchecked(b)` | **unsafe** — the long name is the point: it stays greppable |
| a `char` | `string(c)` | encodes one scalar value |

```sysl
import sysl.text.from_utf8

var good: [2]u8 = [0xC3u8, 0xA9u8]
var bad: [2]u8 = [0xC3u8, 0x28u8]

from_utf8(good[..]) match
    Ok(t) -> print("ok", t.len, t)
    Err(e) -> print("bad", e.offset)

from_utf8(bad[..]) match
    Ok(t) -> print("ok", t.len)
    Err(e) -> print("bad at", e.offset, "truncated:", e.truncated)
```

```output
ok 2 é
bad at 0 truncated: false
```

**`Utf8Error` carries one thing besides the offset: whether the input merely *ended* in the middle of
a sequence.** That is the only distinction a caller can act on differently — more bytes would fix an
unfinished sequence and could never fix a wrong one — which is why it is a field rather than a
taxonomy of fault names nobody would match on. The pair above is the difference: `C3 28` is a lead
byte followed by something that is not a continuation, so no amount of further input rescues it.

**The validator is Unicode's well-formedness table, not a decode-then-range-check**, and the
difference is not stylistic. In the table the *lead* byte fixes the legal range of the byte after it
— `E0` demands `A0..BF`, `ED` only `80..9F`, `F0` demands `90..BF`, `F4` only `80..8F` — so an
overlong encoding, a surrogate, and a value past `10FFFF` are all rejected at the second byte, by the
same test, before any codepoint is assembled. Written the other way each needs its own check and each
is its own chance to be forgotten.

**`from_utf8` copies rather than views.** A `string` could in principle share a `[]u8`'s owner, which
would make the conversion O(1) — but a slice is writable and a string is not, so a later write
through the slice would change a value that had already been checked. Copying is what makes
validation mean anything afterwards, and it is why that entry requires an allocator.

**The division of labour is the reason `from_utf8` is not built in.** The compiler supplies exactly
one primitive — `from_utf8_unchecked`, which is a `[]u8` taken as a `string` with nothing looked at —
and the validator on top of it is ordinary sysl in [`sysl.text`](/library/text/). What no sysl body
could do is that last line, because every safe route to a `string` already carries the guarantee.

```sysl
var bytes: [3]u8 = [104u8, 105u8, 33u8]

print(from_utf8_unchecked(bytes[..]))
```

```output
hi!
```

It needs no import: it is a compiler primitive, deliberately in the same category as a raw pointer,
because breaking the UTF-8 invariant breaks `char`'s invariant downstream.

## Immortal bytes

A string literal is bytes in read-only data with **no owner at all** — the owner word is null, and
retain and release both test for that and do nothing. Three consequences:

- A literal costs no allocation and no refcount traffic. It is a *constant*, so it needs no
  instruction to build either.
- Allocator-free code can hold, pass, compare, index and slice literals.
- Any string derived from a literal by slicing is immortal too, because it shares the owner.
- A module-level `val` may hold one. That storage exists for the whole run and is never let go of, so
  a value that owed a release would have nowhere to write it — and a literal owes none.

A sentinel refcount in a header would say the same thing. The null owner is better because it is not
string-specific: it is already how a slice of static storage says "nothing to keep alive", so
immortality needs no mechanism of its own.

## Granularity: bytes and scalar values

A string is indexed and measured in **bytes**, and decoded into **`char`** — Unicode scalar values.
Grapheme clusters are a library built over the scalar view, not the element type.

The reason is where the Unicode data would have to live. Grapheme breaking needs tables that must not
be in a kernel, and making the default element type the one that requires them would put a length at
O(n) and force an opaque index type on every program that just wants a byte offset. Go's choice is
right for a systems language; Swift's is right for an application language.

| operation | spelling | cost |
|---|---|---|
| byte length | `s.len` | O(1) |
| byte at an index | `s[i] -> u8` | O(1), bounds-checked |
| substring | `s[a..b] -> string` | O(1), shares; bounds-checked **and** boundary-checked |
| bytes | `s.bytes -> []const u8` | O(1) view |
| scalar values | `s.chars` | O(1) per step, total — no replacement characters |
| copy out | `s.copy()` | O(n), allocates; releases the parent |
| concatenation | `a + b` | O(n), allocates |
| repeated append | `str_builder()` | amortized |

```sysl
var s = "héllo"

print(s.len, s[0], s[1], s[2])

var count = 0

for c in s.chars do count += 1

print(count, s.bytes.len)
```

```output
6 104 195 169
5 6
```

Six bytes, five characters, and the `é` is the two bytes `195 169` sitting where one index used to be
enough. That gap between the two counts is the whole of what this section is about.

### Indexing gives a byte, and there is no way to write one

```sysl
var s = "hello"

s[0] = 65u8
```

```error
a string is immutable, so its bytes have no address to write through
```

`s.bytes` does not open a way round it, and gets a diagnostic of its own that says why:

```sysl
var s = "hello"

s.bytes[0] = 65u8
```

```error
'bytes' views the string's own storage rather than a copy of it — so writing through one is writing the string
```

The bytes are a `[]const u8`, which is the type that records exactly this, and the way out is the one
that type always names: copy them into a `[]u8` of your own first.

### Slicing is boundary-checked

```sysl
var s = "héllo"

print(s[0..<1], s[1..<3], s[3..])
```

```output
h é llo
```

`s[a..b]` must land on scalar-value boundaries at **both** ends. Landing mid-character traps, in the
same runtime-safety category as a bounds check and a failed `char(u)`:

```sysl
var s = "héllo"

print(s[0..<2])
```

Byte 2 is the second half of the `é`, so that program stops there — no message, no unwinding.

Go permits the mid-character slice and lets you build an invalid string with it. That option is
closed here by the validity guarantee, and it is closed for a string that arrived through `from_utf8`
exactly as it is for a literal: validating at the door is worth nothing if something downstream may
undo it.

### Walking the characters

`s.chars` is a **cursor** — a value implementing `Iterate[char]` — and it is the one row of the table
above that could not be a value or a view. Every other row yields a value; this one yields a
*sequence*, and the decoding is what makes the scalar values, so something has to carry a position
and answer "the next one".

The cursor validates nothing. A `string` is well-formed by construction, so the decoding reads the
length off the lead byte and takes the continuation bytes as given — which is why the cost is O(1)
per step and why there are no replacement characters to hand back.

A `for` walks a **copy** of a cursor, so a loop cannot be asked afterwards where it got to. A program
that needs to know drives the cursor itself, and gets three more answers, all by value, so asking
consumes nothing:

```sysl
var s = "héllo"
var cur = s.chars

print(cur.offset, cur.count(), cur.peek().unwrap())

cur.next()

print(cur.offset, cur.peek().unwrap())
```

```output
0 5 h
1 é
```

That shape is a lexer's, and it was underserved before those existed: a reader with only "the next
one" indexes bytes by hand and decodes a second time to find out where it is.

**`char_indices` is the paired walk**, yielding each character with the offset of its **first** byte
— Rust's name, for Rust's reason. That the offset is the first byte is what makes it directly usable:
a slice built from two reported offsets lands on boundaries by construction, and since `s[a..b]`
traps on a mid-character bound, that is a guarantee rather than a convention.

```sysl
import sysl.text.char_indices

var s = "héllo"

for pair in char_indices(s.bytes)
    val (i, c) = pair
    print(i, c)
```

```output
0 h
1 é
3 l
4 l
5 o
```

The offsets go `0 1 3` rather than `0 1 2`, which is the two-byte `é` visible from the other side. It
wraps a `Chars` rather than decoding for itself, so there is one decoder and the two cursors cannot
come to disagree about a width.

**`is_char_boundary` is the same question about a single byte** — one mask and one comparison, since
the continuation byte is the only one matching `10xxxxxx`. It is what a program walking backwards, or
snapping an arbitrary offset onto a boundary, would otherwise write inline:

```sysl
import sysl.text.is_char_boundary

var s = "héllo"
var b = s.bytes

print(is_char_boundary(b[1]), is_char_boundary(b[2]), is_char_boundary(b[3]))
```

```output
true false true
```

## Comparison is by bytes

`==`, `<` and the rest compare the byte sequences. For well-formed UTF-8 that is also codepoint order,
so the ordering is the useful one. A literal is also a pattern, matched by the same comparison:

```sysl
var s = "héllo"

print("abc" < "abd", "abc" == "abc", s == "héllo")

var verdict = "yes" match
    "no" -> 0
    "yes" -> 1
    else 2

print(verdict)
```

```output
true true true
1
```

**Normalization is not applied.** Swift's `==` compares by canonical equivalence, so a composed `"é"`
equals a decomposed one — correct for user-facing text, surprising and expensive in systems code,
where a string is usually a path, a device name, or a protocol token that must compare as the bytes
it is. Normalization and collation are library operations, applied where they are wanted and visible
when they cost something.

## Concatenation

`a + b` joins two strings and `s += t` appends onto a slot. Both allocate: the result is a fresh
buffer — an ordinary counted heap object — so it owns a count of its own and frees itself like any
other reference. UTF-8 is closed under concatenation, so the validity invariant is preserved for free
and nothing is re-checked. The operands are copied out rather than aliased, so an operand that was
itself a substring keeps no hold on the result.

```sysl
var a = "hé"
var t = a + "llo"

t += "!"

print(t, t.len)
```

```output
héllo! 7
```

**`+` is strict**: it joins a `string` to a `string` and nothing else.

```sysl
var n = 7

print("n=" + n)
```

```error
'+' needs matching types, got string and int
```

That is the same no-implicit-coercion stance the numeric operators take, where a mixed-width sum is
an error asking for a conversion. The way to build a string out of values of other types is
interpolation, where the conversion is written where it happens; making `+` polymorphic over
"anything with a string form" would reintroduce exactly the invisible conversion the rest of the
language refuses.

`+` is the only arithmetic operator a string defines. The rest are rejected as they are for any type
that does not define them.

## Rendering a value

`str(x)` is the written conversion from a value to its string form — the counterpart to the strict
`+`, and what interpolation is built on.

```sysl
print(str(42), str(true), str('é'), str(2.5), str("already"))
```

```output
42 true é 2.5 already
```

| type | result |
|---|---|
| integer | its decimal digits, with a sign for a negative signed value |
| `bool` | `"true"` or `"false"` |
| `char` | the one scalar value's UTF-8 |
| float | the same `%g` rendering `print` gives it |
| `string` | itself, unchanged |

Every case but a `string` allocates a fresh buffer; a `string` is returned as it is, and a `bool`
renders to one of two immortal literals and allocates nothing. An integer is rendered without the C
library — the digits are divided out into a scratch buffer, which is correct even for the most
negative value because the magnitude is taken in unsigned arithmetic. A float goes through
`snprintf`, the one case that needs libc, chosen so that `str(x)` and `print(x)` can never disagree.

**Any other type renders through `Display`.** A struct or an enum carrying an `impl` writes itself
into a growable buffer, and the bytes that land there become the string — so `str` of a user type is
an ordinary call, and `str` of one *without* an implementation names the `impl` to write:

```sysl
struct Point
    x: int
    y: int
end Point

var p = Point(1, 2)

print(str(p))
```

```error
write an 'impl sysl.Display for Point' to say how it renders
```

A reference, a pointer, a slice and an array remain errors, since none of them can carry an `impl`.

## Interpolation

An interpolated string is a literal with a prefix, and inside it `$name` or `${ expression }`
splices a value in. `$$` is one literal dollar.

```sysl
var n = 7

print(s"n is $n, ${n * 2} doubled, $$5")
```

```output
n is 7, 14 doubled, $5
```

Each spliced value is rendered by `str`, so the same rules apply: a primitive renders, and a type
with no string form is an error at the splice. The whole thing desugars to the machinery already
built — `s"a${e}b"` is exactly `"a" + str(e) + "b"` — so an interpolation is not a new kind of value,
just a concise way to write a concatenation. A hole holds a full expression, which may itself
interpolate, and an empty literal segment beside a hole is dropped, since it is the identity under
`+`.

This follows Scala's interpolators, and deliberately not a `printf`-style format string in the
default form: the value and the text around it stay where they are read, and the conversion is `str`
applied at the splice rather than a directive parsed out of a separate string.

### Format specifiers

`f"…"` adds one thing: a hole may be followed by a printf specifier controlling width, precision,
sign and justification.

```sysl
var n = 7

print(f"${n}%03d|${2.5}%08.2f|${"hi"}%-5s|")
```

```output
007|00002.50|hi   |
```

A hole with no specifier renders through `str` exactly as in an `s"…"` string. A specifier binds only
to the `%` written immediately after a hole, so a bare `%` elsewhere in the text — `f"${n}%d done,
100% sure"` — is ordinary text. Keeping the specifier beside its value is the point of putting it
after the hole rather than in a separate format string.

**The conversion is checked against the value's type while compiling:**

```sysl
var n = 7

print(f"${n}%s")
```

```error
format '%s' expects a string, but the value has type int
```

`%d %i %x %X %o %u` want an integer, `%f %e %g %E %G` a float, and `%s` a string. An unsigned
conversion reads the value at its own width — `%x` of an `i32 -1` is `ffffffff`, of a `u8 255` is
`ff` — while `%d` keeps the value's sign. A string is copied NUL-terminated so that C's `%s` can
apply width and precision, which means an interior NUL ends the field there, as it does for any `%s`.

`s`, `raw` and `f` are only prefixes when written directly against the opening quote. Used as
ordinary names they are unaffected, so `s + raw` and `f + 1` are ordinary expressions.

## Literals

Double-quoted and UTF-8, with the usual escape table. A one-quote literal may not span a line break,
and a comment marker inside one is ordinary text. `raw"…"` does no escape decoding:

```sysl
print(raw"a\nb".len, "a\nb".len, raw"a\nb")
```

```output
4 3 a\nb
```

### Text blocks

A literal that spans lines is written `"""` … `"""`. The content begins on the line **after** the
opening delimiter, which is what gives the indentation rule an anchor: the opening delimiter's own
column then means nothing, so the form reads the same at any depth of nesting.

```sysl
run() -> string
    val block = """
        one
          two
        three
        """
    block

print(run().len)
prints(run())
```

```output
16
one
  two
three
```

Three things happen to a line inside a block that do not happen inside `"…"`.

**Its incidental indentation is dropped.** The strip is the least indented line that carries content,
together with the line the closing delimiter sits on when it sits alone on one — so the closing
delimiter is the control, and there is no margin character to remember. Move it left and the value
keeps more; right and it keeps less. This matters more here than in a free-form language: a block
written inside an indented body would otherwise carry that body's indentation in its value, and the
depth a piece of code sits at is not something its data should record.

**Its trailing blanks are dropped**, because whitespace at the end of a line is invisible in a source
file and would otherwise enter the value unseen. One that is *meant* is written `\u{20}`, which
survives because escapes are read after the trimming rather than before it. A carriage return goes
with the rest of a line's trailing whitespace, so a block means the same thing in a file with either
line ending.

**A `\` at the end of a line joins it to the next**, which is the form's reason for existing:
embedded data wants no line breaks in its value at all.

```sysl
digest() -> string
    """
    dead\
    beef\
    cafe
    """

prints(digest())
```

```output
deadbeefcafe
```

The joining happens in the lexer, so a block is a **single constant** — where the same data assembled
with `+` allocates and copies once per piece.

Whether the value ends with a newline needs no rule of its own: a closing delimiter alone on its line
is reached *after* the last content line's break has been taken, and one that follows content is
reached before any break at all. A lone `"` inside a block is ordinary text, which is the other thing
the form buys; a block cannot end with one, and `\"` writes it.

**The prefixes are orthogonal to the quote form.** `c"""`, `s"""`, `f"""` and `raw"""` all compose,
since a prefix says how a literal is *read* and the quote form says how it was *written*. A `raw`
block does no escape decoding, so nothing joins its lines.

## Making new bytes

Four operations produce a string that is not a substring of one already there.

**`s.copy()`** stops a substring holding its parent alive. Slicing is O(1) and shares the buffer,
which carries Go's hazard with it: a small substring keeps the *whole* parent buffer alive. Swift
addresses that with a distinct `Substring` type you must explicitly copy out of; sysl does not add a
type for it — the operation that copies out is named, and the hazard is documented rather than
encoded.

```sysl
var big = "the quick brown fox"
var piece = big[4..<9]
var alone = piece.copy()

print(piece, alone, piece == alone)
```

```output
quick quick true
```

**`string(c)`** encodes one scalar value:

```sysl
print(string('é').len, string('a'))
```

```output
2 a
```

**`str_builder()`** gathers text without rebuilding what it already has. Concatenation allocates a
fresh buffer each time, so building a string a piece at a time with `+=` copies everything it has so
far on every step:

```sysl
import sysl.text.str_builder

var sb = str_builder()

sb.push("n=")
sb.push_int(42)
sb.push_char('!')

print(sb.finish())
```

```output
n=42!
```

**Every way in carries the guarantee.** A `push` takes a `string`, a `push_char` takes a `char`, and
the four renderers take a number or a `bool` — UTF-8 is closed under appending any of them, so
`finish` hands back a plain `string` rather than something a caller has to validate. That is why a
builder is **not** a `Writer`: a public `write` taking a `[]u8` would be `from_utf8_unchecked` with a
longer name and none of its greppability.

`push_int`, `push_uint`, `push_real` and `push_bool` exist so that gathering a number costs no
allocation — `push(str(n))` builds a whole counted string, copies its bytes out, and drops it, for a
value whose text is a couple of dozen bytes and is wanted only inside this buffer. They agree with
`str` to the byte, which is the property that makes the cheap path a substitute rather than a second
rendering: a program that builds half a line with a builder and half with an interpolation must not
be able to tell which half a number came through.

`finish` **copies rather than lending**, so a builder may go on being appended to and the string
already taken out of it does not change. The alternative — handing over the storage and leaving the
builder empty — would save a copy at the cost of a form whose meaning depends on how many times it
has been called.

**`cstring(s)`** is the fourth, and it is the next section.

## C interop

A `string` has no terminator, so passing one to C is an explicit, allocating conversion. `CString`
owns the copy: Go's version hands back a raw pointer and requires a matching `free`, and sysl has no
manual free to match, so the result is a value whose bytes go when it does. It offers two things —
`cs.ptr`, the `*u8` a C function takes, and `cs.len`, the byte length *not* counting the terminator,
so that it agrees with the `s.len` it came from.

```sysl
import sysl.text.cstring

extern strlen(s: *u8) -> usize

var cs = cstring("a\u{0}b")

print(cs.len, strlen(cs.ptr))
```

```output
3 1
```

**That disagreement is the whole reason the conversion is written rather than inferred.** For
`cstring("a\u{0}b")`, `cs.len` is 3 and C's `strlen` is 1. Both are right, and neither can be made to
be the other. The pointer carries a raw pointer's rule: valid while the `CString` is held, and
keeping it past that is the ordinary raw-pointer mistake rather than a new one. Passing
`cstring(s).ptr` straight into a call is safe, since the temporary lives for the statement; storing
that pointer and using it later is not.

**The literal case needs no allocation at all, and is spelled `c"…"`.** The compiler emits a NUL byte
after every string literal in read-only data — one byte, not counted in `len` — so a literal is
already sitting in memory in exactly the shape C reads. `c"…"` is that constant's address: a plain
`*u8`, no allocation, no copy, no runtime.

```sysl
extern strlen(s: *u8) -> usize

print(strlen(c"hello"))
```

```output
5
```

It is a distinct literal form rather than an inferred optimization, and deliberately: whether an
expression is *literally a literal* is not something a reader should have to work out, and a rule
that silently allocates for `s` but not for `"…"` hides a cost the language promises to show. It is
Rust's `c"…"` and Zig's null-terminated literal, for the reason both added it.

A `c"…"` containing an interior NUL is a compile error rather than a truncation:

```sysl
extern strlen(s: *u8) -> usize

print(strlen(c"a\u{0}b"))
```

```error
a C string ends at its first NUL, so it cannot contain one
```

An ordinary `"a\u{0}b"` is unaffected — it has three bytes and prints as three, because carrying a
length is the whole point — and only the free ride to C is lost, which is exactly where the
diagnostic belongs.

## The allocator-free subset

**The type is not gated; the operations that make new bytes are.** Legal under `no alloc`: holding,
passing, comparing, indexing, slicing, iterating and releasing any string — including a heap-backed
one handed in from outside, which frees itself through its own deallocation hook.

```sysl
no alloc
no os

var name = "/dev/console"
var tail = name[5..]

var count = 0

for c in name.chars do count += 1

print(tail, name.len, name[0], count, tail == "console", name < "/dev/null")
```

```output
console 12 47 12 true true
```

**A module-level `val` may hold one, which is where a table of messages lives.** Storage that exists
for the whole run is never let go of, so such a `val` normally holds nothing that owes a release —
but a literal owes none, so the refusal does not reach it ([`13 §7`](/reference/declarations/)):

```sysl
no alloc

val messages: [3]string = ["out of range", "not permitted", "no such device"]

var i = 2

print(messages[i], messages[i].len, messages[0][0..<3])
```

```output
no such device 14 out
```

A `const` could not have served that: a constant is folded into its uses and has no address, so
there is nothing to index at `i`.

Requiring an allocator: `from_utf8`, `copy()`, concatenation, `string(c)`, `str_builder()` and
`cstring` — every operation that produces new bytes.

```sysl
no alloc

var greeting = "hello"
var full = greeting + " world"

print(full)
```

```error
the string two strings join into needs an allocator, and this module declared 'no alloc'
```

The diagnostic finishes with the rule that makes the subset usable: such a module **may hold and
release storage made elsewhere, and may make none of its own.** So a module that only ever uses
literals sees only immortal strings and its retain and release compile away entirely; one that is
handed a heap-backed string pays ordinary refcount traffic and still links no allocator.

## What lives in the library

Everything the compiler writes for itself is reachable without an import, and everything a program
writes by name is [`sysl.text`](/library/text/). A literal, `+`, `str(x)`, an interpolation and
`s.chars` are all desugarings, so they cost no import — even `s.chars`, whose cursor is the library's,
because the compiler names it by key rather than by resolving the word.

Named at the call site, and so imported: `from_utf8`, `char_from_u32`, `str_builder`, `cstring`,
`char_indices`, `is_char_boundary`, the `parse_*` family, the text operations `split`, `fields`,
`join`, `repeat`, `replace_all`, `to_upper` and `to_lower`, the `Search` and `Ascii` traits, and the
types `Utf8Error`, `ParseError`, `Chars`, `CharIndices`, `StrBuilder` and `CString`. The split is the
one the [modules page](/reference/modules/) describes: what a program cannot avoid needing arrives
free, and what it has to ask for it asks for.

## Relationship to slices

A `string` is exactly an **immutable, validated `[]u8`** — the same three words, the same
retain-on-slice, one implementation underneath. Everything on this page about sharing and immortality
is the general slice rule rather than a string special case, and ownership crosses a `no alloc`
boundary for the general reason: every counted object carries a pointer to the function that frees
it.

The one string-specific addition is the validity invariant. A `[]u8` may hold any bytes and a
`string` is the subset that is well-formed UTF-8, which is why converting between them is checked in
one direction and free in the other.

| | Go | Swift | Rust | sysl |
|---|---|---|---|---|
| size | 16 B | 16 B packed | 24 B / 16 B | 24 B |
| representations | 1 | 3 | 2 types | 1 |
| owns its bytes | the collector does | yes | `String` yes, `&str` no | yes |
| O(1) substring | yes | yes, `Substring` | yes, `&str` | yes |
| valid UTF-8 guaranteed | no | yes | yes | yes |
| element | byte / `rune` | grapheme cluster | byte / `char` | byte / `char` |
| comparison | bytes | canonical equivalence | bytes | bytes |
| NUL-terminated | no | privately | no | no |
| usable without an allocator | no | no | `&str` yes | yes |

---

Next: [traits](/reference/traits/).
