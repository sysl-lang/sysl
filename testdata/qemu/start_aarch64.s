    // `virt` with a Cortex-A53, entered at EL1 from QEMU's `-kernel` loader with the MMU off.
    .section .text.start, "ax"
    .global _start
_start:
    ldr  x0, =_stack_top
    mov  sp, x0

    // **Give the core its FP/SIMD unit.** CPACR_EL1.FPEN resets to trapping, and the back end uses
    // the vector registers for anything it likes -- a variadic function saves q0-q7 in its prologue
    // whether or not a `real` is read -- so the first such instruction would trap.
    mov  x0, #(3 << 20)
    msr  cpacr_el1, x0
    isb

    // **Turn the MMU on over an identity map.** With it off every data access is Device memory, and
    // an unaligned access to Device memory faults -- which the back end is entitled to emit. Three
    // 1 GiB blocks from one level-1 table: the first (the UART, at 0x09000000) Device-nGnRnE, the
    // second (RAM, at 0x40000000) Normal write-back.
    ldr  x0, =0xff00                // MAIR: attr0 Device-nGnRnE, attr1 Normal WB
    msr  mair_el1, x0
    ldr  x0, =0x80803519            // TCR: T0SZ 25, inner/outer WB, inner shareable, 4K, EPD1
    msr  tcr_el1, x0
    adr  x0, l1_table
    msr  ttbr0_el1, x0
    isb
    mrs  x0, sctlr_el1
    orr  x0, x0, #1                 // M
    orr  x0, x0, #(1 << 2)          // C
    orr  x0, x0, #(1 << 12)         // I
    bic  x0, x0, #(1 << 1)          // A off: an unaligned Normal access is allowed
    msr  sctlr_el1, x0
    isb

    // Zero .bss before anything runs -- `rv32.ld` says why.
    ldr  x0, =__bss_start
    ldr  x1, =__bss_end
0:  cmp  x0, x1
    b.hs 1f
    str  xzr, [x0], #8
    b    0b
1:

    bl   main

    // Report what `main` returned through semihosting's SYS_EXIT, whose AArch64 form takes a block
    // of two words: the reason (ADP_Stopped_ApplicationExit) and the status QEMU exits with.
    adr  x1, exit_block
    mov  w2, #0x0026
    movk w2, #0x2, lsl #16
    str  x2, [x1]
    sxtw x0, w0
    str  x0, [x1, #8]
    mov  w0, #0x18
    hlt  #0xf000
2:  b    2b

    .section .data
    .balign 4096
l1_table:
    .quad 0x00000000 | (1 << 10) | (0 << 2) | 1     // Device, AF, block
    .quad 0x40000000 | (1 << 10) | (3 << 8) | (1 << 2) | 1   // Normal, inner shareable, AF, block
    .quad 0x80000000 | (1 << 10) | (0 << 2) | 1
    .fill 509, 8, 0

    .balign 16
exit_block:
    .quad 0, 0
