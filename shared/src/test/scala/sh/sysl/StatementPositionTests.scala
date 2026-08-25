package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** Statement position — a block written for effect has no value (`reference/expressions.md § Statement position discards a block's value`).
 *
 * Assignment yields the value it assigned, which is what makes `while (c = next()) != 0` writable.
 * The cost of that rule is at the *end of a block*: a branch that merely does something ends in an
 * assignment, an `i++`, or a call, and the block would otherwise claim whatever those yield — so
 * two branches under one `if` or `match`, each plainly written for effect, would be made to agree
 * on a value nobody asked for.
 *
 * Sysl has no statement terminator, so there is no `;` to write "and throw this away" with. The
 * rule instead follows the position: where a block's own value is unused, the block has none, and
 * that propagates into the branches of an `if` and the arms of a `match`. A block that does not
 * *arrive* keeps `never`, since that is reachability rather than a value.
 */
class StatementPositionTests extends AnyFreeSpec with CodegenSupport with RunSupport {

  "branches written for effect need not agree" - {
    // The shape the hash map hit: one arm sets a flag, the other counts. `bool` and `usize` have
    // nothing to meet at, and nothing was asking them to.
    "an 'if' whose branches end in assignments of different types" in {
      val src =
        """var full = false
          |var len = 0usize
          |var c = true
          |if c
          |    full = true
          |else
          |    len += 1usize
          |print(str(full))
          |print(str(len))""".stripMargin

      run(src) shouldBe "true\n0\n"
    }

    "a 'match' whose arms end in assignments of different types" in {
      val src =
        """var full = false
          |var len = 0usize
          |2 match
          |    1 -> full = true
          |    else len += 3usize
          |print(str(full))
          |print(str(len))""".stripMargin

      run(src) shouldBe "false\n3\n"
    }

    // Not only assignments: any trailing expression is discarded the same way, which is the point
    // of stating the rule about the *position* rather than about one operator.
    "branches ending in calls that return different types" in {
      val src =
        """n() -> int = 1
          |s() -> string = "x"
          |var c = false
          |if c
          |    n()
          |else
          |    s()
          |print(2)""".stripMargin

      run(src) shouldBe "2\n"
    }

    "a branch ending in an increment, which yields the old value" in {
      val src =
        """var i = 0
          |var b = false
          |if true
          |    i++
          |else
          |    b = true
          |print(str(i))""".stripMargin

      run(src) shouldBe "1\n"
    }
  }

  "the position reaches inward" - {
    "into a 'match' arm's own 'if'" in {
      val src =
        """var a = 0
          |var b = false
          |1 match
          |    1 ->
          |        if a == 0
          |            a = 5
          |        else
          |            b = true
          |    else b = false
          |print(str(a))""".stripMargin

      run(src) shouldBe "5\n"
    }

    // A function owing no value is the outermost statement position, so the `match` that is its
    // whole body is one too — this is `Map.put` with the `return` that used to dodge the rule gone.
    "into the body of a function that returns nothing" in {
      val src =
        """set(v: *int, hit: *bool, k: int)
          |    k match
          |        0 -> *hit = true
          |        else *v += k
          |var n = 0
          |var h = false
          |set(&n, &h, 4)
          |set(&n, &h, 0)
          |print(str(n))
          |print(str(h))""".stripMargin

      run(src) shouldBe "4\ntrue\n"
    }

    // The receiver form is where the rule was first wanted: a `*self` method that mutates and
    // returns nothing is the ordinary shape of one, and its whole body is often a `match`.
    "into a '*self' method that returns nothing" in {
      val src =
        """struct C
          |    n: int
          |    hit: bool
          |
          |    bump(*self, k: int)
          |        k match
          |            0 -> self.hit = true
          |            else self.n += k
          |    end bump
          |var c: C
          |c.bump(4)
          |c.bump(0)
          |print(str(c.n))
          |print(str(c.hit))""".stripMargin

      run(src) shouldBe "4\ntrue\n"
    }

    // The other direction has to keep working: an inner `match` written as a statement is
    // discarded, while the arm it sits in is still asked for the value that follows it.
    "without reaching back out of a statement into the arm around it" in {
      val src =
        """pick(n: int) -> int = n match
          |    1 ->
          |        var a = 0
          |        n match
          |            1 -> a = 5
          |            else a = 6
          |        a
          |    else 0
          |print(pick(1))""".stripMargin

      run(src) shouldBe "5\n"
    }

    "and through a loop body, whose statements were always discarded" in {
      val src =
        """var t = 0
          |var b = false
          |for i in 0..<3
          |    if i == 1
          |        b = true
          |    else
          |        t += i
          |print(str(t))
          |print(str(b))""".stripMargin

      run(src) shouldBe "2\ntrue\n"
    }
  }

  "a value position is unchanged, which is what keeps the diagnostic worth having" - {
    "two 'if' branches that disagree about a value are still refused" in {
      err("""var x = if true then 1 else "no"""") should
        include("if branches have different types: int and string")
    }

    "two 'match' arms that disagree about a value are still refused" in {
      val src =
        """var x = 3 match
          |    1 -> 10
          |    else "big"""".stripMargin

      err(src) should include("match arms have different types: int and string")
    }

    // The branches of a value-returning function's body are in value position too, so the rule
    // does not follow the *syntax* of a trailing `if` — it follows what is asking for the value.
    "and so are the branches ending the body of a function that returns one" in {
      val src =
        """f(c: bool) -> int
          |    if c
          |        1
          |    else
          |        "no"
          |print(f(true))""".stripMargin

      err(src) should include("if branches have different types")
    }

    "while the same body in a function returning nothing is fine" in {
      val src =
        """f(c: bool)
          |    if c
          |        1
          |    else
          |        "no"
          |f(true)
          |print(9)""".stripMargin

      run(src) shouldBe "9\n"
    }
  }

  "assignment still yields its value wherever one is wanted" - {
    // `reference/expressions.md § Assignment`
    "chained, as the reference writes it" in {
      run("""var a = 0
            |var b = 0
            |var c = 0
            |a = b = c = 7
            |print(str(a + b + c))""".stripMargin) shouldBe "21\n"
    }

    "captured in a condition" in {
      run("""var xs = [3, 2, 0, 9]
            |var i = 0
            |var v = 0
            |while (v = xs[i]) != 0
            |    i += 1
            |print(str(i))""".stripMargin) shouldBe "2\n"
    }

    // The rule follows the position and not the operator, which is what this pins: an assignment
    // ending the body of a function that owes a value is still that value. Deciding instead that
    // "an assignment yields `unit` at the end of a block" would have refused this.
    "as the body of a function that returns one" in {
      run("""set(p: *int) -> int = *p = 5
            |var n = 0
            |print(str(set(&n) + n))""".stripMargin) shouldBe "10\n"
    }

    // The branch's value is genuinely the assigned one where the `if` is asked for a value — the
    // rule takes it away only where nothing was asking.
    "and as a branch's value when the 'if' is bound to something" in {
      run("""var a = 0
            |var b = 0
            |var x = if true then a = 4 else b = 5
            |print(str(x))""".stripMargin) shouldBe "4\n"
    }
  }

  "a loop's 'else' is a block too" - {
    // Python's own idiom, which `reference/statements.md` cites the `else` block for: walk, leave
    // early on a hit, and record in the `else` that nothing hit. The bare `break` yields `unit` and
    // the `else` block ended in an assignment, so before the position was taken into account these
    // two were made to meet and could not.
    "so the 'for'/'else' search idiom needs nothing written round it" in {
      val src =
        """var xs = [3, 5, 8, 9]
          |var missing = false
          |for x in xs
          |    if x == 100 then break
          |else
          |    missing = true
          |print(str(missing))""".stripMargin

      run(src) shouldBe "true\n"
    }

    "the same for a 'while'" in {
      val src =
        """var i = 0
          |var ran_out = false
          |while i < 3
          |    i += 1
          |else
          |    ran_out = true
          |print(str(ran_out))""".stripMargin

      run(src) shouldBe "true\n"
    }

    // A `break` carrying a value is an explicit request for one, so it is not discarded along with
    // the block: a loop written for effect that nonetheless breaks with something is a mistake, and
    // saying so is the whole reason the `else` is mandatory in the first place.
    "while a 'break' that carries a value is still held to the 'else'" in {
      val src =
        """var a = 0
          |for i in 0..<3
          |    if i == 1 then break 7
          |else
          |    a = 1
          |print(str(a))""".stripMargin

      err(src) should include("must have the same type")
    }

    "and a loop asked for a value is unchanged" in {
      val src =
        """var found = for i in 0..<5
          |    if i == 3 then break i
          |else -1
          |print(str(found))""".stripMargin

      run(src) shouldBe "3\n"
    }
  }

  "exhaustiveness follows the same line" - {
    // `matchResultType` requires an `else` only of a match that yields a value; arms written for
    // effect never did, and now they still do not when they end in an assignment.
    "a scalar 'match' for effect needs no 'else'" in {
      val src =
        """var a = 0
          |1 match
          |    1 -> a = 1
          |    2 -> a = 2
          |print(str(a))""".stripMargin

      run(src) shouldBe "1\n"
    }

    "but one that yields a value still does" in {
      val src =
        """var x = 1 match
          |    1 -> 10
          |    2 -> 20
          |print(x)""".stripMargin

      err(src) should include("must be exhaustive")
    }

    // An enum's coverage is not about the value at all, so statement position buys nothing there.
    "and an enum 'match' must cover its variants even for effect" in {
      val src =
        """enum Colour
          |    Red
          |    Green
          |    Blue
          |var n = 0
          |Colour.Red match
          |    Red -> n = 1
          |    Green -> n = 2
          |print(str(n))""".stripMargin

      err(src) should include("not exhaustive")
    }
  }

  "a block that does not arrive keeps 'never'" - {
    // Collapsing this to `unit` would leave the merge point of a diverging `if` looking reachable,
    // and the function would end by falling out of it. What says otherwise is that `f` has no
    // `ret` at all — its last block is the merge, and the merge is `unreachable`.
    "so a discarded 'if' whose branches both diverge still ends nowhere" in {
      val src =
        """f(c: bool)
          |    if c
          |        exit(1)
          |    else
          |        exit(2)
          |f(true)""".stripMargin

      val out = defineOf(ir(src), "f")

      out should not include "ret void"
      out.trim should endWith("unreachable")
    }

    "and an arm that diverges beside arms that do not is still set aside" in {
      val src =
        """var a = 0
          |2 match
          |    1 -> exit(1)
          |    else a = 5
          |print(str(a))""".stripMargin

      run(src) shouldBe "5\n"
    }
  }

  "what the discarded value costs at runtime" - {
    // A branch's value is still computed — the rule is about the type, not about whether the code
    // runs — so effects in a discarded trailing expression happen exactly once.
    "the trailing expression still runs, and once" in {
      val src =
        """bump(n: *int) -> int
          |    *n += 1
          |    *n
          |var n = 0
          |if true
          |    bump(&n)
          |else
          |    bump(&n)
          |print(str(n))""".stripMargin

      run(src) shouldBe "1\n"
    }

    // A discarded `&T` is released where it was made rather than handed out. Before the position
    // was taken into account these two branches met at `&P`, so the `if` allocated a merge slot and
    // the statement around it released what it loaded back — a value nobody had asked for, kept
    // alive across the join for no reason.
    "and an owned value made in a discarded branch is let go where it was made" in {
      val src =
        """struct P
          |    x: int
          |var c = true
          |if c
          |    var p: &P = P(1)
          |    p
          |else
          |    var q: &P = P(2)
          |    q
          |print(3)""".stripMargin

      run(src) shouldBe "3\n"

      val out = irMain(src)

      """call void @arc\.release\(ptr %t\d+\)\n  br label %if\.end""".r.findAllIn(out).size shouldBe 2
      out should not include "load ptr, ptr %t"
    }
  }
}
