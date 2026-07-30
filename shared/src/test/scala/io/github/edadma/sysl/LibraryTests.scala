package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

/** The seam every question about the shipped library goes through (`Library`).
 *
 * Two of these tests are worth more than the rest and it is worth saying which. **A name the
 * compiler spells for itself is checked by nothing** — a program's `Option` is resolved and reported
 * on, while `instantiateEnum(Library.key("Option"), …)` on a name the library stopped declaring is an
 * exception at compile time rather than a diagnostic at any time. So both halves of that surface are
 * pinned here: the names written down in `Library.known`, and the renderer and mixer names
 * `CoreTraits` chooses per type, which are not written down anywhere and so are gathered by asking it.
 */
class LibraryTests extends AnyFreeSpec with Matchers with RunSupport {

  /** A program's own declarations, to be told from the library's. */
  private def parsed(src: String): List[Stmt] =
    SyslParser.parse(Source("<input>", src)) match
      case Right(p) => p.body
      case Left(e)  => fail(e)

  "whose declaration it is" - {

    "every declaration the library ships is its own" in {
      Library.decls should not be empty
      Library.decls.filterNot(Library.owns) shouldBe empty
    }

    "nothing a program declares is" in {
      // The two are keyed the same way — a headerless program's declarations are the root module's
      // exactly as the library's are — so the answer cannot come from the key, and this is the test
      // that says it does not.
      val mine = parsed("struct Ok\n    n: int\n\ndouble(n: int) -> int = n * 2\n")

      mine.length shouldBe 2
      mine.filter(Library.owns) shouldBe empty
    }

    "a declaration with no position at all is not the library's" in {
      // A synthesized node carries none, and `owns` is asked of nodes the compiler built as well as
      // of ones it parsed — a wrong answer here would hand a desugaring the library's scope.
      Library.owns(FuncDecl("f", Nil, Nil, None, Nil)) shouldBe false
    }
  }

  "a key and the spelling it stands for" - {

    "a spelling the prelude still holds makes the key it is filed under" in {
      Library.key("Option") shouldBe "Option"
      Modules.moduleOf(Library.key("Option")) shouldBe Modules.root
    }

    "a spelling the standard module holds makes that module's key" in {
      // The whole of what moving a declaration does, seen from the one place that answers for it.
      Library.key("FormatSpec") shouldBe Modules.qualify(Std.module, "FormatSpec")
    }

    "and the key gives the spelling back, from either part" in {
      Library.spelling(Library.key("Option")) shouldBe Some("Option")
      Library.spelling(Library.key("FormatSpec")) shouldBe Some("FormatSpec")
    }

    "a key belonging to another module has no library spelling" in {
      Library.spelling(Modules.qualify("geom", "Option")) shouldBe None
    }

    "and neither has a program's own root-module declaration of a name that has moved" in {
      // A program may write `struct FormatSpec` now, and it is keyed `FormatSpec` — which is where
      // the library's used to be. Asking which module a key is in would call that one the library's;
      // asking whether it is the key the library gives that spelling does not.
      Library.spelling("FormatSpec") shouldBe None
    }
  }

  "the standard module the library is being drained into" - {

    "says in its header what `Std.module` says" in {
      // `module` is a constant so that nothing has to parse to ask which module a name is in. This
      // is what holds it to the source it stands for.
      Std.parsed.module.map(_.show) shouldBe Some(Std.module)
    }

    "declares what it declares, and the prelude no longer does" in {
      Std.decls should not be empty
      Std.decls.forall(Library.owns) shouldBe true
      Prelude.decls.exists(Std.declares) shouldBe false
      Std.decls.exists(Prelude.declares) shouldBe false
    }

    "is a module every file may write the names of without importing it" in {
      Library.modules should contain(Std.module)
      AutoImport.modules should contain(Std.module)
    }
  }

  "what `?` unwraps" - {

    "the two enums, by the key each is filed under" in {
      Library.tryVariants(Library.key("Result")) shouldBe Some(("Ok", "Err"))
      Library.tryVariants(Library.key("Option")) shouldBe Some(("Some", "None"))
    }

    "nothing else" in {
      Library.tryVariants(Library.key("Buf")) shouldBe None
      Library.tryVariants("") shouldBe None
    }

    "and not another module's enum of the same name" in {
      // `?` reaches the library's `Option` and no other, which is the whole reason the base is
      // compared as a key rather than as a spelling.
      Library.tryVariants(Modules.qualify("geom", "Option")) shouldBe None
    }
  }

  "every name the compiler spells for itself is one the library declares" - {

    "the ones written down" in {
      // A name here that the library does not declare is a call that resolves to nothing, reached
      // from inside the compiler where no diagnostic is waiting for it.
      Library.known -- Library.declared shouldBe Set.empty
    }

    "and the traits the operator catalog names" in {
      CoreTraits.required.keySet -- Library.declared shouldBe Set.empty
    }

    "the renderer each built-in reaches, which is chosen per type rather than written down" in {
      val builtins = List(
        Type.Integer(8, signed = true),
        Type.Integer(64, signed = true),
        Type.Integer(64, signed = false),
        Type.Integer(128, signed = false),
        Type.Real,
        Type.Bool,
        Type.Char,
        Type.Str,
      )

      // Each of the eight has a renderer, and no two kinds share one by accident — the four
      // integer rows below are three distinct functions, which is what the widening is for.
      val renderers = builtins.flatMap(t => CoreTraits.display(t).map(_._1))

      renderers.length shouldBe builtins.length
      renderers.toSet.diff(Library.declared) shouldBe Set.empty
    }

    "and the mixer, for the types whose `Hash` the compiler provides" in {
      val hashable = List(
        Type.Integer(64, signed = true),
        Type.Integer(128, signed = false),
        Type.Char,
        Type.Bool,
        Type.Str,
      )
      val mixers = hashable.flatMap(t => CoreTraits.hash(t).map(_._1))

      mixers.length shouldBe hashable.length
      mixers.toSet.diff(Library.declared) shouldBe Set.empty
    }

    "a float has neither, which is the membership `Hash` deliberately withholds" in {
      CoreTraits.hash(Type.Real) shouldBe None
      CoreTraits.display(Type.Real).map(_._1) shouldBe Some("display_real")
    }
  }

  "the seam is what a program actually resolves through" - {

    "a library name arrives unqualified, and its key is the library's" in {
      // The compiler's own answer, read at the surface a program sees: `Option` in a signature is
      // the library's `Option`, filed under the key `Library.key` gives.
      val ir = Compiler.compileToLlvm("f(n: int) -> Option[int] = Some(n)\nprint(f(1).unwrap())")

      ir.isRight shouldBe true
    }

    "and a program may not declare one of its own over it, while it is still the prelude's" in {
      // A name the prelude holds shares the program's namespace, so this is a clash rather than
      // shadowing. Pinned because it is exactly what changes when the declaration moves, and the
      // change should be a failing test rather than a surprise — see the group below for the
      // other side of it.
      Compiler.compileToLlvm("enum Option\n    Yes\n    No\n") match
        case Left(e)  => e should include("already declared")
        case Right(_) => fail("a program redeclared 'Option' and was not told")
    }
  }

  "a declaration that has moved into the standard module" - {

    "arrives unqualified, with no import written" in {
      // The auto-import, on a declaration the compiler also names for itself — an `impl Display`
      // has to write `FormatSpec` in the signature it is matching against.
      run(
        """struct P
          |    x: int
          |
          |impl Display for P
          |    display(self, w: *Writer, spec: FormatSpec) = str(self.x).display(w, spec)
          |
          |print(P(6))
          |""".stripMargin) shouldBe "6\n"
    }

    "is the same type by its full path as by the name that arrives" in {
      // The signature says `FormatSpec` and the call says `sysl.FormatSpec`; two types would not
      // meet in the middle, so this passing is what says the wildcard reaches the module's own.
      run("show(spec: FormatSpec) -> int = spec.width\nprint(show(sysl.FormatSpec(5, -1, false)))")
        .shouldBe("5\n")
    }

    "is what a format hole builds, which the compiler names rather than resolves" in {
      // `Library.key("FormatSpec")` and the `FormatSpec` the prelude's `display_str` wrote have to
      // be one type, or this is a signature mismatch inside the library.
      run("print(f\"[${str(42)}%5s]\")\nprint(f\"[${1.5}%8.3f]\")") shouldBe "[   42]\n[   1.500]\n"
    }

    "and a program may now declare one of its own beside it" in {
      // The other side of the clash pinned above: a moved declaration is a *module's*, so `13 §3`
      // gives the program's own the unqualified spelling and the library's is still reached by path
      // — including by the compiler, which is what the format hole in the same program proves.
      run(
        """struct FormatSpec
          |    n: int
          |
          |var mine = FormatSpec(3)
          |print(mine.n)
          |print(f"[${str(42)}%5s]")
          |""".stripMargin) shouldBe "3\n[   42]\n"
    }

    "which does not reach into the library, whose own signatures still mean the library's" in {
      // The prelude is in the root module and so is a headerless program, so "this module first"
      // would hand `display_pad(text, out, fmt: FormatSpec)` the *program's* struct — and the whole
      // printing surface would fail inside the library against a type its source never named. The
      // rendering below is what walks every one of those signatures.
      run(
        """struct FormatSpec
          |    n: int
          |
          |struct P
          |    x: int
          |
          |impl Display for P
          |    display(self, w: *Writer, spec: sysl.FormatSpec) = str(self.x).display(w, spec)
          |
          |print(FormatSpec(3).n)
          |print(P(6))
          |""".stripMargin) shouldBe "3\n6\n"
    }

    "and writing the shadowed one where the library's is wanted says which is which" in {
      refused(
        """struct FormatSpec
          |    n: int
          |
          |struct P
          |    x: int
          |
          |impl Display for P
          |    display(self, w: *Writer, spec: FormatSpec) = str(self.x).display(w, spec)
          |""".stripMargin) should include(
        "parameter 'spec' of method 'display' is FormatSpec, but trait 'sysl.Display' declares sysl.FormatSpec")
    }
  }

  "a moved declaration whose own signature names one that has not moved" - {

    // `Display.display` is declared `(self, out: *Writer, fmt: FormatSpec)`: `FormatSpec` is beside it
    // in the standard module and `Writer` is still the prelude's, so matching an `impl` against it
    // reads one name in each part. That direction — a standard-module declaration reaching back into
    // the prelude — is what this group is for, and nothing before the move exercised it.

    "an impl matches the trait's signature across both parts of the library" in {
      run(
        """struct P
          |    x: int
          |
          |impl Display for P
          |    display(self, out: *Writer, fmt: FormatSpec) = str(self.x).display(out, fmt)
          |
          |print(P(4))
          |""".stripMargin) shouldBe "4\n"
    }

    "the sink it names is still the prelude's, which a program may not declare over" in {
      // The clash is what protects `Writer` until it moves, and this is the pin that says so — the
      // marker for the next move, and for the test that becomes writable then: with `Writer` in the
      // standard module a program may have one of its own, and `Display.display` must go on meaning
      // the library's. That is the failure `FormatSpec` had, pointing the other way, and it cannot
      // happen while this test passes.
      Library.key("Writer") shouldBe "Writer"
      Modules.moduleOf(Library.key("Writer")) shouldBe Modules.root

      refused("trait Writer\n    log(self) -> int\n") should include("already declared")
    }

    "a program may declare a 'Display' of its own, and the library's is still what print asks for" in {
      // The name clash is gone the moment the trait moves, so `Display` unqualified is the program's
      // here (`13 §3` — own module first) and has nothing to do with rendering.
      run(
        """trait Display
          |    describe(self) -> int
          |
          |struct P
          |    x: int
          |
          |impl Display for P
          |    describe(self) -> int = self.x * 2
          |
          |impl sysl.Display for P
          |    display(self, out: *Writer, fmt: FormatSpec) = str(self.x).display(out, fmt)
          |
          |print(P(5).describe())
          |print(P(5))
          |""".stripMargin) shouldBe "10\n5\n"
    }

    "the bound that advice names is one a program can actually write" in {
      // `'print' needs 'T: sysl.Display'` is advice, and a bound reaches its trait through
      // `resolveBound` rather than through the type path `sysl.FormatSpec` takes — a different
      // lookup, and one nothing had asked a qualified name of. Advice that does not compile when
      // followed is worse than no advice.
      run("show[T: sysl.Display](x: T) = print(x)\nshow(7)\nshow(\"s\")") shouldBe "7\ns\n"
    }

    "and the advice names the trait by the path that reaches it, not by the shadowed spelling" in {
      // Two traits spelled `Display`, and the one `print` needs is the one the program did not
      // implement. Advice to `write an 'impl Display for P'` would have the program implement its
      // own a second time and be refused again for the same reason — so the name in the message is
      // the key, which is what the move made different from the spelling.
      refused(
        """trait Display
          |    describe(self) -> int
          |
          |struct P
          |    x: int
          |
          |impl Display for P
          |    describe(self) -> int = self.x
          |
          |print(P(5))
          |""".stripMargin) should include("write an 'impl sysl.Display for P' to say how it renders")
    }
  }

  "the standard module is the library's, and a program may not add to it" - {

    "a file declaring it is refused" in {
      // A module's declarations are one set however many files they came from, so this would not be
      // a module beside the standard one — it would be the standard one, with the program's names
      // in it.
      refusedOf(
        Source("extra.sysl", "module sysl\n\nstruct Extra\n    n: int\n", List("sysl")),
        Source("main.sysl", "print(1)"),
      ) should include("is the module every program is compiled against")
    }

    "and so is one that redeclares a name it already carries" in {
      refusedOf(
        Source("extra.sysl", "module sysl\n\nstruct FormatSpec\n    n: int\n", List("sysl")),
        Source("main.sysl", "print(1)"),
      ) should include("is the module every program is compiled against")
    }
  }

  private def refused(program: String): String =
    Compiler.compileToLlvm(program) match
      case Left(err) => err
      case Right(_)  => fail("the program compiled, and should not have")

  private def refusedOf(sources: Source*): String =
    Compiler.compile(sources.toList) match
      case Left(err) => err
      case Right(_)  => fail("the program compiled, and should not have")
}
