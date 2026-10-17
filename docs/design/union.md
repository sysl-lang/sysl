# `union` — design

sysl gets an **untagged union** in 0.2.0: storage several fields share, all of them starting at its
first byte, with nothing recording which one was written last. It exists for one reason — C has
them, and a binding cannot mirror `SDL_Event`, `sockaddr`, `in6_addr`, `epoll_data`, `sigaction` or a
register map without one. `sysl import-c` leaves every C union opaque-and-sized today (import-c §3,
Q4); with this item it maps them directly.

Nothing here is built yet. This note settles what the language says so the implementation is
mechanical, and lists what the user still has to decide.

## 1. Decisions

| # | question | recommendation | why, in one line |
|---|---|---|---|
| 1 | syntax | `union U` + one field per line + `end U` (seven-line rule), exactly as `struct`; `union` is a **soft word**; an anonymous `union` / `struct` block inside a struct or union is C11's anonymous member, reached through (`s.i`); generic unions **allowed**, held to the field rule at the instance | `library/sysl/container/set.sysl` already has a function named `union` (`end union`), so the word cannot be reserved |
| 2 | layout | size = largest field rounded up to the largest alignment; every field at offset 0; `@packed` → alignment 1, no rounding; `@align(n)` raises and rounds; nested like any field — **all exactly C's, probed on nine targets** | §3 |
| 2b | by value at a C boundary | `cabi.sysl` gains three union rules: AAPCS64/AAPCS32-hf **HFA counts by size, not by leaves**; **RISC-V never flattens a union**, nor a struct holding one; **wasm/CRAFT unwrap only a one-field union**. SysV, Windows, and the size rules of the rest need nothing new | §4 |
| 3 | safety | **(d) — fields must be *plain data or raw pointers*; an `AnyBits` field reads freely; any other field's read checks the bytes that can be wrong (`bool`, enum, `char`, subtype) and traps on a bad one; a field holding anything counted or program-made is refused at the declaration** | keeps "the raw pointer is the only unsafe primitive" true, and `SDL_Event`'s `bool`s readable; §5 |
| 3b | ownership | a field holding `&T`, `weak T`, `string`, a slice, a `Buf`, a closure, a data enum, or a struct of any of those: **refused** | no tag, so nothing knows which field to release, or whether one is there to release |
| 3c | writes | a write stores that field's bytes and **leaves the rest as they were** (stronger than C11's "unspecified") | it is what a store of the field's width does; nothing has to be added |
| 4 | construction | `U(f = v)`: **exactly one argument, and it is named**; the bytes past the field are **zero** | the name is the only thing that says which field; zero-fill makes a union's bytes deterministic |
| 4b | zero value | all-zero bytes, present when **every field has a zero value** (as a struct's) | consistent with structs; a sysl enum field (no zero value) takes it away |
| 4c | pattern | destructuring a union **refused**; binding one whole is fine | no tag to test |
| 4d | `==` / `Debug` | **no supplied `Eq`** (an `impl Eq` of the program's own is fine); the supplied **`Debug` prints the name and the bytes in hex** | equality of bytes is wrong for floats and for the bytes past a short field; Debug must never trap, and every type has one |
| 5 | header | `typedef union { … } name;` with `__attribute__((packed))` / `__attribute__((aligned(n)))` when the union has them; an anonymous member as an anonymous `union {…};` / `struct {…};` | — and the same attributes are **missing today for `@packed`/`@align` structs**: a bug, §8 |
| 6 | other features | `@byte_order` applies to every integer of every field; `@packed` structs may hold unions (no address, as any packed field); a bitfield **struct** may be a field, a bare sub-byte field may not; a union crosses threads as a struct of scalars does; vector fields allowed; `const`/module `val` fold to bytes, but a compile-time read of a field other than the one built is refused; `sysl prove` refuses a union by name | §6 |
| 7 | import-c | every C union maps to a `union`, every anonymous member to an anonymous block; `*T` members as they are; C `bool` members become checked fields; a member whose type is withheld withholds the union (as for structs) — Q4 asks whether to map it as bytes instead; **function-pointer members wait on Q2** | §7 |

## 2. Syntax

```sysl
union In6
    b8: [16]u8
    b16: [8]u16
    b32: [4]u32

struct Packet
    tag: u8
    union
        word: u32
        level: f32
    len: u16

var a = In6(b32 = [0, 0, 0, 0])
a.b8[15] = 1

var p = Packet(tag = 1, word = 7, len = 2)
print(p.word, offsetof(Packet, level))       // 7 4
```

- **`union` is a soft word.** It begins a type declaration only at the head of an item and when the
  next token is a name (`union In6`) or, for a generic, when the `[…]` after the name is followed by
  the end of the line. `union(a, b)` and `union[K: Hash + Eq](a: Set[K], b: Set[K]) -> Set[K]` stay
  functions, so `sysl.container.set.union` is untouched. Inside a struct or union body, a line that is
  exactly `union` or `struct` opens an anonymous member.
- **Everything a struct declaration takes, a union takes**: visibility per field, `opaque`, methods
  and `impl`s, `@export("name")`, `@packed`, `@align(n)`, `@byte_order`, `@pinned` (a union holding a
  pinned struct is pinned, as a struct holding one is). **Not `invariant`** — there is no field it
  could be stated over. **Not an empty body** — C refuses `union {}` too (GNU's zero-sized one is an
  extension).
- **An anonymous member's fields are the enclosing type's**: `p.word`, `offsetof(Packet, word)`, and
  a field name may not appear twice across the levels (C11's rule; refused by name). An anonymous
  `struct` inside a union is C11's too: `union AS / all: u64 / struct / lo: u32 / hi: u32`.
- **Named nesting needs a named type**: `v: Value` where `union Value` is declared beside it. An
  inline union with a field name (`union { … } v;` in C) is not a spelling; import-c names it (§7).
- **Generic unions are allowed** (`union Bits[T: AnyBits] / value: T / raw: u64`). The field rule
  (§5) is checked at the instance, as `@pinned` is today, and a bound that implies it (`AnyBits`)
  settles it at the declaration. Cheap, because a union is a `StructType` underneath (§12).

## 3. Layout — C's, measured

`union-probes/layout.c` holds thirteen claims as `_Static_assert`s; it was compiled with
`clang -std=c11 -ffreestanding -fsyntax-only` (Apple clang, the toolchain sysl links with) for
`aarch64-apple-darwin`, `aarch64-unknown-linux-gnu`, `x86_64-unknown-linux-gnu`,
`x86_64-pc-windows-msvc`, `thumbv7em-none-eabihf` (`-mfloat-abi=hard -mfpu=fpv5-d16`),
`thumbv7em-none-eabi` (`-mfloat-abi=soft`), `riscv32-unknown-elf` (`rv32imafc`/`ilp32f`),
`riscv64-unknown-elf` (`rv64gc`/`lp64d`), and `wasm32-unknown-unknown`. **Every claim holds on every
one of the nine** ("layout: all static_asserts hold" nine times):

| C | size / align | claim |
|---|---|---|
| `union { u8 a; u32 b; }` | 4 / 4 | the largest field |
| `union { u8 a[5]; u32 b; }` | **8** / 4 | rounded up to the largest alignment |
| `union { u8 a[3]; u16 b; }` | 4 / 2 | the same, at 2 |
| `union { u8 c; }` | 1 / 1 | one field |
| `offsetof` of every field | 0 | every field at the first byte |
| `__attribute__((packed))` of the 5-byte one | **5 / 1** | packed: alignment 1, **no rounding** |
| `__attribute__((aligned(16)))` of it | 16 / 16 | raised, size rounded to it |
| `in6_addr`'s union | 16 / 4 | |
| `epoll_data` (`void *`, `int`, `u32`, `u64`) | 8 / `_Alignof(u64)` = 8 | 8-aligned on the 32-bit targets too (AAPCS32, ILP32 RISC-V, wasm) |
| `struct { u8 tag; union { u32; float; } v; u8 t; }` | `v` at 4, `t` at 8, 12 | a union field is placed by its own size and alignment |
| the same with the union **anonymous** | `i` and `f` at 4, 12 | an anonymous member is laid out where a named one would be |
| `union { u64 all; struct { u32 lo; u32 hi; }; }` | `hi` at 4, 8 | anonymous struct in a union |
| `union { u32 all; struct { u32 a:3; u32 b:5; } bits; }` | 4 | a bitfield struct is its storage unit |
| packed struct `{ u8 tag; union { u32; float; } v; }` | `v` at **1**, 5 | a union inside a packed struct is unaligned |
| `union { void *p; u32 n; }` | `sizeof(void *)` both | pointer-sized |
| SDL_Event's shape (`u32 type` + event structs + `u8 padding[128]`) | 128, `k.key` at 8 | |
| `union { double d; u32 w[2]; }` | 8 / `_Alignof(double)` | |

So `layout.sysl`'s union rule is three lines: `size = round_up(max(size(f)), max(align(f)))`,
`align = max(align(f))`, under `@packed` `align = 1` and no rounding, under `@align(n)` `align =
max(align, n)`. Every field's offset is 0. **`@assert(sizeof(U) == …)` and `@assert(offsetof(U, f) ==
0)` work unchanged**; `offsetof(S, f)` for an anonymous member's field answers the member's offset
plus the field's.

## 4. By value at a C boundary — what each convention does with a union

`union-probes/abi.c` returns each of ten unions (and one struct holding a union) by value;
`clang -O1 -S -emit-llvm`, the `define` lines, for the nine targets above:

| union | aarch64 (Darwin; Linux adds `alignstack(8)`) | x86-64 SysV | x86-64 Windows | thumb hard-float | thumb soft-float | riscv32 ilp32f | riscv64 lp64d | wasm32 |
|---|---|---|---|---|---|---|---|---|
| `FI {float; u32}` | ret `i32`, arg `i64` | `i32` | `i32` | ret `i32`, arg `[1 x i32]` | same | `i32` | `i64` | memory |
| `F1 {float}` | **HFA** `[1 x float]` | `float` | `i32` | **HFA** | `[1 x i32]` | **`i32`** | `i64` | **`float`** |
| `FF {float; float[2]}` | **HFA `[2 x float]`** | `<2 x float>` | `i64` | **HFA** | sret / `[2 x i32]` | **`[2 x i32]`** | `i64` | memory |
| `DI {double; u64}` | `i64` | `i64` | `i64` | sret / `[1 x i64]` | same | `i64` | `i64` | memory |
| `D2 {double[2]}` | **HFA `[2 x double]`** | `double, double` | memory | **HFA** | sret / `[2 x i64]` | memory | **`[2 x i64]`** | memory |
| `FD {float[4]; double[2]}` | `[2 x i64]` (not HFA) | `double, double` | memory | sret / `[2 x i64]` | same | memory | `[2 x i64]` | memory |
| `B12 {u8[12]; u32[3]}` | `[2 x i64]` | `{ i64, i32 }` | memory | sret / `[3 x i32]` | same | memory | `[2 x i64]` | memory |
| `B24 {u8[24]; u64[3]}` | memory | memory (`byval`) | memory | sret / `[3 x i64]` | same | memory | memory | memory |
| `PI {void *; u32}` | `i64` | `ptr` | `i64` | `[1 x i32]` | same | `i32` | `i64` | memory |
| `struct { float x; union { float; u32 } }` | `i64` (not HFA) | `i64` | `i64` | sret / `[2 x i32]` | same | **`[2 x i32]`** | `i64` | memory |

What `cabi.sysl` has to add, convention by convention — everything else it already does by **size
and alignment**, which a union has like any aggregate:

- **AAPCS64 and AAPCS32 hard-float — an HFA is counted by size.** `FF` has three float leaves (`a`,
  `b[0]`, `b[1]`) and is passed as **two** floats: a union is homogeneous when every leaf of every
  field has the one floating type, and its count is `size / sizeof(that type)`. `c_hfa` counting
  `c_leaf_types` would say three. `FD` (`float` and `double` leaves) is not homogeneous, so it takes
  the size rules. A union holding any integer leaf is never an HFA (`FI`, and the struct row).
- **System V — nothing new, provided overlapping leaves are allowed.** The eightbyte classification
  already asks "is every leaf touching this eightbyte floating?"; a union contributes all its fields'
  leaves at their offsets, overlapping, and the same question gives `FI` → INTEGER (`i32`), `FF` →
  SSE (`<2 x float>`), `FD` → SSE twice. The chunk naming must take the widest floating leaf of the
  eightbyte (`double`, where `FD` has two `float`s and a `double` over the same bytes).
- **Windows x64 — nothing new.** Size alone.
- **RISC-V — a union is never flattened, and neither is any aggregate containing one.** `F1`, a
  union of one `float`, is an `i32` on `ilp32f` where a struct of one `float` is a `float`; the
  struct holding a union is `[2 x i32]` where `{float, float}` would be two floats. So the
  hard-float path in `c_riscv` is skipped whenever the walk meets a union; the size rules follow.
- **wasm32 (and CRAFT, which follows wasm's rule) — only a one-field union unwraps.** `F1` is a
  `float`; `FI` and `PI`, two fields of one width, go in memory. `c_only_scalar` sees one leaf only
  when the union has one field.
- **AAPCS32 soft-float, AAPCS64 by size, riscv by size** — unchanged.

All of this lands as new shapes in `tests_abc_abi_against_clang.sysl`, which already compares
sysl's signature with clang's for every shape on every machine; that is the test that makes the table
above the compiler's contract rather than this note's.

## 5. Safety — what a program may do with one

The whole note turns on this. **sysl's promise is that the raw pointer is the single unsafe
primitive** (`memory.md`): no `unsafe` block exists, and a program holding no `*T` cannot forge a
reference or read a value that is not one. A union is a way to read bytes as a type they were not
written as, so it either keeps that promise or becomes a second unsafe primitive.

### The options

| option | what it allows | verdict |
|---|---|---|
| (a) any field type; a non-`AnyBits` field is readable only in an "unsafe-like form" | needs a form sysl does not have | rejected: it invents an `unsafe` tier for one feature, and `ptr_cast` already **is** that tier — `*ptr_cast[*bool](&u)` reads a byte as a `bool` today, greppably |
| (b) unrestricted, as C | reading a `&T` field forges a reference; reading 2 out of a `bool` or 7 out of a two-variant enum hands the optimizer a value it is entitled to assume cannot exist (a `match` on it is a jump table) | rejected: a union becomes an unsafe primitive with no `*` to grep for |
| (c) fields restricted to `AnyBits` | every read is of a value | sound, but **too narrow for the case that motivated the item**: SDL3's `SDL_KeyboardEvent` holds `bool down; bool repeat;`, so `SDL_Event` could not be declared |
| **(d) fields restricted to plain data and raw pointers; non-`AnyBits` parts checked on read** | `AnyBits` fields read freely; a `bool`/enum/`char`/subtype part is checked where it is read, trapping on a bad byte; counted/program-made types refused | **recommended** |

### (d) in detail

**A field's type must be one of these, every part of it:**
- `AnyBits` (the trait on branch `buf-set-len`): whole-byte integers, floats, whole-byte fixed
  point, `*T`, arrays and structs of them, bitfield structs of integers. **Read freely, no check.**
- the rest of `sysl.encoding.Plain` (`plain.sysl`): `bool`, a simple enum, and structs/arrays/bitfield
  structs holding them. **A read checks every byte that can be wrong** — exactly the check `Plain`'s
  decode already emits (`plain_check` in `plain.sysl`), with a trap instead of an `Err`:
  `union field 'down' holds 2, which is no bool`. Reading a whole struct field checks each of its
  checked parts; reading an `AnyBits` field of it checks nothing.
- `char` and a subtype with a range or predicate (outside `Plain` because their rule is not a byte
  rule; their check is the scalar-value test and the subtype's own predicate, both of which exist).
  Recommended in; Q5 asks.

**Refused at the declaration**, naming the field and the reason, in `any_bits_refusal`'s style:
`&T`, `weak T`, `string`, a slice, `Buf` and every collection (they hold a `&`), a closure, a data
enum, a `Task`, an opaque struct by value, and any struct or array holding one. **The reason is
ownership as much as validity**: sysl releases a struct's counted fields when it drops; a union has
no tag, so the drop cannot know which field is there, whether one is, or whether the bytes are an
integer that happens to look like an address. There is no rule that makes a union of a `string` and
a `u64` droppable, so it is refused rather than leaked. (Rust reaches the same place, E0740: a union
field must be `Copy` or `ManuallyDrop`; Zig: "extern unions cannot contain fields of type
'[]const u8' — slices have no guaranteed in-memory representation".)

**A function pointer `*extern(…) -> R` is the one undecided type** (Q2): `AnyBits` refuses it ("a
function pointer has to name a function"), yet sysl lets one be `null` (`ffi.md`, `var installed:
*extern(*u8, *u8) -> i32 = null`), and calling a garbage one is the same act as dereferencing a
garbage `*T`. Darwin's `struct sigaction` is a union of two function pointers, so the answer decides
whether it maps.

**Writes** are unrestricted: storing a field writes its bytes and leaves the others' bytes as they
were. A sub-place write (`u.b8[3] = 1`, `ev.key.down = true`) writes those bytes only. (Rust makes
`a.b8[0] = 1` unsafe — E0133 on the indexed write, probed — because its fields carry validity
invariants; under (d) a sub-place write cannot break one, since a later read checks.)

**Addresses**: `&u.f` gives a `*T` as for a struct field — the unsafe tier already. A `ref` to a
*checked* field is refused: a read through the `ref` would bypass the check (its target is the field,
and the field's bytes can change under it through a sibling field).

**A union of all-`AnyBits` fields is `AnyBits`**; one with any checked field is not. A union is
`Plain` only when every field is both `AnyBits` and `Plain` (no pointers): decoding one from bytes
cannot know which field to check.

**One consequence to state plainly**: a union with a `*T` field and an integer field converts an
integer to a pointer without `ptr_cast(n)`. Making that pointer is harmless — dereferencing any
`*T` is already the unsafe tier — but the greppable spelling of the conversion is now
`union … *` as well as `ptr_cast`. The reference page says so (Q3).

### How it reads for the C cases

| case | fields | what the program writes |
|---|---|---|
| `struct in6_addr`'s `union { u8[16]; u16[8]; u32[4] }` | all `AnyBits` | `a.b16[0]`, `a.b8[15] = 1` — free, no check |
| `epoll_data` (`*u8`, `i32`, `u32`, `u64`) | all `AnyBits` | `ev.data.fd`, `ev.data.ptr` — free |
| `sockaddr` punning: `union { sockaddr; sockaddr_in; sockaddr_in6; sockaddr_storage }` | structs of integers (`sa_family_t` is a `c type`) | free |
| `SDL_Event` | `u32 type`, event structs of `u32`/`i32`/`f32`/`c type` enums, and `bool`s in the keyboard/button events | `ev.type` free; `ev.key.down` checks one byte; `ev.key` whole checks its two `bool`s |
| a register: `union { word: u32; bits: CtrlBits }` (bitfield struct) | `AnyBits` unless a bitfield is a sysl enum | free; an enum bitfield is checked against its variants |
| a C enum through import-c | a `c type` (an integer) | free — import-c already maps C enums to integers |

## 6. Construction, zero, patterns, equality, and the other features

- **Construction `U(f = v)`**: exactly one argument, named — a positional `U(v)` is refused even for
  a one-field union, so adding a field never changes what an existing call means. Two named
  arguments are refused (Rust E0784 "union expressions should have exactly one field"; Zig "union
  initialization expects exactly one field" — both probed). **The bytes past `f` are zeroed**,
  deterministic where C and Rust leave them unspecified: it costs a store of at most `sizeof(U)`, and
  it means a union handed to C never carries stale stack bytes. A struct with an anonymous member is
  built by name (`Packet(tag = 1, word = 7, len = 2)`), naming at most one field per anonymous
  union.
- **Zero value**: all-zero bytes, present exactly when every field has a zero value — the struct rule
  (probed: a struct with a sysl enum field has none, `S has no zero value, so 'z' needs an initial
  value`). Every `AnyBits` field's zero value is zero bytes; `false` is.
- **Patterns**: `match u` / `val U(f = x) = u` destructuring is refused ("a union has no tag to
  match on — read the field you know is there"). Binding a union whole is fine.
- **Equality**: no supplied `Eq`, and `==` refused with the reason (`+0.0`/`-0.0` and NaN make
  bytes-equality wrong for floats; the bytes past a short field are not part of its value). A
  program writes `impl Eq` comparing the field it knows. Rust refuses `derive(PartialEq)` on a union;
  Zig refuses `==` ("operator == not allowed for type") — both probed.
- **Debug**: every sysl type has a supplied `Debug` (`traits.md`), and a union keeps that: the name
  and its bytes in hex, `In6(00 00 … 01)`. It never reads a field, so it never traps. An `impl
  Debug` of the program's own replaces it, as for a struct.
- **`const` and module `val`**: a union built from constants folds to static data (bytes), which is
  what a register map on a board needs (a module `val` that does not fold shuts a package out of a
  bare target). A compile-time read of the **field it was built with** folds; a read of a different
  field is refused in a `const` context for now ("const evaluation reads a union only through the
  field it was built with") — `const_eval` has a value model, not a byte model. Zig reinterprets
  `extern union` bytes at comptime (probed: `Ext{ .f = 1.0 }.i == 0x3f800000` folds); Q6 asks
  whether sysl should.
- **`@byte_order(big)`**: applies to every integer of every field, as on a struct; `union { raw:
  [4]u8; word: u32 }` under it reads `word` in network order.
- **`@packed` fields**: a union inside a `@packed` struct sits unaligned (probed, offset 1) and has no
  address, the existing packed rule. A `@packed` union's fields have no address either.
- **Bitfields**: a bitfield struct may be a field (C's register idiom, probed: 4 bytes). A bare
  sub-byte field (`a: u3` directly in a union) is refused — the bits above it in its byte are not
  its own, `AnyBits`' reason — and the struct form says the same thing.
- **Threads**: a union holds nothing counted, so it crosses a domain as a struct of scalars does; a
  `*T` field as a `*T` does.
- **Vectors**: a vector field is allowed (NEON's `union { float32x4_t; float[4] }`). `any_bits_refusal`
  on `buf-set-len` sends `Vector` to its catch-all and so refuses it, although every bit pattern of a
  vector of whole-byte integers or floats is a value: that is a gap in `AnyBits` to fix before this
  lands (one line, `Vector(_, elem) -> any_bits_refusal(*elem)`).
- **`sysl prove`**: refused by name ("the proof backend does not translate a union"); WhyML has no
  byte model to say what a field read means.
- **`opaque union`**: allowed, as `opaque struct`; the header gets `typedef union h h;`.

## 7. `@export` and the generated header

`c_aggregate_definition` writes `typedef union {\n\t<fields>} name;`. `@packed` adds
`__attribute__((packed))`, `@align(n)` adds `__attribute__((aligned(n)))` — **and the same has to be
done for structs, which today lose both** (§8). An anonymous member is written as C11's anonymous
`union { … };` / `struct { … };` in place. `@export("name")` chooses the typedef's name, as for a
struct. A union may appear in an exported signature; its passing follows §4.

## 8. A bug found while probing: the header drops `@packed` and `@align(n)`

`sysl 0.1.3`, `build-c` of

```
@packed
@export("wire_hdr")
struct H
    a: u8
    b: u32

@align(16)
@export("al")
struct A
    a: u8
```

writes `typedef struct { uint8_t a; uint32_t b; } wire_hdr;` and `typedef struct { uint8_t a; } al;`
— no attribute on either (`c_header.sysl:363-372` has no branch for them). sysl lays `H` out in 5
bytes and aligns `A` to 16 (`print(sizeof(H), alignof(A))` → `5 16`), and a C file including the
header says otherwise:

```
check.c:2: static assertion failed due to requirement 'sizeof(wire_hdr) == 5'  -- evaluates to '8 == 5'
check.c:3: static assertion failed due to requirement '_Alignof(al) == 16'     -- evaluates to '1 == 16'
```

A C caller of an exported function taking `wire_hdr` reads every field after the first at the wrong
offset. `tests_export.sysl`'s `ex_a_struct_name_composes_with_packed` checks the name and not the
layout, which is why it passes. It is a bug in a shipped artifact, so it wants its own fix and
release ahead of the union work (the union's header emission reuses the same fix). Bitfield structs
in the header should be checked in the same fix.

## 9. `import-c` mapping

| C | sysl |
|---|---|
| `union U { … }` / `typedef union { … } U` | `union U` + fields, `SIZEOF_`/`ALIGNOF_` `c const`s and an `@assert` for each, every `offsetof` asserted 0 |
| anonymous `union`/`struct` member | an anonymous block in place; offsets of its fields asserted through the outer type |
| a named field of an anonymous union type (`union { … } v;`) | a generated `union <Outer>_<field>` beside the outer type, and `v: <Outer>_v` |
| a member of pointer type (`T *`, `char *`, `void *`) | `*T` / `*u8` — `AnyBits`, read freely |
| a member of C `bool`, or a struct holding one | the field as it is; its read is checked (§5) |
| a member of a C `enum` type | already a `c type` integer, so `AnyBits` |
| a member of function-pointer type | `*extern(…)` if Q2 lets it in; otherwise the union is withheld with "has a function-pointer member ('sa_handler')" |
| a member whose type import-c withholds (bitfields, a flexible array, `#pragma pack`, another header's record by value) | the union is withheld too, computed to the fixed point as for structs — Q4 asks for bytes instead |
| `__attribute__((packed))` / `aligned(n)` union | `@packed` / `@align(n)` (import-c's Q6 for structs applies equally) |
| a union with a bitfield member directly (`union { unsigned a:3; unsigned b; }`) | withheld, "has a bitfield" (the existing reason) |

## 10. What the analogy checks said

The probes are `union-probes/r.rs` and `union-probes/z.zig` / `z3.zig`; the Rust file and `z3.zig`
are written to fail, and their errors are the evidence. `union-probes/run.zsh` re-runs the C probes
of §3 and §4 on all nine targets.

**Rust** (`rustc 1.94.0`, probed): a `#[repr(C)]` union; fields must be `Copy` or `ManuallyDrop`
(E0740 on a `String` field); every read is `unsafe` (E0133), and so is a pattern on one, and so is a
**sub-place write** (`a.b8[0] = 1` — E0133); a whole-field write is safe; a `bool` field is allowed and
reading a bad one is undefined behaviour; one field at construction (E0784); `derive(PartialEq)` is
refused. Rust can afford "every read is unsafe" because it has `unsafe` blocks; sysl does not, which
is why (d) moves the danger into the field types instead.

**Zig** (`zig 0.15.1`, probed with `build-obj -target aarch64-linux`, since its linker does not run on
this macOS): **two kinds.** A bare `union` carries a **hidden safety tag** in Debug and ReleaseSafe —
`@sizeOf(union { i: u32, f: f32 })` is 8 — and reading the inactive field is a panic (at comptime:
"access of union field 'i' while field 'f' is active"). An `extern union` has C's layout, may be read
through any field, and refuses non-extern field types (slices). Both refuse `==`, and both take one
field at initialization. **What Zig learned** is that an untagged union is two features: a sum type
that happens to be unchecked (which a tag fixes) and a C layout tool (which nothing fixes). sysl
already has the first — a data enum is a tag beside a union — so `union` here is only the second,
which is why it gets C's layout always and no hidden tag.

## 11. Open questions — the user decides

- **Q1 — the safety model.** Recommendation: **(d)**. The alternative worth weighing is (c), strictly
  `AnyBits` fields: no read is ever checked, at the cost of `SDL_Event` (and any C union whose members
  hold a `bool`) mapping only with its `bool`s spelled `u8`.
- **Q2 — function pointers.** Should `*extern(…) -> R` be a union field (and join `AnyBits`, since it
  can already be `null` and calling a bad one is the same act as dereferencing a bad `*T`)?
  Recommendation: **yes**, as `AnyBits`; it is what maps `sigaction` and every C callback table held
  in a union.
- **Q3 — a `*T` field beside an integer is a second spelling of `ptr_cast(n)`.** Recommendation:
  accept it and say so on the reference page; `epoll_data` and `sigval` are exactly this.
- **Q4 — import-c, a member whose type is withheld.** Recommendation: map it as `_<name>: [N]u8`
  with the union carrying `@align(ALIGNOF_)`, so one opaque member does not withhold an
  otherwise-usable union. The cost is a field the program can only read as bytes.
- **Q5 — `char` and range/predicate subtypes as checked fields.** Recommendation: **in**; the check
  exists for both. Leaving them out costs nothing a C binding needs.
- **Q6 — compile-time reads through another field.** Recommendation: **refused for 0.2.0**; a byte
  model in `const_eval` is its own item.
- **Q7 — zero-fill at construction.** Recommendation: **zero**, as above; the alternative (leave the
  rest unspecified, C's and Rust's answer) saves a store and makes `Debug` print stack garbage.

## 12. Implementation plan

A union is a `StructType` with a `union_layout: bool` flag (and anonymous members recorded as nested
`StructType`s with no name), **not a new `Type` variant**: member lookup, generics, visibility,
`impl`s, `@export` naming and attributes then come for free, and the sites that must differ are the
ones that compute an offset, construct, drop, compare, print or classify. Today there are **232
`Struct(…)` match sites in 79 non-test files**; each is read once and classified "same for a union" or
"differs", which is the bulk of the work.

| area | files | what changes | tests (fresh prefix — all free at writing) |
|---|---|---|---|
| parser | `parse_decl`, `parse_member`, `ast_types`, `show_decl` | soft word `union`; anonymous `union`/`struct` blocks; `end` marker | `uni_` parse + the refusals (empty, `invariant`, duplicate name across levels, positional construction) |
| types, layout | `types`, `layout`, `type_queries`, `resolve_type` | the flag; size/align/offset rule; `offsetof` through anonymous members | `uni_` sizes and offsets mirrored from `layout.c` on every target |
| field rule | new `union_fields.sysl` beside `any_bits.sysl`, `plain.sysl` | refusal per §5; vectors into `AnyBits`; union's own `AnyBits`/`Plain` membership | `unr_` every refused type with its diagnostic; generic instance refusal |
| construction, reads, writes | `expr_call`, `expr_member`, `emit_place`, `emit_expr` | one named argument + zero fill; checked reads through `plain_check`; `ref` to a checked field refused | `unk_` checked read traps (bool 2, enum out of range) and passes; IR text: no check on an `AnyBits` read |
| drop, Eq, Debug, patterns, zero | `emit_arc`, `deriving`, `pattern`, `exhaustive`, `type_defaults` | no drop; `==` refused; hex `Debug`; destructuring refused; zero value rule | `uni_` each, error path with the exact diagnostic |
| const | `const_eval`, `const_fold`, `analyze_body` (`static_image`) | fold to bytes; cross-field read refused | `uni_`; a `thumb-freestanding` `build-c` probe that a module `val` union is data |
| C ABI | `cabi` | HFA-by-size, overlapping SysV leaves, RISC-V no-flatten, wasm/CRAFT one-field unwrap | the ten shapes of `abi.c` added to `tests_abc_abi_against_clang.sysl` (every target, both directions) |
| header | `c_header` | `typedef union`, anonymous members, `packed`/`aligned` (**and the §8 struct fix first**) | `unh_`: header compiled by clang with `_Static_assert`s on size/align/offset |
| other checks | `byte_order`, `bitfield_int`, `sharing`, `whyml`, `aliasing`, `self_alias` | `@byte_order` over fields; `prove` refusal; aliasing treats every field as one place | `uni_` per feature; `bor_` gains a union case |
| docs | `reference/types.md` (new `## Unions`), `attributes.md` (`@packed`/`@align`/`@byte_order` on a union), `ffi.md` (a union at the boundary), `library/core.md` (`AnyBits` lists unions and vectors) | runnable blocks with `output`/`error` | the docs sweep for each page |
| import-c | `import_c*.sysl` on the import-c branch | §9 | `imc_` round trips on headers with each C row |

**Size, honestly**: about 2,500–3,500 lines of compiler and 1,500–2,000 of tests, in **five
bounded items**: (1) the §8 header fix, released on its own; (2) parser + types + layout + field rule +
construction/reads/writes + Debug/Eq/zero/patterns, with the `types.md` page; (3) the C ABI and
header, with the ABI-against-clang shapes; (4) anonymous members, `const` folding and the remaining
feature interactions; (5) import-c's mapping, after (2)–(4) are released. Items (2) and (3) are the
large ones; (3) is where a wrong answer is silent, which is why it is gated on clang's own signatures
for every target rather than on this table.
