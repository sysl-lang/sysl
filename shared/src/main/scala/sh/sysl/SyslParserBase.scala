package sh.sysl

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
   *
   * **The node is stamped in place and `p`'s own result is handed back**, rather than a fresh
   * `Success` around it. A `Success` carries the furthest failure the parse reached on its way here
   * (`lastFailure`), which is what a parser that backtracks and then succeeds knows about the file
   * and nothing else does — and it is what `phrase` reports when the whole parse stops short. That
   * field has no public constructor, so rebuilding the result silently emptied it: every rule in the
   * grammar is wrapped in this one, so what survived to be reported was whatever failed *outside*
   * the outermost `at`, which is a position near the top of the file rather than near the mistake.
   *
   * **A failure the rule did not get past is dropped, and only one further on is kept.** A rule that
   * ends in an optional tail — `logicalOr ~ assignOp ~ expression | logicalOr`, or any `opt` — leaves
   * behind a failure at exactly the token it stopped before, saying which of the tail's spellings it
   * looked for last. That is a road not taken rather than a reason, and it is at the position the
   * enclosing rule is about to complain about itself, with a message about what it actually wanted.
   * `val x = 1 2` reported `'>>=' expected` from an assignment nobody was writing; the token the
   * statement wanted there is a newline, and dropping the road not taken is what lets it say so.
   * Rebuilding the result is how the field is emptied, since it cannot be written.
   */
  protected def at[T <: Positioned](p: => Parser[T]): Parser[T] =
    Parser { in =>
      p(in) match {
        case s @ Success(t, rest) =>
          t.setPos(posOf(in))

          if s.lastFailure.exists(f => !(rest.pos < f.next.pos)) then Success(t, rest) else s
        case other => other
      }
    }

  /** Renames the failure `p` reports when it fails **without consuming anything**, so the reader is
   * told what was wanted rather than which candidate the grammar happened to try last.
   *
   * The precedence ladder is a stack of alternations, and `Failure.append` keeps the *last* of the
   * candidates that failed at one position. A token that can begin no expression at all fails every
   * level of the ladder at the same place, so what was reported was whatever sits at the bottom of
   * the last alternative tried — which is why four unrelated mistakes all used to say `'..' expected`
   * and mention a range nobody had written.
   *
   * The rename fires only at the rule's own start. A failure further along is the grammar having got
   * somewhere and then found something specific missing, and that message is the better one.
   */
  protected def describe[T](what: String)(p: => Parser[T]): Parser[T] =
    Parser { in =>
      p(in) match {
        case f: Failure if !(in.pos < f.next.pos) => Failure(s"$what expected", in)
        case other                                => other
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

  /** A name, however it was written — bare, or backtick-quoted.
   *
   * The two forms are one token here because every position that names something takes either: a
   * quoted name is a name, and nothing about a parameter, a field or a call cares how it was
   * spelled. The places that *do* care are the ones matching a specific `Identifier` by its text —
   * the soft keywords `end`, `sync`, `volatile` and `Fn` — and they get the distinction for free by
   * naming the bare token, which is why `` `end` `` is a name rather than a block terminator.
   */
  protected lazy val ident: Parser[String] =
    accept("identifier", {
      case t: lexical.Identifier   => t.chars
      case t: lexical.QuotedIdent  => t.name
    })

  /** A reserved word, as a word — `val`, `match`, `struct`, and not `+` or `..<`.
   *
   * Both lex as `Keyword`, so what tells them apart is the spelling: a reserved word begins with a
   * letter and an operator does not. That test is the whole of it, and it stays right as the set
   * grows, which counting the list here would not.
   *
   * It exists for the refusals below rather than for the grammar — nothing is *read* through it, and
   * a rule that consumed one would be claiming a word the statement grammar needs.
   */
  protected lazy val reservedWord: Parser[String] =
    accept("reserved word", { case t: lexical.Keyword if t.chars.head.isLetter => t.chars })

  /** The sentence a reserved word written where a name is being **bound** is owed — `val: int` as a
   * field, or as a parameter.
   *
   * **The colon is part of the lookahead and is what keeps this off ground the expression grammar
   * needs.** A reserved word may perfectly well begin an argument — `f(true)`, `f(null)`, `f(if c
   * then 1 else 2)` — and a call written at statement position is tried against the *declaration*
   * grammar first, so a refusal keyed on the word alone raises an `Error` inside the parameter list
   * and takes every one of those with it. Followed by a colon it can only be a binding somebody
   * attempted.
   *
   * **It is written out rather than assembled from `guard`, because a lookahead leaks its own
   * position.** `guard(reservedWord ~ op(":"))` hands back the inner failure *as it stands* — at the
   * colon, one token past the word — and a `Failure` further along the line outranks the one `ident`
   * raises at the word itself. What that produced was `reserved word expected` where a field with no
   * name at all should say `identifier expected`, which is the artifact `ParseDiagnosticTests` exists
   * to catch, and did. Re-basing the failure onto `in` puts both candidates at one position, where
   * the last of them wins and `ident` is written last for exactly that reason.
   *
   * **The backtick form is named because it works**, and nothing else tells the reader so: a quoted
   * name is an ordinary name at every position, so `` `val` `` is a field, a parameter or a variable
   * called `val`. Without this the reader is told a name was expected at a place where they wrote
   * one, and the only thing wrong with it is that the language had already spent the word.
   */
  protected def reservedBinding(what: String): Parser[String] =
    Parser { in =>
      guard(reservedWord ~ op(":"))(in) match
        case Success(w ~ _, _) =>
          Error(
            s"'$w' is a reserved word, so it cannot stand as $what — write it '`$w`' if that is " +
              s"the name you want, which is what the backticks are for: a quoted word is an " +
              s"ordinary name wherever one may be written",
            in,
          )
        case ns: NoSuccess => Failure(ns.msg, in)
    }

  /** A name that was written without quoting, and only that — what a **module path** is made of. */
  protected lazy val bareIdent: Parser[String] =
    accept("identifier", { case t: lexical.Identifier => t.chars })

  /** A name that was written backtick-quoted, and only that.
   *
   * One production reads it: a pattern, where the quoting is what says the name is a reference to
   * something already declared rather than a new binding (`09`).
   */
  protected lazy val quotedIdent: Parser[String] =
    accept("quoted identifier", { case t: lexical.QuotedIdent => t.name })

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

  /** Blank lines, however many, including none — the spelling to reach for wherever a construct
   * tolerates them rather than requires them.
   *
   * It cannot fail, and that is the point rather than a convenience. `opt(newlines)` reads the same
   * tokens, but on a line that starts with something else it first *records* a failure saying a
   * newline was expected, and that expectation then competes with the real one: a file whose first
   * statement will not parse was reported as `newline expected` against a line where a newline would
   * have been no help at all. Nothing is ever owed a newline here, so nothing should say it was.
   */
  protected def skipNewlines: Parser[Unit] = Parser { in =>
    var rest = in

    while (!rest.atEnd && rest.first == lexical.Newline) rest = rest.rest

    Success((), rest)
  }

  /** A continuation the writer *may* have put on a following line — a `match`, an `else`, an `end`.
   *
   * Blank lines are crossed to look for it, and where it is not there the refusal is reported back
   * at the token the search started from. Reporting it where the search ended would be a demand for
   * a keyword nobody was writing, at a line the reader had not connected to this construct — and,
   * being further into the file, it would outrank the real mistake and be the one message shown.
   * `print(1)` followed by a stray `)` was reported as `'match' expected` against the `)`.
   */
  protected def onNextLine[T](p: => Parser[T]): Parser[T] = asOneToken(skipNewlines ~> p)

  /** Reports `p`'s refusal back at the token `p` began at, whatever `p` crossed before noticing.
   *
   * For a construct that is being *looked for* rather than required: what the search crossed is not
   * the reader's mistake, and a refusal recorded past it would outrank the real one by sitting
   * further into the file. Where the enclosing rule is a [[maybe]] or a [[repeatedly]], reporting at
   * the start is also what lets them recognise the construct as absent and say nothing at all.
   */
  protected def asOneToken[T](p: => Parser[T]): Parser[T] =
    Parser { in =>
      p(in) match {
        case f: Failure => Failure(f.msg, in)
        case other      => other
      }
    }

  /** `opt(p)` for a construct whose **absence is ordinary**, such as a file's module header.
   *
   * `opt` records what `p` wanted even when `p` never began, so a file that opens with something the
   * grammar cannot read at all was told `'module' expected` — a header nobody omits by mistake, and
   * a demand that would not have helped. Here a refusal at the first token is dropped: the construct
   * simply is not there. A refusal *after* one is kept, exactly as `opt` keeps it, because by then
   * the writer had started the construct and the complaint is about how it goes on.
   */
  protected def maybe[T](p: => Parser[T]): Parser[Option[T]] =
    Parser { in =>
      p(in) match {
        case s @ Success(_, _)                 => s.map(Some(_))
        // Past the first token the writer had started the construct, so the complaint is worth
        // keeping — and `append` onto a `Success` is how the library itself records one.
        case f: Failure if in.pos < f.next.pos => f.append(Success(None, in))
        case _: Failure                        => Success(None, in)
        case e: Error                          => e
      }
    }

  /** `rep(p)` for a construct whose **absence is ordinary** — the repeated form of [[maybe]].
   *
   * A repetition ends by `p` failing, and `rep` records that failure: the list of header attributes
   * a file may open with therefore left behind an expectation of one more attribute, on the very
   * line whose real problem was something else entirely. Ties are settled in favour of whichever was
   * recorded first, and this one is recorded before the statement that will actually fail — so it
   * won, and a file opening with a stray bracket was told `newline expected`.
   */
  protected def repeatedly[T](p: => Parser[T]): Parser[List[T]] =
    maybe(p) >> {
      case Some(x) => repeatedly(p) ^^ (x :: _)
      case None    => success(Nil)
    }

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

  /** That a [[suite]] is what comes next, without reading any of it — for the places a block is one
   * *alternative* among others rather than the only thing that may stand there.
   *
   * It exists because of where the refusal lands. `suite` consumes the newline before asking for the
   * indent, so where there is no block it fails one token further along than an `expression`
   * alternative does — and the furthest failure is the one reported. A closure whose body was
   * forgotten, or a binding whose value was, therefore got `indent expected` against the *following*
   * line, demanding a block the writer had not begun and was not going to. [[asOneToken]] puts that
   * refusal back where the search started, so both alternatives fail at one token and the later of
   * them is the one whose message is shown.
   *
   * It guards the look-ahead only. Once the indent is there the block has *begun*, and a mistake
   * inside it is reported where it was made, exactly as [[maybe]] keeps a refusal past the first
   * token.
   */
  protected lazy val blockAhead: Parser[Unit] =
    asOneToken(guard(newline ~ indent)) ^^^ (())

  /** A block introduced by a keyword, written inline after it or indented under it. */
  protected def body(keyword: String): Parser[List[Stmt]]

  /** A block written on one line, which is the single statement it holds. */
  protected def inlineBody: PackratParser[List[Stmt]]

  /** The quoted symbol an `extern` or a `@link` names, where that differs from the word the program
   * calls it by (`15 §8`).
   */
  protected def linkName: Parser[String]

  /** One pattern, as a `match` arm writes it. Declared here because a condition may test one too
   * (`09 §12`), and the two spellings must be the same grammar rather than two that drift.
   */
  protected def pattern: Parser[Pattern]

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

  /** The string a contract clause or a `@test` carries — the sentence a reader is shown when the
   * check fails, or the name a test report gives the function.
   */
  protected def contractMsg: Parser[String]

  /** The refusal every member block opens each of its lines with: an annotation's sigil where a
   * member was wanted. Defined beside the annotations it is about, and reached from the rules that
   * read a member.
   */
  protected def noMemberAttr: Parser[Unit]

  /** A statement written on the same line as the keyword that introduces it. */
  protected def inlineStatement: PackratParser[Stmt]

  /** A whole file's worth of statements. */
  protected def statements: PackratParser[List[Stmt]]

  /** The expression a one-line function body is, which is an expression that may also be the comma
   * list a multi-result signature returns.
   */
  protected def resultValue: PackratParser[Expr]

  // The six expression forms that are written like statements. `expression` admits them directly,
  // which is what makes `var x = if c then a else b` a binding rather than a special case.

  protected def ifExpr: PackratParser[Expr]
  protected def whileExpr: PackratParser[Expr]
  protected def doWhileExpr: PackratParser[Expr]
  protected def loopExpr: PackratParser[Expr]
  protected def forExpr: PackratParser[Expr]
  protected def matchExpr: PackratParser[Expr]

  /** `for all i in r do P` / `for some i in r do P` (`17 §2`), which is not one of the six above:
   * it is written like an operand rather than like a statement, and it yields a `bool`.
   */
  protected def quantifier: PackratParser[Expr]
}
