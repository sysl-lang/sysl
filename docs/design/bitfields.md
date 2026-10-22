# C-compatible bitfields — design

sysl gets **C-compatible bitfields** in 0.2.0 (user, 2026-10-17): a C struct with bitfields can be
declared field for field in a binding, and `sysl import-c` maps one instead of withholding it ("has a
bitfield, whose layout C leaves to the implementation", `import_c_render.sysl`).

C's standard does leave the allocation to the implementation — but every platform ABI sysl targets
pins it, and clang follows them. This note measures those rules on every sysl target, chooses a
surface, and plans the work.

**Status: built except §7.** A struct with C bitfields is spelled, laid out, read and written by the
target's C rule, mapped by `import-c`, and passed by value at a C boundary as clang passes it (§6,
`cabi.sysl`, checked against clang on every machine by `tests_abc_abi_against_clang.sysl`). Its
declaration in a generated header (§7) is not written yet: the header gives it as an incomplete type.

## 1. Decisions

| # | question | recommendation | why, in one line |
|---|---|---|---|
| 1 | today's bitfield struct | **unchanged**: a `@packed` struct with an odd-width integer field stays one integer, bit-exact, alignment 1 | seven keel structs (system registers, page descriptors) rely on exactly that, and it is the right tool for a register or a wire format |
| 2 | surface | **(c), spelled `name: uN in T`** — the value type, then the C *declared* type the field is allocated in. A struct with such a field is an ordinary (C-layout) struct; `@packed` on it is GNU `packed` | C's layout is a function of each field's declared type, which a width alone does not carry (§3); (a) cannot say `char a:3; int b:5`, and (b) changes a layout by how a struct is used |
| 3 | the layout rule | the target's C rule, one of **four** families measured on nineteen triples: Itanium generic, AAPCS (non-Apple Arm: unnamed and `:0` fields raise the struct's alignment), i386 (falls out of `long long`'s 4-byte struct alignment), **MSVC** (a unit per run of same-size declared types, never straddles) | §3; no target is big-endian, so the bit order is "from the least significant bit" on every one |
| 4 | reads and writes | load the field's **storage unit** (its declared type's width, at the unit's naturally aligned offset), shift, mask; a write is load–insert–store of that unit; `volatile` makes those the volatile accesses, of exactly that width (AAPCS's rule, and what a register needs) | §5 |
| 5 | `@byte_order` | **refused** on a struct with an `in` field in 0.2.0 | C has no portable spelling (gcc's `scalar_storage_order` also reverses the bit allocation), and today's `@packed` bitfield struct already covers wire formats |
| 6 | header | `c_header.sysl` writes the real declaration — `uint32_t mode : 3;` — for an `in` struct; today's bitfield struct keeps its one container integer | the layouts match by construction, so the C reader gets the fields by name |
| 7 | by value at a C boundary | **not** "the same as an ordinary struct of its size" everywhere: true on AArch64, Arm, SysV x86_64 (up to IR type width), MSVC, riscv32 and soft-float riscv64; **wasm32** passes a one-integer struct directly but a two-bitfield one indirectly, and **lp64d riscv64** counts each bitfield (and a `:0`) as an integer member when flattening | §6 — `cabi.sysl` needs two new rules |
| 8 | import-c | `T name : W;` → `name: uW in T'` (`iW` where `T` is signed), with `T'` the alias import-c already uses for an ordinary field of type `T`; `T : W;` unnamed → `_: uW in T'`; `T : 0;` → `_: in T'` | mechanical once the surface names the declared type |

## 2. What sysl has today

**A `@packed` struct whose fields all lower to integers, at least one of them not a whole number of
bytes, is one unsigned integer** (`attributes.md` § "Bitfields — an `iN` field in exactly N bits";
`layout.sysl`, `bitfield_ranges`). Its fields are ranges of that integer, filled from bit 0 upward in
declaration order with no gap and no unit boundary: a field straddles a byte (or any other) boundary
rather than moving off it. Whole-byte fields (`u8`, `u16`, `u36`) are ranges like any other; a field
may be another bitfield struct, taking its fields' total width; `bool`, pointers, floats and arrays
are refused. `T::Bits(n)` and `.bits` convert to and from the container.

The container is `bitfield_bits(ranges)` bits — the used width rounded up to whole bytes, never less
than eight — and **its alignment is 1** (`layout.sysl:251`). Measured with sysl 0.1.5:

| sysl | size / align | the same fields in C (`unsigned` / `uint64_t`), Itanium |
|---|---|---|
| `Ctrl { enable: u1, mode: u3, prescale: u4 }` | 1 / 1 | 4 / 4 (`unsigned`), 1 / 1 (`unsigned char`) |
| `Esr { iss: u25, il: u1, ec: u6, iss2: u5, res0: u27 }` | 8 / 1 | 8 / 8 (`uint64_t`): **same bits**, different alignment |
| `Odd { a: u3, b: u18 }` | 3 / 1 | 4 / 4 |
| `struct Holder { tag: u8, e: Esr }` | 9 / 1 | 16 / 8 |

A field read is one load of the container (`container_lty`, an `iN` of the container's width at
alignment 1) and a shift and mask (`read_bits`, `emit_expr.sysl:430`); a write loads, inserts and
stores it (`emit_place.sysl`, `bit_place`/`container_at`). `volatile` on any field makes every access
to the container volatile. `@byte_order` swaps the container's bytes before the ranges are taken.
`offsetof` and `&` refuse a field ("it has no byte offset").

**The header writes the container, not the fields** (`c_header.sysl:370`, `bitfield_container`): a
comment per field giving its bit range, then `uint{8,16,32,64}_t bits;` or, for any other width,
`uint8_t bits[n];` — all under `SYSL_PACK_PUSH` / `SYSL_PACKED`:

```c
SYSL_PACK_PUSH
typedef struct SYSL_PACKED {
	/* a: bits 0..2 */
	/* b: bits 3..20 */
	uint8_t bits[3];
} Odd;
SYSL_PACK_POP
```

So the bits of today's struct equal C's exactly when C's fields are all one unsigned declared type
whose width is the container's and nothing straddles a unit — the `Esr` case — and even then the
alignment differs. That is what `ffi.md` means by "no sysl spelling", and it is right about today.

## 3. C's rules, measured

`bitfield-probes/layout.c` holds 29 structs covering every case the item lists; `run.zsh` compiles it
with `clang 23.1.3 -fdump-record-layouts` for every triple in `target.sysl` plus
`x86_64-pc-windows-msvc` (which is also a sysl target), and `layouts/<triple>.txt` is the result, one
line per struct (`field@bit+width | size/align`). **CRAFT has no clang** and is not measured; it is
sysl's to define (Q5). **No sysl target is big-endian** (`cpu_little_endian` is true for every `Cpu`),
so on every one bits are allocated from the least significant bit of the unit, upward.

The nineteen triples fall into four families and **agree on everything else**:

| family | triples |
|---|---|
| **Itanium** | `x86_64-{apple-macosx, unknown-linux-gnu, unknown-none-elf}`, `arm64-apple-macosx`, `riscv64-unknown-{linux-gnu, elf}`, `riscv32-unknown-elf`, `wasm32-{unknown-unknown, wasip1}` |
| **AAPCS** | `aarch64-{unknown-linux-gnu, linux-android24, none-elf}`, `thumbv8m.main-none-eabi{,hf}`, `thumbv7em-none-eabihf`, `thumbv7m-none-eabi`, `thumbv6m-none-eabi` |
| **i386** | `i386-unknown-linux-gnu` |
| **MSVC** | `x86_64-pc-windows-msvc` |

Bit offsets are from the start of the struct; a cell left blank is the same as Itanium's.

| C | Itanium | AAPCS | i386 | MSVC |
|---|---|---|---|---|
| `unsigned a:3, b:7, c:22` | b@3 c@10, 4/4 | | | |
| `unsigned a:30; unsigned b:4` (straddles) | b@32, 8/4 | | | |
| `unsigned char a:5, b:4` (straddles) | b@8, 2/1 | | | |
| `unsigned a:30; unsigned long long b:40` | b@64, 16/8 | | **b@32, 12/4** | |
| `unsigned a:20; unsigned long long b:20` | b@20, 8/8 | | 8/**4** | **b@64, 16/8** |
| `unsigned long long a:3` | 8/8 | | **4/4** | |
| `unsigned a:1; unsigned long long b:63` | b@1, 8/8 | | 8/**4** | **b@64, 16/8** |
| `uint64_t iss:25, il:1, ec:6, iss2:5, res0:27` (keel's `Esr`) | 0/25/26/32/37, 8/8 | | 8/**4** | |
| `unsigned a:3; unsigned :0; unsigned b:3` | b@32, 8/4 | | | |
| `unsigned char a:3; unsigned char :0; unsigned char b:3` | b@8, 2/1 | | | |
| `char a; int :0; char b` | b@32, 5/1 | **8/4** | | **b@8, 2/1** |
| `unsigned a:3; unsigned :5; unsigned b:3` | b@8, 4/4 | | | |
| `char a; int :4` | 2/1 | **4/4** | | **:4@32, 8/4** |
| `int a:3; unsigned b:3; char c:3` | b@3 c@8, 4/4 | | | **c@32, 8/4** |
| `char a:3; int b:5` | b@3, 4/4 | | | **b@32, 8/4** |
| `char a; int b:5` | b@8, 4/4 | | | **b@32, 8/4** |
| `short a:9; char b:7` | b@9, 2/2 | | | **b@16, 4/2** |
| `int a:20; char b:6` | b@24, 4/4 | | | **b@32, 8/4** |
| `unsigned a:3; unsigned char c; unsigned b:5` | c@8 b@16, 4/4 | | | **c@32 b@64, 12/4** |
| `unsigned char c; unsigned b:20` | b@8, 4/4 | | | **b@32, 8/4** |
| `_Bool a:1, b:1; unsigned char c:6` | b@1 c@2, 1/1 | | | |
| `_Bool a:1; unsigned b:3` | b@1, 4/4 | | | **b@32, 8/4** |
| `__attribute__((packed))` `unsigned a:3, b:30` | b@3, 5/1 | | | **b@32, 8/1** |
| `__attribute__((packed))` `unsigned char c; unsigned b:20; unsigned long long d:40` | b@8 d@28, 9/1 | | | **d@40, 13/1** |
| `#pragma pack(1)` `unsigned a:3, b:30` | b@3, 5/1 | | | **b@32, 8/1** |
| `#pragma pack(1)` `unsigned char c; unsigned b:20` | b@8, 4/1 | | | **5/1** |
| `unsigned char a:3` / `unsigned a:3` | 1/1 / 4/4 | | | |
| `enum E e:2; unsigned b:3` | b@2, 4/4 | | | |

**The declared type is the whole rule.** It is not the value's type in any useful sense — a 3-bit
field holds 0..7 whatever it is declared as — but it is the *storage unit*: a unit of `sizeof(T)`
bytes, aligned to `alignof(T)` within the struct. On the three Itanium-derived families:

- a field goes at the next free bit **unless** the `alignof(T)`-aligned, `sizeof(T)`-byte unit that
  holds that bit cannot also hold its last bit; then it moves to the next `alignof(T)` boundary. That
  is the whole straddling rule, and why `unsigned char a:5, b:4` is two bytes while `unsigned a:5,
  b:4` is one unit, and why i386, whose `long long` aligns to 4 inside a struct, puts `b:40` at bit 32
  where x86_64 puts it at bit 64;
- a **named** field raises the struct's alignment to `alignof(T)`, so `unsigned a:3` is 4/4 and
  `unsigned char a:3` 1/1;
- an **unnamed** field (`int :4`) and a **zero-width** one (`int :0`) do *not* raise it on Itanium,
  and **do** on AAPCS — the one place the AAPCS family differs (Apple's arm64 ABI follows Itanium
  here). `:0` moves the next field to the next `alignof(T)` boundary;
- `packed` (attribute or `#pragma pack(1)`) drops the unit restriction and the alignment: fields abut
  bit for bit, straddling anything — **which is exactly today's sysl bitfield struct**, except that
  C still rounds the size up to whole bytes and does not insist the struct is one integer.

**MSVC** is a different algorithm, not a variation:

- consecutive bitfields share a unit **only while their declared types have the same size** and the
  unit has room; a different size, or an ordinary field between, starts a new unit at the next
  `alignof(T)` boundary (`char a:3; int b:5` is two units, 8 bytes; `_Bool` and `unsigned char` share
  one, being the same size);
- a field **never straddles** its unit, even under `packed`/`pack(1)`, which only removes the padding
  *between* units (`packed2`: `d` starts at byte 5 in a fresh 8-byte unit);
- `:0` acts only after a bitfield (`char a; int :0; char b` is 2/1), and an unnamed field raises the
  alignment like a named one.

**Signedness** is the declared type's too, and two declared types are not what they look like: a
plain `char` bitfield reads **signed** on x86, Apple, wasm and MSVC, and **unsigned** on Linux/Android
Arm, bare-metal Arm and RISC-V (plain `char` is unsigned there); an `enum` bitfield reads unsigned on
Itanium (an enum of non-negative enumerators has an unsigned underlying type) and signed on MSVC
(`int`). `int a:3` is signed on every target (clang; C leaves it implementation-defined). The layout
never depends on signedness — `int a:3; unsigned b:3` share a unit everywhere, MSVC included.

## 4. Surface

### The options

**(a) A struct attribute, `@c_bitfields`, switching today's layout to C's.** It reads well on one
line, costs today's users nothing (opt-in), and is useless on its own: `a: u3` names a width, and C's
layout needs a declared type. `@c_bitfields(u32)` names one for the whole struct, which covers the
common register header (`uint32_t` throughout) but not `char a:3; int b:5`, not a struct mixing
`uint8_t` and `uint16_t` fields (USB descriptors, many network headers), and not a bitfield beside an
ordinary field of another type — and import-c would have to withhold every such struct anyway.

**(b) A struct reached by `extern`/`@export` gets C's rule automatically.** It needs no syntax and has
the same missing declared type as (a), plus a worse property: the same declaration lays out
differently depending on whether some function in the program passes it to C, so adding an `extern`
moves every field of a struct the rest of the program already uses. It would silently change keel's
`Esr` (8/1 → 8/8, and `Holder` 9 → 16) the day keel declares one `extern` taking it. Rejected.

**(c) Name the declared type per field.** The brief's spelling is `a: u32 bits 3`; the recommended
one is **`a: u3 in u32`**:

```sysl
// struct usb_setup { uint8_t recipient:5, type:2, dir:1; uint8_t request; uint16_t value, index, length; };
struct UsbSetup
    recipient: u5 in u8
    kind: u2 in u8
    dir: u1 in u8
    request: u8
    value: u16
    index: u16
    length: u16

// struct timer_ctrl { volatile uint32_t en:1, mode:3, _:4, presc:16; };
struct TimerCtrl
    en: volatile u1 in u32
    mode: volatile u3 in u32
    _: u4 in u32
    presc: volatile u16 in u32
```

`u3 in u32` keeps the field's **type** what sysl already says it is — an exact three-bit integer, so
`s.mode = 9` is refused at compile time and a read needs no masking by the reader — and adds only what
C's layout needs. `a: u32 bits 3` would make the field's type `u32`, giving up the open integer
family's checking for C's promotion, and adds a word. `in` is already a keyword, so nothing new is
reserved, and the line reads as what it is: three bits *in* a `u32`.

### Rules for (c)

- `T` in `uW in T` is an **integer type** (including the target-dependent aliases import-c already
  uses — `c_long` is 4 bytes on Windows and 8 on LP64, and that is exactly the case where the
  declared type moves the layout) or `bool`; `W ≤ bits(T)`; the value type's signedness is `T`'s,
  so `i3 in u32` is refused (the header writes `T`, and C's read must agree with sysl's).
- The value type may be `uW`/`iW`, a simple enum whose underlying type is `W` bits wide, or `bool`
  (1 bit; `bool in bool` is C's `_Bool b:1`, `bool in u8` its `unsigned char b:1`, the same layout on
  every target).
- `_: uW in T` is an unnamed field; `_: in T` is the zero-width one (sysl has no `u0`: "the width
  families start at 1").
- A struct with any `in` field is an **ordinary struct** laid out by the target's C rule; its other
  fields are ordinary fields, placed as C places them after a bitfield (byte-aligned to their own
  alignment, MSVC: a new unit). `@packed` on it is GNU `packed` (MSVC: `pack(1)`), `@align(n)` raises
  it as for any struct. `in` inside today's `@packed` bit-exact struct is refused — the two models do
  not mix in one struct. A `union` may hold `in` fields (all at bit 0 of their units), which maps
  C's `union { unsigned a:3; unsigned b; }`.
- `&s.f` and `offsetof` refuse an `in` field with today's diagnostics; `.bits`/`::Bits` do not exist
  on an `in` struct (it is not one integer).

**Cost to today's users: nothing.** The org grep for `@packed` structs with odd-width fields finds
seven, all in keel (`kernel/keel/trap/trap.sysl`, `mm/mmu.sysl`, `mm/desc.sysl`): system registers
and translation-table descriptors, which want the bit-exact `u64` layout they have. fat, fat-write and
kfs use `@packed` only for whole-byte on-disk structs. None changes.

**import-c** maps a bitfield in one step: clang's AST gives `isBitfield`, the width (the field's
`ConstantExpr` child) and the declared type, and import-c already has the alias for every declared
type it meets as an ordinary field. Plain `char` maps to the alias whose signedness is the target's
(Q3).

## 5. What a read and a write compile to

For each `in` field the layout computes: the **unit's byte offset** (the `alignof(T)`-aligned
`sizeof(T)` block holding the field, or under `@packed` on Itanium, the smallest whole-byte span
holding it), the **bit shift** within it, and the width.

- **Read**: `load iU, ptr (base + unit offset), align alignof(T)` (align 1 under `@packed`), then
  `lshr` by the shift and `trunc` to `iW` — or, for a signed field, `shl` to the top and `ashr` back,
  which is what clang emits. A `bool` field compares the bit to zero.
- **Write**: load the unit, `and` with the inverted mask, `or` with the value `zext`ed and shifted,
  store the unit.
- **`volatile`**: the load and store are volatile and **exactly the declared type's width**, never
  merged with a neighbour and never narrowed — the AAPCS rule for volatile bit-fields (preserve the number and
  width of container accesses), which is what a peripheral register needs (a peripheral may ignore or fault an access
  narrower than its register). As today, the qualifier on any field of a
  unit qualifies every access to that unit; a write is a read-modify-write and nothing diagnoses what
  that costs a clear-on-read register. A `volatile` field that straddles its unit (possible only
  under `@packed`) is refused — there is no single access to make.
- **Non-volatile** accesses may use the same unit access; LLVM merges adjacent ones. Clang's own
  "access units" (`CGRecordLayout`) sometimes load a wider span; the bits are the same, and only the
  volatile width is ABI.
- A **value** of the struct (not reached through memory) is an IR aggregate whose bitfield units are
  `iU` members, so `extractvalue` + shift does the same job; construction is `insertvalue` of each unit
  built by `or`-ing its fields. A module `val` folds to bytes as any struct does.
- **`@byte_order`** is refused on an `in` struct (Q4).

## 6. The C ABI by value

`bitfield-probes/abi.c` returns twelve bitfield structs by value, each beside an ordinary struct of
the same size and alignment; `abi.zsh` lowers both with clang on every triple and compares the
signatures with the names removed. **Same on 12 of 19 triples** — AArch64 (Apple and AAPCS64), every
Thumb, MSVC, `riscv32-unknown-elf`, `riscv64-unknown-elf` (soft float). The rest:

| triple | shape | bitfield struct | ordinary twin | consequence |
|---|---|---|---|---|
| x86_64 SysV (3 triples) | `{double d; unsigned a:3;}` | `(double, i64)` | `(double, i32)` | same registers; IR type only |
| riscv64 lp64d | `{float f; unsigned a:3;}` | `(float, i8)` | `(float, i32)` | same registers; IR type only |
| **riscv64 lp64d** | `{float f; unsigned a:3, b:3;}` | `i64` (one GPR) | `{float f; unsigned x;}`: `(float, i32)` | **different registers**: two bitfields are two integer members, so the FP+int flattening does not apply |
| **riscv64 lp64d** | `{float f; int :0; float g;}` | `i64` (one GPR) | `{float f; float g;}`: `(float, float)` | **different registers**: clang counts the `:0` as a member |
| **wasm32** (2 triples) | `{unsigned a:3; unsigned b:29;}`, `{unsigned short a:3, b:9;}` | `sret` + `byval` pointer | `{unsigned x;}`: direct `i32` | **different signature**: two fields is not a single-element struct; a one-bitfield `{unsigned a:3;}` *is* direct `i32` |
| i386 | every shape | `byval` pointer | expanded scalars | the same stack bytes (clang expands only when they are); IR shape only |

So `cabi.sysl` classifies an `in` struct by **its members, with each named bitfield one integer
member of its own width rounded up to a byte** (`a:3` → `i8`) — not by its size alone. That is what
the wasm single-element rule and the RISC-V flattening rule both consume, and on every other
convention it reduces to the size-and-alignment answer the ordinary struct gets. The `:0` case follows
clang; GCC's treatment of a zero-width bitfield in the flattening rule has changed between releases and was
not measured here, so the two compilers may disagree on lp64d; sysl follows clang, its oracle (Q6).

Building it against clang on more shapes than the twelve refined the rule per convention (the shapes
are `tests_abc_abi_against_clang.sysl`'s `abc_bit_shapes`):

- **The member's width rounds up to a power-of-two number of bytes**, not to a byte: `a:20` is an
  `i32`, `a:40` an `i64` (RISC-V's `(float, i32)` for `{ float f; unsigned a:20; }`).
- **An unnamed bitfield is a member to AAPCS and RISC-V** (`{ float f; unsigned :4; float g; }` is no
  HFA, and three members on lp64d) **and nothing to System V's classification or wasm's single
  element**; a `:0` is no member to any of them but RISC-V, which gives up flattening where one
  precedes the second member within its struct (`{ float f; float g; int :0; }` still flattens).
- **wasm's single element is the bitfield's declared type**: `{ unsigned a:3; }` is an `i32`,
  `{ unsigned char a:3; }` an `i8`, `{ _Bool on:1; }` an `i1`.
- **System V names an integer eightbyte by its first member only where no bitfield's declared type,
  counted from the bit it begins at, reaches past that member** — named, unnamed or `:0` — which is
  why `{ double d; unsigned a:3; }` is `(double, i64)` and `{ double d; unsigned short a:12; }` is
  `(double, i16)`. And where the first eightbyte's piece is short of eight bytes and the second's
  alignment would put it at byte four, the first widens (`float` → `double`, integer → `i64`):
  `{ float f; long long :0; float g; }` is `(double, float)`.
- **An HFA has no padding**: on AAPCS `{ float f; long long :0; float g; }` is sixteen bytes and
  travels as `[2 x i64]`.

## 7. The header

For an `in` struct, `c_aggregate_definition` writes the fields as C declares them:

```c
typedef struct {
	uint32_t en : 1;
	uint32_t mode : 3;
	uint32_t : 4;
	uint32_t presc : 16;
} TimerCtrl;
```

with `volatile` where the field has it, `bool` for `in bool`, the C name of the alias (`unsigned
long` for `c_ulong`), `T : 0;` for `_: in T`, and `SYSL_PACKED`/`SYSL_PACK_PUSH` for `@packed` as
today — `pack(1)` on MSVC gives MSVC's packed layout, which is what sysl computes for that target, so
the header and the layout agree there too. Today's bitfield struct keeps its container integer.
`uint32_t` as a bitfield type is implementation-defined in C (C11 6.7.2.1p5) and accepted by clang on
every triple above; the header test compiles it there.

## 8. Proposed rewrite of the `ffi.md` row

The row under "What a binding cannot say" (`ffi.md:2711`) would read, once this lands:

| **a shape with no sysl spelling** | an untagged union with a member of a type sysl withholds, and an inline function. A **C bitfield struct is spelled** field for field — `mode: u3 in u32` — and laid out by the target's C rule ([attributes](/reference/attributes/)); only a bitfield struct under `#pragma pack(n)` for `n` other than 1 has no spelling |

and `ffi.md:1344` ("a bitfield struct is the one integer it is stored as … whose allocation unit is
the C compiler's choice") gains the second case: an `in` struct is written as C's own declaration.
`ffi.md` itself is not edited by this note.

## 9. Open questions — the user decides

- **Q1 — the spelling.** Recommendation: **`name: uW in T`**. The alternative is the brief's
  `name: T bits W`, which makes the field's type C's declared type and gives up exact-width checking.
- **Q2 — the zero-width field.** Recommendation: **`_: in T`**. Alternatives: allow `u0` only here
  (`_: u0 in T`), or an attribute on the next field.
- **Q3 — plain `char` in import-c.** Its signedness is the target's (§3). Recommendation: map it to
  `u8`/`i8` per the target being imported for, as import-c does for an ordinary `char` field, and
  say in the generated comment that it was plain `char`.
- **Q4 — `@byte_order` on an `in` struct.** Recommendation: **refused in 0.2.0**. Allowing it means
  choosing between "swap each unit" (what a sysl reader would guess) and gcc's
  `scalar_storage_order`, which also allocates bits from the most significant end; neither is
  expressible in the header.
- **Q5 — CRAFT.** No C compiler to measure. Recommendation: the **Itanium** family, with the
  one-field-unwrap convention CRAFT already uses for unions; the CRAFT C compiler, when there is one,
  follows sysl.
- **Q6 — `:0` under riscv64 lp64d.** clang 23 counts it as a member when flattening; GCC may not (unmeasured). Recommendation: follow clang (the `abc_` oracle) and say so on the page.
- **Q7 — `#pragma pack(n)`, `n` = 2, 4.** Not covered by `@packed`. Recommendation: out of scope; it
  is rare in headers that use bitfields, and `@packed` + `@align(n)` covers most of the rest.

## 10. Implementation plan

| area | files | what changes | tests (fresh prefix — `cbf_`, `cbl_`, `cbh_` free at writing) |
|---|---|---|---|
| parser | `parse_member`, `parse_type`, `ast_types`, `show_decl` | `name: T in S` and `_: in S` on a struct/union field; `volatile` placement | `cbf_` parse, printing, and the refusals with their diagnostics |
| types | `types`, `resolve_type`, `decls`, `type_attr` | a field's storage type; the rules of §4 (integer/`bool` storage, `W ≤ bits(S)`, signedness, no mixing with `@packed` bit-exact, `@byte_order` refused) | `cbf_` each refusal, error path with the exact text |
| target | `target` | a `bitfield_rule` per row: Itanium / AAPCS / MSVC (i386 falls out of its `long long` alignment, CRAFT → Itanium) | — |
| layout | `layout` | `c_bitfield_layout`: unit offset, shift, width per field; size/align per family, packed and not; unions | **`cbl_`: the 29 structs of `layout.c` on all 19 triples, every field's bit offset and the size/align compared with clang's `-fdump-record-layouts` at test time**, as `abc_` asks clang |
| IR type | `ir_type`, `data_layout` | the aggregate: ordinary fields + one `iU` per unit, explicit padding | covered by `cbl_` and the IR-text tests |
| reads, writes | `emit_place`, `emit_expr`, `emit_enum` | the unit access of §5; signed extraction; `bool`; volatile width; enum check on read | `cbf_` host round trips (sysl writes, C reads, and back, through a linked C object); IR text for the volatile access width on `thumbv7em` |
| construction, `with`, patterns, `Eq`, `Debug`, const | `expr_call`, `emit_expr` (`:1151`), `emit_pattern` (`:152`), `deriving`, `const_eval`, `emit_static`, `payload_image` | build units from fields; fold a module `val` to bytes | `cbf_` each; a `thumb-freestanding` `build-c` probe that a module `val` is data |
| C ABI | `cabi` | classify by members (§6): wasm single-element, lp64d flattening | the twelve shapes of `abi.c` in `tests_abc_abi_against_clang.sysl`, every target, both directions |
| header | `c_header` | §7 | `cbh_`: the header compiled by clang for every triple with `_Static_assert`s on size, align and each field's value after a known store |
| docs | `attributes.md` (new section beside today's bitfields), `ffi.md` (§8), `getting-started/from-c.md:251`, `types.md` (field syntax) | runnable blocks with `output`/`error` | the docs sweep for each page |
| import-c | `import_c*.sysl` on the import-c branch | §1 row 8 | `imc_` round trips on headers with each row of §3 |

**Size, honestly**: about 1,500–2,200 lines of compiler and 1,200–1,800 of tests, in **four bounded
items**: (1) parser + types + target rule + layout, with `cbl_` against clang on every triple — the
part where a wrong answer is silent, gated on clang rather than on the table above; (2) reads, writes,
construction, the remaining feature interactions and the `attributes.md` section; (3) the C ABI and the
header, with `abc_` and `cbh_`; (4) import-c's mapping, after (1)–(3) are released. Items (1) and (2)
are the large ones, at roughly three agent-days each; (3) is two, (4) one.
