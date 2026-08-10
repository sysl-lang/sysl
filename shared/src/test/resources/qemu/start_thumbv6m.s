    .syntax unified
    .thumb

    // The Armv6-M startup — a Cortex-M0, which is the RP2040's core. `start_thumb.s` is the same
    // job for the M33 and reads almost identically; what differs is the **instruction set**, and
    // every difference below is an instruction that file uses and this architecture does not have.
    //
    // **The vector table is shorter, and that is architectural rather than a saving.** Armv6-M has
    // no MemManage, BusFault, UsageFault or SecureFault — the configurable faults arrived with
    // Armv7-M — so everything that can go wrong here escalates straight to HardFault. Four entries
    // is therefore the whole of what a program this size can be entered through, and a fifth would
    // be a slot the core never reads.
    //
    // **This one is read at address zero**, with no secure alias to get wrong. `thumb.ld` has a long
    // note about being linked at 0x10000000 because an Armv8-M core resets into the secure state;
    // Armv6-M has no security extension at all, so the table lives where the manual says and the
    // trap that cost that file so much cannot arise here.
    .section .vectors, "a"
    .word _stack_top
    .word _start
    .word fault_handler         // NMI
    .word fault_handler         // HardFault

    .section .text
    .thumb_func
    .global _start
_start:
    // **`ldr sp, =…` is Thumb-2 and will not assemble here**, which is the first thing this
    // architecture refuses. The assembler says so in as many words — "instruction requires: thumb2",
    // and then, helpfully, "operand must be a register in range [r0, r7]" — because the literal-pool
    // load exists on Armv6-M but only into a low register. So the address goes into r0 and `mov`
    // moves it, which is two instructions saying what one says on the M33.
    //
    // The stack is set here rather than left to the table for the reason the M33's is: QEMU enters
    // at the ELF's entry point and does not load SP from the vectors, so a program trusting the
    // table runs with a junk SP and faults inside whatever it called first.
    ldr  r0, =_stack_top
    mov  sp, r0

    // Zero `.bss`, which is the startup's job on a board and nobody else's — `thumb.ld` says what it
    // costs to skip, and it costs the same here.
    //
    // **`str r2, [r0], #4` is Thumb-2 too.** Armv6-M has no writeback form of a register store, so
    // the pointer is advanced by hand. `adds` rather than `add` because the narrow encoding sets the
    // flags whether or not anyone wants it, and `unified` syntax makes you say so.
    ldr  r0, =__bss_start
    ldr  r1, =__bss_end
    movs r2, #0
0:  cmp  r0, r1
    beq  1f
    str  r2, [r0]
    adds r0, r0, #4
    b    0b
1:

    bl   main

    // SYS_EXIT_EXTENDED (0x20), for the reason `start_thumb.s` gives: on AArch32 the plain SYS_EXIT
    // always reports zero, so every assertion made against the status would be worthless.
    sub  sp, sp, #8
    ldr  r1, =0x20026
    str  r1, [sp, #0]
    str  r0, [sp, #4]
    mov  r1, sp
    movs r0, #0x20
    bkpt 0xAB

1:  b 1b

    // A fault leaves through the same door with a status nothing else uses, so a suite reads "the
    // board faulted" rather than waiting out its alarm and reading nothing. The exit block is a
    // constant rather than the stack's: the fault may well be a stack that has run somewhere
    // unusable, and building the argument on it would fault again inside the handler.
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
