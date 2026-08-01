package io.github.edadma.sysl

/** The expression grammar: the precedence ladder, the postfix tail, and every literal form.
 *
 * The ladder is the whole of `01`'s precedence table written out as one rule per level, loosest
 * first — assignment, `||`, `&&`, chained comparison, range, `|`, `^`, `&`, additive,
 * multiplicative-and-shift, prefix, postfix. Each level is `chainl1` over the next, which iterates
 * rather than left-recurses, so associativity falls out of the combinator and the grammar stays
 * linear under packrat.
 *
 * Two levels are deliberately not C's. Comparison **chains** rather than associates, so `a < b < c`
 * is one node and not a comparison against a `bool`. Shift binds like multiplication rather than
 * below the comparisons, which is the correction that makes `a & 1 << n` mean what it looks like.
 */
trait ExprParser extends SyslParserBase {

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
  protected lazy val lambda: PackratParser[Expr] =
    lambdaParams ~ (op("->") ~> lambdaBody) ^^ { case ps ~ b => Lambda(ps, b) }

  /** One parameter with no parentheses, or a parenthesized list of them — including the empty list,
   * which is the one arity that has nowhere else to be written (`12 §5`).
   */
  protected lazy val lambdaParams: Parser[List[LambdaParam]] =
    op("(") ~> commaList(lambdaParam) <~ op(")") |
      at(ident ^^ (n => LambdaParam(n, None))) ^^ (List(_))

  /** A closure's parameter: a name, and a type only where there is nothing to infer one from. The
   * annotation is written inside the parentheses and nowhere else, so `x: int -> …` is not a second
   * spelling of `(x: int) -> …` waiting to disagree with it.
   */
  protected lazy val lambdaParam: Parser[LambdaParam] =
    at(
      ident ~ (op(":") ~> typeRef) <~ noDefault ^^ { case n ~ t => LambdaParam(n, Some(t)) } |
        ident ^^ (n => LambdaParam(n, None)),
    )

  /** A closure's parameter declares no default (`12 §2a`). It is matched through the `Fn` trait,
   * which carries types and no names, so a call through one has nothing to read a default out of —
   * the same absence that stops a closure being called by name. Said here rather than left to the
   * "')' expected" a grammar with no place for one would give.
   *
   * Reached only where the parameter wrote a **type**, and that is what makes saying it safe. A
   * parenthesized list is read speculatively — `(x = 1)` is an argument that is a store, and `(a, b)`
   * is a tuple — so a refusal here that could fire on one of those would refuse it before the rule
   * that was going to accept it ever ran. `x: int` is an expression in no reading, so having got
   * that far the list is a closure's parameters and nothing else.
   */
  protected lazy val noDefault: Parser[Unit] =
    op("=") ~> err("a closure's parameter declares no default — a call reaches a closure through " +
      "'Fn', which carries the types and not the names, so there would be nothing at the call to " +
      "fill one from. Give the value a name of its own and capture it") |
      success(())

  /** A closure's body, which is a function's body without the `=`: an expression, or an indented
   * block whose trailing expression is the value.
   */
  protected lazy val lambdaBody: PackratParser[List[Stmt]] =
    suite | expression ^^ (e => List(ExprStmt(e).setPos(e.pos)))

  protected def binOp(sym: String): Parser[(Expr, Expr) => Expr] =
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

  protected def assignOp: Parser[String] =
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

  protected def compareOp: Parser[String] =
    op("==") | op("!=") | op("<=") | op(">=") | op("<") | op(">")

  protected def rangeOp: Parser[Boolean] = op("..<") ^^^ false | op("..") ^^^ true

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
  protected lazy val postfixTail: PackratParser[Expr => Expr] =
    here ~ (op("[") ~> expression <~ op("]")) ^^ { case p ~ idx => (e: Expr) => Index(e, idx).setPos(p) } |
      here ~ (op(".") ~> ident) ^^ { case p ~ n => (e: Expr) => Field(e, n).setPos(p) } |
      here ~ (op(".") ~> (tupleIndex | nestedTupleIndex)) ^^ { case p ~ n => (e: Expr) => Field(e, n).setPos(p) } |
      here ~ (op("::") ~> ident) ^^ { case p ~ n => (e: Expr) => TypeAttr(e, n).setPos(p) } |
      // A call is the exception: what is wrong with `foo(…)` is nearly always `foo` — it does not
      // exist, or it does not take these arguments — so the callee's own position wins, and the
      // `(` is only the fallback for a callee that somehow has none.
      here ~ (op("(") ~> commaList(argument) <~ op(")")) ^^ { case p ~ args =>
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
  protected lazy val tupleIndex: Parser[String] =
    accept("tuple index", { case t: lexical.IntLit if t.suffix.isEmpty => t.value.toString })

  /** `t.0.1` — the nested selection that the lexer reads as one number, since `0.1` is a float
   * before it is two indices (`00 §13`). Fatal rather than a backtrack: nothing else can be meant
   * by a float immediately after a `.`, and the fix is worth naming where it happened.
   */
  protected lazy val nestedTupleIndex: Parser[Nothing] = Parser { in =>
    in.first match
      case t: lexical.FloatLit if t.suffix.isEmpty && t.text.matches("""\d+\.\d+""") =>
        val Array(outer, inner) = t.text.split('.')

        Error(s"'.${t.text}' reads as a number rather than as two tuple indices — " +
          s"write '(x.$outer).$inner' to select part $inner of part $outer", in)
      case _ => Failure("tuple index expected", in)
  }

  lazy val primary: PackratParser[Expr] =
    at(
      floatLit | intLit | charLit | interpLit | cStrLit | strLit | boolLit | nullLit | layoutOf | selfExpr |
        identExpr |
        arrayLit |
        op("(") ~> parenTail,
    )

  /** `sizeof(T)` and `alignof(T)` — the two forms whose operand is a type (`03 § Reinterpreting
   * storage`).
   *
   * They are read here rather than left to look like calls because a call's argument list holds
   * expressions, and `sizeof(*Node)` would parse as a dereference of a name. Both words are reserved,
   * so nothing else can be meant by one and the parentheses can be insisted on.
   */
  protected lazy val layoutOf: PackratParser[Expr] =
    (op("sizeof") ^^^ "sizeof" | op("alignof") ^^^ "alignof") >> { what =>
      (op("(") ~> typeRef <~ op(")")) ^^ (t => LayoutOf(what, t)) |
        err(s"'$what' takes a type in parentheses, as '$what(int)' — there is no form that takes a " +
          "value, since a value's type is what would be measured anyway")
    }

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
  /** One argument at a call: an ordinary expression, or `name = value` standing at the parameter it
   * names rather than at the one its position would give it (`12 §2a`).
   *
   * `name = value` is also a legal expression — assignment yields the value stored — so the two
   * readings collide and the named argument is the one taken. It is tried first, and only where the
   * name is a bare identifier with an `=` after it: `p.x = 1` and `b[i] = v` are stores as they
   * always were, since neither is a name a parameter list could have written. A store to a plain
   * variable is still reachable in an argument by parenthesizing it, and a name matching no
   * parameter is refused by the analyzer rather than quietly falling back to the store.
   */
  protected lazy val argument: PackratParser[Expr] =
    at(ident ~ (op("=") ~> expression) ^^ { case n ~ v => NamedArg(n, v) }) | expression

  protected def commaList[T](p: Parser[T]): Parser[List[T]] = commaList1(p) | success(Nil)

  protected def commaList1[T](p: Parser[T]): Parser[List[T]] = rep1sep(p, op(",")) <~ opt(op(","))

  /** `self` is reserved, so it never lexes as an identifier; inside a method body it reads as an
   * ordinary name that the analyzer resolves to the receiver binding, and is undefined elsewhere.
   */
  protected lazy val selfExpr: Parser[Expr] = op("self") ^^^ Ident("self")

  /** `[a, b, c]` — an array literal, or `[v; n]` — an array of `n` copies of one value. A leading
   * `[` is unambiguous in operand position, since a subscript is a postfix tail on something
   * already parsed, and the two forms separate on the token after the first expression.
   */
  protected lazy val arrayLit: PackratParser[Expr] =
    op("[") ~> expression ~ (op(";") ~> expression) <~ op("]") ^^ { case v ~ n => ArrayFill(v, n) } |
      op("[") ~> commaList(expression) <~ op("]") ^^ ArrayLit.apply

  /** After `(`: `)` is unit, one expression is a grouping, more are a tuple. */
  protected lazy val parenTail: PackratParser[Expr] =
    op(")") ^^^ UnitLit() |
      expression ~ rep(op(",") ~> expression) <~ op(")") ^^ {
        case e ~ Nil  => e
        case e ~ more => Tuple(e :: more)
      }

  protected lazy val intLit: Parser[Expr] =
    accept("integer literal", { case t: lexical.IntLit => IntLit(t.value, t.suffix) })

  protected lazy val floatLit: Parser[Expr] =
    accept("float literal", { case t: lexical.FloatLit => FloatLit(t.text, t.suffix) })

  protected lazy val charLit: Parser[Expr] =
    accept("character literal", { case t: lexical.CharLit => CharLit(t.codepoint) })

  protected lazy val strLit: Parser[Expr] =
    accept("string literal", { case t: lexical.StrLit => StrLit(t.value) })

  protected lazy val cStrLit: Parser[Expr] =
    accept("C string literal", { case t: lexical.CStrLit => CStrLit(t.value) })

  /** An interpolated string desugars to the concatenation of its literal segments with each
   * embedded expression rendered by `str`: `s"a${e}b"` becomes `"a" + str(e) + "b"`. The embedded
   * source is parsed here as an ordinary expression — so it may itself interpolate — and an empty
   * literal segment is dropped, since it is the identity under `+`.
   */
  protected lazy val interpLit: Parser[Expr] = Parser { in =>
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

  protected def desugarInterp(t: lexical.StrInterp): Either[String, Expr] = {
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
  protected def parseEmbedded(src: String): Either[String, Expr] = {
    val sub = new SyslParser(Source(s"${source.name} (interpolation)", src))

    sub.parseExpression match
      case sub.Success(e, _) => Right(e)
      case ns: sub.NoSuccess => Left(s"in interpolation '$src': ${ns.msg}")
  }

  protected lazy val boolLit: Parser[Expr] =
    op("true") ^^^ BoolLit(true) | op("false") ^^^ BoolLit(false)

  protected lazy val nullLit: Parser[Expr] = op("null") ^^^ NullLit()

  protected lazy val identExpr: Parser[Expr] = ident ^^ Ident.apply
}
