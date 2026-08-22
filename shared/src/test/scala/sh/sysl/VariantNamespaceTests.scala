package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** `09 §3` — **a variant belongs to its enum rather than to the module it was declared in.**
 *
 * Two enums may each name a variant `Circle`, and what a bare `Circle(3)` means is settled by the
 * type expected where it is written. Where nothing expects a type, the qualified `Shape.Circle`
 * spelling says which — the same dot-qualified form a pattern has always been able to carry.
 *
 * Until this landed, a variant name was unique across the whole module: the second enum to name a
 * `Circle` was refused at its declaration, and a module accumulating enums ran out of the ordinary
 * words. `Failed`, `Done`, `Empty` and `Invalid` were each usable once.
 */
class VariantNamespaceTests extends AnyFreeSpec with RunSupport with CodegenSupport {

  /** Two enums with a variant name in common, and a second name apiece so that neither is reachable
   * only through the shared one.
   */
  private val two =
    """enum Shape
      |    Circle(r: int)
      |    Square(side: int)
      |enum Hole
      |    Circle(r: int)
      |    Slot(len: int)
      |""".stripMargin

  /** A struct and an enum variant of one name, which is what a call in a module declaring both has
   * to choose between (card `0220`).
   */
  private val both =
    """struct Segment
      |    a: int
      |    b: int
      |enum Kind
      |    Circle
      |    Segment
      |""".stripMargin

  "two enums in one module may share a variant name" - {
    "which was refused outright before" in {
      ir(two + "val s: Shape = Circle(1)\nprint(1)") should include("%enum.Shape")
    }

    "and each keeps its own storage" in {
      val out = ir(two + "val s: Shape = Circle(1)\nval h: Hole = Circle(2)\nprint(1)")

      out should include("%enum.Shape = type")
      out should include("%enum.Hole = type")
    }
  }

  "the expected type says which enum a bare name means" - {
    "at an annotated binding" in {
      run(two +
        """area(s: Shape) -> int
          |    s match
          |        Circle(r) -> r * 3
          |        Square(side) -> side * side
          |depth(h: Hole) -> int
          |    h match
          |        Circle(r) -> r + 100
          |        Slot(len) -> len + 200
          |val s: Shape = Circle(2)
          |val h: Hole = Circle(2)
          |print(area(s))
          |print(depth(h))
          |""".stripMargin) shouldBe "6\n102\n"
    }

    "at an argument, which supplies one without anything being annotated" in {
      run(two +
        """depth(h: Hole) -> int
          |    h match
          |        Circle(r) -> r + 100
          |        Slot(len) -> len + 200
          |print(depth(Circle(5)))
          |""".stripMargin) shouldBe "105\n"
    }

    "at a return, for a nullary variant" in {
      run("""enum Link
            |    Down
            |    Up
            |enum Door
            |    Down
            |    Open
            |state() -> Link = Down
            |name(l: Link) -> string
            |    l match
            |        Down -> "down"
            |        Up -> "up"
            |print(name(state()))
            |""".stripMargin) shouldBe "down\n"
    }
  }

  "a qualified name says which, where nothing else does" - {
    "carrying data" in {
      run(two +
        """area(s: Shape) -> int
          |    s match
          |        Circle(r) -> r * 3
          |        Square(side) -> side * side
          |var s = Shape.Circle(4)
          |print(area(s))
          |""".stripMargin) shouldBe "12\n"
    }

    "and nullary" in {
      run("""enum Link
            |    Down
            |    Up
            |enum Door
            |    Down
            |    Open
            |name(l: Link) -> string
            |    l match
            |        Down -> "down"
            |        Up -> "up"
            |var l = Link.Down
            |print(name(l))
            |""".stripMargin) shouldBe "down\n"
    }

    "picking the other one, from the same line written twice" in {
      run(two +
        """area(s: Shape) -> int
          |    s match
          |        Circle(r) -> r * 3
          |        Square(side) -> side * side
          |depth(h: Hole) -> int
          |    h match
          |        Circle(r) -> r + 100
          |        Slot(len) -> len + 200
          |print(area(Shape.Circle(2)))
          |print(depth(Hole.Circle(2)))
          |""".stripMargin) shouldBe "6\n102\n"
    }
  }

  "a bare name with two answers and nothing to choose by is refused" - {
    "naming both enums" in {
      val out = err(two + "var s = Circle(1)\nprint(1)")

      out should include("'Circle' is a variant of 'Shape' and 'Hole'")
    }

    "and showing the qualified form that fixes it" in {
      err(two + "var s = Circle(1)\nprint(1)") should include("qualify it, as 'Shape.Circle'")
    }

    "for a nullary variant too" in {
      err("""enum Link
            |    Down
            |    Up
            |enum Door
            |    Down
            |    Open
            |var l = Down
            |print(1)
            |""".stripMargin) should include("'Down' is a variant of 'Link' and 'Door'")
    }

    "listing all three where three enums answer" in {
      val out = err("""enum A
                      |    X
                      |    P
                      |enum B
                      |    X
                      |    Q
                      |enum C
                      |    X
                      |    R
                      |var v = X
                      |print(1)
                      |""".stripMargin)

      out should include("'X' is a variant of 'A', 'B' and 'C'")
    }
  }

  "one enum answering is not an ambiguity" in {
    err("enum Shape\n    Circle(r: int)\n    Square(s: int)\nvar s = Circle(1, 2)\nprint(1)") should
      include("has 1 field, but 2 values were given")
  }

  "a pattern is unaffected, because the scrutinee already says which enum" - {
    "bare, on either of them" in {
      run(two +
        """area(s: Shape) -> int
          |    s match
          |        Circle(r) -> r * 3
          |        Square(side) -> side * side
          |depth(h: Hole) -> int
          |    h match
          |        Circle(r) -> r + 100
          |        Slot(len) -> len + 200
          |print(area(Shape.Circle(1)) + depth(Hole.Circle(1)))
          |""".stripMargin) shouldBe "104\n"
    }

    "qualified, which the dotted form has always allowed" in {
      run(two +
        """area(s: Shape) -> int
          |    s match
          |        Shape.Circle(r) -> r * 3
          |        Shape.Square(side) -> side * side
          |print(area(Shape.Circle(3)))
          |""".stripMargin) shouldBe "9\n"
    }
  }

  // The asymmetry is the rule in one line: two variants of a name are told apart by the enum they
  // belong to, and a variant and a constant have nothing to be told apart *by*.
  "a variant still clashes with anything that is not a variant" - {
    "a constant declared after it" in {
      err("enum Colour\n    Red\nend Colour\nconst Red: int = 1") should
        include("'Red' is already used by enum 'Colour'")
    }

    "and one declared before it, which the enum reports" in {
      err("const Red: int = 1\nenum Colour\n    Red\nend Colour") should
        include("variant name 'Red' is already used by a constant")
    }

    "while a second enum in the same position is accepted" in {
      ir("enum Colour\n    Red\nend Colour\nenum Wine\n    Red\nend Wine\nprint(1)") should
        include("define i32 @main")
    }
  }

  "visibility follows the widest enum that offers the name" - {
    "so a public enum's variant survives a private one naming it too" in {
      runIn(
        ("shapes", "shapes.sysl",
          """module shapes
            |private enum Hidden
            |    Circle(r: int)
            |    Slot(len: int)
            |enum Shape
            |    Circle(r: int)
            |    Square(side: int)
            |area(s: Shape) -> int
            |    s match
            |        Circle(r) -> r * 3
            |        Square(side) -> side * side
            |""".stripMargin),
        ("", "main.sysl",
          """import shapes.*
            |print(area(Circle(2)))
            |""".stripMargin)) shouldBe "6\n"
    }

    "and the private one is not a candidate from outside, so the name is not ambiguous there" in {
      runIn(
        ("shapes", "shapes.sysl",
          """module shapes
            |private enum Hidden
            |    Circle(r: int)
            |    Slot(len: int)
            |enum Shape
            |    Circle(r: int)
            |    Square(side: int)
            |""".stripMargin),
        ("", "main.sysl",
          """import shapes.*
            |var s = Circle(7)
            |
            |s match
            |    Circle(r) -> print(r)
            |    Square(side) -> print(side)
            |""".stripMargin)) shouldBe "7\n"
    }
  }

  "generic enums are no different, since the expected type carries its arguments" - {
    "two of them sharing a variant name" in {
      run("""enum Box[T]
            |    Wrap(v: T)
            |    Bare
            |enum Sack[T]
            |    Wrap(v: T)
            |    Loose
            |unbox(b: Box[int]) -> int
            |    b match
            |        Wrap(v) -> v
            |        Bare -> 0
            |val b: Box[int] = Wrap(9)
            |print(unbox(b))
            |""".stripMargin) shouldBe "9\n"
    }

    "and qualified, where nothing expects one" in {
      run("""enum Box[T]
            |    Wrap(v: T)
            |    Bare
            |enum Sack[T]
            |    Wrap(v: T)
            |    Loose
            |unbox(b: Box[int]) -> int
            |    b match
            |        Wrap(v) -> v
            |        Bare -> 0
            |var b = Box.Wrap(4)
            |print(unbox(b))
            |""".stripMargin) shouldBe "4\n"
    }
  }

  "a shared name whose two variants differ in shape is still told apart by the expected type" in {
    run("""enum Reply
          |    Done(code: int)
          |    Waiting
          |enum Job
          |    Done
          |    Running(pid: int)
          |say(r: Reply) -> int
          |    r match
          |        Done(code) -> code
          |        Waiting -> -1
          |ran(j: Job) -> int
          |    j match
          |        Done -> 100
          |        Running(pid) -> pid
          |val r: Reply = Done(7)
          |val j: Job = Done
          |print(say(r))
          |print(ran(j))
          |""".stripMargin) shouldBe "7\n100\n"
  }

  // `10 §9` — a simple enum's variant stands for its tag where a value parameter's declared type is
  // that enum. The tag is read off the *instantiated* enum, so it has to reach the right one of two.
  "a simple enum's variant as a value argument picks the right enum" in {
    run("""enum Level
          |    Low
          |    High
          |enum Tide
          |    Low
          |    Slack
          |struct Gauge[const L: Level]
          |    reading: int
          |at(g: Gauge[High]) -> int = g.reading
          |val g: Gauge[High] = Gauge(3)
          |print(at(g))
          |""".stripMargin) shouldBe "3\n"
  }

  // A program's own declaration outranks the library's, which is what makes this legal at all — and
  // it was legal before, since `Option` is another module. Kept because a reader meeting the new
  // rule will ask whether it changed.
  "a program may name a variant the library also names" in {
    run("""enum Maybe
          |    Some(v: int)
          |    Nothing
          |unwrap(m: Maybe) -> int
          |    m match
          |        Some(v) -> v
          |        Nothing -> 0
          |val m: Maybe = Some(5)
          |print(unwrap(m))
          |""".stripMargin) shouldBe "5\n"
  }

  "a variant clashes with a module 'var' as it does with a constant" in {
    errIn(
      ("shapes", "shapes.sysl",
        """module shapes
          |var Red: int = 1
          |enum Colour
          |    Red
          |end Colour
          |""".stripMargin),
      ("", "main.sysl", "print(1)\n")) should include("variant name 'Red' is already used by a module 'var'")
  }

  // Card `0220`. A variant is a **value** name and a struct is a **type** name, so one module may
  // declare both — and only a *call* has to choose between them. It used to choose the variant
  // outright, which left the struct impossible to construct by any spelling at all: found writing
  // `box2d`, whose `ShapeKind` names five of the shapes the package also declares as structs.
  //
  // The struct wins where the expected type does not name the variant's enum, and the asymmetry is
  // the argument rather than a preference: a variant keeps `Enum.Variant`, and a struct constructor
  // has no second spelling.
  "a struct and a variant of one name are told apart in call position" - {
    "the struct is constructed by its own name, which was refused outright" in {
      run(both + "seg(x: int, y: int) -> Segment = Segment(x, y)\nprint(seg(3, 4).b)\n") shouldBe "4\n"
    }

    "with nothing expected at all, since the variant still has a qualified spelling" in {
      run(both + "var s = Segment(1, 2)\nprint(s.a)\n") shouldBe "1\n"
    }

    "while the variant wins wherever the expected type names its enum" in {
      run(both +
        """name(k: Kind) -> string
          |    k match
          |        Circle -> "circle"
          |        Segment -> "segment"
          |val k: Kind = Segment
          |print(name(k))
          |""".stripMargin) shouldBe "segment\n"
    }

    "and at an argument, which supplies the expected type without an annotation" in {
      run(both +
        """name(k: Kind) -> string
          |    k match
          |        Circle -> "circle"
          |        Segment -> "segment"
          |print(name(Segment))
          |""".stripMargin) shouldBe "segment\n"
    }

    "the qualified spelling reaches the variant from a call position too" in {
      run(both +
        """name(k: Kind) -> string
          |    k match
          |        Circle -> "circle"
          |        Segment -> "segment"
          |var k = Kind.Segment
          |print(name(k))
          |""".stripMargin) shouldBe "segment\n"
    }

    "and a struct with no same-named variant is unaffected" in {
      run("struct Point\n    x: int\n    y: int\nvar p = Point(1, 2)\nprint(p.y)\n") shouldBe "2\n"
    }

    // The shape `box2d` actually has: the colliding name is a **transparent alias** for a struct
    // declared elsewhere, not a struct declared here. The question is asked through `followAlias`
    // exactly as `typeKey` asks it, so the two cannot disagree about which arm claims the call.
    "and the struct may be reached through an alias, which is the case this came from" in {
      run("""struct RawSeg
            |    a: int
            |    b: int
            |type Segment = RawSeg
            |enum Kind
            |    Circle
            |    Segment
            |seg(x: int, y: int) -> Segment = Segment(x, y)
            |print(seg(3, 4).b)
            |""".stripMargin) shouldBe "4\n"
    }

    // The question "is there a struct of this name?" is the compiler's own, so it is asked
    // quietly: `typeKey` would report the restriction instead of answering, and a variant call
    // would be refused for naming a struct the program cannot see and did not write.
    "a struct of the same name that is out of reach is not a candidate, and does not refuse" in {
      runIn(
        ("shapes", "shapes.sysl",
          """module shapes
            |private struct Segment
            |    a: int
            |    b: int
            |enum Kind
            |    Circle
            |    Segment
            |name(k: Kind) -> string
            |    k match
            |        Circle -> "circle"
            |        Segment -> "segment"
            |""".stripMargin),
        ("", "main.sysl",
          """import shapes.*
            |print(name(Segment))
            |""".stripMargin)) shouldBe "segment\n"
    }

    // …and the quiet ask still searches the imports, so one that *is* in reach still wins.
    "while one that is in reach wins, from another module" in {
      runIn(
        ("shapes", "shapes.sysl",
          """module shapes
            |struct Segment
            |    a: int
            |    b: int
            |enum Kind
            |    Circle
            |    Segment
            |""".stripMargin),
        ("", "main.sysl",
          """import shapes.*
            |var s = Segment(3, 4)
            |print(s.b)
            |""".stripMargin)) shouldBe "4\n"
    }
  }

  // The advice used to be "write it as 'Segment'", printed under a line already reading
  // `Segment(x, y)` — the spelling it had just refused. Card `0220`'s second half.
  "a nullary variant given arguments is told to drop the parentheses" - {
    "at a construction" in {
      val message = err("""enum Kind
                          |    Circle
                          |    Flat
                          |val k: Kind = Circle(1)
                          |print(1)
                          |""".stripMargin)

      message should include("'Circle' carries nothing, so it is written as a name on its own")
      message should include("drop the parentheses and the 1 argument inside them")
    }

    "and at a pattern" in {
      val message = err("""enum Kind
                          |    Circle
                          |    Flat
                          |val k: Kind = Circle
                          |
                          |k match
                          |    Circle(r) -> print(r)
                          |    Flat -> print(0)
                          |""".stripMargin)

      message should include("'Circle' carries nothing, so it is matched as a name on its own")
      message should include("drop the parentheses and the 1 sub-pattern inside them")
    }
  }

  "the rule crosses a module boundary intact" in {
    runIn(
      ("shapes", "shapes.sysl",
        """module shapes
          |enum Shape
          |    Circle(r: int)
          |    Square(side: int)
          |enum Hole
          |    Circle(r: int)
          |    Slot(len: int)
          |""".stripMargin),
      ("", "main.sysl",
        """import shapes.*
          |val s: Shape = Circle(1)
          |val h: Hole = Circle(2)
          |
          |s match
          |    Circle(r) -> print(r)
          |    Square(x) -> print(x)
          |
          |h match
          |    Circle(r) -> print(r)
          |    Slot(l) -> print(l)
          |""".stripMargin)) shouldBe "1\n2\n"
  }
}
