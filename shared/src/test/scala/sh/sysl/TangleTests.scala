package sh.sysl

import io.github.edadma.cross_platform.*

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

/** `sysl tangle`: the program a literate source holds, with the prose stripped.
 *
 * **The machinery is `Literate.tangle` and is already covered where it lives** — a build has been
 * tangling for as long as the format has existed. What is asserted here is the *command*: that the
 * thing it prints is the thing the compiler reads, that it reaches a whole tree, and that it refuses
 * what it cannot answer.
 *
 * **The line correspondence is the property worth pinning.** Prose is replaced by blank lines rather
 * than removed, so line 100 of the tangle is line 100 of the source — which is what lets a
 * diagnostic about the program point into the document, and is the whole reason somebody asks what
 * tangling produced.
 */
class TangleTests extends AnyFreeSpec with Matchers {

  private def source(text: String): Source = Source(s"tangle${Literate.Extension}", text)

  /** A literate source that really is on disk, which is what the three cases below need: what they
    * assert is the *command*, and a command reads a file. `sysl.regex` is the library's own literate
    * module — five files of it — and it took over from `guide/slab` when the guide set was retired.
    */
  private def literateFile: Option[String] =
    StdRoot.root.map(root => s"$root/sysl/regex/vm${Literate.Extension}")


  private def ran(cfg: Config): String = {
    val out    = new java.io.ByteArrayOutputStream
    val notes  = new java.io.ByteArrayOutputStream
    val status = Console.withOut(out)(Console.withErr(notes)(sh.sysl.execute(cfg)))

    withClue(s"${out.toString}${notes.toString}")(status shouldBe 0)
    out.toString
  }

  private def refused(cfg: Config): String = {
    val notes  = new java.io.ByteArrayOutputStream
    val status = Console.withOut(new java.io.ByteArrayOutputStream)(
      Console.withErr(notes)(sh.sysl.execute(cfg)))

    withClue(notes.toString)(status should not be 0)
    notes.toString
  }

  "the prose goes and the program stays" in {
    val found = literateFile

    assume(found.isDefined, "the library is not reachable from the test working directory")

    val path = found.get

    val out = ran(Config(command = "tangle", file = path))

    out.linesIterator.filter(_.trim.nonEmpty).toList should not be empty
    // Whatever the prose said, none of it is program.
    out should not include "##"
  }

  "a line of the program keeps its line number" in {
    // The correspondence is what makes the command worth having: a diagnostic about the program
    // points into the document it was written in, and this is the thing that has to hold for it.
    val text = "Some prose about it.\n\nAnd more.\n\n    print(\"hi\")\n"

    Literate.tangle(source(text)) match
      case Left(err) => fail(err)
      case Right(program) =>
        val lines = program.text.linesIterator.toList

        lines.length shouldBe text.linesIterator.length
        lines(4).trim shouldBe "print(\"hi\")"
        lines.take(4).forall(_.trim.isEmpty) shouldBe true
  }

  "what it prints is what the compiler reads" in {
    // The command exists to answer exactly this question, so the two must not be separately
    // computed -- what is asserted is that the driver hands over the reader's own output.
    val found = literateFile

    assume(found.isDefined, "the library is not reachable from the test working directory")

    val path = found.get

    val out = ran(Config(command = "tangle", file = path))

    Literate.tangle(Source(path, readFile(path))) match
      case Left(err)      => fail(err)
      case Right(program) => out.trim shouldBe program.text.trim
  }

  "it writes to a file when it is asked to" in {
    val found = literateFile

    assume(found.isDefined, "the library is not reachable from the test working directory")

    val path = found.get

    val out = s"${createTempDirectory("sysl-tangle-")}/vm.sysl"

    ran(Config(command = "tangle", file = path, output = Some(out)))
    readFile(out).linesIterator.filter(_.trim.nonEmpty).toList should not be empty
  }

  "an ordinary program has nothing to tangle" in {
    val dir = createTempDirectory("sysl-tangle-plain-")

    writeFile(s"$dir/main.sysl", "main() = print(1)\n")
    refused(Config(command = "tangle", file = dir)) should include(Literate.Extension)
  }

  "a file the compiler refuses is not tangled" in {
    Literate.tangle(source("Text\n\n\tprint(\"hi\")\n")) match
      case Right(_) => fail("a file the compiler refuses was tangled")
      case Left(_)  => succeed
  }
}
