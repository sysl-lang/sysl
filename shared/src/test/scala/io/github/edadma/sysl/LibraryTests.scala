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
class LibraryTests extends AnyFreeSpec with Matchers {

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

    "a spelling makes the key the tables hold it under" in {
      Library.key("Option") shouldBe Modules.qualify(Library.module, "Option")
    }

    "and the key gives the spelling back" in {
      Library.spelling(Library.key("Option")) shouldBe Some("Option")
    }

    "a key belonging to another module has no library spelling" in {
      Library.spelling(Modules.qualify("geom", "Option")) shouldBe None
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

    "and a program may not declare one of its own over it" in {
      // Today the library shares the program's namespace, so this is a clash rather than shadowing.
      // Pinned because it is exactly what changes when the library becomes a module arriving by
      // wildcard, and the change should be a failing test rather than a surprise.
      Compiler.compileToLlvm("enum Option\n    Yes\n    No\n") match
        case Left(e)  => e should include("already declared")
        case Right(_) => fail("a program redeclared 'Option' and was not told")
    }
  }
}
