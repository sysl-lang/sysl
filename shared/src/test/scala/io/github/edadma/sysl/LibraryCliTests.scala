package io.github.edadma.sysl

import io.github.edadma.cross_platform.*

/** `--lib`, `--std-lib` and `--no-std-lib` — which libraries a compilation is given, driven
 * through the driver itself.
 *
 * The compiler's own API cannot reach any of this. Which of two shapes a `--lib` path names, what a
 * corrupt artifact does, what a compilation falls back to when the standard module is not where it
 * was looked for — all of it lives in `execute`, and a test that called `Compiler` and `Toolchain`
 * directly would be re-implementing the driver and pinning its own arrangement rather than the one a
 * user meets.
 *
 * Producing an artifact in the first place is the other half, and it is `LibraryBuildCliTests`.
 */
class LibraryCliTests extends LibraryCliSupport {

  // These suites drive `emit-llvm` a few dozen times and they run beside other suites, so a run
  // whose output nobody asked for has to print nothing at all. An unread module on the console is
  // several hundred lines of IR landing in the middle of another suite's test names — which is
  // where this assertion came from. The second half is what keeps the first from being satisfied
  // by a fixture that swallowed the module outright.
  "a run nobody captured prints nothing, and a captured one still gets the module" in {
    val cfg  = Config(command = "emit-llvm", file = program("print(demo.double(21))"), libs = List(artifact()))
    val loud = new java.io.ByteArrayOutputStream

    Console.withOut(loud)(cli(cfg)) shouldBe 0
    loud.toString shouldBe ""
    emitted(cfg) should startWith("target triple")
  }

  "--lib pointed at an artifact" - {

    "compiles a program against it" in {
      val prog = program("print(demo.double(21))\nprint(demo.larger(3, 7))")

      succeeds(Config(command = "emit-llvm", file = prog, libs = List(artifact())))
    }

    "runs one, with the precompiled body linked from the artifact" in {
      assume(Toolchain.clangAvailable, "clang not available")

      succeeds(Config(command = "run", file = program("print(demo.double(21))"), libs = List(artifact())))
    }

    "takes a source root just as well, which is the other shape of the same flag" in {
      val prog = program("print(demo.double(21))")

      succeeds(Config(command = "emit-llvm", file = prog, libs = List(libraryRoot())))
    }

    "leaves no unpacked object behind" in {
      assume(Toolchain.clangAvailable, "clang not available")

      // The object half is written to a temporary file for the linker's sake. One per invocation
      // that is never removed is a leak a long-running build would feel and nothing would report.
      val probe   = createTempFile("sysl-probe-", "")
      val tempDir = probe.substring(0, math.max(probe.lastIndexOf('/'), probe.lastIndexOf('\\')))

      deleteFile(probe)

      val before = listFiles(tempDir).count(_.contains("sysl-lib-"))

      succeeds(Config(command = "run", file = program("print(demo.double(1))"), libs = List(artifact())))

      listFiles(tempDir).count(_.contains("sysl-lib-")) shouldBe before
    }
  }

  "several --lib at once" - {

    /* The flag is unbounded and the help text says so, which is a claim about behaviour and was the
     * one thing here nothing checked — every other test in this file passes exactly one. What makes
     * more than one worth its own section is that the driver *partitions* them, unions their symbol
     * sets, and concatenates their sources and their object files: four places where a second
     * library either arrives or silently does not. */

    "links two artifacts, both of which the program calls" in {
      val prog = program("print(demo.double(21))\nprint(extra.triple(2))")

      val ir = emitted(Config(command = "emit-llvm", file = prog,
        libs = List(artifact(), artifactOf(rootOf("extra", other)))))

      // Declared, not defined — which says both object halves were accounted for. Exiting 0 would
      // hold for a compilation that quietly compiled the second library in from source instead.
      symbols(ir, "declare") should contain allOf ("demo$double", "extra$triple")
      symbols(ir, "define") should contain noneOf ("demo$double", "extra$triple")
    }

    "and does so whichever order they are given in" in {
      val prog = program("print(demo.double(21))\nprint(extra.triple(2))")
      val two  = List(artifact(), artifactOf(rootOf("extra", other)))
      val ir   = emitted(Config(command = "emit-llvm", file = prog, libs = two.reverse))

      symbols(ir, "declare") should contain allOf ("demo$double", "extra$triple")
    }

    "and mixes an artifact with a source root, which is the other shape of the same flag" in {
      val prog = program("print(demo.double(21))\nprint(extra.triple(2))")

      val ir = emitted(Config(command = "emit-llvm", file = prog,
        libs = List(artifact(), rootOf("extra", other))))

      // The sharp one: the same compilation holds one library it links and one it compiles, so a
      // partition that dropped either half would show here and nowhere else.
      symbols(ir, "declare") should contain("demo$double")
      symbols(ir, "define") should contain("extra$triple")
      symbols(ir, "define") should not contain "demo$double"
    }

    "and runs, which is what says both object halves reached the linker" in {
      assume(Toolchain.clangAvailable, "clang not available")

      succeeds(Config(command = "run", file = program("print(demo.double(21) + extra.triple(2))"),
        libs = List(artifact(), artifactOf(rootOf("extra", other)))))
    }
  }

  "--lib pointed at something that is not an artifact" - {

    "refuses a file that is not one of ours" in {
      refused(Config(command = "emit-llvm", file = program("print(1)"),
        libs = List(corrupt("not a library\n".getBytes))))
    }

    "refuses one built by a different compiler" in {
      refused(Config(command = "emit-llvm", file = program("print(1)"),
        libs = List(corrupt(s"syslib ${LibraryArtifact.Version + 1} 0\n".getBytes))))
    }

    "refuses a truncated one" in {
      refused(Config(command = "emit-llvm", file = program("print(1)"),
        libs = List(corrupt(truncated))))
    }

    "refuses one that is not there at all" in {
      refused(Config(command = "emit-llvm", file = program("print(1)"),
        libs = List(s"${createTempDirectory("sysl-cli-gone-")}/absent${LibraryArtifact.extension}")))
    }

    "refuses a source root holding no sysl files" in {
      refused(Config(command = "emit-llvm", file = program("print(1)"),
        libs = List(createTempDirectory("sysl-cli-empty-lib-"))))
    }

    "reports a link that fails rather than falling over cleaning up after it" in {
      assume(Toolchain.clangAvailable, "clang not available")

      // A member the linker cannot read is the reachable way to fail a link, and what it caught was
      // the cleanup: `createTempFile` reserves a name and the toolchain is what writes to it, so
      // deleting the executable of a build that never produced one threw — and the stack trace stood
      // where the linker's own message should have been.
      //
      // The metadata is well-formed and only the compiled half is rubbish, which is what puts the
      // failure at the link rather than at the read: everything the compiler does succeeds.
      val junk = corrupt(FakeAr(LibraryArtifact.codeMember -> "not an object file".getBytes,
        LibraryArtifact.metadataMember -> LibraryArtifact.frame("0\n")))

      refused(Config(command = "run", file = program("print(1)"), libs = List(junk)))
    }
  }

  "--std-lib" - {

    "runs a program whose share of the standard module came from the artifact" in {
      assume(StdRoot.root.isDefined, "lib/ not found from the test working directory")
      assume(Toolchain.clangAvailable, "clang not available")

      // Exiting 0 is the whole assertion, and it is a strong one. Every std symbol the artifact
      // defines is one this program only *declares* — so if the object half were not handed to the
      // linker, or held different symbols than its metadata says, this would not link at all.
      val (status, notes) = diagnostics(Config(command = "run", file = program("print(21 * 2)"),
        stdLib = Some(std)))

      status shouldBe 0
      notes should not include "warning"
    }

    "builds a library against one too, which is the other thing that gets compiled" in {
      assume(StdRoot.root.isDefined, "lib/ not found from the test working directory")
      assume(Toolchain.clangAvailable, "clang not available")

      val out = createTempFile("sysl-cli-against-std-", LibraryArtifact.extension)

      val (status, notes) =
        diagnostics(Config(command = "build-lib", file = libraryRoot(), output = Some(out), stdLib = Some(std)))

      status shouldBe 0
      notes should not include "warning"
    }

    "refuses the compilation rather than substituting another library" - {

      // The same rule `--lib` follows, and for the same reason: a library that cannot be read leaves
      // the calls into it with nothing to resolve them. That the compiler happens to carry a copy of
      // this one does not make quietly compiling against a *different* standard module than the one
      // asked for an acceptable answer — it makes it a harder mistake to notice.
      def refuses(what: String, path: String): Unit =
        what in {
          val (status, notes) = diagnostics(Config(command = "emit-llvm", file = program("print(1)"),
            stdLib = Some(path)))

          status should not be 0
          notes should include("error")
        }

      refuses("when it is not there at all",
        s"${createTempDirectory("sysl-cli-nocore-")}/absent${LibraryArtifact.extension}")

      refuses("when it is not one of ours", corrupt("not a library\n".getBytes))

      // A real archive of real objects that is simply somebody else's library — a `.a` from a C
      // project renamed. Every part of reading it works up to the part that looks for our metadata.
      refuses("when it is an archive with nothing of ours in it",
        corrupt(FakeAr("foreign.o" -> Array[Byte](7, 7))))

      refuses("when another sysl built it",
        corrupt(FakeAr(LibraryArtifact.metadataMember ->
          LibraryArtifact.framed(s"syslib ${LibraryArtifact.Version + 1} 0"))))

      refuses("when it is truncated", corrupt(truncated))

      refuses("when its metadata will not decode", corrupt(artifactOfMeta("0000000000000000\n0\nrubbish")))

      // The one a developer actually meets: build the artifact, then edit `lib/sysl`. It decodes and
      // would link perfectly — it is simply no longer the standard module in the tree — so nothing
      // but the fingerprint would catch it, and a silently wrong library is the worst of the five.
      refuses("when it was built from a different lib/sysl", corrupt(artifactOfMeta(stale)))
    }

    "is not needed by name, the artifact being looked for where build-lib --std puts it" - {

      // Every case here routes the default path through `stdSearch` to a temporary file rather than
      // using the real one. Suites run in parallel, and an artifact left at the true default would be
      // found by every other test in the run — which is the environment-dependence this feature
      // introduces, arriving first in our own suite. The real default is pinned separately, below.

      "so building it with no -o and compiling with no --std-lib is the whole workflow" in {
        assume(StdRoot.root.isDefined, "lib/ not found from the test working directory")
        assume(Toolchain.clangAvailable, "clang not available")

        val where = s"${createTempDirectory("sysl-cli-found-")}/std${LibraryArtifact.extension}"

        succeeds(Config(command = "build-lib", file = StdRoot.root.get, std = true, stdSearch = where))
        isFile(where) shouldBe true

        val (status, notes) =
          diagnostics(Config(command = "run", file = program("print(21 * 2)"), stdSearch = where))

        status shouldBe 0
        notes should not include "warning"
      }

      "while nothing there is built rather than reported, a fresh clone having one answer" in {
        assume(Toolchain.clangAvailable, "clang not available")
        assume(Toolchain.findAr(None).isRight, "llvm-ar not available")

        val where = s"${createTempDirectory("sysl-cli-none-")}/std${LibraryArtifact.extension}"

        val (status, notes) =
          diagnostics(Config(command = "emit-llvm", file = program("print(1)"), stdSearch = where))

        status shouldBe 0
        isFile(where) shouldBe true

        // Announced rather than done invisibly: a first build that pauses to do work should say what
        // the work was.
        notes should include("building the standard module")
      }

      "and something unreadable there is replaced, the artifact being derived and not authored" in {
        assume(Toolchain.clangAvailable, "clang not available")
        assume(Toolchain.findAr(None).isRight, "llvm-ar not available")

        val where = corrupt("not a library\n".getBytes)

        diagnostics(Config(command = "emit-llvm", file = program("print(1)"), stdSearch = where))._1 shouldBe 0

        // Replaced, not merely worked around: what is at the path afterwards is a standard module
        // this compiler will read, which is the whole of what the rebuild is for.
        // Matched rather than asked whether it `isRight`: the symbol form of that question is
        // answered by reflection, which the JVM has and no other platform does — there it silently
        // becomes an equality against the symbol itself, and fails whatever the value is.
        LibraryArtifact.metadataOf(where, readBytes(where))
          .flatMap(Stdlib.read(where, _, Target.default)) should matchPattern { case Right(_) => }
      }

      "and a stale one is replaced too, which is the state it is actually found in" in {
        assume(Toolchain.clangAvailable, "clang not available")
        assume(Toolchain.findAr(None).isRight, "llvm-ar not available")

        // The one a developer meets after a merge: an artifact whose container is a format behind.
        // It is not corrupt and would decode as far as its own header — only the compiler has moved.
        val where = corrupt(s"syslib ${LibraryArtifact.Version + 1} 0\n".getBytes)

        diagnostics(Config(command = "emit-llvm", file = program("print(1)"), stdSearch = where))._1 shouldBe 0
      }

      "and the program is compiled against the rebuilt one, not against the carried copy" in {
        assume(Toolchain.clangAvailable, "clang not available")
        assume(Toolchain.findAr(None).isRight, "llvm-ar not available")

        val where = s"${createTempDirectory("sysl-cli-rebuilt-")}/std${LibraryArtifact.extension}"
        val src   = program("print(1)")

        // The discriminating half. A rebuild that produced an artifact and then went on compiling
        // against the copy the compiler carries would pass every assertion above, and the library's
        // symbols are what tell the two apart: linked, they are declarations.
        val rebuilt = emitted(Config(command = "emit-llvm", file = src, stdSearch = where))
        val carried = emitted(Config(command = "emit-llvm", file = src, noStdLib = true, stdSearch = where))

        libraryOwn(rebuilt, "define") shouldBe empty
        libraryOwn(rebuilt, "declare") should not be empty
        libraryOwn(carried, "define") should not be empty
      }

      "but one named with --std-lib is not rebuilt, being the one that was asked for" in {
        // The rule the rebuild does *not* reach, and the reason it does not: someone who wrote down
        // which artifact to compile against is owed the truth about that one rather than a different
        // one built underneath them.
        val (status, notes) = diagnostics(Config(command = "emit-llvm", file = program("print(1)"),
          stdLib = Some(corrupt("not a library\n".getBytes))))

        status should not be 0
        notes should include("is not a sysl library")
        notes should not include "building the standard module"
      }

      "and --std-lib is the one consulted, being the one someone actually asked for" in {
        // Both are unreadable, so both refuse — what says which was read is *how* each is broken:
        // the named one is not ours at all, the one at the default path claims a later format.
        val (status, notes) = diagnostics(Config(command = "emit-llvm", file = program("print(1)"),
          stdLib = Some(corrupt("not a library\n".getBytes)),
          stdSearch = corrupt(s"syslib ${LibraryArtifact.Version + 1} 0\n".getBytes)))

        status should not be 0
        notes should include("is not a sysl library")
        notes should not include "built by a different sysl"
      }

      "and the place both ends agree on is the documented one" in {
        // The tests above route around the real default so they cannot collide; this is what says
        // the real default is what they were standing in for.
        Config().stdSearch shouldBe LibraryArtifact.stdDefault
        LibraryArtifact.stdDefault should endWith(LibraryArtifact.extension)
      }

      /* Where that default *is* moved out of the project and into the user's cache when the compiler
       * became something installed rather than something cloned. The value belongs to the machine —
       * a home directory, and a platform convention for where caches go — so what is asserted here is
       * its shape and the one property a caller depends on, never a path from this author's laptop. */

      "and it is keyed by the library, so a compiler carrying another one cannot collide with it" in {
        // The claim that makes an upgrade need no cache invalidation: the key is a fingerprint of the
        // library's contents, so a changed `lib/sysl` *is* a different path rather than a stale hit
        // at the same one. Version-keying would have relied on remembering to bump the version.
        assume(cacheDirectory.isDefined, "this machine has no cache directory")

        LibraryArtifact.stdDefault should include(Std.fingerprint)
      }

      "and it sits under the cache directory rather than in the project" in {
        assume(cacheDirectory.isDefined, "this machine has no cache directory")

        LibraryArtifact.stdDefault should startWith(s"${cacheDirectory.get}/sysl/")
        // The specific regression: a compilation must not write into whatever directory it was run
        // in. `sysl run notes.sysl` in a downloads folder used to leave a `.sysl/` there.
        LibraryArtifact.stdDefault should not startWith LibraryArtifact.stdLocal
      }

      "while a machine with no cache directory still has the project-local answer" in {
        // Not a hypothetical: a build container runs as a user with no home. The compilation then
        // behaves exactly as it did before this moved, rather than failing over somewhere unwritable.
        LibraryArtifact.stdLocal shouldBe s".sysl/std${LibraryArtifact.extension}"
      }
    }
  }

  "--no-std-lib" - {

    /* The compiler keeps its own copy of the standard module, and discovery means that copy is
     * normally reached only by an artifact being absent — which is a fact about the filesystem, not
     * about the command line. These say the flag reaches it on purpose. */

    "does not read the artifact at the default path, even a broken one" in {
      // The discriminating pair: this exact artifact at this exact path *refuses* the compilation
      // without the flag (the discovery section above), so succeeding here is the artifact going
      // unread rather than a corruption that happens not to matter.
      val (status, notes) = diagnostics(Config(command = "emit-llvm", file = program("print(1)"),
        noStdLib = true, stdSearch = corrupt("not a library\n".getBytes)))

      status shouldBe 0
      notes should not include "error"
    }

    "and compiles a tree with no artifact without making one, which the default path would" in {
      // The flag's reason for existing, and what separates it from the rebuild. Both compile in a
      // tree where nothing has been built; only one of them needs a toolchain to do it, which is why
      // this is the path the compiler's own unit tests take and the bootstrap took.
      val nowhere = s"${createTempDirectory("sysl-cli-bare-")}/std${LibraryArtifact.extension}"

      succeeds(Config(command = "emit-llvm", file = program("print(1)"), noStdLib = true,
        stdSearch = nowhere))

      // Nothing was written, where the same run without the flag would have built one there.
      isFile(nowhere) shouldBe false
    }

    "and takes the built-in copy with a good artifact sitting right there" in {
      assume(StdRoot.root.isDefined, "lib/ not found from the test working directory")

      // Which module was used, not merely that one was: the same program at the same path, told to
      // use the artifact and told not to. Exiting 0 both ways would hold for a flag that did
      // nothing, so the assertion is on the seam the flag moves — what the artifact already holds
      // is declared when it is linked and defined when it is not.
      val src    = program("print(21 * 2)")
      val linked = emitted(Config(command = "emit-llvm", file = src, stdSearch = std))
      val carried = emitted(Config(command = "emit-llvm", file = src, noStdLib = true, stdSearch = std))

      libraryOwn(linked, "define") shouldBe empty
      libraryOwn(linked, "declare") should not be empty
      libraryOwn(carried, "define") should not be empty
    }

    // `13 §8` gives the flag a second use beyond the bootstrap: *compiling one program both ways is
    // how the two paths are held to meaning the same thing.* The test above pins which module was
    // used, which is the seam; this one is the claim itself. What may differ is the standard module's
    // own symbols — declared when linked, defined when carried — so what must agree is the code the
    // **program** lowers to, which the way its library arrived has no business changing.
    "and one program compiled both ways lowers to the same program" in {
      assume(StdRoot.root.isDefined, "lib/ not found from the test working directory")

      val src = program("f(n: int) -> int = n * 2\nprint(f(21))\n")
      val linked  = emitted(Config(command = "emit-llvm", file = src, stdSearch = std))
      val carried = emitted(Config(command = "emit-llvm", file = src, noStdLib = true, stdSearch = std))

      // The two modules do *not* hold the same symbols, and should not: the standard module's own
      // and the ARC runtime beside them are defined here only when the copy is carried, and come
      // from the artifact's object otherwise. That difference is the whole point of the flag. What
      // has to agree is the program's own code — the same source, lowered the same way.
      for name <- List("f", "main") do
        bodyOf(linked, name) should not be empty
        bodyOf(linked, name) shouldBe bodyOf(carried, name)
    }

    "and what it compiles is whole, not a program relying on the artifact anyway" in {
      assume(StdRoot.root.isDefined, "lib/ not found from the test working directory")
      assume(Toolchain.clangAvailable, "clang not available")

      // Linking is the assertion. The artifact's object half is not handed to the linker here, so
      // every std symbol this program calls has to have been emitted into it.
      val (status, notes) = diagnostics(Config(command = "run", file = program("print(21 * 2)"),
        noStdLib = true, stdSearch = std))

      status shouldBe 0
      notes should not include "warning"
    }

    "but is refused beside --std-lib, which asks for the other one" in {
      // Two spellings a character apart, so a typo lands here. Refused rather than resolved by
      // precedence: either precedence discards half of what the command line asked for, silently.
      // The path names nothing, which says the refusal comes before the artifact is read.
      refused(Config(command = "emit-llvm", file = program("print(1)"), noStdLib = true,
        stdLib = Some(s"${createTempDirectory("sysl-cli-both-")}/any${LibraryArtifact.extension}")))
    }
  }

  /** A library written as a document (`15 §11`). The whole path is the point: the walk has to find
   * the file, the build has to compile it, the artifact has to carry a source whose text is the
   * program rather than the document, and a program that links it has to run.
   */
  "a literate library" - {

    val doc =
      """A library, explained
        |====================
        |
        |This paragraph is not compiled, and neither is the fenced block below it.
        |
        |```
        |double(n: int) -> int = n * 3
        |```
        |
        |That would be wrong. This is right:
        |
        |    module demo
        |
        |    double(n: int) -> int = n * 2
        |""".stripMargin

    "builds, links, and answers what its indented half says" in {
      assume(Toolchain.clangAvailable, "clang not available")

      val lib  = artifactOf(rootOf("demo", doc, "lib.lsysl"))
      val prog = program("import demo.*\n\nprint(double(21))")

      ran(Config(command = "run", file = prog, libs = List(lib))) shouldBe "42\n"
    }

    "and a diagnostic against it names the line and column of the document" in {
      // The document is on disk and reached by the walk, so this is the whole path a user has: the
      // file they are editing, the location they are given, and the line they are shown. The column
      // counts the four that made the line program text — 23 in the text the lexer saw.
      val wrong =
        """A generic, whose body is checked where it is written
          |
          |    module demo
          |
          |    twice[T](x: T) -> T = x + x
          |""".stripMargin

      val out = createTempFile("sysl-cli-", LibraryArtifact.extension)
      val (status, notes) =
        diagnostics(Config(command = "build-lib", file = rootOf("demo", wrong, "lib.lsysl"), output = Some(out)))

      status should not be 0
      notes should include("lib.lsysl:5:27")
      notes should include("twice[T](x: T) -> T = x + x")
    }
  }

  /** What an artifact may advertise is what a linker can resolve, and a `private` declaration is not
   * that: its symbol is emitted `internal` (`13 §2`), which says every caller is inside the module
   * that defines it.
   *
   * A library used to advertise one anyway — the precompiled half was every function of the
   * library's own modules, read off the key — and the program that reached it did the reasonable
   * thing with what it was told: declared the symbol, called it, and found nothing at the link.
   * The trait default is how a program comes to reach one at all, since the hoisted copy is
   * materialized in the program and its body names whatever the default named.
   *
   * Left out of the advertisement, the program compiles a copy of its own from the tree the artifact
   * carries — the same answer a generic gets, and `internal` is exactly the licence to have two.
   *
   * Found while writing `sysl.text`'s `Search` trait, whose cutset trims called a private membership
   * helper.
   */
  "a private helper reached only from a trait default" - {

    val withDefault =
      """module demo
        |
        |trait Widen
        |    base(self) -> int
        |
        |    scaled(self) -> int = tripled(self.base())
        |
        |impl Widen for int
        |    base(self) -> int = self
        |
        |private tripled(n: int) -> int = n * 3
        |""".stripMargin

    "survives into the artifact, so a program instantiating the default links" in {
      assume(Toolchain.clangAvailable, "clang not available")

      val lib  = artifactOf(rootOf("demo", withDefault))
      val prog = program("import demo.*\n\nvar x = 7\nprint(x.scaled())")

      succeeds(Config(command = "run", file = prog, libs = List(lib)))
    }

    // The neighbouring determinism, which is the weaker half of `StdArtifactTests`' "one library
    // built two ways": the same files read the same way twice say the same thing. What that one adds
    // is the case only the standard module can pose — the same files arriving as the `Source` objects the
    // compiler embeds rather than as a second read of them.
    "and the same library built twice from disk agrees with itself" in {
      val root = rootOf("demo", withDefault.replace("private tripled", "tripled"))

      def symbols(): Set[String] =
        LibraryArtifact.build(Project.collect(root), Target.default, LibraryArtifact.std) match
          case Right((_, meta)) =>
            LibraryArtifact.read("twice.syslib", meta, Target.default) match
              case Right((_, syms, _)) => syms
              case Left(err)           => fail(err)
          case Left(err) => fail(err)

      val first  = symbols()
      val second = symbols()

      second shouldBe first
    }

    // The same library with the helper public links too, which is what says the fault was the
    // linkage and not the trait default on its own.
    "and does so when the helper is public" in {
      assume(Toolchain.clangAvailable, "clang not available")

      val lib  = artifactOf(rootOf("demo", withDefault.replace("private tripled", "tripled")))
      val prog = program("import demo.*\n\nvar x = 7\nprint(x.scaled())")

      succeeds(Config(command = "run", file = prog, libs = List(lib)))
    }

    // The other direction through the same seam: the library calls its *own* default, and the
    // program only calls the function that does. A member of a builtin type is keyed under the type,
    // which has no module — so nothing about the key says whose it is, and what decides has to be
    // the file it was written in.
    "and a library that calls its own default on a builtin type links too" in {
      assume(Toolchain.clangAvailable, "clang not available")

      val callsItsOwn =
        """module demo
          |
          |trait Widen
          |    base(self) -> int
          |
          |    scaled(self) -> int = self.base() * 3
          |
          |impl Widen for int
          |    base(self) -> int = self
          |
          |thrice(n: int) -> int = n.scaled()
          |""".stripMargin

      val lib  = artifactOf(rootOf("demo", callsItsOwn))
      val prog = program("import demo.*\n\nprint(thrice(7))")

      ran(Config(command = "run", file = prog, libs = List(lib))) shouldBe "21\n"
    }
  }

  /** A library's module-level `val` at a counted type, through the artifact (`13 §7`).
   *
   * A `val` may hold a string whose bytes the object file carries, and the shape that asked for it —
   * a table of messages a module with no allocator can index — is exactly the shape a *library*
   * supplies. So the value has to survive the crossing: the artifact carries the declaration's
   * untyped tree, and the program that links it lays the storage down on this side.
   */
  "a library's 'val' holding a table of string literals" - {

    val messages =
      """module demo
        |
        |val names: [3]string = ["alpha", "beta", "gamma"]
        |
        |struct Device
        |    name: string
        |    code: int
        |end Device
        |
        |val devices: [2]Device = [Device("uart", 16), Device("timer", 32)]
        |""".stripMargin

    "crosses the artifact and is indexed at a value only known while running" in {
      assume(Toolchain.clangAvailable, "clang not available")

      val lib  = artifactOf(rootOf("demo", messages))
      val prog = program("import demo.*\n\nvar i = 2\n\nprint(names[i], names[0].len)")

      ran(Config(command = "run", file = prog, libs = List(lib))) shouldBe "gamma 5\n"
    }

    "and so does a table of structs holding them" in {
      assume(Toolchain.clangAvailable, "clang not available")

      val lib  = artifactOf(rootOf("demo", messages))
      val prog = program("import demo.*\n\nvar i = 1\n\nprint(devices[i].name, devices[i].code)")

      ran(Config(command = "run", file = prog, libs = List(lib))) shouldBe "timer 32\n"
    }
  }
}
