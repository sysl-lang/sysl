package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** `val (a, b) = …` — a binding that takes a tuple apart by pattern (`00 §13`).
 *
 * The comma form beside it, `val a, b = …`, already took a tuple apart at one level, and the two are
 * not redundant: a list of names says how *many* things to bind and a pattern says the **shape**, so
 * only the pattern reaches inside a nested tuple or skips a part it does not want. What these assert
 * first is therefore the thing the comma form cannot do.
 *
 * The second half is the refusals. A binding has no other arm to take, so a pattern that could fail
 * to match cannot stand in one — and the diagnostic has to say *that*, rather than reporting the
 * match-arm rules at a reader who is not writing a match.
 */
class PatternBindingTests extends AnyFreeSpec with RunSupport with CodegenSupport {

  "a tuple, taken apart" - {
    "two names" in {
      run(
        """show() =
          |    val p = (3, 4)
          |    val (a, b) = p
          |    print(a, b)
          |
          |show()""".stripMargin
      ) shouldBe "3 4\n"
    }

    "straight from a call, with no name in between" in {
      run(
        """origin() -> (int, int) = (7, 9)
          |
          |show() =
          |    val (x, y) = origin()
          |    print(x, y)
          |
          |show()""".stripMargin
      ) shouldBe "7 9\n"
    }

    "parts of different types" in {
      run(
        """show() =
          |    val (n, s, f) = (1, "one", true)
          |    print(n, s, f)
          |
          |show()""".stripMargin
      ) shouldBe "1 one true\n"
    }

    // The reason the form exists beside the comma one: a list of names has no way to say which
    // level a name belongs to.
    "a nested tuple, which the comma form cannot reach" in {
      run(
        """show() =
          |    val ((a, b), c) = ((1, 2), 3)
          |    print(a, b, c)
          |
          |show()""".stripMargin
      ) shouldBe "1 2 3\n"
    }

    "nested on both sides, to any depth" in {
      run(
        """show() =
          |    val ((a, (b, c)), (d, e)) = ((1, (2, 3)), (4, 5))
          |    print(a, b, c, d, e)
          |
          |show()""".stripMargin
      ) shouldBe "1 2 3 4 5\n"
    }

    "a wildcard binds nothing and skips its part" in {
      run(
        """show() =
          |    val (a, _, c) = (1, 2, 3)
          |    print(a, c)
          |
          |show()""".stripMargin
      ) shouldBe "1 3\n"
    }

    "a wildcard nested inside" in {
      run(
        """show() =
          |    val ((_, b), _) = ((1, 2), 3)
          |    print(b)
          |
          |show()""".stripMargin
      ) shouldBe "2\n"
    }
  }

  "var makes the parts assignable" - {
    "and val does not" in {
      run(
        """show() =
          |    var (a, b) = (3, 4)
          |    a = a + 10
          |    b = b + 20
          |    print(a, b)
          |
          |show()""".stripMargin
      ) shouldBe "13 24\n"
    }

    "a val part is refused an assignment" in {
      err(
        """show() =
          |    val (a, b) = (3, 4)
          |    a = 1
          |
          |show()""".stripMargin
      ) should include("a 'val' is written once")
    }
  }

  // The value is one expression and the parts are reads of one temporary, so a call on the right
  // runs once however many names the pattern binds. Reading the expression per part would print
  // "side" three times here.
  "the value is produced once, however many parts are bound" in {
    run(
      """make() -> (int, int, int) =
        |    print("side")
        |    (1, 2, 3)
        |
        |show() =
        |    val (a, b, c) = make()
        |    print(a, b, c)
        |
        |show()""".stripMargin
    ) shouldBe "side\n1 2 3\n"
  }

  "the edges" - {
    // The expansion holds the whole value in a temporary and reads fields out of it, so a part that
    // is counted has to be retained by the read exactly as a field read anywhere else is. If it
    // were not, the box would be freed with the temporary and this would print through freed memory.
    "a counted part outlives the temporary the value was held in" in {
      run(
        """struct Cell
          |    n: int
          |
          |make() -> (&Cell, &Cell) =
          |    val a: &Cell = Cell(1)
          |    val b: &Cell = Cell(2)
          |    (a, b)
          |
          |show() =
          |    val (a, b) = make()
          |    print(a.n, b.n)
          |
          |show()""".stripMargin
      ) shouldBe "1 2\n"
    }

    "a binding in a loop body runs once per iteration" in {
      run(
        """show() =
          |    var i = 0
          |    while i < 3 do
          |        val (a, b) = (i, i * 2)
          |        print(a, b)
          |        i = i + 1
          |
          |show()""".stripMargin
      ) shouldBe "0 0\n1 2\n2 4\n"
    }

    // The right side is produced before any name is bound, which is the comma form's rule and has to
    // be the pattern form's too: `a` here is the outer one, not the one being bound.
    "the value still means the enclosing scope's names" in {
      run(
        """show() =
          |    val a = 10
          |    val (a2, b) = (a, a + 1)
          |    print(a2, b)
          |
          |show()""".stripMargin
      ) shouldBe "10 11\n"
    }

    "a pattern that binds nothing at all is still a binding" in {
      run(
        """show() =
          |    val (_, _) = (1, 2)
          |    print("done")
          |
          |show()""".stripMargin
      ) shouldBe "done\n"
    }

    // The parser refuses a one-part tuple pattern where the type refuses a one-tuple, so this is a
    // parse-time message rather than a type error about a tuple of one.
    "a one-part pattern is not a tuple" in {
      err(
        """show() =
          |    val (a) = 1
          |    print(a)
          |
          |show()""".stripMargin
      ) should include("one part is not a tuple")
    }
  }

  "what a binding will not accept" - {
    // A binding has nowhere to fall through to, so a pattern that is a *test* cannot stand in one.
    "a literal, which matches only some values" in {
      err(
        """show() =
          |    val (1, b) = (1, 2)
          |    print(b)
          |
          |show()""".stripMargin
      ) should include("no other arm")
    }

    "a range, for the same reason" in {
      err(
        """show() =
          |    val (1..3, b) = (1, 2)
          |    print(b)
          |
          |show()""".stripMargin
      ) should include("no other arm")
    }

    "a variant, which is a choice among shapes" in {
      err(
        """enum Shape
          |    Circle(r: int)
          |    Square(s: int)
          |
          |show() =
          |    val (Circle(r), b) = (Circle(1), 2)
          |    print(r, b)
          |
          |show()""".stripMargin
      ) should include("no other arm")
    }

    "more parts than the tuple has" in {
      err(
        """show() =
          |    val (a, b, c) = (1, 2)
          |    print(a)
          |
          |show()""".stripMargin
      ) should include("to give it")
    }

    "fewer parts than the tuple has" in {
      err(
        """show() =
          |    val (a, b) = (1, 2, 3)
          |    print(a)
          |
          |show()""".stripMargin
      ) should include("to give it")
    }

    "a value that is not a tuple at all" in {
      err(
        """show() =
          |    val (a, b) = 5
          |    print(a)
          |
          |show()""".stripMargin
      ) should include("only a tuple is")
    }

    "one name used twice" in {
      err(
        """show() =
          |    val (a, a) = (1, 2)
          |    print(a)
          |
          |show()""".stripMargin
      ) should include("named twice")
    }

    // The same rule the comma form meets: the parts have nowhere to carry a type, so a module-level
    // one would quietly become a local of the entry point.
    "a module-level val, whose parts have nowhere to state a type" in {
      err("val (a, b) = (1, 2)\n") should include("nowhere to write one")
    }
  }
}
