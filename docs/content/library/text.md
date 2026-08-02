---
title: The text module
summary: "`sysl.text` — validating bytes into text, walking it by character, searching and trimming without an allocator, building and splitting with one, and reading values back out."
weight: 20
---

A `string` is [an immutable, validated `[]u8`](/reference/types/), and the language gives it a few
things directly: its length, its bytes, a boundary-checked substring, and a cursor over its
characters. Everything a program then *does* with text — validate, classify, search, trim, split,
join, build, parse — is `sysl.text`, and none of it is a language feature.

**One piece of the module is reached without being named.** `s.chars` is a member the compiler
provides, and it calls this module's `chars_of` by key rather than by resolving the word — so
walking characters costs a program nothing, not even an import line:

```sysl
var s = "héllo"

print(s.len, s.bytes.len)

for c in s.chars
    prints(str(c))
    prints(".")

prints("\n")
print(s[0..<1], s.bytes[1])
```

```output
6 6
h.é.l.l.o.
h 195
```

Six bytes and five characters, and `s.bytes[1]` is `195` — the first half of `é`. That is the whole
of what this module is about: the two counts are different numbers, and every operation here is
explicit about which one it works in.

## What is in it

The module is six files, and the boundaries between them are arguments rather than filing:

| file | holds | why it is a file |
|---|---|---|
| `utf8.sysl` | `from_utf8`, `Utf8Error`, `char_from_u32`, `from_cstring`, `is_char_boundary`, `Chars`, `CharIndices` | the validity seam — what turns bytes into text |
| `ascii.sysl` | trait `Ascii`, implemented for `u8` **and** `char` | classification, **named for the range it answers over** |
| `find.sysl` | trait `Search`, implemented for `string` **and** `[]const u8` | everything that makes **no new bytes** |
| `edit.sysl` | `split`, `fields`, `join`, `repeat`, `replace_all`, `to_upper`, `to_lower` | everything that **does** — so it needs an allocator |
| `parse.sysl` | `ParseError`, `parse_bool`/`int`/`long`/`uint`/`ulong`/`real` and the `_base` forms | the direction `str(x)` does not go |
| `build.sysl` | `StrBuilder`, `CString`, `str_builder_with_capacity` | gathering text, and the copy C reads |

Two of those rows carry the module's whole design, and they are worth reading before anything else.

**`Ascii` and `Search` are each written once over two receivers.** sysl has no overloading, so the
obvious shape is two sets of functions — `is_digit` for a byte and some suffixed twin for a
character; a search over `string` and another over `[]u8`. The older sysl did exactly that and paid
1,630 lines of near-identical code for it. A trait says "either of these" instead: each
implementation supplies two or three members and every operation is a default written once against
them.

**The `find`/`edit` split is the allocator, not taxonomy.** A search answers with an offset or a
`bool`; a trim answers with a *view* of what it was given. Neither makes a byte that was not already
there, so all of `find.sysl` is reachable under [`no alloc`](/reference/modules/) — and a program
that only searches never links an allocator on account of a `join` it does not call. That is
demonstrated [below](#the-half-that-allocates), because the compiler enforces it.

## Characters and bytes

| operation | spelling | cost |
|---|---|---|
| byte length | `s.len` | O(1) |
| byte at an index | `s[i] -> u8` | O(1), bounds-checked |
| substring | `s[a..b] -> string` | O(1), shares; bounds-checked **and** boundary-checked |
| the bytes | `s.bytes -> []const u8` | O(1) view |
| the characters | `s.chars -> Chars` | O(1) per step |
| concatenation | `a + b` | O(n), allocates |

Every row but one yields a value or a view. `s.chars` yields a **sequence**, and it has to: the
decoding is what makes the scalar values, so there is nothing a string could hand out the way a
container hands out a view of its storage. So `Chars` carries a position and answers "the next one",
which is the whole of the [iteration protocol](/library/core/).

The cursor validates nothing, and does not need to — a `string` is well-formed UTF-8 by
construction, so the decoder reads the width off the lead byte and takes the continuation bytes as
given. A `Chars` built over a `[]u8` that is *not* a string is an ordinary struct over ordinary
bytes: it gives the garbage-in answer and never reads past the end, since every byte it takes goes
through the slice's own bounds check.

### Driving a cursor by hand

A `for` walks a **copy** of a cursor, so a loop over `s.chars` cannot be asked afterwards where it
got to. A program that needs to know drives the cursor itself, and `Chars` offers three things by
value for exactly that — `offset`, `peek` and `count`, none of which consume anything:

```sysl
import sysl.text.{char_indices, is_char_boundary}

var s = "héllo"

print(s.len, s.chars.count())
print(s.chars.peek().unwrap())

var cur = s.chars

print(cur.offset)
print(cur.next().unwrap())
print(cur.offset, cur.count())

for pair in char_indices(s.bytes)
    print(pair.0, pair.1)

print(is_char_boundary(s.bytes[1]), is_char_boundary(s.bytes[2]))
print(s[1..<3])
```

```output
6 5
h
0
h
1 4
0 h
1 é
3 l
4 l
5 o
true false
é
```

**Read the `char_indices` column downward: 0, 1, 3, 4, 5.** The jump from 1 to 3 is `é` being two
bytes wide, and it is the entire reason this walk exists. The offset reported is each character's
**first** byte, so a slice built from two of them lands on boundaries by construction — which matters,
because `s[a..b]` *traps* on a mid-codepoint bound rather than handing back something that is not
text.

`is_char_boundary` asks the same question of a single byte: one mask and one comparison, since a
continuation byte is the only one matching `10xxxxxx`. It is what a program walking backwards, or
snapping an arbitrary offset onto a boundary, would otherwise write inline.

### What that shape is for

This is a lexer, and it is why `offset` and `peek` are on the cursor at all — without them a scanner
has to index bytes by hand and decode a second time:

```sysl
import sysl.text.Ascii

scan(src: string)
    var cur = src.chars

    while cur.peek().is_some()
        var start = cur.offset
        var c = cur.peek().unwrap()

        if c.is_alpha()
            while cur.peek().unwrap_or(' ').is_alnum()
                cur.next()

            print("word  ", src[start..<cur.offset])
        elif c.is_digit()
            while cur.peek().unwrap_or(' ').is_digit()
                cur.next()

            print("number", src[start..<cur.offset])
        else
            cur.next()
end scan

scan("let x1 = 42")

var s = "héllo"
var walked = 0

for c in s.chars
    walked += 1

print("after the loop the cursor is untouched:", s.chars.offset, walked)
```

```output
word   let
word   x1
number 42
after the loop the cursor is untouched: 0 5
```

`src[start..<cur.offset]` is an O(1) substring sharing the source's bytes — a token costs a retain
and no copy.

## Validating bytes into text

```sysl
from_utf8(b: []const u8) -> Result[string, Utf8Error]
from_cstring(p: *u8) -> Result[string, Utf8Error]
char_from_u32(u: u32) -> Option[char]

struct Utf8Error
    offset: usize
    truncated: bool
```

**The compiler supplies exactly one primitive here — `from_utf8_unchecked`, which takes a `[]u8` as a
`string` with nothing looked at — and the validator on top of it is ordinary sysl.** Nothing in a
byte-by-byte scan needs anything the language does not already offer. What no sysl body can write is
the last line, because every *safe* route to a `string` already carries the guarantee.

The validator is Unicode's well-formedness table rather than a decode-then-range-check, and the
difference shows in what it costs to be right: in the table the **lead** byte fixes the legal range
of the byte after it — `E0` demands `A0..BF`, `ED` only `80..9F`, `F0` demands `90..BF`, `F4` only
`80..8F` — so an overlong encoding, a surrogate, and a value past `10FFFF` are all rejected at the
second byte by the same test, before any code point is assembled.

`Utf8Error` carries the offset and **one** distinction beyond it:

```sysl
import sysl.text.from_utf8

var good = [104u8, 195u8, 169u8]
var cut = [104u8, 195u8]
var surr = [237u8, 160u8, 128u8]

from_utf8(good[..]) match
    Ok(t) -> print("ok", t)
    Err(e) -> print("refused", e.offset, e.truncated)

from_utf8(cut[..]) match
    Ok(t) -> print("ok", t)
    Err(e) -> print("refused", e.offset, e.truncated)

from_utf8(surr[..]) match
    Ok(t) -> print("ok", t)
    Err(e) -> print("refused", e.offset, e.truncated)
```

```output
ok hé
refused 1 true
refused 0 false
```

**`truncated` is the only thing a caller can act on differently.** The second input is `h` followed
by a lead byte with its continuation missing — more bytes would fix it, which is exactly the case a
program reading a stream in chunks is in. The third is a surrogate: no continuation could rescue it,
and reading further is reading past an error. A taxonomy of fault names would give a caller more to
match on and nothing more to *do*.

The bytes are **copied**, not viewed, which is why this entry requires an allocator. A `[]u8` is
writable and a `string` is not, so sharing would let a later write through the slice change a value
that had already been checked. Copying is what makes the validation mean anything afterwards.

`char_from_u32` is the same shape one scalar down — the fallible half of `u32 -> char`, refusing a
surrogate or anything past `10FFFF`, where the plain `char(u)` conversion traps instead. It is a free
function because a scalar has no member namespace to hang a `char.try` on.

## Classification: `Ascii`

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
    is_hex_digit(self) -> bool
    is_punct(self) -> bool
    is_print(self) -> bool
    is_control(self) -> bool
    digit_value(self, base: int) -> Option[int]
```

Three members are required; every classifier below the line is a default written once over `code()`.
Adding one is a line in the trait and nothing in either implementation.

**The name is the promise.** This answers over the ASCII range and nothing else — a value at or above
128 answers `false` to every question rather than being guessed at. That is what `is_ascii` is for:
it distinguishes "not a letter" from "not a letter I can see". Real Unicode classification needs
property tables, which must not be in a kernel, so it belongs to a library above this one.

The older sysl called the equivalent module `unicode` and classified nothing above 127, so every
caller read a promise the code did not keep. This name exists to avoid that.

```sysl
import sysl.text.Ascii

print(u8('7').is_digit(), u8('7').is_alpha(), u8('7').digit_value(10).unwrap())
print(u8('f').digit_value(16).unwrap(), u8('z').digit_value(16).is_none())
print('é'.is_ascii(), 'é'.is_alpha(), 'é'.to_upper())
print('A'.to_lower(), u8('a').to_upper())
```

```output
true false 7
15 true
false false é
a 65
```

Two things in that last line. `'é'.to_upper()` is `é` — **both conversions are total**, leaving
anything that is not a letter of the other case exactly as they found it, which is what makes mapping
one over arbitrary text safe rather than merely defined. And `u8('a').to_upper()` prints `65`, not
`A`: it answers in the receiver's own type, and a `u8` renders as a number. That is the only reason
the two conversions are per-implementation at all — the arithmetic is identical.

`digit_value(base)` is here rather than beside the parsers because it is a question about a
character, and because parsing wants exactly this and nothing else from a digit. Letters count from
ten in either case, so base 16 and base 36 need no separate spelling, and a base outside 2..36
answers `None` for every input rather than trapping — the caller that passed it is the one that can
say what to do about it.

## Searching and trimming: `Search`

```sysl
trait Search
    view(self) -> []const u8
    slice(self, lo: usize, hi: usize) -> Self

    is_empty(self) -> bool
    starts_with(self, prefix: Self) -> bool
    ends_with(self, suffix: Self) -> bool
    index_of(self, needle: Self) -> Option[usize]
    last_index_of(self, needle: Self) -> Option[usize]
    index_of_byte(self, b: u8) -> Option[usize]
    last_index_of_byte(self, b: u8) -> Option[usize]
    contains(self, needle: Self) -> bool
    has_byte(self, b: u8) -> bool
    count_of(self, needle: Self) -> usize

    trim(self) -> Self
    trim_start(self) -> Self
    trim_end(self) -> Self
    trim_matches(self, cutset: Self) -> Self
    trim_start_matches(self, cutset: Self) -> Self
    trim_end_matches(self, cutset: Self) -> Self
```

Two required members carry all of it. **`view` is the whole of what a `string` and a `[]const u8`
disagree about** — the bytes to look at. **`slice` is what lets a trim answer with a view rather than
a copy**, and it is required rather than defaulted because a default cannot build a `Self`.

There is deliberately no `len`: both implementations already have one the compiler provides, and a
trait member of that name would hide it rather than agree with it.

```sysl
import sysl.text.Search

var s = "  hello, world  "

print(s.trim())
print(s.contains("world"), s.index_of("world").unwrap())
print("aaa".count_of("aa"), "--x--".trim_matches("-"))
print("path/to/file.txt".last_index_of_byte(u8('/')).unwrap())
print("// comment".starts_with("//"), "file.txt".ends_with(".txt"))
```

```output
hello, world
true 9
1 x
7
true true
```

**`"aaa"` contains one `"aa"`, not two.** Counting is non-overlapping and left to right, which is the
same rule `replace_all` substitutes by — so the number `count_of` reports and the number of
replacements made always agree.

`index_of` answers with an `Option` rather than the older sysl's `-1`, because a sentinel is a value
the type calls ordinary and every caller has to remember to check.

### A byte-level search over UTF-8 is correct, not a shortcut

UTF-8 is self-synchronizing: a continuation byte is distinguishable from a lead byte, so a
well-formed needle **cannot** match starting anywhere but at a character boundary. So these ignore
encoding entirely, and an offset one of them returns is always safe to slice at.

Trimming whitespace is safe from the other side of the same fact — every whitespace byte is ASCII, so
every byte removed is a whole character and every bound left behind is one the receiver already had.

**A cutset is a set of bytes, and that is the group's one caveat.** A non-ASCII character in a cutset
is its bytes and not itself, so such a cutset can cut a character in half — which for a `string` is a
trap rather than a wrong answer. The whitespace trims are the ones without a caveat.

### The second implementation, and what it is for

`Search` is implemented for `[]const u8` as well, and that receiver is the point of the trait: bytes
that are not text, or not yet — what a program has read off a socket or a file before it knows
whether it is UTF-8.

```sysl
import sysl.text.Search

var raw = [104u8, 105u8, 33u8]
var needle = [105u8, 33u8]

print(raw[..].contains(needle[..]), raw[..].index_of(needle[..]).unwrap())
print(raw[..].starts_with([104u8][..]), raw[..].is_empty())
print("  hello  ".bytes.trim().len)
```

```output
true 1
true false
5
```

`raw[..]` is a `[]u8` — writable — and the implementation is written for `[]const u8`. It resolves
because [a `[]T` is accepted wherever a `[]const T` is wanted](/reference/types/): the read-only bit
is part of one type rather than making two, and a receiver is such a place. The reverse does not
hold, and must not: a member written for a `[]T` may write through its receiver.

## The half that allocates

`split`, `fields`, `join`, `repeat`, `replace_all`, `to_upper` and `to_lower` are free functions over
`string`, not members of `Search`. **A trait default cannot write them** — it would have to build a
`Self` out of new bytes, and there is no way for a trait to say how. That is the honest boundary of
the write-it-once trick, and it falls exactly where the allocator does.

```sysl
import sysl.text.{split, fields, join, repeat, replace_all, to_upper, to_lower}

var parts = split("a,b,,c", ",")
var words = ["x", "y", "z"]

print(parts.len, parts[0], parts[2] == "", parts[3])
print(fields("  one   two  ").len)
print(join(words[..], "-"))
print(repeat("ab", 3), repeat("ab", 0) == "")
print(replace_all("aaa", "aa", "b"))
print(to_upper("héllo"), to_lower("HÉLLO"))
```

```output
4 a true c
2
x-y-z
ababab true
ba
HéLLO hÉllo
```

**`to_upper("héllo")` is `HéLLO`.** The `é` is untouched, which is the ASCII promise made visible
rather than merely stated. These walk *characters* rather than bytes and go through `push_char`, so
every way into the builder still carries the UTF-8 guarantee and no unchecked primitive is named —
and it works because `Ascii for char` is total, so a character outside the range is re-encoded to
exactly the bytes it arrived as. A byte map would be the faster loop and would need a raw-byte way
into a builder, which is the one thing the builder deliberately does not offer.

**`split` drops nothing and `fields` drops whitespace.** Adjacent separators yield an empty piece
between them and a separator at either end yields an empty piece outside it, so `"a,b,,c"` is four
pieces of which the third is empty. `fields` is not `split` on a space: a run of whitespace separates
two fields rather than producing empty ones between them, which is what reading a line of columns
wants.

**What splitting hands back are views.** A piece shares the bytes of the string it came from and
costs a retain, so `split` allocates the *vector* and not the text. The older sysl copied every
piece, for want of an O(1) substring to hand out — this is where the representation pays off most
visibly.

Two edges are deliberate and share a reason: an empty separator yields the whole string as one piece,
and an empty pattern in `replace_all` matches nowhere. The byte-level reading of either would cut
multi-byte characters apart, and the character-level reading is what `s.chars` already is.

### The split is enforced, not documented

A module that has given up its allocator can search and trim all it likes:

```sysl
no alloc

import sysl.text.Search

var line = "  key = value  "

print(line.trim())
print(line.index_of("=").unwrap(), line.trim().starts_with("key"))
print(line.trim_matches(" ").len)
```

```output
key = value
6 true
11
```

…and reaching into the other file is refused at compile time, naming the allocating call it found:

```sysl
no alloc

import sysl.text.join

var words = ["a", "b"]

print(join(words[..], "-"))
```

```error
this reaches 'sysl.buf.Buf.extend.byte', which makes heap storage, and this module declared 'no alloc'
```

That diagnostic names `sysl.buf`, three calls down, because [`alloc` is checked on what a module
*calls*](/reference/modules/) rather than on which modules it depends on — the standard library is
exactly why. Inferring it per module would put the whole of `sysl.text` on one side of a line that
runs through the middle of it.

## Gathering text: `StrBuilder`

`a + b` allocates a fresh buffer every time, so gathering *n* pieces that way copies everything it
has so far on every step. A builder keeps one growable buffer and pays for each piece once.

```sysl
import sysl.text.str_builder

var b = str_builder()

b.push("n=")
b.push_int(42)
b.push(" ok=")
b.push_bool(true)
b.push(" x=")
b.push_real(1.5)
b.push_char('!')

print(b.len)
print(b.finish())
```

```output
19
n=42 ok=true x=1.5!
```

**Every way in carries the guarantee.** `push` takes a `string`, `push_char` takes a `char`, and the
four renderers take a number or a `bool` — UTF-8 is closed under appending any of them, so `finish`
hands back a plain `string` rather than something a caller has to validate.

**That is why a builder is not a [`Writer`](/library/core/).** A public `write` taking a `[]u8` would
be `from_utf8_unchecked` with a longer name and none of its greppability. The two shapes look
interchangeable and are not: a `Writer` is a sink for bytes and a builder is a producer of text.

**`push_int`, `push_uint`, `push_real` and `push_bool` exist so that gathering a number costs no
allocation.** The spelling without them is `push(str(n))`, which builds a whole reference-counted
`string` — a heap object with a refcount and a deallocation hook — copies its bytes out, and drops
it, for a value whose text is a couple of dozen bytes and is wanted only inside this buffer. A stack
array and one `snprintf` is the same rendering with none of that.

They agree with `str` to the byte, and that is the property that makes the cheap path a *substitute*
rather than a second rendering: a program that builds half a line with a builder and half with an
interpolation must not be able to tell which half a number came through. `push_real` is `%g` for the
same reason.

They take `long` and `ulong` rather than one member per width — the bargain the `print` family makes,
for the same want of overloading. The difference is that `print` has the compiler widening its
arguments and a member cannot:

```sysl
import sysl.text.str_builder

var b = str_builder()
var k: int = 42

b.push_int(k)
```

```error
'n' of 'sysl.text$StrBuilder.push_int' is long, but int was given
```

So a narrower value is written `b.push_int(long(k))` — and a caller who would rather not is
describing `push(str(k))`, which still works, still costs the allocation, and still saves the
quadratic copying that made a builder worth having. A value of any other type is pushed that way too.

`str_builder_with_capacity(n)` starts one with room, skipping the reallocate-and-copy at each
doubling on the way up to `n`. It is a guess and nothing depends on it: too small and the buffer
grows the way it always does, too large and the slack goes with the rest. `join` uses it, because
`join` can compute the answer's length before it starts.

**`finish` copies rather than lending**, so a builder may go on being appended to and the string
already taken out of it does not change. And `len` is a property rather than a method, which the
compiler will point out:

```sysl
import sysl.text.str_builder

var b = str_builder()

b.push("hello")

print(b.len())
```

```error
'len' is a property of 'sysl.text$StrBuilder' — read it as 'value.len', without '()'
```

## Reading a value back: the parsers

```sysl
enum ParseError
    Empty
    BadDigit(at: usize)
    Overflow
    BadBase(base: int)

parse_bool(s: string) -> Result[bool, ParseError]
parse_int(s: string) -> Result[int, ParseError]
parse_long(s: string) -> Result[long, ParseError]
parse_uint(s: string) -> Result[uint, ParseError]
parse_ulong(s: string) -> Result[ulong, ParseError]
parse_real(s: string) -> Result[real, ParseError]

parse_int_base(s: string, base: int) -> Result[int, ParseError]
parse_long_base(s: string, base: int) -> Result[long, ParseError]
parse_ulong_base(s: string, base: int) -> Result[ulong, ParseError]
```

`str(x)` renders and nothing read back, which is a gap a program feels immediately — an argument, a
configuration field and a number in a file are all text. The digits are easy; the library exists for
the edges.

```sysl
import sysl.text.{parse_int, parse_int_base, parse_long, parse_ulong, parse_bool, parse_real}

print(parse_int("42").unwrap(), parse_int("-42").unwrap())
print(parse_int_base("ff", 16).unwrap())
print(parse_long("-9223372036854775808").unwrap())
print(parse_ulong("18446744073709551615").unwrap())
print(parse_bool("true").unwrap(), parse_real("1.5").unwrap())
```

```output
42 -42
255
-9223372036854775808
18446744073709551615
true 1.5
```

**`long`'s most negative value comes back with no special case, and that is the whole trick.** The
signed range is asymmetric — the magnitude of `MIN` is one larger than the largest positive value —
so a parser that builds a magnitude and negates at the end cannot represent it at any point. These
accumulate on the **negative** side, which covers the entire range with one path and leaves only a
*positive* result of `MIN`'s magnitude to refuse, just before the final negation.

**The overflow test happens before the arithmetic that would overflow**, because integer arithmetic
[wraps rather than trapping](/reference/types/): a product that has already wrapped is not a number a
later comparison can learn anything from.

**The unsigned range is not the signed one with the sign removed.** `"ffffffffffffffff"` is an
ordinary 64-bit mask that overflows every signed parse there is, so a systems language needs the
unsigned direction to read back what its own literals are written in. No sign is accepted there, not
even `+`: a leading `-` on an unsigned value is a question with no good answer.

### Refusing, and saying why

`ParseError`'s four cases are separated by what a caller would *do* about them rather than by
taxonomy — an empty field is often a default, a bad digit is a message to a user, an overflow is a
wider type or a refusal, and a bad base is the program's own mistake rather than the input's. Each
renders through `Display`, so a refusal can be printed without being matched on:

```sysl
import sysl.text.{parse_int, parse_int_base, parse_long, parse_bool, parse_real}

print(parse_int("").unwrap_err())
print(parse_int("12abc").unwrap_err())
print(parse_int("2147483648").unwrap_err(), parse_long("2147483648").unwrap())
print(parse_int_base("10", 99).unwrap_err())
print(parse_bool("True").unwrap_err())
print(parse_real("1.5x").unwrap_err())
```

```output
no digits to read
not a digit at byte 2
value too large for its type 2147483648
99 is not a base between 2 and 36
not a digit at byte 0
not a digit at byte 3
```

Four things are visible there.

**`BadDigit` carries the offset**, for the reason `Utf8Error` does: a message naming *where* is worth
writing and cannot be reconstructed afterwards. `"12abc"` fails at byte 2.

**Trailing garbage is refused.** `"12abc"` is not `12`, and `"1.5x"` is not `1.5` — which for the
float means checking C's end pointer, since `strtod` on its own stops where it likes and reports
success.

**`Overflow` is relative to the type asked for.** `2147483648` is a perfectly good `long` and is not
an `int`, so `parse_int` refuses the same text `parse_long` accepts. What the caller asked for is an
`int`, and there is no honest `int` to hand back.

**`parse_bool` accepts exactly the two spellings `str` produces.** `"True"`, `"yes"` and `"1"` are
each somebody's convention and none is this library's; a program wanting one writes three lines that
read as the policy they are.

`parse_real` goes to C's `strtod` for the reason the float half of `str` goes to `snprintf`:
correctly rounded decimal-to-binary conversion is hard to get right, easy to get subtly wrong, and
the two directions must agree or a value will not survive being written and read back. It costs one
allocation, since C reads a NUL-terminated pointer and a `string` carries a length instead.

### A parse in a `Result` is not the value

```sysl
import sysl.text.parse_int

print(parse_int("41") + 1)
```

```error
'+' needs matching types, got sysl.Result[int, sysl.text.ParseError] and int
```

That is the type doing its job. The usual shape is to let `?` carry the refusal outward:

```sysl
import sysl.text.{split, parse_int, ParseError, Search}

read_port(line: string) -> Result[int, ParseError]
    var parts = split(line, "=")
    var n = parse_int(parts[parts.len - 1usize].trim())?

    Ok(n)
end read_port

print(read_port("port = 8080").unwrap())
print(read_port("port = eighty").unwrap_err())
```

```output
8080
not a digit at byte 0
```

## The C boundary

A `string` carries a length and has no terminator, so passing one to C is an explicit, allocating
conversion — and `CString` owns the copy, because a language with no manual free has to say who frees
it:

```sysl
import sysl.text.{cstring, from_cstring}

extern strlen(p: *u8) -> usize

var cs = cstring("hé")

print(cs.len, strlen(cs.ptr))

from_cstring(cs.ptr) match
    Ok(t) -> print("back", t, t.len)
    Err(e) -> print("refused", e.offset)
```

```output
3 3
back hé 3
```

`cs.ptr` is the `*u8` an `extern` taking a `char *` is given, and `cs.len` is the byte length **not**
counting the terminator, so that it agrees with the `s.len` it came from. The pointer carries an
ordinary `*T`'s rule: it is valid while the `CString` is held.

**The hazard the explicitness exists for survives, and it is worth stating as an equation:**

```sysl
import sysl.text.cstring

extern strlen(p: *u8) -> usize

var cs = cstring("a\0b")

print(cs.len, strlen(cs.ptr))
```

```output
3 1
```

Both numbers are right. sysl counts three bytes because carrying a length is the whole point; C stops
at the interior NUL. Neither can be made to be the other, which is exactly why a conversion is
written rather than inferred.

`from_cstring` is the other direction — a `string` copied out of the NUL-terminated bytes a C
function handed back, which every binding needs the moment a C library reports anything in words. The
copy is not optional: the bytes belong to C, a static buffer it may reuse on the next call or storage
the caller is about to free, and a `string` outlives the call that produced it. But it is **one**
copy, and it is `from_utf8`'s — slicing the `*u8` names the bytes where C left them without taking
any hold on them, and validation is what turns that borrowed run into a `string` owning its own.

It is fallible for the same reason `from_utf8` is: nothing about a `char *` promises well-formed
UTF-8, and a C library reporting in a non-UTF-8 locale is the ordinary case rather than a corrupt one.

For a **literal**, none of this is needed — `c"…"` is a plain `*u8` pointing at read-only data, with
no allocation and no copy. That form is on [foreign functions](/reference/ffi/).

## Reaching the module

Everything above the free surface needs an `import`, and a trait needs to be *in scope* for its
members to be reachable — which the compiler says in those words:

```sysl
import sysl.text.split

print(split("a b", " ").len)
print("  x  ".trim())
```

```error
string has 'trim' from sysl.text.Search, and that trait is not in scope here — import it to reach the member
```

`import sysl.text.Search` is the fix, and it is worth reading as a feature rather than a hoop: a
member arriving on `string` from three modules away, with nothing in the file saying so, is how a
program becomes unreadable. The [import forms](/reference/modules/) are the same ones every module
uses.

One conversion is easy to write backwards, since text and bytes are so nearly the same thing here:

```sysl
import sysl.text.from_utf8

print(from_utf8("hi").unwrap())
```

```error
'b' of 'sysl.text.from_utf8' is []const byte, but string was given
```

`from_utf8` goes **bytes to text**. The other direction is `s.bytes`, is free, and needs no function
at all — a `string` is already a validated `[]u8`, so there is nothing to check on the way out.
(Note that the compiler renders `u8` as `byte` in diagnostics.)

---

Next: [`sysl.buf`](/library/buf/) — the growable sequence everything here builds on.
