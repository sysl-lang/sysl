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
}
