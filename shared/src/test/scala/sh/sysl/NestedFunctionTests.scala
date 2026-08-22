package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** Nested functions (`12 §5a`) — a closure with a name, and the two halves of its rule: every name
 * in a block is in scope throughout it, and what may be captured is settled where the group is
 * written.
 */
class NestedFunctionTests extends AnyFreeSpec with RunSupport with CodegenSupport {

  "a function may stand inside a function body" - {
    "and is called by the name it was given" in {
      run("""outer(n: int) -> int
            |    inner(k: int) -> int
            |        k * 2
            |
            |    inner(n) + 1
            |
            |print(outer(5))
            |""".stripMargin) shouldBe "11\n"
    }

    "the '= expr' short form works here too" in {
      run("""outer(n: int) -> int
            |    inner(k: int) -> int = k * 2
            |
            |    inner(n)
            |
            |print(outer(5))
            |""".stripMargin) shouldBe "10\n"
    }

    "one yielding nothing is written with no result" in {
      run("""shout(n: int)
            |    say(k: int)
            |        print("k", k)
            |
            |    for i in 0..<n do say(i)
            |
            |shout(2)
            |""".stripMargin) shouldBe "k 0\nk 1\n"
    }

    "it takes no arguments as readily as several" in {
      run("""outer() -> int
            |    zero() -> int = 0
            |    three(a: int, b: int, c: int) -> int = a + b + c
            |
            |    zero() + three(1, 2, 3)
            |
            |print(outer())
            |""".stripMargin) shouldBe "6\n"
    }
  }

  "what the name buys" - {
    "recursion — a closure literal's name is not in scope in its own initializer, and this one's is" in {
      run("""fact(n: int) -> int
            |    go(k: int) -> int
            |        if k <= 1 then 1 else k * go(k - 1)
            |
            |    go(n)
            |
            |print(fact(5))
            |""".stripMargin) shouldBe "120\n"
    }

    "mutual recursion, whichever order the two are written in" in {
      run("""parity(n: int) -> string
            |    is_even(k: int) -> bool
            |        if k == 0 then true else is_odd(k - 1)
            |
            |    is_odd(k: int) -> bool
            |        if k == 0 then false else is_even(k - 1)
            |
            |    if is_even(n) then "even" else "odd"
            |
            |print(parity(7), parity(8))
            |""".stripMargin) shouldBe "odd even\n"
    }

    "written types, so nothing has to infer what it takes" in {
      run("""outer() -> int
            |    widen(k: u8) -> i64 = i64(k) * 2i64
            |
            |    int(widen(21u8))
            |
            |print(outer())
            |""".stripMargin) shouldBe "42\n"
    }
  }

  "capture reaches the enclosing variable, not a copy of it" - {
    "a nested function reads the block's locals and its function's parameters" in {
      run("""outer(n: int) -> int
            |    var extra = 10
            |
            |    inner(k: int) -> int = k + n + extra
            |
            |    inner(1)
            |
            |print(outer(2))
            |""".stripMargin) shouldBe "13\n"
    }

    "and a write reaches the variable itself" in {
      // The discriminating case for `§5a`. A nested function cannot escape, so its environment holds
      // the *addresses* of the block's variables — a by-value capture would accumulate into a copy
      // and print 0, which is the shape of bug this test exists to catch.
      run("""sum_to(n: int) -> int
            |    var total = 0
            |
            |    add(k: int)
            |        total = total + k
            |
            |    for i in 1..n do add(i)
            |
            |    total
            |
            |print(sum_to(4))
            |""".stripMargin) shouldBe "10\n"
    }

    "two of them write to one variable and both are seen" in {
      run("""counted() -> int
            |    var n = 0
            |
            |    up()
            |        n = n + 10
            |
            |    down()
            |        n = n - 1
            |
            |    up()
            |    up()
            |    down()
            |    n
            |
            |print(counted())
            |""".stripMargin) shouldBe "19\n"
    }

    "a 'val' captured into one is still written once" in {
      err("""outer() -> int
            |    val fixed = 5
            |
            |    change()
            |        fixed = 6
            |
            |    change()
            |    fixed
            |""".stripMargin) should include("a 'val' is written once")
    }

    "a counted reference is reached, not retained a second time" in {
      run("""struct Node
            |    v: int
            |
            |outer() -> int
            |    var node: &Node = Node(9)
            |
            |    read() -> int = node.v
            |
            |    read() + read()
            |
            |print(outer())
            |""".stripMargin) shouldBe "18\n"
    }

    "the chapter's own example: a quicksort of three nested functions" in {
      run("""sort(xs: []int)
            |    swap(i: usize, j: usize)
            |        var t = xs[i]
            |        xs[i] = xs[j]
            |        xs[j] = t
            |
            |    part(lo: usize, hi: usize) -> usize
            |        var pivot = xs[lo]
            |        var i = lo
            |        var j = hi
            |
            |        loop
            |            while xs[i] < pivot do i = i + 1
            |            while xs[j] > pivot do j = j - 1
            |            if i >= j then break j
            |            swap(i, j)
            |            i = i + 1
            |            j = j - 1
            |
            |    quick(lo: usize, hi: usize)
            |        if lo < hi
            |            var p = part(lo, hi)
            |            quick(lo, p)
            |            quick(p + 1usize, hi)
            |
            |    if xs.len > 0 then quick(0usize, xs.len - 1)
            |
            |var a = [5, 3, 9, 1, 7, 2, 8]
            |
            |sort(a[..])
            |
            |for v in a do print(v)
            |""".stripMargin) shouldBe "1\n2\n3\n5\n7\n8\n9\n"
    }
  }

  "where one may stand, and where it may not" - {
    "a block inside the body is its own block, with its own group" in {
      run("""loops(n: int) -> int
            |    var total = 0
            |
            |    for i in 0..<n
            |        step(k: int)
            |            total = total + k
            |
            |        step(i)
            |
            |    total
            |
            |print(loops(5))
            |""".stripMargin) shouldBe "10\n"
    }

    "one may contain another, and the inner reads the outer's parameters" in {
      run("""deep(n: int) -> int
            |    mid(a: int) -> int
            |        innermost(b: int) -> int = b + n
            |
            |        innermost(a) * 2
            |
            |    mid(n)
            |
            |print(deep(3))
            |""".stripMargin) shouldBe "12\n"
    }

    "it shadows a top-level function of the same name" in {
      // Ordinary lexical scoping — the nearest binding wins, which is `§5a Open`'s second item
      // answered the way every other name in the language answers it.
      run("""twice(x: int) -> int = x * 2
            |
            |shadowed() -> int
            |    twice(x: int) -> int = x * 3
            |
            |    twice(5)
            |
            |print(shadowed(), twice(5))
            |""".stripMargin) shouldBe "15 10\n"
    }

    "a call above the whole group is told where the group begins" in {
      err("""outer() -> int
            |    var n = go(1)
            |
            |    go(k: int) -> int = k + 1
            |
            |    n
            |""".stripMargin) should include("is declared below this call")
    }

    "a visibility modifier has nothing to restrict" in {
      err("""outer() -> int
            |    private inner() -> int = 1
            |
            |    inner()
            |""".stripMargin) should include("nothing for a visibility modifier to restrict")
    }

    "it cannot be generic, since nothing outside would supply the arguments" in {
      err("""outer() -> int
            |    id[T](x: T) -> T = x
            |
            |    id(1)
            |""".stripMargin) should include("cannot be generic")
    }

    "two of one name in one block collide" in {
      err("""outer() -> int
            |    go() -> int = 1
            |    go() -> int = 2
            |
            |    go()
            |""".stripMargin) should include("already declared in this block")
    }

    "a struct or an enum is still top-level only" in {
      err("""outer() -> int
            |    struct P
            |        x: int
            |
            |    1
            |""".stripMargin) should include("may only be declared at the top level")
    }
  }

  "it is called where it is written, and is not a value" - {
    "passing one names what to write instead" in {
      err("""apply(f: int -> int, x: int) -> int = f(x)
            |
            |outer() -> int
            |    inner(k: int) -> int = k * 2
            |
            |    apply(inner, 5)
            |""".stripMargin) should include("called where it is written rather than passed")
    }

    "a closure written beside one does not reach it either" in {
      // A closure may outlive the body around it, and a nested function's environment *is* that
      // body — so a closure calling one would be a way of carrying the frame out of itself.
      err("""apply(f: int -> int, x: int) -> int = f(x)
            |
            |outer() -> int
            |    inner(k: int) -> int = k * 2
            |
            |    apply(x -> inner(x), 5)
            |""".stripMargin) should include("reaches its own nested functions and its own captures and no further")
    }

    "and a nested function does not reach the group of the body around it" in {
      err("""outer() -> int
            |    helper(k: int) -> int = k + 1
            |
            |    mid() -> int
            |        inner() -> int = helper(1)
            |
            |        inner()
            |
            |    mid()
            |""".stripMargin) should include("reaches its own nested functions and its own captures and no further")
    }
  }

  "the arity check names the function a program wrote" in {
    err("""outer() -> int
          |    go(a: int, b: int) -> int = a + b
          |
          |    go(1)
          |""".stripMargin) should include("'go' takes 2 arguments, but 1 argument was given")
  }

  "the edge cases" - {
    "a group inside an 'if' branch, and another inside its 'else'" in {
      run("""branchy(n: int) -> int
            |    if n > 0
            |        pos(k: int) -> int = k * 2
            |
            |        pos(n)
            |    else
            |        neg(k: int) -> int = k * 3
            |
            |        neg(n)
            |
            |print(branchy(4), branchy(-4))
            |""".stripMargin) shouldBe "8 -12\n"
    }

    "three-way mutual recursion" in {
      run("""three(n: int) -> string
            |    a(k: int) -> string = if k == 0 then "a" else b(k - 1)
            |    b(k: int) -> string = if k == 0 then "b" else c(k - 1)
            |    c(k: int) -> string = if k == 0 then "c" else a(k - 1)
            |
            |    a(n)
            |
            |print(three(0), three(1), three(2), three(3))
            |""".stripMargin) shouldBe "a b c a\n"
    }

    "contract clauses of its own" in {
      run("""guarded(n: int) -> int
            |    go(k: int) -> int
            |        require k > 0, "positive"
            |        k * 2
            |
            |    go(n)
            |
            |print(guarded(3))
            |""".stripMargin) shouldBe "6\n"
    }

    "and a broken one stops the program" in {
      exits("""guarded(n: int) -> int
              |    go(k: int) -> int
              |        require k > 0, "positive"
              |        k * 2
              |
              |    go(n)
              |
              |print(guarded(-1))
              |""".stripMargin)
    }

    "it writes through a captured reference" in {
      run("""struct Cell
            |    v: int
            |
            |viaref() -> int
            |    var c: &Cell = Cell(1)
            |
            |    inc()
            |        c.v = c.v + 5
            |
            |    inc()
            |    inc()
            |    c.v
            |
            |print(viaref())
            |""".stripMargin) shouldBe "11\n"
    }

    "a nested function and a closure in one block capture the same name differently" in {
      // The one program that shows both rules at once: the nested function reaches the variable and
      // the closure took a copy of it when it was formed, so the second `bump` moves one and not
      // the other. Either rule applied to both would give 16 or 6 for both.
      run("""mixed(n: int) -> int
            |    var total = n
            |
            |    bump(k: int)
            |        total = total + k
            |
            |    bump(1)
            |
            |    var f: &Fn(int) -> int = k -> k + total
            |
            |    bump(10)
            |    print(total)
            |    f(0)
            |
            |print(mixed(5))
            |""".stripMargin) shouldBe "16\n6\n"
    }

    "a capture written below the group is told which mistake it is" in {
      err("""bad() -> int
            |    go() -> int = later
            |
            |    var later = 1
            |
            |    go()
            |""".stripMargin) should include("'later' is declared below this")
    }

    "and the group is still callable, so one bad body is one message" in {
      val message = err("""bad() -> int
                          |    go() -> int = later
                          |
                          |    var later = 1
                          |
                          |    go()
                          |""".stripMargin)

      message should not include "undefined function 'go'"
    }

    // Card `0221`. The rule is the one above — what the group may capture is what the block had
    // bound where the **first** of them is written — but the message was measured against the wrong
    // thing, so a use written *below* the declaration was told the declaration was below *it*. That
    // reads as a contradiction, and it cost a session five failed reductions before somebody
    // compared the two line numbers.
    "a capture bound after the group begins, read from a function written below it" - {
      val program =
        """var counted = 0
          |
          |bump(k: int)
          |    counted += k
          |end bump
          |
          |val data: [3]int = [1, 2, 3]
          |
          |read() -> int = data[0]
          |
          |bump(1)
          |print(read())
          |""".stripMargin

      "is refused, because the environment was built above the binding" in {
        err(program) should include("'data' is bound after the nested functions of this block begin")
      }

      "and is NOT told the declaration is below it, which is where it sits" in {
        err(program) should not include "'data' is declared below this"
      }

      "and is told both ways out of it" in {
        val message = err(program)

        message should include("Bind 'data' above them")
        message should include("'static'")
      }
    }

    // The two halves of the discrimination, so that fixing one message cannot silently retire the
    // other: the same program with the binding moved above the group compiles, and with the *use*
    // above the binding it gets the original sentence.
    "moving the binding above the group is one of the two fixes the message names" in {
      run("""var counted = 0
            |val data: [3]int = [1, 2, 3]
            |
            |bump(k: int)
            |    counted += k
            |end bump
            |
            |read() -> int = data[0]
            |
            |bump(1)
            |print(read())
            |print(counted)
            |""".stripMargin) shouldBe "1\n1\n"
    }

    "and 'static' is the other, since module storage is bound before any statement runs" in {
      run("""var counted = 0
            |
            |bump(k: int)
            |    counted += k
            |end bump
            |
            |static val data: [3]int = [1, 2, 3]
            |
            |read() -> int = data[0]
            |
            |bump(1)
            |print(read())
            |print(counted)
            |""".stripMargin) shouldBe "1\n1\n"
    }
  }

  "a group that captures nothing carries nothing" in {
    // The environment is a struct like any other, and one over no names is empty — a nested function
    // that captures nothing really is the ordinary static function `§5a` says it is.
    val out = ir("""outer(n: int) -> int
                   |    inner(k: int) -> int = k * 2
                   |
                   |    inner(n)
                   |
                   |print(outer(5))
                   |""".stripMargin)

    out should include("%struct.$env0 = type {  }")
  }
}
