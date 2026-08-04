---
title: Getting Started
summary: Get a compiler that runs, then a program that runs.
weight: 10
---

sysl compiles through LLVM: the compiler emits textual LLVM IR and hands it to `clang` to assemble
and link. So there are two things to install — the compiler itself, and a toolchain for it to hand
its output to. On macOS one `brew install` does both; elsewhere, the compiler builds from source and
the toolchain comes from your package manager.

By the end of this section you will have run a sysl program you wrote yourself. The [tour](/tour/)
picks up from there.
