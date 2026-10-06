---
title: Modules and the library
summary: A module is a directory, an import only shortens a name you could always write in full, and the standard library is one auto-imported module with ten submodules under it.
weight: 110
---

Every program on this site so far has been one file with no header, and that is not a special form —
it is a module that happens to be unnamed. This chapter is what happens when there is more than one.

## A module is a directory

The files under `oskit/arch/` make up the module `oskit.arch`; the files under `std/fs/` make up
`std.fs`. Every declaration in every file of the directory is a member of the one module, so
splitting a growing module into more files adds no new module and changes no import.

Each file states which module it contributes to, and the compiler checks the name against where the
file sits:

```sysl
module oskit.arch

halt()
    print("halted")
```

That is Scala's `package` and Go's directory-package, and it pays off directly for the target: an OS
subsystem *is* a directory — an arch layer, a server, a driver — so the module is the subsystem, and
the subsystem is the unit an importer depends on.

A file with no header at all is in the **anonymous root module**, whose name is the empty path.
Nothing can name it, so its declarations are visible to its own files and to nothing else — which is
the right way round for the place a program starts, and it is why a one-file program's names sit
exactly where they always did.

A top-level *statement* is not a declaration. A declaration is hoisted and belongs to the module as a
whole; a statement runs, and running happens in an order — so **one file of a program carries the
statements it runs**, and a second that carries any is an error naming both.

That file is the program's **entry file**, and its top level is a **body**: a `val` or `var` there is
a local initialized where it stands, and a function there reads the bindings above it.

A program may instead declare `main`, which is the other way of writing the place a program starts:

```sysl
main(args: []string)
    print("the work runs here, with", args.len, "argument")
```

```output
the work runs here, with 1 argument
```

**A program starts in one place**, so it writes one or the other and never both. What `main` gets at
that a statement cannot is **the arguments**: a statement has nowhere to receive them, because it is
not a call and has no parameter list.

A program in which no file carries a statement is a complete program that does nothing. That is what
a tree of pure declarations compiles to, which is what it should compile to — a library is not an
error.

## Imports

A public member is **always** reachable fully-qualified — `sysl.math.max(2, 7)` needs no import at
all. Nothing is required to *see* a member; an import exists only to shorten the reference:

```sysl
import sysl.math.{max, min}
import sysl.math as m

print(max(3, 9), min(3, 9))
print(m.max(2, 7))
print(sysl.math.max(1, 4))
```

```output
9 3
7
4
```

An import is a dotted path through the module tree, and **how the path ends decides what you get**:

```sysl
import sysl.math.max              // max(a, b)      — one member, unqualified
import sysl.math.{max, min}       // several
import sysl.math.*                // every public member
import sysl.math                  // math.max(a, b) — the module itself
import sysl.math.{max as bigger}  // a member, renamed
import sysl.math as m             // m.max(a, b)    — the module, renamed
```

Ending the path at a **member** binds that member under its own name, so calls to it lose their
qualifier entirely. The braces do that for several members at once, and `*` for every public member
of the module.

Ending it at the **module** binds the module's last name segment instead — so `sysl.math` becomes
`math`, and calls keep exactly one level of qualification. That is the self-documenting middle
ground: `math.max` says at the call site where the name came from without listing members up top,
where the wildcard says nothing and the explicit list has to be maintained.

`as` renames whatever the path ended at, member or module. It is what you reach for when two modules
offer the same name, and the only way to resolve that collision without writing the full path at
every use.

If you happen to know Scala 3, these are its import forms unchanged — including `as` and `*`.

Imports usually sit just below the header, but one may also appear **inside a block**, scoped to it,
for a name wanted in one function only.

Resolution is innermost-first: a local binding shadows an imported name, and the fully-qualified path
is always available to break a tie. Two wildcard imports offering the same name make an *unqualified*
use of it an error naming both — including when one of them is the standard library, which is
auto-imported into every file. That is deliberate: the alternative is a precedence tier that makes the
library quietly lose to whatever a program imported, which is the silent capture the error exists to
prevent.

## The standard library

**The library is a module, not a set of names threaded in beside your program.** `sysl` itself is
auto-imported into every file, which is why nothing so far has had to import anything to call
`print` — and what earns that is that it holds what the language desugars onto, which a program
cannot avoid needing. Everything else is a submodule you ask for by name, because a submodule is an
offer rather than part of the language.

| module | what is in it |
|---|---|
| `sysl` | `print`, `Option`, `Result`, `Display`, `Writer`, `Iterate`, the operator traits |
| `sysl.buf` | `Buf[T]`, the growable sequence, and `ByteSink` |
| `sysl.text` | `from_utf8`, `StrBuilder`, `Chars`, `CString` |
| `sysl.io` | `Reader`, `stdin()`, `lines()` |
| `sysl.fs` | files and paths — `read_text`, `write_bytes`, `exists`, `rename`, and `IoError` |
| `sysl.math` | `max`, `min`, `pi`, the float functions, the integer traits `Signed` and `Bits`, and the integer arithmetic above them — `pow`, `gcd`, `lcm`, `divmod`, `is_power_of_two`, `next_power_of_two` |
| `sysl.regex` | POSIX Extended Regular Expressions — `regex`, `Regex`, `Match` |
| `sysl.sync` | `Atomic[T]`, `SpinLock`, and the five memory orderings — requires nothing |
| `sysl.posix.threads` | `spawn`, `Thread.join`, `yield_now`, and `Mutex[T]` |
| `sysl.args` | command-line options — `Scan`, `Cli`, and `args_of` for a raw `argv` |
| `sysl.sys` | the platform seam — what a freestanding target replaces |

The split is one rule: **what a program cannot avoid needing arrives free, and what it has to ask for
it asks for.** An array literal and a `for` loop are in the language, so nothing imports them. A
sequence that grows is a thing a program decides it wants, so it says so:

```sysl
import sysl.buf.{Buf, buf}
import sysl.text.str_builder
import sysl.math.max

var widths: &Buf[int] = buf()

widths.push(3)
widths.push(11)
widths.push(7)

var widest = 0

for i in 0..<widths.len() do widest = max(widest, widths[i])

var b = str_builder()

b.push("widest of ")
b.push(str(widths.len()))
b.push(" is ")
b.push(str(widest))

print(b.finish())
```

```output
widest of 3 is 11
```

Nothing there is a language feature. `Buf` is ordinary sysl over a slice it replaces when it runs
out; `StrBuilder` is ordinary sysl over a `Buf[u8]`; and both are importable rather than free because
a program that wants neither should link neither.

A module may also narrow itself. `@no_alloc` in the header says the module reaches no allocator, and
`requires os` says it needs one — `sysl.fs` is `requires os` because a filesystem is something the
environment either has or does not, and everything under `sysl.posix` requires posix. The atomics
live apart from the threads for exactly this reason: `sysl.sync` requires **nothing**, so a kernel
that has given up both its allocator and its operating system can still import a spinlock. A
freestanding target importing one of the others is told so **at the import** rather than deep in code
generation. [The reference](/reference/modules/) has the clause and how it propagates.

`sysl.io` is the one that needs a word, because it is where the iteration protocol earns its keep:

```sysl
import sysl.io.{stdin, lines}

var src = stdin()

for line in lines(&src)
    print("read:", line)
```

`lines` hands back a cursor, and `for` walks anything implementing `Iterate` — so the loop reads a
line at a time out of a 4 KiB chunk rather than pulling the file into memory. A `Reader` is a trait
with one method, so the same loop reads a socket, a ring buffer, or a test fixture, and a freestanding
target substitutes one body.

A module is compiled once and linked, which is what the acyclic import graph buys. The standard
library itself is an artifact — a real `ar` archive — and the compiler builds it for you when nothing
usable is at the default path, announced on stderr and in well under a second. It lives in your cache
directory under a fingerprint of the library it was built from, so every project on the machine shares
one and a new compiler makes its own without disturbing the old. There is no bootstrap step to run and
none to remember.

---

Next: [contracts](/tour/contracts/) — types that carry a rule, and functions that state what they
require.
