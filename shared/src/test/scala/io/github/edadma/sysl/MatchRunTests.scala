package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** Tier-2 runtime behavior of `match`: literal, `|`-alternative, range, and wildcard
 * patterns; guards; and use both as a value and as a statement.
 */
class MatchRunTests extends AnyFreeSpec with RunSupport {

  "literal, alternative, range, and else arms" in {
    val src =
      """classify(n: int) -> string
        |    n match
        |        0 -> "zero"
        |        1 | 2 | 3 -> "small"
        |        4..10 -> "medium"
        |        else "large"
        |print(classify(0), classify(2), classify(7), classify(99))""".stripMargin

    run(src) shouldBe "zero small medium large\n"
  }

  // `bool` is a closed two-value type: covering `true` and `false` is exhaustive with no `else`.
  "a bool matches its two literal values without an else" in {
    val src =
      """word(b: bool) -> string
        |    b match
        |        true -> "yes"
        |        false -> "no"
        |print(word(true), word(false))""".stripMargin

    run(src) shouldBe "yes no\n"
  }

  "a bool match reads a computed condition" in {
    val src =
      """parity(n: int) -> string
        |    n % 2 == 0 match
        |        true -> "even"
        |        false -> "odd"
        |print(parity(4), parity(7))""".stripMargin

    run(src) shouldBe "even odd\n"
  }

  "a bool match may cover one value and fall through to else" in {
    val src =
      """flag(b: bool) -> int
        |    b match
        |        true -> 1
        |        else 0
        |print(flag(true), flag(false))""".stripMargin

    run(src) shouldBe "1 0\n"
  }

  "a positional struct pattern destructures every field in order" in {
    val src =
      """struct Point
        |    x: int
        |    y: int
        |end Point
        |classify(p: Point) -> string
        |    p match
        |        Point(0, 0) -> "origin"
        |        Point(x, 0) -> "x-axis"
        |        Point(0, y) -> "y-axis"
        |        else "elsewhere"
        |print(classify(Point(0, 0)), classify(Point(5, 0)), classify(Point(0, 3)), classify(Point(1, 2)))""".stripMargin

    run(src) shouldBe "origin x-axis y-axis elsewhere\n"
  }

  // A struct has one shape, so a positional pattern with irrefutable fields is a catch-all — a
  // value match on it needs no `else`.
  "an irrefutable struct pattern is exhaustive without an else" in {
    val src =
      """struct Point
        |    x: int
        |    y: int
        |end Point
        |sum(p: Point) -> int
        |    p match
        |        Point(x, y) -> x + y
        |print(sum(Point(3, 4)))""".stripMargin

    run(src) shouldBe "7\n"
  }

  "a named struct pattern matches fields by name and may omit them" in {
    val src =
      """struct Point
        |    x: int
        |    y: int
        |end Point
        |where(p: Point) -> string
        |    p match
        |        Point{x: 0} -> "y-axis"
        |        Point{y: 0} -> "x-axis"
        |        Point{x, y} -> "elsewhere"
        |print(where(Point(0, 9)), where(Point(9, 0)), where(Point(3, 4)))""".stripMargin

    run(src) shouldBe "y-axis x-axis elsewhere\n"
  }

  "a named-field shorthand binds each field to its own name" in {
    val src =
      """struct Point
        |    x: int
        |    y: int
        |end Point
        |swapSum(p: Point) -> int
        |    p match
        |        Point{y, x} -> x * 10 + y
        |print(swapSum(Point(3, 4)))""".stripMargin

    run(src) shouldBe "34\n"
  }

  "a struct pattern nests inside another struct pattern" in {
    val src =
      """struct Point
        |    x: int
        |    y: int
        |end Point
        |struct Line
        |    a: Point
        |    b: Point
        |end Line
        |describe(l: Line) -> string
        |    l match
        |        Line(Point(0, 0), b) -> "from origin"
        |        else "other"
        |print(describe(Line(Point(0, 0), Point(3, 4))), describe(Line(Point(1, 1), Point(2, 2))))""".stripMargin

    run(src) shouldBe "from origin other\n"
  }

  // A bound refcounted field is retained on bind and released once, and the struct scrutinee is
  // torn down each iteration — a leak or double-free would drift or crash over the loop.
  "binding a refcounted struct field neither leaks nor frees twice" in {
    val src =
      """struct Named
        |    tag: string
        |    n: int
        |end Named
        |var total = 0
        |for i in 1..5000
        |    var nm = Named("ab" + "cd", i)
        |    var got = nm match
        |        Named{tag: t, n} -> int(t[0]) + n
        |    total += got
        |print(total)""".stripMargin

    // per i: 'a' (97) + i; sum over 1..5000 = 5000*97 + 5000*5001/2 = 485000 + 12502500
    run(src) shouldBe "12987500\n"
  }

  "an exclusive range pattern excludes its upper bound" in {
    val src =
      """band(n: int) -> string
        |    n match
        |        0..<10 -> "low"
        |        else "high"
        |print(band(9), band(10))""".stripMargin

    run(src) shouldBe "low high\n"
  }

  "guards select among wildcard arms" in {
    val src =
      """sign(x: int) -> int
        |    x match
        |        _ if x > 0 -> 1
        |        _ if x < 0 -> -1
        |        else 0
        |print(sign(5), sign(-3), sign(0))""".stripMargin

    run(src) shouldBe "1 -1 0\n"
  }

  "match runs as a statement for its effect" in {
    val src =
      """var n = 2
        |n match
        |    1 -> print("one")
        |    2 -> print("two")
        |    else print("many")""".stripMargin

    run(src) shouldBe "two\n"
  }

  // A failed guard must fall through to a *later overlapping* arm, and the earlier arm wins when
  // its guard holds — the first-match-plus-guard rule, not just guards among distinct wildcards.
  "a failed guard falls through to a later overlapping arm" in {
    val src =
      """classify(n: int) -> string
        |    n match
        |        1..10 if n > 5 -> "high"
        |        1..10 -> "low"
        |        else "other"
        |print(classify(3), classify(7), classify(50))""".stripMargin

    run(src) shouldBe "low high other\n"
  }

  // An inclusive range includes both ends, tested exactly at each boundary and just outside.
  "an inclusive range pattern includes both bounds" in {
    val src =
      """band(n: int) -> string
        |    n match
        |        3..7 -> "in"
        |        else "out"
        |print(band(2), band(3), band(7), band(8))""".stripMargin

    run(src) shouldBe "out in in out\n"
  }

  "a guarded arm on a data variant falls through while keeping the binding" in {
    val src =
      """enum Tree
        |    Leaf(v: int)
        |    Node(l: int, r: int)
        |sum(t: Tree) -> int
        |    t match
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
        |    o match
        |        Wrap(Val(n)) -> n
        |        Wrap(Nought) -> -1
        |        else -2
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
        |    n match
        |        5 if noisy() -> 1
        |        else 0
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
        |src() match
        |    1 -> print("one")
        |    2 -> print("two")
        |    else print("other")""".stripMargin

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
        |    b match
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
        |    b match
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
        |    c match
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

  /** A simple enum **is** its discriminant, so a `: iN` annotation is its storage — and a variant
   * test compares against that width rather than against `i32`. Getting this wrong emitted IR that
   * compared an `i8` value at `i32`, which no program with a narrow enum could get past the
   * assembler; every case here is one that failed to build before.
   */
  "a simple enum narrower than i32" - {
    "matches its variants at its own width" in {
      val src =
        """enum Op: u8
          |    Halt
          |    Push
          |    Jump
          |name(o: Op) -> string
          |    o match
          |        Halt -> "halt"
          |        Push -> "push"
          |        Jump -> "jump"
          |print(name(Halt), name(Push), name(Jump))""".stripMargin

      run(src) shouldBe "halt push jump\n"
    }

    // A discriminant above 127 is a legal `u8` and no legal `i8`, so the constant the comparison
    // is emitted with has to be read as the enum's own type rather than as a signed byte.
    "compares a discriminant that fills the high half of a byte" in {
      val src =
        """enum Code: u8
          |    Fine = 3
          |    Gone = 200
          |    Late = 255
          |word(c: Code) -> string
          |    c match
          |        Fine -> "fine"
          |        Gone -> "gone"
          |        Late -> "late"
          |print(word(Fine), word(Gone), word(Late))""".stripMargin

      run(src) shouldBe "fine gone late\n"
    }

    "matches a negative discriminant of a signed underlying type" in {
      val src =
        """enum Trend: i8
          |    Down = -1
          |    Flat = 0
          |    Up = 1
          |step(t: Trend) -> int
          |    t match
          |        Down -> -10
          |        Flat -> 0
          |        Up -> 10
          |print(step(Down), step(Flat), step(Up))""".stripMargin

      run(src) shouldBe "-10 0 10\n"
    }

    "matches at a width between the byte and the word" in {
      val src =
        """enum Port: u16
          |    Shut = 0
          |    Http = 80
          |    High = 40000
          |busy(p: Port) -> bool
          |    p match
          |        Shut -> false
          |        Http -> true
          |        High -> true
          |print(busy(Shut), busy(Http), busy(High))""".stripMargin

      run(src) shouldBe "false true true\n"
    }

    // Nested inside a data enum's payload the value arrives from an `extractvalue` rather than
    // straight off a local, which is the other way a variant test is reached. Coverage is not
    // computed through a nested pattern, so the arms need an `else` to be exhaustive.
    "matches inside another enum's payload" in {
      val src =
        """enum Op: u8
          |    Halt
          |    Push
          |read(o: Option[Op]) -> int
          |    o match
          |        Some(Halt) -> 0
          |        Some(Push) -> 1
          |        else -1
          |var some: Option[Op] = Some(Push)
          |var gone: Option[Op] = None
          |print(read(some), read(gone))""".stripMargin

      run(src) shouldBe "1 -1\n"
    }

    // The conversions were already right — they widen before testing — so this pins the two halves
    // together: what `try` accepts is what a `match` on the result then discriminates.
    "discriminates a value the fallible constructor handed back" in {
      val src =
        """enum Op: u8
          |    Halt
          |    Push = 9
          |decode(b: u8) -> string
          |    Op.try(b) match
          |        Some(Halt) -> "halt"
          |        Some(Push) -> "push"
          |        else "?"
          |print(decode(0u8), decode(9u8), decode(4u8))""".stripMargin

      run(src) shouldBe "halt push ?\n"
    }
  }
}
