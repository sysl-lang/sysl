package io.github.edadma.sysl

import org.scalatest.Assertions
import org.scalatest.matchers.should.Matchers

/** Shared helper for the Tier-2 run suites: compile a program all the way to a native binary,
 * run it, and return its stdout. Needs an LLVM toolchain, so it cancels cleanly (rather than
 * failing) when `clang` is absent.
 */
trait RunSupport extends Matchers { this: Assertions =>

  protected def run(src: String): String = {
    assume(Toolchain.clangAvailable, "clang not available")

    Toolchain.compileAndRun(src) match {
      case Right((0, out))    => out
      case Right((code, out)) => fail(s"program exited with $code:\n$out")
      case Left(err)          => fail(err)
    }
  }

  /** The same, started with arguments — what a `main(args: []string)` is handed. The zeroth is the
   * executable's own path, which the platform supplies and no test can predict, so a test asserts
   * about `args[1..]` or about the count.
   */
  protected def runWith(src: String, args: String*): String = {
    assume(Toolchain.clangAvailable, "clang not available")

    Toolchain.compileAndRun(src, "<input>", args.toList) match {
      case Right((0, out))    => out
      case Right((code, out)) => fail(s"program exited with $code:\n$out")
      case Left(err)          => fail(err)
    }
  }

  /** The same, for a program written as several files. */
  protected def runOf(fs: (String, String)*): String =
    ran(fs.toList.map { case (name, text) => Source(name, text) })

  /** The same, for a project whose files sit in directories — each one written as the dotted path
   * the driver derives from where the file was found, with `""` for the project root.
   */
  protected def runIn(fs: (String, String, String)*): String =
    ran(fs.toList.map { case (dir, name, text) =>
      Source(name, text, if dir.isEmpty then Nil else dir.split('.').toList)
    })

  private def ran(sources: List[Source]): String = {
    assume(Toolchain.clangAvailable, "clang not available")

    Toolchain.compileAndRun(sources) match {
      case Right((0, out))    => out
      case Right((code, out)) => fail(s"program exited with $code:\n$out")
      case Left(err)          => fail(err)
    }
  }

  /** Asserts that a program stops itself rather than running past a failed check. Every runtime
   * check traps, so what is observable is the exit status, not the output.
   */
  protected def exits(src: String): Unit = {
    assume(Toolchain.clangAvailable, "clang not available")

    Toolchain.compileAndRun(src) match {
      case Right((code, _)) => code should not be 0
      case Left(err)        => fail(err)
    }
  }

  /** Asserts the exact status a program exits with, for the one thing that can choose it: a call to
   * `exit`. `exits` above only asks whether the status was non-zero, which is all a trap has to say.
   */
  protected def exitsWith(src: String, code: Int): Unit = {
    assume(Toolchain.clangAvailable, "clang not available")

    Toolchain.compileAndRun(src) match {
      case Right((c, _)) => c shouldBe code
      case Left(err)     => fail(err)
    }
  }

  /** Asserts that a program stops itself *and says why* — the shape of a panic, as against the
   * silent trap `exits` checks for. The diagnostic reaches the pipe because the panic leaves
   * through `exit`, which flushes what was written; `abort` would not have to.
   */
  protected def panics(src: String, message: String): Unit = {
    assume(Toolchain.clangAvailable, "clang not available")

    Toolchain.compileAndRun(src) match {
      case Right((0, out)) => fail(s"expected the program to stop, but it exited cleanly:\n$out")
      case Right((_, out)) => out should include(message)
      case Left(err)       => fail(err)
    }
  }
}
