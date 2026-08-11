package sh.sysl

import io.github.edadma.cross_platform.*

/** Two declarations of one name, and which of them a use means — `12 §1a`.
 *
 * The claim this file holds is that **a name may stand for several functions and every use of it
 * still names exactly one**. That is checked by running rather than by reading IR wherever it can
 * be: each declaration answers differently, so a call reaching the wrong one prints the wrong thing
 * rather than failing to compile, and no accident produces the right answer.
 *
 * The refusals are the other half and they divide in two. A pair no call could tell apart is refused
 * **at the declaration**, because the mistake is in the pair and reporting it at each use would
 * report one mistake many times. A use that fits none or several is refused **at the use**, because
 * that is where the mistake is.
 */
class OverloadTests extends LibraryCliSupport with RunSupport with CodegenSupport {

  "a name may be declared more than once" - {
    // Arity is the plainest case and the one a binding wants: SDL_ttf renders text at one colour or
    // at two, and the pair reads as one operation with an option rather than as `render_shaded`.
    "and a call chooses by how many arguments it passes" in {
      run("""paint(x: int) -> string = s"one $x"
            |paint(x: int, y: int) -> string = s"two $x $y"
            |
            |print(paint(1))
            |print(paint(1, 2))""".stripMargin) shouldBe "one 1\ntwo 1 2\n"
    }

    "or by what type they are" in {
      run("""show(x: int) -> string = s"int $x"
            |show(x: string) -> string = s"str $x"
            |show(x: bool) -> string = s"bool $x"
            |
            |print(show(1))
            |print(show("a"))
            |print(show(true))""".stripMargin) shouldBe "int 1\nstr a\nbool true\n"
    }

    // The case `13` records as the reason `sysl.math` could not be a module of free functions: one
    // name over two float widths. It is the tie-break on exactness that decides this — a literal fits
    // both, and its own type is what says which was meant.
    "including two widths of one number, told apart by the literal's own type" in {
      run("""width(x: int) -> string = "int"
            |width(x: i64) -> string = "i64"
            |
            |print(width(1))
            |print(width(1i64))""".stripMargin) shouldBe "int\ni64\n"
    }

    // Three or more, since a set of two could be told apart by a rule that compared one against the
    // other rather than by choosing among candidates.
    "and there may be more than two of them" in {
      run("""at(a: int) -> string = "1"
            |at(a: int, b: int) -> string = "2"
            |at(a: int, b: int, c: int) -> string = "3"
            |
            |print(at(1) + at(1, 2) + at(1, 2, 3))""".stripMargin) shouldBe "123\n"
    }

    // Overloads declared across two files of one module, which is what makes this a fact about the
    // module rather than about a file. A reader adding a width to a library adds a file.
    "across the files of one module, which is where a name lives" in {
      runIn(
        ("", "main.sysl", "print(m.pick(1))\nprint(m.pick(\"a\"))"),
        ("m", "a.sysl", "module m\n\npick(x: int) -> string = \"int\""),
        ("m", "b.sysl", "module m\n\npick(x: string) -> string = \"str\""),
      ) shouldBe "int\nstr\n"
    }

    // A name declared once must take exactly the path it always did — the fast path is a property
    // worth pinning, since a regression in it would be a slowdown rather than a failure.
    "while a name declared once is unchanged, defaults and all" in {
      run("""only(a: int, b: int = 10) -> int = a + b
            |print(str(only(1)), str(only(1, 2)))""".stripMargin) shouldBe "11 3\n"
    }
  }

  "an address is of one of them, read off the type the context wants" - {
    "which the expected type settles" in {
      run("""add(a: int) -> int = a + 1
            |add(a: int, b: int) -> int = a + b
            |
            |val one: *extern(int) -> int = &add
            |val two: *extern(int, int) -> int = &add
            |
            |print(str(one(41)), str(two(6, 7)))""".stripMargin) shouldBe "42 13\n"
    }

    // The declaration order must not decide it, which is the thing an implementation taking the
    // first candidate would pass the test above on.
    "whichever order they were declared in" in {
      run("""add(a: int, b: int) -> int = a + b
            |add(a: int) -> int = a + 1
            |
            |val one: *extern(int) -> int = &add
            |print(str(one(41)))""".stripMargin) shouldBe "42\n"
    }

    "and with no expected type to read, it says so rather than choosing" in {
      err("""add(a: int) -> int = a + 1
            |add(a: int, b: int) -> int = a + b
            |
            |var f = &add""".stripMargin) should include("read off the type the context wants")
    }
  }

  "a bare name used as a callable chooses the same way" in {
    // Same arity on both, so only the parameter's *type* can have decided it — an implementation
    // taking the first candidate reaches `tag(s: string)` and fails.
    run("""tag(s: string) -> int = 99
          |tag(a: int) -> int = a + 1
          |
          |apply(f: int -> int, x: int) -> int = f(x)
          |
          |print(str(apply(tag, 41)))""".stripMargin) shouldBe "42\n"
  }

  "an 'extern' overloads, and the symbol is what keeps them apart" - {
    // The one case that has to reach a real linker, since the whole claim is about which symbol each
    // call resolves to. The two C functions answer differently for the same input, so a call reaching
    // the wrong one prints the wrong number rather than failing to build.
    "so two of them naming two symbols are two functions" in {
      assume(Toolchain.clangAvailable, "clang not available")

      val root = createTempDirectory("sysl-overload-")

      writeFile(s"$root/shim.c",
        "int probe_one(int a) { return a + 1; }\nint probe_two(int a, int b) { return a * b; }\n")
      writeFile(s"$root/main.sysl",
        """extern "probe_one" bump(a: int) -> int
          |extern "probe_two" bump(a: int, b: int) -> int
          |
          |print(str(bump(41)), str(bump(6, 7)))
          |""".stripMargin)

      ran(Config(command = "run", file = root)) shouldBe "42 42\n"
    }

    // One C function claimed at two signatures. Nothing downstream could tell the two apart, since
    // the symbol is what is emitted — both calls would reach the same code with different arguments.
    "while two naming ONE symbol are refused" in {
      err("""extern "probe_one" bump(a: int) -> int
            |extern "probe_one" bump(a: int, b: int) -> int""".stripMargin) should
        include("one C function cannot be two")
    }

    // A sysl function has no symbol to be told apart by, so the two kinds do not mix. Asked both
    // ways round, because the check is written in two places and one of them could be missing.
    "and an 'extern' may not overload a sysl function" in {
      err("""bump(a: int) -> int = a + 1
            |extern "probe_two" bump(a: int, b: int) -> int""".stripMargin) should
        include("what tells overloads of an 'extern' apart is the symbol each names")
    }

    "nor a sysl function an 'extern'" in {
      err("""extern "probe_two" bump(a: int, b: int) -> int
            |bump(a: int) -> int = a + 1""".stripMargin) should
        include("what tells overloads of an 'extern' apart is the symbol each names")
    }
  }

  "a pair no call could tell apart is refused where it is written" - {
    // Resolution never looks at the result, so this pair has no call that distinguishes them. Refused
    // at the declaration: the mistake is in the pair, and every use of the name would otherwise
    // report the same ambiguity.
    "so two differing only in what they return are refused" in {
      val e = err("""h(x: int) -> string = "s"
                    |h(x: int) -> int = 1""".stripMargin)

      // Told as the duplicate it is — the parameters are the same, so it is one declaration written
      // twice — with the sentence finished, because this is the pair a reader wrote on purpose.
      e should include("function 'h' is already declared")
      e should include("never by what it returns")
    }

    // And the same parameters with the same result is the plain duplicate, which gets the plain
    // message: somebody who wrote one function twice is not owed a paragraph about overloading.
    "while an outright duplicate is told it is one, with nothing about overloads" in {
      val e = err("""h(x: int) -> string = "a"
                    |h(x: int) -> string = "b"""".stripMargin)

      e should include("function 'h' is already declared")
      e should not include "what it returns"
    }

    // The second's default is unreachable — no call can supply one argument to it, because the first
    // takes that call. A default nothing can use is worth saying out loud.
    "as are two whose difference is behind a default" in {
      err("""g(x: int) -> string = "one"
            |g(x: int, y: int = 0) -> string = "two"""".stripMargin) should
        include("could not be told from")
    }

    // The ranges overlap only where the types agree, so this pair is fine: a call of one argument
    // reaches the first, and one of two reaches the second whatever its first argument is.
    "though a default is fine where the overlap has different types" in {
      run("""g(x: int) -> string = "one"
            |g(x: string, y: int = 0) -> string = s"two $y"
            |
            |print(g(1), g("a"), g("a", 5))""".stripMargin) shouldBe "one two 0 two 5\n"
    }
  }

  "a use that fits none or several is refused where it is written" - {
    "with the roster, when nothing takes these arguments" in {
      val e = err("""k(x: int) -> string = "a"
                    |k(x: string) -> string = "b"
                    |print(k(1.5))""".stripMargin)

      e should include("no 'k' takes these arguments")
      e should include("k(x: int)")
      e should include("k(x: string)")
    }

    // Where exactly one declaration could have taken this many arguments, the reader meant that one
    // and got a type wrong — so its own complaint is the useful message and the roster is not.
    "and with the callee's own complaint, where only one could have been meant" in {
      err("""k(x: int) -> string = "a"
            |k(x: string, y: string) -> string = "b"
            |print(k(1.5))""".stripMargin) should include("k(x: int)")
    }
  }

  // C has no overloading, so an overload set has at most one member that may take its own name as a
  // symbol. Caught by name rather than left to the rule that an export's symbol must be a C
  // identifier — which does refuse it, and does so quoting `pick.2`, a spelling the source does not
  // contain.
  "an overload exported under its own name is refused, and not by accident" in {
    val e = err("""@export
                  |pick(a: int) -> int = a + 1
                  |
                  |@export
                  |pick(a: int, b: int) -> int = a + b""".stripMargin)

    e should include("C has no overloading")
    e should not include "pick.2"
  }

  "though each may be exported under a symbol of its own" in {
    val out = ir("""@export("pick_one")
                   |pick(a: int) -> int = a + 1
                   |
                   |@export("pick_two")
                   |pick(a: int, b: int) -> int = a + b""".stripMargin)

    out should include("@pick_one")
    out should include("@pick_two")
  }

  // **An overload set has to survive being shipped**, which is a different path from every test
  // above: a library is compiled once, archived, and hoisted again out of the artifact by whoever
  // links it. Nothing serializes the set — it is rebuilt by hoisting, exactly as it is from source —
  // so this is what says that rebuilding really happens. The two declarations answer differently, so
  // a consumer reaching the wrong one prints the wrong thing.
  "an overload set survives being built into a library and linked against" in {
    val lib = rootOf(
      "demo",
      """module demo
        |
        |scale(n: int) -> string = s"one $n"
        |scale(n: int, by: int) -> string = s"two ${n * by}"
        |""".stripMargin,
    )

    val prog = program("print(demo.scale(3))\nprint(demo.scale(3, 4))")

    ran(Config(command = "run", file = prog, libs = List(artifactOf(lib)))) shouldBe "one 3\ntwo 12\n"
  }

  // `main` is the one name it must not reach: nothing calls `main`, so there is no call site to
  // choose between two of them, and the entry point is found by asking which declarations are called
  // that. A second would be filed under a key of its own and silently never run.
  "'main' does not overload, since a program has one beginning" in {
    err("""main()
          |    print("one")
          |
          |main(args: []string)
          |    print("two")""".stripMargin) should include("a program has one beginning")
  }

  "and the compiler's spelling of an overload never reaches a reader" in {
    // The second declaration is keyed `paint.2` so that the tables stay one-declaration-per-key. A
    // diagnostic naming it that would be naming something the source does not contain.
    val e = err("""paint(x: int) -> string = "a"
                  |paint(x: string) -> string = "b"
                  |print(paint(1.5))""".stripMargin)

    e should not include "paint.2"
  }
}
