package io.github.edadma.sysl

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
 * emitted IR, compared **byte for byte** between a compilation against the embedded core and one
 * against the decoded artifact, over programs chosen to reach as much of the library as they can.
 * That is a stronger statement than any behavioural test could make, and it is cheap: the IR is a
 * string, so nothing here needs a toolchain.
 *
 * The order matters as much as the assertion. Nothing has moved onto the artifact path yet — the
 * whole suite still compiles the library from source, and this is the test that says it *could*.
 * Writing it first is what keeps the move from being a switch: a switch would put every program onto
 * a path nothing had exercised, where one hole fails everything at once with nothing to bisect.
 */
class CoreArtifactTests extends AnyFreeSpec with Matchers {

  /** The core built exactly as `sysl build-lib lib --core` builds it. This is the production path
   * rather than a hand-rolled `AstCodec.encode`, so what is compared below is what a program would
   * actually be handed.
   *
   * No toolchain is involved: `build` yields the IR and the metadata, and only the driver goes on to
   * assemble the first into an object file.
   */
  private lazy val artifact: (String, String) =
    LibraryArtifact.build(Std.sources, Target.default, LibraryArtifact.core) match
      case Right(r)  => r
      case Left(err) => fail(s"the core library did not build: $err")

  private lazy val read: (Core, Set[String]) =
    Core.read("sysl.syslib", artifact._2) match
      case Right(r)  => r
      case Left(err) => fail(s"the core metadata did not read back: $err")

  private def decoded: Core       = read._1
  private def precompiled: Set[String] = read._2

  /** One program compiled against one core, through the entry point the driver itself uses — so the
   * two sides below differ in the core and in nothing else.
   *
   * `linked` is what the core's object half already defines, which the program declares rather than
   * emits a second time. Empty is the compilation every program gets today.
   */
  private def against(core: Core, program: String, linked: Set[String] = Set.empty): String =
    Compiler.compiledWith(List(Source("<input>", program)), Nil, Target.default, linked, core) match
      case Right((ir, _)) => ir
      case Left(err)      => fail(s"the program did not compile:\n$err")

  private def sameBothWays(program: String): Unit =
    against(decoded, program) shouldBe against(Core.embedded, program)

  /** The same program with the core's object half linked rather than emitted. */
  private def linked(program: String): String = against(decoded, program, precompiled)

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
      decoded should not be theSameInstanceAs(Core.embedded)
    }

    "and its declarations belong to it rather than to the embedded copy" in {
      // A `Source` compares by identity, so a decoded file named `lib/sysl/print.sysl` is a
      // different source from the embedded file of the same name — which is exactly what makes the
      // IR match below a result rather than a tautology.
      val one = decoded.decls.find(_.pos.isDefined).getOrElse(fail("the decoded core carries no positions"))

      decoded.owns(one) shouldBe true
      Core.embedded.owns(one) shouldBe false
    }

    "and it carries the same declarations, so the comparison is between equals" in {
      decoded.units.map(_.source.name) shouldBe Core.embedded.units.map(_.source.name)
      decoded.decls.length shouldBe Core.embedded.decls.length
    }
  }

  "a program compiled against the decoded core emits exactly what one compiled against the source does" - {

    "for a program that reaches nothing of the library at all" in {
      // The floor of the claim: with nothing of the library reached, the two compilations may still
      // differ, since which declarations are held back is decided over the core either way.
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
      // `Option`'s own members travel as trees and are built in whatever program instantiates them.
      precompiled.filter(_.startsWith(Library.key("Option"))) shouldBe empty
    }

    "and the library carries no entry point of its own to collide with a program's" in {
      artifact._1 should not include "define i32 @main("
    }

    "and one library built two ways has one object half, whichever `Source` objects carried it" in {
      // A regression test, and the failure it guards is a silent one. Which declarations are held
      // back until something reaches them was decided by `Core.owns` alone, which is identity on the
      // `Source` — so building the core from `Std.sources`, the copy already in memory, held back
      // *every* function in it. Nothing reached any of them, and the artifact came out with an empty
      // object half: it still carried every tree, so every program compiled and ran, and the whole
      // point of precompiling was gone with nothing failing to say so. Read off disk the same files
      // answered the other way. The fix is that a compilation **building** a module does not treat
      // that module as supplied to it (`AnalyzerBase.suppliedByLibrary`).
      assume(CoreLib.root.isDefined, "lib/ not found from the test working directory")

      val fromDisk = LibraryArtifact.build(Project.collect(CoreLib.root.get), Target.default, LibraryArtifact.core)

      fromDisk match
        case Right((_, meta)) =>
          LibraryArtifact.read("disk.syslib", meta) match
            case Right((_, syms)) => syms shouldBe precompiled
            case Left(err)        => fail(err)
        case Left(err) => fail(s"the core library did not build from disk: $err")
    }
  }

  "a program that LINKS the core's object half rather than emitting it" - {

    // This is what the whole exercise is for, and it is the half nothing consumes yet: the trees
    // above establish that an artifact *means* what the source means, and these establish what is
    // gained by taking it — a program that reaches the printing surface no longer carries a copy of
    // it. Nothing routes an ordinary compilation here; the core is still handed over as source.

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
      val fromSrc = against(Core.embedded, program)
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
      against(Core.embedded, "var s = \"x\"\nprint(s)\n") should include("define private void @arc.retain(")
    }

    "while a generic is built here even at a type the library already shipped one instantiation of" in {
      // A generic has no compiled form until a caller fixes its arguments, so what an artifact can
      // carry is instantiations rather than the generic — the library pushes onto a `Buf[u8]` and a
      // `Buf[string]` of its own, and neither is what a program asking for a `Buf[int]` needs. The
      // pair is the discriminating part: the same declaration is linked at one argument and compiled
      // at another, in one program.
      val ir = linked("var b: Buf[int] = buf()\nb.push(1)\nprint(b.len())\n")

      precompiled should contain(s"${Library.key("Buf")}.push.byte")
      precompiled should not contain s"${Library.key("Buf")}.push.int"
      defines(ir) should contain(s"${Library.key("Buf")}.push.int")
    }

    "and it runs, with the library's bodies coming from the artifact's object file" in {
      // The end of the claim: the symbols the module stopped defining resolve at the link, and the
      // program prints what it printed when it carried its own copy of them.
      assume(Toolchain.clangAvailable, "clang not available")

      val program = "print(1)\nprint(\"two\")\nprint(3.5)\nprint(true)\n"
      val obj     = createTempFile("sysl-core-", ".o")
      val exe     = createTempFile("sysl-core-", "")

      Toolchain.compileObject(artifact._1, obj, Target.default) match
        case Left(err) => fail(s"the core library did not assemble: $err")
        case Right(_)  => ()

      val ran = Toolchain.build(linked(program), exe, Target.default, List(obj)).map { _ =>
        val r = exec(List(exe))

        (r.exitCode, r.stdout)
      }

      deleteFile(obj)
      deleteFile(exe)
      ran shouldBe Right((0, "1\ntwo\n3.5\ntrue\n"))
    }
  }
}
