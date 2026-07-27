package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** A transparent `within` subtype checks every value produced into it — a variable's initializer, an
 * assignment, an argument, a return, and an explicit cast — trapping when the value is out of range.
 * Each passing case is paired with the adjacent violation that must stop the program, so a check
 * that is too tight (fails the valid case) or too loose (lets the invalid case run) is caught.
 */
class SubtypeRunTests extends AnyFreeSpec with RunSupport {

  private val Age    = "type Age = int within 0..150\n"
  private val Prob   = "type Prob = f64 within 0.0..<1.0\n"
  private val Letter = "type Letter = char within 'a'..'z'\n"

  "a variable initializer" - {
    "an in-range integer passes and the value reads back through its base" in {
      run(Age + "var a: Age = 30\nprint(a, int(a) + 1)") shouldBe "30 31\n"
    }
    "an out-of-range integer traps" in {
      exits(Age + "var a: Age = 200\nprint(a)")
    }

    "the boundary values pass" in {
      run(Age + "var lo: Age = 0\nvar hi: Age = 150\nprint(lo, hi)") shouldBe "0 150\n"
    }
    "one past the top traps" in {
      exits(Age + "var a: Age = 151\nprint(a)")
    }

    "a float in range passes; the excluded upper endpoint traps" in {
      run(Prob + "var p: Prob = 0.0\nprint(p)") shouldBe "0\n"
    }
    "the exclusive upper bound itself traps" in {
      exits(Prob + "var p: Prob = 1.0\nprint(p)")
    }
    "below the float range traps" in {
      exits(Prob + "var p: Prob = -0.5\nprint(p)")
    }

    "a character in range passes" in {
      run(Letter + "var c: Letter = 'q'\nprint(c)") shouldBe "q\n"
    }
    "a character below the range traps" in {
      exits(Letter + "var c: Letter = 'A'\nprint(c)")
    }
    "a character above the range traps" in {
      exits(Letter + "var c: Letter = '{'\nprint(c)")
    }
  }

  "an assignment re-checks the new value" - {
    "an in-range assignment holds" in {
      run(Age + "var a: Age = 10\na = 140\nprint(a)") shouldBe "140\n"
    }
    "an out-of-range assignment traps" in {
      exits(Age + "var a: Age = 10\na = 200\nprint(a)")
    }
  }

  "an argument is checked at the call" - {
    "an in-range argument passes" in {
      run(Age + "f(a: Age) -> int\n    int(a)\nprint(f(42))") shouldBe "42\n"
    }
    "an out-of-range argument traps" in {
      exits(Age + "f(a: Age) -> int\n    int(a)\nprint(f(999))")
    }
  }

  "a return value is checked" - {
    "an in-range result passes" in {
      run(Age + "g(n: int) -> Age\n    n\nprint(g(120))") shouldBe "120\n"
    }
    "an out-of-range result traps" in {
      exits(Age + "g(n: int) -> Age\n    n\nprint(g(1000))")
    }
  }

  "an explicit cast checks its operand" - {
    "an in-range cast produces the value" in {
      run(Age + "print(Age(75))") shouldBe "75\n"
    }
    "an out-of-range cast traps" in {
      exits(Age + "print(Age(300))")
    }
  }

  // A value of one transparent subtype flows into another over the same base, re-checked against the
  // second subtype's range — the base compatibility that makes the subtype transparent. Narrowing a
  // wide value into a tighter subtype is what exposes the re-check.
  "another subtype over the same base is re-checked" - {
    val both = Age + "type Small = int within 0..10\n" +
      "narrow(a: Age) -> Small\n    a\n"

    "a value valid in both passes" in {
      run(both + "print(narrow(Age(5)))") shouldBe "5\n"
    }
    "a value valid in the wider one but not the tighter traps" in {
      exits(both + "print(narrow(Age(100)))")
    }
  }

  "a subtype value used as its base needs no cast" in {
    run(Age + "var a: Age = 12\nvar n: int = a\nprint(n + 1)") shouldBe "13\n"
  }

  "a where predicate is checked at each produce site" - {
    val Even = "type Even = int within 0..100 where value % 2 == 0\n"

    "a value satisfying both the range and the predicate passes" in {
      run(Even + "var e: Even = 8\nprint(e)") shouldBe "8\n"
    }
    "a value the predicate rejects traps" in {
      exits(Even + "print(Even(7))")
    }
    "a value the range rejects traps before the predicate would even matter" in {
      exits(Even + "print(Even(200))")
    }

    // A predicate with no range is a legal transparent subtype on its own.
    "a predicate-only subtype checks just the predicate" - {
      val Positive = "type Positive = int where value > 0\n"

      "an accepted value passes" in {
        run(Positive + "print(Positive(5))") shouldBe "5\n"
      }
      "a rejected value traps" in {
        exits(Positive + "print(Positive(0))")
      }
    }

    // `char` has no arithmetic, but its predicate may still test equality and ordering.
    "a char predicate uses comparison rather than arithmetic" - {
      val Hex = "type HexDigit = char where value >= '0' && value <= '9'\n"

      "an accepted character passes" in {
        run(Hex + "print(HexDigit('7'))") shouldBe "7\n"
      }
      "a rejected character traps" in {
        exits(Hex + "print(HexDigit('x'))")
      }
    }
  }
}
