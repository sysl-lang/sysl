package io.github.edadma.sysl

import scala.collection.mutable

/** The order a program's computed `val` initializers run in (`13 §7`).
 *
 * A `val` whose initializer is a constant tree is laid straight into the object file and has no
 * order to have. One whose initializer is *code* runs before the program's own statements, and as
 * soon as two of them exist the question is which goes first — which is the question that kept
 * computed initializers out of `val`'s first cut.
 *
 * **The answer falls out of `13 §6` rather than being invented for this.** The rule is that an
 * initializer runs after every `val` it needs, and it needs whichever ones its initializer reads,
 * directly or through anything that initializer calls. That is a graph over the `val`s themselves,
 * and it is finer than the module graph in the one direction that matters: a module's own files have
 * no order at all (§7), so ordering by module could not have settled two tables in one directory.
 *
 * The module DAG is still what makes the rule usable, and in a way worth saying out loud: a
 * cross-module reference follows a module edge, and those may not cycle, so **a cycle among `val`s
 * can only ever be inside one module**. The diagnostic is therefore always local — every declaration
 * it names is in one directory, and usually in one file.
 *
 * Which `val`s an initializer reads is `Reachability`'s walk, asked of one root. Everything that
 * walk over-approximates — a call through a method table above all — errs towards ordering a `val`
 * *earlier* than it strictly had to be, which is the harmless direction.
 */
trait InitOrder extends AnalyzerBase {

  /** Every `val` the program lays down, with the computed ones in the order their initializers run.
   *
   * The constant ones come first and keep the order they were declared in. They are not part of the
   * graph at all: there is no code to place, and nothing that runs can observe one being filled,
   * since it was filled before the process started.
   */
  protected def orderVals(vals: List[TVal], funcs: List[TFunc], vtables: List[TVtable]): List[TVal] = {
    val (constant, computed) = vals.partition(!_.computed)

    if computed.isEmpty then vals
    else
      val ordered = computed.map(_.symbol).toSet
      val deps = computed
        .map(v => v.symbol -> Reachability.reachedFrom(List(v.init), funcs, vtables).vals.intersect(ordered))
        .toMap

      constant ::: sorted(computed, deps)
  }

  /** The computed `val`s in an order that runs each after everything it needs, reporting any cycle
   * that makes such an order impossible.
   *
   * A declaration on a cycle is still emitted, after the ones it does not depend on — nothing is
   * emitted at all while an error stands, so what the order is in that case only has to be *an*
   * order rather than the right one.
   */
  private def sorted(computed: List[TVal], deps: Map[String, Set[String]]): List[TVal] = {
    val byName  = computed.map(v => v.symbol -> v).toMap
    val out     = mutable.ListBuffer.empty[TVal]
    val placed  = mutable.HashSet.empty[String]
    val walking = mutable.ListBuffer.empty[String]
    val blamed  = mutable.HashSet.empty[String]

    def walk(name: String): Unit =
      if walking.contains(name) then report(walking.dropWhile(_ != name).toList)
      else if !placed(name) then
        walking += name
        deps(name).toList.sorted.foreach(walk)
        walking.remove(walking.length - 1)
        placed += name
        out += byName(name)

    // Reported at the declaration whose initializer closes the loop, and once per cycle: a second
    // declaration on the same loop has the same mistake in it, and saying so twice would not help
    // anyone find it.
    def report(cycle: List[String]): Unit =
      val head = cycle.min

      if blamed.add(head) then
        currentPos = valDecls(head).pos

        val chain = (cycle.dropWhile(_ != head) ::: cycle.takeWhile(_ != head)).tail :+ head

        recover(())(err(s"'${qn(head)}' cannot be initialized: its value needs " +
          chain.map(n => s"'${qn(n)}'").mkString(", whose value needs ") +
          " — a computed 'val' runs once before anything else does, so what it needs has to be " +
          "settled first"))

    computed.map(_.symbol).foreach(walk)
    out.toList
  }
}
