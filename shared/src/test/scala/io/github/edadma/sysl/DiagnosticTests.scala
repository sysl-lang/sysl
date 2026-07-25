package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

/** Diagnostics carry a source location and render it: the file, the line, the column, the
 * offending line quoted, and a caret under the column.
 *
 * These assert the *rendering* and the *choice of position*, which are two separable things —
 * a diagnostic that quotes the wrong line is as useless as one that quotes none.
 */
class DiagnosticTests extends AnyFreeSpec with Matchers {

  /** The rendered diagnostic for a program that must be rejected. */
  private def diag(src: String, name: String = "t.sysl"): String =
    Compiler.compileToLlvm(src, name) match {
      case Left(e)  => e
      case Right(_) => fail(s"expected an error from:\n$src")
    }

  "rendering" - {
    "names the file, the line, and the column, and points a caret at it" in {
      diag("var x = 1\nprint(nope)") shouldBe
        List(
          "error: undefined name 'nope'",
          " --> t.sysl:2:7",
          "  |",
          "2 | print(nope)",
          "  |       ^",
        ).mkString("\n")
    }

    "keeps the gutter aligned when the line number takes two digits" in {
      val lines = (1 to 9).map(n => s"var a$n = $n").mkString("\n")

      diag(s"$lines\nprint(nope)") shouldBe
        List(
          "error: undefined name 'nope'",
          "  --> t.sysl:10:7",
          "   |",
          "10 | print(nope)",
          "   |       ^",
        ).mkString("\n")
    }

    "carries whatever name the driver gave the source" in {
      diag("print(nope)", "src/deep/thing.sysl") should include("--> src/deep/thing.sysl:1:7")
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
      diag("add(a: int, b: int) -> int = a + b\nprint(add(1, \"two\"))") should
        include("--> t.sysl:2:14")
    }

    "on the selection that names a missing field" in {
      diag("struct P\n    x: int\nvar p = P(1)\nprint(p.y)") should include("--> t.sysl:4:8")
    }

    "on the construct that raised the error, not on the last thing it looked at" in {
      // The branch types are compared *after* both branches are analyzed, so a position that
      // was not restored would point at the `"s"` in column 30 instead of the `if` in column 9.
      val out = diag("var x = 1\nvar y = if x > 0 then 1 else \"s\"")

      out should include("if branches have different types")
      out should include("--> t.sysl:2:9")
    }

    "on the declaration that redeclares a name" in {
      diag("f() -> int = 1\nf() -> int = 2") should include("--> t.sysl:2:1")
    }

    "on the type reference that names nothing" in {
      diag("var x: Nope = 1") should include("--> t.sysl:1:8")
    }
  }

  "other passes" - {
    "a parse error renders like any other diagnostic" in {
      diag("var a = 1\nvar = 5") shouldBe
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
      val src = "sum(bytes: []u8) -> int = 0\n\nscratch() -> []u8\n    var s: [4]u8\n    s[..]"
      val out = diag(src)

      out should include("would outlive the array")
      out should include("--> t.sysl:5:6")
    }

    "an error about a prelude type still quotes the user's file" in {
      val out = diag("f() -> Option[int]\n    Some(\"x\")")

      out should include("--> t.sysl:2:")
      out should not include "<prelude>"
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
