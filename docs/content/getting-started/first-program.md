---
title: Your first program
summary: A file with no ceremony in it, and what the compiler did with it.
weight: 20
---

Put this in `hello.sysl`, in any directory:

```sysl
print("Hello, sysl!")

var width = 6
var height = 7
print("area =", width * height)
```

Run it:

```bash
sysl run hello.sysl
```

```output
Hello, sysl!
area = 42
```

## What is not there

There is no `main`, no import, and no class around the statements. A sysl file is a module, and
statements at its top level run in order, so the smallest program is just the code you meant to
write.

You can write a `main` when you need one, typically to read the program's arguments:

```sysl
main(args: []string)
    print("started as:", args[0])
    print(args.len - 1, "argument(s) given")
```

`print` comes from `sysl`, the standard module. It is the one module whose names a file can use
without importing it, because it holds what the language itself is built on. `print` takes any
number of arguments, converts each one to text, and separates them with spaces.

## What the compiler did

`run` performed four steps: it parsed and checked the source, emitted textual LLVM IR, passed that
to `clang` to assemble and link against the standard library archive, and ran the resulting binary.
That binary is native code; there is no interpreter or virtual machine underneath it.

`build` stops once the binary is produced, and `test` runs the `@test` functions in a directory.
The tour uses `run` throughout.

## Where to go next

The [tour](/tour/) starts here and builds up. It is meant to be read in order, and every program in
it can be pasted into a file and run.

[The command line](/getting-started/cli/) covers the rest of the subcommands, the flags they share,
and the exit status each one returns. Nothing in the tour depends on it.
