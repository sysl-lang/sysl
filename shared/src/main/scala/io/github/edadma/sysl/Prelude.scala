package io.github.edadma.sysl

/** Declarations every program starts with.
 *
 * These are ordinary sysl source, parsed and hoisted ahead of the user's own declarations —
 * they use nothing the language does not already offer. `Option` and `Result` are here rather
 * than built into the analyzer because they *are* just generic enums; only the `?` operator
 * knows their names.
 */
object Prelude {

  val source: String =
    """enum Option[T]
      |    Some(value: T)
      |    None
      |
      |    is_some(self) -> bool = match self
      |        Some(_) -> true
      |        None -> false
      |
      |    is_none(self) -> bool = !self.is_some()
      |
      |    unwrap_or(self, default: T) -> T = match self
      |        Some(v) -> v
      |        None -> default
      |end Option
      |
      |enum Result[T, E]
      |    Ok(value: T)
      |    Err(error: E)
      |
      |    is_ok(self) -> bool = match self
      |        Ok(_) -> true
      |        Err(_) -> false
      |
      |    is_err(self) -> bool = !self.is_ok()
      |
      |    unwrap_or(self, default: T) -> T = match self
      |        Ok(v) -> v
      |        Err(_) -> default
      |end Result
      |""".stripMargin

  /** The parsed prelude declarations, parsed once. They carry positions into a source of their
   * own, so a diagnostic against a prelude declaration quotes the prelude rather than the user's
   * file at some unrelated line.
   */
  lazy val decls: List[Stmt] =
    SyslParser.parse(Source("<prelude>", source)) match
      case Right(p) => p.body
      case Left(e)  => sys.error(s"the prelude does not parse: $e")

  /** The enum `?` unwraps, paired with its success and failure variant names. */
  def tryVariants(base: String): Option[(String, String)] = base match
    case "Result" => Some(("Ok", "Err"))
    case "Option" => Some(("Some", "None"))
    case _        => None
}
