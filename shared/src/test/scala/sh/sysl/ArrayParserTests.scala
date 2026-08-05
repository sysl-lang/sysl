package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** The surface `07-arrays-and-slices.md` settles: the two type spellings, the literal, the
 * subscript in both its element and its range forms, and a declaration with no initializer.
 */
class ArrayParserTests extends AnyFreeSpec with ParseSupport {

  "types" - {
    "a fixed array names its length" in {
      prog("var a: [4]int = x") shouldBe
        List(VarDecl("a", Some(ArrayType(Some(i(4)), NamedType("int"))), Some(Ident("x"))))
    }

    "a slice omits it" in {
      prog("var a: []u8 = x") shouldBe
        List(VarDecl("a", Some(ArrayType(None, NamedType("u8"))), Some(Ident("x"))))
    }

    "arrays nest, which is how a rectangle is written" in {
      prog("var a: [2][3]f64 = x") shouldBe
        List(VarDecl("a", Some(ArrayType(Some(i(2)), ArrayType(Some(i(3)), NamedType("f64")))), Some(Ident("x"))))
    }

    "a slice of references keeps both sigils" in {
      prog("var a: []&Node = x") shouldBe
        List(VarDecl("a", Some(ArrayType(None, RefType(NamedType("Node"), sync = false))), Some(Ident("x"))))
    }

    "a generic type argument is still a type application, not an array" in {
      prog("var a: Box[int] = x") shouldBe
        List(VarDecl("a", Some(NamedType("Box", List(NamedType("int")))), Some(Ident("x"))))
    }
  }

  "declarations" - {
    "a type with no initializer is a zero-valued declaration" in {
      prog("var buf: [64]u8") shouldBe List(VarDecl("buf", Some(ArrayType(Some(i(64)), NamedType("u8"))), None))
    }
  }

  "expressions" - {
    "an array literal lists its elements" in {
      expr("[1, 2, 3]") shouldBe ArrayLit(List(i(1), i(2), i(3)))
    }

    "an empty literal is legal to write" in {
      expr("[]") shouldBe ArrayLit(Nil)
    }

    "a subscript is a postfix tail, so it chains" in {
      expr("a[1][2]") shouldBe Index(Index(Ident("a"), i(1)), i(2))
    }

    "a range subscript is a slice" in {
      expr("a[1..3]") shouldBe Index(Ident("a"), RangeExpr(Some(i(1)), Some(i(3)), inclusive = true))
    }

    "an exclusive range subscript" in {
      expr("a[1..<3]") shouldBe Index(Ident("a"), RangeExpr(Some(i(1)), Some(i(3)), inclusive = false))
    }

    "either end may be left open" in {
      expr("a[1..]") shouldBe Index(Ident("a"), RangeExpr(Some(i(1)), None, inclusive = true))
      expr("a[..<3]") shouldBe Index(Ident("a"), RangeExpr(None, Some(i(3)), inclusive = false))
      expr("a[..]") shouldBe Index(Ident("a"), RangeExpr(None, None, inclusive = true))
    }

    "a length reads as a field" in {
      expr("a.len") shouldBe Field(Ident("a"), "len")
    }
  }
}
