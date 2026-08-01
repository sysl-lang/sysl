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

  "each term of a chain borrows in a region of its own" - {

    // **No `is` here at all**, and that is the point. Flattening the `&&` chain took every condition
    // in the language off the short-circuit path, and with it the per-branch temp region that path
    // keeps: a second term's borrowed reference exists only on the edge that reached it, so giving
    // it back anywhere the whole chain meets is a release of something the incoming path never made.
    // It fails as invalid IR — "instruction does not dominate all uses" — rather than as a wrong
    // answer, so a plain `&&` over a borrowing right-hand side is the shape worth pinning.
    "an ordinary && whose right term borrows, and whose left term is false" in {
      run("""struct Node
            |    n: int
            |
            |make(i: int) -> &Node = Node(i)
            |
            |var gate = false
            |if gate && make(1).n > 0 then print("yes") else print("no")
            |""".stripMargin) shouldBe "no\n"
    }

    "the same in a while, whose test runs the short-circuiting edge every round" in {
      run("""struct Node
            |    n: int
            |
            |make(i: int) -> &Node = Node(i)
            |
            |var i = 0
            |while i < 400 && make(i).n >= 0
            |    i += 1
            |print(i)
            |""".stripMargin) shouldBe "400\n"
    }

    // The `is` version of the same thing: the second term's subject is a borrowed value, and it is
    // only reached when the first matched. Run enough times that a leak or a double free is not a
    // coin flip.
    "an is term whose subject borrows, reached only when the term before it held" in {
      run("""struct Node
            |    n: int
            |
            |lookup(i: int) -> Option[&Node]
            |    if i % 3 == 0 then Some(Node(i)) else None
            |
            |var i = 0
            |var hits = 0
            |while i < 600
            |    if lookup(i) is Some(a) && lookup(a.n + 3) is Some(b) then hits += b.n - a.n
            |    i += 1
            |print(hits)
            |""".stripMargin) shouldBe "600\n"
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

  "the right side is an arm's left side, `|`-alternatives and all" - {

    "several alternatives share one answer" in {
      run("""enum Step
            |    Work(n: int)
            |    Idle
            |    Done
            |
            |report(s: Step) -> string
            |    if s is Idle | Done then "over" else "running"
            |
            |print(report(Idle), report(Done), report(Work(1)))
            |""".stripMargin) shouldBe "over over running\n"
    }

    "they work under `is not` too" in {
      run("""var n = 5
            |if n is not 1 | 2 | 3 then print("outside") else print("inside")
            |""".stripMargin) shouldBe "outside\n"
    }

    // The same rule an arm's alternatives are held to (`09 §6`): the branch cannot know which of
    // them matched, so there is nothing for a name to hold.
    "and none of them may bind, for the reason an arm's may not" in {
      err("""enum Step
            |    Work(n: int)
            |    Rest(n: int)
            |
            |var s: Step = Work(1)
            |if s is Work(n) | Rest(n) then print(n)
            |""".stripMargin) should include("alternative patterns joined by '|' cannot bind a name")
    }

    "and one irrefutable alternative makes the whole test one, since it answers for the rest" in {
      err("""var n = 1
            |if n is 1 | _ then print(1)
            |""".stripMargin) should include("so the test is always true")
    }
  }

  "what the neighbouring rules say, asked of `is`" - {

    // `09 §8`: an enum match is exhaustive-checked in statement position too, so the one-arm match
    // is *forced* to write a do-nothing catch-all. That refusal is the whole reason this feature
    // exists, so it is asserted here rather than assumed — if it ever stopped being true, `is`
    // would have lost its motivation and this test is where that would show.
    "the one-arm statement match `is` replaces really is refused without a catch-all" in {
      err("""var o: Option[int] = Some(1)
            |o match
            |    Some(n) -> print(n)
            |""".stripMargin) should include("is not exhaustive")
    }

    "and the `is` form needs no such arm" in {
      run("""var o: Option[int] = Some(1)
            |if o is Some(n) then print(n)
            |""".stripMargin) shouldBe "1\n"
    }

    // `09 §11`: selection reaches through a memory mode and a pattern does not, so matching a
    // `&Enum` is written `*e`. A condition is a pattern position, so it inherits the rule and the
    // hint that goes with it.
    "a pattern in a condition does not reach through a reference either" in {
      err("""var o: &Option[int] = Some(1)
            |if o is Some(n) then print(n)
            |""".stripMargin) should include("a pattern does not reach through a memory mode")
    }

    "and the dereference is what makes it match" in {
      run("""var o: &Option[int] = Some(41)
            |if *o is Some(n) then print(n + 1)
            |""".stripMargin) shouldBe "42\n"
    }

    // `16` contracts are conditions in the ordinary sense of the word and not in this one: they
    // guard no branch, so a binding made in one would have nowhere to be live.
    "a contract is not a condition an `is` may sit in" in {
      err("""f(o: Option[int]) -> int
            |    require o is Some(_)
            |    0
            |
            |print(f(Some(1)))
            |""".stripMargin) should include("'is' tests a pattern in the condition of an 'if' or a 'while'")
    }

    // `12 §5` — a closure body is an ordinary body, and the scope an `is` opens is the branch's,
    // so a closure written inside one may capture what the pattern bound.
    "a closure inside the branch captures what the pattern bound" in {
      run("""apply(f: int -> int, x: int) -> int = f(x)
            |
            |var o: Option[int] = Some(40)
            |if o is Some(n) then print(apply(m -> m + n, 2))
            |""".stripMargin) shouldBe "42\n"
    }

    // `08 § Visibility` — naming every field positionally is reading every field, so the pattern
    // owes the same check a constructor does. A condition reaches `analyzePattern` by the same route
    // an arm does, and this is what says so at the seam rather than by inspection.
    "a positional pattern in a condition owes the same field visibility an arm's does" in {
      errOf(
        "shape.sysl" ->
          """module shape
            |
            |struct Point
            |    x: int
            |    private y: int
            |""".stripMargin,
        "main.sysl" ->
          """import shape.Point
            |
            |f(p: Point) -> int
            |    if p is Point(a, b) then a else 0
            |""".stripMargin,
      ) should include("y")
    }

    // `16 §5` — a constrained subtype is laid out as its base, so a range pattern over one is a
    // range over the base's values. Probed rather than assumed: `analyzePattern` asks whether the
    // scrutinee's type is numeric, and a subtype is a distinct `Type`.
    "a range pattern reaches a constrained subtype" in {
      run("""type Small = int within 1..10
            |
            |var n: Small = 5
            |if n is 1..6 then print("low") else print("high")
            |""".stripMargin) shouldBe "low\n"
    }
  }

  "the edge cases, each of which compiles under a rule that is not the one written" - {

    // The binding is declared *after* the subject has been read, so naming it the same thing is a
    // shadow rather than a cycle. A scope opened too early would make this read the binding.
    "a binding may shadow the name it was read out of" in {
      run("""var o: Option[int] = Some(7)
            |if o is Some(o) then print(o)
            |""".stripMargin) shouldBe "7\n"
    }

    // Two terms binding different names out of the same subject: the second `is` re-reads the
    // subject rather than seeing whatever the first bound.
    "the same subject may be tested twice in one chain" in {
      run("""var o: Option[int] = Some(20)
            |if o is Some(n) && o is Some(m) then print(n + m)
            |""".stripMargin) shouldBe "40\n"
    }

    // A nested `if is` inside a branch: the inner binding shadows the outer, and the outer is still
    // live around it. A single flat scope for all bindings would make the inner one clobber it.
    "a nested is shadows the outer binding without disturbing it" in {
      run("""var a: Option[int] = Some(1)
            |var b: Option[int] = Some(2)
            |if a is Some(n)
            |    if b is Some(n) then print(n)
            |    print(n)
            |""".stripMargin) shouldBe "2\n1\n"
    }

    // The second `is` is only reachable because the first matched, so a failed first term must not
    // evaluate the second's *subject* either — not merely skip its test.
    "a failed is does not evaluate the next term's subject" in {
      run("""noisy() -> Option[int]
            |    print("evaluated")
            |    Some(1)
            |
            |var o: Option[int] = None
            |if o is Some(n) && noisy() is Some(m) then print(n + m) else print("no")
            |""".stripMargin) shouldBe "no\n"
    }

    // The binding is the *last* term of the chain, so it is established in the branch's own entry
    // block rather than in an intermediate one — a different code path from the chains above.
    "a chain whose last term is the binding one" in {
      run("""var gate = true
            |var o: Option[int] = Some(41)
            |if gate && o is Some(n) then print(n + 1)
            |""".stripMargin) shouldBe "42\n"
    }

    "a branch that does not finish is still a branch the binding reaches" in {
      run("""find(xs: []int, want: int) -> int
            |    for x in xs
            |        var o: Option[int] = if x == want then Some(x) else None
            |        if o is Some(hit) then return hit
            |    -1
            |
            |var nums: [3]int = [4, 5, 6]
            |var xs = nums[..]
            |print(find(xs, 5), find(xs, 9))
            |""".stripMargin) shouldBe "5 -1\n"
    }

    // A refcounted value bound by the test and handed out as the `if`'s value: it leaves the scope
    // that released it, so it has to have taken a count of its own on the way.
    "a refcounted binding may be the branch's value" in {
      run("""var o: Option[string] = Some("hi" + "!")
            |var s = if o is Some(t) then t else "none"
            |print(s)
            |""".stripMargin) shouldBe "hi!\n"
    }

    // `continue` and `break` leave the body without reaching its bottom, so the releases owed for
    // the round's binding have to be emitted on those edges too. Repeated enough times that a leak
    // or a double free is not a coin flip.
    "a continue out of a while-is body releases the round's binding" in {
      run("""make(i: int) -> Option[&string]
            |    if i < 400 then Some("ab" + "cd") else None
            |
            |var i = 0
            |var hits = 0
            |while make(i) is Some(s)
            |    i += 1
            |    if i % 2 == 0 then continue
            |    hits += 1
            |print(hits)
            |""".stripMargin) shouldBe "200\n"
    }

    "and a break out of one does too" in {
      run("""make(i: int) -> Option[&string]
            |    Some("ab" + "cd")
            |
            |var rounds = 0
            |var i = 0
            |while i < 400
            |    while make(i) is Some(s)
            |        rounds += 1
            |        break
            |    i += 1
            |print(rounds)
            |""".stripMargin) shouldBe "400\n"
    }
  }
}
