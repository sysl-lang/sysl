package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** `sysl.math`'s bit surface, whose membership is the compiler's over the open integer family the
 * way `Signed`'s is (`reference/expressions.md § Operator dispatch`), and whose members lower to
 * LLVM's bit intrinsics rather than to the shift-and-mask loops a program would otherwise write.
 */
class BitsTests extends AnyFreeSpec with RunSupport with CodegenSupport {

  private val importing = "import sysl.math.Bits\n\n"

  "counting" - {

    "reports how many bits are set and how many are clear" in {
      run(importing +
        """main()
          |    var x: u8 = 0b10110000
          |    print(x.count_ones(), x.count_zeros(), (0u8).count_ones(), (0u8).count_zeros())
          |""".stripMargin) shouldBe "3 5 0 8\n"
    }

    "the two always sum to the width, at whatever width the receiver is" in {
      run(importing +
        """main()
          |    var a: u8 = 0b10110000
          |    var b: u32 = 0xF0F0F0F0
          |    print(a.count_ones() + a.count_zeros(), b.count_ones() + b.count_zeros())
          |""".stripMargin) shouldBe "8 32\n"
    }

    "measures the run of zeroes at each end" in {
      run(importing +
        """main()
          |    var x: u8 = 0b10110000
          |    print(x.leading_zeros(), x.trailing_zeros())
          |""".stripMargin) shouldBe "0 4\n"
    }

    "measures the run of ones at each end" in {
      run(importing +
        """main()
          |    var x: u8 = 0b11100011
          |    print(x.leading_ones(), x.trailing_ones())
          |""".stripMargin) shouldBe "3 2\n"
    }

    // The `i1` on `llvm.ctlz` and `llvm.cttz` is what this pins. Left at `true` the answer would be
    // poison — which is to say whatever the target's instruction happens to leave in the register —
    // and the same program would print different numbers on different machines.
    "answers the width where there is nothing to count, at both ends" in {
      run(importing +
        """main()
          |    print((0u8).leading_zeros(), (0u8).trailing_zeros(), (0u32).leading_zeros())
          |""".stripMargin) shouldBe "8 8 32\n"
    }

    "and answers zero where there is a run of the other bit instead" in {
      run(importing +
        """main()
          |    var all: u8 = 255
          |    print(all.leading_zeros(), all.trailing_zeros(), (0u8).leading_ones(), all.leading_ones())
          |""".stripMargin) shouldBe "0 0 0 8\n"
    }
  }

  "reversing" - {

    "puts the lowest bit highest, at the receiver's own width" in {
      run(importing +
        """main()
          |    var x: u8 = 0b10110000
          |    print(x.reverse_bits(), (1u8).reverse_bits(), (1u32).reverse_bits())
          |""".stripMargin) shouldBe "13 128 2147483648\n"
    }

    "is its own inverse" in {
      run(importing +
        """main()
          |    var x: u32 = 0xDEADBEEF
          |    print(x.reverse_bits().reverse_bits() == x)
          |""".stripMargin) shouldBe "true\n"
    }
  }

  "rotating" - {

    "moves the bits leaving one end to the other" in {
      run(importing +
        """main()
          |    var x: u8 = 0b10110000
          |    print(x.rotate_left(1), x.rotate_right(1))
          |""".stripMargin) shouldBe "97 88\n"
    }

    // The reason the member exists rather than the expression it looks equivalent to: written
    // `(x << n) | (x >> (w - n))`, a rotation by zero shifts by the whole width, which is undefined.
    "by zero is the value again, which is what the expression it replaces cannot manage" in {
      run(importing +
        """main()
          |    var x: u8 = 0b10110000
          |    print(x.rotate_left(0), x.rotate_right(0))
          |""".stripMargin) shouldBe "176 176\n"
    }

    "takes its amount modulo the width, so no amount is out of range" in {
      run(importing +
        """main()
          |    var x: u8 = 0b10110000
          |    print(x.rotate_left(8), x.rotate_left(9), x.rotate_right(201))
          |""".stripMargin) shouldBe "176 97 88\n"
    }

    "the two are inverses of each other" in {
      run(importing +
        """main()
          |    var x: u32 = 0xDEADBEEF
          |    print(x.rotate_left(7).rotate_right(7) == x)
          |""".stripMargin) shouldBe "true\n"
    }
  }

  "the open family" - {

    // The whole reason the membership is the compiler's: no `impl` could have been written for a
    // width nobody has named yet, and these are widths a library author would never have listed.
    "reaches a width no impl could have been written for" in {
      run(importing +
        """main()
          |    var x: u12 = 0b100000000001
          |    print(x.count_ones(), x.leading_zeros(), x.trailing_zeros(), x.reverse_bits())
          |""".stripMargin) shouldBe "2 0 0 2049\n"
    }

    // A width that is not a power of two is where the two reductions stop composing: narrowing the
    // amount to `u5` first would take it modulo 32, and 5 does not divide 32 — so 33 would be read
    // as 1 rather than as 3. The amount is reduced at its own width before it is narrowed.
    "rotates by an amount larger than a width that is not a power of two" in {
      run(importing +
        """main()
          |    var x: u5 = 0b00001
          |    print(x.rotate_left(33), x.rotate_left(3), x.rotate_left(5))
          |""".stripMargin) shouldBe "8 8 1\n"
    }

    "covers a width wider than the count it answers in" in {
      run(importing +
        """main()
          |    var x: u128 = 1
          |    print(x.leading_zeros(), x.count_zeros())
          |""".stripMargin) shouldBe "127 127\n"
    }

    // `01` gives the bitwise operators to every integer at either signedness, and these are the same
    // questions asked of the same bit patterns.
    "asks the same questions of a signed value" in {
      run(importing +
        """main()
          |    var x: i8 = -1
          |    var y: i8 = 1
          |    print(x.count_ones(), x.leading_ones(), y.rotate_right(1))
          |""".stripMargin) shouldBe "8 8 -128\n"
    }
  }

  "what is evaluated" - {

    // The node exists so that the receiver is read into a register once. A tree of the shifts and
    // masks each member means would mention it several times over.
    "the receiver is read once, however many times the lowering mentions it" in {
      run(importing +
        """bump() -> u8
          |    print("called")
          |    0b10110000
          |end bump
          |
          |main()
          |    print(bump().count_zeros())
          |    print(bump().rotate_left(1))
          |""".stripMargin) shouldBe "called\n5\ncalled\n97\n"
    }

    "and so is the rotation amount" in {
      run(importing +
        """by() -> u32
          |    print("asked")
          |    1
          |end by
          |
          |main()
          |    var x: u8 = 0b10110000
          |    print(x.rotate_left(by()))
          |""".stripMargin) shouldBe "asked\n97\n"
    }
  }

  // What the trait is *for*. A membership over an open family exists so that a body can be written
  // once against the bound and instantiated at a width nobody listed — the counts and the rotations
  // both have to survive the trip through `[T: Bits]`, not only a call on a concrete type.
  "as a bound" - {

    "a generic body may count the bits of whatever it was instantiated at" in {
      run(importing +
        """popcount[T: Bits](x: T) -> u32 = x.count_ones()
          |
          |main()
          |    var a: u8 = 7
          |    var b: u32 = 255
          |    var c: u12 = 0b100000000001
          |    print(popcount(a), popcount(b), popcount(c))
          |""".stripMargin) shouldBe "3 8 2\n"
    }

    // The rotation is the harder half of the same question: its amount is a `u32` whatever `T` is,
    // so the widening happens per instantiation and the literal `1` is not `T`'s.
    "and may rotate one, at each instantiation's own width" in {
      run(importing +
        """spin[T: Bits](x: T, n: u32) -> T = x.rotate_left(n)
          |
          |main()
          |    var a: u8 = 1
          |    print(spin(a, 1), spin(0x12345678u32, 4))
          |""".stripMargin) shouldBe "2 591751041\n"
    }

    // Every member answers something that mentions `Self` or is a count, so an erased value has
    // forgotten what it would have to answer — which is object safety's ordinary rule reaching this
    // trait rather than anything new about it.
    "but there is no trait object over it, because Self is in the answers" in {
      err(importing + "main()\n    var x: &Bits = null\n    print(1)") should include("Self")
    }
  }

  "what is refused" - {

    // Same rule as `Signed`: a compiler-provided membership says which types have the member, never
    // which files may write it (`reference/modules.md § Visibility`).
    "the trait has to be in scope, membership or not" in {
      err("main()\n    print((1u8).count_ones())") should include("count_ones")
    }

    "a float has no bits to count" in {
      err(importing + "main()\n    var x: real = 1.0\n    print(x.count_ones())") should include("count_ones")
    }

    "a count takes no arguments" in {
      err(importing + "main()\n    print((1u8).count_ones(3))") should include("count_ones")
    }

    "a rotation takes exactly one" in {
      err(importing + "main()\n    print((1u8).rotate_left())") should include("rotate_left")
      err(importing + "main()\n    print((1u8).rotate_left(1, 2))") should include("rotate_left")
    }

    // The signature is the trait's own, so the amount is held to being a count: there is no
    // rotation by a negative number, and the complaint is the ordinary one about a literal.
    "and an amount that is not a count" in {
      err(importing + "main()\n    print((1u8).rotate_left(-1))") should include("does not fit")
    }

    // Deliberately absent, and the absence is a design decision rather than an oversight: reversing
    // the byte order needs a whole number of bytes and at least two, so it is not total over the
    // family the way every member that is here is (`CoreTraits.builtin`).
    "reordering bytes is not one of the members" in {
      err(importing + "main()\n    print((1u32).swap_bytes())") should include("swap_bytes")
    }
  }

  "the amount arrives at the width being rotated" - {

    // A literal amount reaches the intrinsic as an **immediate**, with no widening instruction in
    // front of it, which is what makes a constant rotation one machine instruction rather than a
    // funnel shift through a register. Written as a `zext` of a constant it said the same thing and
    // compiled to more; `sysl.crypto`'s mixing functions rotate by a constant twenty-four times.
    "a literal is the constant itself, not a widening of one" in {
      val out = ir(importing + "main()\n    var x: u64 = 255\n    print(x.rotate_right(8))")

      out should include("call i64 @llvm.fshr.i64(")
      out should not include "zext i32 8 to i64"
    }

    "and a computed amount is widened to the receiver's width" in {
      val out = ir(importing +
        """main()
          |    var x: u64 = 255
          |    var n: u32 = 8
          |    print(x.rotate_right(n))
          |""".stripMargin)

      out should include("zext i32")
      out should include("call i64 @llvm.fshr.i64(")
    }

    "either way the answer is the same" in {
      run(importing +
        """main()
          |    var x: u64 = 0x0123456789abcdef
          |    var n: u32 = 8
          |    print(x.rotate_right(8) == x.rotate_right(n), x.rotate_right(8))
          |""".stripMargin) shouldBe "true 17222085231038278605\n"
    }
  }

  // The signature is read off the trait's declaration rather than restated in the compiler, so
  // everything an ordinary call gets from a declaration works here: the parameter has the name the
  // library gave it, and naming it reaches it.
  "the amount may be passed by the name the trait gave it" in {
    run(importing + "main()\n    print((1u8).rotate_left(n = 1))") shouldBe "2\n"
  }

  // A subtype narrows which values a type has and never which operations it has
  // (`reference/errors.md § A derivation inherits its base's behaviour and may replace none of
  // it`), so the members reach one — at the base's bit pattern, which is the only thing a count
  // could mean.
  "a constrained subtype has them, at its base's bits" in {
    run(
      """import sysl.math.{Bits, Signed}
        |
        |type Temp = int within -50..50
        |
        |main()
        |    var a: Temp = -7
        |    print(a.abs(), a.count_ones())
        |""".stripMargin) shouldBe "7 30\n"
  }

  // Every `llvm.` name this trait reaches, with the signature it is declared under.
  //
  // The compiler builds these signatures rather than reading them off anything a program wrote, so
  // `Intrinsics` — which exists to hold an `extern`'s declaration to LLVM's — has no say over them.
  // What catches a wrong one is the verifier, on every test above that links and runs. This pins the
  // spelling as well, so that the six names sysl emits for `Bits` are written down in one place.
  "the six intrinsics, each under the signature it is called with" in {
    val decls = ir(importing +
      """main()
        |    var x: u32 = 0b10110000
        |    print(x.count_ones(), x.leading_zeros(), x.trailing_zeros())
        |    print(x.reverse_bits(), x.rotate_left(1), x.rotate_right(1))
        |""".stripMargin).linesIterator.filter(_.startsWith("declare")).toSet

    decls should contain allOf (
      "declare i32 @llvm.ctpop.i32(i32)",
      "declare i32 @llvm.ctlz.i32(i32, i1)",
      "declare i32 @llvm.cttz.i32(i32, i1)",
      "declare i32 @llvm.bitreverse.i32(i32)",
      "declare i32 @llvm.fshl.i32(i32, i32, i32)",
      "declare i32 @llvm.fshr.i32(i32, i32, i32)",
    )
  }

  // The declaration carries the width, so a program using one member at three widths gets three
  // `declare` lines and no more — which is what makes `satDecls` a set.
  "each intrinsic is declared once, under the width it was reached at" in {
    val out = ir(importing +
      """main()
        |    var a: u8 = 1
        |    var b: u32 = 1
        |    print(a.count_ones(), b.count_ones(), b.count_ones())
        |""".stripMargin)

    out should include("declare i8 @llvm.ctpop.i8(i8)")
    out should include("declare i32 @llvm.ctpop.i32(i32)")
    out.linesIterator.count(_.startsWith("declare i32 @llvm.ctpop.i32")) shouldBe 1
  }
}
