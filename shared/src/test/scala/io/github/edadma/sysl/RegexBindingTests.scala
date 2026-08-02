package io.github.edadma.sysl

/** The POSIX regex binding in `bindings/regex`, built as a library and run against the C library.
 *
 * Nothing is linked for this one: POSIX regex is part of libc, so `rx.sysl` has no `link` directive
 * and there is no package a machine could be missing. What it does have is `regex.c`, and every
 * number the header owns stays in there — which is the whole reason the binding is shaped this way
 * and the thing that would break silently if the shim stopped being compiled for the same target.
 *
 * `LibraryBuildCliTests` pins that mechanism on a fixture. This pins the file that ships: the
 * surface `rx.sysl` offers a caller, and the offsets the C library really answers.
 */
class RegexBindingTests extends BindingSupport {

  protected val binding = "regex"

  "the checked-in tree" - {

    "builds into an artifact carrying both its sysl and its C" in {
      guard()

      metadata should include("rx$compile")
      metadata should include("rx$Regex.find")

      carriesObject("rx.regex.o") shouldBe true
    }
  }

  "a compiled pattern" - {

    "answers where the match landed, at offsets only real matching gives" in {
      // Chosen so that nothing but POSIX matching produces these numbers. The match starts and ends
      // mid-string, so a binding answering `0..len` to anything that matched at all fails; the
      // bounded repeat has to stop at three digits where four are available, so one that ran to the
      // end of the run fails; and the anchored pattern has to refuse a subject it appears inside.
      out(
        """import rx.*
          |
          |go()
          |
          |go()
          |    val digits = compile("[0-9]{2,3}").expect("digits")
          |
          |    print(digits.matches("abc1234"))
          |    print(digits.matches("abc"))
          |
          |    digits.find("abc1234") match
          |        Some(m) -> print(f"${m.start}..${m.end}")
          |        None    -> print("none")
          |
          |    digits.free()
          |
          |    val anchored = compile("^abc$").expect("anchored")
          |
          |    print(anchored.matches("xabcx"))
          |    print(anchored.matches("abc"))
          |    anchored.free()
          |end go
          |""".stripMargin) shouldBe "true\nfalse\n3..6\nfalse\ntrue\n"
    }

    "and an empty match is a place rather than nothing" in {
      // `x*` matches at offset 0 without consuming anything, which is a `Some` whose start and end
      // are equal — not the `None` that a binding testing the length rather than the return code
      // would give.
      out(
        """import rx.*
          |
          |go()
          |
          |go()
          |    val re = compile("x*").expect("compile")
          |
          |    re.find("yyy") match
          |        Some(m) -> print(f"${m.start}..${m.end}")
          |        None    -> print("none")
          |
          |    re.free()
          |end go
          |""".stripMargin) shouldBe "0..0\n"
    }

    "while a pattern that will not compile answers the C library's own words" in {
      // A repeat whose bounds are the wrong way round. The wording is BSD's or glibc's and differs
      // between them, so only that a sentence arrived is pinned — an empty string here would mean
      // `regerror` was called with a buffer it never wrote into.
      val lines = out(
        """import rx.*
          |
          |compile("a{3,1}") match
          |    Ok(_)    -> print("compiled a pattern that is not one")
          |    Err(why) -> print(f"error: ${why}")
          |""".stripMargin).linesIterator.toList

      lines.length shouldBe 1
      lines.head should startWith("error: ")
      lines.head.length should be > "error: ".length
    }
  }
}
