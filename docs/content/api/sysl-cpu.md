---
title: sysl.cpu
layout: api-module
headingShift: 0
slugStyle: github
module: sysl.cpu
summary: "Asking the processor a program is running on which instruction-set extensions it has."
---

**It is the question an `@unchecked("…")` block promises was asked**
(`reference/attributes.md § @extensions`). A function marked `@extensions("sha3")` is compiled for
an instruction some processors of its architecture lack, and a call of it from code the build does
not promise `sha3` to is accepted only inside `@unchecked("sha3")`. This module is what goes in the
`if` around that block:

```
if cpu.has("sha3")
    @unchecked("sha3")
        digest_fast(data)
else
    digest_portable(data)
```

**The names are `@extensions`'s**: each extension as LLVM names the feature, the names
`codegen.extensions` takes. `names()` lists the ones this target's processor can be asked about.
A literal outside that list is refused while compiling, as `@extensions` refuses it; a name that
arrives at run time and is not in the list answers false.

**How it asks is the system's, and the answer is asked once.** On macOS each name is a
`sysctlbyname("hw.optional.arm.FEAT_…")`; on Linux and Android on AArch64 it is a bit of
`getauxval(AT_HWCAP)` or `getauxval(AT_HWCAP2)`, which is what the kernel has decided user code may
run; on x86-64 it is `cpuid`, with `xgetbv` saying whether the operating system saves the AVX and
AVX-512 registers -- a processor that has AVX under a system that does not save its registers does
not have AVX for a program. The first call asks every name at once and keeps the answer; every call
after it is a load and a mask.

**Only a hosted program asks.** A bare-metal image is built for one processor and has no operating
system to ask, so there the build says what the processor has (`codegen.extensions`, `--cpu`), and a
call of `has` is refused while compiling, naming that. The module still builds there, so a package
that dispatches puts its query under `#if hosted`.

## Index

[`has`](#has) [`names`](#names)

## Functions

### `has`

```sysl
has(name: string) -> bool
```

Whether the processor this program is running on has the extension `name`.

`name` is one of `names()`. Written as a literal, any other name is refused while compiling, naming
the ones there are; arriving at run time, any other name answers false. The first call asks the
processor about every name and keeps the answers, so each call after it costs a load.

It needs an operating system to ask, and is refused on a bare-metal target, where the build's own
`codegen.extensions` says what the processor has.

### `names`

```sysl
names() -> []const string
```

Every name `has` can be asked about on this target's processor, in a fixed order.

They are `@extensions`'s names for the architecture: on AArch64 `crc`, `aes`, `sha2`, `sha3`,
`dotprod`, `lse` and the rest; on x86-64 `sse4.2`, `avx2`, `bmi2`, `avx512f` and the rest. On every
other processor the list is empty, as `@extensions` is refused there.
