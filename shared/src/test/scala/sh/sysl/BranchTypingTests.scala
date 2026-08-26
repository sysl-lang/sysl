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

  // Card `0294`, from slate. A bare `None` is the same shape as a bare literal one tier along: it is
  // not a literal, so the rule above never reached it, and analyzed alone it is asking what an
  // `Option` of nothing in particular holds. The sibling says.
  "a payload-free variant of a generic enum takes its sibling's type too" - {
    "in the else branch, which is where it was refused" in {
      val src =
        """struct Expr
          |    n: int
          |
          |maybe(c: bool) -> Option[Expr]
          |    val b = if c then Some(Expr(1)) else None
          |    b
          |
          |print(maybe(true).unwrap().n, maybe(false).is_none())
          |""".stripMargin

      run(src) shouldBe "1 true\n"
    }

    "and in the then branch, which is the same rule read the other way" in {
      val src =
        """maybe(c: bool) -> Option[int]
          |    val b = if c then None else Some(4)
          |    b
          |
          |print(maybe(false).unwrap(), maybe(true).is_none())
          |""".stripMargin

      run(src) shouldBe "4 true\n"
    }

    "a match arm settles it the same way" in {
      val src =
        """pick(n: int) -> Option[int]
          |    val b = n match
          |        0 -> None
          |        _ -> Some(n)
          |    b
          |
          |print(pick(0).is_none(), pick(5).unwrap())
          |""".stripMargin

      run(src) shouldBe "true 5\n"
    }

    // A `Result` is the other generic enum with a payload-free variant in reach, and nothing about
    // the rule is special to `Option` — it is asked of the declaration.
    "and it is not special to Option" in {
      val src =
        """enum Step[T]
          |    Done
          |    Going(v: T)
          |
          |go(c: bool) -> Step[int]
          |    val s = if c then Going(2) else Done
          |    s
          |
          |go(true) match
          |    Going(v) -> print(v)
          |    Done -> print("done")
          |""".stripMargin

      run(src) shouldBe "2\n"
    }

    // What has NOT moved: with both branches payload-free there is nothing to settle it, so the
    // annotation is still the only thing that can say — exactly as two bare literals still fall to
    // `int`. The refusal is the same one, and it is right.
    "while two variants that both carry nothing still have nothing to go on" in {
      err("""f(c: bool) =
            |    val b = if c then None else None
            |    print(b.is_none())
            |""".stripMargin) should include("cannot infer the type argument")
    }

    // And a variant that CARRIES something is an answer rather than a question, so it keeps supplying
    // the type rather than taking one — which is what makes the first case above work at all. The
    // payload is a `usize` here rather than a bare `1`, because a literal inside the carrying variant
    // is the *older* tiering and would fall to `int` with nothing to say otherwise: `Some(1)` beside
    // a `None` settles the pair at `Option[int]`, correctly, and that is a separate rule from this
    // one.
    "and a carrying variant still supplies the type rather than taking one" in {
      val src =
        """f(c: bool, n: usize) -> Option[usize]
          |    val b = if c then Some(n) else None
          |    b
          |
          |print(f(true, 1).unwrap() + 2usize)
          |""".stripMargin

      run(src) shouldBe "3\n"
    }

    // The same pair with a bare literal in the payload, pinned because it is the case the comment
    // above is about: nothing here says `usize`, so the whole thing is an `Option[int]`.
    "with a bare payload the pair settles at int, which is the older rule and unchanged" in {
      val src =
        """f(c: bool) -> Option[int]
          |    val b = if c then Some(1) else None
          |    b
          |
          |print(f(true).unwrap(), f(false).is_none())
          |""".stripMargin

      run(src) shouldBe "1 true\n"
    }
  }
}
