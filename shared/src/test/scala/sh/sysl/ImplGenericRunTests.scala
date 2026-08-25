package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** Tier-2 runtime behaviour of an `impl` written for a **generic** type (`02`): a block with type
 * parameters of its own implements the trait for every instantiation at once, and its members are
 * monomorphized per receiver exactly as a generic type's own members are.
 *
 * The two halves worth separating are that the *members* work — a call, a property, `Self`, a
 * default, a vtable slot — and that the *conformance* is conditional: `impl[T: Show] Show for
 * Box[T]` makes a `Box[int]` implement `Show` precisely when `int` does, which is a question asked
 * one step in.
 */
class ImplGenericRunTests extends AnyFreeSpec with RunSupport {

  "a member of a generic impl" - {

    "resolves on a value, once per element type" in {
      val src =
        """trait Show
          |    show(self) -> string
          |struct Box[T]
          |    v: T
          |impl[T: Display] Show for Box[T]
          |    show(self) -> string = "box(" + str(self.v) + ")"
          |var a = Box(5)
          |var b = Box("hi")
          |print(a.show(), b.show())""".stripMargin

      run(src) shouldBe "box(5) box(hi)\n"
    }

    "takes a parameter of the block's own type parameter" in {
      val src =
        """trait Plus
          |    plus(self, other: int) -> int
          |struct Box[T]
          |    v: T
          |impl[T] Plus for Box[T]
          |    plus(self, other: int) -> int = other + 1
          |print(Box("ignored").plus(41))""".stripMargin

      run(src) shouldBe "42\n"
    }

    // The receiver modes are the ones `08` gives every member, and a generic block changes none of
    // them: what the mode governs is how the instance is passed, which is settled before `T` is.
    "mutates through a pointer receiver" in {
      val src =
        """trait Bump
          |    bump(*self)
          |struct Box[T]
          |    v: T
          |    n: int
          |impl[T] Bump for Box[T]
          |    bump(*self)
          |        self.n += 1
          |var a = Box("x", 0)
          |a.bump()
          |a.bump()
          |print(a.n)""".stripMargin

      run(src) shouldBe "2\n"
    }

    "reads a property with no parentheses" in {
      val src =
        """trait Sized
          |    size -> int
          |struct Pair[A, B]
          |    a: A
          |    b: B
          |impl[X, Y] Sized for Pair[X, Y]
          |    size -> int = 2
          |print(Pair(1, "z").size)""".stripMargin

      run(src) shouldBe "2\n"
    }

    // `Self` inside a generic block is the type applied to the block's parameters, and it is not a
    // type until an instantiation says what they are — so it is resolved with them rather than
    // ahead of them.
    "writes 'Self' for the type it is being implemented for" in {
      val src =
        """trait Dup
          |    dup(self) -> Self
          |struct Box[T]
          |    v: T
          |impl[T] Dup for Box[T]
          |    dup(self) -> Self = Box(self.v)
          |var a = Box(5)
          |print(a.dup().v)""".stripMargin

      run(src) shouldBe "5\n"
    }

    "inherits a default the block did not write" in {
      val src =
        """trait Greet
          |    name(self) -> string
          |    greet(self) -> string = "hi " + self.name()
          |struct Box[T]
          |    v: T
          |impl[T: Display] Greet for Box[T]
          |    name(self) -> string = str(self.v)
          |print(Box(9).greet())""".stripMargin

      run(src) shouldBe "hi 9\n"
    }

    "belongs to a generic enum the same way" in {
      val src =
        """trait Show
          |    show(self) -> string
          |enum Maybe[T]
          |    Just(v: T)
          |    Nothing
          |impl[T: Display] Show for Maybe[T]
          |    show(self) -> string
          |        self match
          |            Just(x) -> str(x)
          |            Nothing -> "-"
          |var a: Maybe[int] = Just(4)
          |var b: Maybe[int] = Nothing
          |print(a.show(), b.show())""".stripMargin

      run(src) shouldBe "4 -\n"
    }

    // The parameters are matched to the type's arguments by *position in the subject*, not by the
    // order they were declared in, so a block may name them however it reads best.
    "binds its parameters in the order the subject applies them" in {
      val src =
        """trait Show
          |    show(self) -> string
          |struct Pair[A, B]
          |    a: A
          |    b: B
          |impl[X: Display, Y: Display] Show for Pair[Y, X]
          |    show(self) -> string = str(self.a) + "/" + str(self.b)
          |print(Pair(1, "z").show())""".stripMargin

      run(src) shouldBe "1/z\n"
    }

    "sits beside the type's own members without colliding" in {
      val src =
        """trait Show
          |    show(self) -> string
          |struct Box[T]
          |    v: T
          |    twice(self) -> int = self.n * 2
          |    n: int
          |impl[T] Show for Box[T]
          |    show(self) -> string = "b"
          |var a = Box("x", 21)
          |print(a.show(), a.twice())""".stripMargin

      run(src) shouldBe "b 42\n"
    }
  }

  // An operator resolves to the one method its trait requires (`reference/expressions.md § Operator
  // dispatch`), and on a generic type that method is an instantiation — so the operator has to name
  // it the way a call does rather than by appending the method to the type.
  "an operator implemented for a generic type" - {

    "dispatches to the instantiation the operands are" in {
      val src =
        """struct Box[T]
          |    v: T
          |    n: int
          |impl[T] Add for Box[T]
          |    add(self, rhs: Box[T]) -> Box[T] = Box(self.v, self.n + rhs.n)
          |var a = Box("x", 1)
          |var b = Box("y", 2)
          |print((a + b).n)""".stripMargin

      run(src) shouldBe "3\n"
    }

    "updates a place through the same method" in {
      val src =
        """struct Box[T]
          |    v: T
          |    n: int
          |impl[T] Add for Box[T]
          |    add(self, rhs: Box[T]) -> Box[T] = Box(self.v, self.n + rhs.n)
          |var a = Box("x", 1)
          |a += Box("y", 40)
          |print(a.n)""".stripMargin

      run(src) shouldBe "41\n"
    }

    // A comparison chain shares each middle operand between two links, and a dispatched link reads
    // the value codegen already holds — so the generic instantiation has to reach it the same way.
    "compares, and chains, through its own instantiation" in {
      val src =
        """struct Box[T]
          |    v: T
          |    n: int
          |impl[T] Eq for Box[T]
          |    eq(self, rhs: Box[T]) -> bool = self.n == rhs.n
          |var a = Box("x", 1)
          |var b = Box("y", 1)
          |var c = Box("z", 1)
          |print(a == b, a == b == c)""".stripMargin

      run(src) shouldBe "true true\n"
    }
  }

  "a bound is satisfied by a generic impl" - {

    "so a generic function accepts every instantiation" in {
      val src =
        """trait Show
          |    show(self) -> string
          |struct Box[T]
          |    v: T
          |impl[T: Display] Show for Box[T]
          |    show(self) -> string = str(self.v)
          |name[U: Show](x: U) -> string = x.show()
          |print(name(Box(7)), name(Box(true)))""".stripMargin

      run(src) shouldBe "7 true\n"
    }

    // The condition is asked one step in, so a `Box` of a `Box` conforms exactly when the inner one
    // does — which is the whole of what makes conditional conformance compose.
    "and a nested instantiation conforms through the same condition" in {
      val src =
        """trait Show
          |    show(self) -> string
          |struct Box[T]
          |    v: T
          |impl Show for int
          |    show(self) -> string = str(self)
          |impl[T: Show] Show for Box[T]
          |    show(self) -> string = "(" + self.v.show() + ")"
          |name[U: Show](x: U) -> string = x.show()
          |print(name(Box(Box(3))))""".stripMargin

      run(src) shouldBe "((3))\n"
    }

    "and a bounded body may read a property through it" in {
      val src =
        """trait Sized
          |    size -> int
          |struct Box[T]
          |    v: T
          |impl[T] Sized for Box[T]
          |    size -> int = 1
          |total[U: Sized](x: U, y: U) -> int = x.size + y.size
          |print(total(Box("a"), Box("b")))""".stripMargin

      run(src) shouldBe "2\n"
    }
  }

  // `Display` is the one trait the compiler itself reaches for, so a generic implementation of it
  // has to satisfy `print`, `str`, and an `f"…"` hole alike — each of which asks the conformance
  // question and then names the member that answers it.
  "a generic Display implementation renders" - {

    "through print, once per instantiation" in {
      val src =
        """struct Box[T]
          |    v: T
          |impl[T: Display] Display for Box[T]
          |    display(self, out: *Writer, fmt: FormatSpec)
          |        display_str("<", out, FormatSpec(0, -1, false))
          |        self.v.display(out, FormatSpec(0, -1, false))
          |        display_str(">", out, fmt)
          |print(Box(5), Box("hi"))""".stripMargin

      run(src) shouldBe "<5> <hi>\n"
    }

    "through str and an f-string hole, which hands the specifier on" in {
      val src =
        """struct Box[T]
          |    v: T
          |impl[T: Display] Display for Box[T]
          |    display(self, out: *Writer, fmt: FormatSpec)
          |        self.v.display(out, fmt)
          |var b = Box(7)
          |print(str(b), f"[${b}%4s]")""".stripMargin

      run(src) shouldBe "7 [   7]\n"
    }
  }

  "erasing an instantiation" - {

    "dispatches through the table its own instantiation filled" in {
      val src =
        """trait Show
          |    show(self) -> string
          |struct Box[T]
          |    v: T
          |impl[T: Display] Show for Box[T]
          |    show(self) -> string = str(self.v)
          |name(s: &Show) -> string = s.show()
          |var a: &Show = Box(7)
          |var b: &Show = Box("hi")
          |print(name(a), name(b))""".stripMargin

      run(src) shouldBe "7 hi\n"
    }

    // Two instantiations of one generic type are two types, so they get two tables — which is what
    // lets them sit in the one array and still render as themselves.
    "puts two instantiations behind one trait object type" in {
      val src =
        """trait Show
          |    show(self) -> string
          |struct Box[T]
          |    v: T
          |impl[T: Display] Show for Box[T]
          |    show(self) -> string = str(self.v)
          |var xs: [2]&Show = [Box(1), Box(false)]
          |print(xs[0].show(), xs[1].show())""".stripMargin

      run(src) shouldBe "1 false\n"
    }
  }

  // A member of a generic `impl` is an ordinary function once instantiated, so what it does with a
  // reference-counted payload is the ordinary ARC path — worth running in a loop, where a leak or a
  // double free is what a wrong count would show up as.
  "an instantiation over a counted payload neither leaks nor frees twice" in {
    val src =
      """trait Show
        |    show(self) -> string
        |struct Box[T]
        |    v: T
        |impl[T: Display] Show for Box[T]
        |    show(self) -> string = str(self.v) + "!"
        |var n = 0usize
        |for i in 0..<1000
        |    var b = Box("x")
        |    n += b.show().len
        |print(n)""".stripMargin

    run(src) shouldBe "2000\n"
  }
}
