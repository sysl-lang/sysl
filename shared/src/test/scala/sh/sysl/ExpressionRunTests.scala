package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** Tier-2 runtime behavior of expressions and `print`. */
class ExpressionRunTests extends AnyFreeSpec with RunSupport {

  "hello world prints a string" in {
    val src =
      """print("Hello, sysl!")""".stripMargin

    run(src) shouldBe "Hello, sysl!\n"
  }

  "arithmetic evaluates correctly" in {
    val src =
      """print(6 * 7)""".stripMargin

    run(src) shouldBe "42\n"
  }

  // Asymmetric operands so an operand-order or wrong-instruction bug shows: a - b and b - a
  // differ, a / b is not b / a, and each shift and bitwise op has a distinct result.
  "every binary operator, with order-sensitive operands" in {
    val src =
      """var a = 20
        |var b = 6
        |print(a + b, a - b, b - a, a * b, a / b, a % b, a << 1, a >> 1, a & b, a | b, a ^ b)""".stripMargin

    run(src) shouldBe "26 14 -14 120 3 2 40 10 4 22 18\n"
  }

  // Each comparison must yield both outcomes across the row, so an always-true or always-false
  // miscompile of any one of them changes the printed string.
  "every comparison operator, both outcomes" in {
    val src =
      """var a = 3
        |var b = 7
        |print(a < b, a > b, a <= b, a >= b, a == b, a != b, b >= b, b == b)""".stripMargin

    run(src) shouldBe "true false true false false true true true\n"
  }

  "arguments are space-separated" in {
    val src =
      """print("answer", 42)""".stripMargin

    run(src) shouldBe "answer 42\n"
  }

  "floats print and compute" in {
    val src =
      """print(1.5 + 2.5)""".stripMargin

    run(src) shouldBe "4\n"
  }

  "booleans print as words" in {
    val src =
      """print(1 < 2 && 2 < 3)""".stripMargin

    run(src) shouldBe "true\n"
  }

  // && evaluates the right side only when the left is true, || only when the left is false, so a
  // side-effecting right side runs exactly when it should — never on the decided-left path.
  "&& and || short-circuit their right side" in {
    val src =
      """loud(b: bool) -> bool
        |    print("run")
        |    b
        |end loud
        |print(false && loud(true))
        |print(true || loud(false))
        |print(true && loud(false))
        |print(false || loud(true))""".stripMargin

    run(src) shouldBe "false\ntrue\nrun\nfalse\nrun\ntrue\n"
  }

  // The point of short-circuit: the left side is a guard that keeps the right side from running an
  // unsafe operation. Without it, guarded(null) would dereference a null pointer.
  "a short-circuited guard protects the unsafe right side" in {
    val src =
      """guarded(p: *int) -> bool = p != null && *p > 0
        |var n = 5
        |print(guarded(&n), guarded(null))""".stripMargin

    run(src) shouldBe "true false\n"
  }

  // The short-circuit lowering must compose: && binds tighter than ||, and each nests as the
  // right side of another, so the branch structure has to be right at every level.
  "logical operators compose under precedence and nesting" in {
    val src =
      """print(false || true && false)
        |print(true && false || true)
        |print(true && true && false)""".stripMargin

    run(src) shouldBe "false\ntrue\nfalse\n"
  }

  "a chained comparison a < b < c" in {
    val src =
      """var x = 5
        |print(1 < x < 10, 1 < x < 3, 1 <= x < 10)""".stripMargin

    run(src) shouldBe "true false true\n"
  }

  // Regression: the middle operand of a chain used to be emitted for both the left and the right
  // comparison, so a side-effecting one ran twice. It must be evaluated exactly once.
  "a chained comparison evaluates each operand exactly once" in {
    val src =
      """tick(n: int) -> int
        |    print("tick")
        |    n
        |end tick
        |print(1 < tick(5) < 10)""".stripMargin

    run(src) shouldBe "tick\ntrue\n"
  }

  // `01` specifies that a chain short-circuits, and for a long time it did not: every operand was
  // evaluated up front and the pairs ANDed, so a side-effecting later operand ran even once an
  // earlier comparison had already decided the answer.
  "a chained comparison stops at the first comparison that fails" in {
    val src =
      """tick(n: int) -> int
        |    print("tick")
        |    n
        |end tick
        |print(9 < tick(2) < tick(3))""".stripMargin

    // One tick: `9 < 2` is false, so the third operand is never reached.
    run(src) shouldBe "tick\nfalse\n"
  }

  "and it stops wherever in the chain that happens" in {
    val src =
      """tick(label: int, n: int) -> int
        |    print(label)
        |    n
        |end tick
        |print(1 < tick(1, 2) < tick(2, 3) < tick(3, 0) < tick(4, 9))""".stripMargin

    // Operands 1..3 run; `3 < 0` fails, so the fourth is never built.
    run(src) shouldBe "1\n2\n3\nfalse\n"
  }

  "a chain that holds all the way through evaluates every operand" in {
    val src =
      """tick(label: int, n: int) -> int
        |    print(label)
        |    n
        |end tick
        |print(1 < tick(1, 2) < tick(2, 3) < tick(3, 4) < 100)""".stripMargin

    run(src) shouldBe "1\n2\n3\ntrue\n"
  }

  // The operands a chain skips are the interesting case for ownership: a string operand owns its
  // bytes, so one that is built must be released and one that is never built must not be. Looping
  // it turns a double release into a crash and a missed one into unbounded growth.
  "a chain over owned operands neither leaks nor releases twice" in {
    val src =
      """mk(p: *int, s: string) -> string
        |    *p += 1
        |    s + "!"
        |end mk
        |var built = 0
        |var i = 0
        |while i < 20000
        |    var a = mk(&built, "b")
        |    if mk(&built, "z") < a < mk(&built, "y") then print("unreachable")
        |    i += 1
        |print(built)""".stripMargin

    // Two per iteration, never three: the chain fails at `"z!" < "b!"` and stops.
    run(src) shouldBe "40000\n"
  }

  "a chain over strings compares the whole way when it holds" in {
    val src =
      """var s = "m"
        |print("a" < s < "z", "z" < s < "a", "a" < s <= "m")""".stripMargin

    run(src) shouldBe "true false true\n"
  }

  // Arguments are evaluated left to right, each exactly once — a side-effecting argument prints
  // in call order and the packed result confirms each value landed in its own slot.
  "call arguments are evaluated left to right, once each" in {
    val src =
      """tick(label: int) -> int
        |    print(label)
        |    label
        |end tick
        |take(a: int, b: int, c: int) -> int = a * 100 + b * 10 + c
        |print(take(tick(1), tick(2), tick(3)))""".stripMargin

    run(src) shouldBe "1\n2\n3\n123\n"
  }

  // The operands of a binary operator are evaluated left first, each once — the left side prints
  // before the right, and the asymmetric subtraction pins which value went where.
  "binary operands are evaluated left first, once each" in {
    val src =
      """tick(label: int, v: int) -> int
        |    print(label)
        |    v
        |end tick
        |print(tick(1, 20) - tick(2, 5))""".stripMargin

    run(src) shouldBe "1\n2\n15\n"
  }

  // A compound assignment to an indexed element must evaluate the place once, not once for the
  // load and again for the store. A side-effecting index prints a single time, and the element
  // holds the read-modify-write result.
  // A shift amount at or past the operand's width used to be undefined: the machine instruction masks
  // it, LLVM calls the result poison, and what came out was not even a stable wrong answer — `11 >> 64`
  // printed `0`, then `2`, then `8503132480` across compilations of one source, and `11 >> 65` printed
  // `4341799456` on one run of a binary and `0` on the next. Raw integer arithmetic is defined to
  // wrap, so a shift is defined too: shifting a value all the way answers what shifting it all the way
  // means.
  "a shift by the width answers zero rather than anything" in {
    run("""main()
          |    var u: usize = 11
          |    print(u >> 64, u << 64, u >> 65, u << 65)""".stripMargin) shouldBe "0 0 0 0\n"
  }

  // The amount is a variable here, so nothing folds and the emitted compare and select are what runs.
  "and it does so when the amount is not a constant" in {
    run("""main()
          |    var u: usize = 11
          |    var w: usize = 64
          |    var past: usize = 1000
          |    print(u >> w, u << w, u >> past, u >> (w - 1))""".stripMargin) shouldBe "0 0 0 0\n"
  }

  // An arithmetic right shift fills from the sign, so a signed value shifted past its width is all
  // sign — which is the answer that keeps `x >> n` meaning "divide by two, n times" in the limit.
  "a signed right shift past the width is the sign, not zero" in {
    run("""main()
          |    var neg: int = -8
          |    var pos: int = 8
          |    var w: int = 64
          |    print(neg >> 64, pos >> 64, neg >> w, pos >> w)""".stripMargin) shouldBe "-1 0 -1 0\n"
  }

  // The width is the operand's own, so a narrow type reaches the case at a much smaller amount — and
  // `u8` is where masking would have been most visible, since the machine masks to five or six bits
  // and would have left every one of these shifting by something.
  "the width that bounds it is the operand's own, not the machine's" in {
    run("""main()
          |    var b: u8 = 0b1011
          |    var s: i8 = -8
          |    print(b >> 8, b << 8, b >> 200, s >> 8)""".stripMargin) shouldBe "0 0 0 -1\n"
  }

  // Nothing about an in-range shift changes, which is the half a bounds check could quietly break.
  "an in-range shift is untouched" in {
    run("""main()
          |    var u: usize = 11
          |    var s: int = -8
          |    var b: u8 = 0b1011
          |    print(u >> 1, u << 1, s >> 1, b << 1)""".stripMargin) shouldBe "5 22 -4 22\n"
  }

  // A vector shifts lane-wise and is bounded lane-wise, so the amount is splat across the register
  // and the select is a lane mask rather than a branch.
  "a vector shift is bounded in every lane" in {
    run("""main()
          |    var counts: <8>u32 = [1, 2, 4, 8, 16, 32, 64, 128]
          |    var big: <8>u32 = [40, 40, 40, 40, 40, 40, 40, 40]
          |    print((counts << 1)[7], (counts << big)[0], (counts >> big)[7], (counts >> 1)[7])""".stripMargin) shouldBe
      "256 0 0 64\n"
  }

  "a compound assignment evaluates a side-effecting index exactly once" in {
    val src =
      """next() -> int
        |    print(99)
        |    2
        |end next
        |var a = [10, 20, 30, 40]
        |a[next()] += 5
        |print(a[2])""".stripMargin

    run(src) shouldBe "99\n35\n"
  }

  // The same once-only rule for a compound assignment through a side-effecting receiver: the
  // receiver expression is evaluated a single time and the field is updated in place.
  "a compound assignment evaluates a side-effecting receiver exactly once" in {
    val src =
      """struct Box
        |    n: int
        |get(b: &Box) -> &Box
        |    print(99)
        |    b
        |end get
        |var box: &Box = Box(100)
        |get(box).n += 7
        |print(box.n)""".stripMargin

    run(src) shouldBe "99\n107\n"
  }

  "compound assignment operators update in place" in {
    val src =
      """var x = 10
        |x += 5
        |x -= 2
        |x *= 3
        |x /= 2
        |print(x)""".stripMargin

    run(src) shouldBe "19\n"
  }

  "bitwise compound assignment" in {
    val src =
      """var b = 12
        |b &= 10
        |b |= 1
        |b <<= 2
        |print(b)""".stripMargin

    run(src) shouldBe "36\n"
  }
}
