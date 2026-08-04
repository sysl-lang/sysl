package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

/** `sysl --version`, which exists for a reader who has a binary and does not know what it is.
 *
 * A compiler that ships as an installed executable is the first thing here that a user holds a *copy*
 * of rather than a checkout: two people can be running different sysls and neither can tell, and the
 * question comes up the moment one of them reports something the other cannot reproduce. So the flag
 * is part of shipping the binary rather than a nicety to add afterwards.
 *
 * What is pinned is the whole of that contract and not merely that a number appears — the number is
 * the build's (`BuildInfo`, generated from `version` in `build.sbt`), it goes to stdout so a script
 * can read it, and it stands alone rather than being an option to a subcommand.
 */
class VersionCliTests extends AnyFreeSpec with Matchers {

  /** Through the driver's own entry, so what is asked here is what a shell asks. */
  private def parse(args: String*): Option[Config] = parseArgs(args)

  private def ran(cfg: Config): (Int, String) = {
    val out    = new java.io.ByteArrayOutputStream
    val status = Console.withOut(out)(io.github.edadma.sysl.execute(cfg))

    (status, out.toString)
  }

  "what an argument list says" - {

    "--version stands on its own, no subcommand and no path" in {
      // The point of the assertion: `checkConfig` refuses an invocation that names no command, so a
      // flag which did not name one would parse to `None` and print the usage instead of a version.
      parse("--version").map(_.command) shouldBe Some("version")
    }

    "and nothing else has to be supplied with it" in {
      val cfg = parse("--version")

      cfg.map(_.file) shouldBe Some("")
      cfg.map(_.libs) shouldBe Some(Nil)
    }

    "while an invocation naming nothing at all is still refused" in {
      // The neighbouring case, so the one above is a statement about `--version` rather than about
      // `checkConfig` having quietly stopped applying.
      parse() shouldBe None
    }
  }

  "what it prints" - {

    "the version the build was cut at, on stdout, alone on its line" in {
      val (status, out) = ran(Config(command = "version"))

      status shouldBe 0
      out shouldBe s"sysl ${BuildInfo.version}\n"
    }

    // Not a tautology against `BuildInfo`: this says the value is a version rather than whatever
    // string the generator happened to write, which is what a formula's `test` block and a bug
    // report both rely on.
    "and that version is one, rather than a placeholder or an empty string" in {
      BuildInfo.version should fullyMatch regex """\d+\.\d+\.\d+(-.+)?"""
    }

    "and it answers without a toolchain, a project, or anything else on disk" in {
      // The invocation someone makes *before* anything works — a fresh install, a machine with no
      // clang, a directory holding no sysl. Every other subcommand needs a path it can read; this
      // one is asked precisely when the others are failing.
      ran(Config(command = "version"))._1 shouldBe 0
    }
  }
}
