package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** `sysl.math.complex` — a `Complex[F: Float]` and its arithmetic.
 *
 * Three groups of assertion here are not about whether the formulas are right.
 *
 * The first is **the width**. The type is generic over `Float` rather than fixed at `real`, so every
 * identity worth having is run at `f32` as well — a body that quietly widened to binary64 would pass
 * every value test and be wrong.
 *
 * The second is **the operands a textbook formula does not survive**. `abs` through `hypot` and a
 * division that scales before it divides are the two places this module differs from the obvious
 * expression, and the tests that pin them use inputs near the top and the bottom of the range, where
 * the obvious one silently answers an infinity or a zero.
 *
 * The third is **the branch cuts**, which is where complex libraries differ from each other and the
 * one thing a caller cannot work out from a signature. Each is asserted at the boundary rather than
 * near it, because a cut is a statement about exactly the points on it.
 */
class ComplexTests extends AnyFreeSpec with RunSupport with CodegenSupport {

  private val importing = "import sysl.math.complex.Complex\nimport sysl.math.Float\n\n"

  override protected def run(src: String, optimize: String = Toolchain.defaultOptimization): String =
    super.run(importing + src, optimize)

  "construction and what a value says about itself" - {
    "the parts, the conjugate and the negation" in {
      run(
        """var a = Complex(3.0, 4.0)
          |print(a.re, a.im, a.conj(), -a)""".stripMargin
      ) shouldBe "3 4 3-4i -3-4i\n"
    }

    // The rendering writes its four pieces straight through and pads once between them, so the field
    // belongs to the whole value without a string being built to measure it. The pieces, the width
    // and the precision are pinned in `library/sysl/math/complex/tests.sysl`, where a claim about a
    // library function belongs; this case stays because it is the one expectation written in another
    // language, and a rendering the library agreed with itself about would still be worth checking.
    "it renders as a+bi, with the sign written out and never a '+-'" in {
      run(
        """print(Complex(3.0, 4.0), Complex(3.0, -4.0), Complex(-3.0, 0.0))
          |print(f"[${Complex(1.0, -2.0)}%12s]")""".stripMargin
      ) shouldBe "3+4i 3-4i -3+0i\n[        1-2i]\n"
    }

    "the values that need no receiver, at the width the annotation asks for" in {
      run(
        """var z: Complex[real] = Complex.zero()
          |var o: Complex[real] = Complex.one()
          |var i: Complex[real] = Complex.i()
          |print(z, o, i, i * i)""".stripMargin
      ) shouldBe "0+0i 1+0i 0+1i -1+0i\n"
    }

    "polar construction, and the pair it takes back" in {
      run(
        """var a = Complex(3.0, 4.0)
          |var p = a.to_polar()
          |var back = Complex.from_polar(p.0, p.1)
          |print(p.0, p.1, back.near(a, 1.0e-12), Complex.expi(0.0))""".stripMargin
      ) shouldBe "5 0.927295 true 1+0i\n"
    }

    "the predicates, including a zero that is both real and imaginary" in {
      run(
        """var z: Complex[real] = Complex.zero()
          |var i: Complex[real] = Complex.i()
          |var a = Complex(3.0, 4.0)
          |print(z.is_zero(), z.is_real(), z.is_imaginary())
          |print(a.is_real(), i.is_imaginary(), a.is_finite())""".stripMargin
      ) shouldBe "true true true\nfalse true true\n"
    }

    // A NaN in either part is a NaN value, and such a value is neither finite nor infinite — so the
    // two are not each other's negation, which is the case a `!is_finite()` would get wrong.
    "a NaN is neither finite nor infinite" in {
      run(
        """var n = Complex(real.nan(), 0.0)
          |var i = Complex(real.infinity(), 0.0)
          |print(n.is_nan(), n.is_finite(), n.is_infinite())
          |print(i.is_nan(), i.is_finite(), i.is_infinite())""".stripMargin
      ) shouldBe "true false false\nfalse false true\n"
    }
  }

  "the operators" - {
    "the four, over another complex number" in {
      run(
        """var a = Complex(3.0, 4.0)
          |var b = Complex(1.0, -2.0)
          |print(a + b, a - b, a * b, a / b)""".stripMargin
      ) shouldBe "4+2i 2+6i 11-2i -1+2i\n"
    }

    // The second argument list, which is the operation a transform performs most often.
    "and the same four over a real on the right" in {
      run(
        """var a = Complex(3.0, 4.0)
          |print(a + 1.0, a - 1.0, a * 2.0, a / 2.0)""".stripMargin
      ) shouldBe "4+4i 2+4i 6+8i 1.5+2i\n"
    }

    /** The scalar on the **left**, which card `0385` is what makes writable: the coherence rule
      * looked only at an `impl`'s subject, so `real` being the library's settled it and the
      * `Complex` in the trait's argument list was never consulted. Until then `z * 2.0` was legal
      * and `2.0 * z` was a block nobody could write in any module, this one included.
      *
      * Multiplication and addition commute, so those two are the mirror of the blocks above and are
      * asserted equal to them. Subtraction and division do not, and are the cases worth reading:
      * `1.0 - z` is not `z - 1.0`, and `2.0 / z` is a whole complex quotient rather than a scaling.
      */
    "and the same four over a real on the left" in {
      run(
        """var a = Complex(3.0, 4.0)
          |print(1.0 + a, 1.0 - a, 2.0 * a, 2.0 / a)""".stripMargin
      ) shouldBe "4+4i -2-4i 6+8i 0.24-0.32i\n"
    }

    "the two that commute agree with the right-hand form, and the two that do not disagree" in {
      run(
        """var a = Complex(3.0, 4.0)
          |print(2.0 * a == a * 2.0, 1.0 + a == a + 1.0, 1.0 - a == a - 1.0, 2.0 / a == a / 2.0)""".stripMargin
      ) shouldBe "true true false false\n"
    }

    // At `f32` as well, for the reason every identity in this suite is run at both widths — and here
    // it is also what says the two blocks per operator are two rather than one that happens to fit.
    "and at binary32, which is the other block" in {
      run(
        """var a = Complex(3.0f32, 4.0f32)
          |print(2.0f32 * a, 1.0f32 - a)""".stripMargin
      ) shouldBe "6+8i -2-4i\n"
    }

    // The compiler's own multiplication is unmoved by the block beside it: `real` is a member of
    // `Mul` whatever anybody writes, and only the argument list tells the two apart.
    "and the machine's own arithmetic is untouched by either block" in {
      run("print(2.0 * 3.0, 2.0.mul(3.0), 7 << 2)") shouldBe "6 6 28\n"
    }

    "equality compares both parts" in {
      run(
        """var a = Complex(3.0, 4.0)
          |print(a == Complex(3.0, 4.0), a == Complex(3.0, 4.1), a != Complex(4.0, 4.0))""".stripMargin
      ) shouldBe "true false true\n"
    }

    // The complex numbers are not ordered, and `library/core.md § What is in it` keeps `Eq` and `Ord` independent so a type
    // can say so. This is that refusal, and it is the point of not deriving one from the other.
    "there is no ordering, which is the mathematics and not an omission" in {
      err(importing + "print(Complex(1.0, 0.0) < Complex(2.0, 0.0))") should include(
        "'<' is not defined for sysl.math.complex.Complex[real]")
    }

    "a reciprocal is the division, and undoes the multiplication" in {
      run(
        """var a = Complex(3.0, 4.0)
          |print(a.recip() * a, (a * a.recip()).near(Complex(1.0, 0.0), 1.0e-15))""".stripMargin
      ) shouldBe "1+0i true\n"
    }
  }

  // Both of these are the reason the module exists rather than the four-line struct a program would
  // write for itself: each is an ordinary operand whose obvious formula answers an infinity.
  "the operands the obvious formula does not survive" - {
    // `norm_sqr` overflowing is not a defect — it is the squared magnitude, and that number really
    // is out of range. It is asserted here because it is exactly what `abs` would have inherited had
    // `abs` been written as `norm_sqr().sqrt()`.
    "abs is finite where the squared magnitude is not" in {
      run(
        """var huge = Complex(1.0e200, 1.0e200)
          |print(huge.abs().is_finite(), huge.norm_sqr().is_infinite())
          |print((huge.abs() / 1.0e200 - 2.0.sqrt()).abs() < 1.0e-12)""".stripMargin
      ) shouldBe "true true\ntrue\n"
    }

    "and it is finite at the bottom of the range too, where a square underflows to zero" in {
      run(
        """var tiny = Complex(1.0e-200, 1.0e-200)
          |print(tiny.abs() > 0.0, tiny.norm_sqr() == 0.0)""".stripMargin
      ) shouldBe "true true\n"
    }

    // Smith's algorithm. The textbook quotient forms the divisor's squared magnitude, so the first
    // two of these would be a NaN: an infinity over an infinity at the top, and a zero over a zero
    // at the bottom. Both are exactly one. The third really is out of range, and the infinity is the
    // arithmetic saying so rather than the algorithm giving up.
    "division answers at both ends of the range, where the squared magnitude does not exist" in {
      run(
        """var huge = Complex(1.0e200, 1.0e200)
          |var tiny = Complex(1.0e-200, 1.0e-200)
          |print(huge / huge, tiny / tiny, huge / tiny == Complex(real.infinity(), 0.0))""".stripMargin
      ) shouldBe "1+0i 1+0i true\n"
    }

    // A zero divisor is the one case with no answer at all. Among the reals `1/0` is an infinity
    // because there is only one direction to run off in; a complex quotient has an argument as well
    // as a magnitude, and a zero divisor fixes neither — so it is a NaN, which is the value that
    // means exactly that.
    "a zero divisor has no answer, and says so rather than picking a direction" in {
      run(
        """var z = Complex(0.0, 0.0)
          |print((Complex(2.0, 3.0) / z).is_nan(), z.recip().is_nan(), z.is_zero())""".stripMargin
      ) shouldBe "true true true\n"
    }

    // The other half of the same claim: the scaling has not cost accuracy on ordinary operands.
    "and it is still exact on operands that need no scaling" in {
      run(
        """print(Complex(11.0, -2.0) / Complex(1.0, -2.0), Complex(-5.0, 10.0) / Complex(5.0, 0.0))"""
      ) shouldBe "3+4i -1+2i\n"
    }
  }

  "exponentials, logarithms and roots" - {
    "the logarithm and the exponential undo each other" in {
      run(
        """var a = Complex(3.0, 4.0)
          |print(a.ln().exp().near(a, 1.0e-12))""".stripMargin
      ) shouldBe "true\n"
    }

    // The other way round is *not* the identity, and that is the branch cut rather than an error:
    // `exp` is periodic, so the logarithm brings the angle back into `(-pi, pi]`.
    "the other way round wraps the angle, which is what a principal logarithm means" in {
      run(
        """var a = Complex(3.0, 4.0)
          |var back = a.exp().ln()
          |print(back.re, (back.im - (4.0 - 2.0 * real.pi())).abs() < 1.0e-12)""".stripMargin
      ) shouldBe "3 true\n"
    }

    "the square root squares back, and is the principal one" in {
      run(
        """var a = Complex(3.0, 4.0)
          |var minus = Complex(-1.0, 0.0)
          |print(a.sqrt(), a.sqrt().re >= 0.0, minus.sqrt())""".stripMargin
      ) shouldBe "2+1i true 0+1i\n"
    }

    "the root of zero is zero rather than a NaN" in {
      run("print(Complex(0.0, 0.0).sqrt())") shouldBe "0+0i\n"
    }

    "the three logarithm bases agree with each other" in {
      run(
        """var a = Complex(3.0, 4.0)
          |print(a.log2().near(a.log(2.0), 1.0e-12),
          |      a.log10().near(a.log(10.0), 1.0e-12),
          |      a.log(real.one().exp()).near(a.ln(), 1.0e-12))""".stripMargin
      ) shouldBe "true true true\n"
    }

    // The integer power is the one that is exact, which is why it exists beside the other two.
    "the three powers agree, and the integer one is exact" in {
      run(
        """var a = Complex(3.0, 4.0)
          |print(a.powi(2), a.powi(2) == a * a)
          |print(a.powf(2.0).near(a * a, 1.0e-12), a.powc(Complex(2.0, 0.0)).near(a * a, 1.0e-12))
          |print(a.powi(-1).near(a.recip(), 1.0e-15), a.powi(0))""".stripMargin
      ) shouldBe "-7+24i true\ntrue true\ntrue 1+0i\n"
    }

    // A zero base has no logarithm, so both powers answer it without taking one.
    "a zero base is answered without a logarithm" in {
      run(
        """var z = Complex(0.0, 0.0)
          |print(z.powf(0.0), z.powf(2.0), z.powc(z), z.powc(Complex(2.0, 0.0)))""".stripMargin
      ) shouldBe "1+0i 0+0i 1+0i 0+0i\n"
    }
  }

  "trigonometry, circular and hyperbolic" - {
    "sine, cosine and tangent agree with the identity over them" in {
      run(
        """var a = Complex(3.0, 4.0)
          |print((a.sin() * a.sin() + a.cos() * a.cos()).near(Complex(1.0, 0.0), 1.0e-12),
          |      a.tan().near(a.sin() / a.cos(), 1.0e-12))""".stripMargin
      ) shouldBe "true true\n"
    }

    "and the hyperbolic three agree with theirs" in {
      run(
        """var a = Complex(3.0, 4.0)
          |print((a.cosh() * a.cosh() - a.sinh() * a.sinh()).near(Complex(1.0, 0.0), 1.0e-12),
          |      a.tanh().near(a.sinh() / a.cosh(), 1.0e-12))""".stripMargin
      ) shouldBe "true true\n"
    }

    "each inverse undoes its own function" in {
      run(
        """var a = Complex(3.0, 4.0)
          |print(a.asin().sin().near(a, 1.0e-12),
          |      a.acos().cos().near(a, 1.0e-12),
          |      a.atan().tan().near(a, 1.0e-12))
          |print(a.asinh().sinh().near(a, 1.0e-12),
          |      a.acosh().cosh().near(a, 1.0e-12),
          |      a.atanh().tanh().near(a, 1.0e-12))""".stripMargin
      ) shouldBe "true true true\ntrue true true\n"
    }

    // On the real interval where the real functions are defined, the complex ones have to be them.
    "on the real interval they answer what the real functions answer" in {
      run(
        """var h = Complex(0.5, 0.0)
          |print((h.asin().re - 0.5.asin()).abs() < 1.0e-12, h.asin().im.abs() < 1.0e-12,
          |      (h.atanh().re - 0.5.atanh()).abs() < 1.0e-12)""".stripMargin
      ) shouldBe "true true true\n"
    }
  }

  // Where a function is discontinuous, the value *on* the cut is a decision rather than a
  // consequence — and the one thing a caller cannot work out from the signature. Each of these is
  // asserted at the boundary itself.
  "the branch cuts, which are the contract" - {
    // `arg` puts pi on the cut and -pi just below it, so a negative zero in the imaginary part is
    // the difference between the two sides. That is not a curiosity: it is how a value that arrived
    // from below the axis keeps its side of the cut.
    "the logarithm is cut along the negative real axis, and a negative zero says which side" in {
      run(
        """var above = Complex(-1.0, 0.0)
          |var below = Complex(-1.0, -0.0)
          |print((above.ln().im - real.pi()).abs() < 1.0e-15,
          |      (below.ln().im + real.pi()).abs() < 1.0e-15)""".stripMargin
      ) shouldBe "true true\n"
    }

    "the square root keeps a non-negative real part, so it is cut along the same axis" in {
      run(
        """var above = Complex(-4.0, 0.0)
          |var below = Complex(-4.0, -0.0)
          |print(above.sqrt(), below.sqrt())""".stripMargin
      ) shouldBe "0+2i 0-2i\n"
    }

    // Outside [-1, 1] on the real axis `asin` and `acos` leave the reals, which is the cut. The
    // boundary itself is still real, and that is the assertion.
    "asin and acos are real exactly on [-1, 1] and leave it outside" in {
      run(
        """var one = Complex(1.0, 0.0)
          |var past = Complex(2.0, 0.0)
          |print((one.asin().re - real.pi() / 2.0).abs() < 1.0e-12, one.asin().im.abs() < 1.0e-12)
          |print(past.asin().im.abs() > 1.0, past.acos().im.abs() > 1.0)""".stripMargin
      ) shouldBe "true true\ntrue true\n"
    }

    // `atanh` has a pole at each end of its cut, and the two are asserted together because that is
    // what the two-logarithm form buys: written as one logarithm of a quotient, `+1` would divide by
    // zero and answer a NaN while `-1` reached `ln(0)` and answered an infinity — the same pole,
    // reported two ways, for no reason a caller could see.
    "atanh runs off to infinity at both poles, and to the same one on each side" in {
      run(
        """var up = Complex(1.0, 0.0).atanh()
          |var down = Complex(-1.0, 0.0).atanh()
          |print(up.re.is_infinite(), up.re > 0.0, down.re.is_infinite(), down.re < 0.0)
          |print(Complex(0.0, 0.0).atanh())""".stripMargin
      ) shouldBe "true true true true\n0+0i\n"
    }

    // `acosh`'s cut is everything to the left of one, which is the case the two-root form exists
    // for: a single `sqrt(z*z - 1)` picks the other branch here and answers with the wrong sign.
    "acosh is cut to the left of one, and the answer there has a positive real part" in {
      run(
        """var left = Complex(-2.0, 0.0)
          |print(left.acosh().re > 0.0, (left.acosh().im - real.pi()).abs() < 1.0e-12)""".stripMargin
      ) shouldBe "true true\n"
    }

    // `atan` and `asinh` are cut on the *imaginary* axis outside `[-i, i]`, which is `asin`'s cut
    // turned a quarter turn — so inside the segment each stays on its own axis, and outside it the
    // other component appears. That appearance is the cut, and it is what the table on the library
    // page is asserting.
    "atan and asinh are cut on the imaginary axis outside [-i, i]" in {
      run(
        """var inside = Complex(0.0, 0.5)
          |var outside = Complex(0.0, 2.0)
          |print(inside.atan().re.abs() < 1.0e-15, outside.atan().re.abs() > 1.0)
          |print(inside.asinh().re.abs() < 1.0e-15, outside.asinh().re.abs() > 1.0)""".stripMargin
      ) shouldBe "true true\ntrue true\n"
    }
  }

  // Every identity above is at `real`. These are the same claims at the other width, which is what
  // the type being generic is for — and a tolerance of its own, since `f32` has seven digits.
  "the same type at binary32" - {
    "arithmetic, magnitude and the operators" in {
      run(
        """var a = Complex(3.0f32, 4.0f32)
          |var b = Complex(1.0f32, -2.0f32)
          |print(a, a.abs(), a.norm_sqr())
          |print(a + b, a * b, a / b, a * 2.0f32)""".stripMargin
      ) shouldBe "3+4i 5 25\n4+2i 11-2i -1+2i 6+8i\n"
    }

    "the roots, logarithms and both trigonometries round-trip" in {
      run(
        """var a = Complex(3.0f32, 4.0f32)
          |var eps: f32 = 1.0e-4f32
          |print(a.sqrt() * a.sqrt(), a.ln().exp().near(a, eps))
          |print(a.asin().sin().near(a, eps), a.atanh().tanh().near(a, eps))
          |print(a.powi(2), a.powf(2.0f32).near(a * a, 1.0e-2f32))""".stripMargin
      ) shouldBe "3+4i true\ntrue true\n-7+24i true\n"
    }

    // The scaling in the division matters more at this width, not less: binary32 runs out of range
    // three hundred orders of magnitude sooner.
    "and the division still answers where the squared magnitude would overflow" in {
      run(
        """var huge = Complex(1.0e20f32, 1.0e20f32)
          |print(huge.abs().is_finite(), huge.norm_sqr().is_infinite(), huge / huge)""".stripMargin
      ) shouldBe "true true 1+0i\n"
    }
  }

  // The cases a formula written for the middle of its domain does not have in mind.
  "the edges" - {
    // Smith's algorithm branches on which part of the divisor is the larger, so a divisor with a
    // zero part exercises the arm the ordinary tests do not — `1 / i` is `-i` and reaches it.
    "a divisor with one zero part takes the other arm and is still exact" in {
      run(
        """print(Complex(1.0, 0.0) / Complex(0.0, 1.0), Complex(1.0, 0.0) / Complex(2.0, 0.0))
          |print(Complex(3.0, 4.0) / Complex(0.0, 2.0))""".stripMargin
      ) shouldBe "0-1i 0.5+0i\n2-1.5i\n"
    }

    // A divisor whose parts are three hundred orders of magnitude apart, which is the case the
    // scaling exists for at the other end from the overflow: the ratio underflows to zero and the
    // answer is still the one the larger part alone gives.
    "a divisor whose two parts are nothing like each other" in {
      run(
        """var q = Complex(2.0, 0.0) / Complex(1.0e300, 1.0)
          |print(q.re.is_finite(), (q.re - 2.0e-300).abs() < 1.0e-315)""".stripMargin
      ) shouldBe "true true\n"
    }

    // At zero there is no direction to point in, and both parts say so rather than one of them
    // answering a number.
    "the unit vector of zero is not a number" in {
      run(
        """var u = Complex(0.0, 0.0).unit()
          |print(u.is_nan(), Complex(0.0, 0.0).arg())""".stripMargin
      ) shouldBe "true 0\n"
    }

    // A base of one has no logarithm to divide by, so the general `log` runs off the way the real
    // one does rather than answering something.
    "a logarithm in base one is infinite, as it is among the reals" in {
      run("print(Complex(3.0, 4.0).log(1.0).re.is_infinite())") shouldBe "true\n"
    }

    // `near` is a comparison, and a NaN fails every comparison — so a value that is not a number is
    // near nothing, including itself. That is the arithmetic and it is worth pinning, because the
    // opposite convention would make a poisoned value quietly pass a tolerance check.
    "a NaN is near nothing, not even itself" in {
      run(
        """var n = Complex(real.nan(), 0.0)
          |print(n.near(n, 1.0), n.near(Complex(0.0, 0.0), 1.0e300))""".stripMargin
      ) shouldBe "false false\n"
    }

    "a large integer power stays exact where the polar form would not" in {
      run(
        """var i: Complex[real] = Complex.i()
          |print(i.powi(100), i.powi(-100), Complex(2.0, 0.0).powi(30))""".stripMargin
      ) shouldBe "1+0i 1+0i 1.07374e+09+0i\n"
    }

    // `powc` is written over `ln`, so its cut is `ln`'s — and the principal square root of `-1` is
    // `i` rather than `-i` for exactly that reason.
    "powc inherits the logarithm's cut, so a half power is the principal root" in {
      run(
        """var half = Complex(0.5, 0.0)
          |print(Complex(-1.0, 0.0).powc(half).near(Complex(0.0, 1.0), 1.0e-15),
          |      Complex(-4.0, 0.0).powc(half).near(Complex(-4.0, 0.0).sqrt(), 1.0e-14))""".stripMargin
      ) shouldBe "true true\n"
    }
  }

  /** `Magnitude`, which is the one trait a complex number implements that an ordering would have
   * been. There is no `Ord` here on purpose, and yet `|z|` orders these by size perfectly well — so
   * the trait carries an associated type saying what a size comes out at, and for a `Complex[F]`
   * that is `F` rather than the type itself.
   */
  "the size, which is an ordering the values do not have" - {

    "the modulus reached through the trait is the type's own abs" in {
      run("""import sysl.math.Magnitude
            |
            |print(Complex(3.0, 4.0).magnitude(), Complex(0.0 - 5.0, 12.0).magnitude())""".stripMargin) shouldBe
        "5 13\n"
    }

    /** The width is the claim: a size that had been fixed to `real` would answer here too, and would
     * answer at binary64 — so the assertion is that the projection is `F`, made by a body that can
     * only hold what the trait promises.
     */
    "and it comes out at the width the value is held in" in {
      run("""import sysl.math.Magnitude
            |
            |size[T: Magnitude](x: T) -> T::Size = x.magnitude()
            |
            |var narrow: f32 = size(Complex(3.0f32, 4.0f32))
            |var wide: real = size(Complex(3.0, 4.0))
            |
            |print(narrow, wide)""".stripMargin) shouldBe "5 5\n"
    }

    /** The point of the trait rather than of the method: one body over element types whose sizes are
     * not the same type as each other, comparing them without naming either.
     */
    "so one routine picks the largest by size over the plane and over the line" in {
      run("""import sysl.math.Magnitude
            |
            |largest[T: Magnitude](xs: []const T) -> T::Size
            |    var best = xs[0].magnitude()
            |
            |    for x in xs do if best < x.magnitude() then best = x.magnitude()
            |
            |    best
            |
            |print(largest([Complex(3.0, 4.0), Complex(1.0, 1.0)]), largest([3.0, 0.0 - 40.0]))""".stripMargin) shouldBe
        "5 40\n"
    }
  }

  "the module is asked for by name" - {
    // The arithmetic is reachable from a module that has given up both the allocator and the
    // operating system, which is what makes the module usable on a freestanding target.
    "and the arithmetic needs no capability, which is what a freestanding target has to have" in {
      super.run(
        """@no_alloc
          |@no_os
          |
          |import sysl.math.complex.Complex
          |
          |var a = Complex(3.0, 4.0)
          |var q = a.sqrt()
          |
          |print(a.abs(), (a * a).re, q.re, q.im, a.exp().ln().re)""".stripMargin
      ) shouldBe "5 -7 2 1 3\n"
    }

    // **Rendering one used to be refused here, and the rule it was justified by is still true.**
    // `library/core.md § A specifier is the whole value's field` does say a specifier describes the
    // field the *whole* value lands in — but "so the pieces have to be gathered before the padding
    // is applied, and gathering means a string" never followed from it. The width is had by running
    // the same writes through a `Counting` sink, which keeps the length and drops the bytes, so an
    // allocator-free program prints a `Complex` instead of printing its parts one at a time.
    //
    // **What this does not prove is that the module could carry the clause itself.** This program
    // links no allocating `Writer`; one that imports `sysl.buf` refuses the very same code, because
    // a call through `*Writer` is judged against every implementation in the compilation. That is
    // card `0282`, and `CapabilityClauseTests` pins both halves of it.
    "and rendering one needs none either, since the width is measured rather than gathered" in {
      super.run(
        """@no_alloc
          |
          |import sysl.math.complex.Complex
          |
          |print(Complex(3.0, 4.0), Complex(0.5, -0.25))""".stripMargin
      ) shouldBe "3+4i 0.5-0.25i\n"
    }

    "a program that did not import it cannot spell the type" in {
      err("var a = Complex(1.0, 2.0)") should include("undefined")
    }

    // **And importing the type is the whole of what a program has to do.** Every operator here is a
    // conditional conformance whose condition names `Float`, which is the library's own import and
    // not this program's — so a program that had to import `sysl.math` as well to add two complex
    // numbers would be leaking the implementation's terms into its caller's.
    "and importing the type is enough to reach every operator" in {
      super.run(
        """import sysl.math.complex.Complex
          |
          |var a = Complex(3.0, 4.0)
          |
          |print(a + a, a * a, a / a, a * 2.0, -a, a == a)""".stripMargin
      ) shouldBe "6+8i -7+24i 1+0i 6+8i -3-4i true\n"
    }

    // The bound is written with the short name, under the import — which is the spelling a user
    // writing their own generic over `Complex` will reach for.
    "and a program may write its own generic over it, bounded the same way" in {
      run(
        """scaled[F: Float](z: Complex[F], k: F) -> Complex[F] = z * k
          |
          |print(scaled(Complex(3.0, 4.0), 2.0), scaled(Complex(3.0f32, 4.0f32), 2.0f32))""".stripMargin
      ) shouldBe "6+8i 6+8i\n"
    }
  }
}
