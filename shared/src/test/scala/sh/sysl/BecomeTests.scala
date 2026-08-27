package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** `become f(…)` — a call that **replaces** this frame rather than adding to it
 * (`reference/declarations.md § become — a call that replaces the frame`, `TailJumps`).
 *
 * **What `@tailrec` is to a function's calls to itself, this is to a call to a different one** — and
 * the difference is that the second cannot be an optimization. A chain of tail calls between
 * functions is a loop only if *every* one of them is eliminated; one that is not is an immediate
 * stack overflow rather than a slowdown. So the guarantee has to be in the language: LLVM's
 * `musttail` refuses a module it cannot lower, and every rule this suite pins is a refusal at the
 * source rather than a condition on an optimizer.
 *
 * The measurements below are the ones that say the guarantee is real: **ten million** mutual calls
 * at `-O0`, which is the level where nothing is eliminated by luck.
 */
class BecomeTests extends AnyFreeSpec with CodegenSupport with RunSupport {

  private val mutual =
    """even(n: int) -> bool
      |    if n == 0 then return true
      |
      |    become odd(n - 1)
      |
      |odd(n: int) -> bool
      |    if n == 0 then return false
      |
      |    become even(n - 1)
      |
      |""".stripMargin

  "a chain of calls between functions is a loop rather than a stack" - {

    /** **The whole point, at the level where nothing is eliminated by accident.** The comment on
     * `TailCallTests` records that at `-O1` LLVM's sibling-call pass happens to turn this pair into
     * jumps and the same source at `-O0` dies of a stack overflow. `become` is what makes it a
     * promise instead of a coincidence, so this runs unoptimized on purpose.
     */
    "ten million of them, unoptimized" in {
      run(mutual + "print(even(10000000))\n", optimize = "0") shouldBe "true\n"
    }

    "and the answer is the one the recursion computes" in {
      run(mutual + "print(even(4), odd(4), even(7), odd(7))\n", optimize = "0") shouldBe
        "true false false true\n"
    }

    "a function may 'become' itself, which is the self-call made explicit" in {
      run("""count(n: long, acc: long) -> long
            |    if n == 0 then return acc
            |
            |    become count(n - 1, acc + n)
            |
            |print(count(1000000, 0))
            |""".stripMargin, optimize = "0") shouldBe "500000500000\n"
    }
  }

  "the IR says 'musttail', which is what LLVM verifies" in {
    ir(mutual + "print(even(2))\n") should include("musttail call")
  }

  /** **Threaded dispatch is the forcing case**, and what makes it one is that the next callee is
   * chosen at run time out of a table of function addresses. A `become` through a function pointer
   * is what that needs.
   */
  "the callee may be chosen at run time, through a function address" in {
    run("""step(vm: *int) -> int
          |    vm[0] += 1
          |
          |    if vm[0] >= 5 then return vm[0]
          |
          |    var table: [2]*extern(*int) -> int = [&step, &step]
          |
          |    become table[vm[0] % 2](vm)
          |
          |var n = 0
          |print(step(&n))
          |""".stripMargin, optimize = "0") shouldBe "5\n"
  }

  "what it refuses, and each refusal says which rule it is" - {

    // LLVM's own: the arguments have to land where the replaced frame's were, and a result has to
    // come back the way this frame's caller is waiting for it.
    "a callee of a different shape" in {
      val e = err("""f(n: int) -> int
                    |    become g(n, 1)
                    |
                    |g(a: int, b: int) -> int = a + b
                    |
                    |print(f(1))
                    |""".stripMargin)

      e should include("same shape")
    }

    "a callee answering something else" in {
      val e = err("""f(n: int) -> int
                    |    become g(n)
                    |
                    |g(n: int) -> bool = n > 0
                    |
                    |print(f(1))
                    |""".stripMargin)

      e should include("answers")
    }

    // `TailCalls` refuses the same two for the same reasons, stated the same way.
    "an 'ensures', which is checked when a call returns" in {
      val e = err("""f(n: int) -> int
                    |    ensure result > 0
                    |    become g(n)
                    |
                    |g(n: int) -> int = n + 1
                    |
                    |print(f(1))
                    |""".stripMargin)

      e should include("'ensures'")
      e should include("never returns")
    }

    "a 'defer', which runs on the way out and the jump is the way out" in {
      val e = err("""f(n: int) -> int
                    |    defer print("out")
                    |
                    |    become g(n)
                    |
                    |g(n: int) -> int = n + 1
                    |
                    |print(f(1))
                    |""".stripMargin)

      e should include("'defer'")
    }

    /** **ARC's, and the one the card called the real design work.** A frame's references are let go
     * before the jump, and the callee takes its own count at entry — so a counted argument read out
     * of a slot the release is about to free would be a use-after-free, and retaining first would
     * leak instead. Refusing the case is exact; a convention two frames have to agree on is not.
     */
    "a parameter that carries a reference count" in {
      val e = err("""struct Node
                    |    v: int
                    |
                    |f(n: &Node) -> int
                    |    become g(n)
                    |
                    |g(n: &Node) -> int = n.v
                    |
                    |var x: &Node = Node(1)
                    |print(f(x))
                    |""".stripMargin)

      e should include("counted value")
    }

    "an 'extern', whose frame is C's" in {
      val e = err("""extern "abs" c_abs(n: i32) -> i32
                    |
                    |f(n: i32) -> i32
                    |    become c_abs(n)
                    |
                    |print(f(-3))
                    |""".stripMargin)

      e should include("'extern'")
    }

    "and something that is not a call at all" in {
      err("""f(n: int) -> int
            |    become n + 1
            |
            |print(f(1))
            |""".stripMargin) should include("'become' takes a call")
    }

    /** The parser's refusal above is about a form that is not a call at all. This is the analyzer's:
     * a **struct construction** is a `Call` in the grammar and is not a call to anything, so the two
     * refusals are for two different mistakes and both are reachable.
     */
    "or a call-shaped form that calls nothing" in {
      err("""struct P
            |    x: int
            |
            |f(n: int) -> P
            |    become P(n)
            |
            |print(f(1).x)
            |""".stripMargin) should include("has to be a call")
    }
  }

  /** **`become` is a soft word, not a reserved one.** A reserved word is spent out of every
   * program's namespace for the sake of one line apiece; this needs no reservation, because two
   * identifiers in a row are not otherwise a statement.
   */
  "it is a soft word, so 'become' goes on being an ordinary name" - {

    "as a variable" in {
      run("""var become = 41
            |
            |become = become + 1
            |print(become)
            |""".stripMargin) shouldBe "42\n"
    }

    "and as a function, which a call to it still reaches" in {
      run("""become(n: int) -> int = n * 2
            |
            |print(become(21))
            |""".stripMargin) shouldBe "42\n"
    }

    "beside a 'become' of that very function" in {
      run("""become(n: int) -> int
            |    if n <= 0 then return 0
            |
            |    become become(n - 1)
            |
            |print(become(3))
            |""".stripMargin, optimize = "0") shouldBe "0\n"
    }
  }
}
