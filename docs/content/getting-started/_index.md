---
title: Getting Started
summary: Get a compiler that runs, then a program that runs.
weight: 10
---

sysl compiles through LLVM: the compiler emits textual LLVM IR and hands it to `clang` to assemble
and link. You therefore need two things: the compiler itself, and a toolchain for it to hand its
output to. On a Mac with Apple silicon a single `brew install` provides both. On other systems,
build the compiler from source and install the toolchain from your package manager.

By the end of this section you will have written and run a sysl program of your own. The
[tour](/tour/) continues from there, and [the command line](/getting-started/cli/) covers the rest
of the tool beyond `run`.

If you write C, [coming from C](/getting-started/from-c/) is the quickest way in: what translates
directly, what changes shape, and the diagnostics a C programmer meets first.
