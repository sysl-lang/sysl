---
title: sysl.regex
layout: api-module
headingShift: 0
slugStyle: github
module: sysl.regex
summary: "Matching text against a pattern: compile one, then find, replace or split with it."
---

Compiling is separated from matching because it is the expensive half and a pattern is
usually used more than once. Every span a match reports is a **byte offset into the input**
and is always on a character boundary, so `s[a..<b]` takes one without a boundary check
failing; a group that took no part in the match has no span rather than an empty one.

This module is the surface. `parse`, `compile` and `vm` beside it are the engine.

## Index

[`max_program`](#max_program) [`char_class`](#char_class) [`compile`](#compile) [`compile_pattern`](#compile_pattern) [`describe`](#describe) [`dump`](#dump) [`exec`](#exec) [`exec_from`](#exec_from) [`named_class`](#named_class) [`parse`](#parse) [`parse_counted`](#parse_counted) [`regex`](#regex) [`show`](#show) [`CharClass`](#charclass) [`CharRange`](#charrange) [`Inst`](#inst) [`Match`](#match) [`NamedClass`](#namedclass) [`Node`](#node) [`Program`](#program) [`Regex`](#regex-1) [`RegexError`](#regexerror)

## Constants

### `max_program`

```sysl
const max_program: u32 = 100000
```

## Functions

### `char_class`

```sysl
char_class() -> CharClass
```

### `compile`

```sysl
compile(root: &Node, groups: int) -> Result[Program, RegexError]
```

### `compile_pattern`

```sysl
compile_pattern(pattern: string) -> Result[Program, RegexError]
```

### `describe`

```sysl
describe(e: RegexError) -> string
```

### `dump`

```sysl
dump(p: Program) -> string
```

### `exec`

```sysl
exec(prog: Program, input: string) -> Option[[]int]
```

### `exec_from`

```sysl
exec_from(prog: Program, input: string, from: usize) -> Option[[]int]
```

### `named_class`

```sysl
named_class(name: string) -> Option[NamedClass]
```

### `parse`

```sysl
parse(pattern: string) -> Result[&Node, RegexError]
```

### `parse_counted`

```sysl
parse_counted(pattern: string) -> Result[(&Node, int), RegexError]
```

### `regex`

```sysl
regex(pattern: string) -> Result[Regex, RegexError]
```

### `show`

```sysl
show(n: &Node) -> string
```

## Types

### `CharClass`

```sysl
struct CharClass
    ranges: &Buf[CharRange]
    names: &Buf[NamedClass]
    negated: bool
```

| Member | Signature | Description |
|---|---|---|
| `admits` | `admits(self, c: char) -> bool` |  |

### `CharRange`

```sysl
struct CharRange
    lo: u32
    hi: u32
```

### `Inst`

```sysl
enum Inst
    MatchChar(ch: char)
    MatchAny
    MatchSet(of: CharClass)
    Accept
    Jump(to: usize)
    Split(first: usize, second: usize)
    Save(slot: usize)
    AssertStart
    AssertEnd
```

### `Match`

```sysl
struct Match
    src: string
    slots: []int
```

| Member | Signature | Description |
|---|---|---|
| `group_count` | `group_count(self) -> usize` |  |
| `span` | `span(self, i: usize) -> Option[(usize, usize)]` |  |
| `group` | `group(self, i: usize) -> Option[string]` |  |
| `text` | `text(self) -> string` |  |
| `start` | `start(self) -> usize` |  |
| `end` | `end(self) -> usize` |  |

### `NamedClass`

```sysl
enum NamedClass
    Alpha
    Digit
    Alnum
    Upper
    Lower
    Space
    Blank
    Print
    Graph
    Cntrl
    Punct
    XDigit
```

### `Node`

```sysl
enum Node
    Empty
    Lit(ch: char)
    AnyChar
    AtStart
    AtEnd
    InSet(of: CharClass)
    Seq(left: &Node, right: &Node)
    Alt(left: &Node, right: &Node)
    Star(inner: &Node)
    Plus(inner: &Node)
    Quest(inner: &Node)
    Group(inner: &Node, index: int)
```

### `Program`

```sysl
struct Program
    code: &Buf[Inst]
    groups: int
    over: bool
```

| Member | Signature | Description |
|---|---|---|
| `len` | `len(self) -> usize` |  |
| `at` | `at(self, pc: usize) -> Inst` |  |
| `slots` | `slots(self) -> usize` |  |
| `emit` | `emit(*self, i: Inst) -> usize` |  |
| `patch` | `patch(*self, pc: usize, i: Inst)` |  |

### `Regex`

```sysl
struct Regex
    pattern: string
    prog: Program
```

| Member | Signature | Description |
|---|---|---|
| `group_count` | `group_count(self) -> int` |  |
| `find` | `find(self, s: string) -> Option[Match]` |  |
| `find_at` | `find_at(self, s: string, from: usize) -> Option[Match]` |  |
| `is_match` | `is_match(self, s: string) -> bool` |  |
| `find_all` | `find_all(self, s: string) -> &Buf[Match]` |  |
| `replace_all` | `replace_all(self, s: string, with: string) -> string` |  |
| `split` | `split(self, s: string) -> &Buf[string]` |  |

### `RegexError`

```sysl
enum RegexError
    TrailingBackslash(at: usize)
    UnterminatedClass(at: usize)
    UnterminatedClassName(at: usize)
    UnknownClassName(at: usize, name: string)
    UnterminatedGroup(at: usize)
    UnmatchedParen(at: usize)
    UnterminatedInterval(at: usize)
    MissingIntervalCount(at: usize)
    BackwardsInterval(at: usize)
    BackwardsRange(at: usize)
    NothingToRepeat(at: usize)
    PatternTooLarge
```
