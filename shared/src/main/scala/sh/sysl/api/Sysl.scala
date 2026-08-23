package sh.sysl
package api

/** **The sysl compiler as a library**, for a caller holding source as a *string*.
 *
 * The command-line driver takes paths, because a program on disk is what it is for. A tool that
 * *generated* the source it wants compiled — a documentation harness with a page's code block, an
 * editor with an unsaved buffer, a test with an inline program — has no file and no reason to make
 * one, and writing a temporary file merely to hand it back to a process is ceremony with a failure
 * mode of its own. This is the same compiler reached without that step.
 *
 * **Nothing here mentions a syntax tree, and that is deliberate.** Every signature is written in
 * `String`, `Int`, `List` and `Either`, so the language's own declarations stay free to change — a
 * node added to the AST is not a breaking change to anything compiled against this. A caller that
 * genuinely wants the tree is a different kind of tool (a formatter, a language server) and reaches
 * past this on purpose.
 *
 * Where such a caller starts, since there is now somewhere to start: `SyslParser.parse` for the
 * tree, `sh.sysl.Locate` to resolve a cursor to the constructs it is inside, and
 * `sh.sysl.Analyzer.indexed` for the typed tree together with a definition index. None of those is
 * narrow, none of them is stable in the way this is, and that is the trade a tool of that kind
 * makes knowingly.
 *
 * Every method that compiles answers `Left` with the **rendered diagnostic**, exactly as the driver
 * would have printed it, so a caller has something to show a reader without knowing what a
 * diagnostic is. `check` is the exception and is here for the other kind of caller: it answers the
 * mistakes as **data**, each with a range, because an editor cannot underline a paragraph and a
 * build tool cannot group one by file. It is still plain `String` and `Int` — a `Problem` and a
 * `Span` and nothing of the compiler's own.
 *
 * It sits in a sub-package rather than beside the compiler for two reasons, and the second is not a
 * matter of taste. The published surface being somewhere of its own is what makes "narrow" checkable
 * rather than asserted. And an object named `Sysl` cannot sit in a package named `sysl` on a
 * case-insensitive filesystem: its class file and the package's are one file, and what results is
 * not a name clash anybody is told about but a `Sysl` that silently cannot be resolved.
 *
 * ```
 * Sysl.run("main()\n    print(1 + 2)")            // Right(Sysl.Run(0, "3\n"))
 * Sysl.runBody("print(1 + 2)")                    // the same, with the `main` supplied
 * Sysl.compile("main()\n    print(x)")            // Left("… undefined name 'x' …")
 * Sysl.check("main()\n    print(x)")              // List(Problem("undefined name 'x'", Some(…)))
 * ```
 */
object Sysl {

  /** One file of a program: what to call it in a diagnostic, its text, and the directory it would
   * have sat in.
   *
   * `dir` is how a module is named without a filesystem — a module is a directory (`13 §1`), so a
   * file that would live at `std/fs/read.sysl` carries `List("std", "fs")` and one at the project
   * root carries `Nil`. A single-file program never needs it.
   */
  final case class File(name: String, text: String, dir: List[String] = Nil)

  /** What running a compiled program produced: the status the platform reported, and its stdout. */
  final case class Run(exitCode: Int, out: String)

  /** Where something is, as a caller with a file open thinks of it: 1-based lines and columns, and
   * an **exclusive** end — so the width of a span on one line is `endCol - col`, and a span whose
   * end equals its start covers nothing.
   *
   * The columns are the ones in the **file**, which for a literate source are not the ones the
   * lexer counted: the program inside one is indented, and its left margin is added back here so
   * that an editor told to go somewhere goes where the reader is looking.
   */
  final case class Span(file: String, line: Int, col: Int, endLine: Int, endCol: Int)

  /** One thing wrong with a program: what is wrong, and — nearly always — where.
   *
   * `at` is absent for a rule that fires away from any one place: a whole-program check, a
   * synthesized declaration, a toolchain the machine has not got. A caller with nowhere to put it
   * still has something to say.
   */
  final case class Problem(message: String, at: Option[Span])

  /** Whether this machine can *build* what the compiler emits.
   *
   * sysl emits textual LLVM IR and hands it to clang, so `compile` works anywhere and `run` needs a
   * toolchain. A caller that runs programs should ask this first and say so, rather than report a
   * missing linker as if the program were at fault.
   */
  def canRun: Boolean = Toolchain.clangAvailable

  /** The compiler's own version, as `sysl --version` reports it. */
  def version: String = BuildInfo.version

  // --- checking, for a caller that wants the mistakes as data --------------------------------

  /* Everything else here answers `Left` with the diagnostic **rendered**, which is what a caller
   * showing a reader a paragraph wants and is deliberately all they get. These two are the other
   * kind of caller: an editor with a file open wants a range to underline, a build tool wants to
   * group by file, and neither can take a paragraph apart to find them.
   *
   * There is no body form, unlike everything below. A body is what a documentation page shows and
   * what a test writes inline; a tool that checks a file has a file.
   */

  /** Every mistake in one file, in source order — **empty when it compiles**.
   *
   * Nothing is truncated. The compiler's own output stops at five, on the reasoning that a wall of
   * diagnostics is the first few with the signal falling off behind them; an editor marking a file
   * has the opposite need, since the ones it left out are the ones with no underline.
   */
  def check(text: String, name: String = "<input>"): List[Problem] =
    checkFiles(List(File(name, text)))

  /** The same, for a program written as several files. */
  def checkFiles(files: List[File]): List[Problem] =
    Compiler.checked(files.map(source)) match
      case Right(_)       => Nil
      case Left(problems) => problems.map(problem)

  private def problem(d: Diagnostic): Problem = Problem(d.message, d.pos.map(span))

  /** A position in the compiler's terms, as a caller with a file open reads it. `columnOffset` is
   * what a literate file's margin costs, and it is added back on both ends.
   */
  private def span(p: Pos): Span =
    Span(p.source.name, p.line, p.col + p.source.columnOffset, p.endLine,
         p.endCol + p.source.columnOffset)

  // --- whole programs ---------------------------------------------------------------------

  /** Compiles one file to LLVM IR. */
  def compile(text: String, name: String = "<input>"): Either[String, String] =
    compileFiles(List(File(name, text)))

  /** Compiles the files of one program to LLVM IR. */
  def compileFiles(files: List[File]): Either[String, String] =
    Compiler.compile(files.map(source))

  /** Compiles one file, links it, runs it, and answers what it did. */
  def run(text: String, name: String = "<input>", args: List[String] = Nil): Either[String, Run] =
    runFiles(List(File(name, text)), args)

  /** The same, for a program written as several files. */
  def runFiles(files: List[File], args: List[String] = Nil): Either[String, Run] =
    Toolchain.runIr(Compiler.compiled(files.map(source)), args).map(Run.apply)

  // --- bodies -----------------------------------------------------------------------------

  /* A **body** is the statements a program runs, together with whatever declarations they need, and
   * no `main` to put them in. It is what a documentation page shows and what a test writes inline,
   * because the wrapper is not the thing being taught — and `Bodies` supplies it structurally, which
   * is the part a caller could not have done for itself with string operations.
   */

  /** Compiles one body to LLVM IR, supplying the `main` its statements belong to. */
  def compileBody(text: String, name: String = "<input>"): Either[String, String] =
    compileBodyFiles(List(File(name, text)))

  /** The same, for a body written as several files — at most one of which carries statements. */
  def compileBodyFiles(files: List[File]): Either[String, String] =
    bodies(files).flatMap(us => Compiler.compiledTrees(us).map(_.ir))

  /** Compiles one body, links it, runs it, and answers what it did. */
  def runBody(text: String, name: String = "<input>", args: List[String] = Nil): Either[String, Run] =
    runBodyFiles(List(File(name, text)), args)

  /** The same, for a body written as several files. */
  def runBodyFiles(files: List[File], args: List[String] = Nil): Either[String, Run] =
    bodies(files).flatMap(us => Toolchain.runIr(Compiler.compiledTrees(us), args)).map(Run.apply)

  // --- the crossing into the compiler's own terms ------------------------------------------

  private def source(f: File): Source = Source(f.name, f.text, f.dir)

  /** Parses each file and moves its top-level statements into a `main`, reporting every file's
   * parse errors together rather than stopping at the first — which is what the driver does, and
   * what a caller showing a page of results wants.
   */
  private def bodies(files: List[File]): Either[String, List[Program]] = {
    val parsed = files.map(f => Bodies.parseBody(source(f)))

    parsed.collect { case Left(e) => e } match
      case Nil  => Right(parsed.collect { case Right(u) => u })
      case errs => Left(errs.mkString("\n"))
  }
}
