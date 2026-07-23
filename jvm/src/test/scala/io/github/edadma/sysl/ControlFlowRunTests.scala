package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** Tier-2 runtime behavior of control flow: loops, branches, elif chains, inline forms. */
class ControlFlowRunTests extends AnyFreeSpec with RunSupport {

  "a while loop sums 1..10" in {
    val src =
      """var sum = 0
        |var i = 1
        |while i <= 10
        |    sum = sum + i
        |    i++
        |print(sum)""".stripMargin

    run(src) shouldBe "55\n"
  }

  "if/else picks the right branch" in {
    val src =
      """var n = 7
        |if n % 2 == 0
        |    print("even")
        |else
        |    print("odd")""".stripMargin

    run(src) shouldBe "odd\n"
  }

  "an elif chain picks the matching branch" in {
    val src =
      """var n = 2
        |if n == 1 then print("one")
        |elif n == 2 then print("two")
        |elif n == 3 then print("three")
        |else print("many")""".stripMargin

    run(src) shouldBe "two\n"
  }

  "an elif chain falling through to else" in {
    val src =
      """var n = 9
        |if n == 1 then print("one")
        |elif n == 2 then print("two")
        |else print("many")""".stripMargin

    run(src) shouldBe "many\n"
  }

  "an inline while body with `do`" in {
    run("var i = 0\nwhile i < 3 do i++\nprint(i)") shouldBe "3\n"
  }

  "an inline if/then/else" in {
    run("if 1 < 2 then print(\"yes\") else print(\"no\")") shouldBe "yes\n"
  }

  "an inline then with else on the next line" in {
    run("if 1 < 2 then print(\"t\")\nelse print(\"f\")") shouldBe "t\n"
  }

  "a factorial loop" in {
    val src =
      """var n = 5
        |var fact = 1
        |var k = 2
        |while k <= n
        |    fact = fact * k
        |    k++
        |print("5! =", fact)""".stripMargin

    run(src) shouldBe "5! = 120\n"
  }
}
