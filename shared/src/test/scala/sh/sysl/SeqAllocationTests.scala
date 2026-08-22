package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** What a call into `sysl.seq` costs, pinned where it can be read: in the emitted code.
 *
 * The trait's members take their callable by **bare arrow**, which is a bounded type parameter
 * (`12 §6`) — so a closure handed to one is a type argument, monomorphized into a copy of the member
 * and called directly. Nothing is boxed, and the seven members that build no sequence therefore
 * reach the allocator not at all.
 *
 * **This suite exists to keep that honest**, and it has already earned its place twice. The module's
 * prose first claimed there was no allocation when every call boxed its closure; it then claimed two
 * allocations for a fold that made one. Nothing else would have caught either: every answer is
 * correct whichever spelling the parameter uses, and a test that checks answers cannot tell an
 * inlined callable from a boxed one.
 *
 * The counts are written as counts rather than as "at least one" for the same reason — the pair below
 * is what says the difference is the *spelling* and not the module.
 */
class SeqAllocationTests extends AnyFreeSpec with RunSupport with CodegenSupport {

  private def allocations(src: String): Int =
    ir(src).linesIterator.count(l => l.contains("call") && l.contains("@malloc"))

  private def folding =
    """import sysl.seq.Sequence
      |
      |val xs = [1, 2, 3]
      |
      |print(xs[..].fold(0, (a, n) -> a + n))
      |""".stripMargin

  "a callable handed to one of these is not boxed" - {

    "folding a slice allocates nothing, exactly as the loop it replaces does not" in {
      allocations(folding) shouldBe 0
    }

    "and the same walk written as a loop is the comparison that says so" in {
      allocations(
        """val xs = [1, 2, 3]
          |var acc = 0
          |
          |for i in 0..<xs.len
          |    acc = acc + xs[i]
          |
          |print(acc)
          |""".stripMargin,
      ) shouldBe 0
    }

    /** The boxed spelling written out by hand, which is what these members took before a trait's
      * member could write an arrow — one `malloc` for the closure, before an element is touched.
      */
    "while the spelling it replaced allocates once per call" in {
      allocations(
        """trait Folding
          |    folded(self, init: int, f: &Fn(int, int) -> int) -> int
          |impl Folding for []const int
          |    folded(self, init: int, f: &Fn(int, int) -> int) -> int
          |        var acc = init
          |        for i in 0..<self.len
          |            acc = f(acc, self[i])
          |        acc
          |val xs = [1, 2, 3]
          |print(xs[..].folded(0, (a, n) -> a + n))
          |""".stripMargin,
      ) shouldBe 1
    }

    "the answer is right either way, which is why only the code can say this" in {
      run(folding) shouldBe "6\n"
    }

    /** `map` allocates because it builds a sequence, which is inherent and is the one cost the
      * arrow does not remove — so the module's claim is "three of the ten", not "none".
      */
    "and a member that builds a sequence still allocates for the sequence" in {
      allocations(
        """import sysl.seq.Sequence
          |
          |val xs = [1, 2, 3]
          |
          |print(xs[..].map(n -> n * 2))
          |""".stripMargin,
      ) should be > 0
    }
  }
}
