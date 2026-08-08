package sh.sysl

/** The annotations written above a declaration — `@test`, `@tailrec`, `@pure`, `@ghost`.
 *
 * Each alternative reads its own `@`, and the two misspellings a reader arrives with — an
 * unknown word, and `#` where `@` was meant — are rules here rather than parse failures, because
 * what such a reader is missing is which sigil marks what.
 *
 * The fold onto `FuncDecl` lives here too: an attribute exists to say something about the
 * declaration under it, so what it says and how it is applied are one area.
 */
trait AttrParser extends ExprParser {

  /** One annotation: what it says about the function under it. Each alternative reads the `@` for
   * itself so that the annotation's own position is the `@`, which is the line a test report names.
   */
  protected lazy val attribute: PackratParser[Attr] =
    testAttr ^^ Attr.Test.apply | tailrecAttr | pureAttr | ghostAttr | readsAttr | writesAttr |
      packedAttr | alignAttr | unknownAttr | hashAttr

  /** `@packed` — fields at their declared offsets with no interior padding, and an aggregate that
   * needs no alignment of its own (`15 §1`). It takes no arguments: there is nothing to configure
   * about the absence of a gap.
   */
  protected lazy val packedAttr: PackratParser[Attr] =
    op("@") ~> attrWord("packed") ^^ (_ => Attr.Packed)

  /** `@align(n)` — the boundary storage of this type must begin on, which may only be raised.
   *
   * The bound is an expression because it is folded rather than lexed: `@align(CACHE_LINE)` is the
   * form worth writing, and a program that had to repeat the number would be stating the same fact
   * in two places. What it may be is the constant set of `13 §5`.
   */
  protected lazy val alignAttr: PackratParser[Attr] =
    op("@") ~> attrWord("align") ~> (op("(") ~> expression <~ op(")") ^^ Attr.Align.apply | alignErr)

  private def alignErr: Parser[Attr] =
    err("'@align' names the boundary in parentheses — '@align(64)', or '@align(CACHE_LINE)' for a " +
      "constant that says what the number is for. There is no bare form: an alignment with no " +
      "number is not a weaker claim, it is no claim")

  /** `@test`, and the three things it may say about the test: the name a report gives it, that it is
   * a run which should not come back, and the text such a run should have printed on its way out.
   */
  protected lazy val testAttr: PackratParser[TestAttr] =
    at(op("@") ~> attrWord("test") ~> opt(op("(") ~> testArgs <~ op(")"))
      ^^ (_.getOrElse(TestAttr(None, false, None))))

  /** `@tailrec` — the assertion that this function's call to itself is the last thing it does
   * (`12 § Tail calls`). It takes no arguments: there is nothing to configure about a jump, and
   * what the annotation buys is the refusal when there is no jump to make.
   */
  protected lazy val tailrecAttr: PackratParser[Attr] =
    op("@") ~> attrWord("tailrec") ^^ (_ => Attr.TailRec)

  /** `@pure` — the assertion that a caller can observe nothing about this call but its result
   * (`17 §6`). Like `@tailrec` it takes no arguments: purity is not a thing to configure, and what
   * the annotation buys is the refusal when the body does something a caller could observe.
   */
  protected lazy val pureAttr: PackratParser[Attr] =
    op("@") ~> attrWord("pure") ^^ (_ => Attr.Pure)

  /** `@ghost` — the function exists for the specification alone and is erased before codegen
   * (`17 §8`).
   */
  protected lazy val ghostAttr: PackratParser[Attr] =
    op("@") ~> attrWord("ghost") ^^ (_ => Attr.Ghost)

  /** `@reads(a, b)` and `@writes(c)` — which module-level variables the function may touch
   * (`17 §7`). The parentheses are mandatory and may be empty, because `@reads()` is a real and
   * different claim from writing nothing at all: the first says the function reads no module
   * storage, the second says nobody has written down what it does.
   *
   * The argument list is raised **inside** the parentheses rather than after the closing one, per
   * the rule a dead `err` taught: a form that gets further along the line outranks an alternative
   * that failed earlier, so a message written past the point of divergence is never the one
   * reported. Here the only way to fail after `@reads` is the parenthesis, so that is where the
   * sentence goes — otherwise `unknownAttr` below wins by position and says `reads` is not an
   * annotation, which is both wrong and unhelpful.
   */
  protected lazy val readsAttr: PackratParser[Attr] =
    op("@") ~> attrWord("reads") ~> (frameNames ^^ Attr.Reads.apply | frameErr("reads"))

  protected lazy val writesAttr: PackratParser[Attr] =
    op("@") ~> attrWord("writes") ~> (frameNames ^^ Attr.Writes.apply | frameErr("writes"))

  private lazy val frameNames: Parser[List[String]] =
    op("(") ~> repsep(ident, op(",")) <~ op(")")

  private def frameErr(w: String): Parser[Attr] =
    err(s"'@$w' names the module variables it covers, in parentheses — '@$w(count)', or '@$w()' " +
      "for none. The parentheses are what tell a frame that covers nothing from a function that " +
      "never said")

  private lazy val unknownAttr: PackratParser[Attr] =
    op("@") ~> ident >> (n =>
      err(s"'$n' is not an annotation a declaration takes — '@test', '@tailrec', '@pure', " +
        "'@ghost', '@reads(...)' and '@writes(...)' mark a function, and '@packed' and " +
        "'@align(n)' mark a struct's layout. '@no_<capability>', " +
        "'@requires(...)', '@link(\"...\")' and '@tests' belong in the file's header"))

  /** `#test` where `@test` was meant — the sigil a reader arriving from Rust or C reaches for first.
   *
   * It is answered here rather than left to the lexer because the two sigils mark two different
   * kinds of thing, and saying which is which is the whole of what the reader is missing: `#` gates
   * lines before the lexer sees them and sits at the margin, `@` says something about the
   * declaration under it. A directive word is *not* named here, since `#if` at the margin never
   * reaches the grammar at all.
   */
  private lazy val hashAttr: PackratParser[Attr] =
    op("#") ~> ident >> (n =>
      err(s"an annotation is written '@$n' — '#' opens a directive, which gates lines before the " +
        "lexer sees them and sits at the margin"))

  /** An annotation's name. Each stays an ordinary identifier — reserving them would spend the words
   * out of every program's namespace for the sake of one line apiece, which is the trade `alloc`
   * made and the one `capabilities.md § Open` is still paying for.
   */
  protected def attrWord(w: String): Parser[Unit] =
    accept(s"'$w'", { case t: lexical.Identifier if t.chars == w => () })

  /** An annotation whose name *carries* its argument — `@no_alloc` is `no_` and then the capability.
   * What comes back is the part after the prefix, so the caller never sees the joint.
   *
   * The prefix alone is not one of these: `@no_` names no capability, and matching it would hand the
   * analyzer an empty name to complain about instead of the parser refusing a word that is visibly
   * unfinished.
   */
  protected def attrWordPrefixed(prefix: String): Parser[String] =
    accept(
      s"'$prefix…'",
      { case t: lexical.Identifier if t.chars.startsWith(prefix) && t.chars.length > prefix.length =>
        t.chars.drop(prefix.length)
      },
    )

  /** Whether an attribute is one half of a frame, for the refusal of `@pure` beside one. */
  protected def frame(a: Attr): Boolean = a match
    case _: Attr.Reads | _: Attr.Writes => true
    case _                              => false

  /** The attribute written twice, where one is, for the refusal above. */
  protected def duplicated(as: List[Attr]): Option[String] =
    as.map(_.word).groupBy(identity).collectFirst { case (w, ws) if ws.length > 1 => w }

  protected def attributed(f: FuncDecl, as: List[Attr]): FuncDecl =
    as.foldLeft(f) {
      case (d, Attr.Test(t)) => d.copy(test = Some(t))
      case (d, Attr.TailRec) => d.copy(tailrec = true)
      case (d, Attr.Pure)    => d.copy(pure = true)
      case (d, Attr.Ghost)   => d.copy(ghost = true)
      case (d, Attr.Reads(ns))  => d.copy(reads = Some(ns))
      case (d, Attr.Writes(ns)) => d.copy(writes = Some(ns))
      // A layout attribute never reaches here: the grammar routes a declaration carrying one to a
      // struct, and refuses the mix. Listed so that a new attribute makes this fold fail to compile
      // rather than silently drop what it was asked to record.
      case (d, Attr.Packed | _: Attr.Align) => d
    }

  private lazy val testArgs: Parser[TestAttr] =
    contractMsg ~ opt(op(",") ~> testExpectation) ^^ {
      case d ~ e => TestAttr(Some(d), e.isDefined, e.flatten)
    } | testExpectation ^^ (e => TestAttr(None, true, e))

  /** `should_trap`, alone or with the substring a trapping run must have printed. */
  private lazy val testExpectation: Parser[Option[String]] =
    accept("'should_trap'", { case t: lexical.Identifier if t.chars == "should_trap" => () }) ~>
      opt(op(":") ~> contractMsg)
}
