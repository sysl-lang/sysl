package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** `sysl.term.tty` — whether escapes should be written at all.
 *
 * **The split from `sysl.term` is the thing under test, and it is a capability claim rather than a
 * behaviour.** A capability requirement is module-wide, so the whole reason this is a second module
 * is that putting `isatty` beside the constants would have taken all forty of them away from an
 * allocator-free, OS-free program. Two tests below assert exactly that: `sysl.term` is still
 * reachable from a module that gave up everything, and `sysl.term.tty` is refused there.
 *
 * **What a test cannot reach is a terminal.** The harness runs a compiled program with its output on
 * a pipe, so `is_tty` is false for every descriptor a test can name and anything gated on it is
 * false with it. That is why `color_wanted` is its own function: the environment's half of the
 * answer is the half that has interesting logic — `NO_COLOR` being about presence rather than value,
 * `TERM=dumb` — and it can be asked without a terminal, so it is asked here directly.
 *
 * The environment is set by the program itself, through its own `setenv`, rather than by the
 * harness: `RunSupport` offers no way to hand a child an environment, and a program declaring the
 * one symbol it needs is both smaller than adding that and closer to what the module does anyway.
 */
class TermTtyTests extends AnyFreeSpec with RunSupport with CodegenSupport {

  /** A program with the module imported and `setenv`/`unsetenv` available, so a test can put the
   * environment into the state it wants to ask about.
   */
  private def tty(src: String): String =
    run("import sysl.term.tty.*\nimport sysl.text.cstring\n\n" +
      "extern \"setenv\" c_setenv(name: *u8, value: *u8, overwrite: int) -> int\n" +
      "extern \"unsetenv\" c_unsetenv(name: *u8) -> int\n\n" +
      "set(name: string, value: string)\n" +
      "    val n = cstring(name)\n" +
      "    val v = cstring(value)\n\n" +
      "    c_setenv(n.ptr, v.ptr, 1)\n" +
      "end set\n\n" +
      "clear(name: string)\n" +
      "    val n = cstring(name)\n\n" +
      "    c_unsetenv(n.ptr)\n" +
      "end clear\n\n" +
      // Both start unset, so a test says only what it is about and no test inherits the harness's
      // own environment — which does carry a TERM, and would otherwise decide these answers.
      "clear(\"NO_COLOR\")\nclear(\"TERM\")\n\n" + src)

  "a descriptor that is not a terminal" - {

    // The case the module exists for: output redirected to a file or a pipe. The harness gives a
    // pipe, so this is the real answer rather than a simulated one.
    "is not one, and neither standard stream is under the harness" in {
      tty("print(is_tty(1), is_tty(2))") shouldBe "false false\n"
    }

    // A descriptor that was never opened. `isatty` sets errno and answers false, which is the answer
    // this wants — there is no third outcome to represent.
    "and neither is a descriptor that is not open at all" in {
      tty("print(is_tty(37))") shouldBe "false\n"
    }

    // The whole point: escapes are off when nobody is going to render them, whatever the
    // environment says.
    "so colour is off, however much the environment wants it" in {
      tty("set(\"TERM\", \"xterm-256color\")\n\nprint(color(), color_err(), color_on(1))") shouldBe
        "false false false\n"
    }
  }

  "NO_COLOR is about the variable being there" - {

    // The convention is presence, not value. A program looking for "1" has misread it, and this is
    // the case that catches that reading.
    "so any non-empty value turns colour off, including '0'" in {
      tty("set(\"NO_COLOR\", \"0\")\n\nprint(color_wanted())") shouldBe "false\n"
    }

    "and so does a value nobody would call true" in {
      tty("set(\"NO_COLOR\", \"no\")\n\nprint(color_wanted())") shouldBe "false\n"
    }

    // Set-and-empty is the one case that is *not* "present" — the convention says an empty value
    // does not disable, and it is the difference between checking the pointer and checking the byte.
    "while an empty value does not, which is the difference from merely being set" in {
      tty("set(\"NO_COLOR\", \"\")\n\nprint(color_wanted())") shouldBe "true\n"
    }

    "and unset does not either" in {
      tty("print(color_wanted())") shouldBe "true\n"
    }
  }

  "TERM says what the terminal can render" - {

    "a dumb one cannot, so colour is not wanted" in {
      tty("set(\"TERM\", \"dumb\")\n\nprint(color_wanted())") shouldBe "false\n"
    }

    // An exact match, not a prefix: `dumb-something` is a real terminal type and is not this one.
    "and the match is the whole value, not a prefix of it" in {
      tty("set(\"TERM\", \"dumber\")\n\nprint(color_wanted())") shouldBe "true\n"
    }

    "an ordinary one can" in {
      tty("set(\"TERM\", \"xterm-256color\")\n\nprint(color_wanted())") shouldBe "true\n"
    }

    // Unset is not dumb. `isatty` is what answers whether there is a terminal; this function is only
    // asked what kind, and "no answer" is not "cannot".
    "and unset is not dumb — that is the other question" in {
      tty("print(color_wanted())") shouldBe "true\n"
    }
  }

  /** The shape both this module's header and the library page put in front of a reader. It is
   * asserted rather than only written down because a documented call site that does not compile is
   * worse than no example — and the nested spelling in particular had nothing else in the tree
   * exercising a string literal inside an interpolation.
   */
  "the documented way to write a coloured diagnostic" - {

    "binds the two escapes first, and writes nothing when output is not a terminal" in {
      tty("""import sysl.term.{red, reset}
            |
            |val paint = color()
            |val on    = if paint then red else ""
            |val off   = if paint then reset else ""
            |
            |print(f"${on}error${off}: not found")""".stripMargin) shouldBe "error: not found\n"
    }

    // The same thing said inline. A `"` inside `${…}` closes nothing, which is the part worth
    // pinning: the two spellings have to stay interchangeable for the shorter one to be publishable.
    "and says the same inline, where the escape is chosen inside the interpolation" in {
      tty("""import sysl.term.{red, reset}
            |
            |val paint = color()
            |
            |print(f"${if paint then red else ""}error${if paint then reset else ""}: not found")"""
        .stripMargin) shouldBe "error: not found\n"
    }
  }

  "either half refusing is enough" - {

    "NO_COLOR beats a capable terminal" in {
      tty("set(\"TERM\", \"xterm-256color\")\nset(\"NO_COLOR\", \"1\")\n\nprint(color_wanted())") shouldBe
        "false\n"
    }
  }

  /** The reason there are two modules at all. Both of these would fail if the check had been put
   * beside the constants, and they are the whole claim the split is making.
   */
  "the split keeps the constants reachable where the check is not" - {

    "a module with no allocator and no OS may still name a colour" in {
      run("@no_alloc\n@no_os\n@no_posix\n@no_threads\n\nimport sysl.term.red\n\nprint(red.len)") shouldBe "5\n"
    }

    "while the same module may not ask whether to use it" in {
      val e = err("@no_posix\n\nimport sysl.term.tty.color\n\nprint(color())\n")

      e should include("sysl.term.tty")
      e should include("posix")
    }
  }

  /** Cbreak mode, which is what lets one program read a line at a macOS terminal and over a serial
   * cable to a board.
   *
   * **Only the refusing half is reachable here, and that is the half worth pinning.** `RunSupport`
   * hands a compiled program a *closed* standard input, so `is_tty(0)` is false for every run below
   * and `raw` takes its early exit every time — which is precisely why these tests are safe to have
   * at all. A suite that could reach the succeeding branch would be a suite that puts the developer's
   * own terminal into cbreak partway through a run, and leaves it there if anything after it fails.
   *
   * What is therefore *not* covered is whether `stty` sets what it is meant to set. That needs a
   * pseudo-terminal the harness has no way to make, and pretending otherwise by asserting on
   * `system`'s exit status would assert that a shell ran rather than that a terminal changed.
   */
  "cbreak mode, where there is no terminal to put into it" - {

    // The case a hosted program actually meets when its input is a pipe or a file. `false` is not a
    // failure: it says the kernel is not in the way, so the editor is the wrong facility and
    // `console_lines` is the right one.
    "raw() refuses, because there is nothing to change" in {
      tty("print(raw())") shouldBe "false\n"
    }

    // A refused `raw` must leave nothing behind for `cooked` to undo — otherwise a program taking the
    // fallback path would restore settings it never altered, on a terminal it never touched.
    "and cooked() afterwards does nothing, having nothing to put back" in {
      tty("raw()\ncooked()\nprint(\"done\")") shouldBe "done\n"
    }

    // `cooked` on its own is what a program does when it exits down a path that never reached `raw`,
    // which is every early return in a program that has one.
    "as does cooked() on its own" in {
      tty("cooked()\nprint(\"done\")") shouldBe "done\n"
    }

    // Asking twice is one question, not two. The second call sees `entered` already false and takes
    // the same early exit rather than spawning a second shell.
    "and asking twice answers the same and costs nothing more" in {
      tty("print(raw(), raw())") shouldBe "false false\n"
    }

    // The shape a program is meant to be written in: ask, and pick a facility from the answer. This
    // is here so that the fallback path is exercised end to end rather than only in the prose.
    "so a program picks its reader from the answer" in {
      tty("""import sysl.io.{stdin, console_lines}
            |
            |if raw()
            |    print("editing")
            |else
            |    var input = stdin()
            |    var cursor = console_lines(&input)
            |
            |    print("cooked")""".stripMargin) shouldBe "cooked\n"
    }
  }

  /** Cbreak mode **against a real terminal**, through `runOnTty`.
   *
   * **This section exists because the facility was merged with none of it exercised.** Everything
   * above reaches only the branch that declines to change anything, so what shipped first — a signal
   * handler that restored the terminal and exited — had never run once. It could not have: restoring
   * means allocating a command and forking a shell, and a handler doing that while the interrupted
   * code holds the allocator's lock waits forever. It hung on the first real keystroke.
   *
   * These are slower than the rest of the suite, by the second each spends letting the program reach
   * its `stty` before anything is typed at it. That is the cost of testing the half of a terminal
   * program that a pipe cannot show.
   */
  // The two keys that stopped being signals. Named rather than written as escapes because Scala has
  // no `\u{3}` form and its `` is handled by the lexer before the string exists, which is a
  // trap of its own — the character is what a test means, so the character is what it says.
  private val ctrlC = 3.toChar.toString
  private val ctrlD = 4.toChar.toString

  "cbreak mode, against a terminal that is really there" - {

    "raw() succeeds, where the tests above could only watch it decline" in {
      runOnTty("import sysl.term.tty.{is_tty, raw, cooked}\n\n" +
        "print(\"tty:\", is_tty(0))\nprint(\"raw:\", raw())\ncooked()\n", "") should include("raw: true")
    }

    // The whole point of the mode: bytes arrive as they are typed instead of at Enter, and nothing
    // but the editor echoes them.
    "and the editor then reads what is typed, a keystroke at a time" in {
      val out = runOnTty("""import sysl.term.edit.editor
                           |import sysl.io.stdin
                           |import sysl.term.tty.{raw, cooked}
                           |
                           |var input = stdin()
                           |
                           |if raw()
                           |    var ed = editor(&input, stdout())
                           |
                           |    for line in ed do print("got", line)
                           |    cooked()""".stripMargin, "abc\r" + ctrlD)

      out should include("got abc")
    }

    // **The regression test for the deadlock.** Ctrl-C is a byte here rather than a signal, so the
    // program has to still be alive afterwards and go on reading — which is exactly what the version
    // with a signal handler could not do.
    "Ctrl-C abandons the line without ending the program" in {
      val out = runOnTty("""import sysl.term.edit.editor
                           |import sysl.io.stdin
                           |import sysl.term.tty.{raw, cooked}
                           |
                           |var input = stdin()
                           |
                           |if raw()
                           |    var ed = editor(&input, stdout())
                           |
                           |    for line in ed do print("got [" + line + "]")
                           |    cooked()
                           |    print("ended")""".stripMargin, "abc" + ctrlC + "xy\r" + ctrlD)

      out should include("got []")
      out should include("got [xy]")
      out should include("ended")
    }

    // The claim a user feels the moment it is broken: a shell that shows nothing as you type. `after`
    // asks the terminal itself, once the program has left it, because a program that has exited
    // cannot be asked.
    "and the terminal is put back when the program leaves it" in {
      val out = runOnTty("""import sysl.term.tty.{raw, cooked}
                           |
                           |if raw() then cooked()""".stripMargin, "",
        after = "stty -a | tr ' ' '\\n' | grep -c '^-echo$'")

      // Zero occurrences of `-echo`: echo is on, which is what `cooked` promised to restore.
      out should include("0")
    }
  }
}
