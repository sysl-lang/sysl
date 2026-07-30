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
 *   - `sysl run <path>`            compile and execute
 *   - `sysl build <path> -o x`     compile to a native executable
 *   - `sysl build-lib <path> -o x` compile a library to a linkable artifact
 *   - `sysl emit-llvm <path>`      print the generated LLVM IR
 *   - `sysl targets`               list the machines sysl can build for
 *
 * **`--lib` takes either a source tree or an artifact**, and which one is read off the name: a
 * `.syslib` is decoded, anything else is walked as source. That is deliberate — how a library was
 * shipped is the shipper's business, and a program that depends on one should not have to write down
 * which it got. `build-lib` is what turns the first into the second, and the only difference
 * downstream is what the compilation *cost*: an artifact is a linear decode where source is a parse.
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
    libs: List[String] = Nil,
    core: Boolean = false,
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
      cmd("build-lib")
        .action((_, c) => c.copy(command = "build-lib"))
        .text("compile a sysl library to a linkable artifact, for '--lib'")
        .children(
          arg[String]("<path>").required().action((f, c) => c.copy(file = f)),
          opt[String]('o', "output").action((o, c) => c.copy(output = Some(o))).text("output artifact path"),
          opt[Unit]("core")
            .action((_, c) => c.copy(core = true))
            .text("this library is sysl's own standard module, which the compiler otherwise supplies"),
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
      opt[String]("lib")
        .unbounded()
        .action((l, c) => c.copy(libs = c.libs :+ l))
        .text("a library to compile against — a '.syslib' artifact or a source root; " +
          "may be given more than once"),
      checkConfig(c => if c.command.isEmpty then failure("a subcommand is required") else success),
    )
  }

  val (own, forwarded) = processArgs(args).span(_ != "--")

  OParser.parse(parser, own, Config()) match
    case Some(cfg) => processExit(execute(cfg.copy(programArgs = forwarded.drop(1).toList)))
    case None      => processExit(2)
}

/** What a subcommand does, and the exit status it leaves. Visible to the package so a test can drive
 * the driver rather than re-implementing it — the error paths here are the ones a user meets, and
 * none of them is reachable from the compiler's own API.
 */
private[sysl] def execute(cfg: Config): Int = {
  if cfg.command == "targets" then return listTargets()

  val sources =
    try Project.collect(cfg.file)
    catch case e: Exception => return fail(s"cannot read ${cfg.file}: ${e.getMessage}")

  if sources.isEmpty then return fail(s"${cfg.file} holds no sysl source files")

  val target = chooseTarget(cfg.target) match
    case Left(err) => return fail(err)
    case Right(t)  => t

  // Building a library stops here — there is no program to link it into. An artifact is **for a
  // machine**, exactly as an rlib is, because half of it is compiled object code; the generic half
  // travels as trees because there is nothing to compile until a caller fixes its type arguments.
  if cfg.command == "build-lib" then return buildLibrary(cfg, sources, target)

  // Running the result is what makes `run` different from `build`, and only this machine can do
  // that — so a cross target is refused here rather than built and then failed to execute.
  if cfg.command == "run" && !Target.host.contains(target) then
    return fail(s"'run' executes what it builds, and '${target.name}' is not this machine — " +
      s"use 'sysl build --target ${target.name}'")

  // A library reaches a compilation two ways, and neither is a second kind of input — both end as
  // **more modules**. Given as source its files carry the directory segments they were found under,
  // exactly as the program's do. Given as an artifact it arrives already parsed. Which one a path
  // names is read off the name, so a program that depends on a library need not know how it shipped.
  val (artifacts, roots) = cfg.libs.partition(LibraryArtifact.isArtifact)

  // Both halves are read into memory here and neither touches the disk yet: everything below may
  // still refuse the compilation, and a temporary file written before that has to be cleaned up on
  // every one of those paths. The object half is materialized once there is a link to do.
  val unpacked =
    try artifacts.map(p => LibraryArtifact.unpack(p, readBytes(p)))
    catch case e: Exception => return fail(s"cannot read a library: ${e.getMessage}")

  unpacked.collectFirst { case Left(e) => e } match
    case Some(e) => return fail(e)
    case None    => ()

  val decoded = artifacts.zip(unpacked).collect { case (p, Right((meta, _))) => LibraryArtifact.read(p, meta) }

  decoded.collectFirst { case Left(e) => e } match
    case Some(e) => return fail(e)
    case None    => ()

  val collected =
    try roots.map(root => root -> Project.collect(root))
    catch case e: Exception => return fail(s"cannot read a library: ${e.getMessage}")

  collected.find(_._2.isEmpty) match
    case Some((root, _)) => return fail(s"$root holds no sysl source files")
    case None            => ()

  val librarySources = collected.flatMap(_._2)
  val read           = decoded.collect { case Right(r) => r }
  val libraryTrees   = read.flatMap(_._1)

  // What the library already compiled, so this module declares those rather than defining them a
  // second time. Their bodies arrive from the archive at link time.
  val precompiled = read.flatMap(_._2).toSet

  // One compilation, whatever the subcommand does with it. The notes come back beside the IR
  // rather than being printed from inside the compiler, which has no business writing to a console.
  val compiled = Compiler.compiledWith(librarySources ::: sources, libraryTrees, target, precompiled) match
    case Left(err) => return report(err)
    case Right((ir, notes)) =>
      if cfg.explainEscapes then
        if notes.isEmpty then Console.err.println("no arrays were promoted to the heap")
        else notes.foreach(Console.err.println)
      ir

  // The compilation is settled, so the object halves are written where `clang` can be given a path
  // to them. They exist only for the linker's sake and are cleaned up on every path out of here,
  // including the ones that failed.
  val objects = unpacked.collect { case Right((_, obj)) => obj }.map { obj =>
    val path = createTempFile("sysl-lib-", ".o")
    writeBytes(path, obj)
    path
  }

  try
    cfg.command match
      case "emit-llvm" =>
        stdout(compiled); 0

      case "build" =>
        val exe = cfg.output.getOrElse(defaultOutputName(cfg.file))

        Toolchain.build(compiled, exe, target, objects) match
          case Left(err) => fail(err)
          case Right(_)  => Console.err.println(s"wrote $exe"); 0

      case "run" =>
        val exe = createTempFile("sysl-", "")

        Toolchain.build(compiled, exe, target, objects) match
          case Left(err) => discard(exe); fail(err)
          case Right(_) =>
            val result = exec(exe :: cfg.programArgs)
            discard(exe)
            stdout(result.stdout)
            if result.stderr.nonEmpty then Console.err.print(result.stderr)
            result.exitCode

      case other =>
        fail(s"unknown command '$other'")
  finally objects.foreach(discard)
}

/** Removes a temporary file, whether or not it is there.
 *
 * Cleanup runs on the paths that failed, and those are exactly the paths where the file may never
 * have been created: `createTempFile` reserves a name, and it is the toolchain that writes to it. A
 * `deleteFile` on a link that failed therefore threw, and the stack trace replaced the linker's own
 * message — the one thing the user needed to see.
 */
private def discard(path: String): Unit =
  try deleteFile(path)
  catch case _: Exception => ()

/** `sysl build-lib <path> -o <artifact>` — a library compiled once, into the two halves a program
 * links against (`LibraryArtifact`).
 *
 * The artifact is **for one machine**, exactly as an `.rlib` is, because half of it is object code.
 * The other half is the tree, which would travel anywhere: a generic has no compiled form until the
 * program that calls it fixes its type arguments, so it is monomorphized in that program instead.
 *
 * `--core` says the library being built is sysl's own standard module, which is the one thing an
 * ordinary compilation may not declare. It is written down rather than inferred from the module
 * names in the tree: guessing it would turn a clear refusal — *you cannot add to the module every
 * program is compiled against* — into an artifact that builds and then collides with the built-in
 * copy at whatever link tried to use it.
 */
private def buildLibrary(cfg: Config, sources: List[Source], target: Target): Int =
  LibraryArtifact.build(sources, target, if cfg.core then LibraryArtifact.core else Set.empty) match
    case Left(err) => report(err)
    case Right((ir, meta)) =>
      val out = cfg.output.getOrElse(defaultOutputName(cfg.file) + LibraryArtifact.extension)

      val obj = createTempFile("sysl-", ".o")

      val outcome =
        for _ <- Toolchain.compileObject(ir, obj, target)
        yield writeBytes(out, LibraryArtifact.pack(meta, readBytes(obj)))

      deleteFile(obj)

      outcome match
        case Left(err) => fail(err)
        case Right(_)  => Console.err.println(s"wrote $out"); 0

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
