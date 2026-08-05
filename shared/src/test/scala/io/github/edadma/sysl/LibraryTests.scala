package io.github.edadma.sysl

/** The seam every question about the shipped library goes through (`Library`).
 *
 * Two of these tests are worth more than the rest and it is worth saying which. **A name the
 * compiler spells for itself is checked by nothing** — a program's `Option` is resolved and reported
 * on, while `instantiateEnum(Library.key("Option"), …)` on a name the library stopped declaring is an
 * exception at compile time rather than a diagnostic at any time. So both halves of that surface are
 * pinned here: the names written down in `Library.known`, and the renderer and mixer names
 * `CoreTraits` chooses per type, which are not written down anywhere and so are gathered by asking it.
 *
 * Each declaration that has *moved* into the standard module is held to the seam separately, in
 * `LibraryMoveTests`.
 */
class LibraryTests extends LibrarySeamSupport {

  "whose declaration it is" - {

    "every declaration the library ships is its own" in {
      Library.decls should not be empty
      Library.decls.filterNot(Library.carried.owns) shouldBe empty
    }

    "nothing a program declares is" in {
      // Asked of the `Source` rather than of the module, which is the stronger question: it stays
      // right for a user file that happened to sit where the library's do, and it was the *only*
      // workable question while the library still had declarations in the root module.
      val mine = parsed("struct Ok\n    n: int\n\ndouble(n: int) -> int = n * 2\n")

      mine.length shouldBe 2
      mine.filter(Library.carried.owns) shouldBe empty
    }

    "a declaration with no position at all is not the library's" in {
      // A synthesized node carries none, and `owns` is asked of nodes the compiler built as well as
      // of ones it parsed — a wrong answer here would hand a desugaring the library's scope.
      Library.carried.owns(FuncDecl("f", Nil, Nil, None, Nil)) shouldBe false
    }
  }

  "a key and the spelling it stands for" - {

    // Every library spelling is the standard module's now, `Option` included — it was the last to
    // move, and while it had not this pair said the two answers were different.
    "a library spelling makes a key in the standard module, never in the root one" in {
      Modules.moduleOf(Library.key("Option")) shouldBe Std.module
      Modules.moduleOf(Library.key("Option")) should not be Modules.root
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

    "and neither has a program's own declaration of a name the library also declares" in {
      // A program may write `struct FormatSpec`, and in a headerless file it is keyed `FormatSpec` —
      // which is where the library's used to be. The library's is `sysl$FormatSpec`, so the bare key
      // is the program's and has no library spelling.
      Library.spelling("FormatSpec") shouldBe None
    }
  }

  "the standard module, which every program is compiled against" - {

    "says in one of the library's headers what `Std.module` says" in {
      // `module` is a constant so that nothing has to parse to ask which module the auto-imported
      // names are in. This is what holds it to the source it stands for.
      Std.parsed(Target.default) should not be empty
      Std.parsed(Target.default).map(_.module.map(_.show)) should contain(Some(Std.module))
    }

    "is made of more than one file, each of which the driver would build the same way" in {
      // `Display.display` names `Writer` from the other file. That direction is what says a module's
      // members are one set however many files they came from, and it is why neither file imports
      // the other.
      Std.sources.count(_.dir.contains(List(Std.module))) should be > 1
    }

    "declares the whole of what the library declares" in {
      // The two halves used to be checked against each other here. There is one half now, so what
      // is left to say is that it is not empty and that all of it is the library's — the second is
      // what `Library.owns` answers, and an empty module would make it vacuously true.
      Std.decls(Target.default) should not be empty
      Std.decls(Target.default).forall(Library.carried.owns) shouldBe true
      Library.decls shouldBe Std.decls(Target.default)
    }

    "is a module every file may write the names of without importing it" in {
      Library.modules should contain(Std.module)
      AutoImport.modules should contain(Std.module)
    }

    // A submodule is where the library puts what a program should have to ask for, so the one thing
    // that must stay true of it is that asking is required — its names are not among the free ones.
    "which the library's submodules are not" in {
      Library.modules.filterNot(_ == Std.module) should not be empty
      for m <- Library.modules if m != Std.module do AutoImport.modules should not contain m
    }

    "and every file sits in one of them, however deep the tree goes" in {
      Std.sources.map(_.dir.get.mkString(".")).distinct.sorted shouldBe Library.modules
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

    // `Library.key` is a lookup from a spelling to the module that declares it, so a spelling in two
    // of the library's modules would give one of them an answer that is silently the other's. It is
    // a real hazard now that there is more than one module and no machinery guarding it: this is the
    // guard, and it is enough because `key` is only ever asked about `known`.
    "each in exactly one of the library's modules" in {
      val declaredIn =
        for
          u <- Library.carried.units
          n <- Library.names(u.body) if Library.known(n)
        yield n -> Compiler.moduleOf(u)

      declaredIn.groupMap(_._1)(_._2).filter(_._2.distinct.length > 1) shouldBe empty
      declaredIn.map(_._1).toSet shouldBe Library.known
    }

    "and `key` names that module, wherever in the library it turned out to be" in {
      // The one that moved, which is why this is a lookup rather than the standard module's
      // qualification: a `main` taking arguments reaches it from inside the compiler.
      Library.key("args_of") shouldBe Modules.qualify("sysl.args", "args_of")
      Library.key("Option") shouldBe Modules.qualify(Std.module, "Option")
    }

    "and the traits the operator catalog names" in {
      CoreTraits.required.keySet -- Library.declared shouldBe Set.empty
    }

    // There is no renderer table left to assert about: every type reaches `Display` through an
    // `impl`, the closed families each through one of their own and the integers through the
    // blanket block over `Integer`. What is asserted instead is that no built-in gets `Display` by
    // rule any longer, since a membership provided that way is exactly what cannot fill a table
    // slot — and filling one is the whole point of having moved it.
    "no built-in has 'Display' by the compiler's rule, at any width or in any closed family" in {
      val builtins = List(
        Type.Integer(4, signed = false),
        Type.Integer(8, signed = true),
        Type.Integer(64, signed = true),
        Type.Integer(64, signed = false),
        Type.Integer(128, signed = false),
        Type.Integer(256, signed = false),
        Type.Real,
        Type.Bool,
        Type.Char,
        Type.Str,
      )

      for t <- builtins do CoreTraits.builtin("Display", t).shouldBe(false)
    }

    // The membership that replaced it, which is a *family* rather than a rendering: it says which
    // types the blanket covers and promises no operation, so there is no function name to hold
    // against the library the way the renderer rows were held.
    "'Integer' is every integer width and nothing else" in {
      for t <- List(Type.Integer(1, signed = false), Type.Integer(4, signed = false),
                    Type.Integer(32, signed = true), Type.Integer(256, signed = false)) do
        CoreTraits.builtin("Integer", t).shouldBe(true)

      for t <- List(Type.Real, Type.Floating(32), Type.Bool, Type.Char, Type.Str) do
        CoreTraits.builtin("Integer", t).shouldBe(false)
    }

    // A closed trait is one nothing may implement, and that is what makes the blanket sound — so
    // the set is held to being declared exactly as every other compiler-known name is.
    "and the family it names is a trait the library declares" in {
      CoreTraits.closed.diff(Library.declared).shouldBe(Set.empty)
      CoreTraits.closed.shouldBe(Set("Integer"))
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

    // A float has neither membership, for two unrelated reasons worth keeping apart: `Hash`
    // withholds it deliberately (`NaN != NaN` breaks what a table lookup assumes), while `Display`
    // withholds it from every built-in now — a float renders through the `impl` written for its
    // width, exactly as an integer renders through the blanket.
    "a float has neither membership, and the two reasons are different" in {
      CoreTraits.hash(Type.Real).shouldBe(None)
      CoreTraits.builtin("Hash", Type.Real).shouldBe(false)

      CoreTraits.builtin("Display", Type.Real).shouldBe(false)
      CoreTraits.builtin("Integer", Type.Real).shouldBe(false)
    }
  }

  "the seam is what a program actually resolves through" - {

    "a library name arrives unqualified, and its key is the library's" in {
      // The compiler's own answer, read at the surface a program sees: `Option` in a signature is
      // the library's `Option`, filed under the key `Library.key` gives.
      val ir = Compiler.compileToLlvm("f(n: int) -> Option[int] = Some(n)\nprint(f(1).unwrap())")

      ir.isRight shouldBe true
    }

    // `Option` was the last name a program could not take: the prelude held it in the root module a
    // headerless program is also in, so declaring one was a clash rather than shadowing. It moved,
    // and the clash went with it — which is worth a test of its own because `?` reaches the
    // library's `Option` by a route no program writes, so this is where picking the program's would
    // show up.
    "and a program may declare its own 'Option', which '?' does not reach" in {
      run("""enum Option
            |    Yes
            |    No
            |
            |mine() -> Option = Yes
            |
            |g() -> sysl.Option[int] = Some(3)
            |
            |f() -> sysl.Option[int]
            |    var x = g()?
            |    Some(x + 1)
            |end f
            |
            |print(f().unwrap())
            |mine() match
            |    Yes -> print("yes")
            |    No -> print("no")
            |""".stripMargin) shouldBe "4\nyes\n"
    }
  }
}
