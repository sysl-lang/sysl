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

  "loop" - {
    // The plain shape: a counter, and the only way out is the `break`.
    "a loop runs until something breaks out of it" in {
      val src =
        """var i = 0
          |loop
          |    i += 1
          |    if i == 4 then break
          |print(i)""".stripMargin

      run(src) shouldBe "4\n"
    }

    // With no `else` to reach, the value is the break's alone — which is the difference from
    // `while true`, where a value-carrying break needs an `else` that can never run.
    "a loop yields its break value with no else to write" in {
      val src =
        """var n = 0
          |var r = loop
          |    n += 1
          |    if n == 7 then break n * 100
          |print(r)""".stripMargin

      run(src) shouldBe "700\n"
    }

    "an inline loop body with `do`" in {
      val src =
        """var i = 0
          |loop do if i == 3 then break else i++
          |print(i)""".stripMargin

      run(src) shouldBe "3\n"
    }

    "continue starts the next iteration" in {
      val src =
        """var i = 0
          |var sum = 0
          |loop
          |    i += 1
          |    if i > 10 then break
          |    if i % 2 == 0 then continue
          |    sum += i
          |print(sum)""".stripMargin

      run(src) shouldBe "25\n"
    }

    "a labeled break leaves an outer loop from inside an inner one" in {
      val src =
        """var i = 0
          |var j = 0
          |var found = 'outer loop
          |    j = 0
          |    loop
          |        j += 1
          |        if i * j == 12 then break 'outer i * 10 + j
          |        if j == 5 then break
          |    i += 1
          |print(found)""".stripMargin

      // i=3, j=4 is the first product of 12 → 34.
      run(src) shouldBe "34\n"
    }

    // A loop with no break does not finish, so its type is `never` — which is what lets it stand
    // where a function still owes an `int`, with nothing after it to supply one.
    "a loop with no break types as never" in {
      val src =
        """spin(n: int) -> int
          |    if n > 0 then return n
          |    loop
          |        print("x")
          |print(spin(5))""".stripMargin

      run(src) shouldBe "5\n"
    }

    // The loop's expected type reaches its `break`s the way it reaches an `if`'s branches, so a
    // value breaks out boxed rather than being refused against the `&Point` the binding asks for.
    "a break in a &T context boxes its value" in {
      val src =
        """struct Point
          |    x: int
          |    y: int
          |var n = 0
          |var p: &Point = loop
          |    n += 1
          |    if n == 3 then break Point(n, n * 2)
          |print(p.x, p.y)""".stripMargin

      run(src) shouldBe "3 6\n"
    }

    // A `return` leaves the function from inside a loop that has no `break` of its own — so the
    // loop is still `never` and the exit is the return's, not the loop's.
    "a return leaves a loop that has no break" in {
      val src =
        """first_square_over(limit: int) -> int
          |    var i = 0
          |    loop
          |        i += 1
          |        if i * i > limit then return i * i
          |print(first_square_over(20), first_square_over(100))""".stripMargin

      run(src) shouldBe "25 121\n"
    }

    // An unlabeled `break` binds to the nearest loop whichever kind that is, so the `loop` inside
    // a `for` catches it and the `for` keeps going.
    "a loop inside a for catches the unlabeled break" in {
      val src =
        """var total = 0
          |for i in 1..3
          |    var j = 0
          |    loop
          |        j += 1
          |        if j == i then break
          |    total += i * 10 + j
          |print(total)""".stripMargin

      // j ends at i each time: 11 + 22 + 33
      run(src) shouldBe "66\n"
    }

    // The body owns a fresh string each iteration and leaves through a break, so the teardown on
    // the way out has to release what the last iteration took and nothing more.
    "a loop that owns values neither leaks nor frees twice" in {
      val src =
        """var total = 0
          |var k = 0
          |loop
          |    var s = "ab" + "cd"
          |    total += int(s[0])
          |    k += 1
          |    if k == 2000 then break
          |print(total)""".stripMargin

      run(src) shouldBe (97 * 2000).toString + "\n"
    }
  }

  "labeled loops" - {
    // `break 'outer` from the inner loop leaves both loops at once — the outer loop's `hits`
    // counter is never bumped past the iteration where the match is found.
    "a labeled break leaves the outer loop from inside the inner" in {
      val src =
        """var found = -1
          |var hits = 0
          |'outer for i in 0..<4
          |    for j in 0..<4
          |        if i * 4 + j == 7 then
          |            found = i * 10 + j
          |            break 'outer
          |    hits += 1
          |print(found, hits)""".stripMargin

      // i=1, j=3 hits 7 → found = 13; hits bumped only for i=0 before the break → 1
      run(src) shouldBe "13 1\n"
    }

    // `continue 'rows` from the inner loop skips to the next iteration of the OUTER loop, so the
    // inner loop's remaining iterations for that row are abandoned.
    "a labeled continue advances the outer loop" in {
      val src =
        """var sum = 0
          |'rows for i in 0..<3
          |    for j in 0..<3
          |        if j == 1 then continue 'rows
          |        sum += i * 10 + j
          |print(sum)""".stripMargin

      // each row adds only j=0 before continuing the outer: 0 + 10 + 20 = 30
      run(src) shouldBe "30\n"
    }

    // A labeled `break` carries a value out of the *named* loop, which is a function's tail
    // expression — so the value threads through the outer loop's result, not the inner's.
    "a labeled break carries a value out of the named loop" in {
      val src =
        """firstSquareOver(limit: int) -> int
          |    'scan for i in 0..<100
          |        for j in 0..<100
          |            if i * j > limit then break 'scan i * 1000 + j
          |    else -1
          |print(firstSquareOver(50), firstSquareOver(100000))""".stripMargin

      // i=1: j up to 50 gives 50 (not >50), j=51 → 51>50 → break with 1*1000+51 = 1051.
      run(src) shouldBe "1051 -1\n"
    }

    // An unlabeled break still binds to the nearest loop even when an outer loop is labeled, so
    // the label changes nothing until it is named.
    "an unlabeled break beside a label still leaves only the inner loop" in {
      val src =
        """var total = 0
          |'outer for i in 1..3
          |    for j in 0..<5
          |        if j == 2 then break
          |        total += i * 10 + j
          |print(total)""".stripMargin

      run(src) shouldBe "123\n"
    }

    // Three deep: a break to the middle label leaves the middle and innermost loops but resumes
    // the outermost, so the outermost keeps iterating.
    "a break to a middle label leaves two of three loops" in {
      val src =
        """var log = 0
          |for a in 0..<3
          |    'mid for b in 0..<3
          |        for c in 0..<3
          |            if c == 1 then break 'mid
          |            log += a * 100 + b * 10 + c
          |    log += 100000
          |print(log)""".stripMargin

      // For each a, the mid loop starts b=0, inner c=0 adds a*100, then c=1 breaks 'mid → mid ends.
      // So per a: adds a*100 (b=0,c=0) then 100000. a=0,1,2: (0+100+200) + 3*100000 = 300300.
      run(src) shouldBe "300300\n"
    }

    // A labeled loop that also owns heap values in its body must still tear them down correctly
    // when a labeled break unwinds several loops at once.
    "a labeled break through owning loops neither leaks nor frees twice" in {
      val src =
        """var total = 0
          |for k in 1..2000
          |    'find for i in 0..<8
          |        for j in 0..<8
          |            var s = "ab" + "cd"
          |            if i == 3 then break 'find
          |            total += int(s[0])
          |print(total)""".stripMargin

      // Per k: i=0,1,2 each run j=0..7 adding 97 (='a') = 3*8*97 = 2328; i=3 breaks. ×1999 k's
      // (k=1..1999, i.e. 1..2000 exclusive upper? range is inclusive 1..2000 → 2000 iters).
      run(src) shouldBe (2328 * 2000).toString + "\n"
    }
  }

  // A control-flow expression may sit on the right of an assignment, the same tail position a
  // `var` initializer allows — so the value flows through the analyzer and codegen there too.
  "control flow on the right of an assignment" - {
    "a compound assignment accumulates a match value" in {
      val src =
        """var total = 0
          |for i in 0..<4
          |    total += i % 2 == 0 match
          |        true -> 10
          |        false -> 1
          |print(total)""".stripMargin

      // even → +10, odd → +1, over i=0,1,2,3 → 10 + 1 + 10 + 1
      run(src) shouldBe "22\n"
    }

    "a plain reassignment takes an if value" in {
      val src =
        """var x = 0
          |x = if 3 > 2 then 100 else 200
          |print(x)""".stripMargin

      run(src) shouldBe "100\n"
    }

    "a reassignment takes a match value" in {
      val src =
        """var y = 0
          |y = 5 match
          |    5 -> 50
          |    else 0
          |print(y)""".stripMargin

      run(src) shouldBe "50\n"
    }

    "a compound assignment takes a loop's break value" in {
      val src =
        """var acc = 100
          |acc += for k in 0..<10
          |    if k * k > 20 then break k
          |else -1
          |print(acc)""".stripMargin

      // k=5 first has k*k=25 > 20 → break 5; acc = 100 + 5
      run(src) shouldBe "105\n"
    }
  }
}
