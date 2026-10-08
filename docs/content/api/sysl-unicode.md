---
title: sysl.unicode
layout: api-module
headingShift: 0
slugStyle: github
module: sysl.unicode
summary: "What the Unicode Character Database says about a character, and the operations over text that only that database can answer."
---

**The module is three files and the boundary is the allocator**, which is the same split
`sysl.text` draws between `find.sysl` and `edit.sysl` and for the same reason: a program that only
asks what a character is should not link an allocator on account of a `normalize` it never calls.

| | |
|---|---|
| this file | what a character *is* -- the case mappings, the predicates, the category. Allocates nothing |
| `graphemes.sysl` | where one user-perceived character ends, over a byte view. Allocates nothing |
| `map.sysl` | `fold` and `normalize`, which answer with a whole new string and so need a heap |

==Why this is in the standard library rather than in a package==

Case mapping, case folding, normalization and grapheme segmentation are properties of *strings*.
A language whose `to_upper` is ASCII-only has a `string` type that is Unicode in its storage and
ASCII in its operations, and every program that meets a name with an accent in it has to go and
find a binding. Done once here, every sysl program gets it.

==Where the data comes from==

`utf8proc.c` beside this file, vendored -- the same library Julia's `Base` uses behind its own
string functions, for exactly this set. `README.md` records the version, its checksum, and the
five lines this copy carries on top of it.

==What is deliberately NOT here: display width==

utf8proc answers it and this module does not expose it, because `sysl.text.char_columns` already
does, out of tables of its own. Two answers to one question in one standard library is worse than
either of them, and the tables are the half that has to agree.

**Which one stays is decided by who pays.** `sysl.text.columns` is what places a diagnostic's
caret, so it is reached by programs that never asked for anything Unicode -- including on a board,
where it links about four kilobytes of ranges today and would link three hundred through here.
A program reaches `to_upper` by deciding to uppercase a string, so what it pulls in is a cost it
opted into. Width is inherited; case mapping is asked for.

## Index

[`category`](#category) [`fold`](#fold) [`grapheme_count`](#grapheme_count) [`graphemes_of`](#graphemes_of) [`is_alpha`](#is_alpha) [`is_digit`](#is_digit) [`is_lower`](#is_lower) [`is_mark`](#is_mark) [`is_space`](#is_space) [`is_upper`](#is_upper) [`is_valid`](#is_valid) [`normalize`](#normalize) [`to_lower`](#to_lower) [`to_title`](#to_title) [`to_upper`](#to_upper) [`unicode_version`](#unicode_version) [`Category`](#category-1) [`Form`](#form) [`Graphemes`](#graphemes) [Iterate for Graphemes](#iterate-for-graphemes)

## Functions

### `category`

```sysl
category(c: char) -> Category
```

What `c` is, as the database's own General_Category.

An unassigned code point answers `Cn`, which is the database's answer rather than a failure: the
question *what is this character* has an answer for every code point, and for most of them it is
*nothing yet*.

### `fold`

```sysl
fold(s: string) -> string
```

`s` case-folded, which is the comparison key for *is this the same text ignoring case* and is
not the same operation as lowercasing.

**Folding may change a string's length, and that is the point of it**: `fold("ß")` is `"ss"` and
`fold("ﬁ")` is `"fi"`, so `fold(a) == fold(b)` answers yes where `to_lower(a) == to_lower(b)`
answers no. Lowercasing is for showing text to somebody; folding is for deciding whether two
pieces of text are the same one.

The result is composed (`Nfc`), so folding is idempotent and two strings that fold alike compare
alike without a normalization pass of their own.

**Decomposing is a pass of its own, before the fold rather than fused into it.** utf8proc's
casefold mapping runs per code point ahead of canonical reordering, so folding a precomposed
character straight to `Casefold | Compose` can leave a mark casefold introduces -- `U+0345
COMBINING GREEK YPOGEGRAMMENI` out of a folded iota subscript is the one this module has seen --
sitting in the position the *original* character held rather than where its combining class
says it belongs. Decomposing first puts every mark utf8proc will ever see through the same
reordering pass the fold itself performs, which is what Unicode caseless matching (UAX #21):
`toNFD(toCaseFold(toNFD(x)))` asks for. `mapped` composes on the way back out, so this is the two
calls that chain, not three.

### `grapheme_count`

```sysl
grapheme_count(b: []const u8) -> usize
```

How many grapheme clusters `b` holds -- what a person would call its length in characters.

It is a walk, because nothing in the representation knows: the same argument `Chars.count` makes
one level down. What it is worth is that this is the number a field width, a truncation and a
cursor step should all be counted in.

### `graphemes_of`

```sysl
graphemes_of(b: []const u8) -> Graphemes
```

A walk over `b`, which is what a caller holding a `string` reaches as `graphemes_of(s.bytes)`.

### `is_alpha`

```sysl
is_alpha(c: char) -> bool
```

Whether `c` is a letter in any of the five senses the database distinguishes, which is the test
an identifier scanner and a word splitter both want and neither can write out of ASCII.

### `is_digit`

```sysl
is_digit(c: char) -> bool
```

Whether `c` is a decimal digit -- `Nd`, so the Arabic-Indic and Devanagari digits answer yes
along with `0` through `9`, and a superscript `²` does not.

### `is_lower`

```sysl
is_lower(c: char) -> bool
```

The other direction, and everything `is_upper` says applies to it: `ª` is `Lo` and answers yes,
because the database gives it an uppercase mapping and no lowercase one.

### `is_mark`

```sysl
is_mark(c: char) -> bool
```

Whether `c` is a mark -- a combining accent, a spacing mark, an enclosing mark. A caller
measuring or segmenting text is asking this to find the characters that belong to the one before
them.

### `is_space`

```sysl
is_space(c: char) -> bool
```

Whether `c` is a space of some kind, which is the three separator categories plus the control
characters a line of text is broken on. `Zs` alone would leave out `\n` and `\t`, which is not
what anybody trimming a line means.

**This is exactly the database's `White_Space` property**, arrived at from the categories rather
than from a table of its own: `Zs` holds the spaces including the non-breaking one, `Zl` and `Zp`
the two separators, and what is left over is `U+0009` through `U+000D` and `U+0085`, the next-line
control -- six characters the database files under `Cc`, where the rest of the controls are not
spaces at all. Six comparisons is a smaller thing to keep right than a table.

### `is_upper`

```sysl
is_upper(c: char) -> bool
```

Whether `c` is *cased and already upper*, which is a different question from
`category(c) == Category.Lu` and is nearly always the one a caller means.

**The two disagree in both directions, which is why this is a function rather than a comparison.**
`Ⅰ`, the Roman numeral, is `Nl` and answers yes; the circled `Ⓐ` is `So` and answers yes; a
titlecase digraph is `Lt` and answers no although it is not lower either. The test the database
supports is *has a lowercase mapping, has no uppercase one, and is not titlecase* -- which is what
a caller asking "do I need to uppercase this?" is asking.

### `is_valid`

```sysl
is_valid(code: u32) -> bool
```

Whether a number is a code point Unicode allows to be encoded at all -- which excludes the
surrogates and everything above `U+10FFFF`.

**It takes a `u32` rather than a `char`, and that is the whole point of it.** A `char` is a valid
scalar value by construction: `char(0xd800)` does not produce an invalid character, it **traps**,
in the same runtime-safety category as a bounds check. So a `char` overload could only ever answer
`true`, and the caller who needs this question answered is holding a number that has not been made
into a character yet -- a field out of a binary format, a `\u` escape being decoded, an index
arriving from somewhere else. Asking here is how they find out before the conversion traps.

### `normalize`

```sysl
normalize(f: Form, s: string) -> string
```

`s` put into the normal form `f`.

`normalize(Form.Nfc, "e\u{301}")` is `"é"` as one code point, and `normalize(Form.Nfd, "é")` is
the letter and the accent as two. That is the operation two strings have to go through before
`==` between them means what a reader thinks it means: the same text typed on two machines is
routinely two different sequences of code points, and nothing about comparing bytes will say so.

### `to_lower`

```sysl
to_lower(c: char) -> char
```

The other direction, and everything `to_upper` says applies to it unchanged.

### `to_title`

```sysl
to_title(c: char) -> char
```

The titlecase form, which is not the same question as uppercase and differs for exactly the
characters that exist to be a first letter: `ǳ` titlecases to `ǲ` and uppercases to `Ǳ`.

### `to_upper`

```sysl
to_upper(c: char) -> char
```

The character `c` maps to in uppercase, or `c` where the database gives no mapping.

**This is *simple* case mapping, one character in and one out**, which is what a `char -> char`
signature can promise and all that most text needs. The mappings that change a string's *length*
-- `ﬃ` uppercasing to `FFI` -- are *special* casing, and a caller who wants them wants `fold` in
`map.sysl`, which is the operation defined to produce them.

**`ß` is the one worth knowing about, because it has no simple uppercase at all.** Full casing
gives `SS`, which is what most languages' `upper` answers; the Unicode Character Database gives
`ß` no simple uppercase mapping, so `to_upper('ß')` is `'ß'` and `"straße"` uppercases to
`"STRAßE"`. The mapping is not symmetric: `ẞ`, U+1E9E, the capital sharp s, lowercases to `ß`,
but `ß` does not uppercase to it. `fold` is where `ss` comes from, and comparing is what a caller
wanting it was doing.

`to_upper('é')` is `'É'`, which is the whole point of this module: the ASCII answer was `'é'`.

### `unicode_version`

```sysl
unicode_version() -> string
```

The version of the Unicode Character Database this module answers from, as the standard writes
it -- `"17.0.0"` and so on.

It is here because a program that stores normalized text has to be able to say which version
normalized it: normalization is stable across versions for characters that were already assigned,
and says nothing about ones that were not.

## Types

### `Category`

```sysl
enum Category
    Cn
    Lu
    Ll
    Lt
    Lm
    Lo
    Mn
    Mc
    Me
    Nd
    Nl
    No
    Pc
    Pd
    Ps
    Pe
    Pi
    Pf
    Po
    Sm
    Sc
    Sk
    So
    Zs
    Zl
    Zp
    Cc
    Cf
    Cs
    Co
```

A character's General_Category, as the two-letter abbreviations the database itself uses.

The order is the database's own, which is what lets the C answer be read straight into it: a
`utf8proc_category` result is this enum's discriminant and the conversion is a cast rather than a
table. `Cn` is first because an unassigned code point is category zero.

### `Form`

```sysl
enum Form
    Nfc
    Nfd
    Nfkc
    Nfkd
```

Which normal form to put text into.

The four are two questions crossed: whether to compose (`C`) or decompose (`D`), and whether to
apply the compatibility mappings (`K`) that turn `ﬁ` into `fi` and `²` into `2`. `Nfc` is what
text on the wire and in a filename should be; `Nfd` is what a program comparing base letters
separately from their accents wants; the two `K` forms are for matching rather than for storing,
because they throw information away.

### `Graphemes`

```sysl
struct Graphemes
    rest: []const u8
    at: usize
    state: i32
```

A cursor over the grapheme clusters of a run of bytes.

**The state field is not an optimization and cannot be dropped.** Unicode's segmentation rules are
not a function of two adjacent code points -- a regional indicator pairs with the one before it
only if that one was not already paired, and an emoji sequence's extenders depend on what opened
it -- so the boundary algorithm carries state across the whole string. Two `Graphemes` over the
same bytes started at different offsets may therefore disagree, exactly as restarting a regular
expression halfway through would.

| Member | Signature | Description |
|---|---|---|
| `offset` | `offset -> usize` | Where the cursor is, in bytes from the start of the run it was made over -- the same property `Chars` carries, and for the same reason: a `for` walks a copy, so a program that needs the offset drives the cursor by hand. |

## Implementations

### Iterate for Graphemes

```sysl
impl Iterate for Graphemes
```
