package sh.sysl

import io.github.edadma.cross_platform.*
import org.scalatest.Assertions.*
import org.scalatest.matchers.should.Matchers

/** Running a freestanding program **on the machine it was built for**, under QEMU.
 *
 * Every tier below this one reads what the compiler wrote. `CAbiTests` checks a table against
 * itself, `AbiAgainstClangTests` checks it against clang, and `CrossTargetBuildTests` checks that
 * the module verifies and assembles. None of them can see valid IR that computes the wrong thing —
 * a wrong coercion, a length loaded at the wrong width, an index widened to a size the machine does
 * not have — because all three are text, and text is what a miscompile also is.
 *
 * So this tier executes. It links the module against a startup written for the board, boots it in
 * an emulator, and reads **two channels**, which are complementary and both wanted:
 *
 *   - **the exit status**, which says *whether* anything failed, through a device the board has for
 *     the purpose;
 *   - **standard output**, which says *what* did, through the board's UART.
 *
 * A count alone cannot identify a failure that by definition does not reproduce on the host — that
 * being the only kind this tier exists to find.
 *
 * ### What happens when the tools are not here
 *
 * **A test that silently skips is a test that stops running**, so nothing here passes for want of a
 * tool. A missing emulator, linker or back end **cancels the test by name** — cancelled shows in
 * scalatest's own count where a pass does not, and the message says which tool and which board. The
 * gate is therefore honest on a machine with no QEMU and loud on a machine that had one yesterday.
 */
trait QemuSupport extends Matchers {

  /** The boot files, which are in the repository rather than generated: a startup that gives the
   * program a stack and reports what it returned, and a linker script that says where RAM is.
   * `shared/src/test/resources/qemu/README.md` explains both, and what each board needs that the
   * other does not.
   */
  private val boot = "shared/src/test/resources/qemu"

  /** What it takes to boot one board: the emulator, the arguments before the image, the startup and
   * the script. A target with no entry here has no recipe, and asking for one cancels rather than
   * guessing — a wrong `-M` produces a program that hangs, not one that fails.
   */
  private case class Board(qemu: String, machine: List[String], startup: String, script: String)

  private val boards: Map[String, Board] = Map(
    Target.riscv32Freestanding.name ->
      Board("qemu-system-riscv32", List("-M", "virt", "-bios", "none", "-nographic", "-kernel"),
        "start_rv32.s", "rv32.ld")
  )

  /** Whether a program exists to be run. `--version` rather than `which`, because a name on the
   * PATH that will not start is the failure this is meant to catch.
   */
  private def present(program: String): Boolean =
    try exec(Seq(program, "--version")).exitCode == 0
    catch case _: Exception => false

  /** **QEMU has no wall-clock limit of its own**, and a program that never reaches the exit device
   * runs until something kills it — which, in a suite, is the suite. `perl -e 'alarm'` is the
   * portable way to put a bound on it: perl ships with macOS and with every CI image, and `exec`
   * replaces the shell so the signal reaches the emulator rather than a wrapper.
   */
  private def bounded(seconds: Int, command: List[String]): List[String] =
    "perl" :: "-e" :: "alarm shift; exec @ARGV" :: seconds.toString :: command

  /** Builds `src` for `t`, boots it, and answers with what the board said and what it exited with.
   *
   * The startup is assembled by the same clang that compiled the module, for the same triple, so
   * the two agree about the machine without anything having to state it twice. The link is
   * `-nostdlib` — there is no libc on a bare board and asking for one finds the host's — and
   * `-fuse-ld=lld`, because these are ELF images and the system linker on a Mac is not an ELF
   * linker.
   */
  protected def bootUnderQemu(t: Target, src: String, seconds: Int = 20): (Int, String) = {
    val board = boards.getOrElse(t.name, cancel(s"no QEMU recipe for ${t.name}"))
    val cc    = Toolchain.findClang(t).getOrElse(cancel(s"no clang here has a back end for ${t.name}"))

    if !present(board.qemu) then cancel(s"${board.qemu} is not installed, so ${t.name} cannot be booted")
    if !present("ld.lld") then cancel(s"ld.lld is not installed, so a ${t.name} image cannot be linked")

    // The compiler's own message, not a summary of it: a diagnostic swallowed here is a diagnostic
    // that has to be rediscovered by hand, and this tier's programs are written for a machine the
    // author is not sitting at.
    val ir = Compiler.compile(List(Source("p.sysl", src)), t) match
      case Right(ir) => ir
      case Left(e)   => fail(s"did not compile for ${t.name}:\n$e\n\nthe program was:\n$src")

    val prog  = createTempFile("sysl-qemu-", ".o")
    val start = createTempFile("sysl-qemu-start-", ".o")
    val image = createTempFile("sysl-qemu-", ".elf")

    try {
      withClue(s"compiling the module for ${t.triple}: ")(Toolchain.compileObject(ir, prog, t) shouldBe Right(()))

      val asm = exec(Seq(cc, s"--target=${t.triple}", "-c", s"$boot/${board.startup}", "-o", start))

      withClue(s"assembling ${board.startup}:\n${asm.stderr}")(asm.exitCode shouldBe 0)

      val link = exec(Seq(cc, s"--target=${t.triple}", "-nostdlib", "-fuse-ld=lld",
        "-T", s"$boot/${board.script}", start, prog, "-o", image))

      withClue(s"linking the image:\n${link.stderr}")(link.exitCode shouldBe 0)

      val ran = exec(bounded(seconds, board.qemu :: board.machine ::: List(image)))

      (ran.exitCode, ran.stdout)
    } finally
      for f <- List(prog, start, image) do try deleteFile(f) catch case _: Exception => ()
  }
}
