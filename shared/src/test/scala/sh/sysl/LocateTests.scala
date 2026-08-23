package sh.sysl

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

/** Resolving a place in a file to the constructs it is inside — the query an editor makes and no
 * pass of the compiler ever does.
 *
 * The assertions here are mostly about *which* node comes back rather than about spans, because the
 * spans are `DiagnosticTests`' subject and this is about the lookup over them.
 */
class LocateTests extends AnyFreeSpec with Matchers {

  private def parsed(src: String): Program =
    SyslParser.parse(src, "t.sysl") match
      case Right(p) => p
      case Left(e)  => fail(s"the fixture does not parse: $e")

  /** The chain of constructs at a place, named by their case-class names — which is what these
   * tests are about, and is far easier to read than the nodes themselves.
   */
  private def chain(src: String, line: Int, col: Int): List[String] =
    Locate.at(parsed(src), line, col).map(_.getClass.getSimpleName)

  private val src = "var x = 1 + 2\nprint(alpha)\n"

  "the chain of constructs at a place" - {
    "runs from the statement down to the smallest thing the cursor is in" in {
      chain(src, 1, 9) shouldBe List("VarDecl", "Binary", "IntLit")
    }

    "and names the innermost one last, which is what hover wants" in {
      Locate.innermost(parsed(src), 1, 9) shouldBe Some(IntLit(1, None))
    }

    "is empty where nothing covers the place at all" in {
      chain(src, 9, 1) shouldBe Nil
      Locate.innermost(parsed(src), 9, 1) shouldBe None
    }
  }

  /** The lookup reads `extent` and not `pos`, and these are the places where that is the whole
   * difference: a diagnostic about a call is anchored on its callee, so half of `print(alpha)` is
   * outside the call as far as `pos` is concerned and inside it as far as a cursor is.
   */
  "a cursor is inside what it is written inside, not inside what a diagnostic would point at" - {
    "on the callee, where the two agree" in {
      Locate.innermost(parsed(src), 2, 2).map(_.getClass.getSimpleName) shouldBe Some("Ident")
    }

    "on the closing bracket, where nothing points a diagnostic and the call still covers it" in {
      chain(src, 2, 12) shouldBe List("ExprStmt", "Call")
    }

    "and inside an argument, which is inside the call in turn" in {
      chain(src, 2, 8) shouldBe List("ExprStmt", "Call", "Ident")
    }
  }

  /** A span's end is exclusive everywhere else in the compiler, so it is exclusive here. The cost
   * is that a cursor parked just past a name is not on it; the benefit is that two adjacent tokens
   * are never both under one place.
   */
  "the end of a span is exclusive" - {
    // `alpha` occupies columns 7 to 11, so its span ends at 12.
    "the last character of a name is inside it" in {
      chain(src, 2, 11) shouldBe List("ExprStmt", "Call", "Ident")
    }

    "and the place just past it is not" in {
      chain(src, 2, 12) should not contain "Ident"
    }
  }

  "constructs other than expressions" - {
    // A type is written where no expression may stand, so a cursor in one reaches a family of node
    // the walk would silently miss if it only ever descended through expressions.
    "a type in a declaration" in {
      chain("var x: int = 1\n", 1, 8) should contain("NamedType")
    }

    "a whole function, from its name to the end of its body" in {
      val f = "add(a: int, b: int) -> int =\n    a + b\n"

      chain(f, 2, 5).head shouldBe "FuncDecl"
    }

    "a nested call, whose chain has both calls in it" in {
      val nested = "add(a: int, b: int) -> int = a + b\nprint(add(1, 2))\n"

      chain(nested, 2, 11) shouldBe List("ExprStmt", "Call", "Call", "IntLit")
    }
  }

  /** A literate file's program is lexed with its left margin removed, so the compiler's columns are
   * not the file's. A caller holding the file has to say so, and this is the arithmetic it does.
   */
  "a literate file counts columns without its margin" in {
    val program = SyslParser.parse(Source("t.lsysl", "    var x = 1\n")) match
      case Right(p) => p
      case Left(e)  => fail(s"the fixture does not parse: $e")

    program.source.columnOffset shouldBe 4
    Locate.at(program, 1, 5 - program.source.columnOffset).map(_.getClass.getSimpleName) shouldBe
      List("VarDecl")
  }
}
