package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** Compile-time diagnostics for a malformed `type` declaration: a bound outside the base's range, an
 * inverted range, a non-scalar base, a bound of the wrong kind, and a cast of the wrong arity. Each
 * is caught at the declaration or the use, before anything runs.
 */
class SubtypeErrorTests extends AnyFreeSpec with CodegenSupport {

  "an out-of-range literal bound is rejected" in {
    err("type T = byte within 0..300\nvar x: T = 1\nprint(int(x))") should include("does not fit")
  }

  "an inverted range is rejected" in {
    err("type T = int within 10..5\nvar x: T = 7\nprint(int(x))") should include("above its upper bound")
  }

  "an empty exclusive range is rejected" in {
    err("type T = int within 5..<5\nvar x: T = 1\nprint(int(x))") should include("above its upper bound")
  }

  "a non-scalar base is rejected" in {
    err(
      """struct P
        |    x: int
        |end P
        |type T = P within 0..1
        |var t: T = 1
        |print(1)""".stripMargin
    ) should include("must be an integer, a float, or 'char'")
  }

  "bounds of the wrong kind" - {
    "character bounds on an integer base are rejected" in {
      err("type T = int within 'a'..'z'\nvar x: T = 1\nprint(int(x))") should include("integer-literal bounds")
    }
    "integer bounds on a character base are rejected" in {
      err("type L = char within 0..25\nvar x: L = 'a'\nprint(x)") should include("character-literal bounds")
    }
  }

  "a cast with the wrong number of arguments is rejected" in {
    err("type Age = int within 0..150\nprint(int(Age(1, 2)))") should include("takes exactly one value")
  }

  "a bare transparent alias with no constraint is rejected" in {
    err("type T = int\nvar x: T = 1\nprint(int(x))") should include("has no constraint")
  }

  "a non-bool where predicate is rejected without leaking the synthesised function's name" in {
    val e = err("type T = int where value + 1\nvar x: T = 1\nprint(int(x))")
    e should include("a 'where' predicate must be a 'bool'")
    e should not include "$pred"
  }

  "a derived type is nominally distinct" - {
    val Meters = "type Meters = new f64\n"

    "mixing it with its base in arithmetic is rejected" in {
      err(Meters + "print(f64(Meters(3.0) + 1.0))") should include("needs matching types")
    }

    "an implicit conversion from the base is rejected" in {
      err(Meters + "var m: Meters = 3.0\nprint(f64(m))") should include("declared Meters but the value is real")
    }

    "two derived types over one base do not mix" in {
      err(
        Meters + "type Feet = new f64\n" +
          "sum(a: Meters, b: Feet) -> f64\n    f64(a + b)\nprint(1)"
      ) should include("needs matching types")
    }
  }

  /** A derived scalar takes its base's whole catalog and may replace none of it.
   *
   * That is two decisions in one, and they pull opposite ways. Inheriting is what makes a `new u8`
   * cheap to declare — it compares, orders and adds without a line of support code, which is the
   * whole reason `guide/kernel`'s three bounded identities were worth having. Not being able to
   * *replace* any of it is what puts a ceiling on the technique: a derived type gets exactly the
   * behaviour its representation happens to have, including the operations that are meaningless for
   * what it now means, and it cannot be given one operation the representation does not have.
   */
  "a derived type inherits behaviour it cannot replace" - {
    val Stamp = "type Stamp = new i64\ntype Span = new i64\n"

    "an operator implementation collides with the one the compiler provides" in {
      err(Stamp + "impl Add[Span] for Stamp\n    add(self, s: Span) -> Stamp = self\nprint(1)") should
        include("'add' is how 'Add' is implemented for Stamp, and the compiler provides that")
    }

    "and so does any other row of the catalog" in {
      err(Stamp + "impl Display for Stamp\n    display(self, out: *Writer, fmt: FormatSpec)\n" +
        "        display_str(\"x\", out, fmt)\nprint(1)") should
        include("'Stamp' already implements 'Display' — the compiler provides it")
    }

    "even where the base's meaning does not survive the derivation" in {
      err(Stamp + "impl Eq for Stamp\n    eq(self, o: Stamp) -> bool = true\nprint(1)") should
        include("the compiler provides")
    }
  }
}
