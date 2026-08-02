package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** A `.lsysl` file: a Markdown document whose indented blocks are the program (`Literate`).
 *
 * Two things are being asserted throughout, and the second is the one that is easy to lose. The
 * program is the indented text and nothing else — that is the feature. And **a position still points
 * at the file the reader has open** — that is what makes the feature usable, and it is the half a
 * tangler that simply concatenates its code blocks gets wrong, silently, in a way that only shows up
 * when something is already going badly.
 */
class LiterateTests extends AnyFreeSpec with RunSupport with CodegenSupport {

  "the indented part is the program" - {
    "and the prose around it is not" in {
      runOf("hello.lsysl" ->
        """The greeting
          |------------
          |
          |A program is a list of statements. This one has a single statement in it, and every word
          |of this paragraph would be a syntax error if any of it reached the compiler.
          |
          |    print("Hello, sysl!")
          |""".stripMargin) shouldBe "Hello, sysl!\n"
    }

    "a paragraph inside a function body leaves one function" in {
      // The case the format exists for. Were each indented run its own unit, a body could not be
      // explained a step at a time — which is the only reason to want prose in a source file at all.
      runOf("sum.lsysl" ->
        """Summing
          |-------
          |
          |    total(n: int) -> int
          |        var acc = 0
          |
          |The loop is inclusive at both ends, so `total(3)` counts three, two and one.
          |
          |        for i in 1..n do acc += i
          |
          |And the last expression of a body is what it answers with.
          |
          |        acc
          |    end total
          |
          |    print(total(3))
          |""".stripMargin) shouldBe "6\n"
    }

    "an unindented line between two blocks does not end a block, and a fenced one is not code" in {
      // The fenced block holds sysl that would not compile. That is the point of fencing it: a page
      // showing the wrong version beside the right one must be able to say so in sysl.
      runOf("fenced.lsysl" ->
        """Fences
          |------
          |
          |This does not work, and the compiler says why:
          |
          |```
          |print(1 +)
          |```
          |
          |This does:
          |
          |    print(1 + 1)
          |""".stripMargin) shouldBe "2\n"
    }

    "a fence indented to code depth is code, since that is what the indent means" in {
      // A fence only opens an illustration where a fence can be written, which is prose. Four columns
      // in, the backticks are program text — and program text they are not valid in, so this is the
      // error that says the indent decides and the characters do not.
      errOf("bad.lsysl" ->
        """Not a fence
          |
          |    ```
          |    print(1)
          |""".stripMargin) should include("bad.lsysl:3:5")
    }
  }

  "what is under a bullet is prose" - {
    "an indented block inside a list item is the example the bullet is about" in {
      // It would not compile if it were compiled, which is the discriminating part: a test whose
      // list held valid sysl would pass whether or not the rule existed.
      runOf("bullets.lsysl" ->
        """Two things worth knowing
          |
          |- The first, whose example is written out here:
          |
          |      this is not sysl at all
          |
          |- The second.
          |
          |Which is the end of the list, and this is the program.
          |
          |    print(5)
          |""".stripMargin) shouldBe "5\n"
    }

    "an ordered list does the same" in {
      runOf("ordered.lsysl" ->
        """Steps
          |
          |1. First, which is illustrated by
          |
          |       nonsense goes here
          |
          |2. Second.
          |
          |Then the program.
          |
          |    print(6)
          |""".stripMargin) shouldBe "6\n"
    }

    "a bullet opens nothing when it is already inside program text" in {
      // At four columns in, the line was code before anything asked whether it looked like a bullet —
      // so a sysl line starting with `-` or `*` cannot be mistaken for one.
      runOf("minus.lsysl" ->
        """Arithmetic across a line break
          |
          |    var n = (10
          |        - 4)
          |
          |    print(n)
          |""".stripMargin) shouldBe "6\n"
    }
  }

  "a fence that is never closed is refused" - {
    "because everything below it would be silently not compiled" in {
      // Markdown would let it run to the end of the document, which for a document is harmless. Here
      // it would mean the reader is told about a program missing half its declarations, by a
      // diagnostic that names none of the lines responsible.
      val out = errOf("open.lsysl" ->
        """An illustration
          |
          |```
          |print("shown")
          |
          |    print("meant to run")
          |""".stripMargin)

      out should include("open.lsysl:3:1")
      out should include("never closed")
    }

    "and a closing fence may carry trailing spaces, which are invisible" in {
      runOf("trail.lsysl" ->
        ("Shown, then run\n\n```\nnot code\n```   \n\n    print(7)\n")) shouldBe "7\n"
    }

    "a longer fence closes what a shorter one opened, and the shorter does not" in {
      runOf("nested.lsysl" ->
        ("Quoting a fence\n\n````\n```\nstill inside\n```\n````\n\n    print(8)\n")) shouldBe "8\n"
    }
  }

  "a position points into the file the reader has open" - {
    "the line is the line it was written on" in {
      val out = errOf("where.lsysl" ->
        """A title
          |=======
          |
          |Four lines of prose, so that a tangler that dropped them would report line one.
          |
          |    print(nosuch)
          |""".stripMargin)

      out should include("where.lsysl:6:11")
    }

    "and the column counts the indent the reader can see" in {
      // 4 columns of indent, `print(` is six more, so the name starts at column 11 — not at the 7 the
      // lexer counted once the margin was gone.
      val out = errOf("col.lsysl" -> "    print(nosuch)\n")

      out should include("col.lsysl:1:11")
    }

    "a run of prose between two errors does not shift the second" in {
      val out = errOf("two.lsysl" ->
        """    var a: int = "one"
          |
          |Prose between them, which occupies lines of its own.
          |
          |    var b: int = "two"
          |""".stripMargin)

      out should include("two.lsysl:1:")
      out should include("two.lsysl:5:")
    }
  }

  "an ordinary file is untouched by any of this" - {
    "indented lines in a .sysl file are the program they always were" in {
      // The reason the format is chosen by the *name* and never by the content: this file is a valid
      // sysl program whose author indented a continuation, and reading it as literate would compile
      // the opposite of what it says.
      runOf("plain.sysl" -> "print(1 +\n    1)\n") shouldBe "2\n"
    }

    "and its columns are reported with no offset" in {
      err("print(nosuch)\n") should include("<input>:1:7")
    }
  }

  "a tab in the indentation is refused" - {
    "because how wide it is depends on what is displaying it" in {
      val out = errOf("tabbed.lsysl" -> "    print(1)\n\t print(2)\n")

      out should include("tab")
      out should include("tabbed.lsysl:2:1")
    }

    "while a tab inside a line is the author's business" in {
      runOf("inner.lsysl" -> "    print(\"a\tb\")\n") shouldBe "a\tb\n"
    }
  }

  // Tangling runs before the gate, so what `#if` sees is ordinary sysl and neither feature has to
  // know about the other (`SyslParser.parse`).
  "conditional compilation works inside a literate file" in {
    val src = files("cond.lsysl" ->
      """Two machines
        |
        |    #if macos
        |    which() -> int = 1
        |    #else
        |    which() -> int = 2
        |    #endif
        |
        |    print(which())
        |""".stripMargin)

    val out = Compiler.compile(src, Target.x86_64Linux) match
      case Right(ir) => ir
      case Left(e)   => fail(e)

    out should include("ret i32 2")
  }

  "a project may mix the two kinds of file" in {
    runIn(
      ("", "main.lsysl",
        """The entry point
          |
          |    import geom.*
          |
          |    print(area(3, 4))
          |""".stripMargin),
      ("geom", "area.sysl", "module geom\n\narea(w: int, h: int) -> int = w * h\n"),
    ) shouldBe "12\n"
  }
}
