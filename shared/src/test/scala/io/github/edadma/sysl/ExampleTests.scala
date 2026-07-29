package io.github.edadma.sysl

import io.github.edadma.cross_platform.*

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

/** The programs in `examples/`, compiled from the tree and run.
 *
 * These are the first sysl anybody reads — `README.md` points at them and `run-example.sh` runs
 * them — which makes them the one part of the repository whose audience is not the compiler. That is
 * also what makes them rot: nothing else in this suite reads a file out of `examples/`, and
 * `hello.sysl` sat with a match arm the grammar had stopped accepting for long enough that the
 * commit which broke it is not worth finding.
 *
 * So the assertion is the whole of stdout, exactly. An example's output *is* what it teaches, and a
 * change to it should be a line edited in this file rather than something noticed later by a reader.
 * The last test is what keeps the set honest: a new example with no expectation here fails, so the
 * cheap way to add one is to add both.
 */
class ExampleTests extends AnyFreeSpec with Matchers {

  private val dir = "examples"

  /** Compiles and runs `examples/<name>`, returning its stdout.
   *
   * An example is a single file, so it is read and handed over as source rather than through
   * `Project.collect` — which is also what leaves room for the arguments, since a program started
   * with none cannot show what a `main(args: []string)` is for.
   */
  private def example(name: String, args: String*): String = {
    val path = s"$dir/$name"

    assume(Toolchain.clangAvailable, "clang not available")
    assume(isFile(path), s"$path is not reachable from the working directory")

    Toolchain.compileAndRun(readFile(path), path, args.toList) match {
      case Right((0, out))    => out
      case Right((code, out)) => fail(s"$path exited with $code:\n$out")
      case Left(err)          => fail(s"$path did not compile:\n$err")
    }
  }

  "hello — a tour of the language in one file" in {
    example("hello.sysl") shouldBe
      """Hello, sysl!
        |area = 42
        |3 + 4 = 7
        |sum 1..10 = 55
        |55 is odd
        |point: 6 7
        |55 is medium
        |areas: 75 12 0
        |box: wrapped 1
        |quarter: ok odd odd
        |byte: 44 25
        |u64 max: 18446744073709551615
        |convert: 3 3.5 65
        |char: é true ☃
        |counter: 42
        |chain: 6
        |balance: 140 true
        |items: 3 a
        |primes: 4 2 14
        |total: 17
        |slices: 36 6 10 15
        |filled: 2 0 0 5
        |scratch: 6
        |string: 13  world true
        |cut: h é true
        |bytes: 13 104
        |methods: 37 1369 10
        |""".stripMargin
  }

  // The zeroth argument is the executable's own path, which is a temporary file here and is not
  // something a test can predict — so this asserts the lines after it and that the count includes it.
  "args — the arguments a program was started with" in {
    val out = example("args.sysl", "-n", "one", "two")

    out.linesIterator.next() should startWith("started as: ")
    out.linesIterator.drop(1).mkString("\n") shouldBe
      """3 argument(s) given
        |   1: -n
        |   2: one
        |   3: two""".stripMargin
  }

  "an example with nothing after the program's own name prints no arguments" in {
    example("args.sysl") should endWith("0 argument(s) given\n")
  }

  "every example in the tree has an expectation above" in {
    assume(isDirectory(dir), s"$dir is not reachable from the working directory")

    listFiles(dir).filter(_.endsWith(".sysl")).map(Project.basename).toList.sorted shouldBe
      List("args.sysl", "hello.sysl")
  }
}
