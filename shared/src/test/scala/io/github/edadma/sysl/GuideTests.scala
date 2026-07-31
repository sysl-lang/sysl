package io.github.edadma.sysl

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
class GuideTests extends AnyFreeSpec with GuideSupport {

  private def checks(out: String): Int = out.linesIterator.count(_.startsWith("ok"))

  private def sections(out: String): List[String] = out.linesIterator.filter(_.startsWith("--")).toList

  "json — recursive ownership" in {
    val out = guide("json")

    out should not include "FAIL"
    checks(out) shouldBe 49
    sections(out) shouldBe List(
      "-- scalars",
      "-- structures",
      "-- whitespace and escapes are normalized",
      "-- escapes that survive a round trip",
      "-- a tree deep enough that the walk is the point",
      "-- malformed input",
    )
  }

  "hashmap — the trait system under load" in {
    val out = guide("hashmap")

    out should not include "FAIL"
    checks(out) shouldBe 53
    sections(out) shouldBe List(
      "-- string keys",
      "-- replacing",
      "-- removing",
      "-- integer keys",
      "-- a key of one's own",
      "-- every key in one bucket",
      "-- growth",
      "-- walking",
      "-- entries that own what they hold",
      "-- emptying",
    )
  }

  // The only guide program of more than one module, and the only one whose assertion is end to
  // end: source text in, bytecode out, the machine runs it, and what it printed is compared.
  "bytecode — a compiler and a machine over one instruction set" in {
    val out = guide("bytecode")

    out should not include "FAIL"
    checks(out) shouldBe 72
    sections(out) shouldBe List(
      "-- expressions",
      "-- variables",
      "-- control flow",
      "-- programs",
      "-- the instruction set",
      "-- what the compiler emits",
      "-- source the compiler refuses",
      "-- bytecode the machine refuses",
      "-- programs the machine stops",
      "-- limits",
    )
  }

  // Everything this one checks was computed by somebody else: the fixtures came out of a different
  // encoder and the checksum vectors are the published ones, which is the only way a decoder's
  // tests mean anything.
  "png — the byte level" in {
    val out = guide("png")

    out should not include "FAIL"
    // 82 rather than the 84 this once had: `decode` sizes its own intermediate buffers now, so the
    // two checks that made a caller supply one too small have nothing left to provoke.
    checks(out) shouldBe 82
    sections(out) shouldBe List(
      "-- checksums",
      "-- deflate",
      "-- streams the decoder refuses",
      "-- headers",
      "-- pixels",
      "-- filters",
      "-- chunks",
      "-- files the reader refuses",
    )
  }

  // The one program in the set that defines what `+` and `*` mean and then leans on the answer a
  // few hundred thousand times. Nothing it checks was computed by itself: the transforms of an
  // impulse, a constant and a cosine are known in closed form, four bins can be done on paper, and
  // the rest came out of numpy.
  "fft — arithmetic on a type of the program's own, and floats" in {
    val out = guide("fft")

    out should not include "FAIL"
    checks(out) shouldBe 102
    sections(out) shouldBe List(
      "-- complex arithmetic",
      "-- a value that renders its own parts",
      "-- transforms you can do in your head",
      "-- the fast transform against the definition",
      "-- bins somebody else computed",
      "-- a cosine lands in two bins",
      "-- identities the transform has to obey",
      "-- there and back again",
      "-- convolution, two ways",
      "-- the strongest bins",
      "-- what the transform refuses",
    )
  }

  // Four digests out of one body, so the assertions are the published ones twice over: NIST's
  // examples and hashlib for the digests, RFC 4231 for the tags. The fifteen message lengths are
  // the ones an implementation that is wrong is wrong at — either side of both widths' points where
  // the length field stops fitting in the last block.
  "sha2 — one algorithm at two widths, and static tables" in {
    val out = guide("sha2")

    out should not include "FAIL"
    checks(out) shouldBe 161
    sections(out) shouldBe List(
      "-- the four digests of one message",
      "-- the messages the standard uses",
      "-- padding at every boundary",
      "-- the same bytes, differently divided",
      "-- a million bytes in a thousand pieces",
      "-- the pieces both widths are built from",
      "-- what the four do not share",
      "-- keyed hashing",
      "-- what a hasher refuses",
    )
  }

  // The only program in the set that does not know the type of what it is computing with. Every
  // section drives at least two implementations through one call site, and thirty-six of the checks
  // are one law — scaling multiplies area by the square of the factor and perimeter by the factor —
  // asserted against every implementation in the catalogue rather than against a number.
  "shapes — a heterogeneous catalogue behind one trait" in {
    val out = guide("shapes")

    out should not include "FAIL"
    checks(out) shouldBe 90
    sections(out) shouldBe List(
      "-- each shape answers for itself",
      "-- one call site, many implementations",
      "-- a shape made of shapes",
      "-- a shape that wraps a shape",
      "-- the law every shape obeys",
      "-- counted and raw",
      "-- what an object keeps and what it forgets",
      "-- objects made and dropped",
    )
  }

  // The one program whose assertions are almost all one string: a **schedule**, a letter per tick.
  // Every schedule below was worked out on paper before the program was run, which is what makes it
  // an assertion rather than a recording — and the two priority-inversion sections are the same
  // three tasks and the same locks run twice with one switch changed, so the pair of schedules is
  // the whole claim about what priority inheritance does.
  "scheduler — a run queue, priorities, and the locks that reorder them" in {
    val out = guide("scheduler")

    out should not include "FAIL"
    checks(out) shouldBe 106
    sections(out) shouldBe List(
      "-- the heap keeps its shape",
      "-- a task's place follows it",
      "-- one task, then two",
      "-- the most urgent ready task runs",
      "-- arriving in the middle",
      "-- a lock somebody else holds",
      "-- priority inversion",
      "-- what inheritance does about it",
      "-- a lock several tasks want",
      "-- sleeping, and time nobody uses",
      "-- two tasks that will not finish",
      "-- what a task gets wrong",
      "-- many tasks at once",
      "-- the graph comes apart",
    )
  }

  // The same machine as `scheduler`, with no heap: a fixed table, index numbers for identity, and
  // intrusive lists threaded through the tasks. The shared scenarios assert byte-identical
  // schedules, so the two programs check each other and the diff between them is the measurement.
  "kernel — the same scheduler with a fixed table and no allocation at all" in {
    val out = guide("kernel")

    out should not include "FAIL"
    checks(out) shouldBe 160
    sections(out) shouldBe List(
      "-- finding the most urgent level",
      "-- the bitmap keeps its levels",
      "-- a task's place follows it",
      "-- taking a task out of a level",
      "-- one task, then two",
      "-- the most urgent ready task runs",
      "-- arriving in the middle",
      "-- a lock somebody else holds",
      "-- priority inversion",
      "-- what inheritance does about it",
      "-- a lock several tasks want",
      "-- a task holding two locks",
      "-- sleeping, and time nobody uses",
      "-- two tasks that will not finish",
      "-- what a task gets wrong",
      "-- the table has an edge",
      "-- many tasks at once",
      "-- the links come apart",
    )
  }

  // The one program whose expected values all came out of a *different* implementation of the same
  // subject — the transitions, the offsets and every meeting time were computed with Python's
  // `zoneinfo` against the real tz database, so the checks are a cross-check and not a recording.
  // Two of them are the point of the whole program: a week of calendar is 167 hours of timeline in
  // the spring and 169 in the autumn, and neither of those is a bug.
  "datetime — wall clocks, timelines, and the conversion between them" in {
    val out = guide("datetime")

    out should not include "FAIL"
    checks(out) shouldBe 158
    sections(out) shouldBe List(
      "-- instants and durations",
      "-- days and civil dates",
      "-- the day of the week",
      "-- a time of day",
      "-- when the clocks move",
      "-- an instant seen from a wall",
      "-- the offset in force",
      "-- a reading that happens once",
      "-- a reading that never happens",
      "-- a reading that happens twice",
      "-- next week, and one hundred and sixty-eight hours",
      "-- six weeks of one meeting",
      "-- who is awake",
      "-- every hour of a year",
      "-- how much of a year is not a moment",
      "-- the table has ends",
      "-- a meeting that lands where the clock does not",
      "-- the edges of the arithmetic",
    )
  }

  // The one program whose subject is the checking rather than the computing: two implementations of
  // the same buffer, one keeping where the elements end and one computing it, driven through every
  // scenario side by side and required to agree. What this run cannot do is break a contract — a
  // violated `require` traps, so the program would end rather than report — and those claims are
  // the `#test` functions the case below runs.
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
  // coming back (`testing.md`). This was `RingClaimTests`, asserting the same claims from Scala
  // against cut-down copies of the ring's shapes; it is now stated in sysl against the shapes
  // themselves, which is what `#test(should_trap)` bought.
  //
  // The count is asserted for the reason the check counts above are: a `should_trap` test that
  // stopped being compiled would look exactly like one that passed, since there would be no failing
  // line to see. What keeps the traps themselves honest is that **every refusal is written beside
  // the call that is not refused** — the ordinary tests build the same fixtures and *return*, so a
  // trap that fired while a ring was being set up would fail those rather than quietly satisfying
  // the ones that expect it.
  "ring — the refusals, which the program's own run cannot assert" in {
    guideTests("ring") should have length 18
  }

  // The one program that *makes* storage rather than being handed it. `guide/kernel` is
  // allocator-free and indexes three fixed tables it was given; this is what a program would have to
  // write before it could have them, so it is written with no allocator itself — which is what puts
  // the free list inside the free blocks and the whole program in the raw tier.
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
      "-- the alignment the language cannot ask for",
    )
  }

  "matrix — an operator whose result is neither operand's type" in {
    val out = guide("matrix")

    out should not include "FAIL"
    checks(out) shouldBe 47
    sections(out) shouldBe List(
      "-- the four products of a vector space",
      "-- the algebra holds where the results differ",
      "-- indexing through a trait",
      "-- solving a system",
      "-- determinant and rank",
      "-- inverse",
      "-- a system big enough that the walk is the point",
      "-- sharing, and the copy that ends it",
    )
  }
}
