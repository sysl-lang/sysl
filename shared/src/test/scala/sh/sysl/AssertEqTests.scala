package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** `assert_eq` and the two beside it — an assertion that reports the **values**, which is the whole
 * of what it adds over `assert(a == b)` (`library/core.md § assert_eq — the assertion that says what the values were`).
 *
 * A failed `assert` names the line, so its reader knows which check broke and has to run the thing
 * again to learn what the values were. These say so the first time. The hand-written alternative —
 * `assert(a == b, s"got $a, want $b")` — evaluates each side twice and builds a string, which makes
 * heap storage and is therefore unavailable to a module under `@no_alloc`; rendering through
 * `Display` costs neither, and the `@no_alloc` case below is what holds that to being true.
 */
class AssertEqTests extends AnyFreeSpec with CodegenSupport with RunSupport {

  /** What a program printed before it stopped itself, and the status it stopped with. A failing
   * assertion is a `panic`, so what it says arrives on the way out rather than as a return value.
   */
  private def stopped(src: String): (Int, String) = {
    val ran = outcomeOf(src)

    (ran.status, ran.out + ran.err)
  }

  "a check that holds says nothing and returns" - {

    "for an integer" in {
      run("assert_eq(6 * 7, 42)\nprint(\"through\")") shouldBe "through\n"
    }

    "for a string, which is a different 'impl Display' and the same function" in {
      run("assert_eq(\"ab\" + \"c\", \"abc\")\nprint(\"through\")") shouldBe "through\n"
    }

    "and for a slice, element by element" in {
      run("assert_slice_eq([1, 2, 3], [1, 2, 3])\nprint(\"through\")") shouldBe "through\n"
    }

    // Both spellings, because both are what a caller reaches for: the literal at a call written out
    // in place, and the slice of something named that a real comparison usually has in hand.
    "written either as a literal or as a slice of something named" in {
      run("var a = [1, 2, 3]\nvar b = [1, 2, 3]\nassert_slice_eq(a[..], b[..])\nprint(\"through\")") shouldBe
        "through\n"
    }
  }

  "a check that fails names both values and where it was written" - {

    "the pair, in the order 'got' then 'want'" in {
      val (code, out) = stopped("assert_eq(6 * 6, 42)")

      code should not be 0
      out should include("got 36, want 42")
    }

    "the caller's line, not this library's" in {
      val (_, out) = stopped("print(\"x\")\nassert_eq(1, 2)")

      out should include("<input>:2")
    }

    "a message where one was given, ahead of the values" in {
      val (_, out) = stopped("assert_eq(1, 2, \"the counter\")")

      out should include("the counter: got 1, want 2")
    }

    "and nothing but the values where one was not" in {
      val (_, out) = stopped("assert_eq(1, 2)")

      out should include("panic: got 1, want 2")
    }
  }

  "a slice reports which element, and a length before any of them" - {

    "the first index the two disagree at" in {
      val (_, out) = stopped("assert_slice_eq([1, 2, 3], [1, 5, 3])")

      out should include("got 2, want 5 at index 1")
    }

    "the lengths, when those are what differ" in {
      val (_, out) = stopped("assert_slice_eq([1, 2], [1, 2, 3])")

      out should include("got 2 elements, want 3")
    }

    "and a length mismatch is reported instead of an index, not as well" in {
      val (_, out) = stopped("assert_slice_eq([9], [1, 2, 3])")

      out should include("got 1 elements, want 3")
      out should not include "at index"
    }
  }

  "the float pair, which cannot use '==' and so cannot use 'assert_eq'" - {

    "an absolute tolerance admits what rounding moved" in {
      run("import sysl.math.*\nassert_approx_eq(0.1 + 0.2, 0.3, 1e-9)\nprint(\"through\")") shouldBe
        "through\n"
    }

    "and refuses what it did not, naming the tolerance" in {
      val (_, out) = stopped("import sysl.math.*\nassert_approx_eq(1.0, 2.0, 0.001)")

      out should include("got 1, want 2 to within 0.001")
    }

    "a relative tolerance is the test at large magnitudes" in {
      run("import sysl.math.*\nassert_approx_eq_rel(1e18, 1e18 + 1.0, 1e-9)\nprint(\"through\")") shouldBe
        "through\n"
    }
  }

  "and none of it allocates, which is what a hand-written message could not promise" in {
    // The whole reason these exist as functions rather than as a line of prose telling people to
    // interpolate. `@no_alloc` is checked against the call graph, so a report that built a string
    // would be refused here rather than merely being slower.
    ir("""@no_alloc
         |
         |check(n: int)
         |    assert_eq(n, 42)
         |
         |check(42)
         |""".stripMargin) should include("@main")
  }
}
