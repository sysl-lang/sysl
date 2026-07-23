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

  // An empty range runs the body zero times, both when the exclusive bound coincides with the
  // start and when an inclusive low bound is already past its high — the off-by-one corner.
  "an empty for range runs its body zero times" in {
    val src =
      """var s = 0
        |for i in 5..<5 do s += 1
        |for i in 5..4 do s += 100
        |print(s)""".stripMargin

    run(src) shouldBe "0\n"
  }

  // A single-iteration range still enters the body exactly once (inclusive a..a).
  "an inclusive range of one element runs the body once" in {
    val src =
      """var s = 0
        |for i in 7..7 do s += i
        |print(s)""".stripMargin

    run(src) shouldBe "7\n"
  }

  // Called with both an even and an odd input, so an "always take one branch" miscompile can't
  // pass by coincidence the way a single input would.
  "if/else picks the right branch" in {
    val src =
      """parity(n: int) -> string
        |    if n % 2 == 0
        |        "even"
        |    else
        |        "odd"
        |end parity
        |print(parity(4), parity(7))""".stripMargin

    run(src) shouldBe "even odd\n"
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
    val src =
      """var i = 0
        |while i < 3 do i++
        |print(i)""".stripMargin

    run(src) shouldBe "3\n"
  }

  "an inline if/then/else" in {
    val src =
      """if 1 < 2 then print("yes") else print("no")""".stripMargin

    run(src) shouldBe "yes\n"
  }

  "an inline then with else on the next line" in {
    val src =
      """if 1 < 2 then print("t")
        |else print("f")""".stripMargin

    run(src) shouldBe "t\n"
  }

  "a program using end markers runs correctly" in {
    val src =
      """var i = 0
        |var sum = 0
        |while i < 5
        |    sum = sum + i
        |    i++
        |end while
        |if sum == 10 then
        |    print("ten")
        |else
        |    print("other")
        |end if""".stripMargin

    run(src) shouldBe "ten\n"
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

  "an inclusive for range sums its bounds" in {
    val src =
      """var total = 0
        |for i in 1..5
        |    total = total + i
        |print(total)""".stripMargin

    run(src) shouldBe "15\n"
  }

  "an exclusive for range stops before its upper bound" in {
    val src =
      """var total = 0
        |for i in 0..<5 do total = total + i
        |print(total)""".stripMargin

    run(src) shouldBe "10\n"
  }

  "nested for loops accumulate" in {
    val src =
      """var grid = 0
        |for i in 0..<3
        |    for j in 0..<3
        |        grid = grid + i * j
        |print(grid)""".stripMargin

    run(src) shouldBe "9\n"
  }

  "if used as a value binds the taken branch" in {
    val src =
      """var n = 7
        |var label = if n % 2 == 0 then "even" else "odd"
        |print(label)""".stripMargin

    run(src) shouldBe "odd\n"
  }

  "a for loop variable shadows an outer variable" in {
    val src =
      """var i = 100
        |var sum = 0
        |for i in 1..3
        |    sum = sum + i
        |print(i, sum)""".stripMargin

    run(src) shouldBe "100 6\n"
  }
}
