package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** Which operands an operator accepts, and which conversions exist — `01`'s two tables read as
 * claims rather than as prose. Each says something is defined or is rejected, and a claim of the
 * second kind is the one worth pinning: a refusal that quietly stops being one is invisible, because
 * every program that was already written keeps compiling.
 */
class OperatorDomainTests extends AnyFreeSpec with CodegenSupport with RunSupport {

  "equality reaches further than ordering" - {
    "a bool compares equal and unequal" in {
      run("print(true == true, true != false)") shouldBe "true true\n"
    }

    "and has no ordering, which is the half of `01`'s sentence that is a refusal" in {
      err("print(true < false)") should include("'<' is not defined for bool")
    }
  }

  "unary operators take the types that have the operation" - {
    "complement is defined on every integer type, signed or not" in {
      run("""var a: u8 = 1
            |var b: i16 = 1
            |var c: u128 = 0
            |print(~a, ~b)
            |print(~c)""".stripMargin) shouldBe "254 -2\n340282366920938463463374607431768211455\n"
    }

    "and negation needs a type with a sign" in {
      err("var a: u8 = 1\nprint(-a)") should include("unsigned")
    }
  }

  /** `%` is integer-only in `01`'s table, and `reference/expressions.md § Operator dispatch` is
    * explicit about why that matters beyond the token: `Rem`'s membership is given to the integers
    * rather than to the numeric types, because a membership wider than the table would promise a
    * bounded generic an operation that fails at the instantiation the bound was supposed to have
    * proven. So there are two refusals, one at the operator and one at the bound, and the second is
    * the one the narrower membership buys.
    */
  "remainder is integer-only, at the operator and at the bound" - {
    "a float has no remainder to lower" in {
      err("print(5.0 % 2.0)") should include("'%' is not defined for real")
    }

    "and does not satisfy a 'Rem' bound either" in {
      err("m[T: Rem](a: T, b: T) -> T = a % b\nprint(m(5.0, 2.0))") should
        include(s"'T' to implement '${lib("Rem")}', but real does not")
    }

    "while an integer does both" in {
      run("m[T: Rem](a: T, b: T) -> T = a % b\nprint(m(20, 7), 20 % 7)") shouldBe "6 6\n"
    }
  }

  "both operands of a binary operator have the same type" - {
    // Both widths are named, and named by their *alias* — a width with a friendly name is shown by
    // it whichever spelling the program used, so `u8` is reported as `byte`.
    "two widths do not mix, and the message names both" in {
      err("""var a: u8 = 1
            |var b: u16 = 2
            |print(a + b)""".stripMargin) should
        include("'+' needs matching types, got byte and ushort")
    }

    "a width with no alias is shown by its systematic name" in {
      err("""var a: u12 = 1
            |var b: u20 = 2
            |print(a + b)""".stripMargin) should
        include("'+' needs matching types, got u12 and u20")
    }

    /** A shift is the exception, and the rest of the rule is untouched by it: `x << n` asks for `x`
      * shifted `n` places, so `n`'s width has nothing to do with `x`'s. C, Rust, Java, Go and Scala
      * all read it that way, and the cast this used to require was noise a reader had to check for
      * a subtlety that is not there.
      */
    "except a shift, whose right operand is a count and may be any integer width" in {
      run("""var x: u8 = 1
            |var k: u16 = 2
            |print(x << k)""".stripMargin) shouldBe "4\n"
    }

    "and the conversion still compiles, meaning the same thing" in {
      run("""var x: u8 = 1
            |var k: u16 = 2
            |print(x << u8(k))""".stripMargin) shouldBe "4\n"
    }

    "but the count must still be an integer, and the message names it as a count" in {
      err("""var x: u8 = 1
            |print(x << 1.5)""".stripMargin) should
        include("'<<' shifts by a count, and real is not an integer")
    }

    /** The one pair with a meeting point, and so the one place "the same type" is reached rather
      * than required: a `[]T` and a `[]const T` are one type with a bit, and the writable one is
      * accepted wherever the read-only one is wanted.
      *
      * Until they were made to meet, the **order the pair was written in** decided whether it
      * compiled — the first operand's type was taken and the second re-read at it, so the direction
      * that happened to be safe worked and its mirror was refused for the same two values.
      */
    "except the two views of one slice, which meet at the read-only one" - {
      val setup =
        """var a = [1, 2, 3]
          |var b = [1, 2, 3]
          |val c: []const int = b[..]
          |val w: []int = a[..]
          |""".stripMargin

      "with the read-only view on the left" in {
        run(setup + "print(c == w)") shouldBe "true\n"
      }

      "and on the right, which is the half that was refused" in {
        run(setup + "print(w == c)") shouldBe "true\n"
      }

      // Written as a slicing expression rather than as a name, since an expression can be re-read at
      // a type it was not first given and a name cannot — so the pair had to be settled rather than
      // one side adapted.
      "however the writable side is spelled" in {
        run(setup + "print(a[..] == c, c == a[..])") shouldBe "true true\n"
      }

      // Nothing about the meeting weakens what the bit is for: it is reached only when one operand
      // is already read-only, so two writable views settle where they always did.
      "while two writable views settle on the writable type as before" in {
        run(setup + "print(a[..] == b[..])") shouldBe "true\n"
      }
    }
  }

  "a literal takes its type from its position, and a suffixed one never adapts" - {
    "the other operand of a binary operator is one of those positions" in {
      run("""var n: u8 = 250
            |print(n + 5)""".stripMargin) shouldBe "255\n"
    }

    "two literals with nothing to go on both take the default, so `1 << 2` is an int" in {
      irMain("print(1 << 2)") should include("shl i32")
    }

    "a suffix fixes the type on the spot, and the other operand must already match it" in {
      err("""var n: u16 = 1
            |print(n + 5u8)""".stripMargin) should include("matching types")
    }

    "a suffixed literal in a position expecting another width is refused rather than adapted" in {
      err("var x: u8 = 42i32") should include("u8")
    }
  }

  "`usize` and `isize` are distinct types, not aliases" - {
    "so a fixed-width value of the same size still needs the cast written" in {
      err("""var n: u64 = 7
            |var k: usize = n""".stripMargin) should include("usize")
    }

    "and the cast is ordinary in both directions" in {
      run("""var n: u64 = 7
            |var k: usize = usize(n)
            |var back: u64 = u64(k)
            |print(k, back)""".stripMargin) shouldBe "7 7\n"
    }

    "the two of them do not mix with each other either" in {
      err("""var k: usize = 1
            |var j: isize = 1
            |print(k + j)""".stripMargin) should include("matching types")
    }
  }

  "the conversions that do not exist" - {
    "nothing converts to or from bool" in {
      err("print(int(true))") should include("cannot convert bool to int")
      err("print(bool(0))") should include("bool")
    }

    "a number is not a string, in either direction" in {
      err("""print(int("5"))""") should include("int")
      err("print(string(5))") should include("char")
    }

    "and a char is the one primitive a `string` conversion does take" in {
      run("print(string('q'))") shouldBe "q\n"
    }
  }

  "the two conversions `01` calls out as partial or total" - {
    "every char is an integer" in {
      run("print(u32('A'), u32('\\u{10FFFF}'))") shouldBe "65 1114111\n"
    }

    "and an integer is a char only when it names one" in {
      run("print(char(65u32))") shouldBe "A\n"
      exits("print(char(1114112u32))")
    }
  }
}
