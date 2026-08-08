# Booting a target under QEMU

What a freestanding module needs before it can *run*, as opposed to merely assemble
(`CrossTargetBuildTests`). Both boards are the RP2350's, which is why they are the two here: one
board with two architectures is testable in a way a family is not.

Each board is four files — a startup, a linker script, a support package, and an entry in
`QemuSupport.boards` naming the emulator.

## What a board owes the library, and why it is not scaffolding

**A freestanding sysl program is not a program that touches nothing outside itself.** Four C symbols
are named by name, and a bare board has to supply them or the image does not link:

| symbol | reached from |
|---|---|
| `putchar` | `sysl.putbytes`, so every `print` and every `Display` rendering onto standard output |
| `free` | ARC's release path, **whether or not it is ever reached** — a call to a function nothing defines is a link error on a path never taken (ticket 0037) |
| `memcpy`, `memset` | a structure assignment and zeroing, whatever the source said |

`bsp_rv32.c` and `bsp_thumb.c` supply all four plus a bump arena. That is what a real bare-metal
project has too, under another name: pico-sdk's, or newlib-nano's. **It is not a way round anything**
— a program compiled under the default capabilities may allocate, so a board running it owes it an
allocator, and one that prints needs somewhere to print.

## The startup zeroes `.bss`, and that is not optional

`.bss` is a `NOBITS` section: it occupies no bytes in the file, so a loader writes nothing for it and
a program finds whatever the RAM held. On a hosted system the loader makes the storage read as zero;
**on a board it is the startup's job and nobody else's**, and both startups here do it between
setting the stack and calling `main`.

**What it costs when nobody does is not a subtly wrong number.** sysl puts a module's `var` in `.bss`
whenever its initializer is zero — so `sysl.harness`'s `out`, a `*Writer` starting as null, arrives
as a garbage fat pointer, and the first call through its method table branches wherever the garbage
points. Two words of uninitialized storage, and the report is a lockup in the middle of a data
section that names nothing and points nowhere near the cause.

It was found by exactly that route, and the tell was **sensitivity to program shape rather than to
program meaning**: moving one statement above a declaration changed a passing image into a faulting
one, which is not how a semantic bug behaves.

## riscv32 — `qemu-system-riscv32 -M virt -bios none -nographic -kernel <elf>`

`start_rv32.s` sets a stack, zeroes `.bss`, calls `main`, and reports its return value through the
**`sifive_test` device at 0x100000** rather than through semihosting: writing `0x3333 | (code << 16)`
makes QEMU exit with `code`, which is a whole result channel for four instructions and no debug host.

It reports **unconditionally** rather than branching on zero. An earlier version wrote `0x5555` for
the success case and the failure code otherwise, and reported 0 for a `main` that returned 7 — the
unconditional form is both simpler and the one that was observed to work. `main` returning 0 exits 0.

Link with `-nostdlib -T rv32.ld`, and `-fuse-ld=lld`: this is an ELF target and the system linker
on a Mac is not one.

**Printed output needs no semihosting at all.** `-M virt` puts a 16550 **UART at 0x10000000**, and
`-nographic` pipes it to QEMU's stdout, so a `putc` is a volatile store of one byte to a fixed
address. **A volatile store through a pointer is a language feature**, so a board reaches its console
with no `asm` and no debug host — which is what makes `sysl.harness` work here at all.

## thumb — `qemu-system-arm -M mps2-an505 -nographic -semihosting-config enable=on,target=native -kernel <elf>`

A Cortex-M33, the RP2350's Arm core. Everything below differs from the RISC-V half and **every one of
them was measured rather than read**, because each fails in a way that blames the wrong thing.

**The result channel is semihosting `SYS_EXIT_EXTENDED` — `0x20`, not `0x18`.** On AArch32 the
plain `SYS_EXIT` takes its reason in `r1` and **always reports zero**, so a `main` returning 7
arrives as 0 and every assertion made against the status is worthless while looking fine. The
extended call takes a pointer to `{0x20026, code}`. Semihosting also has to be asked for: without
`-semihosting-config` the `bkpt` is a debug event nobody handles.

**The startup sets `sp` itself.** A Cortex-M boots through a vector table and `start_thumb.s` has
one, but QEMU's `-kernel` enters at the **ELF entry point** and does not load `SP` from it. A
program that trusts the table runs with a junk `SP` and faults on the first push — *inside* whatever
it called first, so the register dump blames the callee.

**Everything lives in the code SRAM at 0x00000000, stack included**, which is a measurement rather
than a preference. The board does have an SRAM at `0x20000000`, and it is small: a store at
`0x20000000` succeeds and one at `0x20008000` faults, so a script claiming a few megabytes there
gives a stack pointer backed by nothing. The code region is proven mapped by the program running out
of it. Moving the stack to that 32K SRAM was tried and changed nothing either way, so the simpler
map stayed.

**The UART is the CMSDK one at 0x40200000, and it transmits nothing until it is enabled.** Its
registers are *words*, not bytes: `DATA` at 0x00, `CTRL` at 0x08, `BAUDDIV` at 0x10. Set `BAUDDIV`
to at least 16 and `CTRL` to 1 before writing. A program that writes to it cold produces **no output
and no error** — QEMU says `transmit data write with Tx disabled`, and only under `-d guest_errors`.

**A `.word` naming a function must NOT have `+1` added.** The linker resolves a `.thumb_func` symbol
with the Thumb bit already set, so adding one clears it, and a reset arriving through the table
enters in ARM state — which a Cortex-M cannot do. What that looks like is `UsageFault INVSTATE`
(`CFSR` bit 17) and a lockup reported wherever the CPU wandered to, which is nowhere near the vector
table. The reset entry was `_start+1` for a long time and the board tolerated it, because QEMU
usually enters at the ELF entry instead; it is `_start` now, which is correct either way.

### The MPS2's boot is sensitive to the image's LAYOUT, and this is the open problem

Two changes, neither of which alters what any program does, each turn **every** thumb program here
from green to a lockup:

- growing `.vectors` from two words to seven, to give the fault vectors a handler;
- discarding `.ARM.exidx`, the unwind tables nothing reads, which otherwise land as an orphan section
  **between the vector table and the code**.

So the arrangement that boots is: a two-word `.vectors`, then `.ARM.exidx`, then `.text` — which
puts the vector table and the unwind tables in one read-only segment and the code in another. Change
either and QEMU starts executing at address 0 in ARM state, which `-d in_asm` shows directly:

```
Loaded reset SP 0x0 PC 0x0 from vector table
IN: 0x00000000:  20008000  andhs r8, r0, r0
Taking exception 18 [v7M INVSTATE UsageFault] on CPU 0
```

`virt` runs the identical sources with none of this. **What is ruled out**: it is not the compiler
(the same program in a different shape compiles to the same code and boots), not `.bss` (zeroed
since), not the stack's size or position (moving it to the other SRAM changed nothing), and not
signedness or width in the program. One test in `QemuHarnessTests` is `ignore`d for it — the largest
image the suite builds — with the assertions it should make.

**The cost of not having the fault handlers is worth stating**, since the temptation is to add them
back: a fault with no handler escalates to HardFault, whose vector is past the end of a two-word
table, so the CPU branches into whatever follows and QEMU reports `Lockup: can't escalate 3 to
HardFault` at a meaningless address — **the same report for every possible cause**. Wiring five
vectors to a handler that prints `CFSR`/`HFSR`/`MMFAR` *does* work, and is how the `INVSTATE` above
was identified; it just cannot be left in.

## Wired into the suite

`QemuSupport` links a module against these files, boots it, and answers with the exit status and the
board's output; `QemuRunTests` makes codegen claims and `QemuHarnessTests` runs whole suites through
`sysl.harness`. **Missing tools cancel the test by name rather than passing it** — cancelled shows in
scalatest's count where a pass does not, so the gate stays honest on a machine with no QEMU and loud
on one that had it yesterday.

**QEMU has no wall-clock limit of its own**, so the emulator is run under `perl -e 'alarm'`. A
program that never reaches the exit device would otherwise run until the suite is killed, and perl
ships everywhere this builds.

**The emulator's own standard error is folded into the guest's output.** QEMU reports a fault the
guest cannot survive on stderr and then aborts, so a test reading only stdout sees an empty string
and a status of 134 — which names nothing. Merged, the last thing the program printed sits
immediately above what killed it.

**`SYSL_QEMU_KEEP=<dir>` copies each linked image there before it is deleted.** It is the only way to
get a disassembly of a board program that misbehaved: the image is a temporary that goes when the JVM
exits, so reading its path out of a log is too late. Nothing reads the variable in an ordinary run.

## What is still owed

- **A freestanding entry point takes `argc` and `argv`.** `define i32 @main(i32 %argc, ptr %argv)`
  is emitted for `thumb-freestanding` and `riscv32-freestanding` alike, which is a hosted
  convention on a machine that has nobody to pass them. The startups here pass zeros. It is a
  design question rather than something to paper over.
- **A program cannot choose its own exit status.** `main` may not be declared to return one — *"a
  program's exit status is not something a signature can say"* — and `exit` is a binding to C's,
  which a bare board has not got. So `sysl.harness`'s `finish()` can be *printed* but not returned,
  and this tier reads the verdict off the UART. A board can signal it with a volatile store to its
  own device, which is what `finish` returning rather than exiting is for.

## What this tier found

**Releasing a slice calls `@free`, and an `@no_alloc` module emits it too** — ticket 0037.
`capabilities.md` puts slices *in* the no-alloc subset, says the free path "goes through the object's
own hook", and calls such a module allocator-free and portable to every target.
`NoAllocEmissionTests` carries the diagnosis and the contrast; the claim about `@no_alloc` is
`ignore`d with the assertion it should make.

**Nothing below this tier could have seen it.** Every other cross-target test stops at an object
file, and a call to a function nothing defines makes a perfectly good object. Freestanding targets
had been in the registry for months with no program ever linked for one.
