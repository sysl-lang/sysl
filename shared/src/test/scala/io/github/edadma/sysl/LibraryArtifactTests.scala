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

  /** The metadata as a consumer reads it back: the trees, the precompiled symbols, and the
   * fingerprint of the source it was built from.
   */
  private def metadata: (List[Program], Set[String], String) =
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
      // The version travels from the constant rather than being written out, so that a bump to the
      // container format leaves this testing truncation instead of quietly testing the version
      // check that now fires ahead of it.
      LibraryArtifact.unpack("x.syslib", s"syslib ${LibraryArtifact.Version} 500\nshort".getBytes) match
        case Left(err) => err should include("truncated")
        case Right(_)  => fail("a truncated artifact was accepted")
    }
  }

  /** A library whose one function prints — which is what reaches the shipped library's own
   * `printi`, `printc` and `putbytes` from inside a library build.
   */
  private def printing(module: String, fn: String): (String, String) =
    LibraryArtifact.build(List(Source(s"$module/lib.sysl",
      s"module $module\n\n$fn(n: int)\n    print(n)\nend $fn\n", List(module)))) match
      case Right(r)  => r
      case Left(err) => fail(s"the library did not build: $err")

  "what a library does NOT compile, however much it uses it" - {

    "the shipped library's own functions are declared, not defined a second time here" in {
      // A library that prints reaches `printi` and `putbytes` exactly as a program does, and
      // emitting them would put a copy of the printing surface in every artifact.
      val (ir, meta) = printing("say", "hello")

      LibraryArtifact.read("say.syslib", meta) match
        case Right((_, syms, _)) => syms shouldBe Set("say$hello")
        case Left(err)           => fail(err)

      ir should include("define void @say$hello(")
      ir should include(s"declare void @${Library.key("printi")}(")
      ir should not include s"define void @${Library.key("printi")}("
    }

    "so two libraries that both print can be linked into one program" in {
      // Before the split above they could not: each artifact defined `printi`, `printc` and
      // `putbytes`, and the linker refused the pair with three duplicate symbols. The consuming
      // program defines them once, reached through the very bodies that called for them.
      assume(Toolchain.clangAvailable, "clang not available")

      val built = List(printing("say", "hello"), printing("shout", "loud"))
      val objects = built.map { (ir, _) =>
        val obj = createTempFile("sysl-test-", ".o")

        Toolchain.compileObject(ir, obj) match
          case Left(err) => fail(s"a library did not assemble: $err")
          case Right(_)  => obj
      }
      val trees = built.flatMap { (_, meta) =>
        LibraryArtifact.read("x.syslib", meta) match
          case Right((t, _, _)) => t
          case Left(err)        => fail(err)
      }
      val syms = Set("say$hello", "shout$loud")
      val emitted =
        Compiler.compiledWith(List(Source("<input>", "say.hello(1)\nshout.loud(3)\nprint(2)")),
          trees, Target.default, syms) match
          case Right((out, _)) => out
          case Left(err)       => fail(err)

      val exe = createTempFile("sysl-test-", "")
      val ran = Toolchain.build(emitted, exe, Target.default, objects).map { _ =>
        val r = exec(List(exe))

        (r.exitCode, r.stdout)
      }

      objects.foreach(deleteFile)
      deleteFile(exe)
      ran shouldBe Right((0, "1\n3\n2\n"))
    }
  }

  "a library may not sit in the module a program's own headerless files are in" in {
    // The root module has no name, so nothing that depended on this library could write a path to
    // what it declares — and its keys would be the consuming program's own.
    LibraryArtifact.build(List(Source("lib.sysl", "double(n: int) -> int = n * 2\n", Nil))) match
      case Left(err) => err should include("is reached by naming its module")
      case Right(_)  => fail("a library with no module of its own produced an artifact")
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
    val (ir, _)          = built
    val (trees, syms, _) = metadata
    val obj              = createTempFile("sysl-test-", ".o")

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
