package io.github.edadma.sysl

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

  override protected def run(src: String): String = super.run(importing + src)

  "construction and what a value says about itself" - {
    "the parts, the conjugate and the negation" in {
      run(
        """var a = Complex(3.0, 4.0)
          |print(a.re, a.im, a.conj(), -a)""".stripMargin
      ) shouldBe "3 4 3-4i -3-4i\n"
    }

    // The rendering gathers its pieces and pads once, so the field belongs to the whole value.
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

    "equality compares both parts" in {
      run(
        """var a = Complex(3.0, 4.0)
          |print(a == Complex(3.0, 4.0), a == Complex(3.0, 4.1), a != Complex(4.0, 4.0))""".stripMargin
      ) shouldBe "true false true\n"
    }

    // The complex numbers are not ordered, and `14 §2` keeps `Eq` and `Ord` independent so a type
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

  "the module is asked for by name" - {
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
