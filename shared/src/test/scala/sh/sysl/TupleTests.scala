package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** `(a, b)` — a positional product with no name (`00 §13`).
 *
 * A tuple **is** a struct with its fields named for their positions, so most of what is worth
 * testing is that the reuse is real rather than approximate: the same layout, the same counting,
 * the same patterns, the same generic arguments. The tests that could not pass by accident are the
 * ones about the two things a tuple has that a struct does not — an arity that no implementation
 * can be generic over, and a spelling that collides with a float.
 */
class TupleTests extends AnyFreeSpec with ParseSupport with RunSupport with CodegenSupport {

  private def fn(body: String): String =
    s"""demo()
       |${body.linesIterator.map("    " + _).mkString("\n")}
       |end demo
       |demo()
       |""".stripMargin

  "the type and its values" - {
    "a literal builds one, and a written type names it" in {
      run("""var p: (int, string) = (1, "one")
            |print(p.0, p.1)
            |""".stripMargin) shouldBe "1 one\n"
    }

    "the type is inferred where none is written" in {
      run("""var q = (3, 4)
            |print(q.0 + q.1)
            |""".stripMargin) shouldBe "7\n"
    }

    // The same narrowing a struct's fields get: what the tuple is wanted at reaches each part, so a
    // bare literal takes the width of the position it is heading for rather than defaulting.
    "a part takes the width of the position it is heading for" in {
      run("""var p: (i8, u16) = (1, 2)
            |print(p.0 + 1i8, p.1 + 1u16)
            |""".stripMargin) shouldBe "2 3\n"
    }

    "it goes into and comes out of a function" in {
      run("""swap(p: (int, string)) -> (string, int) = (p.1, p.0)
            |var r = swap((1, "one"))
            |print(r.0, r.1)
            |""".stripMargin) shouldBe "one 1\n"
    }

    "a part is a place, so it can be written through" in {
      run(fn("""var t = (1, 2)
               |t.0 = 5
               |t.1 += 1
               |print(t.0, t.1)""".stripMargin)) shouldBe "5 3\n"
    }

    // `unit` occupies no storage (§12), and a tuple lays out as a struct — so a `unit` part is
    // skipped and the parts behind it shift past, which is only visible if the indices still agree.
    "a 'unit' part is skipped by the layout without moving the parts behind it" in {
      run("""var u: (unit, int, unit, string) = ((), 7, (), "z")
            |print(u.1, u.3)
            |""".stripMargin) shouldBe "7 z\n"
    }
  }

  "two or more parts, never one" - {
    "a one-part type is refused, and told what it already is" in {
      err("var x: (int) = 1") should include("a tuple has two or more parts")
    }

    // `(x)` is a grouping and always will be, so there is no expression form to refuse — the
    // parenthesised expression is simply what it has always been.
    "a parenthesised expression stays a parenthesised expression" in {
      run("print((1) + 2)") shouldBe "3\n"
      expr("(1)") shouldBe i(1)
    }

    "and there is no trailing-comma spelling to reach a one-tuple by" in {
      progError("var y = (1,)") should not be empty
    }

    "a one-part pattern is refused too" in {
      progError("""x match
                  |    (a) -> print(a)
                  |""".stripMargin) should include("two or more parts")
    }
  }

  "reading a part by position" - {
    "the parser reads '.0' as the field it is" in {
      expr("t.0") shouldBe Field(Ident("t"), "0")
    }

    // The wart §13 takes deliberately: `0.1` is a float to the lexer, so the nested selection needs
    // parentheses. Being told exactly which parentheses is the whole of what makes it affordable.
    "a nested selection needs parentheses, and says so when it does not have them" in {
      run("""var n = ((1, 2), 3)
            |print((n.0).1)
            |""".stripMargin) shouldBe "2\n"
      progError("print((1, 2).0.1)") should include("write '(x.0).1'")
    }

    "an index past the last part is its own complaint" in {
      err("print((1, 2).2)") should include("has 2 parts, so there is no '.2'")
    }

    "and a name that is not an index falls back to the member that is missing" in {
      err("print((1, 2).foo)") should include("no field or property 'foo'")
    }

    "a part of something that is not a tuple is not a part at all" in {
      err("print((1).0)") should include("cannot read field '0' of int")
    }
  }

  "destructured by pattern" - {
    "one sub-pattern per part, binding each" in {
      run("""var p = (1, "one")
            |p match
            |    (1, t) -> print("one and", t)
            |    (_, _) -> print("other")
            |""".stripMargin) shouldBe "one and one\n"
    }

    "a wildcard leaves a part unbound" in {
      run("""var p = (7, 8)
            |p match
            |    (_, b) -> print(b)
            |""".stripMargin) shouldBe "8\n"
    }

    "a tuple pattern nests, in either position" in {
      run(fn("""var m = (1, (2, 3))
               |m match
               |    (1, (x, y)) -> print("inner", x, y)
               |    (_, _) -> print("no")
               |var n = ((1, 2), 3)
               |n match
               |    ((_, b), c) -> print(b, c)""".stripMargin)) shouldBe "inner 2 3\n2 3\n"
    }

    "a pattern of the wrong length is refused against the type it was matched on" in {
      err("""var p = (1, 2)
            |p match
            |    (a, b, c) -> print(a)
            |""".stripMargin) should include("has 2 parts, but 3 sub-patterns were given")
    }

    "and a tuple pattern against something else says what it does match" in {
      err("""var p = 1
            |p match
            |    (a, b) -> print(a)
            |""".stripMargin) should include("matches a tuple, but the value is int")
    }
  }

  /** §13's "one comma syntax, three things that feed it" — the third feed. The first two are
   * `MultiAssignTests`'; this is the one that needs a tuple to exist.
   */
  "taken apart at a binding or an assignment" - {
    "a binding names each part" in {
      run(fn("""var p = (1, "one")
               |var a, b = p
               |print(a, b)""".stripMargin)) shouldBe "1 one\n"
    }

    "an assignment writes each part into a place" in {
      run(fn("""var p = (1, "one")
               |var a = 0
               |var s = ""
               |a, s = p
               |print(a, s)""".stripMargin)) shouldBe "1 one\n"
    }

    // A result list is not built yet, and a function returning a tuple is what stands in for one —
    // which is exactly §13's point that the caller usually cannot tell the two apart.
    "a call's one tuple comes apart the same way" in {
      run("""divmod(a: int, b: int) -> (int, int) = (a / b, a % b)
            |var q, r = divmod(17, 5)
            |print(q, r)
            |""".stripMargin) shouldBe "3 2\n"
    }

    // The carrier is evaluated **once**, which a form that read `f()` per place would fail. The
    // counter is what makes a second call visible.
    "and the carrier is evaluated exactly once" in {
      run("""pair() -> (int, int)
            |    print("called")
            |    (1, 2)
            |end pair
            |var a, b = pair()
            |print(a, b)
            |""".stripMargin) shouldBe "called\n1 2\n"
    }

    "a carrier of the wrong arity is refused with both counts" in {
      err(fn("""var p = (1, 2)
               |var a, b, c = p""".stripMargin)) should include("has 2 parts to give them")
    }

    "and something that is not a tuple cannot be taken apart at all" in {
      err(fn("""var a = 1
               |var b = 2
               |a, b = 1""".stripMargin)) should include("one int is not something to take apart")
    }
  }

  "laid out and counted as a struct" - {
    // The reuse claim, checked where it would show: a tuple's aggregate is its parts in order, and
    // it is named for the arity so two arities cannot share one.
    "the emitted aggregate is the parts in order" in {
      ir("""var p = (1, "one")
           |print(p.0)
           |""".stripMargin) should include("""%struct.$tuple2.int.string = type { i32, { ptr, ptr, i64 } }""")
    }

    "a counted part is retained and released like a field" in {
      run("""struct Node
            |    v: int
            |""".stripMargin + fn("""var i = 0
               |while i < 20000
               |    var nd: &Node = Node(i)
               |    var p = (nd, "s" + str(i))
               |    var q = p
               |    i += 1
               |print("done")""".stripMargin)) shouldBe "done\n"
    }

    // Nesting the two the other way round: the ARC walk reaches a reference two aggregates deep
    // only if a tuple is walked as the struct it is rather than skipped as a type it does not know.
    "a tuple is a struct's field, counted through both" in {
      run("""struct Node
            |    v: int
            |struct Holder
            |    p: (&Node, string)
            |    n: int
            |""".stripMargin + fn("""var i = 0
               |while i < 20000
               |    var nd: &Node = Node(i)
               |    var h = Holder((nd, "s" + str(i)), i)
               |    var g = h
               |    i += 1
               |print("done")""".stripMargin)) shouldBe "done\n"
    }

    "a tuple of nothing but 'unit' parts is a tuple with no storage" in {
      run("""var z: (unit, unit) = ((), ())
            |print("ok")
            |""".stripMargin) shouldBe "ok\n"
    }

    "a tuple sits in an array and comes back out" in {
      run(fn("""var a = [(1, "a"), (2, "b")]
               |for e in a
               |    print(e.0, e.1)""".stripMargin)) shouldBe "1 a\n2 b\n"
    }

    // What a result list genuinely cannot do (§13): the pair is *held*.
    "a tuple stands as a generic argument" in {
      run("""find(k: int) -> Option[(string, int)] =
            |    if k == 1 then Some(("one", 1)) else None
            |find(1) match
            |    Some(p) -> print(p.0, p.1)
            |    None -> print("none")
            |""".stripMargin) shouldBe "one 1\n"
    }

    "and is the element a cursor iterates" in {
      run("""struct Cursor
            |    i: int
            |impl Iterate[(int, string)] for Cursor
            |    next(*self) -> Option[(int, string)]
            |        if self.i >= 3 then return None
            |        var n = self.i
            |        self.i += 1
            |        Some((n, "x" + str(n)))
            |    end next
            |var c = Cursor(0)
            |for kv in c
            |    print(kv.0, kv.1)
            |""".stripMargin) shouldBe "0 x0\n1 x1\n2 x2\n"
    }
  }

  "the traits the library provides structurally" - {
    "equality compares part by part" in {
      run("""print((1, 2) == (1, 2), (1, 2) == (1, 3), (1, "a") == (1, "a"))""") shouldBe "true false true\n"
    }

    "ordering is lexicographic, first part first" in {
      run("""print((1, 2) < (1, 3), (2, 0) < (1, 3), (1, 2) < (1, 2))""") shouldBe "true false false\n"
    }

    "and it reaches the parts behind an equal first one, not just the first" in {
      run("""print((1, 1, 2) < (1, 1, 3), (1, 2, 9) < (1, 1, 0))""") shouldBe "true false\n"
    }

    "a hash agrees with the equality and separates the order of the parts" in {
      run("""print((1, 2).hash() == (1, 2).hash(), (1, 2).hash() == (2, 1).hash())""") shouldBe "true false\n"
    }

    "rendering writes the parts in parentheses" in {
      run("""print((1, "one"))
            |print(str((1, 2, 3)))
            |""".stripMargin) shouldBe "(1, one)\n(1, 2, 3)\n"
    }

    // A specifier describes the field the **whole** value occupies (`14 §2`), so the padding is
    // applied once around the finished text rather than handed to each part.
    "and a specifier pads the whole rendering, not each part" in {
      run("""var p = (1, 2)
            |print(f"${p}%10s|", f"${p}%-10s|")
            |""".stripMargin) shouldBe "    (1, 2)| (1, 2)    |\n"
    }

    "a part with no membership takes the trait away from the whole" in {
      err("""struct Opaque
            |    v: int
            |var p = (1, Opaque(2))
            |print(p == p)
            |""".stripMargin) should include("which does not implement it")
    }

    // Arity is a shape and nothing can be generic over one, so the library stops somewhere — and
    // where it stopped is the only useful thing to say, since the fix is a struct rather than a
    // fourth implementation.
    /** There is no widest arity, and there used to be. The rows are written over a type pack
     * (`10 §10`) and cover every tuple, so what a wide one is told is what any other type is told:
     * which of its parts does not implement the trait. The sentence that named the ceiling and sent
     * the reader to write a struct went with the ceiling.
     */
    "and a tuple wider than three implements the catalog like any other" in {
      run("print((1, 2, 3, 4) == (1, 2, 3, 4))") shouldBe "true\n"
    }
  }

  /** §13's paragraph on who owns a tuple's traits, checked against `02`'s rule rather than against
   * an exception written for tuples — which is the whole claim it makes.
   */
  "who may implement what for one" - {
    "a user's own trait may be implemented for a tuple written out in full" in {
      run("""trait Tag
            |    tag(self) -> string
            |impl Tag for (int, string)
            |    tag(self) -> string = "pair " + str(self.0)
            |print((1, "a").tag())
            |""".stripMargin) shouldBe "pair 1\n"
    }

    "and for every tuple of an arity at once" in {
      run("""trait Tag
            |    tag(self) -> string
            |impl[A: Display, B: Display] Tag for (A, B)
            |    tag(self) -> string = str(self.0) + "/" + str(self.1)
            |print((1, "a").tag(), (2, 3).tag())
            |""".stripMargin) shouldBe "1/a 2/3\n"
    }

    // Each arity is its own key, so a pair's implementation says nothing about a triple's — which
    // is what lets the library write one per arity without them colliding.
    "an implementation for one arity does not cover another" in {
      err("""trait Tag
            |    tag(self) -> string
            |impl[A: Display, B: Display] Tag for (A, B)
            |    tag(self) -> string = str(self.0)
            |print((1, 2, 3).tag())
            |""".stripMargin) should include("has no method 'tag'")
    }

    "a shape and a tuple written out in full may not both supply one name" in {
      err("""trait Tag
            |    tag(self) -> string
            |impl Tag for (int, string)
            |    tag(self) -> string = "one"
            |impl[A, B] Tag for (A, B)
            |    tag(self) -> string = "any"
            |print(1)
            |""".stripMargin) should include("every tuple of 2 parts")
    }

    // §13 says a user may not write `impl Eq for (int, string)` — and that this is the existing
    // rule producing the expected answer rather than a new one. The message is the shape rule's.
    /** §13's own words for this case: *"the trait and the type are both the library's — that is the
     * existing rule producing the expected answer, not a new one."* The rule that answers is
     * coherence, and it is what the reader needs: the fix is a trait of their own or a type of their
     * own in the subject, not a differently-worded second row.
     *
     * It used to be answered by the *duplicate* check instead, because the library's row was filed
     * under this arity's shape and the lookup found it there. The row is under the pack's key now
     * (`10 §10`), so the arity's shape holds nothing and coherence is reached first — which is the
     * rule §13 names.
     */
    "and the library's own rows may not be given a second implementation" in {
      err("""impl Eq for (int, int)
            |    eq(self, rhs: Self) -> bool = true
            |print(1)
            |""".stripMargin) should include("so this one has no home")
    }
  }

  /** Two blocks writing `(A, B)` mean two different types, because each block's parameters carry
   * what that block asked of them. Getting this wrong is invisible in a program with one such
   * block and breaks every one after the first — which is exactly how it was found.
   */
  "a tuple over type parameters belongs to the block that wrote it" - {
    "two blocks may each ask something different of the same spelling" in {
      run("""trait First
            |    first(self) -> string
            |trait Second
            |    second(self) -> string
            |impl[A: Display, B: Display] First for (A, B)
            |    first(self) -> string = str(self.0)
            |impl[A: Hash, B: Hash] Second for (A, B)
            |    second(self) -> string = str(self.0.hash() == self.0.hash())
            |print((1, 2).first(), (1, 2).second())
            |""".stripMargin) shouldBe "1 true\n"
    }

    "and the parts of a generic tuple are not laid out as a type of their own" in {
      ir("""trait Tag
           |    tag(self) -> string
           |impl[A: Display, B: Display] Tag for (A, B)
           |    tag(self) -> string = str(self.0)
           |print((1, 2).tag())
           |""".stripMargin) should not include "$tuple2.A.B"
    }
  }

  "the parts must be values" - {
    "a part that never arrives is not a part" in {
      err("""var p = (1, exit(1))
            |print(p.0)
            |""".stripMargin) should include("never produces one")
    }

    "and the two sides of a comparison must be the same tuple" in {
      err("print((1, 2) == (1, 2, 3))") should include("needs matching types, got (int, int) and (int, int, int)")
    }
  }
}
