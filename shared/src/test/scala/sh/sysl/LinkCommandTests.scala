package sh.sysl

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

/** What a target decides about the link, asserted on the command line rather than by linking.
 *
 * These exist because the thing they are about **cannot be caught by running anything here**. A
 * program that needs `sqrt` links on a Mac whether or not `-lm` is passed, since Darwin keeps the
 * mathematics in `libSystem` and the driver links that already; the same program on ELF fails at the
 * link without it. So the machine that finds the bug is not the machine the compiler is developed
 * on, and the only honest test is one that reads the arguments a cross target would be given.
 *
 * The library named throughout is `m`, because it is the one the standard module itself asks for
 * (`library/sysl/sys/math.sysl`) and the one whose placement differs across all four platforms.
 */
class LinkCommandTests extends AnyFreeSpec with Matchers {

  /** The command for a program that asked for the mathematics, which is what `sysl.math`'s own
   * directive makes of every build compiled against the standard module.
   */
  private def commandFor(target: Target): List[String] =
    Toolchain.linkCommand("prog.ll", List("std.syslib"), "prog", target,
                          Toolchain.defaultOptimization, List("m"))

  "the mathematics library" - {
    // ELF has libm as a library of its own, so a program that reaches `sysl.math` has to ask for it.
    "is asked for on Linux" in {
      for t <- Target.all.filter(_.os == Os.Linux) do
        withClue(t.name) { commandFor(t) should contain("-lm") }
    }

    // Darwin's is inside `libSystem` and Windows' inside the CRT, both of which the driver links
    // without being told. Passing `-lm` there is not merely redundant — there is no such library to
    // find, and the link fails.
    "is not asked for where the host already carries it" in {
      for t <- Target.all.filter(t => t.os == Os.MacOS || t.os == Os.Windows) do
        withClue(t.name) { commandFor(t) should not contain "-lm" }
    }

    // The case the `Os` match is written out for rather than left to a default: a bare target has no
    // libc at all, so asking for libm would fail the link of a kernel that never wanted mathematics.
    "is not asked for on a freestanding target, which has no libc to hold it" in {
      for t <- Target.all.filter(_.os == Os.Freestanding) do
        withClue(t.name) { commandFor(t).filter(_.startsWith("-l")) shouldBe empty }
    }

    // A target added to the registry without a decision here would fall into whichever arm the match
    // happened to reach. Asking every target in the registry is what makes adding one a decision.
    // Android is the second, and it was measured rather than assumed to be Linux's twin: an NDK link
    // of a program calling `tgamma` fails on an undefined symbol without `-lm`. `sqrt` alone would
    // have answered the other way and answered wrong — it lowers to an instruction and links either
    // way, which is exactly the shape of mistake a guess here makes.
    "every target in the registry has an answer, and only Linux's and Android's ask for it" in {
      val asking = Target.all.filter(t => Toolchain.libraryFlags(List("m"), t).nonEmpty).map(_.os).distinct

      asking shouldBe List(Os.Linux, Os.Android)
      Toolchain.libraryFlags(List("m"), Target.aarch64Android) shouldBe List("-lm")
    }

    // The directive is what makes it happen at all now. Before `15 §8` the driver appended `-lm` to
    // every ELF link whether or not anything asked, and this is the assertion that the list is read
    // rather than remembered.
    "is not asked for at all when nothing named it" in {
      Toolchain.linkCommand("prog.ll", Nil, "prog", Target.x86_64Linux) should not contain "-lm"
    }
  }

  /** WebAssembly is the one target that needs the *linker* told something, and both halves of what
   * it is told exist because of a failure that does not look like one.
   */
  "the WebAssembly link" - {

    // Without it the driver opens a wasm link the way it opens a hosted one -- `crt1.o`, `-lc` and a
    // wasm `libclang_rt.builtins.a`, none of which exists for this triple. The error names `crt1.o`,
    // so it reads as a broken LLVM installation rather than as a bare target having no libc.
    "says -nostdlib, so the driver does not reach for a libc that is not there" in {
      commandFor(Target.wasm32Freestanding) should contain("-nostdlib")
    }

    // **The one that matters, and the reason it is not `--no-entry`.** A wasm module has no `_start`,
    // so `--no-entry` is the obvious spelling — and paired with the `--gc-sections` every link here
    // passes, nothing is reachable from anywhere and the linker drops the whole program and reports
    // success. Measured: 278 bytes, no `main`, exit 0. Naming `main` as the entry is what keeps it,
    // and what exports it under that name for an embedder to call.
    "names main as the entry, because --no-entry would link an empty module green" in {
      val said = commandFor(Target.wasm32Freestanding)

      said should contain("-Wl,--entry=main")
      said should not contain "-Wl,--no-entry"
    }

    // Both are this target's and no other's: every other row here links with a driver whose defaults
    // are already right for it, and a flag leaking onto one of those would be passed to a linker that
    // has never heard of it.
    "and says neither to any other target" in {
      for t <- Target.all.filterNot(_.cpu == Cpu.Wasm32) do
        withClue(t.name) {
          commandFor(t) should not contain "-nostdlib"
          commandFor(t).filter(_.startsWith("-Wl,--entry")) shouldBe empty
        }
    }
  }

  // A directive names a library and the target spells the flag, which is the whole of `15 §8`'s
  // translation. These are the cases that are not `-l` plus the name.
  "resolving a library name for a target" - {

    "passes an unknown library through on every hosted target, since only its placement is guessable" in {
      for t <- Target.all.filterNot(_.os == Os.Freestanding) do
        withClue(t.name) { Toolchain.libraryFlags(List("z"), t) shouldBe List("-lz") }
    }

    // The C runtime is linked unasked everywhere it exists, so naming it is legal and costs nothing.
    // `15 §8` opened with "-lc and friends", so it has to be writable.
    "asks for libc nowhere, because every target that has one links it already" in {
      for t <- Target.all do
        withClue(t.name) { Toolchain.libraryFlags(List("c"), t) shouldBe empty }
    }

    "keeps a library a freestanding target might really have" in {
      Toolchain.libraryFlags(List("m", "boardsupport"), Target.all.find(_.os == Os.Freestanding).get)
        .shouldBe(List("-lboardsupport"))
    }

    // Order is the author's lever: an archive is scanned once, left to right, so a library calling
    // into another has to precede it. Sorting would decide that by spelling.
    "keeps the order they were written in rather than sorting them" in {
      Toolchain.libraryFlags(List("png", "z"), Target.x86_64Linux) shouldBe List("-lpng", "-lz")
      Toolchain.libraryFlags(List("z", "png"), Target.x86_64Linux) shouldBe List("-lz", "-lpng")
    }
  }

  "the order the linker scans in" - {
    // Left to right: a member is pulled out of an archive only to resolve something already
    // undefined, so anything listed before the module that calls it contributes nothing. Asserted as
    // positions rather than as a whole list so that adding an unrelated flag does not fail it.
    "puts the module first, then the archives, then the system libraries" in {
      val cmd  = commandFor(Target.x86_64Linux)
      val ll   = cmd.indexOf("prog.ll")
      val arch = cmd.indexOf("std.syslib")
      val libm = cmd.indexOf("-lm")

      ll should be > 0
      arch should be > ll
      libm should be > arch
    }

    "and names the output last, so nothing is scanned after it" in {
      commandFor(Target.x86_64Linux).takeRight(2) shouldBe List("-o", "prog")
    }

    // The two halves of the dead-striping pair are spelled per object format, and the link half is
    // here. `07`'s pruning is the other mechanism and this does not replace it.
    "carries the dead-strip spelling the object format uses" in {
      commandFor(Target.aarch64MacOS) should contain("-Wl,-dead_strip")
      commandFor(Target.x86_64Linux) should contain("-Wl,--gc-sections")
    }

    "states the triple, so a cross target fails at the link rather than building for the host" in {
      commandFor(Target.riscv64Linux) should contain(s"--target=${Target.riscv64Linux.triple}")
    }
  }

  // A build asks for an optimization level, and `-O0` is deliberately not what it asks for: the fast
  // instruction selector is a different algorithm from the one everything else in the ecosystem
  // ships through, and a miscompile was found living there (`Toolchain.defaultOptimization`).
  "the optimization level" - {

    "is asked for, and defaults to something other than none at all" in {
      commandFor(Target.aarch64MacOS) should contain(s"-O${Toolchain.defaultOptimization}")
      Toolchain.defaultOptimization should not be "0"
    }

    // Whatever was written, spelled after the `-O` — so `s`, `z` and `fast` reach clang as readily
    // as a digit does, and a level clang has no answer for is clang's to complain about.
    "is whatever was named, and only that" in {
      for level <- List("0", "2", "3", "s", "fast") do
        val cmd = Toolchain.linkCommand("prog.ll", Nil, "prog", Target.aarch64MacOS, level)

        withClue(level) {
          cmd should contain(s"-O$level")
          cmd.count(_.startsWith("-O")) shouldBe 1
        }
    }

    // It goes in front of the inputs, where a driver flag belongs, rather than after the output.
    "is stated before the module it applies to" in {
      val cmd = commandFor(Target.x86_64Linux)

      cmd.indexOf(s"-O${Toolchain.defaultOptimization}") should be < cmd.indexOf("prog.ll")
    }
  }

  /** Where this machine keeps a library, which is the host's question and not the target's
   * (`SearchPaths`). The target decides what a directive's name becomes; the search path decides
   * where that name is looked for, and `15 §8` only ever answered the first.
   */
  "a search path given to the driver" - {

    def withPaths(dirs: String*): List[String] =
      Toolchain.linkCommand("prog.ll", List("std.syslib"), "prog", Target.x86_64Linux,
        Toolchain.defaultOptimization, List("m"), Nil, SearchPaths(link = dirs.toList))

    // Joined, as `-L` has been written since cc, so the line reads as a hand-run clang would.
    "reaches the command line as clang spells one" in {
      withPaths("/opt/homebrew/lib") should contain("-L/opt/homebrew/lib")
    }

    // A search path is not an input being scanned in turn — it is where the scan looks — so a
    // directory named after the library that needs it would be a line nobody could reason about.
    "comes before everything it could affect" in {
      val cmd = withPaths("/opt/homebrew/lib")

      cmd.indexOf("-L/opt/homebrew/lib") should be < cmd.indexOf("prog.ll")
      cmd.indexOf("-L/opt/homebrew/lib") should be < cmd.indexOf("-lm")
    }

    "keeps the order it was given in, since that is the order searched" in {
      withPaths("/first", "/second").filter(_.startsWith("-L")) shouldBe List("-L/first", "-L/second")
    }

    // The whole of what a build with no such flag pays: nothing. Every build in this repository is
    // one of those, because the standard module reaches libc by symbol alone.
    "is absent altogether when none was given" in {
      commandFor(Target.x86_64Linux).filter(_.startsWith("-L")) shouldBe empty
    }

    // Guessing `/opt/homebrew/lib` is the obvious convenience and would be wrong for the reason
    // `libraryFlags` refuses to guess a library's placement: a machine nobody here has.
    "is never guessed at for the host" in {
      for t <- Target.all do
        withClue(t.name) {
          Toolchain.linkCommand("prog.ll", Nil, "prog", t).filter(_.startsWith("-L")) shouldBe empty
        }
    }
  }

  // A program with no library to link against still gets what it asked for: a directive resolves
  // what the *program's own* externs name, not only what the standard module's do.
  "a link with no archives still asks for the mathematics on Linux" in {
    val cmd = Toolchain.linkCommand("prog.ll", Nil, "prog", Target.aarch64Linux,
                                    Toolchain.defaultOptimization, List("m"))

    cmd should contain("-lm")
    cmd.indexOf("-lm") should be > cmd.indexOf("prog.ll")
  }

  /** Where what `pkg-config` answered lands on the line (`packages.md § 8`).
   *
   * This is the suite's own argument exactly: **macOS cannot find this bug.** `ld64` gathers every
   * `-L` before it resolves anything, so both orders link here — checked with clang rather than
   * assumed. GNU ld applies a `-L` only to the `-l` options that come *after* it, so a probed search
   * path arriving at the end of the line leaves a `@link` directive's `-l` unresolvable on any Linux
   * where the library sits outside the default prefix.
   */
  "what a probe answered" - {

    val probed = SearchPaths(probedLibs = List("-L/opt/pfx/lib", "-Wl,-rpath,/opt/pfx/lib", "-lcairo"))

    "puts its search paths above the objects, where a -l from a directive can still see them" in {
      val cmd = Toolchain.linkCommand("prog.ll", Nil, "prog", Target.aarch64Linux,
                                      Toolchain.defaultOptimization, List("cairo"), paths = probed)

      cmd.indexOf("-L/opt/pfx/lib") should be < cmd.indexOf("prog.ll")
    }

    // The other half of the same constraint: a `-l` above the objects is dropped by any linker
    // resolving an archive in one pass, so the library itself has to come after them.
    "and puts the library itself, and its rpath, after everything that could refer to it" in {
      val cmd = Toolchain.linkCommand("prog.ll", List("std.syslib"), "prog", Target.aarch64Linux,
                                      Toolchain.defaultOptimization, Nil, paths = probed)

      cmd.indexOf("-lcairo") should be > cmd.indexOf("std.syslib")
      cmd.indexOf("-Wl,-rpath,/opt/pfx/lib") should be > cmd.indexOf("prog.ll")
    }

    // Nothing is added to a link for a program whose packages named no library, which is every build
    // in this repository.
    "and adds nothing at all when nothing was probed" in {
      Toolchain.linkCommand("prog.ll", Nil, "prog", Target.aarch64Linux) shouldBe
        Toolchain.linkCommand("prog.ll", Nil, "prog", Target.aarch64Linux, paths = SearchPaths())
    }
  }
}
