package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** Tier-2 runtime behavior of `extern` and `never`: a call into the C library that really links
 * and runs, a diverging call that really stops the program, and the two forcing combinators the
 * prelude now writes on top of them.
 *
 * The externs used here are the ones every hosted target has — `abs` for a result and `exit` for a
 * departure — so the suite needs nothing beyond the libc the toolchain already links.
 */
class ExternRunTests extends AnyFreeSpec with RunSupport {

  "calling into the C library" - {
    "an extern's result is an ordinary value" in {
      val src =
        """extern abs(n: int) -> int
          |print(abs(-7), abs(7), abs(0))""".stripMargin

      run(src) shouldBe "7 7 0\n"
    }

    "an extern composes like any other function" in {
      val src =
        """extern abs(n: int) -> int
          |distance(a: int, b: int) -> int = abs(a - b)
          |var total = 0
          |for i in 1..4
          |    total += distance(i, 10)
          |print(total)""".stripMargin

      // 1..4 is inclusive: 9 + 8 + 7 + 6.
      run(src) shouldBe "30\n"
    }

    "an extern reached only through a generic function is still declared" in {
      val src =
        """extern abs(n: int) -> int
          |magnitude[T](x: T, f: int) -> int = abs(f)
          |print(magnitude("ignored", -3), magnitude(1.5, -4))""".stripMargin

      run(src) shouldBe "3 4\n"
    }
  }

  "a call that does not return" - {
    "stops the program where it stands" in {
      val src =
        """print("before")
          |exit(2)
          |print("after")""".stripMargin

      panics(src, "before")
    }

    "carries its status out" in {
      exits("print(\"gone\")\nexit(3)")
    }

    // `exit(0)` is a departure like any other, so the program stops — just successfully. It is the
    // one case a nonzero-status assertion would get wrong, which is why it is checked directly.
    "a zero status is still a departure" in {
      run("print(\"done\")\nexit(0)") shouldBe "done\n"
    }

    "a user function may declare that it does not return" in {
      val src =
        """fail(code: int) -> never
          |    print("stopping")
          |    exit(code)
          |print("first")
          |fail(4)
          |print("never")""".stripMargin

      panics(src, "first\nstopping\n")
    }
  }

  "a diverging branch" - {
    "an if yields the other branch's type" in {
      val src =
        """positive(n: int) -> int = if n > 0 then n else exit(1)
          |print(positive(5))""".stripMargin

      run(src) shouldBe "5\n"
    }

    "a match arm that diverges leaves the others' type alone" in {
      val src =
        """enum Shape
          |    Circle(r: int)
          |    Bad
          |area(s: Shape) -> int = match s
          |    Circle(r) -> 3 * r * r
          |    Bad -> exit(1)
          |print(area(Circle(2)))""".stripMargin

      run(src) shouldBe "12\n"
    }

    "the diverging arm really does stop the program" in {
      val src =
        """enum Shape
          |    Circle(r: int)
          |    Bad
          |area(s: Shape) -> int = match s
          |    Circle(r) -> 3 * r * r
          |    Bad ->
          |        print("bad shape")
          |        exit(1)
          |print(area(Circle(2)))
          |print(area(Bad))
          |print("unreachable")""".stripMargin

      panics(src, "12\nbad shape\n")
    }

    // The value branch owns a reference and the other one leaves; the retain on the arriving path
    // must still happen, and the released count must still balance over a long loop.
    "a reference from the arriving branch is still owned" in {
      val src =
        """struct Inner
          |    v: int
          |grab(seed: int) -> &Inner
          |    if seed >= 0 then Inner(seed) else exit(1)
          |var i = 0
          |var total = 0
          |while i < 500000
          |    var g = grab(i % 4)
          |    total += g.v
          |    i++
          |print(total)""".stripMargin

      run(src) shouldBe "750000\n"
    }

    "a diverging arm beside one that yields a reference neither leaks nor frees twice" in {
      val src =
        """struct Inner
          |    v: int
          |enum Src
          |    Have(v: int)
          |    Gone
          |grab(s: Src) -> &Inner = match s
          |    Have(v) -> Inner(v)
          |    Gone -> exit(1)
          |var i = 0
          |var total = 0
          |while i < 500000
          |    var g = grab(Have(i % 4))
          |    total += g.v
          |    i++
          |print(total)""".stripMargin

      run(src) shouldBe "750000\n"
    }
  }

  "where a divergence may stand" - {
    // The guard clause is what a bottom type is *for*: a bare `if` with no else, leaving the rest
    // of the function to assume the check passed.
    "an if with no else is a guard clause" in {
      val src =
        """check(n: int) -> int
          |    if n < 0 then exit(1)
          |    n * 2
          |print(check(21))""".stripMargin

      run(src) shouldBe "42\n"
    }

    "and the guard clause really guards" in {
      val src =
        """check(n: int) -> int
          |    if n < 0 then
          |        print("negative")
          |        exit(1)
          |    n * 2
          |print(check(21))
          |print(check(-1))""".stripMargin

      panics(src, "42\nnegative\n")
    }

    "an elif chain may diverge in the middle" in {
      val src =
        """grade(n: int) -> int
          |    if n > 100 then exit(1)
          |    elif n > 50 then 1
          |    else 0
          |print(grade(60), grade(10))""".stripMargin

      run(src) shouldBe "1 0\n"
    }

    "a statement match may have a diverging arm" in {
      val src =
        """enum Shape
          |    Circle(r: int)
          |    Bad
          |show(s: Shape)
          |    match s
          |        Circle(r) -> print("circle", r)
          |        Bad -> exit(1)
          |show(Circle(3))
          |print("after")""".stripMargin

      run(src) shouldBe "circle 3\nafter\n"
    }

    "a guarded arm may diverge" in {
      val src =
        """enum Shape
          |    Circle(r: int)
          |    Bad
          |area(s: Shape) -> int = match s
          |    Circle(r) if r < 0 -> exit(1)
          |    Circle(r) -> 3 * r * r
          |    Bad -> exit(2)
          |print(area(Circle(2)))""".stripMargin

      run(src) shouldBe "12\n"
    }

    "every arm may diverge, and then the match itself does not return" in {
      val src =
        """stop(c: bool) -> never = match c
          |    true ->
          |        print("true side")
          |        exit(1)
          |    false -> exit(2)
          |print("before")
          |stop(true)""".stripMargin

      panics(src, "before\ntrue side\n")
    }

    "a loop's break value meets a diverging else" in {
      val src =
        """first_even(hi: int) -> int
          |    var i = 1
          |    while i <= hi
          |        if i % 2 == 0 then break i
          |        i++
          |    else exit(1)
          |print(first_even(5))""".stripMargin

      run(src) shouldBe "2\n"
    }

    "an argument may diverge, and the call never happens" in {
      val src =
        """f(n: int) -> int = n * 2
          |print("before")
          |f(exit(1))
          |print("after")""".stripMargin

      panics(src, "before")
    }

    "a returned value may diverge" in {
      val src =
        """f(n: int) -> int
          |    if n > 0 then return n
          |    return exit(1)
          |print(f(4))""".stripMargin

      run(src) shouldBe "4\n"
    }

    "a member may declare that it does not return" in {
      val src =
        """struct Fail
          |    code: int
          |    raise(self) -> never
          |        print("failing with", self.code)
          |        exit(self.code)
          |print("before")
          |Fail(3).raise()""".stripMargin

      panics(src, "before\nfailing with 3\n")
    }

    "a trait method may declare that it does not return" in {
      val src =
        """trait Stop
          |    halt(self) -> never
          |enum Reason
          |    Broken
          |impl Stop for Reason
          |    halt(self) -> never
          |        print("halting")
          |        exit(1)
          |print("before")
          |Broken.halt()""".stripMargin

      panics(src, "before\nhalting\n")
    }
  }

  // A jump is not an expression, but the block around one is — and that block does not arrive at
  // the bottom, so it has the same type a diverging call does. This is what makes the branch that
  // leaves the function usable in a value position.
  "a block that ends in a jump" - {
    "a branch may return instead of yielding" in {
      val src =
        """halve(n: int) -> int
          |    var h = if n % 2 == 0 then n / 2 else return -1
          |    h * 10
          |print(halve(8), halve(7))""".stripMargin

      run(src) shouldBe "40 -1\n"
    }

    "an arm may return instead of yielding" in {
      val src =
        """first(o: Option[int]) -> int = match o
          |    Some(v) -> v
          |    None -> return -1
          |print(first(Some(4)), first(None))""".stripMargin

      run(src) shouldBe "4 -1\n"
    }

    "a branch may continue to the next iteration" in {
      val src =
        """sum_even(hi: int) -> int
          |    var total = 0
          |    var i = 1
          |    while i <= hi
          |        i++
          |        var v = if (i - 1) % 2 == 0 then i - 1 else continue
          |        total += v
          |    total
          |print(sum_even(10))""".stripMargin

      run(src) shouldBe "30\n"
    }

    "a branch may break with the loop's value" in {
      val src =
        """find(hi: int) -> int
          |    var i = 1
          |    while i <= hi
          |        var keep = if i * i > 20 then break i else i
          |        i = keep + 1
          |    else -1
          |print(find(10), find(3))""".stripMargin

      run(src) shouldBe "5 -1\n"
    }

    "a loop's else may return" in {
      val src =
        """find(hi: int) -> int
          |    var i = 1
          |    var found = while i <= hi
          |        if i * i > 20 then break i
          |        i++
          |    else return -1
          |    found * 100
          |print(find(10), find(3))""".stripMargin

      run(src) shouldBe "500 -1\n"
    }

    // Every path out of the body leaves through a `return`, so the point after the `if` is reached
    // by nothing — and a merge that nothing reaches must not try to produce the value the function
    // was declared to hand back.
    "a body whose every branch returns needs no fall-through value" in {
      val src =
        """sign(c: bool) -> int
          |    if c then return 1 else return 2
          |print(sign(true), sign(false))""".stripMargin

      run(src) shouldBe "1 2\n"
    }

    "a body whose every arm returns is the same shape" in {
      val src =
        """pick(o: Option[int]) -> int = match o
          |    Some(v) -> return v * 2
          |    None -> return -1
          |print(pick(Some(3)), pick(None))""".stripMargin

      run(src) shouldBe "6 -1\n"
    }

    "a nested all-returning if is still a value in the outer one" in {
      val src =
        """classify(n: int) -> int
          |    var kind = if n > 0 then
          |        if n > 10 then return 99 else return 1
          |    else 0
          |    kind + 5
          |print(classify(-1), classify(5), classify(50))""".stripMargin

      run(src) shouldBe "5 1 99\n"
    }

    "a diverging block still owns what it took" in {
      val src =
        """struct Inner
          |    v: int
          |grab(seed: int) -> &Inner
          |    var held: &Inner = Inner(seed)
          |    var out = if held.v >= 0 then held else return Inner(0)
          |    out
          |var i = 0
          |var total = 0
          |while i < 500000
          |    var g = grab(i % 4)
          |    total += g.v
          |    i++
          |print(total)""".stripMargin

      run(src) shouldBe "750000\n"
    }
  }

  "the prelude's unwrap and expect" - {
    "Option hands over the value it holds" in {
      val src =
        """half(n: int) -> Option[int]
          |    if n % 2 == 0 then Some(n / 2) else None
          |print(half(10).unwrap(), half(8).expect("even"))""".stripMargin

      run(src) shouldBe "5 4\n"
    }

    "Result hands over the value it holds" in {
      val src =
        """parse(ok: bool) -> Result[int, string]
          |    if ok then Ok(9) else Err("bad")
          |print(parse(true).unwrap(), parse(true).expect("parsed"))""".stripMargin

      run(src) shouldBe "9 9\n"
    }

    "unwrapping a None says so and stops" in {
      val src =
        """half(n: int) -> Option[int]
          |    if n % 2 == 0 then Some(n / 2) else None
          |print(half(7).unwrap())""".stripMargin

      panics(src, "panic: unwrap of a None value")
    }

    "unwrapping an Err says so and stops" in {
      val src =
        """parse(ok: bool) -> Result[int, string]
          |    if ok then Ok(9) else Err("bad")
          |print(parse(false).unwrap())""".stripMargin

      panics(src, "panic: unwrap of an Err value")
    }

    "expect carries the caller's own message" in {
      val src =
        """half(n: int) -> Option[int]
          |    if n % 2 == 0 then Some(n / 2) else None
          |print(half(7).expect("7 is odd"))""".stripMargin

      panics(src, "panic: 7 is odd")
    }

    "expect on a Result carries it too" in {
      val src =
        """parse(ok: bool) -> Result[int, string]
          |    if ok then Ok(9) else Err("bad")
          |print(parse(false).expect("could not parse"))""".stripMargin

      panics(src, "panic: could not parse")
    }

    "a string payload survives being unwrapped" in {
      val src =
        """pick(n: int) -> Option[string]
          |    if n > 0 then Some("held") else None
          |print(pick(1).unwrap(), pick(2).expect("positive"))""".stripMargin

      run(src) shouldBe "held held\n"
    }

    // unwrap hands out a reference the caller then owns, so over a long loop every Inner must be
    // freed exactly once — a leak grows RSS and a double free crashes.
    "unwrapping a reference neither leaks nor frees twice" in {
      val src =
        """struct Inner
          |    v: int
          |grab(seed: int) -> &Inner
          |    var o: Option[&Inner] = Some(Inner(seed))
          |    o.unwrap()
          |var i = 0
          |var total = 0
          |while i < 500000
          |    var g = grab(i % 4)
          |    total += g.v
          |    i++
          |print(total)""".stripMargin

      run(src) shouldBe "750000\n"
    }

    "expecting a reference out of a Result neither leaks nor frees twice" in {
      val src =
        """struct Inner
          |    v: int
          |grab(seed: int) -> &Inner
          |    var r: Result[&Inner, string] = Ok(Inner(seed))
          |    r.expect("built")
          |var i = 0
          |var total = 0
          |while i < 500000
          |    var g = grab(i % 4)
          |    total += g.v
          |    i++
          |print(total)""".stripMargin

      run(src) shouldBe "750000\n"
    }
  }
}
