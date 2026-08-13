package sh.sysl

/** What a calling convention written on a definition means **on a given processor** (`15 §10`).
 *
 * **`interrupt` is one concept with three different answers, and every one of them was read off
 * clang rather than out of a document.** On x86-64 it is an LLVM *calling convention*, `x86_intrcc`,
 * and the ABI insists the handler take a pointer to the frame the hardware pushed. On RISC-V it is a
 * *function attribute*, `"interrupt"="machine"`, and the handler must take nothing at all. On
 * AArch64 it does not exist: clang answers `__attribute__((interrupt))` there with "unknown
 * attribute ignored" and compiles an ordinary function.
 *
 * That last one is why this table refuses rather than shrugs. A handler compiled as an ordinary
 * function returns with `ret` where the machine needs `iret` or `mret`, and it does not save the
 * registers an asynchronous entry has to save — so the failure is silent, and it arrives as memory
 * corruption at whatever the interrupted code was doing. Clang's warning is a reasonable answer for
 * C, where the attribute is advisory by tradition; it is not a reasonable answer for a language that
 * refuses the annotated/unannotated split everywhere else (`15 §1`).
 *
 * Nothing here is portable, and the design does not pretend otherwise. An interrupt handler is the
 * least portable code there is: it is entered by a mechanism the processor defines, and even the
 * number of arguments differs. What the compiler owes is that the *source says which machine it is
 * for* and that building it for another one fails loudly.
 */
object Conventions {

  /** The one convention that exists today. Named rather than assumed so that the diagnostic for an
   * unknown one can list what there is.
   */
  val interrupt = "interrupt"

  val known: List[String] = List(interrupt)

  /** How a processor spells an interrupt handler, or `None` where it has no such thing. */
  enum Form:
    /** An LLVM calling convention, written on the `define` and on every call — x86-64's
     * `x86_intrcc`.
     */
    case Convention(llvm: String)

    /** An LLVM function attribute, written after the signature — RISC-V's `"interrupt"="mode"`. */
    case Attribute(key: String)

  /** What `interrupt` is on this processor, or **why that processor has none** (`15 §10`).
   *
   * Written out per `Cpu` with no default arm, for the reason `Toolchain.libraryFlags` is: a
   * processor added to the registry has to answer this rather than inherit whichever answer sat at
   * the bottom of the match. The wrong answer here is not a build error, it is a handler that looks
   * right and corrupts memory.
   *
   * **The absent case carries its own sentence rather than sharing one**, which is the same rule one
   * level down. Three processors answer `Left` and they answer it for three unrelated reasons: an
   * A-profile handler is assembly, an M-profile one is an ordinary function, and i386 is simply not
   * lowerable. A shared sentence can only be true of one of them, and the two it is false of are
   * told something about their own machine that is not so. Keeping the reason *in this match* is
   * what makes it impossible to add a processor that has no interrupt form and no account of why.
   */
  def interruptForm(cpu: Cpu): Either[String, Form] = cpu match
    case Cpu.X86_64  => Right(Form.Convention("x86_intrcc"))
    // Both RISC-V widths spell it the same way, because it is the same attribute: the privilege
    // modes below are the architecture's rather than the register width's.
    case Cpu.Riscv64 | Cpu.Riscv32 => Right(Form.Attribute("interrupt"))

    case Cpu.Aarch64 =>
      Left("its exception entry goes through a vector table of fixed-size instruction slots, so a " +
        "handler is assembly and there is nothing here for a convention to describe")

    // **A Cortex-M handler is an ordinary function, and that is the whole answer rather than a gap.**
    // M-profile enters an exception with the caller-saved registers already stacked by the hardware
    // and `EXC_RETURN` in the link register, so a plain AAPCS function returning normally is a
    // correct handler — which is why the vector table can just hold its address. A convention or an
    // attribute here would describe a prologue the processor has already written.
    case Cpu.Thumb =>
      Left("a Cortex-M exception is entered with the caller-saved registers already stacked by the " +
        "processor and 'EXC_RETURN' in the link register, so an ordinary function is already a " +
        "correct handler and there is no prologue for a convention to arrange — write one, and give " +
        "it the name the vector table holds with '@export(\"SysTick_Handler\")'")

    // **WebAssembly has no interrupts to have a form for**, which is a different kind of absence
    // from the two above: those are machines whose exception entry a convention cannot usefully
    // describe, and this is a machine with no exception entry. Nothing arrives asynchronously in a
    // wasm module — the embedder calls an exported function and that call returns — so a handler
    // here is not a thing the compiler declines to lower, it is a thing the execution model has not
    // got.
    case Cpu.Wasm32 =>
      Left("a wasm module is only ever entered by its embedder calling an exported function, so " +
        "nothing arrives asynchronously and there is no handler for a convention to describe")

    // 32-bit x86 has the convention, but i386 is not lowerable for want of a measured C ABI
    // (`Target.supported`), so nothing can reach this and saying so is better than implying support.
    case Cpu.X86 =>
      Left("sysl cannot lower for i386 at all, for want of a measured C ABI, so its interrupt " +
        "convention is not something this compiler implements")

  /** The privilege modes RISC-V distinguishes, in the spelling the attribute takes. `machine` is the
   * default because a handler with no mode written is the one a bare-metal program means: M-mode is
   * where a RISC-V core starts and where a program with no supervisor beneath it stays.
   */
  val riscvModes: List[String] = List("machine", "supervisor", "user")

  val defaultRiscvMode: String = "machine"

  /** Whether this processor's interrupt form takes a mode. Only RISC-V's does — x86-64's convention
   * describes the frame the hardware pushed and has no privilege level to name.
   */
  def takesMode(cpu: Cpu): Boolean = cpu == Cpu.Riscv64
}
