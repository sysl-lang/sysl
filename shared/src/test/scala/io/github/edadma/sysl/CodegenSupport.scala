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

  /** Several files, named so a diagnostic about one can be told from a diagnostic about another.
   * `"a.sysl" -> "…"` reads at a call site the way a small directory looks.
   *
   * They carry no location, which is what a file handed to the compiler with no project around it
   * has: the module each contributes to is whatever its header says, and there is nothing for that
   * header to be held against.
   */
  protected def files(fs: (String, String)*): List[Source] =
    fs.toList.map { case (name, text) => Source(name, text) }

  /** The same files as a **project**: each one paired with the directory it sits in, written as the
   * dotted path a driver walking the tree would have derived. `""` is the project root.
   */
  protected def project(fs: (String, String, String)*): List[Source] =
    fs.toList.map { case (dir, name, text) =>
      Source(name, text, if dir.isEmpty then Nil else dir.split('.').toList)
    }

  /** The IR for a program of several files, all of which must compile. */
  protected def irOf(fs: (String, String)*): String = compiled(files(fs*))

  /** The error message for a program of several files that must be rejected. */
  protected def errOf(fs: (String, String)*): String = rejected(files(fs*))

  /** The IR for a project laid out across directories. */
  protected def irIn(fs: (String, String, String)*): String = compiled(project(fs*))

  /** The error message for a project laid out across directories that must be rejected. */
  protected def errIn(fs: (String, String, String)*): String = rejected(project(fs*))

  private def compiled(sources: List[Source]): String =
    Compiler.compile(sources) match {
      case Right(out) => out
      case Left(e)    => fail(e)
    }

  private def rejected(sources: List[Source]): String =
    Compiler.compile(sources) match {
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
