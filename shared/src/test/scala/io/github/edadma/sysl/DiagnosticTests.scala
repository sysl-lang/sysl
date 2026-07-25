package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

/** Diagnostics: where they point, how they render, and how many of them one compilation yields.
 *
 * These assert the *rendering*, the *choice of position*, and the *recovery* — three separable
 * things. A diagnostic that quotes the wrong line is as useless as one that quotes none, and a
 * compiler that stops at the first mistake makes the other two matter less than they should.
 */
class DiagnosticTests extends AnyFreeSpec with Matchers {

  /** The rendered diagnostics for a program that must be rejected. */
  private def diag(src: String, name: String = "t.sysl"): String =
    Compiler.compileToLlvm(src, name) match {
      case Left(e)  => e
      case Right(_) => fail(s"expected an error from:\n$src")
    }

  /** How many separate errors a compilation reported. */
  private def count(out: String): Int = out.linesIterator.count(_.startsWith("error: "))

  /** The source lines the errors point at, in the order they were reported. */
  private def at(out: String): List[Int] =
    """--> t\.sysl:(\d+):""".r.findAllMatchIn(out).map(_.group(1).toInt).toList

  "rendering" - {
    "names the file, the line, and the column, and points a caret at it" in {
      val src =
        """var x = 1
          |print(nope)
          |""".stripMargin

      diag(src) shouldBe
        List(
          "error: undefined name 'nope'",
          " --> t.sysl:2:7",
          "  |",
          "2 | print(nope)",
          "  |       ^",
        ).mkString("\n")
    }

    "keeps the gutter aligned when the line number takes two digits" in {
      val padding = (1 to 9).map(n => s"var a$n = $n").mkString("\n")

      diag(s"$padding\nprint(nope)\n") shouldBe
        List(
          "error: undefined name 'nope'",
          "  --> t.sysl:10:7",
          "   |",
          "10 | print(nope)",
          "   |       ^",
        ).mkString("\n")
    }

    "carries whatever name the driver gave the source" in {
      diag("print(nope)\n", "src/deep/thing.sysl") should include("--> src/deep/thing.sysl:1:7")
    }

    "indents the caret with tabs where the line has tabs, so it lands under the column" in {
      Pos(Source("t.sysl", "a\tb\n"), 1, 3).render("boom") shouldBe
        List(
          "error: boom",
          " --> t.sysl:1:3",
          "  |",
          "1 | a\tb",
          "  |  \t^",
        ).mkString("\n")
    }

    "renders a message with no position at all rather than inventing one" in {
      Diagnostic.render("something went wrong", None) shouldBe "error: something went wrong"
    }

    "survives a column past the end of its line" in {
      Pos(Source("t.sysl", "ab"), 1, 99).render("boom") should include("|   ^")
    }
  }

  "where the caret lands" - {
    "on the argument that is wrong, not on the call" in {
      // `"two"` starts at column 14 of line 2; the call starts at column 7.
      val src =
        """add(a: int, b: int) -> int = a + b
          |print(add(1, "two"))
          |""".stripMargin

      diag(src) should include("--> t.sysl:2:14")
    }

    "on the callee, for a call that names nothing or takes other arguments" in {
      val src =
        """add(a: int, b: int) -> int = a + b
          |print(missing())
          |print(add(1))
          |""".stripMargin
      val out = diag(src)

      out should include("--> t.sysl:2:7")
      out should include("--> t.sysl:3:7")
    }

    "on the selection that names a missing field" in {
      val src =
        """struct P
          |    x: int
          |var p = P(1)
          |print(p.y)
          |""".stripMargin

      diag(src) should include("--> t.sysl:4:8")
    }

    "on the construct that raised the error, not on the last thing it looked at" in {
      // The branch types are compared *after* both branches are analyzed, so a position that
      // was not restored would point at the `"s"` in column 30 instead of the `if` in column 9.
      val src =
        """var x = 1
          |var y = if x > 0 then 1 else "s"
          |""".stripMargin
      val out = diag(src)

      out should include("if branches have different types")
      out should include("--> t.sysl:2:9")
    }

    "on the declaration that redeclares a name" in {
      val src =
        """f() -> int = 1
          |f() -> int = 2
          |""".stripMargin

      diag(src) should include("--> t.sysl:2:1")
    }

    "on the type reference that names nothing" in {
      diag("var x: Nope = 1\n") should include("--> t.sysl:1:8")
    }
  }

  "other passes" - {
    "a parse error renders like any other diagnostic" in {
      val src =
        """var a = 1
          |var = 5
          |""".stripMargin

      diag(src) shouldBe
        List(
          "error: identifier expected",
          " --> t.sysl:2:5",
          "  |",
          "2 | var = 5",
          "  |     ^",
        ).mkString("\n")
    }

    "a parse error that runs out of input still renders against the source" in {
      val out = diag("f(a: int")

      out should include("--> t.sysl:")
      out should include("f(a: int")
    }

    "an escape error points at the slice that leaves the frame" in {
      val src =
        """sum(bytes: []u8) -> int = 0
          |
          |scratch() -> []u8
          |    var s: [4]u8
          |    s[..]
          |""".stripMargin
      val out = diag(src)

      out should include("would outlive the array")
      out should include("--> t.sysl:5:6")
    }

    "an error about a prelude type still quotes the user's file" in {
      val src =
        """f() -> Option[int]
          |    Some("x")
          |""".stripMargin
      val out = diag(src)

      out should include("--> t.sysl:2:")
      out should not include "<prelude>"
    }
  }

  "reporting more than one error" - {
    "reports every independent mistake, in source order" in {
      val src =
        """var a = nope1
          |var b = nope2
          |var c = nope3
          |""".stripMargin
      val out = diag(src)

      count(out) shouldBe 3
      at(out) shouldBe List(1, 2, 3)
    }

    "separates one diagnostic from the next with a blank line" in {
      val src =
        """var a = nope1
          |var b = nope2
          |""".stripMargin

      diag(src) should include("^\n\nerror:")
    }

    "keeps going through the statements of a single function body" in {
      val src =
        """f() -> int
          |    var a = nope1
          |    var b = nope2
          |    3
          |""".stripMargin
      val out = diag(src)

      count(out) shouldBe 2
      at(out) shouldBe List(2, 3)
    }

    "keeps going from one function to the next" in {
      val src =
        """f() -> int = nope1
          |g() -> int = nope2
          |h() -> int = 3
          |print(h())
          |""".stripMargin
      val out = diag(src)

      count(out) shouldBe 2
      at(out) shouldBe List(1, 2)
    }

    "keeps going through declarations, so a bad one does not hide a later one" in {
      val src =
        """struct P
          |    x: Nope
          |struct Q
          |    y: AlsoNope
          |""".stripMargin
      val out = diag(src)

      count(out) shouldBe 2
      at(out) shouldBe List(2, 4)
    }

    "reports a whole file's worth of unrelated mistakes at once" in {
      val src =
        """add(a: int, b: int) -> int = a + b
          |
          |struct Point
          |    x: int
          |    y: int
          |end Point
          |
          |var p = Point(1, 2)
          |print(add(1, "two"))
          |print(p.zed)
          |print(other)
          |print(add(1))
          |""".stripMargin
      val out = diag(src)

      count(out) shouldBe 4
      at(out) shouldBe List(9, 10, 11, 12)
    }
  }

  "not reporting the consequences of an error twice" - {
    "a name whose initializer failed is still bound, so later uses stay quiet" in {
      val src =
        """f() -> int
          |    var a = nope
          |    var b = a + 1
          |    var c = a * a
          |    b + c
          |""".stripMargin
      val out = diag(src)

      count(out) shouldBe 1
      at(out) shouldBe List(2)
    }

    "but a *different* mistake after one is still reported" in {
      val src =
        """f() -> int
          |    var a = nope
          |    var b = a + 1
          |    var c = alsoNope
          |    b + c
          |""".stripMargin
      val out = diag(src)

      count(out) shouldBe 2
      at(out) shouldBe List(2, 4)
    }

    "a parameter whose type does not resolve does not make its function undefined too" in {
      val src =
        """f(a: Nope) -> int = 1
          |print(f(1))
          |print(f(2))
          |""".stripMargin
      val out = diag(src)

      count(out) shouldBe 1
      out should include("Nope")
      out should not include "undefined function"
    }

    "one mistake in a generic body is one error however many times it is instantiated" in {
      val src =
        """bad[T](x: T) -> int
          |    nope
          |
          |print(bad(1))
          |print(bad("s"))
          |print(bad(true))
          |""".stripMargin
      val out = diag(src)

      count(out) shouldBe 1
      at(out) shouldBe List(2)
    }
  }

  "state left consistent by an abandoned region" - {
    "a field whose type does not resolve does not make later mentions look like a cycle" in {
      // The resolver marks a type as in-progress while it works out the fields. Leaving that mark
      // behind when a field fails made the next mention of the type report that it contains
      // itself — a diagnostic about nothing at all, on top of the real one.
      val src =
        """struct P
          |    x: Nope
          |    y: int
          |end P
          |
          |var p = P(1, 2)
          |print(p.x)
          |print(p.y)
          |""".stripMargin
      val out = diag(src)

      count(out) shouldBe 1
      out should include("unknown type 'Nope'")
      out should not include "contains itself"
    }

    "a block abandoned part-way does not leak its bindings to the statements after it" in {
      // The `if` opens a scope that the failing branch never closes. Winding it back is what
      // keeps `hidden` out of scope on the last line — without it the analyzer would be more
      // permissive after an error than before one, and quietly accept this.
      val src =
        """f() -> int
          |    var x = if true then
          |        var hidden = 1
          |        nope
          |    else 2
          |    hidden
          |""".stripMargin
      val out = diag(src)

      count(out) shouldBe 2
      at(out) shouldBe List(4, 6)
      out should include("undefined name 'hidden'")
    }

    "every function that lets a slice escape is reported, not just the first" in {
      val src =
        """sum(bytes: []u8) -> int = 0
          |
          |one() -> []u8
          |    var a: [4]u8
          |    a[..]
          |
          |two() -> []u8
          |    var b: [4]u8
          |    b[..]
          |""".stripMargin
      val out = diag(src)

      count(out) shouldBe 2
      at(out) shouldBe List(5, 9)
    }
  }

  "positions and structural equality" - {
    "two parses of the same text from different files compare equal" in {
      SyslParser.parse("var x = 1 + 2", "a.sysl") shouldBe SyslParser.parse("var x = 1 + 2", "b.sysl")
    }

    "even though each tree really does carry its own file's positions" in {
      def sourceOf(name: String): Option[String] =
        SyslParser.parse("var x = 1 + 2", name) match {
          case Right(Program(List(VarDecl(_, _, Some(init))))) => init.pos.map(_.source.name)
          case other                                           => fail(s"unexpected parse: $other")
        }

      sourceOf("a.sysl") shouldBe Some("a.sysl")
      sourceOf("b.sysl") shouldBe Some("b.sysl")
    }
  }
}
