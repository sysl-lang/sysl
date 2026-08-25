package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** `.red` — a member of the type the context **expects**, written with that type's own name left
 * off (`reference/expressions.md § A leading dot`).
 *
 * The rule is one sentence: `.name` is `T.name` with the `T` dropped, where `T` is what the context
 * asks for. So what it reaches is exactly what the qualified spelling reaches — a variant, a
 * data-carrying variant, an associated function — and the two spellings are checked against each
 * other here rather than described.
 *
 * The expectation is not new machinery either: every position that already pushes a type down
 * supplies one, which is why the first group below is a list of *positions* rather than a list of
 * features.
 */
class ImplicitMemberTests extends AnyFreeSpec with RunSupport with CodegenSupport {

  private val colour =
    """enum Colour
      |    Red
      |    Green
      |    Blue
      |code(c: Colour) -> int
      |    c match
      |        Red -> 1
      |        Green -> 2
      |        Blue -> 3
      |""".stripMargin

  /** The same enum in a module of its own, which is what makes a use site's spelling worth
   * measuring: reaching `Green` there otherwise needs the module path, the type, or an import.
   */
  private val paint =
    """module paint
      |enum Colour
      |    Red
      |    Green
      |code(c: Colour) -> int
      |    c match
      |        Red -> 1
      |        Green -> 2
      |""".stripMargin

  "the expected type supplies the qualifier" - {
    "at an argument" in {
      run(colour + "print(code(.Green))") shouldBe "2\n"
    }

    "at an annotated binding" in {
      run(colour + "val c: Colour = .Blue\nprint(code(c))") shouldBe "3\n"
    }

    "at a mutable one, and at the assignment that follows it" in {
      run(colour + "var c: Colour = .Red\nc = .Blue\nprint(code(c))") shouldBe "3\n"
    }

    "at a return, and at an expression body" in {
      run(colour + "pick() -> Colour = .Green\nlater() -> Colour\n    return .Blue\nprint(code(pick()), code(later()))") shouldBe
        "2 3\n"
    }

    // The branch rather than the whole expression is what a converting context belongs to, so an
    // `if` used as a value hands each side the same expectation it hands a single expression.
    "in each branch of an `if` used as a value" in {
      run(colour + "val c: Colour = if 1 < 2 then .Red else .Blue\nprint(code(c))") shouldBe "1\n"
    }

    "at a struct's field, which the constructor supplies from the declaration" in {
      run(colour +
        """struct Pen
          |    c: Colour
          |    width: int
          |val p = Pen(.Green, 3)
          |print(code(p.c), p.width)
          |""".stripMargin) shouldBe "2 3\n"
    }

    // A `&T` parameter asks the expression for the `T` it will box, so the payload's type is what
    // arrives here — the boxing is not something the form has to know about.
    "at a reference parameter, whose expectation is the payload it boxes" in {
      run(colour + "shade(c: &Colour) -> int = code(*c)\nprint(shade(.Blue))") shouldBe "3\n"
    }

    "at a variant that carries data" in {
      run("""enum Shape
            |    Circle(r: int)
            |    Square(side: int)
            |area(s: Shape) -> int
            |    s match
            |        Circle(r) -> r * 3
            |        Square(side) -> side * side
            |print(area(.Circle(2)), area(.Square(4)))
            |""".stripMargin) shouldBe "6 16\n"
    }

    "and at the library's own enums, which are no different" in {
      run("val o: Option[int] = .None\nval p: Option[int] = .Some(7)\nprint(o.is_some(), p.unwrap())") shouldBe
        "false 7\n"
    }

    "at an argument written by name rather than by position" in {
      run(colour + "paint(c: Colour) -> int = code(c)\nprint(paint(c = .Blue))") shouldBe "3\n"
    }

    // A default is analyzed at the parameter it fills, in the declaration's own scope — so the
    // expectation reaches it through the wrapper argument binding puts around it rather than in
    // spite of one.
    "at a parameter's default, which is filled at the call that left it out" in {
      run(colour + "paint(c: Colour = .Green) -> int = code(c)\nprint(paint(), paint(.Blue))") shouldBe "2 3\n"
    }

    "at an element of an array literal, and of a slice" in {
      run(colour + "val xs: [2]Colour = [.Red, .Blue]\nval ys: []Colour = [.Green, .Red]\nprint(code(xs[1]), code(ys[0]))") shouldBe
        "3 2\n"
    }

    "at a part of a tuple" in {
      run(colour + "val t: (Colour, int) = (.Blue, 2)\nprint(code(t.0), t.1)") shouldBe "3 2\n"
    }

    // The payload's own type is an expectation like any other, so the form nests: the outer `.Some`
    // is resolved against `Option[Colour]` and hands `Colour` to the inner one.
    "and inside another one, at the payload the outer variant declares" in {
      run(colour + "val o: Option[Colour] = .Some(.Red)\nprint(code(o.unwrap()))") shouldBe "1\n"
    }

    "in the arms of a `match` used as a value" in {
      run(colour +
        """val c: Colour = .Red
          |val d: Colour = c match
          |    Red -> .Blue
          |    Green -> .Green
          |    Blue -> .Red
          |print(code(d))
          |""".stripMargin) shouldBe "3\n"
    }
  }

  /** `c == .Red` is what a reader writes first, and an operand has no expectation of its own to
   * push down — so the *neighbour* supplies one, through the tier `Literals.typedByPosition`
   * already keeps for expressions the position types rather than the expression.
   */
  "an operand takes it from the operand beside it" - {
    "on the right of a comparison" in {
      run(colour + "val c: Colour = .Green\nprint(c == .Green, c != .Green)") shouldBe "true false\n"
    }

    "and on the left, since neither side is privileged" in {
      run(colour + "val c: Colour = .Green\nprint(.Green == c)") shouldBe "true\n"
    }
  }

  "it reaches what the qualified form reaches" - {
    "an associated function of a struct" in {
      run("""struct Point
            |    x: int
            |    y: int
            |    origin() -> Point = Point(0, 0)
            |val p: Point = .origin()
            |print(p.x, p.y)
            |""".stripMargin) shouldBe "0 0\n"
    }

    "an associated function of an enum" in {
      run(colour +
        """enum Signal
          |    Stop
          |    Go
          |    start() -> Signal = Go
          |lit(s: Signal) -> int
          |    s match
          |        Stop -> 0
          |        Go -> 1
          |val s: Signal = .start()
          |print(lit(s))
          |""".stripMargin) shouldBe "1\n"
    }

    // The expectation is read **as written** rather than through `repr`, and this is the case that
    // decides it: a transparent subtype reduces to `int`, and `int` is not where an `Age`'s members
    // are filed. Stripping would have answered "int has no associated function 'fresh'" about a
    // type the reader can see declaring one.
    // The parity is the point of the rule, so it is checked where the qualified form **fails**
    // too: an associated function of a generic type has no receiver to read the arguments off, and
    // both spellings say so in the same words rather than the implicit one inventing a complaint.
    "and it fails where the qualified form fails, in the same words" in {
      val src =
        """struct Box[T]
          |    v: T
          |    empty() -> Box[int] = Box(0)
          |val b: Box[int] = %s
          |print(b.v)
          |""".stripMargin

      err(src.format(".empty()")) should include("cannot infer the type argument 'T' of 'Box.empty'")
      err(src.format("Box.empty()")) should include("cannot infer the type argument 'T' of 'Box.empty'")
    }

    // `try` is reached because the qualified form reaches it, and it is never *useful*: what it
    // yields is an `Option` of the enum, so the expectation that would have named the enum is the
    // one thing it cannot satisfy. The message is about the types, which is the honest complaint.
    "an enum's `try`, which the rule reaches and the types then refuse" in {
      err(colour + "val c: Colour = .try(1)\nprint(code(c))") should
        include("declared Colour but the value is sysl.Option[Colour]")
    }

    "an associated function of a constrained subtype, which `repr` would have thrown away" in {
      run("""type Age = int within 0..150
            |trait Fresh
            |    fresh() -> Self
            |impl Fresh for Age
            |    fresh() -> Age = Age(1)
            |val a: Age = .fresh()
            |print(int(a))
            |""".stripMargin) shouldBe "1\n"
    }
  }

  /** The qualifier the dot leaves off may be one the file never wrote — which is the case the form
   * is *for*: a module's enum is reached through the type the signature already names, so a use site
   * needs neither the module path nor an import to say a variant.
   */
  "the type it resolves against need not be nameable here" - {
    "a variant of another module's enum, with no import and no path" in {
      runIn(
        ("paint", "paint.sysl", paint),
        ("", "main.sysl", "print(paint.code(.Green))\n"),
      ) shouldBe "2\n"
    }

    // Visibility is still the type's to decide: what the dot leaves off is the *spelling*, not the
    // check, and an associated function goes through the same one the qualified call does.
    "and a private associated function is still refused" in {
      errIn(
        ("paint", "paint.sysl", paint +
          """struct Pen
            |    c: Colour
            |    private make() -> Pen = Pen(Red)
            |""".stripMargin),
        ("", "main.sysl", "val p: paint.Pen = .make()\nprint(1)\n"),
      ) should include("private")
    }
  }

  "overloading decides it the way it decides everything else" - {
    // A candidate is chosen by *trying* the call against it, and each try analyzes the argument at
    // that candidate's parameter type — so the implicit member resolves once per candidate and the
    // one whose enum has no such variant simply does not fit.
    "the candidate whose parameter has the variant is the one that fits" in {
      run("""enum Colour
            |    Red
            |    Green
            |enum Fruit
            |    Apple
            |    Pear
            |name(c: Colour) -> int = 1
            |name(f: Fruit) -> int = 2
            |print(name(.Red), name(.Pear))
            |""".stripMargin) shouldBe "1 2\n"
    }

    "and a variant both of them declare is the ambiguity the language already reports" in {
      val e = err("""enum Colour
                    |    Red
                    |    Ripe
                    |enum Fruit
                    |    Apple
                    |    Ripe
                    |name(c: Colour) -> int = 1
                    |name(f: Fruit) -> int = 2
                    |print(name(.Ripe))
                    |""".stripMargin)

      e should include("ambiguous")
    }
  }

  "at a generic parameter it is held back, exactly as `null` is" - {
    "so the argument that settles the parameter settles this one too" in {
      run(colour + "first[T](a: T, b: T) -> T = a\nprint(code(first(Colour.Blue, .Red)))") shouldBe "3\n"
    }

    "and where nothing settles it, the refusal says the context supplied no type" in {
      err(colour + "only[T](a: T) -> int = 1\nprint(only(.Red))") should include("nothing here expects one")
    }
  }

  "what it refuses" - {
    "a position that expects nothing at all" in {
      val e = err(colour + "print(.Red)")

      e should include("'.Red' is a member of whatever type the context expects")
      e should include("nothing here expects one")
    }

    // A trait object pushes no expectation down at all — what may be erased into one is whatever
    // implements the trait — so this arrives as the no-context case, and the message names it
    // rather than leaving a reader to wonder why a type they can see written expects nothing.
    "a trait object, which expects no single type" in {
      err("""trait Shape
            |    area(self) -> int
            |struct Rect
            |    w: int
            |    h: int
            |    unit() -> Rect = Rect(1, 1)
            |impl Shape for Rect
            |    area(self) -> int = self.w * self.h
            |report(s: &Shape) -> int = s.area()
            |print(report(.unit()))
            |""".stripMargin) should include("A trait object expects no single type either")
    }

    // The expectation belongs to the whole expression, and the receiver of a `.x` is not it — so
    // the dot here has nothing to resolve against even though the type is written two lines up.
    "a receiver, since what the context expects is the whole expression's type" in {
      err("""struct Point
            |    x: int
            |    y: int
            |    origin() -> Point = Point(4, 5)
            |print(.origin().x)
            |""".stripMargin) should include("nothing here expects one")
    }

    // A weak reference expects a value something else keeps alive, so a freshly built one is
    // refused — and the refusal is the one the bare spelling gets rather than a complaint that
    // 'weak Colour' declares no such member.
    "a value built at a `weak` reference, which nothing would be holding" in {
      err(colour + "hold(c: weak Colour) -> int = 1\nprint(hold(.Red))") should
        include("a weak reference does not keep Colour alive")
    }

    "a name the expected type does not have" in {
      err(colour + "val c: Colour = .Rd\nprint(code(c))") should include("enum 'Colour' has no variant 'Rd'")
    }

    "a property, which is read on a value" in {
      err("""enum Colour
            |    Red
            |    code -> int = 1
            |val c: Colour = .code
            |print(1)
            |""".stripMargin) should include("'code' is a property of 'Colour'")
    }

    "a method, which is called on one" in {
      err("""enum Colour
            |    Red
            |    tone(self) -> int = 1
            |val c: Colour = .tone()
            |print(1)
            |""".stripMargin) should include("'tone' is an instance method of 'Colour' — call it on a value")
    }

    // The dot is the expression form's, and a pattern is already matched against a type it knows —
    // so a reader who writes the form they were just taught is told where the two differ rather
    // than being handed "a pattern expected" against a line whose subject is a variant name.
    "the form written in a pattern, where the type is already known" in {
      val e = err(colour + "val c: Colour = .Red\nval n = c match\n    .Red -> 1\n    else -> 2\nprint(n)")

      e should include("a pattern is matched against a type it already knows")
      e should include("with no leading dot")
    }
  }

  /** A line that begins with a dot, now that the leading-dot continuation style this form left room
    * for has actually arrived (`LineContinuationTests`).
    *
    * The comment here used to say the two readings "can never both be meant", which was the right
    * call and is why the continuation was available to take. What it did not anticipate is that
    * taking it makes the *continuation* the reading wherever there is a line above to continue — so
    * the refusal below is reached only where there is not one.
    */
  "a statement that begins with one" - {
    "is read as a continuation of the line above, where there is one to continue" in {
      err(colour + ".Red\nprint(1)") should include("cannot read field 'Red'")
    }

    // Nothing precedes it, so nothing is joined and the analyzer gets the statement the parser
    // always produced.
    "and where there is nothing above it, parses and is refused for having no expectation" in {
      err(".Red\n" + colour + "print(1)") should include("nothing here expects one")
    }
  }

  /** The one place the two spellings genuinely compete, pinned from this side as well as from
    * `LineContinuationTests`.
    *
    * A `match` arm's pattern begins a line, so an arm written with the expression form's dot looks
    * exactly like a chain continuing the header. The lexer declines to join after a reserved word —
    * nothing can be called on `match` — which is what leaves this diagnostic reachable at all.
    */
  "the leading-dot continuation does not swallow the first arm of a match" - {
    "so the pattern form is still refused in the words written for it" in {
      val e = err(colour + "val c: Colour = .Red\nval n = c match\n    .Red -> 1\n    else -> 2\nprint(n)")

      e should include("a pattern is matched against a type it already knows")
    }

    // And the ordinary chain, whose line above ends in a name rather than a keyword, is unaffected.
    "while a chain after an ordinary line still joins" in {
      run("import sysl.text.Search\n\nval n = \"  hi  \"\n    .trim()\n    .len\nprint(n)") shouldBe "2\n"
    }
  }

  "the grammar's three neighbours are unchanged" - {
    "a range, whose `..` and `..<` are single tokens" in {
      run("var t = 0\nfor i in 0..<3\n    t += i\nprint(t)") shouldBe "3\n"
    }

    "a tuple index, which is read where there is a value to its left" in {
      run("val t = (1, 2)\nprint(t.0, t.1)") shouldBe "1 2\n"
    }

    // A number begins with a digit, so `.5` was two tokens before this form existed and is two
    // tokens now. What the form buys is that the dot has an owner to complain: the reader is told
    // about the fraction they wrote rather than that an identifier was expected after a dot.
    "and a fraction written with no digit before the point, which is refused by name" in {
      err("print(.5)") should include("a number is written with a digit before the point")
    }
  }
}
