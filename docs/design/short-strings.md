# Short strings held in the value — design (SS1, 0.13)

A `string` of a few bytes made at run time costs a heap object today: a 32-byte header-and-count
block plus the bytes, a `malloc`, four header stores, and on its death a release that goes through
`arc.destroy`, the hook and `free`. C++ and Rust's `smol_str`-style types keep such strings in the
value itself. This document fixes how sysl does the same thing, what it costs and breaks, and the
items that build it.

**Status: proposed for 0.13.** The user endorsed the goal (2026-11-20: *"if we could handle short
strings in a special more efficient way, that would be great"*). The recommendation (§ 5, option A)
is written so that implementation agents can take the items in § 6 directly. One question for the
user remains (§ 7).

## 1. The evidence

The source is AWFY Json, one inner iteration, measured on the 0.12.0 compiler in the profiling study
(`awfy-gaps.md` § Json; `awfy-profile/r1/counts.txt`, `r2/hist.txt`, `r3/traces.txt`).

| | sysl | C++ |
|---|---:|---:|
| time per iteration | ≈ 16.9 ms | ≈ 13.0 ms (sysl is 1.30x, a gap of 3.9 ms) |
| `malloc` calls | 779 053 | 426 530 |
| bytes allocated | 51.5 M | 27.0 M |
| `malloc` + `free` time (from the profile) | ≈ 6.2 ms | ≈ 4.7 ms |

These are the requested sizes in sysl's histogram that are strings. A string object is
32 + n bytes: a three-word `%arc.header` plus a count word, then the bytes.

| request | count | what it is |
|---:|---:|---|
| 33–38 B | 210 508 (35: 66 301, 34: 43 500, 38: 31 200, 37: 26 404, 33: 25 302, 36: 17 801) | 1–6-byte strings. These are `end_capture`'s `self.input[a..b].copy()` (object keys and numbers) and the `f"${capture_buffer}${…}"` joins |
| 32 B | 55 307 | probably the **empty** string these paths make (header + count + 0 bytes). This is unconfirmed: a field-less box would also be 32 B |

C++ has no allocation at these sizes because `std::string` keeps up to 22 bytes inline (libc++).

**A correction to the brief.** The brief's figure of "1.07 M extra mallocs per loop" does not match
the data. The measured difference is 779 K − 427 K = **352 K** mallocs, and **≈ 210 K** of those
(≈ 266 K if the 32 B requests are empty strings) are short strings. The 1.07 M figure looks like the
1 048 629 *releases* in `counts.txt`. The estimates below use the measured numbers.

`self.current`, the one-character string Json reads all the time, is **not** an allocation. It is
`self.input[i..<i + 1]`, a slice of a literal, so its owner is null (the AWFY port's `json.sysl:313`). It matters
below only as the hottest *reader* of strings: whatever SS1 adds to every access is paid there.

## 2. The current representation

**There is one string type, and it owns its bytes.** The language has no separate owned/borrowed
pair (Rust's `String`/`&str`). A `string` is three words, and the first word holds a share of the
heap object that keeps the bytes alive. `StrBuilder` (`library/sysl/text/build.sysl:20`) and
`CString` (`build.sysl:185`) are library types built on `Buf[u8]`, not a second string
representation. `StrBuilder.finish` copies (`build.sysl:140` → `from_utf8_unchecked` = `str_cast`,
`library/sysl/text/utf8.sysl:128`).

| fact | where |
|---|---|
| `Type.Str` — "a read-only view of bytes that are valid UTF-8" | `compiler/sh/sysl/compiler/types.sysl:381-382` |
| lowered exactly as a slice: `l_view` = `{ ptr, ptr, iW }` (owner, first byte, length) | `ir_lower.sysl:61-62`, `ir_type.sysl:160-170` |
| size three words, alignment one word | `layout.sysl:247`, `layout.sysl:300` |
| a literal: owner **null**, `ptr` into rodata (with a NUL the length leaves out) | `emit_str.sysl:13-19` |
| reading the bytes = `extractvalue` fields 1 and 2, used by every runtime call | `emit_str.sysl:22-32` (`str_bytes`), `emit_str.sysl:36-42` |
| the heap object (`StrBuf`): `%arc.header = { word count, ptr hook, word share }`, then a count word, then the bytes, so 32 + n bytes on 64-bit | `str_runtime.sysl:17-55`, `arc_runtime.sysl:93` |
| producers, each of which allocates a `StrBuf`: `sysl.str.concat`, `sysl.str.from_bytes` (`copy()`, `str_cast`, `from_utf8*`), `sysl.str.char` (`string(c)`), the number renderers | `str_runtime.sysl:270`, `:312`, `:341`, `:390`; `emit_str.sysl:52-71`, `:260-278` |
| an interpolation writes into a growing heap buffer, then `from_bytes` **copies** it into a fresh `StrBuf` and frees the buffer, which is two mallocs per interpolated string | `writer_runtime.sysl:147-162` |
| retain/release of a string is `arc.retain_maybe`/`release_maybe` of field 0, which tests for null and does nothing on null | `emit_arc.sysl:105-106`, `:162-172`; `arc_runtime.sysl:100-111` |
| a borrowed view or string in a closure has its **owner word set to null** ("null is the form of a view of a frame's own array") | `emit_arc.sysl:174-185` |
| `s[i]` and `s[a..b]` take the string apart as a view: fields 0, 1, 2 | `emit_place.sysl:267`, `:1107-1121`; boundary checks read the bytes at both ends, `:1257-1266` |
| `s.len` is `TLen`, `s.bytes` is `TBytes`, `s.chars` is `chars_of(TBytes(s))` | `expr_member.sysl:132-146` |
| **`s.bytes` is the identity on the three words** | `emit_expr.sysl:66` |
| `str_view`/`str_alias` give the slice's own three words, sharing the slice's owner | `utf8.sysl:147`; `strings.md:141-180` |
| to C, a string is a three-word aggregate (`c_aggregate`, `c_walk_view`) | `cabi.sysl:83-87`, `:652-653` |
| into a `&sync` object, a string is copied into bytes whose count is atomic; a literal is left alone | `sharing.sysl:80`, `:515`; `strings.md:198-224` |
| escape analysis: `TBytes` views what its receiver views, and a string made from frame bytes promotes them ("is made into a string, which outlives the frame"); under `@no_alloc` a promotion is refused | `escape.sysl:1086`, `:754-757`, `:1584-1601` |

The whole layout is used as one word-for-word identity with `[]u8`: `.bytes`, `str_view`, slicing
and the ARC code all share the view code. That identity is what SS1 has to give up in one controlled
place.

## 3. What the contracts forbid and allow

Every statement here is from `docs/content/reference/`. Each says whether a short form may change it.

| contract | page | effect on SS1 |
|---|---|---|
| "a **three-word** owning view"; owner, `ptr`, `len` | `strings.md:7-23` | The **size** must stay three words. The text describes the fields, and that description must change to name a second form. A spec change, not a break: nothing a program can write reads the fields by name |
| **not NUL-terminated**; only a literal carries a NUL (the NUL is not part of the string) | `strings.md:1003-1065`, `emit_str.sysl:16-18` | Allows a short form with no NUL. `cstring` already copies, and `c"…"` is a literal, which is never short |
| O(1) substring that **shares** its parent | `strings.md:20-23`, `:938-941` | Holds for long strings. A substring of a short string is a ≤ 15-byte copy, which is O(1) by bound |
| `s.bytes -> []const u8` is an **O(1) view**; writing through it is refused | `strings.md:247`, `:283-298` | Still O(1) and still read-only. It is no longer *the same three words*: for a short string the view must point at storage (§ 5.4) |
| "A `string` is exactly an immutable, validated `[]u8` — the same three words… one implementation underneath" | `strings.md:1147-1157` | **Changes.** The type identity holds (`.bytes` is free and checked one way), and the representation identity becomes "or a short form". The table row "representations: 1" becomes "1 type, 2 forms" |
| `str_view(b)` does not copy and **sees later writes** to `b` | `strings.md:141-180` | Must hold. A string made by `str_view`/`str_alias` is **never** short |
| immortal literal: null owner, no count, constant | `strings.md:182-196` | Unchanged. A literal is never short, because it already costs nothing |
| allocator-free subset: hold, pass, compare, index, slice, iterate and release any string handed in | `strings.md:1067-1131` | All hold. The short form needs no allocator. The one exception is an escaping `.bytes` (§ 5.4, § 7) |
| `&sync` crossing copies into atomically counted bytes; `&sync Frozen[T]` refuses `string` elements | `strings.md:198-224`; `memory.md:2634-2641` | A short string has no count, so it crosses as a literal does: a gain. `Frozen` is unchanged |
| `extern`: "what crosses is the programmer's business"; aggregates by value go in the C convention's registers | `ffi.md:385-409` | A short string passed **by value** to an `extern` must be normalised at the call (§ 5.5). A string inside memory C reads through a `*T` is the programmer's business already, and becomes a documented change |
| `@export` **refuses** a `string` parameter, result or exported storage; a struct crossing must be built from scalars and pointers | `ffi.md:965-986`, `ffi.md:1322-1326` | **The `@export` ABI and `build-c` headers are untouched**, since no string can appear in them |
| a function whose signature carries an aggregate has no `*extern` address | `ffi.md:3016-3033` | C never calls sysl with a string, so no callback path is affected |
| two sysl shared libraries share objects only through their C API | `ffi.md:2189-2196` | No mixed-version string ever crosses a binary boundary |

**The conclusion that drives the design:** no ABI a C program can name carries a `string` except an
`extern` declared with a three-word struct parameter. Everything else is internal to one compiled
program, so the representation is free to change if the compiler changes every reader together.

## 4. Options

`W` is the word size in bytes: 8 on aarch64, x86_64 and riscv64; 4 on wasm32, thumb, riscv32 and x86.
Every target sysl names is little-endian (`target.sysl:141-154`).

### Option A — the short form in the value, with the owner word kept null (recommended)

**Layout** (64-bit; 32-bit in brackets):

```
long  (today):  [ owner: ptr | ptr: ptr            | len: iW  (top bit 0) ]
short (new):    [ 0 (null)   | bytes[0 .. 2W-2]               | 0x80|n ]
                  bytes 0..7    bytes 8..22                     byte 23
                 [bytes 0..3    bytes 4..10                     byte 11]
```

- The capacity is `2W − 1` bytes: **15** on 64-bit, **7** on 32-bit. The length `n` is in 1..15 and
  sits in the low 7 bits of the last byte. The last byte's top bit is the tag, and it is the top bit
  of the `len` word read as an integer. No long string can set that bit, because a length of 2^63
  (2^31) or more cannot be allocated.
- **The owner word of a short string is null.** That single choice keeps retain, release, `borrowed`
  owner-nulling (`emit_arc.sysl:174-185`), `arc_elide` and every count-placing analysis exactly as
  they are. All of them already treat a null owner as "nothing to count", and a short string has
  nothing to count.
- The empty string is never short. It is the long form `{null, @empty, 0}`, so a producer that makes
  zero bytes allocates nothing either (the 55 K requests of 32 B, if they are empty strings).
- The value holds no pointer to itself. A bitwise copy, move, `memcpy` relocation (SL1) or spill is
  correct with no fix-up. This rules out a libstdc++-style layout, whose `ptr` points into the object
  and has to be rewritten by every copy. sysl copies strings as SSA values and with plain `memcpy`
  everywhere, so a self-pointer cannot work.

**Cost on every access.** Reading the bytes becomes `str_data(v) -> (ptr, len)`:

```
%w2   = extractvalue %str %v, 2
%sh   = icmp slt iW %w2, 0                          ; the tag
%sn   = and (lshr %w2, 8W-8), 127
%len  = select %sh, %sn, %w2
store %str %v, ptr %slot                            ; spill (fields 1-2 only); dead-store-eliminated when unused
%sp   = getelementptr i8, ptr %slot, W
%p1   = extractvalue %str %v, 1
%ptr  = select %sh, %sp, %p1
```

This is branch-free: one compare, two selects and a spill of two words to an entry-block slot. For
an SSA value the code is loop-invariant, so LICM hoists it out of `for i in 0..<s.len` loops. `s.len`
alone costs only the compare and a select. A string in memory (a field or a `Buf` element) needs no
spill: the short bytes are at `&field + W`. A literal operand has a compile-time form, so `s == "ab"`
can compare words directly as a later optimisation.

**Effects.**

- **Slicing** (`s[a..b]`). For a long string the result is the long view it is today, even when it is
  ≤ 15 bytes: sharing the owner is cheaper than copying, and it keeps `str_view`'s guarantee. For a
  short string the result is short: the 128-bit `fshr`/mask of words 1–2 by `8a`, with tag `0x80|(b-a)`,
  so there is no loop and no call. Boundary checks read the end bytes through `str_data`.
- **C interop.** `cstring(s)` copies through `str_data`. An `extern` taking a string **by value**
  gets `{null, &slot, n}`: the bytes are copied to a frame slot for the call, which is exactly the
  contract of a null-owner frame view. With `@keeps` on that parameter, the compiler builds a heap
  `StrBuf` instead, so C may hold it. A string an `extern` returns is C-made, so it is long. `va_`
  forms and `print` go through `str_data`.
- **Threads.** A short string crosses into a `&sync` object as it is: no atomic copy, no count
  (`sharing.sysl` skips it as it skips a literal). Substrings of it are short, so they need no atomic
  operations either. `&sync Frozen` is unchanged.
- **`@export` ABI and `build-c`.** Unchanged, because strings cannot appear in them (§ 3). An extern
  declared with a three-word struct by value is normalised as above.
- **Migration.** The compiler changes in about 19 emission files that name `Str`
  (`emit_str`, `emit_place`, `emit_expr`, `emit_pattern`, `emit_flow`, `emit_stmt`, `emit_scalar`,
  `emit_checks`, `emit_cross`, `emit_disjoint`, `emit_moves`, `emit_entry`, `emit_enum_attr`,
  `emit_fixed`, `emit_arc` (no logic change), `cabi`, `sharing`, `escape`), plus the runtime blocks in
  `str_runtime` and `writer_runtime`. Nearly all of it is "extract field 1/2" turned into
  `str_data`. The library needs no change for correctness. 111 library files use `.bytes`, and each
  use either borrows (free) or escapes (it then promotes, § 5.4); the audit item counts the escaping
  ones. **Estimated size:** 1.5–2.5 K lines with tests, in six items.
- **Behaviour change: yes, but narrow** (§ 5.6).

### Option B — the libc++ layout: tag in the owner word, 22 bytes inline

**Layout.** Byte 0 is `(n << 1) | 1`. The owner word's low bit is the tag, which is free because
headers are word-aligned. Bytes 1..23 are data, so the capacity is **23** on 64-bit and 11 on
32-bit.

**Cost on every access.** The same select on the tag, plus a byte offset of 1 (unaligned bytes).
**Also on every retain and release:** `owner & 1` must be tested before the owner is dereferenced.
Slices share `retain_maybe`/`release_maybe` today, so strings would need their own pair. Every place
that **writes null into an owner** to borrow (`emit_arc.sysl:174-185`; lent views, `emit_place.sysl:1123-1149`)
would **destroy a short string's first bytes**, so each one must learn the tag.

**Effects.** Slicing and C are as in A. Threads are as in A, except that `sharing.sysl`'s owner test
also changes. `@export` and `build-c` are untouched. The **migration** is everything in A, plus the
ARC runtime split, every owner-writing site, and `arc_elide`'s "null owner" reasoning, which is about
1.5x the work of A with a much wider correctness surface. It is the same narrow behaviour change as A.

**Why not B.** The 8 extra bytes of capacity do not pay for the cost. Json's strings are 1–6 bytes,
identifiers are mostly ≤ 15 bytes, and every `i64` up to 15 digits renders short in A. In exchange,
B puts a tag test on the hottest operation in the runtime (counting), and invalidates the one
invariant the ownership analyses lean on (null owner = nothing to count).

### Option C — no representation change: pooled short-string objects

**Layout.** Unchanged (three words, long form always). A `StrBuf` whose payload is ≤ 32 bytes comes
from a **thread-local free list per size class** (48 B and 64 B blocks), and its hook returns the
block to the list. A string freed on another thread (an atomically counted copy) goes back to
`free`, because shared copies are never pooled.

**Cost on every access:** none. **Slicing, C, threads, `@export`, `build-c`:** all untouched.
**Migration:** about 300 lines in `arc_runtime`/`str_runtime`, plus the hook and the thread-local
slot (the existing `arc.reaper` slot mechanism, `arc_runtime.sysl:64-74`, shows the pattern).
**Behaviour change:** none, apart from memory being held in pools (`SYSL_COUNT_BYTES` unchanged; peak
RSS can be higher).

**Why not C alone.** It removes only part of the cost. The four header stores, the counted
retain/release, and the `release` → `arc.destroy` (noinline) → hook → `arc.unshare` (noinline) chain
on every death all remain. On Json it saves an estimated 0.6–1.0 ms of the 3.9 ms, against A's
1.5–2.4 ms. It also does nothing for memory: a 3-byte string still takes 48 bytes.

### Option D — interning or arenas (rejected)

Interning makes short strings immortal through a global hash table. It costs a hash and a lookup on
every creation, the table grows without bound (Json's numbers are unbounded), and threads need a lock
or a sharded table. Arenas need region lifetimes that sysl's ownership does not express. Neither is
general, and both are worse than A on the Json shape.

### Side by side

| | A: null-owner short form | B: libc++ tag | C: pool | D: intern |
|---|---|---|---|---|
| inline capacity (64 / 32-bit) | 15 / 7 | 23 / 11 | 0 | — |
| malloc for 1–15 bytes | none | none | pooled pop | lookup + maybe insert |
| count ops on a short string | none (null owner) | tag test + none | full | none (immortal) |
| tax on every byte access | compare + 2 selects (hoistable) | the same | none | none |
| tax on every retain/release | **none** | tag test | none | none |
| owner-nulling borrows | unchanged | **must all change** | unchanged | unchanged |
| `.bytes` identity | lost for short strings (§ 5.4) | lost | kept | kept |
| `@export` / `build-c` | untouched | untouched | untouched | untouched |
| `extern` by value | normalised at the call | normalised | untouched | untouched |
| est. Json gain (of 3.9 ms) | **1.5–2.4 ms** | ≈ A | 0.6–1.0 ms | < C |
| migration | ~19 files + runtime | A + ARC + owner writes | runtime only | runtime + locks |
| behaviour change | narrow (§ 5.6) | narrow | none | memory never freed |

## 5. Recommendation: option A

### 5.1 Why

A gives the largest real gain. A short string costs no allocation, no header and **no count
operation at all**, because its owner is null. It keeps strings pleasant: one type, no new API, no
`SmallString` to choose between. It also keeps C interop sound: `@export` and headers cannot see a
string, and the one `extern` path is normalised by the compiler. Every analysis that reasons about
ownership is unaffected, because a short string looks to all of them like a literal.

### 5.2 Expected gain (estimate; § 6, item SS1-6 measures it)

| part | Json, per iteration |
|---|---|
| mallocs + frees removed: 210.5 K certain (+ 55 K if the 32 B requests are empty strings) of 779 K, at ≈ 8 ns a pair (6.2 ms / 779 K) | −1.7 ms (−2.1 ms) |
| header stores + the release → destroy → hook → unshare → free chain on each death, ≈ 3 ns | −0.6 ms (−0.8 ms) |
| new tag selects on string reads (≈ 1 M reads, ≈ 0.3 ns, mostly hoisted) | +0.2 to +0.4 ms |
| **net** | **−1.5 to −2.4 ms of a 3.9 ms gap (40–60%)**: Json 1.30x → about **1.12–1.18x**; AWFY geomean −0.8 to −1.2% |

Bytes allocated per iteration fall by ≈ 7.6 M (51.5 M → ≈ 44 M). Outside AWFY, every
`n.to_string()` of up to 15 digits, every `string(c)`, every `copy()` of a token, and every short
key in a `Map[string, V]` stops allocating. The compiler itself, whose identifiers are mostly
≤ 15 bytes, is a large beneficiary.

The item SS1-7 below (an interpolation's buffer starts in the frame) is separate and adds to this:
a short `f"…"` goes from 2 mallocs to 0.

### 5.3 Risk

- **A missed reader.** Any code that extracts field 1 of a short string and dereferences it reads
  inline bytes as an address and crashes. *Mitigation:* SS1-1 routes every reader through
  `str_data` first, as a pure refactor, and pins it with a source test. No file other than
  `emit_str.sysl` may `InExtract` field 1 or 2 of `lty(e, Str)`. The short form is built behind
  `SYSL_SHORT_STRINGS=1` until SS1-6, so every existing string test family runs under both forms
  before the default flips. `SYSL_SHORT_STRINGS=0` stays as the diagnostic off switch.
- **Spill slots and views.** A `.bytes` of a short string is a frame temporary. If the escape model
  misses a case, a view outlives its slot. *Mitigation:* § 5.4 reuses the existing promoted-temporary
  machinery instead of adding a new one, and ASan runs on the `ssx_` tests.
- **Embedded code size.** The selects add a few instructions per access. On thumb the capacity is 7.
  Measure `.text` on one board example in SS1-6. If it regresses beyond 2%, the off switch per target
  is `short_string_capacity(t) = 0`.

### 5.4 `.bytes`, `.chars`, and views of a short string

`TBytes(s)` stops being the identity (`emit_expr.sysl:66`):

- For a long string, it is the same three words as today.
- For a short string, it is `{null, slot, n}`. The slot is a **fresh frame temporary** of 2W − 1
  bytes holding a copy of the bytes, so a later write to the variable `s` never changes a view
  already taken (`var s = …; val b = s.bytes; s = "zz"` still sees the old bytes).
- **Escape analysis treats that slot as a frame temporary array** (`escape.sysl:1086` gains the
  pseudo-root). A view **lent** to a call, iterated, or indexed costs nothing. A view **kept** past
  its scope, **returned** or **stored in the heap** is promoted, as a temporary array is today: the
  promotion is `sysl.str.unshort(s)`, which makes a heap `StrBuf` only when `s` is short, and the
  view then shares it. `--explain-escapes` says *"is the bytes of a string that may be held in the
  string itself"*.
- Under `@no_alloc`, the promotion is refused by the existing `refuse_no_alloc`
  (`escape.sysl:1584-1601`) **unless the string is provably long**: a literal, a slice of one, a
  `str_view`. This is the one expressiveness question (§ 7).
- `s.chars` (`chars_of(TBytes(s))`) follows from this. A `for c in s.chars` loop is lent and free.

### 5.5 What every producer does

`n ≤ 2W − 1` builds the short value, with no call to the heap. `n = 0` gives the static empty long
form. Larger `n` gives a `StrBuf` as today. The producers are `sysl.str.from_bytes` (`copy()`,
`str_cast`, `from_utf8`, `from_utf8_unchecked`, `StrBuilder.finish`), `sysl.str.concat`,
`sysl.str.char`, the number and float renderers, and `sysl.w.buf.finish`. `str_alias`/`str_view`
**never** produce the short form.

### 5.6 Behaviour changes (for the release notes, above the features)

1. **`s.bytes` of a string of 15 bytes or fewer (7 on 32-bit) that escapes its frame now copies
   those bytes once** where it used to share. It is still O(1) by bound and still read-only. Two
   `.bytes` of the same short string have different addresses.
2. **Under `@no_alloc`, an escaping `.bytes` of a string not known to be long is refused** (pending
   § 7).
3. **An `extern` that receives a `string` by value gets a pointer valid for the call only** when the
   string is short. `@keeps` on the parameter restores a heap copy. A C program that reads a sysl
   string's words **through memory** (`*Struct` with a `string` field) must read the short form, and
   `strings.md` documents the layout.
4. Allocation counts change: `SYSL_COUNT_ALLOCS` and `SYSL_COUNT_BYTES` report fewer, and docs
   blocks or tests that print allocation counts move.

## 6. Implementation items, in dependency order

Each item is one agent, branched off dev, and gated on its own tests only. The test prefix is
**`ssx_`**, followed by the item's letter. Three test axes apply to every item:

- **lengths** 0, 1, 2W−2, 2W−1, 2W, and a multi-byte character straddling the capacity (14 ASCII
  bytes + `é` = 16 bytes);
- **word size** 64-bit host, plus 32-bit through a `target=wasm32` or `thumb` `build-c` block;
- **mode** debug, `--release`, LTO.

**SS1-1 — one reader (pure refactor, no behaviour change).**
- Add `str_data(e, v) -> (ptr, len)`, `str_len(e, v)` and `str_data_at(e, addr)` to `emit_str.sysl`.
  Route every `InExtract` of field 1/2 of a `Str` through them: `str_bytes`, `gen_from_bytes`,
  `emit_place` `view_parts` for `Str` (`:267`, `:1107`, `:1329`), `TLen`, the boundary checks,
  patterns, `emit_checks`, `emit_cross`, `emit_disjoint`, and `cabi`'s argument path.
- Tests: a source test (`ssx_a`) that fails if any compiler file but `emit_str.sysl` extracts field
  1/2 at `lty(e, Str)`. The existing string families and `SYSL_DOCS_ONLY=reference/strings.md` stay
  green.

**SS1-2 — the short form, behind `SYSL_SHORT_STRINGS=1`.**
- In `layout`: `short_string_capacity(l) = 2W − 1`.
- In the emitter: the `str_data` selects and spill, and `TLen`.
- Producers per § 5.5 (`str_runtime.sysl:270`, `:312`, `:341`, `:390`; `writer_runtime.sysl:147`).
- Slicing: short → short by funnel shift; long → long view.
- `sysl.str.cmp`/equality, `match` on a string literal, `print`/writer output.
- `sharing.sysl` skips short strings.
- Static images: a built module `val` may be short.
- Tests (`ssx_b`):
  - **operation** × **length**: len, index, slice (short→short, long→≤15 view), boundary-split
    traps, `==`/`<` against long and literal, `match`, concat (short+short→short, →16), interpolation,
    `to_string` of `i64::MIN`/0/15-digit/16-digit, `string(c)` for 1–4-byte characters, Map key,
    hash;
  - **position** × short: local `val`, reassigned `var`, struct field, `Buf` element after grow,
    tuple, `Option[string]`, enum payload, closure capture (by value and borrowed: owner-nulling is a
    no-op), `&sync` capture, module `var`;
  - **ownership**: the leak sweep is zero, and a long parent's count is not touched by a short slice
    of a short;
  - **async**: a short string live across `await`;
  - **ASan** on the whole family.
  - Re-run every `tests_*str*`/`tests_*text*` family under `SYSL_SHORT_STRINGS=1`.

**SS1-3 — `.bytes` as a frame temporary (needs SS1-2).**
- `TBytes` per § 5.4: the escape pseudo-root, the `sysl.str.unshort` promotion, the
  `--explain-escapes` wording, and the `@no_alloc` refusal with a new diagnostic in the house style.
- Tests (`ssx_c`): `.bytes` lent to a call, iterated, indexed, kept in a local past its scope,
  returned, stored in a heap struct, kept by a `@keeps` C pointer; `s` reassigned after `.bytes`;
  `.chars` and `char_indices` in loops and returned; `@no_alloc` with a literal (accepted), a slice
  of a literal (accepted), and a parameter (refused, diagnostic pinned); a pinned `--explain-escapes`
  line.

**SS1-4 — the C boundary (needs SS1-2; parallel with SS1-3).**
- `cabi` normalises a short string passed by value to an `extern` (frame slot), and with `@keeps`
  builds a heap copy. Returned strings are asserted long. `cstring`, `c"…"`, the `va_` forms and
  `c const` strings (literals) need no change.
- Tests (`ssx_d`): an `extern` in a library `.c` that takes a three-word struct and checks bytes and
  length for each length axis, on aarch64 and on a 32-bit target (AAPCS `CsSplit`). `@keeps` holding
  a string past the call, then reading it. `@export` with a `string` parameter stays refused (pin the
  diagnostic). A `build-c` archive's header is byte-identical before and after.

**SS1-5 — library and org audit (needs SS1-3, SS1-4).**
- `--std` under `SYSL_SHORT_STRINGS=1` with `--filter ssx` plus the text families.
- Grep `library/` for `ptr_cast`/raw reads of a `string`'s words and for `.bytes` views that escape.
  The second list comes from `--explain-escapes` over `library/`. Each such view becomes a borrow
  where it can, or is noted as a one-copy promotion.
- Run the org census with the flag on a scratch compiler, so packages that pass strings by value to
  C are found before the flip.
- Tests: whatever the audit changes gets its own `ssx_e` test.

**SS1-6 — the default flips; docs; measurement (needs SS1-5).**
- Make the short form the default. `SYSL_SHORT_STRINGS=0` stays as an off switch, and gets a row in
  the CLAUDE.md environment table.
- Pages:
  - `strings.md`: the representation table gets the second form; § Immortal bytes; § Relationship to
    slices (row "representations"); § allocator-free subset (the `.bytes` rule); § C interop
    (by-value `extern` and `@keeps`);
  - `memory.md` § promotion (the new `--explain-escapes` line);
  - `ffi.md` § What crosses the boundary.
- Release notes: the four behaviour changes of § 5.6.
- Measure AWFY Json and the whole AWFY set through `marshal run --exclusive` per the bench rules,
  against 0.12.x, with `SYSL_COUNT_BYTES` and the malloc histogram, and record the result in the
  bench repo. Measure `.text` of one thumb example.

**SS1-7 — an interpolation's buffer starts in the frame (independent; after SS1-2 for the full
gain).**
- `sysl.w.buf` starts with a frame-local scratch of 64 bytes and moves to the heap only past it.
  `finish` then builds a short string, or one `StrBuf`, from the scratch.
- Tests (`ssx_g`): interpolations of 0, 15, 16, 64 and 65 bytes; nested interpolation; an
  interpolation inside a loop; `str_builder` is unaffected; the leak sweep.

## 7. For the user

**One question.** Under `@no_alloc`, `.bytes` of a string handed in from outside may only be lent,
iterated or indexed. Returning it or storing it past the frame is refused unless the string is
provably long (a literal, a slice of one, a `str_view`). This is a small loss of expressiveness:
today the view shares the owner. Removing it would mean giving up the short form for strings that
reach `@no_alloc` code, which cannot be known where the string is made. The recommendation is to
accept the refusal: SS1-5's census counts the uses, and the expected count is zero. The diagnostic
would read: *"'s.bytes' outlives this frame, but 's' may hold its bytes in the string itself (15
bytes or fewer), so the view needs a copy, and this module declared '@no_alloc' — return the string
and take '.bytes' where it is used"*.
