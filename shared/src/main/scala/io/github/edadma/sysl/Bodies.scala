package io.github.edadma.sysl

/** Turning a program **body** into a program.
 *
 * A caller holding sysl source as a *string* rather than as a file — a documentation page's code
 * block, a test's inline program — has the statements a program runs and nowhere to put them. This
 * supplies the `main` they belong to, so the string may be written as the thing being shown rather
 * than as a whole program wrapped around it.
 *
 * **The split is structural and could not be textual**, which is the whole reason this exists rather
 * than each caller doing it with string operations. At a file's top level `f()` is a call and
 * `f() -> int = x` is a declaration, and nothing about the characters before the newline separates
 * them — the grammar does, by whether a block follows. So the source is parsed first and partitioned
 * by node, and a caller that cannot parse cannot do this correctly.
 */
object Bodies {

  /** Whether a top-level statement is a **declaration** — hoisted, order-free, and not part of what
   * the program runs.
   *
   * This answers **which file the program starts in**: the one carrying something this is false of.
   * A file holding only a table of constants and the functions that read it declares a lot and runs
   * nothing, and must not thereby become the file the program starts in.
   *
   * It is also what `wrapBody` moves, for the same reason — the statements a body carries are the
   * ones a `main` has to be built around.
   */
  def isDeclaration(s: Stmt): Boolean = s match
    case _: FuncDecl | _: StructDecl | _: EnumDecl | _: TraitDecl | _: ImplDecl | _: ExternDecl |
        _: ExternVarDecl | _: ImportDecl | _: ConstDecl | _: ValDecl | _: TypeDecl | _: StaticDecl =>
      true
    case _ => false

  /** Whether a declaration belongs to the **module** even when it is written in the file the program
   * starts in.
   *
   * The file that carries the statements is a **body**, so what it declares is local to that body: a
   * `val` there is a stack local initialized where it is written, and a function there is a nested
   * function (`12 §5a`) that captures the locals above it. That is the whole of why a top-level `var`
   * has never been readable from a function and a top-level `val` has — a distinction nobody could
   * see a reason for, because there was none.
   *
   * What stays behind is everything with **no runtime identity**: a type names a shape, a `const` is
   * folded into its uses before anything runs, an `extern` names storage somebody else owns, an
   * `import` binds a name for the file. None of them occupies storage or captures anything, and a
   * body has nothing to make them local *to* — `StmtAnalysis` refuses every one of them in a block.
   *
   * This is narrower than `isDeclaration` on purpose, and the two questions really are different: the
   * one above asks which file runs, and is asked of every file; this one asks what that file's own
   * declarations mean, and is asked only of it.
   */
  def isModuleMember(s: Stmt): Boolean = s match
    case _: StructDecl | _: EnumDecl | _: TraitDecl | _: ImplDecl | _: ExternDecl | _: ExternVarDecl |
        _: ImportDecl | _: ConstDecl | _: TypeDecl | _: StaticDecl =>
      true
    case _ => false

  /** The declarations of `u`, plus a `main` holding whatever else it carried.
   *
   * A unit that carries no statements is returned as it stands, so this is safe to apply to a whole
   * program, to a library, or to a body, and a caller needs to know which it has only if it cares.
   *
   * Where the unit **already declares a `main`**, the statements go to the front of that function's
   * body rather than into a second one. That is the order they already had — a program's top-level
   * statements are its initialization and run before the `main` it declared — so a body that mixes
   * the two keeps its meaning rather than becoming a duplicate-`main` error.
   */
  def wrapBody(u: Program): Program = {
    val (decls, stmts) = u.body.partition(isDeclaration)

    if stmts.isEmpty then u
    else
      decls.indexWhere { case f: FuncDecl => f.name == "main"; case _ => false } match
        case -1 =>
          val main = FuncDecl("main", Nil, Nil, None, stmts).setPos(stmts.head.pos)

          u.copy(body = decls :+ main)
        case i =>
          val existing = decls(i).asInstanceOf[FuncDecl]

          u.copy(body = decls.updated(i, existing.copy(body = stmts ::: existing.body).setPos(existing.pos)))
  }

  /** Parses one body and wraps it, which is the whole of what a caller holding a string needs. */
  def parseBody(source: Source, target: Target = Target.default): Either[String, Program] =
    SyslParser.parse(source, target).map(wrapBody)
}
