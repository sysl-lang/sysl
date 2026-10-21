---
title: sysl.encoding.json
layout: api-module
headingShift: 0
slugStyle: github
module: sysl.encoding.json
summary: "JSON, read and written: a document parsed into a value tree that owns itself, and a value tree written back out compactly or for a person to read."
---

**A document keeps the order it was written in.** An object's members are a sequence rather than
a map, so `{"b":1,"a":2}` writes back the way it arrived, and a name written twice keeps both
members. That is the only representation that round-trips, and it is the right trade at the sizes
JSON is used at -- a configuration file, a request body, a protocol message.

**A malformed document is refused with the place it went wrong**: a `JsonError` carries the line,
the column and the byte offset, and what was wrong there, so a message can point at it.

Nothing here opens or reads anything: `parse` takes text the caller already has, and writing goes
into any `Writer`. It needs a heap and nothing else, so it builds for a board that has one.

## Index

[`max_depth`](#max_depth) [`parse`](#parse) [`parse_bytes`](#parse_bytes) [`pretty`](#pretty) [`quoted`](#quoted) [`write_pretty`](#write_pretty) [`Fault`](#fault) [`Json`](#json) [`JsonError`](#jsonerror) [`Member`](#member) [Display for Fault](#display-for-fault) [Display for Json](#display-for-json) [Display for JsonError](#display-for-jsonerror) [Eq for Json](#eq-for-json) [Eq for Member](#eq-for-member)

## Constants

### `max_depth`

```sysl
const max_depth: usize = 128
```

How deep arrays and objects may nest before a document is refused.

The reader is recursive, so without a bound a hostile document of a hundred thousand `[` would
exhaust the stack. 128 is far past what a real document nests and small enough for a board's
stack.

## Functions

### `parse`

```sysl
parse(text: string) -> Result[Json, JsonError]
```

One whole JSON text read into a value, or why it is not one.

Whitespace may surround the value; anything else after it is refused. A number too large for a
`real` reads as an infinity.

### `parse_bytes`

```sysl
parse_bytes(b: []const u8) -> Result[Json, JsonError]
```

The same over bytes that may not be text, which is what arrives from a socket or a file: a string
holding bytes that are not UTF-8 is refused with `NotUtf8` rather than taken.

### `pretty`

```sysl
pretty(j: Json, step: usize) -> string
```

The value as JSON a person reads: one element or member to a line, indented by `step` spaces per
level, with a space after every colon. An empty array or object stays `[]` or `{}`.

### `quoted`

```sysl
quoted(s: string) -> string
```

A string as a JSON string literal, quotes and escapes included -- for a program that assembles
JSON by hand. The quote, the backslash and every byte below a space are escaped; everything else
stands for itself, since JSON text is UTF-8.

### `write_pretty`

```sysl
write_pretty(j: Json, step: usize, out: *Writer)
```

`pretty`'s rendering, written into a sink rather than gathered into a string.

## Types

### `Fault`

```sysl
enum Fault
    Expected(what: string)
    Ended(what: string)
    Trailing
    LeadingZero
    BadEscape
    BadHex
    LoneSurrogate
    Control
    Unclosed
    NotUtf8
    TooDeep
```

What was wrong at the place a `JsonError` names.

### `Json`

```sysl
enum Json
    Null
    Bool(b: bool)
    Int(n: long)
    Big(digits: string)
    Real(x: real)
    Str(s: string)
    Arr(items: Buf[Json])
    Obj(members: Buf[Member])
```

A JSON value: one of the things the grammar can produce.

**Numbers are three variants rather than one, so that an integer stays an integer.** JSON's
grammar has a single numeric production and says nothing about how it is stored. Carrying every
number as a `real` loses the exact value of an integer past 2^53, which is where an identifier or
a timestamp in milliseconds lives; carrying every number as a `long` cannot hold `0.5`. So:

- `Int` is a number written with no fraction and no exponent that fits a `long`;
- `Real` is a number written with a fraction or an exponent -- `1.0` is a `Real`, not an `Int`;
- `Big` is a whole number past a `long` in either direction, carried as the digits the document
  wrote (sign included), so it round-trips exactly. `sysl.math.bigint.parse_radix(d, 10)` computes
  with one.

A caller that does not care which asks `as_real`.

**The tree owns itself.** An array holds a `Buf[Json]` and an object a `Buf[Member]`, with no
reference anywhere, so every walk over a document is a plain `match`.

| Member | Signature | Description |
|---|---|---|
| `is_null` | `is_null(self) -> bool` | Whether this is `null` -- which is a value here, not the absence of one. |
| `as_bool` | `as_bool(self) -> Option[bool]` | The boolean this is, or `None` for every other shape. |
| `as_int` | `as_int(self) -> Option[long]` | The integer this is, only where the document wrote one that fits a `long`. |
| `as_real` | `as_real(self) -> Option[real]` | The number this is, whichever variant carries it -- the one accessor that widens. |
| `as_str` | `as_str(self) -> Option[string]` |  |
| `len` | `len(self) -> usize` | How many elements an array has, or how many members an object has. |
| `at` | `at(self, i: usize) -> Option[Json]` | The element at a position, for an array; `None` past the end and for anything else. |
| `member` | `member(self, i: usize) -> Option[Member]` | The member at a position, for an object, in the order the document wrote them -- how an object is walked when its names are not known in advance. |
| `get` | `get(self, key: string) -> Option[Json]` | The value written under a name, or `None` where there is none. |

### `JsonError`

```sysl
struct JsonError
    fault: Fault
    line: usize
    col: usize
    offset: usize
```

Why a document was refused, and where.

`line` and `col` count from one; a column counts characters, not bytes, so it is what an editor
shows. `offset` is the byte offset from the start of the text, counting from zero.

### `Member`

```sysl
struct Member
    key: string
    value: Json
```

One member of an object: the name it was written under, and what was written there.

## Implementations

### Display for Fault

```sysl
impl Display for Fault
```

### Display for Json

```sysl
impl Display for Json
```

The value as JSON, compactly. Rendering goes straight into the sink, so `print(j)` writes a large
document with nothing assembled in the middle.

### Display for JsonError

```sysl
impl Display for JsonError
```

`line:col: what`, the shape a compiler's diagnostic has.

### Eq for Json

```sysl
impl Eq for Json
```

Two values are equal when they are the same shape holding equal things.

**The numeric variants do not meet**: `Int(1)` and `Real(1.0)` are two different texts, and this
equality is about two documents saying the same thing. **Objects compare in order**, for the same
reason.

### Eq for Member

```sysl
impl Eq for Member
```

Two members are equal when they were written under the same name and hold equal values.
