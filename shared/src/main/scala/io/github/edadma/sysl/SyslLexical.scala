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

  /** A string literal, holding its decoded value. */
  case class StrLit(value: String) extends Token {
    def chars: String = value
  }

  /** Reserved words. Type names are deliberately absent: `int`, `usize`, `f32` and the
   * rest are predeclared identifiers resolved by the analyzer, as in Go and Swift, so
   * the open `iN` / `uN` / `fN` families need no lexical support. This set grows as the
   * statement grammar is settled.
   */
  reserved ++= List(
    "true",
    "false",
    "var",
    "if",
    "then",
    "elif",
    "else",
    "while",
    "do",
    "for",
    "in",
    "match",
    "struct",
    "enum",
    "return",
    "weak",
    "no",
    "alloc",
    "requires",
    "sizeof",
  )

  /** The operator set is closed, so operators are a fixed list tokenized by longest
   * match — `delim` orders the alternatives so `..<` wins over `..`.
   */
  delimiters ++= List(
    "=", "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", "<<=", ">>=",
    "||", "&&", "!",
    "==", "!=", "<", ">", "<=", ">=",
    "..", "..<",
    "|", "^", "&", "~",
    "+", "-", "*", "/", "%", "<<", ">>",
    "++", "--",
    "(", ")", "[", "]", "{", "}", ".", "?",
    ",", ":", "->",
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
    identifier | number | character | string | (elem(EofCh) ^^^ EOF) | delim | failure("illegal character")

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
            else Success(errorToken("character literal must hold exactly one character"), rest)
        }
    }
  }

  private lazy val string: Parser[Token] = Parser { in =>
    if (in.atEnd || in.first != '"') Failure("not a string literal", in)
    else {
      val buf                                        = new StringBuilder
      var rest                                       = in.rest
      var result: Option[ParseResult[Token]]         = None

      while (result.isEmpty)
        if (rest.atEnd || rest.first == '\n') result = Some(Success(errorToken("unterminated string literal"), rest))
        else if (rest.first == '"') {
          result = Some(Success(StrLit(buf.toString), rest.rest))
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
