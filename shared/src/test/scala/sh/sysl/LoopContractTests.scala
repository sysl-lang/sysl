package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** `invariant` and `variant` at the head of a loop body, and `variant` in a function's contract
 * block (`17 §3`, `17 §4`).
 *
 * Both clauses really run, which is what separates them from a specification-only reading, so most
 * of what is here is a pair: a program the clause lets through and the neighbouring one it stops.
 * The two cases that would pass a wrong implementation are called out where they are — a loop nested
 * inside another (a measure armed once per call rather than once per entry) and a `@tailrec` self
 * call (a check the jump would skip).
 */
class LoopContractTests extends AnyFreeSpec with RunSupport with CodegenSupport {

  "a loop invariant" - {

    "holds and the loop runs" in {
      run("""sum_to(n: int) -> int
            |    var i = 0
            |    var s = 0
            |
            |    while i < n
            |        invariant i >= 0 && i <= n
            |        s += i
            |        i += 1
            |
            |    s
            |
            |print(sum_to(5))
            |""".stripMargin) shouldBe "10\n"
    }

    // The same loop with the bound one too tight: `i` reaches 5 and the clause says 4, so the last
    // entry to the body is the one that stops it. Written the other way the loop finishes, which is
    // the test above.
    "fails and stops the program" in {
      exits("""sum_to(n: int) -> int
              |    var i = 0
              |    var s = 0
              |
              |    while i < n
              |        invariant i < 4
              |        s += i
              |        i += 1
              |
              |    s
              |
              |print(sum_to(5))
              |""".stripMargin)
    }

    // It is checked on **entry to the body**, so a loop whose condition is false from the start
    // never checks it at all — a clause that also ran at the loop's own entry would trap here.
    "is not checked when the body never runs" in {
      run("""f() -> int
            |    var i = 10
            |
            |    while i < 5
            |        invariant false
            |        i += 1
            |
            |    i
            |
            |print(f())
            |""".stripMargin) shouldBe "10\n"
    }

    "several are all checked" in {
      exits("""f() -> int
              |    var i = 0
              |
              |    while i < 3
              |        invariant i >= 0
              |        invariant i < 2
              |        i += 1
              |
              |    i
              |
              |print(f())
              |""".stripMargin)
    }

    "carries a message like every other clause" in {
      run("""f() -> int
            |    var i = 0
            |
            |    while i < 2
            |        invariant i >= 0, "the index never goes backwards"
            |        i += 1
            |
            |    i
            |
            |print(f())
            |""".stripMargin) shouldBe "2\n"
    }

    "must be a bool" in {
      err("""f() -> int
            |    var i = 0
            |
            |    while i < 2
            |        invariant i + 1
            |        i += 1
            |
            |    i
            |""".stripMargin) should include("bool")
    }
  }

  "a loop variant" - {

    "decreases and the loop runs" in {
      run("""f(n: int) -> int
            |    var i = 0
            |
            |    while i < n
            |        variant n - i
            |        i += 1
            |
            |    i
            |
            |print(f(4))
            |""".stripMargin) shouldBe "4\n"
    }

    // A loop that would not terminate at all: without the clause this program hangs, so the clause
    // is the only reason there is a result to assert.
    "stops a loop that stops making progress" in {
      exits("""f() -> int
              |    var i = 0
              |
              |    while i < 10
              |        variant 10 - i
              |        if i == 3 then i -= 1
              |        else i += 1
              |
              |    i
              |
              |print(f())
              |""".stripMargin)
    }

    // Strictly decreasing: a measure that merely fails to increase is not enough, and a loop that
    // holds it steady is exactly the shape that fails to terminate.
    "a measure that stands still is refused" in {
      exits("""f() -> int
              |    var i = 0
              |    var k = 0
              |
              |    while i < 3
              |        variant k
              |        i += 1
              |
              |    i
              |
              |print(f())
              |""".stripMargin)
    }

    // THE case a wrong implementation passes: the inner loop is entered three times and its measure
    // starts at 3 each time. A flag armed once per *call* would compare the second entry's first
    // measure (3) against the first entry's last (1) and trap on a loop that is decreasing perfectly
    // well.
    "is armed once per entry to the loop, not once per call" in {
      run("""nested() -> int
            |    var t = 0
            |    var i = 0
            |
            |    while i < 3
            |        variant 3 - i
            |        var j = 0
            |
            |        while j < 3
            |            variant 3 - j
            |            t += 1
            |            j += 1
            |
            |        i += 1
            |
            |    t
            |
            |print(nested())
            |""".stripMargin) shouldBe "9\n"
    }

    "an unsigned measure compares unsigned" in {
      run("""f(n: usize) -> usize
            |    var i = 0usize
            |
            |    while i < n
            |        variant n - i
            |        i += 1usize
            |
            |    i
            |
            |print(f(3usize))
            |""".stripMargin) shouldBe "3\n"
    }

    "must be an integer" in {
      err("""f() -> int
            |    var i = 0
            |
            |    while i < 2
            |        variant true
            |        i += 1
            |
            |    i
            |""".stripMargin) should include("integer measure")
    }

    "one loop declares one" in {
      err("""f() -> int
            |    var i = 0
            |
            |    while i < 2
            |        variant 2 - i
            |        variant 3 - i
            |        i += 1
            |
            |    i
            |""".stripMargin) should include("one 'variant'")
    }
  }

  "the clauses reach every loop form" - {

    "a counted for" in {
      run("""f(n: int) -> int
            |    var s = 0
            |
            |    for i in 0..<n
            |        invariant i >= 0
            |        variant n - i
            |        s += i
            |
            |    s
            |
            |print(f(4))
            |""".stripMargin) shouldBe "6\n"
    }

    "a three-clause for" in {
      run("""f() -> int
            |    var s = 0
            |
            |    for var i = 0; i < 4; i++
            |        variant 4 - i
            |        s += i
            |
            |    s
            |
            |print(f())
            |""".stripMargin) shouldBe "6\n"
    }

    "a loop, where the measure is what ends it" in {
      run("""f() -> int
            |    var i = 0
            |
            |    loop
            |        variant 3 - i
            |        i += 1
            |        if i == 3 then break
            |
            |    i
            |
            |print(f())
            |""".stripMargin) shouldBe "3\n"
    }

    "a do-while" in {
      run("""f() -> int
            |    var i = 0
            |
            |    do
            |        variant 3 - i
            |        i += 1
            |    while i < 3
            |
            |    i
            |
            |print(f())
            |""".stripMargin) shouldBe "3\n"
    }

    "a for over an array" in {
      run("""f(a: [4]int) -> int
            |    var s = 0
            |    var seen = 0
            |
            |    for x in a
            |        invariant seen <= 4
            |        s += x
            |        seen += 1
            |
            |    s
            |
            |var a = [1, 2, 3, 4]
            |print(f(a))
            |""".stripMargin) shouldBe "10\n"
    }
  }

  "a function's variant" - {

    "decreases at each recursive call" in {
      run("""gcd(a: int, b: int) -> int
            |    require b >= 0
            |    variant b
            |    if b == 0 then a
            |    else gcd(b, a % b)
            |
            |print(gcd(48, 18))
            |""".stripMargin) shouldBe "6\n"
    }

    // The recursion really does terminate — it stops at 100 — so what stops this program is the
    // clause and not the stack.
    "stops a recursion whose measure grows" in {
      exits("""grow(n: int) -> int
              |    variant n
              |    if n > 100 then n
              |    else grow(n + 1)
              |
              |print(grow(1))
              |""".stripMargin)
    }

    // `16 §7` explains that a function with an `ensure` is not tail-call transformed, because a
    // postcondition is checked when a call returns and a tail call never returns. A `variant` is
    // checked *before* the call, so it survives the jump — and this is the test that says so, since
    // a check emitted only on the ordinary call path would be skipped here entirely.
    "survives the tail-call transform" in {
      run("""@tailrec
            |walk(n: int, acc: int) -> int
            |    variant n
            |    if n == 0 then acc
            |    else walk(n - 1, acc + n)
            |
            |print(walk(4, 0))
            |""".stripMargin) shouldBe "10\n"
    }

    "and catches a tail self-call that does not decrease" in {
      exits("""@tailrec
              |walk(n: int, acc: int) -> int
              |    variant n
              |    if acc > 50 then acc
              |    else walk(n + 1, acc + n)
              |
              |print(walk(1, 0))
              |""".stripMargin)
    }

    // The measure is over the arguments the call supplies, not over anything the frame holds, so a
    // call whose argument is computed still measures the computed value.
    "measures the argument the call supplies" in {
      run("""down(n: int) -> int
            |    variant n
            |    if n <= 0 then 0
            |    else down(n / 2 - 1) + 1
            |
            |print(down(9))
            |""".stripMargin) shouldBe "2\n"
    }

    "reads its parameters and nothing else" in {
      err("""f(n: int) -> int
            |    variant k
            |    var k = 3
            |    n
            |""".stripMargin) should include("undefined name 'k'")
    }

    "must be an integer" in {
      err("""f(n: int) -> int
            |    variant n > 0
            |    n
            |""".stripMargin) should include("integer measure")
    }

    "one function declares one" in {
      err("""f(n: int, m: int) -> int
            |    variant n
            |    variant m
            |    n
            |""".stripMargin) should include("one 'variant'")
    }

    "a closure declares none, and neither does a function nested in another" in {
      err("""outer() -> int
            |    inner(n: int) -> int
            |        variant n
            |        if n <= 0 then 0
            |        else inner(n - 1) + 1
            |
            |    inner(3)
            |""".stripMargin) should include("top-level function's")
    }

    "and a function with no recursive call simply carries it" in {
      run("""f(n: int) -> int
            |    variant n
            |    n * 2
            |
            |print(f(21))
            |""".stripMargin) shouldBe "42\n"
    }
  }

  "where a clause may stand" - {

    "an invariant after a statement in a loop body is refused" in {
      err("""f() -> int
            |    var i = 0
            |
            |    while i < 2
            |        i += 1
            |        invariant i > 0
            |
            |    i
            |""".stripMargin) should include("head of a loop's body")
    }

    "an invariant in a function's contract block is refused, and named as a require" in {
      err("""f(n: int) -> int
            |    invariant n > 0
            |    n
            |""".stripMargin) should include("'require'")
    }

    "a variant after a statement is refused" in {
      err("""f() -> int
            |    var i = 0
            |
            |    while i < 2
            |        i += 1
            |        variant 2 - i
            |
            |    i
            |""".stripMargin) should include("head of a loop's body")
    }

    "and one in a plain block is refused" in {
      err("""f() -> int
            |    if true then
            |        invariant true
            |        1
            |    else 2
            |""".stripMargin) should include("head of a loop's body")
    }
  }

  "the words stay ordinary identifiers" - {

    // The cost of the contextual spelling, and the realistic collision: a *value* called `invariant`
    // or `variant` is untouched, because neither rule is reached where a name is being read.
    "a value may be called 'invariant'" in {
      run("""f() -> int
            |    var invariant = 7
            |    invariant + 1
            |
            |print(f())
            |""".stripMargin) shouldBe "8\n"
    }

    "and one may be called 'variant'" in {
      run("""f() -> int
            |    var variant = 41
            |    variant + 1
            |
            |print(f())
            |""".stripMargin) shouldBe "42\n"
    }

    "a struct's own 'invariant' clause is untouched" in {
      exits("""struct Span
              |    lo: int
              |    hi: int
              |
              |    invariant lo <= hi
              |
              |print(Span(5, 1).lo)
              |""".stripMargin)
    }
  }
}
