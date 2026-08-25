package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** A trait's member may take a **bare-arrow** callable — `f: T -> U` — which is sugar for a bounded
 * type parameter (`reference/types.md § Function types`) exactly as it is on a type's own member.
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

  /** An arrow's parameter and result may be an **associated-type projection**, which is the shape
   * the sugar exists for and the one it could not express.
   *
   * The rewrite adds a type parameter and moves the arrow's written types into a bound on it, so
   * those types are resolved one scope further in than they were written. Two things there belonged
   * to the scope they came from and were taken by the scope they arrived in: `Self`, which a bound
   * binds to the parameter it constrains, and any other parameter's bounds, which is what says a
   * projection off it resolves at all. Neither is the arrow's to reinterpret — `f: Self::Item -> N`
   * is written where `Self` is the receiver, and it means that wherever the desugaring puts it.
   *
   * The failure read as a missing bound on a parameter that plainly had one, and on a trait member
   * it named `$F1` — the synthesized parameter — as the thing lacking the bound, which is the
   * substitution showing through.
   */
  "an arrow may name an associated type" - {
    "off a bounded parameter" in {
      run("""trait Walk
            |    type Item
            |    step(*self) -> Option[Self::Item]
            |struct Down
            |    n: int
            |impl Walk for Down
            |    type Item = int
            |    step(*self) -> Option[int]
            |        if self.n <= 0 then return None
            |        self.n -= 1
            |        Some(self.n)
            |first_of[S: Walk, N](s: *S, f: S::Item -> N) -> Option[N] =
            |    s.step() match
            |        Some(t) -> Some(f(t))
            |        None -> None
            |var d = Down(3)
            |print(first_of(&d, n -> n * 10).expect("a step"))""".stripMargin) shouldBe "20\n"
    }

    /** The result side of the arrow, which resolves through the same walk and was refused the same
      * way — so fixing one without the other would leave half the shape unwritable.
      */
    "in the arrow's result as well as its parameter" in {
      run("""trait Walk
            |    type Item
            |    step(*self) -> Option[Self::Item]
            |struct Down
            |    n: int
            |impl Walk for Down
            |    type Item = int
            |    step(*self) -> Option[int]
            |        if self.n <= 0 then return None
            |        self.n -= 1
            |        Some(self.n)
            |made[S: Walk, N](s: *S, x: N, f: N -> S::Item) -> S::Item = f(x)
            |var d = Down(1)
            |print(made(&d, 4, n -> n + 38))""".stripMargin) shouldBe "42\n"
    }

    /** The one shape still refused, and it is narrow: a trait's **default body** whose parameter is
      * an arrow over `Self::Item`. The declaration alone is fine — the case below it declares
      * exactly this member and an `impl` supplies it — so what is left is the pass that reads a
      * default's own signature, which resolves the added bound somewhere `Self` is not bound and
      * reports it as `Self` outside a trait.
      *
      * Written with the assertion it should make, since the shape is decided and only the
      * declaration form is unsupported.
      */
    "and 'Self::Item' on a trait's own member" in {
      run("""trait Mapper
            |    type Item
            |    first(self) -> Self::Item
            |    over[N](self, f: Self::Item -> N) -> N = f(self.first())
            |struct One
            |    v: int
            |impl Mapper for One
            |    type Item = int
            |    first(self) -> int = self.v
            |print(One(20).over(n -> n + 1))""".stripMargin) shouldBe "21\n"
    }

    /** The same on an `impl` block's member rather than a trait's default, since the three
      * declaration forms reach the rewrite by three different paths.
      */
    "and on an implementation's member" in {
      run("""trait Mapper
            |    type Item
            |    over[N](self, f: Self::Item -> N) -> N
            |struct One
            |    v: int
            |impl Mapper for One
            |    type Item = int
            |    over[N](self, f: int -> N) -> N = f(self.v)
            |print(One(20).over(n -> n + 1))""".stripMargin) shouldBe "21\n"
    }

    /** The boxed spelling always accepted a projection, and it is what a caller had to reach for.
      * Keeping it here says the fix moved the bare arrow up to it rather than changing what it does.
      */
    "which the boxed spelling could already do" in {
      run("""trait Walk
            |    type Item
            |    step(*self) -> Option[Self::Item]
            |struct Down
            |    n: int
            |impl Walk for Down
            |    type Item = int
            |    step(*self) -> Option[int]
            |        if self.n <= 0 then return None
            |        self.n -= 1
            |        Some(self.n)
            |boxed_first[S: Walk](s: *S, f: &Fn(S::Item) -> int) -> int =
            |    s.step() match
            |        Some(t) -> f(t)
            |        None -> 0
            |var d = Down(3)
            |print(boxed_first(&d, n -> n * 10))""".stripMargin) shouldBe "20\n"
    }

    /** The error path the fix must not swallow: a projection naming something the bound's trait does
      * not declare is still refused, and now says so about the parameter the reader wrote rather
      * than about the synthesized one.
      */
    "while a projection the bound cannot supply is still refused" in {
      val message = err("""trait Walk
                          |    type Item
                          |    step(*self) -> Option[Self::Item]
                          |first_of[S: Walk, N](s: *S, f: S::Missing -> N) -> int = 0
                          |print(1)""".stripMargin)

      message should include("'Missing'")
      message should not include "$F"
    }

    /** Two implementing types, so the default is copied twice and each copy has to bind `Self` to
      * its own subject. One copy binding for both would compile and print the wrong thing, which is
      * the failure a single-implementation test cannot see.
      */
    "with a default body copied to two implementing types" in {
      run("""trait Mapper
            |    type Item
            |    first(self) -> Self::Item
            |    over[N](self, f: Self::Item -> N) -> N = f(self.first())
            |struct One
            |    v: int
            |impl Mapper for One
            |    type Item = int
            |    first(self) -> int = self.v
            |struct Word
            |    w: string
            |impl Mapper for Word
            |    type Item = string
            |    first(self) -> string = self.w
            |print(One(20).over(n -> n + 1))
            |print(Word("hello").over(s -> s.len))""".stripMargin) shouldBe "21\n5\n"
    }

    /** A **generic** implementing type, where `Self` spells `Box[T]` rather than a name — so the
      * projection is read off a subject that is itself still being solved.
      */
    "with a default body on a generic implementing type" in {
      run("""trait Mapper
            |    type Item
            |    first(self) -> Self::Item
            |    over[N](self, f: Self::Item -> N) -> N = f(self.first())
            |struct Box[T]
            |    v: T
            |impl[T] Mapper for Box[T]
            |    type Item = T
            |    first(self) -> T = self.v
            |print(Box(20).over(n -> n + 1))""".stripMargin) shouldBe "21\n"
    }

    /** The projection on **both** sides of a default's arrow, which is the shape that has nothing
      * left for the call to read a type off: the closure's parameter and its result are the same
      * associated type, and only the receiver says what that is.
      */
    "with the projection on both sides of a default's arrow" in {
      run("""trait Mapper
            |    type Item
            |    first(self) -> Self::Item
            |    twice(self, f: Self::Item -> Self::Item) -> Self::Item = f(f(self.first()))
            |struct One
            |    v: int
            |impl Mapper for One
            |    type Item = int
            |    first(self) -> int = self.v
            |print(One(20).twice(n -> n + 1))""".stripMargin) shouldBe "22\n"
    }

    /** A default body whose arrow names a projection the trait does not declare. The refusal has to
      * survive the rewrite, and it has to name what the reader wrote.
      */
    "while a default's projection the trait cannot supply is still refused" in {
      val message = err("""trait Mapper
                          |    type Item
                          |    first(self) -> Self::Item
                          |    over[N](self, f: Self::Missing -> N) -> N = f(self.first())
                          |struct One
                          |    v: int
                          |impl Mapper for One
                          |    type Item = int
                          |    first(self) -> int = self.v
                          |print(One(20).over(n -> n + 1))""".stripMargin)

      message should include("'Missing'")
      message should not include "$F"
    }
  }
}
