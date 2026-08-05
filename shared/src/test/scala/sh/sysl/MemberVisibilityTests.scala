package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** A field's and a member's visibility (`08 § Visibility`) — `13 §2`'s four reaches, one level down.
 *
 * Two things make this more than a re-use of the top-level rule, and most of what is below is about
 * one or the other. **Silence means the type's reach, not public**, so a member of a restricted type
 * is restricted without having said anything and a modifier may only narrow. And **the positional
 * constructor reads every field**, so a restricted field puts `Point(1, 2)` out of reach from
 * outside — which is the whole of what makes a private field worth writing.
 */
class MemberVisibilityTests extends AnyFreeSpec with ParseSupport with CodegenSupport with RunSupport {

  /** A module in two files: one declares a type with something kept back, the other is a sibling
   * that may name the type but not that. Every case below differs only in what it tries to write.
   */
  private def pair(decl: String, use: String): Seq[(String, String, String)] =
    Seq(
      ("", "main.sysl", "print(1)"),
      ("geom", "g.sysl", s"module geom\n$decl"),
      ("geom", "h.sysl", s"module geom\n$use"),
    )

  "the modifiers parse where a member and a field are written" - {
    "a field takes one" in {
      prog("struct P\n    private x: int\nend P") shouldBe
        List(StructDecl("P", Nil, List(Param("x", NamedType("int"), Visibility.File))))
    }

    "a field takes the scoped form too" in {
      prog("struct P\n    private[geom] x: int\nend P") shouldBe
        List(StructDecl("P", Nil, List(Param("x", NamedType("int"), Visibility.Scoped("geom")))))
    }

    "a method takes one" in {
      prog("struct P\n    private step(self) -> int = 1\nend P") shouldBe
        List(StructDecl("P", Nil, Nil,
          List(MethodDecl("step", Some(RecvMode.ByValue), false, Nil, Nil, Some(NamedType("int")),
            List(ExprStmt(i(1))), vis = Visibility.File))))
    }

    "a property takes one" in {
      prog("struct P\n    private twice -> int = 2\nend P") shouldBe
        List(StructDecl("P", Nil, Nil,
          List(MethodDecl("twice", None, true, Nil, Nil, Some(NamedType("int")),
            List(ExprStmt(i(2))), vis = Visibility.File))))
    }

    "an enum's member takes one" in {
      prog("enum E\n    A\n    private step(self) -> int = 1\nend E") shouldBe
        List(EnumDecl("E", Nil, None, List(EnumVariantDecl("A", None, Nil)),
          List(MethodDecl("step", Some(RecvMode.ByValue), false, Nil, Nil, Some(NamedType("int")),
            List(ExprStmt(i(1))), vis = Visibility.File))))
    }

    // An unmarked field and an unmarked member say nothing, which is what lets the default mean the
    // type's reach rather than public.
    "and an unmarked one says nothing" in {
      prog("struct P\n    x: int\nend P") shouldBe
        List(StructDecl("P", Nil, List(Param("x", NamedType("int")))))
    }

    // `invariant` declares no name, so there is nothing for a modifier to restrict — and a field may
    // still be called `invariant`, which is what the ordering in the grammar is for.
    "an 'invariant' clause is still told from a field beneath a modifier" in {
      prog("struct P\n    private invariant: int\nend P") shouldBe
        List(StructDecl("P", Nil, List(Param("invariant", NamedType("int"), Visibility.File))))
    }
  }

  "a field is read by selecting it, and a modifier decides who may" - {
    "the declaring file may" in {
      run("""struct Counter
            |    private n: int
            |    value(self) -> int = self.n
            |end Counter
            |print(Counter(3).value())
            |""".stripMargin) shouldBe "3\n"
    }

    "a sibling file of the same module may not" in {
      errIn(pair("struct P\n    private x: int", "read(p: P) -> int = p.x")*) should include(
        "field 'x' of 'geom.P' is private to 'g.sysl', the file that declares it")
    }

    // A write is the same selection, so one modifier covers both — a field nobody outside may read
    // is not one they may write.
    "and writing it is the same selection" in {
      errIn(pair("struct P\n    private x: int\n    make() -> P = P(1)",
        "bump(p: *P) = p.x = 9")*) should include("field 'x' of 'geom.P' is private")
    }

    "a compound assignment to it is refused too" in {
      errIn(pair("struct P\n    private x: int\n    make() -> P = P(1)",
        "bump(p: *P) = p.x += 1")*) should include("field 'x' of 'geom.P' is private")
    }

    "'private[M]' opens it to the whole module" in {
      runIn(("", "main.sysl", "import geom.{P, read}\nprint(read(P.make()))"),
        ("geom", "g.sysl", "module geom\nstruct P\n    private[geom] x: int\n    make() -> P = P(4)"),
        ("geom", "h.sysl", "module geom\nread(p: P) -> int = p.x")) shouldBe "4\n"
    }

    "and keeps it out of everything above that module" in {
      errIn(("", "main.sysl", "import geom.P\nread(p: P) -> int = p.x\nprint(1)"),
        ("geom", "g.sysl", "module geom\nstruct P\n    private[geom] x: int")) should include(
        "field 'x' of 'geom.P' is private to module 'geom'")
    }

    // The complaint is about the field, not about the struct: `P` itself is perfectly nameable, so
    // an "undefined name" or a "no such field" would both send the reader somewhere else.
    "a field the reader cannot see is not reported as a field that is not there" in {
      errIn(pair("struct P\n    private x: int\n    y: int", "read(p: P) -> int = p.y + p.x")*) should
        include("field 'x' of 'geom.P' is private")
    }
  }

  "the positional forms name every field, so they need every field" - {
    "the constructor is refused from outside" in {
      errIn(pair("struct P\n    private x: int\n    y: int", "make() -> P = P(1, 2)")*) should include(
        "the constructor names every field of 'geom.P' in order, and 'x' is private to 'g.sysl', " +
          "the file that declares it — build it through an associated function of its own")
    }

    // Which is exactly what the associated function is for, and what the diagnostic points at.
    "and an associated function is how the type offers one instead" in {
      runIn(("", "main.sysl", "import geom.P\nprint(P.of(7).doubled())"),
        ("geom", "g.sysl",
         """module geom
           |struct P
           |    private x: int
           |    of(n: int) -> P = P(n)
           |    doubled(self) -> int = self.x * 2
           |""".stripMargin)) shouldBe "14\n"
    }

    "the positional pattern is refused the same way" in {
      errIn(pair("struct P\n    private x: int\n    y: int\n    make() -> P = P(1, 2)",
        "flat(p: P) -> int\n    p match\n        P(a, b) -> a + b\n")*) should include(
        "this pattern names every field of 'geom.P' in order, and 'x' is private")
    }

    // The named form reads only what it writes, so it is the one that still works — and saying so is
    // the useful half of the positional form's complaint.
    "and the named pattern needs only the fields it names" in {
      runIn(("", "main.sysl", "import geom.{P, flat}\nprint(flat(P.make()))"),
        ("geom", "g.sysl", "module geom\nstruct P\n    private x: int\n    y: int\n    make() -> P = P(1, 2)"),
        ("geom", "h.sysl",
         """module geom
           |flat(p: P) -> int
           |    p match
           |        P{y} -> y
           |""".stripMargin)) shouldBe "2\n"
    }

    "a named pattern that names a restricted field is refused" in {
      errIn(pair("struct P\n    private x: int\n    y: int\n    make() -> P = P(1, 2)",
        "flat(p: P) -> int\n    p match\n        P{x} -> x\n")*) should include(
        "field 'x' of 'geom.P' is private")
    }

    "the declaring file may still write both forms" in {
      run("""struct P
            |    private x: int
            |    y: int
            |    sum(self) -> int
            |        self match
            |            P(a, b) -> a + b
            |    end sum
            |end P
            |print(P(3, 4).sum())
            |""".stripMargin) shouldBe "7\n"
    }
  }

  "a member is reached by naming it, and every kind of member is" - {
    "a method" in {
      errIn(pair("struct P\n    x: int\n    private step(self) -> int = self.x",
        "go(p: P) -> int = p.step()")*) should include("method 'step' of 'geom.P' is private to 'g.sysl'")
    }

    "a property" in {
      errIn(pair("struct P\n    x: int\n    private twice -> int = self.x * 2",
        "go(p: P) -> int = p.twice")*) should include("property 'twice' of 'geom.P' is private to 'g.sysl'")
    }

    "an associated function" in {
      errIn(pair("struct P\n    x: int\n    private of(n: int) -> P = P(n)",
        "go() -> P = P.of(1)")*) should include(
        "associated function 'of' of 'geom.P' is private to 'g.sysl'")
    }

    "a generic type's method" in {
      errIn(pair("struct Box[T]\n    v: T\n    private peek(self) -> T = self.v",
        "go(b: Box[int]) -> int = b.peek()")*) should include("method 'peek' of 'geom.Box' is private")
    }

    "an enum's member" in {
      errIn(pair("enum E\n    A\n    B\n    private step(self) -> int = 1",
        "go(e: E) -> int = e.step()")*) should include("method 'step' of 'geom.E' is private")
    }

    "and a member of a generic type reached through its own type parameter" in {
      errIn(pair("struct Box[T]\n    v: T\n    private make(x: T) -> Box[T] = Box(x)",
        "go() -> Box[int] = Box.make(1)")*) should include("associated function 'make' of 'geom.Box' is private")
    }
  }

  /** The rule that is not a re-use of the top-level one: an unmarked member sits at its type's reach,
   * so a restricted type restricts everything in it without anything in it saying so.
   */
  "an unmarked member inherits its type's reach" - {
    "a public member of a file-private struct stays inside that file" in {
      errIn(("", "main.sysl", "print(1)"),
        ("geom", "g.sysl", "module geom\nprivate struct P\n    x: int\n    of(n: int) -> P = P(n)"),
        ("geom", "h.sysl", "module geom\ngo() -> int = P.of(1).x")) should include(
        "'geom.P' is private to 'g.sysl'")
    }

    // And it is what lets a restricted member name a restricted type: the leak rule asks each
    // member at its own reach, so the one that goes nowhere may say anything.
    "a private member may name a private type" in {
      run("""private struct Secret
            |    n: int
            |struct Box
            |    v: int
            |    private hide(self) -> Secret = Secret(self.v)
            |    show(self) -> int = self.hide().n
            |end Box
            |print(Box(5).show())
            |""".stripMargin) shouldBe "5\n"
    }

    "a private field may name a private type" in {
      run("""private struct Secret
            |    n: int
            |struct Box
            |    private s: Secret
            |    of(n: int) -> Box = Box(Secret(n))
            |    show(self) -> int = self.s.n
            |end Box
            |print(Box.of(6).show())
            |""".stripMargin) shouldBe "6\n"
    }

    "while a public one beside it may not" in {
      err("""private struct Secret
            |    n: int
            |struct Box
            |    s: Secret
            |print(1)
            |""".stripMargin) should include("'Box.s' is public, but its type names 'Secret'")
    }

    "and a public method beside it may not either" in {
      err("""private struct Secret
            |    n: int
            |struct Box
            |    v: int
            |    hide(self) -> Secret = Secret(self.v)
            |print(1)
            |""".stripMargin) should include("'Box.hide' is public, but its result names 'Secret'")
    }
  }

  "a modifier may only narrow" - {
    "a member wider than the module its type is scoped to is refused" in {
      errIn(("", "main.sysl", "print(1)"),
        ("a.b", "x.sysl",
         """module a.b
           |private[b] struct P
           |    private[a] x: int
           |""".stripMargin)) should include(
        "'P.x' is visible throughout module 'a', but 'a.b.P' is private to module 'a.b' — a member " +
          "cannot be more visible than the type it belongs to")
    }

    "a member wider than a file-private type is refused" in {
      errIn(("", "main.sysl", "print(1)"),
        ("a", "x.sysl",
         """module a
           |private struct P
           |    private[a] x: int
           |""".stripMargin)) should include(
        "'P.x' is visible throughout module 'a', but 'a.P' is private to 'x.sysl', the file that " +
          "declares it")
    }

    "narrowing in the same direction is fine" in {
      errIn(("", "main.sysl", "import a.P\nread(p: P) -> int = p.x\nprint(1)"),
        ("a", "x.sysl",
         """module a
           |private[a] struct P
           |    private x: int
           |""".stripMargin)) should include("'a.P' is private to module 'a'")
    }

    "and a modifier equal to its type's is fine" in {
      runIn(("", "main.sysl", "print(1)"),
        ("a", "x.sysl",
         """module a
           |private[a] struct P
           |    private[a] x: int
           |    of(n: int) -> P = P(n)
           |""".stripMargin),
        ("a", "y.sysl", "module a\nread() -> int = P.of(2).x")) shouldBe "1\n"
    }
  }

  "a member a trait declares carries no modifier" - {
    "a modifier in a trait body is refused, and says why" in {
      progError("trait Show\n    private show(self) -> int\nend Show") should include(
        "a trait's members and an 'impl' block's carry no visibility of their own")
    }

    "a modifier on a trait's default body is refused too" in {
      progError("trait Show\n    private show(self) -> int = 1\nend Show") should include(
        "carry no visibility of their own")
    }

    "a modifier on a trait's property signature is refused" in {
      progError("trait Wide\n    private width -> int\nend Wide") should include(
        "carry no visibility of their own")
    }

    "and a modifier in an 'impl' block is refused" in {
      progError("""trait Show
                  |    show(self) -> int
                  |struct P
                  |    x: int
                  |impl Show for P
                  |    private show(self) -> int = self.x
                  |""".stripMargin) should include("carry no visibility of their own")
    }

    // What a trait asks for stays reachable wherever the trait is, which is the reason for the
    // refusal rather than a separate rule: a `private` method and a trait's method of the same name
    // are two members, and it is the trait's that answers a bound.
    "a trait's method is reached at the trait's reach, whatever the type keeps back" in {
      runIn(("", "main.sysl", "import geom.{P, loud}\nprint(loud(P.of(3)))"),
        ("geom", "g.sysl",
         """module geom
           |trait Show
           |    show(self) -> int
           |struct P
           |    private x: int
           |    of(n: int) -> P = P(n)
           |impl Show for P
           |    show(self) -> int = self.x * 10
           |""".stripMargin),
        ("geom", "h.sysl", "module geom\nloud[T: Show](v: T) -> int = v.show()")) shouldBe "30\n"
    }
  }

  /** The ways of arriving at a field that are not a bare `p.x`, and the two places a restricted
   * member could have leaked out through something other than its own name.
   */
  "the places a restricted field is still one" - {
    "through a memory mode, which selection reaches through and the modifier still covers" in {
      errIn(pair("struct P\n    private x: int\n    of(n: int) -> &P = P(n)",
        "read(p: &P) -> int = p.x")*) should include("field 'x' of 'geom.P' is private")
    }

    "through a pointer" in {
      errIn(pair("struct P\n    private x: int",
        "read(p: *P) -> int = p.x")*) should include("field 'x' of 'geom.P' is private")
    }

    "on an element of an array" in {
      errIn(pair("struct P\n    private x: int\n    of(n: int) -> P = P(n)",
        "read(ps: [4]P) -> int = ps[0].x")*) should include("field 'x' of 'geom.P' is private")
    }

    "on a generic type, whose fields belong to the declaration rather than the instantiation" in {
      errIn(pair("struct Box[T]\n    private v: T\n    of(x: T) -> Box[T] = Box(x)",
        "read(b: Box[int]) -> int = b.v")*) should include("field 'v' of 'geom.Box' is private")
    }

    // An `impl` written in another file of the module is another file, so it is held to the same
    // line — which is what makes the file level mean something rather than being about the type.
    "and an 'impl' in a sibling file may not reach one either" in {
      errIn(pair("struct P\n    private x: int\ntrait Wide\n    width(self) -> int",
        "impl Wide for P\n    width(self) -> int = self.x")*) should include(
        "field 'x' of 'geom.P' is private")
    }

    // The other direction, and the reason the level is the *file* rather than the type: everything
    // written beside the declaration may read it, whether or not it belongs to that type.
    "while anything else in the declaring file may" in {
      run("""struct P
            |    private x: int
            |struct Q
            |    p: P
            |    inner(self) -> int = self.p.x
            |end Q
            |print(Q(P(8)).inner())
            |""".stripMargin) shouldBe "8\n"
    }
  }

  /** Two negatives worth pinning, because either failing would be a hole rather than a diagnostic:
   * a restricted member must not become reachable by some route that does not spell its name.
   */
  "a restricted member has no second way in" - {
    "it cannot be shadowed by a trait's member of the same name" in {
      err("""trait Show
            |    show(self) -> int
            |struct P
            |    private show(self) -> int = 1
            |impl Show for P
            |    show(self) -> int = 2
            |print(1)
            |""".stripMargin) should include("type 'P' already has a member named 'show'")
    }

    "and it does not answer a trait requirement on its own, so no bound reaches it" in {
      err("""trait Show
            |    show(self) -> int
            |struct P
            |    private show(self) -> int = 1
            |impl Show for P
            |print(1)
            |""".stripMargin) should include("'P' does not implement 'Show': method 'show' is missing")
    }
  }

  "what a restricted field does not change" - {
    // Visibility is about naming, never about layout or storage — a private field is a field.
    "the struct is laid out and copied exactly as it was" in {
      run("""struct P
            |    private a: int
            |    private b: int
            |    of(x: int, y: int) -> P = P(x, y)
            |    sum(self) -> int = self.a + self.b
            |end P
            |var p = P.of(2, 3)
            |var q = p
            |print(p.sum(), q.sum())
            |""".stripMargin) shouldBe "5 5\n"
    }

    // A restriction is about naming a *field*, so the type itself travels as freely as ever — which
    // is the difference between this and restricting the type.
    "the type may still be held, passed, and used as a type argument" in {
      runIn(("", "main.sysl",
             """import geom.P
               |var xs: []P = [P.of(1), P.of(2)]
               |var one: Option[P] = Some(xs[1])
               |one match
               |    Some(p) -> print(p.doubled())
               |    None -> print(0)
               |""".stripMargin),
        ("geom", "g.sysl",
         """module geom
           |struct P
           |    private x: int
           |    of(n: int) -> P = P(n)
           |    doubled(self) -> int = self.x * 2
           |""".stripMargin)) shouldBe "4\n"
    }

    "a counted field behind a modifier is still counted" in {
      run("""struct Node
            |    private tag: string
            |    of(s: string) -> &Node = Node(s)
            |    name(&self) -> string = self.tag
            |end Node
            |var i = 0
            |while i < 20000
            |    var n = Node.of("x")
            |    i += 1
            |print("done")
            |""".stripMargin) shouldBe "done\n"
    }

    "and an invariant over private fields is still checked" in {
      exits("""struct Span
               |    private lo: int
               |    private hi: int
               |    invariant lo <= hi
               |    of(a: int, b: int) -> Span = Span(a, b)
               |    low(self) -> int = self.lo
               |end Span
               |print(Span.of(5, 1).low())
               |""".stripMargin)
    }
  }
}
