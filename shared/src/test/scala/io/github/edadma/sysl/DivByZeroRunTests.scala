package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** Integer division and remainder by zero have no defined result, so they trap at runtime rather
 * than run an `sdiv`/`urem` whose behaviour LLVM leaves undefined. A nonzero divisor is unaffected.
 * The divisor is read from a variable in every trapping case, because a literal zero divisor is
 * already a compile-time error (the constant folder rejects it) and so would never reach codegen.
 */
class DivByZeroRunTests extends AnyFreeSpec with RunSupport {

  "a nonzero divisor computes as usual" - {
    "division" in {
      run("var d = 2\nprint(10 / d)") shouldBe "5\n"
    }
    "remainder" in {
      run("var d = 3\nprint(10 % d)") shouldBe "1\n"
    }
  }

  "a zero divisor traps" - {
    "signed division" in {
      exits("var d = 0\nprint(10 / d)")
    }
    "signed remainder" in {
      exits("var d = 0\nprint(10 % d)")
    }
    "unsigned division exercises the udiv guard" in {
      exits("var d: u64 = 0\nprint(10u64 / d)")
    }
    "unsigned remainder exercises the urem guard" in {
      exits("var d: u64 = 0\nprint(10u64 % d)")
    }
  }
}
