package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** What an **associated type** and a `some` result may not say.
 *
 * Three groups, and they are three different people's mistakes. The **trait's** side is a
 * declaration that leaves nothing open or leaves the wrong thing open. The **implementation's** side
 * is a block that supplies the wrong thing, nothing, or something extra. And the **reader's** side is
 * a projection written where nothing says what it names — which is where erasure lands too, since a
 * trait declaring an associated type is a trait no object can be formed over.
 */
class AssociatedTypeErrorTests extends AnyFreeSpec with CodegenSupport {

  private val render =
    """trait Render
      |    render(self) -> string
      |""".stripMargin

  private val seq =
    render +
      """trait Seq
        |    type Item: Render
        |    head(self) -> Self::Item
        |""".stripMargin

  "the implementation has to supply exactly what the trait left open" - {

    "an associated type the block never supplies is missing" in {
      err(
        """trait Seq
          |    type Item
          |    head(self) -> Self::Item
          |struct Box
          |    v: int
          |impl Seq for Box
          |    head(self) -> Self::Item = self.v
          |print(1)""".stripMargin,
      ) should include("the associated type 'Item' is missing")
    }

    "an associated type the trait never declared cannot be supplied" in {
      err(
        """trait Seq
          |    type Item
          |    head(self) -> Self::Item
          |struct Box
          |    v: int
          |impl Seq for Box
          |    type Item = int
          |    type Extra = bool
          |    head(self) -> Self::Item = self.v
          |print(1)""".stripMargin,
      ) should include("declares no associated type 'Extra'")
    }

    "supplying one twice is refused, since a type has one of each" in {
      err(
        """trait Seq
          |    type Item
          |    head(self) -> Self::Item
          |struct Box
          |    v: int
          |impl Seq for Box
          |    type Item = int
          |    type Item = bool
          |    head(self) -> Self::Item = self.v
          |print(1)""".stripMargin,
      ) should include("'Item' is supplied twice")
    }

    "a supplied type is held to what the trait asked of it" in {
      err(
        seq +
          """struct Box
            |    v: int
            |impl Seq for Box
            |    type Item = int
            |    head(self) -> Self::Item = self.v
            |print(1)""".stripMargin,
      ) should include("asks that its associated type 'Item' implement 'Render', and int does not")
    }
  }

  "a `some` result stands in one position and settles one thing" - {

    "a free function has no trait to settle anything for" in {
      err(
        render +
          """f(x: int) -> some Render = x
            |print(1)""".stripMargin,
      ) should include("it stands only in an 'impl' block")
    }

    "a trait's own member declares the associated type instead" in {
      err(
        render +
          """trait Seq
            |    head(self) -> some Render
            |print(1)""".stripMargin,
      ) should include("it stands only in an 'impl' block")
    }

    "a field is not a result at all" in {
      err(
        render +
          """struct Box
            |    v: some Render
            |print(1)""".stripMargin,
      ) should include("may stand only as the result of a member of an 'impl' block")
    }

    "a member whose trait already fixed the result has nothing left to settle" in {
      err(
        render +
          """trait Seq
            |    head(self) -> int
            |struct Box
            |    v: int
            |impl Seq for Box
            |    head(self) -> some Render = self.v
            |print(1)""".stripMargin,
      ) should include("which is already the answer, so write it")
    }

    "a member the trait does not declare has no associated type to settle" in {
      err(
        render +
          """trait Seq
            |    head(self) -> int
            |struct Box
            |    v: int
            |impl Seq for Box
            |    head(self) -> int = self.v
            |    other(self) -> some Render = self.v
            |print(1)""".stripMargin,
      ) should include("so there is no associated type for 'some Render' to settle")
    }

    "the body has to keep the promise the member made" in {
      err(
        render +
          """trait Seq
            |    type Item
            |    head(self) -> Self::Item
            |struct Box
            |    v: int
            |impl Seq for Box
            |    head(self) -> some Render = self.v
            |print(1)""".stripMargin,
      ) should include("promises 'some Render' and its body yields int, which does not implement 'Render'")
    }

    "two paths yielding two types settle nothing, and the ordinary arm rule says so" in {
      err(
        render +
          """trait Seq
            |    type Item: Render
            |    head(self) -> Self::Item
            |struct A
            |    n: int
            |struct B
            |    n: int
            |impl Render for A
            |    render(self) -> string = "a"
            |impl Render for B
            |    render(self) -> string = "b"
            |struct Box
            |    flag: bool
            |impl Seq for Box
            |    head(self) -> some Render = if self.flag then A(1) else B(2)
            |print(1)""".stripMargin,
      ) should include("if branches have different types: A and B")
    }

    "a result read off a body that reads it back is a loop, and is named as one" in {
      err(
        render +
          """trait Seq
            |    type Item: Render
            |    head(self) -> Self::Item
            |struct Box
            |    v: int
            |impl Seq for Box
            |    head(self) -> some Render = self.head()
            |print(1)""".stripMargin,
      ) should include("a 'some' result cannot depend on itself")
    }

    "and the one the trait asked for, which is a different promise" in {
      err(
        """trait Render
          |    render(self) -> string
          |trait Countable
          |    size(self) -> int
          |impl Countable for int
          |    size(self) -> int = self
          |trait Seq
          |    type Item: Render
          |    head(self) -> Self::Item
          |struct Box
          |    v: int
          |impl Seq for Box
          |    head(self) -> some Countable = self.v
          |print(1)""".stripMargin,
      ) should include("the associated type 'Item' must implement 'Render'")
    }
  }

  "a projection has to have something that says what it names" - {

    "a type parameter bounded by nothing that declares one" in {
      err(
        """trait Plain
          |    hi(self) -> string
          |f[T: Plain](x: T) -> unit
          |    var y: T::Item = x
          |print(1)""".stripMargin,
      ) should include("'T' is not bounded by a trait declaring an associated type 'Item'")
    }

    /** A bound the compiler closes is the one that reaches past the parameter's own bounds: a
     * blanket block written over that family answers the projection for every member of it. What is
     * refused is the same question with no such block behind it — the relaxation is a block that
     * exists, not the family.
     */
    "a type parameter over a closed family that no blanket block implements" in {
      err(
        """trait Seq
          |    type Item
          |    head(self) -> Self::Item
          |f[T: Integer](x: T) -> unit
          |    var y: T::Item = x
          |print(1)""".stripMargin,
      ) should include("'T' is not bounded by a trait declaring an associated type 'Item'")
    }

    // The name is deliberately not `Item`: the standard library's `Iterate` declares one, so
    // `Box::Item` reaches the *next* refusal down — a trait declares it and this type implements
    // none of them — which is the case below rather than this one.
    "a name no trait declares at all" in {
      err(
        """struct Box
          |    v: int
          |val x: Box::Element = 1
          |print(1)""".stripMargin,
      ) should include("no trait declares an associated type 'Element'")
    }

    "a concrete type implementing no trait that declares one" in {
      err(
        seq +
          """struct Box
            |    v: int
            |struct Other
            |    v: int
            |impl Seq for Box
            |    type Item = string
            |    head(self) -> Self::Item = "s"
            |val x: Other::Item = 1
            |print(1)""".stripMargin,
      ) should include("implements no trait declaring the associated type 'Item'")
    }
  }

  "two traits cannot bring one associated-type name to one type" in {
    err(
      """trait A
        |    type Item
        |    a(self) -> Self::Item
        |trait B
        |    type Item
        |    b(self) -> Self::Item
        |struct Box
        |    v: int
        |impl A for Box
        |    type Item = int
        |    a(self) -> Self::Item = self.v
        |impl B for Box
        |    type Item = bool
        |    b(self) -> Self::Item = true
        |print(1)""".stripMargin,
    ) should include("one type cannot have two of one name")
  }

  /** The same rule reaching through a **family**, which is what the standard library's `Magnitude`
   * costs: it declares `type Size` for every integer, so a program's own trait bringing a second
   * `Size` to the integers is refused at the block that creates the collision. The name a library
   * trait picks for an associated type is spent for every type that trait covers.
   */
  "and a blanket block collides with the library's own over the same family" in {
    err(
      """trait Sized
        |    type Size
        |    extent(self) -> Self::Size
        |impl[T: Integer + Zero] Sized for T
        |    type Size = T
        |    extent(self) -> Self::Size = self
        |print(1)""".stripMargin,
    ) should include("already implements 'sysl.math.Magnitude', which declares an associated type 'Size'")
  }

  "declaring an associated type spends the trait's erasability" - {

    "a `&Trait` cannot be formed over it" in {
      err(
        seq +
          """impl Render for int
            |    render(self) -> string = "i"
            |struct Box
            |    v: int
            |impl Seq for Box
            |    type Item = int
            |    head(self) -> Self::Item = self.v
            |show(s: &Seq) -> unit
            |    print(s.head().render())
            |print(1)""".stripMargin,
      ) should include("declares the associated type 'Item', whose meaning is the implementing type's")
    }

    "nor a `*Trait`" in {
      err(
        seq +
          """show(s: *Seq) -> unit
            |    print(1)
            |print(1)""".stripMargin,
      ) should include("so there is no '*Seq' to form")
    }

    "and a trait that merely *requires* one is unerasable too" in {
      err(
        seq +
          """trait Bigger: Seq
            |    more(self) -> int
            |show(s: &Bigger) -> unit
            |    print(1)
            |print(1)""".stripMargin,
      ) should include("declares the associated type 'Item'")
    }

    // What the refusal now *advises*, which is the half a reader acts on. The advice used to be a
    // bound and only a bound; there is a second answer, and a refusal that did not name it would
    // send everybody to rewrite a signature they did not have to.
    "and the refusal names the binding as well as the bound" in {
      val refused = err(
        seq +
          """show(s: *Seq) -> unit
            |    print(1)
            |print(1)""".stripMargin,
      )

      refused should include("Item = …")
      refused should include("[T: Seq]")
    }
  }

  "an object that fixes the associated type has to fix the right one" - {

    "a name the trait does not declare" in {
      err(
        seq +
          """show(s: *Seq[Elem = int]) -> unit
            |    print(1)
            |print(1)""".stripMargin,
      ) should include("declares no associated type 'Elem'")
    }

    // A trait with none at all gets a different sentence, because the reader's mistake is a
    // different one: there is nothing here to fix rather than the wrong thing being fixed.
    "a trait with no associated types has nothing for a binding to name" in {
      err(
        """trait Plain
          |    hi(self) -> string
          |show(s: *Plain[Item = int]) -> unit
          |    print(1)
          |print(1)""".stripMargin,
      ) should include("declares no associated types")
    }

    "the same one twice" in {
      err(
        seq +
          """show(s: *Seq[Item = int, Item = string]) -> unit
            |    print(1)
            |print(1)""".stripMargin,
      ) should include("more than once")
    }

    // The trait's bound on the associated type is a promise about whatever supplies it, and an
    // object supplies one directly — so it is held to the promise exactly as an `impl` is. Without
    // this the slot's signature would typecheck against a type the member's body may not use.
    "a type that does not meet what the trait asked of it" in {
      err(
        seq +
          """impl Render for int
            |    render(self) -> string = "i"
            |show(s: *Seq[Item = bool]) -> unit
            |    print(1)
            |print(1)""".stripMargin,
      ) should include("requires its associated type 'Item'")
    }

    // **The check that makes the binding sound.** Every slot's signature was read under the
    // object's answer, so a value whose implementation chose a different one would be called
    // through a table promising the wrong types. Asked at the erasure, which is the one place both
    // are known — the object type at the context, the implementation at the value.
    "and a value whose implementation chose otherwise does not go into it" in {
      err(
        seq +
          """impl Render for int
            |    render(self) -> string = "i"
            |impl Render for string
            |    render(self) -> string = self
            |struct Box
            |    v: int
            |impl Seq for Box
            |    type Item = int
            |    head(self) -> Self::Item = self.v
            |show(s: &Seq[Item = string]) -> unit
            |    print(s.head().render())
            |show(Box(7))
            |print(1)""".stripMargin,
      ) should (include("says 'Item' is string") and include("supplies int"))
    }

    // A reader one `=` short of the form they meant. Left to resolution they are told there is no
    // type called `A`, which is true and is about the wrong thing.
    "a bare name that is one of the trait's own associated types" in {
      err(
        """trait One
          |    type A
          |    get(self) -> Self::A
          |show(s: &One[A]) -> unit
          |    print(1)
          |print(1)""".stripMargin,
      ) should (include("is an associated type of") and include("[A = …]"))
    }

    // Two things open and one bare argument: the short form cannot say which, and the refusal has
    // to name both rather than reporting an arity the trait does not have.
    "a bare argument where the trait leaves more than one thing open" in {
      err(
        """trait Two
          |    type A
          |    type B
          |    both(self) -> Self::A
          |show(s: &Two[int]) -> unit
          |    print(1)
          |print(1)""".stripMargin,
      ) should (include("'A', 'B'") and include("would not say which"))
    }

    // One thing open and two bare arguments: the reader has the *form* right and the count wrong,
    // so the sentence is about the count. Telling them about ambiguity here would be telling them
    // about somebody else's mistake.
    "and too many bare arguments where the short form would otherwise apply" in {
      err(
        """trait One
          |    type A
          |    get(self) -> Self::A
          |show(s: &One[int, string]) -> unit
          |    print(1)
          |print(1)""".stripMargin,
      ) should (include("One bare argument is the short form for 'A = …'") and include("2 arguments are not"))
    }

    // The bare form is sugar for the named one and is available only where it cannot be read two
    // ways: a trait with parameters of its own has a bare argument meaning one of *those*.
    "and the bare form is refused where the trait has parameters of its own" in {
      err(
        """trait Render
          |    render(self) -> string
          |trait Keyed[K]
          |    type Item: Render
          |    at(self, k: K) -> Self::Item
          |show(s: *Keyed[int]) -> unit
          |    print(1)
          |print(1)""".stripMargin,
      ) should include("declares the associated type 'Item'")
    }
  }

  "the two spellings are told apart by where they are written" - {

    "a trait supplies nothing, so it writes a bound rather than an equals" in {
      err(
        """trait Seq
          |    type Item = int
          |    head(self) -> Self::Item
          |print(1)""".stripMargin,
      ) should include("there is nothing for it to equal here")
    }

    "an implementation chooses, so it writes an equals rather than a bound" in {
      err(
        """trait Seq
          |    type Item
          |    head(self) -> Self::Item
          |struct Box
          |    v: int
          |impl Seq for Box
          |    type Item: Render
          |    head(self) -> Self::Item = self.v
          |print(1)""".stripMargin,
      ) should include("supplies the associated type rather than bounding it")
    }
  }
}
