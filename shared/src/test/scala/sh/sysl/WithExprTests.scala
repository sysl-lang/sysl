package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** `base with { bg = ACCENT }` — the value `base` again, with the fields named here changed
 * (`reference/expressions.md § with`).
 *
 * The rule is one sentence and every case below is a consequence of it: the form is the two
 * statements a reader writes today — a copy bound to a name, an assignment per field, the copy —
 * written as one expression. So the tests come in two groups. The first is what the form *buys*,
 * which is that it composes: a layered style is one expression rather than a `var` and a block, and
 * it chains. The second is that every rule it obeys is an assignment's, checked by writing the two
 * spellings side by side and asking for the same answer.
 *
 * The refusals are the part the desugaring could not have supplied. A base that is not a struct is
 * the one reading it gets *wrong* rather than merely unhandled — binding a second reference to one
 * object writes through to every other holder — so that case is refused by name, with the spelling
 * that copies.
 */
class WithExprTests extends AnyFreeSpec with RunSupport with CodegenSupport {

  private val style =
    """struct Style
      |    fg: int
      |    bg: int
      |    pad: int
      |""".stripMargin

  "what the form buys" - {

    "one field changed, and the base is untouched" in {
      run(style + "val base = Style(1, 2, 3)\nval hot = base with { bg = 9 }\n" +
        "print(base.fg, base.bg, base.pad)\nprint(hot.fg, hot.bg, hot.pad)") shouldBe "1 2 3\n1 9 3\n"
    }

    "several fields at once, written one per line" in {
      run(style +
        """val base = Style(1, 2, 3)
          |val hot = base with {
          |    bg = 9,
          |    pad = 8,
          |}
          |print(hot.fg, hot.bg, hot.pad)
          |""".stripMargin) shouldBe "1 9 8\n"
    }

    "it layers, which is the case a style has and the two-line form cannot reach" in {
      run(style +
        """val theme = Style(1, 2, 3)
          |val pressed = theme with { bg = 9 }
          |val disabled = pressed with { fg = 7 }
          |print(theme.fg, theme.bg)
          |print(pressed.fg, pressed.bg)
          |print(disabled.fg, disabled.bg)
          |""".stripMargin) shouldBe "1 2\n1 9\n7 9\n"
    }

    "and it chains in one expression" in {
      run(style + "val base = Style(1, 2, 3)\nval hot = base with { bg = 9 } with { pad = 8 }\n" +
        "print(hot.fg, hot.bg, hot.pad)") shouldBe "1 9 8\n"
    }

    // The whole of what the card asked for: a style layered **at the point it is used**, which is
    // what a `var` and two statements cannot be.
    "it sits inside a larger expression" in {
      run(style + "sum(s: Style) -> int = s.fg + s.bg + s.pad\nval base = Style(1, 2, 3)\n" +
        "print(sum(base with { bg = 9 }))") shouldBe "13\n"
    }

    /** The base is bound before anything is written, so a base that is a *call* is called once.
     * Re-evaluating it per field would be invisible in every test above, all of whose bases are
     * names.
     */
    "the base is evaluated once, however many fields change" in {
      run(style +
        """var calls = 0
          |theme() -> Style
          |    calls += 1
          |    return Style(1, 2, 3)
          |val hot = theme() with { bg = 9, pad = 8, fg = 7 }
          |print(hot.fg, hot.bg, hot.pad, calls)
          |""".stripMargin) shouldBe "7 9 8 1\n"
    }

    "a counted field is carried into the copy and outlives the base's scope" in {
      run(
        """struct Label
          |    text: string
          |struct Card
          |    n: int
          |    tag: &Label
          |val base = Card(1, Label("hi"))
          |val other = base with { n = 2 }
          |print(base.n, base.tag.text)
          |print(other.n, other.tag.text)
          |""".stripMargin) shouldBe "1 hi\n2 hi\n"
    }

    "a generic struct, at each instantiation" in {
      run(
        """struct Box[T]
          |    v: T
          |    n: int
          |val bi = Box(1, 0) with { n = 5 }
          |val bs = Box("a", 0) with { v = "b" }
          |print(bi.v, bi.n)
          |print(bs.v, bs.n)
          |""".stripMargin) shouldBe "1 5\nb 0\n"
    }

    // The value is analyzed against the field's type, which is the whole reason a literal needs no
    // suffix here — the same rule a named argument at a construction follows.
    "the field's type is what the value is read against, so a literal needs no suffix" in {
      run(
        """struct Style
          |    bg: u32
          |    pad: usize
          |val base = Style(0, 0)
          |val hot = base with { bg = 0x3A6EA5, pad = 4 }
          |print(hot.bg, hot.pad)
          |""".stripMargin) shouldBe "3829413 4\n"
    }
  }

  "every rule it obeys is the assignment's" - {

    "a struct's invariant is rechecked, and a change that breaks it traps" in {
      val account =
        """struct Account
          |    balance: int
          |    invariant balance >= 0
          |""".stripMargin

      run(account + "val a = Account(5)\nprint((a with { balance = 8 }).balance)") shouldBe "8\n"
      exits(account + "val a = Account(5)\nval bad = a with { balance = -1 }\nprint(bad.balance)")
    }

    "a settable property runs its setter" in {
      run(
        """struct Cell
          |    v: int
          |
          |    count -> int = self.v
          |
          |    set count(x)
          |        self.v = x * 10
          |val c = Cell(0)
          |print((c with { count = 4 }).v)
          |""".stripMargin) shouldBe "40\n"
    }

    "the fields are changed in the order written" in {
      val log =
        """struct Log
          |    seen: int
          |
          |    a -> int = 0
          |
          |    set a(x)
          |        self.seen = self.seen * 10 + 1
          |
          |    b -> int = 0
          |
          |    set b(x)
          |        self.seen = self.seen * 10 + 2
          |val l = Log(0)
          |""".stripMargin

      run(log + "print((l with { a = 0, b = 0 }).seen)") shouldBe "12\n"
      run(log + "print((l with { b = 0, a = 0 }).seen)") shouldBe "21\n"
    }

    "a field another module keeps private is refused here as it is at an assignment" in {
      val lib =
        """module shape
          |struct Box
          |    private n: int
          |    w: int
          |    make() -> Box = Box(1, 2)
          |""".stripMargin
      val use = "import shape.*\nval b = Box.make()\nval c = b with { n = 9 }\nprint(c.w)"

      errOf("shape.sysl" -> lib, "main.sysl" -> use) should include("'n'")
    }

    // The sentence, not the caret: the two spellings put the value in different columns, and it is
    // the complaint that has to be the same one.
    "a value the field's type does not take is refused in the assignment's words" in {
      def sentence(src: String): String = err(src).linesIterator.next()

      sentence(style + "val base = Style(1, 2, 3)\nval hot = base with { bg = \"nine\" }\nprint(hot.bg)") shouldBe
        sentence(style + "var base = Style(1, 2, 3)\nbase.bg = \"nine\"\nprint(base.bg)")
    }

    "a field the struct does not have is refused in the assignment's words, at the name" in {
      err(style + "val base = Style(1, 2, 3)\nval hot = base with { bgg = 9 }\nprint(hot.bg)") should
        include("'Style' has no field or property 'bgg'")
    }
  }

  "what the desugaring could not have said itself" - {

    /** The case the rule exists for. `var tmp = p` where `p` is a `&Style` binds a *second
     * reference*, so the writes land on the one object every other holder can see — the form would
     * have compiled, run, and meant the opposite of a copy.
     */
    "a counted reference is refused, with the spelling that copies" in {
      val msg = err(
        """struct Style
          |    bg: int
          |val base: &Style = Style(2)
          |val hot = base with { bg = 9 }
          |print(hot.bg)
          |""".stripMargin)

      msg should include("'with' copies a struct, and this is a counted reference to one")
      msg should include("'(*base) with { … }'")
    }

    "a pointer is refused for the same reason" in {
      err(
        """struct Style
          |    bg: int
          |var base = Style(2)
          |val p = &base
          |val hot = p with { bg = 9 }
          |print(hot.bg)
          |""".stripMargin) should include("this is a pointer to one")
    }

    // …and the spelling the refusal advises is the one that works, which is the half a quoted
    // message never checks.
    "and dereferencing first is what the refusal advised" in {
      run(
        """struct Style
          |    bg: int
          |    pad: int
          |var base = Style(2, 3)
          |val p = &base
          |val hot = (*p) with { bg = 9 }
          |print(base.bg, hot.bg, hot.pad)
          |""".stripMargin) shouldBe "2 9 3\n"
    }

    "a scalar is refused by naming what it is" in {
      err("val n = 3\nval m = n with { bg = 9 }\nprint(m)") should
        include("'with' copies a struct and changes some of its fields, and 'int' is not a struct")
    }

    "a tuple is refused by naming why it has nothing to write" in {
      err("val t = (1, 2)\nval u = t with { n = 9 }\nprint(u.0)") should
        include("a tuple's parts are named for their positions")
    }

    "the same field twice is refused, because the first change would be thrown away" in {
      err(style + "val base = Style(1, 2, 3)\nval hot = base with { bg = 9, bg = 8 }\nprint(hot.bg)") should
        include("'bg' is changed twice in one 'with'")
    }

    "no fields at all is refused by name" in {
      err(style + "val base = Style(1, 2, 3)\nval hot = base with { }\nprint(hot.bg)") should
        include("'with' changes at least one field")
    }

    "and a clause with no braces says what the form takes" in {
      err(style + "val base = Style(1, 2, 3)\nval hot = base with bg = 9\nprint(hot.bg)") should
        include("'with' takes the fields to change in braces")
    }
  }

  "where it sits in the grammar" - {

    // A tail rather than a binary operator, so it binds as tightly as a field selection: the change
    // lands on the operand it is written after and not on the sum.
    "it binds tighter than arithmetic, so it changes the operand it follows" in {
      run(
        """struct N
          |    v: int
          |val a = N(1)
          |val b = N(2)
          |print(a.v + (b with { v = 9 }).v)
          |""".stripMargin) shouldBe "10\n"
    }

    "a selection after the clause reads the changed value" in {
      run(style + "val base = Style(1, 2, 3)\nprint(base with { bg = 9 }.bg)") shouldBe "9\n"
    }

    /** `with` is a soft word, so it is still a name everywhere else — which is the whole of what
     * choosing one over a reserved word buys, and the only thing that can check it.
     */
    "'with' is not reserved, so it stays a legal name" in {
      run("val with = 3\nprint(with)") shouldBe "3\n"
    }

    "including as a field's own name, on both sides of the clause" in {
      run(
        """struct Flags
          |    with: int
          |val f = Flags(1)
          |print((f with { with = 9 }).with)
          |""".stripMargin) shouldBe "9\n"
    }
  }

}
