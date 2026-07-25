package io.github.edadma.sysl

/** Declarations every program starts with.
 *
 * These are ordinary sysl source, parsed and hoisted ahead of the user's own declarations —
 * they use nothing the language does not already offer. `Option` and `Result` are here rather
 * than built into the analyzer because they *are* just generic enums; only the `?` operator
 * knows their names.
 *
 * `exit` is the one thing here that is not sysl: an `extern`, resolved by the linker to the
 * hosted C library's. It is what `unwrap` and `expect` stop the program with — a diagnostic
 * printed and a non-zero status, which is what `11-error-handling.md` says a trap does under the
 * `os` capability — and it is the reason those two need no compiler support of their own.
 *
 * None of this costs an unused program anything: the enums' members are generic, so one exists
 * only where a call asks for it, and an `extern` is declared in the output only if something
 * reaches it.
 */
object Prelude {

  val source: String =
    """extern exit(code: int) -> never
      |
      |enum Option[T]
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
      |
      |    unwrap(self) -> T = match self
      |        Some(v) -> v
      |        None ->
      |            print("panic: unwrap of a None value")
      |            exit(1)
      |
      |    expect(self, msg: string) -> T = match self
      |        Some(v) -> v
      |        None ->
      |            print("panic:", msg)
      |            exit(1)
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
      |
      |    unwrap(self) -> T = match self
      |        Ok(v) -> v
      |        Err(_) ->
      |            print("panic: unwrap of an Err value")
      |            exit(1)
      |
      |    expect(self, msg: string) -> T = match self
      |        Ok(v) -> v
      |        Err(_) ->
      |            print("panic:", msg)
      |            exit(1)
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
