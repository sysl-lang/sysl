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

  /** `16 §1` — a subtype is laid out as the type it narrows, so a pattern matches its values as that
   * type's values. Both forms are here because both reach the same comparison and it had no answer
   * for a subtype: the compiler **crashed** on either, with a Scala stack trace rather than a
   * diagnostic, so a program that matched on a constrained value could not be compiled at all.
   * Found by probing `09 §12`'s claim that a condition takes every pattern an arm does.
   */
  "a pattern matches a constrained subtype as the base it narrows" - {
    val Small = "type Small = int within 1..10\n"

    "a literal pattern" in {
      run(Small + """var n: Small = 5
                    |n match
                    |    5 -> print("five")
                    |    else print("other")
                    |""".stripMargin) shouldBe "five\n"
    }

    "a range pattern" in {
      run(Small + """var n: Small = 5
                    |n match
                    |    1..6 -> print("low")
                    |    else print("high")
                    |""".stripMargin) shouldBe "low\n"
    }

    // An unsigned base picks a different comparison from a signed one, so the reduction has to reach
    // the *base* rather than stop at any integer — a subtype that dropped to `int` would order these
    // signed and get the high half of the range wrong.
    "and one over an unsigned base keeps the unsigned comparison" in {
      run("""type Big = u8 within 200..255
            |
            |var n: Big = 250
            |n match
            |    240..255 -> print("high")
            |    else print("low")
            |""".stripMargin) shouldBe "high\n"
    }

    "which a condition's pattern reaches by the same route (`09 §12`)" in {
      run(Small + """var n: Small = 5
                    |if n is 1..6 then print("low") else print("high")
                    |""".stripMargin) shouldBe "low\n"
    }
  }

  /** `16 §4` lists the places a constrained value is produced, and a struct field is one of them —
   * at construction *and* at every later write, since a field is not read-only.
   */
  "a constrained struct field is checked wherever it is written" - {
    val Person = "type Age = int within 0..150\nstruct Person\n    age: Age\n"

    "a construction in range proceeds" in {
      run(Person + "var p = Person(Age(40))\nprint(int(p.age))") shouldBe "40\n"
    }
    "a construction out of range traps" in {
      exits(Person + "var p = Person(Age(200))\nprint(int(p.age))")
    }
    "a later write in range proceeds" in {
      run(Person + "var p = Person(Age(40))\np.age = 41\nprint(int(p.age))") shouldBe "41\n"
    }
    "and one out of range traps" in {
      exits(Person + "var p = Person(Age(40))\np.age = 200\nprint(int(p.age))")
    }
  }

  // `16 §1`. A struct invariant may read one and is tested for it; a subtype predicate is the same
  // question one declaration over, and it is what lets a table's ceiling be written down once.
  "a where predicate may read a module constant" in {
    run("""const ceiling: int = 150
          |type Small = int where value < ceiling
          |print(Small(3))""".stripMargin) shouldBe "3\n"
  }

  /** A `within` bound may name a `const` too (`16 § Open b`), which is what makes a table's size and
    * the range of the type indexing it one fact instead of two. The bound folds through the same
    * `fold` an array bound and an enum discriminant use, so the three positions accept the same
    * expressions.
    *
    * The load-bearing assertions are the ones showing the bound really *is* the constant's value: a
    * range that merely compiles proves nothing, since a bound silently read as zero would make every
    * value out of range and a bound read as unbounded would check nothing at all.
    */
  "a within bound may name a const" - {
    val table = "const max_tasks: int = 8\ntype Slot = new int within 0..<max_tasks\n"

    "a value inside the constant's range is accepted" in {
      run(table + "print(int(Slot(7)))") shouldBe "7\n"
    }

    // The exclusive upper bound really is 8, so 8 itself is out — this is the assertion that fails if
    // the name folded to something else.
    "and the bound is the constant's value, tested at the edge" in {
      run(table + "print(Slot::Valid(7), Slot::Valid(max_tasks))") shouldBe "true false\n"
    }

    "a value the constant's range excludes still traps" in {
      panics(table + "print(int(Slot(8)))", "")
    }

    // An expression over constants, which is the case that shows the bound is folded rather than
    // pattern-matched against a bare name: `2..5` accepts 5 and rejects 6.
    "an expression over constants is a bound" in {
      run("""const lo: int = 2
            |const hi: int = 8
            |type Mid = new int within lo..hi - 3
            |print(Mid::Valid(2), Mid::Valid(5), Mid::Valid(6), Mid::Valid(1))""".stripMargin) shouldBe
        "true true false false\n"
    }

    // `::Valid` is for an integer subtype, so a `char` one is checked by constructing: one value the
    // constant's range accepts, and one it does not, which traps.
    "a char subtype takes a char constant" in {
      val lower = "const first: char = 'a'\ntype Lower = new char within first..'f'\n"

      run(lower + "print(char(Lower('c')))") shouldBe "c\n"
      panics(lower + "print(char(Lower('z')))", "")
    }

    "and an array of the size the same constant gives is indexed by it" in {
      run("""const max_tasks: int = 4
            |type Slot = new int within 0..<max_tasks
            |var table: [max_tasks]int = [0; 4]
            |table[int(Slot(3))] = 9
            |print(table.len, table[3])""".stripMargin) shouldBe "4 9\n"
    }
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

  "a new derived type is nominally distinct" - {
    val Meters = "type Meters = new f64\n"

    "arithmetic between two of the derived type yields the derived type" in {
      run(
        Meters + "add(a: Meters, b: Meters) -> Meters\n    a + b\n" +
          "print(f64(add(Meters(3.0), Meters(4.5))))"
      ) shouldBe "7.5\n"
    }

    "wrapping and unwrapping round-trips through the base" in {
      run(Meters + "var m: Meters = Meters(2.5)\nprint(f64(m) * 2.0)") shouldBe "5\n"
    }

    "two values of the derived type compare through the base ordering" in {
      run(Meters + "print(Meters(3.0) < Meters(4.0))") shouldBe "true\n"
    }

    "a derived type may carry a range, which still traps" - {
      val SafeAge = "type SafeAge = new int within 0..150\n"

      "an in-range wrap passes" in {
        run(SafeAge + "print(int(SafeAge(40)) + 1)") shouldBe "41\n"
      }
      "an out-of-range wrap traps" in {
        exits(SafeAge + "print(int(SafeAge(200)))")
      }
    }

    "a derived char type wraps and prints through its base" in {
      run("type Glyph = new char\nprint(Glyph('a'))") shouldBe "a\n"
    }

    /** The other side of what `SubtypeErrorTests` refuses. Everything the base can do the derived
     * type can do at itself, for free and with no way to stop it — which is the bargain: a
     * derivation buys the checking that two of them cannot be confused, and buys no say at all in
     * what one of them means on its own.
     */
    "the whole of the base's arithmetic arrives with it" - {
      val Stamp = "type Stamp = new i64\n"

      "including operations the derived meaning does not have" in {
        run(Stamp + "print(i64(Stamp(3i64) + Stamp(4i64)))") shouldBe "7\n"
      }

      "and the ones it does" in {
        run(Stamp + "print(i64(Stamp(7i64) - Stamp(4i64)))") shouldBe "3\n"
      }

      "equality and ordering come along too" in {
        run(Stamp + "print(Stamp(3i64) == Stamp(3i64), Stamp(3i64) < Stamp(4i64))") shouldBe "true true\n"
      }

      "and so does rendering, as the base renders" in {
        run(Stamp + "print(s\"${Stamp(3i64)}\")") shouldBe "3\n"
      }
    }
  }
}
