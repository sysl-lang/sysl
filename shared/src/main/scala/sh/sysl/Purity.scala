package sh.sysl

import scala.collection.mutable

/** What a function marked `@pure` may not do (`17 §6`).
 *
 * **Asked of the typed tree**, for the reason `NoAlloc`'s question is: what a caller can observe is
 * decided in a dozen places across the analyzer, and a guard at each of them is a list nobody can
 * read and that a thirteenth site would quietly not join. A node that is observable is a *node*, so
 * the two lists below are the whole answer and one added later is visibly absent from them.
 *
 * The rule is stated over what a **caller** can observe, which is what makes each exclusion a
 * sentence rather than a convention:
 *
 *   - a write that passes through a dereference reaches storage the function did not create, so the
 *     caller — who supplied the pointer or the reference — sees it;
 *   - a mutable global is storage nobody's call created, so a read of one is not a function of the
 *     arguments and a write to one is visible to everything;
 *   - a call the compiler cannot resolve to a declaration has no declaration to consult, so nothing
 *     can be said about it at all;
 *   - an `extern` is the other side of the seam and says nothing about itself;
 *   - an `asm` block, an atomic and a fence are each an effect written down as an instruction.
 *
 * **Allocation is deliberately absent from both lists**, and `17 §6` argues it out: a caller cannot
 * observe an object that did not exist when the call began, and `13 §4`'s `no alloc` already answers
 * the allocation question for a whole module. Trapping is absent for the same kind of reason —
 * `16`'s constrained arithmetic can trap, so a purity that excluded it would exclude those types
 * wholesale.
 *
 * Purity is **not inferred**. Only the direct callees are checked, which is enough by induction: if
 * every callee of a pure function is itself pure, everything it reaches is. Checking the callees
 * rather than the reachable set is also what lets the diagnostic name the call that was written.
 */
trait Purity extends AnalyzerBase {

  private val reported = mutable.Set.empty[Pos]

  /** Reports everything a `@pure` function does that a caller could observe. */
  protected def checkPurity(funcs: List[TFunc], externs: List[TExtern]): Unit = {
    val pure    = funcs.filter(_.pure).map(_.name).toSet
    val foreign = externs.map(_.name).toSet

    // One caret, one message. A single line the reader wrote can lower to several observable nodes —
    // `print(x)` is a call for the value and another for the newline — and telling them twice about
    // one `print` reads as two mistakes. Different lines still get a message each, which is the
    // property that matters: a body with two problems has two places to change.
    reported.clear()

    for f <- funcs if f.pure do
      scan(f.body, pure, foreign, None)
      // The clauses are the function's own code and run on every call, so they are held to the same
      // rule the body is — an `ensure` that printed would be a pure function that prints.
      f.requires.foreach((c, _) => scan(c, pure, foreign, None))
      f.ensures.foreach((c, _) => scan(c, pure, foreign, None))
      f.variant.foreach(scan(_, pure, foreign, None))
  }

  /** What a node does that a caller could observe, said the way a reader would say it, or nothing.
   *
   * A **write** is examined through its place: what is refused is not writing but writing through a
   * dereference, since that is exactly the storage the function was handed rather than made. An
   * element of a local array is `TIndex` over a `TLoad` and is the function's own; a field of a `&T`
   * parameter is `TField` over a `TDeref` and is its caller's.
   */
  private def observable(e: TExpr, pure: Set[String], foreign: Set[String]): Option[String] = e match {
    case TStore(place, _, _) if !ownStorage(place)     => Some("writes through a reference to storage it did not create")
    case TUpdate(place, _, _, _, _, _) if !ownStorage(place) =>
      Some("writes through a reference to storage it did not create")
    case TIncDec(place, _, _, _, _) if !ownStorage(place) =>
      Some("writes through a reference to storage it did not create")

    case TGlobal(symbol, _, true) =>
      Some(s"reaches '${Modules.show(symbol)}', which is storage outside the call and may change under it")

    // A call through an `Fn` value that the analyzer resolved to one particular closure is still a
    // call the reader made through a value, so it is reported as one — naming the struct the compiler
    // filed the closure under would name something nobody wrote.
    case TCall(name, _, _, _) if Closures.symbol(name) && !pure(name) =>
      Some("calls through a closure, which names no declaration for the compiler to consult")

    case TCall(name, _, _, _) if foreign(name) =>
      Some(s"calls '${Modules.show(name)}', which is an 'extern' and says nothing about what it does")

    case TCall(name, _, _, _) if !pure(name) =>
      Some(s"calls '${Modules.show(name)}', which is not marked '@pure'")

    case _: TCallPtr =>
      Some("calls through a value, which names no declaration for the compiler to consult")

    case _: TVCall =>
      Some("dispatches through a trait object, where which body runs is settled while the program is")

    case _: TAtomic => Some("performs an atomic operation, which is an effect other threads observe")
    case _: TFence  => Some("orders memory, which is an effect other threads observe")

    case _ => None
  }

  /** Whether a place is storage this call made: a local, or a part of one reached without ever going
   * through a pointer or a reference.
   *
   * The walk stops at the first `TDeref` and answers no, which is the whole rule. It is stated as a
   * property of the *path* rather than of the root because both endpoints matter: `a[i] = v` on a
   * local array is the function's own storage however deep the indexing goes, and `p.f.g = v` is the
   * caller's from the first hop.
   */
  private def ownStorage(place: TExpr): Boolean = place match
    case _: TDeref            => false
    case _: TGlobal           => false
    case TField(r, _, _)      => ownStorage(r)
    case TIndex(r, _, _)      => ownStorage(r)
    case TSlice(b, _, _, _, _) => ownStorage(b)
    case _                    => true

  /** Walks a tree, reporting each observable node and going no deeper into one.
   *
   * The descent is through the shape of the tree rather than a case per node, for the reason
   * `Reachability`'s and `NoAlloc`'s are: a node added later is walked without anyone remembering to
   * come back here.
   *
   * `where` is the innermost position seen on the way down, and it is what a **synthesized** node is
   * reported at. Several forms lower to calls nobody wrote — `print(x)` becomes a call to
   * `sysl.printi`, an operator becomes a call to its trait method — and such a node carries no
   * position of its own, so without this the caret lands wherever the walk was last looking, which
   * for a library lowering is a line in the library. The statement the reader wrote is the nearest
   * position above it, which is exactly what this carries down.
   */
  private def scan(x: Any, pure: Set[String], foreign: Set[String], where: Option[Pos]): Unit = {
    val here = x match
      case p: Positioned if p.pos.isDefined => p.pos
      case _                                => where

    x match
      case _: Type => ()
      case _: TAsm =>
        report(here, "may contain no 'asm' block — what the instructions do is outside anything " +
          "the compiler can say about the call")
      case e: TExpr if observable(e, pure, foreign).isDefined =>
        report(here, observable(e, pure, foreign).get + ", and a caller of one may observe nothing " +
          "about the call but its result")
      case xs: Iterable[?] => xs.foreach(scan(_, pure, foreign, here))
      case p: Product      => p.productIterator.foreach(scan(_, pure, foreign, here))
      case _               => ()
  }

  private def report(where: Option[Pos], what: String): Unit =
    if where.forall(reported.add) then recover(())(at(where)(err(s"a '@pure' function $what")))
}
