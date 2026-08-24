package sh.sysl

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

/** An unknown subcommand is somebody else's: `sysl doc` runs `sysl-doc` from the PATH.
 *
 * git's convention, and it exists here to settle where a tool like a documentation generator lives.
 * Every major toolchain ships that as a separate binary — `scaladoc` beside `scalac`, `rustdoc`
 * beside `rustc` — because its dependencies have nothing to do with the compiler's, and the price
 * of a separate binary is that nobody finds it. This buys both.
 *
 * **The case that matters most is the one that could break something already working**: a built-in
 * must win over a same-named binary on the PATH, or installing `sysl-build` would displace the
 * compiler's own `build`. That is the first group below.
 *
 * **What is NOT covered here, said plainly rather than left to be assumed:** the exec itself. Making
 * a real `sysl-<name>` visible to this process would mean changing its own `PATH`, which no JVM can
 * do to itself — so the seam is tested from both sides instead. `findOnPath` is exercised against
 * real files, and `drive` is shown to consult it and to refuse when it answers nothing. What runs
 * the child is `runProgram`, which `sysl run` uses for every program the suite executes.
 */
class SubcommandTests extends AnyFreeSpec with Matchers {

  "the built-in commands are read off the parser, not written down twice" - {
    "the ones sysl has are all there" in {
      // If scopt's usage rendering ever changes, this answers empty and every built-in starts being
      // looked for on the PATH. That is the failure this assertion exists to make loud.
      val builtins = builtinCommands

      builtins should contain allOf ("run", "build", "build-lib", "build-c", "test", "emit-llvm",
        "emit-header", "weave", "tangle", "prove", "deps", "targets")
    }

    "and nothing that is an option or an argument" in {
      val builtins = builtinCommands

      builtins should not contain "--version"
      builtins should not contain "<path>"
      builtins.filter(_.startsWith("-")) shouldBe empty
      builtins.filter(_.startsWith("<")) shouldBe empty
    }
  }

  "a built-in wins over anything on the PATH" - {
    "a command sysl has is never looked for externally" in {
      // `targets` is the one built-in that needs no file and no toolchain, so it is the cheap way to
      // prove the dispatch let a built-in through: it prints the registry and exits 0. Reaching the
      // external path instead would refuse, naming 'sysl-targets'.
      val out = new java.io.ByteArrayOutputStream

      Console.withOut(out)(drive(Seq("targets"))) shouldBe 0
      out.toString should include("aarch64-macos")
    }

    "even though its name would otherwise be a perfectly good external one" in {
      builtinCommands should contain("targets")
    }
  }

  "an unknown command is looked for on the PATH, and refused by name when it is not there" - {
    def refusal(args: Seq[String]): (Int, String) = {
      val errs   = new java.io.ByteArrayOutputStream
      val status = Console.withErr(errs)(drive(args))

      (status, errs.toString)
    }

    "the message names the binary it looked for, not just the word that was typed" in {
      // The distinction the whole PATH search exists for: "there is no sysl-doc on your PATH" tells
      // somebody they have a tool to install. "unknown command 'doc'" tells them they made a typo,
      // and only one of those is usually true.
      val (status, err) = refusal(Seq("nosuchsubcommand"))

      status should not be 0
      err should include("'nosuchsubcommand' is not a sysl command")
      err should include("there is no 'sysl-nosuchsubcommand' on the PATH")
    }

    "and it lists what sysl does have, so a typo is still answerable" in {
      val (_, err) = refusal(Seq("biuld"))

      err should include("build")
      err should include("run")
    }

    "the arguments after it do not change the answer" in {
      val (_, err) = refusal(Seq("nosuchsubcommand", "--flag", "file.sysl", "--", "more"))

      err should include("there is no 'sysl-nosuchsubcommand' on the PATH")
    }
  }

  "a leading option is not a command" - {
    "'--version' is answered by sysl rather than looked for as 'sysl---version'" in {
      val out = new java.io.ByteArrayOutputStream

      Console.withOut(out)(drive(Seq("--version"))) shouldBe 0
      out.toString should include("sysl ")
    }

    "and an empty command line is still the usage failure it always was" in {
      val errs = new java.io.ByteArrayOutputStream

      Console.withErr(errs)(drive(Seq.empty)) should not be 0
    }
  }

  "finding a program on the PATH" - {
    // JS has no PATH to search and says so by answering None; these assertions are about the two
    // platforms that run programs, and the JS build is not one (`platform.scala` says why).
    "finds one that is there and can be run" in {
      assume(platform != "js", "the JS build does not search a PATH")

      // `sh` is on the PATH of every machine this compiler builds on, POSIX requires it, and it is
      // what `ProcessBuilder` would have found too.
      val found = findOnPath("sh")

      found shouldBe defined
      found.get should endWith("sh")
    }

    "answers nothing for a name that is not there" in {
      findOnPath("sysl-a-program-nobody-has-installed") shouldBe None
    }

    "and nothing for a name that is not a program" in {
      assume(platform != "js", "the JS build does not search a PATH")

      // A directory named like the command is not the command. Finding one would stop the search at
      // something that cannot run while a real one sat further down the PATH.
      findOnPath(".") shouldBe None
    }
  }
}
