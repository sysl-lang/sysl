---
title: sysl.args
summary: One function, and the reason a program almost never calls it — how argc and argv become a []string.
weight: 90
---

`sysl.args` holds a single function, and most programs will never write its name:

```
args_of(argc: i32, argv: **u8) -> []string
```

What the platform hands an entry point is C's pair — a count, and a vector of NUL-terminated byte
runs. What a sysl program asks for is a slice of strings. Something has to walk the one and build the
other, and doing it **in the library** is what keeps the pair out of every sysl signature: the two
foreign types are named in one place instead of in every program that wants its arguments.

## Where it is actually called

A program's top-level statements are its entry point and go on being that. What a declared `main`
adds is a **named place** for the work those statements would otherwise do, plus the one thing the
statements cannot get at — the arguments the program was started with.

```sysl
print("top-level runs first")

main(args: []string)
    print("main runs after", args.len)
```

```output
top-level runs first
main runs after 1
```

So `main` is **additive**: the statements run first, in the order they were written, and `main` runs
after them. And declaring it with a parameter is the whole of what asks for the conversion — the
entry point the compiler lays out is what calls `args_of`, which is why a program that reads its
arguments still contains no mention of this module.

The count is `1` above because the program was started with no arguments of its own. Element zero is
always there, and it is the program's own path — the same convention C has, and the reason a loop
over arguments starts at one:

```sysl
main(args: []string)
    print(args.len)

    for i in 1..<args.len
        print(i, args[i])
```

```output
1
```

Run through the compiler's own driver, **everything after a bare `--` belongs to the program**:

```
$ sysl run report.sysl -- --verbose report.txt
3
1 --verbose
2 report.txt
```

The split is made before sysl's own options are parsed, which is why an argument that looks like one
of sysl's is still the program's.

## The two signatures, and nothing else

`main()` and `main(args: []string)`. A `[]const string` is accepted in the same position, because a
program that only reads its arguments may say so and it costs the entry point nothing — the two
views are one layout, and what `args_of` yields may stand in for either:

```sysl
main(args: []const string)
    print(args.len)
```

```output
1
```

**A result is refused**, because a result from `main` would be an exit status, and an exit status is
not something a sysl signature spells:

```sysl
main() -> int
    0
```

```error
'main' yields nothing, so it may not result in int — a program's exit status is not something a signature can say
```

**The platform's own pair is refused**, which is the refusal this module exists to make unnecessary:

```sysl
main(argc: i32, argv: **u8)
    print(argc)
```

```error
'main' takes either nothing or one '[]string' of the program's arguments, not (int, **byte)
```

**Type parameters are refused**, since the caller is the platform and it has none to give:

```sysl
main[T]()
    print("nothing calls this with a type")
```

```error
'main' is called by the platform, which has no type arguments to give it
```

**And there is one `main` in a program**, wherever it is written — so a module may not declare one
beside the one the program starts at. That is the same reservation C makes and for the same reason:
it is not a name a program calls, it is the name the platform calls, and two of them would leave
which one the program *is* to whichever was emitted last.

```sysl
main()
    print("one")

main()
    print("the other one")
```

```error
function 'main' is already declared
```

## Calling it yourself

The function stays public, and there are two reasons — the second of which is the interesting one.

The first is the ordinary one: a program handed an `argv` by something **other than the platform** —
an embedder, a shell it implements, a test that wants to drive its own argument parsing — has
somewhere to go.

```sysl
import sysl.args.args_of
import sysl.text.cstring

var a = cstring("prog")
var b = cstring("--verbose")
var c = cstring("file.txt")
var vec = [a.ptr, b.ptr, c.ptr]
var made = args_of(3i32, &vec[0])

print(made.len)
print(made[0], made[1], made[2])
print(made[1].len, made[1] == "--verbose")
```

```output
3
prog --verbose file.txt
9 true
```

The second is that **this is the only surface on which an argument vector's failure can be reached at
all**, since a well-formed one is all a real process will ever hand over. That failure is the next
section.

## What the conversion actually does

Three things, and each is a decision worth knowing about.

**It finds each run's length by looking for the terminator**, rather than by calling `strlen`. So the
conversion asks the platform for nothing beyond the two values it was handed, which is what lets a
target with no libc still start a program.

**It validates and copies.** A `string` owns what it holds, so an argument outlives the vector it
came from, and nothing a program does to one reaches memory the platform still owns. That copy is
not an oversight to be optimized away later — a borrowed view into `argv` would be a slice whose
owner is the process image, which is a thing no sysl type describes.

**An argument that is not UTF-8 stops the program**, the way `unwrap` does, and it names the byte:

```sysl
import sysl.args.args_of

var bad: []u8 = [255u8, 0u8]
var vec = [&bad[0]]
var made = args_of(1i32, &vec[0])

print(made.len)
```

That program prints

```
panic: command-line argument 0 is not UTF-8 at byte 0
```

and exits with status 1. It is not a checked program on this page for that reason — a non-zero exit
is a failure to the harness — but the message is what a real one prints, and note that it *does*
print, unlike the [trap](/library/sync/) a violated contract lowers to. This one is an ordinary
`print` and `exit`, so the text reaches the terminal.

Putting the check here is deliberate: validation belongs **at the boundary**, so that everything
above it can treat a `string` as well-formed without asking. An argument vector is a boundary.

## Why it is a module of its own

Two reasons, and both are what a submodule is for.

**Almost nobody writes this name.** A `main(args: []string)` is what asks for the conversion, and
the entry point the compiler lays out is what makes it. A name nearly nobody writes has no business
in the set every file gets for free, so a program that does want it names `sysl.args.args_of` and
says so.

**It cannot live beside the platform externs in [`sysl.sys`](/library/sys/).** This calls `print` and
`exit`, which are `sysl`'s, and `sysl` reaches `sysl.sys` for its printing — putting both in one
module would make the two depend on each other, which the
[acyclic module graph](/reference/modules/) refuses. What is left in `sys` is a **leaf that needs
nothing**, which is what a platform module should be.

That second reason is worth sitting with, because it is a general shape rather than an accident of
this module. A conversion that reports its failure in words is not a leaf, because reporting is
itself a dependency. Splitting it out is what let the thing underneath stay one.
