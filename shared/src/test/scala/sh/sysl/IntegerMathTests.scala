package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** `sysl.math`'s integer arithmetic — `pow`, `gcd`, `lcm`, `divmod`, and the two questions about
 * powers of two.
 *
 * These are **free generic functions**, not trait members, so the first thing worth asserting is the
 * consequence of that: one body reaches the signed widths and the unsigned ones, which is what a
 * `Signed` bound would have prevented. Every value test below therefore runs at more than one width,
 * and at both signednesses wherever the function admits both.
 *
 * The second thing is the edges, which is where an integer routine written for one signedness
 * quietly stops working: a negative operand where `%` truncates, a zero operand where the answer
 * would otherwise be a division by zero, and an answer too wide for the type it would have to come
 * back in.
 */
class IntegerMathTests extends AnyFreeSpec with RunSupport {

  private val importing = "import sysl.math.*\n\n"

  override protected def run(src: String): String = super.run(importing + src)

  "pow" - {
    "raises to a whole power" in {
      run("print(pow(2, 10u32), pow(3, 4u32), pow(10, 3u32))") shouldBe "1024 81 1000\n"
    }

    // The two ends of the exponent, which the squaring loop reaches by different paths: a zero
    // exponent never enters the loop at all, and a one takes the odd branch on its only pass.
    "a zero exponent is one, a one is the base" in {
      run("print(pow(7, 0u32), pow(7, 1u32), pow(0, 0u32), pow(0, 5u32))") shouldBe "1 7 1 0\n"
    }

    "reaches the unsigned and the wide widths" in {
      run("print(pow(5u64, 3u32), pow(2u8, 5u32), pow(2i64, 40u32))") shouldBe "125 32 1099511627776\n"
    }

    // A negative base is what tells a squaring loop apart from a repeated multiplication that lost
    // the sign: the parity of the exponent has to survive.
    "a negative base keeps the parity of the exponent" in {
      run("print(pow(-2, 3u32), pow(-2, 4u32), pow(-1, 63u32))") shouldBe "-8 16 -1\n"
    }

    // `01` says integer arithmetic wraps, and the doc comment promises this answer rather than
    // leaving it to whatever the loop happened to do.
    "an overflowing power wraps rather than trapping" in {
      run("print(pow(2, 32u32), pow(2u8, 8u32))") shouldBe "0 0\n"
    }
  }

  "gcd" - {
    "the common divisor, at both signednesses" in {
      run("print(gcd(48, 18), gcd(48u32, 18u32), gcd(1071u64, 462u64))") shouldBe "6 6 21\n"
    }

    // The whole reason the magnitude is taken at the end: `%` truncates, so the loop arrives at the
    // right divisor carrying the dividend's sign. A body that returned `x` unmodified would answer
    // `-6` for the first two of these.
    "a negative operand still answers a positive divisor" in {
      run("print(gcd(-48, 18), gcd(48, -18), gcd(-48, -18))") shouldBe "6 6 6\n"
    }

    "zero is the identity" in {
      run("print(gcd(17, 0), gcd(0, 17), gcd(0, 0))") shouldBe "17 17 0\n"
    }

    // Coprime operands are the case a routine passes by accident if it returns the first operand,
    // so this pins the loop actually running to completion.
    "coprime operands answer one" in {
      run("print(gcd(17, 5), gcd(9, 28), gcd(1u8, 1u8))") shouldBe "1 1 1\n"
    }
  }

  "lcm" - {
    "the common multiple, at both signednesses" in {
      run("print(lcm(4, 6), lcm(21u32, 6u32), lcm(3, 5))") shouldBe "12 42 15\n"
    }

    "a negative operand still answers a positive multiple" in {
      run("print(lcm(-4, 6), lcm(4, -6), lcm(-4, -6))") shouldBe "12 12 12\n"
    }

    // Without the guard this is `gcd(0, 0)`, which is zero, and then a division by it.
    "a zero operand answers zero rather than dividing by one" in {
      run("print(lcm(0, 5), lcm(5, 0), lcm(0, 0))") shouldBe "0 0 0\n"
    }

    // The test that fails if the body is ever rewritten as `a * b / gcd(a, b)`: the product of these
    // two overflows `i64` and their least common multiple does not.
    "divides before multiplying, so a fitting answer is not lost to the product" in {
      run("print(lcm(4611686018427387904i64, 2i64))") shouldBe "4611686018427387904\n"
    }
  }

  "divmod" - {
    "binds the quotient and the remainder from one call" in {
      run(
        """show() =
          |    val q, r = divmod(17, 5)
          |    print(q, r)
          |
          |show()""".stripMargin
      ) shouldBe "3 2\n"
    }

    // Both operators truncate toward zero, so the remainder takes the dividend's sign. The doc
    // comment promises exactly these four, which is why all four are here.
    "truncates toward zero, and the remainder follows the dividend" in {
      run(
        """show() =
          |    val a, b = divmod(-17, 5)
          |    val c, d = divmod(17, -5)
          |    print(a, b, c, d)
          |
          |show()""".stripMargin
      ) shouldBe "-3 -2 -3 2\n"
    }

    "reaches the unsigned widths" in {
      run(
        """show() =
          |    val q, r = divmod(200u8, 7u8)
          |    print(q, r)
          |
          |show()""".stripMargin
      ) shouldBe "28 4\n"
    }
  }

  "is_power_of_two" - {
    "one bit set and above zero" in {
      run("print(is_power_of_two(1), is_power_of_two(16), is_power_of_two(1024))") shouldBe
        "true true true\n"
    }

    "anything else is not" in {
      run("print(is_power_of_two(0), is_power_of_two(3), is_power_of_two(17))") shouldBe
        "false false false\n"
    }

    // The case the `> zero` exists for: at a signed width the most negative value has exactly one
    // bit set, so a body written as `count_ones() == 1` alone answers `true` here.
    "the most negative value is not, though it has one bit set" in {
      run("print(is_power_of_two(-2147483648), is_power_of_two(-4), is_power_of_two(-1))") shouldBe
        "false false false\n"
    }

    "reaches the unsigned widths, including their top bit" in {
      run("print(is_power_of_two(128u8), is_power_of_two(255u8), is_power_of_two(1u64))") shouldBe
        "true false true\n"
    }
  }

  "next_power_of_two" - {
    "rounds up to the next power of two" in {
      run(
        "print(next_power_of_two(5).unwrap(), next_power_of_two(17).unwrap(), " +
          "next_power_of_two(1000).unwrap())"
      ) shouldBe "8 32 1024\n"
    }

    // A value already a power of two is its own answer, which is the off-by-one a `<` in place of a
    // `<=` would break.
    "a power of two is already its own answer" in {
      run(
        "print(next_power_of_two(16).unwrap(), next_power_of_two(1).unwrap(), " +
          "next_power_of_two(1024).unwrap())"
      ) shouldBe "16 1 1024\n"
    }

    "zero and the negatives answer one" in {
      run(
        "print(next_power_of_two(0).unwrap(), next_power_of_two(-5).unwrap(), " +
          "next_power_of_two(-2147483648).unwrap())"
      ) shouldBe "1 1 1\n"
    }

    // The whole reason the result is an `Option`. At `u8` the answer stops existing above 128, and
    // the doubling loop this replaced spun forever here rather than saying so.
    "an answer too wide for an unsigned type is absent" in {
      run(
        """print(next_power_of_two(128u8).unwrap(), next_power_of_two(129u8).is_none(),
          |      next_power_of_two(200u8).is_none(), next_power_of_two(255u8).is_none())""".stripMargin
      ) shouldBe "128 true true true\n"
    }

    // A signed width loses its top bit to the sign, so it runs out one power earlier than the
    // unsigned width beside it. This is the pair that fails if signedness is read off the type
    // rather than off the result.
    "a signed type runs out one power before the unsigned width beside it" in {
      run(
        """print(next_power_of_two(1073741824).is_some(), next_power_of_two(1073741825).is_none(),
          |      next_power_of_two(2147483648u32).unwrap(),
          |      next_power_of_two(2147483649u32).is_none())""".stripMargin
      ) shouldBe "true true 2147483648 true\n"
    }
  }

  "one body, many types" - {
    // The claim the free-function choice is made for: a bound is satisfied by whatever satisfies it,
    // so these reach every width at both signednesses from one source body each.
    "the widths agree with one another" in {
      run(
        """print(gcd(48i8, 18i8), gcd(48u8, 18u8), gcd(48i16, 18i16), gcd(48u16, 18u16),
          |      gcd(48i64, 18i64), gcd(48u64, 18u64))""".stripMargin
      ) shouldBe "6 6 6 6 6 6\n"
    }
  }
}
