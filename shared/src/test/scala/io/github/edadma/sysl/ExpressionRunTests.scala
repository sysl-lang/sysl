package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** Tier-2 runtime behavior of expressions and `print`. */
class ExpressionRunTests extends AnyFreeSpec with RunSupport {

  "hello world prints a string" in {
    run("print(\"Hello, sysl!\")") shouldBe "Hello, sysl!\n"
  }

  "arithmetic evaluates correctly" in {
    run("print(6 * 7)") shouldBe "42\n"
  }

  "arguments are space-separated" in {
    run("print(\"answer\", 42)") shouldBe "answer 42\n"
  }

  "floats print and compute" in {
    run("print(1.5 + 2.5)") shouldBe "4\n"
  }

  "booleans print as words" in {
    run("print(1 < 2 && 2 < 3)") shouldBe "true\n"
  }

  "a chained comparison a < b < c" in {
    run("var x = 5\nprint(1 < x < 10, 1 < x < 3, 1 <= x < 10)") shouldBe "true false true\n"
  }
}
