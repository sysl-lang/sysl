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

  // Asymmetric operands so an operand-order or wrong-instruction bug shows: a - b and b - a
  // differ, a / b is not b / a, and each shift and bitwise op has a distinct result.
  "every binary operator, with order-sensitive operands" in {
    val src =
      """var a = 20
        |var b = 6
        |print(a + b, a - b, b - a, a * b, a / b, a % b, a << 1, a >> 1, a & b, a | b, a ^ b)""".stripMargin

    run(src) shouldBe "26 14 -14 120 3 2 40 10 4 22 18\n"
  }

  // Each comparison must yield both outcomes across the row, so an always-true or always-false
  // miscompile of any one of them changes the printed string.
  "every comparison operator, both outcomes" in {
    val src =
      """var a = 3
        |var b = 7
        |print(a < b, a > b, a <= b, a >= b, a == b, a != b, b >= b, b == b)""".stripMargin

    run(src) shouldBe "true false true false false true true true\n"
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

  // Regression: the middle operand of a chain used to be emitted for both the left and the right
  // comparison, so a side-effecting one ran twice. It must be evaluated exactly once.
  "a chained comparison evaluates each operand exactly once" in {
    val src =
      """tick(n: int) -> int
        |    print("tick")
        |    n
        |end tick
        |print(1 < tick(5) < 10)""".stripMargin

    run(src) shouldBe "tick\ntrue\n"
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
