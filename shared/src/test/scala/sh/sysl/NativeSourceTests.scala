package sh.sysl

import io.github.edadma.cross_platform.*

/** `15 §7` for the trees a compilation walks that are not a `build-lib`'s: the project's own, and a
 * source tree named with `--lib`.
 *
 * **The rule is about a source tree, not about a library.** A `.c` dropped anywhere in a tree is
 * compiled with it, and the walk that finds the sysl already visits every directory — so which tree
 * it is decides nothing. `LibraryBuildCliTests` pins the `build-lib` half, where the objects become
 * archive members; `PackageBuildTests` pins a dependency's. What is left is the two here, and both
 * were silently dropping C for the same reason the dependency path was: `Project.cSources` had one
 * caller in the tree and it was `build-lib`.
 *
 * The project's own tree is the sharpest of the three, because it is the one a package author works
 * in. A package whose C is compiled for everybody who depends on it and not for `sysl test` in its
 * own checkout is a package that cannot be developed.
 */
class NativeSourceTests extends LibraryCliSupport {

  /** Seven is a number only the C knows and the multiplication is only the sysl's, so neither half
   * can be dropped without the answer changing.
   */
  private val shim = "int demo_seven(void) { return 7; }\n"

  /** C that cannot compile, for the cases whose whole claim is that it is never offered to clang. A
   * build reaching the toolchain with this in hand fails; a green run says the file was skipped, and
   * cannot say it by accident.
   */
  private val refuses = "#error this file is not part of any module\n"

  private val calling =
    """extern "demo_seven" c_seven() -> int
      |
      |seven_times(n: int) -> int = c_seven() * n
      |""".stripMargin

  /** A project root: a program at the top and a module beside it, with whatever else it carries. */
  private def projectOf(files: (String, String)*): String = {
    val root = createTempDirectory("sysl-native-")

    for (path, body) <- files do
      Project.parentOf(s"$root/$path").foreach(createDirectories)
      writeFile(s"$root/$path", body)

    root
  }

  "a project's own C" - {

    "is compiled and linked with it" in {
      assume(Toolchain.clangAvailable, "clang not available")

      val root = projectOf(
        "main.sysl"     -> "print(demo.seven_times(6))\n",
        "demo/demo.sysl" -> s"module demo\n\n$calling",
        "demo/shim.c"   -> shim,
      )

      ran(Config(command = "run", file = root)) shouldBe "42\n"
    }

    "including one at the root, where no module is declared" in {
      assume(Toolchain.clangAvailable, "clang not available")

      val root = projectOf(
        "main.sysl"      -> "print(demo.seven_times(6))\n",
        "demo/demo.sysl" -> s"module demo\n\n$calling",
        "shim.c"         -> shim,
      )

      ran(Config(command = "run", file = root)) shouldBe "42\n"
    }

    // The case a package author meets first: the tests that exercise a shim are in the package that
    // ships it, so a test build dropping the C fails on the one tree most likely to hold any.
    "and a test build gets it on the same footing as a run" in {
      assume(Toolchain.clangAvailable, "clang not available")

      val root = projectOf(
        "demo/demo.sysl" -> s"""module demo
                               |
                               |$calling
                               |@test
                               |seven_is_seven() =
                               |    assert(seven_times(6) == 42, "the shim was linked")
                               |""".stripMargin,
      )

      writeFile(s"$root/demo/shim.c", shim)

      ran(Config(command = "test", file = root)) should include("1 passed")
    }

    // Naming a file compiles that file alone (`13 §1`), so there is no tree for C to have travelled
    // with — and the link says so by naming the symbol nothing defined, rather than by quietly
    // building something that works only when the directory was named.
    "is not gathered when a single file was named rather than a tree" in {
      assume(Toolchain.clangAvailable, "clang not available")

      val root = projectOf("main.sysl" -> s"$calling\nprint(seven_times(6))\n", "shim.c" -> shim)

      val (status, notes) = diagnostics(Config(command = "run", file = s"$root/main.sysl"))

      status should not be 0
      notes should include("demo_seven")
    }
  }

  /** `15 §7`: C belongs to a **module**, so a directory holding no sysl contributes none of its own.
   *
   * The case this is for is not hypothetical and not a tidy-up. `cmake -B build -S .` — CMake's
   * default and what `sysl-lang/zephyr-demo` documents — puts the build directory *inside* the
   * project, and a Zephyr build fills `build/zephyr/` with generated C written for the toolchain
   * *it* was configured with. Compiling that with clang against headers Zephyr emitted for gcc
   * stops the build on `#error processor architecture not supported`, which reads as a
   * target-support failure in sysl or in Zephyr and is neither.
   */
  "C in a directory that holds no sysl" - {

    "is not compiled, however deep it sits" in {
      assume(Toolchain.clangAvailable, "clang not available")

      val root = projectOf(
        "main.sysl"            -> "print(demo.seven_times(6))\n",
        "demo/demo.sysl"       -> s"module demo\n\n$calling",
        "demo/shim.c"          -> shim,
        "build/generated.c"    -> refuses,
        "build/deep/nested.c"  -> refuses,
      )

      ran(Config(command = "run", file = root)) shouldBe "42\n"
    }

    // The root is the case a looser rule gets wrong: prune only a subtree with no sysl *below* it
    // and a root holding `main.sysl` makes the whole project one module again, `build/` included.
    "including where the project root itself holds sysl" in {
      assume(Toolchain.clangAvailable, "clang not available")

      val root = projectOf(
        "main.sysl"         -> s"$calling\nprint(seven_times(6))\n",
        "shim.c"            -> shim,
        "build/generated.c" -> refuses,
      )

      ran(Config(command = "run", file = root)) shouldBe "42\n"
    }

    // The root is the tree rather than a directory in it, so it carries C whether or not it holds
    // sysl — which is what a package namespaced by reverse DNS relies on, having nothing at its top.
    // `PackageBuildTests` and `LibraryBuildCliTests` pin that on their own trees; here it is the
    // interaction that matters, since the exemption must not reach the build directory beside it.
    "though the root itself carries C with no sysl of its own" in {
      assume(Toolchain.clangAvailable, "clang not available")

      val root = projectOf(
        "demo/demo.sysl"    -> s"""module demo
                                  |
                                  |$calling
                                  |@test
                                  |seven_is_seven() =
                                  |    assert(seven_times(6) == 42, "the root's shim was linked")
                                  |""".stripMargin,
        "shim.c"            -> shim,
        "build/generated.c" -> refuses,
      )

      ran(Config(command = "test", file = root)) should include("1 passed")
    }

    // The descent is not pruned with the directory: `sh/` and `sh/sysl/` hold nothing at all in
    // every package namespaced by reverse DNS, and the module three levels down is the whole package.
    "while a module below such a directory still carries its own" in {
      assume(Toolchain.clangAvailable, "clang not available")

      val root = projectOf(
        "main.sysl"                 -> "print(sh.sysl.demo.seven_times(6))\n",
        "sh/sysl/demo/demo.sysl"    -> s"module sh.sysl.demo\n\n$calling",
        "sh/sysl/demo/shim.c"       -> shim,
        "sh/stray.c"                -> refuses,
        "sh/sysl/stray.c"           -> refuses,
      )

      ran(Config(command = "run", file = root)) shouldBe "42\n"
    }
  }

  "a library given as a source tree" - {

    // `--lib` takes either an artifact or a source root, and how a library shipped is not something
    // a program depending on it should have to know — which is only true if both roads carry the
    // library's C. The artifact road already did.
    "carries its C exactly as the artifact built from it does" in {
      assume(Toolchain.clangAvailable, "clang not available")

      val root = rootWithC("demo", s"module demo\n\n$calling", "shim.c" -> shim)

      ran(Config(command = "run", file = program("print(demo.seven_times(6))"),
        libs = List(root))) shouldBe "42\n"
    }

    "and two source trees each carrying a file of one name stay two objects" in {
      assume(Toolchain.clangAvailable, "clang not available")

      val left  = rootWithC("left", """module left
                                      |
                                      |extern "left_util" c() -> int
                                      |
                                      |value() -> int = c()
                                      |""".stripMargin, "util.c" -> "int left_util(void) { return 40; }\n")

      val right = rootWithC("right", """module right
                                       |
                                       |extern "right_util" c() -> int
                                       |
                                       |value() -> int = c()
                                       |""".stripMargin, "util.c" -> "int right_util(void) { return 2; }\n")

      ran(Config(command = "run", file = program("print(left.value() + right.value())"),
        libs = List(left, right))) shouldBe "42\n"
    }
  }

  "C that will not compile stops the build, naming the file" in {
    assume(Toolchain.clangAvailable, "clang not available")

    val root = projectOf(
      "main.sysl"      -> "print(demo.seven_times(6))\n",
      "demo/demo.sysl" -> s"module demo\n\n$calling",
      "demo/shim.c"    -> "int demo_seven(void) { return\n",
    )

    val (status, notes) = diagnostics(Config(command = "run", file = root))

    status should not be 0
    notes should include("shim.c")
  }

  // `emit-llvm` prints a module and `prove` stops at the typed tree, so neither has anything to do
  // with an object file. A tree whose C does not compile still emits, which is the observation: had
  // the driver compiled it anyway, this would fail with clang's complaint instead.
  "a command that never links does not compile the tree's C at all" in {
    val root = projectOf(
      "main.sysl"      -> "print(demo.seven_times(6))\n",
      "demo/demo.sysl" -> s"module demo\n\n$calling",
      "demo/shim.c"    -> "int demo_seven(void) { return\n",
    )

    emitted(Config(command = "emit-llvm", file = root)) should include("demo_seven")
  }
}
