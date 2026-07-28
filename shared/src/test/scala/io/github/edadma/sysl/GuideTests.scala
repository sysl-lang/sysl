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
}
