package sh.sysl

/** The machine a program is compiled **for**.
 *
 * A target is the pair a systems language cannot do without — a processor and the operating system
 * conventions layered on it — together with the handful of facts the compiler has to know to emit
 * correct code for it. It is a value, not a global: an invocation names one, it is carried down to
 * codegen, and nothing consults the machine the compiler happens to be running on. That is what
 * lets one compiler build for a machine it is not, and it is why the host appears here only as the
 * **default** an invocation that names no target gets.
 *
 * The facts recorded are the ones something in the compiler reads today. A target is not a
 * description of a machine — it is the answer to the questions codegen asks — so the way to add a
 * field is to have something need it, and the way to add a target is to verify its answers against
 * a C compiler for the same triple rather than to reason about the ABI document.
 */
case class Target(
    name: String,
    triple: String,
    cpu: Cpu,
    os: Os,
    vaList: VaListAbi,
    vaListBytes: Int,
    softFloat: Boolean = false,
) {

  /** How wide an address is, as a value that can be handed to the two places that need it — the
   * layout of anything holding a pointer or a length, and the LLVM type of a view (`Word`).
   */
  def word: Word = Word(cpu.bits)

  def pointerBits: Int = cpu.bits

  def pointerBytes: Int = cpu.bits / 8

  /** Whether floating-point values travel in floating-point registers. This is a question only
   * RISC-V answers two ways: a hosted RISC-V system is built for the D extension and passes them
   * there, and a bare-metal one is not and passes them as the integers they are laid out as. It is
   * recorded here rather than derived because sysl hands its triple to clang, so the two have to
   * make the same default assumption about the same triple or the call disagrees.
   *
   * It reaches exactly one decision: whether a small aggregate of floating members is **flattened**
   * into registers (`CAbi`). A scalar needs nothing here — the backend applies the convention to
   * those itself, from the same triple.
   */
  def hardFloat: Boolean = !softFloat

  /** Whether a `thread_local` global on this target reaches storage something has actually set up.
   *
   * Every target LLVM knows will *accept* the keyword, so this is not a question about the backend —
   * it is a question about what runs before `main`. A hosted system lays down a thread's storage as
   * part of starting it, and a bare one has nothing that would: there is no loader, no libc, and
   * nothing that writes the thread pointer.
   *
   * The reason it has to be asked rather than left to the backend is that the failure is silent.
   * Asked for a freestanding ELF target, LLVM upgrades an internal thread-local to the **local-exec**
   * model — a `:tprel_hi12:` offset from `TPIDR_EL0` on AArch64 — which links clean and reads
   * whatever the register happened to hold. A kernel that never set one up would get a wild address
   * out of the arc runtime rather than a diagnostic out of the linker.
   *
   * Nothing is lost by answering `false` there, because nothing on a bare target can spawn: the
   * threads a program gets from sysl come from `sysl.thread`, which is `requires posix`. A kernel
   * that implements threads of its own and shares a reference across them is outside what the
   * compiler can see either way — it does not know that scheduler exists.
   */
  def hasThreadLocalStorage: Boolean = os != Os.Freestanding

  /** Whether the compiler can lower for this target at all. A target it knows and cannot lower for
   * is worth naming: the diagnostic then says what is missing instead of leaving the name unknown,
   * which reads as a typo.
   */
  def supported: Boolean = Cpu.buildable.contains(cpu)
}

/** How wide an address is on the target, and the one fact about a machine that reaches into the
 * *types* the compiler writes rather than only into the calls it makes.
 *
 * Almost nothing about lowering depends on the target. A struct's members sit at the same offsets
 * everywhere, an `i32` is an `i32`, and the emitted module states its triple so LLVM derives the
 * data layout rather than sysl writing it down twice. **One thing is not like that**: a view is
 * `{ ptr, ptr, iN }` where `N` is the address width, because its length is a `usize` — so the LLVM
 * form of a slice, and of anything containing one, cannot be written without knowing the machine.
 *
 * It is a parameter rather than a global for the reason `targets.md` gives for the target itself: a
 * compiler that reads the width from somewhere ambient can be wrong about it silently, and one that
 * is handed it cannot compile at all until every caller has said which machine it means.
 */
case class Word(bits: Int) {
  def bytes: Int = bits / 8

  /** The integer that is exactly one address wide — what `usize` and `isize` lower to, and what a
   * view's length is.
   */
  def llvm: String = s"i$bits"
}

/** The processor, and the two things about it the compiler asks: how wide an address is, and which
 * C calling convention its aggregates cross under (`CAbi`).
 */
enum Cpu(val bits: Int) {
  case Aarch64 extends Cpu(64)
  case X86_64  extends Cpu(64)
  case Riscv64 extends Cpu(64)
  case Riscv32 extends Cpu(32)
  case Thumb   extends Cpu(32)
  case X86     extends Cpu(32)

  /** How a source file names this processor — in a `#if` condition and in an assembly arm alike.
   * One spelling for both, so a program that gates on a processor and one that writes instructions
   * for it are naming the same thing.
   *
   * `thumb` rather than `arm` because it is the instruction set, not the architecture family, that
   * an assembly arm has to be right about: a Cortex-M executes Thumb only, and an arm written for
   * A32 would assemble for a machine that cannot run it.
   */
  def symbol: String = this match
    case Aarch64 => "aarch64"
    case X86_64  => "x86_64"
    case Riscv64 => "riscv64"
    case Riscv32 => "riscv32"
    case Thumb   => "thumb"
    case X86     => "x86"

  /** The name LLVM registers this processor's back end under, which is what `clang -print-targets`
   * lists and so what says whether a given clang can produce objects for this target at all.
   *
   * **It is not `symbol`, and the difference is not cosmetic.** `symbol` is what a *program* writes
   * in a `#if` or an `asm` arm and is sysl's to choose; this is LLVM's, and LLVM spells the 64-bit
   * x86 back end `x86-64` where a source file says `x86_64`. Deriving one from the other would work
   * for five processors out of six and fail for that one, silently, in the form of a clang that
   * looks incapable of a target it handles perfectly well.
   */
  def backend: String = this match
    case Aarch64 => "aarch64"
    case X86_64  => "x86-64"
    case Riscv64 => "riscv64"
    case Riscv32 => "riscv32"
    case Thumb   => "thumb"
    case X86     => "x86"
}

object Cpu {

  /** The processors a target can actually be built for, which is what an exhaustive set of assembly
   * arms has to cover (`inline-assembly.md §2`).
   *
   * `x86` is nameable and not lowerable, and **the reason is no longer its width** — 32-bit targets
   * build. It is that nothing has measured i386's C calling convention against clang, which is what
   * `targets.md § Adding one` says a target's answers have to come from. Requiring an assembly arm
   * for a processor no program can be built for would be requiring an answer to a question nobody
   * can ask; when the convention is measured it joins this list, and the arms that do not cover it
   * become errors, which is the work of supporting it.
   */
  def buildable: List[Cpu] = values.filterNot(_ == X86).toList
}

/** The system conventions the processor is used under. `Freestanding` is a target with no operating
 * system at all — a kernel or a bare-metal program — which is a real answer here rather than a
 * missing one: the ABI of a freestanding ELF target is fully specified, and it differs from a
 * hosted one of the same processor only where the OS is what fixed the convention.
 */
enum Os {
  case MacOS, Linux, Windows, Freestanding
}

/** What a call hands a C function whose parameter is a `va_list` (`12 §9`).
 *
 * C's `va_list` is a different type on every target and is passed differently on each, so the one
 * thing sysl has — the address of the walk's storage — reaches a foreign callee three ways. Each
 * was read off `clang -S -emit-llvm` for the triple rather than out of an ABI document:
 *
 *   - `Loaded` — the storage holds a single pointer-sized value and the call passes **that value**.
 *     Darwin arm64 (`va_list` is `char *`), Windows x64, and RISC-V.
 *   - `Address` — the storage is an array of one struct, which decays, so the call passes **the
 *     address of the storage itself**. x86-64 System V, hosted and freestanding alike.
 *   - `Copied` — the storage is a struct passed indirectly, so the call passes the address of a
 *     **fresh copy** of it. AAPCS64 everywhere but Darwin.
 *
 * The distinction is invisible in the emitted types — all three pass one `ptr` — which is exactly
 * why it has to be recorded: nothing downstream could recover it from the IR.
 *
 * **AAPCS32 needs no case of its own, and that was measured rather than assumed.** Its `va_list` is
 * `struct { void * }`, which clang declares as `[1 x i32]` because the aggregate rule reaches a
 * one-word struct — so it looks at first like a fourth answer. It is not: compiling a call both ways
 * for `thumbv8m.main-none-eabihf` gives the identical `ldr r1, [r1]`, because one word in a core
 * register is one word in a core register whichever type names it. `Loaded` already says exactly
 * that, and a fourth case would have been a distinction with no instruction behind it.
 */
enum VaListAbi {
  case Loaded, Address, Copied
}

object Target {

  val aarch64MacOS: Target =
    Target("aarch64-macos", "arm64-apple-macosx", Cpu.Aarch64, Os.MacOS, VaListAbi.Loaded, 8)

  val x86_64MacOS: Target =
    Target("x86_64-macos", "x86_64-apple-macosx", Cpu.X86_64, Os.MacOS, VaListAbi.Address, 24)

  val aarch64Linux: Target =
    Target("aarch64-linux", "aarch64-unknown-linux-gnu", Cpu.Aarch64, Os.Linux, VaListAbi.Copied, 32)

  val x86_64Linux: Target =
    Target("x86_64-linux", "x86_64-unknown-linux-gnu", Cpu.X86_64, Os.Linux, VaListAbi.Address, 24)

  val riscv64Linux: Target =
    Target("riscv64-linux", "riscv64-unknown-linux-gnu", Cpu.Riscv64, Os.Linux, VaListAbi.Loaded, 8)

  val x86_64Windows: Target =
    Target("x86_64-windows", "x86_64-pc-windows-msvc", Cpu.X86_64, Os.Windows, VaListAbi.Loaded, 8)

  val aarch64Freestanding: Target =
    Target("aarch64-freestanding", "aarch64-none-elf", Cpu.Aarch64, Os.Freestanding, VaListAbi.Copied, 32)

  val x86_64Freestanding: Target =
    Target("x86_64-freestanding", "x86_64-unknown-none-elf", Cpu.X86_64, Os.Freestanding, VaListAbi.Address, 24)

  /** Bare-metal RISC-V is the one target with no floating-point registers to pass arguments in: the
   * hosted triple is built for the D extension and this one is not, which is clang's default for
   * each and so has to be sysl's.
   */
  val riscv64Freestanding: Target =
    Target("riscv64-freestanding", "riscv64-unknown-elf", Cpu.Riscv64, Os.Freestanding, VaListAbi.Loaded, 8,
      softFloat = true)

  /** The RP2350's Arm personality: a Cortex-M33, which is Armv8-M Mainline and executes Thumb only.
   *
   * The triple carries the whole of what the ABI needs — `eabihf` selects the hard-float convention
   * and clang gives `thumbv8m.main` an `fpv5-d16` by default, so arguments cross in VFP registers
   * and `softFloat` stays false. `-mcpu=cortex-m33` refines instruction selection to the exact core
   * and changes nothing here; it is the sub-architecture question this registry still leaves open.
   */
  val thumbFreestanding: Target =
    Target("thumb-freestanding", "thumbv8m.main-none-eabihf", Cpu.Thumb, Os.Freestanding,
      VaListAbi.Loaded, 4)

  /** The RP2350's other personality: a Hazard3, which is RV32IMAC and has no F extension at all —
   * so, like bare-metal RISC-V at 64 bits, there are no floating registers to pass arguments in.
   */
  val riscv32Freestanding: Target =
    Target("riscv32-freestanding", "riscv32-unknown-elf", Cpu.Riscv32, Os.Freestanding,
      VaListAbi.Loaded, 4, softFloat = true)

  /** A target that is listed and cannot be built for. It is here rather than left out because the
   * limit is the compiler's and not the machine's, and a reader who names it deserves to be told
   * what is missing rather than told the name is unknown.
   *
   * **The limit is no longer its width.** 32-bit targets build; what i386 has not got is a C calling
   * convention measured against clang, which is the only way `targets.md § Adding one` allows one to
   * be arrived at.
   */
  val x86Linux: Target =
    Target("x86-linux", "i386-unknown-linux-gnu", Cpu.X86, Os.Linux, VaListAbi.Address, 4)

  /** Every target the compiler knows, in the order `sysl targets` lists them. */
  val all: List[Target] =
    List(
      aarch64MacOS,
      x86_64MacOS,
      aarch64Linux,
      x86_64Linux,
      riscv64Linux,
      x86_64Windows,
      aarch64Freestanding,
      x86_64Freestanding,
      riscv64Freestanding,
      thumbFreestanding,
      riscv32Freestanding,
      x86Linux,
    )

  private val byName = all.map(t => t.name -> t).toMap

  /** The target of a given name, or a complaint naming the ones there are. A target the compiler
   * knows and cannot lower for is refused here too, so no caller has to remember to ask.
   */
  def named(name: String): Either[String, Target] =
    byName.get(name) match
      case Some(t) if t.supported => Right(t)
      case Some(t) =>
        Left(s"sysl knows '$name' and cannot build for it: no C calling convention has been " +
          s"measured for ${t.cpu.symbol}")
      case None =>
        Left(s"unknown target '$name' — sysl knows ${all.map(_.name).mkString(", ")}")

  /** The registry name for a machine described the way a runtime describes itself, or `""` for one
   * the registry has no entry for.
   *
   * Each platform the compiler runs on spells the same machine differently — a JVM says `amd64`
   * where Node says `x64` and where Scala Native says `x86_64` — so the **asking** is per-platform
   * and the **answering** is here, once, where it can be tested without a machine of each kind to
   * run on.
   */
  def hostName(arch: String, os: String): String = {
    val cpu = arch.toLowerCase match
      case "aarch64" | "arm64"           => "aarch64"
      case "x86_64" | "amd64" | "x64"    => "x86_64"
      case "riscv64"                     => "riscv64"
      case _                             => ""
    val system = os.toLowerCase match
      case s if s.contains("mac") || s.contains("darwin") => "macos"
      case s if s.contains("linux")                       => "linux"
      case s if s.contains("win")                         => "windows"
      case _                                              => ""

    if cpu.isEmpty || system.isEmpty then "" else s"$cpu-$system"
  }

  /** This machine, when the registry knows it. The compiler asks its own platform what it is
   * running on — that is a question about the edge and `hostMachine` answers it there
   * (`cross-platform.md`) — and the pair is turned into a target here.
   */
  val host: Option[Target] = byName.get(hostName(hostMachine._1, hostMachine._2)).filter(_.supported)

  /** This machine in the words its own runtime used, which is what `sysl targets` shows and what a
   * report about a machine sysl does not know has to carry: the mapping above can only be extended
   * by somebody who knows what the unrecognized half actually said.
   */
  def hostMachineShown: String = {
    val (arch, os) = hostMachine

    if arch.isEmpty && os.isEmpty then "not reported" else s"$arch / $os"
  }

  /** What the pure compiler API assumes when a caller names no target and the host is not one it
   * knows. The driver never reaches this — it reports the missing target and stops — so this is
   * the assumption a test or an embedding makes, and the machine sysl is developed on is the
   * honest one to make.
   */
  val default: Target = host.getOrElse(aarch64MacOS)
}
