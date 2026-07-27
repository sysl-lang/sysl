package io.github.edadma.sysl

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
    "const",
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
    "match",
    "struct",
    "enum",
    "trait",
    "impl",
    "extern",
    "module",
    "import",
    "as",
    "private",
    "return",
    "self",
    "weak",
    "no",
    "alloc",
    "requires",
    "sizeof",
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
    ",", "::", ":", "->",
    // Only ever a separator inside a three-clause `for` header (`00` §10). It is deliberately not a
    // statement terminator: a line ends a statement, and a token that could also end one would give
    // the language two answers to the same question.
    ";",
  )

  /** Materializes the token stream with each token's source position, so the parser can
   * memoize over a fixed `List` (not the stateful scanner — see docs/design/front-end.md)
   * yet still report where a parse error occurred.
   */
  def scanPositioned(s: String): List[(Token, Position)] = {
    val buf = ListBuffer.empty[(Token, Position)]
    var t   = read(new CharSequenceReader(s))

    while (!t.atEnd) {
      buf += ((t.first, t.pos))
      t = t.rest
    }

    buf.toList
  }

  override def token: Parser[Token] =
    interpString | cString | identifier | number | label | character | string | (elem(EofCh) ^^^ EOF) | delim | failure(
      "illegal character",
    )

  private def isDigit(c: Char): Boolean    = c >= '0' && c <= '9'
  private def isHexDigit(c: Char): Boolean = isDigit(c) || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')
  private def isBinDigit(c: Char): Boolean = c == '0' || c == '1'
  private def isOctDigit(c: Char): Boolean = c >= '0' && c <= '7'

  private def isIdentStart(c: Char): Boolean = c == '_' || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
  private def isIdentPart(c: Char): Boolean  = isIdentStart(c) || isDigit(c)

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
        scanInterp(afterName.rest, escapes = name != "raw", allowSpec = name == "f")
      else Failure("not an interpolated string", in)
    }
  }

  /** Scans the body of an interpolated string into its literal segments, embedded expressions, and
   * per-hole specifiers. A `$` begins an interpolation — `$name` or `${ … }` — and `$$` is a
   * literal dollar; every other character joins the current segment, decoded through the escape
   * table unless escapes are off. In an `f"…"` string a `%` immediately after a hole is scanned as
   * that hole's specifier; a `%` that does not form a valid specifier stays ordinary text.
   */
  private def scanInterp(start: Reader[Char], escapes: Boolean, allowSpec: Boolean): ParseResult[Token] = {
    val parts = ListBuffer.empty[String]
    val exprs = ListBuffer.empty[String]
    val specs = ListBuffer.empty[Option[String]]
    val part  = new StringBuilder
    var rest  = start

    var result: Option[ParseResult[Token]] = None

    /** After a hole is recorded, take an optional specifier and advance past it. */
    def takeSpec(): Unit =
      if (allowSpec && !rest.atEnd && rest.first == '%')
        scanSpec(rest) match
          case Some((spec, next)) => specs += Some(spec); rest = next
          case None               => specs += None
      else specs += None

    while (result.isEmpty)
      if (rest.atEnd || rest.first == '\n') result = Some(Success(errorToken("unterminated string literal"), rest))
      else if (rest.first == '"') {
        parts += part.toString
        result = Some(Success(StrInterp(parts.toList, exprs.toList, specs.toList), rest.rest))
      } else if (rest.first == '$') {
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
      } else if (!escapes) { part += rest.first; rest = rest.rest }
      else
        scanChar(rest) match {
          case Left((msg, next)) => result = Some(Success(errorToken(msg), next))
          case Right((cp, next)) =>
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

  private def scanEscape(in: Reader[Char]): Either[(String, Reader[Char]), (Int, Reader[Char])] =
    if (in.atEnd) Left(("incomplete escape sequence", in))
    else
      in.first match {
        case 'n'                  => Right(('\n'.toInt, in.rest))
        case 't'                  => Right(('\t'.toInt, in.rest))
        case 'r'                  => Right(('\r'.toInt, in.rest))
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
