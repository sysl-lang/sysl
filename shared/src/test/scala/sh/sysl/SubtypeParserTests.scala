package sh.sysl

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

    /** A bound is a constant *expression*, not a literal (`16 § Open b`), so a name parses here and
      * whether it denotes a constant is settled later. The level is the one tighter than a range,
      * which is the whole reason this position could not simply take `expression`: at any looser
      * level `0..<max_tasks` would parse as a **range expression** and swallow the `..<` that
      * separates the two bounds.
      */
    "a name parses as a bound" in {
      typeDecl("type Slot = int within 0..<max_tasks") shouldBe
        TypeDecl(
          "Slot",
          named("int"),
          derived = false,
          Some(RangeBound(i(0), Ident("max_tasks"), exclusiveHi = true)),
          None,
        )
    }

    "and so does an expression over names" in {
      typeDecl("type Mid = int within lo..hi - 1") shouldBe
        TypeDecl(
          "Mid",
          named("int"),
          derived = false,
          Some(RangeBound(Ident("lo"), Binary("-", Ident("hi"), i(1)), exclusiveHi = false)),
          None,
        )
    }

    // The bound stops below the range operator, so the two bounds stay two — a bound parsed at a
    // looser level would take `0..<n` as one range expression and leave the second bound missing.
    "the bound does not swallow the range operator" in {
      typeDecl("type S = int within a..<b") shouldBe
        TypeDecl(
          "S",
          named("int"),
          derived = false,
          Some(RangeBound(Ident("a"), Ident("b"), exclusiveHi = true)),
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

  // An array bound may name a `const` and a `within` bound now may too, so a table's size and the
  // range of the type that indexes it are one fact written once. Both positions fold through the same
  // `fold`, which is what keeps them from drifting apart.
  "a within bound naming a const parses, as an array bound does" in {
    prog("""const n: usize = 8
           |type Slot = new u8 within 0..<n
           |""".stripMargin) shouldBe
      List(
        ConstDecl("n", named("usize"), i(8)),
        TypeDecl("Slot", named("u8"), derived = true, Some(RangeBound(i(0), Ident("n"), true)), None),
      )
  }
}
