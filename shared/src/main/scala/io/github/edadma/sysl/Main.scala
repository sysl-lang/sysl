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
 * **An artifact is an `ar` archive** (`LibraryArtifact`), so it reaches the linker as it stands and
 * only the members that resolve something are pulled in. Building one therefore needs an `llvm-ar`
 * as well as a `clang`; `--ar` names it where it is somewhere a search would not look.
 *
 * **The standard module's own source is read off disk**, from the library installed with this
 * compiler — `<prefix>/share/sysl/lib` beside the binary, or `lib/` in a checkout (`Std.root`).
 * There is no copy inside the executable: a library nobody can open is not one anybody can learn
 * from or edit, which is what every other toolchain concluded too.
 *
 * **`--std-lib` is the same thing for the standard module** as `--lib` is for any other, and every
 * program is compiled against it whether or not one is named. Built by `build-lib --std` and given
 * back here, it replaces the parse of that source: the signatures arrive decoded, and the half that
 * was already compiled is linked rather than emitted a second time into every program.
 *
 * **It need not be given, and it need not already exist.** `build-lib --std` with no `-o` writes to
 * `LibraryArtifact.stdDefault`, and a compilation with no `--std-lib` looks there — one path at both
 * ends. Where nothing usable is at that path the compiler **builds one**, from the library source,
 * and says so on stderr. The artifact is derived rather than authored: not committed, object code
 * for one machine, and computed entirely from the source beside the compiler, so being absent after
 * a clone or stale after a format change has one answer and it is not a question for whoever ran the
 * command. It sits in the user's cache under a fingerprint of the library it was built from, so
 * every project on a machine shares one and a compiler installed with a different library gets a
 * path of its own rather than a stale hit.
 *
 * **Which is not the same as substituting a library.** What a compiler must never do is answer *I
 * could not find the library you meant* by quietly using a different one — and a rebuild uses **this**
 * one, held to `Std.fingerprint` on the way back in. A `--std-lib` that was named and cannot be read
 * still stops the compilation, because there the reader asked for a particular artifact and is owed
 * the truth about it.
 *
 * **`--no-std-lib` is the one route to the library as source**, ignoring whatever artifact is on
 * disk. Compiling it rather than linking it is what makes bootstrap possible — there is no released
 * sysl to build the first artifact with — so it is reached deliberately rather than by a lookup
 * coming up empty. Taken silently it would be taken always, because then nobody would have any
 * reason to build an artifact at all.
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
 *
 * `--optimize` names the level handed to clang, spelled as clang spells one after the `-O`, and it
 * reaches every object a build produces rather than only the link. The default is `1` rather than
 * nothing at all, which is what it used to be: `-O0` is a different instruction selector, it is the
 * mode a back end's own suite covers least, and a miscompile was found living there
 * (`Toolchain.defaultOptimization` has the case). A level clang does not have is clang's to report.
 */
case class Config(
    command: String = "",
    file: String = "",
    output: Option[String] = None,
    explainEscapes: Boolean = false,
    target: Option[String] = None,
    libs: List[String] = Nil,
    std: Boolean = false,
    stdLib: Option[String] = None,
    noStdLib: Boolean = false,
    /** Where to look for a prebuilt standard module, when somewhere other than the default.
      *
      * **An `Option` so that the default is worked out when it is wanted rather than when a `Config`
      * is built.** The default path holds a fingerprint of the library, so naming it here would have
      * read the library's source during argument parsing — before the driver had a chance to report
      * not finding it, and from a place where the failure could only be an exception. A compiler
      * that cannot find its library has to say so on stderr like anything else (`Std.root`).
      */
    stdSearch: Option[String] = None,
    ar: Option[String] = None,
    programArgs: List[String] = Nil,
    filter: Option[String] = None,
    failFast: Boolean = false,
    optimize: String = Toolchain.defaultOptimization,
    /** `prove --emit-whyml` — print the translation instead of running the prover (`17 §9`). */
    emitWhyML: Boolean = false,
    /** `prove --overflow` — whether staying in an integer's range is a proof obligation. */
    overflow: String = "check",
)

/** The option grammar, held apart from the entry point so that a test can ask what an argument list
 * parses to. The alternative is a suite that builds a `Config` by hand and so never finds out
 * whether the flag it is about is spelled the way the user has to spell it.
 */
private[sysl] val parser = {
  val builder = OParser.builder[Config]

  {
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
          opt[Unit]("std")
            .action((_, c) => c.copy(std = true))
            .text("this library is sysl's own standard module, which the compiler otherwise supplies"),
        ),
      cmd("test")
        .action((_, c) => c.copy(command = "test"))
        .text("run the '@test' functions of a sysl module")
        .children(
          arg[String]("<path>").required().action((f, c) => c.copy(file = f)),
          opt[String]("filter")
            .action((f, c) => c.copy(filter = Some(f)))
            .text("run only the tests whose name or module holds this text"),
          opt[Unit]("fail-fast")
            .action((_, c) => c.copy(failFast = true))
            .text("stop at the first test that fails"),
        ),
      cmd("emit-llvm")
        .action((_, c) => c.copy(command = "emit-llvm"))
        .text("print the generated LLVM IR")
        .children(arg[String]("<path>").required().action((f, c) => c.copy(file = f))),
      cmd("prove")
        .action((_, c) => c.copy(command = "prove"))
        .text("translate a module to WhyML and discharge its proof obligations with Why3 (17)")
        .children(
          arg[String]("<path>").required().action((f, c) => c.copy(file = f)),
          opt[Unit]("emit-whyml")
            .action((_, c) => c.copy(emitWhyML = true))
            .text("print the WhyML instead of proving it"),
          opt[String]("overflow")
            .action((o, c) => c.copy(overflow = o))
            .text("'check' (the default) makes staying in an integer's range a proof obligation; " +
              "'ignore' drops those obligations, for reasoning about the rest of a function first"),
        ),
      cmd("targets")
        .action((_, c) => c.copy(command = "targets"))
        .text("list the machines sysl can build for"),
      // A heading, because everything below belongs to no command and the usage would otherwise
      // print it flush against the last one — where an option renders exactly as that command's own
      // children do, so `--target` read as an option of `targets`.
      note("\nOptions, which any command takes:"),
      // Flags rather than subcommands, because they are what somebody types before they know there
      // are subcommands — and each satisfies `checkConfig` below by naming a command of its own, so
      // `sysl --version` and `sysl --help` stand alone rather than being options to something else.
      opt[Unit]("version")
        .action((_, c) => c.copy(command = "version"))
        .text("print which build of sysl this is"),
      // Not scopt's own `help("help")`, though it exists and would render the same text. That one is
      // a *terminating* option: it reaches `OEffect.Terminate`, which the default setup answers with
      // `sys.exit`, so a test that asked what `--help` does would take the test runner down with it.
      // Naming a command instead keeps it on the same footing as every other one — driven through
      // `execute`, answerable in a test, and printing the usage that `OParser` generates anyway.
      opt[Unit]("help")
        .action((_, c) => c.copy(command = "help"))
        .text("print this usage text"),
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
      opt[String]("std-lib")
        .action((l, c) => c.copy(stdLib = Some(l)))
        .text("a prebuilt standard module to compile against, from 'build-lib --std'; " +
          "one that cannot be read stops the compilation, being the one that was asked for"),
      opt[Unit]("no-std-lib")
        .action((_, c) => c.copy(noStdLib = true))
        .text("compile the standard module from its source rather than linking a prebuilt one, " +
          "ignoring whatever artifact is on disk"),
      opt[String]("ar")
        .action((a, c) => c.copy(ar = Some(a)))
        .text("the llvm-ar to build a library with; defaults to searching for one"),
      opt[String]('O', "optimize")
        .action((o, c) => c.copy(optimize = o))
        .text(s"the optimization level to hand clang, as it spells one after the '-O': " +
          s"defaults to ${Toolchain.defaultOptimization}, and '0' is the mode a miscompile was " +
          s"once found in. '-O2' is written the way clang writes it"),
      checkConfig(c => if c.command.isEmpty then failure("a subcommand is required") else success),
    )
  }
}

/** `-O2` split into `-O` and `2`, which is the one place sysl's spelling of an option and the
 * parser's disagree.
 *
 * A short option takes its value as the next argument, and clang's optimization flag is written
 * **joined** — `-O2`, `-Os` — because that is how it has been written since cc. A short form that
 * only worked detached would look like clang's flag without being it, which is a worse thing to
 * offer than no short form at all, so the argument is rewritten rather than the spelling given up.
 *
 * Only that one letter, and only where something follows it: a bare `-O` still takes the next
 * argument, and `--optimize` is untouched because it does not begin `-O`. Nothing here can reach a
 * program's own arguments, which the caller has already split off at the `--`.
 */
private def splitJoinedLevel(args: Seq[String]): Seq[String] =
  args.flatMap(a => if a.startsWith("-O") && a.length > 2 then Seq("-O", a.drop(2)) else Seq(a))

/** sysl's own arguments, parsed. Held apart from the entry point so that a test asks the question a
 * user's shell asks, rather than building a `Config` by hand and never finding out whether a flag is
 * spelled the way it has to be typed.
 */
private[sysl] def parseArgs(own: Seq[String]): Option[Config] =
  OParser.parse(parser, splitJoinedLevel(own), Config())

@main def sysl(args: String*): Unit = {
  val (own, forwarded) = processArgs(args).span(_ != "--")

  parseArgs(own) match
    case Some(cfg) => processExit(execute(cfg.copy(programArgs = forwarded.drop(1).toList)))
    case None      => processExit(2)
}

/** What a subcommand does, and the exit status it leaves. Visible to the package so a test can drive
 * the driver rather than re-implementing it — the error paths here are the ones a user meets, and
 * none of them is reachable from the compiler's own API.
 */
private[sysl] def execute(cfg: Config): Int = {
  if cfg.command == "version" then return printVersion()
  if cfg.command == "help" then return printUsage()
  if cfg.command == "targets" then return listTargets()

  val sources =
    try Project.collect(cfg.file)
    catch case e: Exception => return fail(s"cannot read ${cfg.file}: ${e.getMessage}")

  if sources.isEmpty then return fail(s"${cfg.file} holds no sysl source files")

  val project = readPackageConfig(cfg.file) match
    case Left(err) => return fail(err)
    case Right(p)  => p

  val target = chooseTarget(cfg.target, project.defaultTarget) match
    case Left(err) => return fail(err)
    case Right(t)  => t

  val provides = project.provides(target.name)

  // What the package says it cannot be built without, asked of the machine it is being built for.
  // This is the config's own half of `requires`, and it is answered here rather than in the analyzer
  // because it is a statement about the *project* — there is no source position to point at, and a
  // build that cannot mean anything should stop before it parses a line.
  val unmet = project.requires.filterNot(provides).toList.sorted

  if unmet.nonEmpty then
    return fail(s"this package requires '${unmet.head}', and '${target.name}' does not provide it")

  // Building the standard module against a prebuilt copy of itself is the one combination that
  // cannot mean anything: the declarations being compiled are the ones the artifact holds. Refused
  // rather than ignored, since ignoring it leaves a command line that reads as though it were used.
  if cfg.std && cfg.stdLib.isDefined then
    return fail("--std-lib compiles against the standard module, and 'build-lib --std' is what builds it")

  // Naming an artifact and refusing all of them at once has no reading either way round, and the two
  // spellings are near enough that a typo produces exactly this line. Refused rather than resolved by
  // precedence, since whichever precedence were chosen would silently discard half of what was asked.
  if cfg.noStdLib && cfg.stdLib.isDefined then
    return fail("--no-std-lib and --std-lib ask for different standard modules")

  // Which standard module this compilation is compiled against — an error if there is none, the same
  // as any other missing library.
  val Stdlib.Resolved(std, coreSymbols, coreArchive) = Stdlib.resolve(stdChoice(cfg), target) match
    case Left(err) => return fail(err)
    case Right(c)  => c

  // Building a library stops here — there is no program to link it into. An artifact is **for a
  // machine**, exactly as an rlib is, because half of it is compiled object code; the generic half
  // travels as trees because there is nothing to compile until a caller fixes its type arguments.
  if cfg.command == "build-lib" then return buildLibrary(cfg, sources, target, std)

  // Running the result is what makes `run` different from `build`, and only this machine can do
  // that — so a cross target is refused here rather than built and then failed to execute.
  //
  // `test` is the same and refuses it in `TestRunner`, where the rest of what it does is: it is one
  // compilation and one link like the two above, and then a run per test rather than a run.
  if cfg.command == "run" && !Target.host.contains(target) then
    return fail(s"'run' executes what it builds, and '${target.name}' is not this machine — " +
      s"use 'sysl build --target ${target.name}'")

  // A library reaches a compilation two ways, and neither is a second kind of input — both end as
  // **more modules**. Given as source its files carry the directory segments they were found under,
  // exactly as the program's do. Given as an artifact it arrives already parsed. Which one a path
  // names is read off the name, so a program that depends on a library need not know how it shipped.
  val (artifacts, roots) = cfg.libs.partition(LibraryArtifact.isArtifact)

  // Only the metadata is read here. The object half stays in the file and reaches the linker as the
  // archive it already is — there is nothing to unwrap, nothing to write to a temporary, and nothing
  // to clean up on the paths below that refuse the compilation.
  val unpacked =
    try artifacts.map(p => LibraryArtifact.metadataOf(p, readBytes(p)))
    catch case e: Exception => return fail(s"cannot read a library: ${e.getMessage}")

  unpacked.collectFirst { case Left(e) => e } match
    case Some(e) => return fail(e)
    case None    => ()

  val decoded =
    artifacts.zip(unpacked).collect { case (p, Right(meta)) => LibraryArtifact.read(p, meta, target) }

  decoded.collectFirst { case Left(e) => e } match
    case Some(e) => return fail(e)
    case None    => ()

  val collected =
    try roots.map(root => root -> Project.collect(root))
    catch case e: Exception => return fail(s"cannot read a library: ${e.getMessage}")

  collected.find(_._2.isEmpty) match
    case Some((root, _)) => return fail(s"$root holds no sysl source files")
    case None            => ()

  // What this project depends on, fetched and version-selected (`packages.md § 3`, `§ 5`). A project
  // with no `dependencies` resolves to itself and this costs nothing, which is what keeps
  // `sysl run hello.sysl` free of ceremony.
  val (dependencySources, packages) = dependencies(cfg, project) match
    case Left(err) => return fail(err)
    case Right(d)  => d

  val librarySources = collected.flatMap(_._2) ::: dependencySources
  val read           = decoded.collect { case Right(r) => r }
  val libraryTrees   = read.flatMap(_._1)

  // What the libraries already compiled, so this module declares those rather than defining them a
  // second time. Their bodies arrive from the archives at link time. The standard module's are in
  // here on the same footing as a named library's: what a prebuilt std buys a program is exactly
  // that its share of the library stops being emitted into every one.
  val precompiled = read.flatMap(_._2).toSet ++ coreSymbols

  // What the linker is handed: the artifacts themselves. A `.syslib` **is** an archive, so it goes on
  // the link line as it stands and the linker takes only the members that resolve something.
  val archives = artifacts ::: coreArchive.toList

  // A test build is its own compilation and branches before the one below, rather than sharing it:
  // it keeps the `@test` functions every other build drops, and it lowers a different entry point
  // (`Tests`). Everything up to here — the libraries, the standard module, the target — is the same,
  // which is why the branch is here and not at the top.
  if cfg.command == "test" then
    return TestRunner.run(cfg, librarySources ::: sources, libraryTrees, target, precompiled, std, archives)

  // Proving stops at the typed tree and never lowers, so it branches before the compilation below
  // (`17 §9`). It reads the tree before pruning and before `@ghost` erasure, because the predicates a
  // specification is written in are exactly what the lowering drops.
  if cfg.command == "prove" then
    return prove(cfg, librarySources ::: sources, libraryTrees, target, std, provides)

  // One compilation, whatever the subcommand does with it. The notes come back beside the IR
  // rather than being printed from inside the compiler, which has no business writing to a console.
  val compiled =
    Compiler.compiledWith(librarySources ::: sources, libraryTrees, target, precompiled, Some(std),
      provides, packages) match
    case Left(err) => return report(err)
    case Right(result) =>
      if cfg.explainEscapes then
        if result.notes.isEmpty then Console.err.println("no arrays were promoted to the heap")
        else result.notes.foreach(Console.err.println)
      result

  cfg.command match
    case "emit-llvm" =>
      stdout(compiled.ir); 0

    case "build" =>
      val exe = cfg.output.getOrElse(defaultOutputName(cfg.file))

      Toolchain.build(compiled.ir, exe, target, archives, cfg.optimize, compiled.links) match
        case Left(err) => fail(err)
        case Right(_)  => Console.err.println(s"wrote $exe"); 0

    case "run" =>
      val exe = createTempFile("sysl-", "")

      Toolchain.build(compiled.ir, exe, target, archives, cfg.optimize, compiled.links) match
        case Left(err) => Project.discard(exe); fail(err)
        case Right(_) =>
          val result = exec(exe :: cfg.programArgs)
          Project.discard(exe)
          stdout(result.stdout)
          if result.stderr.nonEmpty then Console.err.print(result.stderr)
          result.exitCode

    case other =>
      fail(s"unknown command '$other'")
}

/** `sysl prove` — the module as WhyML, and what Why3 made of it (`17 §9`).
 *
 * **A proof is not a build.** Nothing is emitted and nothing about `sysl build` changes, which is
 * `17 §1`: a module that fails to prove still compiles and still runs, with every check `16` and `17`
 * describe. What the prover buys is finding out before the program runs rather than at the trap.
 *
 * The exit status is Why3's, so a proof run is usable in a build script that wants to fail on an
 * undischarged goal.
 */
private def prove(cfg: Config, sources: List[Source], libraries: List[Program], target: Target,
                  std: Stdlib, provides: Set[String]): Int = {
  val (typed, ownModules) = Compiler.typedWith(sources, libraries, target, Some(std), provides) match
    case Left(err)  => return report(err)
    case Right(out) => out

  if cfg.overflow != "check" && cfg.overflow != "ignore" then
    return fail(s"--overflow is 'check' or 'ignore', and '${cfg.overflow}' is neither")

  val mlw = WhyML.generate(typed, moduleName(cfg.file), cfg.overflow == "check", ownModules) match
    case Left(err)  => return fail(err)
    case Right(out) => out

  if cfg.emitWhyML then { stdout(mlw); return 0 }

  Toolchain.why3Prove(mlw) match
    case Left(err) => fail(err)
    case Right((code, out)) =>
      stdout(out)
      if code == 0 then Console.err.println("every goal was discharged")
      code
}

/** A WhyML module's name, taken from the file the program was given. It is only a label — Why3 needs
 * one and sysl's module names hold dots a WhyML identifier may not.
 */
private def moduleName(path: String): String = {
  val base = path.split('/').last.takeWhile(_ != '.')

  if base.isEmpty then "Program" else base.filter(c => c.isLetterOrDigit || c == '_')
}

/** `sysl build-lib <path> -o <artifact>` — a library compiled once, into the two halves a program
 * links against (`LibraryArtifact`).
 *
 * The artifact is **for one machine**, exactly as an `.rlib` is, because half of it is object code.
 * The other half is the tree, which would travel anywhere: a generic has no compiled form until the
 * program that calls it fixes its type arguments, so it is monomorphized in that program instead.
 *
 * `--std` says the library being built is sysl's own standard module, which is the one thing an
 * ordinary compilation may not declare. It is written down rather than inferred from the module
 * names in the tree: guessing it would turn a clear refusal — *you cannot add to the module every
 * program is compiled against* — into an artifact that builds and then collides with the built-in
 * copy at whatever link tried to use it.
 */
private def buildLibrary(cfg: Config, sources: List[Source], target: Target, std: Stdlib): Int = {
  // Before the library is compiled rather than after. Compiling it is the slow part and the archiver
  // is not needed until the end, so discovering it late would make "there is no llvm-ar" a thing a
  // user waited for the whole build to be told.
  val ar = Toolchain.findAr(cfg.ar) match
    case Left(err)   => return fail(err)
    case Right(path) => path

  // The library's C files, which become members of the archive beside the object its own modules
  // compiled to (`15 §7`). Checked for a name collision here rather than after the build, for the
  // same reason the archiver is: the compile is the slow part, and a name that cannot be archived is
  // known before any of it has run.
  val native = Project.cSources(cfg.file)

  LibraryArtifact.collisions(native) match
    case Some(err) => return fail(err)
    case None      => ()

  LibraryArtifact.build(sources, target, if cfg.std then LibraryArtifact.std else Set.empty, Some(std),
                        native) match
    case Left(err) => report(err)
    case Right((ir, meta)) =>
      // The standard module's default output is the place a compilation looks for it, so that
      // building it and finding it are not two things to keep in agreement. Any other library
      // is named after the root it was built from, there being nowhere in particular it belongs.
      val out =
        cfg.output.getOrElse(
          if cfg.std then cfg.stdSearch.getOrElse(LibraryArtifact.stdDefault)
          else defaultOutputName(cfg.file) + LibraryArtifact.extension)

      Project.parentOf(out).foreach(createDirectories)

      // The members are named rather than left as whatever a temporary file was called, so an
      // artifact holds the same names wherever it was built and `ar t` shows a reader something they
      // can make sense of. A directory of our own is what makes naming them possible.
      val staging  = createTempDirectory("sysl-lib-")
      val code     = s"$staging/${LibraryArtifact.codeMember}"
      val metadata = s"$staging/${LibraryArtifact.metadataMember}"
      val objects  = native.map(s => s -> s"$staging/${LibraryArtifact.nativeMember(s)}")

      val outcome =
        for
          _ <- Toolchain.compileObject(ir, code, target, cfg.optimize)
          _ <- Toolchain.compileObject(LibraryArtifact.metadataIr(meta, target), metadata, target, cfg.optimize)
          // Each C file becomes its own member, so the linker pulls in a shim the same way it pulls
          // in anything else: because something left its symbol undefined.
          _ <- objects.foldLeft[Either[String, Unit]](Right(()))((so_far, entry) =>
                 so_far.flatMap(_ => Toolchain.compileC(entry._1.name, entry._2, target, cfg.optimize)))
          _ <- Toolchain.archive(code :: metadata :: objects.map(_._2), out, ar)
        yield ()

      (code :: metadata :: objects.map(_._2) ::: List(staging)).foreach(Project.discard)

      outcome match
        case Left(err) => fail(err)
        case Right(_)  => Console.err.println(s"wrote $out"); 0
}

/** The standard module this compilation gets, or why it has none.
 *
 * Two places, and they are governed by different rules. **A named one is taken as it is**: someone
 * who wrote `--std-lib` down is owed an error when what they named is not there or will not read,
 * rather than a different standard module built underneath them. That is the rule `Toolchain.findAr`
 * applies to a named archiver, and for the same reason.
 *
 * **The one at the default path is a cache, and is rebuilt when it is not usable.** See
 * `Stdlib.resolve`.
 *
 * `--no-std-lib` is the one way to the library **as source**, with no artifact in between. It is
 * what bootstrap needs — there is no released sysl to build the first artifact with — and what the
 * compiler's own unit tests take, running as they do in a tree where nothing has been built.
 * Reaching it is a deliberate act, and that is worth keeping distinct from the rebuild below: this
 * one compiles the library's source into the program, where the rebuild produces the artifact and
 * links it. Both read the same files off disk (`Std.root`); what differs is what they do with them.
 *
 * **`build-lib --std` is exempt, and has to be.** It is the command that produces the artifact, so
 * consulting one would be a deadlock with nothing to break it.
 */
/** Which standard module this command line asks for.
 *
 * The whole of what the driver contributes: the finding, the rebuilding and the checking are
 * `Stdlib.resolve`'s, because they are not properties of a command line — a test suite and a
 * documentation harness need the same answer to the same question, and this is the only place that
 * knows about `Config`.
 *
 * `build-lib --std` takes the source, and has to: it is the command that *produces* the artifact, so
 * consulting one would be a deadlock with nothing to break it.
 */
private def stdChoice(cfg: Config): Stdlib.Choice =
  if cfg.noStdLib || cfg.std then Stdlib.Choice.FromSource
  else
    cfg.stdLib match
      case Some(named) => Stdlib.Choice.Artifact(named)
      case None        => Stdlib.Choice.Default(cfg.stdSearch)


/** Which machine this invocation is for: the one it names, the one the project config names, or this
 * one. A machine sysl has no entry for is reported rather than guessed at — the guess would be a
 * module that looks right and is built for something else.
 *
 * `--target` beats the config, which beats the host. That is the order every other tool uses and the
 * only one that lets a project with a default still be cross-built from the command line without
 * editing a file.
 */
private def chooseTarget(named: Option[String], configured: Option[String]): Either[String, Target] =
  named.orElse(configured) match
    case Some(name) => Target.named(name)
    case None =>
      Target.host.toRight(
        "this machine is not one sysl knows, so a build has to name its target with --target " +
          "('sysl targets' lists them)")

/** The project config, read from the root this invocation was given (`packages.md § 1`).
 *
 * **A missing file is not an error.** A single-file program has no config and wants none, so what
 * comes back is the empty one — the same shape `13 §1` gives the anonymous root module. A file that
 * is *there* and will not read is a different thing entirely and stops the build: somebody wrote it,
 * and building while ignoring it would be building something other than what they asked for.
 *
 * The file is looked for beside the sources rather than searched for upwards. `13 § Open a` settles
 * that the driver is *given* a root rather than discovering one, and a search that walked upward
 * would make a build depend on directories above the one named.
 */
/** The project's dependencies, brought onto this machine and turned into what a compilation needs
 * from them (`packages.md § 3`, `§ 5`, `§ 9`).
 *
 * A dependency reaches a compilation the way a `--lib` source tree does — as **more modules** — and
 * that is the whole of the wiring. What is new beside it is the `Packages` table: each fetched
 * package's files are filed under a canonical prefix taken from its coordinate, and the names its
 * import lines write are read back through the manifest that named it.
 *
 * `sysl.sum` is written back where a package was fetched that no line covered, so the first build
 * after adding a dependency records what it got and every build after that is checked against it.
 * Writing it is not fatal if it fails: a read-only checkout should still build, and the alternative
 * is refusing to compile over a file that exists to be compared against next time.
 */
private def dependencies(cfg: Config, project: PackageConfig)
    : Either[String, (List[Source], Packages)] =
  if project.dependencies.isEmpty then Right((Nil, Packages.none))
  else
    val root = projectRoot(cfg.file)

    for
      cache <- Fetch.cacheRoot
      sums  <- readSums(root)
      graph <- Resolve.graph(root, project, sums, cache)
      files <- collectPackages(graph)
    yield
      if graph.sumsChanged then writeSums(root, graph.sums)
      files

/** Each fetched package's source, filed under the canonical prefix that keeps its module names
 * apart from every other package's.
 */
private def collectPackages(graph: Resolve.Graph): Either[String, (List[Source], Packages)] = {
  val fetched = graph.packages.filterNot(_.isRoot)

  try
    val each = fetched.map(p => p -> Project.collect(p.root))

    each.find(_._2.isEmpty) match
      case Some((p, _)) => Left(s"'${p.canonical}' holds no sysl source files")
      case None =>
        val owned = each.flatMap((p, sources) => sources.map(_ -> p.canonical)).toMap

        Right((
          each.flatMap(_._2),
          Packages(owned, graph.packages.map(p => p.canonical -> p.imports).toMap),
        ))
  catch case e: Exception => Left(s"cannot read a package: ${e.getMessage}")
}

private def readSums(root: String): Either[String, Sums] = {
  val path = s"$root/${Sums.FileName}"

  if !isFile(path) then Right(Sums.empty)
  else
    try Sums.read(readFile(path))
    catch case e: Exception => Left(s"cannot read $path: ${e.getMessage}")
}

private def writeSums(root: String, sums: Sums): Unit =
  try writeFile(s"$root/${Sums.FileName}", sums.render)
  catch
    case e: Exception =>
      Console.err.println(s"warning: cannot write ${Sums.FileName}: ${e.getMessage}")

/** The project root: the directory the driver was given, or the one holding the file it was given.
 *
 * `13 § Open a` settles that the driver is *given* a root rather than discovering one, so this never
 * searches upward — a build that walked up would depend on directories above the one named.
 */
private def projectRoot(file: String): String =
  if isDirectory(file) then file
  else
    val slash = math.max(file.lastIndexOf('/'), file.lastIndexOf('\\'))

    if slash >= 0 then file.substring(0, slash) else "."

private def readPackageConfig(file: String): Either[String, PackageConfig] = {
  val path = s"${projectRoot(file)}/${PackageConfig.FileName}"

  if !isFile(path) then Right(PackageConfig.empty)
  else
    try PackageConfig.read(readFile(path))
    catch case e: Exception => Left(s"cannot read $path: ${e.getMessage}")
}

/** Which build of sysl this is.
 *
 * On stdout rather than stderr, and alone on its line, because the first thing anyone does with a
 * version is read it out of a script — and a bug report that quotes it is the reason it exists at
 * all. The number comes from the build (`BuildInfo`), so a binary cannot claim a version it was not
 * cut at.
 */
private def printVersion(): Int = {
  stdout(s"sysl ${BuildInfo.version}\n")
  0
}

/** The usage text, for someone who asked for it.
 *
 * It was already reachable — an invocation naming no subcommand prints it, because `checkConfig`
 * refuses one — but only by *failing*, on stderr and with a non-zero status. `--help` is the first
 * thing anyone types at an unfamiliar command, and answering it with `Error: Unknown option --help`
 * is the worst first impression a compiler can make.
 *
 * Asked for, it is not an error: stdout, and a zero status, so `sysl --help | less` works and a
 * script that checks the status is not told something went wrong. That is the whole difference
 * between this and the failure path, which keeps stderr and its 2.
 *
 * `OParser.usage` renders it, so this is scopt's own text rather than a second copy to keep in step
 * with the parser above.
 */
private def printUsage(): Int = {
  stdout(OParser.usage(parser) + "\n")
  0
}

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
