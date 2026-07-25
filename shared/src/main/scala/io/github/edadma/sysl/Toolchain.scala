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

  /** Links an IR module into a native executable at `exe`. */
  def build(ir: String, exe: String): Either[String, Unit] = {
    val ll = createTempFile("sysl-", ".ll")
    writeFile(ll, ir)

    val result = exec(Seq("clang", ll, "-o", exe))
    deleteFile(ll)

    if result.exitCode == 0 then Right(())
    else Left(s"clang failed (exit ${result.exitCode}):\n${result.stderr.trim}")
  }

  /** Compiles, links, and runs a source program, returning its exit code and captured
   * stdout — the end-to-end path the run-it test tier exercises.
   */
  def compileAndRun(source: String, name: String = "<input>"): Either[String, (Int, String)] =
    Compiler.compileToLlvm(source, name).flatMap { ir =>
      val exe = createTempFile("sysl-", "")

      build(ir, exe).map { _ =>
        val result = exec(Seq(exe))
        deleteFile(exe)
        (result.exitCode, result.stdout)
      }
    }
}
