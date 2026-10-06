---
title: The path module
summary: "`sysl.path` — joining, taking apart and normalizing a path by the string alone: `join`, `parent`, `file_name`, `extension`, `stem`, `normalize`, `relative_to`. It requires nothing, and `normalize` is not `canonicalize`."
weight: 51
---

**Every declaration in `sysl.path`, with its signature:** [the generated API page](/api/sysl-path/#index). This page is the argument — what the module is for, and how its pieces fit; that one is the list.

`sysl.path` answers everything about a path that is decided by the **string alone**. It opens no
file, asks no directory, and requires no capability at all.

```sysl
import sysl.path.{join, parent, file_name, extension, stem, normalize}

print(join("/home/me", "notes.txt"))
print(parent("/home/me/notes.txt"))
print(file_name("/home/me/notes.txt"))
print(stem("archive.tar.gz"), extension("archive.tar.gz"))
print(normalize("/home/me/./work/../notes.txt"))
```

```output
/home/me/notes.txt
Some(/home/me)
Some(notes.txt)
Some(archive.tar) Some(gz)
/home/me/notes.txt
```

## It is not part of `sysl.fs`, and the reason is the capability

[`sysl.fs`](/library/fs/) requires `os`. Lexical path handling requires nothing — it touches no
syscall; it is string manipulation whose *rules* happen to be per-platform, which is a different
thing from needing the platform to run.

**A requirement is module-wide.** One `getcwd` filed beside `join` would take the whole of path
handling away from every program that has no operating system to ask: a build tool cross-compiling
for a board, a parser handling paths as data, anything under a capability clause that cannot import
`os`. That is the same shape [`sysl.posix.tty`](/library/term/) is a module for — one `isatty`
beside the escape sequences would have cost the allocator-free programs the forty constants — and
[`sysl.posix.rand`](/library/rand/), and `sysl.posix.time`.

**And there is a second reason, which is the one a reader feels.** A module loader resolves an
import against the file that named it, and when that fails it has to say *which file* it could not
read. `cannot read tests/mods/not_here.sl` is only writable because resolution never touched the
disk — a resolver that asked the filesystem has to fail before it has a name to put in the message.
Lexical is not a weaker answer here; it is the one that keeps the diagnostic.

## `join` — an absolute second path replaces rather than appends

This is the case a hand-rolled `a + "/" + b` gets wrong, and the reason the function is worth having.
The second path already says where it starts, so putting the first in front of it names somewhere
neither of them meant. Exactly one separator is written between two non-empty pieces, however many
the first one ended with.

```sysl
import sysl.path.{join, join_all}

print(join("/home/me", "notes"))
print(join("/home/me///", "notes"))
print(join("/home/me", "/etc/passwd"))
print(join("", "notes"), join("notes", ""))
print(join_all(["/home/me", ".config", "thing"]))
```

```output
/home/me/notes
/home/me/notes
/etc/passwd
notes notes
/home/me/.config/thing
```

## Taking one apart

`parent`, `file_name`, `extension` and `stem` each answer an `Option`, because each has a path it has
no answer for: the root is above nothing, and `/`, `.` and `..` name no file.

```sysl
import sysl.path.{parent, file_name, extension, stem, components}

print(parent("/a"), parent("/"), parent("a"))
print(file_name("/a/b/"), file_name("."), file_name("/"))
print(extension(".bashrc"), stem(".bashrc"))
print(components("//a///b//"))
```

```output
Some(/) None None
Some(b) None None
None Some(.bashrc)
[a, b]
```

**A name that begins with a dot and has no other is a name, not an empty stem with an extension.**
That is what every filesystem's own tooling means by it, and it is why `.bashrc` has no extension
while `.tar.gz` has `gz`.

**A leading separator is not a component.** Whether a path starts at the root is `is_absolute`'s
question, and answering it here as well would make every caller filter for a piece that is not a
name.

## `normalize` is **not** `canonicalize`

These give different answers, and the difference is a security bug when it is not understood.

- **`normalize` is lexical.** `a/b/../c` becomes `a/c` by string surgery, with no disk touched.
- **[`sysl.fs.canonicalize`](/library/fs/) asks the filesystem** — it follows every symbolic link and
  answers the path the file system agrees on.

Where `a/b` is a symbolic link to `/elsewhere`, `a/b/../c` normalizes to `a/c` and canonicalizes to
`/c` — **two different files**. Every path-traversal vulnerability in the literature is a program
that lexically normalized user input and believed the result named what the filesystem would name.

**Reach for `canonicalize` whenever the answer decides access to something.** Reach for `normalize`
when the answer is about the path as written.

```sysl
import sysl.path.normalize

print(normalize("/a/./b/../c"))
print(normalize("/../a"))
print(normalize("../a"))
print(normalize("a/../../b"))
print(normalize("a/.."), normalize(""))
```

```output
/a/c
/a
../a
../b
. .
```

Three of those are the cases implementations disagree about, and each is a decision:

- **a climb at the root disappears**, because the root has no parent;
- **a climb at the front of a relative path stays**, because there is no way to know what it would
  have cancelled — which is the same absence read from the other side, and is why a relative path
  never normalizes into an absolute one;
- **a path that cancels to nothing is `.`, never the empty string.** There is no such path as the
  empty one, and answering with it would hand back something no other function here accepts.

## `relative_to` — one path expressed against another

```sysl
import sysl.path.relative_to

print(relative_to("/a/b/c", "/a"))
print(relative_to("/a/b", "/a/c"))
print(relative_to("/a/b", "/a/b"))
print(relative_to("/a/b", "a"))
```

```output
Some(b/c)
Some(../b)
Some(.)
None
```

Both are normalized first, and the two refusals are both about a name that is not in the strings: an
absolute path has no expression against a relative one without knowing where the program is, and a
`..` in the base names a directory whose name nothing here can see.

**Joining the answer back onto the base gives the path again**, which is the property that makes it
worth having.

## Resolving an import against the file that named it

The shape a module loader writes, and the one these functions exist for:

```sysl
import sysl.path.{join, parent, normalize}

var resolved = (from: string, rel: string) -> normalize(join(parent(from).unwrap_or(""), rel))

print(resolved("tests/mods/main.sl", "./util.sl"))
print(resolved("tests/mods/deep/nested.sl", "../util.sl"))
print(resolved("main.sl", "./util.sl"))
print(resolved("tests/mods/main.sl", "/tmp/util.sl"))
```

```output
tests/mods/util.sl
tests/mods/util.sl
util.sl
/tmp/util.sl
```

**`parent` answers nothing for a bare file name**, which is why the `unwrap_or("")` is there and is
worth writing out: a file with no directory in front of it sits somewhere this module cannot name,
and conflating that with the empty path is the disagreement two implementations of this would
otherwise have.

## Matching a glob

`matches(pattern, p)` is Go's `filepath.Match` plus a globstar, over bytes and allocating nothing:

| | |
|---|---|
| `*` | any run of bytes not containing `/` |
| `?` | one byte that is not `/` |
| `[abc]`, `[a-z]` | one byte from the set |
| `[!a-z]`, `[^a-z]` | one byte not from it — both spellings, since shells write `!` and Go writes `^` |
| `**` | a run of zero or more whole components, and only as a whole component |
| `\x` | the byte `x`, whatever it would otherwise mean |

```sysl
import sysl.path.matches

print(matches("*.sysl", "main.sysl"), matches("*.sysl", "src/main.sysl"))
print(matches("**/*.sysl", "src/deep/low.sysl"), matches("**/*.sysl", "top.sysl"))
print(matches("[a-z]?.txt", "a1.txt"), matches("src/**", "src/a/b"))
```

```output
true false
true true
true true
```

**A globstar covers zero components as readily as several**, so `**/*.sysl` reads as *at any depth*
rather than as *at least one directory down* — which is what bash answers and what somebody typing it
means.

**A leading `.` is hidden from `*`, `?` and a class.** That is the shell's rule and it is the safe
default for anything walking a tree: `*.sysl` does not match `.hidden.sysl`, `**` does not descend
into `.git`, and a pattern that means to reach them says so with a literal dot.

```sysl
import sysl.path.matches

print(matches("*", ".hidden"), matches(".*", ".hidden"))
print(matches("**", ".git/config"), matches("**", "src/config"))
```

```output
false true
false true
```

The alternative — matching them silently — makes every tool that uses this walk somebody's `.git` and
their editor's backup files, and the tool's author finds out from a bug report.

**A malformed pattern matches nothing rather than reporting anything.** An unclosed `[` and a trailing
`\` are the two, and both answer `false` for every path, which is what a filter wants — the
alternative is a `Result` at a call site whose whole shape is a `bool`. `is_valid_pattern` is for the
caller who took the pattern from a person and wants to say so before running a walk that will silently
find nothing.

```sysl
import sysl.path.{is_valid_pattern, matches}

print(is_valid_pattern("[abc]"), is_valid_pattern("[abc"))
print(matches("[abc", "a"))
```

```output
true false
false
```

**The whole path is matched, not a prefix of it**, so a pattern has to account for every component.
That is what makes `**` necessary rather than convenient.

`sysl.fs.walk` takes a pattern directly — [`walk(root).matching(pattern)`](/library/fs/#filtering-a-walk-by-a-glob) — which is where most callers meet this.

**What is not here:** brace expansion, `{a,b}`, which is a different operation — `*` and `?` narrow one
pattern and a brace turns one into several, so a caller wanting it loops over the alternatives.
Regular expressions are [`sysl.regex`](/library/regex/). Windows separators are not a thing here, and
the section below says why.

## It is POSIX, and says so

One separator, `/`, and no drive letters. That is the whole of the platform question for every target
this compiler has, and stating it is cheaper than a Windows abstraction nobody here can test.

A path is a plain `string` rather than a type of its own. A `Path` newtype would catch "I
concatenated a path and a filename" at compile time; a `string` composes with the whole of
[`sysl.text`](/library/text/) for free, and `sysl.fs` takes one everywhere.

---

Next: [the fs module](/library/fs/) — what a program can do to what is at the end of a path.
