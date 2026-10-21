---
title: Strings
summary: An immutable, validated `[]u8` — the same three words, with a guarantee added and an operation taken away.
weight: 70
---

A `string` is the slice from the previous chapter with one guarantee added and one operation removed:
its bytes are always well-formed UTF-8, and nothing may write through it. Everything else, the three
words, the O(1) substring and the retain on slicing, is the slice machinery unchanged.

The guarantee is what lets `char` mean "a Unicode scalar value" rather than "whatever these bytes
decode to", and it is why the language never produces replacement characters: invalid text cannot
exist, so there is nothing to repair.

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

Six bytes and five characters, because `ï` takes two bytes. `s[i]` is a `u8`, a byte rather than a
character, and `s.chars` is a cursor that decodes one scalar value at a time.

This follows Go rather than Swift. Grapheme clusters need Unicode break tables, those tables do not
belong in a kernel, and making clusters the default would make `s.len` O(n) for every program that
only needs a byte offset. Grapheme clusters are provided by a library built on top of this.

A substring shares its parent's bytes, with no copy:

```sysl
var path = "/usr/local/bin"

print("front:", path[..<4], "back:", path[11..], "whole:", path.len)
```

```output
front: /usr back: bin whole: 14
```

Slicing a string makes two checks. The bounds are checked, as on any slice, and each end is checked
to fall **between characters**. Slicing through the middle of a multi-byte character traps, because
otherwise the result would be a `string` that is not valid UTF-8.

Sharing has the same cost as in Go: a two-byte substring of a two-megabyte string keeps the whole
buffer alive. `s.copy()` copies the substring out when that matters.

`==` and `<` compare byte sequences, which for well-formed UTF-8 is also code-point order.
Normalization is **not** applied, so a precomposed `é` does not equal a decomposed one:

```sysl
describe(cmd: string) -> string
    cmd match
        "add" -> "combines"
        "del" -> "removes"
        _     -> "unknown"

print(describe("add"), describe("mv"), "add" < "del")
```

```output
combines unknown true
```

Swift compares by canonical equivalence, which suits user-facing text but is unexpected in systems
code, where a string is usually a path, a device name or a protocol token that must compare as its
exact bytes. Normalization is a library operation, applied explicitly where it is needed.

## Joining

`+` joins two strings and `+=` appends to a variable. Both allocate a new buffer. UTF-8 is closed
under concatenation, so the result is not re-validated.

```sysl
var name = "sysl"
var greeting = "hello, " + name

greeting += "!"

print(greeting, greeting.len)
```

```output
hello, sysl! 12
```

`+` is **strict**: it joins a string only to another string.

```sysl
print("n=" + 5)
```

```error
'+' needs matching types, got string and int
```

This matches the numeric operators, which also convert nothing implicitly. The explicit conversion
is `str(x)`, which renders a value as a string:

```sysl
print("count: " + str(3 * 14))
print(str(true) + " " + str('é') + " " + str(2.5))
```

```output
count: 42
true é 2.5
```

`str` allocates a new buffer for everything except a string, which it returns as is, and a `bool`,
which renders to one of two literals. A struct or an enum renders through its `Display`
implementation.

## Interpolation

To avoid writing `str` at every join, a literal may carry a prefix:

```sysl
var n = 42
var word = "left"
var ratio = 2.0 / 3

print(s"n is $n, and twice that is ${n * 2}")
print(f"[${n}%6d] [${word}%-6s] ${ratio}%.3f")
print(raw"a\nb")
```

```output
n is 42, and twice that is 84
[    42] [left  ] 0.667
a\nb
```

`s"…"` inserts `$name` or `${ expression }`, each rendered by `str`, so `s"a${e}b"` is exactly
`"a" + str(e) + "b"`; interpolation does not introduce a new kind of value. `f"…"` adds one thing:
a hole may be followed by a printf-style specifier for width, precision and justification, checked
against the value's type at compile time. `raw"…"` treats a backslash as an ordinary character.

The specifier comes *after* the hole rather than in a separate format string at the front, so each
value and its format stay together.

A literal may also span lines. `"""` opens a block whose content starts on the next line and whose
incidental indentation is removed, by the amount the closing delimiter is indented. Embedded text
written inside a deeply nested body therefore does not carry that body's indentation into its value.
[The reference](/reference/strings/#text-blocks) has the trimming rules.

## Building text incrementally

`+=` in a loop copies everything gathered so far on every iteration. `StrBuilder` keeps a single
growable buffer instead:

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

A builder accepts only input that preserves the guarantee: `push` takes a string and `push_char`
takes a character, and UTF-8 is closed under appending either. `finish` therefore returns a plain
`string` that needs no validation. For the same reason a builder is *not* a `Writer`: a public
`write` that accepted arbitrary bytes would be an unchecked way to construct a string.

Bytes computed by the program are the one source of a string that can fail, so converting them
returns a `Result`:

```sysl
import sysl.text.from_utf8

var good: [3]u8 = [104, 105, 33]
var bad: [2]u8  = [104, 255]

from_utf8(good) match
    Ok(s)  -> print("text:", s)
    Err(e) -> print("bad at", e.offset)

from_utf8(bad) match
    Ok(s)  -> print("text:", s)
    Err(e) -> print("bad at", e.offset, "truncated:", e.truncated)
```

```output
text: hi!
bad at 1 truncated: false
```

The error carries the offset of the invalid byte and one distinction a caller can act on: whether
the input simply *ended* in the middle of a sequence, which more bytes would complete, or contains
something no continuation could make valid.

The bytes are copied rather than viewed. A slice is writable, so sharing it would let a later write
change bytes that had already been validated; copying keeps the validation meaningful.

A string literal is bytes in read-only data with **no owner**: its owner word is null, and retain
and release test for that and do nothing. A literal therefore needs no allocation, no reference
counting, and no code to construct.

This lets code that runs without an allocator hold, pass, compare and slice strings: panic messages,
device node names, format fragments. A slice of a literal has no owner either, so it lives for the
whole program as well.

The type itself is never restricted; the *allocating operations* are. Holding, passing, comparing,
indexing, slicing and iterating a string cost nothing. The operations that create new bytes, `+`,
`str`, `copy()`, `from_utf8`, `str_builder` and `cstring`, are the ones a `no alloc` module may not
use.

---

Next: [enums and patterns](/tour/enums/), where `Option` and `Result` come from, and what `match`
can do.
