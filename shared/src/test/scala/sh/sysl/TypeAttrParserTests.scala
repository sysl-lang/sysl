package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** `T::Attr` parses to a `TypeAttr`, and `T::Attr(x)` to a `Call` over one — the same shape an
 * enum's `E.variant` and `E.assoc(x)` take through `Field`, but reached with `::` because the name
 * belongs to the type rather than to a value.
 */
class TypeAttrParserTests extends AnyFreeSpec with ParseSupport {

  private def lastExpr(src: String): Expr =
    prog(src).reverse.collectFirst { case ExprStmt(e) => e }.getOrElse(fail("expected a trailing expression"))

  "a bare attribute is a TypeAttr on the type name" in {
    lastExpr("Age::First") shouldBe TypeAttr(Ident("Age"), "First")
  }

  "an attribute with an argument is a call over the TypeAttr" in {
    lastExpr("Age::Succ(x)") shouldBe Call(TypeAttr(Ident("Age"), "Succ"), List(Ident("x")))
  }

  "`::` binds tighter than a following operator" in {
    lastExpr("Age::Last + 1") shouldBe Binary("+", TypeAttr(Ident("Age"), "Last"), i(1))
  }
}
