/* MPS2-AN505 — a Cortex-M33, the RP2350's Arm core.
 *
 * Everything lives in the code SRAM at 0x00000000, stack included, and that is a measurement rather
 * than a preference. The board *does* have an SRAM at 0x20000000, but it is small: a store at
 * 0x20000000 succeeds and one at 0x20008000 faults, so a linker script claiming a few megabytes
 * there produces a stack pointer that is not backed by anything. What that looks like is not a bad
 * address -- it is a HardFault with no handler, which on M-profile escalates to a lockup, and QEMU
 * reports it against whichever function first tried to push.
 *
 * The code region is proven by the program running out of it, so putting the stack there too costs
 * nothing and removes the one part of the map that had to be guessed at.
 */
ENTRY(_start)

MEMORY {
  CODE (rwx) : ORIGIN = 0x00000000, LENGTH = 512K
}

SECTIONS {
  .vectors : { KEEP(*(.vectors)) } > CODE
  .text    : { *(.text*) *(.rodata*) } > CODE
  .data    : { *(.data*) } > CODE
  .bss     : { *(.bss*) *(COMMON) } > CODE

  . = ALIGN(8);

  _stack_top = ORIGIN(CODE) + LENGTH(CODE);
}
