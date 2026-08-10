# sysl on a NUCLEO-G491RE

The first sysl program to run on real Armv7E-M silicon, and the reason it is kept: everything else
that exercises `thumbv7em-freestanding` runs under QEMU, and an emulator agreeing with the compiler
says nothing about a part.

**Nothing in the test suite reads this directory.** It cannot — running it needs a board on a USB
port. It is here as reading material and as a recipe that is known to work, which is worth more than
it sounds: every line of it was arrived at against hardware that failed in ways the documentation did
not predict.

## What the board is

An STM32G491RE — a Cortex-M4F, which is the *other* core `thumbv7em-freestanding` serves. 512K of
flash at `0x08000000`, 128K of SRAM at `0x20000000` of which the linker script claims the first 80K.
LD2, the user LED, is on PA5.

## What is different from every QEMU board

**This one runs from flash**, and no board under `shared/src/test/resources/qemu/` does. So `.data`
has an address in RAM and a *load* address in flash, `g491.ld` needs the `> RAM AT > FLASH` clause and
a `__data_load = LOADADDR(.data)`, and `start_g491.s` has to copy the section across before `main`.
The QEMU boards are all RAM-at-zero, where `.data` is already where it is going to be and the copy
loop would be a no-op.

Everything else is shared with the emulated boards: the vector table's first two words, the stack
pointer set in the startup rather than trusted from the table, `.bss` zeroed by hand, and the CPACR
write that enables the FPU — a Cortex-M denies coprocessor access out of reset, and `eabihf` means
the back end may reach for VFP anywhere.

The vector table here is shorter than the MPS2 ones and has no fault handler: there is no semihosting
host to report a status to, so a fault has nowhere to go and `hang` is the honest answer.

## Building and flashing it

The compiler emits LLVM IR; clang assembles the startup and links; `objcopy` flattens the ELF.

```
sbt -batch -error "syslJVM/run emit-llvm --target thumbv7em-freestanding hardware/nucleo-g491re" > blink.ll

CC=/opt/homebrew/opt/llvm/bin/clang
$CC --target=thumbv7em-none-eabihf -c blink.ll -o blink.o
$CC --target=thumbv7em-none-eabihf -c hardware/nucleo-g491re/start_g491.s -o start.o
$CC --target=thumbv7em-none-eabihf -nostdlib -fuse-ld=lld \
   -T hardware/nucleo-g491re/g491.ld start.o blink.o -o blink.elf
/opt/homebrew/opt/llvm/bin/llvm-objcopy -O binary blink.elf blink.bin

cp blink.bin /Volumes/NOD_G491RE/
```

**The `cp` reports `Device not configured` when it SUCCEEDS.** The ST-LINK's mass storage volume
disappears the moment the transfer completes, so the copy's own close fails — an error message is the
expected outcome and a silent success would be the surprise.

**The program does not start until a reset.** Replug the board or press the black B2 button. LD1
blinks *during* the transfer, which is the ST-LINK talking to the host and not the program running, so
a blink at flash time proves nothing.

## Why not a debug probe

`probe-rs` is the obvious tool and it does not work against this board's ST-LINK V3 here: a session
teardown stalls the OUT endpoint, and every subsequent open fails with `cannot perform this operation
while interfaces are claimed` until the device is power-cycled. Drag-and-drop needs no driver, no
probe and no permissions, and it is what the board's own firmware is for.
