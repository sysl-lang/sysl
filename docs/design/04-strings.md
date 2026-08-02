# Strings

**Status:** decided and implemented, including every operation that makes new bytes. This rests on
the memory model (`03`) — a `string` is an immutable, validated `[]u8` and inherits its ownership
rules from slices, so read that one first, and `07` for the view machinery it shares.

The three-word representation, literals, `s.len`, `s[i]`, `s[a..b]` with both the bounds and the
boundary checked, `s.bytes`, comparison by bytes, string literals as `match` patterns,
**concatenation** — `a + b` and `s += t`, which allocate a fresh buffer — **`str(x)`**, which
renders a primitive value into one, **interpolation** — `s"…$x…"`, `raw"…"`, and `f"…${x}%d…"`,
which desugar to concatenation, `str`, and printf-style formatting — **`from_utf8`** with the
`from_utf8_unchecked` primitive under it, which is the one route from bytes a program computed back
to text, and **`s.chars`**, the cursor over the scalar values those bytes encode (`14 §7`).

The four that make new bytes are built too: **`s.copy()`**, which stops a substring holding its
parent alive, **`string(c)`**, which encodes one scalar value, **`StrBuilder`**, which gathers text
without rebuilding what it already has, and **`cstring(s)`**, which copies a string into the shape C
reads. Three of them are the library's rather than the compiler's, for the reason `from_utf8` is:
only the last line of each needs to be underneath the language.

A string is therefore no longer always traceable to a literal; a program can build one by joining,
by rendering, by gathering, or by validating bytes, and can walk what it built at either
granularity.

**Where these live: everything the compiler writes for itself is `sysl` and everything a program
writes by name is `sysl.text`.** A literal, `+`, `str(x)`, an interpolation and `s.chars` are all
desugarings, so they cost no import — even `s.chars`, whose cursor is `sysl.text`'s, because the
compiler names `chars_of` by key rather than by resolving the word (`13 §8`). Named at the call
site, and so imported: `from_utf8`, `from_cstring`, `char_from_u32`, `str_builder`, `cstring`, and
the types `Utf8Error`, `Chars`, `StrBuilder`, `CString` and the `Ascii` trait. The split is the one
`13 § Open h` describes — what a program cannot avoid needing is free, and what it has to ask for it
asks for.

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
| bytes | `from_utf8(b: []const u8) -> Result[string, Utf8Error]` | validates; the error names the offending byte offset |
| bytes, trusted | `from_utf8_unchecked(b: []const u8) -> string` | **unsafe** — the long name is the point: it stays greppable |
| a `char` | `string(c)` | encodes one scalar value |

`from_utf8_unchecked` is in the `*T` category deliberately: breaking the UTF-8 invariant
breaks `char`'s invariant downstream, so it belongs with the other primitive that can break
the safe subset rather than alongside the ordinary conversions.

**Both are free functions, and the first spelling of them here was `string.from_utf8(…)` — an
associated function on a built-in, which `08` says cannot be written**: only a struct or an enum has
a name in call position. The doc had been written against a namespace mechanism the language does
not have, and the fix is to stop pretending it does. Nothing is lost by the shorter spelling and
nothing is decided by it either: if built-ins ever carry associated functions, these two become
`string.from_utf8` with no change to what they do.

**The division of labour is worth stating, because it is the whole reason `from_utf8` is not a
built-in.** The compiler supplies exactly one primitive — `from_utf8_unchecked`, which is a `[]u8`
taken as a `string` with nothing looked at — and the validator on top of it is ordinary sysl in the
library. That is possible for the same reason `Buf[T]` is: nothing in a byte-by-byte scan needs
anything the language does not already offer. What no sysl body can do is the last line, because
every safe route to a `string` already carries the guarantee.

**The validator is Unicode's well-formedness table (Table 3-7), not a decode-then-range-check**, and
the difference is not stylistic. In the table, the *lead* byte fixes the legal range of the byte
after it — `E0` demands `A0..BF`, `ED` only `80..9F`, `F0` demands `90..BF`, `F4` only `80..8F` —
so an overlong encoding, a surrogate, and a value past `10FFFF` are all rejected at the second byte,
by the same test, before any codepoint is assembled. Written the other way each needs its own check
and each is its own chance to be forgotten.

`Utf8Error` carries the offset promised above and one thing more: whether the input merely **ended**
in the middle of a sequence. That distinction is the only one a caller can act on differently — more
bytes would fix an unfinished sequence and could never fix a wrong one — which is why it is a field
rather than a taxonomy of fault names nobody would match on.

**The bytes are copied rather than viewed.** A `string` could in principle share a `[]u8`'s owner,
which would make the conversion O(1) — but a slice is writable and a string is not, so a later write
through the slice would change a value that had already been checked. Copying is what makes
validation mean anything afterwards, and it is why the entry requires `alloc`.

**Two guide programs paid for the absence in different currencies.** `guide/json` paid in
*workarounds*: unescaping copied the source between the escapes and reached text for a `\uXXXX` only
through `str(char(n))`, a rendering path standing in for a constructor. `guide/shapes` paid in
*design* — the allocation-free way to turn a value into text is to render into a `*Writer`, which is
what `Display` is, and a program that did so could not get a `string` back out of the sink, so it
concatenated instead. The second was the more serious report: the first says the conversion is
inconvenient to live without, the second says its absence pushes a program away from the rendering
path this document and `14 §2` are built around.

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
| bytes | `s.bytes -> []const u8` | O(1) view |
| scalar values | `s.chars` | O(1) per step, total — no replacement characters |
| copy out | `s.copy() -> string` | O(n), allocates; releases the parent |
| concatenation | `a + b` | O(n), allocates |
| repeated append | `str_builder()` | amortized |

**`s.chars` is what the iteration protocol was decided around** (`14 §7`), and it is the one row of
this table that could not be a value or a view. Every row above yields a value; this one yields a
*sequence*, and a string cannot hand one out the way a container hands out a view of its storage,
because the decoding is what makes the scalar values. So there has to be something that carries a
position and answers "the next one" — and that is the whole of the protocol: `s.chars` is a cursor
over the bytes, implementing `Iterate[char]`, and `for c in s.chars` walks it.

The cursor validates nothing. A `string` is well-formed UTF-8 by construction, whether it arrived as
a literal or through `from_utf8`, so the decoding reads the length off the lead byte and takes the
continuation bytes as given — which is why the cost in the table is what it is, and why there are no
replacement characters to hand back.

**A cursor built over bytes that are not a string is unsafe in nothing.** `Chars` is an ordinary
struct, so a program may make one over any `[]u8` and skip the validation `s.chars` never had to
do. What it gets is the ordinary garbage-in answer — a lead byte that is really a continuation byte
decodes as itself — and never a read past the end: every byte the decoder takes goes through the
slice's own bounds check, so a truncated sequence traps like any other overrun. That is the same
runtime-safety category `char(u)` and a mid-codepoint slice are in.

**Slicing is boundary-checked.** `s[a..b]` must land on scalar-value boundaries; landing
mid-codepoint traps, in the same runtime-safety category as a bounds check and `char(u)`. Go
permits the mid-codepoint slice and lets you build an invalid string with it — that option is
closed here by the validity guarantee. This holds for a string that arrived through `from_utf8`
exactly as it does for a literal, which is the point of validating at the door: nothing downstream
may undo it.

### Classification is ASCII, and is named for it

Asking what kind of thing a byte or a character is — a digit, a letter, whitespace — is the `Ascii`
trait in `sysl.text`. **It answers over the ASCII range and nothing else, and the name is the
promise:** a value at or above 128 answers `false` to every question rather than being guessed at,
and `is_ascii` is how a caller distinguishes "not a letter" from "not a letter I can see". Real
Unicode classification needs property tables, which is the same argument that puts grapheme
clusters in a library above this one: they must not be in a kernel.

The older sysl called the equivalent module `unicode` and classified nothing above 127, so every
caller read a promise the code did not keep. That is the mistake this name exists to avoid.

**It is a trait rather than two sets of functions**, for exactly the reason `sysl.math.Float` is
one: sysl has no overloading, so free functions would need `is_digit` for a `u8` and some suffixed
twin for a `char`, and every caller would have to track which it was holding. `b.is_digit()` is the
same three words either way. The pair is *closed*, which is what makes an `impl` the right mechanism
here and the wrong one for the integers — `sysl.math.Signed` cannot be written this way because
`iN` is an open family and a finite set of blocks would leave widths out, while a byte and a
character are the only two types this question is ever asked of.

Each implementation supplies three things — `code`, `to_upper`, `to_lower` — and every classifier is
a default written once over `code()`. The two conversions stay per type only because each must
answer in its own type; the arithmetic is the same, and both are **total**, leaving anything that is
not a letter of the other case exactly as they found it. That is what makes mapping one over
arbitrary text safe rather than merely defined.

`digit_value(base)` is here rather than in `sysl.strconv` because it is a question about a
character, and because parsing wants exactly this and nothing else from a digit — the two would
otherwise be the same test written twice. A base outside 2..36 answers `None` rather than trapping,
so the caller that passed it is the one that gets to report it.

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

## Gathering text

Concatenation allocates a fresh buffer each time, so building a string a piece at a time by `+=`
copies everything it has so far on every step. `StrBuilder` is the form for that shape: it keeps one
growable buffer, appends into it, and hands back a string when the gathering is done.

```
import sysl.text.str_builder

var b = str_builder()

b.push("hello, ")
b.push_char(',')
b.push(str(n))

var line = b.finish()
```

**The two ways in are the two that carry the guarantee.** A `push` takes a `string` and a
`push_char` takes a `char`, and UTF-8 is closed under appending either — so `finish` hands back a
plain `string` rather than something a caller has to validate. That is why a builder is **not** a
`Writer`: a public `write` taking a `[]u8` would be `from_utf8_unchecked` with a longer name and
none of its greppability. A value of some other type is pushed as `push(str(x))`, which spends the
one allocation `str` was always going to spend, and still saves the quadratic copying that made a
builder worth having.

**`finish` copies rather than lending.** What comes out is an independent string, so a builder may
go on being appended to and the string already taken out of it does not change. The alternative —
handing over the buffer's storage and leaving the builder empty — would save a copy at the cost of a
form whose meaning depends on how many times it has been called.

It is spelled `str_builder()` rather than `str.builder` for the reason `from_utf8` is not
`string.from_utf8`: only a `struct` or an `enum` has a name in call position (`08`), and a built-in
is neither. Nothing is decided by the spelling; if built-ins ever carry associated functions this
becomes `str.builder` with no change to what it does.

## Rendering a value

`str(x)` is the written conversion from a value to its string form — the counterpart to the strict
`+`, and the thing interpolation is built on. It renders each primitive type:

| Type | Result |
|---|---|
| integer | its decimal digits, with a sign for a negative signed value |
| `bool` | `"true"` or `"false"` |
| `char` | the one scalar value's UTF-8 |
| float | the same `%g` rendering `print` gives it |
| `string` | itself, unchanged |

Every case but a `string` allocates a fresh buffer, so the result owns its bytes like any built
string; a `string` is returned as it is, since it is already one, and a `bool` renders to one of two
immortal literals and allocates nothing. An integer is rendered without the C library — the digits
are divided out into a scratch buffer, which is correct even for the most negative value because the
magnitude is taken in unsigned arithmetic. A float goes through `snprintf`, the one case that needs
libc, chosen so that `str(x)` and `print(x)` never disagree; it is `%g`, not the shortest
round-tripping form this doc otherwise aspires to, and that gap is deliberate for now.

**Any other type renders through `Display`** (`14 §6`). A struct or an enum that carries an
`impl Display` writes itself into a growable buffer, and the bytes that land there become the
string — so `str` of a user type is now an ordinary call rather than an error, and `str` of one
*without* an implementation is a diagnostic naming the `impl` to write. The primitives keep the
direct renderings above, which is `14 §8 b`'s answer rather than an omission: the two agree to the
byte, so the one that needs no sink is the one to emit. A reference, a pointer, a slice, and an
array remain errors, since none of them can carry an `impl` yet.

A value of any other type is not silently rendered across `+` or in a `print`; the conversion is
always written, at the point it happens. That is the same no-implicit-coercion stance the numeric
operators take, and it is what keeps a string's contents something a reader can see the source of.

## Printing

`print(a, b, c)` writes each value, a space between and a newline at the end. It is **not a function
built into the compiler**: it is a desugaring onto ordinary library functions, one per *kind* of
value, chosen by each argument's static type.

```
print(n, 2.5, "done")     ⟶     printi(long(n))
                                printc(' ')
                                printr(2.5)
                                printc(' ')
                                prints("done")
                                printc('\n')
```

The library declares `printi`, `printu`, `printr`, `printb`, `printc`, and `prints`, and every one
of them is sysl a program could have written. What the compiler knows is those six *names* and the
rule that widens an argument to the width its renderer takes — an integer to `long` or `ulong` by
its own signedness, a float to `real` — so that the library needs one function per kind rather than
one per width, which a language without overloading has no other way to arrange. That is the same
kind of knowledge it already has of `Option`'s variant names, and it is the whole of it: the
compiler implements no printing.

Everything reaches the outside through one sink, `putbytes`, and that is not incidental. Two
mechanisms would mean two buffers and output arriving out of order. It writes a byte at a time
because a sysl `string` may hold an interior NUL, and every shortcut through C — `puts`, `%s`, even
the length-bounded `%.*s` — stops at one. It is also the only function a freestanding target has to
replace: give it a `write` syscall and the rest of the surface is unchanged.

The separator and the terminator go out as **characters** rather than one-character strings, so that
printing a number reaches nothing that allocates — a `string` is reference-counted, and a single
`prints(" ")` would pull the whole ARC runtime into a program whose own code never asked for it.

The integer and float renderings call `snprintf`, which is formatting rather than I/O. Doing them in
sysl is a small job for the integers and a large one for the floats, so they wait until there is a
target without a C library to make it worth it. The library reaches `snprintf` and `putchar` under
link names (`12` §1), so both stay free for a program to declare itself.

**Where this goes.** `Display` has landed (`14 §6`), and the seam being a name is what let it: a
value that is not a scalar now reaches `x.display(out, fmt)` instead of a `print*` function, and
every program that printed a number kept working. What is left is `print` becoming an ordinary
variadic function over trait objects rather than a desugaring — which needs a variadic surface that
can carry them, and would remove the last of the names the compiler knows.

## Literals

Double-quoted, UTF-8, the escape table in `01`. A one-quote literal may not span a line break, and
a comment marker inside one is ordinary text. Concatenation of adjacent literals is not specified —
nothing has asked for it now that a block exists.

### Text blocks

A literal that spans lines is written `"""` … `"""`. The content begins on the line **after** the
opening delimiter, which is what gives the indentation rule an anchor — the opening delimiter's own
column then means nothing, so the form reads the same at any depth of nesting.

Three things happen to a line inside a block that do not happen inside `"…"`:

- **Its incidental indentation is dropped.** The strip is the least indented line that carries
  content, together with the line the closing delimiter sits on when it sits on one alone. So the
  closing delimiter is the control — move it left and the value keeps more, right and it keeps less
  — and there is no margin character to remember. This matters more here than in a free-form
  language: a block written inside an indented body would otherwise carry that body's indentation in
  its value, and the depth a piece of code sits at is not something its data should record.
- **Its trailing blanks are dropped.** Whitespace at the end of a line is invisible in a source file
  and would otherwise enter the value unseen. One that is *meant* is written `\u{20}`, which
  survives because escapes are read after the trimming rather than before it.
- **A `\` at the end of it joins it to the next line.** This is the form's reason for existing:
  embedded data wants no line breaks in its value at all, and a file written as hex over twenty
  lines is one string. The joining happens in the lexer, so a block is a **single constant** — where
  the same data assembled with `+` allocates and copies once per piece.

Whether the value ends with a newline needs no rule of its own. A closing delimiter alone on its
line is reached *after* the last content line's break has been taken, and one that follows content
is reached before any break at all.

A carriage return goes with the rest of a line's trailing whitespace, so a block means the same
thing in a file with either line ending. A lone `"` inside a block is ordinary text, which is the
other thing the form buys; a block cannot end with one, and `\"` writes it.

**The prefixes are orthogonal to the quote form** — `c"""`, `s"""`, `f"""` and `raw"""` all
compose, since a prefix says how a literal is *read* and the quote form says how it was *written*.
A `raw` block does no escape decoding, so nothing joins its lines.

## Interpolation

An interpolated string is a literal with a prefix — `s"…"` processes escapes, `raw"…"` leaves a
backslash as an ordinary character — and inside it `$name` or `${ expression }` splices a value in.
`$$` is one literal dollar. Each spliced value is rendered by `str`, so the same rules apply: a
primitive renders, and a type that has no string form is an error at the splice. The whole thing
desugars to the machinery already built — `s"a${e}b"` is exactly `"a" + str(e) + "b"` — so an
interpolation is not a new kind of value, just a concise way to write a concatenation. A hole holds
a full expression, which may itself interpolate, and an empty literal segment beside a hole is
dropped since it is the identity under `+`.

This follows Scala's interpolators, and deliberately not a `printf`-style format string in the
default form: the value and the text around it stay where they are read, and the conversion is `str`
applied at the splice rather than a directive parsed out of a separate string.

### Format specifiers

`f"…"` is the third prefix, and it adds one thing: a hole may be followed by a printf specifier —
`f"${x}%08.2f"`, `f"${n}%x"`, `f"${s}%-10s"` — that controls width, precision, sign, and
justification. A hole with no specifier renders through `str` exactly as in an `s"…"` string; a
specifier binds only to the `%` written immediately after a hole, so a bare `%` elsewhere in the
text — `f"${n}%d done, 100% sure"` — is ordinary text. The specifier keeps the value beside its
formatting, which is the point of putting it after the hole rather than in a separate format string.

The conversion is checked against the value's type at compile time: `%d %i %x %X %o %u` want an
integer, `%f %e %g %E %G` a float, `%s` a string. An unsigned conversion reads the value at its own
width — `%x` of an `i32 -1` is `ffffffff`, of a `u8 255` is `ff` — while `%d` keeps the value's
sign. A numeric value is formatted by handing it to the C library with the specifier translated to
its 64-bit form; a string is copied NUL-terminated so C's `%s` can apply width and precision, which
means an interior NUL ends the field, as it does for any `%s`.

`s`, `raw`, and `f` are only prefixes when written directly against the opening quote; used as
ordinary names they are unaffected, so `s + raw` and `f + 1` are ordinary expressions.

## C interop

A `string` has no terminator, so passing one to C is an explicit, allocating conversion:

```
cstring(s) -> CString      // allocates a NUL-terminated copy; the caller owns it
```

This is Go's `C.CString`, and it is explicit for the same reason: a length-carrying string can
contain a NUL as an ordinary byte, so the conversion can fail or truncate and must be visible.

**`CString` owns the copy, and that is what "the caller owns it" means here.** Go's version hands
back a raw `*C.char` and requires a matching `C.free`; sysl has no manual free to match, so the
result is a value whose bytes go when it does, like every other allocation in the language. It
offers two things and nothing else: `cs.ptr`, the `*u8` a C function takes, and `cs.len`, the byte
length *not* counting the terminator, so that it agrees with the `s.len` it came from.

The hazard the explicitness exists for survives intact and is worth stating as an equation: for
`cstring("a\0b")`, `cs.len` is 3 and C's `strlen(cs.ptr)` is 1. Both are right. Neither can be
made to be the other, which is exactly why a conversion is written rather than inferred.

The pointer is a `*T` and carries a `*T`'s rule: it is valid while the `CString` is held, and
keeping it past that is the ordinary raw-pointer mistake rather than a new one. Passing
`cstring(s).ptr` straight into a call is safe — the temporary lives for the statement — while
storing that pointer and using it later is not.

**The literal case needs no allocation at all, and it is spelled `c"…"`.** The compiler emits a NUL
byte after every string literal in read-only data — it costs one byte and is not counted in `len` —
so a literal is already sitting in memory in exactly the shape C reads. `c"%g"` is that constant's
address: a plain `*u8`, no allocation, no copy, no runtime.

```
extern printf(fmt: *u8, ...) -> int

printf(c"%d items\n", n)
```

**It is a distinct literal form rather than an inferred optimization**, and deliberately: whether an
expression is *literally a literal* is not something a reader should have to work out, and a rule
that silently allocates for `s` but not for `"…"` hides a cost the language promises to show
(`principles.md`). `c"…"` says at the call site which one this is. It is Rust's `c"…"` (stable since
1.77) and Zig's null-terminated literal, for the same reason both added it.

A `c"…"` containing an interior NUL is a **compile error**, not a truncation: the bytes after it
could never be seen by the callee, so the value would not be what was written. An ordinary `"a\0b"`
is unaffected — it has three bytes and prints as three, because carrying a length is the whole
point — and only the free ride to C is lost, which is exactly where the diagnostic belongs.

The general, non-literal direction stays the allocating `cstring(s)` above.

## The allocator-free subset

Following `03`: the *type* is not gated, the *allocating operations* are.

Legal with `no alloc`: holding, passing, comparing, indexing, slicing, iterating, and
releasing any string — including a heap-backed one handed in from outside, which frees itself
through its own deallocation hook.

Requires `alloc`: `from_utf8`, `copy()`, concatenation, `string(c)`, `str_builder()`, `cstring` —
every operation that produces new bytes.

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
