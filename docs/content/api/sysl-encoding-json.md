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

[`max_depth`](#max_depth) [`from_json`](#from_json) [`from_json_bytes`](#from_json_bytes) [`json_decoder`](#json_decoder) [`json_encoder`](#json_encoder) [`parse`](#parse) [`parse_bytes`](#parse_bytes) [`pretty`](#pretty) [`quoted`](#quoted) [`to_json`](#to_json) [`write_pretty`](#write_pretty) [`Fault`](#fault) [`Json`](#json) [`JsonDecoder`](#jsondecoder) [`JsonEncoder`](#jsonencoder) [`JsonError`](#jsonerror) [`Member`](#member) [Decoder for JsonDecoder](#decoder-for-jsondecoder) [Display for Fault](#display-for-fault) [Display for Json](#display-for-json) [Display for JsonError](#display-for-jsonerror) [Encoder for JsonEncoder](#encoder-for-jsonencoder) [Eq for Json](#eq-for-json) [Eq for Member](#eq-for-member) [Fallible for JsonEncoder](#fallible-for-jsonencoder) [Writer for JsonEncoder](#writer-for-jsonencoder)

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

### `from_json`

```sysl
from_json[T](text: string) -> Result[T, CodecError]
```

A value of type `T` read from one JSON text, through `T`'s `Decode` (derived, or the library's
own for a scalar, a string, an `Option`, a `Buf`, a `Map` or a `Set`).

Whitespace may surround the value; anything else after it is refused. Every refusal names the
byte it was found at and the path to it, `users[3].home.zip: an integer expected (byte 214)`;
where the text breaks JSON's grammar, the words are the ones `parse` uses.

### `from_json_bytes`

```sysl
from_json_bytes[T](b: []const u8) -> Result[T, CodecError]
```

The same over bytes that may not be text: a string that is not UTF-8 is refused.

### `json_decoder`

```sysl
json_decoder(b: []const u8) -> JsonDecoder
```

A decoder at the start of `b`.

### `json_encoder`

```sysl
json_encoder(out: *Writer) -> JsonEncoder
```

An encoder writing into `out`.

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

### `to_json`

```sysl
to_json[T](v: T) -> Result[string, CodecError]
```

`v` as compact JSON, through its `Encode`; refused only where `v` holds a NaN or an infinity, with
the byte it would have been written at.

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

### `JsonDecoder`

```sysl
struct JsonDecoder
    private b: []const u8
    private at: usize
    private depth: usize
    private first: bool
```

A pull reader over one JSON text: `sysl.codec`'s `Decoder` for JSON, which `from_json` reads a
value through, and the reader `parse` builds its tree with. It reads the bytes it is given in
place and allocates only for a string it hands back.

### `JsonEncoder`

```sysl
struct JsonEncoder
    private out: *Writer
    private first: bool
    private written: usize
    private fault: Option[codec.Fault]
    private fault_at: usize
```

`sysl.codec`'s `Encoder` for JSON: compact JSON, written into any `Writer` as it goes.

A struct is an object, a sequence an array, `None` and `unit` are `null`. A real is written as the
shortest text that reads back as the same value. **A NaN or an infinity is refused** — JSON has no
spelling for one — by latching `NotFinite` and writing `null` in its place, so the text stays
well-formed and `failed()` says it is not the value.

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

### Decoder for JsonDecoder

```sysl
impl Decoder for JsonDecoder
```

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

### Encoder for JsonEncoder

```sysl
impl Encoder for JsonEncoder
```

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

### Fallible for JsonEncoder

```sysl
impl Fallible for JsonEncoder
```

### Writer for JsonEncoder

```sysl
impl Writer for JsonEncoder
```
