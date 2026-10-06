---
title: sysl.encoding
layout: api-module
headingShift: 0
slugStyle: github
module: sysl.encoding
summary: "Bytes as text and text as bytes: hexadecimal, base64, UUIDs, and fixed-width integers at either byte order."
---

Every conversion here is total in one direction and fallible in the other — encoding cannot fail,
decoding can — so the decoders answer a `Result` carrying `DecodeError`, which says why at the
granularity a caller can act on rather than only that something was wrong.

**None of it interprets what it moves.** A base64 decoder does not know whether the bytes it
produced are a key, an image or a sentence, and a `u32` read out of four bytes is a number and not
a field of anything. What the bytes *mean* belongs to whatever asked for them.

## Index

[`nil`](#nil) [`base64_decode`](#base64_decode) [`base64_decoded_len`](#base64_decoded_len) [`base64_encode`](#base64_encode) [`base64_string`](#base64_string) [`get_u16_be`](#get_u16_be) [`get_u16_le`](#get_u16_le) [`get_u32_be`](#get_u32_be) [`get_u32_le`](#get_u32_le) [`get_u64_be`](#get_u64_be) [`get_u64_le`](#get_u64_le) [`hex_decode`](#hex_decode) [`hex_decoded_len`](#hex_decoded_len) [`hex_encode`](#hex_encode) [`hex_string`](#hex_string) [`of_bytes`](#of_bytes) [`parse`](#parse) [`put_u16_be`](#put_u16_be) [`put_u16_le`](#put_u16_le) [`put_u32_be`](#put_u32_be) [`put_u32_le`](#put_u32_le) [`put_u64_be`](#put_u64_be) [`put_u64_le`](#put_u64_le) [`to_string`](#to_string) [`v4`](#v4) [`v4_of`](#v4_of) [`v7`](#v7) [`Alphabet`](#alphabet) [`DecodeError`](#decodeerror) [`Uuid`](#uuid) [Display for DecodeError](#display-for-decodeerror) [Display for Uuid](#display-for-uuid)

## Values

### `nil`

```sysl
val nil: Uuid = Uuid(…)
```

The UUID that names nothing: sixteen zero bytes, `00000000-0000-0000-0000-000000000000`.

Its version and variant both read as zero, which is not a mistake in the constant -- RFC 9562
exempts it, and a reader checking `version() == 4` on an unset field is meant to see that it is
unset rather than to see a well-formed value.

## Functions

### `base64_decode`

```sysl
base64_decode(text: []const u8, out: []u8) -> Result[usize, DecodeError]
```

The bytes the text spells, written into `out`, and how many there were.

**Six bits at a time into an accumulator**, rather than a four-character group at a time, because
the unpadded spellings end mid-group and a group-at-a-time reader needs a special case for each of
them. Whole bytes fall out of the accumulator as they complete, and the two or four leftover bits
at the end are the padding bits, which are discarded.

### `base64_decoded_len`

```sysl
base64_decoded_len(text: []const u8) -> usize
```

How many bytes `base64_decode` will write, so a caller can size the slice it lends. Trailing
padding is discounted, so this answers the same number for the padded and unpadded spellings of one
input.

### `base64_encode`

```sysl
base64_encode(bytes: []const u8, out: *Writer, alphabet: Alphabet, pad: bool)
```

Three bytes become four characters. The tail -- one or two bytes left over -- becomes two or three
characters, padded out to four with `=` when padding was asked for.

### `base64_string`

```sysl
base64_string(bytes: []const u8, alphabet: Alphabet, pad: bool) -> string
```

The same, gathered into a string. Every character emitted is ASCII, so the bytes are valid UTF-8 by
construction.

### `get_u16_be`

```sysl
get_u16_be(b: []const u8) -> Option[u16]
```

### `get_u16_le`

```sysl
get_u16_le(b: []const u8) -> Option[u16]
```

### `get_u32_be`

```sysl
get_u32_be(b: []const u8) -> Option[u32]
```

### `get_u32_le`

```sysl
get_u32_le(b: []const u8) -> Option[u32]
```

### `get_u64_be`

```sysl
get_u64_be(b: []const u8) -> Option[u64]
```

### `get_u64_le`

```sysl
get_u64_le(b: []const u8) -> Option[u64]
```

### `hex_decode`

```sysl
hex_decode(text: []const u8, out: []u8) -> Result[usize, DecodeError]
```

The bytes the text spells, written into `out`, and how many there were.

### `hex_decoded_len`

```sysl
hex_decoded_len(text: []const u8) -> usize
```

How many bytes `hex_decode` will write, so a caller can size the slice it lends.

Answers for the length alone and does not look at the content: a text of odd length has no whole
number of bytes in it and `hex_decode` refuses it, but the halving is still the right size to have
reserved.

### `hex_encode`

```sysl
hex_encode(bytes: []const u8, out: *Writer)
```

Two characters per byte, written straight through to the sink.

### `hex_string`

```sysl
hex_string(bytes: []const u8) -> string
```

The same, gathered into a string. Every byte emitted above is an ASCII digit, so the result is
valid UTF-8 by construction and needs no validating pass.

### `of_bytes`

```sysl
of_bytes(b: [16]u8) -> Uuid
```

A UUID over bytes the caller already has -- read off a wire, out of a row, out of a file.

**It sets no bits and checks none.** What arrives is what the value holds, so a UUID from
somewhere else survives a round trip through this module unchanged, including one whose variant
is Microsoft's or whose version is a number this library does not generate. `version` and
`variant` are how a caller asks what it got.

### `parse`

```sysl
parse(s: string) -> Option[Uuid]
```

The 36-character form read back, or `None`.

**Only that form.** Braces, a `urn:uuid:` prefix and the unhyphenated 32-character form are all
refused, because accepting them makes `parse` a guess about which of several conventions the text
follows -- and a caller that knows which one it is reading can strip a wrapper in a line. Either
case of hex digit is accepted, which is what RFC 9562 asks for.

A hyphen in the wrong place is refused as firmly as a bad digit: the positions are what tell this
form from the others, so a text with them elsewhere is a different format rather than a sloppy
spelling of this one.

### `put_u16_be`

```sysl
put_u16_be(b: []u8, v: u16) -> bool
```

### `put_u16_le`

```sysl
put_u16_le(b: []u8, v: u16) -> bool
```

### `put_u32_be`

```sysl
put_u32_be(b: []u8, v: u32) -> bool
```

### `put_u32_le`

```sysl
put_u32_le(b: []u8, v: u32) -> bool
```

### `put_u64_be`

```sysl
put_u64_be(b: []u8, v: u64) -> bool
```

### `put_u64_le`

```sysl
put_u64_le(b: []u8, v: u64) -> bool
```

### `to_string`

```sysl
to_string(u: Uuid) -> string
```

The 36-character form, lowercase, hyphenated `8-4-4-4-12`.

Lowercase because RFC 9562 says to emit lowercase and accept either, and `parse` accepts either.
This is the only thing in the module that allocates, and it allocates one 36-byte string.

### `v4`

```sysl
v4(rng: *Rng) -> Uuid
```

A version 4 UUID: 122 random bits, with the six that name the version and the variant written
over them.

**`Rng` IS NOT UNPREDICTABLE, AND THAT DECIDES WHAT THIS MAY BE USED FOR.** `sysl.rand` is PCG32
-- fast, seedable, reproducible, and completely determined by its state, so anyone who sees a
handful of ids can compute every id that follows. That is fine for a primary key, a request id, a
correlation id in a log: things that must be *distinct*. It is not fine for a session token, a
password-reset link, an object name that stands in for an access check, or anything else that must
be *unguessable*.

**For those, take the bytes from the kernel and use `v4_of`** --
`sysl.posix.rand.entropy_from_os` is exactly that source, and the module comment there draws the
same line for the same reason. Seeding a predictable generator unpredictably does not make it
unpredictable.

The generator is a parameter rather than something this module keeps, so nothing here is module
storage and a program on a board can supply one it seeded itself.

### `v4_of`

```sysl
v4_of(b: [16]u8) -> Uuid
```

A version 4 UUID over sixteen bytes the caller brings, with the version and variant bits written
over them.

**This is the one to use where the value must be unguessable**, since what it is worth is exactly
what the bytes are worth. `entropy_from_os` fills a slice straight from the kernel's pool:

```
var raw: [16]u8

if entropy_from_os(raw[0..<16]) then print(v4_of(raw))
```

Six of the 128 bits are overwritten, which is the version and the variant and is what makes it a
v4 rather than sixteen anonymous bytes. A caller wanting all 128 to be its own wants `of_bytes`
and does not want a version number.

### `v7`

```sysl
v7(t: Instant, rng: *Rng) -> Uuid
```

A version 7 UUID: the instant in the high 48 bits as milliseconds since the Unix epoch, and 74
random bits under it.

**What it buys is that ids made in order sort in order**, as bytes and as text, which is what a
database index wants from a primary key and what a v4 conspicuously does not give. Two ids made in
the same millisecond are ordered by their random bits, which is to say arbitrarily -- v7 promises
ordering *between* milliseconds and nothing finer.

**The instant is a parameter, so this module reaches no clock.** A host passes
`sysl.time.now()`; a board passes whatever its own counter says, and a board with no notion of the
epoch should be making v4s instead, since a timestamp that is not one is a field that lies.

The same warning `v4` carries applies to the random half: a v7 built from `sysl.rand` is not
unguessable, and it is a good deal *more* predictable than a v4, since 48 of its bits are a
timestamp anybody can compute.

**48 bits of milliseconds runs out in the year 10889**, so the wrap is not a thing to plan for.
An instant before the epoch has a negative millisecond count and is truncated to its low 48 bits,
which is a value that does not order correctly -- v7 has no way to say "before 1970" and this does
not invent one.

## Types

### `Alphabet`

```sysl
enum Alphabet
    Standard
    UrlSafe
```

Which of RFC 4648's two alphabets. They differ only in the last two characters: `+/` reads badly in
a URL and in a filename, which is the whole reason the second exists.

### `DecodeError`

```sysl
enum DecodeError
    BadByte(at: usize)
    BadLength
    BadPadding(at: usize)
    Short(needed: usize)
```

Why a decode refused, at the granularity a caller can act on.

**A type of its own rather than `sysl.text`'s `ParseError`**, which is about reading a *number* out
of text. Two of that type's four variants -- `Overflow` and `BadBase` -- can never occur here, and
a caller matching on it would be told cases exist that cannot. The shape is copied deliberately:
the variants are separated by what a caller would **do** about each and not by taxonomy.

`BadByte` and `BadPadding` carry the offset for the reason `ParseError.BadDigit` and `Utf8Error` do
-- a message naming where is worth writing and cannot be reconstructed afterwards. `Short` carries
the length that *would* have been enough, so a caller can resize and retry in one step rather than
discovering the requirement one byte at a time.

### `Uuid`

```sysl
struct Uuid
    private raw: [16]u8
```

Sixteen bytes with a layout: what the version is, what the variant is, and -- for a v7 -- when it
was made.

**The bytes are held in the order they are written**, which is the order RFC 9562 numbers them and
is big-endian throughout. That is what makes `to_bytes` directly comparable with a UUID from
anywhere else, and what makes a v7 sort by time as a plain byte string.

`Ord for Uuid` is therefore over those bytes in order, which for a v7 is over the timestamp
first -- the whole point of that version. For a v4 it is an arbitrary total order, which is what
a sorted container wants and means nothing else.

**All three traits are DERIVED, which they could not be until an array had `Ord` and `Hash`.**
The one field is a `[16]u8` and an array's ordering is lexicographic, first element first -- so
the derived comparison is byte order, which is exactly what this type wants and what the two
hand-written blocks here used to spell out. `Hash` is FNV-1a over the elements, and a UUID's
bytes are already well distributed, so all a hash has to add is that a table bucketing on the low
bits sees all of them.

| Member | Signature | Description |
|---|---|---|
| `to_bytes` | `to_bytes(self) -> [16]u8` | The sixteen bytes, in RFC order. |
| `version` | `version(self) -> u8` | Which of RFC 9562's versions this claims to be: the high four bits of byte 6. |
| `variant` | `variant(self) -> u8` | Which family of layouts byte 8 puts this in -- `1` for RFC 9562's, which is what everything generated here is, and what any UUID a reader is likely to meet is. |
| `is_nil` | `is_nil(self) -> bool` | Whether every byte is zero -- the nil UUID, which RFC 9562 defines as the value that names nothing. |

## Implementations

### Display for DecodeError

```sysl
impl Display for DecodeError
```

The rendering, so that a refusal can be printed without matching on it.

### Display for Uuid

```sysl
impl Display for Uuid
```
