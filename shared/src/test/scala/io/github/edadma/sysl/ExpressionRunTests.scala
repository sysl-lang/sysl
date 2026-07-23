package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** Tier-2 runtime behavior of expressions and `print`. */
class ExpressionRunTests extends AnyFreeSpec with RunSupport {

  "hello world prints a string" in {
    val src =
      """print("Hello, sysl!")""".stripMargin

    run(src) shouldBe "Hello, sysl!\n"
  }

  "arithmetic evaluates correctly" in {
    val src =
      """print(6 * 7)""".stripMargin

    run(src) shouldBe "42\n"
  }

  "arguments are space-separated" in {
    val src =
      """print("answer", 42)""".stripMargin

    run(src) shouldBe "answer 42\n"
  }

  "floats print and compute" in {
    val src =
      """print(1.5 + 2.5)""".stripMargin

    run(src) shouldBe "4\n"
  }

  "booleans print as words" in {
    val src =
      """print(1 < 2 && 2 < 3)""".stripMargin

    run(src) shouldBe "true\n"
  }

  "a chained comparison a < b < c" in {
    val src =
      """var x = 5
        |print(1 < x < 10, 1 < x < 3, 1 <= x < 10)""".stripMargin

    run(src) shouldBe "true false true\n"
  }

  "compound assignment operators update in place" in {
    val src =
      """var x = 10
        |x += 5
        |x -= 2
        |x *= 3
        |x /= 2
        |print(x)""".stripMargin

    run(src) shouldBe "19\n"
  }

  "bitwise compound assignment" in {
    val src =
      """var b = 12
        |b &= 10
        |b |= 1
        |b <<= 2
        |print(b)""".stripMargin

    run(src) shouldBe "36\n"
  }
}
