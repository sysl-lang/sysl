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
    testAttr ^^ Attr.Test.apply | tailrecAttr | pureAttr | ghostAttr | unknownAttr | hashAttr

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

  private lazy val unknownAttr: PackratParser[Attr] =
    op("@") ~> ident >> (n =>
      err(s"'$n' is not an annotation a declaration takes — '@test', '@tailrec', '@pure' and " +
        "'@ghost' are the four. '@no_<capability>', '@requires(...)', '@link(\"...\")' and '@tests' " +
        "belong in the file's header"))

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

  /** The attribute written twice, where one is, for the refusal above. */
  protected def duplicated(as: List[Attr]): Option[String] =
    as.map(_.word).groupBy(identity).collectFirst { case (w, ws) if ws.length > 1 => w }

  protected def attributed(f: FuncDecl, as: List[Attr]): FuncDecl =
    as.foldLeft(f) {
      case (d, Attr.Test(t)) => d.copy(test = Some(t))
      case (d, Attr.TailRec) => d.copy(tailrec = true)
      case (d, Attr.Pure)    => d.copy(pure = true)
      case (d, Attr.Ghost)   => d.copy(ghost = true)
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
