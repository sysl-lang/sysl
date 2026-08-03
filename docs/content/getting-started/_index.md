---
title: Getting Started
summary: Get a compiler that runs, then a program that runs.
weight: 10
---

sysl compiles through LLVM: the compiler emits textual LLVM IR and hands it to `clang` to assemble
and link. So there are two things to install — the compiler itself, which is a Scala project you
build from source, and a toolchain for it to hand its output to.

By the end of this section you will have run a sysl program you wrote yourself. The [tour](/tour/)
picks up from there, and [the command line](/getting-started/cli/) is here for when you want more of
the tool than `run`.

If you write C, [coming from C](/getting-started/from-c/) is the shortest way in: what translates
straight across, what changes shape, and the refusals a C program meets first.
