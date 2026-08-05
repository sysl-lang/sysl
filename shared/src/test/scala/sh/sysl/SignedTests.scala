package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** `sysl.math`'s integer half, whose membership is the compiler's rather than a written `impl`
 * (`14 §5`).
 *
 * `Float` could be a library trait because there are two floating-point widths to write blocks for.
 * The integers are an open family — `i5` and `u12` are types a program may name — so `Signed` is
 * declared in source and its memberships are a rule, exactly as `Add`'s are.
 */
class SignedTests extends AnyFreeSpec with RunSupport with CodegenSupport {

  private val importing = "import sysl.math.Signed\n\n"

  "the magnitude" - {

    "is the value without its sign" in {
      run(importing +
        """main()
          |    var a = -7
          |    var b = 7
          |    print(a.abs(), b.abs(), (0).abs())
          |""".stripMargin) shouldBe "7 7 0\n"
    }

    // The width is the receiver's own, and the open family is the whole point — a narrow type the
    // library could never have written an `impl` for has the member on the same rule as `int`.
    "reaches every signed width, including ones no impl could have been written for" in {
      run(importing +
        """main()
          |    var a: i8 = -128
          |    var b: i64 = -9000000000
          |    var c: i16 = -300
          |    print(a.abs(), b.abs(), c.abs())
          |""".stripMargin) shouldBe "-128 9000000000 300\n"
    }

    // Documented in the trait: the magnitude of the most negative value is one larger than the
    // width holds, and plain integer arithmetic wraps rather than trapping (`01`) — so the member
    // answers what the `-` beside it would.
    "answers the most negative value with itself, as negation does" in {
      run(importing +
        """main()
          |    var a: i8 = -128
          |    print(a.abs() == a, -a == a)
          |""".stripMargin) shouldBe "true true\n"
    }

    // The node exists so the receiver is read once; a tree of the operators it means would call
    // `bump` twice and print 2.
    "evaluates its receiver once" in {
      run(importing +
        """bump() -> int
          |    print("called")
          |    -5
          |end bump
          |
          |main()
          |    print(bump().abs())
          |""".stripMargin) shouldBe "called\n5\n"
    }
  }

  "the sign" - {

    "is minus one, zero, or one, at the receiver's own type" in {
      run(importing +
        """main()
          |    var a = -7
          |    print((-7).signum(), (0).signum(), (7).signum(), a.signum() * a)
          |""".stripMargin) shouldBe "-1 0 1 7\n"
    }

    "evaluates its receiver once too" in {
      run(importing +
        """bump() -> int
          |    print("called")
          |    -5
          |end bump
          |
          |main()
          |    print(bump().signum())
          |""".stripMargin) shouldBe "called\n-1\n"
    }
  }

  "what is not a member" - {

    // An unsigned value has no sign to discard, so the member would be the identity and the name
    // would be telling the reader something false about what the code does.
    "an unsigned integer has neither" in {
      err(importing + "main()\n    var a: u8 = 7\n    print(a.abs())") should include("abs")
    }

    // `Float` declares its own `abs`, bound to libm's `fabs` so that it can see the sign bit —
    // which is what makes `(-0.0).abs()` answer `0.0` where a comparison against zero cannot. The
    // two must not both reach a float.
    "a float's magnitude is Float's, and it is the one that sees a negative zero" in {
      err(importing + "main()\n    var x: real = -0.0\n    print(x.abs())") should include("abs")

      run("import sysl.math.Float\n\nmain()\n    var x: real = -0.0\n    print(x.abs())") shouldBe "0\n"
    }
  }

  // The finding this suite exists to pin as much as the members themselves. A compiler-provided
  // membership says which **types** have a member; it is not a way around `13 §2`, which says which
  // **files** may write one. `Add` is unaffected because it is in the standard module and every file
  // auto-imports that.
  "the trait still has to be in scope, membership or not" in {
    val e = err(
      """main()
        |    print((-7).abs())
        |""".stripMargin)

    e should include("abs")

    // And the operator traits, whose memberships are supplied the same way, keep working with no
    // import at all — which is what says the gate is about the module and not about the mechanism.
    run("main()\n    print((5).add(3), 2 + 2)") shouldBe "8 4\n"
  }

  // Nothing is emitted for the trait itself: it has no bodies, and a membership is a fact the type
  // system holds rather than code (`14 §5`).
  "a membership emits no function" in {
    val out = ir(importing + "main()\n    print((-7).abs())")

    out should not include "@Signed"
    out should not include "sysl.math$Signed"
    out should include("select")
  }
}
