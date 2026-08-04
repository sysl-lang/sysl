package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** Tier-2 runtime behavior of an **associated function** on a generic type (`10 § Open b`).
 *
 * A method reads the type's arguments straight off its receiver. An associated function has no
 * receiver, so it stands where a generic *free* function stands: the arguments are inferred from
 * what the call passes and, where that does not settle them, from the type the context expects.
 * These check that inference reaches through both routes, that `Self` in the signature means the
 * type applied to its own parameters, and that what comes out is monomorphized per instantiation
 * exactly as everything else generic is.
 */
class AssociatedGenericRunTests extends AnyFreeSpec with RunSupport {

  "the arguments come from what the call passes" - {

    "one parameter inferred from one argument, at two element types" in {
      val src =
        """struct Box[T]
          |    v: T
          |    of(x: T) -> Box[T] = Box(x)
          |    get(self) -> T = self.v
          |var a = Box.of(41)
          |var b = Box.of("boxed")
          |print(a.get(), b.get())""".stripMargin

      run(src) shouldBe "41 boxed\n"
    }

    "two parameters inferred from two arguments, in the order the subject writes them" in {
      val src =
        """struct Pair[A, B]
          |    a: A
          |    b: B
          |    of(x: A, y: B) -> Pair[A, B] = Pair(x, y)
          |    first(self) -> A = self.a
          |    second(self) -> B = self.b
          |var p = Pair.of(7, "seven")
          |print(p.first(), p.second())""".stripMargin

      run(src) shouldBe "7 seven\n"
    }

    "a parameter appearing under a composed type is reached" in {
      val src =
        """struct Holder[T]
          |    v: T
          |    head(xs: []T) -> Holder[T] = Holder(xs[0])
          |    get(self) -> T = self.v
          |var xs = [3, 4, 5]
          |print(Holder.head(xs[0..<3]).get())""".stripMargin

      run(src) shouldBe "3\n"
    }

    "an argument settles the parameter even where the result names it too" in {
      val src =
        """struct Box[T]
          |    v: T
          |    of(x: T) -> Self = Box(x)
          |    get(self) -> T = self.v
          |print(Box.of(9).get())""".stripMargin

      run(src) shouldBe "9\n"
    }

    "'Self' in a parameter is the type applied to its own parameters" in {
      val src =
        """struct Box[T: Add]
          |    v: T
          |    joined(x: Self, y: Self) -> Self = Box(x.v + y.v)
          |    get(self) -> T = self.v
          |print(Box.joined(Box(2), Box(3)).get())""".stripMargin

      run(src) shouldBe "5\n"
    }
  }

  "the arguments come from the type the context expects" - {

    "an annotated variable settles a parameter no argument mentions" in {
      val src =
        """struct Cursor[T]
          |    p: *T
          |    none() -> Cursor[T] = Cursor(null)
          |    empty(self) -> bool = self.p == null
          |var c: Cursor[int] = Cursor.none()
          |print(c.empty())""".stripMargin

      run(src) shouldBe "true\n"
    }

    "and so does a result written 'Self'" in {
      val src =
        """struct Cursor[T]
          |    p: *T
          |    none() -> Self = Cursor(null)
          |    empty(self) -> bool = self.p == null
          |var c: Cursor[string] = Cursor.none()
          |print(c.empty())""".stripMargin

      run(src) shouldBe "true\n"
    }

    "a declared parameter of the function being called is the expected type" in {
      val src =
        """struct Cursor[T]
          |    p: *T
          |    none() -> Cursor[T] = Cursor(null)
          |empty(c: Cursor[int]) -> bool = c.p == null
          |print(empty(Cursor.none()))""".stripMargin

      run(src) shouldBe "true\n"
    }

    "a declared result is too" in {
      val src =
        """struct Cursor[T]
          |    p: *T
          |    none() -> Cursor[T] = Cursor(null)
          |start() -> Cursor[real] = Cursor.none()
          |print(start().p == null)""".stripMargin

      run(src) shouldBe "true\n"
    }
  }

  "the type's bounds hold at the call and inside the body" - {

    "the body may assume what the type asks of its parameter" in {
      val src =
        """struct Tagged[T: Display]
          |    label: string
          |    describe(x: T) -> Tagged[T] = Tagged("<" + str(x) + ">")
          |    read(self) -> string = self.label
          |print(Tagged.describe(12).read(), Tagged.describe(true).read())""".stripMargin

      run(src) shouldBe "<12> <true>\n"
    }

    "an argument the bound is satisfied by is accepted through a user impl" in {
      val src =
        """trait Rank
          |    rank(self) -> int
          |struct P
          |    n: int
          |impl Rank for P
          |    rank(self) -> int = self.n * 2
          |struct Scored[T: Rank]
          |    score: int
          |    of(x: T) -> Scored[T] = Scored(x.rank())
          |    read(self) -> int = self.score
          |print(Scored.of(P(4)).read())""".stripMargin

      run(src) shouldBe "8\n"
    }
  }

  "a generic caller hands its own parameter on" - {

    // The caller's `T` is the opaque stand-in of the definition-time pass, so this is the call the
    // abstract walk makes as well as the one each instantiation makes.
    "an unbounded parameter passes straight through" in {
      val src =
        """struct Box[T]
          |    v: T
          |    of(x: T) -> Box[T] = Box(x)
          |wrap[T](x: T) -> Box[T] = Box.of(x)
          |print(wrap(3).v, wrap("s").v)""".stripMargin

      run(src) shouldBe "3 s\n"
    }

    "and a bounded one satisfies the same bound at the type" in {
      val src =
        """trait Rank
          |    rank(self) -> int
          |struct P
          |    n: int
          |impl Rank for P
          |    rank(self) -> int = self.n
          |struct Scored[T: Rank]
          |    n: int
          |    of(x: T) -> Scored[T] = Scored(x.rank())
          |keep[T: Rank](x: T) -> Scored[T] = Scored.of(x)
          |print(keep(P(4)).n)""".stripMargin

      run(src) shouldBe "4\n"
    }

    // Reached through `Self` from inside the type's own body, which is the spelling a member writes
    // when it wants a sibling associated function without repeating the type's name.
    "and a member reaches one through Self, at its own instantiation" in {
      val src =
        """struct Box[T]
          |    v: T
          |    of(x: T) -> Box[T] = Box(x)
          |    twin(self) -> Box[T] = Self.of(self.v)
          |print(Box(7).twin().v, Box("s").twin().v)""".stripMargin

      run(src) shouldBe "7 s\n"
    }
  }

  "it is an ordinary member in every other respect" - {

    "an enum declares one beside its variants" in {
      val src =
        """enum Maybe[T]
          |    Nothing
          |    Just(v: T)
          |    of(x: T) -> Maybe[T] = Just(x)
          |    or(self, fallback: T) -> T = self match
          |        Just(v) -> v
          |        Nothing -> fallback
          |print(Maybe.of(3).or(0))""".stripMargin

      run(src) shouldBe "3\n"
    }

    "a generic 'impl' block supplies one for the trait that asked" in {
      val src =
        """trait Make
          |    made(x: int) -> Self
          |struct Box[T]
          |    v: T
          |impl[T] Make for Box[T]
          |    made(x: int) -> Self = Box(x)
          |var b: Box[int] = Box.made(6)
          |print(b.v)""".stripMargin

      run(src) shouldBe "6\n"
    }

    "one associated function calls another of the same type" in {
      val src =
        """struct Box[T]
          |    v: T
          |    of(x: T) -> Box[T] = Box(x)
          |    twice(x: T) -> Box[Box[T]] = Box.of(Box.of(x))
          |print(Box.twice(5).v.v)""".stripMargin

      run(src) shouldBe "5\n"
    }

    "one recurses at the instantiation it was called at" in {
      val src =
        """struct Box[T]
          |    v: T
          |    n: int
          |    down(x: T, k: int) -> Box[T] = if k <= 0 then Box(x, 0) else Box.down(x, k - 1)
          |print(Box.down("z", 4).n)""".stripMargin

      run(src) shouldBe "0\n"
    }

    "one may hand back a reference, which the call boxes as any other result would be" in {
      val src =
        """struct Box[T]
          |    v: T
          |    of(x: T) -> &Box[T] = Box(x)
          |var b = Box.of(8)
          |print(b.v)""".stripMargin

      run(src) shouldBe "8\n"
    }

    "a counted payload is retained and released like any other" in {
      val src =
        """struct Box[T]
          |    v: T
          |    of(x: T) -> Box[T] = Box(x)
          |    get(self) -> T = self.v
          |var n = 0usize
          |for i in 0..<1000
          |    n += Box.of("xy").get().len
          |print(n)""".stripMargin

      run(src) shouldBe "2000\n"
    }
  }

  "each instantiation is its own function" - {

    "two element types give two definitions of the one associated function" in {
      val out = Compiler.compileToLlvm(
        """struct Box[T]
          |    v: T
          |    of(x: T) -> Box[T] = Box(x)
          |print(Box.of(1).v)
          |print(Box.of(2.5).v)""".stripMargin,
      )

      out.map(_.linesIterator.count(l => l.startsWith("define") && l.contains("@Box.of."))) shouldBe Right(2)
    }

    "and two calls at one element type give one" in {
      val out = Compiler.compileToLlvm(
        """struct Box[T]
          |    v: T
          |    of(x: T) -> Box[T] = Box(x)
          |print(Box.of(1).v)
          |print(Box.of(2).v)""".stripMargin,
      )

      out.map(_.linesIterator.count(l => l.startsWith("define") && l.contains("@Box.of."))) shouldBe Right(1)
    }
  }
}
