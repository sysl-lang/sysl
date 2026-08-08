    .syntax unified
    .thumb

    // A Cortex-M boots through a vector table rather than a bare entry point, so one is here even
    // though QEMU's `-kernel` normally enters at the ELF entry instead. Two words: the initial stack
    // pointer and the reset handler.
    //
    // **`.word _start`, and NOT `_start+1`.** The linker resolves a `.thumb_func` symbol with the
    // Thumb bit already set, so adding one clears it -- and a reset that did come through this table
    // would then enter in ARM state, which a Cortex-M cannot do. What that looks like is
    // `UsageFault INVSTATE` (`CFSR` bit 17) and a lockup, reported at whatever address the CPU
    // wandered to rather than at this line.
    //
    // **Do not grow this table.** Wiring the five fault vectors to a handler -- the obvious way to
    // find out what a faulting program did, and one that works in the sense that the handler runs
    // and prints `CFSR` -- makes *every* image on this board fail to boot instead. So QEMU's entry
    // into a program here is sensitive to this section's size in a way that is not yet understood,
    // and two words is the size that boots. `README.md` records what was measured.
    .section .vectors, "a"
    .word _stack_top
    .word _start

    .section .text
    .thumb_func
    .global _start
_start:
    // The stack is set here rather than left to the table above. QEMU enters at the ELF's entry
    // point and does not load SP from the vectors, so a program relying on the table runs with a
    // junk SP and faults on the first push -- which it does *inside* whatever it called first, so
    // the report blames the callee.
    ldr  sp, =_stack_top

    // Zero `.bss`, which is the startup's job on a board and nobody else's. The section is NOBITS,
    // so the loader writes nothing for it and a program finds whatever the RAM held -- and this
    // board's SRAM is not zero at reset. What that costs is not a subtly wrong number: sysl puts a
    // module's `var` here whenever its initializer is zero, so a `*Writer` starting as null arrives
    // as a garbage fat pointer, and the first call through its method table branches into whatever
    // the address happens to name. The report is a lockup in the middle of a data section, which
    // names nothing and points nowhere near here.
    ldr  r0, =__bss_start
    ldr  r1, =__bss_end
    movs r2, #0
0:  cmp  r0, r1
    beq  1f
    str  r2, [r0], #4
    b    0b
1:

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
