package io.github.edadma.sysl

import io.github.edadma.cross_platform.*

import scopt.OParser

/** The sysl command-line driver. It reads a module's source files, runs the pure front end and
 * codegen from the shared module, and drives an LLVM toolchain to link and run the result. Filesystem
 * and process access go through `cross_platform`, so the same driver ships as a native binary
 * and as a Node CLI (the JVM build is for a fast development loop).
 *
 * Each subcommand takes a **path**, which is a **project root** or a single file. A module is a
 * directory and its name is that directory's path relative to the root (`13 §1`), so naming a
 * directory compiles the whole tree under it — one module per directory, each holding its files to
 * the name its location gives it — and naming a file compiles that file alone.
 *
 * Subcommands:
 *   - `sysl run <path>`        compile and execute
 *   - `sysl build <path> -o x` compile to a native executable
 *   - `sysl emit-llvm <path>`  print the generated LLVM IR
 *   - `sysl targets`           list the machines sysl can build for
 *
 * **Everything after a bare `--` belongs to the program being run**, not to sysl: it is passed
 * straight through to the executable, which is what lets `sysl run prog.sysl -- -v file` reach a
 * `main(args: []string)` without sysl having to decide whether `-v` was meant for it. The split is
 * made before the options are parsed, which is why an argument that looks like one of sysl's own is
 * still the program's.
 *
 * `--explain-escapes` may be given to any of them: it reports, on stderr, every local array the
 * compiler moved to the heap and the view that forced it (`05`).
 *
 * `--target` names the machine to build for (`targets.md`). Given none, a build is for the machine
 * it is running on — and if that is one sysl has no entry for, it says so and stops rather than
 * guessing, because a wrong guess produces a module that looks right and is not.
 */
case class Config(
    command: String = "",
    file: String = "",
    output: Option[String] = None,
    explainEscapes: Boolean = false,
    target: Option[String] = None,
    programArgs: List[String] = Nil,
)

@main def sysl(args: String*): Unit = {
  val builder = OParser.builder[Config]
  val parser = {
    import builder.*
    OParser.sequence(
      programName("sysl"),
      cmd("run")
        .action((_, c) => c.copy(command = "run"))
        .text("compile and run a sysl module, given its directory or a single file; " +
          "arguments after '--' go to the program")
        .children(arg[String]("<path>").required().action((f, c) => c.copy(file = f))),
      cmd("build")
        .action((_, c) => c.copy(command = "build"))
        .text("compile a sysl module to a native executable")
        .children(
          arg[String]("<path>").required().action((f, c) => c.copy(file = f)),
          opt[String]('o', "output").action((o, c) => c.copy(output = Some(o))).text("output executable path"),
        ),
      cmd("emit-llvm")
        .action((_, c) => c.copy(command = "emit-llvm"))
        .text("print the generated LLVM IR")
        .children(arg[String]("<path>").required().action((f, c) => c.copy(file = f))),
      cmd("targets")
        .action((_, c) => c.copy(command = "targets"))
        .text("list the machines sysl can build for"),
      opt[Unit]("explain-escapes")
        .action((_, c) => c.copy(explainEscapes = true))
        .text("report every local array promoted to the heap, and the view that forced it"),
      opt[String]("target")
        .action((t, c) => c.copy(target = Some(t)))
        .text("the machine to build for; defaults to this one. 'sysl targets' lists them"),
      checkConfig(c => if c.command.isEmpty then failure("a subcommand is required") else success),
    )
  }

  val (own, forwarded) = processArgs(args).span(_ != "--")

  OParser.parse(parser, own, Config()) match
    case Some(cfg) => processExit(execute(cfg.copy(programArgs = forwarded.drop(1).toList)))
    case None      => processExit(2)
}

private def execute(cfg: Config): Int = {
  if cfg.command == "targets" then return listTargets()

  val target = chooseTarget(cfg.target) match
    case Left(err) => return fail(err)
    case Right(t)  => t

  // Running the result is what makes `run` different from `build`, and only this machine can do
  // that — so a cross target is refused here rather than built and then failed to execute.
  if cfg.command == "run" && !Target.host.contains(target) then
    return fail(s"'run' executes what it builds, and '${target.name}' is not this machine — " +
      s"use 'sysl build --target ${target.name}'")

  val sources =
    try Project.collect(cfg.file)
    catch case e: Exception => return fail(s"cannot read ${cfg.file}: ${e.getMessage}")

  if sources.isEmpty then return fail(s"${cfg.file} holds no sysl source files")

  // One compilation, whatever the subcommand does with it. The notes come back beside the IR
  // rather than being printed from inside the compiler, which has no business writing to a console.
  val compiled = Compiler.compiled(sources, target) match
    case Left(err) => return report(err)
    case Right((ir, notes)) =>
      if cfg.explainEscapes then
        if notes.isEmpty then Console.err.println("no arrays were promoted to the heap")
        else notes.foreach(Console.err.println)
      ir

  cfg.command match
    case "emit-llvm" =>
      stdout(compiled); 0

    case "build" =>
      val exe = cfg.output.getOrElse(defaultOutputName(cfg.file))

      Toolchain.build(compiled, exe, target) match
        case Left(err) => fail(err)
        case Right(_)  => Console.err.println(s"wrote $exe"); 0

    case "run" =>
      val exe = createTempFile("sysl-", "")

      Toolchain.build(compiled, exe, target) match
        case Left(err) => deleteFile(exe); fail(err)
        case Right(_) =>
          val result = exec(exe :: cfg.programArgs)
          deleteFile(exe)
          stdout(result.stdout)
          if result.stderr.nonEmpty then Console.err.print(result.stderr)
          result.exitCode

    case other =>
      fail(s"unknown command '$other'")
}

/** Which machine this invocation is for: the one it names, or this one. A machine sysl has no entry
 * for is reported rather than guessed at — the guess would be a module that looks right and is
 * built for something else.
 */
private def chooseTarget(named: Option[String]): Either[String, Target] = named match
  case Some(name) => Target.named(name)
  case None =>
    Target.host.toRight(
      "this machine is not one sysl knows, so a build has to name its target with --target " +
        "('sysl targets' lists them)")

/** The registry, as a reader of `sysl targets` sees it: the name to write, the LLVM triple it
 * stands for, and — for one sysl knows and cannot build for — why not.
 */
private def listTargets(): Int = {
  val width = Target.all.map(_.name.length).max

  for t <- Target.all do
    val here  = if Target.host.contains(t) then "  (this machine)" else ""
    val limit = if t.supported then "" else s"  (${t.pointerBits}-bit — not yet supported)"

    stdout(s"${t.name.padTo(width, ' ')}  ${t.triple}$here$limit\n")

  // Always the words this machine's own runtime used, recognized or not. On a machine sysl has no
  // entry for that is the whole of what a report needs, and there is nowhere else to read it.
  stdout(s"\nthis machine reports: ${Target.hostMachineShown}\n")

  0
}

private def defaultOutputName(file: String): String = {
  val slash = math.max(file.lastIndexOf('/'), file.lastIndexOf('\\'))
  val name  = if slash >= 0 then file.substring(slash + 1) else file
  val dot   = name.lastIndexOf('.')
  val base  = if dot > 0 then name.substring(0, dot) else name
  if base.isEmpty then "a.out" else base
}

/** A driver failure — something that went wrong around the compiler rather than inside it. */
private def fail(msg: String): Int = {
  Console.err.println(s"sysl: error: $msg")
  1
}

/** A diagnostic from the compiler, which already renders itself with its location and a caret
 * under the offending column, so it is printed exactly as it came.
 */
private def report(diagnostic: String): Int = {
  Console.err.println(diagnostic)
  1
}
