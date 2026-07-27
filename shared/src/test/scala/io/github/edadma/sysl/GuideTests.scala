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
}
