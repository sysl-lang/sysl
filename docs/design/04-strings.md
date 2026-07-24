# Strings

**Status:** representation and semantics decided and implemented; the API surface is sketched,
and most of the part that makes new bytes is not built. This rests on the memory model (`03`) — a
`string` is an immutable, validated `[]u8` and inherits its ownership rules from slices, so read
that one first, and `07` for the view machinery it shares.

What exists: the three-word representation, literals, `s.len`, `s[i]`, `s[a..b]` with both the
bounds and the boundary checked, `s.bytes`, comparison by bytes, string literals as `match`
patterns, and **concatenation** — `a + b` and `s += t`, which allocate a fresh buffer. What does
not: the rest of the operations that produce new bytes — `from_utf8`, `copy()`, `str.builder`,
`cstring`, `string(c)` — since each needs either the raw-bytes surface or methods. A string is
therefore no longer always traceable to a literal; a program can build one by joining.

## The decision in one paragraph

A `string` is a **three-word owning view of validated UTF-8 bytes** — the same shape every
slice has: an owner reference for ARC, an interior pointer, and a byte length. Substrings
share the bytes and cost a retain. Literals are immortal, so allocator-free code can hold and
slice them. Every `string` is well-formed UTF-8, guaranteed at construction. The element
granularity is the byte and the Unicode scalar value; grapheme clusters are a library. Nothing
about the type is NUL-terminated.

## Representation

```
string = { owner: *StrBuf, ptr: *u8, len: usize }        // 24 bytes on a 64-bit target
```

| Field | Meaning |
|---|---|
| `owner` | the refcounted buffer keeping the bytes alive, or null for immortal bytes |
| `ptr` | the first byte of *this* string — an interior pointer into the owner's bytes |
| `len` | length in **bytes**, not characters |

`ptr` is separate from `owner` because a substring must name a range inside its parent's
buffer while still keeping that parent alive. Two words cannot do both jobs: with only
`{ptr, len}`, release has no way back to the buffer header, so either substrings copy or
lifetime goes unchecked. The third word buys O(1) substring sharing — the property that makes
a string library worth using — for 8 bytes.

`StrBuf` is an ordinary ARC heap object: a header — refcount plus the deallocation hook every
ARC object carries (`03`) — followed by the bytes. Nothing in the layout is special-cased; a
`string` is a normal ARC reference with a view attached, which is precisely what a slice is.

### Why not the alternatives

**Go's two words** (`{ptr, len}`) work because a garbage collector owns the bytes and finds
the object from an interior pointer. sysl has no GC, so those two words leave lifetime
unanswered — recovering the answer costs either a borrow checker (which the language exists to
avoid) or a copying substring.

**Swift's packed 16 bytes** hide three representations — small-string-inline, native heap, and
bridged — behind one discriminator. That is a lot of machinery to make invisible, it requires
an allocator unconditionally, and its grapheme-cluster element type needs Unicode break tables
in the runtime. None of that survives a kernel. sysl takes Swift's *guarantees* and Go's
*layout discipline*, not Swift's implementation.

**Rust's split** (`&str` view versus owning `String`) is the honest two-word answer, and the
price is that every signature and every programmer must choose between two types. One type at
three words is the trade this language prefers.

## Ownership and lifetime

A `string` is an ARC value: copying one retains, dropping one releases, and the bytes are
freed when the last reference goes. Slicing produces a new `string` that shares the buffer and
retains it, exactly like Go's `s[i:j]` — O(1), no copy, no allocation.

This is the same trade Go makes, with the same hazard: **a small substring keeps its whole
parent buffer alive.** Swift addresses that with a distinct `Substring` type you must
explicitly copy out of. sysl does not add a type for it; the operation that copies out is
named (`s.copy()`), and the hazard is documented rather than encoded.

### Immortal bytes

A string literal is bytes in read-only data with **no owner at all** — the owner word is null,
and retain and release both test for that and do nothing. The practical consequences:

- A literal costs no allocation and no refcount traffic. It is a *constant*, so it needs no
  instruction to build either.
- Allocator-free code can hold, pass, compare, and slice string literals — panic messages,
  device node names, format fragments — all of which a kernel needs constantly.
- Any string derived from a literal by slicing is also immortal, because it shares the owner.

A sentinel refcount in a header would say the same thing, and was the first plan here. The null
owner is better because it is not string-specific: it is already how a slice of static storage
or of a `*T` region says "nothing to keep alive," so immortality needs no mechanism of its own.

## Validity

**Every `string` is well-formed UTF-8.** This follows `char`, which already enforces that its
value is a Unicode scalar value and traps on `char(u)` when it is not — a `string` that could
hold arbitrary bytes would make decoding partial again and hand the decoder a way to produce a
`char` that cannot exist.

This is Swift's guarantee, not Go's. Go strings are arbitrary bytes that are UTF-8 by
convention, so its decoder must substitute U+FFFD and advance one byte on malformed input.
Here there is no repair path in the core language, because there is nothing to repair.

Construction:

| From | Spelling | Behaviour |
|---|---|---|
| literal | `"héllo"` | validated at compile time |
| bytes | `string.from_utf8(b: []u8) -> Result[string, Utf8Error]` | validates; the error names the offending byte offset |
| bytes, trusted | `string.from_utf8_unchecked(b: []u8)` | **unsafe** — available only where raw pointers are, so it stays greppable |
| a `char` | `string(c)` | encodes one scalar value |

`from_utf8_unchecked` is in the `*T` category deliberately: breaking the UTF-8 invariant
breaks `char`'s invariant downstream, so it belongs with the other primitive that can break
the safe subset rather than alongside the ordinary conversions.

## Granularity: bytes and scalar values, not graphemes

`string` is indexed and measured in **bytes**, and decoded into **`char`** (Unicode scalar
values). Grapheme clusters — Swift's `Character` — are a library built over the scalar view.

The reason is placement of Unicode data. Grapheme breaking needs tables that must not be in a
kernel, and making the default element type the one that requires them would put `s.count` at
O(n) and force an opaque index type on every program that just wants a byte offset. Go's
choice is right for a systems language; Swift's is right for an application language.

| Operation | Spelling | Cost |
|---|---|---|
| byte length | `s.len` | O(1) |
| byte at an index | `s[i] -> u8` | O(1), bounds-checked |
| substring | `s[a..b] -> string` | O(1), shares; bounds-checked **and** boundary-checked |
| bytes | `s.bytes -> []u8` | O(1) view |
| scalar values | `s.chars` | O(1) per step, total — no replacement characters |
| copy out | `s.copy() -> string` | O(n), allocates; releases the parent |
| concatenation | `a + b` | O(n), allocates |
| repeated append | `str.builder` | amortized |

**Slicing is boundary-checked.** `s[a..b]` must land on scalar-value boundaries; landing
mid-codepoint traps, in the same runtime-safety category as a bounds check and `char(u)`. Go
permits the mid-codepoint slice and lets you build an invalid string with it — that option is
closed here by the validity guarantee.

### Comparison is by bytes

`==`, `<`, and friends compare the byte sequences. For well-formed UTF-8 that is also
codepoint order, so the ordering is the useful one.

**Normalization is not applied.** Swift's `==` compares by canonical equivalence, so a
composed `"é"` equals a decomposed one — correct for user-facing text, surprising and
expensive in systems code, where a string is usually a path, a device name, or a protocol
token that must compare as the bytes it is. Normalization and collation are library
operations, applied where they are wanted and visible when they cost something.

## Concatenation

`a + b` joins two strings, and `s += t` appends onto a slot. Both allocate: the result is a
fresh `StrBuf` — an ordinary ARC heap object (`03`), a refcount and a deallocation hook followed
by the two halves' bytes laid end to end — so it owns a count of its own and frees itself like
any other reference. UTF-8 is closed under concatenation, so the validity invariant is preserved
for free; nothing is re-checked. The operands are copied out, not aliased, so an operand that was
itself a substring keeps no hold on the result.

`+` is **strict**: it joins a `string` to a `string` and nothing else. `"n=" + 5` is a type
error, not a silent `str(5)` — the same no-implicit-coercion stance the numeric operators take,
where a mixed-width sum is an error asking for a conversion. The way to build a string out of
values of other types is interpolation, where the conversion is written where it happens. Making
`+` polymorphic over "anything with a string form" would reintroduce exactly the invisible
conversion the rest of the language refuses.

`+` is the only arithmetic operator a string defines; `-`, `*`, and the rest are rejected the way
they are for any type that does not define them.

## Literals

Double-quoted, UTF-8, the escape table in `01`. A literal may not span a line break, and a
comment marker inside one is ordinary text. Interpolation, concatenation of adjacent literals,
and raw/multi-line forms are not yet specified.

## C interop

A `string` has no terminator, so passing one to C is an explicit, allocating conversion:

```
cstring(s) -> CString      // allocates a NUL-terminated copy; the caller owns it
```

This is Go's `C.CString`, and it is explicit for the same reason: a length-carrying string can
contain a NUL as an ordinary byte, so the conversion can fail or truncate and must be visible.

**One optimization is worth building in.** The compiler emits a NUL byte after every string
literal in read-only data. It costs one byte, it is not counted in `len`, and it means passing
a *literal* to a C function needs no allocation and no copy — which is what kernel and driver
code actually does with strings. Swift does the same thing internally.

A NUL *inside* a literal stays an ordinary byte — that is what carrying a length means, and
`"a\0b"` has three bytes and prints as three. What such a literal loses is only the free ride to
C, so that is where the diagnostic belongs: at the conversion, which can see that the bytes it
was asked to hand over would be cut short. Passing one to C is an error, not a truncation.

## The allocator-free subset

Following `03`: the *type* is not gated, the *allocating operations* are.

Legal with `no alloc`: holding, passing, comparing, indexing, slicing, iterating, and
releasing any string — including a heap-backed one handed in from outside, which frees itself
through its own deallocation hook.

Requires `alloc`: `from_utf8`, `copy()`, concatenation, `str.builder`, `cstring` — every
operation that produces new bytes.

A module that only ever uses literals sees only immortal strings, so its retain and release
compile away entirely; one that is handed a heap-backed string pays ordinary refcount traffic
and still links no allocator.

## Relationship to slices

Both questions this doc originally left open are now decided in `03`, and they resolve the
same way:

- **`[]T` is three words too** — `{owner, ptr, len}`, retaining its buffer, with a null owner
  for views of static or otherwise-outliving storage. A `string` is therefore exactly an
  **immutable, validated `[]u8`**: the same representation, the same retain-on-slice, one
  implementation underneath. Everything this doc says about sharing and immortality is the
  general slice rule, not a string special case.
- **Ownership crosses a `no alloc` boundary freely.** Every ARC object carries a pointer to
  the function that frees it, so allocator-free code can hold and release a heap-backed string
  it was handed without linking an allocator. `no alloc` gates *making* strings, not *having*
  them.

The one string-specific addition is the validity invariant: a `[]u8` may hold any bytes, and a
`string` is the subset that is well-formed UTF-8, which is why converting between them is
checked in one direction and free in the other.

## Summary against the languages this came from

| | Go | Swift | Rust | sysl |
|---|---|---|---|---|
| Size | 16 B | 16 B (packed) | 24 B / 16 B | 24 B |
| Representations | 1 | 3 | 2 types | 1 |
| Owns its bytes | GC does | yes (ARC + COW) | `String` yes, `&str` no | yes (ARC) |
| O(1) substring | yes | yes (`Substring`) | yes (`&str`) | yes |
| Valid UTF-8 guaranteed | no | yes | yes | yes |
| Element | byte / `rune` | grapheme cluster | byte / `char` | byte / `char` |
| Comparison | bytes | canonical equivalence | bytes | bytes |
| NUL-terminated | no | privately | no | no |
| Usable without an allocator | no | no | `&str` yes | yes (literals free; heap-backed ones held and released) |
