package sh.sysl

import io.github.edadma.cross_platform.*

/** The standard module **this** compilation is compiled against, and the one place that says where
 * its trees came from.
 *
 * Every program is compiled against the library (`13 §8`), and there has been one way to be handed
 * it: `Std.parsed` — the files under `lib/sysl`, read off disk and parsed. That is what a
 * *source* dependence on the standard module is, and it is what the artifact exists to end: every program
 * re-derives every signature in the standard module from text before it can check its own first
 * line.
 *
 * A `.syslib` carries those same trees already decoded, and — for the half with nothing left to
 * monomorphize — the object code to link against rather than emit a second copy of. Which of the two
 * a compilation gets is this value.
 *
 * **It is a parameter rather than an ambient fact**, for the reason a `Target` is: that a library is
 * installed beside the compiler is not the same claim as this compilation being compiled against
 * it. Making it a parameter is also what lets the two be run side by side and their
 * results compared, which is the only way to establish the thing the artifact has to be true for —
 * that it *means* what the source means.
 *
 * **Only three questions turn on which standard module it is**, which is why this is a small type. What trees
 * the library contributes, which sources are its rather than the program's, and which declarations
 * are its. Everything else the compiler knows about the library — the module's name, the key a
 * declaration is filed under, the spelling a key renders as — is true of the standard module however
 * it arrived, and lives in `Library`.
 */
final class Stdlib(val units: List[Program]) {

  /** The files these trees came from. A `Source` compares by identity (`Diagnostics`), so this is
   * an identity set: a user file that happened to be called `lib/sysl/display.sysl` is not one of
   * these, and neither is the decoded copy of a file an artifact carries under the same name.
   */
  private val own: Set[Source] = units.map(_.source).toSet

  /** The modules these trees declare — the library's own, as *this* compilation was handed it.
   *
   * A program may name any of them and may declare none of them, and both are questions about what
   * is actually there: a compilation given a stand-in library has whatever that one declares, and
   * one given none has nothing to collide with at all.
   */
  lazy val modules: List[String] = units.map(Compiler.moduleOf).distinct.sorted

  /** Whether `module` is one of the library's own here. */
  def carries(module: String): Boolean = modules.contains(module)

  /** The library's files, as units for the walk to read on the same terms as the program's.
   *
   * They *are* files of modules, so what a name in one of them resolves against is what its own
   * header and its own imports say — not one scope standing in for the whole library. That was
   * exact while the library was a single module importing nothing, and stops being so the moment it
   * has more than one: a file of `sysl.sys` naming `Buf` is naming another module's declaration, and
   * has to say so the way any other file would.
   *
   * `building` is the modules this compilation is **producing** rather than being supplied with,
   * which is how the library's own source gets compiled at all: pointed at `lib`, the compiler would
   * otherwise hand a copy of `sysl` to the files that declare it, and every name in them would be
   * declared twice. It is per file rather than all-or-nothing so that the answer stays right for a
   * build producing some of the library's modules and not others.
   */
  def contributed(building: Set[String]): List[Program] =
    units.filterNot(u => building(Compiler.moduleOf(u)))

  /** Whether a declaration is the library's rather than the program's.
   *
   * Asked of the **position** rather than of the module, though the two now agree: a `Source` is a
   * stronger answer than a name, since a user file that happened to sit at `lib/sysl/display.sysl`
   * is not one of these.
   */
  def owns(decl: Positioned): Boolean = decl.pos.exists(p => own(p.source))

  /** Every declaration the library carries. */
  def decls: List[Stmt] = units.flatMap(_.body)
}

object Stdlib {

  /** Which standard module a compilation is to be given.
   *
   * The three ways are not variations on a setting — they are three different sources, and which one
   * a compilation used decides what its diagnostics can say and whether anything is linked. They are
   * a type rather than a set of flags so that a caller cannot ask for two at once: naming an
   * artifact *and* refusing all of them is a command line with no reading, and it is refused by the
   * driver today with a message saying exactly that.
   */
  enum Choice {

    /** Compile the library's source into the program. What `--no-std-lib` asks for, and what
     * `build-lib --std` has to use, since it is the command that produces the artifact.
     */
    case FromSource

    /** A prebuilt artifact somebody named. Never rebuilt and never fallen back from — someone who
     * wrote down which artifact to use is owed the truth about that one.
     */
    case Artifact(path: String)

    /** The path both ends agree on, built there when what is there is not one. `search` overrides
     * where that is; `None` means the default, which is keyed by a fingerprint of the library and so
     * cannot be computed until the library has been found.
     */
    case Default(search: Option[String] = None)
  }

  /** A standard module a compilation can be given: the trees to compile against, the symbols its
   * object half already defines, and the archive to link them from.
   *
   * The archive is `None` where there is nothing to link — the library was compiled in rather than
   * linked, so its bodies are in the program's own IR.
   */
  case class Resolved(std: Stdlib, precompiled: Set[String], archive: Option[String])

  /** The standard module for a target, however the caller wants it found.
   *
   * **This is the entry point anything embedding the compiler wants**, and it is here rather than in
   * the driver because it is not a property of a command line: a test suite, a documentation
   * harness and `sysl build` all need the same answer to the same question, and answering it three
   * ways is how the three drift.
   *
   * Every branch reads the library's source — two compile it and the third checks a prebuilt
   * artifact against a fingerprint of it — so a compiler that cannot find its library fails here,
   * once, with the diagnostic naming where it looked, rather than at whichever branch happened to
   * touch `Std.sources` first, where it would arrive as an exception.
   */
  def resolve(choice: Choice, target: Target): Either[String, Resolved] =
    Std.root.flatMap: _ =>
      choice match
        case Choice.FromSource      => Right(Resolved(fromSource(target), Set.empty, None))
        case Choice.Artifact(named) => load(named, target)
        case Choice.Default(search) =>
          val path = search.getOrElse(LibraryArtifact.stdDefault(target))

          resolved.synchronized(resolved.getOrElseUpdate((path, target), found(path, target)))

  /** The default path's answer, kept — for the reason `fromSource`'s copy is kept, and with the same
   * lock: a caller that resolves repeatedly would otherwise decode an unchanged artifact each time,
   * and a suite that compiles hundreds of programs is exactly such a caller.
   *
   * **Only the default path is memoized, and the key is what makes that safe.** The path is
   * `<cache>/sysl/<fingerprint>/std.syslib`, so a change to the library changes the *path* rather
   * than the file — a stale answer under one key is not reachable. A **named** artifact is
   * deliberately not kept: someone who wrote down which artifact to use may be rebuilding it, and
   * answering from memory would hand them the one they replaced.
   */
  private val resolved = collection.mutable.Map.empty[(String, Target), Either[String, Resolved]]

  /** The standard module at the path both ends agree on, **built there when what is there is not
   * one.**
   *
   * The artifact is a *derived* file: it is object code for one machine, it is not committed, and
   * every byte of it is computed from the library source the compiler already carries. So the states
   * it can be found in — absent after a clone or a fresh worktree, and stale after any change to the
   * tree encoding or the container — are not questions to put to whoever ran the compiler. They have
   * one answer, the compiler can produce it in well under a second, and it is the same answer every
   * time.
   *
   * **This is not the silent fallback the design refuses**, and the distinction is the whole of why
   * it is allowed. What is refused is compiling against a *different* standard module than the one
   * asked for — answering "I could not find your library" by quietly using another. A rebuild
   * answers with **this** library: the sources are the ones the compiler carries, `read` holds the
   * result to `Std.fingerprint` on the way back in, and a program compiled after it is compiled
   * against exactly what it would have been compiled against had the artifact been there. Nothing is
   * substituted, so there is nothing for a reader to be misled about.
   *
   * It says so on stderr rather than doing it invisibly. The work is a second of someone's time and
   * the line is what makes a slow first build explicable instead of mysterious.
   *
   * **A rebuild that cannot happen reports the original problem, not its own.** Without a toolchain
   * there is no artifact to make, and what the reader needs then is the sentence naming the command
   * and the flag — the same one they would have got before — with the reason the compiler could not
   * do it for them appended.
   */
  private def found(path: String, target: Target): Either[String, Resolved] = {
    val already = if isFile(path) then load(path, target) else Left(s"$path does not exist")

    already match
      case Right(got) => Right(got)
      case Left(why) =>
        Console.err.println(s"building the standard module at $path ($why)")

        writeArtifact(path, target) match
          case Right(_) => load(path, target)
          case Left(err) =>
            Left(s"cannot find or build the standard module — build it with " +
              s"'sysl build-lib lib --std', or pass --no-std-lib to compile against the copy built " +
              s"into the compiler ($err)")
  }

  /** A prebuilt standard module read back: the trees to compile against, the symbols its object half
   * already defines, and the archive to link that half from — which is the file itself, since it is
   * already the archive the linker wants.
   *
   * Every way this can go wrong — a file that is not ours, one built by another sysl, one built from
   * other sources than the compiler carries, a tree that will not decode — is a failure of the same
   * kind as not finding it at all, and is reported rather than worked around. A standard module that
   * cannot be read is not a standard module.
   */
  private def load(path: String, target: Target): Either[String, Resolved] = {
    val bytes =
      try readBytes(path)
      catch case e: Exception => return Left(s"cannot read $path: ${e.getMessage}")

    LibraryArtifact.metadataOf(path, bytes).flatMap(meta =>
      read(path, meta, target).map((std, symbols) => Resolved(std, symbols, Some(path))))
  }

  /** The library **parsed from its source**, as a given target sees it — the standard module the
   * long way round, and what an unusable artifact at the default path is rebuilt *from* rather than
   * replaced by (`Main.foundStd`). Reached directly only by `--no-std-lib`.
   *
   * The source is `Std.sources`, read off disk from wherever this compiler's library is installed
   * (`Std.root`). It was generated into the binary once and this was called `embedded`; the name
   * went with the mechanism, because a compilation that reads files can fail to find them and one
   * that unpacked a string could not.
   *
   * The target is a parameter for the reason it is one everywhere else, and here it is not merely
   * consistency: the library may gate on the machine (`Conditional`), so a copy parsed for one
   * target is not the library another target has.
   */
  def fromSource(target: Target): Stdlib =
    cache.synchronized(cache.getOrElseUpdate(target, new Stdlib(Std.parsed(target))))

  /** Locked for the reason `Std.parsed`'s is: this was a `lazy val`, which is initialized once
   * however many threads reach it, and a bare mutable `Map` is not.
   */
  private val cache = collection.mutable.Map.empty[Target, Stdlib]

  /** A standard module read out of the metadata half of an artifact (`LibraryArtifact`).
   *
   * The trees arrive already decoded, which is the whole point, and nothing downstream can tell
   * them from parsed ones — that is what `AstCodec` is for, and what the equivalence test holds it
   * to. The symbols the artifact's object half already defines come back beside it: they are what a
   * program **declares** rather than defines a second time, and they belong to the caller that has
   * a linker to hand them to.
   *
   * **An artifact built from a different `lib/sysl` is refused here**, which is the one check a
   * consumer of the *standard* module can make and a consumer of any other library cannot: the
   * compiler has the source this was supposed to be built from, so it can say whether it was
   * (`Std.fingerprint`). Without it the artifact is built separately and can drift — and a stale one
   * decodes and links perfectly well, it is simply the wrong library, which is the worst way for
   * this to fail. Refusing puts it on the path a corrupt one already takes: at the default path it is
   * rebuilt from the library's own source, and where `--std-lib` named it the refusal stands.
   */
  def read(name: String, metadata: String, target: Target): Either[String, (Stdlib, Set[String])] =
    LibraryArtifact.read(name, metadata, target).flatMap((units, precompiled, source) =>
      if source == Std.fingerprint then Right((new Stdlib(units), precompiled))
      else
        Left(s"$name was built from a different standard module than this compiler's — " +
          "rebuild it with 'sysl build-lib <library root> --std'"))

  /** The standard module compiled to an artifact at `out`, from **this** compiler's library source —
   * the other end of `read`, and what an unusable artifact at the default path is rebuilt by.
   *
   * The sources are `Std.sources` and not the `lib/` in whatever tree the command was run from,
   * which is what makes this dependable: `read` checks a decoded artifact against `Std.fingerprint`,
   * the fingerprint of exactly these files, so building from them is the one thing guaranteed to
   * produce an artifact this compiler will accept. Point it at another library and you get an
   * artifact it refuses, which is `build-lib --std`'s business rather than this routine's.
   *
   * No C files are gathered, and none can be missed: `15 §7` lets a library carry `.c` beside its
   * modules, and the standard module carries none. It could not — a `.c` under `lib/sysl` would be
   * hashed into the fingerprint of an artifact built by `build-lib`, while `Std.sources` collects
   * only sysl files, so every artifact built from the tree would fail the check it is read back
   * through.
   *
   * `ar` names the archiver where it is somewhere a search would not look, exactly as `--ar` does;
   * given none, the search runs.
   *
   * **The archive is assembled under another name and renamed onto `out`**, which is what makes the
   * default path safe to share. Two compilations of the same library compute the same fingerprint
   * and so name the same artifact, and there is nothing keeping them apart: separate worktrees at
   * one commit, or two runs of the same suite. `ar` truncates its output before it writes, so a
   * build that wrote in place would leave every concurrent reader a file that is briefly absent and
   * then briefly half an archive. Published by rename, a reader sees the whole of the previous
   * artifact or the whole of the new one — and a build that fails leaves the one that was already
   * there, rather than deleting a working artifact on its way to not producing one.
   */
  def writeArtifact(out: String, target: Target, ar: Option[String] = None): Either[String, Unit] =
    for
      archiver <- Toolchain.findAr(ar)
      built    <- LibraryArtifact.build(Std.sources, target, LibraryArtifact.std, Some(fromSource(target)))
      _        <- {
                    val staging  = createTempDirectory("sysl-std-")
                    val code     = s"$staging/${LibraryArtifact.codeMember}"
                    val metadata = s"$staging/${LibraryArtifact.metadataMember}"

                    Project.parentOf(out).foreach(createDirectories)

                    // Beside its destination rather than in the staging directory, because a rename
                    // is only a rename within one filesystem and the system's temporary directory
                    // need not be on the same one as the cache. The unique part of the name is the
                    // staging directory's own, which the system has already made unique — two
                    // builds racing for one artifact must not agree on a pending name either, or
                    // one of them would publish the other's half-written archive.
                    val pending = s"$out.${Project.basename(staging)}"

                    val outcome =
                      for
                        _ <- Toolchain.compileObject(built._1, code, target)
                        _ <- Toolchain.compileObject(LibraryArtifact.metadataIr(built._2, target), metadata, target)
                        _ <- Toolchain.archive(List(code, metadata), pending, archiver)
                        _ <- publish(pending, out)
                      yield ()

                    List(code, metadata, staging, pending).foreach(Project.discard)
                    outcome
                  }
    yield ()

  /** The rename itself, as an answer rather than an exception: everything else in the build reports
   * what went wrong by returning it, and a full disk or a read-only cache directory is the ordinary
   * way this step fails.
   */
  private def publish(pending: String, out: String): Either[String, Unit] =
    try Right(moveFile(pending, out))
    catch case e: Exception => Left(s"cannot put the standard module at $out: ${e.getMessage}")
}
