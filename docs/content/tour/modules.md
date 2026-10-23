---
title: Modules and the library
summary: A module is a directory, an import only shortens a name you could always write in full, and the standard library is one auto-imported module with ten submodules under it.
weight: 110
---

Every program on this site so far has been a single file with no header. That is not a special
form: it is a module without a name. This chapter covers programs with more than one module.

## A module is a directory

The files under `oskit/arch/` make up the module `oskit.arch`; the files under `std/fs/` make up
`std.fs`. Every declaration in every file of the directory belongs to that one module, so splitting a
growing module into more files creates no new module and changes no import.

Each file states which module it belongs to, and the compiler checks the name against the file's
location:

```sysl
module oskit.arch

halt()
    print("halted")
```

This is the model of Scala's `package` and Go's directory packages, and it suits operating-system
work well: a subsystem such as an architecture layer, a server or a driver is already a directory, so
the module is the subsystem, and the subsystem is the unit an importer depends on.

A file with no header is in the **anonymous root module**, whose name is the empty path. No other
module can name it, so its declarations are visible only to its own files. That is appropriate for
the code where a program starts, and it is why a one-file program needs no module header.

A top-level *statement* is not a declaration. A declaration is hoisted and belongs to the whole
module; a statement runs, and statements run in order. **Only one file of a program may contain
top-level statements**, and a second one that does is an error naming both files.

That file is the program's **entry file**, and its top level is a **body**: a `val` or `var` there is
a local initialized where it is written, and a function there can read the bindings above it.

Alternatively, a program may declare `main`:

```sysl
main(args: []string)
    print("started with", args.len, "argument")
```

```output
started with 1 argument
```

**A program has one starting point**, so it uses either top-level statements or `main`, never both.
`main` has access to **the command-line arguments**, which top-level statements do not, because they
are not a call and have no parameter list.

A program in which no file contains a statement is a valid program that does nothing. A tree of
declarations alone compiles to exactly that, so a library is not an error.

## Imports

A public member is **always** reachable by its fully qualified name: `sysl.math.max(2, 7)` needs no
import. Nothing needs to be imported to be *visible*; an import only shortens the reference:

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

An import is a dotted path through the module tree, and **the end of the path decides what is
bound**:

```sysl
import sysl.math.max              // max(a, b)      — one member, unqualified
import sysl.math.{max, min}       // several
import sysl.math.*                // every public member
import sysl.math                  // math.max(a, b) — the module itself
import sysl.math.{max as bigger}  // a member, renamed
import sysl.math as m             // m.max(a, b)    — the module, renamed
```

A path ending at a **member** binds that member under its own name, so calls to it need no
qualifier. Braces do the same for several members at once, and `*` for every public member of the
module.

A path ending at a **module** binds the module's last name segment, so `sysl.math` becomes `math` and
calls keep one level of qualification. This is a useful middle ground: `math.max` shows at the call
site where the name comes from, without a member list to maintain at the top of the file and without
the opacity of a wildcard.

`as` renames whatever the path ends at, member or module. Use it when two modules offer the same
name; it is the only way to resolve that collision without writing the full path at every use.

These are the same import forms as Scala 3, including `as` and `*`.

Imports usually follow the header, but an import may also appear **inside a block**, scoped to it,
for a name needed in one function only.

Names resolve innermost first: a local binding shadows an imported name, and the fully qualified path
is always available to disambiguate. When two wildcard imports offer the same name, an *unqualified*
use of it is an error naming both, including when one of them is the standard library, which is
imported into every file automatically. This is deliberate: giving either side precedence would let
a name be captured silently, which is what the error prevents.

## The standard library

**The standard library is a module like any other.** The root module `sysl` is imported into every
file automatically, which is why no example so far has imported anything to call `print`. It holds
what the language itself depends on, which every program needs. Everything else is in a submodule
you import by name.

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
| `sysl.threads` | `spawn`, `Thread.join`, `yield_now`, `Mutex[T]`, `Condvar` and `Channel[T]` |
| `sysl.args` | command-line options — `Scan`, `Cli`, and `args_of` for a raw `argv` |
| `sysl.sys` | the platform seam — what a freestanding target replaces |

The division follows one rule: **what every program needs is available automatically, and anything
else is imported.** Array literals and `for` loops are part of the language, so nothing imports them.
A growable sequence is something a program chooses to use, so it imports one:

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
b.push(widths.len().to_string())
b.push(" is ")
b.push(widest.to_string())

print(b.finish())
```

```output
widest of 3 is 11
```

None of this is a language feature. `Buf` is ordinary sysl over a slice that it replaces when it runs
out of room, and `StrBuilder` is ordinary sysl over a `Buf[u8]`. Both are imported rather than
automatic so that a program that uses neither does not link either.

A module may also declare restrictions. `@no_alloc` in the header says the module uses no allocator,
and `requires os` says it needs an operating system. `sysl.fs` is `requires os` because a filesystem
is something the environment either has or does not, and everything under `sysl.posix` requires
posix. For the same reason the atomics are separate from the threads: `sysl.sync` requires
**nothing**, so a kernel with neither an allocator nor an operating system can still import a
spinlock. A freestanding target that imports one of the other modules is told so **at the import**,
not deep in code generation. [The reference](/reference/modules/) describes the clause and how it
propagates.

`sysl.io` deserves a closer look, because it shows the iteration protocol in use:

```sysl
import sysl.io.{stdin, lines}

var src = stdin()

for line in lines(&src)
    print("read:", line)
```

`lines` returns a cursor, and `for` iterates over anything that implements `Iterate`, so the loop
reads one line at a time from a 4 KiB chunk instead of loading the whole input into memory. A
`Reader` is a trait with one method, so the same loop can read a socket, a ring buffer or a test
fixture, and a freestanding target supplies its own implementation.

Each module is compiled once and linked, which the acyclic import graph makes possible. The standard
library itself is built as an `ar` archive. When no usable archive exists at the default path, the
compiler builds one automatically, reports this on stderr, and takes well under a second. The archive
is stored in your cache directory under a fingerprint of the library it was built from, so every
project on the machine shares it, and a different compiler version builds its own without affecting
the existing one. There is no separate setup step.

---

Next: [contracts](/tour/contracts/): types that carry a rule, and functions that state what they
require.
