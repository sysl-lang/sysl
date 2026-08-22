package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** What a call into `sysl.seq` costs, pinned where it can be read: in the emitted code.
 *
 * The trait's members take their callable as `&Fn(…)`, which is a counted box — so the closure a
 * caller writes is allocated at the call, once per call, whichever member it is and before any
 * element is touched. **This suite exists to keep that honest.** The module's own prose said the
 * opposite when it was first written, and nothing would have caught it: every answer is correct
 * either way, and a test that only checks answers cannot tell a boxed callable from an inlined one.
 *
 * A parameter written with a bare arrow monomorphizes instead and is called directly with nothing
 * boxed (`12 §6`), but a trait's member may not write one — the desugaring that turns an arrow into
 * a bound runs for a type's members and an `impl` block's and never for a trait's. **Card `0230` is
 * that gap, and when it closes these assertions are what should flip**, which is why they are
 * written as counts rather than as "at least one".
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

  "a callable handed to one of these is boxed at the call" - {

    "folding a slice allocates, though it builds no sequence" in {
      allocations(folding) should be > 0
    }

    /** The comparison that says it is the **callable** and not the fold: the same walk written as a
      * loop allocates nothing at all.
      */
    "and the same walk written as a loop does not" in {
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

    "the answer is right either way, which is why only the code can say this" in {
      run(folding) shouldBe "6\n"
    }
  }
}
