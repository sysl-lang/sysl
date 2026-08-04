package io.github.edadma.sysl

import io.github.edadma.cross_platform.*

/** The standard module **this** compilation is compiled against, and the one place that says where
 * its trees came from.
 *
 * Every program is compiled against the library (`13 §8`), and there has been one way to be handed
 * it: `Std.parsed` — the files under `lib/sysl` as the compiler carries them, parsed. That is what a
 * *source* dependence on the standard module is, and it is what the artifact exists to end: every program
 * re-derives every signature in the standard module from text before it can check its own first
 * line.
 *
 * A `.syslib` carries those same trees already decoded, and — for the half with nothing left to
 * monomorphize — the object code to link against rather than emit a second copy of. Which of the two
 * a compilation gets is this value.
 *
 * **It is a parameter rather than an ambient fact**, for the reason a `Target` is: that the compiler
 * happens to carry a copy of the library is not the same claim as this compilation being compiled
 * against that copy. Making it a parameter is also what lets the two be run side by side and their
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
   * these, and neither is the *embedded* copy of a file a decoded standard module carries under the same name.
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

  /** The copy the compiler carries, as **a given target** sees it — which is what an ordinary
   * compilation is compiled against.
   *
   * It has to carry *something*: the standard module is what every program is compiled against, so
   * it cannot be a thing a compilation goes looking for on disk and may not find. This is that
   * guarantee — and it is what an unusable artifact at the default path is rebuilt *from*, rather
   * than a library compiled against in its place (`Main.foundStd`). Reached directly only by
   * `--no-std-lib`.
   *
   * The target is a parameter for the reason it is one everywhere else, and here it is not merely
   * consistency: the library may gate on the machine (`Conditional`), so a copy parsed for one
   * target is not the library another target has.
   */
  def embedded(target: Target): Stdlib =
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
   * compiler carries the source this was supposed to be built from, so it can say whether it was
   * (`Std.fingerprint`). Without it the artifact is built separately and can drift — and a stale one
   * decodes and links perfectly well, it is simply the wrong library, which is the worst way for
   * this to fail. Refusing puts it on the path a corrupt one already takes: at the default path it is
   * rebuilt from the source the compiler carries, and where `--std-lib` named it the refusal stands.
   */
  def read(name: String, metadata: String, target: Target): Either[String, (Stdlib, Set[String])] =
    LibraryArtifact.read(name, metadata, target).flatMap((units, precompiled, source) =>
      if source == Std.fingerprint then Right((new Stdlib(units), precompiled))
      else
        Left(s"$name was built from a different standard module than this compiler carries — " +
          "rebuild it with 'sysl build-lib lib --std'"))

  /** The standard module compiled to an artifact at `out`, from the library source the compiler
   * carries — the other end of `read`, and what an unusable artifact at the default path is rebuilt
   * by.
   *
   * The sources are `Std.sources` and not the `lib/` in some tree, which is what makes this usable at
   * all: an installed compiler has no repository beside it, and `read` checks a decoded artifact
   * against `Std.fingerprint` — the fingerprint of exactly these files — so building from them is the
   * one thing guaranteed to produce an artifact this compiler will accept.
   *
   * No C files are gathered, and none can be missed: `15 §7` lets a library carry `.c` beside its
   * modules, and the standard module carries none. It could not — the fingerprint an artifact is held
   * to covers a library's C sources with its sysl ones, while the one the compiler carries covers only
   * what `StdSource` embeds, so a `.c` under `lib/sysl` would make every artifact built from the tree
   * fail the check it is read back through.
   *
   * `ar` names the archiver where it is somewhere a search would not look, exactly as `--ar` does;
   * given none, the search runs.
   */
  def writeArtifact(out: String, target: Target, ar: Option[String] = None): Either[String, Unit] =
    for
      archiver <- Toolchain.findAr(ar)
      built    <- LibraryArtifact.build(Std.sources, target, LibraryArtifact.std, Some(embedded(target)))
      _        <- {
                    val staging  = createTempDirectory("sysl-std-")
                    val code     = s"$staging/${LibraryArtifact.codeMember}"
                    val metadata = s"$staging/${LibraryArtifact.metadataMember}"

                    Project.parentOf(out).foreach(createDirectories)

                    val outcome =
                      for
                        _ <- Toolchain.compileObject(built._1, code, target)
                        _ <- Toolchain.compileObject(LibraryArtifact.metadataIr(built._2, target), metadata, target)
                        _ <- Toolchain.archive(List(code, metadata), out, archiver)
                      yield ()

                    List(code, metadata, staging).foreach(Project.discard)
                    outcome
                  }
    yield ()
}
