/* MPS2-AN505 — a Cortex-M33, the RP2350's Arm core.
 *
 * The program lives in the code SRAM at 0x00000000 and **the stack lives somewhere else**, which is
 * the part worth explaining. Both regions were measured rather than read off a datasheet: a store at
 * 0x20000000 succeeds and one at 0x20008000 faults, so the board's SRAM is 32K, and the code region
 * is proven by the program running out of it.
 *
 * An earlier version put the stack at the top of the code region, and that is the arrangement to
 * avoid on this board: the stack descends *toward the program*, so a runaway one rewrites the code
 * it is running and the failure arrives long after the cause, as an execution somewhere impossible.
 * What it looks like is a lockup with `SP=00000000` — a stack that has descended through the whole
 * region and wrapped — and nothing in that report points at the recursion. Thirty-two kilobytes of
 * separate SRAM is less stack and a much better failure: the overflow hits unmapped memory at once,
 * and the report names the function that pushed.
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
