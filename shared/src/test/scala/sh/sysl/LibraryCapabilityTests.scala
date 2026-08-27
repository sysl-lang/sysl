package sh.sysl

import io.github.edadma.cross_platform.*

/** Whether the capability passes see through a **linked artifact** — card `0307`.
 *
 * `.syslib` carries the bodies, so the information an escape or capability walk needs is in the
 * artifact. The question this suite answers is whether anything reads it: a program that declares
 * `@no_alloc` and links a library that allocates is either refused, which is the guarantee the
 * clause advertises, or built, which is a hole in it.
 */
class LibraryCapabilityTests extends LibraryCliSupport {

  /** A library whose function makes heap storage — boxing an `int` is an allocation the language
   * itself performs, so this needs nothing from the standard module.
   */
  private val allocating =
    """module demo
      |
      |boxed(n: int) -> &int = n
      |
      |plain(n: int) -> int = n * 2
      |""".stripMargin

  /** A library that lets a slice argument outlive the call, by handing it back.
   *
   * Escape analysis is not a diagnostic — it decides whether a local array lives in the frame or is
   * promoted to the heap — so a walk that could not see this body would leave the caller's array in
   * a frame that has returned, which is a use-after-return rather than a missing message.
   */
  private val escaping =
    """module demo
      |
      |keep(xs: []const int) -> []const int = xs
      |""".stripMargin

  "escape analysis reads a linked library's bodies" - {

    // `a` is this frame's, and the view of it leaves the frame through the library's function. The
    // array therefore has to be promoted, and `--explain-escapes` is where the compiler says so.
    "so an array whose view escapes THROUGH a library function is promoted" in {
      val lib = artifactOf(rootOf("demo", escaping))

      val out = new java.io.ByteArrayOutputStream

      val status = Console.withOut(out)(Console.withErr(out)(sh.sysl.execute(Config(
        command = "build", libs = List(lib), explainEscapes = true, noStdLib = true,
        file = program(
          """import demo
            |leak() -> []const int
            |    var a = [1, 2, 3]
            |    demo.keep(a[0..<3])
            |main()
            |    print(leak()[0])
            |""".stripMargin),
        output = Some(createTempFile("sysl-cap-", ""))))))

      withClue(out.toString)(status shouldBe 0)
      out.toString should include("a")
      out.toString.toLowerCase should include("heap")
    }

    // **The control, and it is what makes the case above mean anything**: the same array, sliced the
    // same way, through a library function that does NOT let the view out. Nothing is promoted, so a
    // walk that promoted on sight — or that gave the conservative answer because it could not read
    // the artifact — would fail here rather than pass both.
    "and one whose view does not escape is left in its frame" in {
      val lib = artifactOf(rootOf("demo", "module demo\n\nfirst(xs: []const int) -> int = xs[0]\n"))

      val out = new java.io.ByteArrayOutputStream

      val status = Console.withOut(out)(Console.withErr(out)(sh.sysl.execute(Config(
        command = "build", libs = List(lib), explainEscapes = true, noStdLib = true,
        file = program(
          """import demo
            |peek() -> int
            |    var a = [1, 2, 3]
            |    demo.first(a[0..<3])
            |main()
            |    print(peek())
            |""".stripMargin),
        output = Some(createTempFile("sysl-cap-", ""))))))

      withClue(out.toString)(status shouldBe 0)
      out.toString should include("no arrays were promoted")
    }
  }

  "a program that gave up the allocator" - {

    "may call a linked library function that does not allocate" in {
      val lib = artifactOf(rootOf("demo", allocating))

      succeeds(Config(command = "build", libs = List(lib),
        file = program("@no_alloc\nimport demo\nmain()\n    print(demo.plain(21))\n"),
        output = Some(createTempFile("sysl-cap-", "")), noStdLib = true))
    }

    // The card's question. A `&int` handed back by the library is heap storage the program asked
    // for, so the clause it wrote is false about the program it produced.
    // **The refusal has to name the allocator**, or this test would pass on any failure at all —
    // a missing standard module, a bad path, a link error — and would say nothing about the walk.
    "and is refused when it calls one that does, naming what it reached" in {
      val lib = artifactOf(rootOf("demo", allocating))

      val (status, notes) = diagnostics(Config(command = "build", libs = List(lib),
        file = program("@no_alloc\nimport demo\nmain()\n    val b = demo.boxed(21)\n    print(*b)\n"),
        output = Some(createTempFile("sysl-cap-", "")), noStdLib = true))

      status should not be 0
      notes should include("no_alloc")
      notes should include("boxed")
    }
  }
}
