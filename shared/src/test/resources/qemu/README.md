# Booting a target under QEMU

What a freestanding module needs before it can *run*, as opposed to merely assemble
(`CrossTargetBuildTests`). Proven end to end for riscv32 with C on 2026-08-08; the Thumb half is
not written yet.

## riscv32 — `qemu-system-riscv32 -M virt -bios none -nographic -kernel <elf>`

`start_rv32.s` sets a stack, calls `main`, and reports its return value through the **`sifive_test`
device at 0x100000** rather than through semihosting: writing `0x3333 | (code << 16)` makes QEMU
exit with `code`, which is a whole result channel for four instructions and no debug host.

It reports **unconditionally** rather than branching on zero. An earlier version wrote `0x5555` for
the success case and the failure code otherwise, and reported 0 for a `main` that returned 7 — the
unconditional form is both simpler and the one that was observed to work. `main` returning 0 exits 0.

Link with `-nostdlib -T rv32.ld`, and `-fuse-ld=lld`: this is an ELF target and the system linker
on a Mac is not one.

## Wired into the suite

`QemuSupport` and `QemuRunTests` are the Scala side: the support trait links the module against
these files, boots it, and answers with the exit status and the UART's output; the suite makes the
claims. **Missing tools cancel the test by name rather than passing it** — cancelled shows in
scalatest's count where a pass does not, so the gate stays honest on a machine with no QEMU and
loud on one that had it yesterday.

**QEMU has no wall-clock limit of its own**, so the emulator is run under `perl -e 'alarm'`. A
program that never reaches the exit device would otherwise run until the suite is killed, and perl
ships everywhere this builds.

## What is still owed

- **The Thumb half.** `qemu-system-arm -M mps2-an505` is a Cortex-M33, the RP2350's exact core. It
  has no `sifive_test`, so the result channel there is semihosting `SYS_EXIT` — `bkpt 0xAB` with
  `r0 = 0x18` and `r1` pointing at `{0x20026, code}` — and it boots through a vector table rather
  than a bare entry point. Its UART is the CMSDK one at 0x40004000.
- **A freestanding entry point takes `argc` and `argv`.** `define i32 @main(i32 %argc, ptr %argv)`
  is emitted for `thumb-freestanding` and `riscv32-freestanding` alike, which is a hosted
  convention on a machine that has nobody to pass them. The startup here passes zeros. It is a
  design question rather than something to paper over.

## What this tier found on its first run

**Releasing a slice calls `@free`, so a bare-board program that passes one to a function does not
link.** ARC's release path names the allocator directly, and an `@no_alloc` module emits it too —
while `capabilities.md` puts slices *in* the no-alloc subset, says the free path "goes through the
object's own hook", and calls such a module allocator-free and portable to every target.

**Nothing below this tier could have seen it.** Every other cross-target test stops at an object
file, and a call to a function nothing defines makes a perfectly good object. Freestanding targets
had been in the registry for months with no program ever linked for one.

`NoAllocEmissionTests` carries the diagnosis, and the two tests that need the fix are `ignore`d with
the assertions they should make.

## Printed output, which needs no semihosting at all

`-M virt` puts a 16550 **UART at 0x10000000**, and `-nographic` pipes it to QEMU's stdout. So a
`putc` is a volatile store of one byte to a fixed address — verified, with
`harness: 3 passed, 0 failed` arriving on stdout beside an exit status of 0.

**That is the whole output channel, and sysl can already write it**: a volatile store through a
pointer is a language feature, so a harness running on the target needs neither `asm` nor a debug
host to say *which* test failed. Semihosting would have needed both.

The two channels are complementary and a runner should use both: **stdout says what failed, the exit
status says whether anything did.** A count alone cannot identify a failure that happens only on the
target, because that is precisely the failure which does not reproduce on the host to be looked at.
