    .section .text.start, "ax"
    .global _start
_start:
    la   sp, _stack_top
    call main
    li   t0, 0x100000
    slli a0, a0, 16
    li   t1, 0x3333
    or   a0, a0, t1
    sw   a0, 0(t0)
1:  j    1b
