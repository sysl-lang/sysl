/* STM32G491RE — 512K flash at 0x08000000, 128K SRAM at 0x20000000.
 *
 * Only SRAM1's 80K is claimed here. SRAM2 (16K at 0x20014000) and CCM (32K at 0x20018000) follow it
 * contiguously and could be taken, but a blink does not need them and a stack pointer inside a
 * region I have not verified is not worth the doubt.
 *
 * `.data` is the thing this file has that no board in sysl's QEMU tier has: an `AT >` clause, so the
 * section lives in RAM and is *stored* in flash. `__data_load` is where the startup copies from.
 */
ENTRY(_start)

MEMORY {
  FLASH (rx)  : ORIGIN = 0x08000000, LENGTH = 512K
  RAM   (rwx) : ORIGIN = 0x20000000, LENGTH = 80K
}

SECTIONS {
  .vectors : { KEEP(*(.vectors)) } > FLASH
  .text    : { *(.text*) *(.rodata*) } > FLASH

  .data : {
    . = ALIGN(4);
    __data_start = .;
    *(.data*)
    . = ALIGN(4);
    __data_end = .;
  } > RAM AT > FLASH

  __data_load = LOADADDR(.data);

  .bss : {
    . = ALIGN(4);
    __bss_start = .;
    *(.bss*)
    *(COMMON)
    . = ALIGN(4);
    __bss_end = .;
  } > RAM

  _stack_top = ORIGIN(RAM) + LENGTH(RAM);
}
