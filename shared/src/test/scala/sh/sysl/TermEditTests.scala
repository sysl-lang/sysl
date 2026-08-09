package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** `sysl.term.edit` — the line editor, reached the way a program reaches it.
 *
 * **What the module's own `@test` functions cannot say is that a *program* can use it.** Those run
 * inside a test build of the library, where every module is already in scope and nothing has been
 * imported across a boundary; a defect in what the module exports, or in what a user program is
 * allowed to name, is invisible from in there. `lib/sysl/term/edit/tests.sysl` therefore carries the
 * behaviour — every key, every redraw — and this carries the two claims it structurally cannot make:
 * that the module is importable, and that a program written against it links.
 *
 * The fixtures are `sysl.io`'s `bytes_reader` and `bytes_writer`, which is also the point: they are
 * public library types rather than test scaffolding, so a program can be written exactly like this.
 */
class TermEditTests extends AnyFreeSpec with RunSupport with CodegenSupport {

  /** A program with the editor and the two in-memory streams in scope. */
  private def edit(src: String): String =
    run("import sysl.term.edit.editor\n" +
      "import sysl.io.{bytes_reader, bytes_reader_at_most, bytes_writer}\n\n" + src)

  "a program outside the library can use the editor" - {

    "reading the lines a byte source yields" in {
      edit("""var src = bytes_reader("one\rtwo\r".bytes)
             |var out = bytes_writer()
             |var ed = editor(&src, &out)
             |
             |for line in ed do print(line)""".stripMargin) shouldBe "one\ntwo\n"
    }

    // A `for` over the editor is an `Iterate[string]` walk, which is what makes it interchangeable
    // with `console_lines` at a call site — the difference between a board and a cooked terminal is
    // then one constructor rather than a different loop.
    "and walks it with the same loop console_lines is walked with" in {
      edit("""import sysl.io.{stdin, console_lines}
             |
             |var src = bytes_reader("a\rb\r".bytes)
             |var out = bytes_writer()
             |var ed = editor(&src, &out)
             |var n = 0
             |
             |for line in ed do n += 1
             |
             |print(n)""".stripMargin) shouldBe "2\n"
    }
  }

  "the editing a terminal cannot do for itself" - {

    // Backspace, an arrow key and a kill, through the public surface rather than the library's own.
    // The exhaustive coverage is in the module's `tests.sysl`; what this pins is that a program gets
    // the same behaviour the library's tests describe.
    "backspace removes what was typed before it" in {
      edit("""var src = bytes_reader("abx\u{8}c\r".bytes)
             |var out = bytes_writer()
             |var ed = editor(&src, &out)
             |
             |for line in ed do print(line)""".stripMargin) shouldBe "abc\n"
    }

    "an arrow key moves the cursor, and typing inserts where it stands" in {
      edit("""var src = bytes_reader("ac\u{1b}[Db\r".bytes)
             |var out = bytes_writer()
             |var ed = editor(&src, &out)
             |
             |for line in ed do print(line)""".stripMargin) shouldBe "abc\n"
    }
  }

  /** The echo is half of what an editor does, and it is the half a caller never sees. A test that
   * looked only at the lines would pass with the cursor left a column out on every keystroke.
   */
  "what the terminal is told" - {

    "is the line as it is typed, ended by CRLF" in {
      edit("""import sysl.text.from_utf8
             |
             |var src = bytes_reader("ab\r".bytes)
             |var out = bytes_writer()
             |var ed = editor(&src, &out)
             |
             |for line in ed do ()
             |
             |print(from_utf8(out.view()).unwrap_or("?") == "ab\r\n")""".stripMargin) shouldBe "true\n"
    }
  }

  /** The case that only arises when a unit of input straddles two reads — and the case a
   * hand-rolled console reader gets wrong. `bytes_reader_at_most` is what makes it reachable.
   */
  "input arriving a byte at a time" - {

    // The two bytes of a CRLF in different reads. The debt has to outlive the call that returned the
    // line, or the LF is read as an empty line following it.
    "does not turn one CRLF into two line endings" in {
      edit("""var src = bytes_reader_at_most("a\r\nb\r\n".bytes, 1)
             |var out = bytes_writer()
             |var ed = editor(&src, &out)
             |var n = 0
             |
             |for line in ed do n += 1
             |
             |print(n)""".stripMargin) shouldBe "2\n"
    }

    // An escape sequence cut in half. The surplus of a read is held, so the bracket and the letter
    // are still found after a refill.
    "does not lose an escape sequence cut in half" in {
      edit("""var src = bytes_reader_at_most("ac\u{1b}[Db\r".bytes, 1)
             |var out = bytes_writer()
             |var ed = editor(&src, &out)
             |
             |for line in ed do print(line)""".stripMargin) shouldBe "abc\n"
    }
  }

  /** The property that makes this a library module rather than a board's package: it asks for
   * nothing. The editor is what a freestanding target most needs and least able to get from
   * elsewhere, so a capability requirement here would have put it out of reach of its main audience.
   */
  "it requires nothing of the platform" - {

    "so a program with no OS and no allocator may still name it" in {
      run("@no_os\n@no_posix\n@no_threads\n\nimport sysl.term.edit.Editor\n\nprint(\"named\")") shouldBe
        "named\n"
    }
  }
}
