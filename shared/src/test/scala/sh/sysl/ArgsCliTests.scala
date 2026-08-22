package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** `sysl.args`'s described half — a table of options, the help text generated from it, and the
 * conventions `parse_or_exit` applies.
 *
 * Two things are asserted separately here and are worth keeping separate. `parse` is a function of
 * its arguments: it neither prints nor stops, so its tests read a value. `parse_or_exit` is the one
 * that decides, so its tests read a *status* and the two output streams — and which stream a thing
 * came out of is half of what makes the conventions the conventions.
 *
 * The help text is compared whole rather than searched for fragments, because column alignment is
 * the part of it that breaks silently and a `should include` would never see it.
 *
 * Every description below is built **inside** a body rather than at the top level, which is not a
 * stylistic choice: an `Opt` holds strings, and a module-level `val` whose value is built while the
 * program runs is refused — storage that lives for the whole run has nowhere to write a release. A
 * program keeps its table where it uses it.
 */
class ArgsCliTests extends AnyFreeSpec with RunSupport {

  /** The description every case below is parsed against: two flags, two valued options, one of each
   * with no short spelling, and a version so that `--version` is offered.
   */
  private val table =
    """    var verbose = flag('v', "verbose", "print more about what is happening")
      |    var output  = option('o', "output", "path", "write the result here")
      |    var dry     = long_flag("dry-run", "work out what would happen, and do none of it")
      |    var jobs    = option('j', "jobs", "n", "how many at a time")
      |    var spec    = cli("count", [verbose, output, dry, jobs],
      |                      about = "Count what is in a file.",
      |                      version = "0.1.0",
      |                      operands = "[file...]")
      |""".stripMargin

  /** A program that parses and prints what it found, one exact line. Every option is reported, so a
   * value landing at the wrong option shows up as two wrong readings rather than none.
   */
  private val reporter =
    "import sysl.args.*\n\nmain(args: []string)\n" + table +
      """
        |    parse(spec, args) match
        |        Ok(Ready(p)) ->
        |            var s = "v=" + str(p.count(verbose)) + " dry=" + str(p.given(dry))
        |
        |            s += " out=" + p.value_or(output, "-") + " jobs=" + p.value_or(jobs, "1")
        |            s += " rest=" + str(p.positionals.len)
        |
        |            for x in p.positionals
        |                s += ":" + x
        |
        |            print(s)
        |        Ok(HelpRequested)    -> print("help")
        |        Ok(VersionRequested) -> print("version")
        |        Err(e)               -> print("err:", e.message())
        |""".stripMargin

  private def parsed(args: String*): String = runWith(reporter, args*)

  "a described command line" - {
    "with nothing on it leaves every option at its default" in {
      parsed() shouldBe "v=0 dry=false out=- jobs=1 rest=0\n"
    }

    "reads a flag" in {
      parsed("-v") shouldBe "v=1 dry=false out=- jobs=1 rest=0\n"
    }

    // Counting rather than recording a `bool` is what makes `-vvv` mean what it means everywhere.
    "counts a flag given more than once" in {
      parsed("-vvv") shouldBe "v=3 dry=false out=- jobs=1 rest=0\n"
    }

    "counts it across separate words too" in {
      parsed("-v", "--verbose") shouldBe "v=2 dry=false out=- jobs=1 rest=0\n"
    }

    "reads a flag that has no short spelling" in {
      parsed("--dry-run") shouldBe "v=0 dry=true out=- jobs=1 rest=0\n"
    }

    "reads a value" in {
      parsed("-o", "x") shouldBe "v=0 dry=false out=x jobs=1 rest=0\n"
    }

    // The two valued options are distinct, so a value going to the wrong slot is visible.
    "keeps two values apart" in {
      parsed("-o", "x", "-j", "4") shouldBe "v=0 dry=false out=x jobs=4 rest=0\n"
    }

    "reads a bundle whose last option takes the rest as its value" in {
      parsed("-vo", "x") shouldBe "v=1 dry=false out=x jobs=1 rest=0\n"
    }

    "collects the operands in the order they were written" in {
      parsed("a", "-v", "b", "c") shouldBe "v=1 dry=false out=- jobs=1 rest=3:a:b:c\n"
    }

    "takes the last of a value given twice" in {
      parsed("-o", "first", "-o", "second") shouldBe "v=0 dry=false out=second jobs=1 rest=0\n"
    }

    "and after '--' an option-looking word is an operand" in {
      parsed("--", "-v") shouldBe "v=0 dry=false out=- jobs=1 rest=1:-v\n"
    }
  }

  "help and version are reported rather than acted on" - {
    "'--help' is an outcome, not a print" in {
      parsed("--help") shouldBe "help\n"
    }

    "and so is '-h'" in {
      parsed("-h") shouldBe "help\n"
    }

    "'--version' is offered because a version was given" in {
      parsed("--version") shouldBe "version\n"
    }

    "and so is '-V'" in {
      parsed("-V") shouldBe "version\n"
    }

    // A description with no version offers no `--version` at all, so the word is an unknown option
    // rather than a silent success.
    "while a description with no version offers neither spelling" in {
      val src =
        """import sysl.args.*
          |
          |main(args: []string)
          |    var q = flag('q', "quiet", "say less")
          |
          |    parse(cli("thing", [q]), args) match
          |        Ok(Ready(_))         -> print("ready")
          |        Ok(HelpRequested)    -> print("help")
          |        Ok(VersionRequested) -> print("version")
          |        Err(e)               -> print("err:", e.message())
          |""".stripMargin

      runWith(src, "--version") shouldBe "err: unknown option --version\n"
      runWith(src, "-V") shouldBe "err: unknown option -V\n"
      runWith(src, "--help") shouldBe "help\n"
    }
  }

  /** The supplied options never take a spelling away from the program that declared one. Both
   * halves matter: the program's meaning wins, and the other spelling of help still works.
   */
  "a program's own spelling wins over the supplied one" - {
    val ownsBoth =
      """import sysl.args.*
        |
        |main(args: []string)
        |    var height  = option('h', "height", "n", "how tall")
        |    var verbose = flag('V', "verbose", "say more")
        |
        |    parse(cli("thing", [height, verbose], version = "2.0"), args) match
        |        Ok(Ready(p)) ->
        |            print("ready h=" + p.value_or(height, "-") + " V=" + str(p.given(verbose)))
        |        Ok(HelpRequested)    -> print("help")
        |        Ok(VersionRequested) -> print("version")
        |        Err(e)               -> print("err:", e.message())
        |""".stripMargin

    "'-h' stays the program's option" in {
      runWith(ownsBoth, "-h", "3") shouldBe "ready h=3 V=false\n"
    }

    "'-V' stays the program's flag" in {
      runWith(ownsBoth, "-V") shouldBe "ready h=- V=true\n"
    }

    "and the long spellings still reach help and version" in {
      runWith(ownsBoth, "--help") shouldBe "help\n"
      runWith(ownsBoth, "--version") shouldBe "version\n"
    }

    // And the help text says so: with `-h` and `-V` taken, the two supplied options are listed with
    // no short spelling rather than claiming a letter that means something else.
    "so the help text lists them without a letter" in {
      val src =
        """import sysl.args.*
          |
          |var height  = option('h', "height", "n", "how tall")
          |var verbose = flag('V', "verbose", "say more")
          |
          |prints(help(cli("thing", [height, verbose], version = "2.0")))
          |""".stripMargin

      run(src) shouldBe
        """|usage: thing [options]
           |
           |options:
           |  -h, --height <n>  how tall
           |  -V, --verbose     say more
           |      --help        show this help and exit
           |      --version     show the version and exit
           |""".stripMargin
    }
  }

  "what a described command line reports as an error" - {
    "an unknown long option" in {
      parsed("--nope") shouldBe "err: unknown option --nope\n"
    }

    "an unknown short option" in {
      parsed("-z") shouldBe "err: unknown option -z\n"
    }

    "an option whose value is missing" in {
      parsed("-o") shouldBe "err: -o requires a value\n"
    }

    "a value given to a flag" in {
      parsed("--verbose=yes") shouldBe "err: --verbose takes no value\n"
    }
  }

  /** The generated text, whole. Column alignment is what breaks silently, so nothing here is
   * matched by fragment.
   */
  "the generated text" - {
    val described =
      """import sysl.args.*
        |
        |var verbose = flag('v', "verbose", "print more about what is happening")
        |var output  = option('o', "output", "path", "write the result here")
        |var dry     = long_flag("dry-run", "work out what would happen, and do none of it")
        |var jobs    = option('j', "jobs", "n", "how many at a time")
        |var spec    = cli("count", [verbose, output, dry, jobs],
        |                  about = "Count what is in a file.",
        |                  version = "0.1.0",
        |                  operands = "[file...]")
        |""".stripMargin

    "is a usage line naming the program and its operands" in {
      run(described + "\nprint(usage_line(spec))\n") shouldBe "usage: count [options] [file...]\n"
    }

    "is a help text whose descriptions line up in one column" in {
      run(described + "\nprints(help(spec))\n") shouldBe
        """|usage: count [options] [file...]
           |
           |Count what is in a file.
           |
           |options:
           |  -v, --verbose        print more about what is happening
           |  -o, --output <path>  write the result here
           |      --dry-run        work out what would happen, and do none of it
           |  -j, --jobs <n>       how many at a time
           |  -h, --help           show this help and exit
           |  -V, --version        show the version and exit
           |""".stripMargin
    }

    /* A label is built out of text the *program* supplied — its long names and its placeholder —
     * so it is not necessarily ASCII, and the column it sets has to be measured in what a terminal
     * draws rather than in bytes. `<préfé>` is five characters and seven bytes, which makes the
     * label twenty columns and twenty-two bytes: measured wrongly, the two rows under it are pushed
     * two positions right of the row that set the column, and every row disagrees with every other
     * by however many accented characters happen to be above it.
     *
     * This is the byte-versus-column finding reaching the second thing that lays text out: a format
     * specifier's width counts bytes on purpose, so that it means what `snprintf` means, and
     * anything laying out a column asks `sysl.text.columns` instead.
     */
    "and the column is screen columns, so a placeholder that is not ASCII still lines up" in {
      val src =
        """import sysl.args.*
          |
          |var out  = option('o', "output", "préfé", "where to write it")
          |var all  = flag('a', "all", "do every one")
          |var spec = cli("thing", [out, all])
          |
          |prints(help(spec))
          |""".stripMargin

      run(src) shouldBe
        """|usage: thing [options]
           |
           |options:
           |  -o, --output <préfé>  where to write it
           |  -a, --all             do every one
           |  -h, --help            show this help and exit
           |""".stripMargin
    }

    // No version, no `--version` row — the help text lists what the program actually accepts.
    "leaves out the version row where no version was given" in {
      val src =
        """import sysl.args.*
          |
          |var q = flag('q', "quiet", "say less")
          |
          |prints(help(cli("thing", [q], about = "Do a thing.")))
          |""".stripMargin

      run(src) shouldBe
        """|usage: thing [options]
           |
           |Do a thing.
           |
           |options:
           |  -q, --quiet  say less
           |  -h, --help   show this help and exit
           |""".stripMargin
    }

    // A label past the cap takes its description on the next line rather than moving every other
    // description to the right; the short ones stay where a table of short ones would put them.
    "puts an over-long label's description on the next line" in {
      val src =
        """import sysl.args.*
          |
          |var q    = flag('q', "quiet", "say less")
          |var big  = long_option("with-a-very-long-name", "PLACEHOLDER", "the long one")
          |
          |prints(help(cli("thing", [q, big])))
          |""".stripMargin

      run(src) shouldBe
        """|usage: thing [options]
           |
           |options:
           |  -q, --quiet  say less
           |      --with-a-very-long-name <PLACEHOLDER>
           |               the long one
           |  -h, --help   show this help and exit
           |""".stripMargin
    }
  }

  /** What `parse_or_exit` does with what `parse` found. Each of these is a convention rather than a
   * detail: which stream, which status, and that the help a user asked for is not an error.
   */
  "the conventions 'parse_or_exit' applies" - {
    val acting = "import sysl.args.*\n\nmain(args: []string)\n" + table +
      """
        |    var p = parse_or_exit(spec, args)
        |
        |    print("ran with", p.positionals.len, "operands")
        |""".stripMargin

    "a command line it can read simply returns" in {
      val r = outcomeOf(acting, "-v", "a")

      r.status shouldBe 0
      r.out shouldBe "ran with 1 operands\n"
      r.err shouldBe ""
    }

    // Help was what the user asked for, so it succeeds and goes to standard output — where it can
    // be piped into a pager, which is the whole reason for the distinction.
    "'--help' prints to standard output and exits 0" in {
      val r = outcomeOf(acting, "--help")

      r.status shouldBe 0
      r.err shouldBe ""
      r.out should startWith("usage: count [options] [file...]\n")
      r.out should include("  -v, --verbose        print more about what is happening\n")
    }

    "'--version' prints the name and the version, and exits 0" in {
      val r = outcomeOf(acting, "--version")

      r.status shouldBe 0
      r.out shouldBe "count 0.1.0\n"
      r.err shouldBe ""
    }

    // A usage error goes to the *other* stream, so a program whose output is being captured does
    // not have the complaint land in the middle of the answer. The status is 2, which getopt, argp
    // and every parser since reserve for being invoked wrongly, as against 1 for running and
    // failing.
    "a bad option goes to standard error, with the usage line, and exits 2" in {
      val r = outcomeOf(acting, "--nope")

      r.status shouldBe 2
      r.out shouldBe ""
      r.err shouldBe
        """|count: error: unknown option --nope
           |usage: count [options] [file...]
           |try 'count --help' for more information.
           |""".stripMargin
    }

    "and so does a missing value" in {
      val r = outcomeOf(acting, "-o")

      r.status shouldBe 2
      r.out shouldBe ""
      r.err should startWith("count: error: -o requires a value\n")
    }
  }

  /** Asking about an option the description does not declare is a mistake in the program, not in
   * what the user typed — no command line could make it right — so it stops rather than answering.
   */
  "asking about an undeclared option stops the program" in {
    val src =
      """import sysl.args.*
        |
        |main(args: []string)
        |    var declared = flag('d', "declared", "in the table")
        |    var stranger = flag('s', "stranger", "not in the table")
        |    var p = parse_or_exit(cli("thing", [declared]), args)
        |
        |    print(p.given(stranger))
        |""".stripMargin

    panics(src, "asked about an option the description does not declare")
  }

  /** The corners the design says nothing about, which is why they are the ones that break: a table
   * with nothing in it, a program that declares no short spellings at all, and a description whose
   * every optional part was left out.
   */
  "the corners" - {
    // A table of no options is a real thing — a program that takes only operands and still wants
    // `--help`. The parallel arrays a `Parsed` holds are sized from the table, so this is the case
    // where they are empty, and the supplied `--help` still has to be found without them.
    "a description with no options of its own still offers help" in {
      val src =
        """import sysl.args.*
          |
          |main(args: []string)
          |    var empty: []Opt = []
          |
          |    parse(cli("thing", empty, operands = "<file>"), args) match
          |        Ok(Ready(p))         -> print("ready with", p.positionals.len)
          |        Ok(HelpRequested)    -> print("help")
          |        Ok(VersionRequested) -> print("version")
          |        Err(e)               -> print("err:", e.message())
          |""".stripMargin

      runWith(src, "a", "b") shouldBe "ready with 2\n"
      runWith(src, "--help") shouldBe "help\n"
      runWith(src, "-x") shouldBe "err: unknown option -x\n"
    }

    "and its help text is the usage line and the one row" in {
      val src =
        """import sysl.args.*
          |
          |var empty: []Opt = []
          |
          |prints(help(cli("thing", empty, operands = "<file>")))
          |""".stripMargin

      run(src) shouldBe
        """|usage: thing [options] <file>
           |
           |options:
           |  -h, --help  show this help and exit
           |""".stripMargin
    }

    // Every optional part of a description left out: no about, no version, no operands. The usage
    // line is then the program's name and `[options]`, and nothing above the table.
    "a description with only a name and a table" in {
      val src =
        """import sysl.args.*
          |
          |var q = flag('q', "quiet", "say less")
          |
          |prints(help(cli("thing", [q])))
          |""".stripMargin

      run(src) shouldBe
        """|usage: thing [options]
           |
           |options:
           |  -q, --quiet  say less
           |  -h, --help   show this help and exit
           |""".stripMargin
    }

    // A table of nothing but long options: every label is indented past where a letter would go, so
    // the `--`s line up with those of options that have one.
    "a table with no short spellings lines its long ones up anyway" in {
      val src =
        """import sysl.args.*
          |
          |var a = long_flag("alpha", "the first")
          |var b = long_option("beta", "n", "the second")
          |
          |prints(help(cli("thing", [a, b])))
          |""".stripMargin

      run(src) shouldBe
        """|usage: thing [options]
           |
           |options:
           |      --alpha     the first
           |      --beta <n>  the second
           |  -h, --help      show this help and exit
           |""".stripMargin
    }

    // A short-only option has no `--` to line up with and takes the column on its own.
    "a short-only option is listed with no word" in {
      val src =
        """import sysl.args.*
          |
          |var old = short_flag('x', "kept for something older")
          |var num = short_option('n', "count", "how many")
          |
          |prints(help(cli("thing", [old, num])))
          |""".stripMargin

      run(src) shouldBe
        """|usage: thing [options]
           |
           |options:
           |  -x          kept for something older
           |  -n <count>  how many
           |  -h, --help  show this help and exit
           |""".stripMargin
    }

    // And it parses by its letter, with no long spelling to reach it by.
    "a short-only option is read by its letter" in {
      val src =
        """import sysl.args.*
          |
          |main(args: []string)
          |    var num = short_option('n', "count", "how many")
          |
          |    parse(cli("thing", [num]), args) match
          |        Ok(Ready(p)) -> print("n=" + p.value_or(num, "-"))
          |        Err(e)       -> print("err:", e.message())
          |        else print("other")
          |""".stripMargin

      runWith(src, "-n", "3") shouldBe "n=3\n"
      runWith(src, "--count", "3") shouldBe "err: unknown option --count\n"
    }
  }

  /** `value` answers `None` for an option that was never given, and for one that takes no value
   * however often it was. Both halves matter: the first is what `value_or`'s default is for, and
   * the second stops a flag from ever looking as though it carried something.
   */
  "the value of an option that has none" - {
    val asking =
      """import sysl.args.*
        |
        |main(args: []string)
        |    var f = flag('f', "flag", "takes nothing")
        |    var o = option('o', "opt", "v", "takes something")
        |    var p = parse_or_exit(cli("thing", [f, o]), args)
        |
        |    p.value(f) match
        |        Some(v) -> print("flag has", v)
        |        None    -> print("flag has nothing")
        |
        |    p.value(o) match
        |        Some(v) -> print("opt has", v)
        |        None    -> print("opt has nothing")
        |""".stripMargin

    "is None for a flag, however it was given" in {
      runWith(asking, "-f") shouldBe "flag has nothing\nopt has nothing\n"
    }

    "and None for a valued option nobody gave" in {
      runWith(asking, "-o", "x") shouldBe "flag has nothing\nopt has x\n"
    }
  }
}
