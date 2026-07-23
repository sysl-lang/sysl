package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

/** Tier-2: compile a program all the way to a native binary, run it, and check its output.
 * These need an LLVM toolchain, so each test cancels cleanly when `clang` is absent.
 */
class RunTests extends AnyFreeSpec with Matchers {

  private def run(src: String): String = {
    assume(Toolchain.clangAvailable, "clang not available")

    Toolchain.compileAndRun(src) match {
      case Right((0, out)) => out
      case Right((code, out)) => fail(s"program exited with $code:\n$out")
      case Left(err)          => fail(err)
    }
  }

  "hello world prints a string" in {
    run("print(\"Hello, sysl!\")") shouldBe "Hello, sysl!\n"
  }

  "arithmetic evaluates correctly" in {
    run("print(6 * 7)") shouldBe "42\n"
  }

  "arguments are space-separated" in {
    run("print(\"answer\", 42)") shouldBe "answer 42\n"
  }

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

  "floats print and compute" in {
    run("print(1.5 + 2.5)") shouldBe "4\n"
  }

  "booleans print as words" in {
    run("print(1 < 2 && 2 < 3)") shouldBe "true\n"
  }

  "an inline while body with `do`" in {
    run("var i = 0\nwhile i < 3 do i++\nprint(i)") shouldBe "3\n"
  }

  "an inline if/then/else" in {
    run("if 1 < 2 then print(\"yes\") else print(\"no\")") shouldBe "yes\n"
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
