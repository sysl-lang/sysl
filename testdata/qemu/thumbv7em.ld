/* MPS2-AN500 — a Cortex-M7, the larger of the two cores this target serves.
 *
 * **The image links at 0x00000000, and that is the difference from `thumb.ld` rather than an
 * oversight.** That file links at 0x10000000 because an ARMv8-M core boots secure and the AN505's
 * IDAU splits the address map by bit 28, so the table the CPU actually reads is the one in the
 * secure alias. **Armv7E-M has no TrustZone at all** — there is one view of memory, the reset
 * vector is read from address zero, and the alias does not exist to be missed. Do not copy the
 * 0x1xxxxxxx base here; it names nothing on this board.
 *
 * The region is `mps.ssram1`, and it is **RAM rather than ROM**, which is what lets `.data` sit
 * where the ELF loader puts it and spares this board the flash-to-RAM copy loop a real ST part
 * needs. Read out of QEMU's own `info mtree` rather than off a datasheet:
 *
 *   0x00000000-0x003fffff   mps.ssram1    4M, and aliased again at 0x00400000
 *   0x20000000-0x203fffff   mps.ssram23   4M
 *   0x40004000-0x40004fff   uart          the CMSDK UART, first of five
 *   0x60000000-0x60ffffff   mps.ram       16M of PSRAM
 *
 * 512K of the 4M is taken, matching `thumb.ld`, and the stack sits at the top of it. The board has
 * eight times that if a program ever wants it; what a bigger number would buy today is a stack
 * pointer further from anything that would notice it being wrong.
 */
ENTRY(_start)

MEMORY {
  CODE (rwx) : ORIGIN = 0x00000000, LENGTH = 512K
}

SECTIONS {
  .vectors : { KEEP(*(.vectors)) } > CODE
  .text    : { *(.text*) *(.rodata*) } > CODE
  .data    : { *(.data*) } > CODE

  /* **The startup zeroes this, and these two symbols are how it knows where.** A `.bss` section is
   * `NOBITS`: it occupies no bytes in the file, so a loader writes nothing for it and what a program
   * finds there is whatever the RAM held. C guarantees the storage reads as zero, and on a hosted
   * system the loader is what makes that true; on a board it is the startup's job and nobody else's.
   */
  .bss     : {
    __bss_start = .;
    *(.bss*)
    *(COMMON)
    . = ALIGN(4);
    __bss_end = .;
  } > CODE

  _stack_top = ORIGIN(CODE) + LENGTH(CODE);
}
