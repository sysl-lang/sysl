---
title: Modules and the library
summary: A module is a directory, an import only shortens a name you could always write in full, and the standard library is a prelude with ten submodules under it.
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

### Hiding the shape is a different axis

Visibility decides who may say a **name**. It does nothing about a type's **layout**: a `private`
field still occupies its place, counts toward the size, shifts the fields after it, and takes part in
the ABI. Anyone who can name the type can still be built against its shape.

`opaque` is the other axis. Inside the declaring module the struct is ordinary:

```sysl
opaque struct Conn
    fd: int
    live: bool
end Conn

open(n: int) -> Conn = Conn(n, true)

describe(c: *Conn) -> string = "fd " + str(c.fd)

var c = open(7)

print(describe(&c), c.live)
```

```output
fd 7 true
```

Outside it, the type is **incomplete** — the same thing C's `struct foo;` is — and the only thing
anyone may say about it is `*Conn`:

```sysl
import net.Conn

var c: Conn        // refused: no size out here
var p: *Conn       // fine — a pointer needs no shape
```

Everything refused outside is refused for one reason: constructing, reading a field, taking an
element, a by-value parameter or result, `sizeof`, `alignof`, a by-value `self` method. Each needs a
size or an offset, and the size is exactly what is being withheld — so it is one rule rather than
fourteen.

The by-value `self` method is the case worth pointing at, because it looks like an ordinary call and
is not. The *function* was compiled by the library, but what crosses the boundary is the **caller's
copy**, laid out as the fields stood when that caller was built — so adding a field would break it
silently, which is the failure the modifier exists to prevent. `*self` and `&self` need no shape, and
are what an opaque type's methods use.

A struct may also be opaque with **no body at all**, which is how a C handle is bound: nothing in
sysl lays a `Dir` out, and the storage is libc's.

The payoff is that a field may move with nothing downstream recompiled. The reach is the declaring
module exactly — not a subtree, the way `private[M]` widens — because the files of a module already
share one scope and are already the unit that recompiles together.

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

## Capabilities ride along

A capability clause narrows a module, and it is written in the header on a line of its own:

```sysl
module oskit.arch
@no_alloc

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

## Naming a library the linker needs

The header has one other inhabitant. An `extern` says which symbol it wants and never where that
symbol lives, so a module binding a C library says it with `link`:

```sysl
module image.png
@link("png")
@link("z")

extern "png_create_read_struct" create(ver: *u8, err: *u8, fn: *u8) -> *u8
```

**A directive names a library, never a flag**, and that is the whole design. Where a library lives is
a property of the machine being built for: the mathematics is a separate file on Linux, part of
`libSystem` on macOS, inside the CRT on Windows, and absent entirely from a freestanding target that
has no libc to hold it. A directive spelling `-lm` would be right on one of those and wrong on three,
and the author could not be told so by any compiler running on the machine that wrote it — the link
that fails is somewhere else. So the file names `m` and the driver decides what that becomes,
including deciding it becomes nothing.

Unlike a capability, `link` is **not** required to agree across a module's files, because it
describes something narrower: a capability is a property of the whole module, while a link
requirement belongs to the `extern`s in one file — and a module that keeps its foreign declarations
together has nothing for its other files to repeat.

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
| `sysl.math` | `max`, `min`, `pi`, the float functions, the integer traits `Signed` and `Bits`, and the integer arithmetic above them — `pow`, `gcd`, `lcm`, `divmod`, `is_power_of_two`, `next_power_of_two` |
| `sysl.regex` | POSIX Extended Regular Expressions — `regex`, `Regex`, `Match` |
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
library itself is an artifact — a real `ar` archive — and the compiler builds it for you when nothing
usable is at the default path, announced on stderr and in well under a second. It lives in your cache
directory under a fingerprint of the library it was built from, so every project on the machine shares
one and a new compiler makes its own without disturbing the old. There is no bootstrap step to run and
none to remember.

---

Next: [contracts](/tour/contracts/) — types that carry a rule, and functions that state what they
require.
