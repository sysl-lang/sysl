package sh.sysl

import io.github.edadma.cross_platform.*

import org.scalatest.freespec.AnyFreeSpec

/** `main` may answer with a `Result[unit, E]`, so that `?` reaches the top of a program.
 *
 * **What it replaces is `.unwrap()` on every fallible call in `main`.** That reports a failure as a
 * panic naming the line that gave up, which is the wrong line: what the reader wants is the thing
 * that went wrong, said once, on the way out. Letting the error travel as a value to the end of the
 * program is what makes that possible, and this is the last step of the journey.
 *
 * The report itself is ordinary sysl — `sysl.main_result`, instantiated at whatever error type
 * `main` named — for the same reason `args_of` is: the entry point should carry as little
 * hand-written IR as the platform allows. The bound on it is load-bearing rather than decorative, so
 * an error nobody could render is refused where it is written rather than exiting silently.
 */
class MainResultTests extends AnyFreeSpec with CodegenSupport with RunSupport {

  /** A program run through the driver, answering with what it printed on each stream and the status
   * it exited with — which is the whole of what this feature is about, and `run` alone reports none
   * of it.
   */
  private def program(src: String): (Int, String, String) = {
    val path = createTempFile("sysl-mainres-", ".sysl")

    writeFile(path, src)

    val out    = new java.io.ByteArrayOutputStream
    val notes  = new java.io.ByteArrayOutputStream
    val status = Console.withOut(out)(Console.withErr(notes)(sh.sysl.execute(Config(command = "run", file = path))))

    deleteFile(path)
    (status, out.toString, notes.toString)
  }

  "a main that answers Ok" - {

    "runs, and exits nothing" in {
      val (status, out, _) = program("main() -> Result[unit, string]\n    print(\"worked\")\n\n    Ok(())\n")

      status shouldBe 0
      out shouldBe "worked\n"
    }
  }

  "a main that answers Err" - {

    // The three things a failing program owes: what went wrong, on the stream for saying so, and a
    // status that says it failed.
    "reports the error on stderr and exits non-zero" in {
      val (status, out, notes) =
        program("main() -> Result[unit, string]\n    print(\"starting\")\n\n    Err(\"the disk is on fire\")\n")

      status should not be 0
      out shouldBe "starting\n"
      notes should include("error: the disk is on fire")
    }

    // The whole point: the failure came from a call three lines up and nothing wrote `.unwrap()`.
    "including one that '?' carried out of a call" in {
      val src =
        """import sysl.fs.{read_text, IoError}
          |
          |main() -> Result[unit, IoError]
          |    val s = read_text("/nonexistent/file")?
          |
          |    print(s)
          |
          |    Ok(())
          |""".stripMargin

      val (status, out, notes) = program(src)

      status should not be 0
      out shouldBe ""
      notes should include("error: no such file or directory")
    }

    "and the arguments form answers the same way" in {
      val src =
        """main(args: []string) -> Result[unit, string]
          |    if args.len > 99 then return Ok(())
          |
          |    Err("too few arguments")
          |""".stripMargin

      val (status, _, notes) = program(src)

      status should not be 0
      notes should include("error: too few arguments")
    }
  }

  "what is still refused" - {

    "a main that answers something other than a Result" in {
      err("main() -> int = 3") should include("'main' yields nothing or a 'Result[unit, E]'")
    }

    // The `unit` is not decoration: a value `main` answered with would have nowhere to go, since the
    // platform takes a status and not a value.
    "a Result carrying a value on the Ok side" in {
      err("main() -> Result[int, string] = Ok(3)") should include("'main' yields nothing or a 'Result[unit, E]'")
    }

    // The bound is what makes the report possible at all.
    "and an error type that cannot be rendered" in {
      val src =
        """struct Silent
          |    n: int
          |end Silent
          |
          |main() -> Result[unit, Silent] = Err(Silent(1))
          |""".stripMargin

      err(src) should include("Display")
    }
  }
}
