package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** `for all i in r do P(i)` and `for some i in r do P(i)` — the quantifiers of
 * `reference/verification.md § for all and for some`.
 *
 * The form is small and almost all of its design is in the edges, so the tests are weighted the same
 * way: the identities at an empty range, the short circuit, the boundary between the quantifier and
 * the counted loop it is spelled almost like, and the greedy body. A test that only shows a
 * quantifier over a range that has elements shows none of that.
 */
class QuantifierTests extends AnyFreeSpec with RunSupport with CodegenSupport {

  "what it computes" - {

    "a universal is true when every element satisfies the predicate" in {
      run("""var a = [2, 4, 6]
            |print(for all i in 0..<3 do a[i] % 2 == 0)
            |""".stripMargin) shouldBe "true\n"
    }

    // The discriminating half: one element that fails, and it is the LAST one, so a body that
    // stopped early for the wrong reason would still answer true.
    "and false when the last one does not" in {
      run("""var a = [2, 4, 7]
            |print(for all i in 0..<3 do a[i] % 2 == 0)
            |""".stripMargin) shouldBe "false\n"
    }

    "an existential is true when one element satisfies the predicate" in {
      run("""var a = [1, 3, 8]
            |print(for some i in 0..<3 do a[i] % 2 == 0)
            |""".stripMargin) shouldBe "true\n"
    }

    "and false when none does" in {
      run("""var a = [1, 3, 9]
            |print(for some i in 0..<3 do a[i] % 2 == 0)
            |""".stripMargin) shouldBe "false\n"
    }

    "the inclusive range reaches its upper bound" in {
      run("""var a = [2, 4, 7]
            |print(for all i in 0..2 do a[i] % 2 == 0)
            |""".stripMargin) shouldBe "false\n"
    }

    // Same data, same predicate, exclusive bound: the element that decides it is the one `..<`
    // leaves out, so this pair is what shows the bound is read rather than ignored.
    "where the exclusive one stops short of it" in {
      run("""var a = [2, 4, 7]
            |print(for all i in 0..<2 do a[i] % 2 == 0)
            |""".stripMargin) shouldBe "true\n"
    }
  }

  "an empty range takes each quantifier's identity" - {

    "a conjunction over nothing is true" in {
      run("print(for all i in 0..<0 do false)\n") shouldBe "true\n"
    }

    "a disjunction over nothing is false" in {
      run("print(for some i in 0..<0 do true)\n") shouldBe "false\n"
    }

    // A range whose bounds are the wrong way round is empty rather than an error, which is what a
    // clause written against a length that turned out to be zero needs: `for all i in 0..<n - 1`
    // with `n` at 0 asks about `0..<-1` and must be true, not a trap.
    "a range whose lower bound is past its upper is empty too" in {
      run("""var n = 0
            |print(for all i in 0..<n - 1 do false)
            |""".stripMargin) shouldBe "true\n"
    }
  }

  "both forms short-circuit" - {

    // Counting the predicate's evaluations is the only way to see this, and the count is what the
    // design specifies rather than an accident of the emitter: a `for all` over five elements whose
    // second fails runs the predicate twice. The counter is a local captured by a nested function,
    // since the language has no module-level `var` for a function to reach (`13 § Why there is no
    // module-level var`).
    "a universal stops at the first counterexample" in {
      run("""probe() -> int
            |    var seen = 0
            |
            |    check(i: int) -> bool
            |        seen += 1
            |        i != 1
            |
            |    print(for all i in 0..<5 do check(i))
            |    seen
            |
            |print(probe())
            |""".stripMargin) shouldBe "false\n2\n"
    }

    "an existential stops at the first witness" in {
      run("""probe() -> int
            |    var seen = 0
            |
            |    check(i: int) -> bool
            |        seen += 1
            |        i == 1
            |
            |    print(for some i in 0..<5 do check(i))
            |    seen
            |
            |print(probe())
            |""".stripMargin) shouldBe "true\n2\n"
    }

    "a universal that never fails runs the predicate on every element" in {
      run("""probe() -> int
            |    var seen = 0
            |
            |    check(i: int) -> bool
            |        seen += 1
            |        true
            |
            |    print(for all i in 0..<5 do check(i))
            |    seen
            |
            |print(probe())
            |""".stripMargin) shouldBe "true\n5\n"
    }
  }

  "the bound name" - {

    "is visible inside the predicate" in {
      run("print(for all i in 3..5 do i >= 3)\n") shouldBe "true\n"
    }

    // The shadow is the point: an outer `i` of a different value is what a wrong implementation
    // would read, and it would answer differently.
    "shadows an outer name of the same spelling" in {
      run("""var i = 100
            |print(for all i in 0..<3 do i < 3)
            |""".stripMargin) shouldBe "true\n"
    }

    "and the outer one is back afterwards" in {
      run("""var i = 100
            |var q = for all i in 0..<3 do i < 3
            |print(i)
            |""".stripMargin) shouldBe "100\n"
    }
  }

  "the body extends as far to the right as an expression does" - {

    // `for all i in r do P(i) && Q(i)` quantifies over the conjunction. Read the other way — the
    // quantifier over `P` alone, conjoined with `Q` — this program would not even compile, since
    // `i` has no meaning outside the predicate. That is what makes it a test rather than a
    // preference.
    "so a conjunction after it is inside it" in {
      run("""var a = [2, 4, 6]
            |print(for all i in 0..<3 do a[i] % 2 == 0 && a[i] > 0)
            |""".stripMargin) shouldBe "true\n"
    }

    "and a quantifier as a later arm of a chain is parenthesized" in {
      run("""var a = [2, 4, 6]
            |print(a[0] == 2 && (for all i in 0..<3 do a[i] > 0))
            |""".stripMargin) shouldBe "true\n"
    }
  }

  "where it may be written" - {

    "in a require" in {
      run("""first_even(a: []int, n: int) -> int
            |    require for all i in 0..<n do a[i] % 2 == 0
            |    a[0]
            |
            |var a = [2, 4, 6]
            |print(first_even(a[..], 3))
            |""".stripMargin) shouldBe "2\n"
    }

    "and a require it fails stops the program" in {
      exits("""first_even(a: []int, n: int) -> int
              |    require for all i in 0..<n do a[i] % 2 == 0
              |    a[0]
              |
              |var a = [2, 5, 6]
              |print(first_even(a[..], 3))
              |""".stripMargin)
    }

    "in an ensure, where it may read result" in {
      run("""zeros() -> [3]int
            |    ensure for all i in 0..<3 do result[i] == 0
            |    [0; 3]
            |
            |print(zeros()[1])
            |""".stripMargin) shouldBe "0\n"
    }

    "in an if condition, which is not a contract position at all" in {
      run("""var a = [2, 4, 6]
            |if for all i in 0..<3 do a[i] % 2 == 0 then print("even") else print("mixed")
            |""".stripMargin) shouldBe "even\n"
    }

    "and on the right of a binding" in {
      run("""var a = [1, 2]
            |var sorted = for all i in 0..<1 do a[i] <= a[i + 1]
            |print(sorted)
            |""".stripMargin) shouldBe "true\n"
    }
  }

  "it is told from a counted loop by one token" - {

    // `all` and `some` stay ordinary identifiers, so a loop whose variable is called `all` is still
    // a loop. This is the whole cost of the spelling and it is what the test is for.
    "a loop over a variable named 'all' is still a loop" in {
      run("""for all in 0..<3 do print(all)
            |""".stripMargin) shouldBe "0\n1\n2\n"
    }

    "and one named 'some' is too" in {
      run("""for some in 0..<2 do print(some)
            |""".stripMargin) shouldBe "0\n1\n"
    }

    "a value named 'all' is an ordinary name" in {
      run("""var all = 7
            |print(all)
            |""".stripMargin) shouldBe "7\n"
    }
  }

  "the widths the range may take" - {

    "a quantifier over a sized integer range works at that width" in {
      run("""print(for all i in 0u8..<3u8 do i < 3u8)
            |""".stripMargin) shouldBe "true\n"
    }

    // Two *declared* bounds rather than two literals, because a literal takes the type it is used
    // at and so can never disagree with its neighbour.
    "bounds of different types are refused" in {
      err("""var lo = 0u8
            |var hi = 3i32
            |print(for all i in lo..<hi do true)
            |""".stripMargin) should include("matching bounds")
    }

    "a non-integer range is refused" in {
      err("print(for all i in 0.0..<3.0 do true)") should include("integer bounds")
    }

    "something that is not a range at all is refused" in {
      err("""var a = [1, 2]
            |print(for all i in a do true)
            |""".stripMargin) should include("quantifies over an integer range")
    }

    "and the message names the form that was written" in {
      err("""var a = [1, 2]
            |print(for some i in a do true)
            |""".stripMargin) should include("'for some'")
    }
  }

  "the predicate must be a bool" in {
    err("print(for all i in 0..<3 do i)") should include("bool")
  }

  "a quantifier nests" in {
    run("""var a = [1, 2, 3]
          |print(for all i in 0..<3 do (for all j in 0..<3 do a[i] + a[j] > 1))
          |""".stripMargin) shouldBe "true\n"
  }

  "and a nested one that fails somewhere in the middle answers false" in {
    run("""var a = [1, 2, 3]
          |print(for all i in 0..<3 do (for all j in 0..<3 do a[i] + a[j] > 2))
          |""".stripMargin) shouldBe "false\n"
  }
}
