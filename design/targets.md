# Targets

**Status:** decided and built. This is the target half of the doc `capabilities.md` said was
still to be written — what a machine is, how one is named, and what naming one changes. The
project-config half of that promise is now `packages.md` — the `package.hocon` schema and
per-target capability sets, both of which are built — and filename-axis platform
selection is still open. What remains open here is listed at the bottom.

A systems language cannot be vague about the machine. Two of them differ in more than speed:
they disagree about how a C function is called, and a compiler that guesses produces a module
that looks right and is not. So sysl compiles **for** a target, always, and says which one in
the module it emits.

## A target is a value, not an ambient fact

`Target` is a plain value carried from the invocation down to codegen. Nothing anywhere consults
the machine the compiler happens to be *running on* — that machine appears exactly once, as the
default an invocation that names no target gets.

That is what lets one compiler build for a machine it is not, and it is why the whole registry
can be exercised from a test suite running on one laptop: a cross-target test reads the emitted
text, which is where everything a target decides shows up.

```
sysl build hello.sysl                          # for this machine
sysl build hello.sysl --target x86_64-linux    # for another
sysl targets                                   # what there is
```

`run` is the one subcommand that refuses a cross target, because running the result is the whole
of what makes it different from `build`.

## The registry

| name | triple | `va_list` | floating registers | an FPU |
|---|---|---|---|---|
| `aarch64-macos` | `arm64-apple-macosx` | loaded | yes | yes |
| `x86_64-macos` | `x86_64-apple-macosx` | address | yes | yes |
| `aarch64-linux` | `aarch64-unknown-linux-gnu` | copied | yes | yes |
| `x86_64-linux` | `x86_64-unknown-linux-gnu` | address | yes | yes |
| `riscv64-linux` | `riscv64-unknown-linux-gnu` | loaded | yes | yes |
| `x86_64-windows` | `x86_64-pc-windows-msvc` | loaded | yes | yes |
| `aarch64-freestanding` | `aarch64-none-elf` | copied | yes | yes |
| `x86_64-freestanding` | `x86_64-unknown-none-elf` | address | yes | yes |
| `riscv64-freestanding` | `riscv64-unknown-elf` | loaded | **no** | **no** |
| `thumb-freestanding` | `thumbv8m.main-none-eabihf` | loaded | yes | yes |
| `thumb-freestanding-softfp` | `thumbv8m.main-none-eabi` | loaded | **no** | yes |
| `thumb-freestanding-soft` | `thumbv8m.main-none-eabi` | loaded | **no** | **no** |
| `thumbv6m-freestanding` | `thumbv6m-none-eabi` | loaded | **no** | **no** |
| `thumbv7m-freestanding` | `thumbv7m-none-eabi` | loaded | **no** | **no** |
| `thumbv7em-freestanding` | `thumbv7em-none-eabihf` | loaded | yes | yes |
| `thumbv7em-freestanding-soft` | `thumbv7em-none-eabi` | loaded | **no** | **no** |
| `riscv32-freestanding` | `riscv32-unknown-elf` | loaded | **no** | **no** |
| `wasm32-freestanding` | `wasm32-unknown-unknown` | loaded | yes | yes |
| `x86-linux` | `i386-unknown-linux-gnu` | *no measured C ABI* | | |

**The last two columns are different questions, and the `softfp` row is where that shows.** The
fourth is where a `double` travels on the way into a call; the fifth is whether the machine has a
unit at all. A Cortex-M33 under `-mfloat-abi=softfp` passes arguments in core registers over an
`fpv5-sp-d16` that is present and used for the arithmetic, so it answers **no** and then **yes** —
and every other row saying no in the fourth column is a machine that never had a choice to record.

**Two rows share a triple, which used to be impossible and is now the point.** A triple names an
architecture and a calling convention, and on Arm it says nothing whatever about the *presence* of
the floating-point unit — so `thumb-freestanding-softfp` and `thumb-freestanding-soft` are one triple
and two machines. What separates them is `-mfpu=`, which sysl puts on every clang command line for a
Thumb row: `none` where the last column says no, and **the name of the unit** where it says yes.
**A target is the whole of what is said to clang**, and the triple stopped being all of that.

**Both answers are given, and giving only one of them was a bug.** A row that said nothing left the
unit's presence to clang's default for the architecture, and defaults are the toolchain's rather than
the language's: `thumbv8m.main-none-eabi` with no `-mfpu` defines `__ARM_FP 0xe` under Apple clang 21
and Homebrew clang 22, and defines nothing at all under apt.llvm.org's clang 20. So
`thumb-freestanding-softfp` was a machine with a unit on one developer's laptop and a machine without
one on the Linux CI, from the same source and the same registry row.

**Nine of these are 32-bit, and eight of the nine are a microcontroller.** The RP2350 boots either a
pair of Cortex-M33s or a pair of RV32IMAC Hazard3 cores; the RP2040 — the original Pico — has a pair
of Cortex-M0+; the Armv7E-M rows are ST's parts; and Armv7-M is the Cortex-M3, which is the board
Zephyr's own documentation reaches for first. The Arm half is a family rather than a board, and all
of it is here because a microcontroller is what *freestanding* is mostly for: the three 64-bit
freestanding rows reach kernels and hypervisors, which is a different audience from the one writing
embedded C, and nearly all of that is 32-bit.

The ninth is `wasm32-freestanding`, which is 32-bit for an unrelated reason and is not a
microcontroller at all — see *WebAssembly, which is not a processor* below.

`thumb` rather than `arm` names the Arm half, and the reason is the assembly arm rather than the
architecture family — a Cortex-M executes Thumb only, so an arm written for A32 would assemble for a
machine that cannot run it. One spelling serves `#if` and `asm` alike, as for every other processor.

**Neither bare-metal RISC-V has floating registers to pass arguments in, at either width.** The
hosted 64-bit triple is built for the D extension and the bare ones are not, which is clang's default
for each — and since sysl hands its own triple to clang, the two have to make the same assumption
about the same triple or the call disagrees. At 32 bits it is firmer than a default: the Hazard3 is
RV32IMAC and has no F extension to use. It reaches exactly one decision, whether a small aggregate of
floating members is flattened into registers, and that is why it is recorded rather than derived.

The Cortex-M33 goes the other way and needed checking rather than assuming: `eabihf` selects the
hard-float convention and the row names an `fpv5-sp-d16` for the part to use, so arguments really do
cross in VFP registers. `-mcpu=cortex-m33` refines instruction selection to the exact core and
changes nothing about the ABI — it is the sub-architecture question left open at the bottom of this
page, and it is not needed for a correct call.

**The unit named is the silicon's and not the triple's default, which is a different answer.** An
M33's is single precision — `fpv5-sp-d16`, sixteen D registers and no double-precision arithmetic —
where clang defaults `thumbv8m.main` to `fpv5-d16` and will lower a `f64` multiply to a `vmul.f64`
the part does not implement. Naming it costs a flag and buys an image that faults on neither the
header nor the first double.

**And it is the one machine here with three rows, because neither the float ABI nor the FPU's
presence is sysl's to pick.** `thumb-freestanding-softfp` is the same Cortex-M33 with arguments
crossing in core registers, which is what `-mfloat-abi=softfp` means and what pico-sdk builds by
default. The two cannot be mixed — GNU ld refuses the link outright, saying one object "uses VFP
register arguments" and the other "does not" — so a sysl object joining a C build has to agree with
it, and offering only the hard-float row meant the *C* had to be rebuilt to follow sysl. That is
backwards for a language whose `@export` claim is that it joins somebody else's build.

`softfp` is gcc's and pico-sdk's own spelling, which is the whole argument for the name: somebody
handed that linker message goes looking for the word in their build system, and it is that one. It is
**not** `soft`, which means something else — no FPU instructions at all, where `softfp` uses the
`fpv5-sp-d16` and changes only the convention. `thumb-freestanding-soft` is that other thing, and the
section below is about it.

### Armv6-M, which is the RP2040 and is a different architecture

`thumbv6m-freestanding` was the **fourth** Thumb row and the first that was not that Cortex-M33. Its
name carries the sub-architecture where the first three do not, because what separates it is not a
calling convention: Armv6-M is Armv8-M's predecessor rather than a subset of its options.

**The float column misled here, and saying so is what eventually produced the fifth column.** It read
`no` exactly as the `softfp` row did, for an unrelated reason — `softfp` is a *convention* chosen over
an FPU that is present, and the M0+ has no FPU at all, so there was never a choice to record. Two
rows agreeing on that column agreed about nothing. The registry now asks the second question outright
rather than leaving it to a paragraph, and this is the paragraph that noticed it was two questions.

**The ABI is the same and needed no work**, which is the happy half: AAPCS32 under soft-float already
described this core exactly, and the oracle of *Adding one* passed on the first run — the only
convention question, whether a homogeneous floating aggregate travels in floating registers, has the
same answer on a core with no such registers as on one whose convention declines to use them.

What differs is everything below the convention. There is no Thumb-2, so a literal-pool load into
`sp` and a store with writeback — both ordinary on the M33 — simply do not assemble. There is no
divider, no 64-bit shift, and no widening multiply, so `/`, `>>` on a `long` and `*` on a `long` are
all **calls**: `__aeabi_idiv`, `__aeabi_llsr`, `__aeabi_lmul` and their relatives. A real project
gets those from the toolchain's runtime, and pico-sdk links one; sysl emits the same references any C
compiler would for this triple, so nothing about them is sysl's to supply.

**The one that is not the toolchain's is atomics, and it is a language question rather than an
arithmetic one.** Armv6-M has no `ldrex`/`strex`, so LLVM cannot lower an `atomicrmw` inline and
calls `__atomic_fetch_add_4` instead. That reaches exactly one construct: the atomic retain pair is
emitted only for a program containing a `&sync T` (`memory.md`), so an ordinary program on this
target emits no atomic at all and links as it stands.

A program that *does* share across the RP2040's two cores needs an answer, and the answer is the
board's. **Disabling interrupts is the obvious implementation and is the wrong one**: `PRIMASK` is
per-core, so it buys atomicity against this core's interrupts and nothing whatever against the other
core — and a lost reference-count update is a premature free, which surfaces nowhere near the
mistake. What the chip has instead is a hardware spinlock, which is a fact about that board and must
not be something the compiler knows. The package carries it, exactly as a package carries any other
thing only the board can answer.

**A program that shares across *tasks* rather than cores owes the same kind of answer for the
reaper's scratch**, and for the same reason: the compiler has no notion of a current thread on a
freestanding target, so a `&sync` release that reaches zero fetches its worklist through the weak
`__sysl_arc_reaper` (`03 § Teardown is iterative`). Nothing to define where nothing schedules; the
scheduler's port defines it where something does. Both of these are the board's answers to questions
the language deliberately declines to guess at, which is what makes them one paragraph rather than
two mechanisms.

### A machine with no floating-point unit, which the triple does not say

**Three rows exist because a triple cannot express this, and it took a real board to notice.** Aiming
sysl at Zephyr's `qemu_cortex_m3` in August 2026 found two things at once: there was no Armv7-M row at
all, and the row that looked closest — `thumb-freestanding-softfp` — still told the C preprocessor an
FPU was there.

```
$ clang --target=thumbv8m.main-none-eabi -dM -E -x c /dev/null | grep __ARM_FP
#define __ARM_FP 0xe
#define __ARM_FPV5__ 1
```

That is not a mistake in the triple. `eabi` versus `eabihf` is a statement about **where arguments
travel**, and that clang gave `thumbv8m.main` an `fpv5-d16` unasked in both cases is a statement
about clang, so `a * b` on an `f32` compiled to `vmla.f32` under either suffix. The soft-float ABI
and an absent unit are different claims, and only the first has a triple to be written in.

**The measurement above is one clang's, which is the second half of the same lesson and was learnt
later.** Run against apt.llvm.org's clang 20 on Linux the command prints nothing at all: the same
triple, the same absent `-mfpu`, and the opposite answer. So a row that named no unit did not mean
*the default* — it meant *whichever compiler is installed*, and `thumb-freestanding-softfp` was two
machines depending on where it was built. That is why the flag is passed for both answers now, and
why a row that has a unit names it rather than leaving the sentence for clang to finish.

The cost of the two being conflated is paid twice, in two unrelated places:

- **at the `#include`**, where CMSIS refuses outright against a device configured without one —
  `error: "Compiler generates FPU instructions for a device without an FPU (check __FPU_PRESENT)"`;
- **at run time**, where an image that got past the headers takes a usage fault on the first VFP
  instruction it reaches, in whatever arithmetic happened to reach one.

So a target records the unit beside its calling convention, and **every** Thumb row has an `-mfpu=`
added to every clang command line sysl builds for it — the link, the object, a package's C, and a
`c const` probe alike. The last two matter as much as the first two: they are compiled *as* the
target and are where the header refusal happens. A row answering *no* passes `none`; a row answering
*yes* passes the unit's name, `fpv5-sp-d16` for the M33 and `fpv4-sp-d16` for the M4F.

**`soft` is the name because gcc already drew this line.** `-mfloat-abi=soft` means no FPU
instructions at all where `-mfloat-abi=softfp` means the unit is used and only the convention is in
core registers, so the three Armv8-M rows are that series end to end: hard, `softfp`, `soft`.

**Armv7-M needed no flag and a row anyway.** `thumbv7m-none-eabi` defines no `__ARM_FP` and calls
`__aeabi_fmul` unasked, because the architecture has no unit to have an opinion about — so half of
this is one plain row. What it is *not* is the Armv6-M row pointed at a Cortex-M3: v6-M code links
into an M3 image, being a subset, but a real project reads its own configuration rather than the
triple. `CONFIG_CPU_CORTEX_M3` says Armv7-M, so Zephyr's inline assembly uses `BASEPRI`, while CMSIS
reading `__ARM_ARCH_6M__` out of our triple supplies the intrinsic set that has no `__get_BASEPRI`.
Neither side is wrong; they were told about two different machines.

**None of the three needed ABI work, which was measured rather than hoped.** All three passed the
oracle of *Adding one* on the first run: AAPCS32 under soft-float already described them, and the one
convention question — whether a homogeneous floating aggregate travels in floating registers — has the
same answer on a core with no such registers as on one whose convention declines to use them. What
they do owe a board is arithmetic: `__aeabi_fmul` and its family, exactly as `__aeabi_ldivmod` is
already owed on every 32-bit row.

**`Freestanding` is a real answer, not a missing one.** A kernel or a bare-metal program has no
operating system, and the ABI of a freestanding ELF target is fully specified; it differs from a
hosted target on the same processor only where the OS is what fixed the convention. That is the
target a `no alloc` module (`capabilities.md`) is eventually built for.

**Freestanding does not mean self-contained, and the difference is a link error rather than a
diagnostic.** A program still names C symbols the target's runtime is expected to define — `putchar`
wherever anything prints, `free` wherever ARC can reach a release, `memcpy` and `memset` for a
structure assignment the source never wrote — and none of them is a *sysl* dependency the compiler
could report on: they are what any C compiler emits for the same code. A bare board therefore owes a
support package, which is what pico-sdk and newlib-nano are.

**The one that surprises is arithmetic.** A `long` is sixty-four bits on every target, so a 32-bit
machine without a 64-bit divider — which is all of them — turns rendering one into a call to a
compiler-rt builtin: `__divdi3` on RISC-V, `__aeabi_ldivmod` under the ARM EABI. The language decided
nothing here; the width of `long` is the language's answer and the instruction set is the machine's,
and where the two do not meet the runtime is what closes the gap, exactly as it does for C. Real
toolchains link `libgcc` or compiler-rt without being asked, which is why the requirement is
invisible until a cross-build has neither.

**A target sysl knows and cannot build for is listed anyway.** `x86-linux` is refused with a message
saying what is missing, because a reader told the name is *unknown* would go looking for a typo that
is not there. **What is missing is no longer its width** — this page said "it is 32-bit" until 32-bit
targets arrived — but a C calling convention measured against clang, which *Adding one* says is the
only way a target's answers may be arrived at. The limit is the compiler's, not the machine's.

### WebAssembly, which is not a processor

`wasm32-freestanding` is the first row here that is a **virtual** machine: a stack machine with typed
values, executed by a browser, by `wasmtime`, or by whatever else embeds one. Most of what makes it
unlike the others turns out to change nothing, and the two things that do change are both about the
link rather than about the code.

**`Freestanding` is the literal truth of `wasm32-unknown-unknown`, not a convenience.** The last field
of a triple is the operating system and `unknown` there means there is none — no libc, no loader,
nothing that runs before an exported function is called. Everything that answer decides is right in
consequence: no `-l` is implied, `hosted` and `posix` are both false, and `thread_local` answers
false, which matters here because the keyword is **accepted and silently meaningless**. LLVM compiles
a wasm `thread_local` to an ordinary data symbol unless the module is built for threads, so the
failure mode is the one that field exists for.

**The float columns read `yes` and neither word is quite literal.** There are no registers on this
machine to pass anything in; what is true is that `f32` and `f64` are types the instruction set has,
that a call takes and returns them as themselves, and that there is an `f64.add`. Neither answer
reaches a decision, because the convention above never asks — an aggregate of floating members is not
flattened here any more than an aggregate of anything else is.

**Atomics need nothing, unlike Armv6-M.** An `atomicrmw` for this triple lowers to a plain
read-modify-write with no undefined symbol, with or without `-matomics`, so a `&sync T` program links
as it stands. A wasm built for threads is a different machine — a shared memory and a real
`thread_local` — and would be a row of its own rather than a flag on this one.

**What does need saying is said to the linker, and one half of it is a green link that is wrong.**
`wasm-ld` is not a variation on `ld`, and the driver's defaults for it are a hosted program's, so
without `-nostdlib` a link opens with `crt1.o`, `-lc` and a wasm `libclang_rt.builtins.a` — none of
which exists for this triple, and the first of which is what the error names, so the failure reads as
a broken LLVM installation. That much is ordinary. The other half is not: a wasm module has no
`_start`, so `--no-entry` is the obvious spelling, and paired with the `--gc-sections` every link here
passes it leaves nothing reachable from anywhere. The linker drops the entire program and **reports
success** — 278 bytes, no `main`, exit 0. `--entry=main` is what keeps `main` and everything it
reaches, and exports it under that name for an embedder to call.

With those two, a program that prints fails at the link naming `putchar`, which is the honest report
this page describes for every bare target, and a program that does not print produces a 263-byte
module `wasmtime` runs.

**This is the first freestanding row the test suite could actually execute**, which is worth noting
because it is not what "no operating system" usually implies: there is no emulator image, no linker
script and no board, and `wasmtime` is one binary. The suite does not yet use that, and the QEMU
board list is where it would go.

**WASI is the row this one is not.** `wasm32-wasip1` is a *hosted* wasm — `printf`, files, a program
that runs rather than a module that is called — and it needs a wasi-libc sysroot. Its ABI is this same
convention with an operating system above it, and it is a row to be measured when there is a sysroot
to measure it against, not one to be reasoned into existence from this one.

### Adding one

A target is not a description of a machine. It is the set of answers codegen asks for, so:

- **the way to add a field is to have something need it** — a fact nothing reads is a fact
  nothing can be wrong about;
- **the way to add a target is to measure it**, by compiling the equivalent C with
  `clang -target <triple> -S -emit-llvm` and reading what comes out. Every row above was
  established that way. An ABI document tells you what is specified; the C compiler on the other
  side of the call tells you what is *done*, and it is the second one a call has to agree with;
- **a measurement of a default is a measurement of one compiler**, so where a row's answer came
  out of clang rather than out of the triple, the row says it instead. That is what `fpu` is: the
  same `thumbv8m.main-none-eabi` reports a floating-point unit under one clang and none under
  another, so a row that named none meant *whichever compiler is installed*. A new Thumb row
  answers this either way — `none`, or the name of the unit its part actually has.

## How a machine names itself

The compiler runs on three platforms and each describes one machine in its own words. Observed on
one Apple-silicon laptop:

| platform | processor | system |
|---|---|---|
| JVM | `aarch64` | `Mac OS X` |
| Scala Native | `aarch64` | `darwin` |
| Node | `arm64` | `darwin` |

Three runtimes, three vocabularies, and no two of them agree on both halves — Scala Native does
not even spell the processor the way its own triple does. So the **asking** is per-platform, at
the edge (`cross-platform.md`), and the **answering** is one shared function. A machine sysl has
no entry for resolves to nothing at all rather than to half a name, and the driver then says so
and stops: a guess here is the one kind of error the output would not show.

`sysl targets` always prints the words this machine's own runtime used, recognized or not. On an
unrecognized machine that line is the whole of what a report needs, and there is nowhere else to
read it.

## What a target decides

Two things, and both of them are the same thing at bottom: **what a call to a C function looks
like**. Sysl's own calls need no target's opinion, because both sides of one are this compiler and a
convention they share is a convention by construction. A foreign callee was compiled by somebody
else against a published document, and then the document is the only thing that can make the two
agree.

### How an aggregate crosses to a C function

A register-width scalar crosses as itself; an `i32` is one register everywhere. A **narrower** one
crosses as itself too but with a widening owed on it, which is the next section. An aggregate is the
case with real work in it, and **LLVM applies no C classification to one of its own accord** — given
a struct type in a signature it assigns one register per element, which is not what any of the six
conventions asks for. So a
foreign declaration names the *coerced* types the convention specifies and the call converts each
value into and out of that shape. The six:

- **AAPCS64** asks first whether the aggregate is a homogeneous floating aggregate — up to four
  members all of one floating width, however deeply nested — because those go in floating registers
  whatever their size, so four doubles are registers and five are not. Otherwise it is size: eight
  bytes or fewer in one register, sixteen or fewer in two, more than that in memory. It is the one
  convention whose two directions differ: a **result** is named by the aggregate's exact width
  (`i24` for three bytes, `i40` for five) and an **argument** by the whole register it travels in.
- **System V** classifies one eightbyte at a time: a chunk every byte of which belongs to a floating
  member goes in a floating register, anything else in an integer one, and past two chunks the whole
  thing goes in memory. Two chunks are two *separate parameters*, where AAPCS64 passed one array of
  two.
- **RISC-V** flattens the narrow floating cases: one or two floating members travel as themselves,
  and one floating member beside one integer member travels in one register of each. A pointer beside
  a float is not that case. **It is one rule at both widths**, written in terms of XLEN — LP64D and
  ILP32 are the same function with a different word, which is why adding RV32 added a parameter and
  not a convention.
- **AAPCS32** is the one whose two directions disagree about *memory itself*, and it is the only
  convention here that does. An HFA travels as the struct type itself in both directions — the
  opposite of AAPCS64, which coerces to an array. Otherwise a **result** of four bytes or fewer is
  an integer of its own width and anything larger is returned through `sret`, **always**; while an
  **argument** goes in registers *at any size* — a sixty-four-byte struct is `[16 x i32]`. So an
  aggregate too big to return is still not too big to pass, and a target where an argument is never
  indirect is a target where the indirect case is unreachable.
- **The Microsoft convention** is one, two, four or eight bytes in one integer register, anything
  else by address. No floating case at all.
- **WebAssembly** is the simplest, and the only one that asks nothing about **size**: an aggregate
  that is one scalar with structs and one-element arrays wrapped round it travels as that scalar, and
  everything else goes in memory at any size at all. So a pair of `i32` — eight bytes, which every
  other convention puts in a register or two — is `byval` here, and so is a pair of floats. There is
  no threshold to be off by one about, because there is no threshold.

Three details are worth stating because no document states them and only the measurement finds them.
System V names an integer chunk after **the member that starts it** when that member is all the chunk
carries — the `u8` after an `i64` is an `i8`, not the register it will travel in. Both AAPCS64
and RISC-V name a sixteen-byte aggregate `i128` rather than two `i64`s once it is aligned to sixteen.
And AAPCS32 picks its register *element* by the aggregate's alignment rather than by the word:
eight-aligned gives `[n x i64]` on a machine whose registers are four bytes, because what LLVM is
being told is the shape to copy and not the registers to use.

A fourth is worth stating for a different reason — it is the one this page's own rule caught rather
than the measurement. **A `byval` alignment is the stack slot's and not the type's**: System V
aligns an argument passed in memory to eight whatever the aggregate is made of, so a `char[64]` is
`align 8` and only a type that demands more gets more. Sysl said the type's alignment until
`AbiAgainstClangTests` asked clang and found the two disagreeing. The generated code was identical,
because the back end applies the minimum on its own — which is exactly why it survived every tier
below this one, and exactly why *measure it against clang* is a rule and not a habit.

**Two conventions ask for that copy and they state its alignment by different rules**, which is why
the answer is a function of the target rather than a flag. System V floors it at eight as above;
WebAssembly states the type's own and nothing more, so the same `char[64]` is `align 1` there. A
single rule would have been right for one of them and quietly wrong for the other.

**That test is the rule made mechanical.** Every convention above is now re-derived from clang on
every run: the equivalent C is compiled for the same triple and the `declare` it produces has to be
the one sysl produces. A table written by reading clang once is only as good as the reading, and a
misreading is pinned by its own test exactly as firmly as a correct one.

Only the boundary is affected. A struct handed over **by address** needs none of this, which is why
that was the workaround while the boundary was broken, and a sysl-to-sysl call is untouched.

### How a scalar narrower than a register crosses

A `u8` is an `i8` to both compilers, so there is nothing to coerce — and it still does not cross for
free. It travels in a register a whole word wide, and what the conventions disagree about is **the
state of the bits above it**. Most of them settle it by making whoever hands the value over widen it
first, `signext` or `zeroext` by the type's own signedness, and LLVM emits that widening only where
the signature asks for it. A declaration without the attribute passes a register whose top bits are
whatever was left in it; a callee compiled by clang, which was promised otherwise, then acts on a
number nobody wrote.

**Two obligations, falling on opposite sides.** Widening an *argument* is the **caller's**, so sysl
writes it at a foreign call and on the declaration that call names. Widening a *result* is the
**callee's**, so sysl writes it on every definition it emits — including ones no C will ever call,
because a definition cannot know that and a sysl caller is not harmed by a guarantee it never asked
for. Nothing is written on a sysl *parameter*: neither end of a sysl-to-sysl call claims the
extension, so neither may rely on it, and they agree the way they agree about everything else.

That second half is why `@export` and `&f` need no rule of their own. Both hand a sysl definition to
C, and the definition already states what it owes.

Three conventions depart from the ordinary rule, and each was found by measuring rather than by
reading:

- **AArch64 away from Darwin widens nothing at all**, `_Bool` included: AAPCS64 leaves the top bits
  unspecified and makes the callee narrow what it reads. Apple's variant of the same convention does
  widen, so the two aarch64 targets in the registry disagree and both are right.
- **The Microsoft convention widens `_Bool` and nothing else**, so `char` and `short` cross bare.
- **RISC-V 64 widens a 32-bit value too**, and `signext` whether or not it is signed — an
  `unsigned int` is *sign*-extended into a 64-bit register. It is the one place a convention asks for
  an extension that contradicts the type's own signedness.

A width C cannot spell — `i5`, `u12` — takes the ordinary rule for its width, there being no C
declaration to measure against.

This is the part of the boundary that was missing longest, because nothing about it is visible in a
type. It was found when a `u8` computed by a `match` reached a C function as a different number,
which had gone unnoticed through every tier: the IR verified, the program linked, and only the
callee's arithmetic was wrong. `AbiAgainstClangTests` now asks clang for every width on every target,
the same way it asks about every aggregate shape.

### How a walk over a variadic tail reaches a C function

C's `va_list` is a different type on every target and is passed three different ways (`12 §9`), so
the address of the walk — the only thing sysl has — crosses over as:

- **loaded** — the storage holds one pointer-sized value and the call passes *that value*. Darwin
  arm64, where `va_list` *is* `char *`; Windows x64; RISC-V at both widths; and AAPCS32, whose
  `va_list` is a one-member struct that clang declares as `[1 x i32]`. **That last one looks like a
  fourth answer and is not** — a struct of one word passed in one core register is what *loaded*
  already describes, and compiling the call both ways gives the identical instruction. It was
  checked rather than assumed, after a fourth case had already been written.
- **address** — the storage is an array of one struct, which decays, so the call passes the
  address of the storage itself. x86-64 System V.
- **copied** — the storage is a struct passed indirectly, so the call passes the address of a
  fresh copy. AAPCS64 everywhere but Darwin.

All three pass one `ptr`. That is exactly why the choice has to be recorded rather than
rediscovered: nothing downstream could tell from the IR which of the three a module was built
with, and a module built with the wrong one links and runs and reads garbage.

**The emitted module states its triple.** LLVM derives the data layout from it, so a module says
what it is rather than taking on the character of whatever reads it. The driver passes the same
triple to `clang`, which is what makes naming a cross target fail honestly at the link — for want
of a sysroot — instead of quietly producing a host binary.

## What a target does not decide

**Layout — which depends on the word, and on nothing else a target carries.** The questions
`Layout` answers have one answer per address width: scalars are their own width and aligned to it,
an address is one word, an aggregate is laid out in declaration order with each member on its own
alignment and the whole rounded up to the widest. That is C's rule and LLVM's. So `Layout` takes a
`Word` rather than a `Target`.

**That is a stronger statement than taking nothing, and it is worth saying why.** This page used to
claim `Layout` needed no target at all, resting it on every target in the registry being 64-bit —
which stopped being true the moment `thumb-freestanding` and `riscv32-freestanding` were added. A
claim that something depends on *nothing* is only ever as good as the registry that makes it
vacuous; naming what it depends on survives the registry growing. The operating system, the
`va_list` walk and the calling convention are all still outside it: two targets of one width lay
every aggregate out identically, and `TargetTests` asserts exactly that by emitting one program for
each of them.

**A width's reach is narrow, which is the other half of the same point.** Only one LLVM *type* in
the language mentions it: a view is `{ ptr, ptr, iN }`, because its length is a `usize`. An
aggregate of fixed-width scalars, an array, a data enum's union region and a `va_list` are spelled
identically at both widths — so the types that move between a 64-bit module and a 32-bit one are
exactly the ones with a view somewhere inside them, and `TargetTests` asserts that from both ends.

That is worth separating from the section above, because the two are easy to run together: **where a
member sits inside an aggregate is one question and which register the aggregate travels in is
another.** Targets of one width answer the first identically, which is why `Layout` needs no more
than that width; they disagree about the second whatever their width, which is why the
classification takes the whole target. The classification is built *on* the layout — it asks which
members share an eightbyte, and that is the layout's answer.

**The storage a `va_list` occupies.** Sysl reserves the widest any target needs (32 bytes,
AAPCS64's) for all of them. The waste is a few bytes of one stack slot in a variadic function,
and the alternative — a storage type that varies per target — would put a target-dependent
*type* into the emitted text for no gain, since nothing but `va_start` and the three lowerings
above ever looks at it. What is per-target is the number of bytes *copied* out of it, which is
the target's own `va_list` and not sysl's storage for one.

## Conditional compilation

Everything above is a fact the *compiler* reads about the machine. `#if` is the one place a
**program** reads one, and it exists because the machines genuinely differ in ways a library
cannot paper over: a syscall number, a struct a header lays out two ways, a symbol one libc
exports and the other does not.

```
#if macos
extern "printf" say(fmt: *u8, ...) -> i32
#else
extern "printf_chk" say(fmt: *u8, ...) -> i32
#endif
```

`#if` / `#elif` / `#else` / `#endif`, nesting freely, and **the branches are exclusive** — the
first whose condition holds is the one that contributes, and a group inside a branch that was not
taken contributes nothing however its own condition reads.

### It gates lines, before the lexer sees anything

A line in a branch this build is not for is **replaced by an empty line, not removed**, and so is
every directive line. After the pass the file is an ordinary sysl file that happens to have some
blank lines in it, and nothing downstream knows any of this happened.

Replaced rather than removed because **every line below a gate has to keep the number it was
written at**. Deleting them would leave the messages right and the carets somewhere else, and
nothing would say so.

**A directive sits at the margin, column 1.** That is a rule, not a convention. Sysl is
indentation-sensitive, and indentation is how the language reads block structure — so a gate
written *in* that channel would look like it takes part in a nesting it has nothing to do with,
when in fact the line is gone before anything counts a column. At the margin it is visibly not
part of the code's shape, which is what it is. It is also how C is written, and it is what keeps a
declaration's `@test` attribute — indented with its declaration — from ever being mistaken for one
of these.

**Why lines and not a construct wrapping declarations.** Rust spells this `#[cfg]`, an attribute on
an item, and can because Rust is brace-delimited: the attribute attaches without moving anything.
Here the equivalent would have to take an indented block, so adding or removing a platform gate
would reindent everything inside it — a one-line intent showing up as a whole-body diff. A flat
marker disturbs nothing.

### The symbols are derived from the target, and the set is closed

| kind | symbols |
|---|---|
| operating system | `macos`, `linux`, `windows`, `freestanding` |
| processor | `aarch64`, `x86_64`, `riscv64`, `riscv32`, `thumb`, `x86` |
| derived | `hosted` (not `freestanding`), `posix` (`macos` or `linux`) |

That is the whole vocabulary. There is no `#define`, nothing a project can add, and **no dependence
on the project config** that is still open below — which is what let this be built at all. A
condition is a symbol, `!`, `&&`, `||`, and parentheses; `&&` binds tighter than `||`.

`posix` is a name for the commonest disjunction rather than a replacement for writing it: `#if
linux || macos` still says the same thing. Old sysl banned `&&` and `||` outright, and this does not
follow it there — in a flat line-marker scheme the only other way to write a disjunction is to nest
the groups, which reads far worse for the sake of a boolean evaluator over a set of strings.

**A symbol nobody knows is an error, not false.** The set is closed, so a name outside it is a
mistake rather than a fact this build happens not to have — and a misspelling that read as false
would gate code out of the build with nothing said. Silently missing code is the one failure this
feature cannot be allowed to have, and it is the one C has.

**Every condition is checked, in the branch being taken and the ones being skipped alike.** So the
misspelling in the Linux half is caught by a macOS build, which is where it would otherwise sit
until somebody built for Linux.

A target's *name* is not a symbol — it has a `-` in it, which no identifier carries — and writing
one is told to write `aarch64 && macos` instead, because otherwise the reader is told that `-` is
not an operator, which is true and no help.

**`posix` here is not `capabilities.md`'s `posix`, and the two are not going to be merged.** This one
asks *is this a POSIX system*, which is a fact about the machine and is settled by the target. That
one asks *may this module use POSIX*, which is a permission a project grants and a `no posix` clause
takes away — so a build can perfectly well be for Linux and deny it. They agree today because
nothing denies anything yet; they are different questions and would part the moment something did.
Whether a condition should be able to ask the second one is left with the config that would define
it (`§ Open`).

### What is given up, and what is not

**The inactive branch is never syntax-checked.** That is the price of gating text rather than
trees, it is C's price too, and a Linux branch can therefore rot while the macOS build stays green.
What finds that is a build for each target — a thing to *run*, not a thing to design around. The
conditions themselves are the part that is checked everywhere, and they are the part where a
mistake would otherwise be silent.

**The gate runs before anything knows what a string or a comment is**, so a line that begins at the
margin with a directive word is a directive even inside a text block or a block comment. Recognizing
those would mean a second copy of the lexer's rules about literals, in a place where the two could
drift with nothing to notice — a worse defect than this one. The margin rule is what keeps it rare:
a text block written anywhere but the top level is indented in the source, whatever its value turns
out to be.

### The library is subject to it too

`library/sysl` is sysl source, so it may gate on the machine like any other — which makes "the standard
module" a question with a target in it, and the compiler's carried copy is parsed per target
accordingly. The one thing held fixed is that **a name the compiler spells for itself is declared on
every target**: a library that gated `Option` away for Windows would be a library nothing compiles
against there, so it is refused in the registry-wide check rather than at the first `?` somebody
writes.

**An artifact records the target it was built for** and is refused by a build for another, because
the trees a library ships are now a per-target answer. `13 §8` has the rest.

## Open

- **The project config — designed in `packages.md`, and built.** `package.hocon` carries
  per-target capability sets (`capabilities.md`'s `alloc` / `os` / `posix` / `threads`), and they are
  parsed and enforced: a target that provides no allocator makes every module of the program
  allocator-free with no clause written anywhere. **What is left open is filename-axis platform
  selection**, and that alone — this item said "unbuilt" of the whole thing, five lines below a
  header on this same page saying the capability sets were built, which is the ordinary way a status
  claim written twice goes stale in one of its two places. The registry here is the fixed table that config
  extends, and deliberately does not try to be one: a target's *capabilities* are exactly the part
  a project has an opinion about. `packages.md § 2` states the boundary the other way round —
  **a config may add capabilities to a registry target, and may not overrule a measured ABI fact** —
  which is what keeps *Adding one* above honest: an ABI answer is measured against clang, not
  configured.
- **Whether a condition may ask about a capability.** `#if` asks only what the *target* says, which
  is what let it be built while the config is still open. Asking `#if no alloc` is a coherent thing
  to want and belongs with the config that would define it — and it is where the two `posix` senses
  above would have to be told apart in the syntax.
- ~~**32-bit targets.**~~ **Built.** `thumb-freestanding` and `riscv32-freestanding` are in the
  registry and build. What this item described — the emitted code assuming a 64-bit address in
  places nothing had been asked to parameterize — was real, and the parameter is `Word`: `Layout`
  takes one, `Type.llvm` takes one, and there is deliberately **no default**, so a site that needs a
  width and was not given one fails to compile rather than quietly emitting a 64-bit type. Six such
  sites existed and every one of them was a `usize` spelled `i64`.

  `x86-linux` is still in the registry and still refused, but **the reason changed**: not that it is
  32-bit, which no longer disqualifies anything, but that no C calling convention has been measured
  for i386. That is the honest statement of what is missing, and it is the same sentence the
  compiler now prints.
- **Cross-linking.** Building for another machine emits a correct module and then hands it to a
  `clang` that has no sysroot for it. That is the toolchain's problem to solve and sysl's to
  report clearly, not to work around.

  **`wasm32-freestanding` is the first cross target that does link**, and it is not an exception to
  that: it links because it needs no sysroot at all, having no libc to find one for. What it did need
  was for the driver to be told so — `-nostdlib`, and an entry — which is the same "report clearly"
  half of this item, met by saying the right thing to the linker rather than by supplying anything.
  The row that would test the item properly is the WASI one, which has a sysroot and has not got it
  here.
- **Sub-architectures.** `-mcpu` / feature levels — a target today is a processor family and a
  system, and nothing yet needs finer.

  **One feature flag has since been needed, and it did not open this.** A row for an FPU-less core
  carries `-mfpu=none`, which is a feature level in clang's own terms — but it is recorded as a *fact
  about the machine* (`Target.noFpu`) with the flag derived from it, rather than as a `-mfpu` a
  caller may set. That is the shape this item would have to keep if it is ever built: what the H7's
  double-precision unit wants is not "a way to pass `-mcpu`", it is a row that answers a question
  codegen asks. The distinction is the whole of *A target is a value, not an ambient fact*.
