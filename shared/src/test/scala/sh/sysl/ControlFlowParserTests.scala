package sh.sysl

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
        whileStmt(
          Compare(List(Ident("i"), i(10)), List("<")),
          List(printStmt(Ident("i")), ExprStmt(PostIncDec("++", Ident("i")))),
        )
      )
    }

    "nested blocks dedent correctly" in {
      prog("while a\n    if b\n        print(1)\n    print(2)") shouldBe List(
        whileStmt(
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
        whileStmt(
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
        whileStmt(
          Compare(List(Ident("i"), i(3)), List("<")),
          List(ExprStmt(PostIncDec("++", Ident("i")))),
        )
      )
    }

    "and `do` is optional before a for's block too" in {
      prog("for x in xs\n    print(x)") shouldBe prog("for x in xs do\n    print(x)")
    }

    // The other half of the rule `00 § Open` used to leave undecided: an introducer is *required* for
    // a one-line body, because with nothing between the condition and the body there is nothing to
    // say where one ends and the other begins. The tests above show each keyword may be left out
    // before a block; these show it may not be left out on one line.
    "a one-line body without its introducer does not parse" in {
      progError("while c print(1)") should not be empty
      progError("if c print(1)") should not be empty
      progError("for x in xs print(x)") should not be empty
      progError("loop print(1)") should not be empty
    }

    "an inline body still ends at a following statement" in {
      prog("while c do print(1)\nprint(2)") shouldBe List(
        whileStmt(Ident("c"), List(printStmt(i(1)))),
        printStmt(i(2)),
      )
    }
  }

  "break, continue, and else" - {
    "a while with an else block" in {
      prog("while c\n    print(1)\nelse\n    print(2)") shouldBe List(
        whileStmt(Ident("c"), List(printStmt(i(1))), Some(List(printStmt(i(2)))))
      )
    }

    "a for with an else block" in {
      prog("for x in xs\n    print(x)\nelse\n    print(0)") shouldBe List(
        forStmt("x", Ident("xs"), List(printStmt(Ident("x"))), Some(List(printStmt(i(0)))))
      )
    }

    "a bare break and a break with a value" in {
      prog("while c\n    break\n    break 5") shouldBe List(
        whileStmt(Ident("c"), List(Break(None, None), Break(None, Some(i(5)))))
      )
    }

    "continue parses as its own statement" in {
      prog("while c\n    continue") shouldBe List(
        whileStmt(Ident("c"), List(Continue(None)))
      )
    }

    "an inline break inside an inline if" in {
      prog("for x in xs do if x then break x") shouldBe List(
        forStmt("x", Ident("xs"), List(ifStmt(Ident("x"), List(Break(None, Some(Ident("x")))))))
      )
    }

    "a labeled loop and labeled break/continue" in {
      prog("'outer for i in xs do\n    break 'outer\n    continue 'outer") shouldBe List(
        forStmt(
          "i",
          Ident("xs"),
          List(Break(Some("outer"), None), Continue(Some("outer"))),
          label = Some("outer"),
        )
      )
    }

    "a labeled break carrying a value" in {
      prog("'scan while c\n    break 'scan 5") shouldBe List(
        whileStmt(Ident("c"), List(Break(Some("scan"), Some(i(5)))), label = Some("scan"))
      )
    }
  }

  "loop" - {
    "a loop with a multi-statement body" in {
      prog("loop\n    print(i)\n    i++") shouldBe List(
        loopStmt(List(printStmt(Ident("i")), ExprStmt(PostIncDec("++", Ident("i")))))
      )
    }

    "a one-line loop body needs `do`" in {
      prog("loop do i++") shouldBe List(
        loopStmt(List(ExprStmt(PostIncDec("++", Ident("i")))))
      )
    }

    "`do` is optional before an indented block" in {
      prog("loop\n    print(1)") shouldBe prog("loop do\n    print(1)")
    }

    "an inline body still ends at a following statement" in {
      prog("loop do print(1)\nprint(2)") shouldBe List(
        loopStmt(List(printStmt(i(1)))),
        printStmt(i(2)),
      )
    }

    "a labeled loop, and a labeled break out of a nested one" in {
      prog("'outer loop\n    loop\n        break 'outer") shouldBe List(
        loopStmt(List(loopStmt(List(Break(Some("outer"), None)))), label = Some("outer"))
      )
    }

    "a break carrying a value" in {
      prog("loop\n    break 5") shouldBe List(loopStmt(List(Break(None, Some(i(5))))))
    }

    "`end loop` closes it and parses the same as without" in {
      prog("loop\n    print(1)\nend loop") shouldBe prog("loop\n    print(1)")
    }

    // An `else` runs on normal completion, which a `loop` does not have; there is nowhere for one
    // to go, so the grammar does not offer it.
    "a loop takes no else" in {
      progError("loop\n    print(1)\nelse\n    print(2)")
    }

    "a loop is a value, so it may sit on the right of a var" in {
      prog("var x = loop\n    break 5") shouldBe List(
        VarDecl("x", None, Some(Loop(None, List(Break(None, Some(i(5)))))))
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
        whileStmt(Ident("c"), List(printStmt(i(1)))),
        printStmt(i(2)),
      )
    }

    "`end` stays usable as an ordinary identifier" in {
      prog("var end = 5\nprint(end)") shouldBe List(
        VarDecl("end", None, Some(i(5))),
        printStmt(Ident("end")),
      )
    }
  }
}
