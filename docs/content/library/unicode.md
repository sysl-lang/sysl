---
title: The unicode module
summary: "`sysl.unicode` — the Unicode Character Database in the standard library: case mapping that answers for `é`, case folding, the four normal forms, grapheme clusters, and General_Category."
weight: 22
---

**Every declaration in `sysl.unicode`, with its signature:** [the generated API page](/api/sysl-unicode/#index). This page is the argument — what the module is for, and how its pieces fit; that one is the list.

A `string` in sysl is UTF-8 and always has been. What it did not have until this module was any
operation that knew what the bytes *mean*: `to_upper` mapped `a`–`z` and left everything else alone,
so a name with an accent in it came back exactly as it went in, and a program that wanted otherwise
had to go and find a binding.

That is what `sysl.unicode` ends. It answers out of the Unicode Character Database, which is vendored
beside it, and it is what [`sysl.text`](/library/text/#the-half-that-allocates)'s case mapping is written on top of.

```sysl
import sysl.unicode.{to_upper, to_lower, category, Category}

print(to_upper('é'), to_lower('É'))
print(category('é'), category('7'), category(' '))
```

```output
É é
Ll Nd Zs
```

## Case mapping is a character at a time, and folding is not

`to_upper`, `to_lower` and `to_title` take a character and answer a character. That is *simple* case
mapping, which is what most text needs and all a `char -> char` signature can promise — it is total,
so a character the database has no mapping for comes back as itself and a whole string can be walked
through it without asking first.

The one that surprises people is `ß`. Most languages uppercase it to `SS`, which is *full* casing and
is two characters; the simple mapping has one to give and gives `ẞ`, the capital sharp s, which
round-trips back.

```sysl
import sysl.unicode.{to_upper, to_lower, fold}

print(to_upper('ß'), to_lower('ẞ'))
print(fold("Straße"), fold("ﬁt"))
```

```output
ẞ ß
strasse fit
```

**`fold` is the operation a caller comparing two strings actually wants**, and it is not lowercasing.
Folding is defined to produce a *comparison key*: it may change a string's length, which is how
`STRASSE` and `straße` come to be the same text, and no per-character mapping could do that — one is
seven characters and the other six, so there is nowhere to put the difference.

```sysl
import sysl.text.to_lower
import sysl.unicode.fold

print(to_lower("STRASSE") == to_lower("straße"))
print(fold("STRASSE") == fold("straße"))
```

```output
false
true
```

So: `to_lower` to show text to somebody, `fold` to decide whether two pieces of text are the same
one.

**And ASCII case mapping, for a protocol identifier.** An HTTP header name, a scheme, a SQL keyword
and a hostname label are all defined as ASCII, and case-mapping one the Unicode way is a known
footgun: **characters outside ASCII map into it**. The Kelvin sign `K` (U+212A) lowercases to a plain
`k`, and the dotless `ı` uppercases to `I` — so two identifiers that were not equal become equal once
you lower them, which is exactly the shape a spoofing check is trying to prevent.
[`sysl.text.Ascii`](/library/text/#classification-ascii) is the tool for that case: it is named for the range it answers
over, its mapping is a comparison rather than a table, and it is what a byte-at-a-time parser wants
anyway. Nothing about it changed.

For ASCII text the two agree exactly, which is why routing `sysl.text.to_upper` through this module
broke nothing: Unicode's simple mapping restricted to `a`–`z` *is* the ASCII mapping. The difference
only shows up on input the old one silently left alone.

## Normalization, and why comparing needs it

The same text typed on two machines is routinely two different sequences of code points: `é` is one
code point on one and `e` followed by a combining acute on the other. They look identical, they are
the same text, and comparing their bytes says no.

`normalize` is the pass that settles it. The form is a closed enum rather than a string, so a
misspelling is a compile error rather than a silent no-op.

```sysl
import sysl.unicode.{normalize, Form}

val one = "é"
val two = normalize(Form.Nfd, one)

print(one.len, two.len, one == two)
print(normalize(Form.Nfc, two) == one)
```

```output
2 3 false
true
```

The four forms are two questions crossed: compose (`C`) or decompose (`D`), and whether to apply the
compatibility mappings (`K`). The compatibility forms throw information away — that is what they are
for — so they are for matching rather than for storing.

```sysl
import sysl.unicode.{normalize, Form}

print(normalize(Form.Nfc, "ﬁt"), normalize(Form.Nfkc, "ﬁt"))
print(normalize(Form.Nfkc, "x²"))
```

```output
ﬁt fit
x2
```

Text going onto a wire, into a filename or into a database key should be `Nfc`. `Nfd` is for a
program that wants base letters and accents separately. The `K` forms are for a search index.

## A grapheme cluster is the third answer to how long text is

`s.len` is bytes. A [`Chars`](/library/text/#characters-and-bytes) walk is code points. Neither is what a
person means by *character*, and neither is what a cursor should move by or a field width should
count in.

```sysl
import sysl.unicode.{graphemes_of, grapheme_count}

val s = "e\u{301}x"

print(s.len, grapheme_count(s.bytes))

for g in graphemes_of(s.bytes)
    print(g.len)
```

```output
4 2
3
1
```

The walk answers **views** onto the bytes it was given rather than new strings, so it allocates
nothing and can be run over a caller's own buffer. A cluster is a run of the input; a program that
wants one as a `string` builds it from the view.

**The cursor carries state, and that is not an optimization.** Unicode's segmentation rules are not a
function of two adjacent code points — a regional indicator pairs with the one before it only if that
one was not already paired — so two walks over the same bytes started at different offsets may
disagree. Start at the beginning of the text.

## What a character is

`category` answers the database's own General_Category, as the two-letter abbreviations it uses. The
predicates are written on top of it, and each is the narrow question rather than the broad one:
`is_digit` is `Nd`, so the Arabic-Indic digits answer yes and a superscript two does not.

```sysl
import sysl.unicode.{category, Category, is_alpha, is_digit, is_space, is_mark}

print(category('A'), category('ǲ'), category('日'))
print(is_alpha('é'), is_digit('٣'), is_digit('²'))
print(is_space('\n'), is_space('\u{a0}'), is_mark('\u{301}'))
```

```output
Lu Lt Lo
true true false
true true true
```

An unassigned code point answers `Cn`, which is the database's own answer rather than a failure — the
question *what is this character* has an answer for every code point, and for most of them it is
*nothing yet*.

**`is_upper` and `is_lower` are not `category(c) == Lu` and `== Ll`**, and writing them that way is
the obvious mistake. The database's test is *cased and already upper*, which cuts across the
categories in both directions: the Roman numeral `Ⅰ` is `Nl` and the circled `Ⓐ` is `So`, and both
are upper; a titlecase digraph is `Lt` and is neither.

```sysl
import sysl.unicode.{is_upper, is_lower, is_valid}

print(is_upper('Ⅰ'), is_upper('ǅ'), is_lower('ǅ'))
print(is_valid(u32('a')), is_valid(0xd800), is_valid(0x110000))
```

```output
true false false
true false false
```

**`is_valid` takes a `u32` rather than a `char`, and that is the point of it.** A `char` is a valid
scalar value by construction — `char(0xd800)` does not make an invalid character, it *traps*, in the
same runtime-safety category as a bounds check. The caller who needs this question answered is
holding a number that has not been made into a character yet: a field out of a binary format, a
`\u` escape being decoded. Asking here is how they find out before the conversion traps.

`unicode_version()` says which version of the database answered, which a program storing normalized
text has to be able to record: normalization is stable across versions for characters that were
already assigned, and says nothing about ones that were not.

## What is not here: display width

utf8proc answers it and this module does not expose it. [`sysl.text.char_columns`](/library/text/#how-wide-is-it-on-screen-columns) has
answered *how many terminal columns* since before this module existed, out of tables of its own, and
two answers to one question in one standard library is worse than either.

**Which one stays is decided by who pays.** `sysl.text.columns` is what places a diagnostic's caret,
so it is reached by programs that never asked for anything Unicode — including on a board, where it
links about four kilobytes of ranges and would link three hundred through here. A program reaches
`to_upper` by deciding to uppercase a string, so what that pulls in is a cost it opted into. Width is
inherited; case mapping is asked for.

## What it costs

Nothing at all to a program that does not call into it.

The standard library is compiled to an archive with one object per C file it carries, an archive
member is pulled in only to resolve a symbol something already referenced, and the link then drops
what nothing reaches. A program whose whole text is `print(1)` carries no table. A program that calls
`to_upper` carries about 330 KB, nearly all of it the database.

That is worth stating plainly because the argument against putting a Unicode table in the standard
library has always been that `sysl.text` is not optional. It is not optional, and it does not follow:
what a program links is decided by the functions it calls, not by the modules it names.

**It reaches a bare-metal target unchanged.** The vendored copy compiles for `thumbv6m-freestanding`
with no libc at all, and the per-character mappings allocate nothing — the string operations are the
only ones that need a heap, which is the split the module is filed in two files for. The compiler's
own suite boots five emulated boards, uppercases `é` on each and reads the answer back off a UART.

**On a small enough part it does not fit, and that is the honest number.** The sixth board in that
suite is a micro:bit, whose nRF51 has 256 KB of flash; linking the database into it overflows by
82,180 bytes. Nothing is wrong there — a 330 KB table does not go into a 256 KB part, and a board
that wants case mapping needs room for it.
