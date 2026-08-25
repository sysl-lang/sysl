package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** `val` as a local, what it refuses, and the name it takes (`07`, `reference/modules.md § val — a thing`).
 *
 * Split out of `ValTests`, which is about the module member. The three subjects here are the ones
 * that are *not* about laying storage into the object file: a `val` inside a body, where the binding
 * is the whole of it; the writes and rebindings refused at every depth; and the collisions with a
 * `const` or a pattern of the same name.
 */
class ValLocalTests extends AnyFreeSpec with CodegenSupport with RunSupport {

  "a local 'val'" - {
    "binds like a 'var'" in {
      run("twice() -> int\n    val n = 4\n    n * 2\nend twice\nprint(str(twice()))") shouldBe "8\n"
    }

    "infers its type from its value" in {
      run("narrow() -> u8\n    val b = 3u8\n    b\nend narrow\nprint(str(narrow()))") shouldBe "3\n"
    }

    "holds an array, which may then be indexed" in {
      run(
        """f() -> int
          |    val xs = [4, 5, 6]
          |    xs[0] + xs[2]
          |end f
          |print(str(f()))""".stripMargin,
      ) shouldBe "10\n"
    }

    // Inside a block it is a local of that block, so the top-level one it shadows is untouched.
    "shadows a module-level one for the rest of its block" in {
      run(
        """static val n: int = 1
          |f() -> int
          |    val n = 50
          |    n
          |end f
          |print(str(f() + n))""".stripMargin,
      ) shouldBe "51\n"
    }
  }

  // Every shape here was **refused** until the rule behind the refusal was read again: storage that
  // lasts the whole run has no line to write a release on, which is true and is a description of a
  // static rather than an argument against one. The group is kept — the same five shapes, asked the
  // other way round — because what it guards now is that each really is *held* rather than merely
  // accepted: storage that failed to take a count of its own reads as freed bytes, not as an error.
  "what a counted value in one does, now that it may be held" - {
    "a string built while the program runs" in {
      run("static val s: string = str(1)\nprint(s)") shouldBe "1\n"
    }

    // Two literals joined looks constant and is not, because joining them allocates. It is the
    // discriminating case against a literal, which the object file carries as it stands.
    "a string joined from two literals, which allocates however constant it looks" in {
      run("static val s: string = \"a\" + \"b\"\nprint(s)") shouldBe "ab\n"
    }

    "a reference, which is the count itself" in {
      run("struct P\n    x: int\nend P\nmk() -> &P = P(1)\nstatic val r: &P = mk()\nprint(r.x)") shouldBe "1\n"
    }

    "a view, whose owner word is a count like any other" in {
      run("static val k: [4]int = [1, 2, 3, 4]\nstatic val s: []const int = k[1..<3]\nprint(s[0], s.len)") shouldBe
        "2 2\n"
    }

    // A struct carrying an `invariant` has to be *checked*, and a check is code — so this is a
    // computed initializer whatever it looks like, and the check runs before the first statement.
    "a struct whose invariant has to run, even with a literal in it" in {
      run(
        """struct Tag
          |    name: string
          |    invariant name.len > 0
          |end Tag
          |static val t: Tag = Tag("uart")
          |print(t.name)""".stripMargin,
      ) shouldBe "uart\n"
    }

    // And that invariant is a real check rather than a formality, which is also what says the
    // initializer ran at all: the same declaration with a value that breaks it stops the program.
    "and that invariant still runs, before the program's own statements" in {
      exits(
        """struct Tag
          |    name: string
          |    invariant name.len > 0
          |end Tag
          |static val t: Tag = Tag("")
          |print("never")""".stripMargin,
      )
    }
  }

  "what it refuses" - {
    "assigning to a module-level 'val'" in {
      err("static val n: int = 1\nn = 2") should include("written once")
    }

    "assigning to one of its elements" in {
      err("static val k: [2]int = [1, 2]\nk[0] = 9") should include("written once")
    }

    "compound assignment, which is an assignment" in {
      err("static val k: [2]int = [1, 2]\nk[1] += 1") should include("written once")
    }

    "an increment, for the same reason" in {
      err("static val k: [2]int = [1, 2]\nk[0]++") should include("written once")
    }

    // A `*T` is a licence to write, so handing one out would move the mistake one step from where
    // it could still be reported.
    "taking its address" in {
      err("static val k: [2]int = [1, 2]\nvar p = &k[0]") should include("written once")
    }

    "assigning to a local 'val'" in {
      err("f() -> int\n    val n = 1\n    n = 2\n    n\nend f\nprint(str(f()))") should include("written once")
    }

    "assigning through a field of one" in {
      err(
        """struct P
          |    x: int
          |end P
          |f() -> int
          |    val p = P(1)
          |    p.x = 2
          |    p.x
          |end f
          |print(str(f()))""".stripMargin,
      ) should include("written once")
    }

    // Slicing is no longer among them: a view of a `val` is a `[]const T`, which carries the
    // read-only-ness rather than losing it, so what is refused is the write through the view.
    "writing through a view of one, which is where the refusal moved when slicing became legal" in {
      run("static val k: [4]int = [1, 2, 3, 4]\nvar s = k[1..<3]\nprint(s[0], s[1])") shouldBe "2 3\n"

      err("static val k: [4]int = [1, 2, 3, 4]\nvar s = k[1..<3]\ns[0] = 9") should
        include("views elements it may not write")
    }

    "a module-level 'val' with no type, which every module member states" in {
      err("static val k = [1, 2, 3]") should include("states its type")
    }

    "a value computed from a variable" in {
      err("var x = 1\nstatic val n: int = x") should include("undefined name")
    }

    "an initializer that does not fit its declared type" in {
      err("static val k: [2]u8 = [1, 300]") should include("does not fit")
    }

    "an initializer of the wrong shape" in {
      err("static val k: [3]int = [1, 2]") should include("[3]int")
    }

    // The line between the two declarations, from the other side: a `const` sizes an array because
    // it is a value, and a `val` cannot because it is storage.
    "naming one as an array's bound" in {
      err("static val n: usize = 4\nvar bad: [n]int") should include("must be a constant")
    }

    // `reference/modules.md § const — a value` argues that sysl cannot have Rust's trap where a name in a pattern quietly binds
    // instead of matching. A `val` is the one thing that could have reintroduced it, so a **bare**
    // name is refused — and the diagnostic now names the backticked form, which says the test was
    // meant (`09`).
    "matching against one with a bare name, which would bind instead of compare" in {
      err("static val n: int = 1\nvar x = 2\nx match\n    n -> print(1)\n    else print(2)") should
        include("would bind rather than match")
    }

    "and the backticked form is what tests it" in {
      run("static val n: int = 1\nvar x = 2\nx match\n    `n` -> print(1)\n    else print(2)") shouldBe "2\n"
    }
  }

  "the name it takes" - {
    "clashes with a constant of that name" in {
      err("const n: int = 1\nstatic val n: int = 2") should include("already used by a constant")
    }

    "clashes with a function of that name" in {
      err("static val n: int = 1\nn() -> int = 2") should include("already declared as a 'val'")
    }

    "clashes with an enum variant of that name" in {
      err("enum Colour\n    Red\nend Colour\nstatic val Red: int = 1") should include("already used by enum")
    }

    "and a second 'val' of that name" in {
      err("static val n: int = 1\nstatic val n: int = 2") should include("already declared")
    }

    "while a constant written over one is reported the other way round" in {
      err("static val n: int = 1\nconst n: int = 2") should include("already used by a 'val'")
    }
  }
}
