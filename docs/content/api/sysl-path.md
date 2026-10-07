---
title: sysl.path
layout: api-module
headingShift: 0
slugStyle: github
module: sysl.path
summary: "Path handling that is decided by the string alone."
---

**This module requires nothing, and that is the whole reason it is not part of `sysl.fs`.** A
requirement is module-wide, so one `getcwd` filed beside `join` would take path handling away from
every program that has no operating system to ask -- a build tool cross-compiling for a board, a
parser handling paths as data, anything under a capability clause that cannot import `os`. It is
the same argument `sysl.posix.tty`, `sysl.posix.rand` and `sysl.posix.time` are each a module for,
arriving from a fourth direction.

**Nothing here touches the filesystem**, which is not a limitation to work around but the property
that makes the module usable at all. Every answer below is a fact about the text; a program that
wants a fact about the disk wants `sysl.fs`.

## `normalize` IS NOT `canonicalize`, AND THE DIFFERENCE IS A SECURITY BUG WHEN IT IS NOT KNOWN

`normalize` resolves `.` and `..` by string surgery. `sysl.fs.canonicalize` asks the filesystem,
which follows every symbolic link. Where `a/b` is a link to `/elsewhere`, `a/b/../c` normalizes to
`a/c` and canonicalizes to `/c` -- two different files. Every path-traversal vulnerability in the
literature is a program that normalized user input lexically and believed the result named what
the filesystem would name. **Reach for `sysl.fs.canonicalize` whenever the answer decides access
to something.**

## It is POSIX, and says so

One separator, `/`, and no drive letters. That is the whole of the platform question for every
target this compiler has, and stating it is cheaper than an abstraction over a Windows nobody here
can test. A path is a plain `string` rather than a type of its own, which is what `sysl.fs` takes
everywhere and what lets the whole of `sysl.text` apply to one for free.

## Index

[`components`](#components) [`extension`](#extension) [`file_name`](#file_name) [`is_absolute`](#is_absolute) [`is_relative`](#is_relative) [`is_valid_pattern`](#is_valid_pattern) [`join`](#join) [`join_all`](#join_all) [`matches`](#matches) [`normalize`](#normalize) [`parent`](#parent) [`relative_to`](#relative_to) [`separator`](#separator) [`stem`](#stem)

## Functions

### `components`

```sysl
components(p: string) -> []string
```

The pieces between the separators, with empty ones dropped.

A leading separator is **not** a component -- whether a path starts at the root is `is_absolute`'s
question, and answering it here as well would make every caller filter for a piece that is not a
name. `.` and `..` are components like any other, since removing them is what `normalize` is for
and doing it silently here would leave no way to see a path as written.

### `extension`

```sysl
extension(p: string) -> Option[string]
```

What follows the final dot in the file name, where there is one to follow.

A name that begins with a dot and has no other is not extended -- `.bashrc` is a name rather than
an empty stem with an extension of `bashrc`, which is what every filesystem's own tooling means by
it. A name ending in a dot has an extension of nothing, which is the honest reading of a name that
says it is extended and then does not say with what.

### `file_name`

```sysl
file_name(p: string) -> Option[string]
```

The last component, or nothing where the path names no file.

`/` has none, and neither has the empty path. `.` and `..` have none either: they name a
directory by where it is rather than by what it is called, so answering with the dots would hand
back something no caller can use as a name.

### `is_absolute`

```sysl
is_absolute(p: string) -> bool
```

Whether the path starts at the root.

### `is_relative`

```sysl
is_relative(p: string) -> bool
```

### `is_valid_pattern`

```sysl
is_valid_pattern(pattern: string) -> bool
```

Whether `pattern` is one `matches` can act on: every `[` closed, and no `\` at the end.

It says nothing about whether the pattern will match anything, which is a different question and
has no answer without a path. What it is for is telling a person who typed a pattern that they
left a bracket open, rather than letting them read an empty result as "nothing here".

### `join`

```sysl
join(a: string, b: string) -> string
```

Two paths laid end to end.

**An absolute second path replaces the first rather than being appended to it**, which is the one
case a hand-rolled `a + "/" + b` gets wrong and the reason this function is worth having. Joining
`"/etc"` onto `"/home/me"` answers `"/etc"`: the second path already says where it starts, so
putting the first in front of it would name somewhere neither of them meant.

An empty piece on either side contributes nothing, and exactly one separator is written between
two non-empty ones however many the first one ended with.

### `join_all`

```sysl
join_all(parts: []const string) -> string
```

Every piece joined in turn, which is what building a path out of parts actually looks like:
`join_all([home, ".config", "thing"])`. An absolute piece discards everything before it, by
`join`'s rule applied at each step.

### `matches`

```sysl
matches(pattern: string, p: string) -> bool
```

Whether `p` matches `pattern`.

```
matches("*.sysl", "main.sysl")    // true
matches("*.sysl", "src/x.sysl")   // false -- `*` does not cross a separator
matches("**", "src/a/b.sysl")     // true -- `**` covers whole components
matches("*.sysl", ".hidden.sysl") // false -- a leading dot is not matched by `*`
```

**The whole path is matched, not a prefix of it**, so a pattern has to account for every
component. That is what makes `**` necessary rather than convenient: `*.sysl` against
`src/x.sysl` is `false`, and the pattern that means *anywhere* is two stars, a separator and
`*.sysl`.

**That last pattern cannot be written inside this comment, which is a fact about the lexer rather
than about globs.** A block comment in sysl **nests**, so a `/` followed by a `*` opens one and a
`*` followed by a `/` closes one -- and a glob pattern is made of exactly those characters. The
module's own comment is written with `//`, where neither means anything, and that is where the
table spells every form out.

**A malformed pattern matches nothing rather than reporting anything.** An unclosed `[` and a
trailing `\` are the two, and both answer `false` for every path -- which is what a filter wants,
since the alternative is a `Result` at a call site whose whole shape is a `bool`.
`is_valid_pattern` is for the caller who took the pattern from a person and wants to say so
before running a walk that will silently find nothing.

### `normalize`

```sysl
normalize(p: string) -> string
```

`.` and `..` resolved by string surgery, with no disk touched. **Read the warning at the head of
this module before using it to decide access to anything.**

A `..` at the root is dropped, because the root has no parent and a path may not climb above it. A
`..` at the front of a *relative* path is kept, since there is no way to know what it would have
cancelled -- which is the same reason a relative path cannot be normalized into an absolute one.

Duplicate and trailing separators go, an absolute path that cancels down to nothing is `/`, and a
relative one that cancels down to nothing is `.` -- there is no such path as the empty one, and
answering with it would hand back something no other function here accepts.

### `parent`

```sysl
parent(p: string) -> Option[string]
```

Everything above the last component, or nothing where there is nothing above it.

`/a/b` has `/a`, `/a` has `/`, and `/` has nothing -- the root is not above anything. A relative
path with one component has nothing either: `a` sits in a directory this module cannot name,
since naming it would mean asking where the program is.

### `relative_to`

```sysl
relative_to(p: string, base: string) -> Option[string]
```

One path expressed against another, or nothing where it cannot be.

Both are normalized first, so `relative_to("/a/./b", "/a")` is `b`. It answers nothing when the
two do not agree about starting at the root -- an absolute path has no expression against a
relative one without knowing where the program is, which is a question for `sysl.fs` -- and when
the base climbs above what is written, since a `..` in the base names a directory whose name is
not in the string.

A path expressed against itself is `.`, which is `normalize`'s answer for a path that cancels to
nothing and is what every caller then joins onto.

### `separator`

```sysl
separator() -> u8
```

The byte that separates one component from the next.

### `stem`

```sysl
stem(p: string) -> Option[string]
```

The file name with its extension and the dot before it taken off. `/a/b.tar.gz` has `b.tar`,
since the extension is what follows the *final* dot.
