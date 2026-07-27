package io.github.edadma.sysl

import scala.collection.mutable

/** The rule that no two modules may depend on each other, directly or through a chain (`13 §6`).
 *
 * The graph the rule is about is the **reference** graph, not the import graph, and the difference
 * is not a nicety: a qualified path reaches another module's declaration with no import at all
 * (`13 §3`), so a file can depend on a module its header never mentions. The edges come from
 * resolution instead — `AnalyzerBase.dependsOn` records one wherever a name turns out to belong
 * somewhere else — and an import contributes one of its own, since a file that imports a module
 * depends on it whether or not it goes on to name anything from it.
 *
 * Being acyclic is what makes a module's dependencies *finished* before the module itself is
 * looked at, which is what capability propagation is a single reverse-topological sweep over rather
 * than a fixpoint (`13 §4`), and what would let modules compile in dependency order. Cycles
 * **within** a module stay free: sibling files share one scope, so mutual recursion across them
 * needs no ceremony at all.
 */
trait ModuleGraph extends AnalyzerBase {

  /** Reports every cycle in the module graph.
   *
   * It runs at the end of the walk, because an edge is made by a reference and a reference may be
   * anywhere a body is — including one only reached by instantiating a generic. A cyclic program is
   * otherwise a perfectly analyzable one, so there is nothing to be gained by asking earlier.
   */
  protected def checkModuleGraph(): Unit = {
    val edges = mutable.LinkedHashSet.from(moduleEdges.keys)

    // Each cycle reported is broken at the reference the diagnostic points at, so the search can go
    // on and find one somewhere else in the graph. Without that a second, unrelated cycle would be
    // invisible until the first was fixed; with it, one mistake is still reported once, because the
    // edge that came back is exactly the one the reader was told about.
    var cycle = findCycle(edges)

    while cycle.isDefined do
      for c <- cycle do
        report(c)
        edges -= ((c.head, c(1)))

      cycle = findCycle(edges)
  }

  /** Some cycle in `edges`, as the modules on it in the order they depend on one another — `a`,
   * `b`, `c` for `a` depending on `b` depending on `c` depending on `a`.
   *
   * A depth-first walk, with the modules and each module's dependencies both taken in name order so
   * that which cycle is found does not depend on the order the files happened to be read in. A
   * module whose walk came back clean is not walked again, which is what keeps a diamond from
   * costing exponential time.
   */
  private def findCycle(edges: collection.Set[(String, String)]): Option[List[String]] = {
    val out     = edges.groupMap(_._1)(_._2).view.mapValues(_.toList.sorted).toMap
    val settled = mutable.HashSet.empty[String]

    def walk(m: String, path: List[String]): Option[List[String]] =
      if path.contains(m) then Some(rotate(path.dropWhile(_ != m)))
      else if settled(m) then None
      else
        val found = out.getOrElse(m, Nil).iterator.map(walk(_, path :+ m)).collectFirst { case Some(c) => c }

        if found.isEmpty then settled += m
        found

    out.keys.toList.sorted.iterator.map(walk(_, Nil)).collectFirst { case Some(c) => c }
  }

  /** A cycle turned so that it begins at the first of its modules by name.
   *
   * Which module a walk *reaches* a cycle at is an accident of where it started; which module the
   * message begins with should not be. Turning it makes the same cycle read the same way whatever
   * found it, so a program's diagnostics do not change when a file is renamed.
   */
  private def rotate(cycle: List[String]): List[String] = {
    val start = cycle.indexOf(cycle.min)

    cycle.drop(start) ::: cycle.take(start)
  }

  /** One cycle, said as the chain it is, pointing at the reference that begins the chain — the
   * place in the first module where it reaches the second, from which a reader can follow the rest.
   */
  private def report(cycle: List[String]): Unit = {
    // Moved outright rather than through `at`, which leaves the cursor where it is when there is no
    // position to move it to — and where a finished walk left it is not this cycle's line.
    currentPos = moduleEdges((cycle.head, cycle(1)))

    val chain = (cycle.tail :+ cycle.head).map(m => s"'$m'").mkString(", which depends on ")

    recover(())(err(s"'${cycle.head}' depends on $chain — modules may not depend on each other, " +
      "directly or through a chain"))
  }
}
