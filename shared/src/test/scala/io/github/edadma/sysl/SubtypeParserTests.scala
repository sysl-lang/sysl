package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** A `type` declaration parses to a `TypeDecl` carrying its base type reference and, when written,
 * a `within` range and a `where` predicate. `new`, `within`, and `where` are contextual keywords —
 * the parser recognises them only here, so they remain ordinary identifiers elsewhere.
 */
class SubtypeParserTests extends AnyFreeSpec with ParseSupport {

  private def typeDecl(src: String): TypeDecl =
    prog(src).collectFirst { case t: TypeDecl => t }.getOrElse(fail("expected a type declaration"))

  private def named(n: String): TypeRef = NamedType(n, Nil)
  private def cmp(op: String, l: Expr, r: Expr): Expr = Compare(List(l, r), List(op))

  "within" - {
    "an inclusive integer range keeps both bounds and is not exclusive" in {
      typeDecl("type Age = int within 0..150") shouldBe
        TypeDecl("Age", named("int"), derived = false, Some(RangeBound(i(0), i(150), exclusiveHi = false)), None)
    }

    "an exclusive float range marks the upper bound excluded" in {
      typeDecl("type Prob = f64 within 0.0..<1.0") shouldBe
        TypeDecl(
          "Prob",
          named("f64"),
          derived = false,
          Some(RangeBound(FloatLit("0.0", None), FloatLit("1.0", None), exclusiveHi = true)),
          None,
        )
    }

    "a character range carries char-literal bounds" in {
      typeDecl("type Letter = char within 'a'..'z'") shouldBe
        TypeDecl(
          "Letter",
          named("char"),
          derived = false,
          Some(RangeBound(CharLit('a'.toInt), CharLit('z'.toInt), exclusiveHi = false)),
          None,
        )
    }

    "a negative lower bound parses as a unary negation" in {
      typeDecl("type Delta = int within -5..5") shouldBe
        TypeDecl(
          "Delta",
          named("int"),
          derived = false,
          Some(RangeBound(Unary("-", i(5)), i(5), exclusiveHi = false)),
          None,
        )
    }
  }

  "where carries the predicate, with `value` a bare identifier" in {
    typeDecl("type Even = int where value % 2 == 0") shouldBe
      TypeDecl(
        "Even",
        named("int"),
        derived = false,
        None,
        Some(cmp("==", Binary("%", Ident("value"), i(2)), i(0))),
      )
  }

  "new marks a derived type and needs neither clause" in {
    typeDecl("type Meters = new f64") shouldBe
      TypeDecl("Meters", named("f64"), derived = true, None, None)
  }

  "the forms compose: new + within + where" in {
    typeDecl("type SafeAge = new int within 0..150 where value % 2 == 0") shouldBe
      TypeDecl(
        "SafeAge",
        named("int"),
        derived = true,
        Some(RangeBound(i(0), i(150), exclusiveHi = false)),
        Some(cmp("==", Binary("%", Ident("value"), i(2)), i(0))),
      )
  }

  // The contextual keywords stay ordinary identifiers everywhere else: a function named `where`
  // with a parameter named `within` still parses as an ordinary declaration.
  "within and where remain legal identifiers outside a type declaration" in {
    prog("""where(within: int) -> int
           |    within""".stripMargin).collectFirst { case f: FuncDecl => f.name } shouldBe Some("where")
  }
}
