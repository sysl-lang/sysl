package sh.sysl

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
 *   - `sysl weave <path>`          render a literate source as an HTML document
 *   - `sysl tangle <path>`         print the program a literate source holds
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
 * compiler — `<prefix>/share/sysl/library` beside the binary, or `library/` in a checkout (`Std.root`).
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
 * **`build-c` and `emit-header` are the exception and take the source unasked**, because what they
 * write is read by a C linker and a `.syslib` is not something one can be given. That is a property
 * of the consumer rather than a preference, which is why it is not left to a flag.
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
    verbose: Boolean = false,
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
    /** `--link-path` and `--include-path` — where on this machine to look for a library a `@link`
      * named, and for a header a carried `.c` includes or an `@include` names (`SearchPaths`). Lists
      * rather than single values because a build that needs one prefix usually needs the two it came
      * with, and the order given is the order searched.
      */
    linkPaths: List[String] = Nil,
    includePaths: List[String] = Nil,
    /** `--include-path <name>=<dir>` — the same flag naming which of a package's declared header
      * requirements the directory answers (`packages.md § 8`).
      *
      * Kept beside `includePaths` rather than instead of it: the directory goes to the C compiler
      * either way, and what the name adds is that a package which asked for it and got nothing is
      * refused by name instead of by clang.
      */
    namedIncludes: Map[String, String] = Map.empty,
    defines: List[String] = Nil,
    programArgs: List[String] = Nil,
    filter: Option[String] = None,
    failFast: Boolean = false,
    optimize: String = Toolchain.defaultOptimization,
    /** `build-c --header` — where the generated C header goes, when somewhere other than beside the
      * archive (`15 §12`).
      */
    header: Option[String] = None,
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
      cmd("build-c")
        .action((_, c) => c.copy(command = "build-c"))
        .text("compile a sysl module to a static archive and a C header, for an existing C " +
          "project to link against")
        .children(
          arg[String]("<path>").required().action((f, c) => c.copy(file = f)),
          opt[String]('o', "output").action((o, c) => c.copy(output = Some(o))).text("output archive path"),
          opt[String]("header")
            .action((h, c) => c.copy(header = Some(h)))
            .text("where to write the C header; defaults to the archive's path with a '.h' suffix"),
        ),
      cmd("emit-header")
        .action((_, c) => c.copy(command = "emit-header"))
        .text("print the C header for what a module exports")
        .children(arg[String]("<path>").required().action((f, c) => c.copy(file = f))),
      cmd("weave")
        .action((_, c) => c.copy(command = "weave"))
        .text("render a literate source as an HTML document, with its prose set, its program " +
          "highlighted and its mathematics typeset")
        .children(
          arg[String]("<path>").required().action((f, c) => c.copy(file = f)),
          opt[String]('o', "output").action((o, c) => c.copy(output = Some(o)))
            .text("where to write the document; a directory when the path holds several sources, " +
              "and standard output by default"),
        ),
      cmd("tangle")
        .action((_, c) => c.copy(command = "tangle"))
        .text("print the program a literate source holds, with the prose stripped")
        .children(
          arg[String]("<path>").required().action((f, c) => c.copy(file = f)),
          opt[String]('o', "output").action((o, c) => c.copy(output = Some(o)))
            .text("where to write the program; defaults to standard output"),
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
          opt[Unit]("std")
            .action((_, c) => c.copy(std = true))
            .text("this tree is sysl's own standard module, which the compiler otherwise supplies"),
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
      opt[Unit]('v', "verbose")
        .action((_, c) => c.copy(verbose = true))
        .text("report what the build decided: which standard module was used and why, the files " +
          "read, the search paths, and the clang and linker command lines"),
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
      opt[String]("link-path")
        .unbounded()
        .action((d, c) => c.copy(linkPaths = c.linkPaths :+ d))
        .text("a directory to look in for a library a 'link' directive named — for one a package " +
          "manager installed outside the toolchain's own prefix; may be given more than once"),
      opt[String]("include-path")
        .unbounded()
        .action { (d, c) =>
          SearchPaths.namedInclude(d) match
            case None => c.copy(includePaths = c.includePaths :+ d)
            case Some((name, dir)) =>
              c.copy(includePaths = c.includePaths :+ dir, namedIncludes = c.namedIncludes + (name -> dir))
        }
        .text("a directory to look in for a header the C beside a module includes, or one an " +
          "'@include' names for a 'c const' block; the other half of --link-path, and needed by the " +
          "same bindings; may be given more than once. Written '<name>=<dir>' it also answers the " +
          "header requirement a package declared under that name"),
      opt[String]('D', "define")
        .unbounded()
        .action((d, c) => c.copy(defines = c.defines :+ d))
        .text("a macro the C beside a module is compiled with, and a 'c const' block's headers are " +
          "read under, as 'NAME' or 'NAME=value' — what a host C project configures its own headers " +
          "with, and which finding the header does not supply; may be given more than once"),
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

  // The files, in the order the walk found them, which is the order the compiler reads them in.
  if cfg.verbose then
    trace(s"${sources.length} source file(s) under ${cfg.file}")
    sources.foreach(src => trace(s"  read ${src.name}"))

  // Rendering is a **source-level** job and stops here, above everything a compilation needs. It
  // asks for no target, no standard module and no library, which is not a shortcut but the whole
  // reason the command is usable: a package's prose is worth reading on a machine that could not
  // build it, and a document that could only be produced by a successful build would be missing
  // exactly when somebody wanted it.
  if cfg.command == "weave" then return weave(cfg, sources)
  if cfg.command == "tangle" then return tangle(cfg, sources)

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

  // Compiling the standard module against a prebuilt copy of itself is the one combination that
  // cannot mean anything: the declarations being compiled are the ones the artifact holds. Refused
  // rather than ignored, since ignoring it leaves a command line that reads as though it were used.
  //
  // Named by what the two flags say rather than by the command that used to be the only one able to
  // say it — `test` takes `--std` as well now, and a message answering it with `build-lib` would be
  // about a command the reader had not typed.
  if cfg.std && cfg.stdLib.isDefined then
    return fail("--std says this tree is the standard module and --std-lib names a prebuilt one to " +
      "compile against — a compilation cannot be both")

  // Naming an artifact and refusing all of them at once has no reading either way round, and the two
  // spellings are near enough that a typo produces exactly this line. Refused rather than resolved by
  // precedence, since whichever precedence were chosen would silently discard half of what was asked.
  if cfg.noStdLib && cfg.stdLib.isDefined then
    return fail("--no-std-lib and --std-lib ask for different standard modules")

  // Refused rather than ignored, because it is the shape of the request that is wrong: an archive a C
  // project links cannot refer to a `.syslib`, so there is nothing a named one could do here but be
  // silently discarded. Said now rather than at the C project's link, where the symptom is an
  // undefined `sysl$` symbol and the cause is a flag on a command that ran successfully.
  if cLibrary(cfg.command) && cfg.stdLib.isDefined then
    return fail(s"${cfg.command} compiles the standard module into what it writes, since a C link " +
      "line cannot carry a '.syslib' — so --std-lib has nothing to name here")

  // A library reaches a compilation two ways, and neither is a second kind of input — both end as
  // **more modules**. Given as source its files carry the directory segments they were found under,
  // exactly as the program's do. Given as an artifact it arrives already parsed. Which one a path
  // names is read off the name, so a program that depends on a library need not know how it shipped.
  //
  // **Split here rather than where the two halves are read**, because both the fetch and the allocator
  // below have to consult the source roots, and both are settled above the standard module. Splitting
  // is a pure function of what was on the command line — no file is opened by it — so the only thing
  // moving it costs is that the reader meets it earlier than the code that uses it most.
  val (artifacts, roots) = cfg.libs.partition(LibraryArtifact.isArtifact)

  // What this compilation depends on, fetched and version-selected (`packages.md § 3`, `§ 5`). A
  // project with no `dependencies` and no declaring source root resolves to itself and this costs
  // nothing, which is what keeps `sysl run hello.sysl` free of ceremony.
  //
  // **Above the standard module rather than below it, because the allocator is settled here** and
  // every artifact this compilation reads is built for one allocator — the standard module's included.
  // It is still below every flag check, so a command line that cannot mean anything is refused without
  // reaching the network.
  //
  // `build-lib` fetches nothing, which is the invariant the early return below preserves: a command
  // that compiles one tree into an artifact for one machine should not reach the network, so a
  // `dependencies` block is refused by `buildLibrary` rather than acted on here.
  val fetched =
    if cfg.command == "build-lib" then PackageSources.none
    else
      dependencies(cfg, project, roots) match
        case Left(err) => return fail(err)
        case Right(d)  => d

  // The pair of C functions this whole program allocates through (`packages.md § 13`). A package that
  // brings its own heap says so, and saying so settles it for the program — which is the only shape
  // that can work, because there is one heap and whoever holds the last reference to something is who
  // frees it (`03`). Two packages naming different pairs is refused here rather than at the link,
  // where it would not be refused at all: both symbols resolve, and the program simply gives one
  // allocator's storage back to another.
  //
  // The project's own declaration is folded in beside the fetched ones, so an application with its own
  // heap and no dependency that has one is answered by the same rule — and so is a **library** being
  // built into an artifact, whose own is the whole answer because it has no dependencies to consult.
  //
  // **And a `--lib` source root's, which is the third road and used to be the silent one.** § 13 makes
  // this a property of the *package* rather than of how the package was reached, so a package that
  // brings its own heap brings it whichever way a build names it. Reached by coordinate it was
  // adopted; handed over as the same directory it was ignored, and what shipped was one program with
  // two heaps and nothing said about it at any point.
  //
  // An **artifact** is not consulted here and is not an omission: its object half is already compiled
  // against a pair, so there is nothing to adopt, and `LibraryArtifact.read` refuses one that does not
  // match rather than quietly re-deciding. Source is the only form where adopting is a thing that can
  // be done at all.
  val fromRoots = libAllocators(roots) match
    case Left(err)   => return fail(err)
    case Right(pair) => pair

  val allocator = Allocator.choose(
    project.allocator.map("this project" -> _).toList ::: fetched.allocators ::: fromRoots) match
    case Left(err) => return fail(err)
    case Right(a)  => a

  // Which standard module this compilation is compiled against — an error if there is none, the same
  // as any other missing library.
  val Stdlib.Resolved(std, coreSymbols, coreArchive) =
    Stdlib.resolve(stdChoice(cfg), target, allocator) match
    case Left(err) => return fail(err)
    case Right(c)  => c

  // **Which standard module this compilation got, and by which route.** An artifact that was read
  // and one that was rejected and rebuilt reach here looking identical, and the difference is the
  // first thing anybody diagnosing a stale library wants: `Stdlib.resolve` announces a *rebuild* on
  // its own, so what is added here is the quiet case.
  if cfg.verbose then
    coreArchive match
      case Some(archive) => trace(s"standard module linked from $archive")
      case None          => trace("standard module compiled from source, not linked from an artifact")

  // Running the result is what makes `run` different from `build`, and only this machine can do
  // that — so a cross target is refused here rather than built and then failed to execute.
  //
  // `test` is the same and refuses it in `TestRunner`, where the rest of what it does is: it is one
  // compilation and one link like the two above, and then a run per test rather than a run.
  if cfg.command == "run" && !Target.host.contains(target) then
    return fail(s"'run' executes what it builds, and '${target.name}' is not this machine — " +
      s"use 'sysl build --target ${target.name}'")

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
    artifacts.zip(unpacked).collect { case (p, Right(meta)) =>
      LibraryArtifact.read(p, meta, target, allocator) }

  decoded.collectFirst { case Left(e) => e } match
    case Some(e) => return fail(e)
    case None    => ()

  val collected =
    try roots.map(root => root -> Project.collect(root))
    catch case e: Exception => return fail(s"cannot read a library: ${e.getMessage}")

  collected.find(_._2.isEmpty) match
    case Some((root, _)) => return fail(s"$root holds no sysl source files")
    case None            => ()

  // Building a library stops here — there is no program to link it into. An artifact is **for a
  // machine**, exactly as an rlib is, because half of it is compiled object code; the generic half
  // travels as trees because there is nothing to compile until a caller fixes its type arguments.
  //
  // **Below the `--lib` resolution and above the fetch, which is the whole of what `build-lib` was
  // missing.** A library built on another library needs that library's declarations to compile at
  // all, and the two ways of naming one — a source root and a `.syslib` — are resolved just above.
  // What it does not do is *fetch*: a command that compiles one tree into an artifact for one
  // machine should not reach the network, so a `dependencies` block is refused here rather than
  // acted on, and `buildLibrary` says so in as many words.
  if cfg.command == "build-lib" then
    // Asked here rather than with the others below, because this return is above them and the reason
    // they are asked applies squarely: `buildLibrary` compiles this package's C into the artifact,
    // so a header it cannot find fails inside clang exactly as it would for a `build`.
    //
    // **Its own manifest and nothing else, which is narrower than every other command and is what
    // this command actually does.** `Project.cSources(cfg.file)` is the whole of the C compiled here
    // — a `--lib` source root's C is not, because that root's C belongs in that root's own artifact,
    // and a `dependencies` block is refused outright a few lines into `buildLibrary` rather than
    // fetched. Charging for either would be charging for a header this command will never open.
    unmetHeaders(project, Nil, cfg.namedIncludes.keySet) match
      case Some(err) => return fail(err)
      case None      => ()

    return buildLibrary(cfg, sources, target, std, project,
                        collected.flatMap(_._2), decoded.collect { case Right(r) => r._1 }.flatten,
                        allocator)

  val librarySources = collected.flatMap(_._2) ::: fetched.sources
  val packages       = fetched.packages
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

  // The C beside the sysl, of every tree this compilation walked — the project's own, each `--lib`
  // source root's, and each package's (`15 §7`, `NativeSources`). An artifact is not among them: a
  // `.syslib` carries its C already compiled, as archive members.
  //
  // Compiled only where something is about to be linked. `emit-llvm` prints IR and `prove` stops at
  // the typed tree, and neither has a use for an object file — running clang for one would be work
  // whose result is thrown away.
  // Where this machine keeps what the toolchain was not told the location of (`SearchPaths`). One
  // value rather than two lists threaded separately, because the two halves are one setting: a
  // binding to a library outside the default prefix needs its headers to compile and its archive to
  // link, and a build given only one of them fails at whichever step comes first.
  val paths = SearchPaths(cfg.linkPaths, cfg.includePaths, cfg.defines)

  if cfg.verbose then
    for lib <- cfg.libs do trace(s"library: $lib")
    for dir <- cfg.linkPaths do trace(s"link path: $dir")
    for dir <- cfg.includePaths do trace(s"include path: $dir")
    for d <- cfg.defines do trace(s"define: $d")

    // Said even when it is libc's, because "which allocator is this program using" is a question with
    // an answer whatever the answer is, and one that is silent until a package changes it reads as a
    // setting that does not exist.
    val who = (project.allocator.map("this project" -> _).toList ::: fetched.allocators ::: fromRoots)
      .collectFirst { case (name, a) if a == allocator => name }

    trace(s"allocator: ${allocator.alloc} / ${allocator.free}" +
      who.fold(" (the C default)")(n => s" (named by $n)"))

  // What the packages said their C has to be able to find, asked of what this command line supplied
  // (`packages.md § 8`). Answered here rather than left to clang because a header that is not there
  // fails inside a compiler that has never heard of sysl: what comes back is `'lwip/tcp.h' file not
  // found`, which names neither the package that wanted it nor the flag that would have supplied it.
  //
  // Asked only where C is going to be compiled. The requirement exists so that a tree's C compiles,
  // so a command that compiles none has nothing unmet — and refusing `emit-llvm` or `prove` over a
  // path they would never open would be charging for something they do not do. `build-lib` compiles
  // C too and is asked at its own return above, for its own manifest only.
  //
  // A `--lib` **source root** is asked too, and is the one road that used to fall through. A package
  // reached through `dependencies` is checked because its manifest came back with the graph, and one
  // reached as a `.syslib` needs no header at all — its `c const` was lowered when the artifact was
  // built. Handed the same package as a directory, the driver read its `.sysl` and nothing else, so
  // the requirement it had written down went unasked and clang answered instead.
  if links(cfg.command) || cLibrary(cfg.command) then
    val fromLibs = libHeaderNeeds(roots) match
      case Left(err)    => return fail(err)
      case Right(needs) => needs

    unmetHeaders(project, fetched.needs ::: fromLibs, cfg.namedIncludes.keySet) match
      case Some(err) => return fail(err)
      case None      => ()

  val native =
    if links(cfg.command) then
      NativeSources.build(NativeSources.of(cfg.file :: roots ::: fetched.roots), target, cfg.optimize,
        paths, cfg.verbose) match
        case Left(err)    => return fail(err)
        case Right(built) => built
    else NativeSources.none

  // A test build is its own compilation and branches before the one below, rather than sharing it:
  // it keeps the `@test` functions every other build drops, and it lowers a different entry point
  // (`Tests`). Everything up to here — the libraries, the standard module, the target — is the same,
  // which is why the branch is here and not at the top.
  if cfg.command == "test" then
    val status =
      TestRunner.run(cfg, librarySources ::: sources, libraryTrees, target, precompiled, std, archives,
        native.objects, paths, allocator)

    native.scratch.foreach(Project.discard)
    return status

  // Proving stops at the typed tree and never lowers, so it branches before the compilation below
  // (`17 §9`). It reads the tree before pruning and before `@ghost` erasure, because the predicates a
  // specification is written in are exactly what the lowering drops.
  if cfg.command == "prove" then
    return prove(cfg, librarySources ::: sources, libraryTrees, target, std, provides)

  // One compilation, whatever the subcommand does with it. The notes come back beside the IR
  // rather than being printed from inside the compiler, which has no business writing to a console.
  val compiled =
    Compiler.compiledWith(librarySources ::: sources, libraryTrees, target, precompiled, Some(std),
      provides, packages, entryPoint = !cLibrary(cfg.command), paths, allocator) match
    case Left(err) => return report(err)
    case Right(result) =>
      if cfg.explainEscapes then
        if result.notes.isEmpty then Console.err.println("no arrays were promoted to the heap")
        else result.notes.foreach(Console.err.println)
      result

  val status = cfg.command match
    case "emit-llvm" =>
      stdout(compiled.ir); 0

    case "emit-header" =>
      stdout(CHeader.render(compiled.exports, project.name.getOrElse(Project.nameOf(cfg.file)))); 0

    case "build-c" =>
      buildForC(cfg, compiled, target, project.name, cfg.file :: roots ::: fetched.roots)

    case "build" =>
      val exe = cfg.output.getOrElse(defaultOutput(cfg.file, project.name))

      Toolchain.build(compiled.ir, exe, target, archives, cfg.optimize, compiled.links, native.objects,
        paths, cfg.verbose) match
        case Left(err) => fail(err)
        case Right(_)  => Console.err.println(s"wrote $exe"); 0

    case "run" =>
      val exe = createTempFile("sysl-", "")

      Toolchain.build(compiled.ir, exe, target, archives, cfg.optimize, compiled.links, native.objects,
        paths, cfg.verbose) match
        case Left(err) => Project.discard(exe); fail(err)
        case Right(_) =>
          // `runProgram` and not `exec`, and the difference is the whole of what this command is
          // for. `exec` runs a **tool** — `clang`, `git`, `llvm-ar` — whose output is a value the
          // compiler goes on to inspect, so it closes the child's input and hands back two strings
          // once the child has finished. A program the user asked to run is the opposite on every
          // count: its input is the user's, and what it writes is for the user to read as it is
          // written. Running one through `exec` handed it end-of-input at once, so `examples/wc.sysl`
          // read nothing from a pipe and a console exited after its banner.
          val status = runProgram(exe :: cfg.programArgs)

          Project.discard(exe)
          status

    case other =>
      fail(s"unknown command '$other'")

  // The objects were a temporary of this build, whichever way it went — a link that failed leaves
  // them as surely as one that worked.
  native.scratch.foreach(Project.discard)
  status
}

/** Whether a subcommand ends at the linker, which is what decides whether a tree's C is worth
 * compiling. `emit-llvm` and `prove` do not, and `build-lib` never reaches here — it archives its
 * own C rather than linking it.
 */
private def links(command: String): Boolean = command == "build" || command == "run" || command == "test"

/** The header requirements nothing on this command line answered, as the one line a build stops on
 * (`packages.md § 8`).
 *
 * ==Why the message is this long==
 *
 * Every other requirement in the file is answered by something the reader already has: a capability
 * is provided by the target or it is not, and there is nothing to go and do. This one is answered by
 * a path on a machine the package has never seen, so the reader is being asked to find something —
 * and a refusal that only names what is missing leaves them to work out *what* it is, *where* it
 * lives and *how* to say so. All three are known here: the package wrote the first two down, and the
 * third is the flag this very check reads.
 *
 * The package's own prose is quoted rather than paraphrased. It is the only part of this nothing in
 * the compiler could have written, and it is the part that says which library is meant.
 *
 * ==One at a time, in the order they were declared==
 *
 * The first unmet requirement stops the build, as `requires` already does for a capability. A
 * consumer satisfying them one flag at a time is the same walk either way, and a list of four would
 * be four things to look up before anything can be tried.
 */
/** What the `--lib` **source roots** declare they need headers for (`packages.md § 8`).
 *
 * **This is the one road a declared requirement used to fall through.** The other two are already
 * answered and neither needed anything: a package reached through `dependencies` arrives with its
 * manifest as part of the graph, and one reached as a `.syslib` never needs a header at all, because
 * `LibraryArtifact` lowers a `c const` to its measured value before writing. Given the very same
 * package as a **directory**, though, the driver collected its `.sysl` files and opened nothing else
 * — so the sentence its author wrote for exactly this moment was never read, and clang's
 * `'cairo.h' file not found` answered in its place, naming neither the package nor the flag.
 *
 * **Only `requires { headers }` is taken from the manifest, and that is deliberate.** `--lib` names
 * a *source root*, which need not be a package at all, and a root that is not one has nothing to say
 * here. Reading the rest of what a manifest can declare is a different question with a real cost —
 * an allocator taken silently from a `--lib` root is the mixed heap `packages.md § 13` exists to
 * prevent — so it is left alone rather than guessed at.
 *
 * **The root is named as the reader wrote it**, which is a path here where it is a coordinate for a
 * dependency. Both are the thing the person reading the message typed and can go and look at; the
 * package's own declared name is neither.
 *
 * A manifest that will not parse stops the build rather than being skipped. That matches what a
 * dependency's does, and the alternative is silently dropping a requirement on the one path this
 * whole check exists to close.
 */
/** The allocator each `--lib` **source root** declares, named by the root as the reader wrote it.
 *
 * `packages.md § 13` makes the allocator a property of the *package*: one that brings its own heap
 * settles the question for the whole program, because there is one heap and whoever holds the last
 * reference is who frees it. That is a claim about the package rather than about the road it arrived
 * by, so a package reached as a directory answers exactly as the same package reached by coordinate.
 *
 * **Until this existed the two roads disagreed in silence**, which is the worst way for them to
 * disagree: a coordinate adopted the pair and a source root ignored it, so one program allocated
 * through two heaps and nothing said so at any point. Only the `-v` line differed, and the failure it
 * predicts is a `free` of storage the other allocator owns.
 *
 * **An artifact is deliberately not here.** Its object half is already compiled against a pair, so
 * there is nothing to adopt — `LibraryArtifact.read` refuses one that disagrees with the program
 * rather than re-deciding, and that refusal is a physical constraint rather than a policy this could
 * have shared.
 *
 * A root with no manifest has nothing to declare, exactly as for the header requirements beside this,
 * and one whose manifest will not parse stops the build rather than being skipped.
 */
private def libAllocators(roots: List[String]): Either[String, List[(String, Allocator)]] =
  roots.foldLeft[Either[String, List[(String, Allocator)]]](Right(Nil)) { (acc, root) =>
    for
      seen   <- acc
      config <- readPackageConfig(root)
    yield seen ::: config.allocator.map(root -> _).toList
  }

private def libHeaderNeeds(roots: List[String]): Either[String, List[HeaderNeed]] =
  roots.foldLeft[Either[String, List[HeaderNeed]]](Right(Nil)) { (acc, root) =>
    for
      seen   <- acc
      config <- readPackageConfig(root)
    yield seen ::: config.headers.toList.sortBy(_._1).map((name, why) => HeaderNeed(root, name, why))
  }

private def unmetHeaders(project: PackageConfig, fromPackages: List[HeaderNeed],
                         supplied: Set[String]): Option[String] = {
  val own  = project.headers.toList.sortBy(_._1).map((name, why) => HeaderNeed("this project", name, why))
  val need = own ::: fromPackages

  need.find(n => !supplied.contains(n.name)).map { n =>
    s"${n.who} needs the '${n.name}' headers and nothing supplied them — ${n.why}. Say where they " +
      s"are with '--include-path ${n.name}=<dir>'"
  }
}

/** Whether a subcommand is producing something a **C project** links, which is what decides that the
 * module is emitted with no entry point (`15 §12`).
 *
 * Both commands here are one compilation with two things read off it, which is why they share this
 * rather than each answering for itself: `emit-header` prints what `build-c` writes beside the
 * archive, and a header describing a different compilation from the archive it sits next to is the
 * one failure a C project cannot diagnose.
 */
private def cLibrary(command: String): Boolean = command == "build-c" || command == "emit-header"

/** `sysl build-c` — the static archive and the C header a C project is handed (`15 §12`).
 *
 * **This is `build-lib`'s shape with a different destination.** The compilation is the ordinary one
 * rather than a library build, because what is wanted is a module lowered for *this* target with its
 * calls resolved, not a tree of declarations somebody else will compile. What differs from `build`
 * is the entry point, which `cLibrary` above suppressed, and the ending: an archive rather than a
 * link.
 *
 * **The standard module is compiled into the archive**, always, and this is the one thing `build-c`
 * decides differently from every other command. A `.syslib` is not something a C project can link:
 * an archive referring to code no reachable file contains fails at the C link naming `sysl$prints`,
 * which is a symbol its author has no way to place. So the archive stands alone or it is not an
 * artifact. See `stdChoice`.
 *
 * **What is NOT in the archive is what this build's own libraries supply** — `libm`, and whatever
 * `@link` named — and the report says so rather than leaving it to be discovered at the C project's
 * link. Those are libraries the author chose and can be given to a linker, which is exactly the
 * distinction the standard module fails.
 *
 * **`roots` is every tree the compilation walked, not the project's own**, and it is a parameter for
 * that reason. `15 §7` gives a source root named with `--lib` and a package a `dependencies` block
 * brought in the same answer as the project itself: their C is compiled and reaches the link. This
 * command reads that table exactly as `NativeSources` does for the commands that link, and the
 * archive is where "the link line" lands when the consumer is a C project — an object the archive
 * left out is one the C author has no way to supply and no way to hear about, since the sysl half
 * compiled cleanly and only the C project's linker ever notices.
 */
/** The literate sources of a tree, or the reason there is nothing to render.
 *
 * **The ordinary `.sysl` files are passed over rather than refused**, because a directory holding
 * both is the normal shape of a literate module — `library/sysl/regex` is five `.lsysl` files and its
 * tests are not — and a command that refused the tree would be unusable on the very trees it is for.
 * What *is* refused is a tree with no literate source at all: there the request cannot be granted
 * however it is read, and saying so beats writing an empty document.
 */
private def literateSources(cfg: Config, sources: List[Source], verb: String): Either[String, List[Source]] = {
  val literate = sources.filter(src => Literate.named(src.name))

  if literate.isEmpty then
    Left(s"${cfg.file} holds no '${Literate.Extension}' files — '$verb' reads the prose a " +
      "literate source is written around, and an ordinary sysl program has none")
  else Right(literate)
}

/** `weave`: each literate source of a tree rendered as its own HTML document (`Weave`).
 *
 * **One source is one document, rather than a tree being joined into one.** A woven document is a
 * leaf artifact somebody opens, so the unit that makes sense is the file that was written; a tree
 * rendered end to end would put five modules under one title and one heading numbering. When the
 * path holds several sources, `-o` therefore names a **directory**, and each document is written
 * into it under its own name.
 */
private def weave(cfg: Config, sources: List[Source]): Int =
  literateSources(cfg, sources, "weave") match
    case Left(err) => fail(err)
    case Right(literate) =>
      val rendered = literate.map(src => src -> Weave.render(src))

      rendered.collectFirst { case (_, Left(err)) => err } match
        case Some(err) => fail(err)
        case None =>
          val documents = rendered.collect { case (src, Right(html)) => src -> html }

          (cfg.output, documents) match
            case (None, (_, html) :: Nil) => stdout(html); 0

            // Several documents and nowhere to put them: they cannot be told apart on a stream, and
            // concatenating them would produce one file with several `<html>` elements in it.
            case (None, _) =>
              fail(s"${cfg.file} holds ${documents.length} literate sources — 'weave' writes one " +
                "document each, so give '-o' a directory to write them into")

            case (Some(path), (_, html) :: Nil) => write(path, html)

            case (Some(dir), many) =>
              try
                createDirectories(dir)

                many.foldLeft(0) { (code, entry) =>
                  val (src, html) = entry

                  if code != 0 then code else write(s"$dir/${Weave.documentName(src.name)}", html)
                }
              catch case e: Exception => fail(s"cannot write $dir: ${e.getMessage}")

/** `tangle`: the program a literate source holds, with the prose stripped (`Literate.tangle`).
 *
 * **This is what the compiler reads**, and that is the whole of why it is worth a command. When a
 * literate file misbehaves — a block indented that should not have been, a fence that swallowed a
 * function — the question is always what tangling produced, and until now there was no way to ask.
 * It also hands the program to anything that does not know the format: a tool, a paste, a bug
 * report.
 */
private def tangle(cfg: Config, sources: List[Source]): Int =
  literateSources(cfg, sources, "tangle") match
    case Left(err) => fail(err)
    case Right(literate) =>
      val tangled = literate.map(Literate.tangle)

      tangled.collectFirst { case Left(err) => err } match
        case Some(err) => fail(err)
        case None =>
          // A tree's programs are joined rather than written separately, because unlike a document
          // they are all the same kind of thing and a module's source is read in one piece.
          val text = tangled.collect { case Right(src) => src.text }.mkString("\n")

          cfg.output match
            case Some(path) => write(path, text)
            case None       => stdout(text); 0

/** A rendered thing written where it was asked for, or the reason it could not be. */
private def write(path: String, text: String): Int = {
  Project.parentOf(path).foreach(createDirectories)

  try
    writeFile(path, text)
    Console.err.println(s"wrote $path")
    0
  catch case e: Exception => fail(s"cannot write $path: ${e.getMessage}")
}

private def buildForC(cfg: Config, compiled: Compiled, target: Target, named: Option[String],
                      roots: List[String]): Int = {
  // Before the compile rather than after, exactly as `build-lib` does it: the archiver is not needed
  // until the end, so discovering it late would make "there is no llvm-ar" a thing somebody waited
  // the whole build for.
  val ar = Toolchain.findAr(cfg.ar) match
    case Left(err)   => return fail(err)
    case Right(path) => path

  // One flat list, because an archive is one flat namespace. `nativeMember` names a member after the
  // path inside its own tree, so two trees can still land on one name — which `collisions` reports,
  // rather than `ar r` replacing by name and shipping an archive quietly missing half of what one of
  // them defined.
  val native = roots.flatMap(Project.cSources)

  LibraryArtifact.collisions(native) match
    case Some(err) => return fail(err)
    case None      => ()

  val out    = cfg.output.getOrElse(defaultOutput(cfg.file, named, ".a"))
  val header = cfg.header.getOrElse(s"$out.h")

  Project.parentOf(out).foreach(createDirectories)
  Project.parentOf(header).foreach(createDirectories)

  // Named members for `build-lib`'s reason: an archive holds the same names wherever it was built,
  // and `ar t` then shows a reader something they can make sense of.
  val staging = createTempDirectory("sysl-c-")
  val code    = s"$staging/${LibraryArtifact.codeMember}"
  val objects = native.map(s => s -> s"$staging/${LibraryArtifact.nativeMember(s)}")

  val outcome =
    for
      _ <- Toolchain.compileObject(compiled.ir, code, target, cfg.optimize)
      _ <- objects.foldLeft[Either[String, Unit]](Right(()))((so_far, entry) =>
             so_far.flatMap(_ => Toolchain.compileC(entry._1.name, entry._2, target, cfg.optimize,
               SearchPaths(cfg.linkPaths, cfg.includePaths, cfg.defines), cfg.verbose)))
      _ <- Toolchain.archive(code :: objects.map(_._2), out, ar)
    yield ()

  (code :: objects.map(_._2) ::: List(staging)).foreach(Project.discard)

  outcome match
    case Left(err) => fail(err)
    case Right(_)  =>
      writeFile(header, CHeader.render(compiled.exports, named.getOrElse(Project.nameOf(cfg.file))))
      Console.err.println(s"wrote $out")
      Console.err.println(s"wrote $header")

      // A build that exported nothing produced an archive with no way in, and the author almost
      // certainly meant to mark something. It is a warning rather than a refusal because an archive
      // of pure C shims is a real thing to want, and because the header says the same on its face.
      if compiled.exports.isEmpty then
        Console.err.println("sysl: warning: nothing in this module is marked '@export', so the " +
          "archive has no C entry point")

      // The libraries this object still needs, which the C project's link line has to carry. Said
      // here because nothing downstream will say it: an unresolved sysl symbol at that link reads
      // as a missing definition rather than as a missing archive.
      if compiled.links.nonEmpty then
        Console.err.println(s"sysl: link this against: ${compiled.links.mkString(", ")}")

      0
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
 *
 * **`--lib` names what this library is built ON, and it is the only way one reaches here.** A
 * package built on another — `sdl3-ttf` on `sdl3` — needs that one's declarations to compile at all,
 * and gets them as a source root or as a `.syslib`, exactly as a program does. What it never does is
 * *fetch*: `dependencies` is a coordinate to resolve over the network, and a command whose whole job
 * is to compile one tree into an artifact for one machine should not be the thing that goes looking.
 * So a package that declares dependencies and is handed no library is refused, with the flag that
 * would have answered it named — rather than compiled until the analyzer reports a module nobody
 * mentioned.
 */
private def buildLibrary(cfg: Config, sources: List[Source], target: Target, std: Stdlib,
                         project: PackageConfig, libraries: List[Source],
                         libraryTrees: List[Program], allocator: Allocator): Int = {
  val named = project.name

  // Said before anything is compiled, and said as a *request* the driver cannot meet rather than as
  // a missing module: the reader wrote a `dependencies` block that every other command honours, and
  // what they are owed is which command they are running and which flag stands in for it here.
  if project.dependencies.nonEmpty && cfg.libs.isEmpty then
    val labels = project.dependencies.map(d => s"'${d.label}'").mkString(", ")

    return fail(s"build-lib compiles the tree it is given and fetches nothing, so this package's " +
      s"dependencies reach it through --lib rather than through 'dependencies' — supply $labels as " +
      "a source root or as a '.syslib' built from one")

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
                        native, SearchPaths(cfg.linkPaths, cfg.includePaths, cfg.defines),
                        libraries, libraryTrees, allocator) match
    case Left(err) => report(err)
    case Right((ir, meta)) =>
      // The standard module's default output is the place a compilation looks for it, so that
      // building it and finding it are not two things to keep in agreement. Any other library is
      // named after the root it was built from and written inside it, which is the same rule a
      // build's executable follows and for the same reason: the root is the one place that names
      // the library without depending on where the caller happened to be standing.
      val out =
        cfg.output.getOrElse(
          if cfg.std then cfg.stdSearch.getOrElse(LibraryArtifact.stdDefault(target, allocator))
          else defaultOutput(cfg.file, named, LibraryArtifact.extension))

      Project.parentOf(out).foreach(createDirectories)

      // The members are named rather than left as whatever a temporary file was called, so an
      // artifact holds the same names wherever it was built and `ar t` shows a reader something they
      // can make sense of. A directory of our own is what makes naming them possible.
      val staging  = createTempDirectory("sysl-lib-")

      // The one place this command writes outside the artifact it was asked for, so it is the one
      // place a reader has to be told about: a build interrupted between here and the cleanup below
      // leaves the directory named here, and nothing else in the run would say where it went.
      if cfg.verbose then trace(s"members staged in $staging")

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
                 so_far.flatMap(_ => Toolchain.compileC(entry._1.name, entry._2, target, cfg.optimize,
                   SearchPaths(cfg.linkPaths, cfg.includePaths, cfg.defines))))
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
 *
 * **`build-c` takes the source too, and for a reason that is about its consumer rather than about
 * this compilation.** What it writes is linked by a C project, and an artifact is only an artifact if
 * that link succeeds — a `.syslib` cannot go on a C link line, so an archive referring to one is
 * unusable by the only tool that was ever going to read it. Folding the library in is what
 * `--no-std-lib` asks for everywhere else; here there is nothing else to ask for.
 */
private def stdChoice(cfg: Config): Stdlib.Choice =
  if cfg.noStdLib || cfg.std || cLibrary(cfg.command) then Stdlib.Choice.FromSource
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
/** What this compilation depends on, brought onto this machine and turned into what it needs from
 * them (`packages.md § 3`, `§ 5`, `§ 9`).
 *
 * A dependency reaches a compilation the way a `--lib` source tree does — as **more modules**, and
 * as a tree whose C is compiled beside the program's (`15 §7`). What is new beside those is the
 * `Packages` table: each fetched package's files are filed under a canonical prefix taken from its
 * coordinate, and the names its import lines write are read back through the manifest that named it.
 *
 * ==A `--lib` source root's own `dependencies` are here too, and used not to be==
 *
 * A package reached as a source root is the same package it is by coordinate, so what it depends on is
 * a property of *it* rather than of the road it arrived by — the argument `§ 13` makes about the
 * allocator, and the reason `§ 8`'s header requirements are read from a root as well. Handed the same
 * directory as a path, the driver read its `.sysl` and nothing else, so the dependency was neither
 * fetched nor mentioned: what a caller got was the unresolved-name cascade that follows a package
 * whose imports point at code nobody brought, none of it naming the package or the flag.
 *
 * **One graph rather than one per root**, which is what makes selection mean anything: MVS takes its
 * maximum over every claim at once (`§ 5`), so a coordinate two roots share resolves to one copy at one
 * version rather than to two the linker would have to choose between. The roots' entries are folded
 * into the list the project's own are read from, so `demanding`, `select` and the root's import table
 * cover them with nothing added.
 *
 * **Their bindings land in the root table**, which follows from where their source lands rather than
 * being a choice: a `--lib` root's files are filed under the empty prefix, the project's own name
 * space, because that is what `--lib` means. So the names its manifest binds are names the project's
 * own files can write too — no more true of a dependency's name than it always was of the root's own
 * modules.
 *
 * A `path` dependency of a root is resolved exactly as the project's own is, by the same
 * `Fetch.ensure`. Reading it against the root it was written in would be a *second* meaning for one
 * field, and whether a relative path should be read against a project root rather than the working
 * directory is the same question on both roads.
 *
 * `sysl.sum` is written back where a package was fetched that no line covered, so the first build
 * after adding a dependency records what it got and every build after that is checked against it.
 * Writing it is not fatal if it fails: a read-only checkout should still build, and the alternative
 * is refusing to compile over a file that exists to be compared against next time.
 */
private def dependencies(cfg: Config, project: PackageConfig, roots: List[String])
    : Either[String, PackageSources] =
  for
    fromRoots <- libDependencies(roots)
    declared   = project.dependencies ::: fromRoots
    got       <- if declared.isEmpty then Right(PackageSources.none)
                 else resolveDependencies(cfg, project.copy(dependencies = declared), roots)
  yield got

/** Resolving a non-empty dependency list against this machine's cache, and recording what it got.
 *
 * The source roots are handed over as well as their dependencies, and for a different purpose: their
 * modules sit in the project's own name space, so they are names a dependency may not also claim
 * (`§ 9`), and nothing in a manifest tells `Resolve` they are there.
 */
private def resolveDependencies(cfg: Config, project: PackageConfig, roots: List[String])
    : Either[String, PackageSources] = {
  val root = projectRoot(cfg.file)

  for
    cache <- Fetch.cacheRoot
    sums  <- readSums(root)
    graph <- Resolve.graph(root, project, sums, cache, roots)
    files <- collectPackages(graph)
  yield
    if graph.sumsChanged then writeSums(root, graph.sums)
    files
}

/** What each `--lib` source root says it depends on (`packages.md § 2`).
 *
 * A root with no manifest depends on nothing, exactly as for the header requirements and the allocator
 * beside it, and one whose manifest will not parse stops the build rather than being skipped — the
 * same three answers `libHeaderNeeds` and `libAllocators` give, off the same read.
 */
private def libDependencies(roots: List[String]): Either[String, List[Dependency]] =
  roots.foldLeft[Either[String, List[Dependency]]](Right(Nil)) { (acc, root) =>
    for
      seen   <- acc
      config <- readPackageConfig(root)
    yield seen ::: config.dependencies
  }

/** Each fetched package's source, filed under the canonical prefix that keeps its module names
 * apart from every other package's — and each one's directory, which is a tree the C walk visits.
 */
private def collectPackages(graph: Resolve.Graph): Either[String, PackageSources] = {
  val fetched = graph.packages.filterNot(_.isRoot)

  try
    val each = fetched.map(p => p -> Project.collect(p.root))

    each.find(_._2.isEmpty) match
      case Some((p, _)) => Left(s"'${p.canonical}' holds no sysl source files")
      case None =>
        val owned = each.flatMap((p, sources) => sources.map(_ -> p.canonical)).toMap

        Right(PackageSources(
          each.flatMap(_._2),
          Packages(owned, graph.packages.map(p => p.canonical -> p.imports).toMap),
          fetched.map(_.root),
          fetched.flatMap(p =>
            p.config.headers.toList.sortBy(_._1).map((name, why) => HeaderNeed(p.canonical, name, why))),
          fetched.flatMap(p => p.config.allocator.map(p.canonical -> _)),
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
    val limit = t.unsupported.fold("")(why => s"  ($why)")

    stdout(s"${t.name.padTo(width, ' ')}  ${t.triple}$here$limit\n")

  // Always the words this machine's own runtime used, recognized or not. On a machine sysl has no
  // entry for that is the whole of what a report needs, and there is nowhere else to read it.
  stdout(s"\nthis machine reports: ${Target.hostMachineShown}\n")

  0
}

/** Where a build writes when the caller named no output.
 *
 * A **file** project is named by a path carrying an extension, so dropping it leaves a name that
 * cannot be the thing being built: `foo.sysl` becomes `foo`, beside the caller.
 *
 * A **directory** project has no extension to drop, and the name left over *is* the directory —
 * so the linker was handed a path it could not open, and `sysl build .` failed for every project
 * there has ever been while `sysl build test` failed for every project sitting in the working
 * directory. A directory has somewhere obvious to put the thing built out of it, which is inside
 * itself, so that is where it goes.
 *
 * **The point is that the answer stops depending on where the build was started.** `sysl build .`,
 * `sysl build test` and `sysl build ../test` are three ways of naming one project, and all three now
 * write `test/test` rather than three different paths, one of which was the project.
 *
 * **`named` is `package.name` where the project wrote one**, and it answers the question of what a
 * directory project *is*: today a directory is a project because it happens to hold `.sysl` files,
 * and nothing gives one an identity of its own. Requiring a `package.hocon` would give every project
 * one and cost every project the ceremony — 15 directories in this repo have none, and neither does
 * a scratch directory anybody makes in thirty seconds. So a bare directory goes on building and is
 * named for itself, and a project that wants to be called something says so.
 *
 * A **file** project is deliberately left out of this. Its name comes from a path the caller typed,
 * and a config sitting beside it quietly moving `foo.sysl`'s executable would be a worse surprise
 * than the thing this exists to fix.
 */
private def defaultOutput(file: String, named: Option[String], suffix: String = ""): String =
  if isDirectory(file) then s"$file/${named.getOrElse(Project.nameOf(file))}$suffix"
  else defaultOutputName(file) + suffix

/** The name a **file** project's output takes: its own, with the extension dropped. */
private def defaultOutputName(file: String): String = {
  val name = Project.basename(file)
  val dot  = name.lastIndexOf('.')
  val base = if dot > 0 then name.substring(0, dot) else name
  if base.isEmpty then "a.out" else base
}

/** What `--verbose` says, on stderr so that it never lands in what a build was for.
 *
 * It is prefixed rather than bare because these lines arrive in the middle of whatever else the
 * terminal is doing, and a reader needs to know which of the programs in front of them is talking.
 */
private[sysl] def trace(what: String): Unit = Console.err.println(s"sysl: $what")

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
