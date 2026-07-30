package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

/** Compiling a program **against a library**, with the library crossing as an `AstCodec` artifact
 * and its names arriving by auto-import (`13 §3`, `13 § Open d`, `13 § Open h`).
 *
 * **This exists to prove the pieces before the prelude is asked to rely on them.** Replacing the
 * prelude with a standard module in one step would mean that a mistake anywhere in the path — the
 * artifact, the linking, the auto-import — breaks every program at once, with 4000 failing tests
 * and nothing to bisect. So the same path is built and exercised against a library that **nothing
 * depends on**: if any of it is wrong, the prelude still works and these tests say which piece it
 * was. The switch is then a change to `AutoImport.modules`, not a rewrite.
 *
 * The library is `devlib/demo`, kept deliberately useless. What it is not useless *about* is shape
 * coverage: a generic is the reason a library ships bodies rather than signatures (it is
 * monomorphized in the program that calls it), and a trait with an `impl` is the reason the orphan
 * rule has to know whose rows those are.
 */
class DevLibraryTests extends AnyFreeSpec with Matchers with RunSupport {

  /** The demo library as the driver would have read it off disk: one module, two files, each
   * carrying the directory it was found under. Kept in step with `devlib/demo` by the test at the
   * bottom, which reads the real files and compares.
   */
  private val numbers =
    """module demo
      |
      |const answer: int = 42
      |
      |val squares: [4]int = [0, 1, 4, 9]
      |
      |double(n: int) -> int = n * 2
      |
      |// A generic is the reason a library ships bodies rather than signatures: it is instantiated in the
      |// program that calls it, so its untyped tree has to survive the crossing.
      |larger[T: Ord](a: T, b: T) -> T = if a < b then b else a
      |
      |private helper(n: int) -> int = n + 1
      |
      |sum_to(n: int) -> int =
      |    var total = 0
      |
      |    for i in 1..n
      |        total += i
      |
      |    total
      |""".stripMargin

  private val pair =
    """module demo
      |
      |struct Pair
      |    a: int
      |    b: int
      |
      |    sum(self) -> int = self.a + self.b
      |    scaled(self, by: int) -> Pair = Pair(self.a * by, self.b * by)
      |    origin() -> Pair = Pair(0, 0)
      |
      |trait Widen
      |    widen(self) -> int
      |
      |impl Widen for Pair
      |    widen(self) -> int = self.sum() * 10
      |
      |enum Shade
      |    Light
      |    Dark
      |
      |    flip(self) -> Shade =
      |        self match
      |            Light -> Shade.Dark
      |            Dark -> Shade.Light
      |""".stripMargin

  private def librarySources: List[Source] =
    List(Source("numbers.sysl", numbers, List("demo")), Source("pair.sysl", pair, List("demo")))

  /** The library as it will really arrive: parsed, encoded to an artifact, and decoded again. Every
   * test below goes through this rather than through the parsed trees, so the artifact is on the
   * path the whole time rather than being checked once and then bypassed.
   */
  private def library: List[Program] = {
    val parsed = librarySources.map { s =>
      SyslParser.parse(s) match
        case Right(p) => p
        case Left(e)  => fail(s"the demo library does not parse: $e")
    }

    AstCodec.decode(AstCodec.encode(parsed)) match
      case Right(ps) => ps
      case Left(e)   => fail(s"the demo library artifact does not decode: $e")
  }

  /** `demo` is auto-imported for these tests alone. A shipped compiler auto-imports only the
   * standard module, so the development library is scoped to the suite that needs it rather than
   * claiming a module name in every compilation everyone runs.
   */
  private def withDemo[T](body: => T): T = AutoImport.including("demo")(body)

  private def ran(program: String): String = {
    assume(Toolchain.clangAvailable, "clang not available")

    withDemo(Toolchain.compileAndRun(List(Source("<input>", program)), library)) match
      case Right((0, out))    => out
      case Right((code, out)) => fail(s"program exited with $code:\n$out")
      case Left(err)          => fail(err)
  }

  private def refused(program: String): String =
    withDemo(Compiler.compileWith(List(Source("<input>", program)), library)) match
      case Left(err) => err
      case Right(_)  => fail("the program compiled, and should not have")

  "a library reached by its full path" - {

    "gives up a function" in {
      ran("print(demo.double(21))") shouldBe "42\n"
    }

    "gives up a const, folded like any other" in {
      ran("print(demo.answer)") shouldBe "42\n"
    }

    "gives up a module-level val, indexed at run time" in {
      ran("var i = 3\nprint(demo.squares[i])") shouldBe "9\n"
    }

    "gives up a struct, its method, and its associated function" in {
      ran("var p = demo.Pair(2, 3)\nprint(p.sum())\nprint(demo.Pair.origin().sum())") shouldBe "5\n0\n"
    }

    "gives up an enum and a member on it" in {
      ran("var s = demo.Shade.Light\nvar f = s.flip()\nf match\n    Dark -> print(1)\n    Light -> print(0)") shouldBe "1\n"
    }

    "gives up a trait, and the impl it was linked with" in {
      ran("var p = demo.Pair(2, 3)\nprint(p.widen())") shouldBe "50\n"
    }

    "instantiates a generic in the program that calls it, which is why bodies cross" in {
      // The library ships `larger[T: Ord]` as a tree; `T` is fixed here, not in the library.
      ran("print(demo.larger(3, 7))\nprint(demo.larger(\"a\", \"b\"))") shouldBe "7\nb\n"
    }

    "keeps a file-private helper to its own file" in {
      refused("print(demo.helper(1))") should include("helper")
    }
  }

  "the library's names arrive unqualified, with no import written" - {

    "a function" in {
      ran("print(double(21))") shouldBe "42\n"
    }

    "a type, where a type is asked for" in {
      ran("var p: Pair = Pair(4, 5)\nprint(p.sum())") shouldBe "9\n"
    }

    "a const, including where only a constant may stand" in {
      ran("var xs: [answer]int\nprint(xs.len)") shouldBe "42\n"
    }

    "an enum variant, through its enum" in {
      ran("var s = Shade.Dark\ns match\n    Dark -> print(1)\n    Light -> print(0)") shouldBe "1\n"
    }

    "a trait, as a bound" in {
      ran("show[T: Widen](x: T) -> int = x.widen()\nprint(show(Pair(1, 1)))") shouldBe "20\n"
    }
  }

  "the names the compiler spells for itself still reach the library" - {

    "a program's own `Display`, a format hole, and a library name in one program" in {
      // Everything here goes through `Library.key` rather than through name resolution: `Writer` and
      // `FormatSpec` in the signature the `impl` must match, the specifier `f"…"` builds, the
      // renderer `str` reaches for a scalar, and `putbytes` in the table standard output dispatches
      // through. A library linked beside the program must not disturb any of them.
      ran(
        """struct P
          |    x: int
          |
          |impl Display for P
          |    display(self, w: *Writer, spec: FormatSpec) = str(self.x).display(w, spec)
          |
          |var p = P(double(3))
          |print(p)
          |print(f"[${str(answer)}%5s]")
          |""".stripMargin) shouldBe "6\n[   42]\n"
    }

    "and a `for` over a library value, which reaches `Iterate` and `Option` by key" in {
      ran("for c in \"hi\".chars\n    print(c)\nprint(demo.answer)") shouldBe "h\ni\n42\n"
    }
  }

  "the auto-import loses to anything more specific" - {

    "a declaration the program makes itself" in {
      // `13 §3`: a local declaration beats a wildcard, so a program may name its own `Pair` without
      // the library's becoming unreachable — it is still there under its full path.
      ran(
        """struct Pair
          |    n: int
          |
          |    sum(self) -> int = self.n * 100
          |
          |var mine = Pair(7)
          |var theirs = demo.Pair(2, 3)
          |print(mine.sum())
          |print(theirs.sum())
          |""".stripMargin) shouldBe "700\n5\n"
    }

    "a function the program declares under the same name" in {
      ran("double(n: int) -> int = n * 3\nprint(double(4))\nprint(demo.double(4))") shouldBe "12\n8\n"
    }

    "a local binding, which shadows it inside the block" in {
      ran("var answer = 7\nprint(answer)\nprint(demo.answer)") shouldBe "7\n42\n"
    }
  }

  "an explicit import still works alongside the auto-import" - {

    "importing the same module by name changes nothing" in {
      ran("import demo\nprint(demo.double(21))\nprint(double(21))") shouldBe "42\n42\n"
    }

    "a selective import with a rename reaches the same declaration" in {
      ran("import demo.{double as twice}\nprint(twice(21))\nprint(double(21))") shouldBe "42\n42\n"
    }

    "a wildcard over the same module is not a second offer of its names" in {
      ran("import demo.*\nprint(double(21))") shouldBe "42\n"
    }
  }

  "without the library" - {

    "the auto-import brings in nothing, and the name is simply undefined" in {
      // The mechanism is conditional on the module being present, so a program compiled on its own
      // is untouched — which is what makes aiming it at `demo` safe for every other test.
      Compiler.compileToLlvm("print(double(21))") match
        case Left(e)  => e should include("double")
        case Right(_) => fail("'double' resolved with no library linked")
    }

    "every other program compiles exactly as it did" in {
      Compiler.compileToLlvm("print(1)").isRight shouldBe true
    }
  }

  "the checked-in library" - {

    "is what these tests describe" in {
      // The fixtures above are in-memory so the suite needs no filesystem, which means they could
      // drift from `devlib/demo`. This is the guard: the real files are read and compared.
      val root = DevLib.root

      assume(root.isDefined, "devlib/demo not found from the test working directory")

      val onDisk = Project.collect(root.get).map(s => s.name.split('/').last -> s.text).toMap

      onDisk.keySet shouldBe Set("numbers.sysl", "pair.sysl")
      onDisk("numbers.sysl") shouldBe numbers
      onDisk("pair.sysl") shouldBe pair
    }

    "compiles and links from source, exactly as the artifact does" in {
      assume(Toolchain.clangAvailable, "clang not available")

      val fromSource =
        withDemo(Compiler.compile(librarySources ::: List(Source("<input>", "print(double(21))"))))
      val fromCodec = withDemo(Compiler.compileWith(List(Source("<input>", "print(double(21))")), library))

      fromCodec shouldBe fromSource
    }
  }
}
