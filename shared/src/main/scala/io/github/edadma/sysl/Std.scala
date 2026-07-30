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
 * **The source is embedded rather than read off disk**, as the prelude's is, because packaging is a
 * separate question from meaning: how the standard module is shipped — as text, as a checked-in
 * `AstCodec` artifact, as files a driver finds — changes what a compilation *costs* and nothing
 * about what it means. Mixing the two would make a failure ambiguous about which caused it.
 *
 * **`FormatSpec` is what a format string's flags are carried in** (`14 §2`, `§8`): the field the
 * whole value occupies, the precision each renderer reads in its own terms, and which side the
 * padding goes on. What honours it is the `display_*` family, whose prose in `Prelude` is where the
 * three fields are explained. It is here first because it depends on nothing — a move is measured by
 * what breaks, and a leaf is what makes that measurement about the mechanism rather than about the
 * declaration.
 */
object Std {

  /** The module these declarations belong to. The header below says the same thing, and a test holds
   * the two to agreeing — this is a constant so that nothing has to parse to ask the question.
   */
  val module: String = "sysl"

  val source: String =
    """module sysl
      |
      |struct FormatSpec
      |    width: int
      |    prec: int
      |    left: bool
      |""".stripMargin

  /** The source these declarations point into, so a diagnostic against one quotes the standard
   * module rather than the user's file at some unrelated line — and so a declaration can be told to
   * have come from here, which is half of what `Library.owns` answers.
   */
  val origin: Source = Source("<sysl>", source)

  /** The parsed standard module, parsed once. */
  lazy val parsed: Program =
    SyslParser.parse(origin) match
      case Right(p) => p
      case Left(e)  => sys.error(s"the standard module does not parse: $e")

  def decls: List[Stmt] = parsed.body

  /** Whether a declaration came from here rather than from the program being compiled. */
  def declares(s: Positioned): Boolean = s.pos.exists(_.source eq origin)
}
