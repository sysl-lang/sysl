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
    "docs/content/reference/_index.md"                -> (0, 0, 0),
    "docs/content/reference/lexical.md"               -> (6, 0, 0),
    "docs/content/reference/types.md"                 -> (8, 0, 0),
    "docs/content/reference/expressions.md"           -> (16, 3, 0),
    "docs/content/reference/statements.md"            -> (13, 1, 1),
    "docs/content/reference/declarations.md"          -> (12, 2, 0),
    "docs/content/reference/patterns.md"              -> (8, 9, 0),
    "docs/content/reference/memory.md"                -> (21, 15, 0),
    "docs/content/reference/arrays.md"                -> (17, 9, 1),
    "docs/content/reference/traits.md"                -> (15, 12, 0),
    "docs/content/reference/generics.md"              -> (12, 7, 0),
    "docs/content/reference/modules.md"                -> (9, 9, 4),
    "docs/content/reference/errors.md"                 -> (19, 27, 1),
    "docs/content/reference/ffi.md"                    -> (12, 17, 3),
    "docs/content/reference/attributes.md"             -> (3, 11, 1),
    "docs/content/library/_index.md"                   -> (0, 0, 0),
    "docs/content/library/core.md"                     -> (13, 4, 8),
    "docs/content/library/text.md"                      -> (15, 6, 4),
    "docs/content/library/regex.md"                      -> (16, 0, 0),
    "docs/content/library/buf.md"                       -> (8, 6, 3),
    "docs/content/library/io.md"                        -> (4, 3, 2),
    "docs/content/library/fs.md"                        -> (6, 5, 3),
    "docs/content/library/math.md"                      -> (14, 9, 2),
    "docs/content/library/sync.md"                       -> (9, 7, 2),
    "docs/content/library/thread.md"                     -> (6, 6, 2),
    "docs/content/library/args.md"                        -> (4, 4, 1),
    "docs/content/library/sys.md"                          -> (4, 3, 1),
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
