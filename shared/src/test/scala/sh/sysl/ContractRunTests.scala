package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** Design-by-contract at runtime: a `require` is checked on entry, an `ensure` before every
 * return. A satisfied clause is invisible; a violated one traps, so each passing case is paired
 * with the adjacent violation that must stop the program — catching a check that is too tight
 * (fails the valid case) or too loose (lets the invalid case run).
 */
class ContractRunTests extends AnyFreeSpec with RunSupport with CodegenSupport {

  "require" - {
    "a satisfied precondition lets the body run" in {
      run(
        """half(x: int) -> int
          |    require x >= 0
          |    x / 2
          |print(half(10))""".stripMargin
      ) shouldBe "5\n"
    }

    "the boundary value passes" in {
      run(
        """half(x: int) -> int
          |    require x >= 0
          |    x / 2
          |print(half(0))""".stripMargin
      ) shouldBe "0\n"
    }

    "a violated precondition traps before the body runs" in {
      exits(
        """half(x: int) -> int
          |    require x >= 0
          |    x / 2
          |print(half(-2))""".stripMargin
      )
    }
  }

  "ensure with result" - {
    "a satisfied postcondition returns normally, result bound to the value" in {
      run(
        """abs(x: int) -> int
          |    ensure result >= 0
          |    if x < 0 then -x else x
          |print(abs(-7), abs(4))""".stripMargin
      ) shouldBe "7 4\n"
    }

    // A postcondition that the trailing-expression path violates must trap on that path — here a
    // negative input would return a negative value, so `result >= 0` fails.
    "a violated postcondition on the fall-through path traps" in {
      exits(
        """bad(x: int) -> int
          |    ensure result >= 0
          |    x
          |print(bad(-1))""".stripMargin
      )
    }
  }

  // An early `return` is its own exit site, so the postcondition is checked there too — not only
  // on the trailing expression.
  "ensure is checked at an early return" - {
    "the early-return path that satisfies it passes" in {
      run(
        """f(x: int) -> int
          |    ensure result >= 10
          |    if x < 0 then return 100
          |    x + 100
          |print(f(-5), f(3))""".stripMargin
      ) shouldBe "100 103\n"
    }

    "the early-return path that violates it traps" in {
      exits(
        """f(x: int) -> int
          |    ensure result >= 10
          |    if x < 0 then return 0
          |    x + 100
          |print(f(-5))""".stripMargin
      )
    }
  }

  "require and ensure together" - {
    "both hold across every path" in {
      run(
        """clamp(x: int, hi: int) -> int
          |    require hi >= 0
          |    ensure result >= 0
          |    ensure result <= hi
          |    if x < 0 then return 0
          |    if x > hi then return hi
          |    x
          |print(clamp(-3, 8), clamp(20, 8), clamp(5, 8))""".stripMargin
      ) shouldBe "0 8 5\n"
    }

    "the precondition is what fails when the caller breaks it" in {
      exits(
        """clamp(x: int, hi: int) -> int
          |    require hi >= 0
          |    ensure result <= hi
          |    if x > hi then return hi
          |    x
          |print(clamp(5, -1))""".stripMargin
      )
    }
  }

  // `result` is a contextual keyword: a real binding of that name still wins, so ordinary code
  // that happens to use `result` as a variable is unaffected.
  "a local named result shadows the contract keyword" in {
    run(
      """var result = 3
        |result = result + 4
        |print(result)""".stripMargin
    ) shouldBe "7\n"
  }

  // `old(e)` remembers the entry value even after the body mutates it — the whole point, and what
  // tells it apart from naming the parameter directly.
  "old captures the entry value across a mutation" - {
    "the postcondition that compares against the entry value holds" in {
      run(
        """bump(x: int) -> int
          |    ensure result == old(x) + 10
          |    x = x + 5
          |    x + 5
          |print(bump(100))""".stripMargin
      ) shouldBe "110\n"
    }

    // Naming `x` here would read the *mutated* value and pass; `old(x)` reads the entry value, so
    // this postcondition is false and must trap — proving the snapshot is the entry value.
    "comparing the mutated value against the entry snapshot traps" in {
      exits(
        """bad(x: int) -> int
          |    ensure result == old(x)
          |    x = x + 5
          |    x
          |print(bad(100))""".stripMargin
      )
    }
  }

  // Several `old`s in one ensure each keep their own entry snapshot.
  "multiple old snapshots are independent" in {
    run(
      """f(a: int, b: int) -> int
        |    ensure result == old(a) - old(b)
        |    a = 0
        |    b = 0
        |    9 - 4
        |print(f(9, 4))""".stripMargin
    ) shouldBe "5\n"
  }

  /** `reference/errors.md § Struct invariants`. A contract is a block at the **top** of the body,
   * both kinds together — an `ensure` is written with the preconditions and checked before every
   * return, and neither kind may come after ordinary work. A method is where the pair earns its
   * keep, because `old` on a `*self` receiver is how a mutating method says what it changed.
   */
  "a method carries contracts, and old reads through its receiver" - {
    val counter =
      """struct Counter
        |    n: int
        |
        |    bump(*self, k: int) -> int
        |        require k > 0
        |        ensure result > old(self.n)
        |        self.n += k
        |        self.n
        |""".stripMargin

    "a call that satisfies both runs" in {
      run(counter + "var c = Counter(1)\nprint(c.bump(2))") shouldBe "3\n"
    }

    "one that breaks the precondition traps" in {
      exits(counter + "var c = Counter(1)\nprint(c.bump(0))")
    }

    // Written after the increment, the postcondition would be a statement in the middle of the
    // body, which is exactly what the placement rule refuses.
    "and an ensure written after ordinary work is refused" in {
      err("""struct Counter
            |    n: int
            |
            |    bump(*self, k: int) -> int
            |        self.n += k
            |        ensure result > 0
            |        self.n
            |var c = Counter(1)
            |print(c.bump(2))""".stripMargin) should
        include("must come before any other statement")
    }
  }
}
