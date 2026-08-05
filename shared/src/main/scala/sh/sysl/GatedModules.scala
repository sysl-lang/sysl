package sh.sysl

import scala.collection.mutable

/** What a module that gave up an environment capability may not reach (`capabilities.md
 * § Propagation through imports`, `13 §4`).
 *
 * **The question is asked of the module graph, and that is what makes it a different pass from
 * `NoAlloc`.** `alloc` changes what the *language* allows, so it is checked at each construction
 * that makes heap storage and at each call that arrives at one — it has to be, because the standard
 * module is one module and half of it allocates, so a rule stated over modules would refuse every
 * `no alloc` module that printed anything. `os` and `posix` gate **which modules exist**, which is a
 * statement about a module and not about any declaration in it: a program either may name `sysl.fs`
 * or may not, and every declaration in that module is equally out of reach.
 *
 * So the edge is the unit here, and the diagnostic lands at the reference that made it — the place a
 * reader has to change — rather than at the clause, which is where it would land if the check were
 * about the module's own text.
 *
 * **A requirement is transitive.** A module that gave up `os` may not reach one that requires it
 * *through* a third, since what the third offers is only reachable because the gated module is: what
 * `capabilities.md` asks for is that "the whole transitive graph must fit within the target's
 * capabilities", and this is that rule for the half of it a module states about itself.
 */
trait GatedModules extends AnalyzerBase {

  /** Reports every reference out of a module that narrowed an environment capability into one that
   * needs it.
   *
   * It runs after the module graph is settled and held to being acyclic, for both of the reasons
   * that pass does: an edge is made by a reference and a reference may be anywhere a body is, and
   * the transitive walk below terminates because the graph has no cycles.
   */
  protected def checkGatedModules(): Unit = {
    val gaveUp = moduleNarrows.view
      .mapValues(_.keySet & Capability.environment)
      .filter(_._2.nonEmpty)
      .toMap

    // Nothing narrowed anything, which is almost every program: the walk below reads every edge, and
    // a compilation with no clause in it should pay nothing at all for this.
    if gaveUp.nonEmpty then
      val needed = requirements()

      for
        ((from, to), pos) <- moduleEdges.toList
        given_up          <- gaveUp.get(from)
        // The least of them by name where a reference is refused for more than one reason, so the
        // message does not vary between runs with the iteration order of a set.
        cap <- (given_up & needed.getOrElse(to, Set.empty)).toList.sorted.headOption
      do
        // Moved outright rather than through `at`, which would leave the cursor wherever the
        // finished walk left it when an edge carries no position of its own.
        currentPos = pos

        recover(())(err(s"this reaches '$to', which requires '$cap', and ${here(from)} declared " +
          s"'no $cap' — an environment capability gates which modules exist, so a module that gave " +
          "one up may not reach one that needs it"))
  }

  /** How a module refers to itself in a diagnostic. The root module has no name to print, and a
   * program's own files are in it, so the common case reads as a sentence rather than as an empty
   * pair of quotes.
   */
  private def here(module: String): String =
    if module.isEmpty then "this module" else s"'$module'"

  /** What each module needs an operating system for, its own clause plus everything it reaches.
   *
   * A module that requires nothing itself still requires whatever it depends on requires — that is
   * the whole of what makes the answer transitive, and it is why a program cannot get at `sysl.fs`
   * by going through something else that does.
   *
   * The walk is memoized per module, so a diamond costs one visit rather than one per path. A module
   * already on the path answers empty rather than recurring, which is only reachable in a program
   * whose module graph has a cycle — already a diagnostic of its own, and the degraded answer here
   * is an under-approximation, so nothing is refused that a correct answer would have allowed.
   */
  private def requirements(): collection.Map[String, Set[String]] = {
    val out  = mutable.HashMap.empty[String, Set[String]]
    val deps = moduleEdges.keys.toList.groupMap(_._1)(_._2)

    def of(m: String, path: Set[String]): Set[String] =
      out.get(m) match
        case Some(found)     => found
        case None if path(m) => Set.empty
        case None            =>
          val own   = moduleRequires.get(m).map(_.keySet).getOrElse(Set.empty) & Capability.environment
          val below = deps.getOrElse(m, Nil).flatMap(of(_, path + m)).toSet
          val all   = own ++ below

          out(m) = all
          all

    for m <- deps.keySet ++ moduleRequires.keySet do of(m, Set.empty)
    out
  }
}
