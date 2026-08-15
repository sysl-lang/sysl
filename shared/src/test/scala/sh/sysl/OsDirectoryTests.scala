package sh.sysl

import io.github.edadma.cross_platform.*

/** `13 §5`: a directory named `__<os>__` selects source for an operating system and names nothing.
 *
 * **Every test here is written against the machine it runs on**, which is what lets one suite cover
 * a mechanism whose whole subject is that two machines see different files: the folder for
 * `Target.default.os` holds what must be compiled, and the folder for another operating system holds
 * something that could not be. What the second one holds is chosen so that a build compiling it
 * *fails* — a duplicate definition, a `#error`, source that does not parse — so a green run says the
 * file was not read and cannot say it by accident.
 *
 * That is the same trick `NativeSourceTests` uses for the directories a C walk must not take, and it
 * is the only honest way to test an absence.
 */
class OsDirectoryTests extends LibraryCliSupport {

  /** The per-OS directory this machine selects, and one it never will. */
  private val here: String = Project.spelling(Target.default.os)

  private val other: String =
    Project.spelling(if Target.default.os == Os.MacOS then Os.Linux else Os.MacOS)

  private def projectOf(files: (String, String)*): String = {
    val root = createTempDirectory("sysl-osdir-")

    for (path, body) <- files do
      Project.parentOf(s"$root/$path").foreach(createDirectories)
      writeFile(s"$root/$path", body)

    root
  }

  /** Seven is a number only the C knows and the multiplication is only the sysl's, so neither half
   * can be dropped without the answer changing.
   */
  private val shim = "int demo_seven(void) { return 7; }\n"

  /** C that cannot compile, for the folder this machine must never look inside. */
  private val refuses = "#error this file is for another operating system\n"

  "a per-OS directory" - {

    "selects the implementation for this machine and contributes no name" in {
      val root = projectOf(
        "main.sysl"          -> "print(demo.tag())\n",
        "demo/common.sysl"   -> "module demo\n\nloud(s: string) -> string = s + \"!\"\n",
        s"demo/$here/impl.sysl"  -> "module demo\n\ntag() -> string = loud(\"selected\")\n",
        s"demo/$other/impl.sysl" -> "module demo\n\ntag() -> string = loud(\"other\")\n",
      )

      // The module is `demo` and not `demo.__macos__`, or the program's call would not resolve; and
      // the other folder's file is not compiled, or the two `tag`s would collide. One line asserts
      // both, which is the point of writing the two implementations at one name.
      ran(Config(command = "run", file = root)) shouldBe "selected!\n"
    }

    "leaves the files beside it compiled for every machine" in {
      val root = projectOf(
        "main.sysl"             -> "print(demo.tag())\n",
        "demo/common.sysl"      -> "module demo\n\nloud(s: string) -> string = s + \"!\"\n",
        s"demo/$here/impl.sysl" -> "module demo\n\ntag() -> string = loud(\"selected\")\n",
      )

      // `loud` is defined outside any folder and called from inside one. A rule that replaced a
      // directory's files with its selected folder's rather than adding to them would lose it.
      ran(Config(command = "run", file = root)) shouldBe "selected!\n"
    }

    "may hold a directory, which is a module of the one holding the folder" in {
      val root = projectOf(
        "main.sysl"                  -> "print(demo.sub.answer())\n",
        "demo/common.sysl"           -> "module demo\n\nloud(s: string) -> string = s + \"!\"\n",
        s"demo/$here/sub/deep.sysl"  -> "module demo.sub\n\nanswer() -> string = \"nested\"\n",
        s"demo/$other/sub/deep.sysl" -> "module demo.sub\n\nanswer() -> string = \"wrong\"\n",
      )

      ran(Config(command = "run", file = root)) shouldBe "nested\n"
    }

    "works at the project root, where the folder is above every module" in {
      val root = projectOf(
        "main.sysl"                  -> "print(util.name())\n",
        s"$here/util/util.sysl"      -> "module util\n\nname() -> string = \"selected\"\n",
        s"$other/util/util.sysl"     -> "module util\n\nname() -> string = \"wrong\"\n",
      )

      ran(Config(command = "run", file = root)) shouldBe "selected\n"
    }

    // The claim `13 §5` makes and the one that costs something: an unselected tree is not compiled,
    // not analyzed and not *parsed*. Source that could never lex proves the last of those, which
    // nothing about a duplicate definition can.
    "is not parsed at all when it is not the one selected" in {
      val root = projectOf(
        "main.sysl"               -> "print(demo.tag())\n",
        s"demo/$here/impl.sysl"   -> "module demo\n\ntag() -> string = \"selected\"\n",
        s"demo/$other/broken.sysl" -> "module demo\n\n((( this is not sysl at all\n",
      )

      ran(Config(command = "run", file = root)) shouldBe "selected\n"
    }
  }

  /** The case the whole mechanism exists for (`15 §7`): a `.c` cannot carry a sysl attribute, so the
   * path is the only place a selector could go — and a shim written against one system's header must
   * not reach the other's compiler.
   */
  "the C under a per-OS directory" - {

    "is compiled for this machine, and the other's is never offered to clang" in {
      assume(Toolchain.clangAvailable, "clang not available")

      val root = projectOf(
        "main.sysl"      -> "print(demo.seven_times(6))\n",
        "demo/demo.sysl" -> """module demo
                              |
                              |extern "demo_seven" c_seven() -> int
                              |
                              |seven_times(n: int) -> int = c_seven() * n
                              |""".stripMargin,
        s"demo/$here/shim.c"  -> shim,
        s"demo/$other/shim.c" -> refuses,
      )

      // A build that offered the other folder's file to clang stops on its `#error`, so 42 is the
      // whole assertion: the shim linked, and the one beside it did not.
      ran(Config(command = "run", file = root)) shouldBe "42\n"
    }

    // `15 §7`'s rule is that C belongs to a **module**, and a folder is not one — so the module it
    // belongs to is the directory holding the folder, exactly as the sysl inside one does.
    "belongs to the module holding the folder, not to the folder" in {
      assume(Toolchain.clangAvailable, "clang not available")

      val root = projectOf(
        "main.sysl"          -> "print(demo.seven_times(6))\n",
        s"demo/$here/demo.sysl" -> """module demo
                                     |
                                     |extern "demo_seven" c_seven() -> int
                                     |
                                     |seven_times(n: int) -> int = c_seven() * n
                                     |""".stripMargin,
        s"demo/$here/shim.c" -> shim,
      )

      // `demo/` holds no sysl of its own — all of it is inside the folder — so the flattening is
      // what makes `demo` a module at all, and `walkModules` takes its C only because of that.
      ran(Config(command = "run", file = root)) shouldBe "42\n"
    }

    // `15 §7`'s root exemption meets the folder. The root is the tree rather than a directory in it,
    // so its C is taken whether or not it holds sysl — and a folder at the root has to inherit that,
    // or a package namespaced by reverse DNS could not put a per-OS shim where its other C goes.
    "is taken at the tree's own root, where no module is declared" in {
      assume(Toolchain.clangAvailable, "clang not available")

      val root = projectOf(
        "main.sysl"      -> "print(demo.seven_times(6))\n",
        "demo/demo.sysl" -> """module demo
                              |
                              |extern "demo_seven" c_seven() -> int
                              |
                              |seven_times(n: int) -> int = c_seven() * n
                              |""".stripMargin,
        s"$here/shim.c"  -> shim,
        s"$other/shim.c" -> refuses,
      )

      ran(Config(command = "run", file = root)) shouldBe "42\n"
    }
  }

  /** `13 §4`: a capability is a property of the **module**, so every file of one states it. A file
   * inside a folder is a file of the module holding the folder, so the clause reaches in there too —
   * which is a consequence of §5 rather than a rule of its own, and is exactly what
   * `library/sysl/fs/tests.sysl` tripped over on the way to being written.
   */
  "a capability clause" - {

    "must appear on a file inside a per-OS directory, like every other file of the module" in {
      val root = projectOf(
        "main.sysl"             -> "print(1)\n",
        "demo/common.sysl"      -> "module demo\n@requires(os)\n\nloud(s: string) -> string = s\n",
        s"demo/$here/impl.sysl" -> "module demo\n\ntag() -> string = loud(\"x\")\n",
      )

      val (status, notes) = diagnostics(Config(command = "run", file = root))

      status should not be 0
      notes should include("capabilit")
    }

    "and the two agreeing is what a module with a per-OS half looks like" in {
      val root = projectOf(
        "main.sysl"             -> "print(demo.tag())\n",
        "demo/common.sysl"      -> "module demo\n@requires(os)\n\nloud(s: string) -> string = s + \"!\"\n",
        s"demo/$here/impl.sysl" -> "module demo\n@requires(os)\n\ntag() -> string = loud(\"selected\")\n",
      )

      // Discriminating against the case above: without this the refusal could be about the folder
      // rather than about the clause, and would look exactly as green.
      ran(Config(command = "run", file = root)) shouldBe "selected!\n"
    }
  }

  "a directory that looks like one and is not" - {

    // A misspelling that read as an ordinary directory would compile nothing on any target and be
    // reported, eventually, as a missing function — which is the failure the closed vocabulary of
    // `Conditional.symbols` exists to refuse, in the other place a source tree names an OS.
    "is refused, and the message says which operating systems there are" in {
      val root = projectOf(
        "main.sysl"            -> "print(demo.tag())\n",
        "demo/__linx__/x.sysl" -> "module demo\n\ntag() -> string = \"typo\"\n",
      )

      val (status, notes) = diagnostics(Config(command = "run", file = root))

      status should not be 0
      notes should include("__linx__")
      notes should include("__linux__")
    }

    "is refused whichever machine is asking" in {
      // `__linx__` is not the selected folder on any target, so this is the same tree as above and
      // the point is that it is still refused: validation is over every folder a walk meets, not
      // only the one it takes. `Conditional` checks the conditions on branches it is not taking for
      // the same reason.
      val root = projectOf("main.sysl" -> "print(1)\n", "demo/__nosuchos__/x.sysl" -> "module demo\n")

      refused(Config(command = "run", file = root))
    }

    // A directory named for nothing in particular is an ordinary module directory and must stay one.
    // The shape is what marks a selector, so this is the line between the two.
    "is an ordinary directory when it does not have the shape" in {
      val root = projectOf(
        "main.sysl"           -> "print(demo.linux.tag())\n",
        "demo/linux/x.sysl"   -> "module demo.linux\n\ntag() -> string = \"a module called linux\"\n",
      )

      ran(Config(command = "run", file = root)) shouldBe "a module called linux\n"
    }
  }

  "nesting one inside another" - {

    // Two axes are `#if` inside a sysl file or the C preprocessor inside a `.c`, and a target has
    // exactly one operating system — so a folder inside a folder could never select anything.
    "is refused directly" in {
      val root = projectOf(
        "main.sysl"                    -> "print(1)\n",
        s"demo/$here/$other/x.sysl"    -> "module demo\n",
      )

      val (status, notes) = diagnostics(Config(command = "run", file = root))

      status should not be 0
      notes should include(other)
      notes should include(here)
    }

    "is refused through a module in between" in {
      val root = projectOf(
        "main.sysl"                       -> "print(1)\n",
        s"demo/$here/sub/$other/x.sysl"    -> "module demo.sub\n",
      )

      // The refusal follows the folder down rather than stopping at its own listing, which is why
      // `contents` pairs each sub-directory with the folder it came out of.
      refused(Config(command = "run", file = root))
    }
  }

  /** What a tree *offers* is a property of the tree rather than of the machine consuming it
   * (`packages.md § 9`), so this one walk takes every folder whatever target is asking.
   */
  "the modules a tree offers" - {

    "include one whose source is only under a per-OS directory" in {
      val root = projectOf(
        s"pkg/$other/only.sysl" -> "module pkg\n",
        "plain/plain.sysl"      -> "module plain\n",
      )

      // `$other` is the folder this machine does not select. A dependency's mount that came and went
      // with the target would resolve on one machine and not on another, and the collision check
      // under it would be checking a different table each time.
      Project.modules(root) shouldBe Set("pkg", "plain")
    }

    "and a malformed one is still refused rather than silently offering nothing" in {
      val root = projectOf("pkg/__linx__/only.sysl" -> "module pkg\n")

      // This walk tolerates a directory it cannot read, because a dependency's root may be anything
      // on disk. A mistake in the tree must not be swallowed by that tolerance.
      a[SelectionError] should be thrownBy Project.modules(root)
    }
  }
}
