package io.github.edadma.sysl

import io.github.edadma.cross_platform.*

import org.scalatest.Assertions
import org.scalatest.matchers.should.Matchers

/** Shared helper for the Tier-2 run suites: compile a program all the way to a native binary,
 * run it, and return its stdout. Needs an LLVM toolchain, so it cancels cleanly (rather than
 * failing) when `clang` is absent.
 */
trait RunSupport extends Matchers { this: Assertions =>

  /** Every run-tier compilation, in one place, against the **prebuilt** standard module where the
   * toolchain can build one.
   *
   * That is the compilation an ordinary `sysl build` performs: the library's determined half is
   * linked from the artifact rather than emitted into this program and handed to clang again. Absent
   * a toolchain there is no artifact and this is the compilation the suite has always done — which is
   * the case the `assume` above each helper has already cancelled for.
   *
   * It goes through `Stdlib.resolve`, which is the call the driver itself makes, rather than through
   * a routine of the suite's own — so a test is compiled against the artifact an ordinary build
   * would find, at the path an ordinary build would find it, and a change to how one is made cannot
   * reach the compiler without reaching the suite.
   */
  protected def prebuiltStd: Option[Stdlib.Resolved] =
    Stdlib.resolve(Stdlib.Choice.Default(), Target.default).toOption

  private def compiled(sources: List[Source], args: List[String]): Either[String, (Int, String)] =
    prebuiltStd match {
      case Some(Stdlib.Resolved(std, precompiled, Some(archive))) =>
        Toolchain.compileAndRun(sources, Nil, args, Some(std), precompiled, List(archive))
      case _ =>
        Toolchain.compileAndRun(sources, Nil, args, None, Set.empty, Nil)
    }

  protected def run(src: String): String = {
    assume(Toolchain.clangAvailable, "clang not available")

    compiled(List(Source("<input>", src)), Nil) match {
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

    compiled(List(Source("<input>", src)), args.toList) match {
      case Right((0, out))    => out
      case Right((code, out)) => fail(s"program exited with $code:\n$out")
      case Left(err)          => fail(err)
    }
  }

  /** The same, given bytes for the program to read: they are written to a temporary file whose path
   * arrives as `args[1]`, and the file is removed however the run ends.
   *
   * It is a file rather than standard input because the harness hands a compiled program a **closed**
   * one — `cross_platform`'s `exec` offers no way to write to a child — so a test that wants real
   * bytes to arrive passes a path and lets the program open it. That exercises everything above the
   * file descriptor, which is all of the reading surface; only the choice of descriptor is left out.
   */
  protected def runReading(src: String, input: String): String = {
    assume(Toolchain.clangAvailable, "clang not available")

    val path = createTempFile("sysl-input-", ".txt")

    writeFile(path, input)

    try runWith(src, path)
    finally deleteFile(path)
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

    compiled(sources, Nil) match {
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

    compiled(List(Source("<input>", src)), Nil) match {
      case Right((code, _)) => code should not be 0
      case Left(err)        => fail(err)
    }
  }

  /** Asserts the exact status a program exits with, for the one thing that can choose it: a call to
   * `exit`. `exits` above only asks whether the status was non-zero, which is all a trap has to say.
   */
  protected def exitsWith(src: String, code: Int): Unit = {
    assume(Toolchain.clangAvailable, "clang not available")

    compiled(List(Source("<input>", src)), Nil) match {
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

    compiled(List(Source("<input>", src)), Nil) match {
      case Right((0, out)) => fail(s"expected the program to stop, but it exited cleanly:\n$out")
      case Right((_, out)) => out should include(message)
      case Left(err)       => fail(err)
    }
  }
}
