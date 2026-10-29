---
title: The codec module
summary: "`sysl.codec` — `derives Codec` on a struct or an enum, and every format reads and writes it: the `Encode`/`Decode` traits, the format's `Encoder`/`Decoder`, the annotations that rename, default, skip, flatten and tag, and an error that names the path and the byte."
weight: 58
---

**Every declaration in `sysl.codec`, with its signature:** [the generated API page](/api/sysl-codec/#index). This page is the argument — what the module is for, and how its pieces fit; that one is the list.

`sysl.codec` turns a value into a format and back. **A type says `derives Codec`; a format
implements `Encoder` and `Decoder`; neither knows the other.** The derivation walks the type's fields
at compile time ([reflection](/reference/reflection/)), so each pair of a type and a format is one
function with a direct call to each of the format's members, and nothing is looked up while the
program runs. JSON is the format the library carries: [`to_json` and `from_json`](/library/json/#reading-and-writing-your-own-types).

```sysl
import sysl.buf.Buf
import sysl.codec.{Codec, rename}
import sysl.encoding.json.{to_json, from_json}

enum Shape derives Codec
    Dot
    Circle(r: int)

struct Drawing derives Codec
    @rename("id") drawing_id: u32
    shapes: Buf[Shape]
    note: Option[string]

val d = from_json[Drawing]("{\"id\": 7, \"shapes\": [\"Dot\", {\"Circle\": {\"r\": 5}}]}").unwrap()

print(d.drawing_id, d.shapes.len(), d.note)
print(to_json(d).unwrap())
```

```output
7 2 None
{"id":7,"shapes":["Dot",{"Circle":{"r":5}}],"note":null}
```

## The three traits

- **`Encode`** writes a value: `encode[E: Encoder](self, e: *E)`. Derived for a struct (an object of
  its fields, in declaration order) or an enum.
- **`Decode`** reads one: `decode[D: Decoder](d: *D) -> Result[Self, CodecError]`, a member with no
  receiver, called on the type — `Drawing.decode(&d)`.
- **`Codec`** requires both, so `derives Codec` derives the pair. A type holding something that
  cannot be read back — a pointer, a closure, a cache — may derive `Encode` alone and still be logged.

The library implements both for `bool`, every integer up to 64 bits, `real`, `f32`, `char` (a string
of one character), `string`, `Option[T]`, `Buf[T]`, `Set[K]` and `Map[K, V]`. A tuple, an array, a
fixed-point number, a reference and `unit` need no `impl`: the free functions **`encode(v, e)`** and
**`decode[T](d)`** read them by their [kind](/reference/reflection/#what-a-type-is-and-what-it-is-called),
and a derived body calls those for every field.

| type | written as |
|---|---|
| a struct | an object, one key per field |
| an enum's variant with no fields | its name, `"Dot"` |
| a variant with fields | an object of one member, `{"Circle":{"r":5}}` |
| `Option[T]` | the value, or null for `None` |
| `Buf[T]`, `Set[K]`, an array, a tuple | a sequence |
| `Map[string, V]` | an object |
| `Map[K, V]`, any other key | a sequence of `[key, value]` pairs |
| `&T` | the `T` it refers to; read back into a new one |
| a fixed-point number | its value as a real, read back only where it is exact |
| `unit` | null |

An integer is held to its type's range when it is read, so `300` read into a `u8` is refused rather
than wrapped; a width past 64 bits is refused when the program is compiled. A reference is written as
what it refers to, so a recursive enum — every syntax tree — reads and writes with nothing written
for it, and two references to one object read back as two.

## The annotations

Six annotations, each an ordinary `@annotation` struct in `sysl.codec`, change what the derivation
does:

| annotation | on | what it does |
|---|---|---|
| `@rename("k")` | a field or a variant | written and read under `k` |
| `@default(v)` | a field | `v` when the input leaves the field out; `v` is checked as the field's own type |
| `@skip` | a field | never written; read as its `@default`, or its type's `Default` |
| `@flatten` | a struct field | its fields written at this level, as if they were this struct's |
| `@tag("k")` | an enum | internally tagged: `{"k":"Circle","r":5}` |
| `@tag("k") @content("c")` | an enum | adjacently tagged: `{"k":"Circle","c":{"r":5}}` |
| `@deny_unknown` | a struct or an enum | a key no field is written under is refused rather than passed over |

```sysl
import sysl.codec.{Codec, Default, default, flatten, skip, tag}
import sysl.encoding.json.{to_json, from_json}

struct Cache
    hits: int

impl Default for Cache
    default() -> Cache = Cache(0)

struct Owner derives Codec
    name: string
    team: string

@tag("kind")
enum Job derives Codec
    Build(target: string)
    Clean

struct Task derives Codec
    @default(3) retries: u8
    @flatten owner: Owner
    job: Job
    @skip cache: Cache

val t = from_json[Task]("{\"job\": {\"target\": \"all\", \"kind\": \"Build\"}, \"team\": \"core\", \"name\": \"ann\"}").unwrap()

print(t.retries, t.owner.name, t.owner.team, t.cache.hits)
print(to_json(t).unwrap())
```

```output
3 ann core 0
{"retries":3,"name":"ann","team":"core","job":{"kind":"Build","target":"all"}}
```

**A missing field is an error** unless its type is an `Option` (it reads as `None`) or it carries
`@default`. **An unknown key is passed over**, whatever its value holds, so a reader keeps working
when a writer adds a field; `@deny_unknown` refuses one. An internally tagged object may give its tag
anywhere among its members: the decoder looks ahead for it and then reads the object as that variant.

`@default` takes a value of the field's own type — `@default(8080)` on a `u16`, `@default("localhost")`
on a `string` — because the annotation's [type parameter](/reference/reflection/#an-annotation-of-the-fields-type)
is bound to each field it is written on. A `@skip` field with no `@default` is given its type's
**`Default`**, a trait of one receiverless member that `bool`, `real`, `string`, `Option`, `Buf`,
`Set` and `Map` implement, and that a program implements for its own types; a skipped field whose
type has neither is refused when the program is compiled.

## What a refusal says

**Decoding answers a `CodecError`**: what was wrong (a `Fault`), the byte offset it was found at, and
the path from the whole value to it — fields by name, elements by index, map keys in brackets. It
shows as `path: fault (byte n)`.

```sysl
import sysl.buf.Buf
import sysl.codec.Codec
import sysl.encoding.json.from_json

struct Point derives Codec
    x: int
    y: int

struct Path derives Codec
    points: Buf[Point]

for text in ["{\"points\": [{\"x\": 1, \"y\": 2}, {\"x\": 3, \"y\": \"4\"}]}", "{\"points\": [{\"x\": 1}]}", "{\"points\": [{\"x\": 1, \"y\": 2},]}", "{\"points\": [{\"x\": 99999999999999999999, \"y\": 0}]}"]
    from_json[Path](text) match
        Ok(_) -> print("accepted")
        Err(e) -> print(e)
```

```output
points[1].y: an integer expected (byte 44)
points[0]: the field 'y' is missing (byte 19)
points: a value expected (byte 29)
points[0].x: out of range for int (byte 18)
```

Where the text breaks the format's own grammar, the words are the format's — the third line is the
JSON reader's, as `parse` would say it. The path keeps its innermost sixteen steps and opens with `…`
past that, and it is built on the way out of a failed read only, so a successful one pays nothing for
it.

**Encoding latches.** An encoder that cannot write something — a NaN where JSON has no spelling for
one, a full buffer — remembers the first fault and answers `true` from `failed()`, as a `Writer`
does, and the rest of the value is written around it. `to_json` turns a latched fault into the same
`CodecError`, at the byte the value would have been written at.

## Writing a format

A format is a pair of types. **The encoder is pushed** the value's shape: `begin_struct`, then
`field(key)` and the value for each field, then `end_struct`; `begin_seq`, `item()` and the element,
`end_seq`; and `none`, `bool`, `int` (an `i128`, which every supported integer fits), `real` and
`str` for the leaves. **The decoder is pulled**: the derived body asks `begin_struct`, then
`next_key()` until it answers `None`; `begin_seq`, then `next_item()` until `false`; `peek()` to see
what comes next; and `skip_value()` for a key it does not know. A decoder also answers `mark()` and
`rewind(m)`, which return to an earlier place in the input — a tagged enum looks ahead for its tag,
and a `@flatten` field reads its object a second time.

Pull rather than serde's visitor: the decoded value is built in one function's frame, through
[`T::Partial`](/reference/reflection/#building-a-value-a-field-at-a-time), so nothing is boxed and no
closure crosses a call.
