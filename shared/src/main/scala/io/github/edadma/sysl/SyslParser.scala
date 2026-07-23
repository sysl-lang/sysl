package io.github.edadma.sysl

import scala.util.parsing.combinator.PackratParsers
import scala.util.parsing.input.{NoPosition, Position, Reader}

/** The sysl parser: a packrat combinator grammar over the materialized token list from
 * `SyslLexical` (see docs/design/front-end.md).
 *
 * The precedence structure mirrors `01-scalar-types-and-operators.md`: assignment (loosest)
 * → `||` → `&&` → chained comparison → range → `|` → `^` → `&` → `+ -` →
 * `* / % << >>` → prefix unary → postfix (tightest). Left-associative binary levels are
 * built with `chainl1`, which iterates rather than left-recurses; the whole grammar still
 * runs under packrat so the choice stays linear.
 *
 * The `List[Token]` is the reversibility seam: a hand-written parser could later consume the
 * same tokens with no change to the lexer.
 */
class SyslParser extends PackratParsers {

  val lexical: SyslLexical = new SyslLexical
  type Elem = lexical.Token

  /** A reader over the pre-scanned, positioned token list. Immutable, so packrat may revisit
   * positions safely — unlike feeding the stateful scanner directly.
   */
  private class TokenReader(tokens: List[(lexical.Token, Position)]) extends Reader[lexical.Token] {
    def first: lexical.Token   = if (tokens.isEmpty) lexical.EOF else tokens.head._1
    def rest: Reader[lexical.Token] = if (tokens.isEmpty) this else new TokenReader(tokens.tail)
    def pos: Position          = if (tokens.isEmpty) NoPosition else tokens.head._2
    def atEnd: Boolean         = tokens.isEmpty
  }

  private def reader(src: String): Reader[lexical.Token] =
    new PackratReader(new TokenReader(lexical.scanPositioned(src)))

  // --- terminals -----------------------------------------------------------------------

  /** Matches a keyword or delimiter token by its exact spelling. Reserved words and
   * operators both lex as `Keyword`, so this covers `if`, `+`, `..<` alike.
   */
  private def op(sym: String): Parser[String] =
    accept(s"'$sym'", { case t: lexical.Keyword if t.chars == sym => sym })

  private lazy val ident: Parser[String] =
    accept("identifier", { case t: lexical.Identifier => t.chars })

  private val newline: Parser[Unit] = accept("newline", { case lexical.Newline => () })
  private val indent: Parser[Unit]  = accept("indent", { case lexical.Indent => () })
  private val dedent: Parser[Unit]  = accept("dedent", { case lexical.Dedent => () })

  private def newlines: Parser[Unit] = rep1(newline) ^^^ (())

  // --- expressions ---------------------------------------------------------------------

  lazy val expression: PackratParser[Expr] = assignment

  private def binOp(sym: String): Parser[(Expr, Expr) => Expr] =
    op(sym) ^^^ ((l: Expr, r: Expr) => Binary(sym, l, r))

  /** Assignment is right-associative and lowest precedence, so it recurses on its right. */
  lazy val assignment: PackratParser[Expr] =
    logicalOr ~ assignOp ~ assignment ^^ { case l ~ o ~ r => Assign(o, l, r) } |
      logicalOr

  private def assignOp: Parser[String] =
    op("=") | op("+=") | op("-=") | op("*=") | op("/=") | op("%=") |
      op("&=") | op("|=") | op("^=") | op("<<=") | op(">>=")

  lazy val logicalOr: PackratParser[Expr]  = chainl1(logicalAnd, binOp("||"))
  lazy val logicalAnd: PackratParser[Expr] = chainl1(comparison, binOp("&&"))

  /** Comparison chains rather than associates: `a < b < c` becomes one `Compare`. */
  lazy val comparison: PackratParser[Expr] =
    rangeExpr ~ rep(compareOp ~ rangeExpr) ^^ {
      case first ~ Nil  => first
      case first ~ rest => Compare(first :: rest.map { case _ ~ e => e }, rest.map { case o ~ _ => o })
    }

  private def compareOp: Parser[String] =
    op("==") | op("!=") | op("<=") | op(">=") | op("<") | op(">")

  private def rangeOp: Parser[Boolean] = op("..<") ^^^ false | op("..") ^^^ true

  /** Ranges are non-associative and sit below arithmetic, so each end is a `bitOr`. Either
   * end may be omitted (`a..`, `..b`).
   */
  lazy val rangeExpr: PackratParser[Expr] =
    bitOr ~ opt(rangeOp ~ opt(bitOr)) ^^ {
      case lo ~ None                => lo
      case lo ~ Some(inc ~ hiOpt)   => RangeExpr(Some(lo), hiOpt, inc)
    } |
      rangeOp ~ bitOr ^^ { case inc ~ hi => RangeExpr(None, Some(hi), inc) }

  lazy val bitOr: PackratParser[Expr]  = chainl1(bitXor, binOp("|"))
  lazy val bitXor: PackratParser[Expr] = chainl1(bitAnd, binOp("^"))
  lazy val bitAnd: PackratParser[Expr] = chainl1(additive, binOp("&"))
  lazy val additive: PackratParser[Expr] =
    chainl1(multiplicative, binOp("+") | binOp("-"))

  /** Shift binds like multiplication (the deliberate correction to C), so `<<`/`>>` live at
   * this level alongside `* / %`.
   */
  lazy val multiplicative: PackratParser[Expr] =
    chainl1(unary, binOp("*") | binOp("/") | binOp("%") | binOp("<<") | binOp(">>"))

  lazy val unary: PackratParser[Expr] =
    (op("-") | op("!") | op("~") | op("*") | op("&")) ~ unary ^^ { case o ~ e => Unary(o, e) } |
      (op("++") | op("--")) ~ unary ^^ { case o ~ e => PreIncDec(o, e) } |
      postfix

  lazy val postfix: PackratParser[Expr] =
    primary ~ rep(postfixTail) ^^ { case p ~ tails => tails.foldLeft(p)((acc, f) => f(acc)) }

  private lazy val postfixTail: PackratParser[Expr => Expr] =
    (op("[") ~> expression <~ op("]")) ^^ (idx => (e: Expr) => Index(e, idx)) |
      (op(".") ~> ident) ^^ (n => (e: Expr) => Field(e, n)) |
      (op("(") ~> repsep(expression, op(",")) <~ op(")")) ^^ (args => (e: Expr) => Call(e, args)) |
      op("?") ^^^ ((e: Expr) => TryExpr(e)) |
      op("++") ^^^ ((e: Expr) => PostIncDec("++", e)) |
      op("--") ^^^ ((e: Expr) => PostIncDec("--", e))

  lazy val primary: PackratParser[Expr] =
    floatLit | intLit | charLit | strLit | boolLit | identExpr | op("(") ~> parenTail

  /** After `(`: `)` is unit, one expression is a grouping, more are a tuple. */
  private lazy val parenTail: PackratParser[Expr] =
    op(")") ^^^ UnitLit() |
      expression ~ rep(op(",") ~> expression) <~ op(")") ^^ {
        case e ~ Nil  => e
        case e ~ more => Tuple(e :: more)
      }

  private lazy val intLit: Parser[Expr] =
    accept("integer literal", { case t: lexical.IntLit => IntLit(t.value, t.suffix) })

  private lazy val floatLit: Parser[Expr] =
    accept("float literal", { case t: lexical.FloatLit => FloatLit(t.text, t.suffix) })

  private lazy val charLit: Parser[Expr] =
    accept("character literal", { case t: lexical.CharLit => CharLit(t.codepoint) })

  private lazy val strLit: Parser[Expr] =
    accept("string literal", { case t: lexical.StrLit => StrLit(t.value) })

  private lazy val boolLit: Parser[Expr] =
    op("true") ^^^ BoolLit(true) | op("false") ^^^ BoolLit(false)

  private lazy val identExpr: Parser[Expr] = ident ^^ Ident.apply

  // --- statements ----------------------------------------------------------------------

  lazy val statement: PackratParser[Stmt] = varDecl | ifStmt | whileStmt | exprStmt

  private lazy val typeRef: Parser[TypeRef] = ident ^^ NamedType.apply

  private lazy val varDecl: PackratParser[Stmt] =
    op("var") ~> ident ~ opt(op(":") ~> typeRef) ~ (op("=") ~> expression) ^^ {
      case n ~ t ~ e => VarDecl(n, t, e)
    }

  private lazy val exprStmt: PackratParser[Stmt] = expression ^^ ExprStmt.apply

  /** An indented block: a leading `Newline`+`Indent` (the lexer's off-side signal) wraps a
   * statement sequence closed by `Dedent`.
   */
  private lazy val suite: PackratParser[List[Stmt]] =
    newline ~> indent ~> statements <~ dedent

  /** A single statement written on the same line as its control-flow keyword. */
  private lazy val inlineBody: PackratParser[List[Stmt]] = statement ^^ (s => List(s))

  /** The body of a control-flow construct, Scala-style: the introducer keyword (`then` /
   * `do`) is required for a one-line body but optional before an indented block, since a
   * following `Newline`+`Indent` already marks the block unambiguously.
   */
  private def body(keyword: String): Parser[List[Stmt]] =
    op(keyword) ~> (suite | inlineBody) | suite

  private lazy val ifStmt: PackratParser[Stmt] =
    op("if") ~> expression ~ body("then") ~ opt(elseClause) ^^ {
      case c ~ t ~ e => If(c, t, e.getOrElse(Nil))
    }

  /** `else` sits on a fresh line after a block body, or on the same line after an inline
   * one — so any intervening `Newline` is optional.
   */
  private lazy val elseClause: Parser[List[Stmt]] =
    opt(newlines) ~> op("else") ~> (suite | inlineBody)

  private lazy val whileStmt: PackratParser[Stmt] =
    op("while") ~> expression ~ body("do") ^^ { case c ~ b => While(c, b) }

  private lazy val statements: PackratParser[List[Stmt]] =
    opt(newlines) ~> repsep(statement, newlines) <~ opt(newlines)

  private lazy val program: PackratParser[Program] = statements ^^ Program.apply

  // --- entry points --------------------------------------------------------------------

  /** Parses a single expression (used by the expression test tier). */
  def parseExpression(src: String): ParseResult[Expr] =
    phrase(expression <~ rep(newline))(reader(src))

  /** Parses a whole program. */
  def parseProgram(src: String): ParseResult[Program] =
    phrase(program)(reader(src))
}

object SyslParser {

  /** Parses a program, returning either the AST or a human-readable error with location. */
  def parse(src: String): Either[String, Program] = {
    val p = new SyslParser

    p.parseProgram(src) match {
      case p.Success(prog, _) => Right(prog)
      case ns: p.NoSuccess =>
        Left(s"parse error at line ${ns.next.pos.line}, column ${ns.next.pos.column}: ${ns.msg}\n${ns.next.pos.longString}")
    }
  }
}
