package io.github.edadma.sysl

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

  /** How wide an address is, which is the one thing about a target the emitted code assumes
   * wholesale rather than asks about — see `Target.unsupported`.
   */
  def pointerBits: Int = cpu.bits

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

  /** Whether the compiler can lower for this target at all. A target it knows and cannot lower for
   * is worth naming: the diagnostic then says what is missing instead of leaving the name unknown,
   * which reads as a typo.
   */
  def supported: Boolean = pointerBits == 64
}

/** The processor, and the only thing about it the compiler asks: how wide an address is. */
enum Cpu(val bits: Int) {
  case Aarch64 extends Cpu(64)
  case X86_64  extends Cpu(64)
  case Riscv64 extends Cpu(64)
  case X86     extends Cpu(32)
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

  /** A 32-bit target is listed and cannot be built for. It is here rather than left out because the
   * limit is the compiler's and not the machine's — the emitted code assumes a 64-bit address in
   * places nothing has yet been asked to parameterize — and a reader who names it deserves to be
   * told that rather than told the name is unknown.
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
        Left(s"'$name' is a ${t.pointerBits}-bit target, and sysl lowers 64-bit targets only")
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
