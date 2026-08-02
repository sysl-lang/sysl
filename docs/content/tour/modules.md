---
title: Modules and the library
summary: A module is a directory, imports are Scala's, and the standard library is a tree of six.
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

## Where a program starts

A top-level *statement* is not a declaration. A declaration is hoisted and belongs to the module as a
whole; a statement runs, and running happens in an order — so **one file of a program carries the
statements it runs**, and a second that carries any is an error naming both.

A program may also declare `main`, which runs after those statements:

```sysl
print("initialization first")

main(args: []string)
    print("then main, with", args.len, "argument")
```

```output
initialization first
then main, with 1 argument
```

Both halves are real and neither replaces the other. The top-level statements are the program's
**initialization** — they are where a top-level `var` lives. `main` is what runs once that is done,
and what it gets at that a statement cannot is **the arguments**: a statement has nowhere to receive
them, because it is not a call and has no parameter list.

A program in which no file carries a statement is a complete program that does nothing. That is what
a tree of pure declarations compiles to, which is what it should compile to — a library is not an
error.

## Visibility

A top-level declaration is **public by default**. Two modifiers restrict it, and they are one keyword
with an optional scope:

| form | visible to |
|---|---|
| `private` | this file |
| `private[own_module]` | every file of this module |
| `private[ancestor]` | the named ancestor module and its whole subtree |
| *(unmarked)* | any module that imports it |

```sysl
module oskit.arch

exported() -> int = 42

private lookup(fd: int) -> int = fd

private[arch] reset(c: int) = print("reset", c)
```

The bare form being **file**-scoped is a deliberate divergence from Scala, and it costs nothing:
module-private is exactly `private[own_module]`, the degenerate case of the scoped form. What it buys
is the one level that provably never crosses a file boundary, which is the level at which a
declaration can be fully inferred and given internal linkage.

The honest cost is that the everyday module-internal helper is now the wordier `private[arch]` rather
than a bare `private`. The alternative spends a whole keyword to save a bracket.

**A restriction is about naming, not existence.** A file-private declaration still belongs to its
module and still spends its name there, so a sibling file cannot declare something else of that name.

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

The forms are Scala 3's, unchanged:

```sysl
import sysl.math.max              // max(a, b)      — one member, unqualified
import sysl.math.{max, min}       // several
import sysl.math.*                // every public member
import sysl.math.{max as bigger}  // renamed
import sysl.math                  // math.max(a, b) — the module itself
import sysl.math as m             // m.max(a, b)    — the module, renamed
```

Bringing in the module *name* is the self-documenting middle ground: `math.max` says at the call site
where the name came from, without listing members. The wildcard is the terse opposite.

Imports usually sit just below the header, but one may also appear **inside a block**, scoped to it,
for a name wanted in one function only.

Resolution is innermost-first: a local binding shadows an imported name, and the fully-qualified path
is always available to break a tie. Two wildcard imports offering the same name make an *unqualified*
use of it an error naming both — including when one of them is the standard library, which is
auto-imported into every file. That is deliberate: the alternative is a precedence tier that makes the
library quietly lose to whatever a program imported, which is the silent capture the error exists to
prevent.

## Capabilities ride along

A capability clause narrows a module, and it is written in the header on a line of its own:

```sysl
module oskit.arch
no alloc

halt()
    print("halted")
```

Because the module is the directory, the clause is a property of the directory — so it must appear in
**every** file of the module, and the compiler rejects a module whose files disagree. The redundancy
is the point: you can never open a file in a `no alloc` module and fail to see that it is.

The other direction is `requires`, and the standard library uses it: `sysl.fs` is `requires os`,
because a filesystem is something the environment either has or does not, and `sysl.thread` is
`requires threads` and `requires posix`, because creating a thread needs a scheduler underneath it. A
freestanding target importing either is told so at the import.

That is also why the atomics live apart from the threads. `sysl.sync` requires **nothing**, so a
module that has given up both its allocator and its operating system can still import it — which is
the point, since a spinlock and an atomic counter are what a kernel has before it has anything else.
A module's requirement is module-wide, so one type in there needing a scheduler would have taken the
whole module out of the kernel's reach.

Propagation is over the module graph, which is acyclic — so a module's effective requirement is
computed in a single sweep rather than an iterated fixpoint, and a `no alloc` module importing one
that requires an allocator is an error **at the import**, not deep in code generation.

## The standard library

`sysl` itself is the prelude: auto-imported into every file, and the reason nothing so far has had to
import anything to call `print`. Everything else is a submodule you ask for by name.

| module | what is in it |
|---|---|
| `sysl` | `print`, `Option`, `Result`, `Display`, `Writer`, `Iterate`, the operator traits |
| `sysl.buf` | `Buf[T]`, the growable sequence, and `ByteSink` |
| `sysl.text` | `from_utf8`, `StrBuilder`, `Chars`, `CString` |
| `sysl.io` | `Reader`, `stdin()`, `lines()` |
| `sysl.fs` | files and paths — `read_text`, `write_bytes`, `exists`, `rename`, and `IoError` |
| `sysl.math` | `max`, `min`, `pi`, the float functions, and the integer traits `Signed` and `Bits` |
| `sysl.sync` | `Atomic[T]`, `SpinLock`, and the five memory orderings — requires nothing |
| `sysl.thread` | `spawn`, `Thread.join`, `yield_now`, and `Mutex[T]` |
| `sysl.args` | `args_of`, for reading a raw `argv` |
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

### Reading input

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

## Separate compilation

A module is compiled once and linked, which is what the acyclic import graph buys. The standard
library itself is an artifact — `.sysl/core.syslib`, a real `ar` archive — and the compiler builds it
for you when nothing usable is at the default path, announced on stderr and in well under a second.
There is no bootstrap step to run and none to remember.

---

Next: [contracts](/tour/contracts/) — types that carry a rule, and functions that state what they
require.
