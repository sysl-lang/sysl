package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** Tier-2 runtime behavior of user functions: expression and block bodies, recursion,
 * mutual recursion via hoisting, parameters, and a unit-returning function.
 */
class FunctionRunTests extends AnyFreeSpec with RunSupport {

  "an expression-bodied function is called" in {
    val src =
      """add(a: int, b: int) -> int = a + b
        |print(add(3, 4))""".stripMargin

    run(src) shouldBe "7\n"
  }

  "a block-bodied function returns its trailing expression" in {
    val src =
      """square(n: int) -> int
        |    var s = n * n
        |    s
        |print(square(6))""".stripMargin

    run(src) shouldBe "36\n"
  }

  "an explicit return exits early" in {
    val src =
      """classify(n: int) -> int
        |    if n < 0 then return -1
        |    if n == 0 then return 0
        |    1
        |print(classify(-5), classify(0), classify(9))""".stripMargin

    run(src) shouldBe "-1 0 1\n"
  }

  "a recursive function computes factorial" in {
    val src =
      """fact(n: int) -> int =
        |    if n <= 1 then 1 else n * fact(n - 1)
        |print(fact(5))""".stripMargin

    run(src) shouldBe "120\n"
  }

  "mutually recursive functions resolve regardless of order" in {
    val src =
      """isEven(n: int) -> bool = if n == 0 then true else isOdd(n - 1)
        |isOdd(n: int) -> bool = if n == 0 then false else isEven(n - 1)
        |print(isEven(10), isOdd(10))""".stripMargin

    run(src) shouldBe "true false\n"
  }

  "a function may be declared after the code that calls it" in {
    val src =
      """print(twice(21))
        |twice(n: int) -> int = n * 2""".stripMargin

    run(src) shouldBe "42\n"
  }

  "a unit-returning function runs for its effect" in {
    val src =
      """greet(name: string)
        |    print("hi", name)
        |greet("sysl")""".stripMargin

    run(src) shouldBe "hi sysl\n"
  }
}
