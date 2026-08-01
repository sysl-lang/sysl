package io.github.edadma.sysl

import scala.util.parsing.combinator.PackratParsers
import scala.util.parsing.input.{NoPosition, Position, Reader}

/** What every part of the grammar stands on: the token reader, position stamping, the terminal
 * matchers, and the handful of shapes that recur at every level — a comma list, a soft word, an
 * indented suite, an `end` marker.
 *
 * It also declares, abstractly, the rules that are reached **across** the grammar's areas. A
 * language's grammar is not a tree of independent parts: an expression may be an `if` whose branches
 * are statements, a statement may be a declaration whose body is statements again, and a type appears
 * inside all three. Splitting the grammar across files therefore has to name those crossings rather
 * than pretend they do not exist, and naming them here is what lets each area be read on its own —
 * the list below *is* the coupling, in full, and nothing outside it reaches sideways.
 *
 * The traits are mixed in one order (`ExprParser`, then `DeclParser`, then `SyslParser` itself), so
 * a rule needs a declaration here only when it is used by an area that comes **before** the one that
 * defines it. That is why the list is shorter than the number of crossings.
 */
trait SyslParserBase extends PackratParsers {

  val source: Source


  val lexical: SyslLexical = new SyslLexical
  type Elem = lexical.Token

  /** A reader over the pre-scanned, positioned token list. Immutable, so packrat may revisit
   * positions safely — unlike feeding the stateful scanner directly.
   */
  protected class TokenReader(tokens: List[(lexical.Token, Position)]) extends Reader[lexical.Token] {
    def first: lexical.Token   = if (tokens.isEmpty) lexical.EOF else tokens.head._1
    def rest: Reader[lexical.Token] = if (tokens.isEmpty) this else new TokenReader(tokens.tail)
    def pos: Position          = if (tokens.isEmpty) NoPosition else tokens.head._2
    def atEnd: Boolean         = tokens.isEmpty
  }

  protected def reader(src: String): Reader[lexical.Token] =
    new PackratReader(new TokenReader(lexical.scanPositioned(src)))

  // --- positions -----------------------------------------------------------------------

  /** Where the next token starts, in this parser's source. */
  protected def posOf(in: Input): Pos = {
    val p = in.pos

    Pos(source, p.line, p.column)
  }

  /** Stamps whatever `p` builds with the position of the first token `p` consumed.
   *
   * Because `setPos` keeps the first position it is given (`Positioned`), wrapping an outer rule
   * never overwrites what an inner one already recorded — so a rule that merely passes its
   * operand through costs nothing, and only the rule that actually built the node decides where
   * it points. `p` is by-name so a rule may wrap a `lazy val` declared later in the file.
   */
  protected def at[T <: Positioned](p: => Parser[T]): Parser[T] =
    Parser { in =>
      p(in) match {
        case Success(t, rest) => Success(t.setPos(posOf(in)), rest)
        case other            => other
      }
    }

  /** The current position, consuming nothing — for a rule that builds its node from a tail it
   * has already passed, where the tail's own start is the better place to point.
   */
  protected def here: Parser[Pos] = Parser(in => Success(posOf(in), in))

  // --- terminals -----------------------------------------------------------------------

  /** Matches a keyword or delimiter token by its exact spelling. Reserved words and
   * operators both lex as `Keyword`, so this covers `if`, `+`, `..<` alike.
   */
  protected def op(sym: String): Parser[String] =
    accept(s"'$sym'", { case t: lexical.Keyword if t.chars == sym => sym })

  protected lazy val ident: Parser[String] =
    accept("identifier", { case t: lexical.Identifier => t.chars })

  /** `_`, which the lexer hands over as an ordinary identifier.
   *
   * Two productions read it and they never read the same position: a pattern's wildcard (`09`) and
   * an expression's placeholder (`12 §5c`). Both go through this one matcher so there is a single
   * answer to what the token is, rather than two spellings of the test that could drift apart.
   */
  protected lazy val wildcard: Parser[Unit] =
    accept("'_'", { case t: lexical.Identifier if t.chars == "_" => () })

  protected val newline: Parser[Unit] = accept("newline", { case lexical.Newline => () })
  protected val indent: Parser[Unit]  = accept("indent", { case lexical.Indent => () })
  protected val dedent: Parser[Unit]  = accept("dedent", { case lexical.Dedent => () })

  protected def newlines: Parser[Unit] = rep1(newline) ^^^ (())

  // --- reached across the grammar's areas -----------------------------------------------
  //
  // Each of these is defined in a later trait than one that uses it, so it is declared here for the
  // earlier one to reach. Overriding an abstract `def` with a `lazy val` is what keeps packrat
  // memoization on the concrete rule.

  /** A type, with or without a memory-mode sigil (`03`). */
  protected def typeRef: Parser[TypeRef]

  /** One statement, of any of the forms `statement` admits. */
  protected def statement: PackratParser[Stmt]

  /** An indented block of statements. */
  protected def suite: PackratParser[List[Stmt]]

  /** A block introduced by a keyword, written inline after it or indented under it. */
  protected def body(keyword: String): Parser[List[Stmt]]

  /** A soft keyword: a word that is only special where the grammar expects it, and an ordinary
   * identifier everywhere else (`front-end.md`). `sync`, `Fn`, `end`, `new`, `within` and `where`
   * are all of them, which is why the three below are declared beside it.
   */
  protected def softWord(word: String): Parser[Unit]

  protected def softSync: Parser[Unit]
  protected def softEnd: Parser[Unit]
  protected def fnWord: Parser[Unit]

  /** A function's declared result: one type, or several separated by commas (`12 §5b`). */
  protected def resultRef: Parser[TypeRef]

  /** `[T, U: Show, V = int]` — the generic parameter list every declaration form reads the same way,
   * and the one bound within it, which a `trait`'s supertrait list also reads.
   */
  protected def boundedTypeParams: Parser[TypeParams]
  protected def boundRef: Parser[BoundRef]

  /** A name that may be reached through the module it belongs to: `File`, `std.fs.File`, and the
   * `[int, string]` an applied generic one carries.
   */
  protected def qualifiedName: Parser[String]
  protected def typeArgs: Parser[List[TypeRef]]

  /** `private`, `private[M]`, or nothing at all — which is public (`13 §2`). */
  protected def visibility: Parser[Visibility]

  /** A statement written on the same line as the keyword that introduces it. */
  protected def inlineStatement: PackratParser[Stmt]

  /** A whole file's worth of statements. */
  protected def statements: PackratParser[List[Stmt]]

  /** The expression a one-line function body is, which is an expression that may also be the comma
   * list a multi-result signature returns.
   */
  protected def resultValue: PackratParser[Expr]

  // The five expression forms that are written like statements. `expression` admits them directly,
  // which is what makes `var x = if c then a else b` a binding rather than a special case.

  protected def ifExpr: PackratParser[Expr]
  protected def whileExpr: PackratParser[Expr]
  protected def loopExpr: PackratParser[Expr]
  protected def forExpr: PackratParser[Expr]
  protected def matchExpr: PackratParser[Expr]
}
