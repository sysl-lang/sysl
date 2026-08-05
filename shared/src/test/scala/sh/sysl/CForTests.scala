package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** `for init; cond; step` — the three-clause loop (`00` §10).
 *
 * It is the fourth loop form and the only one that carries a **step**, which is what it is for: a
 * counted loop written as a `while` puts its increment at the end of the body, where the first
 * `continue` anybody adds skips it. Everything else here is the loop family's shared behaviour —
 * `break` carrying a value, `else` on normal completion, labels — checked because a new form has to
 * join that family rather than sit beside it.
 */
class CForTests extends AnyFreeSpec with CodegenSupport with RunSupport with ParseSupport {

  "the header parses" - {
    "with all three clauses" in {
      prog("for var i = 0; i < 2; i += 1\n    print(i)") shouldBe
        List(ExprStmt(CFor(
          None,
          Some(VarDecl("i", None, Some(i(0)))),
          Some(Compare(List(Ident("i"), i(2)), List("<"))),
          Some(ExprStmt(Assign("+=", Ident("i"), i(1)))),
          List(ExprStmt(Call(Ident("print"), List(Ident("i"))))),
          None,
        )))
    }

    "with clauses left out" in {
      prog("for ; ;\n    print(1)") shouldBe
        List(ExprStmt(CFor(None, None, None, None, List(ExprStmt(Call(Ident("print"), List(i(1))))), None)))
    }

    // The two forms are told apart by the semicolons, so neither has to be written differently to
    // stay unambiguous.
    "without disturbing the range form beside it" in {
      prog("for x in 0..<3\n    print(x)") shouldBe
        List(ExprStmt(For(
          None,
          "x",
          RangeExpr(Some(i(0)), Some(i(3)), inclusive = false),
          List(ExprStmt(Call(Ident("print"), List(Ident("x"))))),
          None,
        )))
    }

    "and a semicolon is a separator here and nowhere else" in {
      progError("print(1); print(2)") should not be empty
    }
  }

  "it counts" - {
    "over a body" in {
      run("var t = 0\nfor var i = 0; i < 5; i += 1\n    t += i\nprint(str(t))") shouldBe "10\n"
    }

    "at whatever type the counter was declared" in {
      run(
        """var t: usize = 0usize
          |for var i: usize = 0usize; i < 4usize; i += 1usize
          |    t += i
          |print(str(t))
          |""".stripMargin,
      ) shouldBe "6\n"
    }

    "downward, which a range cannot say" in {
      run(
        """var s = ""
          |for var i = 3; i > 0; i -= 1
          |    s += str(i)
          |print(s)
          |""".stripMargin,
      ) shouldBe "321\n"
    }

    "by a stride" in {
      run(
        """var s = ""
          |for var i = 0; i < 10; i += 3
          |    s += str(i)
          |print(s)
          |""".stripMargin,
      ) shouldBe "0369\n"
    }

    "with a body that never runs" in {
      run("var t = 0\nfor var i = 5; i < 5; i += 1\n    t += 1\nprint(str(t))") shouldBe "0\n"
    }

    "with an empty init, over a counter declared outside it" in {
      run(
        """var k = 0
          |for ; k < 3; k += 1
          |    print(str(k))
          |""".stripMargin,
      ) shouldBe "0\n1\n2\n"
    }

    "with an empty step, advanced by the body instead" in {
      run(
        """var s = ""
          |for var i = 0; i < 3;
          |    s += str(i)
          |    i += 1
          |print(s)
          |""".stripMargin,
      ) shouldBe "012\n"
    }
  }

  // The reason the form exists. A `while` with the increment at the foot of its body does the wrong
  // thing the moment a `continue` is written above it, and nothing about the loop says so.
  "the step runs on 'continue'" - {
    "so a skipped iteration still advances" in {
      run(
        """var odd = 0
          |for var i = 0; i < 10; i += 1
          |    if i % 2 == 0 then continue
          |    odd += i
          |print(str(odd))
          |""".stripMargin,
      ) shouldBe "25\n"
    }

    "including a 'continue' that reaches an outer loop" in {
      run(
        """var s = ""
          |'outer for var i = 0; i < 3; i += 1
          |    for var j = 0; j < 3; j += 1
          |        if j == 1 then continue 'outer
          |        s += s"${str(i)}${str(j)} "
          |print(s)
          |""".stripMargin,
      ) shouldBe "00 10 20 \n"
    }
  }

  "it is an expression, like every other loop" - {
    "a 'break' carries its value and 'else' supplies the other" in {
      run(
        """var found = for var i = 0; i < 10; i += 1
          |    if i * i > 20 then break i
          |else -1
          |print(str(found))
          |""".stripMargin,
      ) shouldBe "5\n"
    }

    "and the 'else' is what runs when nothing broke" in {
      run(
        """var found = for var i = 0; i < 3; i += 1
          |    if i > 99 then break i
          |else -1
          |print(str(found))
          |""".stripMargin,
      ) shouldBe "-1\n"
    }

    "a header with no condition ends only through a 'break', and carries its value with no 'else'" in {
      run(
        """var n = for var i = 0; ; i += 1
          |    if i == 4 then break i * 10
          |print(str(n))
          |""".stripMargin,
      ) shouldBe "40\n"
    }

    "a labelled 'break' leaves the outer one" in {
      run(
        """var s = ""
          |'outer for var i = 0; i < 3; i += 1
          |    for var j = 0; j < 3; j += 1
          |        if i * j == 2 then break 'outer
          |        s += s"${str(i)}${str(j)} "
          |print(s)
          |""".stripMargin,
      ) shouldBe "00 01 02 10 11 \n"
    }
  }

  "what it will not accept" - {
    "a condition that is not a boolean" in {
      err("for var i = 0; i; i += 1\n    print(i)") should include("bool")
    }

    // An `else` runs on normal completion, and a loop with no condition has none — the same reason
    // `loop` takes no `else` at all.
    "an 'else' on a loop that cannot finish on its own" in {
      err("var n = for var i = 0; ; i += 1\n    if i == 2 then break i\nelse 0\nprint(str(n))") should
        include("'else' cannot run")
    }

    "a name from the init, read after the loop" in {
      err("for var i = 0; i < 2; i += 1\n    print(i)\nprint(i)") should include("undefined name 'i'")
    }
  }

  "the emitted shape" - {
    // `continue` branching to the step block rather than to the condition is the whole difference
    // between this loop and a `while`, so it is asserted on the IR and not only on what it prints.
    "sends 'continue' to the step, not to the test" in {
      val out = ir("for var i = 0; i < 2; i += 1\n    if i == 0 then continue\n    print(i)")

      out should include("cfor.step")
      out should include("br label %cfor.step")
    }
  }
}
