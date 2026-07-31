package io.github.edadma.sysl

/** The standard module, `sysl` — the module every program is compiled against, and the whole of the
 * library (`13 § Open h`).
 *
 * What a program starts with is a *module*, not a set of declarations threaded in beside it. It was
 * the latter once: a `Prelude` of sysl source held in a string inside the compiler, keyed under the
 * anonymous root module. Its declarations were drained into this one a surface at a time, both
 * mechanisms live throughout, so that there was no commit at which neither worked. A switch would
 * have put every unqualified name in every program onto a path nothing had ever exercised, where a
 * single hole fails every test at once with nothing to bisect; a move put **one** declaration onto
 * that path, so what broke named what was wrong.
 *
 * **The source is real files, under `lib/sysl`.** That is what a prelude could never be: a string
 * literal has no other form, while these are ordinary sysl files a driver reads exactly as it reads
 * a user's library — which is what `sysl build-lib` is pointed at, and what makes the library's own
 * source something a reader can open. What the compiler carries is **generated from them**
 * (`CoreSource`, written by `build.sbt`), so the files are the fact and the carrier cannot disagree
 * with them.
 *
 * It has to carry *something*, and that is not a packaging accident. The standard module is what
 * every program is compiled against, so it cannot be a thing a compilation goes looking for on disk
 * and may not find. This is that guarantee, and `Core.embedded` is it seen as a library.
 *
 * **What a given compilation was handed is a different question**, and it is `Core` that answers it:
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

  /** The module these declarations belong to. Every file's header says the same thing, and a test
   * holds them to agreeing — this is a constant so that nothing has to parse to ask the question.
   */
  val module: String = "sysl"

  /** The standard module's files, in the order the generator found them — that is, sorted by
   * name, so that what a compilation sees does not depend on a directory listing.
   *
   * Each carries the directory it sits in below `lib/`, which is the module its header has to agree
   * with (`13 §1`), so these are the same `Source` values the driver would build from disk.
   */
  val sources: List[Source] =
    CoreSource.files.map((name, text) => Source(name, text, List(module)))

  /** What the library's source amounts to, for telling a prebuilt artifact built from *this* library
   * from one built from a different version of it.
   *
   * The artifact is built separately from the compiler that consumes it, and the two can therefore
   * fall out of step: build one, edit `lib/sysl`, and every compilation after that would be against
   * a standard module that is not the one in the tree. Nothing else would notice — a stale artifact
   * decodes perfectly and links perfectly, it is just the wrong library. This is what an artifact is
   * held to on the way in ([[Core.read]]).
   *
   * It composes with the other guard rather than duplicating it: edit `lib/sysl` and rebuild the
   * compiler, and this moves, so a stale artifact is caught here; edit and *don't* rebuild, and
   * `CoreSource` is the stale thing, which is what `CoreLibraryTests` is for. Between them there is
   * no gap.
   */
  lazy val fingerprint: String = LibraryArtifact.fingerprint(sources)

  /** The parsed standard module, parsed once. */
  lazy val parsed: List[Program] =
    sources.map(s =>
      SyslParser.parse(s) match
        case Right(p) => p
        case Left(e)  => sys.error(s"the standard module does not parse: $e"),
    )

  def decls: List[Stmt] = parsed.flatMap(_.body)
}
