package sh.sysl

import io.github.edadma.cross_platform.*

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

/** The standard module handed to a compilation as an **artifact** rather than as source, and the one
 * claim that has to hold before anything is moved onto that path: that the two mean the same thing.
 *
 * A program is compiled against the library (`13 §8`), and every compilation so far has been handed
 * it the same way — `lib/sysl` as the compiler embeds it, parsed. The artifact exists to end that
 * source dependence: the same trees arrive already decoded, and the half with nothing left to
 * monomorphize arrives as object code to link against rather than emit again.
 *
 * **"It links" would prove nothing on its own.** A compilation that reached a *different* library
 * and still produced a program that linked would be exactly as green. So what is pinned here is the
 * emitted IR, compared **byte for byte** between a compilation against the embedded std and one
 * against the decoded artifact, over programs chosen to reach as much of the library as they can.
 * That is a stronger statement than any behavioural test could make, and it is cheap: the IR is a
 * string, so nothing here needs a toolchain.
 *
 * The order matters as much as the assertion. Nothing has moved onto the artifact path yet — the
 * whole suite still compiles the library from source, and this is the test that says it *could*.
 * Writing it first is what keeps the move from being a switch: a switch would put every program onto
 * a path nothing had exercised, where one hole fails everything at once with nothing to bisect.
 */
class StdArtifactTests extends AnyFreeSpec with Matchers {

  /** The std built exactly as `sysl build-lib lib --std` builds it. This is the production path
   * rather than a hand-rolled `AstCodec.encode`, so what is compared below is what a program would
   * actually be handed.
   *
   * No toolchain is involved: `build` yields the IR and the metadata, and only the driver goes on to
   * assemble the first into an object file.
   */
  private lazy val artifact: (String, String) =
    LibraryArtifact.build(Std.sources, Target.default, LibraryArtifact.std) match
      case Right(r)  => r
      case Left(err) => fail(s"the standard module library did not build: $err")

  private lazy val read: (Stdlib, Set[String]) =
    Stdlib.read("sysl.syslib", artifact._2, Target.default) match
      case Right(r)  => r
      case Left(err) => fail(s"the standard module metadata did not read back: $err")

  private def decoded: Stdlib       = read._1
  private def precompiled: Set[String] = read._2

  /** One program compiled against one std, through the entry point the driver itself uses — so the
   * two sides below differ in the standard module and in nothing else.
   *
   * `linked` is what the standard module's object half already defines, which the program declares rather than
   * emits a second time. Empty is the compilation every program gets today.
   */
  private def against(std: Stdlib, program: String, linked: Set[String] = Set.empty): String =
    Compiler.compiledWith(List(Source("<input>", program)), Nil, Target.default, linked, Some(std)) match
      case Right(built) => built.ir
      case Left(err)    => fail(s"the program did not compile:\n$err")

  private def sameBothWays(program: String): Unit =
    against(decoded, program) shouldBe against(Library.carried, program)

  /** The same program with the standard module's object half linked rather than emitted. */
  private def linked(program: String): String = against(decoded, program, precompiled)

  /** The std's metadata with one of its files changed, which is what an edit to `lib/sysl` after an
   * artifact was built amounts to.
   */
  private lazy val drifted: String = {
    val edited = Std.sources.head

    LibraryArtifact.build(
      new Source(edited.name, edited.text + "\nunreachable() -> int = 1\n", edited.dir) :: Std.sources.tail,
      Target.default, LibraryArtifact.std) match
      case Right((_, meta)) => meta
      case Left(err)        => fail(s"the altered std did not build: $err")
  }

  /** What a program and the artifact it links would both define **at a name the linker can see** —
   * empty, or a link that fails wherever a duplicate is refused.
   *
   * `private` and `internal` definitions are excluded and have to be: those are exactly the symbols
   * a program is *meant* to carry its own copy of, since the name does not leave the object file and
   * two copies can never be one symbol. Comparing them would report the arc helpers on every
   * program, which is not a collision and never was.
   */
  private def bothDefine(program: String): Set[String] =
    external(artifact._1) intersect external(linked(program))

  /** The program that found this: `display` takes an `out: *Writer`, which reaches `sysl.stdout` —
   * the one library function a program compiles for itself, because it reads a module-level `val`
   * and a library has no entry point to initialize one.
   */
  private val displaying =
    """struct Point
      |    x: int
      |    y: int
      |
      |impl Display for Point
      |    display(self, out: *Writer, fmt: FormatSpec) =
      |        display_pad(("(" + str(self.x) + ", " + str(self.y) + ")").bytes, out, fmt)
      |
      |var p = Point(3, 4)
      |
      |print(p)
      |""".stripMargin

  private def external(ir: String): Set[String] =
    ir.linesIterator
      .filter(l => l.startsWith("define ") && !l.startsWith("define private") && !l.startsWith("define internal"))
      .flatMap { line =>
        val at = line.indexOf('@')

        Option.when(at >= 0)(line.drop(at + 1).takeWhile(c => c != '(' && c != ' ').stripPrefix("\"").stripSuffix("\""))
      }.toSet

  /** The symbols a module defines, and the ones it leaves to the linker. */
  private def defines(ir: String): Set[String] = symbols(ir, "define")
  private def declares(ir: String): Set[String] = symbols(ir, "declare")

  private def symbols(ir: String, form: String): Set[String] =
    ir.linesIterator.filter(_.startsWith(s"$form ")).flatMap { line =>
      val at = line.indexOf('@')

      Option.when(at >= 0)(line.drop(at + 1).takeWhile(c => c != '(' && c != ' '))
    }.toSet

  "the two cores are genuinely different objects" - {

    // Without this the comparisons below could pass by comparing a thing to itself, which is the
    // shape a vacuous test takes here: `shouldBe` on two identical strings says nothing about where
    // either came from.

    "the decoded one is not the embedded one" in {
      decoded should not be theSameInstanceAs(Library.carried)
    }

    "and its declarations belong to it rather than to the embedded copy" in {
      // A `Source` compares by identity, so a decoded file named `lib/sysl/print.sysl` is a
      // different source from the embedded file of the same name — which is exactly what makes the
      // IR match below a result rather than a tautology.
      val one = decoded.decls.find(_.pos.isDefined).getOrElse(fail("the decoded std carries no positions"))

      decoded.owns(one) shouldBe true
      Library.carried.owns(one) shouldBe false
    }

    "and it carries the same declarations, so the comparison is between equals" in {
      // **Minus the library's own test files**, which an artifact does not carry and should not: a
      // `@tests` file is scaffolding for `sysl test --std`, and a consumer reading this metadata for
      // generic instantiation has no use for a declaration nothing outside a test may name.
      //
      // Written as a filter rather than as a list of the files there are, so that packing another
      // module with tests does not come back here. What is being compared is still every shipping
      // file, which is what makes the IR match below a result: it was `filterNot` on one side and
      // nothing at all on the other that would make this vacuous.
      val shipped = Library.carried.units.filterNot(_.testOnly)

      shipped.length should be < Library.carried.units.length

      decoded.units.map(_.source.name) shouldBe shipped.map(_.source.name)
      decoded.decls.length shouldBe shipped.flatMap(_.body).length
    }
  }

  "what the library costs a program that does not use it" - {

    // The hold-back is decided over whichever std a compilation was handed, so it is already in
    // force against the embedded one — a library declaration is analyzed and emitted only once
    // something reaches it. That is worth pinning on its own: it is the reason an artifact's object
    // half is the only thing linking can save, the rest having never been emitted in the first
    // place.

    "nothing of it, when nothing reaches it" in {
      defines(against(Library.carried, "var x = 2 + 3\nvar y = x * 2\n"))
        .filter(_.startsWith(Library.key(""))) shouldBe empty
    }

    "and the surface it does reach, when something does" in {
      // Discriminating against the above: the same compilation, one statement further on, has to
      // carry the library or the first assertion would hold for a compiler that emitted nothing.
      defines(against(Library.carried, "print(1)\n"))
        .filter(_.startsWith(Library.key(""))) should not be empty
    }
  }

  "an artifact built from a different standard module than the compiler carries" - {

    // The failure this exists for is the quiet one. The artifact is built separately from the
    // compiler that consumes it, so the two drift: build one, edit `lib/sysl`, and every compilation
    // afterwards is against a standard module that is not the one in the tree. A stale artifact
    // decodes and links perfectly — it is simply the wrong library, and nothing else would notice.

    "is refused, rather than compiled against" in {
      Stdlib.read("stale.syslib", drifted, Target.default) match
        case Left(err) => err should include("different standard module")
        case Right(_)  => fail("a standard module built from other source was accepted")
    }

    "while the one built from what the compiler carries is accepted" in {
      // Discriminating against the above: without this the refusal could be unconditional, which
      // would reject every artifact and look exactly as green.
      Stdlib.read("sysl.syslib", artifact._2, Target.default) shouldBe a[Right[?, ?]]
    }

    "and the fingerprint is what tells them apart" in {
      LibraryArtifact.fingerprint(Std.sources) shouldBe Std.fingerprint
      LibraryArtifact.fingerprint(Std.sources.reverse) shouldBe Std.fingerprint
      LibraryArtifact.fingerprint(
        Std.sources.map(s => new Source(s"/elsewhere/${Project.basename(s.name)}", s.text, s.dir)))
        .shouldBe(Std.fingerprint)
      LibraryArtifact.fingerprint(Std.sources.tail) should not be Std.fingerprint
    }

    "and it is exactly the hash it says it is" in {
      // A known-answer test, over a fixed input rather than the library, so that editing `lib/sysl`
      // does not come here. It is the only kind that pins a hash: every behavioural property worth
      // asserting — that a change moves it, that order and path do not — holds just as well of the
      // FNV-1a underneath without the `fmix64` finalizer on top, so nothing short of the value
      // itself can tell whether the algorithm is still the one that was written down.
      LibraryArtifact.fingerprint(List(Source("a/one.sysl", "module m\n"))) shouldBe "438db5d52d94904b"

      LibraryArtifact.fingerprint(
        List(Source("a/one.sysl", "module m\n"), Source("b/two.sysl", "f() -> int = 1\n")))
        .shouldBe("34149c796c985378")
    }
  }

  "a program compiled against the decoded std emits exactly what one compiled against the source does" - {

    "for a program that reaches nothing of the library at all" in {
      // The floor of the claim: with nothing of the library reached, the two compilations may still
      // differ, since which declarations are held back is decided over the standard module either way.
      sameBothWays("var x = 2 + 3\nvar y = x * 2\n")
    }

    "for one that prints, which reaches the printing surface and the sink under it" in {
      sameBothWays("print(1)\nprint(\"two\")\nprint(3.5)\nprint(true)\nprint('c')\n")
    }

    "for one that monomorphizes the library's own generic enums" in {
      // `Option` and `Result` are declared in the library and instantiated in the program, so their
      // layouts and every function over them are built here out of the trees the artifact carried —
      // which is the half of a library that can never be precompiled.
      sameBothWays(
        """unwrap(o: Option[int], dflt: int) -> int
          |    o match
          |        Some(v) -> v
          |        None -> dflt
          |end unwrap
          |
          |tenfold(o: Option[int]) -> Option[int]
          |    var v = o?
          |    Some(v * 10)
          |end tenfold
          |
          |checked(n: int) -> Result[int, string]
          |    if n > 0 then Ok(n) else Err("no")
          |end checked
          |
          |ok(r: Result[int, string]) -> int
          |    r match
          |        Ok(v) -> v
          |        Err(_) -> 0
          |end ok
          |
          |print(unwrap(tenfold(Some(3)), -1), unwrap(tenfold(None), -1))
          |print(ok(checked(1)), ok(checked(-1)))
          |""".stripMargin)
    }

    "for one that renders through a format string, which carries the library's own FormatSpec" in {
      sameBothWays(
        """var i = 7
          |var s = "x"
          |print(f"[${i}%4d] ${s}%s ${2.5}")
          |""".stripMargin)
    }

    "for one that walks a string's characters, which reaches the library's iteration surface" in {
      sameBothWays(
        """for c in "hello".chars
          |    print(c)
          |end for
          |""".stripMargin)
    }

    "for one that implements a library trait, which builds a table over the library's own members" in {
      sameBothWays(
        """struct P
          |    n: int
          |impl Display for P
          |    display(self, out: *Writer, fmt: FormatSpec) = self.n.display(out, fmt)
          |print(P(1))
          |""".stripMargin)
    }

    "and for a program that declares a main taking its arguments, which the library builds" in {
      // `args_of` is reached by no name a program writes — the entry point asks for it by key — so
      // this is the route into the library that a source-level comparison would miss.
      sameBothWays(
        """main(args: []string)
          |    print(args.len)
          |""".stripMargin)
    }

    /* Everything above reaches a part of the library some earlier step needed. These reach the rest
     * of it, because divergence is not a property of the library as a whole — the artifact carries
     * declarations one at a time, and a surface no program here touches is one where the two paths
     * could disagree with the suite staying green. What makes them cheap enough to be worth having
     * is that a byte-for-byte IR comparison needs no toolchain. */

    "for one that builds a string, which reaches the growable buffer under the builder" in {
      // `StrBuilder` holds a `&Buf[u8]`, so this monomorphizes a library generic *behind* a library
      // struct — a layout the program never names and cannot get from the precompiled half.
      sameBothWays(
        """import sysl.text.str_builder
          |
          |var b = str_builder()
          |b.push("count: ")
          |b.push_char('#')
          |print(b.finish(), b.len, b.is_empty)
          |""".stripMargin)
    }

    "for one that hashes, which reaches the mixers a built-in's membership renders through" in {
      // A built-in has no lowered `int.hash` to call; the trait resolves to a mixer chosen by type.
      // Which mixer is a decision made over the standard module, so it is one the two paths could differ on.
      sameBothWays(
        """h[T: Hash](x: T) -> u64 = x.hash()
          |print(h(7), h("x"), h(true))
          |""".stripMargin)
    }

    "for one that implements an operator, which resolves through the library's own Add" in {
      // `+` on a program's own struct is a library trait bound satisfied by a program `impl` — the
      // trait declaration comes from the artifact and the table is built here.
      sameBothWays(
        """struct V
          |    x: int
          |impl Add for V
          |    add(self, rhs: V) -> V = V(self.x + rhs.x)
          |print((V(1) + V(2)).x)
          |""".stripMargin)
    }

    "for one that reads lines, which reaches the library's reader surface and its buffering" in {
      // The heaviest thing in the library that a program can reach by name: `Lines` holds a reader,
      // a fixed array, a slice, and a `Buf` all at once.
      sameBothWays(
        """import sysl.io.*
          |
          |var r = stdin()
          |for line in lines(&r)
          |    print(line)
          |end for
          |""".stripMargin)
    }

    "for one that hands bytes to C, which reaches the owned NUL-terminated copy" in {
      sameBothWays(
        """import sysl.text.cstring
          |
          |var c = cstring("hi")
          |print(c.len)
          |""".stripMargin)
    }

    "for one that passes a read-only view into the library, the type being the library's to declare" in {
      // `[]const u8` is what the printing and text surfaces take. A program that spells it reaches
      // the same declarations through a different door than `print` does, and the view's constness
      // is carried in the artifact's own encoding of the type rather than re-derived from source.
      sameBothWays(
        """import sysl.text.chars_of
          |
          |count(b: []const u8) -> usize
          |    var n = 0usize
          |    for c in chars_of(b) do n += 1
          |    n
          |end count
          |print(count("hello".bytes))
          |""".stripMargin)
    }
  }

  "what the artifact's object half already holds" - {

    // Not yet consumed by any compilation — a program is still handed the whole library and defines
    // every part of it that it reaches. What these pin is that there is something to consume, which
    // is what the next step is for.

    "the printing surface is compiled once, by the library" in {
      precompiled should contain(Library.key("printi"))
      precompiled should contain(Library.key("putbytes"))
    }

    "a generic is not, because there is nothing to compile until a caller fixes its arguments" in {
      // `Option`'s own members travel as trees and are built in whatever program fixes their
      // arguments, so the generic itself is never in the object half however much of it is used.
      //
      // An **instantiation** may be, where the library itself asked for one: the text surface's
      // `contains` is an `index_of` and an `is_some` at `usize`, and that one has a compiled form
      // like any other function. It is the same statement `Buf.push.byte` makes further down rather
      // than an exception to this one — what a library can precompile is the arguments it fixed.
      val option = Library.key("Option")

      precompiled should not contain s"$option.is_some"
      precompiled should not contain s"$option.unwrap"
      precompiled should contain(s"$option.is_some.usize")
    }

    "and the library carries no entry point of its own to collide with a program's" in {
      artifact._1 should not include "define i32 @main("
    }

    "and one library built two ways has one object half, whichever `Source` objects carried it" in {
      // A regression test, and the failure it guards is a silent one. Which declarations are held
      // back until something reaches them was decided by `Stdlib.owns` alone, which is identity on the
      // `Source` — so building the standard module from `Std.sources`, the copy already in memory, held back
      // *every* function in it. Nothing reached any of them, and the artifact came out with an empty
      // object half: it still carried every tree, so every program compiled and ran, and the whole
      // point of precompiling was gone with nothing failing to say so. Read off disk the same files
      // answered the other way. The fix is that a compilation **building** a module does not treat
      // that module as supplied to it (`AnalyzerBase.suppliedByLibrary`).
      assume(StdRoot.root.isDefined, "lib/ not found from the test working directory")

      val fromDisk = LibraryArtifact.build(Project.collect(StdRoot.root.get), Target.default, LibraryArtifact.std)

      fromDisk match
        case Right((_, meta)) =>
          LibraryArtifact.read("disk.syslib", meta, Target.default) match
            case Right((_, syms, _)) => syms shouldBe precompiled
            case Left(err)           => fail(err)
        case Left(err) => fail(s"the standard module library did not build from disk: $err")
    }
  }

  "a program that LINKS the standard module's object half rather than emitting it" - {

    // This is what the whole exercise is for, and it is the half nothing consumes yet: the trees
    // above establish that an artifact *means* what the source means, and these establish what is
    // gained by taking it — a program that reaches the printing surface no longer carries a copy of
    // it. Nothing routes an ordinary compilation here; the standard module is still handed over as source.

    "declares the printing surface instead of defining it" in {
      val ir = linked("print(1)\n")

      ir should include(s"declare void @${Library.key("printi")}(")
      ir should not include s"define void @${Library.key("printi")}("
    }

    "and every definition it loses is one the artifact holds, or the module's own runtime" in {
      // The claim that matters. Linking is allowed to change the IR — that is the point — but only in
      // ways that account for themselves, and anything else would be a second compilation rather than
      // the same one with its bodies elsewhere.
      //
      // Two kinds of definition go, and they go for different reasons. A library function the
      // artifact compiled becomes a **declaration**, resolved at the link. The ARC runtime is not
      // that: it is emitted on demand by the bodies that need it, so a module that stopped emitting
      // those bodies stops needing it, and it simply is not there. It leaves no declaration behind
      // because nothing links to it — see the next test.
      val program = "print(1)\nprint(\"two\")\nprint(3.5)\n"
      val fromSrc = against(Library.carried, program)
      val fromLib = linked(program)

      val (nowLinked, nowUnneeded) = (defines(fromSrc) -- defines(fromLib)).partition(precompiled)

      nowLinked should not be empty
      declares(fromLib) -- declares(fromSrc) shouldBe nowLinked
      nowUnneeded.filterNot(_.startsWith("arc.")) shouldBe empty

      // And nothing appeared out of nowhere: every symbol the linked module defines, the source one
      // defined too.
      defines(fromLib) -- defines(fromSrc) shouldBe empty
    }

    "and the runtime it drops is module-private, which is why the artifact may carry one too" in {
      // What makes the line above safe rather than lucky. `arc.retain` and its neighbours are emitted
      // with `private` linkage, so the artifact's copy and a program's own are different symbols and
      // the linker never sees a pair. Were they external, a program that still needed the runtime for
      // its *own* counted values would collide with the library that shipped one.
      artifact._1 should include("define private void @arc.retain(")
      against(Library.carried, "var s = \"x\"\nprint(s)\n") should include("define private void @arc.retain(")
    }

    "while a generic is built here even at a type the library already shipped one instantiation of" in {
      // A generic has no compiled form until a caller fixes its arguments, so what an artifact can
      // carry is instantiations rather than the generic — the library pushes onto a `Buf[u8]` and a
      // `Buf[string]` of its own, and neither is what a program asking for a `Buf[int]` needs. The
      // pair is the discriminating part: the same declaration is linked at one argument and compiled
      // at another, in one program.
      //
      // **`int` is also the argument `lib/sysl/buf/tests.sysl` uses throughout**, which makes this
      // the regression test for `Tests.stripSource`. Analyzing a test body monomorphizes whatever it
      // names, and an instantiation is an ordinary library function afterwards — so a library that
      // stripped its tests from the *typed* tree would ship the whole of `Buf[int]` here and fail on
      // the line below. That the library's own tests exercise a type its shipping code does not is
      // the luck; that the artifact is unmoved by them is the claim.
      val ir = linked("import sysl.buf.*\n\nvar b: Buf[int] = buf()\nb.push(1)\nprint(b.len())\n")

      precompiled should contain(s"${Library.key("Buf")}.push.byte")
      precompiled should not contain s"${Library.key("Buf")}.push.int"
      defines(ir) should contain(s"${Library.key("Buf")}.push.int")
    }

    "and it runs, with the library's bodies coming from the artifact's object file" in {
      // The end of the claim: the symbols the module stopped defining resolve at the link, and the
      // program prints what it printed when it carried its own copy of them.
      assume(Toolchain.clangAvailable, "clang not available")

      val program = "print(1)\nprint(\"two\")\nprint(3.5)\nprint(true)\n"
      val obj     = createTempFile("sysl-std-", ".o")
      val exe     = createTempFile("sysl-std-", "")

      Toolchain.compileObject(artifact._1, obj, Target.default) match
        case Left(err) => fail(s"the standard module library did not assemble: $err")
        case Right(_)  => ()

      val ran = Toolchain.build(linked(program), exe, Target.default, List(obj)).map { _ =>
        val r = exec(List(exe))

        (r.exitCode, r.stdout)
      }

      deleteFile(obj)
      deleteFile(exe)
      ran shouldBe Right((0, "1\ntwo\n3.5\ntrue\n"))
    }

    /* A library's object half is one `.o` named on the link line, and a named object is linked
     * *entire* — so what a program does not call is carried anyway unless the linker is asked to
     * drop it. Before `Toolchain.deadStrip`, a program whose whole text was `print(1)` carried all
     * 61 of the standard module's symbols: the reader, the line buffering, the string builder, the
     * hashes. The binary was 53,496 bytes where 33,728 would do. */

    "and carries only the library it reaches, the rest being dropped at the link" in {
      assume(Toolchain.clangAvailable, "clang not available")

      val obj = createTempFile("sysl-std-", ".o")
      val exe = createTempFile("sysl-std-", "")

      Toolchain.compileObject(artifact._1, obj, Target.default) match
        case Left(err) => fail(s"the standard module library did not assemble: $err")
        case Right(_)  => ()

      Toolchain.build(linked("print(1)\n"), exe, Target.default, List(obj)) match
        case Left(err) => fail(s"the program did not link: $err")
        case Right(_)  => ()

      // `nm` rather than a byte count: the size is a consequence and would drift with every change
      // to the library, while *which* symbols survive is the claim itself. Reading it needs no
      // parsing beyond a substring, since every std symbol carries the module in its name.
      val listed = exec(List("nm", exe))
      val kept   = listed.stdout.linesIterator.filter(_.contains(Library.key(""))).toList

      deleteFile(obj)
      deleteFile(exe)

      // Skipped rather than failed where there is no `nm`: this asserts something about the
      // platform's linker, and a machine that cannot list symbols cannot be asked about it.
      assume(listed.exitCode == 0, "nm not available")

      // Reached: printing an int goes through the renderer and out to the sink.
      kept.count(_.contains(Library.key("printi"))) shouldBe 1
      kept.count(_.contains(Library.key("putbytes"))) shouldBe 1

      // Not reached by `print(1)` — and every one of these was in the binary before dead-stripping,
      // which is what makes this a test of the flag rather than of the library's shape.
      for gone <- List("lines", "find_byte", "str_builder", "cstring", "hash_u128", "fd_reader") do
        withClue(s"$gone should have been dropped: ") {
          kept.exists(_.contains(Library.key(gone))) shouldBe false
        }

      // The whole library is far larger than what one `print` reaches, so a link that dropped
      // nothing would leave dozens here. Bounded rather than exact, since which helpers the
      // renderer itself pulls in is the library's business and changes with it.
      kept.length should be < 12
    }
  }

  /* Everything above builds the artifact in memory, which is the half that can be checked without a
   * toolchain. `Stdlib.writeArtifact` is the other half: the same build, assembled and archived onto
   * disk at a named path. It is what a compilation finding nothing usable at the default path calls
   * (`Main.foundStd`) and what the suite's own `PrebuiltStd` calls, so the two cannot drift — and
   * nothing pinned it while it was a private routine of the driver's. */

  "the standard module written to disk as an artifact" - {

    "reads back as the standard module this compiler carries" in {
      assume(Toolchain.clangAvailable, "clang not available")
      assume(Toolchain.findAr(None).isRight, "llvm-ar not available")

      val out = s"${createTempDirectory("sysl-write-")}/std${LibraryArtifact.extension}"

      Stdlib.writeArtifact(out, Target.default) shouldBe Right(())

      val back =
        for
          m <- LibraryArtifact.metadataOf(out, readBytes(out))
          r <- Stdlib.read(out, m, Target.default)
        yield r

      // The exact symbol set and the exact module list, not merely that something decoded: an
      // artifact built from other sources than the compiler carries decodes perfectly well and is
      // the wrong library, which is the failure `Stdlib.read`'s fingerprint check exists to catch.
      back.map(_._2) shouldBe Right(precompiled)
      back.map(_._1.modules) shouldBe Right(decoded.modules)

      deleteFile(out)
    }

    "makes the directory it is asked to write into, which a fresh clone has never had" in {
      assume(Toolchain.clangAvailable, "clang not available")
      assume(Toolchain.findAr(None).isRight, "llvm-ar not available")

      // The default path is inside a cache directory that no installer creates, and the artifact is
      // derived rather than committed. Writing to a directory that has never existed is therefore the
      // ordinary case rather than an unusual one, so the parent has to be made rather than assumed.
      val dir = s"${createTempDirectory("sysl-write-")}/.sysl"
      val out = s"$dir/std${LibraryArtifact.extension}"

      isDirectory(dir) shouldBe false
      Stdlib.writeArtifact(out, Target.default) shouldBe Right(())
      isFile(out) shouldBe true

      deleteFile(out)
    }

    "and says which flag names the archiver when it cannot run the one it was given" in {
      // The archiver is looked for first, so a build that has nowhere to put its members fails
      // before it compiles any — and the message names `--ar`, since a caller who passed one is
      // being told about the path they passed rather than about a search that never ran.
      val out = s"${createTempDirectory("sysl-write-")}/std${LibraryArtifact.extension}"

      Stdlib.writeArtifact(out, Target.default, Some("/nonexistent/llvm-ar")) match
        case Right(_)  => fail("an archiver that cannot run should not have produced an artifact")
        case Left(err) =>
          err should include("/nonexistent/llvm-ar")
          err should include("--ar")

      exists(out) shouldBe false
    }

    "and a build that fails leaves the artifact that was already there readable" in {
      assume(Toolchain.clangAvailable, "clang not available")
      assume(Toolchain.findAr(None).isRight, "llvm-ar not available")

      // A program that runs, is given the archiver's arguments, and writes nothing — which is the
      // only way to fail at the *archive* step, since an archiver that cannot run at all is caught
      // by the search before a single member is compiled.
      val inert = List("/usr/bin/true", "/bin/true").find(exists)

      assume(inert.isDefined, "no do-nothing program to stand in for an archiver")

      val dir = createTempDirectory("sysl-write-")
      val out = s"$dir/std${LibraryArtifact.extension}"

      Stdlib.writeArtifact(out, Target.default) shouldBe Right(())

      val was = readBytes(out).toList

      // The artifact at the default path is *shared* — it is named by a fingerprint of the library,
      // so every compilation of the same library on the machine names the same file, and separate
      // worktrees at one commit name it at the same time. Assembling in place would mean `ar`
      // truncating that file before it wrote, so a rebuild that then failed would take a working
      // artifact with it and a reader arriving mid-build would find half an archive.
      Stdlib.writeArtifact(out, Target.default, inert) match
        case Right(_)  => fail("an archiver that produced no archive should not have reported success")
        case Left(err) => err should include(out)

      readBytes(out).toList shouldBe was

      // And the half-built one is not left lying beside it under whatever name it was assembled
      // under: the artifact is the only thing either run put in this directory.
      listFiles(dir).map(Project.basename) shouldBe List(Project.basename(out))

      deleteFile(out)
      deleteFile(dir)
    }
  }

  /** The invariant a linker enforces, asserted where a linker is not needed to see it.
   *
   * A program and the artifact it links must not both **define** a symbol. Everything else in this
   * file asks whether the right things are declared; this asks the other half of the same question,
   * and it is the half that was wrong.
   *
   * **It is asserted on the IR rather than by linking, because linking does not reliably fail.**
   * `ld64` takes the first of two definitions and says nothing, so a run-tier test on macOS passes
   * with the bug present; GNU `ld` reports `multiple definition` and stops. A suite that ran only
   * where the linker is forgiving would go on being green — which is exactly what happened, for as
   * long as the pages that trigger it were only ever compiled on one platform.
   */
  "nothing is defined twice" - {

    "not for a program that reaches the library at all" in {
      bothDefine("""print("x")""") shouldBe empty
    }

    // The case that found this. `display` takes an `out: *Writer`, which reaches `sysl.stdout` —
    // the one library function a program compiles for itself, because it reads a module-level
    // `val` and a library has no entry point to initialize one. Being compiled for itself is
    // correct; being compiled into the artifact *as well* is what made two definitions.
    "not for a program that implements Display, which reaches a writer" in {
      bothDefine(displaying) shouldBe empty
    }

    // Stated from the other end, so a change that stopped emitting something the program needs is
    // caught too: a program that reaches `sysl.stdout` still has to have it from somewhere, and
    // here it is its own. A program that does not reach it has neither copy, which is the pruning
    // working rather than a hole.
    "and what the program compiles for itself, it really does compile" in {
      external(linked(displaying)) should contain(Library.key("stdout"))
    }

    "which the artifact leaves to it" in {
      defines(artifact._1) should not contain Library.key("stdout")
      precompiled should not contain Library.key("stdout")
    }
  }
}
