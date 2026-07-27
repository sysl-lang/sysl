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
    checks(out) shouldBe 84
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
}
