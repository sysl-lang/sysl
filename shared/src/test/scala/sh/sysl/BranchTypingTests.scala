package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** What an `if` or a `match` used as a value does when one of its branches is a bare literal.
 *
 * A branch is one of two — or of many — positions that have to agree on one type, which is the
 * shape a binary operator's operands and a range's two ends already have: the side that knows what
 * it is supplies the type, and the side that has none takes it. `n + 1` has never needed a suffix
 * for that reason, and `if n == 0 then 1 else n` had to have one until this.
 *
 * The other half is what has *not* moved, and it is what the rule was protecting: two branches that
 * each genuinely know what they are still have to agree, in the same words as before. A literal is
 * the exception because it has no opinion to be overridden — nothing is being silently decided by
 * whichever branch the reader looked at second.
 */
class BranchTypingTests extends AnyFreeSpec with RunSupport with CodegenSupport {

  "a bare literal takes the type of the branch beside it" - {
    // The seam is the *call*, not the arithmetic: `width` takes a `usize`, so an `if` that came out
    // as `int` does not compile at all. Asserting only that the program runs would pass on a
    // program where both branches had quietly become `int`.
    "in the else branch, checked by handing the result to something that takes a usize" in {
      val src =
        """width(n: usize) -> usize = n
          |
          |gap(a: usize, b: usize) -> usize =
          |    width(if a > b then a - b else 0)
          |
          |print(gap(9, 2))
          |print(gap(2, 9))""".stripMargin

      run(src) shouldBe "7\n0\n"
    }

    "in the then branch, which is the direction that needs the else read first" in {
      val src =
        """width(n: usize) -> usize = n
          |
          |atLeastOne(n: usize) -> usize =
          |    width(if n == 0 then 1 else n)
          |
          |print(atLeastOne(0))
          |print(atLeastOne(4))""".stripMargin

      run(src) shouldBe "1\n4\n"
    }

    // The declaration is where the annotation used to have to go, and it said nothing a reader
    // wanted to know — `val w: usize` above a line whose other branch is already a `usize`.
    "a val takes the width without being told it" in {
      val src =
        """f(n: usize) -> usize =
          |    val w = if n == 0 then 1 else n
          |    w
          |
          |print(f(0))""".stripMargin

      run(src) shouldBe "1\n"
    }

    "an elif chain settles from the one branch that knows" in {
      val src =
        """width(n: usize) -> usize = n
          |
          |f(n: usize) -> usize =
          |    width(if n == 0 then 1 elif n == 1 then 2 else n)
          |
          |print(f(0))
          |print(f(1))
          |print(f(6))""".stripMargin

      run(src) shouldBe "1\n2\n6\n"
    }

    // Not an integer-only rule: a float literal has no width of its own either, and `0.0` beside an
    // `f32` used to fall to `real` and be refused for it.
    "a float literal takes its sibling's width" in {
      val src =
        """take(x: f32) -> f32 = x
          |
          |clamp(x: f32) -> f32 =
          |    take(if x > 0.0f32 then x else 0.0)
          |
          |print(clamp(2.5f32))
          |print(clamp(-1.0f32))""".stripMargin

      run(src) shouldBe "2.5\n0\n"
    }
  }

  "a match arm is the same rule over as many alternatives as the form has" - {
    "a literal arm takes what the arms that know settled" in {
      val src =
        """width(n: usize) -> usize = n
          |
          |f(n: usize) -> usize =
          |    val w = n match
          |        0 -> 1
          |        else n
          |    width(w)
          |
          |print(f(0))
          |print(f(3))""".stripMargin

      run(src) shouldBe "1\n3\n"
    }

    // The arm that knows is the *last* one here, so the rule cannot be "the first arm decides".
    "the arm that knows may be written after the ones that do not" in {
      val src =
        """width(n: usize) -> usize = n
          |
          |f(n: usize) -> usize =
          |    val w = n match
          |        0 -> 1
          |        1 -> 2
          |        else n
          |    width(w)
          |
          |print(f(0))
          |print(f(1))
          |print(f(8))""".stripMargin

      run(src) shouldBe "1\n2\n8\n"
    }

    // An arm that does not finish settles nothing, so it must not be mistaken for the arm that
    // knows — `never` is set aside here exactly as `matchResultType` sets it aside.
    "an arm that does not finish still settles nothing" in {
      val src =
        """width(n: usize) -> usize = n
          |
          |f(n: usize) -> usize =
          |    val w = n match
          |        0 -> 1
          |        1 -> return 99
          |        else n
          |    width(w)
          |
          |print(f(0))
          |print(f(1))
          |print(f(5))""".stripMargin

      run(src) shouldBe "1\n99\n5\n"
    }
  }

  "what the rule does not do" - {
    // The property the refusal exists for: neither side is silently decided by the other, because
    // both of them arrived with an opinion.
    "two branches that each know their type still have to agree" in {
      err("""f(a: int, b: usize) =
            |    val w = if a == 0 then a else b
            |    print(w)
            |""".stripMargin) should include("if branches have different types: int and usize")
    }

    "and so do two arms" in {
      err("""f(a: int, b: usize) =
            |    val w = a match
            |        0 -> a
            |        else b
            |    print(w)
            |""".stripMargin) should include("match arms have different types: int and usize")
    }

    // A literal is adaptable, not universal: nothing makes an integer literal into a string, so the
    // refusal is the one it always was.
    "a literal beside something it cannot be is refused as before" in {
      err("""f(s: string) =
            |    val w = if true then 1 else s
            |    print(w)
            |""".stripMargin) should include("if branches have different types")
    }

    // With a type already written, both branches were being told it before this rule existed and
    // still are — the literal is checked against what was asked for rather than against its sibling.
    "a written type still overrules both branches" in {
      err("""f(b: usize) =
            |    val w: string = if true then 1 else b
            |    print(w)
            |""".stripMargin) should not be empty
    }
  }
}
