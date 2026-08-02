---
title: Regular expressions
summary: "`sysl.regex` — POSIX Extended Regular Expressions, matched by a Pike VM whose cost is the input length times the pattern length and never anything worse."
weight: 20
---

`sysl.regex` compiles a POSIX Extended Regular Expression and matches it against text. Two types are
the whole of the surface: a `Regex`, which is a compiled pattern, and a `Match`, which is what one
found.

```sysl
import sysl.regex.regex

var re = regex("([a-z]+)@([a-z.]+)").unwrap()

re.find("write to ed@example.com today") match
    Some(m) -> print(m.text(), "from", m.group(1).unwrap(), "at", m.group(2).unwrap())
    None -> print("no address")
```

```output
ed@example.com from ed at example.com
```

## What it costs

Matching is a [Pike VM](https://swtch.com/~rsc/regexp/regexp2.html) — Ken Thompson's 1968 NFA
simulation, extended by Rob Pike to carry capture positions. Every branch of the pattern is followed
at once rather than one at a time with backtracking, so the work is **the input length times the
pattern length**, and no pattern makes it worse than that.

That is a guarantee rather than a typical case. `(a|a)*b` against a run of `a`s with no `b` is the
standard demonstration: a backtracking engine has to try every way of splitting the run between two
identical alternatives, which at forty characters is a trillion attempts and will not finish.

```sysl
import sysl.regex.regex

var re = regex("(a|a)*b").unwrap()
var hay = ""

for _ in 0..<40 do hay += "a"

print(re.is_match(hay))
```

```output
false
```

The price of that guarantee is the feature backtracking buys and this cannot have: there are **no
backreferences**. `(a)\1` is not a pattern that matches a doubled character; `\1` is an escaped `1`.

The bound is on *matching*, and the other end needs bounding too. An interval is expanded into that
many copies of what precedes it, so intervals stack multiplicatively: `a{200}{200}{200}` is sixteen
characters and eight million instructions, and one more factor is a billion. A pattern is therefore
refused if it would lay out more than a hundred thousand — far past anything written on purpose, and
what keeps a pattern arriving from somewhere untrusted from exhausting memory before it ever runs.

```sysl
import sysl.regex.{regex, describe}

regex("a{200}{200}{200}") match
    Ok(_) -> print("compiled")
    Err(e) -> print(describe(e))
```

```output
the pattern expands past 100000 instructions
```

## Compiling

`regex` answers a `Result`, because a pattern is text and text can be wrong. Compiling is separated
from matching on purpose — it is the expensive half, and a program matching in a loop should compile
once outside it.

```sysl
import sysl.regex.{regex, describe}

regex("(a") match
    Ok(_) -> print("compiled")
    Err(e) -> print(describe(e))
```

```output
a group is never closed, opened at 0
```

Every refusal carries the offset in the pattern where the trouble is, and `RegexError` is an enum, so
a program that wants to treat one case differently from another can match on it rather than reading
a message.

## What a match reports

Spans, as **byte offsets into the input** — always on character boundaries, so one is directly what
`s[a..<b]` takes.

```sysl
import sysl.regex.regex

var m = regex("[0-9]+").unwrap().find("order 1234 shipped").unwrap()

print(m.start(), m.end(), m.text())
```

```output
6 10 1234
```

A capture group is asked for by number, and the answer is an `Option`. That is not caution: there is
a real difference between a group that took no part in the match and one that matched the empty
string, and a span alone cannot tell them apart.

```sysl
import sysl.regex.regex

var either = regex("(a)|(b)").unwrap().find("b").unwrap()
var empty = regex("(a*)b").unwrap().find("b").unwrap()

print(either.group(1).is_some(), either.group(2).unwrap())
print(empty.group(1).is_some(), s"[${empty.group(1).unwrap()}]")
```

```output
false b
true []
```

Group 0 is the whole match and always took part, which is why `text()` unwraps it for you.

## Walking a text

`find_all` gives every match, left to right and not overlapping.

```sysl
import sysl.regex.regex

var words = regex("[a-z]+").unwrap()
var all = words.find_all("the quick brown fox")

for i in 0usize..<all.len()
    print(all.at(i).start(), all.at(i).text())
```

```output
0 the
4 quick
10 brown
16 fox
```

A pattern that can match the empty string would otherwise be found at the same place for ever, so
the walk resumes one character past an empty match. `a*` over `"bb"` therefore finds three: before
each `b`, and after the last.

```sysl
import sysl.regex.regex

print(regex("a*").unwrap().find_all("bb").len())
```

```output
3
```

**The anchors keep speaking about the whole text**, not about where a search resumed. That is why
`find_all` takes a starting position rather than searching a shortened input — the obvious way to
write it would hand `^` a fresh beginning to match against at every step.

```sysl
import sysl.regex.regex

print(regex("^ab").unwrap().find_all("abab").len())
```

```output
1
```

## Replacing and splitting

Both are written in terms of `find_all`, so all three agree about what the matches are.

```sysl
import sysl.regex.regex

var assign = regex("([a-z]+)=([0-9]+)").unwrap()

print(assign.replace_all("x=1, yy=22", "\\2:\\1"))
```

```output
1:x, 22:yy
```

The replacement is not a pattern, but it may name the groups the match found: `\1` through `\9` for
those groups, `\0` for the whole match, `\\` for a backslash. A group that took no part contributes
nothing rather than the two characters that named it.

Note the doubling. `\2` is not one of sysl's [string escapes](/reference/lexical/) and the compiler
refuses it, so a replacement naming group 2 is written `"\\2"` — the same doubling a pattern needs
for `"\\."`.

`split` answers the pieces between the matches, empty ones included — a splitter that drops them
loses a field.

```sysl
import sysl.regex.regex

var parts = regex(",").unwrap().split("a,b,,c")

print(parts.len())
for i in 0usize..<parts.len() do print(s"[${parts.at(i)}]")
```

```output
4
[a]
[b]
[]
[c]
```

## The pattern syntax

POSIX ERE, in full.

| form | means |
|---|---|
| `a` | the character itself |
| `.` | any one character, newline included |
| `^` `$` | the beginning and the end of the text |
| `[abc]` `[a-z]` `[^a-z]` | a bracket expression, a range, a negated one |
| `[[:alpha:]]` | a named class — see below |
| `(e)` | a capture group, numbered by where its `(` is |
| `e*` `e+` `e?` | zero or more, one or more, zero or one |
| `e{n}` `e{n,}` `e{n,m}` | exactly, at least, between |
| `ab` | `a` then `b` |
| `a\|b` | `a` or `b` |
| `\x` | the character `x`, ordinary whatever it usually means |

The twelve named classes are `alpha`, `digit`, `alnum`, `upper`, `lower`, `space`, `blank`, `print`,
`graph`, `cntrl`, `punct` and `xdigit`. Each is the corresponding member of
[`sysl.text.Ascii`](/library/), so each answers over the ASCII range and `false` above it.

```sysl
import sysl.regex.regex

var word = regex("[[:alpha:]][[:alnum:]_]*").unwrap()

print(word.find("  n42_ok!").unwrap().text())
```

```output
n42_ok
```

Two positional rules inside a bracket expression are worth knowing because they are the way to
include the awkward characters. A `]` **first** is an ordinary `]`, and a `-` **first or last** is an
ordinary `-`.

```sysl
import sysl.regex.regex

print(regex("[]-]+").unwrap().find("a]-]b").unwrap().text())
```

```output
]-]
```

A backslash inside a bracket expression is an ordinary character, as POSIX requires — so `[\t]` is
the two characters backslash and `t`, not a tab.

## Characters, not bytes

A `string` in sysl is UTF-8 by construction, and this engine matches over its characters. So `.` is
one character however many bytes it occupies, and the span it reports is one a slice can cut — where
a byte-stepping matcher would hand back half of a character.

```sysl
import sysl.regex.regex

var m = regex("..").unwrap().find("héllo").unwrap()

print(m.text(), m.end())
```

```output
hé 3
```

The same holds in a pattern: `[é-ü]` is a range between two characters, not between four bytes.

## Leftmost, then longest

POSIX asks for the match that begins earliest, and among those the one that runs longest. Both hold
here, and the second is where POSIX and Perl part company: given `a|ab` against `"ab"`, Perl takes
the first alternative that works and answers `a`.

```sysl
import sysl.regex.regex

print(regex("a|ab").unwrap().find("ab").unwrap().text())
```

```output
ab
```

**What is not implemented is POSIX's rule for the subexpressions.** The whole match is leftmost and
longest; a capture group inside it holds what the preferred path through the pattern gave it, which
is the greedy reading rather than the one POSIX derives for each group in turn. Go and Rust make the
same choice, and it shows on patterns like `(a|ab)(c|bcd)`.

## What is not here

- **Basic Regular Expressions.** `\(` opens a group in BRE and is a literal parenthesis here, which
  is the opposite convention. Only ERE is implemented.
- **Backreferences**, for the reason above: they are what makes matching NP-hard, and the whole point
  of this engine is that it cannot be made slow.
- **Lazy quantifiers.** `a*?` is not a lazy star; ERE has no such thing, and it reads as a star made
  optional again.
- **Flags** — no case-insensitive mode, no multiline mode. `[[:alpha:]]` and an explicit `\n` say
  both, at the cost of saying them.

## Looking at what a pattern became

The tree and the program are both public, and both render, for a caller asking why their pattern does
what it does.

```sysl
import sysl.regex.{parse, compile_pattern, show, dump}

print(show(parse("a(b|c)*").unwrap()))
print(dump(compile_pattern("a?").unwrap()))
```

```output
seq(lit(a), star(group1(alt(lit(b), lit(c)))))
0: save 0
1: split 2, 3
2: char a
3: save 1
4: accept
```
