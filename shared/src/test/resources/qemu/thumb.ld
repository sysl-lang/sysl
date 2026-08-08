/* MPS2-AN505 — a Cortex-M33, the RP2350's Arm core.
 *
 * **The program is linked at 0x10000000, which is the SECURE alias of the code SRAM, and the address
 * matters more than anything else in this file.** An ARMv8-M core comes out of reset in the secure
 * state, so it reads its initial stack pointer and reset vector from the *secure* vector table — and
 * on this board the IDAU splits the map by bit 28, making 0x0xxxxxxx the non-secure view of the same
 * memory and 0x1xxxxxxx the secure one. An image linked at 0x00000000 therefore leaves the address
 * the CPU actually reads full of zeros.
 *
 * **What that cost was not a failure to boot, which is why it survived so long.** With SP and PC both
 * read as zero the core faults immediately, escalates to a secure HardFault whose vector is also
 * zero, and ends up branching to `0x1` — address zero with the Thumb bit — where it begins executing
 * the vector table and the unwind tables *as instructions*, runs off the end of them, and falls into
 * `_start`, which sets a real stack and calls `main`. The program then works. It works whenever the
 * bytes between address zero and the entry point happen to decode as harmless Thumb, which is a
 * property of how many functions the image has and what the linker put in `.ARM.exidx` — so a
 * program that grew by a few unwind entries would lock up while computing nothing differently, and
 * the fault was reported at whatever address the runaway reached. `-d int` is what showed it:
 * `Loaded reset SP 0x0 PC 0x0 from vector table` on the first line of every run, healthy or not.
 *
 * Linked here, the reset is an ordinary one: the table is read, `_start` gets control with a real
 * stack, and nothing depends on the size of anything.
 *
 * The other region on this board is the 32K SRAM at 0x20000000 — measured rather than read off a
 * datasheet: a store at 0x20000000 succeeds and one at 0x20008000 faults. The stack stays at the top
 * of the code region rather than there, because 32K is not much and the failure a descending stack
 * produces is now reported honestly by the fault handler rather than as a mystery.
 */
ENTRY(_start)

MEMORY {
  CODE (rwx) : ORIGIN = 0x10000000, LENGTH = 512K
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
