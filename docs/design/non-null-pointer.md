# A non-null raw pointer (M4b)

A proposal for the user. Nothing here is built.

## Where M4 left it

D-M4 (user, 2026-11-18) gave the null niche to `&T` and `&sync T` only. Probed on 0.12.0:

| | size |
|---|---|
| `*int` | 8 |
| `Option[&Node]` | 8 (`None` is null) |
| `Option[*int]` | 16 (tag beside the address; `Some(null)` is not `None`) |

An `extern` naming `Option[*u8]` is refused:

```error
the 'extern' 'strchr' returns Option[*byte], which C has no spelling for — an 'Option' of a raw
pointer is a tag beside the address, so 'Some(null)' stays apart from 'None'. A pointer C may make
null is a plain '*byte', compared with 'null'
```

So a binding can't say "this C pointer is never null". It also can't say "this one may be null, and
the caller must match on it". Each `*T` is maybe-null, and nothing reminds the reader to check it.

## The proposal

A third kind of raw pointer that is never null. It has every operation a `*T` has, and it gets the null
niche: an `Option` of it is one word, with `None` as null. That `Option` is C's nullable `T *`, so
the extern boundary gets back the matched style that ffi.md dropped in M4.

## Spelling

| | written | for | against |
|---|---|---|---|
| **A** | `*nonnull T` | Same place as `*const T` (a property of the pointer, after the `*`). A soft word, like `sync`, `volatile` and `const`. Deref, `p.f`, `p[i]` all work as on `*T` | Long. Has to fix an order with `const` |
| B | `*!T` | Terse | `!` reads as "not" or "never". A sigil a reader has to look up. `*const !T` reads badly |
| C | `NonNull[T]` library struct (Rust) | Opens a general `@niche` on a one-field struct, which would also give `NonZero[u32]` | Rust's ergonomics: `p.ptr.f`, `*p.ptr`, no `p[i]` unless auto-deref is added. Needs the niche mechanism first |
| D | Flip the default (Zig): `*T` is non-null, a nullable one is `Option[*T]` | The cleanest end state | Breaks every `null` in the org (656 comparisons, 655 pointer fields, 652 extern results). Reverses D-M4's "a nullable C pointer is `*T` compared with null" |

**Recommendation: A, `*nonnull T`.** It is the existing `*const T` pattern applied a second time, so
there's nothing new to learn and nothing breaks. With both words it is `*const nonnull T`: access first,
then the pointer's value, in one fixed order (the other order is refused, naming this one). Function
pointers take it too: `*nonnull extern(int) -> int` is a callback C must be given.

In the compiler it is a third field on `Type.Ptr(inner, read_only, non_null)`. That touches about 430
`Ptr(` patterns in about 110 files (a rough count that includes IR names), most of them mechanical.

## Making one

| written | does | tier |
|---|---|---|
| `p.nonnull()` on a `*T` | answers `Option[*nonnull T]`. It costs nothing, since the bits are the same | safe |
| `p.nonnull().unwrap()` or `.expect("…")` | the checked constructor: traps on null | safe |
| `nonnull_unchecked(p)` from `sysl.raw` | no check. A null here is read as `None` by any `Option` holding it, and a deref faults | raw, imported like `ptr_cast` |
| `&place` in a `*nonnull T` position | an address of a place is never null | safe |
| `r.into_raw()` on a `&T` | answers `*nonnull T` (widening: it still goes anywhere a `*T` did) | as today |
| `null` in a `*nonnull T` position | refused at compile time, naming `Option[*nonnull T]` and `None` | |

I don't propose a separate checked function, `NonNull::new` style. `.nonnull()` plus the `Option`
methods already covers it with one name, and `?` works on it.

**Conversions.** `*nonnull T` widens to `*T` implicitly, the way `&place` already reaches a `*const T`.
The other direction is only ever spelled out (`.nonnull()`). `*nonnull T` to `*const nonnull T` is
implicit, as `*T` to `*const T` is.

**No zero value.** A `*nonnull T` has none, so a struct with such a field has no zeroed default, can't
be zero-filled, and stays out of `Plain` (D-M1 already shuts raw pointers out).
Slot storage holding one is ordinary slot storage. This is the one place a binding that swaps a
`*T` field for `*nonnull T` may feel friction: the field must be given at construction.

## The extern / `@export` ABI

It is a plain pointer in every position, and the header spells it `T *`. `Option[*nonnull T]` is the
same one word, with `NULL` for `None`.

| position | `*nonnull T` | `Option[*nonnull T]` |
|---|---|---|
| extern parameter | sysl proved it; C trusts it | `None` passes `NULL` |
| extern result | **checked at the call**: a `NULL` traps, naming the extern | matched by the caller |
| `@export` parameter | **checked at entry**: a C caller's `NULL` traps, naming the export | matched by the body |
| `@export` result | sysl proved it | `None` returns `NULL` |

The two checks sit where C hands sysl a promise sysl can't verify. The cost is one compare per call
into C, which is small next to the call. Without the check, a wrong binding gives a silent
`None`-from-`Some`. `@unchecked` on the declaration drops it. The emitted LLVM carries `nonnull` on
the parameters and results either way, and `!nonnull` on loads of such fields, so the optimiser
gains too.

**`Option[*nonnull T]` in an extern signature does not relax D-M4.** D-M4 refuses `Option[*T]`
because its tag makes it the wrong C type. It stays refused. `Option[*nonnull T]` has no tag, so
nothing about it was ever wrong for C. The refusal's last sentence changes to also offer
"or an `Option[*nonnull byte]`, matched". The `strchr` example on ffi.md gets its `match` back:

```sysl
extern strchr(s: *nonnull u8, c: i32) -> Option[*nonnull u8]

strchr(text.ptr, i32(c)) match
    Some(at) -> print(at - text.ptr)
    None -> print("absent")
```

Optionally, the header could spell these with clang's `_Nonnull` / `_Nullable` under
`__has_feature(nullability)`. That's a nice extra, not needed, and left out of the first cut.

## Raw-tier interactions

- **`ptr_cast(p)`** from a `*nonnull A` to a `*nonnull B` stays non-null, because re-typing can't make
  it null. A plain `*B` position takes it by widening.
- **`ptr_cast(n)`** from an integer to `*nonnull T` is accepted for a literal that isn't zero
  (checked at compile time, which covers MMIO bases like `ptr_cast(0x4000_C000)`). Otherwise it is
  refused: go through `*T` and `.nonnull()`.
- **`&p[n]`** from a `*nonnull T` answers `*nonnull T`. Offsetting is already unchecked, and LLVM's
  inbounds GEP from a non-null base is non-null. `p - q` is an `isize`, as today. `p[i]` and
  `p[0..<n]` are unchanged.
- **`usize(p)`** is unchanged: a number, never zero. It is not a niche of its own.
- **`p == null`** on a `*nonnull T` is refused as always false, pointing at `Option` for a pointer
  that may be absent.
- **`volatile`, `@keeps`, `@answers`, `into_raw`/`from_raw`** take a `*nonnull T` wherever they take
  a `*T`. `from_raw` accepts both.
- **Generics**: `T = *nonnull X` gets the niche in `Option[T]` the same way `&X` does today, through
  `null_niche` in `layout.sysl` (one more arm).

## What binding authors gain

These counts are from the 116 repos under `~/dev/sysl-lang/*/sh` that have a `.git` directory,
leaving out worktrees and `sysl` itself:

| pattern | count |
|---|---|
| `== null` / `!= null` comparisons | 656, in 131 files across 37 repos |
| `== null` followed within 2 lines by `None`, `Err(`, `panic` or a trap | 233 |
| `extern`s returning a `*T` | 652, in 50 repos (of 4881 externs in 65 repos) |
| `extern`s taking a `*T` | 3488 |
| struct fields of type `*T` (mostly C handles) | 655 |
| `Option[*…]` outside externs (each 16 bytes today) | 25 |

What a binding gains:

1. **About 230 hand-written "null → `None`/`Err`" sites** (sdl3, libgit2, raylib, glfw, mlt…) become
   an extern that answers `Option[*nonnull T]`, matched directly, or `.nonnull()?`.
2. **The 652 pointer-returning externs can each say which kind they are.** Never-null (`*nonnull T`,
   checked once at the call) or maybe-null (`Option[...]`, which the type forces the caller to handle).
   Today the reader has to go to the C docs.
3. **Handle fields** (`handle: *sqlite3` and the like, ~655) can be `*nonnull`, so the drop and every
   method stop guarding against null.
4. **25 in-sysl `Option[*T]`s** (benchtop, syslui, slab, sdl3, fatfs) halve to one word where `Some(null)`
   was never meant.

None of this is forced. `*T` keeps its meaning, so adoption is a per-repo sweep that can be done one
binding at a time, and nothing in the org breaks.

## Decision table

| option | cost | what it buys |
|---|---|---|
| **A. `*nonnull T` (recommended)** | One `Ptr` field (~430 mechanical sites). Parser soft word. `null_niche` arm. Two boundary checks. ffi.md + memory.md sections. Optional org sweep | Everything above, and nothing breaks |
| B. `*!T` | Same as A | Terser, harder to read |
| C. `NonNull[T]` struct + `@niche` | A general niche attribute first (layout, `Option` lowering, const-eval of the forbidden value), then the type. Deref needs `.ptr` or auto-deref | Opens `NonZero[uN]` too, at Rust's ergonomics |
| D. Flip `*T` to non-null | Breaks every null use in the org (≈2000 sites). Reverses D-M4 | The cleanest end state |
| E. Nothing | none | Bindings keep `== null`, `Option[*T]` stays two words |

Sub-decisions under A, each with my recommendation:

| question | recommend | alternative |
|---|---|---|
| order with `const` | `*const nonnull T` | `*nonnull const T`, or either order |
| extern result / export param check | check, `@unchecked` drops it | trust (UB on a wrong binding) |
| `_Nonnull`/`_Nullable` in the header | later | in the first cut |
| non-zero integer literal through `ptr_cast` | allowed | always via `.nonnull()` |
