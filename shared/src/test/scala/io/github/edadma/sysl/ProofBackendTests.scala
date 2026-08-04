package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

/** The proof backend: sysl to WhyML, and Why3's verdict on the result (`17 §9`).
 *
 * **The tests that matter run the prover.** A translation test can only say the output looks like
 * what was expected, and what was expected is exactly what might be wrong — a module that typechecks
 * and proves something *else* passes every string comparison anybody writes. Old sysl's WhyML suite
 * was 1648 lines of string comparison and never invoked Why3 once, which is the shape to avoid. So
 * the assertions here are mostly of the form "this discharges" and "this neighbouring one does not",
 * and they skip cleanly on a machine with no prover.
 */
class ProofBackendTests extends AnyFreeSpec with Matchers {

  private def translated(src: String, overflow: Boolean): Either[String, String] =
    Compiler.typedWith(List(Source("<input>", src)), Nil) match
      case Left(e)               => fail(e)
      case Right((typed, mine))  => WhyML.generate(typed, "Test", overflow, mine)

  private def whyml(src: String, overflow: Boolean = true): String =
    translated(src, overflow) match
      case Left(e)  => fail(e)
      case Right(m) => m

  private def refusal(src: String): String =
    translated(src, true) match
      case Left(e)  => e
      case Right(m) => fail(s"expected a refusal, got:\n$m")

  private def prover: Boolean = Toolchain.why3Available && Toolchain.why3HasProver

  /** Whether Why3 discharged every goal. The exit status is the answer — `why3 prove` reports each
   * goal on its own line and comes back non-zero when one is left.
   */
  private def proves(src: String, overflow: Boolean = true): Boolean = {
    assume(prover, "why3 with a prover not available")
    Toolchain.why3Prove(whyml(src, overflow)) match
      case Left(e)         => fail(e)
      case Right((code, _)) => code == 0
    }

  "a contract is discharged" - {

    "a precondition and a postcondition over integers" in {
      proves("""half(x: int) -> int
               |    require x >= 0
               |    ensure result >= 0
               |    x / 2
               |""".stripMargin) shouldBe true
    }

    // The neighbouring one, and it is what says the test above is not passing because everything
    // passes: the same function with a postcondition that is false.
    "and one that does not hold is not" in {
      proves("""same(x: int) -> int
               |    require x >= 0
               |    ensure result > x
               |    x
               |""".stripMargin) shouldBe false
    }

    "a recursion, including that its measure decreases" in {
      proves("""gcd(a: int, b: int) -> int
               |    require a >= 0
               |    require b >= 0
               |    ensure result >= 0
               |    variant b
               |    if b == 0 then a
               |    else gcd(b, a % b)
               |""".stripMargin) shouldBe true
    }

    "a loop, through its invariant and its variant" in {
      proves("""count_to(n: int) -> int
               |    require n >= 0
               |    ensure result == n
               |    var i = 0
               |
               |    while i < n
               |        invariant i >= 0
               |        invariant i <= n
               |        variant n - i
               |        i += 1
               |
               |    i
               |""".stripMargin) shouldBe true
    }

    // The loop above with one invariant taken away, which is the half that says the invariants are
    // load-bearing rather than decoration: without `i <= n` nothing establishes the postcondition.
    "and the same loop without the invariant that establishes its result is not" in {
      proves("""count_to(n: int) -> int
               |    require n >= 0
               |    ensure result == n
               |    var i = 0
               |
               |    while i < n
               |        invariant i >= 0
               |        variant n - i
               |        i += 1
               |
               |    i
               |""".stripMargin) shouldBe false
    }
  }

  "integer overflow is a proof obligation" - {

    // `17 §9`'s decision, and the sharpest thing about the translation. `n * 2` for an `n` that is
    // only known non-negative can leave the range, and the mathematical model would have hidden it.
    "so a multiplication that can leave the range is not discharged" in {
      proves("""double_it(n: int) -> int
               |    require n >= 0
               |    n * 2
               |""".stripMargin) shouldBe false
    }

    // The same function with a precondition that bounds the input. Nothing else changed, which is
    // what makes this pair evidence about the obligation rather than about the arithmetic.
    "and the same one is, once the input is bounded" in {
      proves("""double_it(n: int) -> int
               |    require n >= 0
               |    require n <= 1000
               |    n * 2
               |""".stripMargin) shouldBe true
    }

    "'--overflow ignore' drops the obligation, and the first one then discharges" in {
      proves("""double_it(n: int) -> int
               |    require n >= 0
               |    n * 2
               |""".stripMargin, overflow = false) shouldBe true
    }

    "a division carries the obligation that its divisor is not zero" in {
      proves("""ratio(a: int, b: int) -> int = a / b""") shouldBe false
    }

    "which a precondition discharges" in {
      proves("""ratio(a: int, b: int) -> int
               |    require b > 0
               |    require a >= 0
               |    require a <= 1000
               |    a / b
               |""".stripMargin) shouldBe true
    }
  }

  "a ghost declaration is what a specification is written in" - {

    "a ghost predicate reads in a contract and is discharged" in {
      proves("""@ghost
               |divides(d: int, n: int) -> bool = d != 0 && n % d == 0
               |
               |double_it(n: int) -> int
               |    require n >= 0
               |    require n <= 1000
               |    ensure divides(2, result)
               |    n * 2
               |""".stripMargin) shouldBe true
    }

    // A bool-valued ghost function becomes a `predicate` rather than a `bool`-valued function, and
    // this is the case that forces it: `forall` is a formula and has no type, so the other spelling
    // is a syntax error rather than a translation that proves something else.
    "a ghost predicate may be a quantifier" in {
      whyml("""@ghost
              |small(n: int) -> bool = for all i in 0..<n do i < n
              |
              |f(n: int) -> int
              |    require small(n)
              |    n
              |""".stripMargin) should include("predicate small")
    }

    "and that one discharges too" in {
      proves("""@ghost
               |small(n: int) -> bool = for all i in 0..<n do i < n
               |
               |f(n: int) -> int
               |    require small(n)
               |    n
               |""".stripMargin) shouldBe true
    }

    // The rule that keeps the two worlds apart: a contract is mathematics, and an ordinary function
    // is a program. `@ghost` is what moves one across, which is the same thing `17 §8` says it is
    // for at runtime.
    "an ordinary function called from a contract is refused, and told to mark it" in {
      refusal("""ok(n: int) -> bool = n > 0
                |
                |f(n: int) -> int
                |    require ok(n)
                |    n
                |""".stripMargin) should include("'@ghost'")
    }

    "and a ghost function whose body is not one expression is refused" in {
      refusal("""@ghost
                |ok(n: int) -> bool
                |    var k = n
                |    k > 0
                |
                |f(n: int) -> int
                |    require ok(n)
                |    n
                |""".stripMargin) should include("a specification is mathematics")
    }
  }

  "what the translation refuses, by name" - {

    // A type it has no model for is refused at the signature, before anything in the body is
    // looked at — which is the earlier and the better of the two places, since what has to change is
    // the signature.
    "a string" in {
      refusal("""f(s: string) -> string = s""") should include("the type string")
    }

    "an array" in {
      refusal("""f(a: [4]int) -> int = a[0]""") should include("the type [4]int")
    }

    "a struct" in {
      refusal("""struct P
                |    x: int
                |
                |f(p: P) -> int = p.x
                |""".stripMargin) should include("the type P")
    }

    "a 'match'" in {
      refusal("""f(n: int) -> int
                |    n match
                |        0 -> 1
                |        _ -> 2
                |""".stripMargin) should include("a 'match'")
    }

    "an early return, and it says why WhyML has no answer for one" in {
      refusal("""f(n: int) -> int
                |    if n > 0 then return 1
                |    0
                |""".stripMargin) should include("part-way through")
    }

    "a 'for' loop" in {
      refusal("""f(n: int) -> int
                |    var s = 0
                |
                |    for i in 0..<n
                |        s += i
                |
                |    s
                |""".stripMargin) should include("a 'for' loop")
    }

    "a call into the library, which this module does not declare" in {
      refusal("""f(n: int) -> int
                |    print(n)
                |    n
                |""".stripMargin) should include("which this module does not declare")
    }

    "a quantifier in code rather than in a contract" in {
      refusal("""f(n: int) -> bool = for all i in 0..<n do i < n""") should include("outside a contract")
    }

    // Every refusal opens the same way, which is what makes a gap in the translator read as one.
    "and every refusal says it is the translator's gap" in {
      refusal("""f(s: string) -> string = s""") should startWith("the proof backend does not translate")
    }
  }

  "the emitted module" - {

    "declares the checked helper for each width it used, once" in {
      val m = whyml("""f(a: int, b: int) -> int
                      |    require a >= 0
                      |    require b >= 0
                      |    require a <= 100
                      |    require b <= 100
                      |    a + b + a + b
                      |""".stripMargin)

      m.split("let add_i32").length - 1 shouldBe 1
    }

    "carries each parameter's own range, which its type already said" in {
      whyml("""f(x: int) -> int = x""") should include("<= x <= ")
    }

    // And drops them with the obligations, since keeping one without the other would leave a
    // function promising a result nothing makes it stay inside.
    "and drops them when the obligations are dropped" in {
      whyml("""f(x: int) -> int = x""", overflow = false) should not include "<= x <= "
    }

    "renames a module-qualified symbol to something WhyML will take" in {
      whyml("""module demo
              |
              |f(x: int) -> int = x
              |""".stripMargin) should include("demo_f")
    }
  }
}
