package sh.sysl

/** Turning `_` into a closure (`12 §5c`).
 *
 * A placeholder is read as an ordinary operand, and the closure it belongs to is decided by
 * *position* rather than by anything the operand itself carries. So the work happens where the
 * grammar already knows a boundary has been reached — a parenthesized group, a call argument, a
 * statement-level expression — and each of those calls [[lift]] on what it just read.
 *
 * **A placeholder is an [[Ident]] from the moment it is read**, under a name the source cannot
 * spell, and [[lift]] does nothing but collect those names into a parameter list. Nothing new
 * therefore reaches the analyzer, `AstCodec`, or any later pass: a lifted closure is exactly the
 * tree `x -> x + 1` would have produced, so every one of them handles this feature by already
 * handling §5. The alternative — a node of its own, rewritten away at the boundary — would have
 * needed a rewrite over every expression form and a codec tag besides.
 *
 * [[lift]] is idempotent in the way that matters: a subtree whose placeholders an inner boundary
 * already took is a [[Lambda]], which the walk stops at, so an outer boundary finds nothing free
 * and hands the subtree back untouched. That is what makes `xs.map(_ + 1)` give `map` the closure
 * instead of becoming one.
 */
object Placeholders {

  /** The prefix that marks a name as one of these rather than the program's.
   *
   * `$` is the separator `Modules` reserves and no identifier may contain, so a source name can
   * never collide with a placeholder's and a diagnostic showing one is visibly not quoting the
   * program back.
   */
  private val Prefix = "$ph"

  def isPlaceholder(name: String): Boolean = name.startsWith(Prefix)

  /** A fresh placeholder name. The counter runs per parser, so the names are unique within a file,
   * which is all uniqueness has to buy: two closures never share a parameter list.
   */
  def fresh(n: Int): String = s"$Prefix$n"

  /** Every placeholder under `e` that no closure has bound yet, in the order they were written.
   *
   * The walk is over `Product` rather than a case per node type, which is what keeps it correct as
   * the expression grammar grows: a node added tomorrow is walked because it is a case class, and
   * `productIterator` yields its fields in declaration order — which for every node here is source
   * order. `List` and `Option` are walked for the same reason, both being products of their parts.
   *
   * A [[Lambda]] is where the walk stops. Its body was itself a boundary, so any placeholder inside
   * it is already somebody's parameter, and descending would steal it from the closure that has it.
   */
  private def free(x: Any): List[Ident] = x match
    case i @ Ident(n) if isPlaceholder(n) => List(i)
    case _: Lambda                        => Nil
    case p: Product                       => p.productIterator.toList.flatMap(free)
    case _                                => Nil

  /** The closure `e` becomes if it holds placeholders, or `e` unchanged if it holds none.
   *
   * The parameter list is the placeholders in the order they were *written*, which is why this
   * happens at the boundary and not at the operand: until the whole expression has been read there
   * is no order to put them in.
   */
  def lift(e: Expr): Expr =
    free(e) match
      case Nil => e
      case ids =>
        val params = ids.map(i => LambdaParam(i.name, None).setPos(i.pos))

        Lambda(params, List(ExprStmt(e).setPos(e.pos))).setPos(e.pos)

  /** A call argument, which is a boundary except for the one case that would leave the closure
   * empty (`12 §5c`).
   *
   * A bare `_` is handed back untouched so the *call* closes over it — `f(_, 0)` is `x -> f(x, 0)`,
   * and lifting here would have made it `f(x -> x, 0)` instead, which is a different program and
   * never the one meant. Anything larger than the placeholder is lifted where it stands, which is
   * what makes `xs.map(_ + 1)` hand `map` a closure rather than becoming one.
   */
  def liftArg(e: Expr): Expr = e match
    case Ident(n) if isPlaceholder(n)            => e
    case NamedArg(_, Ident(n)) if isPlaceholder(n) => e
    case NamedArg(n, v)                          => NamedArg(n, lift(v)).setPos(e.pos)
    case _                                       => lift(e)
}
