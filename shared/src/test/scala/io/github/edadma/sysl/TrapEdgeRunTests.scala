package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** Tier-2 edge cases for the runtime checks that trap: each is paired with the adjacent valid
 * case that must *not* trap, so an off-by-one in a bounds check is caught in both directions —
 * a too-tight check would fail the valid case, a too-loose one would let the invalid case run.
 */
class TrapEdgeRunTests extends AnyFreeSpec with RunSupport {

  "array index" - {
    "the last valid index reads" in {
      run(
        """var a = [10, 20, 30]
          |print(a[2])""".stripMargin
      ) shouldBe "30\n"
    }

    "one past the end traps" in {
      exits(
        """var a = [10, 20, 30]
          |print(a[3])""".stripMargin
      )
    }

    "a negative index traps rather than wrapping to a huge one" in {
      exits(
        """var a = [10, 20, 30]
          |var i: int = -1
          |print(a[i])""".stripMargin
      )
    }
  }

  "slice range" - {
    "an inclusive high at the last element reads" in {
      run(
        """var b: &[4]int = [1, 2, 3, 4]
          |var s = b[0..3]
          |print(s.len, s[3])""".stripMargin
      ) shouldBe "4 4\n"
    }

    "an inclusive high one past the last traps" in {
      exits(
        """var b: &[4]int = [1, 2, 3, 4]
          |var s = b[0..4]
          |print(s.len)""".stripMargin
      )
    }

    "an exclusive high past the length traps" in {
      exits(
        """var b: &[4]int = [1, 2, 3, 4]
          |var s = b[0..<5]
          |print(s.len)""".stripMargin
      )
    }

    "an inverted range traps" in {
      exits(
        """var b: &[4]int = [1, 2, 3, 4]
          |var s = b[3..<1]
          |print(s.len)""".stripMargin
      )
    }

    "an empty slice at the very end is legal" in {
      run(
        """var b: &[4]int = [1, 2, 3, 4]
          |var s = b[4..]
          |print(s.len)""".stripMargin
      ) shouldBe "0\n"
    }
  }

  "char conversion" - {
    "the maximum scalar value converts and round-trips" in {
      run("print(u32(char(1114111)))") shouldBe "1114111\n"
    }

    "one past the maximum traps" in {
      exits("print(char(1114112))")
    }

    "a surrogate value traps" in {
      exits("print(char(55296))")
    }
  }
}
