package io.github.edadma.sysl

import java.nio.file.{Files, Path}

import scala.sys.process.*
import scala.util.Try

/** JVM-side glue between the pure compiler and an installed LLVM toolchain: it writes the
 * generated IR to a temporary `.ll`, links it with `clang`, and runs the result. Kept out of
 * the shared module because it touches the filesystem and spawns processes.
 */
object Toolchain {

  /** Whether a `clang` capable of consuming textual LLVM IR is on the PATH. Tests that link
   * and run gate on this so they skip cleanly on a machine without a toolchain.
   */
  lazy val clangAvailable: Boolean =
    Try(Seq("clang", "--version").!(ProcessLogger(_ => ()))).toOption.contains(0)

  /** Links an IR module into a native executable at `exe`. */
  def build(ir: String, exe: Path): Either[String, Unit] = {
    val ll = Files.createTempFile("sysl-", ".ll")
    Files.write(ll, ir.getBytes("UTF-8"))

    val stderr = new StringBuilder
    val logger = ProcessLogger(_ => (), line => { stderr ++= line; stderr += '\n' })
    val exit   = Seq("clang", ll.toString, "-o", exe.toString).!(logger)

    Files.deleteIfExists(ll)
    if exit == 0 then Right(())
    else Left(s"clang failed (exit $exit):\n${stderr.toString.trim}")
  }

  /** Compiles, links, and runs a source program, returning its exit code and captured
   * stdout — the end-to-end path the run-it test tier exercises.
   */
  def compileAndRun(source: String): Either[String, (Int, String)] =
    Compiler.compileToLlvm(source).flatMap { ir =>
      val exe = Files.createTempFile("sysl-", "")
      Files.deleteIfExists(exe)

      build(ir, exe).map { _ =>
        val out    = new StringBuilder
        val logger = ProcessLogger(line => { out ++= line; out += '\n' }, _ => ())
        val code   = Seq(exe.toString).!(logger)

        Files.deleteIfExists(exe)
        (code, out.toString)
      }
    }
}
