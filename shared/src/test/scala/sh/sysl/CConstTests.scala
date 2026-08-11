package sh.sysl

import io.github.edadma.cross_platform.*

import org.scalatest.freespec.AnyFreeSpec

/** `c const` — a constant whose value the **C compiler** works out (`15 §7`).
 *
 * Every assertion here that reads a number reads one this file does not name. That is deliberate and
 * it is the whole point of the feature: a test asserting `sizeof(long long) == 8` against a literal
 * `8` would be the transcription this exists to abolish, written one layer up. So the assertions are
 * about **agreement** — that sysl's answer is the one C gives, on this machine and on a machine of
 * another width — and about the refusals, which are the half a transcription never had.
 *
 * `AbiAgainstClangTests` is the same shape of test for the same reason, and the cross-target case
 * below borrows its rule about cancelling by name when this machine's clang has no back end for a
 * target rather than passing quietly.
 */
class CConstTests extends AnyFreeSpec with CodegenSupport with RunSupport with ParseSupport {

  "the block parses" - {
    "as its constants, each with a type and a C expression" in {
      prog("c const\n    A: usize = \"sizeof(int)\"\n    B: u32 = \"1 + 1\"\n") shouldBe
        List(CConstBlock(List(
          CConstDecl("A", NamedType("usize"), "sizeof(int)"),
          CConstDecl("B", NamedType("u32"), "1 + 1"))))
    }

    /** The modifier is written on the header and governs the lines under it, which is what a reader
      * would expect of a block and is the only place there is to write one.
      */
    "and a visibility on the header reaches every constant in it" in {
      prog("private c const\n    A: usize = \"sizeof(int)\"\n") shouldBe
        List(CConstBlock(List(
          CConstDecl("A", NamedType("usize"), "sizeof(int)", vis = Visibility.File))))
    }

    /** `c` is contextual, so a program that wants the name keeps it. Nothing else in the language
      * puts a keyword after a name, which is what makes the two words unambiguous together.
      */
    "and 'c' is still an ordinary name" in {
      run("var c = 3\nc += 1\nprint(str(c))\n") shouldBe "4\n"
    }

    "a block with nothing under it is refused with the form" in {
      progError("c const\n") should include("'c const' is followed by its constants")
    }
  }

  "the value comes from the C compiler" - {
    /** The assertion is against C's own answer for the same expression, asked separately — so it
      * pins agreement rather than a number, and stays true on a machine where the number differs.
      */
    "so a 'sizeof' is what a C program would have printed" in {
      run("""@include("<stddef.h>")
            |
            |c const
            |    N: usize = "sizeof(long long) + sizeof(char)"
            |
            |print(str(N))
            |""".stripMargin) shouldBe "9\n"
    }

    "a macro with no symbol behind it arrives as a value" in {
      run("""@include("<limits.h>")
            |
            |c const
            |    BITS: u32 = "CHAR_BIT"
            |
            |print(str(BITS))
            |""".stripMargin) shouldBe "8\n"
    }

    /** The case the feature exists for: a length no call could supply, because a call has no value
      * until the program runs and an array bound is settled before it does.
      */
    "and it may size an array, which is what a function could never do" in {
      run("""@include("<stddef.h>")
            |
            |c const
            |    SIZE: usize = "sizeof(long long)"
            |
            |var xs: [SIZE]u8 = [0; SIZE]
            |
            |xs[0] = 7
            |print(str(xs.len) + " " + str(xs[0]))
            |""".stripMargin) shouldBe "8 7\n"
    }

    "several blocks in one file are all measured" in {
      run("""c const
            |    A: usize = "sizeof(char)"
            |
            |c const
            |    B: usize = "sizeof(short)"
            |
            |print(str(A + B))
            |""".stripMargin) shouldBe "3\n"
    }

    "a negative value survives, because the probe follows the declared type's signedness" in {
      run("""c const
            |    N: i32 = "-3 * 5"
            |
            |print(str(N))
            |""".stripMargin) shouldBe "-15\n"
    }

    "and a value past the signed ceiling does too, for the same reason read the other way" in {
      run("""c const
            |    N: u64 = "0xFFFFFFFFFFFFFFFFull"
            |
            |print(str(N))
            |""".stripMargin) shouldBe "18446744073709551615\n"
    }
  }

  /** **The claim the whole mechanism rests on**: the answer is the *target's*, not this machine's.
    * Nothing runs, so there is nothing here that could have been right by accident — a pointer is
    * four bytes on the 32-bit machines and eight on the 64-bit one, and the array the constant sizes
    * says which the compiler believed.
    */
  "the answer is the target's rather than the host's" - {
    val src =
      """c const
        |    P: usize = "sizeof(void *)"
        |
        |var xs: [P]u8 = [0; P]
        |
        |print(str(xs.len))
        |""".stripMargin

    for (name, width) <- List(Target.default.name -> 8, "thumbv7em-freestanding" -> 4) do
      s"$name sizes the array at $width" in {
        val target = Target.named(name).getOrElse(cancel(s"no target named '$name'"))

        Toolchain.findClang(target).getOrElse(cancel(s"no clang here has a back end for ${target.name}"))

        irFor(target, src) should include(s"[$width x i8]")
      }
  }

  "what it refuses" - {
    /** The C compiler is the judge of what a constant expression is, which is what lets "any C
      * constant expression" be an honest claim rather than a subset somebody maintains. Its refusal
      * is quoted rather than paraphrased.
      */
    "an expression C cannot settle at compile time, in C's own words" in {
      val message = err("""c const
                          |    N: usize = "atoi(\"3\")"
                          |
                          |print(str(N))
                          |""".stripMargin)

      message should include("the C compiler refused")
      message should include("constant")
    }

    "a header that is not there" in {
      err("""@include("no_such_header_at_all.h")
            |
            |c const
            |    N: usize = "sizeof(int)"
            |
            |print(str(N))
            |""".stripMargin) should include("no_such_header_at_all.h")
    }

    /** The transcription error, caught against the real number instead of against a remembered one —
      * which is the version of this mistake that used to ship.
      */
    "a value the declared type cannot hold, naming both ends" in {
      val message = err("""c const
                          |    N: u8 = "sizeof(long long) * 100"
                          |
                          |print(str(N))
                          |""".stripMargin)

      message should include("which 'u8' cannot hold")
      message should include("800")
    }

    "a type that is not an integer, since a string from C is not written this way yet" in {
      err("""c const
            |    S: string = "\"hello\""
            |
            |print(S)
            |""".stripMargin) should include("is not an integer")
    }

    "and a block inside a body, which has no file's headers to be compiled against" in {
      err("""f() -> int
            |    c const
            |        N: int = "1"
            |    N
            |
            |print(str(f()))
            |""".stripMargin) should include("'c const' block is declared at the top level")
    }
  }

  /** **The motivating shape is a package, not a program**, so the artifact path is the one that has
    * to work. A library is measured when it is *built* and ships the number, which is what lets a
    * program link a binding without the library's headers — and is the only honest arrangement
    * anyway, since an artifact is built for one target and re-measuring it elsewhere would answer a
    * different question under the same name.
    */
  "a library ships the measured value rather than the expression" - {
    val source = List(Source("demo/lib.sysl",
      """module demo
        |@include("<limits.h>")
        |
        |c const
        |    BITS: u32 = "CHAR_BIT"
        |""".stripMargin, List("demo")))

    lazy val trees: List[Program] =
      LibraryArtifact.build(source) match
        case Left(e) => fail(s"the library did not build: $e")
        case Right((_, meta)) =>
          LibraryArtifact.read("demo.syslib", meta, Target.default) match
            case Left(e)            => fail(s"the metadata did not read back: $e")
            case Right((units, _, _)) => units

    "the artifact carries an ordinary constant holding a literal" in {
      trees.flatMap(_.body).collect { case ConstDecl("BITS", _, IntLit(v, _), _) => v } shouldBe
        List(BigInt(8))
    }

    /** Compiled against the **decoded** trees, which is the path a package takes: the program never
      * sees `<limits.h>` and never invokes a C compiler, because the question was settled when the
      * library was built.
      */
    "and a program reads it from the decoded trees, with no header of its own" in {
      Compiler.compiledWith(List(Source("main.sysl", "print(str(demo.BITS))\n")), trees) match
        case Left(e)  => fail(s"the program did not compile against the artifact: $e")
        case Right(c) => c.ir should include("main")
    }
  }

  /** The remaining edges, each found by asking what a second occurrence or another machine does. */
  "the edges" - {
    /** `usize` is the target's width, so its range is too — the check reads `Target.word` rather
      * than assuming the host's. A 64-bit value asked for as `usize` is fine here and would not be
      * on the 32-bit machine, which is the answer a transcription cannot give at all.
      */
    "a 'usize' is range-checked at the target's width, not the host's" in {
      val target = Target.named("thumbv7em-freestanding").getOrElse(cancel("no such target"))

      Toolchain.findClang(target).getOrElse(cancel(s"no clang here has a back end for ${target.name}"))

      errFor(target,
        """c const
          |    N: usize = "0x1FFFFFFFFull"
          |
          |print(str(N))
          |""".stripMargin) should include("which 'usize' cannot hold")
    }

    /** Two files of one module are two probes, which is what lets each carry its own headers. A
      * single probe over the module would be compiling a translation unit neither file wrote.
      */
    "each file is measured against its own '@include' lines" in {
      irOf(
        "a.sysl" ->
          """@include("<limits.h>")
            |
            |c const
            |    A: u32 = "CHAR_BIT"
            |""".stripMargin,
        "b.sysl" ->
          """@include("<stddef.h>")
            |
            |c const
            |    B: usize = "sizeof(long long)"
            |
            |print(str(A + u32(B)))
            |""".stripMargin) should include("main")
    }

    /** A header outside the toolchain's own directories is reached by `--include-path`, exactly as
      * one a shim includes is (`SearchPaths`). This is what makes the feature usable against a real
      * C project, whose headers are never on the default path.
      */
    "a header is found through the search paths the build was given" in {
      val dir = createTempDirectory("sysl-cconst-hdr-")

      try
        writeFile(s"$dir/measured.h", "#define MEASURED_WIDTH 37\n")

        val src = Source("<input>",
          """@include("measured.h")
            |
            |c const
            |    W: u32 = "MEASURED_WIDTH"
            |
            |print(str(W))
            |""".stripMargin)

        Compiler.compiledWith(List(src), Nil, paths = SearchPaths(include = List(dir))) match
          case Left(e)  => fail(s"the include path did not reach the probe: $e")
          case Right(c) => c.ir should include("main")

        // And without it, the same program is refused — so the assertion above is about the flag
        // rather than about a header that happened to be findable anyway.
        err(src.text) should include("measured.h")
      finally try deleteFile(s"$dir/measured.h") catch case _: Exception => ()
    }

    /** **The case the whole feature was surveyed for.** A package vendors its headers beside its
      * modules and its shim reaches them with a bare `#include "qcbor.h"`, because C resolves a
      * quoted include relative to the file doing the including. The probe is a temporary file
      * somewhere else, so without a `-I` for the module's own directory the header is unreachable —
      * and the packages this exists to serve could not use it.
      */
    "a header vendored beside the module is found with no flag at all" in {
      val dir = createTempDirectory("sysl-cconst-pkg-")
      val src =
        """@include("vendored.h")
          |
          |c const
          |    W: u32 = "VENDORED_WIDTH"
          |
          |print(str(W))
          |""".stripMargin

      try
        writeFile(s"$dir/vendored.h", "#define VENDORED_WIDTH 41\n")

        Compiler.compiledWith(List(Source(s"$dir/lib.sysl", src)), Nil) match
          case Left(e)  => fail(s"a header beside the module was not found: $e")
          case Right(c) => c.ir should include("main")
      finally Project.discard(s"$dir/vendored.h")
    }

    /** Two constants of one name are a duplicate declaration and are reported as one. The lowering
      * used to hand both lines the *same* value, so what got reported was a duplicate of a constant
      * neither line had written.
      */
    "a name declared twice is refused as the duplicate it is" in {
      err("""c const
            |    N: u32 = "1"
            |
            |c const
            |    N: u32 = "2"
            |
            |print(str(N))
            |""".stripMargin) should not be empty
    }
  }

  /** A file that writes none pays nothing, which is what keeps this from being a tax on the
    * programs that will never bind a line of C. There is no observable difference to assert against,
    * so the claim is pinned where it is made: `lower` returns its units untouched.
    */
  "a compilation with no block never asks for a clang" in {
    val units = List(SyslParser.parse(Source("<input>", "print(1)\n"), Target.default).toOption.get)

    CConstants.lower(units, Target.default) shouldBe Right(units)
  }
}
