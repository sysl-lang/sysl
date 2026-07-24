package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** Tier-2 runtime behavior of `match`: literal, `|`-alternative, range, and wildcard
 * patterns; guards; and use both as a value and as a statement.
 */
class MatchRunTests extends AnyFreeSpec with RunSupport {

  "literal, alternative, range, and else arms" in {
    val src =
      """classify(n: int) -> string
        |    match n
        |        0 -> "zero"
        |        1 | 2 | 3 -> "small"
        |        4..10 -> "medium"
        |        else -> "large"
        |print(classify(0), classify(2), classify(7), classify(99))""".stripMargin

    run(src) shouldBe "zero small medium large\n"
  }

  // `bool` is a closed two-value type: covering `true` and `false` is exhaustive with no `else`.
  "a bool matches its two literal values without an else" in {
    val src =
      """word(b: bool) -> string
        |    match b
        |        true -> "yes"
        |        false -> "no"
        |print(word(true), word(false))""".stripMargin

    run(src) shouldBe "yes no\n"
  }

  "a bool match reads a computed condition" in {
    val src =
      """parity(n: int) -> string
        |    match n % 2 == 0
        |        true -> "even"
        |        false -> "odd"
        |print(parity(4), parity(7))""".stripMargin

    run(src) shouldBe "even odd\n"
  }

  "a bool match may cover one value and fall through to else" in {
    val src =
      """flag(b: bool) -> int
        |    match b
        |        true -> 1
        |        else -> 0
        |print(flag(true), flag(false))""".stripMargin

    run(src) shouldBe "1 0\n"
  }

  "an exclusive range pattern excludes its upper bound" in {
    val src =
      """band(n: int) -> string
        |    match n
        |        0..<10 -> "low"
        |        else -> "high"
        |print(band(9), band(10))""".stripMargin

    run(src) shouldBe "low high\n"
  }

  "guards select among wildcard arms" in {
    val src =
      """sign(x: int) -> int
        |    match x
        |        _ if x > 0 -> 1
        |        _ if x < 0 -> -1
        |        else -> 0
        |print(sign(5), sign(-3), sign(0))""".stripMargin

    run(src) shouldBe "1 -1 0\n"
  }

  "match runs as a statement for its effect" in {
    val src =
      """var n = 2
        |match n
        |    1 -> print("one")
        |    2 -> print("two")
        |    else -> print("many")""".stripMargin

    run(src) shouldBe "two\n"
  }

  // A failed guard must fall through to a *later overlapping* arm, and the earlier arm wins when
  // its guard holds — the first-match-plus-guard rule, not just guards among distinct wildcards.
  "a failed guard falls through to a later overlapping arm" in {
    val src =
      """classify(n: int) -> string
        |    match n
        |        1..10 if n > 5 -> "high"
        |        1..10 -> "low"
        |        else -> "other"
        |print(classify(3), classify(7), classify(50))""".stripMargin

    run(src) shouldBe "low high other\n"
  }

  // An inclusive range includes both ends, tested exactly at each boundary and just outside.
  "an inclusive range pattern includes both bounds" in {
    val src =
      """band(n: int) -> string
        |    match n
        |        3..7 -> "in"
        |        else -> "out"
        |print(band(2), band(3), band(7), band(8))""".stripMargin

    run(src) shouldBe "out in in out\n"
  }

  "a guarded arm on a data variant falls through while keeping the binding" in {
    val src =
      """enum Tree
        |    Leaf(v: int)
        |    Node(l: int, r: int)
        |sum(t: Tree) -> int
        |    match t
        |        Leaf(v) if v < 0 -> 0
        |        Leaf(v) -> v
        |        Node(a, b) -> a + b
        |print(sum(Leaf(5)), sum(Leaf(-3)), sum(Node(10, 20)))""".stripMargin

    run(src) shouldBe "5 0 30\n"
  }

  "a nested variant pattern destructures through a layer" in {
    val src =
      """enum Inner
        |    Val(n: int)
        |    Nought
        |enum Outer
        |    Wrap(i: Inner)
        |    Bare
        |peek(o: Outer) -> int
        |    match o
        |        Wrap(Val(n)) -> n
        |        Wrap(Nought) -> -1
        |        else -> -2
        |print(peek(Wrap(Val(42))), peek(Wrap(Nought)), peek(Bare))""".stripMargin

    run(src) shouldBe "42 -1 -2\n"
  }

  // A guard is evaluated only after its arm's pattern matches, never for an arm that was ruled
  // out — check(3) never runs the guard, so "guard" prints only for check(5).
  "a guard is evaluated only when its arm's pattern matches" in {
    val src =
      """noisy() -> bool
        |    print("guard")
        |    true
        |end noisy
        |check(n: int) -> int
        |    match n
        |        5 if noisy() -> 1
        |        else -> 0
        |print(check(3))
        |print(check(5))""".stripMargin

    run(src) shouldBe "0\nguard\n1\n"
  }

  // The scrutinee is evaluated once, not re-evaluated per arm — a side-effecting scrutinee prints
  // exactly once.
  "the scrutinee is evaluated exactly once" in {
    val src =
      """src() -> int
        |    print("eval")
        |    2
        |end src
        |match src()
        |    1 -> print("one")
        |    2 -> print("two")
        |    else -> print("other")""".stripMargin

    run(src) shouldBe "eval\ntwo\n"
  }

  // A match in a `&T` context reaches each arm, so an arm returning the bound `&Point` payload
  // and an arm yielding a fresh value `Point` meet at `&Point` (the value arm is boxed). Boxing
  // the whole match instead would type it `unit` — one arm is already `&Point` and could not
  // become plain `Point`. The extracted payload must outlive the enum it was bound from.
  "a match in a &T context extracts a reference payload alongside a value arm" in {
    val src =
      """struct Point
        |    x: int
        |    y: int
        |enum Box
        |    Full(p: &Point)
        |    Empty
        |extract(b: Box) -> &Point
        |    match b
        |        Full(p) -> p
        |        Empty -> Point(0, 0)
        |var got = extract(Full(Point(11, 22)))
        |var zero = extract(Empty)
        |print(got.x, got.y, zero.x)""".stripMargin

    run(src) shouldBe "11 22 0\n"
  }

  // The `Full(p) -> p` arm hands the bound payload out of the frame, so it must be retained on
  // bind to outlive the enum, then freed exactly once. A long loop catches a double-free (crash)
  // or a wrong count (wrong total); peak RSS was separately confirmed flat, so no leak either.
  "an extracted reference payload is retained on bind and freed exactly once" in {
    val src =
      """struct Point
        |    x: int
        |    y: int
        |enum Box
        |    Full(p: &Point)
        |    Empty
        |extract(b: Box) -> &Point
        |    match b
        |        Full(p) -> p
        |        Empty -> Point(0, 0)
        |var i = 0
        |var total = 0
        |while i < 20000
        |    var got = extract(Full(Point(3, 0)))
        |    total += got.x
        |    i++
        |print(total)""".stripMargin

    run(src) shouldBe "60000\n"
  }

  // The guard-fallthrough path binds a refcounted payload and must release it exactly once when
  // the guard fails. A long loop catches a double-free (a crash) or a wrong count (a wrong total);
  // peak RSS was separately confirmed flat across a 10x loop, so there is no leak either.
  "a refcounted binding under a failed guard is released exactly once" in {
    val src =
      """struct Point
        |    x: int
        |    y: int
        |enum Cell
        |    Full(p: &Point)
        |    Empty
        |score(c: Cell) -> int
        |    match c
        |        Full(p) if p.x > 100 -> 1
        |        Full(p) -> p.x
        |        Empty -> 0
        |var total = 0
        |var i = 0
        |while i < 20000
        |    total += score(Full(Point(i % 200, 0)))
        |    i++
        |print(total)""".stripMargin

    run(src) shouldBe "514900\n"
  }
}
