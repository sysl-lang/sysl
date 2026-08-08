    .section .text.start, "ax"
    .global _start
_start:
    la   sp, _stack_top

    // Zero .bss before anything runs. It is NOBITS, so nothing has written it, and a module `var`
    // whose initializer is zero lives here -- `thumb.ld`'s comment has the failure this prevents.
    la   t0, __bss_start
    la   t1, __bss_end
0:  bgeu t0, t1, 1f
    sw   zero, 0(t0)
    addi t0, t0, 4
    j    0b
1:

    call main
    li   t0, 0x100000
    slli a0, a0, 16
    li   t1, 0x3333
    or   a0, a0, t1
    sw   a0, 0(t0)
1:  j    1b
