package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** `do body while cond` — the post-test loop (`00` §10).
 *
 * It is the fifth loop form, and it earns its place the way the three-clause `for` earns its own: it
 * is not sugar. The shape a program reaches for instead — `loop` with `if !cond then break` at the
 * foot — has no test for a `continue` to reach, so the first `continue` written into it jumps over
 * the exit and never leaves. That difference is asserted here on what a program prints and again on
 * the emitted IR, because it is the only reason the form exists.
 *
 * Everything else is the loop family's shared behaviour — `break` carrying a value, `else` on
 * normal completion, labels — checked because a new form has to join that family rather than sit
 * beside it.
 */
class DoWhileTests extends AnyFreeSpec with CodegenSupport with RunSupport with ParseSupport {

  "the form parses" - {
    "with an indented body and the test on the line that closes it" in {
      prog("do\n    print(1)\nwhile more()") shouldBe
        List(ExprStmt(DoWhile(
          None,
          List(ExprStmt(Call(Ident("print"), List(i(1))))),
          Call(Ident("more"), Nil),
          None,
        )))
    }

    "with the whole loop on one line" in {
      prog("do total += 1 while more()") shouldBe
        List(ExprStmt(DoWhile(
          None,
          List(ExprStmt(Assign("+=", Ident("total"), i(1)))),
          Call(Ident("more"), Nil),
          None,
        )))
    }

    "with a label and an 'else'" in {
      prog("'scan do\n    print(1)\nwhile more()\nelse 0") shouldBe
        List(ExprStmt(DoWhile(
          Some("scan"),
          List(ExprStmt(Call(Ident("print"), List(i(1))))),
          Call(Ident("more"), Nil),
          Some(List(ExprStmt(i(0)))),
        )))
    }

    // `do` is a body introducer everywhere else, and it only ever appears there after a loop header
    // on the same line — so it is never the first token of a statement and the two readings cannot
    // meet. These three are the introducer, and they still read as they did.
    "without disturbing 'do' as a body introducer" - {
      "after a 'while' header" in {
        prog("while c do x = 1") shouldBe
          List(ExprStmt(While(None, Ident("c"), List(ExprStmt(Assign("=", Ident("x"), i(1)))), None)))
      }

      "after a 'loop' header" in {
        prog("loop do x = 1") shouldBe
          List(ExprStmt(Loop(None, List(ExprStmt(Assign("=", Ident("x"), i(1)))))))
      }

      "after a 'for' header" in {
        prog("for x in 0..<3 do print(x)") shouldBe
          List(ExprStmt(For(
            None,
            "x",
            RangeExpr(Some(i(0)), Some(i(3)), inclusive = false),
            List(ExprStmt(Call(Ident("print"), List(Ident("x"))))),
            None,
          )))
      }
    }
  }

  "the body runs before anything is asked" - {
    // The whole of what a post-test loop says, and the case a `while` gets wrong: a test that is
    // false at the top still owes one pass.
    "even when the test is false at entry" in {
      run("var t = 0\ndo\n    t += 1\nwhile false\nprint(str(t))") shouldBe "1\n"
    }

    "which is what makes zero print as a digit" in {
      run(
        """var n = 0
          |var s = ""
          |do
          |    s = str(n % 10) + s
          |    n /= 10
          |while n > 0
          |print(s)
          |""".stripMargin,
      ) shouldBe "0\n"
    }

    "and the same loop still walks a number with several digits" in {
      run(
        """var n = 4071
          |var s = ""
          |do
          |    s = str(n % 10) + s
          |    n /= 10
          |while n > 0
          |print(s)
          |""".stripMargin,
      ) shouldBe "4071\n"
    }

    "on one line, where the body is a single statement" in {
      run("var i = 0\ndo i += 1 while i < 4\nprint(str(i))") shouldBe "4\n"
    }
  }

  // The reason the form exists. The `loop` + `if !cond then break` rewrite has no test for a
  // `continue` to reach, so this program written that way would never finish.
  "'continue' runs the test" - {
    "so a skipped iteration still asks whether to stop" in {
      run(
        """var i = 0
          |var s = ""
          |do
          |    i += 1
          |    if i % 2 == 0 then continue
          |    s += str(i)
          |while i < 5
          |print(s)
          |""".stripMargin,
      ) shouldBe "135\n"
    }

    "including a 'continue' that reaches an outer one" in {
      run(
        """var s = ""
          |var i = 0
          |'outer do
          |    i += 1
          |    var j = 0
          |    do
          |        j += 1
          |        if j == 2 then continue 'outer
          |        s += s"${str(i)}${str(j)} "
          |    while j < 3
          |while i < 3
          |print(s)
          |""".stripMargin,
      ) shouldBe "11 21 31 \n"
    }
  }

  "it is an expression, like every other loop" - {
    "a 'break' carries its value and 'else' supplies the other" in {
      run(
        """var i = 0
          |var found = do
          |    i += 1
          |    if i * i > 20 then break i
          |while i < 10
          |else -1
          |print(str(found))
          |""".stripMargin,
      ) shouldBe "5\n"
    }

    "and the 'else' is what runs when nothing broke" in {
      run(
        """var i = 0
          |var found = do
          |    i += 1
          |    if i > 99 then break i
          |while i < 3
          |else -1
          |print(str(found))
          |""".stripMargin,
      ) shouldBe "-1\n"
    }

    "a labelled 'break' leaves the outer one" in {
      run(
        """var s = ""
          |var i = 0
          |'outer do
          |    for j in 0..<3
          |        if i * j == 2 then break 'outer
          |        s += s"${str(i)}${str(j)} "
          |    i += 1
          |while i < 3
          |print(s)
          |""".stripMargin,
      ) shouldBe "00 01 02 10 11 \n"
    }

    "and a bare 'break' with no 'else' is the ordinary statement loop" in {
      run(
        """var i = 0
          |do
          |    i += 1
          |    if i == 3 then break
          |while i < 10
          |print(str(i))
          |""".stripMargin,
      ) shouldBe "3\n"
    }
  }

  "what it will not accept" - {
    "a test that is not a boolean" in {
      err("do\n    print(1)\nwhile 1") should include("bool")
    }

    // The body's scope has closed by the time the test is reached, which is C's rule and the only
    // one the form can have: a `var` in the body is remade each round, so a test reading one would
    // be reading the round that has just ended.
    "a name the body declared, read by the test" in {
      err("do\n    var k = 1\nwhile k < 2") should include("undefined name 'k'")
    }

    // An `is` binding is live through the branch its test guards, and a test at the foot guards
    // nothing — the body it belongs to has already run.
    "an 'is' binding in the test, which would have nothing to reach" in {
      err(
        """var p: Option[int] = Some(1)
          |do
          |    p = None
          |while p is Some(v)
          |""".stripMargin,
      ) should include("'is'")
    }

    "a 'break' with a value and no 'else' to match it" in {
      err("var n = do\n    break 1\nwhile false\nprint(str(n))") should include("no 'else'")
    }

    "a 'break' value and an 'else' value that disagree" in {
      err("var n = do\n    break 1\nwhile false\nelse \"x\"\nprint(str(n))") should include("same type")
    }
  }

  "the emitted shape" - {
    // Entering at the body rather than at the test is what makes the loop post-test, and `continue`
    // reaching the test is what makes it more than a `loop` with a `break` at the foot. Both are
    // asserted on the IR, since a program can print the right answer for the wrong shape.
    "enters at the body, not at the test" in {
      val out = ir("var i = 0\ndo\n    i += 1\nwhile i < 2\nprint(str(i))")

      // The entry branch names the body, so the body's label is mentioned before the test's exists
      // at all. A pre-test loop's first mention is its condition, which is the whole difference.
      out should include regex """br label %dowhile\.body\d+"""
      out.indexOf("dowhile.body") should be < out.indexOf("dowhile.cond")
    }

    "sends 'continue' to the test, not back to the body" in {
      val out = ir("var i = 0\ndo\n    i += 1\n    if i == 1 then continue\nwhile i < 3\nprint(str(i))")

      out should include("br label %dowhile.cond")
    }
  }

  // A loop is an expression, so a `break` of a frame-backed slice out of one carries it out exactly
  // as a `return` does. `while` and the range loops were already walked for this; `do while`, `loop`
  // and the three-clause `for` were not, so a slice broken out of those three left the frame with
  // nothing keeping its storage alive.
  "a view broken out of it is promoted, as it is from every other loop" - {
    "out of a 'do while'" in {
      val src =
        """leak() -> []int
          |    var buf: [4]int
          |    buf[2usize] = 5
          |    var i = 0
          |    do
          |        i += 1
          |        if i == 2 then break buf[..]
          |    while i < 4
          |    else buf[0..<0]
          |end leak
          |var v = leak()
          |print(v.len, v[2usize])
          |""".stripMargin

      run(src) shouldBe "4 5\n"
      ir(src) should include("call ptr @malloc")
    }

    "out of a 'loop'" in {
      val src =
        """leak() -> []int
          |    var buf: [4]int
          |    buf[2usize] = 5
          |    var i = 0
          |    loop
          |        i += 1
          |        if i == 2 then break buf[..]
          |end leak
          |var v = leak()
          |print(v.len, v[2usize])
          |""".stripMargin

      run(src) shouldBe "4 5\n"
      ir(src) should include("call ptr @malloc")
    }

    "out of a three-clause 'for'" in {
      val src =
        """leak() -> []int
          |    var buf: [4]int
          |    buf[2usize] = 5
          |    for var i = 0; i < 4; i += 1
          |        if i == 2 then break buf[..]
          |    else buf[0..<0]
          |end leak
          |var v = leak()
          |print(v.len, v[2usize])
          |""".stripMargin

      run(src) shouldBe "4 5\n"
      ir(src) should include("call ptr @malloc")
    }
  }

  // The artifact carries the tree, so a node the codec cannot round-trip is a library that cannot
  // be read back — and the failure would surface as a corrupt import rather than as a bad loop.
  "it survives the artifact round trip" in {
    val src =
      """f(n: int) -> int
        |    var t = n
        |    'count do
        |        t += 1
        |    while t < 4
        |    else t
        |end f
        |""".stripMargin

    val source = Source("<t>", src)

    val tree = SyslParser.parse(source) match
      case Right(p) => List(p)
      case Left(e)  => fail(s"the fixture does not parse: $e")

    AstCodec.decode(AstCodec.encode(tree), Map("<t>" -> source)) match
      case Right(back) => back shouldBe tree
      case Left(e)     => fail(s"decode failed: $e")
  }
}
