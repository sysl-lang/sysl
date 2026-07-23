package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** Tier-2 runtime behavior of `match`: literal, comma-alternative, range, and wildcard
 * patterns; guards; and use both as a value and as a statement.
 */
class MatchRunTests extends AnyFreeSpec with RunSupport {

  "literal, comma, range, and else arms" in {
    val src =
      """classify(n: int) -> string
        |    match n
        |        0 -> "zero"
        |        1, 2, 3 -> "small"
        |        4..10 -> "medium"
        |        else -> "large"
        |print(classify(0), classify(2), classify(7), classify(99))""".stripMargin

    run(src) shouldBe "zero small medium large\n"
  }

  "an exclusive range pattern excludes its upper bound" in {
    val src =
      """band(n: int) -> string
        |    match n
        |        0..<10 -> "low"
        |        else -> "high"
        |print(band(9), band(10))""".stripMargin

    run(src) shouldBe "low high\n"
  }

  "guards select among wildcard arms" in {
    val src =
      """sign(x: int) -> int
        |    match x
        |        _ if x > 0 -> 1
        |        _ if x < 0 -> -1
        |        else -> 0
        |print(sign(5), sign(-3), sign(0))""".stripMargin

    run(src) shouldBe "1 -1 0\n"
  }

  "match runs as a statement for its effect" in {
    val src =
      """var n = 2
        |match n
        |    1 -> print("one")
        |    2 -> print("two")
        |    else -> print("many")""".stripMargin

    run(src) shouldBe "two\n"
  }
}
