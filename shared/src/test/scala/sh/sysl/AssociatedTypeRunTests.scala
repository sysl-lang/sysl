package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** Tier-2 runtime behaviour of an **associated type** — a trait parameter the implementation
 * supplies — and of the `some` result that infers one from a body.
 *
 * The two are one feature. An associated type lets a trait name a type the implementation chooses
 * (`type Item: Render`), and `T::Item` is how anything else reads it; a `some` result is what saves
 * the implementation from having to write that type out, which for the tree-shaped types this exists
 * for is the difference between a line and a page.
 *
 * What these check is the whole path: that a projection is **abstract** where its subject is a type
 * parameter and licensed by exactly the bounds the trait declared, that it **normalizes** to the
 * concrete type the moment the subject is concrete, that a generic block's answer is one type per
 * instantiation, and that monomorphization keeps the concrete type all the way down — so the
 * abstraction costs nothing at run time.
 */
class AssociatedTypeRunTests extends AnyFreeSpec with RunSupport {

  /** The shape every test below is written against: something to be bounded by, and a trait whose
   * result the implementation chooses.
   */
  private val render =
    """trait Render
      |    render(self) -> string
      |impl Render for int
      |    render(self) -> string = "i"
      |impl Render for string
      |    render(self) -> string = self
      |""".stripMargin

  "a trait declares one and an implementation supplies it" - {

    "the projection reaches the type the implementation chose" in {
      val src = render +
        """trait Seq
          |    type Item: Render
          |    head(self) -> Self::Item
          |struct Box
          |    v: int
          |impl Seq for Box
          |    type Item = int
          |    head(self) -> Self::Item = self.v
          |print(Box(7).head())""".stripMargin

      run(src) shouldBe "7\n"
    }

    "a bound licenses the projection's own bounds, and nothing else" in {
      val src = render +
        """trait Seq
          |    type Item: Render
          |    head(self) -> Self::Item
          |struct Box
          |    v: string
          |impl Seq for Box
          |    type Item = string
          |    head(self) -> Self::Item = self.v
          |first[S: Seq](s: S) -> string = s.head().render()
          |print(first(Box("hi")))""".stripMargin

      run(src) shouldBe "hi\n"
    }

    "the projection is a type a program may write, off a concrete subject" in {
      val src = render +
        """trait Seq
          |    type Item: Render
          |    head(self) -> Self::Item
          |struct Box
          |    v: int
          |impl Seq for Box
          |    type Item = int
          |    head(self) -> Self::Item = self.v
          |val n: Box::Item = 5
          |print(n.render(), n)""".stripMargin

      run(src) shouldBe "i 5\n"
    }

    "two types answer with two different item types through one bound" in {
      val src = render +
        """trait Seq
          |    type Item: Render
          |    head(self) -> Self::Item
          |struct Nums
          |    v: int
          |struct Words
          |    v: string
          |impl Seq for Nums
          |    type Item = int
          |    head(self) -> Self::Item = self.v
          |impl Seq for Words
          |    type Item = string
          |    head(self) -> Self::Item = self.v
          |first[S: Seq](s: S) -> string = s.head().render()
          |print(first(Nums(1)), first(Words("w")))""".stripMargin

      run(src) shouldBe "i w\n"
    }
  }

  "a generic block answers with one type per instantiation" - {

    "the block's own parameter may stand in the associated type" in {
      val src = render +
        """trait Seq
          |    type Item: Render
          |    head(self) -> Self::Item
          |struct Cell[T]
          |    v: T
          |impl[T: Render] Render for Cell[T]
          |    render(self) -> string = "[" + self.v.render() + "]"
          |struct Box[T]
          |    v: T
          |impl[T: Render] Seq for Box[T]
          |    type Item = Cell[T]
          |    head(self) -> Self::Item = Cell(self.v)
          |first[S: Seq](s: S) -> string = s.head().render()
          |print(first(Box(3)), first(Box("x")))""".stripMargin

      run(src) shouldBe "[i] [x]\n"
    }

    "the projection off one instantiation names that instantiation's answer" in {
      val src = render +
        """trait Seq
          |    type Item: Render
          |    head(self) -> Self::Item
          |struct Cell[T]
          |    v: T
          |impl[T: Render] Render for Cell[T]
          |    render(self) -> string = "[" + self.v.render() + "]"
          |struct Box[T]
          |    v: T
          |impl[T: Render] Seq for Box[T]
          |    type Item = Cell[T]
          |    head(self) -> Self::Item = Cell(self.v)
          |val c: Box[int]::Item = Cell(9)
          |print(c.render())""".stripMargin

      run(src) shouldBe "[i]\n"
    }
  }

  "a `some` result reads the type off the body" - {

    "the associated type is whatever the body produced" in {
      val src = render +
        """trait Seq
          |    type Item: Render
          |    head(self) -> Self::Item
          |struct Text
          |    s: string
          |impl Render for Text
          |    render(self) -> string = self.s
          |struct Box
          |    v: int
          |impl Seq for Box
          |    head(self) -> some Render = Text("t")
          |print(Box(1).head().render())""".stripMargin

      run(src) shouldBe "t\n"
    }

    "a generic caller sees only the bound, and the call still dispatches" in {
      val src = render +
        """trait Seq
          |    type Item: Render
          |    head(self) -> Self::Item
          |struct Text
          |    s: string
          |impl Render for Text
          |    render(self) -> string = self.s
          |struct Box
          |    v: int
          |impl Seq for Box
          |    head(self) -> some Render = Text("t")
          |first[S: Seq](s: S) -> string = s.head().render()
          |print(first(Box(1)))""".stripMargin

      run(src) shouldBe "t\n"
    }

    "the concrete type is still nameable through the projection" in {
      val src = render +
        """trait Seq
          |    type Item: Render
          |    head(self) -> Self::Item
          |struct Text
          |    s: string
          |impl Render for Text
          |    render(self) -> string = self.s
          |struct Box
          |    v: int
          |impl Seq for Box
          |    head(self) -> some Render = Text("t")
          |val t: Box::Item = Box(1).head()
          |print(t.s)""".stripMargin

      run(src) shouldBe "t\n"
    }

    "a generic block infers one per instantiation" in {
      val src = render +
        """trait Seq
          |    type Item: Render
          |    head(self) -> Self::Item
          |struct Cell[T]
          |    v: T
          |impl[T: Render] Render for Cell[T]
          |    render(self) -> string = "[" + self.v.render() + "]"
          |struct Box[T]
          |    v: T
          |impl[T: Render] Seq for Box[T]
          |    head(self) -> some Render = Cell(self.v)
          |first[S: Seq](s: S) -> string = s.head().render()
          |print(first(Box(3)), first(Box("x")))""".stripMargin

      run(src) shouldBe "[i] [x]\n"
    }

    "a property may carry one, which is the shape the feature was asked for" in {
      val src = render +
        """trait View
          |    type Body: Render
          |    body -> Self::Body
          |struct Text
          |    s: string
          |impl Render for Text
          |    render(self) -> string = self.s
          |struct Counter
          |    n: int
          |impl View for Counter
          |    body -> some Render = Text("count")
          |draw[V: View](v: V) -> string = v.body.render()
          |print(draw(Counter(7)))""".stripMargin

      run(src) shouldBe "count\n"
    }
  }

  "`some` is a contextual word and nothing more" in {
    val src =
      """val some = 41
        |some_thing(some: int) -> int = some + 1
        |print(some, some_thing(1))""".stripMargin

    run(src) shouldBe "41 2\n"
  }

  "the type an implementation chooses may be one nobody else can name" in {
    val src = render +
      """trait Seq
        |    type Item: Render
        |    head(self) -> Self::Item
        |private struct Hidden
        |    s: string
        |impl Render for Hidden
        |    render(self) -> string = self.s
        |struct Box
        |    v: int
        |impl Seq for Box
        |    head(self) -> some Render = Hidden("h")
        |first[S: Seq](s: S) -> string = s.head().render()
        |print(first(Box(1)))""".stripMargin

    run(src) shouldBe "h\n"
  }

  "the trait may take its own parameters beside an associated one" in {
    val src = render +
      """trait Tagged[K]
        |    type Item: Render
        |    tag(self, k: K) -> Self::Item
        |struct Box
        |    v: int
        |impl Tagged[int] for Box
        |    type Item = int
        |    tag(self, k: int) -> Self::Item = self.v + k
        |print(Box(1).tag(2), Box(1).tag(2).render())""".stripMargin

    run(src) shouldBe "3 i\n"
  }

  "an associated type may itself be a projection" in {
    val src = render +
      """trait Seq
        |    type Item: Render
        |    head(self) -> Self::Item
        |struct Inner
        |    v: int
        |impl Seq for Inner
        |    type Item = int
        |    head(self) -> Self::Item = self.v
        |struct Outer
        |    inner: Inner
        |impl Seq for Outer
        |    type Item = Inner::Item
        |    head(self) -> Self::Item = self.inner.head()
        |print(Outer(Inner(4)).head())""".stripMargin

    run(src) shouldBe "4\n"
  }

  /** **An object may fix what the implementation would have chosen**, which is the escape from the
   * rule that a trait declaring an associated type cannot be erased.
   *
   * The rule was never arbitrary: a slot's signature is a function of the type the object forgot, so
   * a table over `Seq` alone would have a `head` whose result differed per implementing type. What
   * the binding does is put the answer in the *object type* — `&Seq[Item = int]` is a value of some
   * forgotten type whose `Item` is known to be `int` — so every slot has one signature again and
   * there is a table to point at. Rust spells it `dyn Iterator<Item = String>` for the same reason.
   */
  "an object may fix the associated type, and then it is a table like any other" - {

    "the named form carries the answer through the table" in {
      val src = render +
        """trait Seq
          |    type Item: Render
          |    head(self) -> Self::Item
          |struct Box
          |    v: int
          |impl Seq for Box
          |    type Item = int
          |    head(self) -> Self::Item = self.v
          |show(s: &Seq[Item = int]) -> unit
          |    print(s.head(), s.head().render())
          |show(Box(7))""".stripMargin

      run(src) shouldBe "7 i\n"
    }

    // The bare form is sugar for the named one where the trait has no parameters of its own and
    // exactly one associated type, so the two are one type — a function declared with one takes
    // what the other made, with no conversion between them.
    "and the bare form is the same type as the named one" in {
      val src = render +
        """trait Seq
          |    type Item: Render
          |    head(self) -> Self::Item
          |struct Box
          |    v: int
          |impl Seq for Box
          |    type Item = int
          |    head(self) -> Self::Item = self.v
          |named(s: &Seq[Item = int]) -> int = s.head()
          |bare(s: &Seq[int]) -> int = named(s)
          |print(bare(Box(4)))""".stripMargin

      run(src) shouldBe "4\n"
    }

    // A `*Trait` points straight at a value and a `&Trait` carries a counted box, so the two are
    // separate types and the binding has to reach both.
    "a raw object binds it too" in {
      val src = render +
        """trait Seq
          |    type Item: Render
          |    head(self) -> Self::Item
          |struct Box
          |    v: int
          |impl Seq for Box
          |    type Item = int
          |    head(self) -> Self::Item = self.v
          |show(s: *Seq[Item = int]) -> unit
          |    print(s.head())
          |var b = Box(9)
          |show(&b)""".stripMargin

      run(src) shouldBe "9\n"
    }

    // Two implementations choosing two different types are two object types, and each carries its
    // own answer — which is the whole claim, since a table shared between them would have to
    // promise both.
    "and two types choosing differently are two object types" in {
      val src = render +
        """trait Seq
          |    type Item: Render
          |    head(self) -> Self::Item
          |struct Num
          |    v: int
          |impl Seq for Num
          |    type Item = int
          |    head(self) -> Self::Item = self.v
          |struct Word
          |    v: string
          |impl Seq for Word
          |    type Item = string
          |    head(self) -> Self::Item = self.v
          |ints(s: &Seq[Item = int]) -> string = s.head().render()
          |words(s: &Seq[Item = string]) -> string = s.head().render()
          |print(ints(Num(2)), words(Word("w")))""".stripMargin

      run(src) shouldBe "i w\n"
    }

    // A trait that merely *requires* one is unerasable for the same reason and is rescued the same
    // way: the required trait's members are slots in this trait's own table, so its associated type
    // is a hole in a signature here and is bound in these brackets under the name it was declared
    // with.
    "a required trait's associated type is bound in the same brackets" in {
      val src = render +
        """trait Seq
          |    type Item: Render
          |    head(self) -> Self::Item
          |trait Bigger: Seq
          |    more(self) -> int
          |struct Box
          |    v: int
          |impl Seq for Box
          |    type Item = int
          |    head(self) -> Self::Item = self.v
          |impl Bigger for Box
          |    more(self) -> int = self.v + 1
          |show(s: &Bigger[Item = int]) -> unit
          |    print(s.head(), s.more())
          |show(Box(5))""".stripMargin

      run(src) shouldBe "5 6\n"
    }
  }
}
