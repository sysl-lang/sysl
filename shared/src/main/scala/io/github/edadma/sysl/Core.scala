package io.github.edadma.sysl

/** The standard module **this** compilation is compiled against, and the one place that says where
 * its trees came from.
 *
 * Every program is compiled against the library (`13 §8`), and there has been one way to be handed
 * it: `Std.parsed` — the files under `lib/sysl` as the compiler carries them, parsed. That is what a
 * *source* dependence on the core is, and it is what the artifact exists to end: every program
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
 * **Only three questions turn on which core it is**, which is why this is a small type. What trees
 * the library contributes, which sources are its rather than the program's, and which declarations
 * are its. Everything else the compiler knows about the library — the module's name, the key a
 * declaration is filed under, the spelling a key renders as — is true of the standard module however
 * it arrived, and lives in `Library`.
 */
final class Core(val units: List[Program]) {

  /** The files these trees came from. A `Source` compares by identity (`Diagnostics`), so this is
   * an identity set: a user file that happened to be called `lib/sysl/display.sysl` is not one of
   * these, and neither is the *embedded* copy of a file a decoded core carries under the same name.
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

object Core {

  /** The copy the compiler carries, which is what an ordinary compilation is compiled against.
   *
   * It has to carry *something*: the standard module is what every program is compiled against, so
   * it cannot be a thing a compilation goes looking for on disk and may not find. This is that
   * guarantee, and it is also the fallback — a compilation handed an artifact it cannot read is
   * better off compiling against this than not compiling at all.
   */
  lazy val embedded: Core = new Core(Std.parsed)

  /** A core read out of the metadata half of an artifact (`LibraryArtifact`).
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
   * this to fail. Refusing puts it on the path a corrupt one already takes: a warning, and the copy
   * the compiler carries.
   */
  def read(name: String, metadata: String): Either[String, (Core, Set[String])] =
    LibraryArtifact.read(name, metadata).flatMap((units, precompiled, source) =>
      if source == Std.fingerprint then Right((new Core(units), precompiled))
      else
        Left(s"$name was built from a different standard module than this compiler carries — " +
          "rebuild it with 'sysl build-lib lib --core'"))
}
