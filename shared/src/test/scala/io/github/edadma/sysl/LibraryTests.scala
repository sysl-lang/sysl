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
      // `Library.key("FormatSpec")` and the `FormatSpec` the library's `display_str` wrote have to
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
      // A name written in a library file means the library's before it means the module's — the one
      // place resolution inverts its first two steps. Without it `display_pad(text, out, fmt:
      // FormatSpec)` would take the *program's* struct, and the whole printing surface would fail
      // inside the library against a type its source never named. The rendering below walks every
      // one of those signatures.
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

  "a moved declaration whose own signature names other moved ones" - {

    // `Display.display` is declared `(self, out: *Writer, fmt: FormatSpec)`, and all three names are
    // now the standard module's. What the group holds is that a program's `impl` is matched against
    // the trait's signature as the *library* reads it: every one of those three is a word a program
    // may also declare, and the two below are what say the trait keeps meaning its own.
    //
    // The group was written when `Writer` was still the prelude's and this spanned the two halves.
    // That direction is still live — `args_of` reaches `print` and `exit`, and the renderers reach
    // `sysl_snprintf` — it is simply no longer what these two exercise.

    "an impl matches the trait's signature, every name in it the library's" in {
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

    "and the sink it names means the library's even where the program declares a 'Writer'" in {
      // The clash protected `Writer` until it moved; this is the test that became writable when it
      // did. If the trait's declared parameter types were resolved where the `impl` is written,
      // `*Writer` in `Display.display` would bind whatever the program calls `Writer` — and the
      // program below calls it something that cannot receive bytes at all, so the whole `display_*`
      // family would fail inside the library against a type its source never named. That is the
      // failure `FormatSpec` had, pointing the other way.
      run(
        """trait Writer
          |    log(self) -> int
          |
          |struct P
          |    x: int
          |
          |impl Display for P
          |    display(self, out: *sysl.Writer, fmt: FormatSpec) = str(self.x).display(out, fmt)
          |
          |print(P(9))
          |""".stripMargin) shouldBe "9\n"
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

    "a sink of the program's own is refused where the library's is asked for, and named apart" in {
      // Two traits spelled `Writer` and one object type, so the message has one job: say which of
      // the two the program handed over. Before the move this could not be written at all — the
      // clash refused the declaration on its first line.
      refused(
        """trait Writer
          |    log(self) -> int
          |
          |struct S
          |    n: int
          |
          |impl Writer for S
          |    log(self) -> int = self.n
          |
          |var s: S
          |var w: *Writer = &s
          |display_int(1, w, FormatSpec(0, -1, false))
          |""".stripMargin) should include("*sysl.Writer")
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

  "a moved FUNCTION, which is what a renderer is" - {

    "a program may declare one of its own over it, and both are reachable" in {
      // The first functions to cross, and the first name a program could take for itself that the
      // compiler also calls: `print(7)` goes to the library's `display_int` through `CoreTraits`,
      // whatever a program means by the words. Before the move the clash refused line one.
      run(
        """display_int(n: long, out: *Writer, fmt: FormatSpec) = display_str("mine", out, fmt)
          |
          |struct P
          |    x: int
          |
          |impl sysl.Display for P
          |    display(self, out: *Writer, fmt: FormatSpec) = display_int(long(self.x), out, fmt)
          |
          |print(P(5))
          |print(7)
          |""".stripMargin) shouldBe "mine\n7\n"
    }

    "and the library's own is still reachable, by the path that names it" in {
      run(
        """display_int(n: long, out: *Writer, fmt: FormatSpec) = display_str("mine", out, fmt)
          |
          |struct P
          |    x: int
          |
          |impl sysl.Display for P
          |    display(self, out: *Writer, fmt: FormatSpec) =
          |        sysl.display_int(long(self.x), out, fmt)
          |
          |print(P(5))
          |""".stripMargin) shouldBe "5\n"
    }

    "and a rendering the compiler chose names the library's in a diagnostic, not the program's" in {
      // Two functions spelled `display_int`; the one the message is about is the library's, and
      // saying so is the whole of what the qualified rendering buys.
      refused(
        """display_int(n: long) -> int = n
          |
          |struct S
          |    n: usize
          |
          |impl Writer for S
          |    write(*self, bytes: []const u8)
          |        self.n += bytes.len
          |    failed(*self) -> bool = false
          |
          |var w: &Writer = S(0usize)
          |sysl.display_int(1, w, FormatSpec(0, -1, false))
          |""".stripMargin) should include(s"'${Modules.show(Library.key("display_int"))}'")
    }
  }

  "a moved surface a program is expected to build on" - {

    "a program may declare a 'Reader' of its own, and the library's cursor still means the library's" in {
      // `Lines.src` is a `*Reader`, and it is the library's `Reader` by the word wherever `Lines`
      // was written. A program that declares an input abstraction of its own — which is a name a
      // program plausibly wants — does not thereby change what a line cursor reads through.
      run(
        """trait Reader
          |    poll(self) -> int
          |
          |struct Tick
          |    n: int
          |
          |impl Reader for Tick
          |    poll(self) -> int = self.n
          |
          |struct Bytes
          |    src: []const u8
          |    at: usize
          |
          |impl sysl.io.Reader for Bytes
          |    read(*self, into: []u8) -> []const u8
          |        var n = self.src.len - self.at
          |        if n > into.len then n = into.len
          |        for i in 0usize..<n do into[i] = self.src[self.at + i]
          |        self.at += n
          |        into[0..<n]
          |
          |var b = Bytes("a\nbb\n".bytes, 0usize)
          |var r: *sysl.io.Reader = &b
          |for line in sysl.io.lines(r) do print(line, Tick(3).poll())
          |""".stripMargin) shouldBe "a 3\nbb 3\n"
    }

    "and a sink of the program's own is refused where the library's reader is asked for" in {
      refused(
        """trait Reader
          |    poll(self) -> int
          |
          |struct Tick
          |    n: int
          |
          |impl Reader for Tick
          |    poll(self) -> int = self.n
          |
          |var t: Tick
          |var r: *Reader = &t
          |for line in sysl.io.lines(r) do print(line)
          |""".stripMargin) should include("sysl.io.Reader")
    }
  }

  "a moved surface the LANGUAGE reaches by name, not the program" - {

    // `s.chars` is a member the compiler provides, and what it provides is a call to `chars_of` —
    // a name no program wrote and none can see it choose. Nothing pinned that symbol before this
    // move, so nothing would have noticed it changing.
    "a string's characters reach the library's cursor, under the key the standard module files it as" in {
      val out = Compiler.compileToLlvm("""for c in "ab".chars do print(c)""")

      out.map(_.contains(s"@${Library.key("chars_of")}(")) shouldBe Right(true)
      out.map(_.contains("@chars_of(")) shouldBe Right(false)
    }

    "and a program declaring 'chars_of' of its own does not become what '.chars' walks" in {
      // The compiler asks the library for this one by name. A program is free to mean something
      // else by the word, and `.chars` is not it.
      run(
        """chars_of(b: []const u8) -> int = int(b.len)
          |
          |print(chars_of("abc".bytes))
          |for c in "ab".chars do print(c)
          |""".stripMargin) shouldBe "3\na\nb\n"
    }

    "the validator is asked for by name, and its error type comes with it" in {
      // Importing the function alone is enough to read the failure: `e` is the library's `Utf8Error`
      // by inference, so a program that only wants to know what went wrong never names the type.
      run(
        """import sysl.text.from_utf8
          |
          |from_utf8([0xC3u8, 0xA9u8]) match
          |    Ok(s) -> print(s)
          |    Err(e) -> print("bad at", e.offset)
          |""".stripMargin) shouldBe "é\n"
    }

    "and a program may declare a 'Utf8Error' of its own beside the library's" in {
      // A plausible name for a program to want, and the library's is still what `from_utf8`
      // answers with — told apart by the path, as every moved declaration is. Here the import
      // names only the function, so the program's own type is the only `Utf8Error` in scope and
      // the two do not even have to be told apart.
      run(
        """import sysl.text.from_utf8
          |
          |struct Utf8Error
          |    why: string
          |
          |bad() -> Utf8Error = Utf8Error("mine")
          |
          |print(bad().why)
          |
          |from_utf8([0xFFu8]) match
          |    Ok(s) -> print(s)
          |    Err(e) -> print("bad at", e.offset, e.truncated)
          |""".stripMargin) shouldBe "mine\nbad at 0 false\n"
    }

    "the conversion advice names the validator by the path that reaches it" in {
      refused("""var b = [0x61u8]
                |var s = string(b[..])
                |""".stripMargin) should include(s"'${Modules.show(Library.key("from_utf8"))}(b)'")
    }

    "and a program's own 'from_utf8' leaves both of the library's callers alone" in {
      // A shape none of the earlier moves had: this one has *two* library callers, `line_text` and
      // `args_of`, reached by two different routes — one from a program's own `for` over `lines`,
      // the other from the entry point, which names the conversion by key rather than by the word.
      // A program that means something else by that word has to leave both meaning the library's.
      runWith(
        """from_utf8(b: []const u8) -> string = "mine"
          |
          |struct Bytes
          |    src: []const u8
          |    at: usize
          |
          |impl sysl.io.Reader for Bytes
          |    read(*self, into: []u8) -> []const u8
          |        var n = self.src.len - self.at
          |        if n > into.len then n = into.len
          |        for i in 0usize..<n do into[i] = self.src[self.at + i]
          |        self.at += n
          |        into[0..<n]
          |
          |main(args: []string)
          |    print(from_utf8("x".bytes))
          |    for a in args[1..] do print(a)
          |    var b = Bytes("hi\nthere\n".bytes, 0usize)
          |    var r: *sysl.io.Reader = &b
          |    for line in sysl.io.lines(r) do print(line)
          |""".stripMargin, "one", "two") shouldBe "mine\none\ntwo\nhi\nthere\n"
    }

    "and the fallible half of a scalar conversion came across too" in {
      run(
        """import sysl.text.char_from_u32
          |
          |char_from_u32(9731u32) match
          |    Some(c) -> print(c)
          |    None -> print("no")
          |char_from_u32(0xD800u32) match
          |    Some(c) -> print(c)
          |    None -> print("no")
          |""".stripMargin) shouldBe "☃\nno\n"
    }

    // The printing surface became shadowable by moving, and it is the one surface where that could
    // go wrong silently: `print` is a desugaring the *compiler* writes, so if it resolved the
    // renderer by spelling rather than by key, `print(1)` would quietly call the program's function
    // and every printing test in the suite would still pass while printing the wrong thing.
    "a program may declare its own 'printi', and 'print' still reaches the library's" in {
      // The program's takes an `int` and yields a `string` where the library's takes a `long` and
      // yields nothing, so resolution picking the wrong one either way would not type-check.
      run("""printi(n: int) -> string = "mine"
            |
            |print(printi(1))
            |print(42)
            |""".stripMargin) shouldBe "mine\n42\n"
    }

    // Same question one layer down, at the sink every renderer writes through — and this one the
    // compiler names in the *emitted IR* rather than in a desugaring (`WriterEmitter`), so it is a
    // different mechanism reaching the same declaration.
    "and its own 'putbytes', which the writer table still resolves past" in {
      // The `print` on the last line is itself the check: every renderer writes through the
      // library's sink, so if the program's had been picked there would be no output at all.
      run("""import sysl.buf.byte_sink
            |
            |putbytes(b: []const u8) -> int = 7
            |
            |var g = byte_sink()
            |var w: *Writer = &g
            |
            |display_str("hi", w, FormatSpec(0, -1, false))
            |print(putbytes("x".bytes), g.text().len)
            |""".stripMargin) shouldBe "7 2\n"
    }
  }

  "a moved GENERIC, which is what a growable sequence is" - {

    // Every declaration that crossed before this one was monomorphic. A generic is emitted per
    // instantiation, so what carries the module is the *stem* of the mangled name — nothing pinned
    // that before, and a generic is exactly where a key could have been dropped without a failure.
    "an instantiation a program asks for is keyed by the module the generic is declared in" in {
      val out = Compiler.compileToLlvm(
        """import sysl.buf.*
          |
          |var b: Buf[int] = buf()
          |b.push(7)
          |print(b[0usize], b.len())""".stripMargin)

      out.map(_.contains(s"%struct.${Library.key("Buf")}.int = type")) shouldBe Right(true)
      out.map(_.contains(s"@${Library.key("Buf")}.push.int(")) shouldBe Right(true)
      out.map(_.contains(s"@${Library.key("buf")}.int(")) shouldBe Right(true)
      out.map(_.contains("@Buf.push.int(")) shouldBe Right(false)
    }

    // A library `impl` is keyed under its *subject*, not its trait — which is what lets `Buf`'s row
    // sit with `Buf` however the two are split across files, and is why the seam never had to decide
    // which of a pair an `impl` belongs to. Written when `Index` was still the prelude's and this
    // spanned the halves; the keying it pins is unchanged by their having joined.
    "and its impl of a trait declared elsewhere is keyed under the subject, not the trait" in {
      val out = Compiler.compileToLlvm(
        """import sysl.buf.*
          |
          |var b: Buf[int] = buf()
          |b.push(7)
          |print(b[0usize])""".stripMargin)

      out.map(_.contains(s"@${Library.key("Buf")}.index.int(")) shouldBe Right(true)
    }

    "a program may declare a 'Buf' of its own, and what the library builds on still means the library's" in {
      // `StrBuilder` holds a `&Buf[u8]`, so the library reaches its own `Buf` through a second
      // declaration rather than at the call — and now across two of its own modules, since the
      // builder is `sysl.text`'s and the buffer is `sysl.buf`'s. With a *generic*, too, where the
      // program's own is not even the same arity of thing, and with the program's `Buf` reached by
      // the bare word while the library's is reached by neither of the imports written here.
      run(
        """import sysl.text.str_builder
          |
          |struct Buf[T]
          |    only: T
          |
          |mine[T](v: T) -> Buf[T] = Buf(v)
          |
          |print(mine(4).only)
          |
          |var s = str_builder()
          |s.push("hi")
          |print(s.finish())
          |""".stripMargin) shouldBe "4\nhi\n"
    }

    "and the library's own is still reachable beside it, by the path that names it" in {
      run(
        """struct Buf[T]
          |    only: T
          |
          |var mine: Buf[int] = Buf(9)
          |var theirs: sysl.buf.Buf[int] = sysl.buf.buf()
          |
          |theirs.push(3)
          |print(mine.only, theirs[0usize], theirs.len())
          |""".stripMargin) shouldBe "9 3 1\n"
    }

    "a sink the library supplies gathers a rendering that pads across the whole value" in {
      // `ByteSink` is why it sits with `Buf`: it is the writer a multi-part `Display` gathers into
      // before its specifier can pad what the parts came to, and it is ordinary sysl over the
      // buffer. Three modules meet in one body here — the sink's, the validator's, and `sysl`'s own
      // renderers, which arrive with nothing written because `print` is what desugars onto them.
      run(
        """import sysl.buf.byte_sink
          |import sysl.text.from_utf8
          |
          |struct Pair
          |    a: int
          |    b: int
          |
          |impl sysl.Display for Pair
          |    display(self, out: *Writer, fmt: FormatSpec)
          |        var g = byte_sink()
          |        var w: *Writer = &g
          |        display_int(long(self.a), w, FormatSpec(0, -1, false))
          |        display_str(":", w, FormatSpec(0, -1, false))
          |        display_int(long(self.b), w, FormatSpec(0, -1, false))
          |        display_str(from_utf8(g.text()).unwrap(), out, fmt)
          |
          |print(f"[${Pair(1, 2)}%6s]")
          |""".stripMargin) shouldBe "[   1:2]\n"
    }
  }

  "a moved surface whose whole point is the last line the compiler supplies" - {

    "a builder gathers text and hands back one string, from the module that holds it" in {
      run(
        """import sysl.text.str_builder
          |
          |var b = str_builder()
          |
          |b.push("ab")
          |b.push_char('é')
          |print(b.finish(), b.len)
          |""".stripMargin) shouldBe "abé 4\n"
    }

    "and a program may declare a 'StrBuilder' of its own beside the library's" in {
      // A name a program plausibly wants, and the library's is still what `str_builder()` returns —
      // which the import does not even have to be told apart from, since it names the function only.
      run(
        """import sysl.text.str_builder
          |
          |struct StrBuilder
          |    tag: int
          |
          |var mine = StrBuilder(7)
          |var theirs = str_builder()
          |
          |theirs.push("hi")
          |print(mine.tag, theirs.finish())
          |""".stripMargin) shouldBe "7 hi\n"
    }

    "the C copy is owned by a value, and its length excludes the terminator" in {
      // `CString` is the one place the library answers "who frees it" for a foreign shape, so the
      // storage being one longer than the text is the invariant worth pinning.
      run(
        """import sysl.text.cstring
          |
          |var c = cstring("abc")
          |
          |print(c.len, c.bytes.len, c.bytes[3])
          |""".stripMargin) shouldBe "3 4 0\n"
    }

    "and what it hands a foreign function is a pointer to those bytes" in {
      run(
        """import sysl.text.cstring
          |
          |extern "strlen" c_strlen(p: *u8) -> usize
          |
          |var c = cstring("hello")
          |print(c_strlen(c.ptr))
          |""".stripMargin) shouldBe "5\n"
    }
  }

  "the moved OPERATOR CATALOG, whose identity the compiler holds separately from its declaration" - {

    "a program may declare a catalog trait of its own, and the operator still means the library's" in {
      // Before the move this was refused outright — the catalog sat in the root module and the name
      // was taken. That clash was the only thing protecting the operator, and moving the catalog
      // out of the root module removes it, so what keeps `<` meaning the library's `Ord` is that
      // `CoreTraits` resolves the token through `Library.key` rather than by the bare word.
      run(
        """trait Ord
          |    rank(self) -> int
          |
          |struct Tier
          |    n: int
          |
          |impl Ord for Tier
          |    rank(self) -> int = self.n
          |
          |print(Tier(3).rank(), 1 < 2, "b" < "a")
          |""".stripMargin) shouldBe "3 true false\n"
    }

    "and a bound on the program's own does not accept a built-in, which is the whole distinction" in {
      refused(
        """trait Ord
          |    rank(self) -> int
          |
          |top[T: Ord](x: T) -> int = x.rank()
          |
          |print(top(3))
          |""".stripMargin) should include("Ord")
    }

    "the structural rows still make a tuple comparable exactly when its parts are" in {
      run(
        """print((1, "a") == (1, "a"), (1, 2) < (1, 3), (1, 2, 3) == (1, 2, 4))
          |""".stripMargin) shouldBe "true true false\n"
    }

    "and a tuple renders as one field, so a width pads the whole of it" in {
      run("""print(f"[${(1, 2)}%8s]")""") shouldBe "[  (1, 2)]\n"
    }
  }

  "the moved traits the LANGUAGE'S OWN SYNTAX resolves through" - {

    // `b[i]` and `for x in c` are syntax, not calls a program writes — the compiler picks `Index`
    // and `Iterate` for itself. A program declaring either name gets its own, and the syntax keeps
    // meaning the library's, which is the whole distinction these two make.
    "a program may declare an 'Index' of its own, and a subscript still means the library's" in {
      run(
        """import sysl.buf.*
          |
          |trait Index[I, E]
          |    lookup(self, i: I) -> E
          |
          |var b: Buf[int] = buf()
          |
          |b.push(4)
          |b.push(9)
          |print(b[1usize], b.len())
          |""".stripMargin) shouldBe "9 2\n"
    }

    "and an 'Iterate' of its own, while a 'for' still walks through the library's" in {
      run(
        """trait Iterate[E]
          |    step(*self) -> E
          |
          |var total = 0
          |
          |for c in "abc".chars do total += 1
          |print(total)
          |""".stripMargin) shouldBe "3\n"
    }

    "a subscript refused through the library's Index names it by the path that reaches it" in {
      // The advice spelled `'Index'` literally while resolving `IndexSet` by key — the standing
      // defect, found again by the audit rather than by a failure.
      refused(
        """import sysl.buf.*
          |
          |var b: Buf[int] = buf()
          |
          |b.push(1)
          |b[0usize] += 2
          |""".stripMargin) should include(s"'${Modules.show(Library.key("Index"))}'")
    }

    "and a 'for' over something that walks nowhere names 'Iterate' the same way" in {
      refused(
        """struct Still
          |    n: int
          |
          |for x in Still(1) do print(x)
          |""".stripMargin) should include(s"'${Modules.show(Library.key("Iterate"))}'")
    }
  }

  "a moved trait whose memberships the compiler supplies rather than a program declaring them" - {

    "a program may declare a 'Hash' of its own, and a built-in still satisfies the library's" in {
      // `Hash`'s members are a *rule* rather than a table of `impl`s — the integer family is open,
      // so there is no finite list to write one for. That makes the bound `[K: sysl.Hash]` the only
      // way an `i5` was ever going to be accepted, and the shadowing program has to leave it alone.
      run(
        """trait Hash
          |    digest(self) -> int
          |
          |struct Token
          |    n: int
          |
          |impl Hash for Token
          |    digest(self) -> int = self.n
          |
          |keyed[K: sysl.Hash](k: K) -> u64 = k.hash()
          |
          |print(Token(4).digest())
          |print(keyed(1u8) == keyed(1i64))
          |""".stripMargin) shouldBe "4\ntrue\n"
    }

    "and a bound on the program's own does not accept a built-in, which is the whole distinction" in {
      // Two traits spelled `Hash`; only one of them a built-in belongs to, and the membership is
      // what the compiler supplies rather than what the name says.
      refused(
        """trait Hash
          |    digest(self) -> int
          |
          |keyed[K: Hash](k: K) -> int = k.digest()
          |
          |print(keyed(1))
          |""".stripMargin) should include("Hash")
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
