    .syntax unified
    .thumb

    // A Cortex-M boots through a vector table, and **this one is read**: the image is linked at the
    // secure alias 0x10000000, which is where an ARMv8-M core coming out of reset looks. `thumb.ld`
    // explains what linking it at 0x00000000 did instead, and why that looked like a working board.
    //
    // **`.word _start`, and NOT `_start+1`.** The linker resolves a `.thumb_func` symbol with the
    // Thumb bit already set, so adding one clears it, and the reset then enters in ARM state -- which
    // a Cortex-M cannot do. What that looks like is `UsageFault INVSTATE` (`CFSR` bit 17) and a
    // lockup reported at whatever address the CPU wandered to, rather than at this line.
    //
    // The faults are wired to a handler for one reason: **without one they all report the same
    // thing.** A fault with no vector escalates to HardFault, whose vector would be past the end of
    // a short table, so the CPU branches into whatever follows and QEMU says `Lockup: can't escalate
    // 3 to HardFault` at a meaningless address -- for every possible cause, and only after the whole
    // wall-clock alarm has expired. Handled, a faulting program stops at once with a status of its
    // own, and a suite that expected output gets a failure naming the board rather than a timeout.
    .section .vectors, "a"
    .word _stack_top
    .word _start
    .word fault_handler         // NMI
    .word fault_handler         // HardFault
    .word fault_handler         // MemManage
    .word fault_handler         // BusFault
    .word fault_handler         // UsageFault
    .word fault_handler         // SecureFault

    .section .text
    .thumb_func
    .global _start
_start:
    // The stack is set here rather than left to the table above. QEMU enters at the ELF's entry
    // point and does not load SP from the vectors, so a program relying on the table runs with a
    // junk SP and faults on the first push -- which it does *inside* whatever it called first, so
    // the report blames the callee.
    ldr  sp, =_stack_top

    // **Enable the FPU before anything can use it.** The triple is `thumbv8m.main-none-eabihf`, so
    // clang gives the core an `fpv5-d16` and the back end is free to reach for VFP anywhere -- and a
    // Cortex-M denies coprocessor access out of reset, so the first such instruction raises a
    // UsageFault with the NOCP bit. CPACR is at 0xE000ED88, bits 23:20 being full access to CP10 and
    // CP11.
    //
    // **This was missing until 2026-08-10, and cost nothing only because nothing had used a float.**
    // Every program that had ever run on this board was integer, so the omission was invisible. The
    // first `f32` program written for this tier faulted here and reported a status of 99 -- on this
    // board alone, while the two Armv7E-M boards, whose startup did enable it, passed. That is the
    // argument for testing the FPU deliberately rather than waiting for a program that happens to
    // want one: the gap sat here for as long as the board has existed and no suite could see it.
    ldr  r0, =0xE000ED88
    ldr  r1, [r0]
    orr  r1, r1, #(0xF << 20)
    str  r1, [r0]
    dsb
    isb

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

    // A fault leaves through the same door as a clean exit, with a status nothing else uses, so the
    // suite reads "the board faulted" rather than waiting out its alarm and reading nothing. It does
    // not try to say *which* fault: `CFSR` is a register a handler can print, and printing needs the
    // UART enabled and a hex routine, which is a program to get wrong inside the thing that reports
    // other programs going wrong. `-d int` says it better and costs nothing to turn on.
    //
    // The exit block is a constant rather than the stack's: the fault may well be a stack that has
    // run somewhere unusable, and building the argument on it would fault again inside the handler.
    .thumb_func
    .global fault_handler
fault_handler:
    ldr  r1, =fault_exit
    movs r0, #0x20
    bkpt 0xAB

2:  b 2b

    .section .rodata
    .align 2
fault_exit:
    .word 0x20026
    .word 99
