package sh.sysl

import org.scalatest.{ParallelTestExecution, Suite}
import org.scalatest.freespec.AnyFreeSpec

/** The guide programs, compiled from `guide/` and run (see `guide/README.md`).
 *
 * Each one **checks itself**: every line it prints is either a section header or the word `ok`
 * followed by what was checked, so a failure is a line that says otherwise. This suite therefore
 * asserts three things about the whole of stdout — that nothing failed, that the *number* of checks
 * is the one expected, and that the sections ran in order. The count is what makes the first
 * assertion mean something: a check that quietly stopped running would otherwise look exactly like
 * a check that passed.
 *
 * Asserting the literal text of every line instead would move the program's expectations into this
 * file, where the code that produced them is not — and the round-trips in particular are only
 * legible next to the documents they are about.
 */
/** **Each guide is its own test, and they run at the same time.** A guide program is a real compile,
 * link and run of a few hundred lines. Nothing is shared between them — each compiles from its own
 * directory into its own temporary files — so the only thing that had made them sequential was the
 * runner taking one test at a time.
 */
class GuideTests extends AnyFreeSpec with GuideSupport with ParallelTestExecution {

  /** Only the JVM can supply this by reflection; on JS and Native it is abstract, so a suite that
   * runs its tests in parallel has to say how one of itself is made.
   */
  override def newInstance: Suite & ParallelTestExecution = new GuideTests

  private def checks(out: String): Int = out.linesIterator.count(_.startsWith("ok"))

  private def sections(out: String): List[String] = out.linesIterator.filter(_.startsWith("--")).toList

  // The one program whose subject is the checking rather than the computing: two implementations of
  // the same buffer, one keeping where the elements end and one computing it, driven through every
  // scenario side by side and required to agree. What this run cannot do is break a contract — a
  // violated `require` traps, so the program would end rather than report — and those claims are
  // the `@test` functions the case below runs.
  "ring — bounded indices, and an invariant that found a redundant field" in {
    val out = guide("ring")

    out should not include "FAIL"
    checks(out) shouldBe 100
    sections(out) shouldBe List(
      "-- the slot type, and the step it will not take",
      "-- an empty ring",
      "-- what goes in comes out in order",
      "-- filling it, and what a full ring refuses",
      "-- going round the end",
      "-- emptying",
      "-- a producer and a consumer over one ring",
      "-- two fields where ordering the writes is enough",
      "-- every state a ring of this size has",
    )
  }

  // The other half of the ring's evidence, and the half its own run cannot produce: a refusal
  // traps, and a trap ends the run rather than reporting into it. Each of these passes by not
  // coming back (`reference/attributes.md § What a test may be`). This was `RingClaimTests`, asserting the same claims from Scala
  // against cut-down copies of the ring's shapes; it is now stated in sysl against the shapes
  // themselves, which is what `@test(should_trap)` bought.
  //
  // The count is asserted for the reason the check counts above are: a `should_trap` test that
  // stopped being compiled would look exactly like one that passed, since there would be no failing
  // line to see. What keeps the traps themselves honest is that **every refusal is written beside
  // the call that is not refused** — the ordinary tests build the same fixtures and *return*, so a
  // trap that fired while a ring was being set up would fail those rather than quietly satisfying
  // the ones that expect it.
  "ring — the refusals, which the program's own run cannot assert" in {
    guideTests("ring") should have length 19
  }

  // The one program that *makes* storage rather than being handed it, and it is written with no
  // allocator itself — which is what puts the free list inside the free blocks and the whole
  // program in the raw tier.
  "slab — raw storage, an intrusive free list, and blocks measured rather than written down" in {
    val out = guide("slab")

    out should not include "FAIL"
    checks(out) shouldBe 37
    sections(out) shouldBe List(
      "-- what a block costs",
      "-- carving a region into blocks",
      "-- the free list lives in the free blocks themselves",
      "-- allocating and releasing",
      "-- a released block is the one handed out next",
      "-- exhaustion answers rather than trapping",
      "-- the alignment an allocator rounds up to anyway",
    )
  }
}
