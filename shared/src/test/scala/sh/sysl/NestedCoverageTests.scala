package sh.sysl

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

/** Coverage computed through nested patterns (`09 §8`).
  *
  * `guide/bytecode` reported that `Some(Halt)`, `Some(Push)` and `None` cover an `Option[Op]`
  * between them and the analyzer asked for an `else` anyway. The question that had to be answered
  * before any code was whether that is a bug or a limit, and `09 §8` answers it itself: coverage is
  * *"about which values are guaranteed handled, not merely which tags appear"*. Those three arms
  * guarantee every value is handled, so refusing them contradicts the rule rather than approximating
  * it — a bug.
  *
  * What replaces the old check is the standard matrix algorithm: arms cover a type *together*, so
  * they are read together, one row per unguarded pattern and one column per value still being
  * discriminated. The half of the rule the old check got right survives unchanged, because it falls
  * out of the same algorithm — `Some(0)` still does not discharge `Some`, since the values a literal
  * leaves behind have no name.
  *
  * The suite is in the two halves `9b` asks for.
  */
class NestedCoverageTests extends AnyFreeSpec with Matchers with RunSupport with CodegenSupport {

  /** The customer's shape: a two-variant enum inside an `Option`. */
  private val OpOption =
    """enum Op: u8
      |    Halt
      |    Push
      |""".stripMargin

  "what the documents claim" - {
    // THE customer, and the runtime half matters as much as the acceptance: with no `else` the
    // emitted fallthrough is `unreachable`, so a match that was wrongly *accepted* would miscompile
    // rather than merely mis-diagnose.
    "the arms of a nested match cover the type between them" in {
      run(OpOption +
        """read(o: Option[Op]) -> int
          |    o match
          |        Some(Halt) -> 0
          |        Some(Push) -> 1
          |        None -> -1
          |var some: Option[Op] = Some(Push)
          |var halt: Option[Op] = Some(Halt)
          |var gone: Option[Op] = None
          |print(read(some), read(halt), read(gone))""".stripMargin) shouldBe "1 0 -1\n"
    }

    // `09 §8`'s own worked example, which the new algorithm has to keep refusing: a `Some` holding
    // anything but zero slips through, and the complement of one literal has no name to write, so
    // the report names the variant rather than inventing a pattern for the gap.
    "a literal sub-pattern still does not discharge its variant" in {
      val e = err(
        """read(o: Option[int]) -> int
          |    o match
          |        Some(0) -> 0
          |        None -> -1""".stripMargin)

      e should include("not exhaustive")
      e should include("missing Some")
      e should not include "Some("
    }

    // The payoff of computing coverage properly: what is missing is named at the depth it is
    // missing at, so the diagnostic is the to-do list `09 §8` says a closed sum type buys.
    "a gap inside a payload is named at the depth it is missing" in {
      err(OpOption +
        """read(o: Option[Op]) -> int
          |    o match
          |        Some(Halt) -> 0
          |        None -> -1""".stripMargin) should include("missing Some(Push)")
    }

    // The flat case is the same algorithm and reports exactly as it always did — one witness per
    // uncovered variant, in declaration order.
    "a flat enum still names every missing variant" in {
      err(
        """enum Shape
          |    Circle(r: int)
          |    Rect(w: int, h: int)
          |    Dot
          |area(s: Shape) -> int
          |    s match
          |        Dot -> 0""".stripMargin) should include("missing Circle, Rect")
    }

    // `09 §7` — a guard is not proof, so a guarded arm discharges nothing. Nesting does not change
    // that, and the check is worth pinning here because the arms would otherwise cover.
    "a guarded arm discharges nothing, nested or not" in {
      err(OpOption +
        """read(o: Option[Op], n: int) -> int
          |    o match
          |        Some(Halt) if n > 0 -> 0
          |        Some(Push) -> 1
          |        None -> -1""".stripMargin) should include("missing Some(Halt)")
    }

    // Alternatives within one arm cover together, exactly as separate arms do — either may match,
    // so each is its own row.
    "alternatives in one arm cover together" in {
      run(OpOption +
        """seen(o: Option[Op]) -> bool
          |    o match
          |        Some(Halt) | Some(Push) -> true
          |        None -> false
          |var some: Option[Op] = Some(Push)
          |var gone: Option[Op] = None
          |print(seen(some), seen(gone))""".stripMargin) shouldBe "true false\n"
    }

    // `09 §6` says the two forms compose, and coverage now follows them: a struct has one shape, so
    // destructuring it inside a variant covers that variant.
    "a struct nested in a variant covers it" in {
      run(
        """struct Point
          |    x: int
          |    y: int
          |end Point
          |sum(o: Option[Point]) -> int
          |    o match
          |        Some(Point(x, y)) -> x + y
          |        None -> 0
          |var here: Option[Point] = Some(Point(3, 4))
          |var gone: Option[Point] = None
          |print(sum(here), sum(gone))""".stripMargin) shouldBe "7 0\n"
    }

    // `bool` is closed wherever it appears, not only as a scrutinee, so its two values cover a
    // payload the same way a two-variant enum does.
    "a bool payload is covered by its two values" in {
      run(
        """word(o: Option[bool]) -> string
          |    o match
          |        Some(true) -> "yes"
          |        Some(false) -> "no"
          |        None -> "?"
          |var t: Option[bool] = Some(true)
          |var f: Option[bool] = Some(false)
          |var n: Option[bool] = None
          |print(word(t), word(f), word(n))""".stripMargin) shouldBe "yes no ?\n"
    }

    // The other half of `09 §8` is untouched: a type with no set of values to complete is covered
    // only by a catch-all, and the complaint about one says just that.
    "a scalar match still needs an else" in {
      val e = err("""var x = 1 match
                    |    1 -> 10
                    |    2 -> 20""".stripMargin)

      e should include("must be exhaustive")
      e should not include "missing"
    }

    // `09 §8` again — an enum match is checked in both positions, because falling off the end has
    // no defined result even for effect. So the nested case is accepted for effect too.
    "an enum match for effect is covered by nested arms as well" in {
      run(OpOption +
        """var n = 0
          |var o: Option[Op] = Some(Push)
          |o match
          |    Some(Halt) -> n = 1
          |    Some(Push) -> n = 2
          |    None -> n = 3
          |print(n)""".stripMargin) shouldBe "2\n"
    }
  }

  "what the edges do" - {
    // Two levels down. The split is recursive, so depth is not a case the algorithm knows about —
    // which is exactly the claim worth testing rather than assuming.
    "coverage reaches through two levels of nesting" in {
      run(OpOption +
        """read(o: Option[Option[Op]]) -> int
          |    o match
          |        Some(Some(Halt)) -> 0
          |        Some(Some(Push)) -> 1
          |        Some(None) -> 2
          |        None -> 3
          |var deep: Option[Option[Op]] = Some(Some(Push))
          |var mid: Option[Option[Op]] = Some(None)
          |var out: Option[Option[Op]] = None
          |print(read(deep), read(mid), read(out))""".stripMargin) shouldBe "1 2 3\n"
    }

    // …and a gap two levels down is named two levels down.
    "a gap two levels down is named in full" in {
      err(OpOption +
        """read(o: Option[Option[Op]]) -> int
          |    o match
          |        Some(Some(Halt)) -> 0
          |        Some(None) -> 2
          |        None -> 3""".stripMargin) should include("missing Some(Some(Push))")
    }

    // A wildcard inside a variant covers the whole payload, so a partially-listed variant plus a
    // wildcard arm for the rest is exhaustive. This is the case a per-arm check got right and the
    // one an over-eager split could get wrong.
    "a wildcard sub-pattern covers the rest of a payload" in {
      run(
        """read(o: Option[int]) -> int
          |    o match
          |        Some(0) -> 100
          |        Some(_) -> 1
          |        None -> -1
          |var z: Option[int] = Some(0)
          |var n: Option[int] = Some(7)
          |var g: Option[int] = None
          |print(read(z), read(n), read(g))""".stripMargin) shouldBe "100 1 -1\n"
    }

    // A binding covers as much as a wildcard does — it is the same pattern with a name on it.
    "a bound sub-pattern covers as a wildcard does" in {
      run(
        """read(o: Option[int]) -> int
          |    o match
          |        Some(n) -> n
          |        None -> -1
          |var s: Option[int] = Some(7)
          |print(read(s))""".stripMargin) shouldBe "7\n"
    }

    // A struct of enums is where the split multiplies: two fields of two variants is four values,
    // and covering three of them leaves exactly one gap, named in full.
    "a struct of enums leaves a gap named in full" in {
      err(OpOption +
        """struct Pair
          |    a: Op
          |    b: Op
          |end Pair
          |read(p: Pair) -> int
          |    p match
          |        Pair(Halt, Halt) -> 0
          |        Pair(Halt, Push) -> 1
          |        Pair(Push, Halt) -> 2""".stripMargin) should include("Pair(Push, Push)")
    }

    // …and covering all four needs no `else`, which is the same statement from the other side.
    "a struct of enums is covered by its combinations" in {
      run(OpOption +
        """struct Pair
          |    a: Op
          |    b: Op
          |end Pair
          |read(p: Pair) -> int
          |    p match
          |        Pair(Halt, Halt) -> 0
          |        Pair(Halt, Push) -> 1
          |        Pair(Push, Halt) -> 2
          |        Pair(Push, Push) -> 3
          |print(read(Pair(Halt, Push)), read(Pair(Push, Push)))""".stripMargin) shouldBe "1 3\n"
    }

    // A named struct pattern leaves the fields it does not list unconstrained, so it covers them —
    // the two source forms end as one pattern and coverage cannot tell them apart.
    "a named struct pattern covers the fields it omits" in {
      run(OpOption +
        """struct Pair
          |    a: Op
          |    b: Op
          |end Pair
          |read(p: Pair) -> int
          |    p match
          |        Pair{a: Halt} -> 0
          |        Pair{a: Push} -> 1
          |print(read(Pair(Halt, Push)), read(Pair(Push, Halt)))""".stripMargin) shouldBe "0 1\n"
    }

    // A gap wide enough to be a list rather than a to-do says so, rather than presenting a prefix
    // as the whole of what is left. Ten variants, one handled, eight named and the cut reported.
    "a report long enough to be cut says that it was" in {
      val e = err(
        """enum Wide
          |    A
          |    B
          |    C
          |    D
          |    E
          |    F
          |    G
          |    H
          |    I
          |    J
          |read(w: Wide) -> int
          |    w match
          |        A -> 0""".stripMargin)

      e should include("missing B, C, D, E, F, G, H, I, and more")
      e should not include "J"
    }

    // A recursive type reaches itself only through a memory mode, and a pattern does not reach
    // through one — so the split ends there rather than descending forever. The witness for the
    // uncovered case is the variant, since its payload is not something to enumerate.
    "a recursive enum terminates the split" in {
      val src =
        """enum List
          |    Empty
          |    Cons(head: int, tail: &List)
          |len(l: &List) -> int
          |    *l match
          |        Empty -> 0
          |        Cons(_, t) -> 1 + len(t)
          |var one: &List = Cons(1, Empty)
          |var two: &List = Cons(2, one)
          |print(len(two))""".stripMargin

      run(src) shouldBe "2\n"
    }

    // …and the same type with a variant left out reports it without descending into the reference.
    "a recursive enum with a gap reports it" in {
      err(
        """enum List
          |    Empty
          |    Cons(head: int, tail: &List)
          |len(l: &List) -> int
          |    *l match
          |        Empty -> 0""".stripMargin) should include("missing Cons")
    }

    // A catch-all still ends the question wherever it appears, and an `else` after full coverage is
    // not an error — nothing checks for an arm that cannot be reached.
    "an else beside full coverage is still accepted" in {
      run(OpOption +
        """read(o: Option[Op]) -> int
          |    o match
          |        Some(Halt) -> 0
          |        Some(Push) -> 1
          |        None -> -1
          |        else -2
          |var s: Option[Op] = Some(Halt)
          |print(read(s))""".stripMargin) shouldBe "0\n"
    }

    // A single-variant enum is covered by that variant alone, which is the degenerate case of the
    // split — one constructor, and completing it completes the type.
    "a one-variant enum is covered by its only variant" in {
      run(
        """enum Only
          |    One(n: int)
          |read(o: Only) -> int
          |    o match
          |        One(n) -> n
          |print(read(One(5)))""".stripMargin) shouldBe "5\n"
    }

    // A struct scrutinee is closed too, so destructuring it is a catch-all — the behaviour a
    // per-arm check already had, kept because it now comes from a different place.
    "a struct scrutinee is covered by destructuring it" in {
      run(
        """struct Point
          |    x: int
          |    y: int
          |end Point
          |sum(p: Point) -> int
          |    p match
          |        Point(x, y) -> x + y
          |print(sum(Point(3, 4)))""".stripMargin) shouldBe "7\n"
    }

    // …and a refutable one is not, with the generic complaint rather than a witness: the gap is a
    // number's complement, which has no pattern to name it.
    "a refutable struct scrutinee still needs an else" in {
      val e = err(
        """struct Point
          |    x: int
          |    y: int
          |end Point
          |var r = Point(1, 2) match
          |    Point(0, 0) -> 1""".stripMargin)

      e should include("must be exhaustive")
      e should not include "missing"
    }

    // `bool` covered on both sides needs no `else`, and one side alone still gets its own sentence
    // rather than a witness list — the message a reader of a two-value type wants.
    "a bool covering one value keeps its own diagnostic" in {
      err("var x = true match\n    true -> 1") should include("must cover both 'true' and 'false'")
    }

    // A nested match under an expected type is checked once, at the match — the coverage question
    // does not depend on what the arms yield, which is what lets the same check serve a statement.
    "a nested payload of a simple enum narrower than int is covered" in {
      run(OpOption +
        """read(o: Option[Op]) -> string
          |    Op.try(0u8) match
          |        Some(Halt) -> "halt"
          |        Some(Push) -> "push"
          |        None -> "?"
          |var s: Option[Op] = None
          |print(read(s))""".stripMargin) shouldBe "halt\n"
    }

    // The IR is the proof that acceptance is not merely tolerance: with the arms covering, the
    // fallthrough after the last one is `unreachable`, so nothing falls out of the match.
    "a fully covered match leaves no reachable fallthrough" in {
      ir(OpOption +
        """read(o: Option[Op]) -> int
          |    o match
          |        Some(Halt) -> 0
          |        Some(Push) -> 1
          |        None -> -1
          |var s: Option[Op] = None
          |print(read(s))""".stripMargin) should include("unreachable")
    }

    // `09 §7` — a failed guard falls through to a later overlapping arm. Coverage discounts the
    // guarded arm, and the unguarded one below it both covers *and* catches the fall, so the two
    // rules have to hold at once for this to print what it does.
    "a failed guard falls through to the arm that covered for it" in {
      run(OpOption +
        """read(o: Option[Op], n: int) -> int
          |    o match
          |        Some(Halt) if n > 0 -> 100
          |        Some(Halt) -> 0
          |        Some(Push) -> 1
          |        None -> -1
          |var h: Option[Op] = Some(Halt)
          |print(read(h, 1), read(h, 0))""".stripMargin) shouldBe "100 0\n"
    }

    // `09 §9` — an arm that does not finish constrains nothing about the match's *type*, but it
    // still covers what its pattern covers. So a diverging nested arm discharges its case.
    "a diverging nested arm still covers its case" in {
      run(OpOption +
        """read(o: Option[Op]) -> int
          |    o match
          |        Some(Halt) -> exit(3)
          |        Some(Push) -> 1
          |        None -> -1
          |var p: Option[Op] = Some(Push)
          |print(read(p))""".stripMargin) shouldBe "1\n"
    }

    // `09 §10` — refcounts survive destructuring, and dropping the `else` changes which blocks the
    // releases land in. A list rebuilt and walked in a loop is what a missing release shows up as.
    "a nested match over a counted payload neither leaks nor frees twice" in {
      run(
        """struct Node
          |    value: int
          |    next: Option[&Node]
          |end Node
          |walk(n: Option[&Node], acc: int) -> int
          |    n match
          |        Some(node) -> walk(node.next, acc + node.value)
          |        None -> acc
          |var total = 0
          |for i in 0..<200
          |    var head: Option[&Node] = None
          |    for j in 1..10 do
          |        head = Some(Node(j, head))
          |    total += walk(head, 0)
          |print(total)""".stripMargin) shouldBe "11000\n"
    }

    // `11` — a `Result` is an enum like any other, so its two variants cover it and a nested error
    // enum covers the payload. The customer's shape with the other of the two library enums.
    "a Result is covered through its error's variants" in {
      run(
        """enum Fault
          |    Late
          |    Lost
          |grade(r: Result[int, Fault]) -> int
          |    r match
          |        Ok(n) -> n
          |        Err(Late) -> -1
          |        Err(Lost) -> -2
          |var good: Result[int, Fault] = Ok(7)
          |var late: Result[int, Fault] = Err(Late)
          |var lost: Result[int, Fault] = Err(Lost)
          |print(grade(good), grade(late), grade(lost))""".stripMargin) shouldBe "7 -1 -2\n"
    }

    // Three levels through both kinds of composition — an enum holding a struct holding an enum.
    // The split does not know what it is descending through, which is the claim being checked.
    "coverage descends through an enum, a struct, and an enum again" in {
      run(OpOption +
        """struct Step
          |    op: Op
          |    hot: bool
          |end Step
          |read(o: Option[Step]) -> int
          |    o match
          |        Some(Step(Halt, true)) -> 0
          |        Some(Step(Halt, false)) -> 1
          |        Some(Step(Push, true)) -> 2
          |        Some(Step(Push, false)) -> 3
          |        None -> 4
          |var a: Option[Step] = Some(Step(Push, true))
          |var b: Option[Step] = None
          |print(read(a), read(b))""".stripMargin) shouldBe "2 4\n"
    }

    // A product of three three-variant enums has twenty-six uncovered combinations, and the report
    // is six shapes: the split only descends where a column was actually narrowed, so a column no
    // arm constrained stays a `_` and stands for all of its values at once. Worth pinning, because
    // the obvious implementation enumerates the product and this one does not.
    "a wide product of enums is reported as a minimal set of shapes" in {
      val e = err(
        """enum Dir
          |    N
          |    E
          |    S
          |struct Turn
          |    a: Dir
          |    b: Dir
          |    c: Dir
          |end Turn
          |read(t: Turn) -> int
          |    t match
          |        Turn(N, N, N) -> 0""".stripMargin)

      e should include(
        "missing Turn(N, N, E), Turn(N, N, S), Turn(N, E, _), Turn(N, S, _), Turn(E, _, _), Turn(S, _, _)")
    }

    // `09 §4` — a payload is matched at its *instantiated* type, and inside a generic that type is
    // a parameter, which has no values to list. So a binding covers it and nothing shorter does,
    // whatever the function is later instantiated at.
    "a payload of a type parameter is covered by binding it" in {
      run(
        """first[T](o: Option[T], fallback: T) -> T
          |    o match
          |        Some(v) -> v
          |        None -> fallback
          |var n: Option[int] = Some(7)
          |var s: Option[string] = None
          |print(first(n, 0), first(s, "-"))""".stripMargin) shouldBe "7 -\n"
    }

    // `09 §6`'s own worked example of the two pattern forms composing, which is the direction the
    // positional tests above do not take: a *named* struct pattern inside a variant, listing one
    // field and leaving the rest unconstrained.
    "a named struct pattern inside a variant covers it" in {
      run(
        """struct Point
          |    x: int
          |    y: int
          |end Point
          |side(o: Option[Point]) -> int
          |    o match
          |        Some(Point{x}) -> x
          |        None -> -1
          |var here: Option[Point] = Some(Point(3, 4))
          |print(side(here))""".stripMargin) shouldBe "3\n"
    }

    // A variant may be written with its enum's name in front, which `09 §6` says the type makes
    // redundant rather than wrong. Coverage reads the variant it resolved to, so the two spellings
    // discharge the same case — and mixing them in one match is the discriminating check.
    "a qualified nested variant pattern covers the same case" in {
      run(OpOption +
        """read(o: Option[Op]) -> int
          |    o match
          |        Some(Op.Halt) -> 0
          |        Some(Push) -> 1
          |        None -> -1
          |var h: Option[Op] = Some(Halt)
          |var p: Option[Op] = Some(Push)
          |print(read(h), read(p))""".stripMargin) shouldBe "0 1\n"
    }

    // `13 §7` — a `const` in a pattern compares rather than binds, so nested it behaves as the
    // literal it folds to: it narrows the variant without discharging it, and the report says so
    // the same way `Some(0)` does.
    "a const in a nested pattern narrows without discharging" in {
      val e = err(
        """const Z: int = 0
          |read(o: Option[int]) -> int
          |    o match
          |        Some(Z) -> 0
          |        None -> -1""".stripMargin)

      e should include("missing Some")
      e should not include "Some("
    }

    // A variant whose payload is zero-sized has nothing to enumerate, so the binding that reads
    // nothing still covers it — the coverage question does not care whether a payload has storage.
    "a zero-sized payload is covered by binding it" in {
      run(
        """enum Done
          |    Fine(u: unit)
          |    Late
          |read(d: Done) -> int
          |    d match
          |        Fine(u) -> 1
          |        Late -> 2
          |print(read(Fine(())), read(Late))""".stripMargin) shouldBe "1 2\n"
    }
  }
}
