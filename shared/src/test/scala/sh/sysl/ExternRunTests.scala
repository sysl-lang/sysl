package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** Tier-2 runtime behavior of `extern` and `never`: a call into the C library that really links
 * and runs, a diverging call that really stops the program, and the two forcing combinators the
 * library writes on top of them.
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

    // A link name has to reach the *linker*, not just the emitted text: this only runs if `magn`
    // really resolved to libc's `abs`.
    "a link name resolves to the C function it names" in {
      val src =
        """extern "abs" magn(n: int) -> int
          |print(magn(-7), magn(7))""".stripMargin

      run(src) shouldBe "7 7\n"
    }

    // Why the library's own externs carry link names: `print` renders through `snprintf`, and a
    // program is still free to declare `snprintf` for itself and have both reach libc's.
    "the library leaves the C names it renders through free" in {
      val src =
        """extern snprintf(buf: *u8, n: usize, fmt: *u8, ...) -> int
          |var buf: [8]u8
          |var fmt: [3]u8 = [37u8, 100u8, 0u8]
          |var k = snprintf(&buf[0], 8usize, &fmt[0], 42)
          |print(k, int(buf[0]), int(buf[1]))""".stripMargin

      // "42" is two bytes, '4' and '2'.
      run(src) shouldBe "2 52 50\n"
    }
  }

  /** A variadic extern really reaching `printf`, which is the point of the feature: this is the
   * one call shape sysl could not make before, and the one every C library reserves for its
   * printing.
   *
   * `printf` wants a NUL-terminated format, which a sysl `string` is not (it carries a length),
   * so these build one as a `[N]u8` and hand over `&fmt[0]` — the honest way to reach a C
   * interface, and a reminder that `extern` is a seam rather than a bridge.
   */
  "a variadic extern" - {
    /** A NUL-terminated `[N]u8` holding `text`, as a declaration the test can prepend. */
    def format(name: String, text: String): String = {
      val bytes = text.getBytes("UTF-8").map(b => s"${b & 0xff}u8") :+ "0u8"

      s"var $name: [${bytes.length}]u8 = [${bytes.mkString(", ")}]"
    }

    "carries what it is given, in order" in {
      val src =
        s"""extern printf(fmt: *u8, ...) -> int
           |${format("fmt", "%d %g %c|")}
           |printf(&fmt[0], 42, 2.5, 'A')
           |print("")""".stripMargin

      run(src) shouldBe "42 2.5 A|\n"
    }

    // LLVM promotes nothing on its own, so an unwidened value would be read out of the wrong
    // number of bytes — these are the cases that would print garbage if the widening were missing.
    "widens a narrow argument the way C would have" in {
      val src =
        s"""extern printf(fmt: *u8, ...) -> int
           |${format("fmt", "%u %d %g|")}
           |var a: u8  = 200
           |var b: i8  = -5
           |var c: f32 = 1.5f32
           |printf(&fmt[0], a, b, c)
           |print("")""".stripMargin

      run(src) shouldBe "200 -5 1.5|\n"
    }

    "passes a 64-bit value at its own width" in {
      val src =
        s"""extern printf(fmt: *u8, ...) -> int
           |${format("fmt", "%lld|")}
           |printf(&fmt[0], 9000000000i64)
           |print("")""".stripMargin

      run(src) shouldBe "9000000000|\n"
    }

    "may be called with no tail at all" in {
      val src =
        s"""extern printf(fmt: *u8, ...) -> int
           |${format("fmt", "plain|")}
           |printf(&fmt[0])
           |print("")""".stripMargin

      run(src) shouldBe "plain|\n"
    }

    "returns its result like any other call" in {
      val src =
        s"""extern printf(fmt: *u8, ...) -> int
           |${format("fmt", "abc")}
           |var n = printf(&fmt[0])
           |print("")
           |print(n)""".stripMargin

      run(src) shouldBe "abc\n3\n"
    }

    // A variadic with several named parameters, and one whose tail is read into memory rather
    // than printed — the other shape a C library offers.
    "several named parameters precede the tail" in {
      val src =
        s"""extern snprintf(buf: *u8, n: usize, fmt: *u8, ...) -> int
           |extern printf(fmt: *u8, ...) -> int
           |${format("fmt", "%d-%d")}
           |${format("out", "%s|")}
           |var buf: [16]u8
           |var n = snprintf(&buf[0], 16usize, &fmt[0], 7, 8)
           |printf(&out[0], &buf[0])
           |print("")
           |print(n)""".stripMargin

      run(src) shouldBe "7-8|\n3\n"
    }

    "a call in a loop neither leaks nor gets confused" in {
      val src =
        s"""extern printf(fmt: *u8, ...) -> int
           |${format("fmt", "%d,")}
           |var total = 0
           |for i in 1..5
           |    total += printf(&fmt[0], i)
           |print("")
           |print(total)""".stripMargin

      // Five calls of two characters each.
      run(src) shouldBe "1,2,3,4,5,\n10\n"
    }

    // What a variadic call leaves behind still reaches the pipe, because the departure is the
    // library's `exit` and `exit` flushes what was written.
    "what it printed survives a departure taken right after it" in {
      val src =
        s"""extern printf(fmt: *u8, ...) -> int
           |${format("fmt", "reached %d\\n")}
           |printf(&fmt[0], 1)
           |exit(3)
           |printf(&fmt[0], 2)""".stripMargin

      panics(src, "reached 1")
    }
  }

  /** Standard input asks nothing of the language: `getchar` is an ordinary `extern` returning an
   * `int`, and the negative value it hands back at end of input is an ordinary comparison. There is
   * no read primitive to add, which is worth pinning because the absence looks like a gap.
   *
   * What these can assert is the *shape* of such a program and what it does on empty input, since
   * the harness hands a compiled program a closed standard input — so every read here is at end of
   * input by construction, and a test that wanted to feed bytes in would need a runner that passes
   * its own stdin down.
   *
   * These are the raw form, and they stay raw on purpose: the library supplies `Reader` and the
   * `Lines` cursor over `read(2)` (`ReadingSurfaceTests`), and the point of keeping the bare `extern`
   * pinned here is that it is still all a program needs. A surface built on top of the language does
   * not change what the language asks for.
   */
  "reading standard input" - {
    "the end-of-input value is what C says it is" in {
      val src =
        """extern "getchar" get_char() -> int
          |print(get_char())""".stripMargin

      run(src) shouldBe "-1\n"
    }

    "a read loop over empty input completes without reading anything" in {
      val src =
        """extern "getchar" get_char() -> int
          |var n = 0
          |var c = get_char()
          |while c >= 0
          |    n += 1
          |    c = get_char()
          |print("bytes:", n)""".stripMargin

      run(src) shouldBe "bytes: 0\n"
    }

    // A byte arriving as an `int` is a `char` by the checked conversion `01` describes, so building
    // text out of input needs no separate path — the same conversion any other integer takes.
    "an input byte becomes text through the conversion every integer takes" in {
      val src =
        """extern "getchar" get_char() -> int
          |read_or(fallback: char) -> char
          |    var c = get_char()
          |    if c < 0 then fallback else char(u32(c))
          |print(read_or('x'))""".stripMargin

      run(src) shouldBe "x\n"
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
          |area(s: Shape) -> int = s match
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
          |area(s: Shape) -> int = s match
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
          |grab(s: Src) -> &Inner = s match
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
          |    s match
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
          |area(s: Shape) -> int = s match
          |    Circle(r) if r < 0 -> exit(1)
          |    Circle(r) -> 3 * r * r
          |    Bad -> exit(2)
          |print(area(Circle(2)))""".stripMargin

      run(src) shouldBe "12\n"
    }

    "every arm may diverge, and then the match itself does not return" in {
      val src =
        """stop(c: bool) -> never = c match
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
        """first(o: Option[int]) -> int = o match
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
        """pick(o: Option[int]) -> int = o match
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

  "the library's unwrap and expect" - {
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
