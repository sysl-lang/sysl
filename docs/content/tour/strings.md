---
title: Strings
summary: An immutable, validated `[]u8` — the same three words, with a guarantee added and an operation taken away.
weight: 70
---

A `string` is the slice from the last chapter with one thing added and one taken away: its bytes are
guaranteed to be well-formed UTF-8, and nothing may write through it. Everything else — the three
words, the O(1) substring, the retain on slicing — is the slice machinery unchanged.

That guarantee is not decoration. It is what lets `char` mean "a Unicode scalar value" rather than
"whatever these bytes turned out to be", and it is why there are no replacement characters anywhere
in the language: there is nothing to repair.

## Bytes and characters

A string is measured and indexed in **bytes**, and decoded into **characters**:

```sysl
var s = "naïve"

var chars = 0

for c in s.chars do chars += 1

print("bytes:", s.len, "chars:", chars, "last byte:", s[s.len - 1])
```

```output
bytes: 6 chars: 5 last byte: 101
```

Six bytes and five characters, because `ï` takes two. `s[i]` is a `u8` — a byte, not a character —
and `s.chars` is a cursor that decodes them one scalar value at a time.

That is Go's choice rather than Swift's, and the reason is placement: grapheme clusters need Unicode
break tables, tables must not be in a kernel, and making them the default would put `s.len` at O(n)
for every program that only wanted a byte offset. Grapheme clusters are a library built over this,
not the thing underneath.

A substring shares its parent's bytes and costs no copy:

```sysl
var path = "/usr/local/bin"

print("front:", path[..<4], "back:", path[11..], "whole:", path.len)
```

```output
front: /usr back: bin whole: 14
```

Two checks happen there rather than one. The bounds are checked, as on any slice — and the ends are
checked for landing **between characters**. Slicing through the middle of a multi-byte character
traps, because the alternative is a `string` that is not valid UTF-8, and the whole type rests on
that not being possible.

The sharing has the hazard Go's has: a two-byte substring of a two-megabyte string keeps the whole
buffer alive. The operation that copies out of it is named — `s.copy()` — and the hazard is
documented rather than encoded in a second type.

## Joining

`+` joins two strings and `+=` appends onto a slot. Both allocate a fresh buffer; UTF-8 is closed
under concatenation, so nothing is re-validated.

```sysl
var name = "sysl"
var greeting = "hello, " + name

greeting += "!"

print(greeting, greeting.len)
```

```output
hello, sysl! 12
```

`+` is **strict**. It joins a string to a string and to nothing else:

```sysl
print("n=" + 5)
```

```error
'+' needs matching types, got string and int
```

That is the same no-implicit-conversion stance the numeric operators take. The written conversion is
`str(x)`, which renders a value into its string form:

```sysl
print("count: " + str(3 * 14))
print(str(true) + " " + str('é') + " " + str(2.5))
```

```output
count: 42
true é 2.5
```

Every case but a string allocates a fresh buffer. A `bool` renders to one of two literals and
allocates nothing at all, and a struct or an enum renders through its `Display` implementation.

## Interpolation

Writing `str` at every splice gets old, so a literal may carry a prefix:

```sysl
var n = 42
var word = "left"
var ratio = 2.0 / 3.0

print(s"n is $n, and twice that is ${n * 2}")
print(f"[${n}%6d] [${word}%-6s] ${ratio}%.3f")
print(raw"a\nb")
```

```output
n is 42, and twice that is 84
[    42] [left  ] 0.667
a\nb
```

`s"…"` splices `$name` or `${ expression }`, each rendered by `str` — so `s"a${e}b"` is exactly
`"a" + str(e) + "b"`, and interpolation is not a new kind of value. `f"…"` adds one thing: a hole may
be followed by a printf specifier controlling width, precision and justification, checked against the
value's type while compiling. `raw"…"` leaves a backslash as an ordinary character.

Note where the specifier sits — *after* the hole, not in a separate format string at the front. The
value and the way it is formatted stay next to each other, which is the whole reason for the
spelling.

## Literals that span lines

```sysl
var doc = """
    to whom it may concern:
    the indentation you see here is not in the value
    """

print(doc.len)
print(doc)
```

```output
73
to whom it may concern:
the indentation you see here is not in the value
```

The content starts on the line *after* the opening delimiter, and each line's incidental indentation
is dropped — the strip is the least-indented line with content, together with the closing delimiter's
own line when it sits alone. So the closing delimiter is the control: move it left and the value
keeps more indentation, right and it keeps less.

This matters more in an indentation-sensitive language than elsewhere. A block written inside a
deeply nested body would otherwise carry that body's indentation into its value, and how deep a piece
of code sits is not something its data should record.

Trailing blanks are dropped too, since whitespace at the end of a line is invisible in a source file.
A trailing space that is *meant* is written `\u{20}`, which survives because escapes are decoded after
the trimming. And a `\` at the end of a line joins it to the next, which is what the form is really
for: a blob of embedded data written over twenty lines is a single constant, where the same data
assembled with `+` would allocate and copy once per piece.

## Comparison, and matching

`==` and `<` compare the byte sequences, which for well-formed UTF-8 is also codepoint order.
Normalization is **not** applied — a composed `é` does not equal a decomposed one:

```sysl
describe(cmd: string) -> string
    cmd match
        "add" -> "combines"
        "del" -> "removes"
        _     -> "unknown"

print(describe("add"), describe("nope"), "add" < "del")
```

```output
combines unknown true
```

Swift compares by canonical equivalence, which is right for user-facing text and surprising in
systems code, where a string is usually a path, a device name, or a protocol token that has to
compare as the bytes it is. Normalization is a library operation, applied where it is wanted and
visible when it costs something.

## Building text a piece at a time

`+=` in a loop copies everything gathered so far on every step. `StrBuilder` keeps one growable
buffer instead:

```sysl
import sysl.text.str_builder

var b = str_builder()

b.push("items:")

for i in 1..3
    b.push_char(' ')
    b.push(str(i))

print(b.finish())
```

```output
items: 1 2 3
```

The two ways in are the two that keep the guarantee: a `push` takes a string and a `push_char` takes
a character, and UTF-8 is closed under appending either — so `finish` hands back a plain `string`
that nobody has to validate. That is exactly why a builder is *not* a `Writer`: a public `write`
taking arbitrary bytes would be an unchecked constructor with a friendlier name.

## Coming from bytes

Bytes a program computed are the one route into a string that can fail, so it is the one that returns
a `Result`:

```sysl
import sysl.text.from_utf8

var good: [3]u8 = [104, 105, 33]
var bad: [2]u8  = [104, 255]

from_utf8(good[..]) match
    Ok(s)  -> print("text:", s)
    Err(e) -> print("bad at", e.offset)

from_utf8(bad[..]) match
    Ok(s)  -> print("text:", s)
    Err(e) -> print("bad at", e.offset, "truncated:", e.truncated)
```

```output
text: hi!
bad at 1 truncated: false
```

The error carries the offending offset and one distinction a caller can act on: whether the input
merely *ended* mid-sequence, which more bytes would fix, or holds something no continuation could
rescue.

The bytes are copied rather than viewed, and that is deliberate — a slice is writable, so sharing
would let a later write change something that had already been checked. Copying is what makes the
validation mean anything afterwards.

## Talking to C

A sysl string carries a length and may hold a NUL as an ordinary byte, so there is no free conversion
to the shape C reads. For a literal there is no conversion needed at all — the compiler emits a NUL
after every string literal in read-only data, and `c"…"` is that constant's address:

```sysl
extern printf(fmt: *u8, ...) -> int

printf(c"%d items\n", 7)
```

```output
7 items
```

No allocation, no copy, no runtime. For a string that is not a literal, `cstring(s)` allocates a
NUL-terminated copy and hands back a `CString` that owns it — and the hazard the explicitness exists
for is worth stating as an equation: for `cstring("a\0b")`, `cs.len` is 3 and C's `strlen(cs.ptr)` is
1. Both are right, and neither can be made into the other.

## Literals cost nothing

A string literal is bytes in read-only data with **no owner at all** — the owner word is null, and
retain and release both test for that and do nothing. So a literal needs no allocation, no refcount
traffic, and not even an instruction to build.

That is what lets allocator-free code hold, pass, compare and slice strings: panic messages, device
node names, format fragments. Anything derived from a literal by slicing is immortal too, because it
shares the owner — which is to say it shares having none.

The rule for the whole type is that the *type* is not gated, the *allocating operations* are. Holding,
passing, comparing, indexing, slicing and iterating any string are free; the ones that make new bytes
— `+`, `str`, `copy()`, `from_utf8`, `str_builder`, `cstring` — are the ones a `no alloc` module may
not reach.

---

Next: [enums and patterns](/tour/enums/) — where `Option` and `Result` come from, and what `match`
can really do.
