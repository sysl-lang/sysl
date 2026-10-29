---
title: sysl.codec
layout: api-module
headingShift: 0
slugStyle: github
module: sysl.codec
summary: "Turning a value into a format and back, written once for every type and every format."
---

**A type says how it is built; a format says how things are spelled; neither knows the other.**
A type takes `derives Codec` (or `Encode`, or `Decode`, alone), and the library's derivation walks
its fields at compile time. A format implements `Encoder` and `Decoder`, which spell a handful of
shapes — null, a bool, an integer, a real, a string, a sequence, a struct — and every derived type
reads and writes through it. Each pair of a type and a format is one function, monomorphized, with
a direct call to each of the format's members: nothing is looked up while the program runs.

`sysl.encoding.json` is the first format (`to_json`, `from_json`).

**Encoding latches; decoding answers.** An encoder that cannot write something — a full buffer, a
NaN where the format has no spelling for one — remembers the first fault and says so through
`failed()`, as a `Writer` does. A decoder answers a `Result`, and its error says what was wrong,
the byte it was found at, and the path of fields, variants and indexes that led there:
`users[3].home.zip: an integer expected (byte 214)`.

What the annotations here change is in the library page, `library/codec.md`.

## Index

[`path_room`](#path_room) [`decode`](#decode) [`encode`](#encode) [`refusal`](#refusal) [`CodecError`](#codecerror) [`content`](#content) [`default`](#default) [`deny_unknown`](#deny_unknown) [`Fault`](#fault) [`flatten`](#flatten) [`Path`](#path) [`rename`](#rename) [`skip`](#skip) [`Step`](#step) [`tag`](#tag) [`Token`](#token) [`Codec`](#codec) [`Decode`](#decode-1) [`Decoder`](#decoder) [`Default`](#default-1) [`Encode`](#encode-1) [`Encoder`](#encoder) [Decode for bool](#decode-for-bool) [Decode for Buf[T]](#decode-for-buft) [Decode for char](#decode-for-char) [Decode for f32](#decode-for-f32) [Decode for Map[K, V]](#decode-for-mapk-v) [Decode for Option[T]](#decode-for-optiont) [Decode for real](#decode-for-real) [Decode for Set[K]](#decode-for-setk) [Decode for string](#decode-for-string) [Default for bool](#default-for-bool) [Default for Buf[T]](#default-for-buft) [Default for Map[K, V]](#default-for-mapk-v) [Default for Option[T]](#default-for-optiont) [Default for real](#default-for-real) [Default for Set[K]](#default-for-setk) [Default for string](#default-for-string) [Display for CodecError](#display-for-codecerror) [Display for Fault](#display-for-fault) [Display for Path](#display-for-path) [Encode for bool](#encode-for-bool) [Encode for Buf[T]](#encode-for-buft) [Encode for char](#encode-for-char) [Encode for f32](#encode-for-f32) [Encode for Map[K, V]](#encode-for-mapk-v) [Encode for Option[T]](#encode-for-optiont) [Encode for real](#encode-for-real) [Encode for Set[K]](#encode-for-setk) [Encode for string](#encode-for-string) [Encode for T](#encode-for-t) [Eq for CodecError](#eq-for-codecerror)

## Constants

### `path_room`

```sysl
const path_room: usize = 16
```

How deep a path is kept: the innermost steps, which say the most.

## Functions

### `decode`

```sysl
decode[T, D: Decoder](d: *D) -> Result[T, CodecError]
```

Reads any value `encode` writes. An integer of any width up to 64 bits is read here, with its
range checked, rather than through an `impl`: a blanket `impl` over `Integer` may not declare a
member with no receiver, which `Decode.decode` is.

### `encode`

```sysl
encode[T, E: Encoder](v: T, e: *E)
```

Writes any value: one that implements `Encode`, or a tuple, an array, a fixed-point number, a
reference (as what it refers to) or `unit` (as null), which need no `impl`.

### `refusal`

```sysl
refusal(fault: Fault, at: usize) -> CodecError
```

An error with no path yet: a fault at byte `at`.

## Types

### `CodecError`

```sysl
struct CodecError
    fault: Fault
    at: usize
    path: Path
```

Why a value could not be read (or written): the fault, the byte offset it was found at, and the
path to it. Shown as `path: fault (byte n)`.

### `content`

```sysl
struct content
    field: string
```

With `@tag`, the key the payload is written under, beside the tag.

### `default`

```sysl
struct default[F]
    v: F
```

The value a field takes when the input does not mention it.

### `deny_unknown`

```sysl
struct deny_unknown
end deny_unknown
```

A struct that refuses a key none of its fields is written under, rather than skipping it.

### `Fault`

```sysl
enum Fault
    Syntax(what: string)
    Expected(what: string)
    Missing(field: string)
    Unknown(key: string, of: string)
    Duplicate(key: string)
    UnknownVariant(name: string, of: string)
    NoTag(key: string)
    OutOfRange(of: string)
    Inexact(of: string)
    NotFinite
```

What was wrong with the input, or with the value being written.

### `flatten`

```sysl
struct flatten
end flatten
```

A struct field whose own fields are written at this level, as if they were this struct's.

### `Path`

```sysl
struct Path
    steps: [path_room]Step
    len: usize
    cut: bool
```

The fields, variants, indexes and keys from the outside in. Built on the way out of a failed
decode, on the failure path only; past `path_room` steps the outermost are dropped, and the path
opens with `…`.

### `rename`

```sysl
struct rename
    name: string
```

The key a field, or the name a variant, is written under, in place of its own.

### `skip`

```sysl
struct skip
end skip
```

A field never written, and given its `@default` — or its type's `Default` — when decoded.

### `Step`

```sysl
struct Step
    kind: u8
    name: string
    index: usize
```

One step of the way from the whole value to where a fault was found.

### `tag`

```sysl
struct tag
    field: string
```

An enum written **internally tagged**: the variant's name is one more member of its payload's
object, under this key — `{"type":"Circle","r":5}`. With `@content` beside it, **adjacently
tagged**: `{"t":"Circle","c":{"r":5}}`.

### `Token`

```sysl
enum Token
    Null
    Bool
    Number
    Text
    Seq
    Struct
```

What the next value in a decoder's input is, without reading it.

## Traits

### `Codec`

```sysl
trait Codec: Encode + Decode
```

Both directions: `derives Codec` derives `Encode` and `Decode`.

### `Decode`

```sysl
trait Decode
    decode[D: Decoder](d: *D) -> Result[Self, CodecError]
    when_missing() -> Option[Self]
```

A value that can be read from any format. Derived for a struct or an enum.

| Member | Signature | Description |
|---|---|---|
| `decode` | `decode[D: Decoder](d: *D) -> Result[Self, CodecError]` |  |
| `when_missing` | `when_missing() -> Option[Self]` | What a field of this type is when the input leaves it out: `None` here, which makes that a `Missing` error. |

### `Decoder`

```sysl
trait Decoder
    type Mark
    mark(*self) -> Self::Mark
    rewind(*self, m: Self::Mark)
    offset(*self) -> usize
    peek(*self) -> Result[Token, CodecError]
    none(*self) -> Result[bool, CodecError]
    bool(*self) -> Result[bool, CodecError]
    int(*self) -> Result[i128, CodecError]
    real(*self) -> Result[real, CodecError]
    str(*self) -> Result[string, CodecError]
    begin_seq(*self) -> Result[unit, CodecError]
    next_item(*self) -> Result[bool, CodecError]
    begin_struct(*self) -> Result[unit, CodecError]
    next_key(*self) -> Result[Option[string], CodecError]
    skip_value(*self) -> Result[unit, CodecError]
```

A format's reading half: a pull reader the derived `decode` asks for one shape at a time.

A struct is `begin_struct`, then `next_key` until it answers `None`, with each key's value read
(or skipped) in between; a sequence is `begin_seq`, then `next_item` until it answers `false`. A
member that reads a value refuses one of another shape with `Expected`, and leaves the input where
it was.

`mark` and `rewind` return to an earlier place in the input: a decoder reading an internally
tagged enum looks ahead for the tag, and one with a `@flatten` field reads the object twice.

| Member | Signature | Description |
|---|---|---|
| `mark` | `mark(*self) -> Self::Mark` |  |
| `rewind` | `rewind(*self, m: Self::Mark)` |  |
| `offset` | `offset(*self) -> usize` | The byte offset the next thing read starts at: a value, a key, or the close of the sequence or struct being read -- past any separator still to be read before it. |
| `peek` | `peek(*self) -> Result[Token, CodecError]` |  |
| `none` | `none(*self) -> Result[bool, CodecError]` | Reads the format's null and answers `true` where one is next; otherwise reads nothing. |
| `bool` | `bool(*self) -> Result[bool, CodecError]` |  |
| `int` | `int(*self) -> Result[i128, CodecError]` |  |
| `real` | `real(*self) -> Result[real, CodecError]` |  |
| `str` | `str(*self) -> Result[string, CodecError]` |  |
| `begin_seq` | `begin_seq(*self) -> Result[unit, CodecError]` |  |
| `next_item` | `next_item(*self) -> Result[bool, CodecError]` |  |
| `begin_struct` | `begin_struct(*self) -> Result[unit, CodecError]` |  |
| `next_key` | `next_key(*self) -> Result[Option[string], CodecError]` |  |
| `skip_value` | `skip_value(*self) -> Result[unit, CodecError]` |  |

### `Default`

```sysl
trait Default
    default() -> Self
```

The value a skipped field is given when it is decoded and carries no `@default`.

| Member | Signature | Description |
|---|---|---|
| `default` | `default() -> Self` |  |

### `Encode`

```sysl
trait Encode
    encode[E: Encoder](self, e: *E)
```

A value that can be written to any format. Derived for a struct or an enum.

| Member | Signature | Description |
|---|---|---|
| `encode` | `encode[E: Encoder](self, e: *E)` |  |

### `Encoder`

```sysl
trait Encoder: Fallible
    none(*self)
    bool(*self, v: bool)
    int(*self, v: i128)
    real(*self, v: real)
    str(*self, v: string)
    begin_seq(*self, n: usize)
    item(*self)
    end_seq(*self)
    begin_struct(*self, name: string, n: usize)
    field(*self, key: string)
    end_struct(*self)
    fail(*self, fault: Fault)
```

A format's writing half. The derived `encode` calls these in the order the value is laid out:

- a struct is `begin_struct`, then `field(key)` and the field's value for each field, then
  `end_struct`; a map with `string` keys is written the same way;
- a sequence (a `Buf`, a `Set`, an array, a tuple) is `begin_seq`, then `item()` and the element
  for each, then `end_seq`.

An encoder that cannot write something records it with `fail` and goes on quietly; the first
fault is the one kept.

| Member | Signature | Description |
|---|---|---|
| `none` | `none(*self)` | The format's null: `None`, and `unit`. |
| `bool` | `bool(*self, v: bool)` |  |
| `int` | `int(*self, v: i128)` |  |
| `real` | `real(*self, v: real)` |  |
| `str` | `str(*self, v: string)` |  |
| `begin_seq` | `begin_seq(*self, n: usize)` |  |
| `item` | `item(*self)` |  |
| `end_seq` | `end_seq(*self)` |  |
| `begin_struct` | `begin_struct(*self, name: string, n: usize)` |  |
| `field` | `field(*self, key: string)` |  |
| `end_struct` | `end_struct(*self)` |  |
| `fail` | `fail(*self, fault: Fault)` |  |

## Implementations

### Decode for bool

```sysl
impl Decode for bool
```

### Decode for Buf[T]

```sysl
impl[T] Decode for Buf[T]
```

### Decode for char

```sysl
impl Decode for char
```

### Decode for f32

```sysl
impl Decode for f32
```

### Decode for Map[K, V]

```sysl
impl[K: Hash + Eq, V] Decode for Map[K, V]
```

### Decode for Option[T]

```sysl
impl[T] Decode for Option[T]
```

### Decode for real

```sysl
impl Decode for real
```

### Decode for Set[K]

```sysl
impl[K: Hash + Eq] Decode for Set[K]
```

### Decode for string

```sysl
impl Decode for string
```

### Default for bool

```sysl
impl Default for bool
```

### Default for Buf[T]

```sysl
impl[T] Default for Buf[T]
```

### Default for Map[K, V]

```sysl
impl[K: Hash + Eq, V] Default for Map[K, V]
```

### Default for Option[T]

```sysl
impl[T] Default for Option[T]
```

### Default for real

```sysl
impl Default for real
```

### Default for Set[K]

```sysl
impl[K: Hash + Eq] Default for Set[K]
```

### Default for string

```sysl
impl Default for string
```

### Display for CodecError

```sysl
impl Display for CodecError
```

### Display for Fault

```sysl
impl Display for Fault
```

### Display for Path

```sysl
impl Display for Path
```

### Encode for bool

```sysl
impl Encode for bool
```

### Encode for Buf[T]

```sysl
impl[T] Encode for Buf[T]
```

### Encode for char

```sysl
impl Encode for char
```

A character is a string of one.

### Encode for f32

```sysl
impl Encode for f32
```

### Encode for Map[K, V]

```sysl
impl[K: Hash + Eq, V] Encode for Map[K, V]
```

A map with `string` keys is an object; with any other key, a sequence of `[key, value]` pairs.

### Encode for Option[T]

```sysl
impl[T] Encode for Option[T]
```

`None` is the format's null; a field left out of the input reads as `None`.

### Encode for real

```sysl
impl Encode for real
```

### Encode for Set[K]

```sysl
impl[K: Hash + Eq] Encode for Set[K]
```

### Encode for string

```sysl
impl Encode for string
```

### Encode for T

```sysl
impl[T: Integer] Encode for T
```

Every integer width up to 64 bits, signed or not, read with its range checked.

### Eq for CodecError

```sysl
impl Eq for CodecError
```
