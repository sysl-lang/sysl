package io.github.edadma.sysl

/** What is left of the declarations every program used to start with.
 *
 * These are ordinary sysl source, parsed and hoisted ahead of the user's own declarations — they
 * use nothing the language does not already offer. `Option` and `Result` are here rather than built
 * into the analyzer because they *are* just generic enums; only the `?` operator knows their names,
 * and it asks `Library.tryVariants` rather than spelling them.
 *
 * **Everything else has moved into the standard module**, which is real files under `lib/sysl` —
 * the rendering, reading, text, buffer, builder and argument surfaces, the core trait catalog, the
 * printing family, and the C `extern`s beneath it. These two are the last of it, and they are last
 * on purpose: every fallible signature in the library and nearly every test program names them, so
 * they are the move with the widest blast radius and the one worth making when nothing else is in
 * flight.
 *
 * They are reached from the moved half with no import — `from_utf8` answers with a `Result` and
 * `char_from_u32` with an `Option`, both written in `sysl` and resolved here — because a name
 * written in either part of the library is looked for among the library's own first. That both
 * directions work is the whole reason the drain could proceed one surface at a time.
 *
 * When these go, `Library.owns` collapses to asking which module a declaration is in, and what a
 * program starts with is a module rather than a set of declarations threaded in beside it.
 *
 * Neither costs an unused program anything: the enums' members are generic, so one exists only
 * where a call asks for it. Layout is the exception that does not apply to them — a *non-generic*
 * type is instantiated eagerly wherever it is declared, and these are not.
 */
object Prelude {

  val source: String =
    """enum Option[T]
      |    Some(value: T)
      |    None
      |
      |    is_some(self) -> bool = self match
      |        Some(_) -> true
      |        None -> false
      |
      |    is_none(self) -> bool = !self.is_some()
      |
      |    unwrap_or(self, default: T) -> T = self match
      |        Some(v) -> v
      |        None -> default
      |
      |    unwrap(self) -> T = self match
      |        Some(v) -> v
      |        None ->
      |            print("panic: unwrap of a None value")
      |            exit(1)
      |
      |    expect(self, msg: string) -> T = self match
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
      |    is_ok(self) -> bool = self match
      |        Ok(_) -> true
      |        Err(_) -> false
      |
      |    is_err(self) -> bool = !self.is_ok()
      |
      |    unwrap_or(self, default: T) -> T = self match
      |        Ok(v) -> v
      |        Err(_) -> default
      |
      |    unwrap(self) -> T = self match
      |        Ok(v) -> v
      |        Err(_) ->
      |            print("panic: unwrap of an Err value")
      |            exit(1)
      |
      |    expect(self, msg: string) -> T = self match
      |        Ok(v) -> v
      |        Err(_) ->
      |            print("panic:", msg)
      |            exit(1)
      |
      |    unwrap_err(self) -> E = self match
      |        Err(e) -> e
      |        Ok(_) ->
      |            print("panic: unwrap_err of an Ok value")
      |            exit(1)
      |
      |    expect_err(self, msg: string) -> E = self match
      |        Err(e) -> e
      |        Ok(_) ->
      |            print("panic:", msg)
      |            exit(1)
      |end Result
      |
      |""".stripMargin

  /** The source the prelude's own declarations point into, so a diagnostic against one quotes the
   * prelude rather than the user's file at some unrelated line — and so a declaration can be told
   * to have come from here, which is what makes an unused one droppable.
   */
  val origin: Source = Source("<prelude>", source)

  /** The parsed prelude declarations, parsed once. */
  lazy val decls: List[Stmt] =
    SyslParser.parse(origin) match
      case Right(p) => p.body
      case Left(e)  => sys.error(s"the prelude does not parse: $e")

  /** Whether a declaration came from here rather than from the program being compiled.
   *
   * Asked through `Library.owns` rather than directly, so that what counts as the library's is one
   * question with one answer while declarations are moving out of here and into a module.
   */
  def declares(s: Positioned): Boolean = s.pos.exists(_.source eq origin)
}
