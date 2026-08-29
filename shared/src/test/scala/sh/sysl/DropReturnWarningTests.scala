package sh.sysl

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

/** The compiler's first **positioned warning**, and the rule it exists for (card `0372`).
 *
 * `impl Drop for T` is dead code unless something hands back a `&T` — a destructor fires when a
 * *box's* strong count reaches zero — so a constructor declared `handle() -> Result[Handle, Error]`
 * leaks the resource on every call, with every test green and every answer correct. It was found
 * leaking three shipped packages at once: `brotli` 26 GB over 50,000 iterations against 4.8 MB
 * fixed, `hiredis` 38 MB, and `libpq`'s failed-connect path 738 MB.
 *
 * **These assert on `Compiled.warnings` rather than on printed text**, which is the whole reason the
 * diagnostic carries a severity rather than the driver printing a string: a warning is data, the
 * same as an error, and a test that matched a console line would be testing `Main`.
 */
class DropReturnWarningTests extends AnyFreeSpec with Matchers {

  private val dropping =
    """struct Thing
      |    n: int
      |
      |impl Drop for Thing
      |    drop(self) = print(s"drop ${self.n}")
      |""".stripMargin

  /** The warnings one program raises. A compilation that *fails* has none to report — the left is
   * the errors — so a `Left` is a broken fixture rather than an answer, and it says so.
   */
  private def warningsOf(src: String): List[Diagnostic] =
    Compiler.compiled(List(Source("<input>", src))) match
      case Right(out) => out.warnings
      case Left(e)    => fail(s"expected a compilation, got:\n$e")

  "a declaration handing back a Drop type by value is warned about" - {

    // The card's own reduction. `Result[Thing, string]` carries the `Thing` by value, so the box
    // that would have run the destructor is never made.
    "through a Result, which is the shape a constructor actually has" in {
      val w = warningsOf(dropping + "make(n: int) -> Result[Thing, string] = Ok(Thing(n))\nprint(1)\n")

      w.map(_.message) should have length 1
      w.head.message should include("'make' hands back 'Thing' by value")
      w.head.message should include("Return '&Thing' instead")
      w.head.severity shouldBe Severity.Warning
    }

    // Bare, with no generic in the way, so the rule is not read as being about `Result`.
    "and bare, which is the same claim with nothing wrapping it" in {
      warningsOf(dropping + "make(n: int) -> Thing = Thing(n)\nprint(1)\n") should have length 1
    }

    // An `Option` is the other prelude enum a constructor answers with.
    "and through an Option" in {
      warningsOf(dropping + "make(n: int) -> Option[Thing] = Some(Thing(n))\nprint(1)\n") should
        have length 1
    }
  }

  "and one handing back a box is not" - {

    // The fix the message names, which is the case that must be silent or the warning is noise.
    "because a '&T' is what a destructor is about" in {
      warningsOf(dropping + "make(n: int) -> Result[&Thing, string] = Ok(Thing(n))\nprint(1)\n") shouldBe
        empty
    }

    "bare, as well as through a Result" in {
      warningsOf(dropping + "make(n: int) -> &Thing = Thing(n)\nprint(1)\n") shouldBe empty
    }
  }

  "a type with no destructor is never mentioned" in {
    warningsOf(
      """struct Plain
        |    n: int
        |make(n: int) -> Result[Plain, string] = Ok(Plain(n))
        |print(1)
        |""".stripMargin) shouldBe empty
  }

  // The `drop` a `Drop` block declares takes `self` **by value** and is the one member the rule is
  // not about — a rule that fired on it would fire on every correct `Drop` in existence.
  "the 'drop' member itself is never warned about" in {
    warningsOf(dropping + "print(1)\n") shouldBe empty
  }

  // A raw pointer owns nothing and never claimed to, so a binding handing one back is making no
  // claim about who frees it. This is the opposite reason from `&T`'s and lands in the same place.
  "a raw pointer is not a claim about ownership, so it is not warned about" in {
    warningsOf(dropping + "make(p: *Thing) -> *Thing = p\nprint(1)\n") shouldBe empty
  }

  // A container of them is a real question and a different one: widening the rule to slices would
  // fire on every buffer of handles a program legitimately manages itself.
  "a slice of them is left alone, which is a decision rather than an oversight" in {
    warningsOf(dropping + "first(xs: []Thing) -> []Thing = xs\nprint(1)\n") shouldBe empty
  }

  // The position is what makes a warning worth having over a note: it names the declaration rather
  // than the type, because the declaration is what has to change.
  "the warning points at the declaration, which is what has to change" in {
    val w = warningsOf(dropping + "make(n: int) -> Thing = Thing(n)\nprint(1)\n").head

    w.pos.map(_.line) shouldBe Some(6)
    w.rendered should startWith("warning: ")
  }

  // A compilation that warns still succeeds — that is the whole difference between the two
  // severities, and a rule that stopped a build would have to be right every time.
  "a warning does not fail the compilation" in {
    Compiler.compileToLlvm(dropping + "make(n: int) -> Thing = Thing(n)\nprint(1)\n") should be a Symbol("right")
  }
}
