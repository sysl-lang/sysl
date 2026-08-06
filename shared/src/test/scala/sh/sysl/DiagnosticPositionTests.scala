package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** Where a caret lands, for the diagnostics that name a thing rather than a construct.
 *
 * **The rule this suite pins: a message that names an identifier points at that identifier.** A
 * caret under the punctuation beside it sends the reader one column away from the word they were
 * just told about — and where a single mistake produces several of these, as one `val` bound to a
 * mutable object does, the reader is looking in the wrong place several times.
 *
 * It is **not** a rule about every diagnostic. An expression's position is its start, deliberately,
 * which is why `1 + "x"` points at the `1` and why an `if` keeps the column of its keyword
 * (`DiagnosticTests` pins that one). What is asserted here is narrower: where the message says a
 * name, the caret is under that name.
 */
class DiagnosticPositionTests extends AnyFreeSpec with CodegenSupport {

  /** The 1-based column the caret points at in the source line above it. */
  private def column(src: String): Int = {
    val rendered = err(src)
    val loc      = rendered.linesIterator.find(_.trim.startsWith("-->")).getOrElse(fail(rendered))

    loc.trim.split(":").last.toInt
  }

  "a message naming a member points at the member" - {

    // The case that prompted the rule: one `val` bound to a mutable object produces one of these per
    // mutating call, and every one used to point at a `.`.
    "a receiver that may not be written through" in {
      val src = "struct C\n    n: int\n\n    bump(*self) = self.n += 1\nend C\n\nval c: C = C(0)\nc.bump()"

      // `c.bump()` is the last line; `bump` starts at column 3.
      column(src) shouldBe 3
    }

    "a field that is not there" in {
      val src = "struct S\n    n: int\nend S\n\nvar s = S(1)\nprint(s.missing)"

      // `missing` starts at column 9 of `print(s.missing)`.
      column(src) shouldBe 9
    }

    "and a method that is not there" in {
      val src = "var n = 1\nprint(n.nope())"

      column(src) shouldBe 9
    }
  }

  "a message about what was written between brackets points there" - {

    "an index of the wrong type" in {
      val src = "var a = [1, 2, 3]\nprint(a[\"x\"])"

      // The `"x"` starts at column 9; the `[` it used to point at is column 8.
      column(src) shouldBe 9
    }
  }

  "what deliberately keeps the older convention" - {

    // An expression's position is its start. This is the case the rule above does *not* extend to,
    // and it is asserted so that a later change to the member rule does not quietly take this with
    // it — `1 + "x"` naming `'+'` while pointing at the `1` is a decision, recorded in a ticket.
    "a binary operator's mismatch points at the start of the expression" in {
      val src = "print(1 + \"x\")"

      column(src) shouldBe 7
    }
  }
}
