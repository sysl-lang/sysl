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

    // A trait with parameters of its own keeps them where a reader expects them — in order, at the
    // front — and names the associated type beside them. This is the shape the bare form is refused
    // for, so it is the one that has to work.
    "a trait with both takes its arguments in order and its associated type by name" in {
      val src = render +
        """trait Keyed[K]
          |    type Item: Render
          |    at(self, k: K) -> Self::Item
          |struct Row
          |    v: int
          |impl Keyed[int] for Row
          |    type Item = int
          |    at(self, k: int) -> Self::Item = self.v + k
          |show(s: &Keyed[int, Item = int]) -> unit
          |    print(s.at(2), s.at(2).render())
          |show(Row(5))""".stripMargin

      run(src) shouldBe "7 i\n"
    }

    /** The binding is an ordinary written type, so a generic signature may name one of its own
     * parameters there — which is what makes an object usable from generic code at all rather than
     * only at types written out.
     *
     * **The argument has to be written**, and that is a limit rather than a decision: `shown(Box(1))`
     * is refused with *"cannot infer the type argument 'T'"*, because a trait binds no type
     * parameter during inference (`GenericInstantiation.unify` says so in as many words, for the
     * reason that `f[T](p: *T)` handed a `*Writer` would otherwise instantiate at a type with no
     * layout). Solving `T` backwards through an implementation's `type Item` is a separate piece of
     * machinery and is not built.
     */
    "and a generic signature may bind it to one of its own parameters" in {
      val src = render +
        """trait Seq
          |    type Item: Render
          |    head(self) -> Self::Item
          |struct Box
          |    v: int
          |impl Seq for Box
          |    type Item = int
          |    head(self) -> Self::Item = self.v
          |struct Word
          |    v: string
          |impl Seq for Word
          |    type Item = string
          |    head(self) -> Self::Item = self.v
          |shown[T: Render](s: &Seq[Item = T]) -> string = s.head().render()
          |print(shown[int](Box(1)), shown[string](Word("w")))""".stripMargin

      run(src) shouldBe "i w\n"
    }
  }

  /** A bound on an associated type that is **parameterised**, which is card `0383`.
   *
   * `Add` is declared `Add[Rhs = Self, Out = Self]`, so a bound writing it bare leaves two arguments
   * to be filled from defaults that name `Self` — and `Self` in a bound is the thing the bound is
   * written on. On an associated type that is the associated type; it was the *implementing* type,
   * so `type W = u32` inside `impl Holder for N` asked whether `uint` implements `Add[N, N]`.
   *
   * **Nothing caught it because the two halves have to meet.** A bound has to be on an associated
   * type *and* be parameterised, and the only bounded associated type anywhere — `sysl.math`'s
   * `type Size: Ord` — is bounded by a trait that takes no parameters, so no default was ever filled
   * on this path.
   */
  "a bound on an associated type may be parameterised" - {

    "a bare Add fills its defaults from the associated type, not from the implementing type" in {
      val src =
        """trait Holder
          |    type W: Add
          |    one(self) -> Self::W
          |struct N
          |    v: u32
          |impl Holder for N
          |    type W = u32
          |    one(self) -> u32 = self.v
          |sum[H: Holder](h: H) -> H::W = h.one() + h.one()
          |print(sum(N(21)))""".stripMargin

      run(src) shouldBe "42\n"
    }

    /** Five parameterised bounds at once, at both widths — the shape `sysl.crypto` would be written
      * over. Every one of the five leaves two arguments to be filled from a `Self` default, so this is
      * the case that would have reported five separate refusals naming the implementing type.
      */
    "several parameterised bounds at once, at two widths" in {
      val src =
        """trait Word
          |    type W: Add + Sub + Mul + BitAnd + BitXor
          |    seed(self) -> Self::W
          |struct Narrow
          |    v: u32
          |struct Wide
          |    v: u64
          |impl Word for Narrow
          |    type W = u32
          |    seed(self) -> u32 = self.v
          |impl Word for Wide
          |    type W = u64
          |    seed(self) -> u64 = self.v
          |mixed[T: Word](t: T) -> T::W
          |    val x = t.seed()
          |    (x + x) ^ (x & x)
          |print(mixed(Narrow(3)), mixed(Wide(3)))""".stripMargin

      run(src) shouldBe "5 5\n"
    }
  }

  /** What the trait asked of the associated type, answered once every block is registered rather
   * than at the block that chose it — card `0386`.
   *
   * `checkImplSupers` already holds the question about the type an `impl` is **for**, because the
   * block supplying a required trait may be written below the one that needs it. The same is true
   * one step over, of the type a block **chose**, and that question was being asked inline — so
   * `sysl.crypto`'s `impl Compression for Sha1C`, writing `type W = u32` under a `type W: Word`,
   * was refused by a module whose next file writes `impl Word for u32`. Reordering the two files
   * compiled the same program, which is what makes it a defect rather than a rule.
   */
  "a bound on an associated type may be met by a block written below" in {
    val src =
      """trait Mine
        |    tag(self) -> int
        |trait Holder
        |    type W: Mine
        |    one(self) -> Self::W
        |struct N
        |    v: u32
        |impl Holder for N
        |    type W = u32
        |    one(self) -> u32 = self.v
        |impl Mine for u32
        |    tag(self) -> int = 7
        |print(N(3).one().tag())""".stripMargin

    run(src) shouldBe "7\n"
  }

  /** …and asked in the terms the **block** was written in, not the program's.
   *
   * Holding the question until every `impl` is registered means asking it somewhere else, and the
   * bound is only half resolved: answering it walks the supertraits of whatever the supplied type
   * is *bounded by*, and those are held as written. `sysl.math.Magnitude`'s `type Size: Ord` on a
   * `type Size = F` under an `F: Float` reaches `Ord` through `Float`'s own supers — so asked in the
   * program's scope, a program that declares an `Ord` of its own answers with **that** one, and the
   * library is told its own `Magnitude` is unimplementable.
   *
   * The `abs()` is what makes the program reach `Magnitude` at all; the shadowing trait is what
   * makes the scope matter.
   */
  "a supplied type is held to its bound in the terms the impl was written in" in {
    val src =
      """import sysl.math.complex.Complex
        |trait Ord
        |    rank(self) -> int
        |struct Tier
        |    n: int
        |impl Ord for Tier
        |    rank(self) -> int = self.n
        |print(Tier(3).rank(), Complex(3.0, 4.0).abs())""".stripMargin

    run(src) shouldBe "3 5\n"
  }

  /** A projection asked while the `impl` blocks are still being hoisted — card `0384`.
   *
   * A **non-generic** type is instantiated eagerly, so that it is emitted whether or not anything
   * uses it, and that pass runs before any `impl` block is hoisted because the blocks read its
   * answer. So a field naming a generic applied to a concrete type asked what that type's
   * associated type was of a table that did not exist yet, and was told the type implemented no
   * trait declaring one — pointing at the generic's own field, three lines above the `impl` that
   * plainly supplies it.
   *
   * **A generic holder never reproduced it**, which is what made it look like a defect in the
   * projection rather than in the ordering: a generic type is instantiated by whatever first asks
   * for one, and that is always after the blocks are in.
   */
  "a generic instantiated in a struct field sees the impl supplying its associated type" - {

    /** The shape `sysl.crypto`'s hashers are: a wrapper holding one `Sha[C]`, whose own field is an
      * array of `C::W`.
      */
    "a field holding a generic applied to a concrete type" in {
      val src =
        """trait Comp
          |    type W: Add
          |    step(h: *[4]Self::W)
          |struct Machine[C: Comp]
          |    h: [4]C::W
          |    run(*self) -> C::W
          |        C.step(&self.h)
          |        self.h[0]
          |struct Narrow
          |end Narrow
          |impl Comp for Narrow
          |    type W = u32
          |    step(h: *[4]u32) = h[0] += h[1]
          |struct Wrapper
          |    inner: Machine[Narrow]
          |var w = Wrapper(Machine([7; 4]))
          |print(w.inner.run())""".stripMargin

      run(src) shouldBe "14\n"
    }

    /** The `impl` written **below** the field that reads it, which is the ordering the defect was
      * about: hoisting is what makes a declaration usable before it appears, and this is the one
      * road on which it was not.
      */
    "the impl may be written below the type whose field reads it" in {
      val src =
        """struct Holder
          |    inner: Cell[Tag]
          |struct Cell[C: Named]
          |    v: C::Label
          |    show(self) -> C::Label = self.v
          |trait Named
          |    type Label: Display
          |struct Tag
          |end Tag
          |impl Named for Tag
          |    type Label = string
          |print(Holder(Cell("hi")).inner.show())""".stripMargin

      run(src) shouldBe "hi\n"
    }

    /** The block supplying it is **generic**, so the answer is one type per instantiation and the
      * block's own parameter is what stands at the subject's argument — the substitution a filed
      * block is read under, made here from the declaration.
      */
    "a generic impl block supplies it, at the argument the field fixed" in {
      val src =
        """trait Named
          |    type Label: Display
          |struct Wrap[T: Display]
          |    v: T
          |impl[T: Display] Named for Wrap[T]
          |    type Label = T
          |struct Cell[C: Named]
          |    v: C::Label
          |struct Holder
          |    inner: Cell[Wrap[int]]
          |print(Holder(Cell(4)).inner.v)""".stripMargin

      run(src) shouldBe "4\n"
    }

    /** An **enum** eagerly instantiated for the same reason, so that the fix is not about structs.
      */
    "a variant may carry one too" in {
      val src =
        """trait Named
          |    type Label: Display
          |struct Cell[C: Named]
          |    v: C::Label
          |struct Tag
          |end Tag
          |impl Named for Tag
          |    type Label = int
          |enum Slot
          |    Empty
          |    Full(cell: Cell[Tag])
          |val s = Slot.Full(Cell(9))
          |print(s match
          |    Full(c) -> c.v
          |    Empty -> 0)""".stripMargin

      run(src) shouldBe "9\n"
    }
  }
}
