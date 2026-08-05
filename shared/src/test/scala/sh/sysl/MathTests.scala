package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** `sysl.math` — the `Float` trait, the constants, and the comparisons.
 *
 * The module is a **trait over the built-in float widths** rather than two sets of free functions,
 * which is what a language with no overloading has available and what `02`'s `impl` for a built-in
 * is for. So the first thing these assert is that one spelling reaches both widths and that the
 * width is really still the width — a `.sqrt()` that quietly widened everything to binary64 would
 * pass every value test below and be wrong.
 *
 * The second thing they assert is the split the trait is built around: what libm computes is
 * required of each width, and what is arithmetic over it is a default written once. A default is
 * only worth having if it is the *same* body both times, so the tests that matter run the inherited
 * methods against both `real` and `f32`.
 */
class MathTests extends AnyFreeSpec with RunSupport with CodegenSupport {

  /** Nothing here arrives unasked-for: `sysl.math` is a submodule, so a program says so. Written
   * once so that each program below is about the mathematics.
   */
  private val importing = "import sysl.math.*\n\n"

  override protected def run(src: String): String = super.run(importing + src)

  "the trait reaches both widths" - {
    "one spelling, two receivers" in {
      run(
        """var x: real = 9.0
          |var y: f32 = 16.0f32
          |print(x.sqrt(), y.sqrt())""".stripMargin
      ) shouldBe "3 4\n"
    }

    // The test that would catch a `Float` that had quietly become binary64 everywhere: `1e20`
    // squared is `1e40`, which binary32 cannot hold and binary64 can. If the `f32` impl were not
    // really operating at its own width, both answers here would be `false`.
    "an f32 computes at f32's width, not real's" in {
      run(
        """var small: f32 = 1.0e20f32
          |var big: real = 1.0e20
          |print(small.square().is_infinite(), big.square().is_infinite())""".stripMargin
      ) shouldBe "true false\n"
    }

    "a default body is inherited by both" in {
      run(
        """var x: real = 3.0
          |var y: f32 = 3.0f32
          |print(x.square(), y.square(), x.is_nan(), y.is_nan())""".stripMargin
      ) shouldBe "9 9 false false\n"
    }
  }

  /** The members with no receiver, which is what lets a body shared by two widths name a value of
   * whichever width it was instantiated at. Before them, `signum` and `recip` had to be written per
   * width for want of a one to divide, and a routine generic over the width could not start a
   * running minimum because `infinity()` answered in `real`.
   */
  "the type's own values" - {
    "each width answers with its own" in {
      run("print(real.zero(), real.one(), f32.zero(), f32.one())") shouldBe "0 1 0 1\n"
    }

    // The two the width really decides. Binary32's largest finite value is far below binary64's, so
    // a `max_value` that had quietly become one number would be caught here and nowhere else.
    "the largest finite value is the width's, not the widest one's" in {
      run(
        """print(real.max_value() > 1.0e308, f32.max_value() < 1.0e39, f32.max_value() > 1.0e38)"""
      ) shouldBe "true true true\n"
    }

    "epsilon is the step above one at each width" in {
      run(
        """print(real.one() + real.epsilon() > real.one(), f32.one() + f32.epsilon() > f32.one())"""
      ) shouldBe "true true\n"
    }

    "the two values no literal spells" in {
      run(
        """print(real.infinity().is_infinite(), f32.infinity().is_infinite(),
          |      real.nan().is_nan(), f32.nan().is_nan())""".stripMargin
      ) shouldBe "true true true true\n"
    }

    // The free `infinity()` is the `real` one and answers the same value, which is what keeps the
    // definition in one place rather than in two that could drift.
    "the module's own infinity is the real width's" in {
      run("print(infinity() == real.infinity(), nan().is_nan())") shouldBe "true true\n"
    }

    "pi is at the width asked for" in {
      run("print(real.pi() == pi, (f32.pi() - f32(pi)).abs() < f32.epsilon())") shouldBe "true true\n"
    }

    // The point of all of it: one body, two widths, and a value of the width made inside it.
    "a routine generic over the width builds values of that width" in {
      run(
        """smallest[T: Float](xs: []const T) -> T
          |    var best = T.infinity()
          |
          |    for i in 0..<xs.len do if xs[i] < best then best = xs[i]
          |
          |    best
          |
          |main()
          |    var wide: [3]real = [4.0, -2.0, 9.0]
          |    var narrow: [3]f32 = [1.5f32, 8.0f32, 0.25f32]
          |    print(smallest(wide[..]), smallest(narrow[..]))""".stripMargin
      ) shouldBe "-2 0.25\n"
    }

    // An empty sequence is what a running minimum started at infinity is *for*: there is nothing to
    // replace the start, and the answer is the start.
    "the start of a running minimum survives an empty sequence" in {
      run(
        """smallest[T: Float](xs: []const T) -> T
          |    var best = T.infinity()
          |
          |    for i in 0..<xs.len do if xs[i] < best then best = xs[i]
          |
          |    best
          |
          |main()
          |    var none: [0]f32 = []
          |    print(smallest(none[..]).is_infinite())""".stripMargin
      ) shouldBe "true\n"
    }

    "the defaults that needed a literal are now one body serving both widths" in {
      run(
        """var x: real = -4.0
          |var y: f32 = -4.0f32
          |print(x.signum(), y.signum(), x.recip(), y.recip())""".stripMargin
      ) shouldBe "-1 -1 -0.25 -0.25\n"
    }

    // `signum` answering a zero with that zero rather than with a one is what the comparison pair
    // buys, and it has to keep holding now that the one it does not answer with comes from `one()`.
    "signum still hands a zero back unchanged and a NaN back unchanged" in {
      run("print((0.0).signum(), (-0.0).signum(), nan().signum().is_nan())") shouldBe "0 -0 true\n"
    }
  }

  "roots, exponentials and logarithms" - {
    "square and cube roots" in {
      run("print((144.0).sqrt(), (27.0).cbrt())") shouldBe "12 3\n"
    }

    // The reason `cbrt` is required rather than left to `pow(1.0 / 3.0)`, which is NaN here: a real
    // cube root of a negative number exists and a fractional power of one does not.
    "a cube root is defined for a negative operand where a fractional power is not" in {
      run("print((-27.0).cbrt(), (-27.0).pow(1.0 / 3.0).is_nan())") shouldBe "-3 true\n"
    }

    "the exponential and its inverse undo each other" in {
      run("print(((5.0).ln().exp() - 5.0).abs() < 1.0e-12, ((5.0).exp().ln() - 5.0).abs() < 1.0e-12)")
        .shouldBe("true true\n")
    }

    "the two based exponentials and the three based logarithms" in {
      run("print((10.0).exp2(), (1024.0).log2(), (1000.0).log10(), (2.718281828459045).ln())")
        .shouldBe("1024 10 3 1\n")
    }
  }

  "powers" - {
    "raising to a power" in {
      run("print((2.0).pow(10.0), (9.0).pow(0.5), (2.0).pow(-2.0))") shouldBe "1024 3 0.25\n"
    }

    "the length of a two-dimensional vector" in {
      run("print((3.0).hypot(4.0), (5.0).hypot(12.0))") shouldBe "5 13\n"
    }

    // Why `hypot` is required of each width rather than written as a default over `square` and
    // `sqrt`: the squares overflow long before the answer does. The naive form is the second half of
    // this line, and it is infinite where the library's is not.
    "hypot survives operands whose squares overflow" in {
      run(
        """var big: real = 1.0e200
          |print(big.hypot(big).is_finite(), (big.square() + big.square()).sqrt().is_finite())"""
          .stripMargin
      ) shouldBe "true false\n"
    }
  }

  "trigonometry" - {
    "the circular functions at the quarter turn" in {
      run(
        """print(((tau / 4.0).sin() - 1.0).abs() < 1.0e-15,
          |      (0.0).cos(),
          |      ((tau / 8.0).tan() - 1.0).abs() < 1.0e-15)""".stripMargin
      ) shouldBe "true 1 true\n"
    }

    "the inverse circular functions" in {
      run(
        """print(((1.0).asin() - pi / 2.0).abs() < 1.0e-15,
          |      (1.0).acos(),
          |      ((1.0).atan() - pi / 4.0).abs() < 1.0e-15)""".stripMargin
      ) shouldBe "true 0 true\n"
    }

    // What the two-argument form buys, and the reason the receiver is the vertical coordinate: a
    // ratio cannot tell the second quadrant from the fourth, and `atan2` can. The second and third
    // answers here would both be `-pi / 4` had the ratio been taken first.
    "atan2 tells the quadrants apart where a ratio cannot" in {
      run(
        """var q1 = (1.0).atan2(1.0)
          |var q2 = (1.0).atan2(-1.0)
          |var q4 = (-1.0).atan2(1.0)
          |print((q1 - pi / 4.0).abs() < 1.0e-15,
          |      (q2 - 3.0 * pi / 4.0).abs() < 1.0e-15,
          |      (q4 + pi / 4.0).abs() < 1.0e-15)""".stripMargin
      ) shouldBe "true true true\n"
    }
  }

  "hyperbolic trigonometry" - {
    "at zero, and in the limit" in {
      run("print((0.0).sinh(), (0.0).cosh(), (0.0).tanh(), (30.0).tanh())") shouldBe "0 1 0 1\n"
    }

    "the hyperbolic identity holds" in {
      run(
        """var t: real = 1.3
          |print((t.cosh().square() - t.sinh().square() - 1.0).abs() < 1.0e-12)""".stripMargin
      ) shouldBe "true\n"
    }

    "each inverse undoes its own function" in {
      run(
        """var t: real = 1.3
          |print((t.sinh().asinh() - t).abs() < 1.0e-12,
          |      (t.cosh().acosh() - t).abs() < 1.0e-12,
          |      (t.tanh().atanh() - t).abs() < 1.0e-12)""".stripMargin
      ) shouldBe "true true true\n"
    }

    // The closed forms, which are what a complex `asinh` would otherwise have to be written from --
    // and the check that the bindings are the entry points they claim rather than each other.
    "each inverse agrees with the logarithm it is" in {
      run(
        """var x: real = 2.5
          |var h: real = 0.4
          |print(((x + (x.square() + 1.0).sqrt()).ln() - x.asinh()).abs() < 1.0e-12,
          |      ((x + (x.square() - 1.0).sqrt()).ln() - x.acosh()).abs() < 1.0e-12,
          |      (0.5 * ((1.0 + h) / (1.0 - h)).ln() - h.atanh()).abs() < 1.0e-12)""".stripMargin
      ) shouldBe "true true true\n"
    }

    // `asinh` is odd and total; the other two are not, and where they have no answer libm hands back
    // a NaN rather than trapping -- so a program that can reach outside a domain is told nothing
    // unless it asks. The boundaries themselves *do* have answers, and both are zero.
    "the two with domains answer NaN outside them, and zero at the boundary" in {
      run(
        """print((-2.5).asinh() + (2.5).asinh(),
          |      (0.5).acosh().is_nan(), (1.0).acosh(),
          |      (1.0).atanh().is_infinite(), (1.5).atanh().is_nan(), (0.0).atanh())""".stripMargin
      ) shouldBe "0 true 0 true true 0\n"
    }

    "and every one of them is bound at f32 too" in {
      run(
        """var t: f32 = 1.3f32
          |print((t.sinh().asinh() - t).abs() < 1.0e-5f32,
          |      (t.cosh().acosh() - t).abs() < 1.0e-5f32,
          |      (t.tanh().atanh() - t).abs() < 1.0e-5f32,
          |      (0.5f32).acosh().is_nan())""".stripMargin
      ) shouldBe "true true true true\n"
    }
  }

  "rounding to an integral value" - {
    // Positive operands make all four agree except at the half, so the negative row is the one that
    // says which is which: `floor` goes down, `ceil` goes up, `trunc` goes towards zero.
    "the four differ on a negative operand" in {
      run("print((-2.5).floor(), (-2.5).ceil(), (-2.5).trunc(), (-2.5).round())")
        .shouldBe("-3 -2 -2 -3\n")
    }

    // C's rule, and deliberately not the banker's rounding a *printed* float gets: a half goes away
    // from zero, so `2.5` is `3` and not the `2` that round-half-to-even would give.
    "a half rounds away from zero" in {
      run("print((0.5).round(), (1.5).round(), (2.5).round(), (3.5).round())") shouldBe "1 2 3 4\n"
    }

    "an operand that is already integral is left alone" in {
      run("print((7.0).floor(), (7.0).ceil(), (-7.0).trunc(), (-7.0).round())") shouldBe "7 7 -7 -7\n"
    }
  }

  "the floating-point remainder" - {
    // It keeps the sign of the *receiver*, which is truncating division's rule and the one C's
    // `fmod` follows. A Euclidean remainder would answer `0.5` for the second of these.
    "it keeps the receiver's sign" in {
      run("print((7.5).fmod(2.0), (-7.5).fmod(2.0), (7.5).fmod(-2.0))") shouldBe "1.5 -1.5 1.5\n"
    }

    "an exact division leaves nothing" in {
      run("print((8.0).fmod(2.0), (-8.0).fmod(2.0))") shouldBe "0 -0\n"
    }
  }

  "sign" - {
    "absolute value and signum" in {
      run("print((-2.5).abs(), (2.5).abs(), (-2.5).signum(), (2.5).signum(), (0.0).signum())")
        .shouldBe("2.5 2.5 -1 1 0\n")
    }

    // The three sign operations answer differently at the edges, which is why there are three. A
    // comparison cannot see the sign of a zero; `copysign` reads the bit and can.
    "copysign sees a signed zero where signum cannot" in {
      run("print((1.0).copysign(-0.0), (1.0).copysign(0.0), (-0.0).signum())") shouldBe "-1 1 -0\n"
    }

    "a NaN keeps itself through abs and signum" in {
      run("print(nan().abs().is_nan(), nan().signum().is_nan())") shouldBe "true true\n"
    }

    // A magnitude is never negative, and a negative zero is the operand that says whether the
    // implementation knows it. `if x < 0 then -x else x` fails here — no comparison tells the two
    // zeroes apart, so the negation never runs and `-0` comes back out of an absolute value.
    "a magnitude is never negative, not even for a negative zero" in {
      run("print((-0.0).abs(), (0.0).abs(), (-0.0f32).abs())") shouldBe "0 0 0\n"
    }

    "the reciprocal" in {
      run("print((4.0).recip(), (-0.25).recip(), infinity().recip())") shouldBe "0.25 -4 0\n"
    }
  }

  "classification" - {
    "the three questions over the four kinds of value" in {
      run(
        """print(nan().is_nan(), nan().is_infinite(), nan().is_finite())
          |print(infinity().is_nan(), infinity().is_infinite(), infinity().is_finite())
          |print((-infinity()).is_infinite(), (0.0).is_finite(), (1.0e308).is_finite())"""
          .stripMargin
      ) shouldBe "true false false\nfalse true false\ntrue true true\n"
    }

    // The one that a `self != self` test written for `is_infinite` would get wrong, and the reason
    // the body is a magnitude comparison: a NaN fails every comparison, so it is not infinite.
    "a NaN is not infinite" in {
      run("print(nan().is_infinite(), (nan() * 0.0).is_infinite())") shouldBe "false false\n"
    }

    "an overflow is infinite and a value at the top of the range is not" in {
      run("print((1.7976931348623157e308).is_finite(), (1.7976931348623157e308 * 2.0).is_infinite())")
        .shouldBe("true true\n")
    }
  }

  "degrees and radians" - {
    "the two conversions" in {
      run("print((180.0).to_radians() == pi, pi.to_degrees(), (90.0).to_radians() == pi / 2.0)")
        .shouldBe("true 180 true\n")
    }

    "a whole turn either way" in {
      run("print((360.0).to_radians() == tau, tau.to_degrees())") shouldBe "true 360\n"
    }
  }

  "what the trait answers for itself" - {
    // The reason `square` is worth a name at all: `x * x` written over a call evaluates the call
    // twice, and this evaluates its receiver once. Asserted on the emitted `main` rather than by
    // counting side effects, because one call and two calls to a pure function are indistinguishable
    // from inside the program.
    "square evaluates its receiver once" in {
      val body = irMain(importing + "side() -> real = 3.0\n\nprint(side().square())")

      body.linesIterator.count(_.contains("@side(")) shouldBe 1
    }

    "a logarithm in an arbitrary base" in {
      run("print((81.0).log(3.0), (32.0).log(2.0), (1.0).log(7.0))") shouldBe "4 5 0\n"
    }

    "the arbitrary base agrees with the two dedicated ones" in {
      run(
        """var x: real = 37.5
          |print((x.log(2.0) - x.log2()).abs() < 1.0e-12, (x.log(10.0) - x.log10()).abs() < 1.0e-12)"""
          .stripMargin
      ) shouldBe "true true\n"
    }

    "interpolation" in {
      run("print((0.0).lerp(10.0, 0.25), (10.0).lerp(20.0, 0.5), (2.0).lerp(-2.0, 0.75))")
        .shouldBe("2.5 15 -1\n")
    }

    // The reason for `a + (b - a) * t` over `a * (1 - t) + b * t`, and the price. This form returns
    // the start exactly at `t = 0` for every pair of operands, because the multiply is by zero and
    // the add is of nothing; it does *not* always return the end exactly at `t = 1`, and these
    // operands are a pair where it does not — the difference swallows the smaller value whole, so
    // adding it back lands on zero rather than on `1.0`. The other form trades the two ends around.
    // Starting where the caller said to start is what an animation notices on its first frame.
    "interpolation is exact at the start, and not always at the end" in {
      run(
        """var from: real = 1.0e300
          |var to: real = 1.0
          |print(from.lerp(to, 0.0) == from, from.lerp(to, 1.0) == to, from.lerp(to, 1.0))"""
          .stripMargin
      ) shouldBe "true false 0\n"
    }
  }

  "the constants" - {
    "pi, tau and e" in {
      run("print(pi, tau, e)") shouldBe "3.14159 6.28319 2.71828\n"
    }

    // Doubling a binary64 is exact, so this is an equality rather than a tolerance — and it is worth
    // asserting because the two are written out as separate digit strings rather than derived.
    "tau is exactly twice pi" in {
      run("print(tau == pi * 2.0, pi == tau / 2.0)") shouldBe "true true\n"
    }

    "the derived constants agree with the functions they name" in {
      run(
        """print((sqrt2 - (2.0).sqrt()).abs() < 1.0e-15,
          |      (ln2 - (2.0).ln()).abs() < 1.0e-15,
          |      (ln10 - (10.0).ln()).abs() < 1.0e-15,
          |      (e.ln() - 1.0).abs() < 1.0e-15)""".stripMargin
      ) shouldBe "true true true true\n"
    }

    // What the module says an `f32` program does instead of a second set of constants.
    "a narrower program converts rather than declaring its own" in {
      run(
        """var half: f32 = f32(pi) / 2.0f32
          |print((half.sin() - 1.0f32).abs() < 1.0e-6f32)""".stripMargin
      ) shouldBe "true\n"
    }
  }

  "the values no literal spells" - {
    "infinity" in {
      run("print(infinity().is_infinite(), (-infinity()).is_infinite(), infinity() > 1.0e308)")
        .shouldBe("true true true\n")
    }

    "not-a-number is equal to nothing, itself included" in {
      run("print(nan() == nan(), nan() != nan(), nan() < 1.0, nan() > 1.0)")
        .shouldBe("false true false false\n")
    }

    "the arithmetic that produces each" in {
      run("print((1.0 / 0.0).is_infinite(), (0.0 / 0.0).is_nan(), (infinity() - infinity()).is_nan())")
        .shouldBe("true true true\n")
    }
  }

  "min, max and clamp" - {
    "over the integers, the floats and the strings" in {
      run("""print(min(3, 7), max(3, 7), min(2.5, 1.5), max(2.5, 1.5), min("b", "a"), max("b", "a"))""")
        .shouldBe("3 7 1.5 2.5 a b\n")
    }

    "over a type the program ordered itself" in {
      run(
        """struct Pair
          |    key: int
          |    tag: string
          |
          |impl Ord for Pair
          |    lt(self, rhs: Pair) -> bool = self.key < rhs.key
          |
          |print(min(Pair(2, "low"), Pair(5, "high")).tag, max(Pair(2, "low"), Pair(5, "high")).tag)"""
          .stripMargin
      ) shouldBe "low high\n"
    }

    // Which of two values that compare equal comes back is observable whenever equality is not
    // identity, and both answer with the *first*. A `min` written `if a < b` and a `max` written
    // `if b < a` would each answer "second" here, which is what makes a fold over a sequence
    // shuffle its ties.
    "a tie answers with the first argument" in {
      run(
        """struct Pair
          |    key: int
          |    tag: string
          |
          |impl Ord for Pair
          |    lt(self, rhs: Pair) -> bool = self.key < rhs.key
          |
          |print(min(Pair(1, "first"), Pair(1, "second")).tag,
          |      max(Pair(1, "first"), Pair(1, "second")).tag)""".stripMargin
      ) shouldBe "first first\n"
    }

    "clamp holds a value to its range" in {
      run("print(clamp(9, 0, 5), clamp(-3, 0, 5), clamp(3, 0, 5), clamp(0, 0, 5), clamp(5, 0, 5))")
        .shouldBe("5 0 3 0 5\n")
    }

    // The low end is tested first, so an inverted range answers with it. Pinned because it is the
    // one input for which the name stops describing the result, and a later rewrite that tested the
    // high end first would change it silently.
    "an inverted range answers with its low end" in {
      run("print(clamp(3, 10, 1), clamp(30, 10, 1))") shouldBe "10 1\n"
    }

    // Documented rather than relied on: a NaN loses every comparison, so the single comparison each
    // of these makes is false either way and both keep the *first* argument. This is the behaviour
    // C's `fmin` was criticised for, and a program that must reject a NaN tests for one.
    "a NaN survives in the first argument and is dropped from the second" in {
      run("print(min(nan(), 1.0).is_nan(), min(1.0, nan()), max(nan(), 1.0).is_nan(), max(1.0, nan()))")
        .shouldBe("true 1 true 1\n")
    }
  }

  // What the module does where the mathematics runs out. None of these is an error: IEEE 754 says
  // what each answer is, and a library that trapped instead would be taking a decision away from the
  // caller who can see which of them matters.
  "outside the domain" - {
    "a root and a logarithm of a negative operand are not numbers" in {
      run("print((-1.0).sqrt().is_nan(), (-1.0).ln().is_nan(), (-2.0).asin().is_nan())")
        .shouldBe("true true true\n")
    }

    "the logarithm of zero is the infinity it tends to" in {
      run("print((0.0).ln(), (0.0).log2(), (0.0).log10())") shouldBe "-inf -inf -inf\n"
    }

    "a remainder by zero is not a number, where an integer remainder would trap" in {
      run("print((1.0).fmod(0.0).is_nan(), (0.0).fmod(1.0))") shouldBe "true 0\n"
    }

    // The three corners C fixes by fiat rather than by limit, and a program is entitled to rely on
    // them: nothing raised to nothing is one, and a negative power of zero is the infinity.
    "the corners of a power" in {
      run("print((0.0).pow(0.0), (0.0).pow(-1.0), (-8.0).pow(2.0), (-8.0).pow(0.5).is_nan())")
        .shouldBe("1 inf 64 true\n")
    }

    // What the default's division does when the base has no logarithm to divide by. Worth pinning
    // because it is the one place a *derived* member can go wrong in a way its parts cannot.
    "a logarithm in base one" in {
      run("print((2.0).log(1.0), (1.0).log(1.0).is_nan())") shouldBe "inf true\n"
    }

    "the top of binary32's range" in {
      run(
        """var top: f32 = 3.4028234663852886e38f32
          |print(top.is_finite(), (top * 2.0f32).is_infinite(), top.recip().is_finite())"""
          .stripMargin
      ) shouldBe "true true true\n"
    }
  }

  // The payoff of the trait being a trait rather than two sets of functions: a program can bound a
  // type parameter by it and write the mathematics once. The supertraits are what make the body
  // below legal — `+` inside a generic needs `Add`, and `Float` requires it so that a caller does
  // not have to write the bound out.
  "width-generic mathematics" - {
    "a program may bound a type parameter by the trait" in {
      run(
        """length[T: Float](x: T, y: T) -> T = (x.square() + y.square()).sqrt()
          |
          |print(length(3.0, 4.0), length(3.0f32, 4.0f32))""".stripMargin
      ) shouldBe "5 5\n"
    }

    // A default is a copy per implementing type rather than one shared body (`02`), so the two
    // widths get their own `square` and neither is emitted through the other. Counted on the whole
    // module, since these are library members and not the program's own.
    "a default is materialized once per width" in {
      val out = ir(importing + "var x: real = 3.0\nvar y: f32 = 3.0f32\nprint(x.square(), y.square())")

      out.linesIterator.count(l => l.startsWith("define") && l.contains(".square(")) shouldBe 2
    }

    "and reach the defaults through the bound" in {
      run(
        """decibels[T: Float](power: T, reference: T) -> T = (power / reference).log10()
          |
          |print(decibels(100.0, 1.0), decibels(100.0f32, 1.0f32))""".stripMargin
      ) shouldBe "2 2\n"
    }
  }

  "the error path" - {
    // What the import gates, which is now one answer for both halves of the module. A *name* — `pi`,
    // `min`, `nan` — lives in a submodule and has to be asked for, which is `13 §1`'s rule and the
    // reason `sysl.math` is a submodule at all. A **member** is asked for the same way: it is
    // reachable where its **trait** is (`13 §2`), so `Float` giving `real` a `sqrt` reaches only the
    // files that named `Float`. Pinned in both directions because the symmetry is what a reader is
    // owed — a submodule costs a program nothing it did not ask for, names and members alike.
    "a name from the module has to be asked for" in {
      err("print(pi)") should include("pi")
      err("print(min(1, 2))") should include("min")
      err("print(nan())") should include("nan")
    }

    "and so does a member, which is the same rule and not an exception to it" in {
      // This suite's own `run` prepends the wildcard import, so this is the reachable direction.
      run("print((2.0).sqrt() > 1.414)") shouldBe "true\n"

      // Without the import the member is not reachable, and the message is the import — there is
      // nothing else a file in this position could have been missing.
      val e = err("print((144.0).sqrt())")

      e should include("'sqrt'")
      e should include("sysl.math.Float")
      e should include("not in scope")
    }

    // Naming the trait by hand reaches it exactly as a wildcard does, which is what makes the rule
    // about the trait rather than about the module: `cbrt` comes with `Float` and not with `tau`.
    "naming the trait alone is enough to reach its members" in {
      super.run("import sysl.math.Float\nprint((144.0).sqrt(), (8.0).cbrt())") shouldBe "12 2\n"
    }

    "a width the trait was not implemented for" in {
      err("import sysl.math.*\nprint((2).sqrt())") should include("sqrt")
    }

    "an argument of the other width" in {
      err("import sysl.math.*\nvar x: real = 2.0\nprint(x.pow(2.0f32))") should include("f32")
    }

    "an ordering the type does not have" in {
      err(
        """import sysl.math.*
          |
          |struct Blob
          |    n: int
          |
          |print(min(Blob(1), Blob(2)).n""".stripMargin + ")"
      ) should include("Ord")
    }

    // What `sysl.sys` being the library's own is for: the libm bindings under `sysl.math` are not a
    // surface a program may reach, whether or not it imports the module that uses them.
    "the C bindings underneath are the library's own" in {
      err("import sysl.math.*\nprint(sysl_sqrt(2.0))") should include("sysl_sqrt")
    }

    // The library claims no names. A trait's members are reachable where the **trait** is in scope
    // (`13 §2`), so `Float` giving `real` a `sqrt` leaves the name free for anyone else's trait to
    // give it another — which is what keeps a shipped library implementing a wide trait for a
    // built-in from spending those names on every program that will ever compile.
    // `super.run`, because this suite's own `run` prepends the import and the point here is a file
    // that did not write one.
    "a program may give a float a member the trait already names" in {
      val mine =
        """trait Mine
          |    sqrt(self) -> real
          |
          |impl Mine for real
          |    sqrt(self) -> real = 42.0
          |
          |main()
          |    var x: real = 9.0
          |    print(x.sqrt())""".stripMargin

      super.run(mine) shouldBe "42\n"
    }

    // And with both in scope the call is the thing that cannot be resolved — reported where it
    // happens, naming both traits, rather than one of them silently winning.
    "a call reaching both traits' member is refused, not silently resolved" in {
      val both =
        """import sysl.math.*
          |
          |trait Mine
          |    sqrt(self) -> real
          |
          |impl Mine for real
          |    sqrt(self) -> real = 42.0
          |
          |main()
          |    var x: real = 9.0
          |    print(x.sqrt())""".stripMargin

      err(both) should include("'sqrt'")
      err(both) should include("which was meant")
    }

    // `real` and `f64` are one type under one owner key (`02`), so the library's implementation is
    // already there under both spellings and a program cannot add a second. The diagnostic renders
    // the key, which is why it says `real` for an `impl` written `for f64`.
    "the widths cannot be implemented for a second time under their other spelling" in {
      err(importing + "impl Float for f64\n    sqrt(self) -> f64 = 0.0") should
        include("'real' already implements 'sysl.math.Float'")
    }

    // The trait is not object-safe, and it is its **supertraits** that decide that: `Eq::eq` takes a
    // second `Self`, which an erased value has forgotten. So `Float` is for bounds and receivers and
    // there is no `&Float` to pass around — worth pinning because the requirement list is what would
    // change it, and the requirement list is there for the operators the defaults use.
    "there is no trait object over it" in {
      err(importing + "report(f: &Float) -> real = f.sqrt()") should
        include("no '&sysl.math.Float' to form")
    }

    // And the other side of it: a *name* is only the module's inside the module, so a program is
    // free to declare its own `min` — and while it does, the import cannot also be offering one.
    "a program may declare a name the module offers, so long as it does not import it too" in {
      run("print(3.0.hypot(4.0))") shouldBe "5\n"

      super.run(
        """min(a: string, b: string) -> string = if b < a then b else a
          |
          |print(min("pear", "apple"))""".stripMargin
      ) shouldBe "apple\n"
    }
  }
}
