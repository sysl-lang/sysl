package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** A trait's member may take a **bare-arrow** callable — `f: T -> U` — which is sugar for a bounded
 * type parameter (`12 §6`) exactly as it is on a type's own member.
 *
 * **What it buys is that nothing is boxed.** The other spelling, `&Fn(T) -> U`, is a counted
 * reference: the closure a caller writes goes on the heap at the call, once per call, before an
 * element is touched. A bare arrow monomorphizes instead — one specialized copy per call site,
 * called directly — which is what `sysl.slices`'s sorts have always had and what nothing reached
 * through a trait could have.
 *
 * **What it costs is the member's table slot**, and that is the standing rule about a member with
 * type parameters of its own rather than a new one: the function does not exist until a call names
 * them, so no slot can point at it. A trait whose members all take bare arrows still forms an
 * object; what that object cannot do is dispatch them.
 *
 * The desugaring ran for a type's members and for an `impl` block's and never for a trait's, so the
 * spelling was refused there — which is why `sysl.seq` asks for `&Fn(…)` and allocates once per
 * call for every one of its ten members.
 */
class TraitCallableTests extends AnyFreeSpec with RunSupport with CodegenSupport {

  "a trait's member takes a bare-arrow callable" - {
    "and the value it is called on runs it" in {
      run("""trait Applies
            |    apply(self, f: int -> int) -> int
            |struct N
            |    v: int
            |impl Applies for N
            |    apply(self, f: int -> int) -> int = f(self.v)
            |print(N(20).apply(n -> n + 1))""".stripMargin) shouldBe "21\n"
    }

    /** The `impl` writes the arrow again rather than the synthesized parameter, which is the point
      * of doing this at the declaration: both sides are rewritten the same way, and they line up by
      * position.
      */
    "through a bound, which is where a library would reach it" in {
      run("""trait Applies
            |    apply(self, f: int -> int) -> int
            |struct N
            |    v: int
            |impl Applies for N
            |    apply(self, f: int -> int) -> int = f(self.v)
            |twice[A: Applies](a: A) -> int = a.apply(n -> n * 2)
            |print(twice(N(21)))""".stripMargin) shouldBe "42\n"
    }

    /** The result type may be the member's own parameter, which is `map`'s shape and the reason
      * this matters beyond saving an allocation.
      */
    "and the result type may be chosen by the closure" in {
      run("""trait Mapping[T]
            |    mapped[U](self, f: T -> U) -> U
            |struct One[E]
            |    v: E
            |impl[E] Mapping[E] for One[E]
            |    mapped[U](self, f: E -> U) -> U = f(self.v)
            |print(One(3).mapped(n -> s"<${n}>"))""".stripMargin) shouldBe "<3>\n"
    }

    /** The whole reason for the spelling, read where it cannot be argued with. A closure handed to
      * a `&Fn` parameter is allocated at the call; handed to a bare arrow it is a type argument, and
      * the emitted program never reaches the allocator.
      */
    "and the closure is not boxed, which is the whole point" in {
      val out = ir(
        """trait Applies
          |    apply(self, f: int -> int) -> int
          |struct N
          |    v: int
          |impl Applies for N
          |    apply(self, f: int -> int) -> int = f(self.v)
          |print(N(20).apply(n -> n + 1))""".stripMargin,
      )

      out.linesIterator.count(l => l.contains("call") && l.contains("@malloc")) shouldBe 0
    }

    /** The comparison that says it is the *spelling* and not the trait: the same program written
      * with the boxed form allocates.
      */
    "where the boxed spelling beside it does" in {
      val out = ir(
        """trait Applies
          |    apply(self, f: &Fn(int) -> int) -> int
          |struct N
          |    v: int
          |impl Applies for N
          |    apply(self, f: &Fn(int) -> int) -> int = f(self.v)
          |print(N(20).apply(n -> n + 1))""".stripMargin,
      )

      out.linesIterator.count(l => l.contains("call") && l.contains("@malloc")) should be > 0
    }
  }

  /** A member that declares type parameters of its own has no table slot, and a bare arrow declares
    * one — so this is the cost, stated where somebody choosing between the two spellings will meet
    * it. The object still forms and still dispatches the members that have slots.
    */
  "what it costs is the table slot" - {
    "the object forms and dispatches everything else" in {
      run("""trait Applies
            |    tag(self) -> int
            |    apply(self, f: int -> int) -> int
            |struct N
            |    v: int
            |impl Applies for N
            |    tag(self) -> int = self.v
            |    apply(self, f: int -> int) -> int = f(self.v)
            |val o: &Applies = N(7)
            |print(o.tag())""".stripMargin) shouldBe "7\n"
    }

    "and refuses the one that has none" in {
      err("""trait Applies
            |    tag(self) -> int
            |    apply(self, f: int -> int) -> int
            |struct N
            |    v: int
            |impl Applies for N
            |    tag(self) -> int = self.v
            |    apply(self, f: int -> int) -> int = f(self.v)
            |val o: &Applies = N(7)
            |print(o.apply(n -> n + 1))""".stripMargin) should
        include("'apply' of 'Applies' declares type parameters of its own")
    }

    /** So a trait meant to be erased writes the boxed form deliberately, and this is the pair that
      * says the choice is the author's rather than the language's.
      */
    "while the boxed spelling keeps its slot and dispatches" in {
      run("""trait Applies
            |    apply(self, f: &Fn(int) -> int) -> int
            |struct N
            |    v: int
            |impl Applies for N
            |    apply(self, f: &Fn(int) -> int) -> int = f(self.v)
            |val o: &Applies = N(20)
            |print(o.apply(n -> n + 1))""".stripMargin) shouldBe "21\n"
    }
  }

  /** The shape a library reaches for immediately: one implementation answering by handing its
    * callable to another's. `sysl.seq`'s `Buf` half is ten lines of exactly this, so whether the
    * module can drop the boxed spelling at all turns on it.
    *
    * What makes it a question is that the parameter's type is by then the *bound* type parameter
    * rather than a callable type — so passing it on asks whether a value known only by its bound
    * satisfies the same bound at the next call.
    */
  "one implementation may hand its callable to another" in {
    run("""trait Applies
          |    apply(self, f: int -> int) -> int
          |struct N
          |    v: int
          |struct M
          |    inner: N
          |impl Applies for N
          |    apply(self, f: int -> int) -> int = f(self.v)
          |impl Applies for M
          |    apply(self, f: int -> int) -> int = self.inner.apply(f)
          |print(M(N(20)).apply(n -> n + 1))""".stripMargin) shouldBe "21\n"
  }

  /** A default body may take one too — it is an ordinary member of the trait, and the lowered
    * function carries the trait's parameters and the member's own together.
    */
  "a default body takes one as well" in {
    run("""trait Applies
          |    value(self) -> int
          |    apply(self, f: int -> int) -> int = f(self.value())
          |struct N
          |    v: int
          |impl Applies for N
          |    value(self) -> int = self.v
          |print(N(20).apply(n -> n + 1))""".stripMargin) shouldBe "21\n"
  }
}
