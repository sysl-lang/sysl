package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** Tier-2 edge cases for integers: wrapping at each width boundary, the sign rules of division
 * and remainder, shifting into the sign bit, and signed-vs-unsigned comparison at the extreme —
 * the places an off-by-one, a wrong-signedness instruction, or a missing wrap would show.
 */
class IntegerEdgeRunTests extends AnyFreeSpec with RunSupport {

  "addition wraps past the maximum, signed and unsigned, at each width" in {
    run(
      """var a: i8 = 127
        |var b: u8 = 255
        |var c: i16 = 32767
        |var d: u16 = 65535
        |print(a + 1, b + 1, c + 1, d + 1)""".stripMargin
    ) shouldBe "-128 0 -32768 0\n"
  }

  "subtraction wraps below the minimum" in {
    run(
      """var a: i8 = -128
        |var b: u8 = 0
        |print(a - 1, b - 1)""".stripMargin
    ) shouldBe "127 255\n"
  }

  "multiplication overflow keeps the low bits of the width" in {
    run(
      """var a: i8 = 127
        |var b: u8 = 255
        |print(a * 2, b * 2)""".stripMargin
    ) shouldBe "-2 254\n"
  }

  "negating the signed minimum wraps back to the minimum" in {
    run(
      """var m: i8 = -128
        |print(-m)""".stripMargin
    ) shouldBe "-128\n"
  }

  "division truncates toward zero for every sign combination" in {
    run("print(7 / 3, -7 / 3, 7 / -3, -7 / -3)") shouldBe "2 -2 -2 2\n"
  }

  "remainder takes the sign of the dividend for every sign combination" in {
    run("print(7 % 3, -7 % 3, 7 % -3, -7 % -3)") shouldBe "1 -1 1 -1\n"
  }

  "a left shift can reach the sign bit" in {
    run(
      """var one: i8 = 1
        |print(one << 7)""".stripMargin
    ) shouldBe "-128\n"
  }

  "comparison uses the signedness of the type at the sign boundary" in {
    run(
      """var s: i8 = -1
        |var u: u8 = 255
        |print(s < 0, s > 0, u > 128, u < 128)""".stripMargin
    ) shouldBe "true false true false\n"
  }
}
