package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** `const` — the one kind of module-level binding there is (`13 §7`).
 *
 * A constant is folded into every use and has no storage, so nothing here checks what is emitted:
 * what it names is a value, and the assertions are about the value arriving intact at each of the
 * four places a constant may stand — an expression, an array bound, an enum discriminant, and a
 * pattern. The last three are what a nullary function cannot do, and are the reason the
 * declaration exists.
 */
class ConstTests extends AnyFreeSpec with CodegenSupport with RunSupport with ParseSupport {

  "the declaration parses" - {
    "with its type and its value" in {
      prog("const n: int = 1") shouldBe List(ConstDecl("n", NamedType("int"), i(1)))
    }

    "and carries a visibility modifier like any other declaration" in {
      prog("private const n: int = 1") shouldBe
        List(ConstDecl("n", NamedType("int"), i(1), vis = Visibility.File))
      prog("private[geom] const n: int = 1") shouldBe
        List(ConstDecl("n", NamedType("int"), i(1), vis = Visibility.Scoped("geom")))
    }

    "a value is not optional" in {
      progError("const n: int") should not be empty
    }

    "and neither is a type" in {
      progError("const n = 1") should not be empty
    }
  }

  "a constant is a value" - {
    "read in an expression" in {
      run("const n: int = 7\nprint(str(n + 1))") shouldBe "8\n"
    }

    "with its declared type, not the one the context wanted" in {
      err("const n: usize = 7\nvar m: int = n") should include("usize")
    }

    // The literal takes its type from the declaration, which is the ordinary rule for where a
    // literal sits (`01`) — so no suffix is written and none is needed.
    "of any scalar type" in {
      run(
        """const a: u8 = 255
          |const b: real = 0.5
          |const c: bool = true
          |const d: char = 'q'
          |const e: string = "hi"
          |print(s"${str(a)} ${str(b)} ${str(c)} ${str(d)} $e")
          |""".stripMargin,
      ) shouldBe "255 0.5 true q hi\n"
    }

    "reachable from another module by its full path" in {
      runIn(
        ("limits", "limits.sysl", "module limits\nconst width: int = 12\n"),
        ("", "main.sysl", "print(str(limits.width))\n"),
      ) shouldBe "12\n"
    }

    "and by an import" in {
      runIn(
        ("limits", "limits.sysl", "module limits\nconst width: int = 12\n"),
        ("", "main.sysl", "import limits.width\nprint(str(width))\n"),
      ) shouldBe "12\n"
    }

    "unless it is private to its file" in {
      errIn(
        ("limits", "a.sysl", "module limits\nprivate const width: int = 12\n"),
        ("limits", "b.sysl", "module limits\nwide() -> int = width\n"),
        ("", "main.sysl", "print(str(limits.wide()))\n"),
      ) should include("private")
    }
  }

  "a constant expression folds" - {
    "arithmetic and bit operations" in {
      run(
        """const a: int = 2 + 3 * 4
          |const b: int = 1 << 10
          |const c: int = 0xFF & 0x0F | 0x30
          |const d: int = ~0
          |print(s"${str(a)} ${str(b)} ${str(c)} ${str(d)}")
          |""".stripMargin,
      ) shouldBe "14 1024 63 -1\n"
    }

    "one constant in terms of another, whichever order they were written in" in {
      run(
        """const total: usize = head + tail
          |const head: usize = 8
          |const tail: usize = 4
          |print(str(total))
          |""".stripMargin,
      ) shouldBe "12\n"
    }

    "a comparison, to a boolean" in {
      run("const big: bool = 100 > 99\nprint(str(big))") shouldBe "true\n"
    }

    "a conversion, which truncates exactly as a written one does" in {
      run("const low: u8 = u8(300)\nprint(str(low))") shouldBe "44\n"
    }

    "floating-point arithmetic" in {
      run("const half: real = 1.0 / 4.0 + 0.25\nprint(str(half))") shouldBe "0.5\n"
    }
  }

  "a constant may be an array bound" - {
    "which is the whole reason for the declaration" in {
      run(
        """const capacity: usize = 6
          |var buf: [capacity]u8
          |print(str(buf.len))
          |""".stripMargin,
      ) shouldBe "6\n"
    }

    "including one computed from others" in {
      run(
        """const lit: usize = 286
          |const dist: usize = 30
          |var lengths: [lit + dist]u8
          |print(str(lengths.len))
          |""".stripMargin,
      ) shouldBe "316\n"
    }

    "and it sizes a struct's field, so two declarations cannot drift apart" in {
      run(
        """const capacity: usize = 4
          |struct Chunk
          |    code: [capacity]u8
          |    len: usize
          |end Chunk
          |var c: Chunk
          |print(str(c.code.len))
          |""".stripMargin,
      ) shouldBe "4\n"
    }

    "a call is still not one" in {
      err("capacity() -> usize = 8usize\nvar buf: [capacity()]u8") should include("must be a constant")
    }
  }

  "a constant may be an enum discriminant" - {
    "on its own" in {
      run(
        """const base: int = 10
          |enum Step
          |    First = base
          |    Second
          |end Step
          |print(s"${str(int(Step.First))} ${str(int(Step.Second))}")
          |""".stripMargin,
      ) shouldBe "10 11\n"
    }

    "and in an expression, at the enum's own width" in {
      run(
        """const top: u8 = 255
          |enum Level: u8
          |    Low = 1
          |    High = top - 1
          |end Level
          |print(str(u8(Level.High)))
          |""".stripMargin,
      ) shouldBe "254\n"
    }

    "one that does not fit is still refused" in {
      err("const top: int = 300\nenum Level: u8\n    High = top\nend Level") should include("does not fit")
    }
  }

  // The trap this rides past: in Rust a lowercase `const` in a pattern binds instead of matching,
  // silently making the arm irrefutable. A name here resolves against what is declared before it is
  // taken as a binding, which is the same path an enum variant already went down.
  "a constant may be a pattern" - {
    "and matches by value rather than binding" in {
      run(
        """const limit: int = 3
          |describe(n: int) -> string =
          |    n match
          |        limit -> "at the limit"
          |        else "somewhere else"
          |print(s"${describe(3)} ${describe(4)}")
          |""".stripMargin,
      ) shouldBe "at the limit somewhere else\n"
    }

    "a name that is not a constant still binds" in {
      run(
        """describe(n: int) -> string =
          |    n match
          |        other -> s"bound ${str(other)}"
          |print(describe(9))
          |""".stripMargin,
      ) shouldBe "bound 9\n"
    }
  }

  "what a constant may not be" - {
    "defined in terms of itself" in {
      err("const n: int = n + 1") should include("in terms of itself")
    }

    "or in terms of something that is defined in terms of it" in {
      val message = err("const a: int = b\nconst b: int = a\nprint(str(a))")

      message should include("in terms of itself")
      message should include("b")
    }

    "given a value that is not constant" in {
      err("f() -> int = 1\nconst n: int = f()") should include("not a constant expression")
    }

    "given a value that does not fit its type" in {
      err("const n: u8 = 256") should include("does not fit")
    }

    "given a value of another kind entirely" in {
      err("const n: int = \"twelve\"") should include("declared int")
    }

    "declared at a type that is not a scalar" in {
      err("struct P\n    x: int\nend P\nconst p: P = 1") should include("is not")
    }

    "divided by zero where the compiler is the one dividing" in {
      err("const n: int = 1 / 0") should include("divided by zero")
    }

    "declared twice" in {
      err("const n: int = 1\nconst n: int = 2") should include("already declared")
    }

    "declared over a function's name" in {
      err("const n: int = 1\nn() -> int = 2") should include("already declared as a constant")
    }

    "declared over an enum variant's name" in {
      err("enum Colour\n    Red\nend Colour\nconst Red: int = 1") should include("already used by enum")
    }
  }
}
