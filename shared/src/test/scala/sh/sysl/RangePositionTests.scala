package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** A range is a **form** in four positions and a **value** everywhere else, and the two readings
 * must not come to disagree about what a range is.
 *
 * The form is what a `for` header, a slice index, a `match` pattern and a quantifier read: the
 * bounds are taken directly and no `Range` exists in what is emitted, which is what keeps the most
 * ordinary loop in the language a counter and a comparison. The value is `sysl.Range[T]`, built
 * anywhere else, and it is what lets a range be named, passed, returned and implemented for.
 *
 * An **open** end has no value reading at all: what an absent bound is depends on what is being
 * indexed, so `..`, `lo..` and `..hi` stay index-only and are refused elsewhere by name.
 */
class RangePositionTests extends AnyFreeSpec with RunSupport with CodegenSupport {

  "the four positions read a range as a form" - {

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

    // The whole reason the form survives the value: a counted loop stays a counter and a comparison,
    // with no struct built, no `Option` a step and no call to `next`. Reading the emitted IR is the
    // only way to assert it — the two programs print the same numbers either way — and the *pair*
    // is what makes the assertion mean something, since a needle that never appears would pass the
    // first half on a compiler that had stopped emitting anything at all.
    "and the counted loop calls no cursor, where walking a value does" in {
      val counted = ir("for i in 0..<3\n    print(i)\n")
      val walked  = ir("val r = 0..<3\nfor i in r\n    print(i)\n")

      counted should not include "Range.next"
      walked should include("Range.next")
    }
  }

  "a range with both ends written is a value" - {

    "it is bound to a name and walked" in {
      run("""val r = 0..<4
            |var total = 0
            |
            |for i in r
            |    total += i
            |
            |print(total)
            |""".stripMargin) shouldBe "6\n"
    }

    "it is passed to a function and returned from one" in {
      run("""total(r: Range[int]) -> int
            |    var acc = 0
            |
            |    for i in r
            |        acc += i
            |
            |    acc
            |
            |upto(n: int) -> Range[int] = 0..<n
            |
            |print(total(upto(4)))
            |""".stripMargin) shouldBe "6\n"
    }

    "the inclusive form carries its own flag" in {
      run("""val a = 0..3
            |val b = 0..<3
            |var m = 0
            |var n = 0
            |
            |for i in a
            |    m += 1
            |
            |for i in b
            |    n += 1
            |
            |print(m, n)
            |""".stripMargin) shouldBe "4 3\n"
    }

    // The bounds are read by the same two questions the `for` header asks, in the same words, so a
    // range value and a counted loop cannot disagree about what a range is.
    //
    // **Both ends are written as bindings rather than as literals**, because a literal takes its
    // type from the other end: `0..<3u8` is a `u8` range and not a mismatch, exactly as it is in a
    // `for` header.
    "its bounds must agree with each other" in {
      err("""f(a: int, b: u8) -> int
            |    val r = a..<b
            |    0
            |
            |print(f(0, 3))
            |""".stripMargin) should include("matching bounds")
    }

    "and must be integers" in {
      err("""f(a: real, b: real) -> int
            |    val r = a..<b
            |    0
            |
            |print(f(0.0, 3.0))
            |""".stripMargin) should include("integer bounds")
    }
  }

  // A **pre-existing** defect the range work found, in the counted loop rather than in anything new:
  // the step incremented and then tested, and at the top of a width the increment wraps — there is
  // no value one greater than the last one — so `0 <= 255` started the walk again and the loop never
  // ended. Both counted forms had it.
  "an inclusive range ending at the width's maximum" - {

    "finishes, in a counted loop" in {
      run("""var n = 0
            |var last: u8 = 0
            |
            |for b in 250u8..255u8
            |    n += 1
            |    last = b
            |
            |print(n, last)
            |""".stripMargin) shouldBe "6 255\n"
    }

    "finishes, in a quantifier" in {
      run("print(for all i in 250u8..255u8 do i >= 250)\n") shouldBe "true\n"
    }

    "finishes, walked as a value" in {
      run("""val top = 250u8..255u8
            |var n = 0
            |
            |for b in top
            |    n += 1
            |
            |print(n)
            |""".stripMargin) shouldBe "6\n"
    }

    "and the signed end of a width is the same case" in {
      run("""var n = 0
            |
            |for b in 125i8..127i8
            |    n += 1
            |
            |print(n)
            |""".stripMargin) shouldBe "3\n"
    }

    // The guard is on the inclusive form only, so the ordinary loop keeps the shape it had: one
    // compare at the top and an unconditional increment at the bottom.
    "while an exclusive loop gains no second compare" in {
      ir("for i in 0..<3\n    print(i)\n") should not include "for.more"
    }
  }

  "an open end stays a form" - {

    "and is refused where a value was wanted, naming the one place it is legal" in {
      val message = err("val r = 1..\nprint(1)")

      message should include("open end")
      message should include("slice index")
    }

    "in every open spelling" in {
      err("val r = ..2\nprint(1)") should include("open end")
      err("val r = ..\nprint(1)") should include("open end")
    }

    // `..2` is the **inclusive** spelling, so it takes three of the four — the same reading `0..2`
    // has everywhere else, which is the point: an open end changes which bound is missing and not
    // what the operator means.
    "while a slice index still takes all three" in {
      run("""val xs = [1, 2, 3, 4]
            |
            |print(xs[..].len, xs[1..].len, xs[..2].len, xs[..<2].len)
            |""".stripMargin) shouldBe "4 3 3 2\n"
    }
  }
}
