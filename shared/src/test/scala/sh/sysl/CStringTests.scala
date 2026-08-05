package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** `c"…"` — a NUL-terminated string literal of type `*u8` (`04-strings.md` "C interop").
 *
 * A sysl `string` carries a length and no terminator, which is the one shape a C interface cannot
 * read. Every string literal is already laid down NUL-terminated in read-only data, so this form
 * costs nothing at run time: it is that constant's address. What it buys is that the call site says
 * which of the two shapes it is handing over, rather than the compiler inferring it.
 */
class CStringTests extends AnyFreeSpec with ParseSupport with CodegenSupport with RunSupport {

  /** `c"…"` is a literal and costs nothing named, but the conversion back is a library function in
   * `sysl.text`, so the programs below that use one ask for it.
   */
  private def converting(src: String): String = run("import sysl.text.from_cstring\n\n" + src)

  "parsing" - {
    "a C string is its own literal, distinct from an ordinary one" in {
      prog("""var a = c"hi"
             |var b = "hi"""".stripMargin) shouldBe
        List(VarDecl("a", None, Some(CStrLit("hi"))), VarDecl("b", None, Some(StrLit("hi"))))
    }

    "escapes are decoded exactly as in an ordinary string" in {
      prog("""var a = c"a\tb\n"""") shouldBe List(VarDecl("a", None, Some(CStrLit("a\tb\n"))))
    }

    "the empty C string is a literal like any other" in {
      prog("""var a = c""""") shouldBe List(VarDecl("a", None, Some(CStrLit(""))))
    }

    // The prefix has to be the whole identifier, exactly as for `s"…"` / `raw"…"` / `f"…"`, so a
    // name that merely begins with `c` stays a name beside a string.
    "a name ending in c does not become a prefix" in {
      prog("""var abc = 1
             |var x = abc""".stripMargin) shouldBe
        List(VarDecl("abc", None, Some(i(1))), VarDecl("x", None, Some(Ident("abc"))))
    }

    "an unterminated C string is a parse error" in {
      progError("""var a = c"oops""") should not be empty
    }
  }

  "typing" - {
    "a C string is a raw byte pointer" in {
      err("""var n: int = c"hi"""") should include("*byte")
    }

    "it is not a string, and a string is not it" in {
      err("""var s: string = c"hi"""") should include("*byte")
      err("""extern f(p: *u8)
             |f("hi")""".stripMargin) should include("'p' of 'f' is *byte, but string was given")
    }

    // The bytes after an interior NUL could never be read by the callee, so the value would not be
    // what was written. An ordinary string is unaffected — carrying a length is what lets it hold one.
    "an interior NUL is refused" in {
      err("""var a = c"a\0b"""") should include("cannot contain one")
      ir("""var a = "a\0b"
           |print(a.bytes.len)""".stripMargin) should include("define i32 @main(")
    }

    "a NUL is still refused when it ends the literal, where it would be silently redundant" in {
      err("""var a = c"hi\0"""") should include("cannot contain one")
    }
  }

  "codegen" - {
    "the constant carries the terminator, uncounted" in {
      val out = ir("""extern f(p: *u8)
                     |f(c"hi")""".stripMargin)

      out should include("""[3 x i8] c"hi\00"""")
      out should include regex "call void @f\\(ptr @\\.str\\d+\\)"
    }

    "no allocation and no copy — the literal is passed by address" in {
      val out = ir("""extern f(p: *u8)
                     |f(c"hi")""".stripMargin)

      out should not include "@malloc"
      out should not include "sysl.str.from_bytes"
    }

    // An ordinary literal is interned NUL-terminated too, so the two forms share the mechanism and
    // differ only in what is read out of it: a three-word view, or the address.
    "an ordinary string of the same text is a view over the same shape of constant" in {
      val out = ir("""var s = "hi"
                     |print(s)""".stripMargin)

      out should include("""[3 x i8] c"hi\00"""")
      out should include("{ ptr, ptr, i64 }")
    }
  }

  "running" - {
    "a C string reaches a C function as the string it spells" in {
      val src =
        """extern printf(fmt: *u8, ...) -> int
          |printf(c"plain\n")""".stripMargin

      run(src) shouldBe "plain\n"
    }

    "it carries a format a variadic call then fills in" in {
      val src =
        """extern printf(fmt: *u8, ...) -> int
          |printf(c"%d and %g\n", 42, 2.5)""".stripMargin

      run(src) shouldBe "42 and 2.5\n"
    }

    // The whole point of the form: a sysl string is length-delimited, so `%.*s` is how one crosses
    // to C without a copy — and the length has to go with it.
    "a sysl string crosses through a C string format, by length" in {
      val src =
        """extern printf(fmt: *u8, ...) -> int
          |var s = "hello"
          |printf(c"[%.*s]\n", int(s.bytes.len), &s.bytes[0])""".stripMargin

      run(src) shouldBe "[hello]\n"
    }

    "an escape in a C string is the byte it stands for, not two" in {
      val src =
        """extern printf(fmt: *u8, ...) -> int
          |printf(c"a\tb\n")""".stripMargin

      run(src) shouldBe "a\tb\n"
    }

    "the same literal used twice is one constant, and both reads work" in {
      val src =
        """extern printf(fmt: *u8, ...) -> int
          |printf(c"%d\n", 1)
          |printf(c"%d\n", 2)""".stripMargin

      run(src) shouldBe "1\n2\n"
    }
  }

  /** `from_cstring` — the other direction, and the one every binding needs the moment a C library
    * reports anything in words. `c"…"` hands C a pointer into read-only data; this takes a pointer C
    * hands *back* and copies the bytes out to where a `string` can own them.
    */
  "from_cstring" - {

    "round-trips a C string literal" in {
      converting("""print(from_cstring(c"hello").unwrap())""") shouldBe "hello\n"
    }

    "gives the empty string for a pointer at an immediate NUL" in {
      converting("""print(f"[${from_cstring(c"").unwrap()}]")""") shouldBe "[]\n"
    }

    "stops at the first NUL and reads nothing after it" in {
      // The discriminating case a literal cannot reach: bytes *do* follow the terminator, so a
      // length taken from the allocation rather than from the scan would show up here as "hi\0xy".
      converting("""var b: [6]u8 = [104u8, 105u8, 0u8, 120u8, 121u8, 0u8]
                   |print(f"[${from_cstring(&b[0]).unwrap()}]")""".stripMargin) shouldBe "[hi]\n"
    }

    "copies, so the string outlives what C did next with the buffer" in {
      // The claim the doc comment makes, and the one worth pinning: C's storage is a static buffer
      // it reuses or memory the caller is about to free. A view would print "xi" here.
      converting("""var b: [4]u8 = [104u8, 105u8, 0u8, 0u8]
                   |var s = from_cstring(&b[0]).unwrap()
                   |b[0] = 120u8
                   |print(s)""".stripMargin) shouldBe "hi\n"
    }

    "carries multi-byte UTF-8 through as characters, not bytes" in {
      // `é` is two bytes, so a conversion that counted bytes as characters would disagree here.
      converting("""var s = from_cstring(c"héllo").unwrap()
                   |var n = 0
                   |for c in s.chars do n += 1
                   |print(n)""".stripMargin) shouldBe "5\n"
    }

    "reads what a real C function wrote" in {
      // Not a literal: `snprintf` writes the bytes and the terminator at run time, which is the
      // shape every binding actually meets.
      converting("""extern "snprintf" c_snprintf(b: *u8, n: usize, f: *u8, ...) -> int
                   |var buf: [16]u8
                   |c_snprintf(&buf[0], 16usize, c"%d-%s", 42, c"ok")
                   |print(from_cstring(&buf[0]).unwrap())""".stripMargin) shouldBe "42-ok\n"
    }

    "refuses bytes that are not UTF-8, rather than making a string of them" in {
      // The error path, and the reason the result is a `Result`: nothing about a `char *` promises
      // UTF-8, and a C library reporting in another locale is ordinary rather than corrupt.
      converting("""var b: [3]u8 = [255u8, 0u8, 0u8]
                   |from_cstring(&b[0]) match
                   |    Ok(s) -> print(f"wrongly accepted ${s}")
                   |    Err(e) -> print(e.offset, e.truncated)""".stripMargin) shouldBe "0 false\n"
    }

    "and says a sequence merely ran out, where that is what happened" in {
      // `0xC3` opens a two-byte sequence and the NUL ends the input before its continuation. That is
      // `truncated`, which is the one distinction a caller can act on — more bytes would fix it.
      converting("""var b: [2]u8 = [195u8, 0u8]
                   |from_cstring(&b[0]) match
                   |    Ok(s) -> print(f"wrongly accepted ${s}")
                   |    Err(e) -> print(e.offset, e.truncated)""".stripMargin) shouldBe "0 true\n"
    }
  }
}
