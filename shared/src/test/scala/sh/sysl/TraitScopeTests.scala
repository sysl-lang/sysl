package sh.sysl

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

  // The bound answers for a method as well as for an associated function, and the two need
  // different machinery to get there. `T.zero()` still has the `T` written in it; `x.id()` does not,
  // and at an instantiation the analyzed receiver is an `int` with no memory of the parameter it was
  // written as. `pbounds` is what keeps that memory, read off the source expression rather than off
  // the receiver's type.
  "a method on a bounded parameter resolves through the bound too" in {
    run(
      """trait Zero
        |    id(self) -> int
        |
        |trait Blank
        |    id(self) -> int
        |
        |impl Zero for int
        |    id(self) -> int = 1
        |
        |impl Blank for int
        |    id(self) -> int = 2
        |
        |tell[T: Zero](x: T) -> int = x.id()
        |
        |main()
        |    print(tell(7))
        |""".stripMargin) shouldBe "1\n"
  }

  // The second axis crossing the first. Two implementations of one parameterized trait are told
  // apart by their arguments (`08`), a third trait's member of the same name by scope — and a type
  // may be in both situations at once, which is three members filed under one written name.
  "a third trait may name what two implementations of one already share" in {
    run(
      """trait Of[T]
        |    of(x: T) -> Self
        |
        |trait Blank
        |    of(x: bool) -> Self
        |
        |struct P
        |    n: int
        |
        |impl Of[int] for P
        |    of(x: int) -> P = P(x)
        |
        |impl Of[string] for P
        |    of(x: string) -> P = P(1)
        |
        |impl Blank for P
        |    of(x: bool) -> P = P(99)
        |
        |main()
        |    print(P.of(7).n)
        |""".stripMargin) shouldBe "7\n"
  }

  // The error path of the same crossing: the arguments narrow first, and what they leave decides
  // which complaint this is. Two survivors from two traits is not a question about arguments — both
  // take an `int` — so the message names the traits rather than the argument types.
  "and where the arguments leave two traits standing, the traits are what is named" in {
    val e = err(
      """trait Of[T]
        |    of(x: T) -> Self
        |
        |trait Blank
        |    of(x: int) -> Self
        |
        |struct P
        |    n: int
        |
        |impl Of[int] for P
        |    of(x: int) -> P = P(x)
        |
        |impl Of[string] for P
        |    of(x: string) -> P = P(1)
        |
        |impl Blank for P
        |    of(x: int) -> P = P(99)
        |
        |main()
        |    print(P.of(7).n)
        |""".stripMargin)

    e should include("Of")
    e should include("Blank")
    e should include("which was meant")
  }

  // A default the block left out is hoisted for the implementing type exactly as a written method
  // is, and the block it lands in is the suffixed one — so the copy has to be filed and named under
  // the same suffix its siblings got, or it is emitted under a name nothing reaches.
  "a default is inherited into the block that took the suffix" in {
    run(
      """trait Zero
        |    zero() -> Self
        |
        |trait Blank
        |    zero() -> Self
        |    twice(self) -> int = 2
        |
        |impl Zero for int
        |    zero() -> int = 0
        |
        |impl Blank for int
        |    zero() -> int = -1
        |
        |main()
        |    var n = 7
        |    print(n.twice())
        |""".stripMargin) shouldBe "2\n"
  }

  // The same hole one path over: a member the suffixed block **wrote** rather than inherited is
  // filed under the suffix too, and has no bare-named sibling either. Both routes into
  // `hoistMemberList` reach it, so both are pinned.
  "and so is a method only the suffixed block declares" in {
    run(
      """trait Zero
        |    zero() -> Self
        |
        |trait Blank
        |    zero() -> Self
        |    thrice(self) -> int
        |
        |impl Zero for int
        |    zero() -> int = 0
        |
        |impl Blank for int
        |    zero() -> int = -1
        |    thrice(self) -> int = 3
        |
        |main()
        |    var n = 7
        |    print(n.thrice())
        |""".stripMargin) shouldBe "3\n"
  }

  // An object forgets its type, so a call through one is answered by the table rather than by the
  // member lookup — and the table is built per trait. The slot has to hold the implementation the
  // object's own trait supplied, not whichever was filed first under the written name.
  "a trait object calls its own trait's member, not the other's" in {
    run(
      """trait Zero
        |    id(self) -> int
        |
        |trait Blank
        |    id(self) -> int
        |
        |struct P
        |    n: int
        |
        |impl Zero for P
        |    id(self) -> int = 1
        |
        |impl Blank for P
        |    id(self) -> int = 2
        |
        |tell(z: &Zero) -> int = z.id()
        |
        |main()
        |    var z: &Zero = P(0)
        |    print(tell(z))
        |""".stripMargin) shouldBe "1\n"
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
