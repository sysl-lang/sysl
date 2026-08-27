package sh.sysl

/** Which trees can arrive at one of a named set of functions, and the smallest sub-tree that still
 * can — the walk two capability checks are written in terms of.
 *
 * **What varies between them is the seed set and the sentence; the descent does not.** `NoAlloc`
 * seeds it with the functions whose own bodies make heap storage and reports an allocation reached
 * from an allocator-free module; `DeclCapabilities` seeds it with the declarations that wrote
 * `@needs(...)` and reports a capability reached from a module that does not have it. Both want the
 * same thing of the tree, and the descent is subtle enough — see `blame` — that two copies of it
 * would drift.
 *
 * The reachable set is the one `Reachability` computes **in its `written` mode**, which answers a
 * run-time target with the tables this code erased a value into rather than with every table for the
 * trait. That distinction is the whole of why a capability question may not use the default walk: a
 * clause is a promise about a module's own conduct, and which `impl Writer` is behind a `*Writer`
 * parameter is its caller's choice, made in a module of their own.
 */
class Reaches(funcs: List[TFunc], vtables: List[TVtable], seeds: Set[String]) {

  /** Every seed this tree can arrive at. */
  def reached(x: Any): Set[String] =
    if seeds.isEmpty then Set.empty
    else Reachability.reachedFrom(List(x), funcs, vtables, written = true).calls.filter(seeds)

  /** Reports the **smallest** sub-tree that still reaches a seed, which is as close to the call as
   * the tree can put the caret: a body reaches one through some statement, that statement through
   * some expression, and the descent stops where no part of the node answers on its own.
   *
   * **Every such sub-tree, not merely the first.** A body that calls two seeded functions has two
   * things wrong with it and two places to change, and a reader told about one of them fixes it and
   * is told about the next — which is a worse experience than a direct walk gives for the same
   * mistake one step nearer. So where several children still reach, the descent branches into all
   * of them rather than picking one.
   *
   * That is not the same as reporting every node: the descent still stops as soon as no part of a
   * node answers on its own, which is what keeps one expression reaching one seed from being a
   * message per node on the way down.
   *
   * `report` is handed the site's position and the **least** seed it reaches by name, rather than
   * whichever the set happened to yield first — the site is what the reader has to change, and a
   * diagnostic that varied between runs would be a poor thing to assert on.
   */
  def blame(x: Any)(report: (Option[Pos], String) => Unit): Unit =
    if reached(x).nonEmpty then
      Reaches.parts(x).filter(c => reached(c).nonEmpty) match
        case Nil  => for who <- reached(x).toList.sorted.headOption do report(Reaches.position(x), who)
        case kids => kids.foreach(blame(_)(report))
}

object Reaches {

  /** The children of a node, by its shape rather than by a case per kind — the reason
   * `Reachability`'s own descent is written this way: a node added later is walked without anybody
   * remembering to come back here. A `Type` is not walked, having nothing that runs in it.
   */
  def parts(x: Any): List[Any] = x match
    case _: Type         => Nil
    case xs: Iterable[?] => xs.toList
    case p: Product      => p.productIterator.toList
    case _               => Nil

  /** Where the caret goes: the node's own position, or the first one under it that has one — a
   * statement wrapping an expression carries none of its own.
   */
  def position(x: Any): Option[Pos] = x match
    case p: Positioned if p.pos.isDefined => p.pos
    case _                                => parts(x).flatMap(c => position(c).toList).headOption
}
