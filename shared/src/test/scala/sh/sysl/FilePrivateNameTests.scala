package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** Two files of one module may each declare the same **file-private** name
 * (`reference/modules.md § Visibility`).
 *
 * `private` in sysl is private to the file, and until 2026-08-27 it restricted the *reach* of a name
 * without restricting the *namespace*: a second file declaring its own `Limit` was refused as a
 * duplicate of a name it could not have named anyway. That defeated the thing file-privacy is for —
 * the reason to keep a helper to its file is that `Limit`, `helper`, `check` are local matters — so
 * a module of any size grew `MaxCallDepth` beside `MaxHashDepth` for two bounds that were each one
 * file's business. Rust, C and Go all scope the name as well as the reach.
 *
 * **What stays refused is a private name against a PUBLIC one of the same spelling**, which is a
 * genuine ambiguity for the sibling file's own references, and a second declaration of one spelling
 * inside a single file, which is the ordinary duplicate this never meant to allow.
 */
class FilePrivateNameTests extends AnyFreeSpec with CodegenSupport with RunSupport {

  "two files may each keep a name to themselves" - {

    "a constant" in {
      runIn(
        ("", "main.sysl", "print(m.from_one(), m.from_two())"),
        ("m", "one.sysl",
         """module m
           |private const Limit: int = 1
           |from_one() -> int = Limit
           |""".stripMargin),
        ("m", "two.sysl",
         """module m
           |private const Limit: int = 2
           |from_two() -> int = Limit
           |""".stripMargin),
      ) shouldBe "1 2\n"
    }

    // The card's own case: two files of a module each bounding how deep something walks, each
    // wanting to call the bound what it is.
    "a function, which is the case the card was filed from" in {
      runIn(
        ("", "main.sysl", "print(m.from_one(), m.from_two())"),
        ("m", "one.sysl",
         """module m
           |private helper() -> int = 1
           |from_one() -> int = helper()
           |""".stripMargin),
        ("m", "two.sysl",
         """module m
           |private helper() -> int = 2
           |from_two() -> int = helper()
           |""".stripMargin),
      ) shouldBe "1 2\n"
    }

    "a module 'val'" in {
      runIn(
        ("", "main.sysl", "print(m.from_one(), m.from_two())"),
        ("m", "one.sysl",
         """module m
           |private val base: int = 10
           |from_one() -> int = base
           |""".stripMargin),
        ("m", "two.sysl",
         """module m
           |private val base: int = 20
           |from_two() -> int = base
           |""".stripMargin),
      ) shouldBe "10 20\n"
    }

    "a struct" in {
      runIn(
        ("", "main.sysl", "print(m.from_one(), m.from_two())"),
        ("m", "one.sysl",
         """module m
           |private struct Cell
           |    n: int
           |from_one() -> int = Cell(1).n
           |""".stripMargin),
        ("m", "two.sysl",
         """module m
           |private struct Cell
           |    s: int
           |    t: int
           |from_two() -> int = Cell(2, 3).t
           |""".stripMargin),
      ) shouldBe "1 3\n"
    }

    // Each file's own declaration is what its bodies see — the assertion the two above would still
    // pass if both files somehow shared one declaration, so it is made on its own.
    "and each file names its own, not the other's" in {
      runIn(
        ("", "main.sysl", "print(m.one_says(), m.two_says())"),
        ("m", "one.sysl",
         """module m
           |private const Which: int = 111
           |one_says() -> int = Which
           |""".stripMargin),
        ("m", "two.sysl",
         """module m
           |private const Which: int = 222
           |two_says() -> int = Which
           |""".stripMargin),
      ) shouldBe "111 222\n"
    }
  }

  "what is still refused" - {

    // The sibling file's own references would have two answers with nothing to tell them apart, so
    // this pairing is a real ambiguity rather than the separable case.
    "a private name against a public one of the same spelling" in {
      errIn(
        ("", "main.sysl", "print(1)"),
        ("m", "one.sysl",
         """module m
           |private const Limit: int = 1
           |""".stripMargin),
        ("m", "two.sysl",
         """module m
           |const Limit: int = 2
           |""".stripMargin),
      ) should include("already declared")
    }

    "and one file declaring the same private name twice, which is the ordinary duplicate" in {
      errIn(
        ("", "main.sysl", "print(1)"),
        ("m", "one.sysl",
         """module m
           |private const Limit: int = 1
           |private const Limit: int = 2
           |""".stripMargin),
      ) should include("already declared")
    }

    // Reach is untouched by any of this: a name scoped to its file is still unreachable from
    // outside it, which is what `private` was always buying.
    "and a sibling file still cannot name the other's private declaration" in {
      errIn(
        ("", "main.sysl", "print(1)"),
        ("m", "one.sysl",
         """module m
           |private const Limit: int = 1
           |""".stripMargin),
        ("m", "two.sysl",
         """module m
           |borrow() -> int = Limit
           |""".stripMargin),
      ) should include("Limit")
    }
  }
}
