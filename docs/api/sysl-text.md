---
title: sysl.text
layout: api-module
headingShift: 0
slugStyle: github
module: sysl.text
summary: "The whole text surface: what a `string` is made of, and every operation over one."
---

Validation and the two conversions either way, the character cursors, `Ascii` for asking a byte or
a character what kind of thing it is, `Search` and the trimming that makes no new bytes, splitting
and joining, `StrBuilder` for text gathered a piece at a time, the parsers, `CString` for the
boundary with C, and the terminal width — which is a third answer to how long text is, after bytes
and after characters.

**What is here is the encoding and what is the character database is `sysl.unicode`.** This module
knows that UTF-8 says where a character begins and what `Ascii` makes of a byte; it does not know
that `ß` upper-cases to `ẞ`, and it is the other module that does.

## Index

[`char_columns`](#char_columns) [`char_from_u32`](#char_from_u32) [`char_indices`](#char_indices) [`chars_of`](#chars_of) [`cluster_columns`](#cluster_columns) [`columns`](#columns) [`contains_fold`](#contains_fold) [`cstring`](#cstring) [`ends_with_fold`](#ends_with_fold) [`eq_fold`](#eq_fold) [`fields`](#fields) [`from_cstring`](#from_cstring) [`from_utf8`](#from_utf8) [`from_utf8_lossy`](#from_utf8_lossy) [`from_utf8_unchecked`](#from_utf8_unchecked) [`grapheme_columns`](#grapheme_columns) [`is_char_boundary`](#is_char_boundary) [`join`](#join) [`parse_bool`](#parse_bool) [`parse_bool`](#parse_bool-1) [`parse_int`](#parse_int) [`parse_int`](#parse_int-1) [`parse_int_base`](#parse_int_base) [`parse_int_base`](#parse_int_base-1) [`parse_long`](#parse_long) [`parse_long`](#parse_long-1) [`parse_long_base`](#parse_long_base) [`parse_long_base`](#parse_long_base-1) [`parse_real`](#parse_real) [`parse_real`](#parse_real-1) [`parse_uint`](#parse_uint) [`parse_uint`](#parse_uint-1) [`parse_ulong`](#parse_ulong) [`parse_ulong`](#parse_ulong-1) [`parse_ulong_base`](#parse_ulong_base) [`parse_ulong_base`](#parse_ulong_base-1) [`repeat`](#repeat) [`replace_all`](#replace_all) [`split`](#split) [`starts_with_fold`](#starts_with_fold) [`str_builder`](#str_builder) [`str_builder_with_capacity`](#str_builder_with_capacity) [`str_view`](#str_view) [`to_lower`](#to_lower) [`to_upper`](#to_upper) [`CharIndices`](#charindices) [`Chars`](#chars) [`CString`](#cstring-1) [`ParseError`](#parseerror) [`StrBuilder`](#strbuilder) [`Utf8Error`](#utf8error) [`Ascii`](#ascii) [`Search`](#search) [Ascii for char](#ascii-for-char) [Ascii for u8](#ascii-for-u8) [Display for ParseError](#display-for-parseerror) [Iterate for CharIndices](#iterate-for-charindices) [Iterate for Chars](#iterate-for-chars) [Search for []const u8](#search-for-const-u8) [Search for string](#search-for-string)

## Functions

### `char_columns`

```sysl
char_columns(c: char) -> usize
```

How many terminal columns one character occupies -- **two** for the East Asian wide and fullwidth
forms, **none** for a combining mark or a format character, and one for everything else.

A control character answers zero, which is the honest answer to a question it does not really
have: a terminal does not draw `\n` in a column, it acts on it. A caller laying out a field is
therefore measuring text it has already decided is printable, and one that has not is asking
about the wrong string rather than getting a wrong number.

### `char_from_u32`

```sysl
char_from_u32(u: u32) -> Option[char]
```

The fallible half of `u32` -> `char` (`reference/types.md § char`). It is a free function because
a scalar has no member namespace to hang a `char.try` on, and it needs no unchecked primitive
underneath it: the guard it writes and the check `char(u)` already makes are the same two
comparisons on the same value, so the cast on the far side of the guard can never trip and the
optimizer folds it. Trading a primitive for a redundant compare in a cold branch is the better
side of that bargain.

### `char_indices`

```sysl
char_indices(b: []const u8) -> CharIndices
```

### `chars_of`

```sysl
chars_of(b: []const u8) -> Chars
```

### `cluster_columns`

```sysl
cluster_columns(cluster: []const u8) -> usize
```

How many terminal columns one **grapheme cluster** occupies -- the answer `grapheme_columns`
sums, and the one that is right for an emoji sequence where a per-code-point sum is not.

**A cluster is as wide as the character it starts with.** Everything after the first is there to
modify it -- a combining accent, a variation selector, a skin tone, a second regional indicator,
whatever a zero-width joiner attached -- and a terminal draws the whole cluster in the base
character's cell or pair of cells. So a family emoji is two columns rather than the eight its
four emoji and three joiners come to, and a thumb with a skin tone is two rather than four.

**Two things widen a cluster past what its first character is worth, and both say *draw this as
an emoji*.** `U+FE0F`, the variation selector, turns a text-presentation character into an emoji
one: `❤` is one column and `❤️` is two, and the difference is a code point that measures zero.
And a **regional indicator** is a letter that is never drawn alone -- a pair of them is one flag,
two columns, where the database rates each as narrow because on its own it is a boxed letter.
`U+FE0E` is the opposite request and needs no rule, since what it applies to is already narrow.

**What this does NOT do is decide anything a terminal disagrees with.** Emoji width is the one
corner of the question where terminals genuinely differ -- a keycap sequence is one column here
and two in some -- and no table settles it. This is the rule the majority implement.

### `columns`

```sysl
columns(text: []const u8) -> usize
```

How many terminal columns a run of UTF-8 occupies, which is the sum of what its characters do.

It takes bytes rather than a `string` so that text being assembled -- what a sink has gathered,
what a slice of a larger buffer holds -- can be measured without being copied into one first.
`s.bytes` is the view a caller holding a `string` passes.

### `contains_fold`

```sysl
contains_fold(s: string, needle: string) -> bool
```

Whether `needle` occurs anywhere in `s`, ignoring case.

This is the one a search box wants, and it is the one where folding rather than lowercasing shows
up most often in practice: a user typing `strasse` expects to find `Straße`.

### `cstring`

```sysl
cstring(s: string) -> CString
```

### `ends_with_fold`

```sysl
ends_with_fold(s: string, suffix: string) -> bool
```

The other end, and it is `starts_with_fold`'s claim read backwards.

### `eq_fold`

```sysl
eq_fold(a: string, b: string) -> bool
```

Whether two strings are the same text ignoring case, which is what a caller comparing a header
name, a filename, a scheme or a keyword actually wants.

**`to_lower(a) == to_lower(b)` is the obvious spelling and it is wrong**, because simple case
mapping is one character in and one out and case *folding* is not. `"STRASSE"` and `"straße"` are
the same word and lowercase to `"strasse"` and `"straße"`, which differ; folded, both are
`"strasse"`. The ligature `"ﬁ"` and the letters `"fi"` go the same way. Folding is the operation
Unicode defines for exactly this question and nothing else answers it.

**It allocates two strings, and a caller in a loop should not use it.** Each call folds both
sides; a caller comparing one needle against many haystacks folds the needle once with
`sysl.unicode.fold` and compares against `fold(h)` itself. That is the same advice `to_upper`
gives about building a string per call, and it is why this is a free function rather than an
operator: `==` on two strings is a byte comparison and stays one.

**The folded form is composed**, so two strings that fold alike compare alike with no separate
normalization pass -- `sysl.unicode.fold` says why.

**There is no locale in it.** The one common casualty is Turkish, where a dotless `ı` and an `i`
are different letters and this reports them as the same; a program that has to make that
distinction has a notion of locale that this library does not.

### `fields`

```sysl
fields(s: string) -> []string
```

The runs of non-whitespace, with the whitespace between them discarded.

This is not `split` on a space: a run of several spaces separates two fields rather than
producing empty ones between them, and leading or trailing whitespace produces nothing at all.
It is what reading a line of columns wants, and writing it as a `split` and a filter would be
slower and no clearer.

### `from_cstring`

```sysl
from_cstring(p: *u8) -> Result[string, Utf8Error]
```

A `string` copied out of the NUL-terminated bytes a C function handed back -- the other direction
of `cstring`, and the one every binding needs the moment a C library reports anything in words.

**The copy is not optional.** The bytes belong to C: a static buffer it reuses on the next call,
or storage the caller is about to free. A `string` outlives the call that produced it, so it
cannot be a view onto either.

**But it is one copy, and it is `from_utf8`'s.** Slicing a `*u8` names the bytes where C left them
without taking any hold on them -- a `*T` promises nothing about how long they stay -- and
validation is what turns that borrowed run into a `string` owning its own. Gathering them into a
`[]u8` first, as this once did, is a second buffer for the same bytes that is freed on the next
line.

Fallible for the same reason `from_utf8` is, and it goes through it rather than around it: nothing
about a `char *` promises well-formed UTF-8, and a C library reporting in a non-UTF-8 locale is
the ordinary case rather than a corrupt one.

### `from_utf8`

```sysl
from_utf8(b: []const u8) -> Result[string, Utf8Error]
```

The one direction a `string` could not otherwise be reached from, and it is written here rather
than in the compiler because only its last line needs to be: validation is ordinary sysl over a
`[]u8`, and the compiler supplies just `str_cast`, the raw-tier primitive that says "these bytes
are a string now".

The validation is `scan_at` below, which is also what the lossy form walks -- one table, so that
the two can never come to disagree about which bytes are text.

### `from_utf8_lossy`

```sysl
from_utf8_lossy(b: []const u8) -> string
```

What a decoder is expected to do with bytes that are not text: keep what is well-formed and put
U+FFFD where the rest was, rather than refusing the whole input.

**It is the right answer for untrusted bytes and the wrong one for bytes that ought to be valid**,
which is why it sits beside `from_utf8` rather than replacing it. Text coming off a wire, out of a
file somebody else wrote, or from a serial port carries whatever was sent; a caller reading that
wants a string it can show a person, and a `Result` there only moves the substitution one line up.
A caller decoding its own output wants to be told, and `from_utf8` tells it.

One replacement character per **maximal ill-formed subsequence**, which is what Unicode recommends
and what `scan_at` reports: a truncated three-byte sequence at the end of the input is one U+FFFD
and not two.

Valid input costs one walk and no allocation at all -- the bytes are handed over as they are, which
is the case a caller who cannot know has most of the time.

### `from_utf8_unchecked`

```sysl
from_utf8_unchecked(b: []const u8) -> string
```

The same conversion with the validation left out -- the bytes become a `string` and nothing looks
at them.

**It is here so that a reader meets it beside `from_utf8`**, which is the whole of what one has to
choose between. It was a compiler form until 0.0.82 and so belonged to no module: in scope
everywhere, importable from nowhere, and absent from the page where somebody comparing the two
would look. What could not move is the *operation* -- every safe route to a `string` carries the
UTF-8 guarantee, so setting it aside comes from underneath the language -- and that is `str_cast`,
in the raw tier beside `ptr_cast`, which this is one line over.

**The caller owes the guarantee the compiler would otherwise have.** A `string` holding bytes that
are not UTF-8 breaks `char` downstream of it, which is why the name is long and greppable and why
`from_utf8` is the one to reach for on anything that came from outside the program.

### `grapheme_columns`

```sysl
grapheme_columns(text: []const u8) -> usize
```

How many terminal columns a run of UTF-8 occupies, counted a **grapheme cluster** at a time.

**This and `columns` answer the same question and disagree about emoji.** `columns` sums what
each code point is worth, which is right for text and for a combining accent -- a mark measures
zero, so `e` and an acute come to one either way -- and wrong for a sequence a terminal draws as
one glyph: a family emoji joined with zero-width joiners is four emoji at two columns each, and
it occupies two. `cluster_columns` carries the rule and the cases.

**`columns` is the one to reach for by default, and this is the one to reach for when the text
might hold emoji.** The reason is a cost rather than a preference. Segmenting text into clusters
needs the Unicode Character Database -- 330 KB of tables -- and `columns` needs 499 ranges of its
own and nothing else. `columns` is what lays out a diagnostic's caret and a table of numbers, on
a board as much as on a workstation; a program that measures user-supplied text with emoji in it
has asked for the database and gets it. `library/unicode.md` is where that trade is stated in
full, and `StdArtifactTests` pins that calling `columns` alone does not link the tables.

### `is_char_boundary`

```sysl
is_char_boundary(b: u8) -> bool
```

Whether a byte begins a character rather than continuing one. A continuation byte is the only one
matching `10xxxxxx`, so this is one mask and one comparison -- and it is what a program walking
backwards through text, or snapping an arbitrary offset onto a boundary, needs and would
otherwise write inline. `reference/strings.md § Granularity` calls the same property
"boundary-checked" where the compiler enforces it on `s[a..b]`; this is how a program asks the
question for itself.

### `join`

```sysl
join(parts: []const string, sep: string) -> string
```

Every piece laid end to end with a separator between, which is `split`'s inverse whenever the
separator is one `split` would have found.

It sizes the buffer before it starts, since it can: the answer's length is the sum of the pieces
plus the separators, and knowing it turns a series of doublings into one allocation.

### `parse_bool`

```sysl
parse_bool(b: []const u8) -> Result[bool, ParseError]
```

`"true"` or `"false"`, and nothing else.

Exactly the two spellings `str(b)` produces, so the pair round-trips and neither direction has an
opinion the other lacks. `"True"`, `"yes"` and `"1"` are refused deliberately: each is somebody's
convention, none is this library's, and a program wanting one can say so in three lines that read
as the policy they are.

### `parse_bool`

```sysl
parse_bool(s: string) -> Result[bool, ParseError]
```

### `parse_int`

```sysl
parse_int(b: []const u8) -> Result[int, ParseError]
```

### `parse_int`

```sysl
parse_int(s: string) -> Result[int, ParseError]
```

### `parse_int_base`

```sysl
parse_int_base(b: []const u8, base: int) -> Result[int, ParseError]
```

The same, narrowed to `int`. A value that is a perfectly good `long` and does not fit here is an
`Overflow`, since what the caller asked for is an `int` and there is no honest `int` to hand back.

### `parse_int_base`

```sysl
parse_int_base(s: string, base: int) -> Result[int, ParseError]
```

### `parse_long`

```sysl
parse_long(b: []const u8) -> Result[long, ParseError]
```

### `parse_long`

```sysl
parse_long(s: string) -> Result[long, ParseError]
```

### `parse_long_base`

```sysl
parse_long_base(b: []const u8, base: int) -> Result[long, ParseError]
```

The whole signed range in the given base, `MIN` included.

**The accumulation runs negative, and that is the whole trick.** The signed range is asymmetric
-- the magnitude of the most negative value is one larger than the largest positive one -- so a
parser building the magnitude and negating at the end cannot represent `MIN` at any point and has
to special-case it. Building on the negative side covers the entire range with one path, and the
only value needing a word of its own is a *positive* result of `MIN`'s magnitude, refused just
before the final negation.

**The overflow test happens before the arithmetic that would overflow**, because integer
arithmetic wraps rather than trapping (`01`): a product that has already wrapped is not a number
a later comparison can learn anything from. `limit` is the largest magnitude surviving a multiply
by the base, and `cutoff` is the largest digit that may still be subtracted once the accumulator
is exactly there.

### `parse_long_base`

```sysl
parse_long_base(s: string, base: int) -> Result[long, ParseError]
```

### `parse_real`

```sysl
parse_real(b: []const u8) -> Result[real, ParseError]
```

A floating-point value, through the C library.

It goes to `strtod` for the reason the float half of `str` goes to `snprintf`: correctly rounded
decimal-to-binary conversion is a hard algorithm to get right and an easy one to get subtly
wrong, and the two directions must agree or a value will not survive being written and read back.
Writing it in sysl waits for a target with no C library, which is the same condition
`reference/strings.md § Rendering a value` puts on the rendering half.

**It costs a copy and usually not an allocation**, because `strtod` reads a NUL-terminated
pointer and neither a slice nor a `string` carries a terminator. The copy goes into a buffer on
the stack for any text short enough to fit one, which is every float anybody writes and every
float `str` produces; longer input falls back to the heap rather than being refused, since a
decimal expansion may legitimately run to hundreds of digits and every one of them can move the
last bit of the result.

The end pointer is what turns C's lenient parse into this library's strict one: `strtod` stops at
the first byte it cannot use and reports where, so anything left over is trailing garbage and
`"1.5x"` is refused rather than read as `1.5`.

### `parse_real`

```sysl
parse_real(s: string) -> Result[real, ParseError]
```

### `parse_uint`

```sysl
parse_uint(b: []const u8) -> Result[uint, ParseError]
```

### `parse_uint`

```sysl
parse_uint(s: string) -> Result[uint, ParseError]
```

### `parse_ulong`

```sysl
parse_ulong(b: []const u8) -> Result[ulong, ParseError]
```

### `parse_ulong`

```sysl
parse_ulong(s: string) -> Result[ulong, ParseError]
```

### `parse_ulong_base`

```sysl
parse_ulong_base(b: []const u8, base: int) -> Result[ulong, ParseError]
```

The unsigned range, which is not the signed one with the sign removed: `"ffffffffffffffff"` is a
perfectly ordinary 64-bit mask and overflows every signed parse there is. A systems language that
could not read one back would be missing the case its own literals are written in.

No sign is accepted, not even `+`. A leading `-` on an unsigned value is a question with no good
answer -- wrap, clamp or refuse -- and refusing at the first byte is the one that cannot surprise.

### `parse_ulong_base`

```sysl
parse_ulong_base(s: string, base: int) -> Result[ulong, ParseError]
```

### `repeat`

```sysl
repeat(s: string, n: usize) -> string
```

The text repeated `n` times, and nothing when `n` is zero.

### `replace_all`

```sysl
replace_all(s: string, old: string, new: string) -> string
```

Every non-overlapping occurrence of `old` replaced by `new`, left to right.

Non-overlapping and left-to-right is the same rule `count_of` counts by, so the number of
replacements made is exactly the number it reports -- and the scan continues *after* what was
substituted, so a replacement containing the pattern does not feed on itself.

An empty `old` matches nowhere rather than everywhere: the alternative inserts a copy of `new`
between every pair of bytes, which for a `string` would cut multi-byte characters apart.

### `split`

```sysl
split(s: string, sep: string) -> []string
```

The pieces between each occurrence of a separator.

Adjacent separators yield an empty piece between them, and a separator at either end yields an
empty piece outside it, so the count is always one more than the number of separators found and
nothing about the input is silently dropped. A caller wanting the other behaviour wants `fields`.

**An empty separator yields the whole string as one piece.** Splitting on nothing has no
meaningful answer here: the byte-level reading would cut multi-byte characters in half and hand
back pieces that are not text, and the character-level reading is what `s.chars` already is.

### `starts_with_fold`

```sysl
starts_with_fold(s: string, prefix: string) -> bool
```

Whether `s` starts with `prefix`, ignoring case, and everything `eq_fold` says applies.

**Both sides are folded before the comparison rather than either being folded alone**, which is
what makes it right at the boundary: folding can change a string's length, so a prefix folded
against an unfolded haystack would be looking for bytes that are not there.

### `str_builder`

```sysl
str_builder() -> StrBuilder
```

### `str_builder_with_capacity`

```sysl
str_builder_with_capacity(n: usize) -> StrBuilder
```

A builder that already has room for `n` bytes, for a caller who knows roughly how long the text
will be -- a line being assembled from fields, a buffer sized from what it is copying. What it
saves is the reallocate-and-copy at each doubling on the way up to `n`.

It is a guess and nothing depends on it: too small and the buffer grows the way it always does,
too large and the slack is freed with the rest when the builder goes.

### `str_view`

```sysl
str_view(b: []u8) -> string
```

The bytes of a writable slice as a `string` **without copying them**: the string is a view that
shares the slice's storage, exactly as `s[a..b]` shares a string's, so a later write through the
slice is seen by the string.

**It is here for a buffer that grows in place and is read as text at every step** -- an
appending builder whose contents are handed out as a `string` each time, where
`from_utf8_unchecked` would copy the whole of the text on every call. Go's `unsafe.String` is the
same operation.

**The caller owes two guarantees, not one.** The bytes are valid UTF-8, as for
`from_utf8_unchecked`; and **nothing writes the bytes the string covers while the string is
alive**, since a `string` is taken everywhere to be immutable and a change underneath one breaks
whatever compared, hashed or measured it. Writing *past* the end of the view is fine: the string's
length is the slice's, fixed where it is made. What the compiler still guarantees is the storage.
The string holds a share of the slice's owner, so the bytes live as long as the string does, and a
view of an array the frame owns moves that array to the heap.

### `to_lower`

```sysl
to_lower(s: string) -> string
```

The other direction, and everything `to_upper` says applies to it unchanged: `to_lower("HÉLLO")`
is `héllo`, and `to_lower('ẞ')` is `ß`.

### `to_upper`

```sysl
to_upper(s: string) -> string
```

Unicode case conversion.

**It walks characters rather than bytes, and that is what keeps it safe.** A byte map would be
the faster loop and would need a way to put a raw byte into a builder -- which is the one thing
the builder deliberately does not offer, since a public `write` taking bytes is
`from_utf8_unchecked` with a longer name (`reference/strings.md § Making new bytes`). Going
through `push_char` instead means every way in still carries the UTF-8 guarantee and no unchecked
primitive is named here at all. The decode and re-encode that costs is the right price for that.

It works because `sysl.unicode.to_upper` is **total**: a character the database gives no mapping
for comes back as itself, so text that has no case is re-encoded to exactly the bytes it arrived
as. `to_upper("héllo")` is `HÉLLO`.

**The mapping is the *simple* one, character for character**, which is what a walk over a
`char -> char` function can promise. *Special* casing, where a character uppercases to more than
one and the result is longer than its input, is `sysl.unicode.fold`'s -- and folding is the
operation a caller comparing two strings wanted anyway. *Locale-sensitive* casing needs a notion
of locale this library does not have.

**`ß` is the one worth knowing before calling this.** Most languages uppercase it to `SS`, which
is full casing and two characters; the simple mapping has one to give and gives `ẞ`, the capital
sharp s, which lowercases back. `sysl.unicode.to_upper` carries the detail.

**A program that never calls this links no table.** The database is 330 KB and `sysl.text` is not
optional -- it is what places a diagnostic's caret -- so the two facts have to be stated together
or the first reads as a cost every program pays. It is not: the library is one archive with an
object per C file, a member is pulled in only to resolve a symbol something already referenced,
and the link runs `-dead_strip` over `-ffunction-sections`. What a program links is decided by
the functions it calls rather than by the modules it names, and `StdArtifactTests` pins both
directions of that.

## Types

### `CharIndices`

```sysl
struct CharIndices
    inner: Chars
```

The same walk, reporting where each character started as well as what it was.

This is the cursor a lexer actually wants, and its absence is why the JSON reader that used to sit
in `guide/` and the bytecode lexer beside it both index bytes by hand: a `for` walks a *copy* of a
cursor, so `for c in s.chars`
cannot be asked afterwards where it got to, and a program needing both the character and its
offset had no form that gave it both. Rust calls this `char_indices` and it is here for the same
reason.

The offset is the character's **first** byte, so it is directly what `s[a..b]` takes -- a slice
from a reported offset lands on a boundary by construction.

It wraps a `Chars` rather than decoding for itself, so there is one decoder and the two cursors
cannot come to disagree about a width.

### `Chars`

```sysl
struct Chars
    rest: []const u8
    at: usize
```

A cursor over the characters of a run of bytes, which is what `s.chars` reaches -- the compiler
names `chars_of` for itself, so this is a declaration the language depends on by name rather than
one a program has to find.

| Member | Signature | Description |
|---|---|---|
| `offset` | `offset -> usize` | Where the cursor is, in bytes from the start of the run it was made over. |
| `peek` | `peek(self) -> Option[char]` | The next character without moving to it. |
| `count` | `count(self) -> usize` | How many characters are left, which costs a walk of the remaining bytes -- there is nothing in the representation that knows it, since the whole point of UTF-8 is that the count and the byte length are different numbers. |

### `CString`

```sysl
struct CString
    bytes: []u8
```

A string copied into the NUL-terminated shape C reads, and the owner of that copy -- a language
with no manual free has to say who frees it, so the bytes belong to a value with a lifetime
rather than to whoever remembers.

`len` is one less than the storage because the terminator is not part of the text, and `ptr` is
what an `extern` taking a `char *` is given.

| Member | Signature | Description |
|---|---|---|
| `ptr` | `ptr -> *u8` |  |
| `len` | `len -> usize` |  |

### `ParseError`

```sysl
enum ParseError
    Empty
    BadDigit(at: usize)
    Overflow
    BadBase(base: int)
```

Why a parse refused, at the granularity a caller can act on.

`BadDigit` carries the byte offset for the same reason `Utf8Error` does: a message naming where
is worth writing and cannot be reconstructed afterwards. The four are separated by what a caller
would *do* about them and not by taxonomy -- an empty field is often a default, a bad digit is a
message to a user, an overflow is a wider type or a refusal, and a bad base is the program's own
mistake rather than the input's.

### `StrBuilder`

```sysl
struct StrBuilder
    bytes: &Buf[u8]
```

Text gathered a piece at a time, handed back as one string.

It is here rather than in the compiler because only its last line needs to be underneath the
language: everything above `finish` is ordinary sysl over the same `Buf[u8]` a `ByteSink` uses,
and what no sysl body can write is turning owned bytes into a `string`, which is
`from_utf8_unchecked` of bytes this builder has held all along.

It exists so that joining does not rebuild what it already has. `a + b` allocates a fresh buffer
every time, so gathering n pieces that way costs the sum of every prefix; a builder pays for each
piece once and grows geometrically.

| Member | Signature | Description |
|---|---|---|
| `len` | `len -> usize` |  |
| `is_empty` | `is_empty -> bool` |  |
| `push` | `push(*self, s: string)` |  |
| `push_char` | `push_char(*self, c: char)` | A character's bytes, through the library's one encoder rather than `string(c)` -- which would allocate a whole string per character only to copy its bytes back out. |
| `push_int` | `push_int(*self, n: long)` | The numbers, rendered straight into the buffer. |
| `push_uint` | `push_uint(*self, n: ulong)` |  |
| `push_real` | `push_real(*self, x: real)` | `%g`, so that a float gathered into a builder reads the same as the one `str` and `print` would have produced. |
| `push_bool` | `push_bool(*self, b: bool)` | Two immortal literals, so this allocates nothing either -- and it is here rather than left to the caller so that the set of things a builder takes without a `str` is the set `str` itself renders directly. |
| `clear` | `clear(*self)` |  |
| `finish` | `finish(self) -> string` | Every byte that went in came from a `string` or from `push_char`, so what is held is well-formed by construction and there is nothing left to validate. |

### `Utf8Error`

```sysl
struct Utf8Error
    offset: usize
    truncated: bool
```

What a refused decode reports: the offset `04` asks for, plus the one distinction a caller can
act on -- whether the input merely *ended* in the middle of a sequence, which more bytes would
fix, or holds one that no continuation could rescue.

## Traits

### `Ascii`

```sysl
trait Ascii
    code(self) -> u32
    to_upper(self) -> Self
    to_lower(self) -> Self
    is_ascii(self) -> bool
    is_digit(self) -> bool
    is_upper(self) -> bool
    is_lower(self) -> bool
    is_alpha(self) -> bool
    is_alnum(self) -> bool
    is_space(self) -> bool
    is_blank(self) -> bool
    is_hex_digit(self) -> bool
    is_punct(self) -> bool
    is_print(self) -> bool
    is_graph(self) -> bool
    is_control(self) -> bool
    digit_value(self, base: int) -> Option[int]
```

Asking a byte or a character what kind of thing it is, in the ASCII range.

**It is named for the range it answers over**, which the module this replaces in the older sysl
was not: that one was called `unicode` and classified nothing above 127, so every caller read a
promise the code did not keep. Everything here is a comparison against the ASCII table, and a
value outside it answers `false` to every question rather than being guessed at.

**The property tables are `sysl.unicode`'s**, and this trait answers a narrower question on
purpose. What it is for is the cases genuinely about the ASCII range -- a byte in a wire format, a
digit in a number being parsed, a header name -- where a table lookup would be a slower answer to
a question a comparison already settles, and where the receiver is a `u8` that is not a character
at all. It is also the right tool for a protocol identifier: Unicode case mapping carries
characters *into* the ASCII range, the Kelvin sign lowercasing to a plain `k`, which is the shape
a spoofing check exists to prevent. `reference/strings.md § Granularity` states the layering.

**A trait rather than two sets of functions, for the reason `Float` is one** (`sysl.math`).
Overloading would answer the naming half of it now (`reference/declarations.md § Overloading`) --
it did not exist when this was written, so `is_digit` and `is_digit_char` were the alternative --
but dispatch on the receiver is what makes `b.is_digit()` the same three words whichever of the
two it is, and what lets a member written over the others be written once.

The pair is closed, which is what makes an `impl` the right mechanism here and the wrong one for
the integers. `sysl.math.Signed` cannot be written this way because `iN` is an open family and
five blocks would leave `i128` out; a byte and a character are the only two types this question
is ever asked of, so two blocks are the whole of it.

**What each implementation supplies is three things, and the rest is written once.** A `code` to
compare against, and the two case conversions -- which stay per type only because each has to
answer in its own type, not because the arithmetic differs. Every classifier below is a default
over `code()`, so adding one is one line here and none in either block.

| Member | Signature | Description |
|---|---|---|
| `code` | `code(self) -> u32` | The receiver's value as a code point, which is what every question below is asked of. |
| `to_upper` | `to_upper(self) -> Self` | The case conversions, which answer in the receiver's own type and so cannot be shared. |
| `to_lower` | `to_lower(self) -> Self` |  |
| `is_ascii` | `is_ascii(self) -> bool` | Whether the receiver is in the range every other member here speaks for. |
| `is_digit` | `is_digit(self) -> bool` |  |
| `is_upper` | `is_upper(self) -> bool` |  |
| `is_lower` | `is_lower(self) -> bool` |  |
| `is_alpha` | `is_alpha(self) -> bool` |  |
| `is_alnum` | `is_alnum(self) -> bool` |  |
| `is_space` | `is_space(self) -> bool` | Space, tab, newline, vertical tab, form feed, carriage return -- C's `isspace` set, and the one a program hand-rolling the test tends to get wrong by stopping after the first two. |
| `is_blank` | `is_blank(self) -> bool` | The two characters that separate words on a line without ending it. |
| `is_hex_digit` | `is_hex_digit(self) -> bool` |  |
| `is_punct` | `is_punct(self) -> bool` | Printable and not a space or alphanumeric: the four runs the ASCII table leaves between its named groups. |
| `is_print` | `is_print(self) -> bool` | Something that occupies a column when written, the space included. |
| `is_graph` | `is_graph(self) -> bool` | Something that leaves a mark, which is `is_print` minus the space -- the distinction C draws between `isprint` and `isgraph`, and the one a program measuring visible width needs. |
| `is_control` | `is_control(self) -> bool` | The C0 range and the delete, which is the one control character that is not below the space. |
| `digit_value` | `digit_value(self, base: int) -> Option[int]` | The receiver read as a digit in the given base, or `None` when it is not one. |

### `Search`

```sysl
trait Search
    view(self) -> []const u8
    slice(self, lo: usize, hi: usize) -> Self
    is_empty(self) -> bool
    starts_with(self, prefix: Self) -> bool
    ends_with(self, suffix: Self) -> bool
    index_of(self, needle: Self) -> Option[usize]
    index_of_from(self, needle: Self, from: usize) -> Option[usize]
    last_index_of(self, needle: Self) -> Option[usize]
    last_index_of_from(self, needle: Self, from: usize) -> Option[usize]
    index_of_byte(self, b: u8) -> Option[usize]
    index_of_byte_from(self, b: u8, from: usize) -> Option[usize]
    last_index_of_byte(self, b: u8) -> Option[usize]
    last_index_of_byte_from(self, b: u8, from: usize) -> Option[usize]
    contains(self, needle: Self) -> bool
    has_byte(self, b: u8) -> bool
    count_of(self, needle: Self) -> usize
    trim_start(self) -> Self
    trim_end(self) -> Self
    trim(self) -> Self
    trim_start_matches(self, cutset: Self) -> Self
    trim_end_matches(self, cutset: Self) -> Self
    trim_matches(self, cutset: Self) -> Self
```

Searching text and trimming it -- the half of the operations that makes no new bytes.

**Everything here is allocation-free, and that is what the file boundary is drawn on.** A search
answers with an offset or a `bool`, and a trim answers with a *view* of what it was given, so
nothing in this file needs an allocator and all of it is reachable under `no alloc`. What makes
new text -- joining, replacing, changing case -- is in `edit.sysl` beside it, and needs one.

**It is written once, over `[]const u8`, and that is the whole design.** The older sysl wrote this
surface twice: `std.strings` over `string` and `std.bytes` over `[]byte`, 1,630 lines of
near-identical code, because it had no way to say "either of these". A trait says it: each
implementation supplies two members -- the bytes to look at, and how to cut a piece of itself out
-- and every operation below is a default written against those two. So `s.starts_with("//")` and
`b.starts_with(prefix)` are the same code, and adding an operation adds it to both.

**A byte-level search over UTF-8 is not a shortcut, it is correct.** UTF-8 is self-synchronizing:
a continuation byte is distinguishable from a lead byte, so a well-formed needle cannot match
starting anywhere but at a character boundary. That is why these can ignore encoding entirely,
and why an offset one of them returns is always safe to slice at -- which matters, since
`s[a..b]` traps on a mid-codepoint bound.

**The subsequence searches are Boyer-Moore-Horspool, and the sentence this replaces said one day
they might be.** A needle of two bytes or more is looked for by aligning its *last* byte and, on a
mismatch, shifting the window by however far that byte's own last occurrence in the needle is from
the end -- so the common case reads a fraction of the haystack rather than all of it. Over a
megabyte in which the needle's first byte occurs four thousand times, `index_of` went from 478 to
35 microseconds. Nothing observable changed: the offsets, the empty-needle conventions and the
non-overlapping count are what they were, which is what the differential test in `tests.sysl`
asserts against a scan written out longhand, over a few thousand random inputs.

**The table is 256 bytes on the STACK, which is what keeps the file's allocation claim true.** A
shift is capped at 255 rather than widened to a `usize`, because a shift smaller than the ideal
one is still correct -- it only costs a needle longer than 255 bytes a few more windows -- and
2 KB of frame is a real thing to ask of a target that reaches this module with no allocator at all.

**Below a couple of words of haystack the naive scan is still the faster one and is still what
runs**, because building the table costs something before the first byte is read. Where the two
cross is `sublinear_floor`'s own comment, and it is a good deal lower than the arithmetic suggests.
A one-byte needle never builds a table at all -- it is a single byte scan, which is what
`index_of_byte` already was.

| Member | Signature | Description |
|---|---|---|
| `view` | `view(self) -> []const u8` | The bytes to look at. |
| `slice` | `slice(self, lo: usize, hi: usize) -> Self` | A piece of the receiver, as the receiver's own type -- what lets a trim answer with a view rather than a copy, and what a default cannot do for itself because it does not know how to build a `Self`. |
| `is_empty` | `is_empty(self) -> bool` | There is deliberately no `len` here: both implementations already have one the compiler provides, and a trait member of that name would hide it rather than agree with it. |
| `starts_with` | `starts_with(self, prefix: Self) -> bool` | The empty prefix is a prefix of everything, which falls out of the comparison rather than being said: two slices of no elements are equal. |
| `ends_with` | `ends_with(self, suffix: Self) -> bool` |  |
| `index_of` | `index_of(self, needle: Self) -> Option[usize]` | Where the first occurrence starts, or `None`. |
| `index_of_from` | `index_of_from(self, needle: Self, from: usize) -> Option[usize]` | The same search begun part-way along, which is what walks every occurrence without cutting the haystack up: `index_of_from(n, k + 1)` is the next one after `k`. |
| `last_index_of` | `last_index_of(self, needle: Self) -> Option[usize]` | The empty needle is found at the end here rather than at 0, which is the same convention read from the other side: the last place it occurs. |
| `last_index_of_from` | `last_index_of_from(self, needle: Self, from: usize) -> Option[usize]` | The last occurrence at or after `from` -- the backward search over the same suffix the forward one takes, per the rule on `index_of_from`. |
| `index_of_byte` | `index_of_byte(self, b: u8) -> Option[usize]` |  |
| `index_of_byte_from` | `index_of_byte_from(self, b: u8, from: usize) -> Option[usize]` |  |
| `last_index_of_byte` | `last_index_of_byte(self, b: u8) -> Option[usize]` |  |
| `last_index_of_byte_from` | `last_index_of_byte_from(self, b: u8, from: usize) -> Option[usize]` |  |
| `contains` | `contains(self, needle: Self) -> bool` |  |
| `has_byte` | `has_byte(self, b: u8) -> bool` | Whether one byte occurs anywhere. |
| `count_of` | `count_of(self, needle: Self) -> usize` | How many non-overlapping occurrences there are, counted the way a `replace_all` would find them -- so `count_of` and the number of replacements `replace_all` makes always agree. |
| `trim_start` | `trim_start(self) -> Self` | ASCII whitespace off the front, the back, or both -- which is the answer for a receiver of BYTES, and is not what a `string` gives. |
| `trim_end` | `trim_end(self) -> Self` |  |
| `trim` | `trim(self) -> Self` |  |
| `trim_start_matches` | `trim_start_matches(self, cutset: Self) -> Self` | The same three against a set of bytes the caller names, which is what strips quotes, slashes or padding rather than whitespace. |
| `trim_end_matches` | `trim_end_matches(self, cutset: Self) -> Self` |  |
| `trim_matches` | `trim_matches(self, cutset: Self) -> Self` |  |

## Implementations

### Ascii for char

```sysl
impl Ascii for char
```

A scalar value, which is what walking `s.chars` yields. Anything outside ASCII passes through
both conversions untouched, which is what makes mapping one over arbitrary text safe rather than
merely defined: `é` is not a letter this trait knows, so it is left alone instead of being shifted
into something else.

### Ascii for u8

```sysl
impl Ascii for u8
```

A byte, which is what a program walking `s.bytes` or a `[]const u8` is holding. The case shift is
the single bit ASCII sets between the two alphabets, but it is written as the arithmetic rather
than as a mask: a mask would also change eleven punctuation characters, and the guard is what
makes these total.

### Display for ParseError

```sysl
impl Display for ParseError
```

The rendering, so that a refusal can be printed without matching on it.

### Iterate for CharIndices

```sysl
impl Iterate for CharIndices
```

### Iterate for Chars

```sysl
impl Iterate for Chars
```

### Search for []const u8

```sysl
impl Search for []const u8
```

Bytes that are not text, or not yet: what a program reads off a socket or a file before it knows
whether it is UTF-8. The same operations answer the same way, which is the point of the trait --
and here there is no encoding to be careful of, since there is no guarantee to keep.

### Search for string

```sysl
impl Search for string
```

Text, which is the receiver these are nearly always written on. `slice` goes through the
language's own boundary-checked subscript, so a piece cut out of a string is a string with the
same guarantee -- and the bounds every operation above hands it are ones the search or the trim
already knows are boundaries.
