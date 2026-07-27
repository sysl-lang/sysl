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
}
