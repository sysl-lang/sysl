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

## What is still owed

- **The Thumb half.** `qemu-system-arm -M mps2-an505` is a Cortex-M33, the RP2350's exact core. It
  has no `sifive_test`, so the result channel there is semihosting `SYS_EXIT` — `bkpt 0xAB` with
  `r0 = 0x18` and `r1` pointing at `{0x20026, code}` — and it boots through a vector table rather
  than a bare entry point. Its UART is the CMSDK one at 0x40004000.
- **A `QemuSupport` in the test tree**, analogous to `RunSupport`: link, run, capture both channels,
  fail the Scala test on a non-zero status and attach the output to the failure.
- **A freestanding entry point takes `argc` and `argv`.** `define i32 @main(i32 %argc, ptr %argv)`
  is emitted for `thumb-freestanding` and `riscv32-freestanding` alike, which is a hosted
  convention on a machine that has nobody to pass them. The startup here passes zeros. It is a
  design question rather than something to paper over.

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
