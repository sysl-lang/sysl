package sh.sysl

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

    // Under the field's own name, not under the `.` beside it — the message says `'y'`, so that is
    // where a reader is sent. `DiagnosticPositionTests` holds the general form of this.
    "on the selection that names a missing field" in {
      val src =
        """struct P
          |    x: int
          |var p = P(1)
          |print(p.y)
          |""".stripMargin

      diag(src) should include("--> t.sysl:4:9")
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

    "just after the '=' of a binding whose value was left on the next line" in {
      // A binding's value goes on its own line or on a continuation of it, and the caret lands in
      // the space that value should have occupied: column 8, immediately past the `=` in column 7.
      // A `const` written the same way has always reported there, and a `var` now agrees with it.
      val out = diag("var x =\n    1 + 2\nprint(str(x))\n")

      out should include("--> t.sysl:1:8")
      out should include("expression expected")
    }

    // The same mistake, one indent deeper. A function's body is parsed as part of the declaration,
    // so a body that will not parse makes the *declaration* fail — and what used to survive was the
    // position where the declaration was reconsidered as something else, which is its first line.
    //
    // The invariant this asserts is the one every other test in this section asserts: a diagnostic
    // points at the construct that is wrong. It pointed several lines above it, at a line that is
    // correct, and sent the reader looking at the signature.
    "on the binding inside a body, and not on the declaration the body belongs to" in {
      val src =
        """f(a: int) -> int
          |    val b =
          |        a + 1
          |
          |    b
          |end f
          |
          |print(str(f(1)))
          |""".stripMargin

      diag(src) should include("--> t.sysl:2:")
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
      // The vehicle is an array **parameter**, since a local one is promoted rather than reported
      // now (`05`). What is being checked is the caret, not the rule.
      val src =
        """sum(bytes: []u8) -> int = 0
          |
          |scratch(s: [4]u8) -> []u8
          |    s[..]
          |""".stripMargin
      val out = diag(src)

      out should include("would outlive the array")
      out should include("--> t.sysl:4:6")
    }

    /** A lexical error must say what the lexer knew, not what the grammar made of the wreckage.
     * Every message in `SyslLexical` used to be unreachable this way: the token it produced matched
     * no rule, so the reported failure was wherever the longest partial match stopped — `print("oops`
     * said "newline expected" at the paren, because `print` alone is a complete statement. The lexer
     * tests call the lexer directly and so could not see it.
     */
    "a lexical error reports itself rather than the grammar's reaction to it" in {
      diag("print(\"oops\n") shouldBe
        List(
          "error: unterminated string literal",
          " --> t.sysl:1:7",
          "  |",
          "1 | print(\"oops",
          "  |       ^",
        ).mkString("\n")
    }

    // The comment case is the one the lexer could not report on its own at all: an unclosed comment
    // consumes to end of input, so every later token is gone and the grammar's complaint is about
    // whatever the first line happened to end in.
    "an unclosed block comment says so, at the delimiter that opened it" in {
      val src =
        """print(1)
          |/* never closed
          |print(2)
          |""".stripMargin

      diag(src) shouldBe
        List(
          "error: unclosed comment",
          " --> t.sysl:2:1",
          "  |",
          "2 | /* never closed",
          "  | ^",
        ).mkString("\n")
    }

    "an unterminated character literal reports itself too" in {
      diag("var c = '1\n") should include("error: unterminated character literal")
    }

    // An inner close satisfies only the comment it opened, so a nested comment left open reports the
    // outer one — the case a depth counter gets wrong by treating the first `*/` as the end.
    "a nested comment left open reports the outer one" in {
      val src =
        """print(1)
          |/* outer /* inner */
          |print(2)
          |""".stripMargin
      val out = diag(src)

      out should include("error: unclosed comment")
      out should include("--> t.sysl:2:1")
    }

    // Indentation made of both tabs and spaces on one line has no defensible width, so it is
    // refused — and refused by the lexer, which is why it reaches a reader through the same route
    // an unterminated literal does.
    "indentation mixing tabs and spaces on one line is refused" in {
      val src = "f(n: int) -> int\n\tif n > 0 then\n    \tn\n\telse\n\t    0\n"
      val out = diag(src)

      out should include("only tabs or spaces")
      out should include("--> t.sysl:3:1")
    }

    // The negative names the library's own directory rather than a source name of the compiler's.
    // It used to say `<prelude>`, which was that string literal's `Source.name` — a name nothing
    // carries now, so the assertion would hold whatever the diagnostic pointed at.
    "an error about a library type still quotes the user's file" in {
      val src =
        """f() -> Option[int]
          |    Some("x")
          |""".stripMargin
      val out = diag(src)

      out should include("--> t.sysl:2:")
      out should not include "lib/sysl"
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

  "how many errors are reported" - {
    "stops at five, and says how many there were" in {
      val src = (1 to 8).map(n => s"var v$n = nope$n").mkString("\n")
      val out = diag(src)

      count(out) shouldBe 5
      at(out) shouldBe List(1, 2, 3, 4, 5)
      out should endWith("showing the first 5 of 8 errors")
    }

    "keeps the earliest five, since an error early in a file is the one worth reading" in {
      val src = (1 to 8).map(n => s"var v$n = nope$n").mkString("\n")

      diag(src) should include("undefined name 'nope1'")
      diag(src) should not include "nope6"
    }

    "says nothing extra when the count is exactly at the limit" in {
      val src = (1 to 5).map(n => s"var v$n = nope$n").mkString("\n")
      val out = diag(src)

      count(out) shouldBe 5
      out should not include "showing the first"
    }

    "caps escape errors the same way" in {
      val funcs = (1 to 7).map { n =>
        s"""f$n(a$n: [4]u8) -> []u8
           |    a$n[..]
           |""".stripMargin
      }.mkString("\n")
      val out = diag(funcs)

      count(out) shouldBe 5
      out should endWith("showing the first 5 of 7 errors")
    }
  }

  "counted things read as English" - {
    "one argument is an argument, not arguments" in {
      val src =
        """f(a: int) -> int = a
          |print(f(1, 2))
          |""".stripMargin

      diag(src) should include("takes 1 argument, but 2 arguments were given")
    }

    "one value given is 'was given', not 'were given'" in {
      val src =
        """struct P
          |    x: int
          |    y: int
          |var p = P(1)
          |""".stripMargin

      diag(src) should include("has 2 fields, but 1 value was given")
    }

    "and the plural forms still agree" in {
      val src =
        """f(a: int, b: int) -> int = a
          |print(f(1))
          |""".stripMargin

      diag(src) should include("takes 2 arguments, but 1 argument was given")
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
          |one(a: [4]u8) -> []u8
          |    a[..]
          |
          |two(b: [4]u8) -> []u8
          |    b[..]
          |""".stripMargin
      val out = diag(src)

      count(out) shouldBe 2
      at(out) shouldBe List(4, 7)
    }
  }

  "positions and structural equality" - {
    // A position is metadata a node carries, not part of what the node *is*, so the same text
    // parses to the same tree wherever it was read from. The `Program` around it is the one thing
    // that does differ: a file is a file, and which one it is is exactly what it records.
    "two parses of the same text from different files compare equal" in {
      def bodyOf(name: String) = SyslParser.parse("var x = 1 + 2", name).map(_.body)

      bodyOf("a.sysl") shouldBe bodyOf("b.sysl")
    }

    "even though each tree really does carry its own file's positions" in {
      def sourceOf(name: String): Option[String] =
        SyslParser.parse("var x = 1 + 2", name) match {
          case Right(Program(List(VarDecl(_, _, Some(init), _, _)), _, _, _, _, _)) => init.pos.map(_.source.name)
          case other                                                            => fail(s"unexpected parse: $other")
        }

      sourceOf("a.sysl") shouldBe Some("a.sysl")
      sourceOf("b.sysl") shouldBe Some("b.sysl")
    }
  }
}
