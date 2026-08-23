package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** Where a range may be written, and what is said when one is written anywhere else.
 *
 * A range is a **form** rather than a value — there is no `Range` type, so one cannot be named,
 * passed or returned — which makes the refusal the only written statement of the rule. It names all
 * four legal places for that reason: a sentence naming two of them tells a reader who wrote one in
 * a slice index that the index is not allowed either.
 */
class RangePositionTests extends AnyFreeSpec with RunSupport with CodegenSupport {

  "a range outside the four places" - {

    "is refused, and the refusal names every one of them" in {
      val message = err("val r = 0..<10\nprint(1)")

      message should include("a range is only allowed")
      message should include("'for' loop")
      message should include("quantifier")
      message should include("slice index")
      message should include("'match' pattern")
    }

    "is refused wherever a value could stand, not only in a binding" in {
      err("f(r: int) -> int = r\nprint(f(0..<10))") should include("a range is only allowed")
      err("g() -> int\n    0..<10\nprint(g())") should include("a range is only allowed")
    }
  }

  // The other half of the claim, and the half a diagnostic cannot make: each of the four named
  // places really does take one. Without this the message could name a position the compiler
  // refuses and nothing would notice.
  "the four places themselves" - {

    "a 'for' header counts over one" in {
      run("val xs = [1, 2, 3]\nfor i in 0..<xs.len\n    print(i)\n") shouldBe "0\n1\n2\n"
    }

    "a slice index takes one" in {
      run("val xs = [1, 2, 3]\nprint(xs[0..<2].len)\n") shouldBe "2\n"
    }

    "a quantifier takes one" in {
      run("print(for all i in 0..<3 do i < 3, for some i in 0..<3 do i == 2)\n") shouldBe "true true\n"
    }

    "a 'match' pattern takes one" in {
      run("""f(n: int) -> string = n match
            |    0..<2 -> "low"
            |    else "high"
            |
            |print(f(1), f(9))
            |""".stripMargin) shouldBe "low high\n"
    }
  }
}
