---
title: The cpu module
summary: "`sysl.cpu` — `has`, which asks the processor a program is running on whether it has an instruction-set extension, so a program can choose an `@unchecked` fast path safely; `names`, the extensions there are to ask about."
weight: 77
---

`sysl.cpu` asks the processor a program is **running** on which instruction-set extensions it has.
It is the other half of [`@extensions`](/reference/attributes/#extensions-a-body-compiled-for-instructions-the-build-does-not-promise):
a function marked `@extensions("sha3")` is compiled for an instruction some processors of its
architecture lack, and a call of it from code the build does not promise `sha3` to is accepted only
inside an `@unchecked("sha3")` block — the program's word that it has asked first. This module is the
asking.

## `has`

```sysl
has(name: string) -> bool
```

`has(name)` answers whether the processor has the extension `name`. The pattern it exists for is a
branch on the answer around the block that calls the fast function, with a portable body on the other
side. Here the fast side is AArch64's CRC-32C instruction and the portable side computes the same
checksum a bit at a time, so the program prints the same line on every processor and only its speed
depends on the answer:

```sysl
import sysl.cpu

// CRC-32C of a word's eight bytes, a bit at a time.
crc_portable(word: u64) -> u32
    var crc: u32 = 0xffffffff

    for i in 0..<8
        crc ^= u32((word >> u64(8 * i)) & 0xff)

        for _ in 0..<8
            crc = if crc & 1 == 1 then (crc >> 1) ^ 0x82f63b78 else crc >> 1

    crc ^ 0xffffffff
end crc_portable

#if aarch64
extern "llvm.aarch64.crc32cx" crc_word(crc: u32, word: u64) -> u32

@extensions("crc")
crc_fast(word: u64) -> u32 = crc_word(0xffffffff, word) ^ 0xffffffff

crc(word: u64) -> u32
    if cpu.has("crc")
        @unchecked("crc")
            return crc_fast(word)

    crc_portable(word)
#else
crc(word: u64) -> u32 = crc_portable(word)
#endif

print(f"${crc(0x0123_4567_89ab_cdef)}%x")
```

```output
65b0d823
```

**The first call asks the processor about every name at once and keeps the answer**, so a call
after it is a load and a mask, cheap enough to make on every call of the function it guards. Two
threads making the first call together both ask and both keep the same answer, so no lock is taken.

**How it asks is the system's**:

| target | what answers |
|---|---|
| macOS on AArch64 | `sysctlbyname("hw.optional.arm.FEAT_…")`, one key per name |
| Linux and Android on AArch64 | a bit of `getauxval(AT_HWCAP)` or `getauxval(AT_HWCAP2)` — what the kernel lets user code run |
| x86-64 | `cpuid`, with `xgetbv` saying whether the system saves the AVX and AVX-512 registers |

On x86-64 an extension whose registers the operating system does not save is answered false: a
processor with AVX under a system that does not save the `ymm` registers does not have AVX for a
program. macOS saves the AVX-512 registers from a thread's first use of them, so there AVX-512 is
answered from `cpuid` alone, as LLVM's own `__builtin_cpu_supports` answers it.

## The names are `@extensions`'s

A name is the extension as LLVM names the feature — the names
[`@extensions`](/reference/attributes/#extensions-a-body-compiled-for-instructions-the-build-does-not-promise)
and [`codegen.extensions`](/reference/packages/#a-processor-s-extensions) take — so the name in the
`if` is the name in the block under it. A literal no processor of the target's kind can be asked about
is refused while compiling, and the refusal lists the ones there are:

```sysl
import sysl.cpu

print(cpu.has("crcc"))
```

```error
'sysl.cpu.has' is asked about 'crcc', which is not an extension an aarch64 processor can be asked about
```

So is a name of the other architecture's, as `@extensions` refuses it — a program that dispatches on
both writes each branch under its own `#if`:

```sysl
import sysl.cpu

print(cpu.has("avx2"))
```

```error
'sysl.cpu.has' is asked about 'avx2', which is not an extension an aarch64 processor can be asked about
```

A name that arrives while the program runs cannot be checked, and one outside the list answers false.
`names()` is the list:

```sysl
names() -> []const string
```

| processor | names |
|---|---|
| AArch64 | `fp-armv8`, `neon`, `crc`, `aes`, `sha2`, `sha3`, `sm4`, `lse`, `lse2`, `rdm`, `dotprod`, `fullfp16`, `fp16fml`, `rcpc`, `rcpc-immo`, `jsconv`, `complxnum`, `flagm`, `altnzcv`, `fptoint`, `ssbs`, `sb`, `pauth`, `dit`, `bf16`, `i8mm`, `sve`, `sve2`, `bti`, `rand`, `sme`, `sme2`, `cssc`, `wfxt`, `hbc`, `mops` |
| x86-64 | `sse`, `sse2`, `sse3`, `ssse3`, `sse4.1`, `sse4.2`, `popcnt`, `pclmul`, `aes`, `cx16`, `movbe`, `xsave`, `rdrnd`, `avx`, `fma`, `f16c`, `bmi`, `bmi2`, `avx2`, `adx`, `rdseed`, `sha`, `avx512f`, `avx512dq`, `avx512ifma`, `avx512cd`, `avx512bw`, `avx512vl`, `avx512vbmi`, `avx512vbmi2`, `gfni`, `vaes`, `vpclmulqdq`, `avx512vnni`, `avx512bitalg`, `avx512vpopcntdq`, `avxvnni`, `lzcnt`, `avx512bf16`, `avx512fp16` |
| any other | none — `@extensions` is accepted on aarch64 and x86_64 only |

```sysl
import sysl.cpu

// Each list opens with its processor's baseline, `fp-armv8` or `sse`, which every one of them has.
val all = cpu.names()

print(all.len > 0 && cpu.has(all[0]))
```

```output
true
```

**An extension the build is compiled with always answers true**, since the program could not be
running otherwise: `neon` and `fp-armv8` on every AArch64 machine, `sse` and `sse2` on every x86-64
one, and on `aarch64-macos` — built for the M1 — `crc`, `aes`, `sha3` and `dotprod` too.

## Only a hosted program asks

A bare-metal image has no operating system to ask, and is built for one processor, which its build
already names: [`codegen.extensions`](/reference/packages/#a-processor-s-extensions) or `--cpu` says
what that processor has, and a function compiled for an extension the build promises is called with
no `@unchecked` block and no branch at all. So a call of `has` there is refused at the program's own
line, as a [`sysl.fenv`](/library/fenv/) call is refused where there is no floating-point unit:

```sysl target=aarch64-freestanding
import sysl.cpu

@export("main")
start() -> i32 = if cpu.has("crc") then 1 else 0
```

```error
'sysl.cpu.has' asks the operating system which extensions the processor has, and 'aarch64-freestanding' has none to ask
```

The refusal follows the call through the program's own functions, and lands on the line that leads
there. The module itself builds on every target, and `names()` answers on every one, so a package that
dispatches on a hosted machine puts its query under `#if hosted` and builds for a board too.

Answering from the build instead — true for what `codegen.extensions` names, false for the rest — was
the alternative, and is not what this module does: it would be a constant dressed as a question, and a
board whose processor has `crc` that the build forgot to name would be told it does not, with nothing
said. The refusal says it.
