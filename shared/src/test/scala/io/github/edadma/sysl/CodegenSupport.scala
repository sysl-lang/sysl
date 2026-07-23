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
}
