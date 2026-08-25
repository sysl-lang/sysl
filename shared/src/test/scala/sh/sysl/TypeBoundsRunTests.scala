package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** A bound on a **type's own** type parameters (`reference/generics.md § Bounds`).
 *
 * `struct SortedList[T: Ord]` is the same list a generic function writes, in the same place, meaning
 * the same thing: it is what the declaration assumes of the parameter, and what every application of
 * it must supply. What it buys a *type* is what it buys a function — the members may assume it, so
 * they are checked once at their definition rather than once per instantiation.
 */
class TypeBoundsRunTests extends AnyFreeSpec with RunSupport {

  private val show =
    """trait Show
      |    show(self) -> string
      |struct P
      |    v: int
      |impl Show for P
      |    show(self) -> string = "p" + str(self.v)
      |""".stripMargin

  "a struct's parameter" - {

    "may carry a bound, and its members may assume it" in {
      run(
        s"""${show}struct Wrap[T: Show]
           |    inner: T
           |    label(self) -> string = self.inner.show()
           |var w = Wrap(P(7))
           |print(w.label())""".stripMargin,
      ) shouldBe "p7\n"
    }

    // The member is checked against the bound alone, so nothing about it waits for an
    // instantiation — a type nothing constructs still has its members checked.
    "licenses a member of a type nothing ever constructs" in {
      run(
        s"""${show}struct Wrap[T: Show]
           |    inner: T
           |    label(self) -> string = self.inner.show()
           |print("ok")""".stripMargin,
      ) shouldBe "ok\n"
    }

    "may carry several bounds, all of them available" in {
      run(
        """trait Show
          |    show(self) -> string
          |trait Size
          |    size(self) -> int
          |struct P
          |    v: int
          |impl Show for P
          |    show(self) -> string = "p"
          |impl Size for P
          |    size(self) -> int = 3
          |struct Wrap[T: Show + Size]
          |    inner: T
          |    both(self) -> string = self.inner.show() + str(self.inner.size())
          |print(Wrap(P(1)).both())""".stripMargin,
      ) shouldBe "p3\n"
    }

    // A bound is a trait, and a trait's operators come with it — `+` on the element is `Add`,
    // exactly as it is inside a bounded generic function.
    "licenses an operator through the trait that supplies it" in {
      run(
        """struct Sum[T: Add]
          |    a: T
          |    b: T
          |    total(self) -> T = self.a + self.b
          |print(Sum(3, 4).total())
          |print(Sum(1.5, 2.25).total())""".stripMargin,
      ) shouldBe "7\n3.75\n"
    }

    "licenses a property as readily as a method" in {
      run(
        s"""${show}struct Wrap[T: Show]
           |    inner: T
           |    label -> string = self.inner.show()
           |print(Wrap(P(2)).label)""".stripMargin,
      ) shouldBe "p2\n"
    }

    // `Display` is the bound the rendering surface asks for (`library/core.md § Rendering to a
    // sink`), so a type whose element it bounds may print one.
    "licenses rendering the element" in {
      run(
        """struct Wrap[T: Display]
          |    inner: T
          |    line(self) -> string = f"[${self.inner}]"
          |print(Wrap(41).line())
          |print(Wrap("x").line())""".stripMargin,
      ) shouldBe "[41]\n[x]\n"
    }
  }

  "an enum's parameter" - {

    "carries a bound the same way a struct's does" in {
      run(
        s"""${show}enum Maybe[T: Show]
           |    Just(value: T)
           |    Nothing
           |
           |    render(self) -> string = self match
           |        Just(v) -> v.show()
           |        Nothing -> "-"
           |var a = Just(P(4))
           |var b: Maybe[P] = Nothing
           |print(a.render(), b.render())""".stripMargin,
      ) shouldBe "p4 -\n"
    }
  }

  "the bound travels" - {

    // A type whose parameter is bounded may hand it to anything asking the same, which is the same
    // "a bound is satisfied by a bound" rule a generic function's body follows.
    "from the type's parameter into a bounded function it calls" in {
      run(
        s"""${show}render[U: Show](x: U) -> string = x.show()
           |struct Wrap[T: Show]
           |    inner: T
           |    label(self) -> string = render(self.inner)
           |print(Wrap(P(9)).label())""".stripMargin,
      ) shouldBe "p9\n"
    }

    "from the type's parameter into another bounded type it holds" in {
      run(
        s"""${show}struct Inner[T: Show]
           |    v: T
           |    label(self) -> string = self.v.show()
           |struct Outer[T: Show]
           |    held: Inner[T]
           |    label(self) -> string = self.held.label()
           |print(Outer(Inner(P(5))).label())""".stripMargin,
      ) shouldBe "p5\n"
    }

    // Whether an argument meets a bound is answerable only once every `impl` is registered, so the
    // question is held until then rather than answered against a table still being filled. Here the
    // implementation that makes it true is written below the signature that needs it.
    "even where the 'impl' that satisfies it comes later in the file" in {
      run(
        """trait Show
          |    show(self) -> string
          |struct Wrap[T: Show]
          |    inner: T
          |label(w: Wrap[P]) -> string = w.inner.show()
          |struct P
          |    v: int
          |impl Show for P
          |    show(self) -> string = "p" + str(self.v)
          |print(label(Wrap(P(8))))""".stripMargin,
      ) shouldBe "p8\n"
    }

    // A generic `impl` for a bounded type has to ask at least what the type asks, since its subject
    // is an application of that type like any other.
    "into a generic 'impl' written for the bounded type" in {
      run(
        s"""${show}trait Loud
           |    loud(self) -> string
           |struct Wrap[T: Show]
           |    inner: T
           |impl[T: Show] Loud for Wrap[T]
           |    loud(self) -> string = self.inner.show() + "!"
           |print(Wrap(P(3)).loud())""".stripMargin,
      ) shouldBe "p3!\n"
    }
  }

  "an unbounded parameter is unchanged" - {

    // Holding and handing along any `T` is the free baseline (`reference/generics.md § Bounds`), so
    // the type that asks nothing is exactly the type that could be written before bounds existed.
    "so a container that only moves values around needs no bound" in {
      run(
        """struct Box[T]
          |    value: T
          |    get(self) -> T = self.value
          |var a = Box(41)
          |var b = Box("boxed")
          |print(a.get(), b.get())""".stripMargin,
      ) shouldBe "41 boxed\n"
    }

    "and any type at all may be its argument" in {
      run(
        s"""${show}struct Box[T]
           |    value: T
           |    get(self) -> T = self.value
           |print(Box(P(6)).get().show())""".stripMargin,
      ) shouldBe "p6\n"
    }
  }

  "a bound reaches through the shapes a type is built from" - {

    // A recursive type is laid out with its own parameter standing in for itself, so a bound on it
    // has to survive reaching the type again through the pointer that breaks the cycle.
    "including a type that holds a pointer to itself" in {
      run(
        s"""${show}struct Node[T: Show]
           |    value: T
           |    next: *Node[T]
           |    head(self) -> string = self.value.show()
           |var tail: Node[P] = Node(P(2), null)
           |var list = Node(P(1), &tail)
           |print(list.head(), tail.head())""".stripMargin,
      ) shouldBe "p1 p2\n"
    }

    "and a function that bounds its own parameter to match" in {
      run(
        s"""${show}struct Wrap[T: Show]
           |    inner: T
           |label[U: Show](w: Wrap[U]) -> string = w.inner.show()
           |print(label(Wrap(P(1))))""".stripMargin,
      ) shouldBe "p1\n"
    }

    // Erasure asks whether the type implements the trait, which for a conditional implementation is
    // a question about the argument — and the bound is what makes the answer available.
    "and erasing a bounded type into a trait object" in {
      run(
        s"""${show}trait Loud
           |    loud(self) -> string
           |struct Wrap[T: Show]
           |    inner: T
           |impl[T: Show] Loud for Wrap[T]
           |    loud(self) -> string = self.inner.show() + "!"
           |var o: &Loud = Wrap(P(1))
           |print(o.loud())""".stripMargin,
      ) shouldBe "p1!\n"
    }
  }

  "each definition is checked against its own bounds" - {

    // A parameter standing in for itself is remembered under the name it was written with, and
    // every declaration here spells that name `T` while bounding it differently. A walk that kept
    // what an earlier one worked out would answer the next one's question with the wrong bounds and
    // report a body that is perfectly correct.
    "however many declarations spell their parameter the same way" in {
      run(
        """trait Show
          |    show(self) -> string
          |trait Rank
          |    rank(self) -> int
          |struct P
          |    v: int
          |impl Show for P
          |    show(self) -> string = "p"
          |impl Rank for P
          |    rank(self) -> int = 7
          |struct Box[T: Show]
          |    v: T
          |    named(self) -> string = self.v.show()
          |trait Loud
          |    loud(self) -> string
          |impl[T: Show + Rank] Loud for Box[T]
          |    loud(self) -> string = str(self.v.rank())
          |rendered[T: Display](x: T) -> string = str(x)
          |var b = Box(P(1))
          |print(b.named(), b.loud(), rendered(3))""".stripMargin,
      ) shouldBe "p 7 3\n"
    }
  }

  "a bounded type is emitted per instantiation" - {

    "with one copy of the member for each element type" in {
      val out = Compiler.compileToLlvm(
        """struct Sum[T: Add]
          |    a: T
          |    b: T
          |    total(self) -> T = self.a + self.b
          |print(Sum(1, 2).total())
          |print(Sum(1.5, 2.5).total())""".stripMargin,
      )

      out.map(_.linesIterator.count(l => l.startsWith("define") && l.contains("@Sum.total."))) shouldBe Right(2)
    }
  }
}
