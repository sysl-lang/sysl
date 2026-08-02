package io.github.edadma.sysl

import io.github.edadma.cross_platform.*

import org.scalatest.freespec.AnyFreeSpec

/** The documentation site's programs, compiled and run out of the markdown a reader is looking at
 * (see `DocsSupport`).
 *
 * Two things are asserted, and the second is what makes the first mean anything. Every program on a
 * page does what the page says it does — and the **number** of programs on each page is the number
 * written down here. A fence that stopped being recognised, a page that lost its `output` block in
 * an edit, a chapter quietly reduced to prose: each of those turns a checked program into an
 * unchecked one, and without the count they all look exactly like a page whose programs pass.
 *
 * That is the same reason `GuideTests` counts the `ok` lines rather than only looking for `FAIL`,
 * and it was learned there the hard way.
 */
class DocsTests extends AnyFreeSpec with DocsSupport {

  /** How many `sysl` blocks of each kind a page carries: runnable programs, refusals, fragments.
   *
   * Written here rather than derived, because deriving it from the page would assert that the page
   * agrees with itself — which it always does.
   */
  private val expected: Map[String, (Int, Int, Int)] = Map(
    "docs/content/_index.md"                          -> (0, 0, 1),
    "docs/content/getting-started/_index.md"          -> (0, 0, 0),
    "docs/content/getting-started/installation.md"    -> (0, 0, 0),
    "docs/content/getting-started/first-program.md"   -> (1, 0, 1),
    "docs/content/tour/_index.md"                     -> (0, 0, 0),
    "docs/content/tour/values.md"                     -> (6, 1, 0),
    "docs/content/tour/control-flow.md"               -> (9, 0, 0),
    "docs/content/tour/functions.md"                  -> (10, 0, 0),
    "docs/content/tour/structs.md"                    -> (5, 0, 0),
    "docs/content/tour/memory.md"                     -> (9, 3, 0),
    "docs/content/tour/arrays.md"                     -> (9, 1, 0),
    "docs/content/tour/strings.md"                    -> (10, 1, 0),
    "docs/content/tour/enums.md"                      -> (10, 2, 0),
    "docs/content/tour/errors.md"                     -> (6, 1, 2),
    "docs/content/tour/traits.md"                     -> (8, 1, 0),
    "docs/content/tour/modules.md"                    -> (4, 0, 7),
    "docs/content/tour/contracts.md"                  -> (6, 2, 0),
    "docs/content/tour/capstone.md"                   -> (1, 0, 1),
  )

  "every page on the site is accounted for" in {
    assume(isDirectory("docs/content"), "the docs tree is not reachable from the working directory")

    // A new page with no entry is not a failure of that page — it is a page nobody decided the
    // shape of, and the decision is the point. Left to default, an unlisted page would contribute
    // its programs to the run and none of the counts that keep them honest.
    pages.toSet shouldBe expected.keySet
  }

  "the programs on each page do what the page says" in {
    assume(Toolchain.clangAvailable, "clang not available")
    assume(isDirectory("docs/content"), "the docs tree is not reachable from the working directory")

    val complaints = pages.flatMap(snippets).flatMap(check)

    if complaints.nonEmpty then fail(complaints.mkString("\n\n"))
  }

  "each page carries the programs it is supposed to" in {
    assume(isDirectory("docs/content"), "the docs tree is not reachable from the working directory")

    val counted = pages.map { page =>
      val found = snippets(page)

      page -> (
        found.count(_.claim.isInstanceOf[Claim.Prints]),
        found.count(_.claim.isInstanceOf[Claim.Refused]),
        found.count(_.claim == Claim.Fragment),
      )
    }.toMap

    counted shouldBe expected
  }
}
