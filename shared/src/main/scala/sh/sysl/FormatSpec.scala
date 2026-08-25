package sh.sysl

/** A printf-style format specifier, as written in an `f"…"` string after a hole (`%08.2f`, `%x`,
 * `%-10s`). The lexer has already checked the shape — a leading `%`, optional flags, width, and
 * precision, then one conversion letter — so the work left here is classifying the conversion and
 * turning the sysl spec into the C one snprintf is handed.
 */
object FormatSpec {

  /** The conversion letter, which the lexer guarantees is the last character of a well-formed
   * spec.
   */
  def conversion(spec: String): Char = spec.last

  def isInt(c: Char): Boolean      = "diouxX".contains(c)
  def isSignedInt(c: Char): Boolean = c == 'd' || c == 'i'
  def isFloat(c: Char): Boolean    = "eEfgG".contains(c)
  def isStr(c: Char): Boolean      = c == 's'

  /** The C format string for the same specifier. An integer conversion is fed a 64-bit value, so
   * its conversion gains the `ll` length modifier; a float (a `double`) and a string (a C pointer)
   * pass through unchanged.
   */
  def cFormat(spec: String): String = {
    val c = conversion(spec)

    if isInt(c) then spec.dropRight(1) + "ll" + c else spec
  }

  /** The three parts of a specifier a `Display` implementation is handed: the minimum field width,
   * the precision, and whether the field is left-justified (`library/core.md § A specifier is the
   * whole value's field`).
   *
   * A width of `0` and a precision of `-1` mean neither was written, which is the reading `%s` and
   * `%.0s` would otherwise be confused by. Only these three cross the boundary: the numeric flags
   * belong to a conversion the library's own renderers apply, and a type rendering itself has no
   * conversion letter to interpret.
   */
  def parts(spec: String): (Int, Int, Boolean) = {
    val flagged        = spec.drop(1).dropRight(1)
    val flags          = flagged.takeWhile("-+0 #".contains(_))
    val (width, after) = flagged.drop(flags.length).span(_.isDigit)
    val precision      = after.drop(1)

    (
      if width.isEmpty then 0 else width.toInt,
      if after.isEmpty then -1 else if precision.isEmpty then 0 else precision.toInt,
      flags.contains('-'),
    )
  }

  /** A human phrase for the kind of value a conversion wants, for a mismatch diagnostic. */
  def expects(c: Char): String =
    if isInt(c) then "an integer"
    else if isFloat(c) then "a float"
    else "a string"
}
