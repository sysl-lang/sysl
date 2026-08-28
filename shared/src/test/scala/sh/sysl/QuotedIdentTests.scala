package sh.sysl

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

/** Backtick-quoted identifiers, `` `like this` ``, at both jobs the form does.
 *
 * As a **name** it reaches what the ordinary identifier grammar cannot: a reserved word, and a
 * name carrying spaces and punctuation. As a **pattern** it is a reference rather than a binding,
 * which is the one thing a bare name in pattern position cannot say for itself.
 *
 * The two are one suite because they are one token, and the second is only well defined given the
 * first: what makes `` `limit` `` a reference is that the lexer kept the quoting rather than
 * handing the parser an ordinary `Identifier`.
 */
class QuotedIdentTests extends AnyFreeSpec with Matchers with CodegenSupport with RunSupport {

  /** The token case classes are path-dependent, so an expected token must come from the same
   * lexer instance that scanned it.
   */
  private def withLexer(body: SyslLexical => Any): Unit = { body(new SyslLexical); () }

  extension (l: SyslLexical) {

    def bare(src: String): List[Any] =
      l.scan(src).filterNot(t => t == l.Newline || t == l.Indent || t == l.Dedent)

    /** The message of the error token a malformed quoting produces. */
    def bad(src: String): String = l.bare(src).head.toString
  }

  "the token" - {
    "an ordinary word, quoted, is the same name" in withLexer { l =>
      l.bare("`hello`") shouldBe List(l.QuotedIdent("hello"))
    }

    // The whole point of the form: characters `isIdentPart` refuses.
    "spaces and punctuation" in withLexer { l =>
      l.bare("`hello world`") shouldBe List(l.QuotedIdent("hello world"))
      l.bare("`get-thing`") shouldBe List(l.QuotedIdent("get-thing"))
      l.bare("`a+b`") shouldBe List(l.QuotedIdent("a+b"))
      l.bare("`3 sheets`") shouldBe List(l.QuotedIdent("3 sheets"))
    }

    // A reserved word never reaches `processIdent`, so it stays a name.
    "a reserved word is a name" in withLexer { l =>
      l.bare("`match`") shouldBe List(l.QuotedIdent("match"))
      l.bare("`struct`") shouldBe List(l.QuotedIdent("struct"))
      l.bare("`if`") shouldBe List(l.QuotedIdent("if"))
    }

    // The soft keywords match `Identifier` by its text, so quoting one yields a different token
    // and they reject it without needing a rule of their own.
    "a soft keyword is a distinct token from the bare word" in withLexer { l =>
      l.bare("end") shouldBe List(l.Identifier("end"))
      l.bare("`end`") shouldBe List(l.QuotedIdent("end"))
    }

    "a backtick is no longer an illegal character" in withLexer { l =>
      l.bad("`") should include("unterminated quoted identifier")
    }
  }

  "malformed quoting" - {
    "unterminated, and a newline does not close it" in withLexer { l =>
      l.bad("`oops") should include("unterminated quoted identifier")
      l.bad("`oops\nmore`") should include("unterminated quoted identifier")
    }

    "empty" in withLexer { l =>
      l.bad("``") should include("needs a name between the backticks")
    }

    // Names travel through the compiler as dotted strings, so a dot inside one segment could not
    // be told from the separator between two.
    "a dot is refused" in withLexer { l =>
      l.bad("`a.b`") should include("may not contain '.'")
    }
  }

  // `Modules.split` recovers a module from a key by finding its first `$`, and `LlvmName.guard`
  // marks a `$` a *name* holds. Nothing marks a module path, so a quoted segment could put a raw
  // one before the separator and the key would name a module nobody declared. An accented segment
  // could not — a letter is not a `$` — which is why `UnicodeIdentTests` has a nested accented
  // module path and this refusal did not move.
  "a module path may not be quoted" - {
    "in the header" in {
      errOf(
        "m.sysl" -> "module `my mod`",
        "main.sysl" -> "print(1)",
      ) should include("a module path is written with plain names")
    }

    "in an import" in {
      errOf(
        "m.sysl" -> "module m\nval x: int = 1",
        "main.sysl" -> "import `m`.x\nprint(1)",
      ) should include("a module path is written with plain names")
    }
  }

  "a quoted name is an ordinary name" - {
    "declared, read, and assigned" in {
      val src =
        """var `item count` = 3
          |`item count` += 4
          |print(`item count`)""".stripMargin

      run(src) shouldBe "7\n"
    }

    "a reserved word as a name" in {
      val src =
        """val `match`: int = 5
          |print(`match`)""".stripMargin

      run(src) shouldBe "5\n"
    }

    "as a function and its parameters" in {
      val src =
        """`add two`(`the value`: int) -> int = `the value` + 2
          |print(`add two`(40))""".stripMargin

      run(src) shouldBe "42\n"
    }

    // **This used to say "which only quoting can reach", and that stopped being true when an
    // identifier could be written in any script** (`reference/lexical.md § Identifiers`, 0.0.89).
    // `café` is now a name the ordinary grammar produces, and the two spellings are one name at one
    // symbol — `UnicodeIdentTests` asserts that pair directly. What quoting still reaches alone is
    // punctuation, spaces and a reserved word.
    //
    // The encoding is unchanged and is `LlvmName.safe`'s: one byte becomes `$XX`, a codepoint above
    // it `$uXXXXXX` per UTF-16 unit.
    "a name outside ASCII, which quoting reaches and no longer reaches alone" in {
      val src =
        """var `café` = 3
          |`café` += 1
          |print(`café`)""".stripMargin

      run(src) shouldBe "4\n"
    }

    "as a struct and its fields" in {
      val src =
        """struct `Grid Cell`
          |    `row index`: int
          |end `Grid Cell`
          |var c = `Grid Cell`(9)
          |print(c.`row index`)""".stripMargin

      run(src) shouldBe "9\n"
    }

    // **Both of the two below crashed or refused every release up to 0.0.88, and neither had a
    // case.** A quoted name reached its key already made LLVM-safe, so the key was a spelling no
    // declaration had and no composition site downstream had ever seen a character worth escaping.
    // Making an identifier writable in any script is what forced the split those two now rest on: a
    // key is guarded (`LlvmName.guard`, `$` alone) and IR text is made safe (`LlvmName.safe`).
    //
    // A **method** on a quoted type composed `<type key>.<member>` with the member raw, so the
    // define line read `define i32 @Point.is ok(...)` and clang refused the module — a link-shaped
    // failure from a name-shaped mistake.
    "a quoted method name, which used to emit IR that would not parse" in {
      val src =
        """struct Point
          |    x: int
          |
          |    `is ok`(self) -> int = self.x
          |
          |val p = Point(3)
          |
          |print(p.`is ok`())""".stripMargin

      run(src) shouldBe "3\n"
    }

    // And a quoted **variant** crashed the compiler outright — `None.get` out of
    // `CallAnalysis.constructVariant`, which looked its declaration up by comparing the key's tail
    // against the written name. Nothing about accents was needed to reach it; it is the same defect
    // `UnicodeIdentTests` found from the other side.
    "a quoted enum variant, which used to crash the compiler" in {
      val src =
        """enum Season
          |    `late autumn`
          |    Winter
          |
          |val e = Season.`late autumn`
          |
          |print(e == `late autumn`, e == Winter)""".stripMargin

      run(src) shouldBe "true false\n"
    }

    // The collision `LlvmName.guard` exists for, and the only reason a key is not simply the name
    // as written: `safe` writes a space as `$20`, so a name that already holds that text has to be
    // told apart from one that does not. Two names, two symbols, two values.
    "a name holding a dollar sign is not the name that encodes to it" in {
      val src =
        """var `a b` = 1
          |var `a$20b` = 2
          |print(`a b`, `a$20b`)""".stripMargin

      run(src) shouldBe "1 2\n"
    }
  }

  "as a pattern it references rather than binds" - {
    // The case the whole form exists for: a bare `limit` would bind and match everything, so the
    // second arm would be unreachable. The quoting is what makes it a test.
    "a val is tested at run time" in {
      val src =
        """val limit: int = 10
          |describe(n: int) -> string
          |    n match
          |        `limit` -> "at the limit"
          |        else "elsewhere"
          |print(describe(10), describe(3))""".stripMargin

      run(src) shouldBe "at the limit elsewhere\n"
    }

    "a local is tested, not shadowed" in {
      val src =
        """check(n: int, want: int) -> string
          |    n match
          |        `want` -> "same"
          |        else "different"
          |print(check(4, 4), check(4, 5))""".stripMargin

      run(src) shouldBe "same different\n"
    }

    // A `const` folds to the literal it always did, so quoting one changes nothing but the
    // reader's certainty about which reading was meant.
    "a const still folds" in {
      val src =
        """const limit: int = 10
          |describe(n: int) -> string
          |    n match
          |        `limit` -> "at the limit"
          |        else "elsewhere"
          |print(describe(10), describe(3))""".stripMargin

      run(src) shouldBe "at the limit elsewhere\n"
    }

    // The consequence `09` records: a pattern is a place a name is *read*, so a body that mentions
    // one nowhere else still captures it. Before quoted names no pattern could read anything, and
    // both free-name walks took patterns for their bindings alone.
    "a nested function captures a name it mentions only in a pattern" in {
      val src =
        """outer() -> string
          |    val limit = 10
          |    inner(n: int) -> string
          |        n match
          |            `limit` -> "at the limit"
          |            else "elsewhere"
          |    inner(10) + " " + inner(3)
          |print(outer())""".stripMargin

      run(src) shouldBe "at the limit elsewhere\n"
    }

    "a closure captures one the same way" in {
      val src =
        """outer() -> string
          |    val limit = 10
          |    var check = (n: int) ->
          |        n match
          |            `limit` -> "at the limit"
          |            else "elsewhere"
          |    check(10) + " " + check(3)
          |print(outer())""".stripMargin

      run(src) shouldBe "at the limit elsewhere\n"
    }

    "the bare name still binds, which is what the quoting distinguishes it from" in {
      val src =
        """const limit: int = 10
          |describe(n: int) -> string
          |    n match
          |        other -> "bound " + str(other)
          |print(describe(3))""".stripMargin

      run(src) shouldBe "bound 3\n"
    }
  }

  "what a quoted pattern refuses" - {
    "a name that resolves to nothing is a mistake, not a new local" in {
      val src =
        """describe(n: int) -> string
          |    n match
          |        `nothing here` -> "yes"
          |        else "no"
          |print(describe(1))""".stripMargin

      err(src) should include("nothing here")
    }

    // A binding has no other arm to take, so a test there cannot mean anything. The destructuring
    // form is what reaches this: a plain `val `x` = 3` is a *declaration*, and quoting a name being
    // declared is only ever quoting — the reference reading belongs to pattern position.
    "it cannot stand at an irrefutable binding" in {
      val src =
        """val limit: int = 10
          |val (`limit`, b) = (3, 4)
          |print(b)""".stripMargin

      err(src) should include("a binding cannot test a value")
    }

    // The other half of the same line: quoting a name at a declaration declares that name.
    "quoting a name being declared just declares it" in {
      val src =
        """val `limit`: int = 3
          |print(`limit`)""".stripMargin

      run(src) shouldBe "3\n"
    }
  }

  // The refusal is for a *module-level* `val`, so it takes a second file: an entry file's top level
  // is a body (`reference/modules.md § Where a program starts`), and a `val` there is a local, which a bare name may legitimately shadow.
  "the diagnostic for a module val in a pattern points at the quoted form" in {
    errOf(
      "m.sysl" ->
        """module m
          |val limit: int = 10""".stripMargin,
      "main.sysl" ->
        """import m.limit
          |describe(n: int) -> string
          |    n match
          |        limit -> "at the limit"
          |        else "elsewhere"
          |print(describe(10))""".stripMargin,
    ) should include("`limit`")
  }
}
