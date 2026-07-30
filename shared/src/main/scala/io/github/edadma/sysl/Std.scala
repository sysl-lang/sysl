package io.github.edadma.sysl

/** The standard module, `sysl` — the module every program is compiled against, and what the prelude
 * is being emptied into (`13 § Open h`).
 *
 * The end state is that this holds everything `Prelude` holds today and `Prelude` holds nothing, at
 * which point the prelude mechanism goes away and what a program starts with is a *module* rather
 * than a set of declarations threaded in beside it. The way there is one declaration at a time. A
 * switch would put every unqualified name in every program onto a path nothing had ever exercised,
 * and a single hole in it fails every test at once with nothing to bisect; a move puts **one**
 * declaration onto that path, so what breaks names what is wrong.
 *
 * The two mechanisms therefore coexist, and there is no commit at which neither works. A declaration
 * here is reached exactly as one in `Prelude` is — `Library` is the one thing that knows which of
 * the two a name is in, and by the end it will know only this one.
 *
 * **The source is real files, under `lib/sysl`.** That is the difference between a standard
 * module and a second prelude: the prelude is a string literal inside the compiler and could never
 * be anything else, while these are ordinary sysl files a driver reads exactly as it reads a user's
 * library — which is what `sysl build-lib` is pointed at, and what makes the library's own source
 * something a reader can open. What the compiler carries is **generated from them** (`CoreSource`,
 * written by `build.sbt`), so the files are the fact and the carrier cannot disagree with them.
 *
 * It has to carry *something*, and that is not a packaging accident. The standard module is what
 * every program is compiled against, so it cannot be a thing a compilation goes looking for on disk
 * and may not find. How it is carried — as text, as a checked-in `AstCodec` artifact — changes what
 * a compilation *costs* and nothing about what it means, so the two questions are kept apart.
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

  /** The parsed standard module, parsed once. */
  lazy val parsed: List[Program] =
    sources.map(s =>
      SyslParser.parse(s) match
        case Right(p) => p
        case Left(e)  => sys.error(s"the standard module does not parse: $e"),
    )

  def decls: List[Stmt] = parsed.flatMap(_.body)

  /** Whether a declaration came from here rather than from the program being compiled. Sources
   * compare by identity, so a user file that happened to be called `lib/sysl/display.sysl` is not
   * this one.
   */
  def declares(s: Positioned): Boolean = s.pos.exists(p => sources.exists(_ eq p.source))
}
