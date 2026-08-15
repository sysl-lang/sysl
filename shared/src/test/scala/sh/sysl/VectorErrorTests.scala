package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** What a vector refuses, and what the reader is told instead.
 *
 * **Every refusal here is one somebody would reach for**, which is why each has a test: a vector
 * looks enough like an array that the differences — a fixed lane count, no `if` over lanes, a
 * constant index — read as omissions until the message says why they are not.
 */
class VectorErrorTests extends AnyFreeSpec with CodegenSupport {

  "the type as written" - {
    "a lane count is part of the type, so the empty spelling is refused" in {
      err("val v: <>f32 = [1.0]") should include("a vector's lane count is part of its type")
    }

    "no lanes at all" in {
      err("val v: <0>f32 = []") should include("a vector of no lanes holds nothing")
    }

    "a lane is a scalar" in {
      err("struct P\n    x: int\n\nf() -> unit\n    var v: <4>P") should include("a vector's lanes are scalars")
    }

    "a lane may not be a view" in {
      err("f() -> unit\n    var v: <4>[]int") should include("a vector's lanes are scalars")
    }

    // The qualifier goes on the outside, and saying so is the whole of this message's job: the
    // reader has written something with a meaning, and it is not the one they wanted.
    "a volatile lane is refused, and the spelling that works is named" in {
      err("f() -> unit\n    var v: <4>volatile u32") should include("a vector's lanes are scalars")
    }

    "a lane count must be constant" in {
      err("f(n: usize) -> unit\n    var v: <n>f32") should include("lane count must be a constant")
    }
  }

  "construction" - {
    "a literal that does not fill the lanes" in {
      err("val v: <4>f32 = [1.0, 2.0]") should include("has 4 lanes and this literal has 2")
    }

    "a literal with too many" in {
      err("val v: <2>f32 = [1.0, 2.0, 3.0]") should include("has 2 lanes and this literal has 3")
    }
  }

  "arithmetic" - {
    "two widths do not compute together" in {
      val src =
        """val a: <4>f32 = [1.0, 2.0, 3.0, 4.0]
          |val b: <8>f32 = [1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0]
          |f() -> unit
          |    val c = a + b
          |""".stripMargin

      err(src) should include("needs matching types")
    }

    "two lane types do not compute together" in {
      val src =
        """val a: <4>f32 = [1.0, 2.0, 3.0, 4.0]
          |val b: <4>i32 = [1, 2, 3, 4]
          |f() -> unit
          |    val c = a + b
          |""".stripMargin

      err(src) should include("needs matching types")
    }

    // The reason is in the message because it is not guessable: it is about what a trap can and
    // cannot be per lane, and a reader who does not know that reads the absence as an oversight.
    "integer division is refused, with the reason" in {
      val src =
        """val a: <4>i32 = [10, 20, 30, 40]
          |f() -> unit
          |    val b = a / a
          |""".stripMargin

      err(src) should include("traps per lane and a register traps as a whole")
    }

    "integer remainder likewise" in {
      val src =
        """val a: <4>i32 = [10, 20, 30, 40]
          |f() -> unit
          |    val b = a % a
          |""".stripMargin

      err(src) should include("traps per lane and a register traps as a whole")
    }

    "a mask has the bitwise operators and not the arithmetic ones" in {
      val src =
        """val a: <4>i32 = [1, 2, 3, 4]
          |f() -> unit
          |    val m = (a < 3) + (a < 2)
          |""".stripMargin

      err(src) should include("is not defined for")
    }
  }

  "comparison" - {
    // A chain reads as though it short-circuits, and nothing lane-wise does — so it is refused at
    // the spelling rather than lowered into an `&` that behaves differently from what it looks like.
    "a chain has no lane-wise form" in {
      val src =
        """val a: <4>i32 = [1, 2, 3, 4]
          |f() -> unit
          |    val m = 1 < a < 4
          |""".stripMargin

      err(src) should include("compare two vectors at a time and combine the masks with '&'")
    }

    "two widths do not compare" in {
      val src =
        """val a: <4>f32 = [1.0, 2.0, 3.0, 4.0]
          |val b: <2>f32 = [1.0, 2.0]
          |f() -> unit
          |    val m = a < b
          |""".stripMargin

      err(src) should include("cannot compare")
    }
  }

  "lanes" - {
    "a computed index is refused, and the array that answers is named" in {
      val src =
        """f(i: usize) -> f32
          |    val v: <4>f32 = [1.0, 2.0, 3.0, 4.0]
          |    return v[i]
          |""".stripMargin

      err(src) should include("a lane index is part of the instruction that reads it")
    }

    "an index past the last lane" in {
      err("val v: <4>f32 = [1.0, 2.0, 3.0, 4.0]\nprint(v[4])") should include("has lanes 0 to 3")
    }

    "an index that is not an integer at all" in {
      err("val v: <4>f32 = [1.0, 2.0, 3.0, 4.0]\nprint(v[\"x\"])") should include("must be an integer")
    }
  }

  // **The C boundary is refused rather than guessed at**, which is the same call `Exports` makes
  // about an aggregate and with more force: a vector's register and alignment differ by target and
  // by which extensions the other side was compiled for, and emitting the `declare` anyway makes a
  // corrupt call rather than a link error. This pair was *not* refused when the feature was first
  // written — the scope said it was and nothing implemented it, which a probe found and these pin.
  "the C boundary" - {
    "a vector parameter on an extern" in {
      val src =
        """extern "libc_thing" g(v: <4>f32) -> unit
          |""".stripMargin

      err(src) should include("how a vector reaches a C function differs by target")
    }

    "a vector result from an extern" in {
      val src =
        """extern "libc_thing" g() -> <4>f32
          |""".stripMargin

      err(src) should include("how a vector comes back from a C function differs by target")
    }

    "an exported function may not take one either" in {
      err("@export\nf(v: <4>f32) -> f32 = v[0]") should include("which C has no way to spell")
    }
  }

  "the methods" - {
    "select is a mask's, and a vector of numbers is told so" in {
      val src =
        """val a: <4>f32 = [1.0, 2.0, 3.0, 4.0]
          |f() -> unit
          |    val b = a.select(a, a)
          |""".stripMargin

      err(src) should include("it is read off a mask")
    }

    "select takes two vectors" in {
      val src =
        """val a: <4>f32 = [1.0, 2.0, 3.0, 4.0]
          |f() -> unit
          |    val b = (a < 2.0).select(a)
          |""".stripMargin

      err(src) should include("takes the two vectors to choose between")
    }

    "select's two sides are one type" in {
      val src =
        """val a: <4>f32 = [1.0, 2.0, 3.0, 4.0]
          |val b: <4>i32 = [1, 2, 3, 4]
          |f() -> unit
          |    val c = (a < 2.0).select(a, b)
          |""".stripMargin

      err(src) should include("two vectors of one type")
    }

    // A mask of four cannot choose between registers of eight — the widths are independent in the
    // type system and this is the one place they have to agree.
    "a mask chooses at its own width" in {
      val src =
        """val a: <4>f32 = [1.0, 2.0, 3.0, 4.0]
          |val b: <8>f32 = [1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0]
          |f() -> unit
          |    val c = (a < 2.0).select(b, b)
          |""".stripMargin

      err(src) should include("chooses between 4 lanes")
    }

    "any is a mask's, and a vector of numbers is told so" in {
      val src =
        """val a: <4>f32 = [1.0, 2.0, 3.0, 4.0]
          |print(a.any())
          |""".stripMargin

      err(src) should include("it is read off a mask")
    }

    "sum is not a mask's, and a mask is told what it reduces with" in {
      val src =
        """val a: <4>f32 = [1.0, 2.0, 3.0, 4.0]
          |print((a < 2.0).sum())
          |""".stripMargin

      err(src) should include("'any()' and 'all()' are what")
    }

    "a reduction takes no arguments" in {
      val src =
        """val a: <4>f32 = [1.0, 2.0, 3.0, 4.0]
          |print(a.sum(1.0))
          |""".stripMargin

      err(src) should include("takes no arguments")
    }

    // A name that is not one of the six falls through to the ordinary member complaint rather than
    // being caught by the vector path and answered with a list of what a vector does have.
    "an unknown method is the ordinary complaint" in {
      val src =
        """val a: <4>f32 = [1.0, 2.0, 3.0, 4.0]
          |print(a.reverse())
          |""".stripMargin

      err(src) should include("reverse")
    }
  }
}
