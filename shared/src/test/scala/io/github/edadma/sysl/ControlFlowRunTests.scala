package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** Tier-2 runtime behavior of control flow: loops, branches, elif chains, inline forms. */
class ControlFlowRunTests extends AnyFreeSpec with RunSupport {

  "a while loop sums 1..10" in {
    val src =
      """var sum = 0
        |var i = 1
        |while i <= 10
        |    sum = sum + i
        |    i++
        |print(sum)""".stripMargin

    run(src) shouldBe "55\n"
  }

  // An empty range runs the body zero times, both when the exclusive bound coincides with the
  // start and when an inclusive low bound is already past its high — the off-by-one corner.
  "an empty for range runs its body zero times" in {
    val src =
      """var s = 0
        |for i in 5..<5 do s += 1
        |for i in 5..4 do s += 100
        |print(s)""".stripMargin

    run(src) shouldBe "0\n"
  }

  // A single-iteration range still enters the body exactly once (inclusive a..a).
  "an inclusive range of one element runs the body once" in {
    val src =
      """var s = 0
        |for i in 7..7 do s += i
        |print(s)""".stripMargin

    run(src) shouldBe "7\n"
  }

  // Called with both an even and an odd input, so an "always take one branch" miscompile can't
  // pass by coincidence the way a single input would.
  "if/else picks the right branch" in {
    val src =
      """parity(n: int) -> string
        |    if n % 2 == 0
        |        "even"
        |    else
        |        "odd"
        |end parity
        |print(parity(4), parity(7))""".stripMargin

    run(src) shouldBe "even odd\n"
  }

  "an elif chain picks the matching branch" in {
    val src =
      """var n = 2
        |if n == 1 then print("one")
        |elif n == 2 then print("two")
        |elif n == 3 then print("three")
        |else print("many")""".stripMargin

    run(src) shouldBe "two\n"
  }

  "an elif chain falling through to else" in {
    val src =
      """var n = 9
        |if n == 1 then print("one")
        |elif n == 2 then print("two")
        |else print("many")""".stripMargin

    run(src) shouldBe "many\n"
  }

  "an inline while body with `do`" in {
    val src =
      """var i = 0
        |while i < 3 do i++
        |print(i)""".stripMargin

    run(src) shouldBe "3\n"
  }

  "an inline if/then/else" in {
    val src =
      """if 1 < 2 then print("yes") else print("no")""".stripMargin

    run(src) shouldBe "yes\n"
  }

  "an inline then with else on the next line" in {
    val src =
      """if 1 < 2 then print("t")
        |else print("f")""".stripMargin

    run(src) shouldBe "t\n"
  }

  "a program using end markers runs correctly" in {
    val src =
      """var i = 0
        |var sum = 0
        |while i < 5
        |    sum = sum + i
        |    i++
        |end while
        |if sum == 10 then
        |    print("ten")
        |else
        |    print("other")
        |end if""".stripMargin

    run(src) shouldBe "ten\n"
  }

  "a factorial loop" in {
    val src =
      """var n = 5
        |var fact = 1
        |var k = 2
        |while k <= n
        |    fact = fact * k
        |    k++
        |print("5! =", fact)""".stripMargin

    run(src) shouldBe "5! = 120\n"
  }

  "an inclusive for range sums its bounds" in {
    val src =
      """var total = 0
        |for i in 1..5
        |    total = total + i
        |print(total)""".stripMargin

    run(src) shouldBe "15\n"
  }

  "an exclusive for range stops before its upper bound" in {
    val src =
      """var total = 0
        |for i in 0..<5 do total = total + i
        |print(total)""".stripMargin

    run(src) shouldBe "10\n"
  }

  "nested for loops accumulate" in {
    val src =
      """var grid = 0
        |for i in 0..<3
        |    for j in 0..<3
        |        grid = grid + i * j
        |print(grid)""".stripMargin

    run(src) shouldBe "9\n"
  }

  "if used as a value binds the taken branch" in {
    val src =
      """var n = 7
        |var label = if n % 2 == 0 then "even" else "odd"
        |print(label)""".stripMargin

    run(src) shouldBe "odd\n"
  }

  // A `&T` context reaches each branch, so a value branch is boxed to `&T` and meets an
  // already-`&T` branch there — the whole `if` is `&Point`. This is the design's documented
  // conditional-boxing example; boxing the aggregate instead would reject the mismatch.
  "an if in a &T context unifies a value branch with a reference branch" in {
    val src =
      """struct Point
        |    x: int
        |    y: int
        |origin() -> &Point = Point(9, 9)
        |pick(c: bool) -> int
        |    var q: &Point = origin()
        |    var p: &Point = if c then Point(1, 2) else q
        |    p.x
        |print(pick(true), pick(false))""".stripMargin

    run(src) shouldBe "1 9\n"
  }

  "a for loop variable shadows an outer variable" in {
    val src =
      """var i = 100
        |var sum = 0
        |for i in 1..3
        |    sum = sum + i
        |print(i, sum)""".stripMargin

    run(src) shouldBe "100 6\n"
  }

  "loops as expressions" - {
    // `break x` makes the loop yield x; the search hits the target, so the loop is 30 and the
    // `else` never runs. The both-paths pair below runs the other way.
    "a for that breaks with a value yields it, skipping the else" in {
      val src =
        """var xs = [10, 20, 30, 40]
          |var found = for x in xs
          |    if x == 30 then break x
          |else -1
          |print(found)""".stripMargin

      run(src) shouldBe "30\n"
    }

    // No element matches, so the loop finishes normally and the `else` supplies the value. Same
    // program as above with a target that is absent — the discriminating half of the pair.
    "a for that finds nothing takes the else value" in {
      val src =
        """var xs = [10, 20, 30, 40]
          |var found = for x in xs
          |    if x == 99 then break x
          |else -1
          |print(found)""".stripMargin

      run(src) shouldBe "-1\n"
    }

    // A `while` whose only exit is `break value` — the infinite-loop shape. The `else` is
    // unreachable here (the condition never turns false), so the value can only be the break's.
    "a while true yields its break value" in {
      val src =
        """var n = 0
          |var r = while true
          |    n += 1
          |    if n == 7 then break n * 100
          |else 0
          |print(r)""".stripMargin

      run(src) shouldBe "700\n"
    }

    // `continue` skips the rest of the iteration: the odds are summed, the evens skipped. Sum of
    // 1,3,5,7,9 = 25 discriminates against summing everything (45) or nothing.
    "continue skips the rest of the iteration" in {
      val src =
        """var sum = 0
          |for i in 1..10
          |    if i % 2 == 0 then continue
          |    sum += i
          |print(sum)""".stripMargin

      run(src) shouldBe "25\n"
    }

    // `break`/`continue` bind to the nearest loop. The inner loop breaks out of itself at j==1,
    // so each outer step adds only j==0's contribution (i*0 = 0) plus... nothing; the outer loop
    // runs to completion. The total pins that the break left the inner loop, not the outer.
    "break leaves only the innermost loop" in {
      val src =
        """var hits = 0
          |var total = 0
          |for i in 1..3
          |    for j in 0..<5
          |        if j == 2 then break
          |        total += i * 10 + j
          |    hits += 1
          |print(hits, total)""".stripMargin

      // inner runs j=0,1 for each i in 1..3: 10+11 + 20+21 + 30+31 = 123
      run(src) shouldBe "3 123\n"
    }

    // The `else` runs for effect (unit loop) when the loop completes normally, and is skipped
    // when a bare `break` cuts it short — Python's for/else, kept for the statement form.
    "an else on a unit loop runs only on normal completion" in {
      val src =
        """search(hit: bool)
          |    for i in 0..<3
          |        if i == 1 then
          |            if hit then break
          |        print(i)
          |    else print(99)
          |search(false)
          |print(0)
          |search(true)""".stripMargin

      // no break: prints 0,1,2 then else 99; break at i==1: prints 0 then 1 (from before the
      // hit check)… actually the print is after the break check, so hit path prints 0 then breaks
      run(src) shouldBe "0\n1\n2\n99\n0\n0\n"
    }

    // A loop is a function's whole body — its value is the function's return value, threaded
    // through `break`/`else` exactly as when it initializes a `var`.
    "a loop is a function's tail expression" in {
      val src =
        """firstOver(limit: int) -> int
          |    for x in [3, 6, 9]
          |        if x > limit then break x
          |    else -1
          |print(firstOver(5), firstOver(100))""".stripMargin

      run(src) shouldBe "6 -1\n"
    }
  }
}
