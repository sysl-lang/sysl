/* micro:bit — an nRF51822, whose core is a Cortex-M0 and therefore Armv6-M, which is what the
 * RP2040 has. QEMU has no RP2040 machine; what this tier needs is not that board but *that
 * architecture*, running real instructions the compiler chose.
 *
 * **The image is linked at address zero, and unlike the M33's that is simply where the manual puts
 * it.** `thumb.ld` carries a long note about the MPS2's secure alias at 0x10000000, because an
 * Armv8-M core comes out of reset in the secure state and reads a secure vector table. Armv6-M has
 * no security extension, no IDAU and no alias — so the table is at 0x00000000, the reset reads it,
 * and the failure mode that note describes cannot happen here.
 *
 * **The two regions are separate, where the M33's script put everything in one.** This part has 16K
 * of RAM against 256K of flash, and flash is not writable, so `.data` and `.bss` have to be in SRAM
 * rather than left wherever `.text` ended up. The stack descends from the top of RAM.
 *
 * `.data` has no initializer copy, and that is a deliberate limit of this tier rather than an
 * oversight: nothing in these test programs starts a module `var` at a non-zero constant, so the
 * section is empty in practice. A program that needed one would want a `_data_load` copy loop in the
 * startup, and would notice immediately, because the value would read as whatever the SRAM held.
 */
ENTRY(_start)

MEMORY {
  FLASH (rx)  : ORIGIN = 0x00000000, LENGTH = 256K
  RAM   (rwx) : ORIGIN = 0x20000000, LENGTH = 16K
}

SECTIONS {
  .vectors : { KEEP(*(.vectors)) } > FLASH
  .text    : { *(.text*) *(.rodata*) } > FLASH
  .data    : { *(.data*) } > RAM

  /* **The startup zeroes this, and these two symbols are how it knows where.** A `.bss` section is
   * `NOBITS`: it occupies no bytes in the file, so a loader writes nothing for it and what a program
   * finds there is whatever the RAM held.
   */
  .bss     : {
    __bss_start = .;
    *(.bss*)
    *(COMMON)
    . = ALIGN(4);
    __bss_end = .;
  } > RAM

  _stack_top = ORIGIN(RAM) + LENGTH(RAM);
}
