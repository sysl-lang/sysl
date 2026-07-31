package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** `private` and `private[M]` — how far a declaration may be named from (`13 §2`).
 *
 * Public is the unmarked default, so every case here is about what a modifier takes away. The two
 * levels are one keyword and its argument: a bare `private` is the **file**, and `private[M]`
 * widens to a module the declaration is already inside and everything beneath it.
 */
class VisibilityTests extends AnyFreeSpec with CodegenSupport with RunSupport with ParseSupport {

  // A module in two files, one of which keeps a helper to itself. Every case below differs only in
  // where it tries to name `scale` from.
  private val hidden =
    ("geom", "g.sysl",
     """module geom
       |private scale(n: int) -> int = n * 2
       |twice(n: int) -> int = scale(n)
       |""".stripMargin)

  "the modifiers parse" - {
    "a bare 'private' is the file" in {
      prog("private f() -> int = 1") shouldBe
        List(FuncDecl("f", Nil, Nil, Some(NamedType("int")), List(ExprStmt(i(1))), vis = Visibility.File))
    }

    "'private[M]' carries the name as written" in {
      prog("private[geom] f() -> int = 1") shouldBe
        List(FuncDecl("f", Nil, Nil, Some(NamedType("int")), List(ExprStmt(i(1))), vis = Visibility.Scoped("geom")))
    }

    "an unmarked declaration is public" in {
      prog("f() -> int = 1") shouldBe
        List(FuncDecl("f", Nil, Nil, Some(NamedType("int")), List(ExprStmt(i(1)))))
    }

    "a struct takes one" in {
      prog("private struct P\n    x: int\nend P") shouldBe
        List(StructDecl("P", Nil, List(Param("x", NamedType("int"))), vis = Visibility.File))
    }

    "an enum takes one" in {
      prog("private[a] enum E\n    A\nend E") shouldBe
        List(EnumDecl("E", Nil, None, List(EnumVariantDecl("A", None, Nil)), vis = Visibility.Scoped("a")))
    }

    "a trait takes one" in {
      prog("private trait T\n    show(self) -> int\nend T") shouldBe
        List(TraitDecl("T", Nil,
          List(MethodDecl("show", Some(RecvMode.ByValue), false, Nil, Nil, Some(NamedType("int")), Nil)),
          vis = Visibility.File))
    }

    "an extern takes one" in {
      prog("private extern abs(n: int) -> int") shouldBe
        List(ExternDecl("abs", List(Param("n", NamedType("int"))), Some(NamedType("int")), vis = Visibility.File))
    }

    // The argument is a simple name, not a path: a visibility scope is always an enclosing module,
    // and there is no way to name an unrelated one (`13 §2`).
    "but the scope argument is one segment, not a path" in {
      progError("private[a.b] f() -> int = 1") should include("']'")
    }
  }

  "a bare 'private' is the file that declares it" - {
    "which may use it freely" in {
      runIn(("", "main.sysl", "print(geom.twice(21))"), hidden) shouldBe "42\n"
    }

    "a sibling file of its own module may not" in {
      errIn(
        ("", "main.sysl", "print(1)"),
        hidden,
        ("geom", "h.sysl", "module geom\nquad(n: int) -> int = scale(scale(n))"),
      ) should include("'geom.scale' is private to 'g.sysl', the file that declares it")
    }

    "another module may not name it in full" in {
      errIn(("", "main.sysl", "print(geom.scale(21))"), hidden) should
        include("'geom.scale' is private to 'g.sysl', the file that declares it")
    }

    "and importing it is refused at the import" in {
      errIn(("", "main.sysl", "import geom.scale\nprint(1)"), hidden) should
        include("'geom.scale' is private to 'g.sysl', the file that declares it")
    }

    "including a selector list, which points at the selector" in {
      errIn(("", "main.sysl", "import geom.{twice, scale}\nprint(1)"), hidden) should
        include("'geom.scale' is private to 'g.sysl'")
    }

    // A wildcard offers what it can see, so a private helper is not among what it brings in — which
    // is a different answer from being told the import cannot have it.
    "a wildcard does not offer it at all" in {
      errIn(("", "main.sysl", "import geom.*\nprint(scale(21))"), hidden) should
        include("undefined function 'scale'")
    }

    "so it cannot make a name from a second wildcard ambiguous" in {
      runIn(
        ("", "main.sysl", "import geom.*\nimport text.*\nprint(scale(21))"),
        hidden,
        ("text", "t.sysl", "module text\nscale(n: int) -> int = n + 1"),
      ) shouldBe "22\n"
    }
  }

  "a file is not a namespace, only a visibility level" - {
    // `13 §8`: the file is a contribution to its module, not a unit of its own. So a private
    // declaration still spends its name in the module it belongs to.
    "a private declaration still collides with a sibling file's public one" in {
      errIn(
        ("", "main.sysl", "print(1)"),
        ("geom", "g.sysl", "module geom\nprivate scale(n: int) -> int = n * 2"),
        ("geom", "h.sysl", "module geom\nscale(n: int) -> int = n + 1"),
      ) should include("function 'scale' is already declared")
    }

    "nor with a second file's private one, for the same reason" in {
      errIn(
        ("", "main.sysl", "print(1)"),
        ("geom", "g.sysl", "module geom\nprivate scale(n: int) -> int = n * 2"),
        ("geom", "h.sysl", "module geom\nprivate scale(n: int) -> int = n + 1"),
      ) should include("function 'scale' is already declared")
    }

    "and two modules may each keep one of the same name" in {
      runIn(
        ("", "main.sysl", "print(geom.twice(10) + text.twice(10))"),
        ("geom", "g.sysl", "module geom\nprivate scale(n: int) -> int = n * 2\ntwice(n: int) -> int = scale(n)"),
        ("text", "t.sysl", "module text\nprivate scale(n: int) -> int = n * 3\ntwice(n: int) -> int = scale(n)"),
      ) shouldBe "50\n"
    }
  }

  "'private[M]' widens to a module and its subtree" - {
    "every file of the declaring module may use it" in {
      runIn(
        ("", "main.sysl", "print(geom.quad(5))"),
        ("geom", "g.sysl", "module geom\nprivate[geom] scale(n: int) -> int = n * 2"),
        ("geom", "h.sysl", "module geom\nquad(n: int) -> int = scale(scale(n))"),
      ) shouldBe "20\n"
    }

    "but a module outside it may not" in {
      errIn(
        ("", "main.sysl", "print(geom.scale(5))"),
        ("geom", "g.sysl", "module geom\nprivate[geom] scale(n: int) -> int = n * 2"),
      ) should include("'geom.scale' is private to module 'geom'")
    }

    "an ancestor reaches every module beneath it" in {
      runIn(
        ("", "main.sysl", "print(oskit.mm.reserve(3))"),
        ("oskit.arch", "cpu.sysl", "module oskit.arch\nprivate[oskit] frames(n: int) -> int = n * 4"),
        ("oskit.mm", "mm.sysl", "module oskit.mm\nreserve(n: int) -> int = oskit.arch.frames(n)"),
      ) shouldBe "12\n"
    }

    "and the ancestor itself" in {
      runIn(
        ("", "main.sysl", "print(oskit.pages(3))"),
        ("oskit.arch", "cpu.sysl", "module oskit.arch\nprivate[oskit] frames(n: int) -> int = n * 4"),
        ("oskit", "k.sysl", "module oskit\npages(n: int) -> int = oskit.arch.frames(n)"),
      ) shouldBe "12\n"
    }

    "but nothing outside that subtree" in {
      errIn(
        ("", "main.sysl", "print(oskit.arch.frames(3))"),
        ("oskit.arch", "cpu.sysl", "module oskit.arch\nprivate[oskit] frames(n: int) -> int = n * 4"),
      ) should include("'oskit.arch.frames' is private to module 'oskit'")
    }

    // Read outward from the declaration, first hit winning, so the nearer `geom` is the one meant.
    "a repeated segment binds to the innermost one" in {
      errIn(
        ("", "main.sysl", "print(geom.reach(1))"),
        ("geom.mesh.geom.tri", "t.sysl", "module geom.mesh.geom.tri\nprivate[geom] area(n: int) -> int = n * 2"),
        ("geom", "g.sysl", "module geom\nreach(n: int) -> int = geom.mesh.geom.tri.area(n)"),
      ) should include("is private to module 'geom.mesh.geom'")
    }

    "and the module the declaration is in counts as enclosing itself" in {
      runIn(
        ("", "main.sysl", "print(a.b.go(4))"),
        ("a.b", "x.sysl", "module a.b\nprivate[b] step(n: int) -> int = n + 1"),
        ("a.b", "y.sysl", "module a.b\ngo(n: int) -> int = step(n)"),
      ) shouldBe "5\n"
    }
  }

  "a scope that names nothing" - {
    "a module the declaration is not inside" in {
      errIn(
        ("", "main.sysl", "print(1)"),
        ("geom", "g.sysl", "module geom\nprivate[text] scale(n: int) -> int = n * 2"),
        ("text", "t.sysl", "module text\nwrap(n: int) -> int = n"),
      ) should include("'text' is not 'geom' or one of its ancestors")
    }

    "a descendant of it, which is inside-out" in {
      errIn(
        ("", "main.sysl", "print(1)"),
        ("oskit", "k.sysl", "module oskit\nprivate[arch] pages(n: int) -> int = n"),
        ("oskit.arch", "cpu.sysl", "module oskit.arch\nframes(n: int) -> int = n"),
      ) should include("'arch' is not 'oskit' or one of its ancestors")
    }

    "a file at the project root, whose module has no name" in {
      errIn(("", "main.sysl", "private[root] scale(n: int) -> int = n * 2\nprint(1)")) should
        include("this file is at the project root, whose module has no name")
    }

    // The declaration still exists: one mistake is one diagnostic, and the uses of a name whose
    // scope could not be worked out are not each a second complaint about it.
    "and the declaration it was written on still stands" in {
      errIn(("", "main.sysl", "private[root] scale(n: int) -> int = n * 2\nprint(scale(21))")) should
        not include "undefined function 'scale'"
    }
  }

  "every declaration form takes one" - {
    "a struct" in {
      errIn(
        ("", "main.sysl", "var p: geom.Point = geom.Point(1, 2)\nprint(p.x)"),
        ("geom", "g.sysl", "module geom\nprivate struct Point\n    x: int\n    y: int"),
      ) should include("'geom.Point' is private to 'g.sysl'")
    }

    "an enum" in {
      errIn(
        ("", "main.sysl", "var s: geom.Shape = geom.Shape.Dot\nprint(1)"),
        ("geom", "g.sysl", "module geom\nprivate enum Shape\n    Dot\n    Round"),
      ) should include("'geom.Shape' is private to 'g.sysl'")
    }

    "and its variants with it" in {
      errIn(
        ("", "main.sysl", "import geom.Dot\nprint(1)"),
        ("geom", "g.sysl", "module geom\nprivate enum Shape\n    Dot\n    Round"),
      ) should include("'geom.Dot' is private to 'g.sysl'")
    }

    "a trait" in {
      errIn(
        ("", "main.sysl", "loud[T: geom.Show](x: T) -> int = x.show()\nprint(1)"),
        ("geom", "g.sysl", "module geom\nprivate trait Show\n    show(self) -> int"),
      ) should include("'geom.Show' is private to 'g.sysl'")
    }

    "an extern" in {
      errIn(
        ("", "main.sysl", "print(geom.abs(0 - 3))"),
        ("geom", "g.sysl", "module geom\nprivate extern abs(n: int) -> int"),
      ) should include("'geom.abs' is private to 'g.sysl'")
    }

    "a function" in {
      errIn(("", "main.sysl", "print(geom.scale(21))"), hidden) should include("'geom.scale' is private")
    }

    // `13 §2` gives the exception a reason of its own, so the refusal has to carry the reason
    // rather than the grammar's complaint that the modifier was not followed by a name. This is
    // the same refusal the members *inside* an `impl` already get.
    "and an 'impl' does not, having no name for one to restrict" in {
      val src =
        """trait Show
          |    show(self) -> int
          |
          |struct P
          |    v: int
          |
          |private impl Show for P
          |    show(self) -> int = self.v
          |
          |print(P(1).show())""".stripMargin

      err(src) should include("an 'impl' block carries no visibility of its own")
      err(src) should not include "identifier expected"
      err(src.replace("private impl", "private[geom] impl")) should
        include("an 'impl' block carries no visibility of its own")
    }
  }

  "what a restriction does not touch" - {
    // An import binds a shorter spelling; visibility decides what may be spelled at all. A public
    // wrapper over a private helper is the whole point of the level, and it keeps working.
    "a public wrapper reaches its own module's private helper" in {
      runIn(
        ("", "main.sysl", "import geom.twice\nprint(twice(21))"),
        hidden,
      ) shouldBe "42\n"
    }

    "a private type is still a type its own file may use" in {
      runIn(
        ("", "main.sysl", "print(geom.area())"),
        ("geom", "g.sysl",
         "module geom\nprivate struct Point\n    x: int\n    y: int\narea() -> int = Point(3, 4).x * Point(3, 4).y"),
      ) shouldBe "12\n"
    }

    "a private trait may still be implemented and used inside its file" in {
      runIn(
        ("", "main.sysl", "print(geom.go())"),
        ("geom", "g.sysl",
         "module geom\nprivate trait Show\n    show(self) -> int\nstruct Tag\n    n: int\n" +
           "impl Show for Tag\n    show(self) -> int = self.n * 2\ngo() -> int = Tag(5).show()"),
      ) shouldBe "10\n"
    }
  }

  "an import inside a block is held to the same rule" in {
    errIn(
      ("", "main.sysl", "f() -> int =\n    import geom.scale\n    scale(21)\nprint(f())"),
      hidden,
    ) should include("'geom.scale' is private to 'g.sysl', the file that declares it")
  }

  // A name a file may not reach is not a candidate for it, so resolution goes on past it rather
  // than stopping there. Only where nothing else answers at all does the restriction get reported —
  // at which point it is the whole story, and a better one than an undefined name.
  "a name a file cannot reach does not stand in the way of one it can" - {
    "an explicit import beats a sibling file's private declaration of the same name" in {
      runIn(
        ("", "main.sysl", "print(geom.go())"),
        ("geom", "g.sysl", "module geom\nprivate width(n: int) -> int = n * 2"),
        ("geom", "h.sysl", "module geom\nimport text.width\ngo() -> int = width(21)"),
        ("text", "t.sysl", "module text\nwidth(n: int) -> int = n + 1"),
      ) shouldBe "22\n"
    }

    "a wildcard does too" in {
      runIn(
        ("", "main.sysl", "print(geom.go())"),
        ("geom", "g.sysl", "module geom\nprivate width(n: int) -> int = n * 2"),
        ("geom", "h.sysl", "module geom\nimport text.*\ngo() -> int = width(21)"),
        ("text", "t.sysl", "module text\nwidth(n: int) -> int = n + 1"),
      ) shouldBe "22\n"
    }

    "and so does one written inside a block" in {
      runIn(
        ("", "main.sysl", "print(geom.go())"),
        ("geom", "g.sysl", "module geom\nprivate width(n: int) -> int = n * 2"),
        ("geom", "h.sysl", "module geom\ngo() -> int =\n    import text.width\n    width(21)"),
        ("text", "t.sysl", "module text\nwidth(n: int) -> int = n + 1"),
      ) shouldBe "22\n"
    }

    "the library still answers where only a private declaration would have" in {
      runIn(
        ("", "main.sysl", "print(geom.go())"),
        ("geom", "g.sysl", "module geom\nprivate print(n: int) -> int = n"),
        ("geom", "h.sysl", "module geom\ngo() -> int =\n    print(9)\n    1"),
      ) shouldBe "9\n1\n"
    }

    "while the declaring file goes on getting its own" in {
      runIn(
        ("", "main.sysl", "print(geom.go())"),
        ("geom", "g.sysl", "module geom\nprivate width(n: int) -> int = n * 2\ngo() -> int = width(21)"),
        ("text", "t.sysl", "module text\nwidth(n: int) -> int = n + 1"),
      ) shouldBe "42\n"
    }
  }

  "the rest of the surface it reaches" - {
    "a module brought in by name, whose member is private" in {
      errIn(
        ("", "main.sysl", "import text.util\nprint(util.width(4))"),
        ("text.util", "u.sysl", "module text.util\nprivate width(n: int) -> int = n + 1"),
      ) should include("'text.util.width' is private to 'u.sysl'")
    }

    // A directory holding only sub-directories is a module's parent without being a module itself,
    // and `private[M]` names an enclosing *path* — so it is a scope like any other.
    "an ancestor that holds no source of its own" in {
      runIn(
        ("", "main.sysl", "print(a.b.c.go())"),
        ("a.b.c", "c.sysl", "module a.b.c\nprivate[b] step(n: int) -> int = n + 1\ngo() -> int = step(4)"),
      ) shouldBe "5\n"
    }

    "which still keeps everything outside it out" in {
      errIn(
        ("", "main.sysl", "print(a.b.c.step(4))"),
        ("a.b.c", "c.sysl", "module a.b.c\nprivate[b] step(n: int) -> int = n + 1"),
      ) should include("'a.b.c.step' is private to module 'a.b'")
    }

    "a sibling module under the same ancestor may reach it, and one outside may not" in {
      errIn(
        ("", "main.sysl", "print(1)"),
        ("a.b", "x.sysl", "module a.b\nprivate[a] step(n: int) -> int = n"),
        ("a.c", "y.sysl", "module a.c\ngo() -> int = a.b.step(1)"),
        ("d", "z.sysl", "module d\nreach() -> int = a.b.step(1)"),
      ) should include("'a.b.step' is private to module 'a'")
    }

    "a private declaration may name itself" in {
      runIn(
        ("", "main.sysl", "private down(n: int) -> int =\n    if n <= 0 then 0 else down(n - 1) + 1\nprint(down(5))"),
      ) shouldBe "5\n"
    }

    // Naming a trait is resolved twice — once where the bound is written, once by the
    // definition-time walk — and one unreachable name is one mistake, not two.
    "a private trait in a bound is reported once" in {
      val out = errIn(
        ("", "main.sysl", "loud[T: geom.Show](x: T) -> int = 1\nprint(1)"),
        ("geom", "g.sysl", "module geom\nprivate trait Show\n    show(self) -> int"),
      )

      out should include("'geom.Show' is private to 'g.sysl'")
      out.linesIterator.count(_.contains("is private to")) shouldBe 1
    }
  }

  // A restriction a signature could carry a value out of would hardly be one: another module could
  // hold a value of a type it cannot name, pass it on, and read its fields. So what a declaration
  // says about itself has to be as nameable as the declaration is.
  "a declaration may not be more visible than the types it names" - {
    "a public function's result" in {
      errIn(("", "main.sysl",
        """private struct Point
          |    x: int
          |make() -> Point = Point(1)
          |print(1)
          |""".stripMargin)) should include(
        "'make' is public, but its result names 'Point', which is private to 'main.sysl', the file " +
          "that declares it — a declaration may not be more visible than the types it names")
    }

    "a public function's parameter" in {
      errIn(("", "main.sysl",
        """private struct Point
          |    x: int
          |read(p: Point) -> int = p.x
          |print(1)
          |""".stripMargin)) should include("'read' is public, but parameter 'p' names 'Point'")
    }

    "and a type reached through a slice or a memory mode is named just as much" in {
      errIn(("", "main.sysl",
        """private struct Point
          |    x: int
          |count(ps: []Point) -> int = 0
          |print(1)
          |""".stripMargin)) should include("'count' is public, but parameter 'ps' names 'Point'")
    }

    // A bound is part of what a caller has to satisfy, so a trait it cannot name leaves it unable to
    // say what the declaration is asking of it.
    "a bound naming a private trait" in {
      errIn(("", "main.sysl",
        """private trait Show
          |    show(self) -> int
          |loud[T: Show](x: T) -> int = x.show()
          |print(1)
          |""".stripMargin)) should include(
        "'loud' is public, but the bound on 'T' names 'Show', which is private to 'main.sysl'")
    }

    // A field carries its own reach, so it is named as one and asked at its own — public here,
    // since it said nothing and the struct it belongs to is public.
    "a field of a public struct" in {
      errIn(("", "main.sysl",
        """private struct Point
          |    x: int
          |struct Line
          |    a: Point
          |print(1)
          |""".stripMargin)) should include("'Line.a' is public, but its type names 'Point'")
    }

    "the payload of a public enum's variant" in {
      errIn(("", "main.sysl",
        """private struct Point
          |    x: int
          |enum Shape
          |    Dot(at: Point)
          |    Round
          |print(1)
          |""".stripMargin)) should include("'Shape' is public, but the 'at' of variant 'Dot' names 'Point'")
    }

    // A member carries no modifier of its own, so it is as visible as the type it belongs to.
    "a member of a public struct" in {
      errIn(("", "main.sysl",
        """private struct Point
          |    x: int
          |struct Line
          |    n: int
          |    tip(self) -> Point = Point(self.n)
          |print(1)
          |""".stripMargin)) should include("'Line.tip' is public, but its result names 'Point'")
    }

    "a method a public trait declares" in {
      errIn(("", "main.sysl",
        """private struct Point
          |    x: int
          |trait Tip
          |    tip(self) -> Point
          |print(1)
          |""".stripMargin)) should include("'Tip.tip' is public, but its result names 'Point'")
    }

    "an extern, which the linker resolves and the rule reaches all the same" in {
      errIn(("", "main.sysl",
        """private enum Mode
          |    On
          |    Off
          |extern pick() -> Mode
          |print(1)
          |""".stripMargin)) should include("'pick' is public, but its result names 'Mode'")
    }

    "a type argument, which is named as much as the type it is applied to" in {
      errIn(("", "main.sysl",
        """private struct Point
          |    x: int
          |struct Box[T]
          |    value: T
          |make() -> Box[Point] = Box(Point(1))
          |print(1)
          |""".stripMargin)) should include("'make' is public, but its result names 'Point'")
    }

    "a trait behind a memory mode, which is an object over it" in {
      errIn(("", "main.sysl",
        """private trait Show
          |    show(self) -> int
          |render(s: &Show) -> int = s.show()
          |print(1)
          |""".stripMargin)) should include("'render' is public, but parameter 's' names 'Show'")
    }

    // The diagnostic names the type by the path a reader would have to be able to write, not by the
    // shorter spelling this file gave it — the alias is exactly what they do not have.
    "and an alias does not hide which type is meant" in {
      errIn(
        ("", "main.sysl", "print(1)"),
        ("geom", "g.sysl", "module geom\nprivate[geom] struct P\n    x: int"),
        ("geom", "h.sysl", "module geom\nimport geom.{P as Q}\nmake() -> Q = Q(1)"),
      ) should include("'make' is public, but its result names 'geom.P', which is private to module 'geom'")
    }

    // Both are restricted; what fails is that one subtree does not contain the other.
    "a 'private[M]' signature naming a type scoped more narrowly than it is" in {
      errIn(
        ("", "main.sysl", "print(1)"),
        ("a.b", "x.sysl",
         """module a.b
           |private[b] struct P
           |    x: int
           |private[a] make() -> P = P(1)
           |""".stripMargin),
      ) should include(
        "'make' is visible throughout module 'a', but its result names 'a.b.P', which is private " +
          "to module 'a.b'")
    }
  }

  "but a signature naming a type that reaches at least as far is fine" - {
    // The one level that never needs checking: a file-private declaration is read in one file, and
    // every type it names is visible there or the signature would not have resolved.
    "a private function may name a private type" in {
      runIn(
        ("", "main.sysl", "print(geom.read())"),
        ("geom", "g.sysl",
         """module geom
           |private struct P
           |    x: int
           |private make() -> P = P(7)
           |read() -> int = make().x
           |""".stripMargin),
      ) shouldBe "7\n"
    }

    "and a narrower scope may name a type its ancestor keeps" in {
      runIn(
        ("", "main.sysl", "print(a.b.go())"),
        ("a.b", "x.sysl",
         """module a.b
           |private[a] struct P
           |    x: int
           |private[b] make() -> P = P(7)
           |go() -> int = make().x
           |""".stripMargin),
      ) shouldBe "7\n"
    }

    "and one private type may be another's field, in the file that has both" in {
      runIn(("", "main.sysl",
        """private struct Point
          |    x: int
          |private struct Line
          |    a: Point
          |print(Line(Point(6)).a.x)
          |""".stripMargin)) shouldBe "6\n"
    }

    // The rule is about names, and a signature's type parameters are not names of declarations —
    // even where the module happens to declare something spelled the same way.
    "a type parameter is not the private type it shares a spelling with" in {
      runIn(("", "main.sysl",
        """private struct T
          |    x: int
          |id[T](x: T) -> T = x
          |print(id(5))
          |""".stripMargin)) shouldBe "5\n"
    }
  }

  // An `impl` names no type of its own, so neither half of it is a leak: the members' signatures are
  // the trait's, and a mismatch is refused as non-conformance rather than reaching this rule at all.
  "an 'impl' is outside the rule, in both directions" - {
    "a private trait may be implemented for a public type" in {
      runIn(("", "main.sysl",
        """private trait Show
          |    show(self) -> int
          |struct Tag
          |    n: int
          |impl Show for Tag
          |    show(self) -> int = self.n
          |print(Tag(5).show())
          |""".stripMargin)) shouldBe "5\n"
    }

    "and a public trait for a private type" in {
      runIn(("", "main.sysl",
        """trait Show
          |    show(self) -> int
          |private struct Tag
          |    n: int
          |impl Show for Tag
          |    show(self) -> int = self.n
          |print(Tag(5).show())
          |""".stripMargin)) shouldBe "5\n"
    }
  }
}
