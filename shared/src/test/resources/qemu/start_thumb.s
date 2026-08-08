    .syntax unified
    .thumb

    // A Cortex-M boots through a vector table rather than a bare entry point, so one is here even
    // though QEMU's `-kernel` uses the ELF entry instead. Two words is the whole of what this needs:
    // the initial stack pointer and the reset handler, the low bit set because every M-profile
    // branch target is Thumb.
    .section .vectors, "a"
    .word _stack_top
    .word _start+1

    .section .text
    .thumb_func
    .global _start
_start:
    // The stack is set here rather than left to the table above. QEMU enters at the ELF's entry
    // point and does not load SP from the vectors, so a program relying on the table runs with a
    // junk SP and faults on the first push -- which it does *inside* whatever it called first, so
    // the report blames the callee.
    ldr  sp, =_stack_top

    bl   main

    // SYS_EXIT_EXTENDED (0x20), not SYS_EXIT (0x18). On AArch32 the plain call takes the reason in
    // r1 and always reports zero, so a `main` returning 7 arrives as 0 and every assertion made
    // against the status is worthless. The extended call takes a pointer to {reason, code}, and
    // ADP_Stopped_ApplicationExit is the reason that means "ran to completion".
    sub  sp, sp, #8
    ldr  r1, =0x20026
    str  r1, [sp, #0]
    str  r0, [sp, #4]
    mov  r1, sp
    movs r0, #0x20
    bkpt 0xAB

1:  b 1b
