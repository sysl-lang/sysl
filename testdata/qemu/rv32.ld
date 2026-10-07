ENTRY(_start)
MEMORY { RAM (rwx) : ORIGIN = 0x80000000, LENGTH = 16M }
SECTIONS {
  .text : { *(.text.start) *(.text*) } > RAM
  .rodata : { *(.rodata*) } > RAM
  .data : { *(.data*) } > RAM

  /* `start_rv32.s` zeroes this, and these two symbols are how it knows where. A `.bss` section is
   * NOBITS -- no bytes in the file, so a loader writes nothing for it -- and the guarantee that its
   * storage reads as zero is the startup's to keep on a board. `thumb.ld` says what it costs when
   * nobody does. */
  .bss : {
    __bss_start = .;
    *(.bss*)
    *(COMMON)
    . = ALIGN(4);
    __bss_end = .;
  } > RAM

  _stack_top = ORIGIN(RAM) + LENGTH(RAM);
}
