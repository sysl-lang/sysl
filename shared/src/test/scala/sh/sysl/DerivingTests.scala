package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** The `deriving` clause — `struct Size deriving Eq, Ord, Hash, Display` (`reference/traits.md`).
  *
  * What the clause promises is that the compiler writes the block a person would have written out
  * field by field, so the suite asserts against the **behaviour** of the four traits rather than
  * against the shape of what was synthesised: that equality is field-by-field, that ordering is
  * lexicographic in declaration order, that the hash depends on which field holds which value, and
  * that a value renders as its own name and its fields.
  *
  * The second half is the clause's own refusals, which are about the words in front of the reader
  * rather than about the type's fields, and are raised before any block is built.
  */
class DerivingTests extends AnyFreeSpec with RunSupport with CodegenSupport {

  private val sized =
    """struct Size deriving Eq, Ord, Hash, Display
      |    w: int
      |    h: int
      |end Size
      |
      |""".stripMargin

  private val shape =
    """enum Shape deriving Eq, Ord, Hash, Display
      |    Circle(r: int)
      |    Rect(w: int, h: int)
      |    Empty
      |end Shape
      |
      |""".stripMargin

  "a struct" - {

    "equality is field by field" in {
      run(sized + """print(Size(3, 4) == Size(3, 4))
                   |print(Size(3, 4) == Size(3, 5))
                   |print(Size(3, 4) != Size(4, 4))
                   |""".stripMargin) shouldBe "true\nfalse\ntrue\n"
    }

    // The first field decides, and the second is only reached where the first agrees — which is what
    // makes this lexicographic rather than four independent comparisons.
    "ordering is lexicographic, first field first" in {
      run(sized + """print(Size(3, 4) < Size(3, 5))
                   |print(Size(3, 9) < Size(4, 0))
                   |print(Size(3, 4) < Size(3, 4))
                   |print(Size(4, 0) < Size(3, 9))
                   |""".stripMargin) shouldBe "true\ntrue\nfalse\nfalse\n"
    }

    "the derived comparisons reach '>' and '<=' too, which Ord derives from 'lt'" in {
      run(sized + """print(Size(4, 0) > Size(3, 9))
                   |print(Size(3, 4) <= Size(3, 4))
                   |""".stripMargin) shouldBe "true\ntrue\n"
    }

    "equal values hash equal" in {
      run(sized + """print(Size(3, 4).hash() == Size(3, 4).hash())
                   |""".stripMargin) shouldBe "true\n"
    }

    // A plain XOR of the fields would make these one key. The FNV prime between them is what carries
    // the position, exactly as it does for a tuple.
    "which field holds which value changes the hash" in {
      run(sized + """print(Size(3, 4).hash() == Size(4, 3).hash())
                   |""".stripMargin) shouldBe "false\n"
    }

    "a value renders as its own name and its fields" in {
      run(sized + """print(Size(3, 4))
                   |""".stripMargin) shouldBe "Size(3, 4)\n"
    }

    // `14 §2`'s rule is that a specifier describes the field the *whole* value occupies, so a width
    // pads the rendering rather than its first field.
    "a width pads the whole rendering" in {
      run(sized + """val s = Size(3, 4)
                   |
                   |print(f"[${s}%12s]")
                   |""".stripMargin) shouldBe "[  Size(3, 4)]\n"
    }

    "one field" in {
      run("""struct Wrap deriving Eq, Ord, Display
            |    v: int
            |end Wrap
            |
            |print(Wrap(1) == Wrap(1), Wrap(1) < Wrap(2), Wrap(7))
            |""".stripMargin) shouldBe "true true Wrap(7)\n"
    }

    // A struct with no fields is one value, so it equals itself, is not less than itself, and has
    // nothing to put in brackets.
    "no fields at all" in {
      run("""struct Unit deriving Eq, Ord, Hash, Display
            |end Unit
            |
            |print(Unit() == Unit(), Unit() < Unit(), Unit())
            |""".stripMargin) shouldBe "true false Unit\n"
    }

    "a field whose own type derives" in {
      run("""struct Point deriving Eq, Display
            |    x: int
            |    y: int
            |end Point
            |
            |struct Line deriving Eq, Display
            |    a: Point
            |    b: Point
            |end Line
            |
            |print(Line(Point(1, 2), Point(3, 4)))
            |print(Line(Point(1, 2), Point(3, 4)) == Line(Point(1, 2), Point(3, 4)))
            |print(Line(Point(1, 2), Point(3, 4)) == Line(Point(1, 2), Point(3, 5)))
            |""".stripMargin) shouldBe "Line(Point(1, 2), Point(3, 4))\ntrue\nfalse\n"
    }

    "a string field" in {
      run("""struct Named deriving Eq, Ord, Display
            |    name: string
            |    n: int
            |end Named
            |
            |print(Named("a", 1) < Named("b", 0), Named("a", 1))
            |""".stripMargin) shouldBe "true Named(a, 1)\n"
    }

    "a derived membership satisfies a bound" in {
      run("""struct Size deriving Eq
            |    w: int
            |end Size
            |
            |same[T: Eq](a: T, b: T) -> bool = a == b
            |
            |print(same(Size(1), Size(1)))
            |""".stripMargin) shouldBe "true\n"
    }
  }

  "a generic type derives conditionally" - {

    "the parts have the trait, so the whole does" in {
      run("""struct Box[T] deriving Eq, Display
            |    v: T
            |end Box
            |
            |print(Box(1) == Box(1), Box(1) == Box(2), Box("x"))
            |""".stripMargin) shouldBe "true false Box(x)\n"
    }

    // The bound is on the block, so an instantiation whose argument lacks the trait simply is not a
    // member — and says so where the membership is asked for, not where the type was declared.
    "an argument without the trait leaves that instantiation out" in {
      val src =
        """struct Opaque
          |    v: int
          |end Opaque
          |
          |struct Box[T] deriving Eq
          |    v: T
          |end Box
          |
          |print(Box(Opaque(1)) == Box(Opaque(1)))
          |""".stripMargin

      err(src) should include("Eq")
    }

    "the type's own bounds are kept" in {
      run("""struct Sorted[T: Ord] deriving Eq
            |    v: T
            |end Sorted
            |
            |print(Sorted(1) == Sorted(1))
            |""".stripMargin) shouldBe "true\n"
    }
  }

  "a simple enum" - {

    // Every variant is dataless, so `Ord` is the discriminants' order — and the discriminants are in
    // declaration order unless the enum said otherwise.
    "orders by declaration order" in {
      run("""enum Colour deriving Ord
            |    Red
            |    Green
            |    Blue
            |end Colour
            |
            |print(Red < Green, Blue < Green, Red < Red)
            |""".stripMargin) shouldBe "true false false\n"
    }

    "an explicit discriminant is what is ordered" in {
      run("""enum Level deriving Ord
            |    Low = 10
            |    High = 2
            |end Level
            |
            |print(Low < High)
            |""".stripMargin) shouldBe "false\n"
    }

    "renders as the variant's name" in {
      run("""enum Colour deriving Display
            |    Red
            |    Green
            |end Colour
            |
            |print(Red, Green)
            |""".stripMargin) shouldBe "Red Green\n"
    }

    "hashes, and two variants differ" in {
      run("""enum Colour deriving Hash
            |    Red
            |    Green
            |end Colour
            |
            |print(Red.hash() == Red.hash(), Red.hash() == Green.hash())
            |""".stripMargin) shouldBe "true false\n"
    }

    // `Eq` is the one trait a simple enum already has, by the rule that its value *is* its
    // discriminant — so the clause is told to drop it rather than the block being refused later.
    "'Eq' is refused, at the word the reader wrote" in {
      val e = err("""enum Colour deriving Eq
                    |    Red
                    |end Colour
                    |""".stripMargin)

      e should include("a simple enum is already 'Eq'")
      e should include("Remove 'Eq' from the 'deriving' clause")
    }

    "and the enum is Eq anyway, with the clause not naming it" in {
      run("""enum Colour deriving Display
            |    Red
            |    Green
            |end Colour
            |
            |print(Red == Red, Red == Green)
            |""".stripMargin) shouldBe "true false\n"
    }
  }

  "a data enum" - {

    "equality is by variant, then field by field" in {
      run(shape + """print(Circle(1) == Circle(1))
                    |print(Circle(1) == Circle(2))
                    |print(Circle(1) == Rect(1, 2))
                    |print(Rect(1, 2) == Rect(1, 2))
                    |print(Empty == Empty)
                    |print(Empty == Circle(0))
                    |""".stripMargin) shouldBe "true\nfalse\nfalse\ntrue\ntrue\nfalse\n"
    }

    // Variants first, in declaration order, and the fields only where the variants agree.
    "ordering puts the variants first, then their fields" in {
      run(shape + """print(Circle(9) < Rect(0, 0))
                    |print(Rect(0, 0) < Empty)
                    |print(Circle(1) < Circle(2))
                    |print(Rect(1, 5) < Rect(1, 6))
                    |print(Rect(2, 0) < Rect(1, 9))
                    |print(Empty < Empty)
                    |""".stripMargin) shouldBe "true\ntrue\ntrue\ntrue\nfalse\nfalse\n"
    }

    "a variant renders under its own name" in {
      run(shape + """print(Circle(2))
                    |print(Rect(3, 4))
                    |print(Empty)
                    |""".stripMargin) shouldBe "Circle(2)\nRect(3, 4)\nEmpty\n"
    }

    "equal values hash equal" in {
      run(shape + """print(Rect(1, 2).hash() == Rect(1, 2).hash())
                    |""".stripMargin) shouldBe "true\n"
    }

    // The tag is mixed before the payload, so two variants carrying equal payloads are not one key.
    "the variant is part of the hash" in {
      run("""enum Two deriving Hash
            |    A(v: int)
            |    B(v: int)
            |end Two
            |
            |print(A(1).hash() == B(1).hash())
            |""".stripMargin) shouldBe "false\n"
    }

    "one variant, so there is no pair the match does not cover" in {
      run("""enum Only deriving Eq, Ord, Display
            |    Just(v: int)
            |end Only
            |
            |print(Just(1) == Just(1), Just(1) < Just(2), Just(3))
            |""".stripMargin) shouldBe "true true Just(3)\n"
    }

    "a generic data enum derives conditionally" in {
      run("""enum Maybe[T] deriving Eq, Display
            |    Yes(v: T)
            |    No
            |end Maybe
            |
            |val a: Maybe[int] = Yes(1)
            |val b: Maybe[int] = No
            |
            |print(a == Yes(1), a == b, a, b)
            |""".stripMargin) shouldBe "true false Yes(1) No\n"
    }
  }

  "what a derived block sits beside" - {

    // The clause is part of the declaration, so the block it writes is in the module that declares
    // the type and every field is in scope there. That is the whole of why visibility never became a
    // question this feature had to answer.
    "a private field is walked, because the block is written where the type is" in {
      run("""struct P deriving Eq, Display
            |    private x: int
            |end P
            |
            |print(P(1) == P(1), P(1))
            |""".stripMargin) shouldBe "true P(1)\n"
    }

    "a type's own members are untouched by it" in {
      run("""struct P deriving Eq, Display
            |    x: int
            |
            |    twice(self) -> int = self.x * 2
            |end P
            |
            |print(P(2).twice(), P(2))
            |""".stripMargin) shouldBe "4 P(2)\n"
    }

    // A counted field is `Eq` by address, which is what `&` already means for `==` — deriving adds
    // no rule of its own about what a field's equality is.
    "a counted field compares as one does anywhere else" in {
      run("""struct Node deriving Eq
            |    v: int
            |end Node
            |
            |struct Holder deriving Eq
            |    n: &Node
            |end Holder
            |
            |val a: &Node = Node(1)
            |
            |print(Holder(a) == Holder(a))
            |""".stripMargin) shouldBe "true\n"
    }

    // A derived block reports the way a written one does, which is what carrying the synthesis in
    // source-level AST buys — so a field that cannot do the work says so in the ordinary words, at
    // the trait in the clause that asked for it.
    "a field the trait cannot be written over says so at the clause" in {
      err("""struct Opaque
            |    v: int
            |end Opaque
            |
            |struct S deriving Hash
            |    a: Opaque
            |end S
            |
            |print(S(Opaque(1)).hash())
            |""".stripMargin) should include("'Opaque' has no method 'hash'")
    }
  }

  // Two claims the reference makes that nothing else here runs.
  "claims the reference makes" - {

    // A compiler-*provided* membership has no method to put in a table, so a value holding one
    // cannot be erased (`library/sysl/display.sysl` says so of the open integer family). A derived
    // block is an ordinary block with an ordinary method, so it can — and the page says "found,
    // checked, dispatched and erased exactly as a written one is", which is this.
    "a derived implementation can be erased to a trait object" in {
      run("""struct Size deriving Display
            |    w: int
            |    h: int
            |end Size
            |
            |show(d: *Display)
            |    print(d)
            |
            |var s = Size(3, 4)
            |
            |show(&s)
            |""".stripMargin) shouldBe "Size(3, 4)\n"
    }

    // A value parameter is not a type and has no membership to ask for, so it gains no bound — which
    // is only observable on a type that has one, since a bound on a `const` would not resolve.
    "a const value parameter gains no bound" in {
      run("""struct Buf[const N: usize] deriving Eq, Display
            |    n: usize
            |end Buf
            |
            |val a: Buf[4] = Buf(1)
            |
            |print(a == Buf[4](1), a)
            |""".stripMargin) shouldBe "true Buf(1)\n"
    }
  }

  "the clause's own refusals" - {

    "a trait the compiler cannot write" in {
      err("""trait Show
            |    show(self) -> string
            |
            |struct P deriving Show
            |    x: int
            |end P
            |""".stripMargin) should include("is not a trait the compiler knows how to write")
    }

    "the four are named" in {
      err("struct P deriving Nope\n    x: int\nend P\n") should include("Eq, Ord, Hash or Display")
    }

    "trait arguments" in {
      err("struct P deriving Eq[int]\n    x: int\nend P\n") should
        include("a derived implementation takes none")
    }

    // C's incomplete type: nothing here knows its shape, so a derived block would answer `true` for
    // every pair with nothing to tell the reader it had done so.
    "an opaque struct with no fields" in {
      err("opaque struct Handle deriving Eq\n\nprint(1)\n") should
        include("opaque and declares no fields")
    }

    "the same trait twice" in {
      err("struct P deriving Eq, Eq\n    x: int\nend P\n") should include("is named twice")
    }

    // Deriving is all-or-nothing per trait: there is no writing the block and then replacing one
    // method of it, and the duplicate-implementation rule is what says so.
    "a derived block and a hand-written one for the same trait collide" in {
      err("""struct P deriving Eq
            |    x: int
            |end P
            |
            |impl Eq for P
            |    eq(self, rhs: Self) -> bool = true
            |""".stripMargin) should include("Eq")
    }
  }
}
