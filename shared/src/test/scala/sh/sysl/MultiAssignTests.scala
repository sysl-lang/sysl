package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** `a, b = b, a` and `val a, b = …` — the comma forms at a binding and at an assignment (`00 §2`).
 *
 * The form's whole promise is an **order**: everything is read before anything is written. So the
 * tests that matter are the ones that can tell a simultaneous write from two sequential ones — a
 * swap, a compound arm whose right side names the other arm's place, a counted value whose last
 * count would go if the first store let go of it. A form that wrote left to right would pass a test
 * that only checked the values arrived.
 */
class MultiAssignTests extends AnyFreeSpec with ParseSupport with RunSupport with CodegenSupport {

  private def fn(body: String): String =
    s"""demo()
       |${body.linesIterator.map("    " + _).mkString("\n")}
       |end demo
       |demo()
       |""".stripMargin

  "the form parses" - {
    "as a list of places and a list of values" in {
      prog("a, b = b, a") shouldBe
        List(MultiAssign("=", List(Ident("a"), Ident("b")), List(Ident("b"), Ident("a"))))
    }

    "with any place form on the left" in {
      prog("xs[i], p.f, *q = 1, 2, 3") shouldBe
        List(MultiAssign(
          "=",
          List(Index(Ident("xs"), Ident("i")), Field(Ident("p"), "f"), Unary("*", Ident("q"))),
          List(i(1), i(2), i(3)),
        ))
    }

    // One place is an ordinary assignment written the long way round, and `expression` already reads
    // it — so the comma form commits only at the second name.
    "and one place is still a plain assignment" in {
      prog("a = b") shouldBe List(ExprStmt(Assign("=", Ident("a"), Ident("b"))))
    }

    "a compound operator is read, not refused by the grammar" in {
      prog("a, b += 1, 2") shouldBe
        List(MultiAssign("+=", List(Ident("a"), Ident("b")), List(i(1), i(2))))
    }

    "a binding names several things" in {
      prog("val a, b = 1, 2") shouldBe List(MultiDecl(List("a", "b"), mutable = false, List(i(1), i(2))))
      prog("var a, b = 1, 2") shouldBe List(MultiDecl(List("a", "b"), mutable = true, List(i(1), i(2))))
    }

    "and a single-name binding is unchanged" in {
      prog("val a = 1") shouldBe List(ValDecl("a", None, i(1)))
      prog("var a = 1") shouldBe List(VarDecl("a", None, Some(i(1))))
    }

    "the step of a three-clause 'for' takes one" in {
      prog("for var i = 0; i < 3; i, j = i + 1, j - 1\n    print(i)") shouldBe
        List(ExprStmt(CFor(
          None,
          Some(VarDecl("i", None, Some(i(0)))),
          Some(Compare(List(Ident("i"), i(3)), List("<"))),
          Some(MultiAssign(
            "=",
            List(Ident("i"), Ident("j")),
            List(Binary("+", Ident("i"), i(1)), Binary("-", Ident("j"), i(1))),
          )),
          List(printStmt(Ident("i"))),
          None,
        )))
    }
  }

  "everything is read before anything is written" - {
    "so two names swap" in {
      run(fn("""var a = 1
               |var b = 2
               |a, b = b, a
               |print(a, b)""".stripMargin)) shouldBe "2 1\n"
    }

    "three places rotate" in {
      run(fn("""var a = 1
               |var b = 2
               |var c = 3
               |a, b, c = c, a, b
               |print(a, b, c)""".stripMargin)) shouldBe "3 1 2\n"
    }

    "two elements of an array swap" in {
      run(fn("""var xs: [4]int
               |xs[0usize], xs[1usize] = 7, 8
               |xs[0usize], xs[1usize] = xs[1usize], xs[0usize]
               |print(xs[0usize], xs[1usize])""".stripMargin)) shouldBe "8 7\n"
    }

    // The one that separates this form from two statements: written out, `c += d` then `d += c`
    // folds the *new* c into d, and nothing on the page says so.
    "and a compound arm sees the value its place started with" in {
      run(fn("""var c = 3
               |var d = 4
               |c, d += d, c
               |print(c, d)""".stripMargin)) shouldBe "7 7\n"
    }

    "a plain compound form advances every place at once" in {
      run(fn("""var a = 2
               |var b = 1
               |a, b += 10, 20
               |print(a, b)""".stripMargin)) shouldBe "12 21\n"
    }
  }

  /** `00 §2` — a place's own subexpressions run exactly once, before the values, and the effects
   * happen in written order. Without the rule the form would be a way of accidentally calling
   * something twice, so what is asserted is the *whole* trace and not just the answer.
   */
  "a place's subexpressions run once, in written order" in {
    run("""note(s: string, i: usize) -> usize
          |    print(s)
          |    i
          |end note
          |demo()
          |    var xs: [4]int
          |    xs[0usize] = 10
          |    xs[1usize] = 20
          |    xs[note("f", 0usize)], xs[note("g", 1usize)] = xs[note("G", 1usize)], xs[note("F", 0usize)]
          |    print(xs[0usize], xs[1usize])
          |end demo
          |demo()
          |""".stripMargin) shouldBe "f\ng\nG\nF\n20 10\n"
  }

  /** Counted values are where a form that reads everything first stops being free. Writing into the
   * first place lets go of what was there, and in a swap that is exactly what the second arm is
   * holding — so a naive lowering frees a value between reading it and storing it, and the second
   * print reads a dead object. Each of these would have passed on `int`.
   */
  "a counted value survives the store that lets go of it" - {
    "two strings swap" in {
      run(fn("""var s = "one"
               |var t = "two"
               |s, t = t, s
               |print(s, t)""".stripMargin)) shouldBe "two one\n"
    }

    "two references swap" in {
      run("""struct Node
            |    n: int
            |end Node
            |demo()
            |    var p: &Node = Node(1)
            |    var q: &Node = Node(2)
            |    p, q = q, p
            |    print(p.n, q.n)
            |end demo
            |demo()
            |""".stripMargin) shouldBe "2 1\n"
    }

    // A compound arm holds the value it read as well as the value on the right, and both are at
    // risk from the other arm's store.
    "two strings append each other" in {
      run(fn("""var s = "a"
               |var t = "b"
               |s, t += t, s
               |print(s, t)""".stripMargin)) shouldBe "ab ba\n"
    }

    // Enough traffic that a count let go of once too often would have to show, the way a single
    // swap need not: a freed string is only sometimes a wrong answer.
    "and the counts hold up over enough traffic to show it" in {
      run(fn("""var s = "x"
               |var t = "y"
               |for i in 0..<100000
               |    s, t = t, s
               |print(s, t)""".stripMargin)) shouldBe "x y\n"
    }
  }

  "a binding names several things" - {
    "and infers each from its own value" in {
      run(fn("""val a, b = 1, "two"
               |print(a, b)""".stripMargin)) shouldBe "1 two\n"
    }

    "a 'var' binding is assignable afterwards" in {
      run(fn("""var lo, hi = 0, 9
               |lo, hi = hi, lo
               |print(lo, hi)""".stripMargin)) shouldBe "9 0\n"
    }

    // The right side is produced before any name is bound, so a value may still name whatever the
    // enclosing scope calls one of them — the binding does not shadow itself half way through.
    "and a value on the right sees the outer name, not the one being bound" in {
      run(fn("""val a = 5
               |val b, a2 = a, a + 1
               |print(b, a2)""".stripMargin)) shouldBe "5 6\n"
    }

    "a 'val' part may not be assigned to afterwards" in {
      err(fn("""val a, b = 1, 2
               |a = 3""".stripMargin)) should include("'val' is written once")
    }
  }

  "the rules the form still owes" - {
    // One value for several places is the one length mismatch that has another reading — the value
    // may be a tuple to take apart (§13) — so it is complained about in those terms, and every
    // other mismatch keeps the plain count.
    "the two sides must be the same length" in {
      err(fn("""var a = 1
               |var b = 2
               |a, b = 1, 2, 3""".stripMargin)) should include("2 places on the left and 3 values on the right")
      err(fn("""var a = 1
               |var b = 2
               |a, b = 1""".stripMargin)) should include("one int is not something to take apart")
    }

    "a binding names as many things as it is given" in {
      err(fn("val a, b = 1, 2, 3")) should include("names 2 things and has 3 values")
    }

    "each value must suit the place it is heading for" in {
      err(fn("""var a = 1
               |var b = ""
               |a, b = 1, 2""".stripMargin)) should include("cannot assign int to 'b' of type string")
    }

    // The expected type reaches each value, exactly as it does after a single `=` — so a bare
    // literal takes the width of its place rather than defaulting to `int` and then disagreeing.
    "a literal takes the width of the place it is heading for" in {
      run(fn("""var a: u8 = 0u8
               |var b: i16 = 0i16
               |a, b = 200, -300
               |print(a, b)""".stripMargin)) shouldBe "200 -300\n"
    }

    "every place must be one" in {
      err(fn("""var a = 1
               |1, a = 1, 2""".stripMargin)) should include("something with an address")
    }

    "a 'val' is not a place to write through" in {
      err(fn("""val v = 1
               |var a = 2
               |v, a = 1, 2""".stripMargin)) should include("'val' is written once")
    }

    "a binding may not name the same thing twice" in {
      err(fn("val a, a = 1, 2")) should include("'a' is named twice in one binding")
    }

    // Each arm is checked by the rule its own operator already has, so a compound arm is refused by
    // the same diagnostic a single `s += 1` earns rather than by one this form invented.
    // An element of a container is set by a call, so it has no address the locating phase can find
    // and nothing separating what it reads from what it writes — the one place form a single `=`
    // accepts and this does not.
    "an element set through 'Index' is not a place of one" in {
      err("""struct Bag
            |    n: int
            |end Bag
            |impl IndexSet[int, int] for Bag
            |    index_set(*self, i: int, v: int)
            |        self.n = v
            |impl Index[int, int] for Bag
            |    index(self, i: int) -> int = self.n
            |demo()
            |    var b = Bag(0)
            |    var a = 1
            |    b[0], a = 1, 2
            |end demo
            |demo()
            |""".stripMargin) should include("cannot be one place of a multiple assignment")
    }

    "a compound arm is held to its operator's own rule" in {
      err(fn("""var s = ""
               |var n = 0
               |s, n += 1, 1""".stripMargin)) should include("'+' needs matching types, got string and int")
    }

    "and no arm may be given a value that never arrives" in {
      err(fn("""var a = 1
               |var b = 2
               |a, b = 1, exit(1)""".stripMargin)) should include("cannot assign")
    }

    "nor may a binding take one" in {
      err(fn("val a, b = 1, exit(1)")) should include("never returns")
    }
  }

  /** A module member states its type (`13 §2`), and this form has nowhere to write one for any of its
    * parts (`12 §5b`) — so it is a local form, and the one place that could be in doubt is a file the
    * program starts in, where `static` is what asks for the member.
    *
    * Written plain it needs no rule at all now: everything a body declares is local, so a multiple
    * `val` there is exactly what it looks like. The refusal is what `static` gets.
    */
  "a multiple 'val' is a local form" - {
    "so written plain at the top of a program it is simply a local" in {
      run("""val a, b = 1, 2
            |print(a, b)
            |""".stripMargin) shouldBe "1 2\n"
    }

    "while asking for the module's is refused, since the parts have nowhere to state a type" in {
      err("""static val a, b = 1, 2
            |print(a, b)
            |""".stripMargin) should include("nowhere to write one")
    }
  }

  "a field write re-checks its struct's invariant" - {
    "one that still holds is let through" in {
      run("""struct Span
            |    lo: int
            |    hi: int
            |    invariant lo <= hi
            |end Span
            |demo()
            |    var s = Span(1, 5)
            |    s.lo, s.hi = 2, 9
            |    print(s.lo, s.hi)
            |end demo
            |demo()
            |""".stripMargin) shouldBe "2 9\n"
    }

    // A struct that starts at its zero value is never *constructed*, so nothing else in the program
    // names the predicate — and a walk that reached it only through construction would drop it from
    // the output and leave this call with nothing to land in.
    "one whose struct was never constructed still finds its predicate" in {
      run("""struct Span
            |    lo: int
            |    hi: int
            |    invariant lo <= hi
            |end Span
            |demo()
            |    var s: Span
            |    s.lo, s.hi = 2, 9
            |    print(s.lo, s.hi)
            |end demo
            |demo()
            |""".stripMargin) shouldBe "2 9\n"
    }

    // The transition this form exists for: both fields move at once, and the state in between —
    // which the program never asked for and cannot observe — is not held against it. Checking after
    // each field in turn would refuse this for the instant `lo` was 6 and `hi` still 5.
    "a transition through a state neither end holds is let through" in {
      run("""struct Span
            |    lo: int
            |    hi: int
            |    invariant lo <= hi
            |end Span
            |demo()
            |    var s = Span(1, 5)
            |    s.lo, s.hi = 6, 8
            |    print(s.lo, s.hi)
            |end demo
            |demo()
            |""".stripMargin) shouldBe "6 8\n"
    }

    "and one that does not stops the program" in {
      exits("""struct Span
              |    lo: int
              |    hi: int
              |    invariant lo <= hi
              |end Span
              |demo()
              |    var s = Span(1, 5)
              |    s.lo, s.hi = 9, 2
              |    print(s.lo, s.hi)
              |end demo
              |demo()
              |""".stripMargin)
    }
  }

  "the three-clause 'for' takes one in its step" in {
    run(fn("""var j = 10
             |for var i = 0; i < 3; i, j = i + 1, j - 1
             |    print(i, j)""".stripMargin)) shouldBe "0 10\n1 9\n2 8\n"
  }

  // An index is bounds-checked wherever it is written, and a place of a multi-assignment is no
  // exception — the check happens in the phase that locates the places, before any store.
  "an element place is bounds-checked" in {
    exits(fn("""var xs: [2]int
               |var i = 5usize
               |xs[i], xs[0usize] = 1, 2""".stripMargin))
  }

  /** `00 §2` says the left elements are "the same set a single `=` accepts", and that the form is a
   * statement rather than an expression. Both are claims about what else is true of it, so they are
   * asked about here rather than assumed.
   */
  "the claims the section makes about the form" - {
    "a dereference is a place, as it is after a single '='" in {
      run(fn("""var a = 1
               |var b = 2
               |var p = &a
               |var q = &b
               |*p, *q = *q, *p
               |print(a, b)""".stripMargin)) shouldBe "2 1\n"
    }

    "a field through a pointer is one too" in {
      run("""struct Pair
            |    x: int
            |    y: int
            |end Pair
            |flip(p: *Pair)
            |    p.x, p.y = p.y, p.x
            |demo()
            |    var s = Pair(1, 2)
            |    flip(&s)
            |    print(s.x, s.y)
            |end demo
            |demo()
            |""".stripMargin) shouldBe "2 1\n"
    }

    "a nested element is one too" in {
      run(fn("""var grid: [2][2]int
               |grid[0usize][1usize] = 5
               |grid[1usize][0usize] = 9
               |grid[0usize][1usize], grid[1usize][0usize] = grid[1usize][0usize], grid[0usize][1usize]
               |print(grid[0usize][1usize], grid[1usize][0usize])""".stripMargin)) shouldBe "9 5\n"
    }

    "and any number of places, not only two" in {
      run(fn("""var a, b, c, d, e = 1, 2, 3, 4, 5
               |a, b, c, d, e = e, d, c, b, a
               |print(a, b, c, d, e)""".stripMargin)) shouldBe "5 4 3 2 1\n"
    }

    // A form that has no value cannot sit anywhere a value is read, and the grammar is where that is
    // settled rather than the analyzer — there is no node for a condition to ask the type of.
    "it has no value, so it cannot stand where one is read" in {
      progError("if a, b = 1, 2\n    print(1)") should not be empty
    }

    // A comma is already a separator in an argument list, so `print(a, b = 1, 2)` is three arguments
    // and not a multiple assignment. That is the reason the form commits only at statement level:
    // below one, the comma always means something else.
    //
    // What the middle argument *is* changed with `12 §2a`: `b = 1` at a call is now the named
    // argument it reads as, not the assignment it used to be. That is a decision about the call and
    // not about this form, which is why the count is what matters here — three arguments either way.
    "a comma below statement level goes on meaning what it meant" in {
      prog("print(a, b = 1, 2)") shouldBe
        List(printStmt(Ident("a"), NamedArg("b", i(1)), i(2)))
    }

    // And the escape from that collision, which belongs beside it: parentheses make the store a
    // store again, so the reading this test used to pin is still reachable and still one argument.
    "and a parenthesized one is the assignment it always was" in {
      prog("print(a, (b = 1), 2)") shouldBe
        List(printStmt(Ident("a"), Assign("=", Ident("b"), i(1)), i(2)))
    }

    // A comma inside parentheses is the *other* comma form — `(a, b)` is a tuple (§13). Worth
    // pinning so the two are not confused: putting a multi-assignment in parentheses does not make
    // it an expression, it makes it a tuple of something else.
    "and parentheses around one read as a tuple, not as this" in {
      prog("var x = (a, b)") shouldBe List(VarDecl("x", None, Some(Tuple(List(Ident("a"), Ident("b"))))))
      run("""var x = (1, 2)
            |print(x.0, x.1)
            |""".stripMargin) shouldBe "1 2\n"
    }
  }

  /** The two forms are ordinary statements, so they work wherever a statement does — which is worth
   * pinning because the analyzer hands back several of them where every other statement hands back
   * one, and a block that flattened the list in only some positions would still compile.
   */
  "it is an ordinary statement" - {
    "among the statements the program itself runs" in {
      run("""var a = 1
            |var b = 2
            |a, b = b, a
            |print(a, b)
            |""".stripMargin) shouldBe "2 1\n"
    }

    "inside a branch" in {
      run(fn("""var a = 1
               |var b = 2
               |if a < b
               |    a, b = b, a
               |print(a, b)""".stripMargin)) shouldBe "2 1\n"
    }

    "inside a loop body, where a binding is made afresh each time round" in {
      run(fn("""var total = 0
               |for i in 0..<3
               |    val lo, hi = i, i * 10
               |    total += lo + hi
               |print(total)""".stripMargin)) shouldBe "33\n"
    }

    "as the last statement of a function that returns nothing" in {
      run("""flip(p: *int, q: *int)
            |    *p, *q = *q, *p
            |demo()
            |    var a = 1
            |    var b = 2
            |    flip(&a, &b)
            |    print(a, b)
            |end demo
            |demo()
            |""".stripMargin) shouldBe "2 1\n"
    }
  }

  // Naming one place twice is two writes to it, and the last one wins — the same answer sequential
  // stores give, because the ordering rule is about the *reads*. Nothing forbids it, so what it does
  // is pinned rather than left to be discovered.
  "one place written twice takes the last value" in {
    run(fn("""var a = 0
             |a, a = 1, 2
             |print(a)""".stripMargin)) shouldBe "2\n"
  }

  /** A view is a value like any other, so an arm may carry one — and where it does, the array it
   * views is subject to the same escape rules a single `=` would have brought (`05`). What this
   * pins is that the analysis sees through the form at all: a multi-assignment it did not walk
   * would leave the array in the frame and the returned view dangling.
   */
  "a view assigned by one of these still moves its array to the heap" in {
    val src =
      """leak() -> []int
        |    var buf: [4]int
        |    var s: []int
        |    var t: []int
        |    s, t = buf[..], buf[0..<2]
        |    buf[0usize] = 7
        |    print(t.len)
        |    s
        |end leak
        |var v = leak()
        |print(v.len, v[0usize])
        |""".stripMargin

    run(src) shouldBe "2\n4 7\n"
    ir(src) should include("call ptr @malloc")
  }
}
