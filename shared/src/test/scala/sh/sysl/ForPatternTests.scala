package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** `for (k, v) in pairs` — a loop that takes its element apart.
 *
 * The pattern is the one a `val` or a `var` binding takes, which is the whole rule: a `for` binds
 * what a binding binds. So what these assert is not a second pattern grammar but that the existing
 * one is reachable from a loop header, including the parts of it a list of names could never say —
 * a nested tuple, a part skipped with `_`, a struct by field name, and the value kept whole beside
 * its parts with `n @`.
 *
 * The second half is the refusals, and one of them is the reason the card existed. `for k, v in
 * pairs` used to be answered with `'>>=' expected`, naming a compound assignment nobody had
 * written, because the three-clause loop's init clause reads a multi-assignment and got furthest.
 * The comma spelling stays illegal — a `for` header already has three shapes competing for its
 * first token — so what it owes the reader is the form that works, spelled with their own names.
 */
class ForPatternTests extends AnyFreeSpec with RunSupport with CodegenSupport {

  "an element, taken apart" - {
    "a pair" in {
      run(
        """show() =
          |    for (a, b) in [(1, 2), (3, 4)]
          |        print(a, b)
          |
          |show()""".stripMargin
      ) shouldBe "1 2\n3 4\n"
    }

    "parts of different types" in {
      run(
        """show() =
          |    for (n, s) in [(1, "one"), (2, "two")]
          |        print(n, s)
          |
          |show()""".stripMargin
      ) shouldBe "1 one\n2 two\n"
    }

    // What a list of names could not reach, which is the argument for the pattern form here as much
    // as it is at a binding.
    "a nested tuple" in {
      run(
        """show() =
          |    for ((a, b), c) in [((1, 2), 3), ((4, 5), 6)]
          |        print(a, b, c)
          |
          |show()""".stripMargin
      ) shouldBe "1 2 3\n4 5 6\n"
    }

    "a part skipped with a wildcard" in {
      run(
        """show() =
          |    var total = 0
          |
          |    for (_, v) in [("a", 1), ("b", 2)]
          |        total += v
          |
          |    print(total)
          |
          |show()""".stripMargin
      ) shouldBe "3\n"
    }

    "a struct, by field name" in {
      run(
        """struct Point
          |    x: int
          |    y: int
          |
          |show() =
          |    for Point{x, y} in [Point(1, 2), Point(3, 4)]
          |        print(x * y)
          |
          |show()""".stripMargin
      ) shouldBe "2\n12\n"
    }

    "the whole element beside its parts, with 'n @'" in {
      run(
        """show() =
          |    for whole @ (a, _) in [(7, 8)]
          |        print(a, whole.1)
          |
          |show()""".stripMargin
      ) shouldBe "7 8\n"
    }

    // The parts are `var` because the loop variable itself is: taking the element apart should not
    // quietly take away what a plain `for` allows.
    "a part may be assigned to, exactly as a loop variable may" in {
      run(
        """show() =
          |    for (n, _) in [(1, 0), (2, 0)]
          |        n += 10
          |        print(n)
          |
          |show()""".stripMargin
      ) shouldBe "11\n12\n"
    }

    "one loop inside another, each with its own pattern" in {
      run(
        """show() =
          |    for (n, inner) in [(1, [(10, 20), (30, 40)])]
          |        for (a, b) in inner
          |            print(n + a + b)
          |
          |show()""".stripMargin
      ) shouldBe "31\n71\n"
    }
  }

  "the loop is still a loop" - {
    "a label, a 'continue', a 'break' and the value it carries" in {
      run(
        """show() =
          |    val pairs = [(1, 2), (3, 4), (5, 6)]
          |
          |    val found = 'walk for (a, b) in pairs
          |        if a == 1
          |            continue 'walk
          |
          |        if b == 6
          |            break 'walk a
          |    else
          |        0
          |
          |    print(found)
          |
          |show()""".stripMargin
      ) shouldBe "5\n"
    }

    "the 'else' runs when the walk finishes on its own" in {
      run(
        """show() =
          |    val n = for (a, _) in [(1, 2)]
          |        if a == 99
          |            break a
          |    else
          |        -1
          |
          |    print(n)
          |
          |show()""".stripMargin
      ) shouldBe "-1\n"
    }

    // What the feature was asked for. A map has no entry to hand back a reference to, so `walk`
    // yields a pair and every loop over one used to pay a line for it.
    "a map, walked by pattern" in {
      run(
        """import sysl.container.map.{map}
          |
          |show() =
          |    var m = map[string, int]()
          |
          |    m.put("a", 1)
          |    m.put("b", 2)
          |
          |    var total = 0
          |
          |    for (_, v) in m.walk()
          |        total += v
          |
          |    print(total)
          |
          |show()""".stripMargin
      ) shouldBe "3\n"
    }
  }

  // Every alternative of the pattern grammar needs a '(' or a '{' where a plain loop has its `in`,
  // so the two forms cannot be confused for one another. These are the guards on that claim.
  "what a pattern must not disturb" - {
    "a plain loop over a range" in {
      run(
        """show() =
          |    for i in 0..<3
          |        print(i)
          |
          |show()""".stripMargin
      ) shouldBe "0\n1\n2\n"
    }

    "a three-clause loop whose init declares several names" in {
      run(
        """show() =
          |    for var i, j = 0, 6; i < j; i += 2
          |        print(i)
          |
          |show()""".stripMargin
      ) shouldBe "0\n2\n4\n"
    }

    // The header the comma refusal below had to leave alone: `for a, b = …` is a legal init clause,
    // and it is the same first three tokens as the spelling being refused.
    "a three-clause loop whose init assigns to several names" in {
      run(
        """show() =
          |    var a = 0
          |    var b = 6
          |
          |    for a, b = 0, 6; a < b; a += 3
          |        print(a)
          |
          |show()""".stripMargin
      ) shouldBe "0\n3\n"
    }
  }

  "the refusals" - {
    // The card's second defect. The message named an operator the program did not contain, and sent
    // a reader who wrote a comma looking for a compound assignment.
    "the comma spelling is answered with the form that works" in {
      val message = err(
        """show() =
          |    for k, v in [(1, 2)]
          |        print(k, v)
          |
          |show()""".stripMargin
      )

      message should include("write 'for (k, v) in")
      message should not include ">>="
    }

    "and it names however many parts were written" in {
      err(
        """show() =
          |    for a, b, c in [(1, 2, 3)]
          |        print(a)
          |
          |show()""".stripMargin
      ) should include("write 'for (a, b, c) in")
    }

    // The pattern is checked against the element, so a range answers with what a range has.
    "a range has nothing to take apart" in {
      err(
        """show() =
          |    for (a, b) in 0..<3
          |        print(a, b)
          |
          |show()""".stripMargin
      ) should include("is not something to take apart")
    }

    "a pattern with the wrong number of parts" in {
      err(
        """show() =
          |    for (a, b) in [(1, 2, 3)]
          |        print(a, b)
          |
          |show()""".stripMargin
      ) should include("has 3 parts to give it")
    }

    // A loop is a binding, and a binding has no other arm to take when the value has another shape.
    "a variant, which is a choice among shapes" in {
      err(
        """enum Shape
          |    Circle(r: int)
          |    Square(s: int)
          |
          |show() =
          |    for Circle(r) in [Shape.Circle(1)]
          |        print(r)
          |
          |show()""".stripMargin
      ) should include("cannot choose among variants")
    }

    // The unrolled loop counts a range, so there is never anything there to take apart.
    "'for const' takes a name and nothing else" in {
      err(
        """show() =
          |    for const (a, b) in 0..<2
          |        print(a)
          |
          |show()""".stripMargin
      ) should include("identifier expected")
    }
  }
}
