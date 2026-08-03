---
title: Your first program
summary: A file with no ceremony in it, and what the compiler did with it.
weight: 20
---

Put this in `hello.sysl`, anywhere you like:

```sysl
print("Hello, sysl!")

var width = 6
var height = 7
print("area =", width * height)
```

Run it:

```bash
sbt "syslJVM/run run hello.sysl"
```

```output
Hello, sysl!
area = 42
```

## What is not there

No `main`. No imports. No class wrapped around the statements to give them somewhere to live. A
sysl file is a module, and statements written at its top level run in order — so the smallest
program is the code you actually meant to write.

A `main` is available when you want one, and you want one as soon as you care about the program's
arguments:

```sysl
main(args: []string)
    print("started as:", args[0])
    print(args.len - 1, "argument(s) given")
```

`print` came from the prelude, which is the part of the standard library every program gets without
asking. It takes any number of arguments, converts each one to text, and separates them with
spaces.

## What the compiler did

`run` is one command of several, and it did four things in a row: parsed and checked the source,
emitted textual LLVM IR, handed that to `clang` to assemble and link against the standard library
archive, and ran the binary that came out. The binary is real — there is no interpreter and no VM
underneath it.

`build` stops after producing that binary instead of running it, and `test` runs the `#test`
functions in a directory. The tour uses `run` throughout.

## Where to go next

The [tour](/tour/) starts here and builds up. It is meant to be read in order, and every program in
it is one you can paste into a file and run.

[The command line](/getting-started/cli/) is the other direction: the rest of the subcommands, the
flags they share, and what each one leaves as an exit status. Nothing in the tour needs it.
