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
   */
  lazy val expression: PackratParser[Expr] = at(ifExpr | matchExpr | whileExpr | forExpr | assignment)

  private def binOp(sym: String): Parser[(Expr, Expr) => Expr] =
    op(sym) ^^^ ((l: Expr, r: Expr) => Binary(sym, l, r))

  /** Assignment is right-associative and lowest precedence, so it recurses on its right. The right
   * side is a full `expression`, so a control-flow expression may sit there — `x = match …`,
   * `total += if …` — in the same tail position `var x = …` already allows one; this does not put
   * those forms into a binary operand, so `1 + match …` still does not parse.
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
      // A call is the exception: what is wrong with `foo(…)` is nearly always `foo` — it does not
      // exist, or it does not take these arguments — so the callee's own position wins, and the
      // `(` is only the fallback for a callee that somehow has none.
      here ~ (op("(") ~> repsep(expression, op(",")) <~ op(")")) ^^ { case p ~ args =>
        (e: Expr) => Call(e, args).setPos(e.pos).setPos(p)
      } |
      here <~ op("?") ^^ (p => (e: Expr) => TryExpr(e).setPos(p)) |
      here <~ op("++") ^^ (p => (e: Expr) => PostIncDec("++", e).setPos(p)) |
      here <~ op("--") ^^ (p => (e: Expr) => PostIncDec("--", e).setPos(p))

  lazy val primary: PackratParser[Expr] =
    at(
      floatLit | intLit | charLit | interpLit | strLit | boolLit | nullLit | selfExpr | identExpr | arrayLit |
        op("(") ~> parenTail,
    )

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
      structDecl | enumDecl | traitDecl | implDecl | externDecl | funcDecl | varDecl | returnStmt | breakStmt |
        continueStmt | exprStmt,
    )

  /** A type: a memory-mode sigil applied to a type, or a name optionally applied to type
   * arguments (`Box[int]`, `Result[T, string]`). `sync` stays a soft keyword — it is only
   * special immediately after `&`, and the `&sync T` alternative is tried first so that a
   * reference to a type actually named `sync` still parses.
   */
  private lazy val typeRef: Parser[TypeRef] =
    at(
      op("*") ~> typeRef ^^ PtrType.apply |
        op("&") ~> softSync ~> typeRef ^^ (t => RefType(t, sync = true)) |
        op("&") ~> typeRef ^^ (t => RefType(t, sync = false)) |
        (op("[") ~> opt(expression) <~ op("]")) ~ typeRef ^^ { case n ~ t => ArrayType(n, t) } |
        ident ~ opt(op("[") ~> rep1sep(typeRef, op(",")) <~ op("]")) ^^ { case n ~ args =>
          NamedType(n, args.getOrElse(Nil))
        },
    )

  private lazy val softSync: Parser[Unit] =
    accept("'sync'", { case t: lexical.Identifier if t.chars == "sync" => () })

  /** The `[T, U]` type-parameter list of a generic declaration. */
  private lazy val typeParams: Parser[List[String]] =
    op("[") ~> rep1sep(ident, op(",")) <~ op("]")

  /** The type-parameter list of a generic *function*, where a parameter may carry a trait bound:
   * `[T, U: Show, V: Ord + Hash]`. It yields the parameter names alongside a name-keyed map of the
   * bounds, so an unbounded parameter is simply absent from the map. Bounds on a type's own
   * parameters (a struct or enum) are a separate, deferred surface, so only functions parse them.
   */
  private lazy val boundedTypeParams: Parser[(List[String], Map[String, List[String]])] =
    op("[") ~> rep1sep(boundedTypeParam, op(",")) <~ op("]") ^^ { ps =>
      (ps.map(_._1), ps.collect { case (n, bs) if bs.nonEmpty => n -> bs }.toMap)
    }

  private lazy val boundedTypeParam: Parser[(String, List[String])] =
    ident ~ opt(op(":") ~> rep1sep(ident, op("+"))) ^^ { case n ~ bs => (n, bs.getOrElse(Nil)) }

  private lazy val varDecl: PackratParser[Stmt] =
    op("var") ~> ident ~ opt(op(":") ~> typeRef) ~ opt(op("=") ~> expression) ^^ {
      case n ~ t ~ e => VarDecl(n, t, e)
    }

  private lazy val exprStmt: PackratParser[Stmt] = expression ^^ (e => ExprStmt(e).setPos(e.pos))

  private lazy val returnStmt: PackratParser[Stmt] =
    op("return") ~> opt(expression) ^^ Return.apply

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
      val (names, bounds) = tps.getOrElse((Nil, Map.empty))
      (op("(") ~> repsep(param, op(",")) <~ op(")")) ~ opt(op("->") ~> typeRef) ~ funcBody <~ endName(name) ^^ {
        case params ~ ret ~ body => FuncDecl(name, names, params, ret, body, bounds)
      }
    }

  /** `extern name(params) -> ret` — a header with no body at all, which is what tells it from a
   * function declaration. The result is optional and absent means `unit`, exactly as for a
   * function; `-> never` says the callee does not come back.
   */
  private lazy val externDecl: PackratParser[Stmt] =
    op("extern") ~> ident ~ (op("(") ~> repsep(param, op(",")) <~ op(")")) ~ opt(op("->") ~> typeRef) ^^ {
      case name ~ params ~ ret => ExternDecl(name, params, ret)
    }

  /** A function body is either an `= expr` short form (whose value is the return value) or an
   * indented block (whose trailing expression is the return value).
   */
  private lazy val funcBody: PackratParser[List[Stmt]] =
    op("=") ~> (suite | expression ^^ (e => List(ExprStmt(e).setPos(e.pos)))) | suite

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
    at(
      ident ~ opt(typeParams) >> { case name ~ tps =>
        methodTail(name, tps.getOrElse(Nil)) |
          (if tps.isEmpty then propertyTail(name) else failure("a property takes no type parameters"))
      },
    )

  private def methodTail(name: String, tparams: List[String]): Parser[MethodDecl] =
    (op("(") ~> methodParams <~ op(")")) ~ opt(op("->") ~> typeRef) ~ funcBody <~ endName(name) ^^ {
      case (recv, params) ~ ret ~ body => MethodDecl(name, recv, isProperty = false, tparams, params, ret, body)
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
    receiver ~ rep(op(",") ~> param) ^^ { case r ~ ps => (Some(r), ps) } |
      repsep(param, op(",")) ^^ (ps => (None, ps))

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
    op("enum") ~> ident ~ opt(typeParams) ~ opt(op(":") ~> typeRef) >> { case name ~ tps ~ under =>
      (newline ~> indent ~> opt(newlines) ~> repsep(enumItem, newlines) <~ opt(newlines) <~ dedent) <~ endName(name) ^^ {
        items =>
          val variants = items.collect { case Left(v)  => v }
          val members  = items.collect { case Right(m) => m }
          EnumDecl(name, tps.getOrElse(Nil), under, variants, members)
      }
    }

  /** A line inside an enum body is either a variant or a member declaration, told apart the same
   * way a struct body's lines are: a member is tried first and needs a body to follow its header,
   * so `Circle(radius: int)` — a header with nothing after it — falls through to `enumVariant`.
   */
  private lazy val enumItem: Parser[Either[EnumVariantDecl, MethodDecl]] =
    member ^^ (Right(_)) | enumVariant ^^ (Left(_))

  private lazy val enumVariant: Parser[EnumVariantDecl] =
    at(
      ident ~ (op("(") ~> repsep(param, op(",")) <~ op(")")) ^^ { case n ~ fs => EnumVariantDecl(n, None, fs) } |
        ident ~ (op("=") ~> expression) ^^ { case n ~ v => EnumVariantDecl(n, Some(v), Nil) } |
        ident ^^ (n => EnumVariantDecl(n, None, Nil)),
    )

  /** `trait Name` with indented method signatures. A signature is a method header — a receiver, a
   * parameter list, and an optional result — with no body; it parses to a `MethodDecl` whose empty
   * body marks it as a signature rather than a definition.
   */
  private lazy val traitDecl: PackratParser[Stmt] =
    op("trait") ~> ident ~ opt(typeParams) >> { case name ~ tps =>
      (newline ~> indent ~> opt(newlines) ~> repsep(methodSig, newlines) <~ opt(newlines) <~ dedent) <~ endName(name) ^^ {
        methods => TraitDecl(name, tps.getOrElse(Nil), methods)
      }
    }

  /** A trait method signature: a header with no `= body`. The receiver and parameters parse
   * exactly as a real method's do, so a signature and its implementation are compared shape for
   * shape.
   */
  private lazy val methodSig: PackratParser[MethodDecl] =
    at(
      ident ~ opt(typeParams) ~ (op("(") ~> methodParams <~ op(")")) ~ opt(op("->") ~> typeRef) ^^ {
        case name ~ tps ~ ((recv, params)) ~ ret =>
          MethodDecl(name, recv, isProperty = false, tps.getOrElse(Nil), params, ret, Nil)
      },
    )

  /** `impl Trait for Type` with indented method definitions — ordinary members, reusing the same
   * grammar as a method written in a struct's own body. The block is closed by an optional
   * `end Type`.
   */
  private lazy val implDecl: PackratParser[Stmt] =
    op("impl") ~> ident ~ (op("for") ~> ident) >> { case tname ~ forType =>
      (newline ~> indent ~> opt(newlines) ~> repsep(member, newlines) <~ opt(newlines) <~ dedent) <~ endName(forType) ^^ {
        methods => ImplDecl(tname, forType, methods)
      }
    }

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
    opt(labelRef) ~ (op("while") ~> expression) ~ body("do") ~ opt(elseClause) ~ opt(endMarker("while")) ^^ {
      case lbl ~ c ~ b ~ e ~ _ => While(lbl, c, b, e)
    }

  private lazy val forExpr: PackratParser[Expr] =
    opt(labelRef) ~ (op("for") ~> ident) ~ (op("in") ~> expression) ~ body("do") ~ opt(elseClause) ~ opt(
      endMarker("for"),
    ) ^^ {
      case lbl ~ n ~ it ~ b ~ e ~ _ => For(lbl, n, it, b, e)
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
    at(
      op("else") ~> (op("->") ~> (suite | inlineBody)) ^^ (b => MatchArm(List(WildcardPattern), None, b)) |
        rep1sep(pattern, op("|")) ~ opt(op("if") ~> expression) ~ (op("->") ~> (suite | inlineBody)) ^^ {
          case pats ~ guard ~ b => MatchArm(pats, guard, b)
        },
    )

  /** Patterns: scalar literals and ranges, the `_` wildcard, a positional destructuring
   * `V(sub…)` (an enum variant or a struct), a named struct destructuring `S{field: sub…}`, or a
   * bare name — which the analyzer reads as a nullary-variant pattern when it names a variant of
   * the scrutinee's enum, and as a binding otherwise.
   */
  private lazy val pattern: Parser[Pattern] =
    patternLit ~ (rangeOp ~ patternLit) ^^ { case lo ~ (inc ~ hi) => RangePattern(lo, hi, inc) } |
      structPattern |
      variantPattern |
      wildcard ^^^ WildcardPattern |
      ident ^^ IdentPattern.apply |
      patternLit ^^ LitPattern.apply

  private lazy val variantPattern: Parser[Pattern] =
    ident ~ (op("(") ~> repsep(pattern, op(",")) <~ op(")")) ^^ { case n ~ ps => VariantPattern(n, ps) }

  /** `S{field: sub, other}` — a named struct pattern. A bare `field` is shorthand for
   * `field: field`, binding the field to a variable of the same name.
   */
  private lazy val structPattern: Parser[Pattern] =
    ident ~ (op("{") ~> repsep(fieldPattern, op(",")) <~ op("}")) ^^ { case n ~ fs => StructPattern(n, fs) }

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

  private lazy val program: PackratParser[Program] = statements ^^ Program.apply

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
