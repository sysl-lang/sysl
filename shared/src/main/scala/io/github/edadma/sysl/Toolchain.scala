package io.github.edadma.sysl

import io.github.edadma.cross_platform.*

/** Glue between the pure compiler and an installed LLVM toolchain: it writes the generated IR
 * to a temporary `.ll`, links it with `clang`, and runs the result. All filesystem and process
 * access goes through `cross_platform`, so this works on every backend (JVM/JS/Native).
 *
 * **Two external tools are required: a `clang`, and — to build a library — an `llvm-ar`.** The
 * second is not a preference for LLVM's archiver over the platform's. A `.syslib` is an `ar` archive
 * (`LibraryArtifact`) whose members are objects for the machine it was *built for*, which is not
 * necessarily the machine it was built on, and a platform archiver only understands its own format:
 * asked to archive an ELF object on macOS, the system `ar` exits 0, prints a `ranlib` warning, and
 * writes an archive with the member **missing**. A cross-built library would be silently empty.
 * `llvm-ar` indexes every format, so one archiver covers every target.
 */
object Toolchain {

  /** Whether a `clang` capable of consuming textual LLVM IR is on the PATH. Tests that link
   * and run gate on this so they skip cleanly on a machine without a toolchain.
   */
  lazy val clangAvailable: Boolean =
    exec(Seq("clang", "--version")).exitCode == 0

  /** Whether a Why3 is on the PATH, for `sysl prove` (`17 §9`). The proof tests gate on it so they
   * skip cleanly on a machine without one.
   *
   * Looked for by name only. Why3 installs through opam and has no Homebrew formula, so on a machine
   * that has one it is opam's switch that puts it on the PATH — which is the thing to say in the
   * diagnostic rather than a directory to go guessing at.
   */
  lazy val why3Available: Boolean = runs("why3")

  /** Which provers to try, in order, by the name Why3 files them under. Alt-Ergo first because it is
   * the one Why3 ships alongside; Z3 next, since Homebrew has a formula for it and opam does not.
   */
  private val provers = List("Alt-Ergo", "Z3", "CVC5", "CVC4")

  /** The prover to name on the command line, as `Name,Version` — which is the spelling Why3 wants
   * when a configuration holds several.
   *
   * **A bare name is not enough and the failure says so rather than falling back**: with three
   * Alt-Ergo entries configured (plain, bitvector, counterexample) `-P Alt-Ergo` is refused as
   * ambiguous. The alternatives are the parenthesized ones, and they are exactly what is skipped
   * here — the plain entry is the one that answers an ordinary goal.
   */
  private lazy val prover: Option[String] =
    if !why3Available then None
    else
      val listed =
        exec(Seq("why3", "config", "list-provers")).stdout.linesIterator
          .map(_.trim)
          .filter(l => l.nonEmpty && !l.contains('('))
          .toList

      provers.iterator
        .flatMap(p => listed.find(_.startsWith(p + " ")).map(_.replaceFirst(" ", ",")))
        .nextOption()

  /** Whether Why3 has a prover to discharge anything with. A machine may have Why3 and no prover at
   * all, which is a different failure from having neither and is worth telling apart.
   */
  lazy val why3HasProver: Boolean = prover.isDefined

  /** Runs Why3 over a WhyML module, answering what it said (`17 §9`).
   *
   * `prove` is the batch subcommand: it splits each verification condition into goals, runs the
   * configured provers on them, and reports one line per goal. The exit status is what says whether
   * everything was discharged, and the output is what says which goal was not.
   */
  def why3Prove(mlw: String, timeoutSeconds: Int = 5): Either[String, (Int, String)] =
    if !why3Available then
      Left("cannot find why3, which proving needs — it installs through opam ('opam install why3') " +
        "and has no Homebrew formula, so a shell that has not run 'eval $(opam env)' will not see " +
        "one that is installed. A prover is wanted too: 'opam install alt-ergo', or Homebrew's z3, " +
        "either of which why3 finds once 'why3 config detect' has run")
    else
      val file = createTempFile("sysl-", ".mlw")

      writeFile(file, mlw)

      // A prover has to be named for a time limit to mean anything, and naming one is what makes
      // this a *discharge* rather than a well-formedness check.
      val args = prover.toList.flatMap(p => List("--timelimit", timeoutSeconds.toString, "-P", p))
      val result = exec(List("why3", "prove") ::: args ::: List(file))

      deleteFile(file)
      Right((result.exitCode, result.stdout + result.stderr))

  /** What optimization a build asks for when nothing named one — the level, as clang spells it after
   * the `-O`.
   *
   * **Nothing was passed here at all until a program miscompiled.** `guide/sha2` handed a 184-byte
   * struct to a function **by value** and the callee received zeros, from correct IR, on a build
   * that differed from a working one by a single `zext` feeding an `llvm.fshr`. `-O0` was where it
   * lived: the same module at `-O1` ran correctly, and the case reduced to a hand-edited `.ll` with
   * none of sysl's own code in the edit. The shape of it is a FastISel fallback losing a live value
   * — a path that exists only because `-O0` selects instructions the fast way and falls back to the
   * full selector for whatever it cannot handle.
   *
   * **So the default is not a workaround for that one bug.** It is the decision to stop building on
   * the least-exercised path in the back end. `-O0` is the mode a compiler's own suite covers least
   * and the one where instruction selection is a different algorithm; a language whose programs were
   * correct only at `-O0` would have the problem the wrong way round.
   *
   * `1` rather than `2` because what is wanted is the ordinary pipeline and not the aggressive one.
   * `-O1` is where the standard selector and the everyday simplifications are, which is the whole of
   * what this is for; it is also where a debugger still finds its footing and where compile time is
   * not something a program has to be big before noticing. `--optimize` names another.
   */
  val defaultOptimization = "1"

  /** The flag a level is passed as. A level is whatever was written — `0`, `2`, `s`, `z`, `fast` are
   * all clang's — and one clang does not have is clang's to complain about, since it is the
   * authority on its own levels and would say so better than a list here could.
   */
  private def flag(level: String) = s"-O$level"

  /** Where an `llvm-ar` is looked for, in order, when none was named.
   *
   * The PATH first, since a toolchain installed to be used is on it. Then the places a package
   * manager puts an LLVM that it deliberately keeps *off* the PATH — Homebrew does this so that its
   * LLVM does not shadow the system clang, which means the common case on a Mac is that `llvm-ar` is
   * installed, works, and is invisible to `which`. Finding it there is the difference between a
   * toolchain that works out of the box and one that starts with an error message.
   */
  private val arCandidates: List[String] =
    List(
      "llvm-ar",
      "/opt/homebrew/opt/llvm/bin/llvm-ar",
      "/usr/local/opt/llvm/bin/llvm-ar",
    )

  /** The archiver to use, or why there is none.
   *
   * A named one is **not** searched for and not fallen back from: someone who wrote down which
   * archiver to use is owed an error when it is not there, rather than a library quietly built with
   * a different one.
   */
  def findAr(named: Option[String]): Either[String, String] = named match
    case Some(path) =>
      Either.cond(runs(path), path, s"cannot run '$path' — --ar names the llvm-ar to build libraries with")
    case None =>
      searched.toRight(
        "cannot find llvm-ar, which building a library needs — install LLVM, or name one with --ar. " +
          "The platform's own 'ar' is not a substitute: it indexes only its own object format, and " +
          "drops the members of a library built for another machine without failing")

  /** The search, made once. Each candidate costs a process to rule out, and nothing about the answer
   * changes between two builds in one run — of which a test suite does many.
   */
  private lazy val searched: Option[String] = arCandidates.find(runs)

  private def runs(path: String): Boolean =
    try exec(Seq(path, "--version")).exitCode == 0
    catch case _: Exception => false

  /** Objects gathered into an archive the linker takes as it is.
   *
   * `r` replaces, `c` creates without remarking on it, and `s` writes the symbol index — the index
   * being the whole reason this is a subprocess rather than thirty lines of `Ar`: building one means
   * reading the symbol table of every member, in whichever object format the target uses.
   *
   * The output is removed first because `r` *merges* into an archive that is already there. Rebuilding
   * a library whose sources had shrunk would otherwise keep the members of the previous build, and
   * they would still resolve, so nothing would go wrong until the stale definition was the one linked.
   */
  def archive(objects: List[String], out: String, ar: String): Either[String, Unit] = {
    try if exists(out) then deleteFile(out)
    catch case e: Exception => return Left(s"cannot replace $out: ${e.getMessage}")

    val result = exec(ar :: "rcs" :: out :: objects)

    if result.exitCode == 0 then Right(())
    else Left(s"$ar failed (exit ${result.exitCode}):\n${result.stderr.trim}")
  }

  /** Links an IR module into a native executable at `exe`, for a given machine.
   *
   * The triple goes on the command line as well as in the module because the two answer different
   * questions: the module's says what the code *is*, and the driver's decides which linker and
   * which system libraries it is linked against. Passing it is therefore what makes naming a
   * cross target fail honestly at the link rather than silently produce a host binary.
   *
   * `-Wno-override-module` is the one warning suppressed, and only because sysl states a target
   * *family* — `arm64-apple-macosx` — where the driver refines it to the installed SDK's version.
   * With `--target` passed, the only override that can still happen is that refinement.
   */
  def build(ir: String, exe: String, target: Target = Target.default,
            archives: List[String] = Nil, level: String = defaultOptimization,
            links: List[String] = Nil, objects: List[String] = Nil): Either[String, Unit] = {
    val ll = createTempFile("sysl-", ".ll")
    writeFile(ll, ir)

    val result = exec(linkCommand(ll, archives, exe, target, level, links, objects))
    deleteFile(ll)

    if result.exitCode == 0 then Right(())
    else Left(s"clang failed (exit ${result.exitCode}):\n${result.stderr.trim}")
  }

  /** The whole of what is handed to the driver to link one program, as a list, so that what a target
   * decides about it can be asserted without a machine of that kind to link on.
   *
   * The order is the linker's and not a style: an archive goes **after** the module that calls into
   * it, because the scan is left to right and a member is pulled in only to resolve a symbol already
   * undefined — an archive listed first would be scanned before anything needed it and contribute
   * nothing. The system libraries go after both for exactly the same reason, since the library's own
   * object is one of the things that calls them.
   *
   * `objects` is the C a source tree carried (`NativeSources`), and it sits between the two for the
   * same reason again: an object is linked whether or not anything needed it, so its own calls are
   * undefined symbols by the time the archives and the `-l`s are scanned. A shim placed after them
   * would have nothing left to resolve `sqlite3_open` against.
   */
  private[sysl] def linkCommand(ll: String, archives: List[String], exe: String, target: Target,
                                level: String = defaultOptimization,
                                links: List[String] = Nil, objects: List[String] = Nil): List[String] =
    List("clang", s"--target=${target.triple}", "-Wno-override-module", flag(level)) ::: deadStrip(target) :::
      List(ll) ::: objects ::: archives ::: libraryFlags(links, target) ::: List("-o", exe)

  /** What a build's link directives (`15 §8`) become on **this** target's command line.
   *
   * A directive names a library and never a flag, so this is the whole of the translation, and it is
   * a decision per operating system written out per case rather than a default that some target
   * falls into. Three things can happen to a name, and only two of them look alike from outside:
   *
   *   - **The target has it, separately.** `-lm` on ELF. The ordinary case, and the one every name
   *     the compiler has never heard of takes.
   *   - **The target already links what holds it.** Darwin keeps libc and libm in `libSystem` and
   *     Windows keeps them in the CRT, both of which the driver passes unasked, so naming either
   *     adds nothing. `-lm` on a Mac is not merely redundant — there is no `libm.dylib` to find.
   *   - **The target does not have it at all.** A freestanding build has no libc, so nothing can be
   *     passed for one. A program that then calls `sqrt` fails at the link naming `sqrt`, which is
   *     the honest report: the missing thing is the function, and no `-l` would have supplied it.
   *
   * The last two both emit nothing and are written apart anyway, because they are different facts
   * and a target added to the registry has to answer them separately. `hosted` is what tells them
   * apart: it is the C runtime's own family that a freestanding target is missing, and a directive
   * naming anything else — a driver's own archive, say — is passed through on every target.
   *
   * **This replaced a list the driver carried.** Until `15 §8` existed, `-lm` was appended to every
   * ELF link whether or not the program touched mathematics, because the compiler had no way to be
   * told and `sysl.math` had no way to say. It says so itself now, in `lib/sysl/sys/math.sysl`, and
   * the decision left here is the only part that was ever the driver's: where a named library lives
   * on the machine being built for.
   */
  private[sysl] def libraryFlags(links: List[String], target: Target): List[String] =
    links.filter(name => !provided(target.os).contains(name)).map(name => s"-l$name")

  /** The libraries a target needs no flag for — because something the driver already links holds
   * them, or because the target has none of them to link.
   *
   * The set is deliberately small: it holds what the compiler can *defend* rather than every library
   * a platform might bundle. `c` and `m` are here because the standard module itself needs them and
   * because their placement genuinely differs across the four. Guessing about `pthread` or `dl` would
   * mean guessing wrong for some platform nobody here has, and the cost of being wrong is a link that
   * fails on a machine the author cannot reach — so an unknown name is passed through, where a wrong
   * `-l` at least names itself in the error.
   */
  private def provided(os: Os): Set[String] =
    os match
      // libSystem and the CRT carry both, and the driver links them without being asked.
      case Os.MacOS | Os.Windows => Set("c", "m")
      // clang links libc itself; the mathematics is a file of its own.
      case Os.Linux => Set("c")
      // No libc exists here, so there is nothing to pass for one.
      case Os.Freestanding => Set("c", "m")

  /** Ask the linker to drop what nothing reaches.
   *
   * **This is the second of two mechanisms, and it is not made redundant by the first.** Because a
   * `.syslib` is an archive, the linker already pulls in only the members that resolve something
   * undefined — but a member is pulled *entire*, and one member holds many definitions. Dead-striping
   * is what removes the rest of a member that was pulled in for one function. Without it a program
   * whose whole text is `print(1)` carried all 61 of the standard module's symbols, the reader and
   * the string builder and the hashes among them, none of which it can reach.
   *
   * The two spellings are not interchangeable. Mach-O resolves at **atom** granularity, so
   * `-dead_strip` needs nothing from the compile step. ELF resolves at **section** granularity and
   * `--gc-sections` can therefore only drop a function that was given a section of its own, which is
   * what `-ffunction-sections` in `compileObject` is for — the two halves are one mechanism and
   * changing either alone silently stops working.
   */
  private def deadStrip(target: Target): List[String] =
    target.os match
      case Os.MacOS => List("-Wl,-dead_strip")
      case _        => List("-Wl,--gc-sections")

  /** Assembles an IR module into a relocatable object file — the ahead-of-time half of a library.
   *
   * `-c` is the whole difference from `build`: nothing is linked, so a module with no `main` and
   * with unresolved calls into the program that will use it is exactly what is wanted.
   *
   * `-ffunction-sections`/`-fdata-sections` give every definition a section of its own, which is
   * what lets the linker drop the ones a program never reaches (`deadStrip`). It is the *library*
   * side of that pair and useless without the linker side, and on Mach-O it is redundant rather than
   * wrong — atoms are already per-definition there. Passed unconditionally so that an artifact built
   * on one host still strips when linked for another.
   */
  def compileObject(ir: String, obj: String, target: Target = Target.default,
                    level: String = defaultOptimization): Either[String, Unit] = {
    val ll = createTempFile("sysl-", ".ll")
    writeFile(ll, ir)

    val result = exec(Seq("clang", s"--target=${target.triple}", "-Wno-override-module", flag(level),
      "-ffunction-sections", "-fdata-sections", "-c", ll, "-o", obj))
    deleteFile(ll)

    if result.exitCode == 0 then Right(())
    else Left(s"clang failed (exit ${result.exitCode}):\n${result.stderr.trim}")
  }

  /** Compiles one of a library's C files into a relocatable object, to be archived beside the
   * object the library's own sysl compiled to (`15 §7`).
   *
   * **This is what makes a binding to a C library writable at all.** A caller-allocated type like
   * `regex_t` has a size and an alignment that only the target's own headers know, and a macro like
   * `REG_EXTENDED` has no symbol to link against; both are reachable from C and from nothing else.
   * A few lines of C beside the binding turn each of them into an ordinary function the sysl side
   * declares with `extern` — so the numbers come from the headers rather than from a transcription
   * that is right until the platform changes.
   *
   * The same section flags as `compileObject`, for the same reason: a shim a program never calls is
   * dropped by the linker only if it was given a section of its own. A library that binds forty
   * functions should not cost a program that calls one of them the other thirty-nine.
   *
   * `-Wno-override-module` is *not* passed. It exists for a `.ll` that states a target family the
   * driver then refines, and a `.c` has no module statement to override — passing it here would be
   * suppressing a warning that cannot arise.
   */
  def compileC(source: String, obj: String, target: Target = Target.default,
               level: String = defaultOptimization): Either[String, Unit] = {
    val result = exec(Seq("clang", s"--target=${target.triple}", flag(level),
      "-ffunction-sections", "-fdata-sections", "-c", source, "-o", obj))

    if result.exitCode == 0 then Right(())
    else Left(s"$source did not compile (exit ${result.exitCode}):\n${result.stderr.trim}")
  }

  /** Compiles, links, and runs a source program, returning its exit code and captured
   * stdout — the end-to-end path the run-it test tier exercises.
   *
   * There is one target here and not two: a program is built for the machine it is about to be run
   * on, so a cross target has nothing to run the result with and is not offered.
   */
  def compileAndRun(source: String, name: String = "<input>",
                    args: List[String] = Nil): Either[String, (Int, String)] =
    runIr(Compiler.compiled(List(Source(name, source))), args)

  /** The same, for the files of one program. */
  def compileAndRun(sources: List[Source]): Either[String, (Int, String)] =
    runIr(Compiler.compiled(sources), Nil)

  /** The same, for a program compiled **against a library** whose modules arrive as trees rather
   * than as source — an `AstCodec` artifact, decoded (`Compiler.compileWith`).
   */
  def compileAndRun(sources: List[Source], libraries: List[Program]): Either[String, (Int, String)] =
    runIr(Compiler.compiledWith(sources, libraries), Nil)

  /** The same, compiled against a **prebuilt standard module** rather than the copy the compiler
   * carries: the trees arrive decoded, the symbols its object half already defines are declared
   * instead of emitted a second time, and the archive holding them is handed to the linker.
   *
   * This is the shape an ordinary `sysl build` has — it is what `.sysl/std.syslib` is *for* — and
   * the caller supplies the artifact because building one belongs to whoever can decide how often it
   * is worth doing. Given none, this is the compilation the other overloads perform.
   */
  def compileAndRun(sources: List[Source], libraries: List[Program], args: List[String],
                    std: Option[Stdlib], precompiled: Set[String], archives: List[String])
      : Either[String, (Int, String)] =
    runIr(Compiler.compiledWith(sources, libraries, Target.default, precompiled, std), args, archives)

  /** The same, with the program's two output streams kept apart. */
  def compileAndRunFully(sources: List[Source], libraries: List[Program], args: List[String],
                         std: Option[Stdlib], precompiled: Set[String], archives: List[String])
      : Either[String, (Int, String, String)] =
    runIrFully(Compiler.compiledWith(sources, libraries, Target.default, precompiled, std), args, archives)

  /** `args` are the words the program is started with, which reach it exactly as they would from a
   * shell: the executable's own path arrives ahead of them as the zeroth, since that is what the
   * platform passes and not something this could withhold.
   *
   * The whole `Compiled` is taken rather than the IR alone because what a program links against
   * comes back beside its module and nowhere else (`15 §8`). Taking the IR was enough only while the
   * driver carried one hardcoded library for every build.
   */
  private[sysl] def runIr(compiled: Either[String, Compiled], args: List[String],
                          archives: List[String] = Nil): Either[String, (Int, String)] =
    runIrFully(compiled, args, archives).map { case (code, out, _) => (code, out) }

  /** The same, keeping the two streams apart.
   *
   * Almost everything that runs a program wants its output and does not care which descriptor it
   * came out of, which is what `runIr` above is for. What needs the distinction is anything
   * asserting about a *diagnostic*: `sysl.args` puts a usage error on standard error and its
   * `--help` on standard output precisely so the two can be redirected apart, and a test that
   * concatenated them could not tell whether that had happened.
   */
  private[sysl] def runIrFully(compiled: Either[String, Compiled], args: List[String],
                               archives: List[String] = Nil): Either[String, (Int, String, String)] =
    compiled.flatMap { c =>
      val exe = createTempFile("sysl-", "")

      build(c.ir, exe, Target.default, archives, defaultOptimization, c.links).map { _ =>
        val result = exec(exe :: args)
        deleteFile(exe)
        (result.exitCode, result.stdout, result.stderr)
      }
    }
}
