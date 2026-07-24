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

  /** `if` and `match` are expressions (they yield the taken branch's value), so they sit at
   * the top of the grammar — an ordinary operand everywhere an expression is expected.
   */
  lazy val expression: PackratParser[Expr] = ifExpr | matchExpr | whileExpr | forExpr | assignment

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
      rangeOp ~ bitOr ^^ { case inc ~ hi => RangeExpr(None, Some(hi), inc) } |
      rangeOp ^^ (inc => RangeExpr(None, None, inc))

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
    floatLit | intLit | charLit | strLit | boolLit | nullLit | selfExpr | identExpr | arrayLit |
      op("(") ~> parenTail

  /** `self` is reserved, so it never lexes as an identifier; inside a method body it reads as an
   * ordinary name that the analyzer resolves to the receiver binding, and is undefined elsewhere.
   */
  private lazy val selfExpr: Parser[Expr] = op("self") ^^^ Ident("self")

  /** `[a, b, c]` — an array literal. A leading `[` is unambiguous in operand position, since a
   * subscript is a postfix tail on something already parsed.
   */
  private lazy val arrayLit: PackratParser[Expr] =
    op("[") ~> repsep(expression, op(",")) <~ op("]") ^^ ArrayLit.apply

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

  private lazy val nullLit: Parser[Expr] = op("null") ^^^ NullLit()

  private lazy val identExpr: Parser[Expr] = ident ^^ Ident.apply

  // --- statements ----------------------------------------------------------------------

  lazy val statement: PackratParser[Stmt] =
    structDecl | enumDecl | funcDecl | varDecl | returnStmt | breakStmt | continueStmt | exprStmt

  /** A type: a memory-mode sigil applied to a type, or a name optionally applied to type
   * arguments (`Box[int]`, `Result[T, string]`). `sync` stays a soft keyword — it is only
   * special immediately after `&`, and the `&sync T` alternative is tried first so that a
   * reference to a type actually named `sync` still parses.
   */
  private lazy val typeRef: Parser[TypeRef] =
    op("*") ~> typeRef ^^ PtrType.apply |
      op("&") ~> softSync ~> typeRef ^^ (t => RefType(t, sync = true)) |
      op("&") ~> typeRef ^^ (t => RefType(t, sync = false)) |
      (op("[") ~> opt(expression) <~ op("]")) ~ typeRef ^^ { case n ~ t => ArrayType(n, t) } |
      ident ~ opt(op("[") ~> rep1sep(typeRef, op(",")) <~ op("]")) ^^ {
        case n ~ args => NamedType(n, args.getOrElse(Nil))
      }

  private lazy val softSync: Parser[Unit] =
    accept("'sync'", { case t: lexical.Identifier if t.chars == "sync" => () })

  /** The `[T, U]` type-parameter list of a generic declaration. */
  private lazy val typeParams: Parser[List[String]] =
    op("[") ~> rep1sep(ident, op(",")) <~ op("]")

  private lazy val varDecl: PackratParser[Stmt] =
    op("var") ~> ident ~ opt(op(":") ~> typeRef) ~ opt(op("=") ~> expression) ^^ {
      case n ~ t ~ e => VarDecl(n, t, e)
    }

  private lazy val exprStmt: PackratParser[Stmt] = expression ^^ ExprStmt.apply

  private lazy val returnStmt: PackratParser[Stmt] =
    op("return") ~> opt(expression) ^^ Return.apply

  private lazy val breakStmt: PackratParser[Stmt] =
    op("break") ~> opt(expression) ^^ Break.apply

  private lazy val continueStmt: PackratParser[Stmt] =
    op("continue") ^^^ Continue

  /** One `name: type` binding — a function parameter or a struct field. */
  private lazy val param: Parser[Param] =
    ident ~ (op(":") ~> typeRef) ^^ { case n ~ t => Param(n, t) }

  /** A function declaration, Scala-style but keyword-less: `name[T…](params) -> ret = expr` or
   * a block body, `-> ret` optional (absent ⇒ `unit`). It is tried before an expression
   * statement; a bare call `foo(1)` fails here (its arguments are not `name: type` bindings,
   * and nothing follows to open a body) and falls through to `exprStmt`.
   */
  private lazy val funcDecl: PackratParser[Stmt] =
    ident ~ opt(typeParams) >> { case name ~ tps =>
      (op("(") ~> repsep(param, op(",")) <~ op(")")) ~ opt(op("->") ~> typeRef) ~ funcBody <~ endName(name) ^^ {
        case params ~ ret ~ body => FuncDecl(name, tps.getOrElse(Nil), params, ret, body)
      }
    }

  /** A function body is either an `= expr` short form (whose value is the return value) or an
   * indented block (whose trailing expression is the return value).
   */
  private lazy val funcBody: PackratParser[List[Stmt]] =
    op("=") ~> (suite | expression ^^ (e => List(ExprStmt(e)))) | suite

  private lazy val structDecl: PackratParser[Stmt] =
    op("struct") ~> ident ~ opt(typeParams) >> { case name ~ tps =>
      (newline ~> indent ~> opt(newlines) ~> repsep(structItem, newlines) <~ opt(newlines) <~ dedent) <~ endName(name) ^^ {
        items =>
          val fields  = items.collect { case Left(f)  => f }
          val members = items.collect { case Right(m) => m }
          StructDecl(name, tps.getOrElse(Nil), fields, members)
      }
    }

  /** A line inside a struct body is either a `name: type` field or a member declaration. A member
   * is tried first: it needs a `(` (a method or associated function) or a `->` (a property) after
   * the name, so a bare field falls through to `param`.
   */
  private lazy val structItem: Parser[Either[Param, MethodDecl]] =
    member ^^ (Right(_)) | param ^^ (Left(_))

  /** A member of a type's body. What follows the name decides the kind: `(params)` is a method
   * (or, with no `self`, an associated function), and `-> type = body` with no parameter list is
   * a computed property.
   */
  private lazy val member: PackratParser[MethodDecl] =
    ident ~ opt(typeParams) >> { case name ~ tps =>
      methodTail(name, tps.getOrElse(Nil)) |
        (if tps.isEmpty then propertyTail(name) else failure("a property takes no type parameters"))
    }

  private def methodTail(name: String, tparams: List[String]): Parser[MethodDecl] =
    (op("(") ~> methodParams <~ op(")")) ~ opt(op("->") ~> typeRef) ~ funcBody <~ endName(name) ^^ {
      case (recv, params) ~ ret ~ body => MethodDecl(name, recv, isProperty = false, tparams, params, ret, body)
    }

  private def propertyTail(name: String): Parser[MethodDecl] =
    (op("->") ~> typeRef) ~ (op("=") ~> expression) <~ endName(name) ^^ {
      case ret ~ e => MethodDecl(name, None, isProperty = true, Nil, Nil, Some(ret), List(ExprStmt(e)))
    }

  /** The parenthesised part of a method: an optional receiver shorthand (`self`, `*self`,
   * `&self`, `&sync self`) followed by ordinary `name: type` parameters. With no receiver the
   * member is an associated function.
   */
  private lazy val methodParams: Parser[(Option[RecvMode], List[Param])] =
    receiver ~ rep(op(",") ~> param) ^^ { case r ~ ps => (Some(r), ps) } |
      repsep(param, op(",")) ^^ (ps => (None, ps))

  private lazy val receiver: Parser[RecvMode] =
    op("*") ~> op("self") ^^^ RecvMode.ByPtr |
      op("&") ~> softSync ~> op("self") ^^^ RecvMode.ByRef(sync = true) |
      op("&") ~> op("self") ^^^ RecvMode.ByRef(sync = false) |
      op("self") ^^^ RecvMode.ByValue

  /** `enum Name[T…]` with indented variants. A variant is a bare name (`Empty`), a name with an
   * explicit integer value (`Blue = 10`), or a name with a payload (`Circle(radius: int)`).
   */
  private lazy val enumDecl: PackratParser[Stmt] =
    op("enum") ~> ident ~ opt(typeParams) >> { case name ~ tps =>
      (newline ~> indent ~> opt(newlines) ~> repsep(enumVariant, newlines) <~ opt(newlines) <~ dedent) <~ endName(name) ^^ {
        variants => EnumDecl(name, tps.getOrElse(Nil), variants)
      }
    }

  private lazy val enumVariant: Parser[EnumVariantDecl] =
    ident ~ (op("(") ~> repsep(param, op(",")) <~ op(")")) ^^ { case n ~ fs => EnumVariantDecl(n, None, fs) } |
      ident ~ (op("=") ~> expression) ^^ { case n ~ v => EnumVariantDecl(n, Some(v), Nil) } |
      ident ^^ (n => EnumVariantDecl(n, None, Nil))

  /** An optional `end Name` marker closing a declaration block, Scala-style. `end` is a soft
   * keyword; the trailing name must equal the declaration's own name, or it is a parse error.
   */
  private def endName(name: String): Parser[Unit] =
    opt(opt(newlines) ~> softEnd ~> checkedEndName(name)) ^^^ (())

  private def checkedEndName(expected: String): Parser[Unit] =
    ident >> { n =>
      if n == expected then success(()) else err(s"'end $n' does not match '$expected'")
    }

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

  /** `if cond then a else b` — an expression. Its branches are statement lists whose trailing
   * expression is the branch value; `elif` nests into the else branch, and the `else` is
   * optional (a missing one gives an open branch that only the analyzer's unit rule allows).
   */
  private lazy val ifExpr: PackratParser[Expr] =
    op("if") ~> expression ~ body("then") ~ rep(elifClause) ~ opt(elseClause) ~ opt(endMarker("if")) ^^ {
      case c ~ t ~ elifs ~ e ~ _ =>
        val elseChain = elifs.foldRight(e) { case ((ec, eb), acc) => Some(List(ExprStmt(IfExpr(ec, eb, acc)))) }
        IfExpr(c, t, elseChain)
    }

  /** `end` is a soft keyword — an ordinary identifier everywhere except immediately before a
   * construct keyword, where `end if` / `end while` / `end for` close the preceding block
   * Scala-style. It is optional; matching it here (rather than reserving `end`) keeps `end`
   * usable as a name.
   */
  private lazy val softEnd: Parser[Unit] =
    accept("'end'", { case t: lexical.Identifier if t.chars == "end" => () })

  private def endMarker(construct: String): Parser[Unit] =
    opt(newlines) ~> softEnd ~> op(construct) ^^^ (())

  /** `elif cond then …` is sugar for `else if cond then …` — each one nests into the else
   * branch of the previous, so no distinct AST node is needed.
   */
  private lazy val elifClause: Parser[(Expr, List[Stmt])] =
    opt(newlines) ~> op("elif") ~> expression ~ body("then") ^^ { case c ~ b => (c, b) }

  /** `else` sits on a fresh line after a block body, or on the same line after an inline
   * one — so any intervening `Newline` is optional.
   */
  private lazy val elseClause: Parser[List[Stmt]] =
    opt(newlines) ~> op("else") ~> (suite | inlineBody)

  /** `while cond body [else …]` — an expression. The optional `else` reuses the same clause as
   * `if`, sitting after the body and before any `end while`.
   */
  private lazy val whileExpr: PackratParser[Expr] =
    op("while") ~> expression ~ body("do") ~ opt(elseClause) ~ opt(endMarker("while")) ^^ {
      case c ~ b ~ e ~ _ => While(c, b, e)
    }

  private lazy val forExpr: PackratParser[Expr] =
    op("for") ~> ident ~ (op("in") ~> expression) ~ body("do") ~ opt(elseClause) ~ opt(endMarker("for")) ^^ {
      case n ~ it ~ b ~ e ~ _ => For(n, it, b, e)
    }

  // --- match ---------------------------------------------------------------------------

  /** `match scrutinee` followed by an indented list of `pattern[, pattern…] [if guard] -> body`
   * arms — an expression yielding the taken arm's value.
   */
  private lazy val matchExpr: PackratParser[Expr] =
    op("match") ~> expression ~ (newline ~> indent ~> opt(newlines) ~> repsep(matchArm, newlines) <~ opt(newlines) <~ dedent) ^^ {
      case scrut ~ arms => MatchExpr(scrut, arms)
    }

  private lazy val matchArm: Parser[MatchArm] =
    op("else") ~> (op("->") ~> (suite | inlineBody)) ^^ (b => MatchArm(List(WildcardPattern), None, b)) |
      rep1sep(pattern, op("|")) ~ opt(op("if") ~> expression) ~ (op("->") ~> (suite | inlineBody)) ^^ {
        case pats ~ guard ~ b => MatchArm(pats, guard, b)
      }

  /** Patterns: scalar literals and ranges, the `_` wildcard, a variant destructuring
   * `V(sub…)`, or a bare name — which the analyzer reads as a nullary-variant pattern when it
   * names a variant of the scrutinee's enum, and as a binding otherwise.
   */
  private lazy val pattern: Parser[Pattern] =
    patternLit ~ (rangeOp ~ patternLit) ^^ { case lo ~ (inc ~ hi) => RangePattern(lo, hi, inc) } |
      variantPattern |
      wildcard ^^^ WildcardPattern |
      ident ^^ IdentPattern.apply |
      patternLit ^^ LitPattern.apply

  private lazy val variantPattern: Parser[Pattern] =
    ident ~ (op("(") ~> repsep(pattern, op(",")) <~ op(")")) ^^ { case n ~ ps => VariantPattern(n, ps) }

  private lazy val wildcard: Parser[Unit] =
    accept("'_'", { case t: lexical.Identifier if t.chars == "_" => () })

  /** A pattern literal: any scalar literal, or a negated numeric literal. */
  private lazy val patternLit: Parser[Expr] =
    op("-") ~> (floatLit | intLit) ^^ (e => Unary("-", e)) |
      floatLit | intLit | charLit | strLit | boolLit

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
