package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** `x is Pat` in the condition of an `if` or a `while` (`09 §12`).
 *
 * The feature is a binding whose reach is stated rather than inferred, so most of what is worth
 * pinning is *where a name is and is not visible* — and a test that only shows the binding working
 * shows nothing about that. Every reach test here has the position it does **not** reach beside it.
 */
class IsPatternTests extends AnyFreeSpec with RunSupport with CodegenSupport {

  /** Something that yields an `Option` at run time, so the test cannot be answered by folding. */
  private val fetch =
    """fetch(i: int) -> Option[int]
      |    if i < 3 then Some(i * 10) else None
      |
      |""".stripMargin

  "the pattern binds for the rest of the condition and the branch it guards" - {

    "the plain form, which is the one-arm match written without the arm" in {
      run("""var o: Option[int] = Some(41)
            |if o is Some(n) then print(n + 1)
            |""".stripMargin) shouldBe "42\n"
    }

    // The value the branch prints has to come *out of* the pattern, or a test would pass against an
    // implementation that only got the tag right and bound garbage.
    "the binding holds the payload, not merely the fact that it matched" in {
      run(fetch + """if fetch(2) is Some(n) then print(n) else print(-1)
                    |if fetch(9) is Some(n) then print(n) else print(-1)
                    |""".stripMargin) shouldBe "20\n-1\n"
    }

    "the else is optional, and the no-match path then does nothing" in {
      run(fetch + """if fetch(9) is Some(n) then print(n)
                    |print("after")
                    |""".stripMargin) shouldBe "after\n"
    }

    "an indented branch reads the binding throughout" in {
      run("""var o: Option[int] = Some(4)
            |if o is Some(n)
            |    var d = n * 2
            |    print(d + n)
            |""".stripMargin) shouldBe "12\n"
    }

    // An `if` is an expression, so the binding has to survive into the position the value comes
    // from and not only into a statement body.
    "and it is an expression, so the branch's value may be what the pattern bound" in {
      run(fetch + """var a = if fetch(1) is Some(n) then n else 0
                    |var b = if fetch(7) is Some(n) then n else 0
                    |print(a, b)
                    |""".stripMargin) shouldBe "10 0\n"
    }
  }

  "the binding does not reach past the branch it guards" - {

    // The three positions that are *next to* the then-branch and must not see the name. Each would
    // be a different mistake in the scoping, and each has to be asked separately.
    "not the else branch" in {
      err("""var o: Option[int] = Some(1)
            |if o is Some(n) then print(n) else print(n)
            |""".stripMargin) should include("undefined name 'n'")
    }

    "not an elif's condition" in {
      err("""var o: Option[int] = Some(1)
            |if o is Some(n) then print(n)
            |elif n > 0 then print(2)
            |""".stripMargin) should include("undefined name 'n'")
    }

    "not after the whole if" in {
      err("""var o: Option[int] = Some(1)
            |if o is Some(n) then print(n)
            |print(n)
            |""".stripMargin) should include("undefined name 'n'")
    }
  }

  "chaining with && is what makes the form worth having" - {

    // Without this the feature covers only the unguarded sliver: `09 §7` already gives a match arm
    // a guard, so the moment a condition appears the reader is back at `match`.
    "a term to the right of the is reads what it bound" in {
      run(fetch + """if fetch(2) is Some(n) && n > 15 then print(n) else print(-1)
                    |if fetch(1) is Some(n) && n > 15 then print(n) else print(-1)
                    |""".stripMargin) shouldBe "20\n-1\n"
    }

    "a term to the left of it is an ordinary test, evaluated first" in {
      run(fetch + """var gate = false
                    |if gate && fetch(2) is Some(n) then print(n) else print(-1)
                    |gate = true
                    |if gate && fetch(2) is Some(n) then print(n) else print(-1)
                    |""".stripMargin) shouldBe "-1\n20\n"
    }

    // Two bindings in one chain, where the second is only reachable because the first bound: the
    // shape a nested `match` would need two levels of indentation for.
    "two is terms chain, the second seeing the first's binding" in {
      run(fetch + """if fetch(1) is Some(n) && fetch(n / 10 + 1) is Some(m) then print(n, m)
                    |else print(-1)
                    |""".stripMargin) shouldBe "10 20\n"
    }

    "and the chain fails as a whole when the second does not match" in {
      run(fetch + """if fetch(2) is Some(n) && fetch(n) is Some(m) then print(n, m)
                    |else print(-1)
                    |""".stripMargin) shouldBe "-1\n"
    }

    // Short-circuiting is not an optimization here: the term to the right of an `is` may only be
    // *written* because the binding exists, so evaluating it when the pattern failed would read a
    // name whose value was never established.
    "a term after a failed is is not evaluated" in {
      run("""noisy(x: int) -> bool
            |    print("evaluated")
            |    x > 0
            |
            |var o: Option[int] = None
            |if o is Some(n) && noisy(n) then print("yes") else print("no")
            |""".stripMargin) shouldBe "no\n"
    }
  }

  "`is not` is the early-exit guard, and binds nothing" - {

    "it holds exactly when the pattern does not match" in {
      run(fetch + """if fetch(9) is not Some(_) then print("empty") else print("full")
                    |if fetch(1) is not Some(_) then print("empty") else print("full")
                    |""".stripMargin) shouldBe "empty\nfull\n"
    }

    "it chains like any other term" in {
      run("""var o: Option[int] = None
            |var ready = true
            |if ready && o is not Some(_) then print("nothing yet")
            |""".stripMargin) shouldBe "nothing yet\n"
    }

    "and a pattern under it may not bind, there being no path on which the name holds anything" in {
      err("""var o: Option[int] = Some(1)
            |if o is not Some(n) then print(n)
            |""".stripMargin) should include("a pattern under 'is not' cannot bind a name")
    }
  }

  "`while` takes the same condition" - {

    "the drain loop, whose binding is remade each round" in {
      run(fetch + """var i = 0
                    |while fetch(i) is Some(v)
                    |    print(v)
                    |    i += 1
                    |""".stripMargin) shouldBe "0\n10\n20\n"
    }

    "a chained term ends the loop early, exactly as it ends a branch" in {
      run(fetch + """var i = 0
                    |while fetch(i) is Some(v) && v < 20
                    |    print(v)
                    |    i += 1
                    |""".stripMargin) shouldBe "0\n10\n"
    }

    // A `while`'s `else` runs on the round that finished the loop, which is the round on which
    // nothing was bound — so it is on the far side of the binding's scope, like an `if`'s.
    "the else runs on normal completion and cannot read the binding" in {
      err(fetch + """var i = 0
                    |while fetch(i) is Some(v)
                    |    i += 1
                    |else
                    |    print(v)
                    |""".stripMargin) should include("undefined name 'v'")
    }

    "and a break leaves with the loop's value" in {
      run(fetch + """var i = 0
                    |var found = while fetch(i) is Some(v)
                    |    if v == 10 then break v
                    |    i += 1
                    |else -1
                    |print(found)
                    |""".stripMargin) shouldBe "10\n"
    }
  }

  "every pattern form a match arm may write, a condition may write" - {

    "a struct pattern, named" in {
      run("""struct Point
            |    x: int
            |    y: int
            |
            |var p = Point(3, 4)
            |if p is Point{x: 3, y} then print(y) else print(-1)
            |""".stripMargin) shouldBe "4\n"
    }

    "a tuple pattern" in {
      run("""var t = (1, 2)
            |if t is (1, b) then print(b) else print(-1)
            |""".stripMargin) shouldBe "2\n"
    }

    "a literal pattern, which binds nothing and is still a test" in {
      run("""var n = 7
            |if n is 7 then print("seven") else print("other")
            |""".stripMargin) shouldBe "seven\n"
    }

    "a range pattern" in {
      run("""var n = 42
            |if n is 40..50 then print("in") else print("out")
            |""".stripMargin) shouldBe "in\n"
    }

    "a nested variant pattern, reaching two levels in" in {
      run("""enum Step
            |    Work(n: int)
            |    Idle
            |
            |var o: Option[Step] = Some(Work(9))
            |if o is Some(Work(n)) then print(n) else print(-1)
            |""".stripMargin) shouldBe "9\n"
    }

    "and a nullary variant, which is a test with nothing to bind" in {
      run("""enum Step
            |    Work(n: int)
            |    Idle
            |
            |var s: Step = Idle
            |if s is Idle then print("idle") else print("busy")
            |""".stripMargin) shouldBe "idle\n"
    }
  }

  "the positions an `is` may not be written" - {

    // The rule is about the binding, not the boolean: `||` has no path on which the binding is
    // known to have been made, and `!` inverts the one that would have made it.
    "not under ||" in {
      err("""var o: Option[int] = Some(1)
            |if o is Some(n) || false then print(n)
            |""".stripMargin) should include("'is' tests a pattern in the condition of an 'if' or a 'while'")
    }

    "not under !" in {
      err("""var o: Option[int] = Some(1)
            |if !(o is Some(_)) then print(0)
            |""".stripMargin) should include("'is' tests a pattern in the condition of an 'if' or a 'while'")
    }

    "not as a value" in {
      err("""var o: Option[int] = Some(1)
            |var b = o is Some(_)
            |""".stripMargin) should include("'is' tests a pattern in the condition of an 'if' or a 'while'")
    }

    "not as an argument" in {
      err("""var o: Option[int] = Some(1)
            |print(o is Some(_))
            |""".stripMargin) should include("'is' tests a pattern in the condition of an 'if' or a 'while'")
    }

    "not in a match arm's guard" in {
      err("""var o: Option[int] = Some(1)
            |var n = 1
            |n match
            |    x if o is Some(_) -> print(x)
            |    else print(0)
            |""".stripMargin) should include("'is' tests a pattern in the condition of an 'if' or a 'while'")
    }

    // A three-clause `for`'s condition is a `while`'s in every other respect, so this is the one
    // refusal that is a boundary rather than a principle — recorded so that moving it is a decision
    // somebody makes rather than something that drifts.
    "and not in a three-clause for's condition" in {
      err("""var o: Option[int] = Some(1)
            |for var i = 0; o is Some(n); i += 1 do print(n)
            |""".stripMargin) should include("'is' tests a pattern in the condition of an 'if' or a 'while'")
    }
  }

  "a test that cannot fail is refused rather than folded away" - {

    "a bare binding, which is a declaration wearing a test's clothes" in {
      err("""var o: Option[int] = Some(1)
            |if o is x then print(1)
            |""".stripMargin) should include("matches every sysl.Option[int], so the test is always true")
    }

    "a wildcard" in {
      err("""var n = 1
            |if n is _ then print(1)
            |""".stripMargin) should include("matches every int, so the test is always true")
    }

    "a struct pattern whose every field is irrefutable, a struct having no tag to test" in {
      err("""struct Point
            |    x: int
            |    y: int
            |
            |var p = Point(1, 2)
            |if p is Point{x} then print(x)
            |""".stripMargin) should include("matches every Point, so the test is always true")
    }

    // The same pattern under `is not` is never true rather than always true, so it gets its own
    // words — the reader's mistake is a different one.
    "and under `is not` it is never true, which is said differently" in {
      err("""var n = 1
            |if n is not _ then print(1)
            |""".stripMargin) should include("so 'is not' is never true here")
    }

    // A one-variant enum stays refutable: the tag is read and compared either way, and adding a
    // second variant must not change what an existing condition means.
    "though a variant pattern is refutable even where the enum has one variant" in {
      run("""enum Only
            |    Just(n: int)
            |
            |var o = Just(5)
            |if o is Just(n) then print(n)
            |""".stripMargin) shouldBe "5\n"
    }
  }

  "`is` and `not` stay ordinary names" - {

    "both may be declared and read" in {
      run("""var is = 3
            |var not = 4
            |print(is + not)
            |""".stripMargin) shouldBe "7\n"
    }

    // The word is read as a keyword only where a pattern may follow it, so a variable called `is`
    // at the head of a statement is still that variable.
    "and a statement may start with one" in {
      run("""var is = 3
            |is += 1
            |print(is)
            |""".stripMargin) shouldBe "4\n"
    }
  }

  "the counts a binding takes are given back on every path out" - {

    // A binding that holds a reference is retained into its slot, so the paths that leave without
    // reaching the branch have to hand it back. The chain below fails at its *second* term, after
    // the first has already bound — the one edge a naive lowering leaks on.
    "including the edge where a later term of the chain fails" in {
      run("""struct Node
            |    n: int
            |
            |var o: Option[&Node] = Some(Node(7))
            |var i = 0
            |while i < 3
            |    if o is Some(p) && p.n > 100 then print("big") else print("small")
            |    i += 1
            |""".stripMargin) shouldBe "small\nsmall\nsmall\n"
    }

    "and the edge where the branch was taken" in {
      run("""struct Node
            |    n: int
            |
            |var o: Option[&Node] = Some(Node(7))
            |var i = 0
            |while i < 3
            |    if o is Some(p) then print(p.n)
            |    i += 1
            |""".stripMargin) shouldBe "7\n7\n7\n"
    }

    // A loop's binding is per-iteration, which is what keeps a drain of a million elements from
    // holding a million counts. Asserted through a run rather than through the IR, since what is
    // wanted is that the program finishes rather than that a particular instruction is present.
    "and a while's binding is remade and released each round" in {
      run("""struct Node
            |    n: int
            |
            |make(i: int) -> Option[&Node]
            |    if i < 200 then Some(Node(i)) else None
            |
            |var i = 0
            |var total = 0
            |while make(i) is Some(p)
            |    total += p.n
            |    i += 1
            |print(total)
            |""".stripMargin) shouldBe "19900\n"
    }
  }

  "the shape of what is emitted" - {

    // A condition with no `is` in it must generate what it generated before the feature existed:
    // one test, one conditional branch, no scope of its own.
    "an ordinary condition is unchanged" in {
      val out = ir("var n = 1\nif n > 0 then print(1)\n")
      out should include("icmp sgt")
      out should not include "cond.bind"
      out should not include "cond.unbind"
    }

    // The pattern's test is a pure read, so it happens *before* anything is committed to — the tag
    // is compared on the way in to the branch, and the binding is stored once the branch is the one
    // being taken. A failure therefore retains nothing it then has to release.
    "a pattern's test precedes its bindings" in {
      val out   = ir("""var o: Option[int] = Some(1)
                       |if o is Some(n) then print(n)
                       |""".stripMargin)
      val entry = out.indexOf("if.then")
      entry should be > -1
      out.indexOf("icmp eq")           should be < entry
      out.indexOf("ptr %n.addr", entry) should be > entry
    }

    // Nothing is retained for a binding that holds no reference, so no unbind block is generated
    // for a chain that fails after one — the machinery is there for the counts, not for the names.
    "a chain over plain data needs no unbind block" in {
      val out = ir("""var o: Option[int] = Some(1)
                     |if o is Some(n) && n > 0 then print(n)
                     |""".stripMargin)
      out should include("cond.bind")
      out should not include "cond.unbind"
    }
  }

  "an `is` survives the artifact a library is carried in" - {

    // A generic function's body is stored as a **tree** and re-analyzed at each instantiation, so an
    // `is` written in one is encoded and decoded rather than compiled where it stands. Nothing in
    // the ordinary run tests reaches that path, and without a codec tag it is where the feature
    // would fail — for libraries only, which is the failure that would ship.
    "the codec carries one, both spellings, whole" in {
      val src =
        """pick[T](o: Option[T], fallback: T) -> T
          |    if o is Some(v) then v else fallback
          |
          |empty[T](o: Option[T]) -> bool
          |    if o is not Some(_) then true else false
          |""".stripMargin
      // A `Source` compares by identity, so the one the fixture was parsed from is handed to the
      // decoder by name — otherwise the trees differ in the object each position points at and the
      // comparison would fail for a reason that is not about `is`.
      val source = Source("<t>", src)
      val before = SyslParser.parse(source) match
        case Right(p) => p
        case Left(e)  => fail(s"the fixture does not parse: $e")

      AstCodec.decode(AstCodec.encode(List(before)), Map("<t>" -> source)) match
        case Right(List(after)) => after shouldBe before
        case Right(other)       => fail(s"expected one program, got ${other.length}")
        case Left(e)            => fail(s"decode failed: $e")
    }

    // A generic body really is re-analyzed from that tree, so the instantiation has to work as well
    // as the round trip — the codec being right about the shape says nothing about the name the
    // pattern binds surviving into the analyzer that reads it back.
    "and a generic function whose body tests one instantiates at two types" in {
      run("""pick[T](o: Option[T], fallback: T) -> T
            |    if o is Some(v) then v else fallback
            |
            |var a: Option[int] = Some(41)
            |var b: Option[int] = None
            |var c: Option[bool] = Some(true)
            |print(pick(a, 0), pick(b, 9), pick(c, false))
            |""".stripMargin) shouldBe "41 9 true\n"
    }
  }
}
