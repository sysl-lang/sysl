package sh.sysl

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
    val status = Console.withOut(out)(sh.sysl.execute(cfg))

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

  /* `--help` is the same shape and shipped later, because 0.0.1 went out without it: `sysl --help`
   * answered `Error: Unknown option --help`. The usage text was always reachable — an invocation
   * naming no subcommand prints it — but only by failing, which is not the same thing as answering. */

  "--help" - {

    "stands on its own, like --version" in {
      parse("--help").map(_.command) shouldBe Some("help")
    }

    "prints the usage, on stdout and with a zero status" in {
      // The distinction worth pinning. The same text reaches stderr with a 2 when an invocation is
      // wrong, and that is right — but somebody who *asked* has not made a mistake, so
      // `sysl --help | less` gets the text and a script checking the status is not told otherwise.
      val (status, out) = ran(Config(command = "help"))

      status shouldBe 0
      out should include("Usage: sysl")
    }

    "and the usage it prints lists every subcommand, being generated rather than written out" in {
      // Not a copy kept in step with the parser by hand: `OParser.usage` renders the parser itself,
      // so a subcommand added above cannot go missing here. Asserting the list is what would catch
      // it having been replaced by a hand-maintained string.
      val out = ran(Config(command = "help"))._2

      for command <- List("run", "build", "build-lib", "test", "emit-llvm", "prove", "targets") do
        withClue(s"'$command' missing from the usage: ") { out should include(command) }
    }

    "and it names the two flags that stand alone" in {
      val out = ran(Config(command = "help"))._2

      out should include("--version")
      out should include("--help")
    }

    "and puts the options that belong to no command under a heading of their own" in {
      // An option renders exactly as a command's own children do — same indent, same shape — so
      // printed flush against the last command they read as *its* options, and `sysl --target`
      // looked like something `sysl targets` took. The heading is the separation, and each of these
      // is asserted to fall on the far side of it rather than merely to be present.
      val lines = ran(Config(command = "help"))._2.linesIterator.toList
      val where = lines.indexWhere(_.startsWith("Options, which any command takes:"))

      where should be > 0

      // The line an option is *declared* on, which is the one that matters: `--lib` is also named in
      // the prose of `build-lib`, above the heading, and a search for the text alone would find that
      // and call it a pass.
      for flag <- List("--version", "--help", "--explain-escapes", "--target", "--lib",
                       "--std-lib", "--no-std-lib", "--ar", "--optimize")
      do withClue(s"'$flag' is not declared under the heading: ") {
        lines.indexWhere(l => l.startsWith("  -") && l.contains(flag)) should be > where
      }
    }

    "while an invocation naming nothing still fails rather than helpfully succeeding" in {
      // The neighbour that keeps the above from being a statement about `checkConfig` having
      // quietly stopped applying. A bare `sysl` is a mistake and is still reported as one.
      parse() shouldBe None
    }
  }
}
