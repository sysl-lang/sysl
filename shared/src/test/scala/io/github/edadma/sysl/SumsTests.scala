package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

/** `sysl.sum` — what a resolved package's source was when it was first seen (`packages.md § 6`).
 *
 * Reading is a pure function of the file's text, as `package.hocon`'s is, so every case here runs
 * identically on all three platforms and none of them needs a package to exist.
 */
class SumsTests extends AnyFreeSpec with Matchers {

  private val one = s"${Hashing.Prefix}${"a" * 64}"
  private val two = s"${Hashing.Prefix}${"b" * 64}"

  private def read(text: String): Sums =
    Sums.read(text) match
      case Right(s) => s
      case Left(e)  => fail(s"expected sums, got: $e")

  private def refused(text: String): String =
    Sums.read(text) match
      case Left(e)  => e
      case Right(s) => fail(s"expected a refusal, got: $s")

  "a file that records two packages" in {
    val sums = read(s"github.com/e/json v1.4.0 $one\ngithub.com/e/regex v0.4.0 $two\n")

    sums.hashOf("github.com/e/json", Version(1, 4, 0)) shouldBe Some(one)
    sums.hashOf("github.com/e/regex", Version(0, 4, 0)) shouldBe Some(two)
  }

  "a package no line covers" in {
    read(s"github.com/e/json v1.4.0 $one\n").hashOf("github.com/e/json", Version(1, 5, 0)) shouldBe None
  }

  "an empty file records nothing" in {
    read("") shouldBe Sums.empty
  }

  "blank lines are not entries" in {
    read(s"\ngithub.com/e/json v1.4.0 $one\n\n").entries should have size 1
  }

  // Sorted and one per line, so that two runs resolving the same graph write the same bytes and a
  // diff shows what changed rather than how a map was iterated.
  "what is written back is stable" in {
    val text = s"github.com/e/json v1.4.0 $one\ngithub.com/e/regex v0.4.0 $two\n"

    read(text).render shouldBe text
    read(s"github.com/e/regex v0.4.0 $two\ngithub.com/e/json v1.4.0 $one\n").render shouldBe text
  }

  "a version's entries sort by version and not by their text" in {
    val ten   = s"github.com/e/j v1.10.0 $one"
    val point = s"github.com/e/j v1.2.0 $two"

    read(s"$ten\n$point\n").render shouldBe s"$point\n$ten\n"
  }

  "recording a package that was not there" in {
    Sums.empty.recording("github.com/e/json", Version(1, 4, 0), one).render shouldBe
      s"github.com/e/json v1.4.0 $one\n"
  }

  // The file exists to refuse things. One that skipped what it could not read would answer "no
  // entry covers this package" for a package it was carrying an entry for.
  "a line that cannot be read is an error rather than a line skipped" - {

    "too few fields" in {
      refused("github.com/e/json v1.4.0") should include("a coordinate, a version tag and a digest")
    }

    "a version with no tag on it" in {
      refused(s"github.com/e/json 1.4.0 $one") should include("is not a version tag")
    }

    "a version tag that is not a version" in {
      refused(s"github.com/e/json vlatest $one") should include("is not a version")
    }

    "a digest that names no algorithm" in {
      refused(s"github.com/e/json v1.4.0 ${"a" * 64}") should include("is not a sha256: digest")
    }

    "a digest of the wrong length" in {
      refused(s"github.com/e/json v1.4.0 ${Hashing.Prefix}abc") should include("is not a sha256: digest")
    }

    // Badly merged, or edited by hand. There is no reading of it under which one of the two is the
    // answer, so neither is taken.
    "one package recorded twice, differently" in {
      refused(s"github.com/e/json v1.4.0 $one\ngithub.com/e/json v1.4.0 $two\n") should
        include("two different digests")
    }

    "one package recorded twice, identically, is not a disagreement" in {
      read(s"github.com/e/json v1.4.0 $one\ngithub.com/e/json v1.4.0 $one\n").entries should have size 1
    }
  }
}
