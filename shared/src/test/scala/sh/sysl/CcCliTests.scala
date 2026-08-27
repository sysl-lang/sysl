package sh.sysl

import io.github.edadma.cross_platform.*

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

/** `--cc`, at the seams a flag naming a compiler can quietly stop applying at.
 *
 * Three `Toolchain` diagnostics told the reader to *"name one with `--cc`"* for months while nothing
 * parsed the flag at all (card `0197`). So the first thing asserted here is the one that was false:
 * that the argument list yields it. The rest is about reach — a flag honoured in `build` and dropped
 * when the standard module is rebuilt underneath it would fail later than the flag and blame the
 * library, which is the half-measure the card names.
 *
 * **A path that cannot be run is what every reach test uses**, because it is the one answer that
 * cannot be produced by a search: no machine has a clang at `/nonexistent`, so the message naming it
 * proves the argument travelled rather than that something plausible happened.
 */
class CcCliTests extends AnyFreeSpec with Matchers {

  private def parse(args: String*): Option[Config] = parseArgs(args)

  private val absent = "/nonexistent/clang"

  "what an argument list says" - {

    "a build that names no compiler carries none, so the search still decides" in {
      parse("build", "prog.sysl").map(_.cc) shouldBe Some(None)
    }

    "and one that names a compiler carries it" in {
      parse("build", "prog.sysl", "--cc", "/opt/homebrew/opt/llvm/bin/clang").map(_.cc) shouldBe
        Some(Some("/opt/homebrew/opt/llvm/bin/clang"))
    }

    // The flag belongs to every command that reaches a C compiler, not to `build` alone. A `--cc`
    // that silently stopped applying when you changed command is the same defect as one that stops
    // applying when the library is rebuilt, one level up.
    "every command that reaches clang accepts it" in {
      for command <- List("run", "build", "build-lib", "build-c", "test") do
        withClue(command) { parse(command, "prog.sysl", "--cc", absent).map(_.cc) shouldBe Some(Some(absent)) }
    }

    // `--ar` is the companion flag and the two are independent: naming one must not answer for the
    // other, which is the mistake a single "toolchain" option would have invited.
    "and it is independent of --ar" in {
      val cfg = parse("build-lib", "lib", "--cc", absent, "--ar", "/nonexistent/llvm-ar")

      cfg.map(_.cc) shouldBe Some(Some(absent))
      cfg.map(_.ar) shouldBe Some(Some("/nonexistent/llvm-ar"))
    }
  }

  "what a named compiler reaches" - {

    // `findClang`'s own contract: a named one is not searched past and not fallen back from. Without
    // that, naming a compiler that cannot run would quietly build with a different one.
    "a named compiler that cannot run is refused rather than searched past" in {
      Toolchain.findClang(Target.default, Some(absent)) match
        case Left(why) => why should include(absent)
        case Right(cc) => fail(s"searched past a named compiler and found '$cc'")
    }

    // The link step takes its compiler from the search paths, which is the record every build
    // already carries. This is the flag's ordinary road.
    "the link step takes it from the search paths" in {
      val exe = createTempFile("sysl-cc-", "")

      Toolchain.build("", exe, Target.default, paths = SearchPaths(cc = Some(absent))) match
        case Left(why) => why should include(absent)
        case Right(_)  => fail("linked with a compiler that does not exist")
    }

    // **THE ONE THE CARD IS ABOUT.** `Stdlib.writeArtifact` reaches the toolchain by a path carrying
    // no `SearchPaths`, so it is where a flag threaded through the ordinary road silently stops. A
    // build whose standard module happens to be cold would then change compiler halfway.
    "and so does the standard module's own rebuild, which carries no search paths" in {
      val out = s"${createTempDirectory("sysl-cc-")}/std.syslib"

      Stdlib.writeArtifact(out, Target.default, cc = Some(absent)) match
        case Left(why) => why should include(absent)
        case Right(_)  => fail("rebuilt the standard module with a compiler that does not exist")
    }

    // The negative control, so the assertions above are known to be about the flag rather than about
    // everything failing: the same call with the compiler a search would have found does build.
    "while naming the one a search would have found builds exactly as not naming it does" in {
      assume(Toolchain.clangAvailable, "clang not available")

      val found = Toolchain.findClang(Target.default).getOrElse(fail("no clang to name"))
      val exe   = createTempFile("sysl-cc-", "")
      val ir    = """define i32 @main() { ret i32 0 }"""

      Toolchain.build(ir, exe, Target.default, paths = SearchPaths(cc = Some(found))) shouldBe Right(())
      isFile(exe) shouldBe true
    }
  }
}
