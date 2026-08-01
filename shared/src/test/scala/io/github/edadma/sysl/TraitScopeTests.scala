package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** A trait's members are reachable where the **trait** can be named, not wherever its implementing
 * type can (`13 §2`).
 *
 * Before this, a type's members were one flat namespace whatever brought them, so the first trait to
 * claim a name on a type claimed it from every program that would ever compile — `sysl.math`'s
 * `Float` took forty-one names on `real` and `f32`, and `guide/fft` could not declare its own `zero`
 * for a float width. Two traits may now each declare a name for one type, and which one a use means
 * is read off what the file imported.
 */
class TraitScopeTests extends AnyFreeSpec with RunSupport with CodegenSupport {

  "two traits may each give one type a member of the same name" in {
    run(
      """trait Zero
        |    zero() -> Self
        |
        |trait Blank
        |    zero() -> Self
        |
        |impl Zero for int
        |    zero() -> int = 0
        |
        |impl Blank for int
        |    zero() -> int = -1
        |
        |main()
        |    print("ok")
        |""".stripMargin) shouldBe "ok\n"
  }

  "a bound says which of the two a generic body means" in {
    run(
      """trait Zero
        |    zero() -> Self
        |
        |trait Blank
        |    zero() -> Self
        |
        |impl Zero for int
        |    zero() -> int = 0
        |
        |impl Blank for int
        |    zero() -> int = -1
        |
        |start[T: Zero]() -> T = T.zero()
        |blank[T: Blank]() -> T = T.zero()
        |
        |main()
        |    var a: int = start()
        |    var b: int = blank()
        |    print(a, b)
        |""".stripMargin) shouldBe "0 -1\n"
  }

  "a call reaching both in scope is refused, and the message names them" in {
    val e = err(
      """trait Zero
        |    zero() -> Self
        |
        |trait Blank
        |    zero() -> Self
        |
        |impl Zero for int
        |    zero() -> int = 0
        |
        |impl Blank for int
        |    zero() -> int = -1
        |
        |main()
        |    print(int.zero())
        |""".stripMargin)

    e should include("'zero'")
    e should include("which was meant")
  }

  "a program may declare a name the library already gave a built-in" in {
    run(
      """trait Zero
        |    zero() -> Self
        |
        |impl Zero for real
        |    zero() -> real = 0.0
        |
        |sum[T: Add + Zero](xs: []const T) -> T
        |    var total = T.zero()
        |    for i in 0..<xs.len do total += xs[i]
        |    total
        |end sum
        |
        |main()
        |    var xs: [3]real = [1.5, 2.5, 3.0]
        |    print(sum(xs[..]))
        |""".stripMargin) shouldBe "7\n"
  }

  // What the whole rule rests on: a submodule is reached by naming or importing it (`13 §3`), so a
  // library trait is out of scope in a file that did not ask for it — which is what leaves its
  // member names free for that file to use.
  "a submodule's trait is not nameable without its import" in {
    err(
      """impl Float for f64
        |    sqrt(self) -> f64 = 0.0""".stripMargin) should include("unknown trait 'Float'")
  }

  "the type's own member still collides, because nothing scopes it" in {
    val e = err(
      """trait Zero
        |    zero() -> Self
        |
        |struct P
        |    x: int
        |    zero(self) -> int = 0
        |
        |impl Zero for P
        |    zero() -> P = P(0)
        |
        |main()
        |    print(1)
        |""".stripMargin)

    e should include("already has a member named 'zero'")
  }
}
