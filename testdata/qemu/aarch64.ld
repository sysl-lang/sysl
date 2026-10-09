/* `virt` — RAM at 0x40000000, where QEMU's `-kernel` loader places an ELF's segments. */
ENTRY(_start)
MEMORY { RAM (rwx) : ORIGIN = 0x40000000, LENGTH = 64M }
SECTIONS {
  .text : { *(.text.start) *(.text*) } > RAM
  .rodata : { *(.rodata*) } > RAM
  .data : { *(.data*) } > RAM

  /* `start_aarch64.s` zeroes this, eight bytes at a time; `rv32.ld` says why it has to. */
  .bss : {
    . = ALIGN(8);
    __bss_start = .;
    *(.bss*)
    *(COMMON)
    . = ALIGN(8);
    __bss_end = .;
  } > RAM

  . = ALIGN(16);
  _stack_top = ORIGIN(RAM) + LENGTH(RAM);
}
