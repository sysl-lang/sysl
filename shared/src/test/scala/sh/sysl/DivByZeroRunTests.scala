package sh.sysl

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

  // The other undefined signed division: the minimum value divided by -1, whose true quotient is one
  // past the maximum. The minimum is built by stepping one below -127 so no INT_MIN literal is needed.
  "signed minimum divided by -1 overflows" - {
    "the division traps" in {
      exits("var a: i8 = -127\na = a - 1i8\nvar b: i8 = -1i8\nprint(int(a / b))")
    }
    "the remainder is its defined zero, not a trap" in {
      run("var a: i8 = -127\na = a - 1i8\nvar b: i8 = -1i8\nprint(int(a % b))") shouldBe "0\n"
    }
    "dividing a value other than the minimum by -1 is unaffected" in {
      run("var a: i8 = 10\nvar b: i8 = -1i8\nprint(int(a / b))") shouldBe "-10\n"
    }
  }
}
