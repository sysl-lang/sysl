package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

/** What a target decides about the link, asserted on the command line rather than by linking.
 *
 * These exist because the thing they are about **cannot be caught by running anything here**. A
 * program that needs `sqrt` links on a Mac whether or not `-lm` is passed, since Darwin keeps the
 * mathematics in `libSystem` and the driver links that already; the same program on ELF fails at the
 * link without it. So the machine that finds the bug is not the machine the compiler is developed
 * on, and the only honest test is one that reads the arguments a cross target would be given.
 */
class LinkCommandTests extends AnyFreeSpec with Matchers {

  private def commandFor(target: Target): List[String] =
    Toolchain.linkCommand("prog.ll", List("core.syslib"), "prog", target)

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
    "every target in the registry has an answer, and only Linux's is non-empty" in {
      val asking = Target.all.filter(t => Toolchain.systemLibraries(t).nonEmpty).map(_.os).distinct

      asking shouldBe List(Os.Linux)
    }
  }

  "the order the linker scans in" - {
    // Left to right: a member is pulled out of an archive only to resolve something already
    // undefined, so anything listed before the module that calls it contributes nothing. Asserted as
    // positions rather than as a whole list so that adding an unrelated flag does not fail it.
    "puts the module first, then the archives, then the system libraries" in {
      val cmd  = commandFor(Target.x86_64Linux)
      val ll   = cmd.indexOf("prog.ll")
      val arch = cmd.indexOf("core.syslib")
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

  // A program with no library to link against still gets the system libraries: they resolve what the
  // *program's own* externs name, not only what the standard module's do.
  "a link with no archives still asks for the mathematics on Linux" in {
    val cmd = Toolchain.linkCommand("prog.ll", Nil, "prog", Target.aarch64Linux)

    cmd should contain("-lm")
    cmd.indexOf("-lm") should be > cmd.indexOf("prog.ll")
  }
}
