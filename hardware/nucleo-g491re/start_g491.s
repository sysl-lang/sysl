    .syntax unified
    .thumb

    // Startup for an STM32G491RE on a Nucleo-64. Unlike every board in sysl's QEMU tier, this one
    // runs from FLASH, so `.data` has an address in RAM and a *load* address in flash and the
    // startup has to copy it across. Nothing else does that yet.

    .section .vectors, "a"
    .word _stack_top
    .word _start
    .word hang                  // NMI
    .word hang                  // HardFault
    .word hang                  // MemManage
    .word hang                  // BusFault
    .word hang                  // UsageFault

    .section .text
    .thumb_func
    .global _start
_start:
    ldr  sp, =_stack_top

    // The triple is eabihf and a Cortex-M denies coprocessor access out of reset, so the first VFP
    // instruction would fault inside whatever arithmetic reached for it. CPACR, bits 23:20.
    ldr  r0, =0xE000ED88
    ldr  r1, [r0]
    orr  r1, r1, #(0xF << 20)
    str  r1, [r0]
    dsb
    isb

    // .data: flash -> RAM.
    ldr  r0, =__data_start
    ldr  r1, =__data_end
    ldr  r2, =__data_load
0:  cmp  r0, r1
    beq  1f
    ldr  r3, [r2], #4
    str  r3, [r0], #4
    b    0b
1:

    // .bss: zeroed here, because NOBITS means the image carries no bytes for it.
    ldr  r0, =__bss_start
    ldr  r1, =__bss_end
    movs r2, #0
2:  cmp  r0, r1
    beq  3f
    str  r2, [r0], #4
    b    2b
3:

    bl   main

    .thumb_func
    .global hang
hang:
    b    hang
