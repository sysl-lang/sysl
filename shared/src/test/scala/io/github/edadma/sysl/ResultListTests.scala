package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** `-> int, int` — several results as a property of the signature (`12 §5b`).
 *
 * The whole design is that **there is no carrier**: the several things travel from callee to caller
 * and are taken apart there, so a result list may stand in exactly three places and nowhere else.
 * The tests that matter are therefore the refusals — a form that quietly produced a tuple would
 * pass every test that only checked the values arrived.
 */
class ResultListTests extends AnyFreeSpec with ParseSupport with RunSupport with CodegenSupport {

  private def fn(body: String): String =
    s"""demo()
       |${body.linesIterator.map("    " + _).mkString("\n")}
       |end demo
       |demo()
       |""".stripMargin

  private val divmod =
    """divmod(a: int, b: int) -> int, int
      |    a / b, a % b
      |""".stripMargin

  "a function may declare several results" - {
    "and a binding takes them apart" in {
      run(divmod + """var q, r = divmod(17, 5)
                     |print(q, r)
                     |""".stripMargin) shouldBe "3 2\n"
    }

    "an assignment writes them into places that already exist" in {
      run(divmod + fn("""var a = 0
                        |var b = 0
                        |a, b = divmod(9, 2)
                        |print(a, b)""".stripMargin)) shouldBe "4 1\n"
    }

    "there may be more than two" in {
      run("""civil(d: int) -> int, int, int = d / 400, (d / 30) % 12, d % 30
            |var y, m, day = civil(20520)
            |print(y, m, day)
            |""".stripMargin) shouldBe "51 0 0\n"
    }

    "the results need not have the same type" in {
      run("""pick(c: bool) -> int, string = if c then 1 else 0, if c then "yes" else "no"
            |var n, s = pick(true)
            |print(n, s)
            |""".stripMargin) shouldBe "1 yes\n"
    }

    "a 'return' hands them back too" in {
      run("""signs(n: int) -> int, int
            |    return n, -n
            |end signs
            |var a, b = signs(3)
            |print(a, b)
            |""".stripMargin) shouldBe "3 -3\n"
    }

    // The third place: a value produced *as* the function's result, which is what a branch and a
    // nested block are — so the permission has to reach them or half the ways of writing a body
    // stop working.
    "and each branch of a body may produce its own" in {
      run("""pick(c: bool) -> int, string
            |    if c then
            |        1, "yes"
            |    else
            |        0, "no"
            |end pick
            |var n, s = pick(false)
            |print(n, s)
            |""".stripMargin) shouldBe "0 no\n"
    }

    "a function may forward another's whole list" in {
      run("""pick(c: bool) -> int, string = if c then 1 else 0, if c then "yes" else "no"
            |forward(c: bool) -> int, string = pick(c)
            |var n, s = forward(true)
            |print(n, s)
            |""".stripMargin) shouldBe "1 yes\n"
    }
  }

  "where it may be written" - {
    "a method declares one" in {
      run("""struct Box
            |    n: int
            |    both(self) -> int, int = self.n, self.n * 2
            |end Box
            |var x, y = Box(5).both()
            |print(x, y)
            |""".stripMargin) shouldBe "5 10\n"
    }

    "a trait declares one, and an implementation supplies it" in {
      run("""struct Box
            |    n: int
            |trait Pairish
            |    parts(self) -> int, int
            |impl Pairish for Box
            |    parts(self) -> int, int
            |        return self.n, -self.n
            |    end parts
            |var p, q = Box(5).parts()
            |print(p, q)
            |""".stripMargin) shouldBe "5 -5\n"
    }

    "a generic function declares one over its own parameters" in {
      run("""both[T](a: T, b: T) -> T, T = a, b
            |var u, v = both("a", "b")
            |print(u, v)
            |""".stripMargin) shouldBe "a b\n"
    }

    // A result list is a property of a *signature*, so the places that ask for a type cannot reach
    // one — which is what makes "nothing may store one" a consequence rather than a rule.
    "a field cannot be declared as one" in {
      progError("""struct S
                  |    f: int, int
                  |""".stripMargin) should not be empty
    }

    "and neither can an 'extern' result, which is an ABI question" in {
      progError("extern g() -> int, int") should not be empty
    }
  }

  /** The rule the whole section exists for: a multi-result call may appear in exactly three places
   * (`12 §5b`). Each of these is a place it may not, and each is refused in the same words.
   */
  "nothing may hold one" - {
    "not an argument" in {
      err(divmod + "print(divmod(7, 2))") should include("this yields 2 results, and one value is wanted here")
    }

    "not a variable of the tuple type its parts lay out as" in {
      err(divmod + fn("var p: (int, int) = divmod(7, 2)")) should include("2 results")
    }

    "not a part of something larger" in {
      err(divmod + fn("var a = [divmod(7, 2)]")) should include("2 results")
    }

    "and not an operand" in {
      err(divmod + fn("var a = divmod(7, 2) == divmod(7, 2)")) should include("2 results")
    }

    // The permission covers one expression and nothing inside it, so a call nested inside the
    // right-hand side of a binding is refused exactly as one anywhere else is.
    "the permission does not reach inside the expression it was given for" in {
      err("""wrap(a: int, b: int) -> int, int = a, b
            |take(n: int) -> int, int = n, n
            |""".stripMargin + fn("var x, y = wrap(take(1), 2)")) should include("2 results")
    }
  }

  "there is no partial take" - {
    "a single binding may not take the first of several" in {
      err(divmod + fn("var a = divmod(7, 2)")) should include("2 results")
    }

    "a binding that names too few is refused in the callee's terms" in {
      err("""three() -> int, int, int = 1, 2, 3
            |""".stripMargin + fn("var a, b = three()")) should include("names 2 things, and this yields 3 results")
    }

    "and one that names too many is refused the same way" in {
      err(divmod + fn("var a, b, c = divmod(7, 2)")) should include("names 3 things, and this yields 2 results")
    }
  }

  "the callee's own side" - {
    "a body that yields one value where several were declared is refused in the words written" in {
      err("f() -> int, int = 1\nprint(1)") should include("should return int, int, but its body yields int")
    }

    "too many values are counted against the declaration" in {
      err("f() -> int, int = 1, 2, 3\nprint(1)") should include("yields 2 results, but 3 values were given")
    }

    "each value is checked against the result it is heading for" in {
      err("""f() -> int, int = 1, "x"
            |print(1)
            |""".stripMargin) should include("this result is declared int, but the value is string")
    }

    "a comma list in a function that declares one result says which is wrong" in {
      err("f() -> int = 1, 2\nprint(1)") should include("this function declares one result")
    }

    // "There is no tuple value anywhere" is enforced on the callee too, not only on the caller:
    // parentheses here would build the carrier the form says never exists.
    "and the parentheses are refused where they would build a carrier" in {
      err("f() -> int, int = (1, 2)\nprint(1)") should include("yields 2 results rather than a tuple")
    }
  }

  /** `00 §13`'s claim that the caller usually cannot tell the two apart — the point being that a
   * callee may change its mind between the two forms without touching a call site that destructures.
   */
  "beside a tuple, at the same binding" - {
    "the same spelling takes a result list and a tuple apart" in {
      run("""asList(n: int) -> int, int = n, n * 2
            |asTuple(n: int) -> (int, int) = (n, n * 2)
            |var a, b = asList(3)
            |var c, d = asTuple(3)
            |print(a, b, c, d)
            |""".stripMargin) shouldBe "3 6 3 6\n"
    }

    // And the one place they differ, which is the whole discriminator: a tuple is a value and may
    // be held, a result list is not and may not.
    "and only the tuple may be held" in {
      run("""asTuple(n: int) -> (int, int) = (n, n * 2)
            |var p = asTuple(3)
            |print(p.0, p.1)
            |""".stripMargin) shouldBe "3 6\n"
      err("""asList(n: int) -> int, int = n, n * 2
            |""".stripMargin + fn("var p = asList(3)")) should include("2 results")
    }
  }

  "the values it carries" - {
    "a counted result survives the journey" in {
      run("""labels(n: int) -> string, string = "a" + str(n), "b" + str(n)
            |""".stripMargin + fn("""var i = 0
               |while i < 20000
               |    var s, t = labels(i)
               |    i += 1
               |print("done")""".stripMargin)) shouldBe "done\n"
    }

    "a 'unit' result takes no room, as it does anywhere else" in {
      run("""f() -> unit, int = (), 2
            |var u, n = f()
            |print(n)
            |""".stripMargin) shouldBe "2\n"
    }

    "and a result that never arrives is not a result" in {
      err("""f() -> never, int = exit(1), 2
            |print(1)
            |""".stripMargin) should include("only be a result type")
    }
  }

  /** A comma at the end of a line is a result list; a comma anywhere else still means what it
   * meant. This is the one place the form could have taken something that was not its own, and it
   * did — an inline `else` body is a statement, so the greedy reading swallowed a call's arguments.
   */
  "a comma that is not this form goes on meaning what it meant" - {
    // A branch written inline is part of a larger expression, so a comma after it belongs to
    // whatever that expression is part of — here, the function's own result list.
    "an inline branch at the end of a line does not take the comma after it" in {
      run("""pick(c: bool) -> int, string = if c then 1 else 0, if c then "yes" else "no"
            |var n, s = pick(true)
            |print(n, s)
            |""".stripMargin) shouldBe "1 yes\n"
    }

    "an inline branch inside an argument list keeps its neighbours" in {
      run("""pick(c: bool, a: int, b: int) -> int = if c then a else b
            |print(pick(true, if false then 1 else 2, 3))
            |""".stripMargin) shouldBe "2\n"
    }

    "a call's arguments are still its arguments" in {
      run("""print(1, if true then 2 else 3, 4)""") shouldBe "1 2 4\n"
    }

    "and an assignment with a comma is still the assignment form" in {
      prog("a, b = b, a") shouldBe
        List(MultiAssign("=", List(Ident("a"), Ident("b")), List(Ident("b"), Ident("a"))))
    }
  }
}
