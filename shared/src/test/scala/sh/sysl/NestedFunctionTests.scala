package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** Nested functions (`reference/declarations.md`) — a closure with a name, and the two halves of
 * its rule: every name in a block is in scope throughout it, and what may be captured is settled
 * where the group is written.
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

    // Card `0224`. This used to be refused: the group's environment was built where the *first* of
    // its functions stood, so a binding written below that point was out of reach of all of them. It
    // is built after the last binding any of them reads instead, so the ordinary layout — helpers
    // above the data they use — is simply what it looks like.
    "a capture written below the group is read, and reads what its initializer left" in {
      run("""good() -> int
            |    go() -> int = later
            |
            |    var later = 1
            |
            |    go()
            |
            |print(good())
            |""".stripMargin) shouldBe "1\n"
    }
    // Card `0221`. The rule is the one above — what the group may capture is what the block had
    // bound where the **first** of them is written — but the message was measured against the wrong
    // thing, so a use written *below* the declaration was told the declaration was below *it*. That
    // reads as a contradiction, and it cost a session five failed reductions before somebody
    // compared the two line numbers.
    // Cards `0221` and `0224` together. `0221` fixed the message, which used to tell a use written
    // below a declaration that the declaration was below *it*; `0224` removed the restriction it was
    // reporting, so the program simply runs.
    "a binding made after the group's first function is still one it can read" in {
      run("""var counted = 0
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
            |print(counted)
            |""".stripMargin) shouldBe "1\n1\n"
    }

    // A *write* through to a later binding, which is the case that would go wrong quietly if the
    // environment held anything but the real slot's address: a copy would accumulate and print 0.
    "and a write reaches the later binding itself, not a copy of it" in {
      run("""add(k: int)
            |    total = total + k
            |
            |var total = 0
            |
            |add(3)
            |add(4)
            |print(total)
            |""".stripMargin) shouldBe "7\n"
    }

    // Every block is its own group, so a loop body gets a fresh environment per iteration and the
    // binding it waits on is that iteration's.
    "a group inside a loop body waits on that iteration's own binding" in {
      run("""var grand = 0
            |
            |for i in 0..<3
            |    step(k: int)
            |        acc = acc + k
            |
            |    var acc = 0
            |
            |    step(i)
            |    step(i)
            |    grand = grand + acc
            |
            |print(grand)
            |""".stripMargin) shouldBe "6\n"
    }

    // The half that keeps the relaxation honest. One environment serves the whole group, so until it
    // is built there is nothing to pass to *any* of them — and calling one early would otherwise read
    // a slot whose initializer has not run.
    "but calling one before that binding is refused, and both ways out are named" - {
      val early =
        """var counted = 0
          |
          |bump(k: int)
          |    counted += k
          |end bump
          |
          |read() -> int = data[0]
          |
          |bump(1)
          |
          |val data: [3]int = [1, 2, 3]
          |
          |print(read())
          |""".stripMargin

      "naming the binding that is not ready" in {
        err(early) should include("'data' is bound below this call")
      }

      "and saying it is the shared environment that is waiting" in {
        err(early) should include("share one environment")
      }

      "and offering the two moves that fix it" in {
        val message = err(early)

        message should include("move the call below it")
        message should include("move it above the functions")
      }

      // `bump` reads nothing that is waiting and is refused anyway, which is forced rather than
      // conservative: the environment it would be passed is the one that does not exist yet.
      "including for a function that does not itself read it" in {
        err(early) should include("'bump' cannot be called here")
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
