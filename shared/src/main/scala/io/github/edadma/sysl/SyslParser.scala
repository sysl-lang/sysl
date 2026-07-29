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
 *
 * Every rule that builds a node wraps itself in `at`, which stamps the node with the position of
 * the first token the rule consumed. A parser is bound to one `Source` so that stamp is complete
 * — file, line, and column — the moment the node exists.
 */
/** What a `[T, U: Show, V = int]` list says, kept as one value because every generic declaration
  * parses the same list and hands all three parts to the node it builds. A parameter carrying
  * neither a bound nor a default is simply absent from both maps.
  */
case class TypeParams(
    names: List[String],
    bounds: Map[String, List[BoundRef]] = Map.empty,
    defaults: Map[String, TypeRef] = Map.empty,
)

object TypeParams {
  val none: TypeParams = TypeParams(Nil)
}

class SyslParser(val source: Source) extends PackratParsers {

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

  // --- positions -----------------------------------------------------------------------

  /** Where the next token starts, in this parser's source. */
  private def posOf(in: Input): Pos = {
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
  private def at[T <: Positioned](p: => Parser[T]): Parser[T] =
    Parser { in =>
      p(in) match {
        case Success(t, rest) => Success(t.setPos(posOf(in)), rest)
        case other            => other
      }
    }

  /** The current position, consuming nothing — for a rule that builds its node from a tail it
   * has already passed, where the tail's own start is the better place to point.
   */
  private def here: Parser[Pos] = Parser(in => Success(posOf(in), in))

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
   *
   * `match` is **postfix**, as Scala's is, so it comes last: it reads an ordinary expression and
   * then looks for the keyword, which is what makes `x match` and `a + b match` both name the
   * value they are written after. An operand with no `match` behind it is that operand, so this
   * alternative is also the ordinary fall-through to `assignment`.
   */
  lazy val expression: PackratParser[Expr] = at(lambda | ifExpr | whileExpr | loopExpr | forExpr | matchExpr)

  /** `x -> x + 1` — a closure literal (`12 §5`).
   *
   * It sits at the top of the expression grammar because its body extends as far to the right as an
   * expression can: `x -> x + 1` is a closure over the sum, not a closure over `x` added to `1`.
   *
   * Nothing else in the grammar begins with a name or a parenthesized list and then an arrow, so the
   * commitment is the `->` and the alternatives below are reached by an ordinary backtrack. A match
   * arm is not a competitor even though it is written with the same token: an arm's left side is a
   * *pattern*, parsed by its own production, and only the arm's body is an expression.
   */
  private lazy val lambda: PackratParser[Expr] =
    lambdaParams ~ (op("->") ~> lambdaBody) ^^ { case ps ~ b => Lambda(ps, b) }

  /** One parameter with no parentheses, or a parenthesized list of them — including the empty list,
   * which is the one arity that has nowhere else to be written (`12 §5`).
   */
  private lazy val lambdaParams: Parser[List[LambdaParam]] =
    op("(") ~> commaList(lambdaParam) <~ op(")") |
      at(ident ^^ (n => LambdaParam(n, None))) ^^ (List(_))

  /** A closure's parameter: a name, and a type only where there is nothing to infer one from. The
   * annotation is written inside the parentheses and nowhere else, so `x: int -> …` is not a second
   * spelling of `(x: int) -> …` waiting to disagree with it.
   */
  private lazy val lambdaParam: Parser[LambdaParam] =
    at(ident ~ opt(op(":") ~> typeRef) ^^ { case n ~ t => LambdaParam(n, t) })

  /** A closure's body, which is a function's body without the `=`: an expression, or an indented
   * block whose trailing expression is the value.
   */
  private lazy val lambdaBody: PackratParser[List[Stmt]] =
    suite | expression ^^ (e => List(ExprStmt(e).setPos(e.pos)))

  private def binOp(sym: String): Parser[(Expr, Expr) => Expr] =
    op(sym) ^^^ ((l: Expr, r: Expr) => Binary(sym, l, r))

  /** Assignment is right-associative and lowest precedence, so it recurses on its right. The right
   * side is a full `expression`, so a control-flow expression may sit there — `x = if …`,
   * `total += if …` — in the same tail position `var x = …` already allows one; this does not put
   * those forms into a binary operand, so `1 + if …` still does not parse.
   *
   * `match` needs no such allowance, being postfix: `x = y match` is an assignment whose right side
   * is a match, and `a + b match` matches on the sum, exactly as the same lines read in Scala.
   */
  lazy val assignment: PackratParser[Expr] =
    at(
      logicalOr ~ assignOp ~ expression ^^ { case l ~ o ~ r => Assign(o, l, r) } |
        logicalOr,
    )

  private def assignOp: Parser[String] =
    op("=") | op("+=") | op("-=") | op("*=") | op("/=") | op("%=") |
      op("&=") | op("|=") | op("^=") | op("<<=") | op(">>=")

  lazy val logicalOr: PackratParser[Expr]  = at(chainl1(logicalAnd, binOp("||")))
  lazy val logicalAnd: PackratParser[Expr] = at(chainl1(comparison, binOp("&&")))

  /** Comparison chains rather than associates: `a < b < c` becomes one `Compare`. */
  lazy val comparison: PackratParser[Expr] =
    at(
      rangeExpr ~ rep(compareOp ~ rangeExpr) ^^ {
        case first ~ Nil  => first
        case first ~ rest => Compare(first :: rest.map { case _ ~ e => e }, rest.map { case o ~ _ => o })
      },
    )

  private def compareOp: Parser[String] =
    op("==") | op("!=") | op("<=") | op(">=") | op("<") | op(">")

  private def rangeOp: Parser[Boolean] = op("..<") ^^^ false | op("..") ^^^ true

  /** Ranges are non-associative and sit below arithmetic, so each end is a `bitOr`. Either
   * end may be omitted (`a..`, `..b`).
   */
  lazy val rangeExpr: PackratParser[Expr] =
    at(
      bitOr ~ opt(rangeOp ~ opt(bitOr)) ^^ {
        case lo ~ None              => lo
        case lo ~ Some(inc ~ hiOpt) => RangeExpr(Some(lo), hiOpt, inc)
      } |
        rangeOp ~ bitOr ^^ { case inc ~ hi => RangeExpr(None, Some(hi), inc) } |
        rangeOp ^^ (inc => RangeExpr(None, None, inc)),
    )

  lazy val bitOr: PackratParser[Expr]  = at(chainl1(bitXor, binOp("|")))
  lazy val bitXor: PackratParser[Expr] = at(chainl1(bitAnd, binOp("^")))
  lazy val bitAnd: PackratParser[Expr] = at(chainl1(additive, binOp("&")))
  lazy val additive: PackratParser[Expr] =
    at(chainl1(multiplicative, binOp("+") | binOp("-")))

  /** Shift binds like multiplication (the deliberate correction to C), so `<<`/`>>` live at
   * this level alongside `* / %`.
   */
  lazy val multiplicative: PackratParser[Expr] =
    at(chainl1(unary, binOp("*") | binOp("/") | binOp("%") | binOp("<<") | binOp(">>")))

  lazy val unary: PackratParser[Expr] =
    at(
      (op("-") | op("!") | op("~") | op("*") | op("&")) ~ unary ^^ { case o ~ e => Unary(o, e) } |
        (op("++") | op("--")) ~ unary ^^ { case o ~ e => PreIncDec(o, e) } |
        postfix,
    )

  lazy val postfix: PackratParser[Expr] =
    at(primary ~ rep(postfixTail) ^^ { case p ~ tails => tails.foldLeft(p)((acc, f) => f(acc)) })

  /** A postfix tail points at *itself* rather than at the operand it extends: a missing field is
   * a complaint about the `.name`, and a bad call is a complaint about the argument list, so the
   * caret belongs on the tail, not back at the start of the receiver.
   */
  private lazy val postfixTail: PackratParser[Expr => Expr] =
    here ~ (op("[") ~> expression <~ op("]")) ^^ { case p ~ idx => (e: Expr) => Index(e, idx).setPos(p) } |
      here ~ (op(".") ~> ident) ^^ { case p ~ n => (e: Expr) => Field(e, n).setPos(p) } |
      here ~ (op(".") ~> (tupleIndex | nestedTupleIndex)) ^^ { case p ~ n => (e: Expr) => Field(e, n).setPos(p) } |
      here ~ (op("::") ~> ident) ^^ { case p ~ n => (e: Expr) => TypeAttr(e, n).setPos(p) } |
      // A call is the exception: what is wrong with `foo(…)` is nearly always `foo` — it does not
      // exist, or it does not take these arguments — so the callee's own position wins, and the
      // `(` is only the fallback for a callee that somehow has none.
      here ~ (op("(") ~> commaList(expression) <~ op(")")) ^^ { case p ~ args =>
        (e: Expr) => Call(e, args).setPos(e.pos).setPos(p)
      } |
      here <~ op("?") ^^ (p => (e: Expr) => TryExpr(e).setPos(p)) |
      here <~ op("++") ^^ (p => (e: Expr) => PostIncDec("++", e).setPos(p)) |
      here <~ op("--") ^^ (p => (e: Expr) => PostIncDec("--", e).setPos(p))

  /** `t.0` — a tuple's part, selected by position. It is a `Field` because it *is* one: a tuple's
   * fields are named for their positions, so nothing downstream needs a second form of selection.
   * A suffix (`t.0u8`) is not an index, since what follows the dot names a part rather than
   * denoting a number.
   */
  private lazy val tupleIndex: Parser[String] =
    accept("tuple index", { case t: lexical.IntLit if t.suffix.isEmpty => t.value.toString })

  /** `t.0.1` — the nested selection that the lexer reads as one number, since `0.1` is a float
   * before it is two indices (`00 §13`). Fatal rather than a backtrack: nothing else can be meant
   * by a float immediately after a `.`, and the fix is worth naming where it happened.
   */
  private lazy val nestedTupleIndex: Parser[Nothing] = Parser { in =>
    in.first match
      case t: lexical.FloatLit if t.suffix.isEmpty && t.text.matches("""\d+\.\d+""") =>
        val Array(outer, inner) = t.text.split('.')

        Error(s"'.${t.text}' reads as a number rather than as two tuple indices — " +
          s"write '(x.$outer).$inner' to select part $inner of part $outer", in)
      case _ => Failure("tuple index expected", in)
  }

  lazy val primary: PackratParser[Expr] =
    at(
      floatLit | intLit | charLit | interpLit | cStrLit | strLit | boolLit | nullLit | selfExpr | identExpr |
        arrayLit |
        op("(") ~> parenTail,
    )

  /** A comma-separated list that may end in a comma.
   *
   * Every such list in sysl is bracketed, and a bracket suspends the off-side rule until it closes
   * (`00 §9`), so a list is free to span lines. That is what makes the trailing comma worth having
   * rather than a curiosity: with one element per line, the last line stops being different from
   * the others, so an element can be added, removed or reordered without touching its neighbour,
   * and a diff shows the line that changed and no other.
   *
   * The comma is optional only *after* an element, never instead of one — `[,]` and `f(,)` stay
   * errors. That is why the empty case is a separate alternative rather than `repsep` with an
   * optional comma hung off it, which would have accepted both.
   */
  private def commaList[T](p: Parser[T]): Parser[List[T]] = commaList1(p) | success(Nil)

  private def commaList1[T](p: Parser[T]): Parser[List[T]] = rep1sep(p, op(",")) <~ opt(op(","))

  /** `self` is reserved, so it never lexes as an identifier; inside a method body it reads as an
   * ordinary name that the analyzer resolves to the receiver binding, and is undefined elsewhere.
   */
  private lazy val selfExpr: Parser[Expr] = op("self") ^^^ Ident("self")

  /** `[a, b, c]` — an array literal, or `[v; n]` — an array of `n` copies of one value. A leading
   * `[` is unambiguous in operand position, since a subscript is a postfix tail on something
   * already parsed, and the two forms separate on the token after the first expression.
   */
  private lazy val arrayLit: PackratParser[Expr] =
    op("[") ~> expression ~ (op(";") ~> expression) <~ op("]") ^^ { case v ~ n => ArrayFill(v, n) } |
      op("[") ~> commaList(expression) <~ op("]") ^^ ArrayLit.apply

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

  private lazy val cStrLit: Parser[Expr] =
    accept("C string literal", { case t: lexical.CStrLit => CStrLit(t.value) })

  /** An interpolated string desugars to the concatenation of its literal segments with each
   * embedded expression rendered by `str`: `s"a${e}b"` becomes `"a" + str(e) + "b"`. The embedded
   * source is parsed here as an ordinary expression — so it may itself interpolate — and an empty
   * literal segment is dropped, since it is the identity under `+`.
   */
  private lazy val interpLit: Parser[Expr] = Parser { in =>
    in.first match
      case t: lexical.StrInterp =>
        desugarInterp(t) match
          case Right(e) => Success(e, in.rest)
          // A malformed embedded expression has no other reading, so the failure is fatal rather
          // than a cue to backtrack — that keeps the real message instead of a generic one from
          // whatever alternative the enclosing rule tries next.
          case Left(msg) => Error(msg, in)
      case _ => Failure("string interpolation expected", in)
  }

  private def desugarInterp(t: lexical.StrInterp): Either[String, Expr] = {
    val parsed =
      t.exprs.foldRight(Right(Nil): Either[String, List[Expr]]) { (src, acc) =>
        for
          rest <- acc
          e    <- parseEmbedded(src)
        yield e :: rest
      }

    // A plain hole renders through `str`; a hole with a specifier renders through `format`, which
    // carries the specifier as a literal for the analyzer to check against the value's type.
    def render(e: Expr, spec: Option[String]): Expr = spec match
      case None       => Call(Ident("str"), List(e))
      case Some(fmt)  => Call(Ident("format"), List(e, StrLit(fmt)))

    parsed.map { exprs =>
      val terms =
        t.parts.head match
          case "" => List.empty[Expr]
          case p  => List(StrLit(p): Expr)

      val holes = exprs.lazyZip(t.parts.tail).lazyZip(t.specs)

      val rendered = holes.foldLeft(terms) { case (acc, (e, part, spec)) =>
        val withExpr = acc :+ render(e, spec)
        if part.isEmpty then withExpr else withExpr :+ StrLit(part)
      }

      rendered match
        case Nil     => StrLit("")
        case x :: xs => xs.foldLeft(x)((l, r) => Binary("+", l, r))
    }
  }

  /** Lexes and parses the source of a `${ … }` interpolation as a single expression.
   *
   * The embedded text is its own little source, so a position inside a hole points into the hole
   * rather than into an unrelated column of the line the string sits on.
   */
  private def parseEmbedded(src: String): Either[String, Expr] = {
    val sub = new SyslParser(Source(s"${source.name} (interpolation)", src))

    sub.parseExpression match
      case sub.Success(e, _) => Right(e)
      case ns: sub.NoSuccess => Left(s"in interpolation '$src': ${ns.msg}")
  }

  private lazy val boolLit: Parser[Expr] =
    op("true") ^^^ BoolLit(true) | op("false") ^^^ BoolLit(false)

  private lazy val nullLit: Parser[Expr] = op("null") ^^^ NullLit()

  private lazy val identExpr: Parser[Expr] = ident ^^ Ident.apply

  // --- statements ----------------------------------------------------------------------

  lazy val statement: PackratParser[Stmt] =
    at(
      importDecl | implDecl | declaration | varDecl | returnStmt |
        breakStmt | continueStmt | requireStmt | ensureStmt | multiAssign | resultListStmt | exprStmt,
    )

  /** A statement written on the same line as the keyword that introduces it.
   *
   * It is every statement **but** a result list, which is a whole line by construction: a branch
   * written inline is part of a larger expression, so a comma after it belongs to whatever that
   * expression is part of. Without this, `-> int, string = if c then 1 else 0, "x"` would read the
   * comma as the *branch's* result list and leave the function one value.
   */
  private lazy val inlineStatement: PackratParser[Stmt] =
    at(
      importDecl | implDecl | declaration | varDecl | returnStmt |
        breakStmt | continueStmt | requireStmt | ensureStmt | multiAssign | exprStmt,
    )

  /** `a, b = b, a` — a comma list of places, a comma list of values (`00 §2`).
   *
   * It comes before `exprStmt` and after everything else, and it needs **two or more** targets to
   * commit: with one it would be an ordinary assignment written the long way round, which
   * `expression` already reads. Nothing below a statement admits a bare comma, so the first one is
   * enough to tell the two apart with no lookahead to speak of.
   */
  private lazy val multiAssign: PackratParser[Stmt] =
    (logicalOr <~ op(",")) ~ rep1sep(logicalOr, op(",")) ~ assignOp ~ rep1sep(expression, op(",")) ^^ {
      case first ~ rest ~ o ~ values => MultiAssign(o, first :: rest, values)
    }

  /** `require <cond> [, "message"]` / `ensure <cond> [, "message"]` — a design-by-contract
   * clause. Only meaningful at the top of a function body; the analyzer rejects one that
   * appears after ordinary statements.
   */
  private lazy val requireStmt: PackratParser[Stmt] =
    op("require") ~> expression ~ opt(op(",") ~> contractMsg) ^^ { case c ~ m => Require(c, m) }

  private lazy val ensureStmt: PackratParser[Stmt] =
    op("ensure") ~> expression ~ opt(op(",") ~> contractMsg) ^^ { case c ~ m => Ensure(c, m) }

  private lazy val contractMsg: Parser[String] =
    accept("string literal", { case t: lexical.StrLit => t.value })

  /** A declaration that may carry a visibility modifier (`13 §2`).
   *
   * The five forms are grouped so the modifier is written once, before whichever of them follows,
   * rather than threaded through five rules that would each have to remember it. An `impl` is not
   * among them and takes none: it declares no name, so there is nothing for a modifier to restrict.
   */
  private lazy val declaration: PackratParser[Stmt] =
    visibility ~ (structDecl | enumDecl | typeDecl | traitDecl | externDecl | constDecl | valDecl | funcDecl) ^^ {
      case Visibility.Public ~ d => d
      case v ~ d                 => restrict(v, d)
    }

  /** `private`, `private[M]`, or nothing at all — which is public (`13 §2`). There is no `pub`
   * keyword; its absence *is* public, so the unmarked case is the one that writes nothing.
   */
  private lazy val visibility: Parser[Visibility] =
    op("private") ~> opt(op("[") ~> ident <~ op("]")) ^^ {
      case Some(m) => Visibility.Scoped(m)
      case None    => Visibility.File
    } | success(Visibility.Public)

  private def restrict(v: Visibility, d: Stmt): Stmt = d match
    case s: StructDecl => s.copy(vis = v).setPos(s.pos)
    case e: EnumDecl   => e.copy(vis = v).setPos(e.pos)
    case t: TraitDecl  => t.copy(vis = v).setPos(t.pos)
    case e: ExternDecl => e.copy(vis = v).setPos(e.pos)
    case c: ConstDecl  => c.copy(vis = v).setPos(c.pos)
    case l: ValDecl    => l.copy(vis = v).setPos(l.pos)
    case f: FuncDecl   => f.copy(vis = v).setPos(f.pos)
    case t: TypeDecl   => t.copy(vis = v).setPos(t.pos)
    case other         => other

  /** A dotted name — a module path. */
  private lazy val dottedName: Parser[List[String]] = rep1sep(ident, op("."))

  /** A name that may be reached through the module it belongs to: `File`, `std.fs.File`.
   *
   * A public member is always reachable fully-qualified, with no import (`13 §3`), so every
   * position that names a declaration takes this rather than a bare identifier. The dots are kept
   * in the name as written — which module the prefix is and which part of it is the declaration's
   * own name is a question only the analyzer, holding the program's module names, can answer.
   */
  private lazy val qualifiedName: Parser[String] = dottedName ^^ (_.mkString("."))

  /** `module a.b.c`, the header naming the module this file contributes to. It is not a statement:
   * it may appear once, and only before everything else, so it is a prefix of the program rather
   * than an alternative within it.
   */
  private lazy val moduleHeader: Parser[ModuleName] =
    at(op("module") ~> dottedName ^^ ModuleName.apply)

  /** `import a.b.c`, `import a.b.{c, d as e}`, `import a.b.*` — the Scala forms (`13 §3`).
   *
   * The path is the greedy dotted name, so the tail forms are read off what is left after it: a
   * `.` followed by something that is not an identifier is not part of the path, which is what
   * lets one rule cover all three without lookahead. Which part of the path is the module is not
   * decided here — it is a question about the program, not about the text.
   *
   * It is a **statement** rather than a header, because an import may also appear inside a block,
   * scoped to it, for a name wanted in one function only.
   */
  private lazy val importDecl: Parser[ImportDecl] =
    op("import") ~> dottedName ~ opt(importTail) ^^ {
      case path ~ None                => ImportDecl(path)
      case path ~ Some(Left(_))       => ImportDecl(path, wildcard = true)
      case path ~ Some(Right(sels))   => ImportDecl(path, sels)
    }

  /** The wildcard's `.*` is **one token**, which is why it is matched here rather than as a `.`
   * followed by the multiplication operator. Nothing about the written form changes; what it buys
   * is that a line never ends in a bare `*` that was really the end of a statement, so `*` can
   * carry a line like every other binary operator (`SyslLexical`).
   */
  private lazy val importTail: Parser[Either[Unit, List[ImportSelector]]] =
    op(".*") ^^^ Left(()) |
      (op(".") ~> op("{") ~> commaList1(importSelector) <~ op("}")) ^^ (Right(_))

  private lazy val importSelector: Parser[ImportSelector] =
    at(ident ~ opt(op("as") ~> ident) ^^ { case n ~ a => ImportSelector(n, a) })

  /** A type: a memory-mode sigil applied to a type, or a name optionally applied to type
   * arguments (`Box[int]`, `Result[T, string]`). `sync` stays a soft keyword — it is only
   * special immediately after `&`, and the `&sync T` alternative is tried first so that a
   * reference to a type actually named `sync` still parses.
   *
   * `weak` is a reserved word rather than a sigil, since a mode a program reaches for only for a
   * genuine back-reference (`03`) is better read than punctuated.
   */
  private lazy val typeRef: Parser[TypeRef] =
    at(coreType ~ opt(op("->") ~> typeRef) ^^ {
      case t ~ None                        => t
      case TupleType(parts, false) ~ Some(r) => FnType(parts, r, bare = true)
      case t ~ Some(r)                     => FnType(List(t), r, bare = true)
    })

  /** A type with no arrow on it — everything a bare-arrow callable is written *out of*.
   *
   * The arrow is a suffix on this rather than an alternative among these, so `(A, B) -> C` reads its
   * left side as the parenthesized list it looks like and only then learns it was a parameter list.
   * That is what keeps one production for `(A, B)` whether a tuple or a callable was meant, and it
   * is why the two cannot disagree about how a comma inside parentheses is read.
   */
  private lazy val coreType: Parser[TypeRef] =
    at(
      // `Fn(A) -> R`, the callable's type written out (`12 §6`). It comes first because `Fn` is an
      // ordinary identifier: without this the name alternative below would take it and leave the
      // parameter list stranded.
      (fnWord ~> op("(") ~> commaList(typeRef) <~ op(")")) ~ (op("->") ~> typeRef) ^^ {
        case ps ~ r => FnType(ps, r, bare = false)
      } |
        // `() -> R` — a callable of no arguments. Empty parentheses are not a type, so this is the
        // one place they may be written, and the arrow is what says so.
        (op("(") ~> op(")") ~> op("->") ~> typeRef) ^^ (r => FnType(Nil, r, bare = true)) |
        op("*") ~> coreType ^^ PtrType.apply |
        op("&") ~> softSync ~> coreType ^^ (t => RefType(t, sync = true)) |
        op("&") ~> coreType ^^ (t => RefType(t, sync = false)) |
        op("weak") ~> softSync ~> err("an atomic reference has no weak form yet — 'weak sync T' " +
          "wants the concurrency model of '06', which is not built") |
        op("weak") ~> coreType ^^ WeakType.apply |
        (op("[") ~> opt(expression) <~ op("]")) ~ coreType ^^ { case n ~ t => ArrayType(n, t) } |
        tupleType |
        qualifiedName ~ opt(typeArgs) ^^ { case n ~ args => NamedType(n, args.getOrElse(Nil)) },
    )

  /** `(A, B)` — a tuple type. A single part is refused rather than read as a grouping, because the
   * two spellings would then differ by a comma and mean different things; `(T)` is the shape
   * somebody writes when they mean a one-tuple, and there is no such type (`00 §13`).
   */
  private lazy val tupleType: Parser[TypeRef] =
    (op("(") ~> commaList1(typeRef) <~ op(")")) >> {
      case List(one) =>
        err(s"'(${one.show})' is a type in parentheses, and a tuple has two or more parts — " +
          s"a product of one thing is that thing, so write '${one.show}'")
      case parts => success(TupleType(parts))
    }

  /** A function's declared result: one type, or several separated by commas (`12 §5b`).
   *
   * A result list is a property of the signature and not a type, so it is spelled here rather than
   * in `typeRef` — nothing that asks for a *type* can reach one, which is what keeps `-> int, int`
   * and a field or a parameter apart with no rule of its own.
   */
  private lazy val resultRef: Parser[TypeRef] =
    typeRef ~ rep(op(",") ~> typeRef) ^^ {
      case t ~ Nil  => t
      case t ~ more => TupleType(t :: more, results = true)
    }

  /** The `[int, string]` argument list of an applied generic name, whether the name is a type's or
   * a trait's — a trait takes its arguments the same way and in the same place.
   */
  private lazy val typeArgs: Parser[List[TypeRef]] =
    op("[") ~> commaList1(typeRef) <~ op("]")

  private lazy val softSync: Parser[Unit] =
    accept("'sync'", { case t: lexical.Identifier if t.chars == "sync" => () })

  /** `Fn` stays a soft word for the reason `sync` does: it is only special immediately before a
   * parenthesized parameter list, so a program with a type of its own named `Fn` still parses.
   */
  private lazy val fnWord: Parser[Unit] =
    accept("'Fn'", { case t: lexical.Identifier if t.chars == "Fn" => () })

  /** A type-parameter list where a parameter may carry a trait bound: `[T, U: Show, V: Ord + Hash]`.
   * It yields the parameter names alongside a name-keyed map of the bounds, so an unbounded
   * parameter is simply absent from the map.
   *
   * Every declaration that may be generic over types it does not know parses this one list — a
   * function, an `impl` block, a struct, an enum, a trait — because a bound means the same thing in
   * each: it is what the declaration assumes of the parameter, and what everything applying it must
   * supply.
   *
   * A bound is a trait **applied**, so it takes type arguments where the trait declares any:
   * `[E: From[IoError]]`. The arguments are types and parse as such, which is what lets one mention
   * another of the parameters being declared.
   *
   * A parameter may also carry a **default**, `[Rhs = Self]`, which stands in where the declaration
   * is applied to fewer arguments than it declares. The bound comes first and the default last, so
   * `[R: Show = Self]` reads as the two clauses it is; both are optional and independent. Whether a
   * default means anything in the position being parsed is the analyzer's question, since only it
   * can say so with the declaration under the message.
   */
  private lazy val boundedTypeParams: Parser[TypeParams] =
    op("[") ~> commaList1(boundedTypeParam) <~ op("]") ^^ { ps =>
      TypeParams(
        ps.map(_._1),
        ps.collect { case (n, bs, _) if bs.nonEmpty => n -> bs }.toMap,
        ps.collect { case (n, _, Some(d)) => n -> d }.toMap,
      )
    }

  private lazy val boundedTypeParam: Parser[(String, List[BoundRef], Option[TypeRef])] =
    ident ~ opt(op(":") ~> rep1sep(boundRef, op("+"))) ~ opt(op("=") ~> typeRef) ^^ {
      case n ~ bs ~ d => (n, bs.getOrElse(Nil), d)
    }

  private lazy val boundRef: Parser[BoundRef] =
    at(qualifiedName ~ opt(typeArgs) ^^ { case n ~ args => BoundRef(n, args.getOrElse(Nil)) })

  private lazy val varDecl: PackratParser[Stmt] =
    multiDecl("var", mutable = true) |
      op("var") ~> ident ~ opt(op(":") ~> typeRef) ~ opt(op("=") ~> expression) ^^ {
        case n ~ t ~ e => VarDecl(n, t, e)
      }

  /** `val a, b = …` / `var a, b = …` — a binding that names several things (`00 §2`).
   *
   * Two or more names, and an initializer, are both required: one name is the ordinary form, and a
   * multiple binding with nothing to take apart names nothing. The parts carry no type annotation,
   * which is `12 §5b`'s open question rather than an oversight — inference covers what the form is
   * for, and there is no spelling yet for the case it does not.
   */
  private def multiDecl(keyword: String, mutable: Boolean): PackratParser[Stmt] =
    (op(keyword) ~> ident <~ op(",")) ~ rep1sep(ident, op(",")) ~ (op("=") ~> rep1sep(expression, op(","))) ^^ {
      case first ~ rest ~ values => MultiDecl(first :: rest, mutable, values)
    }

  /** `const name: type = value` (`13 §7`). Both halves are mandatory, which is what tells it apart
   * from a `var` at a glance as well as to the parser: a constant with no value is not a
   * declaration of anything, and a type left off would be the one declaration in the language whose
   * interface could not be read off its syntax.
   */
  private lazy val constDecl: PackratParser[Stmt] =
    op("const") ~> ident ~ (op(":") ~> typeRef) ~ (op("=") ~> expression) ^^ {
      case n ~ t ~ v => ConstDecl(n, t, v)
    }

  /** `val name [: type] = value` — a binding that is written once (`07`, `13 §7`).
   *
   * The **value is mandatory** and the type is not, which is the opposite arrangement from `const`
   * and for the opposite reason: a `val` with nothing to hold is not a declaration of anything,
   * while its type is readable off the value it was given. Whether a type may be left off is
   * nevertheless a question about *where* it was written — a module member states its interface —
   * and where is something only the analyzer knows, so the syntax accepts either and the rule is
   * applied there.
   */
  private lazy val valDecl: PackratParser[Stmt] =
    multiDecl("val", mutable = false) |
      op("val") ~> ident ~ opt(op(":") ~> typeRef) ~ (op("=") ~> expression) ^^ {
        case n ~ t ~ v => ValDecl(n, t, v)
      }

  private lazy val exprStmt: PackratParser[Stmt] = expression ^^ (e => ExprStmt(e).setPos(e.pos))

  /** `a, b` standing alone — a function's result list as its trailing expression. It is tried
   * after the assignment form, which starts the same way and is settled by its `=`.
   */
  private lazy val resultListStmt: PackratParser[Stmt] =
    expression ~ rep1(op(",") ~> expression) <~ endOfStatement ^^ { case e ~ more =>
      ExprStmt(ResultList(e :: more).setPos(e.pos))
    }

  /** That the list just parsed really was a whole line.
   *
   * Without it the form is greedy across a comma that belongs to something outside: an inline
   * `else` body is a statement, so `f(if c then a else b, x, y)` would read `b, x, y` as a result
   * list and leave the call one argument. A result list is the last thing on its line by
   * construction, so requiring that is exact rather than a heuristic.
   */
  private lazy val endOfStatement: Parser[Unit] =
    guard(newline) | guard(dedent) | Parser(in =>
      if in.atEnd then Success((), in) else Failure("end of statement expected", in))

  private lazy val returnStmt: PackratParser[Stmt] =
    op("return") ~> opt(resultValue) ^^ Return.apply

  /** What a function hands back: one expression, or the several its result list declares. */
  private lazy val resultValue: PackratParser[Expr] =
    expression ~ rep(op(",") ~> expression) ^^ {
      case e ~ Nil  => e
      case e ~ more => ResultList(e :: more).setPos(e.pos)
    }

  private lazy val breakStmt: PackratParser[Stmt] =
    op("break") ~> opt(labelRef) ~ opt(expression) ^^ { case lbl ~ v => Break(lbl, v) }

  private lazy val continueStmt: PackratParser[Stmt] =
    op("continue") ~> opt(labelRef) ^^ (lbl => Continue(lbl))

  /** A `'name` label reference, as used before a loop and after `break`/`continue`. */
  private lazy val labelRef: Parser[String] =
    accept("label", { case t: lexical.Label => t.name })

  /** One `name: type` binding — a function parameter or a struct field. */
  private lazy val param: Parser[Param] =
    at(ident ~ (op(":") ~> typeRef) ^^ { case n ~ t => Param(n, t) })

  /** A function declaration, Scala-style but keyword-less: `name[T…](params) -> ret = expr` or
   * a block body, `-> ret` optional (absent ⇒ `unit`). It is tried before an expression
   * statement; a bare call `foo(1)` fails here (its arguments are not `name: type` bindings,
   * and nothing follows to open a body) and falls through to `exprStmt`.
   */
  private lazy val funcDecl: PackratParser[Stmt] =
    ident ~ opt(boundedTypeParams) >> { case name ~ tps =>
      val tp = tps.getOrElse(TypeParams.none)
      (op("(") ~> paramList <~ op(")")) ~ opt(op("->") ~> resultRef) ~ funcBody <~ endName(name) ^^ {
        case ((params, variadic)) ~ ret ~ body =>
          FuncDecl(name, tp.names, params, ret, body, tp.bounds, variadic, tdefaults = tp.defaults)
      }
    }

  /** `extern name(params) -> ret` — a header with no body at all, which is what tells it from a
   * function declaration. The result is optional and absent means `unit`, exactly as for a
   * function; `-> never` says the callee does not come back.
   *
   * A string before the name is the *symbol*, and the name after it is what the program calls it by:
   * `extern "snprintf" fmt(…)` resolves to libc's `snprintf` without spending the name `snprintf`.
   * A leading string is unambiguous — a declaration otherwise begins with an identifier — so this
   * costs no keyword. Haskell's `foreign import ccall "snprintf" c_snprintf` is the same shape.
   */
  private lazy val externDecl: PackratParser[Stmt] =
    op("extern") ~> opt(linkName) ~ ident ~ (op("(") ~> paramList <~ op(")")) ~ opt(op("->") ~> typeRef) ^^ {
      case link ~ name ~ ((params, variadic)) ~ ret => ExternDecl(name, params, ret, variadic, link)
    }

  private lazy val linkName: Parser[String] =
    accept("symbol name", { case t: lexical.StrLit => t.value })

  /** A declared parameter list, which may end in `...` — the C ellipsis, and the one arity a
   * declaration does not fix. Shared by `extern` and by a sysl function, which may be variadic too.
   * The `...`-only form parses so the analyzer can say why a variadic needs a named parameter before
   * it, rather than the grammar reporting a stray token.
   */
  private lazy val paramList: Parser[(List[Param], Boolean)] =
    op("...") ^^^ (Nil, true) |
      // The variadic marker is tried before the trailing comma, so `f(a: int, ...)` still reads the
      // comma as the separator it is; `f(a: int,)` falls through to the trailing-comma case.
      repsep(param, op(",")) ~ opt(op(",") ~> op("...")) <~ opt(op(",")) ^^ { case ps ~ dots =>
        (ps, dots.isDefined)
      }

  /** A function body is either an `= expr` short form (whose value is the return value) or an
   * indented block (whose trailing expression is the return value).
   */
  private lazy val funcBody: PackratParser[List[Stmt]] =
    op("=") ~> (suite | resultValue ^^ (e => List(ExprStmt(e).setPos(e.pos)))) | suite

  /** One parsed line of a struct body, before the three kinds are sorted into their own lists. */
  private enum StructPart:
    case Fld(f: Param)
    case Mem(m: MethodDecl)
    case Inv(e: Expr)

  private lazy val structDecl: PackratParser[Stmt] =
    op("struct") ~> ident ~ opt(boundedTypeParams) >> { case name ~ tps =>
      val tp = tps.getOrElse(TypeParams.none)

      (newline ~> indent ~> opt(newlines) ~> repsep(structItem, newlines) <~ opt(newlines) <~ dedent) <~ endName(name) ^^ {
        items =>
          val fields     = items.collect { case StructPart.Fld(f)  => f }
          val members    = items.collect { case StructPart.Mem(m)  => m }
          val invariants = items.collect { case StructPart.Inv(e)  => e }
          StructDecl(name, tp.names, fields, members, tp.bounds, invariants, tdefaults = tp.defaults)
      }
    }

  /** A line inside a struct body is a member declaration, an `invariant <bool>` clause, or a
   * `name: type` field. A member is tried first — it needs a `(` (a method or associated function)
   * or a `->` (a property) after the name; an `invariant` clause next — it is the contextual word
   * `invariant` followed by an expression; and a bare field falls through to `param` (so a field
   * may still be named `invariant`, since `invariant: type` matches neither of the first two).
   *
   * A field and a member may each carry a **visibility modifier** (`08 § Visibility`), written in
   * the same place and the same spellings a top-level declaration writes one. An `invariant` clause
   * declares no name, so like an `impl` block it takes none.
   */
  private lazy val structItem: Parser[StructPart] =
    restrictedMember ^^ (StructPart.Mem(_)) |
      invariantClause ^^ (StructPart.Inv(_)) |
      visibility ~ param ^^ { case v ~ f => StructPart.Fld(f.copy(vis = v).setPos(f.pos)) }

  /** A member of a type's own body, which is the one kind that may say how far it is visible. */
  private lazy val restrictedMember: PackratParser[MethodDecl] =
    visibility ~ member ^^ { case v ~ m => m.copy(vis = v).setPos(m.pos) }

  /** The refusal a trait's member and an `impl`'s share (`08 § Visibility`). Both are reached at the
   * reach the *trait* has — one asks for the member and the other supplies what was asked — so
   * there is nothing here for a modifier to decide, and saying that is worth more than the
   * "newline expected" a grammar with no place for one would give.
   */
  private lazy val noVisibility: Parser[Unit] =
    op("private") ~> err("a trait's members and an 'impl' block's carry no visibility of their own — a " +
      "trait's member is as visible as the trait, and an implementation supplies what the trait asked for") |
      success(())

  /** `invariant <bool>` among a struct's fields: a condition every value of the struct must satisfy,
   * re-checked whenever the struct is built or one of its fields is written. Bare field names are in
   * scope. `invariant` is contextual — an ordinary identifier everywhere else. */
  private lazy val invariantClause: Parser[Expr] = invariantKw ~> expression

  private lazy val invariantKw: Parser[Unit] = softWord("invariant")

  /** A member of a type's body. What follows the name decides the kind: `(params)` is a method
   * (or, with no `self`, an associated function), and `-> type = body` with no parameter list is
   * a computed property.
   *
   * A member may declare **type parameters of its own**, in the same bracketed list every other
   * generic declaration writes and in the same position — directly after the name. They are the
   * member's, not the type's: a call fixes them from what it passes, while the type's own are
   * already fixed by the receiver.
   */
  private lazy val member: PackratParser[MethodDecl] =
    at(
      ident ~ opt(boundedTypeParams) >> { case name ~ tps =>
        methodTail(name, tps.getOrElse(TypeParams.none)) |
          (if tps.isEmpty then propertyTail(name) else failure("a property takes no type parameters"))
      },
    )

  private def methodTail(name: String, generics: TypeParams): Parser[MethodDecl] =
    (op("(") ~> methodParams <~ op(")")) ~ opt(op("->") ~> resultRef) ~ funcBody <~ endName(name) ^^ {
      case (recv, params) ~ ret ~ body =>
        MethodDecl(name, recv, isProperty = false, generics.names, params, ret, body, generics.bounds,
          generics.defaults)
    }

  private def propertyTail(name: String): Parser[MethodDecl] =
    (op("->") ~> typeRef) ~ (op("=") ~> expression) <~ endName(name) ^^ {
      case ret ~ e => MethodDecl(name, None, isProperty = true, Nil, Nil, Some(ret), List(ExprStmt(e).setPos(e.pos)))
    }

  /** The parenthesised part of a method: an optional receiver shorthand (`self`, `*self`,
   * `&self`, `&sync self`) followed by ordinary `name: type` parameters. With no receiver the
   * member is an associated function.
   */
  private lazy val methodParams: Parser[(Option[RecvMode], List[Param])] =
    receiver ~ rep(op(",") ~> param) <~ opt(op(",")) ^^ { case r ~ ps => (Some(r), ps) } |
      commaList(param) ^^ (ps => (None, ps))

  private lazy val receiver: Parser[RecvMode] =
    op("*") ~> op("self") ^^^ RecvMode.ByPtr |
      op("&") ~> softSync ~> op("self") ^^^ RecvMode.ByRef(sync = true) |
      op("&") ~> op("self") ^^^ RecvMode.ByRef(sync = false) |
      op("self") ^^^ RecvMode.ByValue

  /** `enum Name[T…]` with indented variants, and an optional `: iN` underlying-type annotation
   * that pins a simple enum's storage. A variant is a bare name (`Empty`), a name with an
   * explicit integer value (`Blue = 10`), or a name with a payload (`Circle(radius: int)`).
   */
  private lazy val enumDecl: PackratParser[Stmt] =
    op("enum") ~> ident ~ opt(boundedTypeParams) ~ opt(op(":") ~> typeRef) >> { case name ~ tps ~ under =>
      val tp = tps.getOrElse(TypeParams.none)

      (newline ~> indent ~> opt(newlines) ~> repsep(enumItem, newlines) <~ opt(newlines) <~ dedent) <~ endName(name) ^^ {
        items =>
          val variants = items.collect { case Left(v)  => v }
          val members  = items.collect { case Right(m) => m }
          EnumDecl(name, tp.names, under, variants, members, tp.bounds, tdefaults = tp.defaults)
      }
    }

  /** A line inside an enum body is either a variant or a member declaration, told apart the same
   * way a struct body's lines are: a member is tried first and needs a body to follow its header,
   * so `Circle(radius: int)` — a header with nothing after it — falls through to `enumVariant`.
   */
  private lazy val enumItem: Parser[Either[EnumVariantDecl, MethodDecl]] =
    restrictedMember ^^ (Right(_)) | enumVariant ^^ (Left(_))

  private lazy val enumVariant: Parser[EnumVariantDecl] =
    at(
      ident ~ (op("(") ~> commaList(param) <~ op(")")) ^^ { case n ~ fs => EnumVariantDecl(n, None, fs) } |
        ident ~ (op("=") ~> expression) ^^ { case n ~ v => EnumVariantDecl(n, Some(v), Nil) } |
        ident ^^ (n => EnumVariantDecl(n, None, Nil)),
    )

  /** `type Name = [new] Base [within lo..hi] [where predicate]` — a constrained subtype (`16`).
   * `new`, `within`, and `where` are contextual: they are ordinary identifiers everywhere else, so
   * a function or field may still be named `where`, and are recognised as keywords only here.
   */
  private lazy val typeDecl: PackratParser[Stmt] =
    op("type") ~> ident ~ (op("=") ~> opt(newKw) ~ typeRef ~ opt(withinClause) ~ opt(whereClause)) ^^ {
      case name ~ (nw ~ base ~ range ~ pred) => TypeDecl(name, base, nw.isDefined, range, pred)
    }

  /** `within lo..hi` (inclusive) or `within lo..<hi` (upper-exclusive). The `..<` token is a single
   * lexeme, so it is told from `..` by the tokenizer rather than here.
   */
  private lazy val withinClause: Parser[RangeBound] =
    withinKw ~> boundLit ~ (op("..<") ^^^ true | op("..") ^^^ false) ~ boundLit ^^ {
      case lo ~ excl ~ hi => RangeBound(lo, hi, excl)
    }

  private lazy val whereClause: Parser[Expr] = whereKw ~> expression

  /** A bound of a `within` range: a character literal, or a numeric literal with an optional sign.
   *
   * A name is the thing somebody writes here and cannot: an array bound may be a `const`
   * (`[max_tasks]Task`) and a range bound may not, so a table's size and the range of the type
   * indexing it are written twice with nothing checking they agree (`13 §7`, `16 § Open b`). The
   * restriction is not settled, but the message a bare grammar failure gives — "newline expected",
   * pointing past the end of the line — is the wrong shape whether it stays or goes.
   */
  private lazy val boundLit: Parser[Expr] =
    charLit | op("-") ~> (floatLit | intLit) ^^ (Unary("-", _)) | floatLit | intLit |
      guard(ident) >> (n => err(s"a 'within' bound is a literal, and '$n' is a name — a constant cannot " +
        "stand in a range yet, so the number has to be written out here as well as wherever the " +
        "constant is used"))

  private lazy val newKw: Parser[Unit]    = softWord("new")
  private lazy val withinKw: Parser[Unit] = softWord("within")
  private lazy val whereKw: Parser[Unit]  = softWord("where")

  /** A contextual keyword: an identifier spelled exactly `word`, matched where the grammar wants the
   * keyword but the word must stay a legal identifier everywhere else (the `sync` of `&sync T`).
   */
  private def softWord(word: String): Parser[Unit] =
    accept(s"'$word'", { case t: lexical.Identifier if t.chars == word => () })

  /** `trait Name` with indented member declarations. Each is a method header — a receiver, a
   * parameter list, and an optional result — either bare, which requires an implementation to
   * supply it, or followed by a body, which supplies a **default** every `impl` inherits unless it
   * writes its own.
   *
   * `trait Name: Super + Other` names the traits this one **requires**, spelled exactly as a bound
   * on a type parameter is — the same `:` and the same `+` — because it asks the same thing of the
   * implementing type. A generic trait writes both: `trait Word[T]: Add`, the parameters first.
   */
  private lazy val traitDecl: PackratParser[Stmt] =
    op("trait") ~> ident ~ opt(boundedTypeParams) ~ opt(op(":") ~> rep1sep(boundRef, op("+"))) >> {
      case name ~ tps ~ supers =>
        val tp = tps.getOrElse(TypeParams.none)

        (newline ~> indent ~> opt(newlines) ~> repsep(traitMember, newlines) <~ opt(newlines) <~ dedent) <~
          endName(name) ^^ { methods =>
            TraitDecl(name, tp.names, methods, tp.bounds, supers.getOrElse(Nil), tdefaults = tp.defaults)
          }
    }

  /** A line inside a trait body. A **definition** is tried first, since it is a signature with more
   * after it: `member` needs a body to follow the header, so a bare method signature falls through
   * to `methodSig` and a bare property signature to `propertySig`. A signature of either kind asks
   * an implementation for that member; one written with a body supplies a default instead.
   */
  private lazy val traitMember: PackratParser[MethodDecl] = noVisibility ~> (member | methodSig | propertySig)

  /** A trait method signature: a header with no `= body`. The receiver and parameters parse
   * exactly as a real method's do, so a signature and its implementation are compared shape for
   * shape.
   */
  private lazy val methodSig: PackratParser[MethodDecl] =
    at(
      ident ~ opt(boundedTypeParams) ~ (op("(") ~> methodParams <~ op(")")) ~ opt(op("->") ~> resultRef) ^^ {
        case name ~ tps ~ ((recv, params)) ~ ret =>
          val tp = tps.getOrElse(TypeParams.none)
          MethodDecl(name, recv, isProperty = false, tp.names, params, ret, Nil, tp.bounds, tp.defaults)
      },
    )

  /** A property signature — `name -> type` with neither a parameter list nor a body. */
  private lazy val propertySig: PackratParser[MethodDecl] =
    at(ident ~ (op("->") ~> typeRef) ^^ { case name ~ ret =>
      MethodDecl(name, None, isProperty = true, Nil, Nil, Some(ret), Nil)
    })

  /** `impl Trait for Type` with indented method definitions — ordinary members, reusing the same
   * grammar as a method written in a struct's own body. The block is closed by an optional
   * `end Type`.
   *
   * The type is a full type reference, not a name: `impl Show for []int` is as ordinary as
   * `impl Show for Point`, and which types an `impl` may be *for* is the analyzer's to decide
   * rather than something to leave the grammar unable to express.
   *
   * The block may declare **type parameters of its own**, in the same bracketed list a generic
   * function writes and in the same position — directly after the keyword that opens the
   * declaration: `impl[T: Show] Show for Box[T]`. They are what makes the implementation cover a
   * generic type as a whole, and the bounds on them are what make it conditional.
   *
   * The body itself is optional, because a trait whose every method has a default leaves a
   * conforming type nothing to write: `impl Zero for E` on its own line is the whole of that
   * implementation, and the opt-in it states is the point of writing it.
   */
  private lazy val implDecl: PackratParser[Stmt] =
    op("impl") ~> opt(boundedTypeParams) ~ implTrait ~ (op("for") ~> typeRef) >> {
      case tps ~ ((tname, targs)) ~ forType =>
        val tp = tps.getOrElse(TypeParams.none)

        (implBody | success(Nil)) <~ endTypeRef(forType) ^^ { methods =>
          ImplDecl(tname, forType, methods, tp.names, tp.bounds, targs, tp.defaults)
        }
    }

  /** The trait an `impl` is of: a name and its arguments, or a callable written as one (`12 §6`).
   *
   * The arrow spelling is here so that the arity-carrying declaration behind a call trait stays out
   * of programs entirely — a type made callable by hand is written `impl Fn(int) -> int for Doubler`,
   * the same way the type of one is written everywhere else.
   */
  private lazy val implTrait: Parser[(String, List[TypeRef])] =
    (fnWord ~> op("(") ~> commaList(typeRef) <~ op(")")) ~ (op("->") ~> typeRef) ^^ {
      case ps ~ r => (Type.Fn.base(ps.length), ps :+ r)
    } |
      qualifiedName ~ opt(typeArgs) ^^ { case n ~ args => (n, args.getOrElse(Nil)) }

  private lazy val implBody: PackratParser[List[MethodDecl]] =
    newline ~> indent ~> opt(newlines) ~> repsep(noVisibility ~> member, newlines) <~ opt(newlines) <~ dedent

  /** An optional `end Name` marker closing a declaration block, Scala-style. `end` is a soft
   * keyword; the trailing name must equal the declaration's own name, or it is a parse error.
   */
  private def endName(name: String): Parser[Unit] =
    opt(opt(newlines) ~> softEnd ~> checkedEndName(name)) ^^^ (())

  private def checkedEndName(expected: String): Parser[Unit] =
    ident >> { n =>
      if n == expected then success(()) else err(s"'end $n' does not match '$expected'")
    }

  /** The same marker closing an `impl`, whose subject is a type rather than a name — `end []int`
   * as readily as `end Point`. The two references are compared as written, since nothing has
   * resolved either of them yet and matching the spelling is all this marker was ever doing.
   */
  private def endTypeRef(expected: TypeRef): Parser[Unit] =
    opt(opt(newlines) ~> softEnd ~> (typeRef >> { t =>
      if t == expected then success(()) else err(s"'end ${t.show}' does not match '${expected.show}'")
    })) ^^^ (())

  /** An indented block: a leading `Newline`+`Indent` (the lexer's off-side signal) wraps a
   * statement sequence closed by `Dedent`.
   */
  private lazy val suite: PackratParser[List[Stmt]] =
    newline ~> indent ~> statements <~ dedent

  /** A single statement written on the same line as its control-flow keyword. */
  private lazy val inlineBody: PackratParser[List[Stmt]] = inlineStatement ^^ (s => List(s))

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
    opt(labelRef) ~ (op("while") ~> expression) ~ body("do") ~ opt(elseClause) ~ opt(endMarker("while")) ^^ {
      case lbl ~ c ~ b ~ e ~ _ => While(lbl, c, b, e)
    }

  /** `loop body` — a `while` with the condition left out, ended by a `break` rather than by a test.
   *
   * It takes no `else`: an `else` runs when a loop finishes on its own, and this one never does.
   * The inline `loop do …` form is there because `while c do …` has it and a one-line body should
   * not have to change shape when its condition goes away.
   */
  private lazy val loopExpr: PackratParser[Expr] =
    opt(labelRef) ~ (op("loop") ~> body("do")) ~ opt(endMarker("loop")) ^^ { case lbl ~ b ~ _ => Loop(lbl, b) }

  private lazy val forExpr: PackratParser[Expr] = cForExpr | forInExpr

  private lazy val forInExpr: PackratParser[Expr] =
    opt(labelRef) ~ (op("for") ~> ident) ~ (op("in") ~> expression) ~ body("do") ~ opt(elseClause) ~ opt(
      endMarker("for"),
    ) ^^ {
      case lbl ~ n ~ it ~ b ~ e ~ _ => For(lbl, n, it, b, e)
    }

  /** `for init; cond; step` — the three-clause loop (`00` §10).
   *
   * Tried before `for x in …`, and unambiguous against it: this one needs two `;` in its header and
   * that one has none, so whichever the reader wrote is the one that parses. No parentheses, as in
   * Go — every other header in the language is without them, and a parenthesized one here would be
   * the only place they were structure rather than grouping.
   *
   * Each clause may be empty. An absent condition is `true`, which makes `for ; ;` a `loop` spelled
   * the long way; `loop` says it better and this does not forbid it.
   */
  private lazy val cForExpr: PackratParser[Expr] =
    opt(labelRef) ~ (op("for") ~> opt(forClause) <~ op(";")) ~ (opt(expression) <~ op(";")) ~ opt(forClause) ~
      body("do") ~ opt(elseClause) ~ opt(endMarker("for")) ^^ {
        case lbl ~ init ~ cond ~ step ~ b ~ e ~ _ => CFor(lbl, init, cond, step, b, e)
      }

  /** One clause of a three-clause `for` header: a `var` declaration or a bare expression, which for
   * the step is the assignment or increment that advances the loop.
   */
  private lazy val forClause: PackratParser[Stmt] = at(varDecl | multiAssign | exprStmt)

  // --- match ---------------------------------------------------------------------------

  /** `scrutinee match` followed by an indented list of `pattern[, pattern…] [if guard] -> body`
   * arms — an expression yielding the taken arm's value.
   *
   * The keyword goes **after** the value, as in Scala, and for Scala's reason: a match is a
   * *transformation of the thing to its left*, so writing it there is what lets one feed another.
   * `a match … match …` reads in the order the values flow, where the prefix form would have made
   * the second one wrap the first and put the arms of each at a different distance from the value
   * they choose between.
   *
   * It is therefore repeated rather than optional, and left-associative — each arms block matches
   * whatever the chain has produced so far. An operand with no `match` after it is returned
   * unchanged, which is how this doubles as the fall-through from `expression` to `assignment`.
   */
  private lazy val matchExpr: PackratParser[Expr] =
    assignment ~ rep(opt(newlines) ~> op("match") ~>
      (newline ~> indent ~> opt(newlines) ~> repsep(matchArm, newlines) <~ opt(newlines) <~ dedent)) ^^ {
      case scrut ~ chain => chain.foldLeft(scrut)((e, arms) => MatchExpr(e, arms).setPos(e.pos))
    }

  /** One arm. The `->` separates a *pattern* from what to do when it matches, and `else` is not a
   * pattern — it is the fallback, and it takes its body directly, exactly as an `if`'s `else` does.
   *
   * The arrow after `else` is called out by name rather than left to fail as an expression, since
   * what it fails as is a body that does not start with a `->` and the position that reports is
   * the `match` several lines above.
   */
  private lazy val matchArm: Parser[MatchArm] =
    at(
      op("else") ~> op("->") ~> err("the 'else' arm takes its body directly, with no '->' — 'else' names no pattern to separate one from") |
        op("else") ~> (suite | inlineBody) ^^ (b => MatchArm(List(WildcardPattern), None, b)) |
        rep1sep(pattern, op("|")) ~ opt(op("if") ~> expression) ~ (op("->") ~> (suite | inlineBody)) ^^ {
          case pats ~ guard ~ b => MatchArm(pats, guard, b)
        },
    )

  /** Patterns: scalar literals and ranges, the `_` wildcard, a positional destructuring
   * `V(sub…)` (an enum variant or a struct), a named struct destructuring `S{field: sub…}`, or a
   * bare name — which the analyzer reads as a nullary-variant pattern when it names a variant of
   * the scrutinee's enum, and as a binding otherwise. A name may be qualified wherever a variant
   * may be, which is every form: a nullary variant is spelled like a name and reached like one.
   */
  private lazy val pattern: Parser[Pattern] =
    patternLit ~ (rangeOp ~ patternLit) ^^ { case lo ~ (inc ~ hi) => RangePattern(lo, hi, inc) } |
      structPattern |
      variantPattern |
      tuplePattern |
      wildcard ^^^ WildcardPattern |
      qualifiedName ^^ IdentPattern.apply |
      patternLit ^^ LitPattern.apply

  private lazy val variantPattern: Parser[Pattern] =
    qualifiedName ~ (op("(") ~> commaList(pattern) <~ op(")")) ^^ { case n ~ ps => VariantPattern(n, ps) }

  /** `S{field: sub, other}` — a named struct pattern. A bare `field` is shorthand for
   * `field: field`, binding the field to a variable of the same name.
   */
  private lazy val structPattern: Parser[Pattern] =
    qualifiedName ~ (op("{") ~> commaList(fieldPattern) <~ op("}")) ^^ { case n ~ fs => StructPattern(n, fs) }

  /** `(a, b)` — a tuple pattern. Two or more sub-patterns for the same reason the type takes two
   * or more parts, and a single one is refused where the type refuses it rather than being read as
   * a pattern in parentheses, which sysl has no use for.
   */
  private lazy val tuplePattern: Parser[Pattern] =
    (op("(") ~> commaList1(pattern) <~ op(")")) >> {
      case List(_) => err("a tuple pattern matches two or more parts — one part is not a tuple")
      case parts   => success(TuplePattern(parts))
    }

  private lazy val fieldPattern: Parser[(String, Pattern)] =
    ident ~ opt(op(":") ~> pattern) ^^ { case n ~ p => (n, p.getOrElse(IdentPattern(n))) }

  private lazy val wildcard: Parser[Unit] =
    accept("'_'", { case t: lexical.Identifier if t.chars == "_" => () })

  /** A pattern literal: any scalar literal, or a negated numeric literal. */
  private lazy val patternLit: Parser[Expr] =
    op("-") ~> (floatLit | intLit) ^^ (e => Unary("-", e)) |
      floatLit | intLit | charLit | strLit | boolLit

  private lazy val statements: PackratParser[List[Stmt]] =
    opt(newlines) ~> repsep(statement, newlines) <~ opt(newlines)

  /** A file: an optional module header, then its statements. A file with no header contributes to
   * the anonymous root module, which is what lets a one-file program be written with no ceremony.
   */
  private lazy val program: PackratParser[Program] =
    opt(newlines) ~> opt(moduleHeader) ~ statements ^^ { case m ~ body => Program(body, m, source) }

  // --- entry points --------------------------------------------------------------------

  /** Parses this parser's source as a single expression (used by the expression test tier). */
  def parseExpression: ParseResult[Expr] =
    phrase(expression <~ rep(newline))(reader(source.text))

  /** Parses this parser's source as a whole program. */
  def parseProgram: ParseResult[Program] =
    phrase(program)(reader(source.text))
}

object SyslParser {

  /** Parses a program, returning either the AST or a rendered diagnostic. */
  def parse(src: String, name: String = "<input>"): Either[String, Program] =
    parse(Source(name, src))

  def parse(source: Source): Either[String, Program] = {
    val p = new SyslParser(source)

    p.parseProgram match {
      case p.Success(prog, _) => Right(prog)
      case ns: p.NoSuccess    => Left(failedAt(source, ns.next.pos).render(ns.msg))
    }
  }

  /** Where a parse failed. Running out of tokens leaves no position at all, and pointing at the
   * end of the last line is more use than pointing at nothing — an unclosed block is exactly the
   * case that reports there.
   */
  private def failedAt(source: Source, at: scala.util.parsing.input.Position): Pos =
    if at.line > 0 then Pos(source, at.line, at.column)
    else {
      val last = math.max(1, source.lines.length)

      Pos(source, last, source.line(last).length + 1)
    }
}
