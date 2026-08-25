package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** A trait's members are resolved where the **trait** is written, not where something implements it.
 *
 * Every other kind of member lowers to a function, and a function's signature is resolved when it is
 * hoisted — so an unknown type in one is reported at the declaration. A trait's members lower to
 * nothing: they are a promise, and the only thing that used to read them was the conformance check
 * an `impl` runs. A trait nobody implemented could therefore promise a type that does not exist.
 *
 * `Self` and the trait's own parameters stand in for themselves here, which is what the signature
 * means — it holds for every implementing type. So the names are what this checks, and everything
 * that depends on knowing *which* type `Self` is stays with the conformance check.
 */
class TraitSignatureTests extends AnyFreeSpec with CodegenSupport with RunSupport {

  /** The message with its caret line, for the one test that is about *where* the report lands. */
  private def located(src: String): String =
    Compiler.compileToLlvm(src, "t.sysl") match
      case Left(e)  => e
      case Right(_) => fail(s"expected an error from:\n$src")

  "a name that stands for nothing is reported at the trait" - {
    "in a parameter, with nothing implementing the trait" in {
      err("trait R\n    put(self, h: Nope) -> int\nprint(1)") should include("unknown type 'Nope'")
    }

    "in the result" in {
      err("trait R\n    take(self) -> Nope\nprint(1)") should include("unknown type 'Nope'")
    }

    "inside a type argument, where a shallower walk would miss it" in {
      err("trait R\n    all(self) -> Option[Nope]\nprint(1)") should include("unknown type 'Nope'")
    }

    "behind a memory mode" in {
      err("trait R\n    borrow(self, h: &Nope) -> int\nprint(1)") should include("unknown type 'Nope'")
    }

    "in a property, which has a result and no parameters at all" in {
      err("trait R\n    size -> Nope\nprint(1)") should include("unknown type 'Nope'")
    }

    // The position is the whole point: a reader is sent to the type they wrote, not to the trait's
    // first line and not to some `impl` in another file.
    "and the report lands on the name that was written" in {
      located("trait R\n    put(self, h: Nope) -> int\nprint(1)\n") should include("--> t.sysl:2:18")
    }
  }

  "what stands for itself is left alone" - {
    // Each of these would resolve to nothing if the substitution were wrong, so together they are
    // the check that this pass asks only about names.
    "`Self`, alone and inside a type argument and as a result" in {
      run(
        """trait Cloneable
          |    dup(self) -> Self
          |    maybe(self) -> Option[Self]
          |    take(self, other: Self) -> int
          |struct P
          |    v: int
          |impl Cloneable for P
          |    dup(self) -> P = self
          |    maybe(self) -> Option[P] = Some(self)
          |    take(self, other: P) -> int = self.v + other.v
          |print(P(2).take(P(3)))""".stripMargin
      ) shouldBe "5\n"
    }

    "the trait's own type parameters" in {
      run(
        """trait Sink[T]
          |    push(*self, x: T) -> int
          |    peek(self) -> Option[T]
          |struct Box
          |    n: int
          |impl Sink[int] for Box
          |    push(*self, x: int) -> int
          |        self.n = self.n + x
          |        self.n
          |    peek(self) -> Option[int] = Some(self.n)
          |var b = Box(1)
          |print(b.push(4))""".stripMargin
      ) shouldBe "5\n"
    }

    // A trait object formed from a signature whose `Self` is not yet known is the one shape the
    // resolver refuses erasure for, so a trait returning `&OtherTrait` is the case that would break
    // if this pass asked for more than the names.
    "a trait object in a trait's own signature" in {
      run(
        """trait Show
          |    show(self) -> string
          |trait Render
          |    render(self) -> &Show
          |struct P
          |    v: int
          |impl Show for P
          |    show(self) -> string = "p"
          |f(s: &Show) -> string = s.show()
          |print(f(P(1)))""".stripMargin
      ) shouldBe "p\n"
    }

    // A result list is a signature form and not a type (`reference/declarations.md § Several
    // results`), so it goes through the return resolver rather than the type resolver — the one
    // shape that would have been refused if this pass had reached for `resolveType` on everything.
    "a result list, which is a signature form and not a type" in {
      run(
        """trait Split
          |    halves(self) -> int, int
          |struct P
          |    v: int
          |impl Split for P
          |    halves(self) -> int, int = self.v / 2, self.v - self.v / 2
          |show(p: P)
          |    val a, b = p.halves()
          |    print(a, b)
          |show(P(7))""".stripMargin
      ) shouldBe "3 4\n"
    }

    "a required trait's parameters, read through the requirement" in {
      run(
        """trait Show
          |    show(self) -> string
          |trait Word: Show
          |    word(self) -> string
          |struct P
          |    v: int
          |impl Show for P
          |    show(self) -> string = "p"
          |impl Word for P
          |    word(self) -> string = self.show() + "!"
          |print(P(1).word())""".stripMargin
      ) shouldBe "p!\n"
    }
  }

  /** **What this pass resolves, it must not leave behind**, and a `Type.Abstract` is identified by
   * its *name* — so a trait's `T` and an unrelated declaration's `T` are one type as far as a cache
   * key is concerned. Resolving a promise of `Maybe[T]` here registers a `Maybe` instantiated at
   * *this trait's* stand-in, and every later walk asking for `Maybe[T]` was handed that one.
   *
   * What it looked like is the reason it is worth a test rather than a comment: the definition-time
   * walk of `impl[T: Display] Display for Maybe[T]` was told that its own body assumed what its own
   * bounds promise, about an `impl` the trait has nothing to do with beyond the letter its parameter
   * is spelled with. Both blocks below are correct, and the program prints.
   */
  "what the pass instantiates does not outlive it" - {
    "so a trait promising a generic type leaves that type's own 'impl' bounds alone" in {
      run(
        """enum Maybe[T]
          |    Just(v: T)
          |    Nothing
          |impl[T: Display] Display for Maybe[T]
          |    display(self, out: *Writer, fmt: FormatSpec)
          |        self match
          |            Just(v) -> v.display(out, fmt)
          |            Nothing -> out.write("Nothing".bytes)
          |    end display
          |end Maybe[T]
          |trait Sink[T]
          |    peek(self) -> Maybe[T]
          |var m: Maybe[int] = Just(3)
          |print(str(m))""".stripMargin
      ) shouldBe "3\n"
    }

    // The same shape against the library's own, which is where it was found: `Option` carries `Eq`
    // and `Display` blocks of exactly that form, so any trait promising an `Option[T]` used to
    // report four complaints from inside `library/sysl/option.sysl`.
    "including the library's, which is where this was found" in {
      run(
        """trait Sink[T]
          |    peek(self) -> Option[T]
          |var o: Option[int] = Some(3)
          |print(str(o), o == Some(3))""".stripMargin
      ) shouldBe "Some(3) true\n"
    }
  }

  // The conformance check still resolves both sides against a concrete `Self`, so a trait whose
  // signature is fine and an `impl` that disagrees with it is reported exactly as before.
  "the conformance check is untouched" in {
    err(
      """trait Doubler
        |    twice(self) -> Self
        |struct P
        |    v: int
        |impl Doubler for P
        |    twice(self) -> int = 1""".stripMargin
    ) should include("but trait 'Doubler' declares")
  }
}
