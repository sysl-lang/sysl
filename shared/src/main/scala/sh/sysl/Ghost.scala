package sh.sysl

/** `@ghost` — what exists for the specification alone (`reference/verification.md § @ghost — what
 * costs nothing to say`).
 *
 * **The problem it solves is asymptotic rather than aesthetic.** Everything else in `17` executes,
 * which is `§1` and is what keeps a clause from meaning two things. The one place that costs more
 * than it is worth is a specification that is more expensive than the code it specifies: `invariant
 * for all j in 0..<i do a[j] <= a[j+1]` is the right invariant for an insertion sort's outer loop,
 * it is O(n) where the body is O(n), and checking it every iteration turns an O(n²) sort into O(n³).
 *
 * **The rule that makes erasing it sound is a rule about who may mention it**, and it is two
 * sentences:
 *
 *   1. Nothing executable may call a ghost function. If it could, erasing the declaration would
 *      change what the program computes.
 *   2. A clause that calls one is a clause that does not run. Given the first, it *cannot* run —
 *      the callee is not there — so the only question was whether to allow such a clause at all, and
 *      refusing would leave `@ghost` marking things nothing may mention.
 *
 * **The second is the one exception to `§1`, and it is acceptable because it is legible in the
 * source.** A reader asking whether a clause executes reads the names in it. What sysl refuses is a
 * *switch* — one program with two meanings depending on how it was built. Here two clauses have two
 * meanings and each says which it is by what it mentions.
 */
object Ghost {

  /** Whether a tree names a ghost function anywhere in it.
   *
   * Asked of the whole clause rather than of the part that mentions the ghost, because a clause is
   * one condition — half of a conjunction is not a condition anybody wrote. It lives here rather
   * than on the trait because both ends of the compiler ask it: the analyzer to decide what may be
   * called from where, and codegen to decide which clauses to lay down.
   */
  def mentions(x: Any, ghost: Set[String]): Boolean = x match
    case _: Type                             => false
    case TCall(name, _, _, _) if ghost(name) => true
    case xs: Iterable[?]                     => xs.exists(mentions(_, ghost))
    case p: Product                          => p.productIterator.exists(mentions(_, ghost))
    case _                                   => false
}

trait Ghost extends AnalyzerBase {

  /** The ghost functions of a compilation, by the symbol a call names. */
  protected def ghostNames(funcs: List[TFunc]): Set[String] = funcs.filter(_.ghost).map(_.name).toSet

  /** Reports every call to a ghost function from somewhere that runs.
   *
   * The clauses are where one *may* be called, so they are not scanned: a function's `require`,
   * `ensure` and `variant` are held on the node beside the body, and a loop's `invariant` and
   * `variant` are statements the walk stops at. Everything else is executable.
   */
  protected def checkGhost(funcs: List[TFunc], main: List[TStmt]): Unit = {
    val ghost = ghostNames(funcs)

    if ghost.nonEmpty then
      // A ghost function's own body is where real state is read — an `is_sorted` walks a real slice —
      // so it may call whatever it likes, ghost or not.
      for f <- funcs if !f.ghost do executable(f.body, ghost)
      executable(main, ghost)
  }

  /** Walks the part of a tree that runs, reporting a call to a ghost function wherever it is.
   *
   * A clause is a statement here (`TInvariant`, `TVariantCheck`) and is skipped whole, which is what
   * makes "where a ghost function may be called" a property of the syntax a reader can see rather
   * than of a context the compiler is tracking.
   */
  private def executable(x: Any, ghost: Set[String]): Unit = x match
    case _: Type                       => ()
    case _: TInvariant | _: TVariantCheck => ()
    case c @ TCall(name, _, _, _) if ghost(name) =>
      recover(())(at(c.pos)(err(s"'${Modules.show(name)}' is '@ghost', so it exists for the " +
        "specification and is not there when the program runs — it may be called from a 'require', " +
        "an 'ensure', a loop's 'invariant' or 'variant', or from another '@ghost' function, and " +
        "from nowhere else")))
    case xs: Iterable[?] => xs.foreach(executable(_, ghost))
    case p: Product      => p.productIterator.foreach(executable(_, ghost))
    case _               => ()

}
