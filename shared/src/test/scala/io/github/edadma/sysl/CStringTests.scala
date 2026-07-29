package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** `c"…"` — a NUL-terminated string literal of type `*u8` (`04-strings.md` "C interop").
 *
 * A sysl `string` carries a length and no terminator, which is the one shape a C interface cannot
 * read. Every string literal is already laid down NUL-terminated in read-only data, so this form
 * costs nothing at run time: it is that constant's address. What it buys is that the call site says
 * which of the two shapes it is handing over, rather than the compiler inferring it.
 */
class CStringTests extends AnyFreeSpec with ParseSupport with CodegenSupport with RunSupport {

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
}
