package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** The operations of `04-strings.md` that make new bytes: `s.copy()`, `string(c)`, `StrBuilder`,
 * and `cstring(s)`.
 *
 * Every one of them allocates, which is what kept them out of the language until there was an
 * allocator surface to build them on — and it is also what decides their spellings. `copy` and
 * `finish` are written with parentheses because they walk the bytes, while `len` beside them is a
 * projection of a word already there; `string(c)` is a conversion because a `char` has exactly one
 * encoding, while `str(x)` is a rendering because a number has more than one.
 */
class StringSurfaceTests extends AnyFreeSpec with CodegenSupport with RunSupport {

  /** `s.copy()` exists for one reason (`04 § Ownership and lifetime`): a substring shares its
   * parent's buffer, so a short-lived slice of a long-lived string would otherwise pin the whole
   * thing. Copying is the named operation that breaks that hold.
   */
  "a string copies out of the buffer it was sharing" - {
    "the copy reads the same bytes" in {
      run("""var s = "hello"
            |var t = s[0..<3usize]
            |print(t.copy(), t.copy().len, t.copy() == t)
            |""".stripMargin) shouldBe "hel 3 true\n"
    }

    // The discriminating one: the parent is a *heap* string, so it is freed the moment nothing
    // holds it. A copy that had kept a view of the parent's bytes would read whatever the two
    // hundred thousand allocations after it left behind.
    "and it no longer holds the buffer it came from" in {
      run("""grab() -> string
            |    var whole = "0123456789" + "0123456789"
            |    whole[3..<5usize].copy()
            |end grab
            |var kept = grab()
            |var i = 0
            |while i < 200000
            |    var junk = "0123456789" + "0123456789"
            |    if junk.len != 20usize then exit(1)
            |    i += 1
            |print(kept, kept.len)
            |""".stripMargin) shouldBe "34 2\n"
    }

    "an empty string copies to an empty string" in {
      run("""print("".copy().len)""") shouldBe "0\n"
    }

    // A literal's bytes are immortal and its owner word is null, so the copy is the first version
    // of those bytes that anything counts.
    "a literal copies too, and the copy owns what the literal did not" in {
      run("""var c = "abc".copy()
            |print(c, c.len)
            |""".stripMargin) shouldBe "abc 3\n"
    }
  }

  /** `04 § Granularity` puts `copy` on the method side of `08`'s line and `len` on the property
   * side, because one walks the bytes and the other reads a word that is already there. Both
   * mistakes name the fix.
   */
  "and it is a method, because it costs something" - {
    "read without parentheses it says so" in {
      err("""var x = "a".copy""") should include("'copy' is a method of 'string' — call it with 'copy()'")
    }

    "it takes nothing, since what it copies is what it was read off" in {
      err("""var x = "a".copy(1)""") should include("'copy' takes no arguments")
    }

    // `08`: a compiler-provided member is reached ahead of the member table, so an `impl` may not
    // declare one of the same name — it would be registered and never found.
    "and an 'impl' may not declare one of the same name" in {
      err("""trait Show
            |    show(self) -> int
            |impl Show for string
            |    copy(self) -> int = 1
            |    show(self) -> int = 2
            |print(1)
            |""".stripMargin) should not be empty
    }

    // `10 §5`: an unbounded parameter permits only what every type supports, and no trait declares
    // `copy`, so there is no bound to suggest.
    "a type parameter cannot reach it, and no bound would help" in {
      err("""f[T](x: T) = x.copy()
            |print(1)
            |""".stripMargin) should include("no trait declares a method 'copy'")
    }
  }

  /** `string(c)` is the fourth row of `04 § Validity`'s construction table, and the only conversion
   * whose result is not a scalar.
   */
  "a char converts to the string that spells it" - {
    "one scalar value becomes its UTF-8" in {
      run("""print(string('A'), string('\u{2603}'))""") shouldBe "A ☃\n"
    }

    // The byte length follows from the codepoint's range, and the four ranges are the four lengths.
    "and the byte length is the encoding's, not one" in {
      run("""print(string('\u{7f}').len, string('\u{80}').len, string('\u{7ff}').len)
            |print(string('\u{800}').len, string('\u{ffff}').len, string('\u{10000}').len)
            |print(string('\u{10ffff}').len)
            |""".stripMargin) shouldBe "1 2 2\n3 3 4\n4\n"
    }

    // A `string` carries a length rather than a terminator, so NUL is an ordinary byte and the
    // string that spells it has one byte in it rather than none.
    "including the null character, which is one byte and not the end of anything" in {
      run("""print(string('\u{0}').len)""") shouldBe "1\n"
    }

    // `04 § Rendering a value` gives `char` the same row, so the two must agree to the byte —
    // which is why `string(c)` needs nothing of its own underneath.
    "and it agrees with 'str' of the same char" in {
      run("""print(string('\u{10ffff}') == str('\u{10ffff}'), string('q') == str('q'))""") shouldBe
        "true true\n"
    }

    "a value that is not a char is sent to the form that renders" in {
      err("""var x = string(5)""") should include("'str(x)' renders a value as text")
    }

    "a real too, since a number has a rendering rather than an encoding" in {
      err("""var x = string(3.0)""") should include("real is not one")
    }

    "bytes are sent to the form that validates them" in {
      err("""var b: []u8 = [104u8, 105u8]
            |var x = string(b)
            |""".stripMargin) should include(s"'${Modules.show(Library.key("from_utf8"))}(b)'")
    }

    "and a string is told it is already one" in {
      err("""var x = string("a")""") should include("already a string")
    }

    "it takes exactly one value" in {
      err("""var x = string()""") should include("exactly one value")
    }
  }

  /** `StrBuilder` is `04 § Granularity`'s "repeated append, amortized" row. Its two ways in — a
   * string and a char — are the two that carry the UTF-8 guarantee with them, which is what lets
   * `finish` hand back a `string` rather than something that has to be validated.
   */
  "a builder appends without rebuilding what it already has" - {
    "strings and chars go in, one string comes out" in {
      run("""var b = str_builder()
            |b.push("he")
            |b.push_char('l')
            |b.push("lo ")
            |b.push(str(42))
            |print(b.finish(), b.len, b.is_empty)
            |""".stripMargin) shouldBe "hello 42 8 false\n"
    }

    "a fresh one is empty, and so is what it finishes to" in {
      run("""var b = str_builder()
            |print(b.len, b.is_empty, b.finish().len)
            |""".stripMargin) shouldBe "0 true 0\n"
    }

    // The discriminating one: `finish` copies the bytes rather than viewing them, so a string
    // taken out of a builder is not disturbed by what is appended afterwards. A `finish` that
    // handed back a view of the buffer would pass every test above and fail this one.
    "and what it hands back does not change when the builder does" in {
      run("""var b = str_builder()
            |b.push("abc")
            |var first = b.finish()
            |b.push("def")
            |print(first, first.len, b.finish(), b.len)
            |""".stripMargin) shouldBe "abc 3 abcdef 6\n"
    }

    "clearing empties it without disturbing what it already handed out" in {
      run("""var b = str_builder()
            |b.push("abc")
            |var first = b.finish()
            |b.clear()
            |b.push("z")
            |print(first, b.finish(), b.len)
            |""".stripMargin) shouldBe "abc z 1\n"
    }

    // The buffer underneath doubles, so this crosses its growth several times over — and the
    // point of the form is that the cost of doing so is amortized rather than quadratic.
    "it grows past its initial capacity as often as it needs to" in {
      run("""var b = str_builder()
            |var k = 0
            |while k < 1000
            |    b.push("ab")
            |    k += 1
            |print(b.len, b.finish().len)
            |""".stripMargin) shouldBe "2000 2000\n"
    }

    // `push_char` encodes into the buffer directly rather than making a one-character string and
    // copying that, so the two encoders have to be checked against each other.
    "and a pushed char is encoded exactly as 'string(c)' encodes it" in {
      run("""var b = str_builder()
            |b.push_char('\u{0}')
            |b.push_char('\u{7f}')
            |b.push_char('\u{80}')
            |b.push_char('\u{7ff}')
            |b.push_char('\u{800}')
            |b.push_char('\u{ffff}')
            |b.push_char('\u{10000}')
            |b.push_char('\u{10ffff}')
            |var joined = string('\u{0}') + string('\u{7f}') + string('\u{80}') + string('\u{7ff}')
            |var rest = string('\u{800}') + string('\u{ffff}') + string('\u{10000}') + string('\u{10ffff}')
            |print(b.len, b.finish() == joined + rest)
            |""".stripMargin) shouldBe "20 true\n"
    }

    // What comes out is a `string` like any other, so the guarantee it carries is the one every
    // string carries: the scalar values walk back out of the bytes that went in.
    "and what comes out walks as characters" in {
      run("""var b = str_builder()
            |b.push("a")
            |b.push_char('\u{2603}')
            |b.push("z")
            |var n = 0
            |for c in b.finish().chars do n += 1
            |print(n, b.len)
            |""".stripMargin) shouldBe "3 5\n"
    }
  }

  /** `cstring(s)` is `04 § C interop`'s general direction — the one for a string that is not a
   * literal, where `c"…"` cannot apply because there is no constant to take the address of.
   */
  "a string copies into the shape C reads" - {
    "the copy is NUL-terminated, and C finds the same length sysl does" in {
      run("""extern "strlen" c_strlen(p: *u8) -> usize
            |var cs = cstring("abc")
            |print(cs.len, c_strlen(cs.ptr))
            |""".stripMargin) shouldBe "3 3\n"
    }

    "an empty string still gets its terminator" in {
      run("""extern "strlen" c_strlen(p: *u8) -> usize
            |var cs = cstring("")
            |print(cs.len, c_strlen(cs.ptr))
            |""".stripMargin) shouldBe "0 0\n"
    }

    // The hazard `04` says the conversion is explicit *for*: a sysl string may hold a NUL as an
    // ordinary byte, and C cannot see past one. Both answers are right, and they disagree — which
    // is exactly why the conversion is written rather than inferred.
    "and an interior NUL is where the two notions of length part company" in {
      run("""extern "strlen" c_strlen(p: *u8) -> usize
            |var cs = cstring("a\0b")
            |print(cs.len, c_strlen(cs.ptr))
            |""".stripMargin) shouldBe "3 1\n"
    }

    "the bytes reach C intact" in {
      run("""extern "puts" c_puts(p: *u8) -> int
            |var cs = cstring("hi " + "there")
            |var n = c_puts(cs.ptr)
            |print(cs.len)
            |""".stripMargin) shouldBe "hi there\n8\n"
    }

    // The value owns its bytes, so it keeps them for as long as it is held — which is what "the
    // caller owns it" means in a language with no manual free.
    "and it keeps its bytes for as long as it is held" in {
      run("""extern "strlen" c_strlen(p: *u8) -> usize
            |make() -> CString = cstring("0123456789")
            |var kept = make()
            |var i = 0
            |while i < 200000
            |    var junk = cstring("0123456789")
            |    if junk.len != 10usize then exit(1)
            |    i += 1
            |print(kept.len, c_strlen(kept.ptr))
            |""".stripMargin) shouldBe "10 10\n"
    }
  }

  /** What the four of them are *not*, each pinned because it would otherwise be a hole. */
  "what the new surface does not quietly become" - {
    // `02`'s orphan rule reaches the library's own types exactly as it reaches everything else.
    "a library type is not a home for a program's 'impl' of a library trait" in {
      err("""impl Display for CString
            |    display(self, out: *Writer, fmt: FormatSpec) = display_str("x", out, fmt)
            |print(1)
            |""".stripMargin) should include("has no home")
    }

    // These names are the standard module's, so a program declaring one of its own gets its own —
    // the same answer `from_utf8` and `buf` now give, since they moved too. What the surface must
    // not quietly become is *unreachable*: the library's is still there under the path that names
    // it, and it is what the library's own callers keep meaning.
    "and a program's own is its own, while the library's stays reachable by its path" in {
      run("""cstring(s: string) -> int = 1
            |
            |print(cstring("a"), sysl.cstring("abc").len)
            |""".stripMargin) shouldBe "1 3\n"
    }

    // A builder that took bytes would be `from_utf8_unchecked` with a longer name, so it is not a
    // `Writer`: the two ways in are the two that carry the guarantee.
    "a builder takes text rather than bytes" in {
      err("""var b = str_builder()
            |var raw: []u8 = [104u8]
            |b.push(raw)
            |""".stripMargin) should not be empty
    }

    // No *code* reaches a program that does not ask for it: a library declaration nothing calls is
    // neither analyzed nor emitted. Its **layout** is the documented exception — a non-generic type
    // is instantiated where it is declared — so what a quiet program carries is two more
    // `%struct.… = type` lines, which name no storage and emit no instructions.
    //
    // Every name here is read off `Library.key`. Spelled literally, all three negatives would go on
    // passing once these declarations moved into the standard module and their symbols gained the
    // `sysl$` prefix — asserting nothing, and saying so nowhere.
    "and a program that uses none of it carries no code for it" in {
      val out = ir("""print(1)""")

      out should not include s"@${Library.key("cstring")}("
      out should not include s"@${Library.key("str_builder")}("
      out should not include s"@${Library.key("StrBuilder")}."
      out should include(s"%struct.${Library.key("StrBuilder")} = type")
    }
  }
}
