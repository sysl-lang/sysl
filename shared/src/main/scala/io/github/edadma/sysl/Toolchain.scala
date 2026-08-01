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
            archives: List[String] = Nil): Either[String, Unit] = {
    val ll = createTempFile("sysl-", ".ll")
    writeFile(ll, ir)

    val result = exec(linkCommand(ll, archives, exe, target))
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
   */
  private[sysl] def linkCommand(ll: String, archives: List[String], exe: String,
                                target: Target): List[String] =
    List("clang", s"--target=${target.triple}", "-Wno-override-module") ::: deadStrip(target) :::
      List(ll) ::: archives ::: systemLibraries(target) ::: List("-o", exe)

  /** The system libraries a program is linked against beyond the ones the driver passes itself.
   *
   * There is one, and it is the mathematics: `sysl.math` binds libm, and **where libm lives is a
   * property of the host rather than of the library**. A Darwin program gets it from `libSystem`,
   * which the driver already links, and a Windows one from the CRT; a program hosted on ELF has to
   * say `-lm`, and until it did, every float program built for Linux failed at the link naming
   * `sqrt` — while the same program built on a Mac linked and ran. That is the worst shape a
   * portability bug can have, because the machine that finds it is not the machine that is being
   * developed on.
   *
   * Freestanding gets nothing, and that is the case the `Os` match is written for rather than a
   * default falling out of it: there is no libc on a bare target, so `-lm` there would fail the link
   * of a kernel that never asked for mathematics.
   *
   * **This is the fixed part of a question `15 § Open c` answers in general.** A module of externs
   * should be able to say which library resolves them, and when it can, this becomes what the
   * standard module's own directive says rather than a list the driver carries.
   */
  private[sysl] def systemLibraries(target: Target): List[String] =
    target.os match
      case Os.Linux                                  => List("-lm")
      case Os.MacOS | Os.Windows | Os.Freestanding   => Nil

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
  def compileObject(ir: String, obj: String, target: Target = Target.default): Either[String, Unit] = {
    val ll = createTempFile("sysl-", ".ll")
    writeFile(ll, ir)

    val result = exec(Seq("clang", s"--target=${target.triple}", "-Wno-override-module",
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
  def compileC(source: String, obj: String, target: Target = Target.default): Either[String, Unit] = {
    val result = exec(Seq("clang", s"--target=${target.triple}",
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
    runIr(Compiler.compileToLlvm(source, name), args)

  /** The same, for the files of one program. */
  def compileAndRun(sources: List[Source]): Either[String, (Int, String)] =
    runIr(Compiler.compile(sources), Nil)

  /** The same, for a program compiled **against a library** whose modules arrive as trees rather
   * than as source — an `AstCodec` artifact, decoded (`Compiler.compileWith`).
   */
  def compileAndRun(sources: List[Source], libraries: List[Program]): Either[String, (Int, String)] =
    runIr(Compiler.compileWith(sources, libraries), Nil)

  /** The same, compiled against a **prebuilt standard module** rather than the copy the compiler
   * carries: the trees arrive decoded, the symbols its object half already defines are declared
   * instead of emitted a second time, and the archive holding them is handed to the linker.
   *
   * This is the shape an ordinary `sysl build` has — it is what `.sysl/core.syslib` is *for* — and
   * the caller supplies the artifact because building one belongs to whoever can decide how often it
   * is worth doing. Given none, this is the compilation the other overloads perform.
   */
  def compileAndRun(sources: List[Source], libraries: List[Program], args: List[String],
                    core: Option[Core], precompiled: Set[String], archives: List[String])
      : Either[String, (Int, String)] =
    runIr(Compiler.compiledWith(sources, libraries, Target.default, precompiled, core).map(_._1),
          args, archives)

  /** `args` are the words the program is started with, which reach it exactly as they would from a
   * shell: the executable's own path arrives ahead of them as the zeroth, since that is what the
   * platform passes and not something this could withhold.
   */
  private def runIr(compiled: Either[String, String], args: List[String],
                    archives: List[String] = Nil): Either[String, (Int, String)] =
    compiled.flatMap { ir =>
      val exe = createTempFile("sysl-", "")

      build(ir, exe, Target.default, archives).map { _ =>
        val result = exec(exe :: args)
        deleteFile(exe)
        (result.exitCode, result.stdout)
      }
    }
}
