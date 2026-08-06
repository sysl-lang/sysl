package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** `sysl.term` — the escape sequences a terminal understands.
 *
 * **A module of forty transcribed constants has exactly one interesting failure, and it is a wrong
 * number.** Nothing about it is visible in review: `"\u{1b}[36m"` where cyan wanted `36` and where a
 * slip would have written `35` are the same shape, they compile the same way, and a program using
 * the wrong one prints in the wrong colour and does nothing else unusual. Restating each constant in
 * a test would catch none of it, because the restatement is copied from the thing it is checking.
 *
 * So what is asserted here is the **arithmetic between** them, which is the specification and not a
 * coincidence: a background is its foreground plus ten, and a bright colour is its ordinary one plus
 * sixty. Both hold across all eight colours, in both families, and a single transcription slip
 * breaks one of the relations wherever it landed.
 *
 * The `@no_alloc` claim on the module needs no test of its own: the compiler holds a module to what
 * it declares, so a violation stops the library from building and every suite fails at once.
 */
class TermTests extends AnyFreeSpec with RunSupport {

  private def term(src: String): String = run("import sysl.term.*\n\n" + src)

  /** The SGR parameter out of a sequence, so that a test can do arithmetic on what the constants
   * actually say rather than on what it hoped they said.
   */
  private val code =
    """code(s: string) -> int
      |    var n = 0
      |
      |    for b in s.bytes
      |        if b >= u8('0') && b <= u8('9') then n = n * 10 + int(b - u8('0'))
      |
      |    n
      |
      |""".stripMargin

  "the colours" - {

    // The eight, in the order the specification puts them. This is the one place a literal list is
    // right: it is the origin the relations below are measured from, and it is what fixes which
    // colour each name refers to at all.
    "start at 30 and run to 37, in the order the specification gives them" in {
      term(code +
        """val fg = [black, red, green, yellow, blue, magenta, cyan, white]
          |var n = 0
          |
          |for i in 0..<8 do if code(fg[i]) == 30 + i then n += 1
          |
          |print(n)""".stripMargin) shouldBe "8\n"
    }

    "and a bright colour is its own plus sixty" in {
      term(code +
        """val fg = [black, red, green, yellow, blue, magenta, cyan, white]
          |val br = [bright_black, bright_red, bright_green, bright_yellow, bright_blue,
          |          bright_magenta, bright_cyan, bright_white]
          |var n = 0
          |
          |for i in 0..<8 do if code(br[i]) == code(fg[i]) + 60 then n += 1
          |
          |print(n)""".stripMargin) shouldBe "8\n"
    }

    "and a background is its foreground plus ten, bright ones included" in {
      term(code +
        """val fg = [black, red, green, yellow, blue, magenta, cyan, white]
          |val bg = [on_black, on_red, on_green, on_yellow, on_blue, on_magenta, on_cyan, on_white]
          |val br = [on_bright_black, on_bright_red, on_bright_green, on_bright_yellow,
          |          on_bright_blue, on_bright_magenta, on_bright_cyan, on_bright_white]
          |var n = 0
          |
          |for i in 0..<8
          |    if code(bg[i]) == code(fg[i]) + 10 then n += 1
          |    if code(br[i]) == code(fg[i]) + 70 then n += 1
          |
          |print(n)""".stripMargin) shouldBe "16\n"
    }

    // The pair that is not `reset`, and the reason both exist: ANSI ends every attribute at once or
    // none, so a program wanting its colour back without losing an emphasis needs these.
    "and the defaults sit ten apart, at the end of each family" in {
      term(code + """print(code(default_color), code(on_default))""") shouldBe "39 49\n"
    }
  }

  "the attributes" - {

    "are the SGR parameters they are named for" in {
      term(code +
        """print(code(reset), code(bold), code(dim), code(italic), code(underline))
          |print(code(blink), code(reverse), code(hidden), code(strike))""".stripMargin) shouldBe
        "0 1 2 3 4\n5 7 8 9\n"
    }
  }

  "every sequence" - {

    // A constant that lost its escape or its terminator would still be a string, would still
    // concatenate, and would print its own text into the middle of the output.
    "is introduced by the control sequence introducer" in {
      term(
        """val all = [reset, bold, dim, italic, underline, blink, reverse, hidden, strike,
          |           black, red, green, yellow, blue, magenta, cyan, white, default_color,
          |           bright_black, bright_red, bright_green, bright_yellow, bright_blue,
          |           bright_magenta, bright_cyan, bright_white,
          |           on_black, on_red, on_green, on_yellow, on_blue, on_magenta, on_cyan, on_white,
          |           on_default, on_bright_black, on_bright_red, on_bright_green, on_bright_yellow,
          |           on_bright_blue, on_bright_magenta, on_bright_cyan, on_bright_white,
          |           clear_screen, clear_line, clear_below, clear_to_line_end, home,
          |           hide_cursor, show_cursor, save_cursor, restore_cursor]
          |var n = 0
          |
          |for s in all do if s.len > 2 && s.bytes[0] == 27u8 && s.bytes[1] == u8('[') then n += 1
          |
          |print(n, all.len)""".stripMargin) shouldBe "52 52\n"
    }

    "ends the way its family ends — a colour with 'm', a screen sequence with its own letter" in {
      term(
        """val sgr = [reset, bold, red, on_bright_white, default_color]
          |var n = 0
          |
          |for s in sgr do if s.bytes[s.len - 1] == u8('m') then n += 1
          |
          |print(n)""".stripMargin) shouldBe "5\n"
    }
  }

  "in use" - {

    // What a program actually writes, byte for byte. The one assertion here that a reader can check
    // against a terminal's documentation without running anything.
    "a coloured word is the sequence, the text, and the reset" in {
      term("""print(f"${red}error${reset}")""") shouldBe "\u001b[31merror\u001b[0m\n"
    }

    "and an emphasis composes with a colour by being written beside it" in {
      term("""print(f"${bold}${on_blue}${white}!${reset}")""") shouldBe
        "\u001b[1m\u001b[44m\u001b[37m!\u001b[0m\n"
    }
  }
}
