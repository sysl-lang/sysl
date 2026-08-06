package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** Tier-2: what the six built-in identifiers evaluate to (`reserved-identifiers.md`).
 *
 * The load-bearing group is *the caller's location*, near the bottom. Everything above it is a
 * constant folded into its use and would be hard to get wrong; the thing worth pinning is that a
 * built-in written in a **default argument** reports the call rather than the declaration, because
 * that behaviour is not written anywhere in this feature — it falls out of `12 §2a`, and a change to
 * how defaults are spliced would take it away silently.
 */
class SourceLocationTests extends AnyFreeSpec with RunSupport {

  "__LINE__ is the line it is written on" in {
    run("""print(__LINE__)
          |print(__LINE__)
          |""".stripMargin) shouldBe "1\n2\n"
  }

  "__FILE__ is the name of the file it is written in" in {
    run("print(__FILE__)\n") shouldBe "<input>\n"
  }

  "__COLUMN__ is the column it starts at" in {
    run("print(__COLUMN__)\n") shouldBe "7\n"
  }

  "__FUNCTION__ is the enclosing function, as it was written" in {
    run("""adds_two() -> string = __FUNCTION__
          |print(adds_two())
          |""".stripMargin) shouldBe "adds_two\n"
  }

  "a built-in in an ordinary body reports its own line, not its caller's" in {
    run("""f() -> int = __LINE__
          |print(f())
          |print(f())
          |""".stripMargin) shouldBe "1\n1\n"
  }

  "__LINE__ takes the integer type its context asks for" in {
    run("""var small: u16 = __LINE__
          |narrow(n: i32) -> i32 = n
          |print(small, narrow(__LINE__))
          |""".stripMargin) shouldBe "1 3\n"
  }

  "the build stamp" - {
    "__DATE__ is C's eleven-character date" in {
      run("print(__DATE__.len)\n") shouldBe "11\n"
    }

    // Indexing a string yields the byte, so the separators are asserted as the code point 58 rather
    // than as ':' — which is the same check and says plainly what a string index gives back.
    "__TIME__ is C's eight-character clock, colons and all" in {
      run("""print(__TIME__.len)
            |print(__TIME__[2], __TIME__[5])
            |""".stripMargin) shouldBe "8\n58 58\n"
    }
  }

  "a default reports the CALLER, which is the whole point of the feature" - {
    "one call site" in {
      run("""where(line: int = __LINE__) -> int = line
            |print(where())
            |""".stripMargin) shouldBe "2\n"
    }

    "two call sites report their own lines from one declaration" in {
      run("""where(line: int = __LINE__) -> int = line
            |print(where())
            |print(where())
            |print(where(0))
            |""".stripMargin) shouldBe "2\n3\n0\n"
    }

    "an argument written out wins, as any argument does" in {
      run("""where(line: int = __LINE__) -> int = line
            |print(where(99))
            |""".stripMargin) shouldBe "99\n"
    }

    "__FILE__ in a default names the file the call is in" in {
      run("""whence(file: string = __FILE__) -> string = file
            |print(whence())
            |""".stripMargin) shouldBe "<input>\n"
    }

    "__FUNCTION__ in a default names the calling function" in {
      run("""who(name: string = __FUNCTION__) -> string = name
            |caller() -> string = who()
            |print(caller())
            |""".stripMargin) shouldBe "caller\n"
    }

    "the OUTERMOST call wins where a default fills another default" in {
      run("""inner(line: int = __LINE__) -> int = line
            |outer(n: int = inner()) -> int = n
            |print(outer())
            |""".stripMargin) shouldBe "3\n"
    }

    // The standard module is the first customer for this, and the reason the feature exists: its
    // `assert` required a message because the condition's source could not be printed. It can now.
    "the standard module's 'assert' names where it failed" in {
      panics("""var x = 1
               |assert(x == 2)
               |""".stripMargin, "assertion failed (<input>:2)")
    }

    "and still carries a message where one is given" in {
      panics("""assert(1 == 2, "one is not two")
               |""".stripMargin, "panic: one is not two (<input>:1)")
    }

    // `assert` forwards its own file and line to `panic` rather than letting `panic` fill them, so
    // the location is the reader's call and not the line inside `check.sysl` that calls `panic`.
    "a location forwarded through a helper stays the caller's" in {
      panics("""panic("direct")
               |""".stripMargin, "panic: direct (<input>:1)")
    }

    "a method's default reports its call site too" in {
      run("""struct P
            |    x: int
            |    at(self, line: int = __LINE__) -> int = line
            |var p = P(1)
            |print(p.at())
            |""".stripMargin) shouldBe "5\n"
    }
  }
}
