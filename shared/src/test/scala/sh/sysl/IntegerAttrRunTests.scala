package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** `T::Min` and `T::Max` on a built-in integer type — the bounds a `within` subtype has answered
 * since `16 §5` while the integer it is declared over answered nothing.
 *
 * The wide cases carry the point of the feature. A program can write `4294967295` for a `u32` and
 * cannot write the largest `u10000` at all, so for a wide member of the open family the attribute is
 * the only way to name a value the type obviously has.
 */
class IntegerAttrRunTests extends AnyFreeSpec with RunSupport with CodegenSupport {

  "the common widths report their bounds" - {
    "unsigned starts at zero" in {
      run("print(u8::Min, u8::Max)") shouldBe "0 255\n"
    }
    "signed is asymmetric, one further down than up" in {
      run("print(i8::Min, i8::Max)") shouldBe "-128 127\n"
    }
    "u32 and i32" in {
      run("print(u32::Max)") shouldBe "4294967295\n"
      run("print(i32::Min, i32::Max)") shouldBe "-2147483648 2147483647\n"
    }
    "u64 reaches the width a literal still can" in {
      run("print(u64::Max)") shouldBe "18446744073709551615\n"
    }
  }

  "the friendly aliases answer as the widths they stand for" - {
    "int is i32" in {
      run("print(int::Min, int::Max)") shouldBe "-2147483648 2147483647\n"
    }
    "byte is u8" in {
      run("print(byte::Min, byte::Max)") shouldBe "0 255\n"
    }
    "usize is pointer-width, so only its floor is fixed across targets" in {
      run("print(usize::Min)") shouldBe "0\n"
    }
  }

  "an odd width is not a special case" - {
    "u3 holds seven" in {
      run("print(u3::Min, u3::Max)") shouldBe "0 7\n"
    }
    "i5 is asymmetric at its own width" in {
      run("print(i5::Min, i5::Max)") shouldBe "-16 15\n"
    }
    "u1 is the narrowest the family admits" in {
      run("print(u1::Min, u1::Max)") shouldBe "0 1\n"
    }
  }

  "a width no literal could spell" - {
    // 1146! is 10000 bits to the bit, which is what `test/factorial.sysl` is about. The largest
    // `u10000` is 3,011 digits, so this attribute is the only access to it.
    "u10000's maximum is 2^10000 - 1, to the digit" in {
      run("print(u10000::Max)") shouldBe ((BigInt(1) << 10000) - 1).toString + "\n"
    }
    "and its minimum is still zero" in {
      run("print(u10000::Min)") shouldBe "0\n"
    }
    "a signed one of the same width is one bit narrower in magnitude" in {
      // Not written as `i10000::Max < u10000::Max`: comparing across signedness is refused, which
      // is the language's rule and not this feature's business.
      run("print(i10000::Max)") shouldBe ((BigInt(1) << 9999) - 1).toString + "\n"
      run("print(i10000::Min)") shouldBe (-(BigInt(1) << 9999)).toString + "\n"
    }
  }

  "the bounds are constants, not calls" - {
    // `13 §5` admits no call in a `const` initializer, so an attribute that resolved as one would be
    // unusable in the two places bounds are most wanted. This is the test that pins that.
    "a const initializer folds one" in {
      run("const LIMIT: u16 = u16::Max\nprint(LIMIT)") shouldBe "65535\n"
    }
    "arithmetic over them folds too" in {
      run("const HALF: u32 = u32::Max / 2\nprint(HALF)") shouldBe "2147483647\n"
    }
    "an @assert reads one at compile time" in {
      run("@assert(u8::Max == 255)\nprint(1)") shouldBe "1\n"
    }
    "one sizes an array" in {
      run("val a: [u3::Max]u8 = [0u8; 7]\nprint(a.len)") shouldBe "7\n"
    }
  }

  /** Through a **type parameter**, which is what makes the bounds usable by the library rather than
   * only by a program: bounded narrowing, integer parsing, saturating arithmetic and a min/max
   * reduction's identity all want `T`'s extreme.
   *
   * The substitution these read is `tsubst`, the same one `sizeof(T)` resolves its operand through
   * and `T(x)` finds its target in. That is worth saying because the case sat `ignore` for several
   * releases under a comment claiming there was no ambient substitution at an expression — there
   * is, and three of the four ways a parameter reaches expression position already used it.
   */
  "through a type parameter" - {
    "a generic body reads the argument's maximum" in {
      run("widest[T]() -> T = T::Max\nval x: u8 = widest()\nprint(x)") shouldBe "255\n"
    }
    "and its minimum, which is where the signed case shows" in {
      run("least[T]() -> T = T::Min\nval x: i16 = least()\nprint(x)") shouldBe "-32768\n"
    }
    // The parameter is solved from a written type argument as readily as from the expected type,
    // which is the case with nowhere else for the width to come from.
    "the argument may be written at the call rather than inferred" in {
      run("widest[T]() -> T = T::Max\nprint(widest[u8]())") shouldBe "255\n"
    }
    "one body serves every width it is instantiated at" in {
      run("widest[T]() -> T = T::Max\nval a: u8 = widest()\nval b: u16 = widest()\nprint(a, b)") shouldBe
        "255 65535\n"
    }

    /** A **derived** subtype answers its own bounds through a parameter, exactly as it does under
      * its own name — a bound of `T` is a `T`, so a generic over one hands back the subtype rather
      * than the integer it is stored as.
      */
    "a derived subtype answers in itself" in {
      run("type Age = new int within 0..150\nwidest[T]() -> T = T::Max\nval a: Age = widest()\nprint(a)") shouldBe
        "150\n"
    }

    /** A **transparent** subtype answers differently depending on how the parameter was *solved*,
      * and the attribute is not what differs — it reports whatever `T` was bound to. The three cases
      * are pinned rather than reconciled here, because which of them is right is a question about
      * inference and not about attributes.
      *
      * `T::Max` is simply the first thing that makes the split visible. Everything else a body can
      * do with a `T` behaves the same whether it is bound to `Age` or to `int` — that is what
      * transparent *means* — so nothing had a reason to ask before.
      */
    "a transparent one answers its own bound when the argument is written" in {
      run("type Age = int within 0..150\nwidest[T]() -> T = T::Max\nprint(int(widest[Age]()))") shouldBe
        "150\n"
    }
    "and its own again when the parameter is solved from a value" in {
      run("type Age = int within 0..150\nwidest[T](x: T) -> T = T::Max\n" +
            "val a = Age(3)\nprint(int(widest(a)))") shouldBe "150\n"
    }

    /** **And its base's when solved from the expected type**, which is the one route out of step.
      * `Type.repr` strips a transparent subtype for agreement, so `val a: Age = widest()` solves
      * `T = int` — and `int::Min` is then outside `Age`, which the produce site checks and traps on.
      * The trap is the only way to observe it: every value both readings agree on is a value that
      * cannot tell them apart.
      */
    "and its base's when solved from the expected type, which the produce site then refuses" in {
      exits("""type NonNeg = int within 0..2147483647
              |least[T]() -> T = T::Min
              |val a: NonNeg = least()
              |print(int(a))""".stripMargin)
    }

    /** Only the two bounds. Every other attribute answers in a type of its own — `Valid` a `bool`,
      * `Pos` a `usize`, `Image` a `string` — and the walk that checks a generic body once, with `T`
      * standing at an abstract, would have to restate each of those a second time. `Min` and `Max`
      * both answer in `T`, so one placeholder is right for both and cannot drift.
      */
    "the rest are asked on a written type name, and say so" in {
      val e = err("first[T]() -> T = T::First\nval a: u8 = first()\nprint(a)")

      e should include("'T' is a type parameter")
      e should include("'T::Min' and 'T::Max'")
    }

    /** No bound is required of `T`, following `sizeof(T)` — so a parameter given something with no
      * maximum is reported at the instantiation that gave it one, and the message names both.
      */
    "an argument with no bounds is reported where it was supplied" in {
      val e = err("struct P\n    x: int\nwidest[T]() -> T = T::Max\nval a: P = widest()\nprint(1)")

      e should include("'T::Max' needs an integer type")
      e should include("'T' is P here")
    }
    "and a bool is the same answer, since a bound is a range of numbers" in {
      err("widest[T]() -> T = T::Max\nval a: bool = widest()\nprint(a)") should
        include("'T' is bool here")
    }

    /** The constant folder has resolved `T::Max` through the substitution all along, so an array
      * bound and an `@assert` inside a generic were the half of this that always worked — which is
      * what made the expression case look like a decision rather than a missing arm.
      */
    "a bound folds in an array length, where it always did" in {
      val src =
        """cap[T]() -> usize
          |    var buf: [T::Max]u8
          |    buf.len
          |print(cap[u8]())""".stripMargin

      run(src) shouldBe "255\n"
    }
    "and in an '@assert', which is checked per instantiation" in {
      val src =
        """ok[T]() -> int
          |    @assert(T::Max > 0)
          |    1
          |print(ok[u8]())""".stripMargin

      run(src) shouldBe "1\n"
    }
    // The fill was the one bound that did *not* reach it, which this branch fixed alongside.
    "and in the fill that initializes such an array" in {
      val src =
        """cap[T]() -> usize
          |    val buf: [T::Max]u8 = [0; T::Max]
          |    buf.len
          |print(cap[u8]())""".stripMargin

      run(src) shouldBe "255\n"
    }
  }

  "a ranged subtype answers both pairs" - {
    // They are different questions that agree on a range written in order. Refusing `Min` on the one
    // kind of type whose whole purpose is bounds would be the odd outcome.
    "Min and Max read the same numbers First and Last do" in {
      run("type Age = int within 0..150\nprint(Age::Min, Age::Max, Age::First, Age::Last)") shouldBe
        "0 150 0 150\n"
    }
    "and an exclusive upper is one below, for both spellings" in {
      run("type Prob = int within 0..<10\nprint(Prob::Max, Prob::Last)") shouldBe "9 9\n"
    }
  }

  "they answer in the type they are the bounds of" - {
    "a maximum assigns to its own width without a cast" in {
      run("val x: u8 = u8::Max\nprint(x)") shouldBe "255\n"
    }
    "and wraps like any other value of it" in {
      run("var x: u8 = u8::Max\nx += 1u8\nprint(x)") shouldBe "0\n"
    }
  }
}
