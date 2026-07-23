package io.github.edadma.sysl

import java.nio.file.{Files, Path, Paths}

import scala.sys.process.*

import scopt.OParser

/** The sysl command-line driver (JVM only — it touches the filesystem and drives an LLVM
 * toolchain). The pure front end and codegen live in the shared module.
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

  OParser.parse(parser, args, Config()) match
    case Some(cfg) => sys.exit(execute(cfg))
    case None      => sys.exit(2)
}

private def execute(cfg: Config): Int = {
  val source =
    try new String(Files.readAllBytes(Paths.get(cfg.file)), "UTF-8")
    catch case e: Exception => return fail(s"cannot read ${cfg.file}: ${e.getMessage}")

  cfg.command match
    case "emit-llvm" =>
      Compiler.compileToLlvm(source) match
        case Left(err) => fail(err)
        case Right(ir) => print(ir); 0

    case "build" =>
      Compiler.compileToLlvm(source) match
        case Left(err) => fail(err)
        case Right(ir) =>
          val exe = Paths.get(cfg.output.getOrElse(defaultOutputName(cfg.file)))
          Toolchain.build(ir, exe) match
            case Left(err) => fail(err)
            case Right(_)  => Console.err.println(s"wrote $exe"); 0

    case "run" =>
      Compiler.compileToLlvm(source) match
        case Left(err) => fail(err)
        case Right(ir) =>
          val exe = Files.createTempFile("sysl-", "")
          Files.deleteIfExists(exe)
          Toolchain.build(ir, exe) match
            case Left(err) => fail(err)
            case Right(_) =>
              val code = Seq(exe.toString).!
              Files.deleteIfExists(exe)
              code

    case other =>
      fail(s"unknown command '$other'")
}

private def defaultOutputName(file: String): String = {
  val name = Paths.get(file).getFileName.toString
  val base = if name.contains('.') then name.substring(0, name.lastIndexOf('.')) else name
  if base.isEmpty then "a.out" else base
}

private def fail(msg: String): Int = {
  Console.err.println(s"sysl: $msg")
  1
}
