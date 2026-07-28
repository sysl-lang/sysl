package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** Parsing of the memory-mode sigils in type position — `*T`, `&T`, `&sync T` — and of the
 * expression forms that go with them.
 */
class MemoryModeParserTests extends AnyFreeSpec with ParseSupport {

  "type sigils" - {
    "a raw pointer" in {
      prog("var x: *int = p") shouldBe List(VarDecl("x", Some(PtrType(NamedType("int"))), Some(Ident("p"))))
    }

    "a reference, plain and atomic" in {
      prog("var x: &Point = p") shouldBe
        List(VarDecl("x", Some(RefType(NamedType("Point"), sync = false)), Some(Ident("p"))))

      prog("var x: &sync Point = p") shouldBe
        List(VarDecl("x", Some(RefType(NamedType("Point"), sync = true)), Some(Ident("p"))))
    }

    "sigils stack, right to left" in {
      prog("var x: **int = p") shouldBe List(VarDecl("x", Some(PtrType(PtrType(NamedType("int")))), Some(Ident("p"))))

      prog("var x: *&int = p") shouldBe
        List(VarDecl("x", Some(PtrType(RefType(NamedType("int"), sync = false))), Some(Ident("p"))))
    }

    "a sigil applies to a type argument as readily as to a name" in {
      prog("var x: Box[*int] = p") shouldBe
        List(VarDecl("x", Some(NamedType("Box", List(PtrType(NamedType("int"))))), Some(Ident("p"))))
    }

    "in a signature, on a parameter and a result" in {
      prog("head(list: *Node) -> &Node = list") shouldBe List(
        FuncDecl(
          "head",
          Nil,
          List(Param("list", PtrType(NamedType("Node")))),
          Some(RefType(NamedType("Node"), sync = false)),
          List(ExprStmt(Ident("list"))),
        )
      )
    }

    "in a struct field, which is what makes a type recursive" in {
      prog("struct Node\n    value: int\n    next: *Node") shouldBe List(
        StructDecl("Node", Nil, List(Param("value", NamedType("int")), Param("next", PtrType(NamedType("Node")))))
      )
    }

    // `03 § weak T` describes a third mode — a non-owning reference that degrades to `None` when
    // its referent goes — and nothing implements it: `weak` is in the reserved words and has no
    // production in the type grammar, so the declaration stops at the colon. Pinned here so the
    // gap is a fact the suite states rather than one a reader has to discover from a design
    // document that describes the feature in the present tense.
    "'weak' is reserved and is not yet a type" in {
      progError("var w: weak Node = a") should include("newline expected")
    }

    "'sync' stays an ordinary name, so a type may be called it" in {
      prog("var x: &sync = p") shouldBe
        List(VarDecl("x", Some(RefType(NamedType("sync"), sync = false)), Some(Ident("p"))))
    }
  }

  "expressions" - {
    "address-of and dereference are prefix operators" in {
      expr("&x") shouldBe Unary("&", Ident("x"))
      expr("*p") shouldBe Unary("*", Ident("p"))
      expr("**p") shouldBe Unary("*", Unary("*", Ident("p")))
    }

    "a dereference is an assignment target" in {
      prog("*p = 5") shouldBe List(ExprStmt(Assign("=", Unary("*", Ident("p")), i(5))))
    }

    "null is a primary" in {
      expr("null") shouldBe NullLit()
      expr("p == null") shouldBe Compare(List(Ident("p"), NullLit()), List("=="))
    }
  }
}
