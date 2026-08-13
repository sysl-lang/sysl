    .syntax unified
    .thumb

    // Armv7-M, on the MPS2-AN385's Cortex-M3. `start_thumbv7em.s` is the nearest sibling, and this is
    // that file with one block taken out; what the two share is the vector table's length and the
    // link address, both of which follow from the same fact — **neither core has TrustZone**.
    //
    // So there is no SecureFault, and slot 7 is reserved rather than a handler. `thumbv7m.ld` says
    // the other consequence: the image links at address zero, because there is no secure alias for
    // the reset vector to be hiding in.
    //
    // **The block that is gone is the FPU enable, and its absence is what this board is here for.**
    // Every other Thumb startup in this directory writes CPACR to grant CP10 and CP11 before anything
    // can reach for VFP. A Cortex-M3 has no such coprocessor to grant access to, and the target that
    // names it says `noFpu` — so the back end selects `__aeabi_fmul` where it would have selected
    // `vmul.f32`, and no instruction ever asks. A startup here that wrote CPACR anyway would assemble
    // and run, the bits being reserved rather than faulting, and would stand as a sentence telling
    // the next reader that this core has a unit.
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
    // wall-clock alarm has expired.
    .section .vectors, "a"
    .word _stack_top
    .word _start
    .word fault_handler         // NMI
    .word fault_handler         // HardFault
    .word fault_handler         // MemManage
    .word fault_handler         // BusFault
    .word fault_handler         // UsageFault
    .word 0                     // reserved -- an Armv8-M core has SecureFault here, this one does not

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
    // so the loader writes nothing for it and a program finds whatever the RAM held. What that costs
    // is not a subtly wrong number: sysl puts a module's `var` here whenever its initializer is
    // zero, so a `*Writer` starting as null arrives as a garbage fat pointer, and the first call
    // through its method table branches into whatever the address happens to name. The report is a
    // lockup in the middle of a data section, which names nothing and points nowhere near here.
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
