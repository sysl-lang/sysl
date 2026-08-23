package sh.sysl

/** Which constructs a place in a file is inside.
 *
 * This is the question an editor asks and no pass of the compiler ever does: the compiler walks a
 * tree it already has in hand, while an editor has a cursor and has to find the tree. Hover, the
 * expanding selection, "what am I looking at" and the first half of go-to-definition are all this
 * one query, and it is answerable at all because `SyslParserBase.at` records what each node was
 * parsed from (`Positioned.extent`).
 *
 * **It reads `extent` rather than `pos`, and the difference is the whole point.** A node's `pos` is
 * where a *complaint* about it belongs, which a rule may put somewhere inside the node — a call is
 * anchored on its callee. A cursor in the argument list of `xs.foo(1)` is inside the call whether or
 * not it is anywhere near `foo`.
 */
object Locate {

  /** Every construct covering `line`/`col`, outermost first — the path from the file down to the
   * smallest thing the cursor is in.
   *
   * Both are 1-based, as everything that reports a position is, and they are the columns of the
   * **file**: subtract `source.columnOffset` first for a literate file, whose program text the lexer
   * saw with its margin already removed.
   *
   * A caller matches on the concrete node — `Ident`, `Call`, `VarDecl` — which is why this hands
   * back the nodes themselves rather than their spans. The last one is the innermost, and is what
   * hover wants; the whole list is what an expanding selection wants.
   *
   * The end of a span is **exclusive**, so a cursor sitting just past the last character of a name
   * is not in it. That is the convention every span in the compiler already keeps, and it is what
   * makes two adjacent tokens unambiguous — a caller that wants an editor's more forgiving reading
   * asks again at `col - 1`.
   */
  def at(program: Program, line: Int, col: Int): List[Positioned] = {
    val covering = walk(program).filter(covers(_, line, col))

    // Outermost first, by start ascending and end descending — which puts a node before everything
    // nested in it, and is a total order, so nodes that merely overlap still come back in a
    // predictable one. The sort is stable, so two constructs of exactly the same extent — a
    // statement and the expression that is the whole of it — keep the order the walk found them in,
    // which is the enclosing one first.
    covering.sortWith { (a, b) =>
      val x = a.extent.get
      val y = b.extent.get

      if x.line != y.line || x.col != y.col then before(x.line, x.col, y.line, y.col)
      else before(y.endLine, y.endCol, x.endLine, x.endCol)
    }
  }

  /** The smallest construct covering `line`/`col` — hover's question, and the one a name is read
   * from before asking what it refers to.
   */
  def innermost(program: Program, line: Int, col: Int): Option[Positioned] = at(program, line, col).lastOption

  /** Whether a node's extent covers the place, with the end exclusive. A node that never went
   * through `at` has no extent and covers nothing rather than everything.
   */
  private def covers(node: Positioned, line: Int, col: Int): Boolean = node.extent.exists { p =>
    !before(line, col, p.line, p.col) && before(line, col, p.endLine, p.endCol)
  }

  /** Whether one place in a file comes before another. Written out rather than compared as tuples,
   * which needs an ordering import to mean anything and reads as though it might be comparing
   * something else.
   */
  private def before(line: Int, col: Int, otherLine: Int, otherCol: Int): Boolean =
    line < otherLine || (line == otherLine && col < otherCol)

  /** Every positioned node in the tree, in the order a walk over the case-class children reaches
   * them — so an enclosing node is always found before what is inside it.
   *
   * The walk is over `Product` rather than over the node types by name, and that is deliberate:
   * every node in the AST is a case class, there are well over a hundred of them, and a hand-written
   * traversal would be a second copy of the grammar's shape that goes stale the first time a node
   * grows a field. What it costs is that nothing checks the walk reaches everything — which is why
   * the tests assert on nodes from each family rather than only on an expression.
   *
   * A `List` and a `Some` are themselves `Product`s, so the collections are matched first; otherwise
   * a list would be walked as its `::` cells, reaching the same nodes by a route that is far less
   * obvious to read. A `Source` is a plain class and stops the walk, which is what keeps a node's
   * own file out of it.
   *
   * `DefinitionIndex` walks the **typed** tree with this same method, which works because a `TExpr`
   * is `Positioned` too — one reflection walk rather than two that could disagree about what a tree
   * contains.
   */
  private[sysl] def walk(node: Any): List[Positioned] = {
    val here = node match
      case p: Positioned => List(p)
      case _             => Nil

    val below = node match
      case xs: Iterable[?] => xs.toList.flatMap(walk)
      case o: Option[?]    => o.toList.flatMap(walk)
      case p: Product      => p.productIterator.toList.flatMap(walk)
      case _               => Nil

    here ::: below
  }
}
