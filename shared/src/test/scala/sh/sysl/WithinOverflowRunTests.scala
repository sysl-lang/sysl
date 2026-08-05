package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** Arithmetic on a ranged (`within`) integer type is overflow-detecting when the operands' declared
 * ranges allow a result the base width cannot hold — a plain instruction would wrap before the
 * produce-site range check could examine it. A range narrow enough that its results always fit the
 * width stays on the plain path, and raw integers wrap as always, so this changes only the case that
 * was previously unsound: a wide range whose arithmetic can overflow the representation.
 */
class WithinOverflowRunTests extends AnyFreeSpec with RunSupport {

  // i32 holds ±2_147_483_647; the range stops well inside it so two near-maximal values sum past it.
  private val Big  = "type Big = i32 within -2000000000..2000000000\n"
  // u32 holds 0..4_294_967_295; again the range leaves headroom the sum of two operands can cross.
  private val UBig = "type UBig = u32 within 0..4000000000\n"

  "a wide signed range detects overflow the base width cannot hold" - {
    "addition that overflows traps" in {
      exits(Big + "print(Big(2000000000) + Big(2000000000))")
    }
    "addition that fits computes normally" in {
      run(Big + "print(Big(1000000000) + Big(1000000000))") shouldBe "2000000000\n"
    }
    "subtraction that underflows traps" in {
      exits(Big + "print(Big(-2000000000) - Big(2000000000))")
    }
    "multiplication that overflows traps" in {
      exits(Big + "print(Big(100000) * Big(100000))")
    }
    "multiplication that fits computes normally" in {
      run(Big + "print(Big(40000) * Big(40000))") shouldBe "1600000000\n"
    }
  }

  "a wide unsigned range detects overflow too" - {
    "addition past the top traps" in {
      exits(UBig + "print(UBig(3000000000) + UBig(3000000000))")
    }
    "addition that fits computes normally" in {
      run(UBig + "print(UBig(2000000000) + UBig(2000000000))") shouldBe "4000000000\n"
    }
  }

  // A left shift has no overflow intrinsic, so it is checked by shifting back: a bit pushed out of
  // the top does not return, and a shift amount at or past the width is undefined and traps.
  "a wide range shifted left detects lost bits" - {
    "a shift that pushes bits out traps" in {
      exits(UBig + "print(UBig(2000000000) << 2u32)")
    }
    "a shift that fits computes normally" in {
      run(UBig + "print(UBig(1) << 3u32)") shouldBe "8\n"
    }
    "a shift amount at the width traps" in {
      exits(UBig + "print(UBig(1) << 32u32)")
    }
  }

  // A narrow range can never overflow the representation on the way to the produce-site check, so it
  // stays on the plain path and the range check alone catches an out-of-range value.
  "a narrow range stays on the plain path" - {
    val Age = "type Age = int within 0..150\n"

    "in-range arithmetic computes without an overflow check" in {
      run(Age + "print(int(Age(100) + Age(40)))") shouldBe "140\n"
    }
    "an out-of-range result is caught by the produce-site range check, not overflow" in {
      exits(Age + "var a: Age = Age(100) + Age(60)\nprint(int(a))")
    }
  }

  // The systems substrate is unchanged: raw integer arithmetic is defined to wrap at the width and
  // is never overflow-checked.
  "raw integer arithmetic still wraps" in {
    run("var x: i32 = 2000000000\nvar y: i32 = 2000000000\nprint(x + y)") shouldBe "-294967296\n"
  }
}
