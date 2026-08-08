package sh.sysl

import io.github.edadma.cross_platform.*

/** The library the compiler is installed with: the standard module `sysl` that every program is
 * compiled against, and the submodules beneath it (`13 § Open h`).
 *
 * **`sysl` is the auto-imported part and not the whole of it.** `lib/sysl` is a tree, so a directory
 * under it is a submodule by `13 §1`'s ordinary rule, and only the standard module's names are the
 * ones every file gets for free. That is what a submodule is for: what a program cannot avoid
 * needing goes in `sysl`, and what it should have to ask for goes below.
 *
 * What a program starts with is a *module*, not a set of declarations threaded in beside it. It was
 * the latter once: a `Prelude` of sysl source held in a string inside the compiler, keyed under the
 * anonymous root module. Its declarations were drained into this one a surface at a time, both
 * mechanisms live throughout, so that there was no commit at which neither worked. A switch would
 * have put every unqualified name in every program onto a path nothing had ever exercised, where a
 * single hole fails every test at once with nothing to bisect; a move put **one** declaration onto
 * that path, so what broke named what was wrong.
 *
 * **The source is real files, under `lib/sysl`, and the compiler reads them off disk.** That is what
 * the string inside the compiler could never be: a literal has no other form, while these are files
 * a driver reads exactly as it reads a user's library — which is what `sysl build-lib` is pointed
 * at, and what makes the library's own source something a reader can open, edit, and rebuild
 * against.
 *
 * They were carried *inside* the binary once, generated into a `StdSource` object by `build.sbt`.
 * That bought a guarantee — a compilation could not fail to find its library — and it was bootstrap
 * scaffolding rather than the design: a library nobody can open is not one anybody can learn from,
 * and a compiler that cannot be pointed at an edited one cannot have its library worked on at all.
 * What replaces the guarantee is `root` below, and above all the diagnostic it fails with, which
 * names every place it looked.
 *
 * **Every real toolchain resolves this way.** `rustc` computes a sysroot from its own location,
 * `clang` finds its resource directory from its own, `zig` its `lib/`. None of them carries its
 * standard library in the executable, and none of them asks the user to set a variable.
 *
 * **What a given compilation was handed is a different question**, and it is `Stdlib` that answers it:
 * a program may be compiled against a `.syslib` whose trees never went through the parser, and every
 * question about which declarations are the library's has to be answered over the one it actually
 * got. This file is only the copy that is always available.
 *
 * **It is more than one file, and that is load-bearing rather than tidiness.** `Display.display`
 * names `Writer`, which is declared in the other one: a module's members are one set however many
 * files they came from (`13 §6`), so neither file imports the other and the order they are read in
 * decides nothing. A library that could only ever be one file would not be a library.
 */
object Std {

  /** The **auto-imported** module, and the root of the library's tree: every other module the
   * library carries is below it. A constant so that nothing has to parse to ask which module the
   * free names are in; which modules there are in total is `Library.modules`, read off the headers.
   */
  val module: String = "sysl"

  /** Where the library's source is, or why it could not be found — resolved once, and the one thing
   * about the installation the rest of the compiler asks.
   *
   * The order is the order the answers are *trusted* in. An explicit `SYSL_LIB` is the user
   * overriding everything and is taken at its word; the compiler's own location is the installed
   * case and is what makes an install self-contained; the working directory is the development one,
   * where the compiler is being run out of a checkout.
   *
   * `SYSL_LIB` is an escape hatch and not the mechanism. A toolchain that needed a variable set
   * before it would work would be one nobody could install, so nothing on the ordinary path reads
   * it — it is here for a broken install and for working on the library itself, and it is the only
   * one of the three that reports its own failure rather than falling through, because someone who
   * set it is owed the truth about what they set.
   */
  lazy val root: Either[String, String] = rootOf(envVar("SYSL_LIB"), executablePath, isDirectory)

  /** The resolution itself, over its three inputs rather than over the machine it is running on —
   * the variable, this compiler's own location, and what counts as a directory.
   *
   * **Taking them as parameters is what makes every branch testable.** Read ambiently, the only case
   * a suite could reach is whichever one happens to hold on the machine it is running on, and the
   * others would be asserted by reading. That is the mistake `cross_platform.cacheRoot` was written
   * to undo, where the one test for a named root was cancelled on every run.
   */
  private[sysl] def rootOf(named: Option[String], exe: Option[String],
                           isDir: String => Boolean): Either[String, String] = {
    // A root is one that **holds the standard module**, not merely one that exists. `lib` is an
    // ordinary directory name — a C project has one, and so does half of everything else — so an
    // installed compiler standing in someone's source tree would otherwise take theirs for its own
    // and fail somewhere far from the cause. Asking for `<root>/sysl` is one `isDirectory` and it
    // makes the search skip what it should skip.
    def holdsLibrary(root: String): Boolean = isDir(s"$root/$module")

    named match
      case Some(path) =>
        Either.cond(holdsLibrary(path), path,
          s"SYSL_LIB names '$path', which does not hold a '$module' directory — it should be the " +
            s"library root, which is the directory *above* '$module' rather than '$module' itself")
      case None => candidates(exe).map(_._1).find(holdsLibrary).toRight(missing(exe))
  }

  /** The places a library root is looked for, in order, each with the reason it is a candidate — the
   * reasons being what the diagnostic below is made of.
   *
   * The installed answer is `<prefix>/share/sysl/lib`, reached from the binary's own resolved path
   * by going up out of `bin`. That is a plain Unix prefix layout and is exactly what Homebrew's
   * `pkgshare` is, so an installed sysl finds its library with nothing configured and two installs
   * of different versions each find their own.
   *
   * The relative ones are the development tree. `../` and `../../` are there because the compiler's
   * own tests do not all run from the repository root, and because a developer running the driver
   * from a subdirectory of a checkout is the same case.
   */
  private[sysl] def candidates(exe: Option[String]): List[(String, String)] =
    exe.flatMap(path => Project.parentOf(path).flatMap(Project.parentOf)).toList
      .map(prefix => s"$prefix/share/sysl/lib" -> "beside this compiler") :::
      List("lib", "../lib", "../../lib").map(_ -> "from the working directory")

  /** What a compilation with no library to compile against says.
   *
   * **This is what replaces a guarantee**, so it is written as the thing a reader can act on rather
   * than as a report that something failed: every path that was tried, in the order they were tried,
   * with what each one was — followed by the two ways out. A compiler that cannot find its standard
   * library is broken, and the difference between a broken installation and a mystery is this list.
   */
  private def missing(exe: Option[String]): String =
    s"cannot find the standard module's source, which every program is compiled against. Looked " +
      s"for a library root holding '$module' at:\n" +
      candidates(exe).map((path, why) => s"  $path ($why)").mkString("\n") +
      "\n\nA sysl installed from a package has it beside the binary; one run out of a checkout " +
      "finds it in the tree. Set SYSL_LIB to the library root to name it outright."

  /** The library's files, sorted by their place in it rather than by where they were found — so that
   * what a compilation sees depends on the library and not on the order a directory listed, or on
   * which of the paths above the root was reached by.
   *
   * Each carries the directory it sits in below the root, which is the module its header has to
   * agree with (`13 §1`). They are read by the same walk that reads a user's library, because they
   * *are* a library: `sysl build-lib lib --std` is pointed at this same tree and gets these same
   * values.
   */
  lazy val sources: List[Source] = root match
    case Right(dir) =>
      val found = Project.collect(dir).sortBy(place)

      // A directory that answers the search and holds nothing readable is a *different* failure from
      // not finding one, and it has to say so: every program would otherwise fail at its first free
      // name — `undefined function 'print'` — which points at the program rather than at the empty
      // library that caused it. Reachable through a truncated install, or a `SYSL_LIB` aimed one
      // level too high.
      if found.isEmpty then sys.error(s"the library at $dir holds no sysl source files")

      found.map(named(dir, _))
    case Left(err) => sys.error(err)

  /** A library file under the name a **diagnostic** should call it: the library root's own name and
   * the file's place below it, never the path it was read from.
   *
   * **A message naming a library file has to read the same on every machine.** The library moves
   * with the installation — `/opt/homebrew/Cellar/sysl/0.0.3/share/sysl/lib` on one machine, `lib`
   * in a checkout, wherever `SYSL_LIB` says on a third — and a diagnostic that quoted that path
   * would be noise on the first, different on the second, and unquotable by the documentation on
   * all of them. Which is how this was found: two library pages quote
   * `lib/sysl/thread/mutex.sysl` in a refusal about a private field, and the executable-docs suite
   * failed the moment the name became absolute.
   *
   * A program's own files keep the path they were given, and should: those a reader can open, and
   * the whole point of a diagnostic pointing at one is that they go and look.
   */
  private[sysl] def named(root: String, s: Source): Source =
    Source(s"${Project.basename(root)}/${place(s)}", s.text, s.dir.getOrElse(Nil))

  /** A file's place in the library: the module directories it sits under, then its own name. The
   * same key the fingerprint sorts and hashes by, and for the same reason — it is what is true of a
   * file wherever the library was found, while its path is not.
   */
  private def place(s: Source): String =
    (s.dir.getOrElse(Nil) :+ Project.basename(s.name)).mkString("/")

  /** What the library's source amounts to, for telling a prebuilt artifact built from *this* library
   * from one built from a different version of it.
   *
   * The artifact is built separately from the compiler that consumes it, and the two can therefore
   * fall out of step: build one, edit `lib/sysl`, and every compilation after that would be against
   * a standard module that is not the one in the tree. Nothing else would notice — a stale artifact
   * decodes perfectly and links perfectly, it is just the wrong library. This is what an artifact is
   * held to on the way in ([[Stdlib.read]]).
   *
   * **Rebuilding the compiler has nothing to do with it, and that is what reading the library off
   * disk bought.** While the source was generated into the binary there were two copies and two
   * ways to be stale — an artifact behind the carried source, and carried source behind the tree —
   * and the second needed a test of its own to catch. Now an edit to `lib/sysl` moves this the next
   * time the compiler runs, the artifact keyed by it is a different file, and the drift the second
   * copy made possible cannot occur.
   */
  lazy val fingerprint: String = LibraryArtifact.fingerprint(sources)

  /** The parsed standard module, **for a target** — and the trees of **one** target are kept, not
   * every target's.
   *
   * The library is sysl source like any other and may gate on the machine it is being built for
   * (`Conditional`), so which trees it comes to is a question with a target in it. Two targets may
   * therefore see two different standard modules — that is the point of the feature — and the answer
   * is memoized because this is on the path of every compilation that has no artifact to read
   * instead.
   *
   * **What is memoized is the LAST target asked for, and that bound is the whole point.** The
   * paragraph this replaces argued that the table should not be built from the registry up front
   * because *"a run compiles for one target and would pay for ten"* — which is true of a run, and
   * stopped being true of the **suite** the moment a test iterated `Target.all`. Filling it lazily
   * costs exactly what filling it eagerly costs, only later: `AbiAgainstClangTests` walks every
   * supported target, so it arrived at ten parsed standard modules held for the life of the process,
   * and a Scala Native test agent that had run it could not then run anything else.
   *
   * A single slot keeps every bit of the benefit for the access pattern the comment describes — a
   * loop over one target hits every time — and costs a re-parse only where the caller alternates,
   * which is the case that was never meant to be cached anyway.
   */
  def parsed(target: Target): List[Program] =
    cache.synchronized {
      cache.get(target) match
        case Some(programs) => programs
        case None =>
          val programs = sources.map(s =>
            SyslParser.parse(s, target) match
              case Right(p) => p
              case Left(e)  => sys.error(s"the standard module does not parse: $e"),
          )

          cache.clear()
          cache(target) = programs
          programs
    }

  /** **Locked, and it is not decoration.** This was a `lazy val` before it took a target, and a
   * `lazy val` is initialized exactly once however many threads reach it. A bare mutable `Map` is
   * not: two compilations for two targets, running at once, can be inside it together and leave the
   * table itself broken — which is not a wrong answer but a corrupted one, and it would show up as
   * something unrelated much later. The suite compiles for several targets from several threads, so
   * this is a live case rather than a hypothetical one.
   */
  private val cache = collection.mutable.Map.empty[Target, List[Program]]

  /** How many targets' trees are held. Exists so a test can pin the bound the comment above claims —
   * a memory property has no other observable surface, and this one regressed a whole release.
   */
  private[sysl] def cachedTargets: Int = cache.synchronized(cache.size)

  /** Whether a second ask for one target answers from memory, decided **without letting go of the
   * lock between the two asks**.
   *
   * A test cannot settle this by calling `parsed` twice and comparing, because only one target's
   * trees are kept: another suite asking for a different target in between clears the slot, and the
   * second ask reparses through no fault of the memo. The suites run concurrently and several sweep
   * every target, so that is an ordinary interleaving rather than a rare one — it reproduces against
   * `CrossTargetBuildTests` and `TargetTests` every time.
   *
   * Holding the lock across both is what makes the question answerable at all. It asks the memo
   * exactly what it promises: that a caller staying on one target parses once.
   */
  private[sysl] def memoAnswersTwice(target: Target): Boolean =
    cache.synchronized { parsed(target) eq parsed(target) }

  def decls(target: Target): List[Stmt] = parsed(target).flatMap(_.body)
}
