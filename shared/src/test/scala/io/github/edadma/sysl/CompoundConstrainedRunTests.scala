package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** Compound assignment (`+=`, `-=`, `*=`) works on a constrained type: it computes at the base,
 * detecting overflow where the range invites it, and lands the result back in the place through the
 * same range and predicate checks a plain store would apply. A narrow range needs only the range
 * check; a wide range additionally needs the overflow detection, since its arithmetic can leave the
 * base width before the range check would see it.
 */
class CompoundConstrainedRunTests extends AnyFreeSpec with RunSupport {

  private val Age = "type Age = int within 0..150\n"
  private val Big = "type Big = i32 within -2000000000..2000000000\n"

  "a transparent subtype supports compound assignment" - {
    "an in-range += updates and reads back" in {
      run(Age + "var a: Age = 10\na += 5\nprint(int(a))") shouldBe "15\n"
    }
    "an in-range -= updates" in {
      run(Age + "var a: Age = 40\na -= 15\nprint(int(a))") shouldBe "25\n"
    }
    "a result past the top is caught by the range check" in {
      exits(Age + "var a: Age = 100\na += 60\nprint(int(a))")
    }
    "a result below the bottom is caught too" in {
      exits(Age + "var a: Age = 10\na -= 40\nprint(int(a))")
    }
  }

  "compound assignment on a wide range detects overflow" - {
    "a += that overflows the width traps" in {
      exits(Big + "var x: Big = Big(2000000000)\nx += Big(2000000000)\nprint(int(x))")
    }
    "a *= that overflows the width traps" in {
      exits(Big + "var x: Big = Big(100000)\nx *= Big(100000)\nprint(int(x))")
    }
    "a wide compound that fits computes normally" in {
      run(Big + "var x: Big = Big(1000000000)\nx += Big(1000000000)\nprint(int(x))") shouldBe "2000000000\n"
    }
  }

  "a where-predicate subtype re-checks the result" - {
    val Even = "type Even = int within 0..100 where value % 2 == 0\n"

    "an update keeping it even and in range passes" in {
      run(Even + "var e: Even = 4\ne += 2\nprint(int(e))") shouldBe "6\n"
    }
    "an update past the range traps" in {
      exits(Even + "var e: Even = 100\ne += 2\nprint(int(e))")
    }
  }
}
