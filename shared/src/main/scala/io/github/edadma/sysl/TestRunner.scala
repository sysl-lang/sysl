package io.github.edadma.sysl

import io.github.edadma.cross_platform.*

/** `sysl test` — builds the `@test` functions of a source tree and runs them (`testing.md`).
 *
 * **One build, one process per test.** The whole tree is compiled once, into a binary whose entry
 * point takes a test's name and runs that test alone (`Codegen.genTestMain`); the runner then starts
 * it once per test. A test that fails does so by trapping, and a trap takes its process with it — so
 * a run that shared one process would report the first failure and nothing after it. Paying for a
 * process per test buys every test a verdict, and it is the cheap half: the compile is the slow one
 * and there is only ever one of those.
 *
 * **The verdict is the exit status, and nothing else.** A test passes by returning. That is the whole
 * protocol, and it is what lets a test assert with the language it is testing rather than with a
 * framework: a broken `require`, a bounds violation, an `unwrap` of `None` — each ends the process,
 * and none of them needed to know it was running under a test. `@test(should_trap)` inverts the
 * reading for a test whose subject *is* the check.
 */
object TestRunner {

  /** What was asked of a run, beyond which tree to run.
   *
   * `filter` selects by substring against both the reported name and the key, so a module's tests can
   * be named by their module and one test by its own name. `failFast` stops at the first failure,
   * which is what someone iterating on one wants and the opposite of what someone reading a report
   * does — hence a flag rather than a default either way.
   */
  case class Options(filter: Option[String] = None, failFast: Boolean = false)

  /** One test, run. `detail` is absent exactly when the test passed; `output` is everything the run
   * printed, on either stream, which a failure shows and a pass keeps to itself.
   */
  case class Outcome(test: TTest, detail: Option[String], output: String, millis: Long) {
    def passed: Boolean = detail.isEmpty
  }

  /** `sysl test <path>`: compile the tree as a test build, run what it holds, report.
   *
   * A test build is **for this machine**, for the same reason `run` is — the binary is executed and
   * only this machine can execute it. A cross target is refused here rather than built and then found
   * to be unrunnable.
   *
   * `objects` is the C the walked trees carried, already compiled (`NativeSources`). A test build
   * gets it on the same footing as a `build` does, and has to: a package's tests are exactly what
   * exercises the shims it ships, so a test run that dropped them would fail at the link on the one
   * tree most likely to hold C.
   */
  def run(cfg: Config, sources: List[Source], libraries: List[Program], target: Target,
          precompiled: Set[String], std: Stdlib, archives: List[String],
          objects: List[String] = Nil, paths: SearchPaths = SearchPaths.none): Int = {
    if !Target.host.contains(target) then
      return fail(s"'test' runs what it builds, and '${target.name}' is not this machine")

    val (built, tests) = Compiler.compileTests(sources, libraries, target, precompiled, Some(std)) match
      case Left(err)     => return report(err)
      case Right(result) => result

    val opts     = Options(cfg.filter, cfg.failFast)
    val selected = tests.filter(matches(_, opts.filter))

    // Nothing to run is not nothing to say. A tree with no tests at all and a filter that matched
    // none of the tests there are lead to the same empty report and are different mistakes, so each
    // is named. Neither is a failure: a program is allowed to have no tests.
    if tests.isEmpty then
      Console.err.println(s"no '@test' functions in ${cfg.file}")
      return 0

    if selected.isEmpty then
      Console.err.println(s"no test matches '${opts.filter.getOrElse("")}' — ${tests.length} to choose from")
      return 0

    val exe = createTempFile("sysl-test-", "")

    Toolchain.build(built.ir, exe, target, archives, cfg.optimize, built.links, objects, paths) match
      case Left(err) => Project.discard(exe); fail(err)
      case Right(_) =>
        val outcomes = execute(exe, selected, opts)

        Project.discard(exe)
        stdout(rendered(outcomes, tests.length - selected.length))
        if outcomes.forall(_.passed) then 0 else 1
  }

  /** Every selected test, run in the order it was written — which is the order it is reported in, so
   * that a reader following a failure down a file finds the tests where the file put them.
   *
   * `failFast` stops the loop rather than the report: what has run is still reported, and the tests
   * that never ran are simply absent. Reporting them as skipped would be a third verdict for
   * something that is not a verdict at all.
   */
  def execute(exe: String, tests: List[TTest], opts: Options): List[Outcome] = {
    val done = List.newBuilder[Outcome]
    var stop = false

    for t <- tests if !stop do
      val started = System.currentTimeMillis()
      val result  = exec(List(exe, t.func))
      val output  = result.stdout + result.stderr
      val outcome = Outcome(t, verdict(t, result.exitCode, output), output, System.currentTimeMillis() - started)

      done += outcome
      if opts.failFast && !outcome.passed then stop = true

    done.result()
  }

  /** Whether a test's run was what the test said it would be, and if not, what it was instead.
   *
   * The status is read as the platform leaves it: zero for a function that returned, and anything
   * else for a process that did not get there. **A trap is not told from an `exit`**, deliberately —
   * both are a test that did not come back, the language offers no way to distinguish them from
   * inside, and a rule that read one as a pass and the other as a failure would rest on which signal
   * a given machine's `llvm.trap` happens to raise.
   */
  def verdict(t: TTest, status: Int, output: String): Option[String] =
    if !t.shouldTrap then
      Option.when(status != 0)(s"did not return — exit status $status")
    else if status == 0 then
      Some("returned, and was expected to trap")
    else
      t.expected.flatMap(want =>
        Option.when(!output.contains(want))(
          s"trapped, but printed nothing holding \"$want\""))

  /** A test is selected when the pattern is a substring of either name it has: the one a report shows
   * and the key its module gives it. Two, because the two are what a reader has to hand — a name read
   * off a failing report, and a module named to run everything under it.
   */
  def matches(t: TTest, filter: Option[String]): Boolean =
    filter.forall(f => t.display.contains(f) || Modules.show(t.func).contains(f))

  /** The report, grouped by the file each test was written in.
   *
   * The file is the grouping because that is where a reader goes next, and the tests under it keep
   * their source order. A failure's own line says what happened; anything the run printed follows it,
   * indented, and is shown **only** for a failure — output from a test that passed is what the test
   * was doing, not something anyone asked to read.
   */
  def rendered(outcomes: List[Outcome], filtered: Int): String = {
    val out    = new StringBuilder
    val failed = outcomes.count(!_.passed)
    val total  = outcomes.map(_.millis).sum
    val width  = if outcomes.isEmpty then 0 else outcomes.map(_.test.display.length).max

    out ++= s"running ${outcomes.length} ${if outcomes.length == 1 then "test" else "tests"}"
    if filtered > 0 then out ++= s" of ${outcomes.length + filtered}"
    out ++= "\n"

    for (file, group) <- outcomes.groupBy(_.test.file).toList.sortBy(_._1) do
      out ++= s"\n$file\n"

      for o <- group.sortBy(_.test.line) do
        out ++= s"  ${if o.passed then "ok  " else "FAIL"}  ${o.test.display.padTo(width, ' ')}  ${o.millis}ms\n"

        for detail <- o.detail do
          out ++= s"        $detail\n"
          out ++= s"        at ${o.test.file}:${o.test.line}\n"
          for line <- o.output.linesIterator do out ++= s"        > $line\n"

    out ++= s"\n${outcomes.length - failed} passed, $failed failed — ${total}ms\n"
    out.toString
  }

  private def fail(msg: String): Int = {
    Console.err.println(s"sysl: error: $msg")
    1
  }

  private def report(diagnostic: String): Int = {
    Console.err.println(diagnostic)
    1
  }
}
