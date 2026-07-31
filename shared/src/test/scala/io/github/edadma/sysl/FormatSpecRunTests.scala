package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** Tier-2 runtime behaviour of a `FormatSpec` once it reaches a renderer (`14 §2`, `§8 d`).
 *
 * The spec has always been *delivered* — an `f"…"` hole hands its width, precision, and
 * justification to the `Display` it calls. What is under test here is that the library's own
 * renderers now **act** on it, so a width means the same thing whether the value is an integer the
 * compiler renders or a struct that renders itself.
 *
 * Every case drives the spec through a wrapper that forwards it, because that is the only way one
 * reaches a built-in's renderer: `%s` on a bare integer is still the mistake it was, and a hole
 * whose value renders itself is the path a specifier travels.
 */
class FormatSpecRunTests extends AnyFreeSpec with RunSupport {

  /** A wrapper whose whole rendering is one field's, so forwarding the specifier down is correct
   * rather than merely convenient — the field's text *is* the value's text.
   */
  private def wrap(ty: String, expr: String) =
    s"""struct W
       |    n: $ty
       |impl Display for W
       |    display(self, out: *Writer, fmt: FormatSpec) = self.n.display(out, fmt)
       |var w = W($expr)
       |""".stripMargin

  private def int(expr: String)  = wrap("int", expr)
  private def text(expr: String) = wrap("string", expr)

  "a width" - {
    "right-justifies by default" in {
      run(int("7") + """print(f"[${w}%6s]")""") shouldBe "[     7]\n"
    }

    "left-justifies when the specifier says so" in {
      run(int("7") + """print(f"[${w}%-6s]")""") shouldBe "[7     ]\n"
    }

    // A width is a *minimum*: printf never truncates to it, and neither does this.
    "narrower than the text changes nothing" in {
      run(int("1234567") + """print(f"[${w}%3s]")""") shouldBe "[1234567]\n"
    }

    "equal to the text pads by nothing" in {
      run(int("123") + """print(f"[${w}%3s]")""") shouldBe "[123]\n"
    }

    // Wider than the fill buffer, so the padding loop has run several times and the last chunk is
    // a partial one — 40 spaces is two full passes and a remainder.
    "far wider than the buffer the padding is written from" in {
      run(int("7") + """print(f"[${w}%41s]")""") shouldBe s"[${" " * 40}7]\n"
    }

    // The sign belongs to the number, so it is inside the field rather than beside it.
    "counts a negative number's sign" in {
      run(int("-42") + """print(f"[${w}%6s]")""") shouldBe "[   -42]\n"
    }

    "counts bytes, not characters" in {
      run(text("\"é\"") + """print(f"[${w}%4s]")""") shouldBe "[  é]\n"
    }
  }

  "a precision" - {
    // On a number it is a minimum digit count, as printf's is: the zeros go after the sign, and
    // the width is then measured over the whole thing.
    "fills a number out to a minimum digit count" in {
      run(int("42") + """print(f"[${w}%.5s]")""") shouldBe "[00042]\n"
    }

    "puts its zeros after a minus sign, inside the field" in {
      run(int("-42") + """print(f"[${w}%8.5s]")""") shouldBe "[  -00042]\n"
    }

    "shorter than the number changes nothing" in {
      run(int("12345") + """print(f"[${w}%.2s]")""") shouldBe "[12345]\n"
    }

    "truncates text" in {
      run(text("\"hello\"") + """print(f"[${w}%.3s]")""") shouldBe "[hel]\n"
    }

    "longer than the text leaves it whole" in {
      run(text("\"hi\"") + """print(f"[${w}%.9s]")""") shouldBe "[hi]\n"
    }

    // Truncation counts bytes, as C's does — but it backs off to a character boundary rather than
    // writing half of one, since a sink that is handed invalid UTF-8 has no way to recover.
    "never splits a character" in {
      run(text("\"héllo\"") +
        """print(f"[${w}%.3s]")
          |print(f"[${w}%.2s]")""".stripMargin) shouldBe "[hé]\n[h]\n"
    }

    "combines with a width, which measures the truncated text" in {
      run(text("\"hello\"") + """print(f"[${w}%6.3s]")""") shouldBe "[   hel]\n"
    }
  }

  "a real" - {
    "renders as it always did when no precision is written" in {
      run(wrap("real", "2.5") + """print(f"[${w}%8s]")""") shouldBe "[     2.5]\n"
    }

    "reads a precision as significant digits, the conversion it renders through" in {
      run(wrap("real", "1.0 / 3.0") + """print(f"[${w}%.3s]")""") shouldBe "[0.333]\n"
    }

    // A precision is written by the programmer and the scratch buffer is fixed, so an absurd one is
    // clamped rather than allowed to overrun it. Forty significant digits is well past the
    // seventeen a double carries: what the tail shows is the binary value's own decimal expansion,
    // which is why clamping there loses nothing a program could have meant.
    "clamps a precision no double carries information at" in {
      run(wrap("real", "1.0 / 3.0") + """print(f"[${w}%.200s]")""") shouldBe
        "[0.3333333333333333148296162562473909929395]\n"
    }
  }

  "other renderings" - {
    "an unsigned number pads like a signed one" in {
      run(wrap("u32", "7u32") + """print(f"[${w}%.3s]")""") shouldBe "[007]\n"
    }

    "a bool pads and truncates as the text it is" in {
      run(wrap("bool", "true") +
        """print(f"[${w}%6s]")
          |print(f"[${w}%.2s]")""".stripMargin) shouldBe "[  true]\n[tr]\n"
    }

    "a char pads" in {
      run(wrap("char", "'x'") + """print(f"[${w}%3s]")""") shouldBe "[  x]\n"
    }
  }

  "the padding is written, not conjured" - {
    // The spaces go out through the sink like every other byte, so a writer of the program's own
    // sees them. If padding were applied around the call instead, this counter would read 1.
    "reaches a writer the program wrote itself" in {
      run("""struct Counter
            |    n: usize
            |impl Writer for Counter
            |    write(*self, bytes: []const u8)
            |        self.n += bytes.len
            |var c: Counter
            |var w: *Writer = &c
            |display_int(7, w, FormatSpec(5, -1, false))
            |print(c.n)""".stripMargin) shouldBe "5\n"
    }

    // What a composite implementation reaches for: render the parts into a string, then let one
    // call put that text in the field the specifier asked for.
    "is available to an implementation with text of its own" in {
      run("""struct Point
            |    x: int
            |    y: int
            |impl Display for Point
            |    display(self, out: *Writer, fmt: FormatSpec)
            |        display_pad((str(self.x) + "," + str(self.y)).bytes, out, fmt)
            |print(f"[${Point(1, 2)}%7s]")
            |print(f"[${Point(1, 2)}%-7s]")""".stripMargin) shouldBe "[    1,2]\n[1,2    ]\n"
    }
  }

  /** `print` and `str` write no specifier, so the neutral one has to leave every renderer doing
   * exactly what it did before the padding existed.
   */
  "the neutral specifier changes nothing" - {
    "no width and no precision render each built-in plainly" in {
      run("""print(-2147483648, 0, 7u8, 2.5, true, 'é', "s")
            |print(str(-42) + "|" + str(2.5) + "|" + str(false))""".stripMargin) shouldBe
        "-2147483648 0 7 2.5 true é s\n-42|2.5|false\n"
    }
  }
}
