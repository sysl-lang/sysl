package sh.sysl

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
        |arrays: [2, 3, 5, 7] [4, 6, 10, 14]
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

  /** The scanner, driven by hand. Every kind of piece a command line can hold is on this one line —
   * a bundle whose last option takes the rest of its word, a long option, an operand, and a `--`
   * after which something that looks like an option is not one.
   */
  "options — reading a command line with the scanner" in {
    example("options.sysl", "-vo", "out.txt", "one", "--", "-two") shouldBe
      """verbose: true
        |output : out.txt
        |files  : 2
        |   one
        |   -two
        |""".stripMargin
  }

  "options — and with nothing given, the program's own defaults stand" in {
    example("options.sysl") shouldBe
      """verbose: false
        |output : -
        |files  : 0
        |""".stripMargin
  }

  /** `wc` has to be given something to count, and a temporary file is the only input an assertion can
   * be sure of — counting a file of the repository would pin this test to whatever that file happens
   * to say today, which is the kind of expectation that gets deleted rather than fixed.
   *
   * Only the counting is asserted. Every way this example stops early exits non-zero, and `example`
   * fails on that before there is any output to read, so what it says about a file it cannot open
   * belongs to `FsTests` — where the same failure is reached through the library rather than through
   * a program — and what it says about a bad option belongs to `ArgsCliTests`.
   */
  "wc — counting the lines, words and bytes of a file" in {
    val path = createTempFile("sysl-wc-", ".txt")

    writeFile(path, "one two three\nfour five\n\nsix\n")

    try example("wc.sysl", path) shouldBe f"${4}%8d${6}%8d${29}%8d $path%s\n"
    finally deleteFile(path)
  }

  // A flag narrows what is printed to the one count it names, which is the whole point of the table
  // this example grew. Each of the three is asked for separately, so a flag reaching the wrong
  // counter shows up as the wrong number rather than as a missing column.
  "wc — a flag prints only the count it names" in {
    val path = createTempFile("sysl-wc-", ".txt")

    writeFile(path, "one two three\nfour five\n\nsix\n")

    try {
      example("wc.sysl", "-l", path) shouldBe f"${4}%8d $path%s\n"
      example("wc.sysl", "-w", path) shouldBe f"${6}%8d $path%s\n"
      example("wc.sysl", "-c", path) shouldBe f"${29}%8d $path%s\n"
      example("wc.sysl", "-lw", path) shouldBe f"${4}%8d${6}%8d $path%s\n"
      example("wc.sysl", "--words", "--lines", path) shouldBe f"${4}%8d${6}%8d $path%s\n"
    } finally deleteFile(path)
  }

  // The help text is generated from the same table the flags above are read against, so this is the
  // assertion that catches the two drifting apart.
  "wc — and its help text is the table it reads" in {
    example("wc.sysl", "--help") shouldBe
      """usage: wc [options] <file>
        |
        |Count lines, words and bytes in a file.
        |
        |options:
        |  -l, --lines    print the line count
        |  -w, --words    print the word count
        |  -c, --bytes    print the byte count
        |  -h, --help     show this help and exit
        |  -V, --version  show the version and exit
        |""".stripMargin
  }

  "every example in the tree has an expectation above" in {
    assume(isDirectory(dir), s"$dir is not reachable from the working directory")

    listFiles(dir).filter(_.endsWith(".sysl")).map(Project.basename).toList.sorted shouldBe
      List("args.sysl", "hello.sysl", "options.sysl", "wc.sysl")
  }
}
