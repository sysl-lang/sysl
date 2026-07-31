package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** `sizeof(T)` and `alignof(T)` parse to a `LayoutOf` over the **type** grammar (`03 § Reinterpreting
 * storage`).
 *
 * They are parser forms rather than calls the analyzer recognizes by name, and the reason is visible
 * here: an argument list holds expressions, so `sizeof(*Node)` read as a call would be a dereference
 * of a name and `sizeof([16]u8)` would not parse at all.
 */
class RawStorageParserTests extends AnyFreeSpec with ParseSupport {

  private def lastExpr(src: String): Expr =
    prog(src).reverse.collectFirst { case ExprStmt(e) => e }.getOrElse(fail("expected a trailing expression"))

  "the operand is read with the type grammar, not the expression grammar" - {
    "a named type" in {
      lastExpr("sizeof(int)") shouldBe LayoutOf("sizeof", NamedType("int"))
    }
    "a pointer, which as an expression would be a dereference" in {
      lastExpr("sizeof(*Node)") shouldBe LayoutOf("sizeof", PtrType(NamedType("Node")))
    }
    "an array, which is not an expression at all" in {
      lastExpr("sizeof([16]u8)") shouldBe LayoutOf("sizeof", ArrayType(Some(i(16)), NamedType("u8")))
    }
    "a slice" in {
      lastExpr("sizeof([]int)") shouldBe LayoutOf("sizeof", ArrayType(None, NamedType("int")))
    }
    "a reference" in {
      lastExpr("sizeof(&Node)") shouldBe LayoutOf("sizeof", RefType(NamedType("Node"), sync = false))
    }
    "a tuple" in {
      lastExpr("sizeof((int, real))") shouldBe
        LayoutOf("sizeof", TupleType(List(NamedType("int"), NamedType("real"))))
    }
    "an applied generic" in {
      lastExpr("sizeof(Option[int])") shouldBe LayoutOf("sizeof", NamedType("Option", List(NamedType("int"))))
    }
  }

  "'alignof' is the same form under the other word" in {
    lastExpr("alignof(*Node)") shouldBe LayoutOf("alignof", PtrType(NamedType("Node")))
  }

  "it is a primary, so an operator around it binds outside it" - {
    "a following operator" in {
      lastExpr("sizeof(int) * 4") shouldBe Binary("*", LayoutOf("sizeof", NamedType("int")), i(4))
    }
    "a preceding one" in {
      lastExpr("8 - alignof(int)") shouldBe Binary("-", i(8), LayoutOf("alignof", NamedType("int")))
    }
  }

  /** There is no value form. C accepts `sizeof x` too, and the two disagree in the one case that
   * matters — `sizeof arr` against `sizeof ptr` after a decay. sysl has no decay, so the value form
   * would buy only the confusion, and somebody reaching for it is told what to write.
   */
  "a value operand is refused by name rather than left to a parse error" - {
    "with no parentheses" in {
      val e = progError("var n = 3\nprint(sizeof n)")
      e should include("'sizeof' takes a type in parentheses")
      e should include("there is no form that takes a value")
    }
    "'alignof' says the same in its own words" in {
      progError("var n = 3\nprint(alignof n)") should include("'alignof' takes a type in parentheses")
    }
  }

  /** Both are reserved words, which is what lets the parser insist on the parenthesized type. The
   * cost is that neither may name anything, and that cost is pinned here rather than discovered.
   */
  "neither word may be used as a name" - {
    "'sizeof'" in {
      progError("var sizeof = 1") should not be empty
    }
    "'alignof'" in {
      progError("var alignof = 1") should not be empty
    }
  }
}
