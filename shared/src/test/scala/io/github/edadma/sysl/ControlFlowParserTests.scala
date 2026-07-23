package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** Parsing of control flow: `if`/`elif`/`else` and `while`, block and inline (Scala-style
 * `then`/`do`) bodies, and correct dedenting of nested blocks. `if` is an expression, so in
 * statement position it appears as an `IfExpr` inside an `ExprStmt` (the `ifStmt` helper).
 */
class ControlFlowParserTests extends AnyFreeSpec with ParseSupport {

  "blocks" - {
    "an if with an else block" in {
      prog("if x\n    print(1)\nelse\n    print(2)") shouldBe List(
        ifStmt(Ident("x"), List(printStmt(i(1))), Some(List(printStmt(i(2)))))
      )
    }

    "an if with no else has no else body" in {
      prog("if x\n    print(1)") shouldBe List(
        ifStmt(Ident("x"), List(printStmt(i(1))))
      )
    }

    "a while loop with a multi-statement body" in {
      prog("while i < 10\n    print(i)\n    i++") shouldBe List(
        While(
          Compare(List(Ident("i"), i(10)), List("<")),
          List(printStmt(Ident("i")), ExprStmt(PostIncDec("++", Ident("i")))),
        )
      )
    }

    "nested blocks dedent correctly" in {
      prog("while a\n    if b\n        print(1)\n    print(2)") shouldBe List(
        While(
          Ident("a"),
          List(
            ifStmt(Ident("b"), List(printStmt(i(1)))),
            printStmt(i(2)),
          ),
        )
      )
    }
  }

  "then/do introducers" - {
    "a while with `do` before an indented block" in {
      prog("while i <= 10 do\n    i++") shouldBe List(
        While(
          Compare(List(Ident("i"), i(10)), List("<=")),
          List(ExprStmt(PostIncDec("++", Ident("i")))),
        )
      )
    }

    "`do` is optional before an indented block" in {
      prog("while c\n    print(1)") shouldBe prog("while c do\n    print(1)")
    }

    "`then` is optional before an indented block" in {
      prog("if x\n    print(1)") shouldBe prog("if x then\n    print(1)")
    }

    "a one-line while body needs `do`" in {
      prog("while i < 3 do i++") shouldBe List(
        While(
          Compare(List(Ident("i"), i(3)), List("<")),
          List(ExprStmt(PostIncDec("++", Ident("i")))),
        )
      )
    }

    "an inline body still ends at a following statement" in {
      prog("while c do print(1)\nprint(2)") shouldBe List(
        While(Ident("c"), List(printStmt(i(1)))),
        printStmt(i(2)),
      )
    }
  }

  "inline if/then/else" - {
    "an inline if/then/else" in {
      prog("if x then print(1) else print(2)") shouldBe List(
        ifStmt(Ident("x"), List(printStmt(i(1))), Some(List(printStmt(i(2)))))
      )
    }

    "an inline if with no else" in {
      prog("if x then print(1)") shouldBe List(
        ifStmt(Ident("x"), List(printStmt(i(1))))
      )
    }

    "a block `then` with an inline `else`" in {
      prog("if x then\n    print(1)\nelse print(2)") shouldBe List(
        ifStmt(Ident("x"), List(printStmt(i(1))), Some(List(printStmt(i(2)))))
      )
    }

    "an inline then with else on its own line" in {
      prog("if cond then true_part()\nelse false_part()") shouldBe List(
        ifStmt(Ident("cond"), List(callStmt("true_part")), Some(List(callStmt("false_part"))))
      )
    }
  }

  "elif chains" - {
    "an elif chain nests into the else branch" in {
      prog("if a then\n    print(1)\nelif b then\n    print(2)\nelse\n    print(3)") shouldBe List(
        ifStmt(
          Ident("a"),
          List(printStmt(i(1))),
          Some(List(ifStmt(Ident("b"), List(printStmt(i(2))), Some(List(printStmt(i(3))))))),
        )
      )
    }

    "an inline elif chain" in {
      prog("if a then x()\nelif b then y()\nelse z()") shouldBe List(
        ifStmt(
          Ident("a"),
          List(callStmt("x")),
          Some(List(ifStmt(Ident("b"), List(callStmt("y")), Some(List(callStmt("z")))))),
        )
      )
    }

    "an elif with no else leaves the innermost else empty" in {
      prog("if a then x()\nelif b then y()") shouldBe List(
        ifStmt(
          Ident("a"),
          List(callStmt("x")),
          Some(List(ifStmt(Ident("b"), List(callStmt("y"))))),
        )
      )
    }
  }

  "optional end markers" - {
    "`end while` closes a while and parses the same as without it" in {
      prog("while c\n    print(1)\nend while") shouldBe prog("while c\n    print(1)")
    }

    "`end if` closes an if/elif/else chain" in {
      prog("if a then\n    print(1)\nelif b then\n    print(2)\nelse\n    print(3)\nend if") shouldBe
        prog("if a then\n    print(1)\nelif b then\n    print(2)\nelse\n    print(3)")
    }

    "a statement after an end marker still parses" in {
      prog("while c\n    print(1)\nend while\nprint(2)") shouldBe List(
        While(Ident("c"), List(printStmt(i(1)))),
        printStmt(i(2)),
      )
    }

    "`end` stays usable as an ordinary identifier" in {
      prog("var end = 5\nprint(end)") shouldBe List(
        VarDecl("end", None, i(5)),
        printStmt(Ident("end")),
      )
    }
  }
}
