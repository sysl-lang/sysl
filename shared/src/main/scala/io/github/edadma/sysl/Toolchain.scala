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
      List("clang", s"--target=${target.triple}", "-Wno-override-module", ll) ::: archives ::: List("-o", exe))
    deleteFile(ll)

    if result.exitCode == 0 then Right(())
    else Left(s"clang failed (exit ${result.exitCode}):\n${result.stderr.trim}")
  }

  /** Assembles an IR module into a relocatable object file — the ahead-of-time half of a library.
   *
   * `-c` is the whole difference from `build`: nothing is linked, so a module with no `main` and
   * with unresolved calls into the program that will use it is exactly what is wanted.
   */
  def compileObject(ir: String, obj: String, target: Target = Target.default): Either[String, Unit] = {
    val ll = createTempFile("sysl-", ".ll")
    writeFile(ll, ir)

    val result =
      exec(Seq("clang", s"--target=${target.triple}", "-Wno-override-module", "-c", ll, "-o", obj))
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
