package io.github.edadma.sysl

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

  /** What `interrupt` is on this processor.
   *
   * Written out per `Cpu` with no default arm, for the reason `Toolchain.libraryFlags` is: a
   * processor added to the registry has to answer this rather than inherit whichever answer sat at
   * the bottom of the match. The wrong answer here is not a build error, it is a handler that looks
   * right and corrupts memory.
   */
  def interruptForm(cpu: Cpu): Option[Form] = cpu match
    case Cpu.X86_64  => Some(Form.Convention("x86_intrcc"))
    case Cpu.Riscv64 => Some(Form.Attribute("interrupt"))
    // Exception entry on AArch64 goes through a vector table the processor indexes by cause, and
    // each entry is a fixed-size slot of instructions — so the entry point is assembly by
    // construction and there is nothing for a convention on a sysl function to describe.
    case Cpu.Aarch64 => None
    // 32-bit x86 has the convention, but no 32-bit target is lowerable at all (`Target.supported`),
    // so nothing can reach this and saying so is better than implying support.
    case Cpu.X86 => None

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
