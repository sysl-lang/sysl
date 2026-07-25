package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** Parsing of statements and whole programs: declarations, sequencing, error reporting. */
class StatementParserTests extends AnyFreeSpec with ParseSupport {

  "a sequence of statements" in {
    prog("var x = 1\nprint(x)") shouldBe List(
      VarDecl("x", None, Some(i(1))),
      printStmt(Ident("x")),
    )
  }

  "a var with a type annotation" in {
    prog("var n: int = 0") shouldBe List(VarDecl("n", Some(NamedType("int")), Some(i(0))))
  }

  "a parse error reports a location" in {
    SyslParser.parse("var = 5", "sample.sysl") match {
      case Left(msg) => msg should include("--> sample.sysl:1:5")
      case Right(p)  => fail(s"expected a parse error, got $p")
    }
  }
}
