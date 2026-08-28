package sh.sysl

import scala.collection.mutable.ListBuffer
import scala.util.parsing.input.CharArrayReader.EofCh
import scala.util.parsing.input.{CharSequenceReader, Position, Reader}

import io.github.edadma.indentation.IndentationLexical

/** The sysl lexer.
 *
 * Off-side rule, bracket line joining, and comment skipping come from
 * `IndentationLexical`; everything sysl-specific is here: the token ADT, the literal
 * grammar, the reserved words, and the closed operator set.
 *
 * The token types are declared inside the class because `scala-parser-combinators`
 * makes `Token` path-dependent — a parser sees them as `lexical.IntLit` and friends.
 *
 * Literals carry their *value*, not a tagged string: an `IntLit` holds a `BigInt`
 * because the integer family is arbitrary-width, and a `FloatLit` holds its text
 * because `f128` does not fit a `Double`. Widening either later is then a matter of
 * the analyzer, not of re-lexing.
 */
class SyslLexical
    extends IndentationLexical(
      newlineBeforeIndent = true,
      newlineAfterDedent = true,
      startLineJoining = List("(", "[", "{"),
      endLineJoining = List(")", "]", "}"),
      lineComment = "//",
      blockCommentStart = "/*",
      blockCommentEnd = "*/",
    ) {

  /** An integer literal. `suffix` is a canonical primitive type name (`i8`, `u12`,
   * `usize`, …); absent means the literal takes its type from context.
   */
  case class IntLit(value: BigInt, suffix: Option[String]) extends Token {
    def chars: String = value.toString + suffix.getOrElse("")
  }

  /** A floating-point literal, kept as text so no precision is lost before the
   * analyzer knows the target width.
   */
  case class FloatLit(text: String, suffix: Option[String]) extends Token {
    def chars: String = text + suffix.getOrElse("")
  }

  /** A character literal, holding one Unicode scalar value. */
  case class CharLit(codepoint: Int) extends Token {
    def chars: String = codepointToString(codepoint)
  }

  /** A loop label, `'name` — the apostrophe form Rust uses. It is told from a character literal by
   * the absence of a closing quote: `'a'` is the character, `'a` is the label.
   */
  case class Label(name: String) extends Token {
    def chars: String = s"'$name"
  }

  /** A backtick-quoted identifier, `` `like this` `` — a name the ordinary identifier grammar would
   * refuse: a reserved word, or one carrying spaces and punctuation.
   *
   * It is its own token rather than an `Identifier` for two reasons. The soft keywords — `end`,
   * `sync`, `volatile`, `Fn` — match `Identifier` by its text, so they reject a quoted one for free,
   * and `` `end` `` is therefore a name rather than a block terminator. And a pattern has to tell a
   * reference from a binding, which is the whole of what the quoting means there.
   */
  case class QuotedIdent(name: String) extends Token {
    def chars: String = name
  }

  /** A string literal, holding its decoded value. */
  case class StrLit(value: String) extends Token {
    def chars: String = value
  }

  /** A C string literal, `c"…"` — the same decoded value, marked for the terminator C expects and
   * the `*u8` it reads as. Told apart at the token so nothing downstream has to remember which
   * quote form produced a given value.
   */
  case class CStrLit(value: String) extends Token {
    def chars: String = s"c\"$value\""
  }

  /** An interpolated string, `s"…"`, `raw"…"`, or `f"…"`. The literal segments are already decoded
   * (with escapes, unless `raw`); the embedded expressions are held as their raw source, to be
   * lexed and parsed where the token is consumed. `specs` carries one entry per hole — the printf
   * specifier written after it in an `f"…"` string, or `None` for a hole rendered by `str`. The
   * invariants `parts.length == exprs.length + 1` and `specs.length == exprs.length` let the parser
   * interleave them: `parts(0) + render(exprs(0)) + parts(1) + …`.
   */
  case class StrInterp(parts: List[String], exprs: List[String], specs: List[Option[String]]) extends Token {
    def chars: String =
      parts.head + parts.tail.zip(exprs).map { case (p, e) => "${" + e + "}" + p }.mkString
  }

  /** Reserved words. Type names are deliberately absent: `int`, `usize`, `f32` and the
   * rest are predeclared identifiers resolved by the analyzer, as in Go and Swift, so
   * the open `iN` / `uN` / `fN` families need no lexical support. This set grows as the
   * statement grammar is settled.
   */
  reserved ++= List(
    "true",
    "false",
    "null",
    "var",
    "val",
    "ref",
    "const",
    "static",
    "if",
    "then",
    "elif",
    "else",
    "while",
    "loop",
    "do",
    "for",
    "in",
    "break",
    "continue",
    "defer",
    "match",
    "struct",
    "enum",
    "trait",
    "impl",
    "override",
    "extern",
    "module",
    "import",
    "as",
    "private",
    "return",
    "self",
    "weak",
    "sizeof",
    "alignof",
    "offsetof",
    "require",
    "ensure",
    "type",
  )

  /** The operator set is closed, so operators are a fixed list tokenized by longest
   * match — `delim` orders the alternatives so `..<` wins over `..`.
   */
  delimiters ++= List(
    "=", "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", "<<=", ">>=",
    "||", "&&", "!",
    "==", "!=", "<", ">", "<=", ">=",
    "..", "..<", "...",
    "|", "^", "&", "~",
    "+", "-", "*", "/", "%", "<<", ">>",
    "++", "--",
    "(", ")", "[", "]", "{", "}", ".", "?",
    // The wildcard import's tail is one token rather than a `.` and a `*`. A `.` is otherwise only
    // ever followed by a name, so the pair is unambiguous — and lexing it together is what lets `*`
    // stay in the continuation set below, since a line then never ends in a bare `*` that was
    // really the end of a statement.
    ".*",
    ",", "::", ":", "->",
    // Opens an **annotation**, which is a declaration's only prefix that is not a word
    // (`reference/attributes.md § @test — a function with a caller nothing else has`). It is deliberately not an operator: nothing in the expression grammar spells
    // `@`, so a line beginning with one can only be an annotation and the reading needs no
    // lookahead.
    //
    // A **directive** keeps `#`, and the two are told apart by the sigil rather than by the margin.
    // That is what lets an annotation sit at column 1 — which one on a `module` line has to, since
    // the declaration it is on is there — where a rule about indentation would have had the two
    // forms competing for the same position.
    "@",
    // Kept a token although no declaration form reads one, so that a `#` reaching the grammar is
    // answered by a sentence naming `@` rather than by the lexer's complaint about a character it
    // does not know. Someone arriving from Rust or C will write `#` for an annotation, and that is
    // a reading worth answering rather than a typo.
    "#",
    // Only ever a separator inside a three-clause `for` header (`00` §10). It is deliberately not a
    // statement terminator: a line ends a statement, and a token that could also end one would give
    // the language two answers to the same question.
    ";",
  )

  /** The operators that carry an expression onto the next line.
   *
   * A bracketed expression already continues, because `(`, `[` and `{` suspend the off-side rule
   * until they close. What was missing is the unbracketed case, and the rule is the narrowest one
   * that covers it: **an operator that cannot finish an expression continues the line.** After any
   * of these something must follow, so a newline there cannot have been the end of a statement and
   * there is nothing to be ambiguous about.
   *
   * That rule decides the exclusions rather than a taste for which operators look right:
   *
   *   - **`=` and `->`** are left out although they are binary, because both already open an
   *     indented block — a function body, a match arm — and a token cannot mean "the block starts
   *     here" and "the line goes on" at once.
   *   - **`++`, `--` and `?`** are postfix, so a line ending in one is a complete statement.
   *   - **`..`, `..<` and `...`** are left out because they too can be complete: `s[..]` is the
   *     whole range and `int...` is a variadic tail.
   *
   * **`*` needed the exception removed rather than taken.** A wildcard import is a whole statement
   * whose text ends in a `*`, so continuing there joined the import to the declaration after it —
   * eighteen tests said so. The fix is not to drop multiplication from the set, which would leave
   * the rule with an arbitrary hole, and not to respell the wildcard: it is to lex `.*` as one
   * token, above. A `.` is only ever followed by a name, so nothing else can produce that pair, and
   * a line then never ends in a bare `*` that was really the end of a statement.
   *   - **`.`** is left out because the continuation style worth having for a call chain puts the
   *     dot at the *start* of the following line, which needs the opposite mechanism — and now has
   *     one, in `isLineContinuationStart` below. A trailing `.` remains an error, deliberately: two
   *     ways to write one chain is a style argument in every file that has one.
   *   - **`,`, `:` and `;`** are separators, and the only one with a real customer (`,`) already
   *     continues wherever it appears, since an argument list is bracketed.
   *
   * Prefix operators are in: `!`, `~` and the unary readings of `-`, `*` and `&` cannot finish an
   * expression either, and leaving them out would make the rule a list to memorize instead of a
   * rule.
   *
   * The one hazard is the one every joining language has, including the brackets sysl already
   * joins on: a continuation line that is *dedented* has its dedent swallowed with the newline, so
   * a trailing operator can silently hold a block open. It is written down here rather than
   * guarded against, because guarding would mean the indentation of a continuation line carried
   * meaning, and the point of continuing is that it does not.
   */
  private val continuationOperators = Set(
    "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", "<<=", ">>=",
    "||", "&&", "!",
    "==", "!=", "<", ">", "<=", ">=",
    "|", "^", "&", "~",
    "+", "-", "*", "/", "%", "<<", ">>",
  )

  override protected def isLineContinuationToken(tok: Token): Boolean = tok match
    case Keyword(chars) => continuationOperators(chars)
    case _              => false

  /** A line beginning `.name` continues the line above it.
   *
   * This is the other half of the rule above, and it exists because a *chain* is the one expression
   * people habitually break across lines, and the break they write is before the dot rather than
   * after it:
   *
   * {{{
   * val face = text(label)
   *     .foreground(WHITE)
   *     .padding(8)
   *     .background(ground, radius)
   * }}}
   *
   * Every language with a fluent surface reads that shape — Scala, Kotlin, Swift, C# — and a
   * language whose interface toolkit is built out of chained modifiers cannot ask for it on one
   * line: the four links above are 92 characters together, and there is no operator at the end of
   * any of them for the trailing rule to see.
   *
   * **A name after the dot is required, and that is the whole safety argument.** A continued line's
   * own margin is discarded, so a rule that fired on something which could also *begin* a statement
   * would pull the line into the block above and move where that block ends. Nothing in the
   * expression grammar begins a statement with `.`, and requiring a letter or `_` after it excludes
   * everything else spelled with one: `..` and `..<` are ranges, `...` is a variadic tail, `.0` is a
   * tuple index, and `.*` is an import wildcard. None of those can start a line either, but the
   * predicate does not have to know that — it never sees them.
   *
   * The one thing it deliberately does not do is join a line beginning with an *operator*. `+` and
   * friends already continue from the end of the previous line, and a language that accepted both
   * ends would be asking every file to pick a side.
   *
   * **AND THE LINE ABOVE HAS TO ADMIT IT, WHICH LOOKAHEAD ALONE CANNOT SEE.** sysl has one other
   * construct spelled with a leading dot — the **implicit member** (`.Red`, whose type comes from
   * what the context expects) — and a `match` arm's pattern begins a line:
   *
   * {{{
   * val n = c match
   *     .Red -> 1
   * }}}
   *
   * Joined, that reads as `c match .Red`, and the reader who wrote the form they had just been
   * taught gets `newline expected` instead of the sentence `PatternParser.noImplicitMember` exists to
   * give them. So the rule asks both ends, which is the exact dual of the trailing one above: a
   * trailing operator continues because it **cannot** finish an expression, and a leading dot
   * continues only where the line above **could** have.
   *
   * A reserved word cannot finish one — there is nothing to call a method on — so `match`, `then`,
   * `else`, `do` and the rest all decline, and no call chain is lost, because none of them can be a
   * receiver. The four that *are* values are the exception, and `self` is the one that matters:
   * `self` on its own line with `.field` under it is an ordinary chain.
   *
   * **What this does NOT rescue is the second arm**, whose line above ends in whatever the first
   * arm's value was — an ordinary expression, which admits the join. That is the residual cost, and
   * it is bounded by the fact that an implicit member in a pattern is *illegal in sysl either way*:
   * what is lost is the quality of a diagnostic, never the meaning of a legal program.
   */
  override protected def isLineContinuationStart(r: Reader[Char]): Boolean =
    !r.atEnd && r.first == '.' && {
      val after = r.rest

      !after.atEnd && (after.first.isLetter || after.first == '_')
    } && canEndExpression(previousToken)

  /** The tokens that open an indented block, so that they open one **inside brackets too**.
   *
   * Brackets suspend the off-side rule, which is what lets an argument list be laid out however
   * reads best. A block opened inside such a list is the one place that is wrong: the body's margin
   * is the only thing saying where the block ends, so the newline, indent and dedent have to be
   * emitted after all — and without them a `match` written as an argument was refused with
   * *newline expected*, pointing at its first arm.
   *
   * **The rule is the same shape as `isLineContinuationToken` and answers the opposite question.**
   * That one says a newline here is not a newline; this says a newline here is one after all. Read
   * together they are one statement about where a line ends, rather than two lists to remember.
   *
   * **Both tokens are here because one of them would be a rule nobody could state.** A `match` opens
   * its arms and an arrow opens a closure's body or an arm's, and a language admitting the first as
   * an argument and refusing the second asks a reader to remember which block forms may be written
   * where. What is worth saying instead is that a block opens wherever it is written:
   *
   * {{{
   * print(n match                    xs.each((x) ->
   *     0 -> "none"                      val doubled = x * 2
   *     1 -> "one"                       print(doubled))
   *     else "many")
   * }}}
   *
   * **Neither can finish an expression**, which is the safety condition — the same one the leading-dot
   * rule turns on from the other side. A token that could end one would make the *next* line's margin
   * significant in an argument list, which is exactly what the bracket rule exists to prevent.
   *
   * `then`, `else` and `do` are deliberately absent. Each opens a block too, so the rule would admit
   * them — but an `if` written across lines as an argument puts its `else` back at the outer margin,
   * which is a dedent the enclosing bracket has to swallow rather than one the block owns, and that
   * is a different mechanism from this one rather than more of it. A branch as an argument still has
   * its one-line form, which is what an argument wants anyway.
   */
  override protected def isBlockTrigger(tok: Token): Boolean = tok match
    case Keyword("match") | Keyword("->") => true
    case _                                => false

  /** The reserved words that are values, and so *can* end an expression.
   *
   * Everything else in `reserved` introduces something, so a line ending in one has not finished an
   * expression and there is nothing under it for a chain to continue.
   */
  private val valueWords = Set("self", "true", "false", "null")

  /** Whether the token before a newline could have been the end of an expression.
   *
   * A delimiter is admitted rather than enumerated: `)`, `]` and `}` all end one, and the operators
   * that do not are already handled at the *other* end by `isLineContinuationToken`, which suppresses
   * the newline before this is ever consulted.
   */
  private def canEndExpression(tok: Token): Boolean = tok match
    case null           => false
    case Keyword(chars) => !reserved(chars) || valueWords(chars)
    case _              => true

  /** Materializes the token stream with each token's source position, so the parser can
   * memoize over a fixed `List` rather than over the stateful scanner,
   * yet still report where a parse error occurred.
   *
   * The third element is the offset **just past** the token, which is what lets a diagnostic
   * underline it rather than point at its first character. It is `rest`'s own offset: a scanner is
   * built from the character reader positioned immediately after the previous token and reports
   * that reader's offset as its own, so asking the next scanner where it starts is asking where
   * this token stopped. Nothing else in the lexer knows a token's width — an integer literal's
   * `chars` is its value re-spelled rather than what was written, and a string's is what it
   * denotes — so this is the only honest source of it.
   *
   * The two tokens the scanner *synthesizes* at end of input, a closing newline and a dedent, are
   * built over the reader in front of them and so report an end at or before their own start. They
   * occupy no characters, which is what a caller clamping the end to the start gets right anyway.
   */
  /** The documentation comments the last scan passed over, by the offset each one starts at.
   *
   * A comment is trivia and stays trivia: none of these reaches the token stream, no grammar rule
   * can see one, and nothing in the analyzer reads this. It is here for the things that want a
   * declaration's prose rather than its meaning — a generator, an editor's hover text — and
   * `DocComments.attach` is what turns it into an answer about declarations.
   *
   * **Keyed by offset because the lexer may report one comment twice**, which
   * `IndentationLexical.comment` documents: deciding whether a line continues the one above runs
   * the line-prefix skip over the next line before that line is scanned for real. A map makes the
   * second report a no-op; a buffer would have made it a duplicate paragraph in somebody's
   * generated documentation.
   */
  private val docs = scala.collection.mutable.LinkedHashMap.empty[Int, (Int, String)]

  /** Only the documentation form, `/** … */`, and never the empty block comment `/**/`.
   *
   * The delimiter is what tells a comment written for a reader of the *documentation* from one
   * written for a reader of the *code*, and it is the whole of the distinction — an ordinary block
   * comment and every `//` line stay invisible, which is what lets an implementation note sit above
   * a declaration without being published.
   *
   * `/**/` opens with those three characters and is an empty comment rather than a doc comment,
   * which is worth excluding here rather than discovering as an empty paragraph later.
   */
  override protected def comment(from: Reader[Char], to: Reader[Char]): Unit = {
    val start = from.offset
    val end   = to.offset
    val text  = from.source.subSequence(start, end).toString

    if text.startsWith("/**") && text != "/**/" then docs(start) = (end, text)
  }

  /** The doc comments of the last `scanPositioned`, in source order. */
  def docComments: List[(Int, Int, String)] =
    docs.toList.sortBy(_._1).map((start, rest) => (start, rest._1, rest._2))

  def scanPositioned(s: String): List[(Token, Position, Int)] = {
    val buf = ListBuffer.empty[(Token, Position, Int)]

    // A lexer instance is reused across scans — `reader` builds one per parse — so the previous
    // scan's comments have to go, or a second file inherits the first file's prose.
    docs.clear()

    var t = read(new CharSequenceReader(s))

    while (!t.atEnd) {
      // `first` and `pos` are read before `rest`, which is what advances the lexer's own state.
      val token = t.first
      val start = t.pos
      val next  = t.rest

      buf += ((token, start, next.offset))
      t = next
    }

    buf.toList
  }

  override def token: Parser[Token] =
    interpString | cString | identifier | number | label | character | string | quotedIdent |
      (elem(EofCh) ^^^ EOF) | delim | failure(
        "illegal character",
      )

  private def isDigit(c: Char): Boolean    = c >= '0' && c <= '9'
  private def isHexDigit(c: Char): Boolean = isDigit(c) || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')
  private def isBinDigit(c: Char): Boolean = c == '0' || c == '1'
  private def isOctDigit(c: Char): Boolean = c >= '0' && c <= '7'

  /** What may begin a name: `_`, or any character Unicode calls a letter.
   *
   * **`café`, `año`, `μ` and `名前` are names, and that is a readability decision rather than an
   * internationalisation one.** A language whose identifiers are ASCII asks everybody who does not
   * think in English to transliterate their own vocabulary, and the words that suffer most are the
   * domain ones — the very names a reader needs to recognise. Go, Java, Scala and C# all took this
   * road; the ASCII rule here was never argued for, it was what `>= 'a' && <= 'z'` happened to say.
   *
   * **One predicate rather than a list of ranges**, which is what makes it a rule rather than a
   * table somebody extends every time a script is asked for. `Character.isLetter` is Unicode's own
   * answer and it moves with the JDK's Unicode version rather than with this file.
   *
   * The ASCII test is first because it is what almost every character is, and `isLetter` is a table
   * lookup; the two agree on `a`–`z` and `A`–`Z`, so the fast path changes no answer.
   *
   * **It is BMP-only, and that is a real edge rather than an oversight.** A `Char` is one UTF-16
   * unit, so a letter above U+FFFF arrives as a surrogate pair and `isLetter` answers false for
   * either half — the character is refused as illegal. Every living script's letters are inside the
   * BMP, CJK Unified Ideographs included; what is outside is historic scripts and the CJK extension
   * planes. Scala's own lexer draws the line in the same place and for the same reason.
   */
  private def isIdentStart(c: Char): Boolean =
    c == '_' || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || Character.isLetter(c)

  /** What may continue one: anything that may begin it, plus a digit.
   *
   * `Character.isDigit` rather than the ASCII test, so that a name written in a script with its own
   * digits can use them — `caf٣` is a name where `٣` alone is not, since a digit may not *begin*
   * one. The number **literal** grammar is deliberately unmoved: `isDigit` above is ASCII and stays
   * ASCII, because a literal is a value the machine has to read and `٣` is not a spelling of three
   * that any of this compiler's arithmetic knows.
   */
  private def isIdentPart(c: Char): Boolean = isIdentStart(c) || isDigit(c) || Character.isDigit(c)

  private def takeWhile(in: Reader[Char], pred: Char => Boolean): (String, Reader[Char]) = {
    val buf = new StringBuilder
    var r   = in

    while (!r.atEnd && pred(r.first)) {
      buf += r.first
      r = r.rest
    }

    (buf.toString, r)
  }

  private lazy val identifier: Parser[Token] = Parser { in =>
    if (in.atEnd || !isIdentStart(in.first)) Failure("not an identifier", in)
    else {
      val (name, rest) = takeWhile(in, isIdentPart)

      Success(processIdent(name), rest)
    }
  }

  /** Numeric literals — decimal, `0x` / `0b` / `0o`, `_` separators, and a canonical
   * primitive type suffix.
   *
   * The scan is deliberately greedy and validated afterwards rather than encoded as a
   * grammar: `42abc` must be one bad literal, not `42` followed by an identifier, and
   * that is only knowable once the trailing word has been consumed.
   */
  private lazy val number: Parser[Token] = Parser { in =>
    if (in.atEnd || !isDigit(in.first)) Failure("not a number", in)
    else {
      val prefixed = in.first == '0' && !in.rest.atEnd && "xbo".contains(in.rest.first)

      val (radix, digitPred, afterPrefix) =
        if (prefixed)
          in.rest.first match {
            case 'x' => (16, isHexDigit, in.rest.rest)
            case 'b' => (2, isBinDigit, in.rest.rest)
            case _   => (8, isOctDigit, in.rest.rest)
          }
        else (10, isDigit, in)

      val (mantissa, afterMantissa) = takeWhile(afterPrefix, c => digitPred(c) || c == '_')

      var isFloat = false
      var text    = ""
      var rest    = afterMantissa

      if (radix == 10 && !rest.atEnd && rest.first == '.' && !rest.rest.atEnd && isDigit(rest.rest.first)) {
        val (fraction, afterFraction) = takeWhile(rest.rest, c => isDigit(c) || c == '_')

        text = "." + fraction
        isFloat = true
        rest = afterFraction
      }

      if (radix == 10 && !rest.atEnd && (rest.first == 'e' || rest.first == 'E')) {
        val afterE           = rest.rest
        val signed           = !afterE.atEnd && (afterE.first == '+' || afterE.first == '-')
        val afterSign        = if (signed) afterE.rest else afterE
        val exponentFollows  = !afterSign.atEnd && isDigit(afterSign.first)

        if (exponentFollows) {
          val (exponent, afterExponent) = takeWhile(afterSign, c => isDigit(c) || c == '_')

          text += "e" + (if (signed) afterE.first.toString else "") + exponent
          isFloat = true
          rest = afterExponent
        }
      }

      val (suffix, afterSuffix) = takeWhile(rest, isIdentPart)

      def bad(msg: String) = Success(errorToken(msg), afterSuffix)

      separators(mantissa, allowLeading = prefixed) match {
        case Left(msg)     => bad(msg)
        case Right(digits) =>
          val floatSuffix = suffix.startsWith("f")

          if (suffix.nonEmpty && !validSuffix(suffix)) bad(s"invalid literal suffix '$suffix'")
          else if (isFloat && suffix.nonEmpty && !floatSuffix)
            bad(s"floating-point literal cannot have the integer suffix '$suffix'")
          else if (floatSuffix && radix != 10) bad("floating-point suffix on a non-decimal literal")
          else {
            val suffixOpt = if (suffix.isEmpty) None else Some(suffix)

            val fractionRun =
              text.stripPrefix(".").takeWhile(_ != 'e')
            val exponentRun =
              if (text.contains('e')) text.substring(text.indexOf('e') + 1).dropWhile(c => c == '+' || c == '-')
              else ""

            def badRun(run: String): Option[String] =
              if (run.isEmpty) None
              else
                separators(run, allowLeading = true) match {
                  case Left(msg) => Some(msg)
                  case Right(_)  => None
                }

            badRun(fractionRun).orElse(badRun(exponentRun)) match {
              case Some(msg) => bad(msg)
              case None =>
                if (isFloat || floatSuffix)
                  Success(FloatLit(digits + text.replace("_", ""), suffixOpt), afterSuffix)
                else
                  Success(IntLit(BigInt(digits, radix), suffixOpt), afterSuffix)
            }
          }
      }
    }
  }

  /** Validates the `_` placement of one digit run and returns it with the separators
   * removed. A separator sits *between* digits, so it may neither trail the run nor
   * double up; it may lead only when a base prefix precedes it.
   */
  private def separators(run: String, allowLeading: Boolean): Either[String, String] =
    if (run.isEmpty) Left("missing digits")
    else if (run.startsWith("_") && !allowLeading) Left("digit separator may not lead the digits")
    else if (run.endsWith("_")) Left("digit separator may not trail the digits")
    else if (run.contains("__")) Left("only a single digit separator may appear between digits")
    else {
      val digits = run.replace("_", "")

      if (digits.isEmpty) Left("missing digits") else Right(digits)
    }

  /** A suffix names a canonical primitive: the systematic `iN` / `uN` / `fN` forms plus
   * the two pointer-width types. The friendly aliases (`int`, `byte`, `real`, …) are not
   * suffixes — they reach a literal through a type context or a cast.
   */
  private def validSuffix(s: String): Boolean =
    s == "usize" || s == "isize" ||
      (s.length > 1 && "iuf".contains(s.head) && s(1) != '0' && s.tail.forall(isDigit))

  /** A loop label `'name`: an apostrophe, an identifier, and — crucially — no closing apostrophe,
   * which is what separates it from the character literal `'n'`. When a closing quote does follow,
   * this yields so the `character` parser reads the literal instead.
   */
  private lazy val label: Parser[Token] = Parser { in =>
    if (in.atEnd || in.first != '\'' || in.rest.atEnd || !isIdentStart(in.rest.first)) Failure("not a label", in)
    else {
      val (name, after) = takeWhile(in.rest, isIdentPart)

      if (!after.atEnd && after.first == '\'') Failure("not a label", in)
      else Success(Label(name), after)
    }
  }

  /** A backtick-quoted identifier: everything up to the closing backtick, taken literally.
   *
   * There are no escapes inside, so a name can never carry a backtick — which is what keeps the
   * scan a single `takeWhile` and the form unambiguous to a reader. A newline ends the search
   * rather than the name, so an unclosed quote is reported at the line it opened on instead of
   * swallowing the rest of the file.
   *
   * **A `.` is refused**, and that is a restriction on the name rather than on the lexer's ability
   * to read it: a qualified name is carried through the compiler as a dotted string, so a dot
   * inside a segment would be indistinguishable from the separator between two.
   */
  private lazy val quotedIdent: Parser[Token] = Parser { in =>
    if (in.atEnd || in.first != '`') Failure("not a quoted identifier", in)
    else {
      val (name, rest) = takeWhile(in.rest, c => c != '`' && c != '\n')

      if (rest.atEnd || rest.first != '`') Success(errorToken("unterminated quoted identifier"), rest)
      else if (name.isEmpty) Success(errorToken("a quoted identifier needs a name between the backticks"), rest.rest)
      else if (name.contains('.'))
        Success(errorToken("a quoted identifier may not contain '.', which separates the parts of a " +
          "qualified name"), rest.rest)
      else Success(QuotedIdent(name), rest.rest)
    }
  }

  private lazy val character: Parser[Token] = Parser { in =>
    if (in.atEnd || in.first != '\'') Failure("not a character literal", in)
    else {
      val body = in.rest

      if (body.atEnd || body.first == '\n') Success(errorToken("unterminated character literal"), body)
      else if (body.first == '\'') Success(errorToken("empty character literal"), body.rest)
      else
        scanChar(body) match {
          case Left((msg, rest)) => Success(errorToken(msg), rest)
          case Right((cp, rest)) =>
            if (!rest.atEnd && rest.first == '\'') Success(CharLit(cp), rest.rest)
            else if (rest.atEnd || rest.first == '\n')
              Success(errorToken("unterminated character literal"), rest)
            else Success(errorToken("character literal must hold exactly one character"), rest)
        }
    }
  }

  private lazy val string: Parser[Token] = stringBody(StrLit.apply)

  /** `c"…"` — a **C string**: the same literal, laid down NUL-terminated and read as a `*u8`, which
   * is what a C interface expects and what a sysl `string` (a length, no terminator — `04`) is not.
   * The prefix has to be the whole identifier, as an interpolation prefix does, so a name beginning
   * with `c` beside a string stays two tokens.
   */
  private lazy val cString: Parser[Token] = Parser { in =>
    if (in.atEnd || in.first != 'c') Failure("not a C string literal", in)
    else {
      val (name, after) = takeWhile(in, isIdentPart)

      if (name == "c" && !after.atEnd && after.first == '"') stringBody(CStrLit.apply)(after)
      else Failure("not a C string literal", in)
    }
  }

  /** The shared body of both quote forms: scan to the closing `"`, decoding escapes. `token` is what
   * to build from the decoded value, which is the only thing the two forms differ by.
   */
  private def stringBody(token: String => Token): Parser[Token] = Parser { in =>
    if (in.atEnd || in.first != '"') Failure("not a string literal", in)
    else if (opensBlock(in)) scanBlock(in.rest.rest.rest, escapes = true, token)
    else {
      val buf                                = new StringBuilder
      var rest                               = in.rest
      var result: Option[ParseResult[Token]] = None

      while (result.isEmpty)
        if (rest.atEnd || rest.first == '\n') result = Some(Success(errorToken("unterminated string literal"), rest))
        else if (rest.first == '"') {
          result = Some(Success(token(buf.toString), rest.rest))
        } else
          scanChar(rest) match {
            case Left((msg, next)) => result = Some(Success(errorToken(msg), next))
            case Right((cp, next)) =>
              buf ++= codepointToString(cp)
              rest = next
          }

      result.get
    }
  }

  /** The body of a text block, `"""` … `"""`, which is the one-quote scan with a line discipline
   * over it (`reference/lexical.md § Strings`).
   *
   * Three things happen to a line that do not happen inside `"…"`. Its incidental indentation is
   * dropped, so the block may be indented with the code around it. Its trailing blanks are dropped,
   * because whitespace at a line's end is invisible and would otherwise enter the value unseen —
   * `\u{20}` writes one that is meant, since escapes are read after the trimming and so survive it.
   * And a `\` at the end of a line joins it to the next, which is how data written a line at a time
   * becomes one string with no breaks in it.
   *
   * The trailing newline needs no rule of its own: a closing delimiter on a line of its own is
   * reached *after* the last content line's break has been taken, and one that follows content is
   * reached before any break at all.
   */
  private def scanBlock(start: Reader[Char], escapes: Boolean, token: String => Token): ParseResult[Token] =
    blockIndent(start, escapes) match {
      case Left((msg, at)) => Success(errorToken(msg), at)
      case Right((strip, first)) =>
        val buf     = new StringBuilder
        val pending = new StringBuilder
        var rest    = afterIndent(first, strip)
        var result: Option[ParseResult[Token]] = None

        while (result.isEmpty)
          if (rest.atEnd) result = Some(Success(errorToken("unterminated text block"), rest))
          else if (rest.first == '"' && opensBlock(rest))
            result = Some(Success(token(buf.toString), rest.rest.rest.rest))
          else if (rest.first == '\n') {
            pending.clear(); buf += '\n'; rest = afterIndent(rest.rest, strip)
          } else if (isBlank(rest.first)) { pending += rest.first; rest = rest.rest }
          else if (escapes && rest.first == '\\' && joinsLine(rest.rest).isDefined) {
            pending.clear(); rest = afterIndent(joinsLine(rest.rest).get, strip)
          } else if (!escapes) {
            buf ++= pending; pending.clear()
            buf += rest.first; rest = rest.rest
          } else
            scanChar(rest) match {
              case Left((msg, next)) => result = Some(Success(errorToken(msg), next))
              case Right((cp, next)) =>
                buf ++= pending; pending.clear()
                buf ++= codepointToString(cp)
                rest = next
            }

        result.get
    }

  /** An interpolated string is an identifier `s`, `raw`, or `f` written directly against a `"`. The
   * prefix has to be the whole identifier — `sfoo"…"` is an ordinary name beside a string, not an
   * interpolation — so a mismatch falls through to `identifier`, which keeps those names usable.
   * `raw` alone keeps backslashes literal; `f` alone allows a printf specifier after a hole.
   */
  private lazy val interpString: Parser[Token] = Parser { in =>
    if (in.atEnd || !isIdentStart(in.first)) Failure("not an interpolated string", in)
    else {
      val (name, afterName) = takeWhile(in, isIdentPart)

      if ((name == "s" || name == "raw" || name == "f") && !afterName.atEnd && afterName.first == '"')
        if (opensBlock(afterName))
          // A block's shape is settled before its holes are, since the strip has to be known at the
          // first line and only the last line can lower it.
          blockIndent(afterName.rest.rest.rest, escapes = name != "raw") match {
            case Left((msg, at)) => Success(errorToken(msg), at)
            case Right((strip, first)) =>
              scanInterp(afterIndent(first, strip), escapes = name != "raw",
                         allowSpec = name == "f", block = true, strip = strip)
          }
        else scanInterp(afterName.rest, escapes = name != "raw", allowSpec = name == "f")
      else Failure("not an interpolated string", in)
    }
  }

  /** Scans the body of an interpolated string into its literal segments, embedded expressions, and
   * per-hole specifiers. A `$` begins an interpolation — `$name` or `${ … }` — and `$$` is a
   * literal dollar; every other character joins the current segment, decoded through the escape
   * table unless escapes are off. In an `f"…"` string a `%` immediately after a hole is scanned as
   * that hole's specifier; a `%` that does not form a valid specifier stays ordinary text.
   */
  private def scanInterp(start: Reader[Char], escapes: Boolean, allowSpec: Boolean,
                         block: Boolean = false, strip: Int = 0): ParseResult[Token] = {
    val parts   = ListBuffer.empty[String]
    val exprs   = ListBuffer.empty[String]
    val specs   = ListBuffer.empty[Option[String]]
    val part    = new StringBuilder
    val pending = new StringBuilder
    var rest    = start

    var result: Option[ParseResult[Token]] = None

    /** After a hole is recorded, take an optional specifier and advance past it. */
    def takeSpec(): Unit =
      if (allowSpec && !rest.atEnd && rest.first == '%')
        scanSpec(rest) match
          case Some((spec, next)) => specs += Some(spec); rest = next
          case None               => specs += None
      else specs += None

    while (result.isEmpty)
      if (rest.atEnd || (!block && rest.first == '\n'))
        result = Some(Success(errorToken(if (block) "unterminated text block" else "unterminated string literal"), rest))
      // A block ends at `"""`, and a lone `"` inside one is ordinary text; a one-line literal ends
      // at the first quote it meets.
      else if (rest.first == '"' && (if (block) opensBlock(rest) else true)) {
        parts += part.toString
        result = Some(Success(StrInterp(parts.toList, exprs.toList, specs.toList),
                              if (block) rest.rest.rest.rest else rest.rest))
      } else if (block && rest.first == '\n') {
        pending.clear(); part += '\n'; rest = afterIndent(rest.rest, strip)
      } else if (block && isBlank(rest.first)) { pending += rest.first; rest = rest.rest }
      else if (block && escapes && rest.first == '\\' && joinsLine(rest.rest).isDefined) {
        pending.clear(); rest = afterIndent(joinsLine(rest.rest).get, strip)
      } else if (rest.first == '$') {
        part ++= pending; pending.clear()
        val after = rest.rest

        if (after.atEnd) result = Some(Success(errorToken("expected a name or '{' after '$'"), after))
        else if (after.first == '$') { part += '$'; rest = after.rest }
        else if (after.first == '{')
          scanBraced(after.rest) match {
            case Left((msg, next)) => result = Some(Success(errorToken(msg), next))
            case Right((expr, next)) =>
              parts += part.toString; part.clear()
              exprs += expr
              rest = next
              takeSpec()
          }
        else if (isIdentStart(after.first)) {
          val (name, next) = takeWhile(after, isIdentPart)

          parts += part.toString; part.clear()
          exprs += name
          rest = next
          takeSpec()
        } else result = Some(Success(errorToken("expected a name or '{' after '$'"), after))
      } else if (!escapes) {
        part ++= pending; pending.clear()
        part += rest.first; rest = rest.rest
      } else
        scanChar(rest) match {
          case Left((msg, next)) => result = Some(Success(errorToken(msg), next))
          case Right((cp, next)) =>
            part ++= pending; pending.clear()
            part ++= codepointToString(cp)
            rest = next
        }

    result.get
  }

  /** Tries to read a printf specifier `%[-+ 0#]*[0-9]*(.[0-9]+)?[conv]` starting at a `%`. It
   * commits only on a complete specifier ending in a conversion letter; anything else leaves the
   * `%` as ordinary text, so a bare percent in an `f"…"` string prints literally.
   */
  private def scanSpec(in: Reader[Char]): Option[(String, Reader[Char])] = {
    val sb   = new StringBuilder("%")
    var rest = in.rest

    val (flags, afterFlags) = takeWhile(rest, c => "-+ 0#".contains(c))
    sb ++= flags; rest = afterFlags

    val (width, afterWidth) = takeWhile(rest, isDigit)
    sb ++= width; rest = afterWidth

    if (!rest.atEnd && rest.first == '.') {
      val (prec, afterPrec) = takeWhile(rest.rest, isDigit)
      sb += '.'; sb ++= prec; rest = afterPrec
    }

    if (!rest.atEnd && "diouxXeEfgGs".contains(rest.first)) {
      sb += rest.first
      Some((sb.toString, rest.rest))
    } else None
  }

  /** Captures the source of a `${ … }` expression by matching its braces, skipping over nested
   * braces and over any string or character literal inside so a `}` within one does not close the
   * interpolation early. The captured text is re-lexed by the parser, which is where its own
   * well-formedness is judged.
   */
  private def scanBraced(start: Reader[Char]): Either[(String, Reader[Char]), (String, Reader[Char])] = {
    val buf   = new StringBuilder
    var rest  = start
    var depth = 1

    var result: Option[Either[(String, Reader[Char]), (String, Reader[Char])]] = None

    def copyQuoted(quote: Char): Unit = {
      buf += quote; rest = rest.rest
      var closed = false
      while (!closed)
        if (rest.atEnd || rest.first == '\n') closed = true
        else if (rest.first == '\\' && !rest.rest.atEnd) { buf += '\\'; buf += rest.rest.first; rest = rest.rest.rest }
        else if (rest.first == quote) { buf += quote; rest = rest.rest; closed = true }
        else { buf += rest.first; rest = rest.rest }
    }

    while (result.isEmpty)
      if (rest.atEnd || rest.first == '\n') result = Some(Left(("unterminated interpolation", rest)))
      else
        rest.first match {
          case '{'          => depth += 1; buf += '{'; rest = rest.rest
          case '}'          =>
            depth -= 1
            if (depth == 0) result = Some(Right((buf.toString, rest.rest)))
            else { buf += '}'; rest = rest.rest }
          case '"'          => copyQuoted('"')
          case '\''         => copyQuoted('\'')
          case c            => buf += c; rest = rest.rest
        }

    result.get
  }

  /** Whether a `"` begins a **text block**, `"""` — the multi-line literal form. Asked at the
   * opening quote of every literal form, so the prefixes compose: `c"""`, `s"""`, `raw"""`, `f"""`.
   */
  private def opensBlock(in: Reader[Char]): Boolean =
    !in.rest.atEnd && in.rest.first == '"' && !in.rest.rest.atEnd && in.rest.rest.first == '"'

  /** What a text block treats as blank. A carriage return is in the set so that a block means the
   * same thing in a file with either line ending: it is dropped with the rest of a line's trailing
   * whitespace rather than reaching the value, so a checkout's line endings cannot change what a
   * program says.
   */
  private def isBlank(c: Char): Boolean = c == ' ' || c == '\t' || c == '\r'

  /** How much leading whitespace every line of a text block gives up.
   *
   * This is the block's **incidental** indentation: the least indented of the lines that carry
   * content, together with the line the closing delimiter sits on when it sits on one alone. The
   * closing delimiter counting is what puts the programmer in control — moving it left widens what
   * the value keeps, moving it right narrows it — without a margin character to remember, and it is
   * why the block can be indented with the code around it and still say what it means. Lines that
   * are entirely blank say nothing about the block's shape and are left out of the reckoning.
   *
   * A pre-pass rather than a running minimum, because the strip has to be known at the first line
   * and the last line may lower it.
   */
  private def blockIndent(start: Reader[Char],
                          escapes: Boolean): Either[(String, Reader[Char]), (Int, Reader[Char])] =
    joinsLine(start) match {
      case None =>
        Left(("a text block's content begins on the line after its opening \"\"\"", start))
      case Some(first) =>
        var rest    = first
        var least   = Int.MaxValue
        var indent  = 0
        var blank   = true
        var atStart = true
        var result: Option[Either[(String, Reader[Char]), (Int, Reader[Char])]] = None

        while (result.isEmpty)
          if (rest.atEnd) result = Some(Left(("unterminated text block", rest)))
          // The line the delimiter sits on counts whether or not it carries content: alone, its
          // own column is the offer; after content, it is an ordinary content line.
          else if (rest.first == '"' && opensBlock(rest)) {
            val strip = least min indent

            result = Some(Right((if (strip == Int.MaxValue) 0 else strip, first)))
          }
          else if (rest.first == '\n') {
            if (!blank) least = least min indent
            indent = 0; blank = true; atStart = true; rest = rest.rest
          } else if (atStart && isBlank(rest.first)) { indent += 1; rest = rest.rest }
          // Stepped over whole, so a `\"""` inside the block does not read as the terminator.
          else if (escapes && rest.first == '\\' && !rest.rest.atEnd) {
            atStart = false; blank = false; rest = rest.rest.rest
          } else { atStart = false; blank = false; rest = rest.rest }

        result.get
    }

  /** Steps over the incidental whitespace at the head of a text block's line. A line with less
   * whitespace than the strip — a blank one, in practice — simply gives up what it has.
   */
  private def afterIndent(in: Reader[Char], strip: Int): Reader[Char] = {
    var rest = in
    var n    = 0

    while (n < strip && !rest.atEnd && isBlank(rest.first)) { n += 1; rest = rest.rest }
    rest
  }

  /** Whether a backslash joins its line to the next — a `\` with nothing but blanks between it and
   * the line break. This is what lets embedded data be written a line at a time and still be one
   * string with no breaks in it, which is the case a text block otherwise cannot serve.
   */
  private def joinsLine(in: Reader[Char]): Option[Reader[Char]] = {
    var rest = in

    while (!rest.atEnd && isBlank(rest.first)) rest = rest.rest
    Option.when(!rest.atEnd && rest.first == '\n')(rest.rest)
  }

  /** Reads one character of a character or string literal — an escape sequence, a plain
   * character, or a surrogate pair, which is one scalar value however the host platform
   * stores it.
   */
  private def scanChar(in: Reader[Char]): Either[(String, Reader[Char]), (Int, Reader[Char])] =
    if (in.first == '\\') scanEscape(in.rest)
    else {
      val c = in.first

      if (isHighSurrogate(c) && !in.rest.atEnd && isLowSurrogate(in.rest.first))
        Right((0x10000 + ((c - 0xd800) << 10) + (in.rest.first - 0xdc00), in.rest.rest))
      else if (isHighSurrogate(c) || isLowSurrogate(c)) Left(("unpaired surrogate", in.rest))
      else Right((c.toInt, in.rest))
    }

  /** The escapes a character or string literal accepts: `\n` `\t` `\r` `\b` `\f` `\0` `\\` `\'`
   * `\"`, and `\u{...}` for anything else. The named ones are the set C fixed and every language
   * since has carried, so a programmer arriving from one of them finds what they reach for.
   *
   * `\e` for the escape character is deliberately absent, though ANSI terminal code wants it more
   * than it wants any of the above: it is a GNU extension rather than standard C, and a program
   * that needs it writes `'\u{1b}'` once and gives it a name.
   */
  private def scanEscape(in: Reader[Char]): Either[(String, Reader[Char]), (Int, Reader[Char])] =
    if (in.atEnd) Left(("incomplete escape sequence", in))
    else
      in.first match {
        case 'n'                  => Right(('\n'.toInt, in.rest))
        case 't'                  => Right(('\t'.toInt, in.rest))
        case 'r'                  => Right(('\r'.toInt, in.rest))
        case 'b'                  => Right((0x08, in.rest))
        case 'f'                  => Right((0x0c, in.rest))
        case '0'                  => Right((0, in.rest))
        case '\\' | '\'' | '"'    => Right((in.first.toInt, in.rest))
        case 'u'                  => scanBracedCodepoint(in.rest)
        case c                    => Left((s"unknown escape sequence '\\$c'", in.rest))
      }

  /** `\u{...}` is braced rather than a fixed four hex digits because a Unicode scalar
   * value needs up to six.
   */
  private def scanBracedCodepoint(in: Reader[Char]): Either[(String, Reader[Char]), (Int, Reader[Char])] =
    if (in.atEnd || in.first != '{') Left(("expected '{' after \\u", in))
    else {
      val (hex, rest) = takeWhile(in.rest, isHexDigit)

      if (hex.isEmpty) Left(("expected hex digits in \\u{...}", rest))
      else if (rest.atEnd || rest.first != '}') Left(("unterminated \\u{...} escape", rest))
      else if (hex.length > 6) Left((s"'$hex' is not a Unicode scalar value", rest.rest))
      else {
        val cp = Integer.parseInt(hex, 16)

        if (isScalarValue(cp)) Right((cp, rest.rest))
        else Left((f"U+$cp%04X is not a Unicode scalar value", rest.rest))
      }
    }

  private def isHighSurrogate(c: Char): Boolean = c >= 0xd800 && c <= 0xdbff
  private def isLowSurrogate(c: Char): Boolean  = c >= 0xdc00 && c <= 0xdfff

  /** The set `char` is defined over: every codepoint up to `0x10FFFF` except the
   * surrogates, which is exactly what UTF-8 and UTF-16 encode.
   */
  private def isScalarValue(cp: Int): Boolean = cp <= 0x10ffff && !(cp >= 0xd800 && cp <= 0xdfff)

  private def codepointToString(cp: Int): String =
    if (cp > 0xffff) {
      val v = cp - 0x10000

      new String(Array(((v >> 10) + 0xd800).toChar, ((v & 0x3ff) + 0xdc00).toChar))
    } else cp.toChar.toString
}
