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
| 64-bit division | rendering a `long`, which divides by ten — and neither core has the instruction |

`bsp_rv32.c` and `bsp_thumb.c` supply all of them plus a bump arena. That is what a real bare-metal
project has too, under another name: pico-sdk's, or newlib-nano's. **It is not a way round anything**
— a program compiled under the default capabilities may allocate, so a board running it owes it an
allocator, and one that prints needs somewhere to print.

**The division is the one that arrives as a surprise, and its name depends on the architecture.**
RISC-V asks for `__divdi3`; the ARM EABI asks for `__aeabi_ldivmod`, which answers the quotient *and*
the remainder in registers no C signature can name, so it is a naked wrapper around an ordinary
helper. `divmod64.h` has the arithmetic and both files spell their own names. A real project links
`libgcc` or compiler-rt for these and never sees them; this machine has **no cross builtins at all**,
which is why they are here. Nothing below a link would have said so — a call to a function nothing
defines makes a perfectly good object file, which is this tier's argument in one sentence again.

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

**The image is linked at 0x10000000, the SECURE alias of the code SRAM, and this is the single most
important line in this directory.** An ARMv8-M core comes out of reset in the secure state and reads
its initial `SP` and reset vector from the *secure* vector table; the board's IDAU splits the map by
bit 28, so `0x0xxxxxxx` is the non-secure view of the same memory and `0x1xxxxxxx` is the secure one.
Linked at zero, the address the CPU actually reads is full of zeros.

**What that cost was not a board that refused to boot, which is exactly why it survived so long.**
With `SP` and `PC` both read as zero the core faults at once, escalates to a secure HardFault whose
vector is also zero, and branches to `0x1` — address zero with the Thumb bit — where it starts
executing the vector table and the unwind tables *as instructions*, runs off the end of them, and
falls into `_start`, which sets a real stack and calls `main`. The program then runs and prints the
right answer. It does that whenever the bytes ahead of the entry point happen to decode as harmless
Thumb, which is a property of how many functions the image has and what the linker put in
`.ARM.exidx` — so this directory spent a long time believing the board was *"sensitive to the image's
layout"*, with two tests `ignore`d for it and a warning in `start_thumb.s` never to grow the vector
table.

**One command said so, and it was the first line of every run all along:**

```
$ qemu-system-arm -M mps2-an505 -nographic -semihosting-config enable=on,target=native -d int -kernel <elf>
Loaded reset SP 0x0 PC 0x0 from vector table
Taking exception 18 [v7M INVSTATE UsageFault] on CPU 0
...taking pending secure exception 3
...loading from element 3 of secure vector table at 0x1000000c
...loaded new PC 0x1
ok
```

`0x1000000c` is the fourth word of the table the CPU wanted, and it names the address the image
should have been at. **The lesson is about the evidence rather than the board**: the healthy runs
printed those same lines, and the reason nobody read them is that they were healthy. A trace was only
ever taken of a failing run, where three lines of prologue look like part of the failure.

**The stack is at the top of the code region.** The board also has a small SRAM at `0x20000000` — a
store there succeeds and one at `0x20008000` faults, so it is 32K and a script claiming megabytes
would hand out a stack backed by nothing. Putting the stack there was tried and changed nothing, so
the simpler map stayed.

**The UART is the CMSDK one at 0x40200000, and it transmits nothing until it is enabled.** Its
registers are *words*, not bytes: `DATA` at 0x00, `CTRL` at 0x08, `BAUDDIV` at 0x10. Set `BAUDDIV`
to at least 16 and `CTRL` to 1 before writing. A program that writes to it cold produces **no output
and no error** — QEMU says `transmit data write with Tx disabled`, and only under `-d guest_errors`.

**A `.word` naming a function must NOT have `+1` added.** The linker resolves a `.thumb_func` symbol
with the Thumb bit already set, so adding one clears it, and a reset arriving through the table
enters in ARM state — which a Cortex-M cannot do. What that looks like is `UsageFault INVSTATE`
(`CFSR` bit 17) and a lockup reported wherever the CPU wandered to, which is nowhere near the vector
table. This mattered less than it looked while nothing was reading the table; it matters now.

**The faults have a handler, and it exits rather than reporting.** Without one they all say the same
thing: a fault with no vector escalates to HardFault, the CPU branches into whatever follows the
table, and QEMU says `Lockup: can't escalate 3 to HardFault` at a meaningless address — for every
possible cause, and only after the wall-clock alarm has run out. The handler leaves through the same
semihosting exit a clean run does, with status **99**, so a faulting program stops immediately and
the suite reports a board fault instead of a timeout. It deliberately does not print `CFSR`: that
needs the UART enabled and a hex routine, which is a program to get wrong inside the thing that
reports other programs going wrong, and `-d int` says it better for nothing.

## thumbv7em — `qemu-system-arm -M mps2-an500 …`

A Cortex-M7, and the architecture the STM32 Nucleo boards use. Most of it is the AN505's board with
two addresses changed; what is worth knowing is what **stops** applying, because the temptation is to
copy the sibling wholesale.

**The secure alias is gone, and with it the worst trap in this directory.** `thumb.ld` links at
`0x10000000` because an ARMv8-M core boots secure and the AN505's IDAU splits the map at bit 28.
**Armv7E-M has no TrustZone**, so there is one view of memory and `thumbv7em.ld` links at the real
address — `mps.ssram1`, 4 MB of RAM at `0x00000000`. The vector table is also one entry shorter:
slot 7 is SecureFault on an M33 and reserved here.

**The UART is the same CMSDK device at `0x40004000`**, first of five in the APB region, and
`-nographic` wires it to QEMU's stdout. Everything the AN505 section says about it still holds: word
registers, and silence until `BAUDDIV` >= 16 and `CTRL` = 1.

**The startup enables the FPU, which no other board here does.** The triple is
`thumbv7em-none-eabihf`, so a `double` travels in `d0`/`d1` and the back end may reach for VFP
anywhere — and a Cortex-M denies coprocessor access out of reset, so the first such instruction
raises a UsageFault with the NOCP bit and the program dies somewhere arithmetic, **blaming the
arithmetic**. `CPACR` is at `0xE000ED88`, bits 23:20. `start_thumb.s` omits this and has got away
with it because nothing that has run on the AN505 used a float; that is a property of the test
programs rather than of the board, and it is not one to inherit on a target whose whole reason for
existing is its FPU.

**The M4F end of this target is not wired up.** One row serves both Nucleo cores on the argument
that an M4F and an M7 both pass a `double` in `d0`/`d1` under `eabihf` — and nothing here tests that,
because `boards` is keyed by target and one target admits one recipe. `netduinoplus2` (an STM32F405)
is the machine for it, and it needs more than an address: its flash is a **ROM** region at
`0x08000000` aliased at zero, so `.data` has to be copied to SRAM by the startup, which no board
here does yet.

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

**Keep an image that PASSES too, and run `-d int` on it.** The boot bug above was invisible for as
long as it was because every trace ever taken was of a failing run, where three lines of exception
prologue read as part of the failure. Side by side with a healthy run's — identical, up to the point
where one of them recovered — they name the cause in a sentence.

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

**Rendering a `long` needs the board to supply 64-bit division.** A `long` is sixty-four bits on
every target and neither of the RP2350's cores has the instruction, so `lib/sysl/display.sysl`
dividing by ten becomes a call to `__divdi3` or `__aeabi_ldivmod`. That is not a defect in anything —
C does the same here — but it is a link error with no other warning, and a real project only never
sees it because pico-sdk has already linked `libgcc`.

**Nothing below this tier could have seen either of them.** Every other cross-target test stops at an
object file, and a call to a function nothing defines makes a perfectly good object. Freestanding
targets had been in the registry for months with no program ever linked for one.
