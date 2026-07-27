package io.github.edadma.sysl

import org.scalatest.Assertions
import org.scalatest.matchers.should.Matchers

/** Shared helpers for the Tier-1 codegen suites: assert on the emitted LLVM IR *text* without
 * running it. The IR is just a string, so these are pure and cross-platform.
 */
trait CodegenSupport extends Matchers { this: Assertions =>

  /** The IR for a program that must compile. */
  protected def ir(src: String): String =
    Compiler.compileToLlvm(src) match {
      case Right(out) => out
      case Left(e)    => fail(e)
    }

  /** The error message for a program that must be rejected. */
  protected def err(src: String): String =
    Compiler.compileToLlvm(src) match {
      case Right(out) => fail(s"expected an error, got:\n$out")
      case Left(e)    => e
    }

  /** The files of one module, named so a diagnostic about one can be told from a diagnostic about
   * another. `"a.sysl" -> "…"` reads at a call site the way a small directory looks.
   */
  protected def files(fs: (String, String)*): List[Source] =
    fs.toList.map { case (name, text) => Source(name, text) }

  /** The IR for a module of several files, all of which must compile. */
  protected def irOf(fs: (String, String)*): String =
    Compiler.compile(files(fs*)) match {
      case Right(out) => out
      case Left(e)    => fail(e)
    }

  /** The error message for a module of several files that must be rejected. */
  protected def errOf(fs: (String, String)*): String =
    Compiler.compile(files(fs*)) match {
      case Right(out) => fail(s"expected an error, got:\n$out")
      case Left(e)    => e
    }

  /** One emitted function, for a test that counts instructions rather than looking for one.
   *
   * A whole-module count is not what such a test means: the module also holds the ARC runtime and
   * whatever prelude functions the program reached, and either can grow without the lowering under
   * test having changed.
   */
  protected def defineOf(out: String, name: String): String =
    val header = (l: String) => l.startsWith("define") && l.contains(s"@$name(")
    val body   = out.linesIterator.dropWhile(!header(_)).takeWhile(_ != "}")

    body.mkString("\n")

  /** Just `main`, which is where a top-level statement lands. */
  protected def mainOf(out: String): String = defineOf(out, "main")

  /** The IR of `main` alone. */
  protected def irMain(src: String): String = mainOf(ir(src))
}
