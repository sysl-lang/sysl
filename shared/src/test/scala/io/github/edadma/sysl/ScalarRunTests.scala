package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** Tier-2: the widened scalar table — integer widths and signedness, the IEEE float widths,
 * `char`, and the explicit conversions between them.
 */
class ScalarRunTests extends AnyFreeSpec with RunSupport {

  "integer widths" - {
    "arithmetic wraps at the declared width" in {
      run("""var b: byte = 200
            |var c: byte = 100
            |print(b + c)
            |""".stripMargin) shouldBe "44\n"
    }

    "an odd width wraps at its own bit count" in {
      run("""var n: u12 = 4095
            |print(n, n + 1)
            |""".stripMargin) shouldBe "4095 0\n"
    }

    "a signed minimum is writable as a negated literal" in {
      run("""var s: i8 = -128
            |var w: i32 = -2147483648
            |print(s, w)
            |""".stripMargin) shouldBe "-128 -2147483648\n"
    }

    "the 64-bit extremes print unmangled" in {
      run("""var big: i64 = 9223372036854775807
            |var u: u64 = 18446744073709551615
            |print(big, u)
            |""".stripMargin) shouldBe "9223372036854775807 18446744073709551615\n"
    }

    "a literal takes the width its context expects" in {
      run("""f(x: u16) -> u16 = x * 2
            |print(f(300))
            |""".stripMargin) shouldBe "600\n"
    }

    "a suffix names the width on the spot" in {
      run("print(7i8, 250u8, 10usize)") shouldBe "7 250 10\n"
    }

    "usize divides and compares unsigned" in {
      run("""var n: usize = 40
            |print(n / 3, n % 3, n > 39usize)
            |""".stripMargin) shouldBe "13 1 true\n"
    }
  }

  "signedness picks the instruction" - {
    "division and remainder" in {
      run("""var s: i32 = -7
            |var u: u32 = 4294967289
            |print(s / 2, u / 2)
            |""".stripMargin) shouldBe "-3 2147483644\n"
    }

    "the right shift keeps the sign only when the type has one" in {
      run("""var s: i8 = -8
            |var u: u8 = 200
            |print(s >> 1, u >> 2)
            |""".stripMargin) shouldBe "-4 50\n"
    }

    "comparison of a large unsigned value is not a negative one" in {
      run("""var u: u32 = 4000000000
            |print(u > 1u32)
            |""".stripMargin) shouldBe "true\n"
    }
  }

  "floating-point widths" - {
    "f32 computes and prints" in {
      run("""var f: f32 = 1.5
            |print(f, f * 2.0)
            |""".stripMargin) shouldBe "1.5 3\n"
    }

    "f16 holds a value it can represent exactly" in {
      run("""var h: f16 = 0.5
            |print(h + 0.25)
            |""".stripMargin) shouldBe "0.75\n"
    }
  }

  "for loops follow the width of their bounds" in {
    run("""var count: u16 = 0
          |for i in 0u16..<5u16
          |    count += i
          |print(count)
          |""".stripMargin) shouldBe "10\n"
  }

  "char" - {
    "prints as UTF-8 at every encoded length" in {
      run("print('A', 'é', '\\u{2603}', '\\u{1F600}')") shouldBe "A é ☃ 😀\n"
    }

    "orders by scalar value, which chains" in {
      run("""var c = 'q'
            |print('a' <= c <= 'z')
            |""".stripMargin) shouldBe "true\n"
    }

    "matches literals and ranges" in {
      run("""kind(c: char) -> string
            |    c match
            |        'a'..'z' -> "lower"
            |        ' ' -> "space"
            |        else "other"
            |end kind
            |print(kind('m'), kind(' '), kind('7'))
            |""".stripMargin) shouldBe "lower space other\n"
    }
  }

  "conversions" - {
    "widen, narrow, and change signedness" in {
      run("""var s: i8 = -1
            |print(i32(s), byte(s), u16(300u32), byte(300u32))
            |""".stripMargin) shouldBe "-1 255 300 44\n"
    }

    "cross between integer and floating point" in {
      run("print(int(3.9), int(-3.9), f32(7) / f32(2), real(3))") shouldBe "3 -3 3.5 3\n"
    }

    "carry a char to its codepoint and back" in {
      run("print(u32('A'), char(9731))") shouldBe "65 ☃\n"
    }
  }
}
