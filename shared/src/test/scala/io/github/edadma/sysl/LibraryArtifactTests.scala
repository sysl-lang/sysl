package io.github.edadma.sysl

import io.github.edadma.cross_platform.*

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

/** Separate compilation: a library built once into a `.syslib`, and a program linked against it
 * (`LibraryArtifact`, `13 § Open d`).
 *
 * **What is worth pinning here is the split**, because it is the whole claim. A declaration with no
 * type parameters is compiled by whoever built the library and *linked* by whoever uses it; a
 * generic has nothing to compile until a caller fixes its arguments, so it travels as a tree and is
 * monomorphized in the consuming program. Both halves are checked at the seam a user sees — the
 * emitted IR for which is which, and a program that actually runs.
 */
class LibraryArtifactTests extends AnyFreeSpec with Matchers {

  private val library =
    """module demo
      |
      |double(n: int) -> int = n * 2
      |
      |larger[T: Ord](a: T, b: T) -> T = if a < b then b else a
      |
      |val squares: [4]int = [0, 1, 4, 9]
      |
      |lookup(i: int) -> int = squares[i]
      |""".stripMargin

  private def sources: List[Source] = List(Source("demo/lib.sysl", library, List("demo")))

  private def built: (String, String) =
    LibraryArtifact.build(sources) match
      case Right(r)  => r
      case Left(err) => fail(s"the library did not build: $err")

  /** The metadata as a consumer reads it back. */
  private def metadata: (List[Program], Set[String]) =
    LibraryArtifact.read("demo.syslib", built._2) match
      case Right(r)  => r
      case Left(err) => fail(s"the metadata did not read back: $err")

  "what a library compiles ahead of time" - {

    "a declaration with no type parameters is compiled once, by the library" in {
      metadata._2 should contain("demo$double")
    }

    "a generic is not, because there is nothing to compile until a caller fixes its arguments" in {
      metadata._2.filter(_.startsWith("demo$larger")) shouldBe empty
    }

    "nor is one that reads module-level storage, which no library initializes" in {
      // The honest boundary of what separate compilation reaches today: a `val`'s storage is written
      // by the entry point, and a library has none — so `lookup` is compiled in the program, where
      // the initialization it depends on actually happens.
      metadata._2 should not contain "demo$lookup"
    }

    "and the tree carries every declaration, precompiled or not" in {
      // A call into the precompiled half still has to be type-checked, and the signature is in the
      // tree — so the tree is not just the generics.
      val names = metadata._1.flatMap(_.body).collect { case f: FuncDecl => f.name }

      names should contain allOf ("double", "larger", "lookup")
    }
  }

  "the container" - {

    "carries the metadata and the object code as one file" in {
      val packed = LibraryArtifact.pack("meta", Array[Byte](1, 2, 3))

      LibraryArtifact.unpack("x.syslib", packed) match
        case Right((meta, obj)) => meta shouldBe "meta"; obj.toList shouldBe List[Byte](1, 2, 3)
        case Left(err)          => fail(err)
    }

    "keeps a boundary that holds when the metadata is not ASCII" in {
      // The length in the header is in **bytes**, and the metadata carries the library's own source
      // text, which is UTF-8. Counting characters would put the object's first byte inside the text.
      val packed = LibraryArtifact.pack("π≈3", Array[Byte](7, 7))

      LibraryArtifact.unpack("x.syslib", packed) match
        case Right((meta, obj)) => meta shouldBe "π≈3"; obj.toList shouldBe List[Byte](7, 7)
        case Left(err)          => fail(err)
    }

    "refuses a file that is not one of ours rather than reading it as one" in {
      LibraryArtifact.unpack("x.syslib", "not a library at all\n".getBytes) match
        case Left(err) => err should include("is not a sysl library")
        case Right(_)  => fail("a foreign file was read as a library")
      }

    "refuses one built by a different compiler, and says to rebuild it" in {
      val stale = s"syslib ${LibraryArtifact.Version + 1} 0\n".getBytes

      LibraryArtifact.unpack("x.syslib", stale) match
        case Left(err) => err should include("rebuild it with 'sysl build-lib'")
        case Right(_)  => fail("an artifact from another format version was accepted")
    }

    "refuses a truncated one rather than handing back a short object" in {
      LibraryArtifact.unpack("x.syslib", "syslib 1 500\nshort".getBytes) match
        case Left(err) => err should include("truncated")
        case Right(_)  => fail("a truncated artifact was accepted")
    }
  }

  "a library that does not check is refused before anything is written" in {
    // Otherwise the artifact ships anyway and every program that links against it is handed a
    // diagnostic pointing into somebody else's source.
    LibraryArtifact.build(List(Source("demo/bad.sysl", "module demo\n\nf() -> int = \"no\"\n", List("demo")))) match
      case Left(err) => err should include("int")
      case Right(_)  => fail("a library that does not type-check produced an artifact")
  }

  /** The library built to a real object file, and a program compiled against it exactly as the
   * driver does it — decoded metadata for the trees, the unpacked object handed to the linker.
   */
  private def linked(program: String): (String, Either[String, (Int, String)]) = {
    val (ir, _)       = built
    val (trees, syms) = metadata
    val obj           = createTempFile("sysl-test-", ".o")

    Toolchain.compileObject(ir, obj) match
      case Left(err) => fail(s"the library did not assemble: $err")
      case Right(_)  => ()

    val emitted = Compiler.compiledWith(List(Source("<input>", program)), trees, Target.default, syms) match
      case Right((out, _)) => out
      case Left(err)       => fail(err)

    val exe = createTempFile("sysl-test-", "")
    val ran =
      Toolchain.build(emitted, exe, Target.default, List(obj)).map { _ =>
        val r = exec(List(exe))
        (r.exitCode, r.stdout)
      }

    deleteFile(obj)
    deleteFile(exe)
    (emitted, ran)
  }

  "a program linked against the artifact" - {

    "declares the precompiled half rather than defining it a second time" in {
      // Defining it here as well is a duplicate symbol at the link, which is the failure this
      // whole mechanism exists to avoid.
      val (ir, _) = linked("print(demo.double(21))")

      ir should include("declare i32 @demo$double(i32)")
      ir should not include "define i32 @demo$double("
    }

    "defines a generic here, at each type the program uses it at" in {
      val (ir, _) = linked("print(demo.larger(3, 7))\nprint(demo.larger(\"a\", \"b\"))")

      ir should include("define i32 @demo$larger.int(")
      ir should include regex """define \{ ptr, ptr, i64 \} @demo\$larger\.string\("""
    }

    "runs, with the precompiled body coming from the library's object file" in {
      assume(Toolchain.clangAvailable, "clang not available")

      linked("print(demo.double(21))\nprint(demo.larger(3, 7))")._2 shouldBe Right((0, "42\n7\n"))
    }

    "and the library carries no entry point of its own to collide with the program's" in {
      // A `main` in the library's object would be a duplicate symbol in every program that linked
      // it, and the linker's complaint names neither the library nor the cause.
      built._1 should not include "define i32 @main("
    }
  }
}
