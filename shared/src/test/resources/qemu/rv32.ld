ENTRY(_start)
MEMORY { RAM (rwx) : ORIGIN = 0x80000000, LENGTH = 16M }
SECTIONS {
  .text : { *(.text.start) *(.text*) } > RAM
  .rodata : { *(.rodata*) } > RAM
  .data : { *(.data*) } > RAM
  .bss : { *(.bss*) *(COMMON) } > RAM
  _stack_top = ORIGIN(RAM) + LENGTH(RAM);
}
