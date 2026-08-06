package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** The reserved identifier shape (`reserved-identifiers.md`): which names belong to the language,
 * that no declaration may take one, and the two build-stamp formats.
 *
 * The shape is tested apart from the built-ins on purpose. Reserving the space is the half that has
 * to be right *before* anything occupies it — a release that adds a seventh built-in is only
 * non-breaking because a program could never have declared the name, and that guarantee is this
 * predicate rather than the table beside it.
 */
class ReservedNameTests extends AnyFreeSpec with CodegenSupport {

  "the shape" - {
    "is two underscores, capitals, two underscores" in {
      ReservedNames.shaped("__FILE__") shouldBe true
      ReservedNames.shaped("__A__") shouldBe true
      ReservedNames.shaped("__A_B__") shouldBe true
      ReservedNames.shaped("__WITH_MANY_WORDS__") shouldBe true
    }

    "admits an empty middle, so the space is reserved before anything fills it" in {
      ReservedNames.shaped("____") shouldBe true
      ReservedNames.shaped("_____") shouldBe true
    }

    "needs both markers whole, which three underscores cannot supply" in {
      ReservedNames.shaped("___") shouldBe false
      ReservedNames.shaped("__") shouldBe false
      ReservedNames.shaped("_") shouldBe false
      ReservedNames.shaped("") shouldBe false
    }

    "is capitals only, so an ordinary name one letter away stays the author's" in {
      ReservedNames.shaped("__file__") shouldBe false
      ReservedNames.shaped("__File__") shouldBe false
      ReservedNames.shaped("__F1__") shouldBe false
    }

    "needs both ends, so C's half-reserved spellings are ordinary names here" in {
      ReservedNames.shaped("__FILE_") shouldBe false
      ReservedNames.shaped("_FILE__") shouldBe false
      ReservedNames.shaped("__errno_location") shouldBe false
      ReservedNames.shaped("my__name") shouldBe false
      ReservedNames.shaped("plain") shouldBe false
    }
  }

  "no declaration may take one" - {
    "a function" in {
      err("__F__() -> int = 1") should include("reserved for names the compiler answers")
    }

    "a type parameter" in {
      err("f[__T__](x: __T__) -> __T__ = x") should include("type parameter")
    }

    "a 'val'" in {
      err("val __V__: int = 1") should include("'val'")
    }

    // A `var` at the top of the *entry* file is a binding the program's own statements make rather
    // than a declaration the hoisting pass registers, so it is answered as a binding — which is what
    // it is there. A `val` beside it is hoisted, and says `'val'`.
    "a 'var', as the binding it is in an entry file" in {
      err("var __V__ = 1") should include("not a name a binding may take")
    }

    "a 'const'" in {
      err("const __C__: int = 1") should include("'const'")
    }

    "a struct, and a field of one" in {
      err("struct __S__\n    x: int") should include("type")
      err("struct P\n    __X__: int") should include("field")
    }

    "an enum, and a variant of one" in {
      err("enum __E__\n    A") should include("type")
      err("enum E\n    __A__") should include("variant")
    }

    "a trait" in {
      err("trait __T__\n    f(self) -> int") should include("trait")
    }

    "a type alias" in {
      err("type __T__ = int") should include("type")
    }

    "an 'extern', and a parameter of one — which nothing else would ever bind" in {
      err("""extern "puts" __P__(s: *u8) -> i32""") should include("declaration")
      err("""extern "puts" p(__S__: *u8) -> i32""") should include("parameter")
    }

    // An alias binds through the import tables rather than through `declare`, so this pass is the
    // only thing that can see one — and an alias squatting in the reserved space is exactly the
    // collision a later built-in would hit.
    "an import alias" in {
      err("import sysl.text as __TEXT__\nprint(1)") should include("import")
    }

    "an alias inside a selector list" in {
      err("import sysl.{text as __T__}\nprint(1)") should include("import")
    }
  }

  "nor may anything that binds a name inside a body" - {
    "a parameter" in {
      err("f(__A__: int) -> int = __A__") should include("reserved for names the compiler answers")
    }

    "a local" in {
      err("f() -> int\n    var __X__ = 1\n    __X__") should include("reserved for names the compiler answers")
    }

    "a 'for' element" in {
      err("f()\n    for __I__ in 0..<3 do print(__I__)") should
        include("reserved for names the compiler answers")
    }

    "a closure's parameter, which no declaration form would have caught" in {
      err("apply(f: int -> int) -> int = f(1)\nprint(apply(__X__ -> __X__))") should
        include("reserved for names the compiler answers")
    }
  }

  "a reserved name where no built-in could stand" - {
    "in a type position" in {
      err("var x: __FOO__ = 1") should include("__FOO__")
    }

    "assigned to" in {
      err("__FILE__ = 1") should include("__FILE__")
    }

    "called" in {
      err("print(__FILE__())") should include("__FILE__")
    }

    "at a type it is not" in {
      err("var n: int = __FILE__") should include("string")
    }
  }

  "the refusal names the shape rather than the six, since the name need not be one of them" in {
    val message = err("var __MY_FLAG__ = 1")

    message should include("__MY_FLAG__")
    message should include("whether or not it means anything yet")
    message should include("my_flag")
  }

  "a name with the shape and no meaning is an error rather than nothing" in {
    val message = err("print(__NOPE__)")

    message should include("no built-in called '__NOPE__'")
    message should include("__FILE__")
    message should include("__TIME__")
  }

  "a location is a literal, and is range-checked as one" in {
    err(("\n" * 300) + "var n: u8 = __LINE__\n") should include("does not fit")
  }

  "the build stamp" - {
    "renders the epoch in C's formats" in {
      ReservedNames.date(0L) shouldBe "Jan  1 1970"
      ReservedNames.time(0L) shouldBe "00:00:00"
    }

    "pads the day with a space rather than a zero, as C does" in {
      ReservedNames.date(86400000L) shouldBe "Jan  2 1970"
      ReservedNames.date(86400000L * 30) shouldBe "Jan 31 1970"
    }

    "gets a leap day right" in {
      ReservedNames.date(951782400000L) shouldBe "Feb 29 2000"
    }

    "reads a known timestamp as UTC" in {
      ReservedNames.date(1234567890000L) shouldBe "Feb 13 2009"
      ReservedNames.time(1234567890000L) shouldBe "23:31:30"
    }

    "is one stamp per compilation, so a program cannot disagree with itself" in {
      ReservedNames.stamp shouldBe ReservedNames.stamp
    }
  }
}
