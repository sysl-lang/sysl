package sh.sysl

import io.github.edadma.cross_platform.*

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

/** `link "z"` — the directive of `reference/ffi.md § @link`, by which a module of `extern`s tells
 * the driver which library resolves them.
 *
 * The suite is in three parts because the feature is: the clause has to *parse* where a header goes
 * and nowhere else, it has to reach the *command line* through every path that links, and it has to
 * survive being written into an artifact and read back. The last of those is the one that would fail
 * silently — a binding that works from source and stops working the moment it ships is a bug whose
 * first report comes from somebody else's build.
 */
class LinkDirectiveTests extends AnyFreeSpec with Matchers with RunSupport with CodegenSupport {

  /** What a program of these files would be linked against. */
  private def linksOf(fs: (String, String)*): List[String] =
    Compiler.compiled(files(fs*)) match
      case Right(built) => built.links
      case Left(e)      => fail(s"did not compile:\n$e")

  /** The same for a single-file program. */
  private def linksIn(src: String): List[String] = linksOf("t.sysl" -> src)

  /** What the standard module asks for on its own, with nothing added by the program. */
  private val fromTheLibrary = linksIn("print(1)\n")

  "the directive is read in the file's header" - {

    "beside a module header" in {
      linksOf("thing/a.sysl" -> "module thing\n@link(\"z\")\n\nextern zlibVersion() -> *u8\n",
        "main.sysl" -> "print(1)") should contain("z")
    }

    "a file with no module header may name one, since the root module is a module" in {
      linksIn("@link(\"z\")\n\nextern zlibVersion() -> *u8\n\nprint(1)\n") should contain("z")
    }

    // The two headers are about different things — what the module may do, and what its externs
    // need — so demanding one group before the other would be a rule with nothing behind it.
    "interleaved with capability clauses, in whatever order they were written" in {
      val src = "module thing\n@requires(os)\n@link(\"z\")\n@no_alloc\n@link(\"png\")\n\nf() -> int = 1\n"

      linksOf("thing/a.sysl" -> src, "main.sysl" -> "print(thing.f())")
        .filterNot(fromTheLibrary.contains) shouldBe List("z", "png")
    }

    "several on their own lines, each naming one library" in {
      linksIn("@link(\"png\")\n@link(\"z\")\n\nprint(1)\n")
        .filterNot(fromTheLibrary.contains) shouldBe List("png", "z")
    }

    "and one written below the statements says where it belongs" in {
      val e = err("f() -> int = 1\n@link(\"z\")\n")

      e should include("belongs in the file's header")
      e should include("directly after 'module'")
      e shouldNot include("newline expected")
    }

    // Each attribute takes a line, exactly as a capability's does, so that
    // `module m @no_alloc @link("z")` is never a line anyone has to read.
    "one on the header's own line is refused" in {
      err("module m @link(\"z\")\n\nf() -> int = 1\n") should include("belongs in the file's header")
    }
  }

  // A header line is a line, so `getting-started/cli.md § targets`'s gate reaches it — the lines of
  // a branch this build is not for are blanked before anything is parsed. That is worth having
  // rather than incidental: a library is named differently on different platforms often enough
  // (`ws2_32` for the sockets that are in libc elsewhere) that a binding needs to be able to say
  // so.
  "a directive can be gated on the target, like any other line" - {
    val src = "#if macos\n@link(\"framework-ish\")\n#endif\n\nprint(1)\n"

    def linksFor(target: Target): List[String] =
      Compiler.compiled(List(Source("t.sysl", src)), target) match
        case Right(built) => built.links
        case Left(e)      => fail(s"did not compile for ${target.name}:\n$e")

    "present for the target the branch is for" in {
      linksFor(Target.aarch64MacOS) should contain("framework-ish")
    }

    "and absent for one it is not" in {
      linksFor(Target.x86_64Linux) should not contain "framework-ish"
    }
  }

  // `link` is a soft keyword and has to stay one: `guide/slab` declares a function called `link` —
  // the pointer threading a free block — and reserving the word would break it. This is the pair of
  // tests that holds the grammar to it.
  "'link' is still an ordinary name everywhere else" - {

    "a function may be called it, and called" in {
      run("""link(b: *int) -> int = *b + 1
            |
            |var n = 4
            |print(link(&n))
            |""".stripMargin) shouldBe "5\n"
    }

    "a variable may be called it" in {
      run("var link = 3\nprint(link * 2)\n") shouldBe "6\n"
    }

    "and a field may be, which is what the slab guide actually does" in {
      run("""struct Node
            |    link: int
            |end Node
            |
            |var n = Node(7)
            |print(n.link)
            |""".stripMargin) shouldBe "7\n"
    }
  }

  "a directive that names no library is refused" - {

    "the empty name" in {
      err("@link(\"\")\n\nprint(1)\n") should include("names a library")
    }

    // A name is pasted onto a `-l`, so one beginning with a dash would arrive at the linker as a
    // flag this compiler never meant to pass.
    "a linker flag written where the library goes" in {
      val e = err("@link(\"-lz\")\n\nprint(1)\n")

      e should include("begins with a dash")
      e should include("names the library itself")
    }

    "two libraries in one directive" in {
      err("@link(\"png z\")\n\nprint(1)\n") should include("holds a space")
    }

    "and asking one file for the same library twice, which links it once" in {
      err("@link(\"z\")\n@link(\"z\")\n\nprint(1)\n") should include("already linked by this file")
    }
  }

  // The one place this differs from the capability clause it is otherwise shaped like. A capability
  // describes the whole module, so `reference/modules.md § Capabilities are a module property`
  // holds a module's files to agreeing; a link requirement describes one file's externs, and a
  // module whose externs sit in one file has nothing for the others to repeat.
  "the files of a module need not agree, unlike a capability" - {

    "one file may name a library the others do not" in {
      linksOf("thing/a.sysl" -> "module thing\n@link(\"z\")\n\nextern zlibVersion() -> *u8\n",
        "thing/b.sysl" -> "module thing\n\ng() -> int = 2\n",
        "main.sysl" -> "print(thing.g())") should contain("z")
    }

    "and two files naming the same one ask for it once" in {
      linksOf("thing/a.sysl" -> "module thing\n@link(\"z\")\n\nf() -> int = 1\n",
        "thing/b.sysl" -> "module thing\n@link(\"z\")\n\ng() -> int = 2\n",
        "main.sysl" -> "print(thing.g())").count(_ == "z") shouldBe 1
    }
  }

  "what reaches the command line" - {

    // The standard module says this for itself now, in `library/sysl/sys/math.sysl`, where the driver
    // used to carry it — which is what makes the mechanism load-bearing rather than decorative.
    "the standard module's own directive is in every compilation" in {
      fromTheLibrary should contain("m")
    }

    "and it is what puts '-lm' on an ELF link" in {
      Toolchain.libraryFlags(fromTheLibrary, Target.x86_64Linux) should contain("-lm")
      Toolchain.libraryFlags(fromTheLibrary, Target.aarch64MacOS) should not contain "-lm"
    }

    // A program's own directive and the library's both arrive, since an `extern` in a program is as
    // much a binding as one in a library.
    "a program's own directive arrives beside the library's" in {
      val links = linksIn("@link(\"z\")\n\nextern zlibVersion() -> *u8\n\nprint(1)\n")

      links should contain("z")
      links should contain("m")
    }

    // A test build is a different compilation from a program build rather than a variant of it, so
    // it collects its own — and a `sysl test` that linked without libm would fail on ELF only.
    "a test build asks for the same libraries a program build does" in {
      Compiler.compileTests(files("t.sysl" -> "@link(\"z\")\n\n@test\nt() = ()\n"), Nil) match
        case Right((built, _)) =>
          built.links should contain("z")
          built.links should contain("m")
        case Left(e) => fail(s"did not compile:\n$e")
    }
  }

  // Everything above reads a list this compiler built. This is the one that watches the linker read
  // it: a library that does not exist has to fail the link and name itself, which nothing but a real
  // `-l` reaching a real clang can produce.
  "the flag reaches the linker, not just the command list" in {
    assume(Toolchain.clangAvailable, "clang not available")

    val built = Compiler.compiled(files("t.sysl" -> "@link(\"sysl-no-such-library\")\n\nprint(1)\n")) match
      case Right(c) => c
      case Left(e)  => fail(s"did not compile:\n$e")

    val exe = createTempFile("sysl-link-", "")

    try
      Toolchain.build(built.ir, exe, links = built.links) match
        case Right(_)  => fail("a link against a library that does not exist should not have succeeded")
        case Left(err) => err should include("sysl-no-such-library")
    finally
      try deleteFile(exe)
      catch case _: Exception => ()
  }

  "a directive survives being written into an artifact" - {

    // Without this a binding works from source and stops working the moment it ships, which is the
    // worst shape the bug could take: the build that breaks is one nobody here ran.
    "the clause is carried by the codec, with its position" in {
      val parsed = SyslParser.parse("module thing\n@link(\"z\")\n\nf() -> int = 1\n", "a.sysl") match
        case Right(p)  => p
        case Left(err) => fail(err)

      val back = AstCodec.decode(AstCodec.encode(List(parsed))) match
        case Right(ps) => ps.head
        case Left(err) => fail(err)

      back.links.map(_.name) shouldBe List("z")
      back.links.head.pos.map(_.line) shouldBe parsed.links.head.pos.map(_.line)
    }

    // The codec is what carries it, but a `.syslib` is what ships — so the assertion is made once
    // through the container a consumer actually reads, and not only through the encoding inside it.
    "a real artifact carries it to the program that links against it" in {
      val lib = List(Source("demo/net.sysl",
        "module demo\n@link(\"z\")\n\nzipped(n: int) -> int = n + 1\n", List("demo")))

      val meta = LibraryArtifact.build(lib) match
        case Right((_, m)) => m
        case Left(err)     => fail(s"the library did not build: $err")

      val trees = LibraryArtifact.read("demo.syslib", meta, Target.default) match
        case Right((units, _, _)) => units
        case Left(err)            => fail(s"the metadata did not read back: $err")

      Compiler.compiledWith(files("main.sysl" -> "print(demo.zipped(1))"), trees) match
        case Right(built) => built.links should contain("z")
        case Left(e)      => fail(s"did not compile against the artifact:\n$e")
    }

    "and a library decoded from one still asks for it" in {
      val lib = SyslParser.parse("module thing\n@link(\"z\")\n\nf() -> int = 1\n", "a.sysl") match
        case Right(p)  => p
        case Left(err) => fail(err)

      val decoded = AstCodec.decode(AstCodec.encode(List(lib))).toOption.get

      Compiler.compiledWith(files("main.sysl" -> "print(thing.f())"), decoded) match
        case Right(built) => built.links should contain("z")
        case Left(e)      => fail(s"did not compile:\n$e")
    }
  }
}
