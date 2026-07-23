package io.github.edadma.sysl

import io.github.edadma.cross_platform.*

import scopt.OParser

/** The sysl command-line driver. It reads a source file, runs the pure front end and codegen
 * from the shared module, and drives an LLVM toolchain to link and run the result. Filesystem
 * and process access go through `cross_platform`, so the same driver ships as a native binary
 * and as a Node CLI (the JVM build is for a fast development loop).
 *
 * Subcommands:
 *   - `sysl run <file>`        compile and execute
 *   - `sysl build <file> -o x` compile to a native executable
 *   - `sysl emit-llvm <file>`  print the generated LLVM IR
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
        .text("compile and run a sysl program")
        .children(arg[String]("<file>").required().action((f, c) => c.copy(file = f))),
      cmd("build")
        .action((_, c) => c.copy(command = "build"))
        .text("compile a sysl program to a native executable")
        .children(
          arg[String]("<file>").required().action((f, c) => c.copy(file = f)),
          opt[String]('o', "output").action((o, c) => c.copy(output = Some(o))).text("output executable path"),
        ),
      cmd("emit-llvm")
        .action((_, c) => c.copy(command = "emit-llvm"))
        .text("print the generated LLVM IR")
        .children(arg[String]("<file>").required().action((f, c) => c.copy(file = f))),
      checkConfig(c => if c.command.isEmpty then failure("a subcommand is required") else success),
    )
  }

  OParser.parse(parser, processArgs(args), Config()) match
    case Some(cfg) => processExit(execute(cfg))
    case None      => processExit(2)
}

private def execute(cfg: Config): Int = {
  val source =
    try readFile(cfg.file)
    catch case e: Exception => return fail(s"cannot read ${cfg.file}: ${e.getMessage}")

  cfg.command match
    case "emit-llvm" =>
      Compiler.compileToLlvm(source) match
        case Left(err) => fail(err)
        case Right(ir) => stdout(ir); 0

    case "build" =>
      Compiler.compileToLlvm(source) match
        case Left(err) => fail(err)
        case Right(ir) =>
          val exe = cfg.output.getOrElse(defaultOutputName(cfg.file))
          Toolchain.build(ir, exe) match
            case Left(err) => fail(err)
            case Right(_)  => Console.err.println(s"wrote $exe"); 0

    case "run" =>
      Compiler.compileToLlvm(source) match
        case Left(err) => fail(err)
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

private def fail(msg: String): Int = {
  Console.err.println(s"sysl: $msg")
  1
}
