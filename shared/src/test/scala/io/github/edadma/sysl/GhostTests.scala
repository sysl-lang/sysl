package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** `@ghost` — a declaration that exists for the specification and is erased before codegen
 * (`17 §8`).
 *
 * Two halves have to be shown and neither implies the other. That the program still *runs* is the
 * easy half. That the ghost really is **gone** is the half the feature exists for, and a run test
 * cannot see it — a ghost predicate that was quietly emitted and called would give the same answers
 * and the same output, and would cost exactly what `@ghost` was added to avoid. So the erasure is
 * asserted against the emitted IR.
 */
class GhostTests extends AnyFreeSpec with RunSupport with CodegenSupport {

  /** An insertion sort with a ghost `is_sorted` in its postcondition and its outer invariant — the
   * shape `17 §8` is written about, where checking the clause would turn O(n²) into O(n³).
   */
  private val sort =
    """@ghost
      |is_sorted(a: []int, n: int) -> bool = for all i in 0..<n - 1 do a[i] <= a[i + 1]
      |
      |insertion(a: []int, n: int) -> int
      |    ensure is_sorted(a, n)
      |    var i = 1
      |
      |    while i < n
      |        invariant is_sorted(a, i)
      |        variant n - i
      |        var j = i
      |
      |        while j > 0 && a[j - 1] > a[j]
      |            variant j
      |            var t = a[j - 1]
      |
      |            a[j - 1] = a[j]
      |            a[j] = t
      |            j -= 1
      |
      |        i += 1
      |
      |    n
      |
      |var xs = [5, 2, 9, 1, 7]
      |print(insertion(xs[..], 5))
      |print(xs[0], xs[1], xs[2], xs[3], xs[4])
      |""".stripMargin

  "a ghost predicate in a contract" - {

    "leaves the program running" in {
      run(sort) shouldBe "5\n1 2 5 7 9\n"
    }

    // The half a run test cannot see. A ghost function that was emitted and called would give the
    // same output and cost the thing the annotation exists to avoid.
    "and the function is not in the emitted module at all" in {
      ir(sort) should not include "is_sorted"
    }

    // Its body is a quantifier, which lowers to a counted loop. If the clauses had been laid down,
    // this is what would be in the module — so it is the sharper of the two assertions.
    "nor is the loop its body would have lowered to" in {
      ir(sort) should not include "quant"
    }

    // The non-ghost clause beside it is untouched, which is what says the erasure is about the ghost
    // name rather than about clauses in general.
    "while an ordinary clause in the same function is still emitted" in {
      val src =
        """@ghost
          |big(n: int) -> bool = n > 0
          |
          |f(n: int) -> int
          |    require n >= 0
          |    require big(n)
          |    n
          |
          |print(f(1))
          |""".stripMargin

      ir(src) should not include "big"
      run(src) shouldBe "1\n"
      // The ordinary `require` still traps, so the neighbouring value is refused.
      exits("""@ghost
              |big(n: int) -> bool = n > 0
              |
              |f(n: int) -> int
              |    require n >= 0
              |    require big(n)
              |    n
              |
              |print(f(0 - 1))
              |""".stripMargin)
    }
  }

  "where a ghost function may be called" - {

    "from a require" in {
      run("""@ghost
            |ok(n: int) -> bool = n > 0
            |
            |f(n: int) -> int
            |    require ok(n)
            |    n
            |
            |print(f(1))
            |""".stripMargin) shouldBe "1\n"
    }

    "from an ensure" in {
      run("""@ghost
            |ok(n: int) -> bool = n > 0
            |
            |f(n: int) -> int
            |    ensure ok(result)
            |    n
            |
            |print(f(1))
            |""".stripMargin) shouldBe "1\n"
    }

    "from a loop invariant" in {
      run("""@ghost
            |ok(n: int) -> bool = n >= 0
            |
            |f() -> int
            |    var i = 0
            |
            |    while i < 3
            |        invariant ok(i)
            |        i += 1
            |
            |    i
            |
            |print(f())
            |""".stripMargin) shouldBe "3\n"
    }

    // A ghost function's body reads real state freely — that is the whole point of an `is_sorted` —
    // so what it calls is not restricted at all.
    "from another ghost function, and from a real one it calls" in {
      run("""plain(n: int) -> bool = n > 0
            |
            |@ghost
            |inner(n: int) -> bool = plain(n)
            |
            |@ghost
            |outer(n: int) -> bool = inner(n)
            |
            |f(n: int) -> int
            |    require outer(n)
            |    n
            |
            |print(f(1))
            |""".stripMargin) shouldBe "1\n"
    }
  }

  "where it may not" - {

    "from the body of an ordinary function" in {
      err("""@ghost
            |ok(n: int) -> bool = n > 0
            |
            |f(n: int) -> int
            |    if ok(n) then n
            |    else 0
            |
            |print(f(1))
            |""".stripMargin) should include("is '@ghost'")
    }

    "from a top-level statement" in {
      err("""@ghost
            |ok(n: int) -> bool = n > 0
            |
            |print(ok(1))
            |""".stripMargin) should include("is '@ghost'")
    }

    "and the message says where one may be called from" in {
      err("""@ghost
            |ok(n: int) -> bool = n > 0
            |
            |print(ok(1))
            |""".stripMargin) should include("'require'")
    }
  }

  "the annotation itself" - {

    "composes with @pure" in {
      run("""@ghost
            |@pure
            |ok(n: int) -> bool = n > 0
            |
            |f(n: int) -> int
            |    require ok(n)
            |    n
            |
            |print(f(1))
            |""".stripMargin) shouldBe "1\n"
    }

    "is refused on anything but a function" in {
      err("""@ghost
            |struct Point
            |    x: int
            |""".stripMargin) should include("annotation marks a function")
    }

    "and the unknown-annotation message names all four" in {
      err("""@sideways
            |f(x: int) -> int = x
            |""".stripMargin) should include("'@ghost'")
    }
  }

  "a value may still be called 'ghost'" in {
    run("""f() -> int
          |    var ghost = 41
          |    ghost + 1
          |
          |print(f())
          |""".stripMargin) shouldBe "42\n"
  }
}
