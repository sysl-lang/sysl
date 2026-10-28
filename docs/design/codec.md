# Codec — design

A **codec** turns a value into bytes and bytes back into a value. sysl takes one for 0.4.0, and it
takes it in the shape the [reflection](reflection.md) note was written for: **serialization written
once, in the library, over reflection**, rather than a derivation per format that the compiler
writes. A type says `derives Encode, Decode`; JSON, CBOR and any format a package adds then read and
write it, and the compiler's own syntax trees and declaration tables become the largest user of it.

Nothing here is built. The note settles what the library says and lists what the user decides.
Every block marked **proposed** is code that does not compile today; every other result is the
installed `sysl 0.3.6`'s real output, from the probe programs in [`codec-probes/`](codec-probes/)
(`sysl run codec-probes/pNN.sysl`; `p10` is `sysl build-c codec-probes/p10 --target
thumb-freestanding-softfp`).

## 1. What exists, and the gaps (probed on 0.3.6)

| exists | where |
|---|---|
| `T::Kind`, `T::Fields`, `T::Variants`, `v.(f)`, `x is c`, `x.(c)`, `c::Payload`, `T::Make(c, p)`, `if const T: Trait`, `@annotation` + `f::Annotation[A]`, `derive` members | reference/reflection.md; design/reflection.md, all decided and built in 0.3.0 |
| `sysl.encoding.json`: `parse`/`parse_bytes` into an owned `Json` tree (`Null`, `Bool`, `Int(long)`, `Big(digits)`, `Real`, `Str`, `Arr`, `Obj` with ordered `Member`s), `JsonError` with `line:col` and byte offset, `Display`/`pretty`/`quoted` to write | `library/sysl/encoding/json/` — **a tree, not a stream**: nothing reads into a caller's type |
| `sysl.encoding.Plain`: a value's own bytes, `decode(b) -> Option[Self]` / `encode(self, out) -> bool`, compiler-decided membership (integers, floats, fixed, bool, simple enums, arrays and structs of them; `@byte_order`) | `encoding/plain.sysl` — **a memory image**, no strings, no `Buf`, no versioning |
| `sysl.encoding.DecodeError` (`BadByte`, `BadLength`, `BadPadding`, `Short`, `Utf8`) for base64/hex/percent | `encoding/error.sysl` — the name is taken; the codec's error needs another |
| `Writer: Fallible` — a stream **latches** failure rather than returning it; `Reader: Fallible` | `write.sysl`, `stream.sysl`, `io/read.sysl` |
| the `.syslib` artifact: an `ar` of compiled code plus a **symbol list**; "the metadata carries no trees" | `compiler/…/syslib.sysl`. 0.2.4 measured std's parse at ~130 ms and its declarations at ~52 ms of a ~335 ms two-line build |

So a program can already walk any value's structure at compile time, and the reference page's
`Json` example (reflection.md § Writing a derivation) is a working one-format serializer. What does
not exist is a **format-neutral** pair of traits, a decoder of any kind into a caller's type, a
binary format, and an error that says which field failed.

## 2. What the probes established

Each row is one claim the design leans on, the probe that settles it, and what 0.3.6 answered.

| # | claim | probe | 0.3.6 |
|---|---|---|---|
| C1 | a trait member may be generic over the format (`encode[E: Encoder](self, e: *E)`), and a generic helper written with reflection implements it for any struct, monomorphized per (type, format) | p02 | **yes**: `{"id":7,"name":"ann","inner":{"z":3}}` |
| C2 | a **`derive` member** may be generic over the format | p01 | **no** — `'(' expected` at `derive[T] encode[E: Encoder](…)`; `derive[T, E: Encoder]` is refused with *a 'derive' member takes one type parameter* → **Q1** |
| C3 | a `derive` member over a trait object (`e: *Encoder`) works today | p11 | **yes**: ` x=1 y=2` — one indirect call per primitive |
| C4 | a `derive` member may have no receiver (a decoder has no value to start from) | p12 | **no** — *a 'derive' member takes the deriving type first, as 'self: T'* → **Q2** |
| C5 | a generic decode builds a struct field by field: `var out: T`, `out.(f) = …`, keys matched per unrolled copy, unknown keys skipped, `return Err(…)` from inside a copy | p03 | **yes** for fields with a zero: `7 ann 3 0` |
| C6 | `var seen: [T::Fields.len]bool` sizes an array by a reflection answer | p03 | **yes** |
| C7 | an annotation is settled in an `if const`, so a `@skip` field's type is never asked to implement `Decode` | p03 | **yes**: the skipped field's type has no `Decode` and compiles |
| C8 | the failing field's path is built on the way out, on the failure path only | p03 | **yes**: `error at inner.z an integer expected`, `error at name missing` |
| C9 | which types have a **zero** (what `var out: T` needs) | p04 | `string`, `*T`, integers, structs of them: yes. **`Option[T]`, `Buf[T]`, `Map[K, V]`, `&T`, a data enum: no** → **Q3** |
| C10 | a data enum is built generically from a variant name: `var p: c::Payload`, `p.(f) = …` (one-field payloads included), `T::Make(c, p)` | p06 | **yes**: `Rect(2, 3) Circle(5) Dot true` |
| C11 | the same over a payload holding `&T` (every syntax tree) | p07 | **no** — *(&Expr, &Expr) has no zero value, so 'p' needs an initial value* → **Q3** |
| C12 | encoding reaches every shape: data enums (`x is c`, `x.(c)`), `Option`, `Buf`, tuples, `q15`, recursive `&Expr`, and `@flatten` by recursing into `f::Type`'s fields | p05 | **yes**: `{"title":"t","rev":4,"tag":"x","shapes":["Dot",{"Circle":{"r":5}},…],"note":null,"pair":[1,"one"],"gain":0.5,"tree":{"Add":{…}}}` |
| C13 | a member call reaches through `&T`, so no `impl … for &T` is written | p05 | **yes** — and one is refused: *'&T' is a way of holding a T rather than a type of its own* |
| C14 | what `T::Kind` says of the library's containers | p05 | `Option` `DataEnum`, **`Buf` `Struct`, `Map` `Struct`**, `q15` `Fixed`, `&Expr` `Ref` — a container must be met by its own `impl` before a derivation would walk its private fields |
| C15 | an annotation's arguments: concrete fields only, constants only | p08, p09 | `@default_int(8080)`, `@tag("type", "data")` on an enum: **yes**. `struct default[T]` refused on 0.7 (*an annotation takes no type parameters*; accepted from 0.8.0, Q5); a function as argument refused (*'@default_fn' takes constants*) → **Q5** |
| C16 | `f::Type.decode(d)` reaches a receiverless member through a field's type | p19 | **no** — *'f::Type' is a type, so it stands where a type is written*; and `decode_as[f::Type](d)` is refused (*takes 2 type arguments*), so it is `decode_as[f::Type, D](d)` → **Q4** |
| C17 | a receiverless member on a tuple instance | p13 | **no** — *'(A, B)' is not a name a call could reach it through*. Not a gap for the codec: tuples and arrays are handled structurally (C18) |
| C18 | a free generic function covers tuples and arrays by `T::Kind` with no `impl`, and one `impl[T: Integer + Display]` covers the open `iN`/`uN` family | p14 | **yes**: `70000 [-3,9] [1,2,3]` for a `u24`, an `(i7, u64)`, a `[3]u8` |
| C19 | a decoder narrows a wide integer to any width with `T::Min`/`T::Max` and `T(x)` | p15 | **yes**: `true true true 255` |
| C20 | `derives Codec`, for `trait Codec: Encode + Decode` | p16 | **no** — *'Codec' … carries no 'derive' member* → **Q6** |
| C21 | a format answers a compile-time question (keyed or positional) through a `const` member | p17 | **no** — `if const F.keyed()`: *its condition has to be known then* → **Q7**. A **marker trait** does it today: `if const F: Positional` (p18: `keys positions`) |
| C22 | the result is monomorphized and allocation-free: a fixed-buffer encoder under `@no_alloc` builds for `thumb-freestanding-softfp` | p10 | **yes**: symbols `encode_struct.Reading.Fixed`, `Reading.encode.Fixed`, `uint.encode.Fixed`, two `.L.str` (the field names), **no `malloc`**, no table of types |

## 3. Decisions

| # | question | recommendation | why, in one line |
|---|---|---|---|
| 1 | what a codec is | **Two traits, `Encode` and `Decode`, each with a `derive` member**, plus **`Codec`**, which requires both, so `derives Codec` asks for the pair (Q6). `derives` is the existing clause; nothing is special-cased | a type holding a `*T`, a closure or a cache can be logged (encoded) and never decoded; Rust's serde and Kotlin's serialization split the same way |
| 2 | the model | **serde's split: a type describes itself to a format through `Encoder`/`Decoder` traits, and the format is a generic parameter (`encode[E: Encoder]`)**, so each (type, format) pair is one monomorphized function. Not one trait per format | N types × M formats costs N + M written things, not N × M; a package's format reads every derived type with no edit to either |
| 2b | encoding | **push**: `encode[E: Encoder](self, e: *E)`; the derived body calls `e.begin_struct(name, n)`, `e.field(key)`, the field's own `encode`, `e.end_struct()` | as p02, which runs |
| 2c | decoding | **pull, not visitor**: `decode[D: Decoder](d: *D) -> Result[Self, CodecError]`; the derived body asks `d.begin_struct()`, `d.next_key()`, and calls the field type's `decode`. serde's visitor exists because a Rust derive emits a `Visitor` *type* per target; a sysl derive cannot declare a type (that is the 0.4.0 "declare a type from reflection" note) and does not need one, since a `for const` copy matches keys in place (p03) | the decoded value is built in one function's frame, so nothing is boxed and no closure crosses a call |
| 2d | structural types | **No `impl` for tuples or arrays**: the library's free entry points `encode(v, e)` and `decode[T](d)` ask `if const T: Encode` first, then `T::Kind` (`Tuple`, `Array`), as p14 does. Integers are one `impl[T: Integer]` (C18) | a tuple cannot carry a receiverless member (C17), and does not need to |
| 3 | formats in 0.4.0 | **JSON and CBOR (RFC 8949)**. No third, bespoke binary format: CBOR with **structs as arrays** (positional, keys omitted) is the compact mode, chosen by the encoder (`if const E: Positional`, C21) | JSON already has a reader and writer here; CBOR is self-describing (so unknown fields can be skipped), has an RFC appendix of worked examples to test against, and costs a byte of header per small item |
| 4 | the annotations it honours | **`@rename("k")`** (field or variant), **`@skip`** (never written, decoded as its `@default`), **`@default`** (bare: the field type's `Default`), **`@flatten`** (a struct field's fields written at this level, p05), **`@tag(field, content)`** (enum style, 5), **`@deny_unknown`** (type). All ordinary `@annotation` structs in `sysl.codec` | each is one `if const` in the derived body; all six are expressible today except a typed `@default(value)` (Q5) |
| 5 | enum tagging | **Externally tagged by default** — a unit variant is its name, `"Dot"`; one with fields is `{"Circle":{"r":5}}` (p05). `@tag("type")` = internally tagged `{"type":"Circle","r":5}`; `@tag("t", "c")` = adjacent `{"t":"Circle","c":{…}}`. **No untagged form** | external decodes in one pass with no buffering on any format; internal needs the tag first, so a JSON decoder buffers the object into a `Json` tree when it is not (heap); untagged needs backtracking |
| 6 | `Option`, containers, strings | `Option`: `None` is the format's null, and a **missing field decodes as `None`** with no annotation. `Buf[T]`/`Set[K]`: a sequence. `Map[string, V]`: an object; another key type: a sequence of pairs. `string`: text (must be UTF-8 to decode, as `parse_bytes` already requires). `Buf[u8]`/`[]u8` marked `@bytes`: a CBOR byte string, a base64 JSON string | the conventions every JSON consumer expects; the library's `impl`s are explicit (C14) |
| 7 | numbers | integers narrowed with `T::Min`/`T::Max` (C19), refused when out of range; `real` written shortest-round-trip (as `json/write.sysl` does); **NaN and the infinities are refused by the JSON encoder** (latched, 9); **fixed point is written as its real value and decoded only when exactly representable** | a fixed-point value has one exact decimal, so a round trip is lossless; refusing an inexact one keeps `q15` from silently rounding |
| 8 | `&T` and recursion | written as the `T` it holds (C13); decoded by building the `T` and boxing it, which a recursive enum needs (C11, **Q3**). Sharing is not preserved: two references to one object decode as two | serde does the same; a graph codec is a different design |
| 9 | errors | **Encoding latches through `Fallible`** (`e.failed()`), as `Writer` does: a full buffer, a NaN in JSON, a failed write. **Decoding returns `CodecError { fault: Fault, at: usize, path }`**, where `path` is **a fixed 16-entry list of `Field(name)`/`Variant(name)`/`Index(usize)`/`Key(string)`** pushed on the way out (C8); the innermost 16 are kept, then `…`. Field and variant names are compile-time literals (C22), so a path costs no heap | the same shape error traces chose (a fixed ring, failure path only); a message reads `users[3].home.zip: an integer expected (byte 214)` |
| 10 | versioning and unknown fields | a keyed format **skips an unknown field** (`d.skip_value()`, p03); `@deny_unknown` refuses one. A **missing field is an error** unless it is an `Option` (6) or `@default`. The positional (array) mode is **not** versioned: a reorder is a different type | add-a-field-with-a-default is the one evolution every keyed format survives; positional is for caches keyed by the producer (11) |
| 11 | no alloc and bare metal | **Both directions run under `@no_alloc`** for types that hold no heap: the encoder writes into a caller's `[]u8` and latches overflow (p10); a decoder reads from a `[]const u8`. A `Buf` field needs a heap, as it does anywhere. Whether a decoded `string` field can be a view into the input rather than a copy (CBOR text, a JSON string with no escapes) is **not probed here**; step 5 settles it on the board build | a sensor frame in CBOR from a Pico is the first board use |
| 12 | cost | **Nothing at run time that a hand-written codec would not have**: no type table, no field-name table beyond the names written, no indirect call (C22). A decoder's key match is one string compare per field per key in declaration order; a `const` perfect hash over `T::Fields` names is a later optimization behind the same surface | the reflection note's decision 9 (no run-time reflection) carried through |
| 13 | where it lives | **`sysl.codec`**: `Encode`, `Decode`, `Codec`, `Encoder`, `Decoder`, `Default`, the six annotations, `CodecError`, the free `encode`/`decode`. **`sysl.encoding.json`** gains `JsonEncoder` (over any `*Writer`), `JsonDecoder` (a pull reader over `[]const u8`, factored out of `read.sysl` so the tree parser runs on it too), and `to_json(v)` / `from_json[T](text)`. **`sysl.encoding.cbor`** is new, with the same four | the formats stay beside the existing encodings; the traits are format-free |
| 14 | the compiler's AST and declaration codec | **After 0.4.0 ships** (the compiler builds with N-1): `ast.sysl`'s nodes and the declaration tables `derive Codec`, and `.syslib`'s metadata carries std's trees and tables in **positional CBOR**. The artifact is already keyed by the compiler's own digest, so no versioning is needed. It needs Q3 (every node is `{ span, kind: &Kind }`) | the ~130 ms parse and ~52 ms of declarations that 0.2.4 measured are what it removes; written once, not by hand |

## 4. How it reads — **proposed**

```sysl
import sysl.codec.{Codec, rename, skip, tag}
import sysl.encoding.json.{to_json, from_json}

@tag("type")
enum Event derives Codec
    Login(user: string)
    @rename("logout") Leave(user: string, after_ms: u32)

struct Session derives Codec
    @rename("id") session_id: u64
    events: Buf[Event]
    note: Option[string]
    @skip cache: Lookup

val text = to_json(session)
val back = from_json[Session](text)?
```

and the library's half, which is the whole of the struct derivation:

```sysl
trait Encode
    encode[E: Encoder](self, e: *E)

    derive[T] encode[E: Encoder](self: T, e: *E)
        if const T::Kind == Kind.Struct
            e.begin_struct(T::BaseName, T::Fields.len)

            for const f in T::Fields
                if const f::Annotation[skip].is_none()
                    e.field(f::Annotation[rename].map(r -> r.name).unwrap_or(f.name))
                    self.(f).encode(e)

            e.end_struct()
        elif const T::Kind == Kind.DataEnum || T::Kind == Kind.SimpleEnum
            encode_variant(self, e)
        else
            @assert(false, s"Encode cannot derive for ${T::Name}, which is a ${Kind::Image(T::Kind)}")
```

Everything in the second block runs today as p02's free function; the only refused part is the
`derive` member's own `[E: Encoder]` (Q1). The decoder's body is p03's `decode_struct` with
`var out: T` replaced by Q3's answer.

## 5. The analogy checks

| | model | derive | field metadata | formats | run time |
|---|---|---|---|---|---|
| Rust serde | `Serializer`/`Deserializer` traits, generic; decode by `Visitor` types | proc macro emits code | `#[serde(rename, skip, default, flatten, tag)]` | crates, any number | none |
| Go `encoding/json` | one package per format | none: run-time reflection | struct tags (`json:"id,omitempty"`) | per package, each re-walks reflection | `reflect` on every call |
| Zig `std.json` | one module per format | `inline for` over `@typeInfo` | none; `jsonParse` member by convention | JSON only in std | none |
| Swift `Codable` | `Encoder`/`Decoder` protocols, keyed and unkeyed containers | compiler-synthesized | `CodingKeys` enum | JSON, plist | existential containers |
| Kotlin serialization | `Encoder`/`Decoder` + descriptors | compiler plugin | `@SerialName`, `@Transient` | JSON, CBOR, protobuf | descriptor objects |
| **sysl** | `Encoder`/`Decoder` traits, generic; decode by pull | a `derive` member in the library | `@annotation` structs | JSON, CBOR in std; any package | none |

serde is the model and the four annotations are serde's; Kotlin's choice of JSON + CBOR as the two
first formats is the one recommended here. Go is the cautionary row: reflection at run time is what
sysl's compile-time walk avoids.

## 6. Open questions — the language gaps (0.4.0)

Each is something reflection or the derive mechanism cannot express on 0.3.6. None is worked around
in this design.

1. **A `derive` member generic over the format (C2).** `derive[T] encode[E: Encoder](self: T, e: *E)`
   is refused, so a derived `Encode` cannot be monomorphized per format. Recommended: a `derive`
   member may carry its own type parameters after the name, exactly as the member it writes does
   (p02 shows the instantiated body already compiles). The alternative that runs today, `e:
   *Encoder` (C3), pays an indirect call per primitive and stops inlining across the format; it is
   rejected as the shipped shape.
2. **A `derive` member with no receiver (C4).** `decode` has no value to read. Recommended: allow
   `derive[T] decode[D: Decoder](d: *D) -> Result[T, CodecError]`, the derived member taking `T`'s
   place as `Self` in the result rather than as `self`.
3. **Building a value whose fields have no zero (C9, C11).** `var out: T` is refused for any struct
   with an `Option`, `Buf`, `Map`, `&T` or data-enum field, and `var p: c::Payload` for any variant
   holding one — which is every real record and every syntax tree. The decision of 2026-10-19 (no
   definite-assignment check; a `var` starts at zero) stands, so the answer has to be a value that
   *is* zero. Three ways:
   - (a) give `Option[T]` a zero (`None`, as `weak T` has) and `Buf`/`Map`/`Set` one (empty) —
     covers records, not `&T` or data enums;
   - (b) **`T::Partial`** (and `c::Partial`): a tuple with one `Option[f::Type]` per field, whose zero
     is all `None`, read and written with the same `p.(f)`, and **`T::Make(partial)`** /
     `T::Make(c, partial)` building the value, trapping on a `None` the decoder has already
     reported — covers everything, and is the `Partial[T]` the planned "declare a type from
     reflection" note names;
   - (c) Zig's `undefined` — rejected: an unread field is undefined behaviour, which is what the
     zero rule exists to prevent.

   Recommended: **(b)**, with (a) for `Option` alone as a separate, smaller question.
4. **Reaching a receiverless member through `f::Type` (C16).** `f::Type.decode(d)` is refused where
   `T.decode(d)` is accepted, and `decode_as[f::Type](d)` cannot leave `D` to inference. Recommended:
   `f::Type` (and `c::Payload`) may stand before `.` as a type parameter does; separately, trailing
   type arguments may be inferred when leading ones are written.
5. **A default of the field's own type (C15).** An annotation cannot be generic and takes only
   constants, so `@default(8080)` on an `int` and `@default("localhost")` on a `string` cannot be one
   annotation. The bare `@default` (the field type's `Default`, a new std trait with `default() ->
   Self`) needs no language change. Recommended question: may an annotation field be typed
   **`Self`-of-the-annotated-field** (written, say, `v: Field`), checked against each use?
   **Answered in 0.8.0** (spelling decided by the user): `@annotation struct default[F] { v: F }` —
   an annotation may take one type parameter, which each use binds to the type of the field it is
   written on; `f::Annotation[default]` answers an `Option[default[f::Type]]`. A use on a type or a
   variant is refused (no field type to bind); a second parameter or a `const` one is refused; a bound
   on `F` is checked at each use. `reference/reflection.md § An annotation of the field's type`.
6. **`derives Codec` for a trait that only joins two derivable ones (C20).** Recommended: a
   `derives` naming a trait with no `derive` member derives each required trait that has one, and is
   refused only when none does.
7. **A `const` member reached through a type parameter in `if const` (C21).** `if const F.keyed()`
   is refused although `keyed` is `const` in every `impl`; the comptime note's rule says "a call
   whose body can run", which at an instance it is. A marker trait (`if const F: Positional`) does
   the same job today, so this is low priority — bug or deliberate is the user's call.

## 7. Decisions for the user

1. **Two traits, `Encode` + `Decode`, and `Codec` requiring both** (§3 row 1). Recommend: yes.
2. **serde's split — format as a generic `Encoder`/`Decoder` parameter — over one trait per format**
   (row 2). Recommend: yes; it needs Q1 and Q2.
3. **Pull decoding, not serde's visitor** (row 2c). Recommend: yes.
4. **JSON and CBOR in 0.4.0, with CBOR's struct-as-array mode as the compact binary form; no bespoke
   format** (row 3). Recommend: yes.
5. **The six annotations: `rename`, `skip`, `default`, `flatten`, `tag`, `deny_unknown`** (row 4).
   Recommend: yes.
6. **Externally tagged enums by default, internal and adjacent by `@tag`, no untagged** (row 5).
   Recommend: yes.
7. **Unknown fields skipped, a missing `Option` is `None`, any other missing field is an error**
   (rows 6, 10). Recommend: yes.
8. **NaN refused in JSON; fixed point decoded only when exact** (row 7). Recommend: yes.
9. **`CodecError` with a fixed 16-entry path, and encoding failures latched through `Fallible`**
   (row 9). Recommend: yes.
10. **Q3's answer: `T::Partial` + `T::Make(partial)`** (option b). Recommend: (b); (a) for `Option`
    as its own question.
11. **Q1, Q2, Q3 and Q6 are fixed before the codec is built; Q4, Q5 and Q7 may follow.** Recommend:
    yes — without Q1–Q3 the derived codec cannot be written, and the trait-object fallback would ship
    as reading material.
12. **The compiler's AST/declaration codec is the first piece of work after 0.4.0 ships**, in
    positional CBOR inside `.syslib` (row 14). Recommend: yes, measured against the 0.2.4 phase
    timings before and after.

## 8. Implementation plan

Prefix `cdc_`. Each step is one agent.

1. **Language: Q1 and Q2** — `derive` members with their own type parameters and with no receiver
   (`derive_members.sysl`), with the traits.md § A trait says how it is derived page.
2. **Language: Q3** — `T::Partial`, `c::Partial`, `T::Make(partial)` (`type_reflect.sysl`), with the
   reflection.md page. Q6 beside it (the `derives` clause).
3. **`sysl.codec`**: the traits, `Default`, the annotations, `CodecError`, the derive bodies, the free
   `encode`/`decode`, the std `impl`s (integers, floats, fixed, `bool`, `char`, `string`, `Option`,
   `Buf`, `Map`, `Set`), tested against an in-memory token format as p03 is.
4. **JSON**: the pull reader factored out of `read.sysl` (the tree parser re-based on it, its suite
   unchanged), `JsonEncoder`, `JsonDecoder`, `to_json`/`from_json`.
5. **CBOR**: `sysl.encoding.cbor`, keyed and positional, checked against RFC 8949 Appendix A, and a
   `thumb-freestanding-softfp` `@no_alloc` build as p10.
6. **Pages**: a reference page for the codec, and the library pages.
7. **After 0.4.0 ships**: the compiler's AST and declaration tables through it (decision 12).
