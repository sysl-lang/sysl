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
 */
case class Config(
    command: String = "",
    file: String = "",
    output: Option[String] = None,
)

@main def sysl(args: String*): Unit = {
  val builder = OParser.builder[Config]
  val parser = {
    import builder.*
    OParser.sequence(
      programName("sysl"),
      cmd("run")
        .action((_, c) => c.copy(command = "run"))
        .text("compile and run a sysl module, given its directory or a single file")
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
      checkConfig(c => if c.command.isEmpty then failure("a subcommand is required") else success),
    )
  }

  OParser.parse(parser, processArgs(args), Config()) match
    case Some(cfg) => processExit(execute(cfg))
    case None      => processExit(2)
}

private def execute(cfg: Config): Int = {
  val sources =
    try Project.collect(cfg.file)
    catch case e: Exception => return fail(s"cannot read ${cfg.file}: ${e.getMessage}")

  if sources.isEmpty then return fail(s"${cfg.file} holds no sysl source files")

  cfg.command match
    case "emit-llvm" =>
      Compiler.compile(sources) match
        case Left(err) => report(err)
        case Right(ir) => stdout(ir); 0

    case "build" =>
      Compiler.compile(sources) match
        case Left(err) => report(err)
        case Right(ir) =>
          val exe = cfg.output.getOrElse(defaultOutputName(cfg.file))
          Toolchain.build(ir, exe) match
            case Left(err) => fail(err)
            case Right(_)  => Console.err.println(s"wrote $exe"); 0

    case "run" =>
      Compiler.compile(sources) match
        case Left(err) => report(err)
        case Right(ir) =>
          val exe = createTempFile("sysl-", "")
          Toolchain.build(ir, exe) match
            case Left(err) => deleteFile(exe); fail(err)
            case Right(_) =>
              val result = exec(Seq(exe))
              deleteFile(exe)
              stdout(result.stdout)
              if result.stderr.nonEmpty then Console.err.print(result.stderr)
              result.exitCode

    case other =>
      fail(s"unknown command '$other'")
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
