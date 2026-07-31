package io.github.edadma.sysl

import io.github.edadma.cross_platform.*

/** Glue between the pure compiler and an installed LLVM toolchain: it writes the generated IR
 * to a temporary `.ll`, links it with `clang`, and runs the result. All filesystem and process
 * access goes through `cross_platform`, so this works on every backend (JVM/JS/Native) — the
 * only external requirement is a `clang` on the PATH.
 */
object Toolchain {

  /** Whether a `clang` capable of consuming textual LLVM IR is on the PATH. Tests that link
   * and run gate on this so they skip cleanly on a machine without a toolchain.
   */
  lazy val clangAvailable: Boolean =
    exec(Seq("clang", "--version")).exitCode == 0

  /** Links an IR module into a native executable at `exe`, for a given machine.
   *
   * The triple goes on the command line as well as in the module because the two answer different
   * questions: the module's says what the code *is*, and the driver's decides which linker and
   * which system libraries it is linked against. Passing it is therefore what makes naming a
   * cross target fail honestly at the link rather than silently produce a host binary.
   *
   * `-Wno-override-module` is the one warning suppressed, and only because sysl states a target
   * *family* — `arm64-apple-macosx` — where the driver refines it to the installed SDK's version.
   * With `--target` passed, the only override that can still happen is that refinement.
   */
  def build(ir: String, exe: String, target: Target = Target.default,
            archives: List[String] = Nil): Either[String, Unit] = {
    val ll = createTempFile("sysl-", ".ll")
    writeFile(ll, ir)

    // A library archive goes on the command line after the module that calls into it, which is what
    // the linker's left-to-right scan wants: a member is pulled in to resolve a symbol already
    // undefined, so an archive listed first would be scanned before anything needed it.
    val result = exec(
      List("clang", s"--target=${target.triple}", "-Wno-override-module") ::: deadStrip(target) :::
        List(ll) ::: archives ::: List("-o", exe))
    deleteFile(ll)

    if result.exitCode == 0 then Right(())
    else Left(s"clang failed (exit ${result.exitCode}):\n${result.stderr.trim}")
  }

  /** Ask the linker to drop what nothing reaches.
   *
   * **A library's object half is one `.o` named on the command line, and a named object is linked
   * entire** — unlike an archive member, which is pulled only to resolve something already
   * undefined. So without this, every symbol a library compiled lands in every binary that links it:
   * a program whose whole text is `print(1)` carried all 61 of the standard module's symbols,
   * including the reader, the string builder and the hashes, none of which it can reach.
   *
   * The two spellings are not interchangeable. Mach-O resolves at **atom** granularity, so
   * `-dead_strip` needs nothing from the compile step. ELF resolves at **section** granularity and
   * `--gc-sections` can therefore only drop a function that was given a section of its own, which is
   * what `-ffunction-sections` in `compileObject` is for — the two halves are one mechanism and
   * changing either alone silently stops working.
   */
  private def deadStrip(target: Target): List[String] =
    target.os match
      case Os.MacOS => List("-Wl,-dead_strip")
      case _        => List("-Wl,--gc-sections")

  /** Assembles an IR module into a relocatable object file — the ahead-of-time half of a library.
   *
   * `-c` is the whole difference from `build`: nothing is linked, so a module with no `main` and
   * with unresolved calls into the program that will use it is exactly what is wanted.
   *
   * `-ffunction-sections`/`-fdata-sections` give every definition a section of its own, which is
   * what lets the linker drop the ones a program never reaches (`deadStrip`). It is the *library*
   * side of that pair and useless without the linker side, and on Mach-O it is redundant rather than
   * wrong — atoms are already per-definition there. Passed unconditionally so that an artifact built
   * on one host still strips when linked for another.
   */
  def compileObject(ir: String, obj: String, target: Target = Target.default): Either[String, Unit] = {
    val ll = createTempFile("sysl-", ".ll")
    writeFile(ll, ir)

    val result = exec(Seq("clang", s"--target=${target.triple}", "-Wno-override-module",
      "-ffunction-sections", "-fdata-sections", "-c", ll, "-o", obj))
    deleteFile(ll)

    if result.exitCode == 0 then Right(())
    else Left(s"clang failed (exit ${result.exitCode}):\n${result.stderr.trim}")
  }

  /** Compiles, links, and runs a source program, returning its exit code and captured
   * stdout — the end-to-end path the run-it test tier exercises.
   *
   * There is one target here and not two: a program is built for the machine it is about to be run
   * on, so a cross target has nothing to run the result with and is not offered.
   */
  def compileAndRun(source: String, name: String = "<input>",
                    args: List[String] = Nil): Either[String, (Int, String)] =
    runIr(Compiler.compileToLlvm(source, name), args)

  /** The same, for the files of one program. */
  def compileAndRun(sources: List[Source]): Either[String, (Int, String)] =
    runIr(Compiler.compile(sources), Nil)

  /** The same, for a program compiled **against a library** whose modules arrive as trees rather
   * than as source — an `AstCodec` artifact, decoded (`Compiler.compileWith`).
   */
  def compileAndRun(sources: List[Source], libraries: List[Program]): Either[String, (Int, String)] =
    runIr(Compiler.compileWith(sources, libraries), Nil)

  /** `args` are the words the program is started with, which reach it exactly as they would from a
   * shell: the executable's own path arrives ahead of them as the zeroth, since that is what the
   * platform passes and not something this could withhold.
   */
  private def runIr(compiled: Either[String, String], args: List[String]): Either[String, (Int, String)] =
    compiled.flatMap { ir =>
      val exe = createTempFile("sysl-", "")

      build(ir, exe).map { _ =>
        val result = exec(exe :: args)
        deleteFile(exe)
        (result.exitCode, result.stdout)
      }
    }
}
