package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** `val (a, b) = …` — a binding that takes a tuple apart by pattern (`reference/types.md §
 * Tuples`).
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

  // A struct has exactly one shape, so naming it cannot fail — the same property that makes a tuple
  // pattern irrefutable, and `09 §` calls a tuple pattern the positional form of this one.
  "a struct, taken apart by field name" - {
    "every field" in {
      run(
        """struct Point
          |    x: int
          |    y: int
          |
          |show() =
          |    val Point{x, y} = Point(3, 4)
          |    print(x, y)
          |
          |show()""".stripMargin
      ) shouldBe "3 4\n"
    }

    // Unlisted fields bind nothing, which is where a binding differs from a match arm: there is no
    // exhaustiveness to discharge, so nothing has to stand in for the fields left out.
    "only the fields it names" in {
      run(
        """struct Point
          |    x: int
          |    y: int
          |    z: int
          |
          |show() =
          |    val Point{y} = Point(1, 2, 3)
          |    print(y)
          |
          |show()""".stripMargin
      ) shouldBe "2\n"
    }

    "fields in an order of its own" in {
      run(
        """struct Point
          |    x: int
          |    y: int
          |
          |show() =
          |    val Point{y, x} = Point(3, 4)
          |    print(x, y)
          |
          |show()""".stripMargin
      ) shouldBe "3 4\n"
    }

    "a field renamed to a sub-pattern, and a struct nested in one" in {
      run(
        """struct Point
          |    x: int
          |    y: int
          |
          |struct Line
          |    a: Point
          |    b: Point
          |
          |show() =
          |    val Line{a: Point{x, y}, b: Point{x: bx}} = Line(Point(1, 2), Point(3, 4))
          |    print(x, y, bx)
          |
          |show()""".stripMargin
      ) shouldBe "1 2 3\n"
    }

    "a struct inside a tuple, and a tuple inside a struct" in {
      run(
        """struct Pair
          |    both: (int, int)
          |
          |struct Point
          |    x: int
          |    y: int
          |
          |show() =
          |    val (Point{x, y}, n) = (Point(1, 2), 3)
          |    val Pair{both: (a, b)} = Pair((4, 5))
          |    print(x, y, n, a, b)
          |
          |show()""".stripMargin
      ) shouldBe "1 2 3 4 5\n"
    }

    "var makes the named fields assignable" in {
      run(
        """struct Point
          |    x: int
          |    y: int
          |
          |show() =
          |    var Point{x, y} = Point(3, 4)
          |    x = x + 10
          |    print(x, y)
          |
          |show()""".stripMargin
      ) shouldBe "13 4\n"
    }

    "a counted field is retained by the read" in {
      run(
        """struct Inner
          |    n: int
          |
          |struct Outer
          |    cell: &Inner
          |
          |make() -> Outer =
          |    val c: &Inner = Inner(7)
          |    Outer(c)
          |
          |show() =
          |    val Outer{cell} = make()
          |    print(cell.n)
          |
          |show()""".stripMargin
      ) shouldBe "7\n"
    }
  }

  // `Name(…)` reads as a variant pattern until the value's type says otherwise, which is the rule an
  // arm already applies. Against a struct it is one shape, so it is irrefutable for the reason the
  // named form is — and it names every field, which is what turns adding one to the struct into a
  // checked to-do rather than a binding that quietly went on binding fewer.
  "a struct, taken apart by position" - {
    "every field, in declaration order" in {
      run(
        """struct Point
          |    x: int
          |    y: int
          |
          |show() =
          |    val Point(x, y) = Point(3, 4)
          |    print(x, y)
          |
          |show()""".stripMargin
      ) shouldBe "3 4\n"
    }

    "nested in a tuple, and with a tuple nested in it" in {
      run(
        """struct Pair
          |    both: (int, int)
          |
          |struct Point
          |    x: int
          |    y: int
          |
          |show() =
          |    val (Point(x, y), n) = (Point(1, 2), 3)
          |    val Pair((a, b)) = Pair((4, 5))
          |    print(x, y, n, a, b)
          |
          |show()""".stripMargin
      ) shouldBe "1 2 3 4 5\n"
    }

    "a wildcard skips a field it does not want" in {
      run(
        """struct Point
          |    x: int
          |    y: int
          |    z: int
          |
          |show() =
          |    val Point(_, y, _) = Point(1, 2, 3)
          |    print(y)
          |
          |show()""".stripMargin
      ) shouldBe "2\n"
    }

    "var makes the bound fields assignable" in {
      run(
        """struct Point
          |    x: int
          |    y: int
          |
          |show() =
          |    var Point(x, y) = Point(3, 4)
          |    x = x + 10
          |    print(x, y)
          |
          |show()""".stripMargin
      ) shouldBe "13 4\n"
    }

    // The value is read once, as it is for every other pattern at a binding: two names out of one
    // struct must not mean two calls.
    "the value is evaluated exactly once" in {
      run(
        """struct Point
          |    x: int
          |    y: int
          |
          |made(n: *int) -> Point =
          |    *n = *n + 1
          |    Point(1, 2)
          |
          |show() =
          |    var calls = 0
          |    val Point(x, y) = made(&calls)
          |    print(x, y, calls)
          |
          |show()""".stripMargin
      ) shouldBe "1 2 1\n"
    }

    // Unlike the named form, this one has to account for every field — so a struct that grew one
    // reports the pattern that stopped covering it instead of binding the same names as before.
    "a sub-pattern per field, and not fewer" in {
      err(
        """struct Point
          |    x: int
          |    y: int
          |    z: int
          |
          |show() =
          |    val Point(x, y) = Point(1, 2, 3)
          |    print(x)
          |
          |show()""".stripMargin
      ) should include("has 3 fields")
    }

    "a struct name that is not the value's" in {
      err(
        """struct Point
          |    x: int
          |    y: int
          |
          |struct Size
          |    w: int
          |    h: int
          |
          |show() =
          |    val Size(w, h) = Point(3, 4)
          |    print(w)
          |
          |show()""".stripMargin
      ) should include("does not match")
    }
  }

  "what a struct binding will not accept" - {
    "a field the struct does not have" in {
      err(
        """struct Point
          |    x: int
          |    y: int
          |
          |show() =
          |    val Point{x, w} = Point(3, 4)
          |    print(x)
          |
          |show()""".stripMargin
      ) should include("has no field 'w'")
    }

    // `{x, x}` is a duplicate on both counts — one field twice, and one name bound twice — and the
    // name check is the one that fires, which is the more useful of the two to hear first.
    "one field named twice binds one name twice" in {
      err(
        """struct Point
          |    x: int
          |    y: int
          |
          |show() =
          |    val Point{x, x} = Point(3, 4)
          |    print(x)
          |
          |show()""".stripMargin
      ) should include("named twice in one binding")
    }

    // Renaming the second one leaves the names distinct, so only the field check can catch it. This
    // is the case that would pass silently if a struct binding did not check its fields at all.
    "the same field twice under two names is still the same field twice" in {
      err(
        """struct Point
          |    x: int
          |    y: int
          |
          |show() =
          |    val Point{x: a, x: b} = Point(3, 4)
          |    print(a, b)
          |
          |show()""".stripMargin
      ) should include("more than once")
    }

    "a struct name that is not the value's" in {
      err(
        """struct Point
          |    x: int
          |    y: int
          |
          |struct Size
          |    w: int
          |    h: int
          |
          |show() =
          |    val Size{w, h} = Point(3, 4)
          |    print(w)
          |
          |show()""".stripMargin
      ) should include("does not match")
    }

    "a value that is not a struct at all" in {
      err(
        """struct Point
          |    x: int
          |    y: int
          |
          |show() =
          |    val Point{x, y} = 5
          |    print(x)
          |
          |show()""".stripMargin
      ) should include("matches a struct, but the value is")
    }

    // The pattern that is still refused, and the one the rule is really about: an enum has several
    // shapes, so naming one of them is a test rather than a description.
    "a variant, which is still a choice among shapes" in {
      err(
        """enum Shape
          |    Circle(r: int)
          |    Square(s: int)
          |
          |show() =
          |    val Circle(r) = Circle(1)
          |    print(r)
          |
          |show()""".stripMargin
      ) should include("no other arm")
    }
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

    // The same rule the comma form meets: the parts have nowhere to carry a type, so this is a local
    // form. At the top of a program that needs no saying — a body's declarations are local — and the
    // refusal is what asking for the module's storage gets.
    "a module-level val, whose parts have nowhere to state a type" in {
      err("static val (a, b) = (1, 2)\n") should include("nowhere to write one")
    }

    "while the plain form at the top of a program is an ordinary local" in {
      run("val (a, b) = (1, 2)\nprint(a, b)\n") shouldBe "1 2\n"
    }
  }
}
