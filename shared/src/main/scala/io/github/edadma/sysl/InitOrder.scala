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
 * **A call through a method table is followed too.** Which function a `&Trait` lands in is not known
 * here, but the whole program is compiled at once, so the *set* it could land in is: every table for
 * that trait supplies one function per slot. Taking the union of those is an over-approximation that
 * cannot miss a read, and it is a narrow one — the alternative, refusing dynamic dispatch inside an
 * initializer, would be a rule with no reason behind it.
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
      val deps    = computed.map(v => v.symbol -> needs(v, funcs, vtables).intersect(ordered)).toMap

      constant ::: sorted(computed, deps)
  }

  /** Which `val`s an initializer ends up reading — the ones it names itself, plus the ones named by
   * everything it can reach from there.
   */
  private def needs(v: TVal, funcs: List[TFunc], vtables: List[TVtable]): Set[String] = {
    val direct = summarize(v.init, vtables)
    val seen   = mutable.HashSet.empty[String]
    val queue  = mutable.Queue.from(direct.calls)
    val byName = funcs.map(f => f.name -> f).toMap
    val reads  = mutable.HashSet.from(direct.vals)

    // A plain reachability walk rather than a fixpoint: recursion among the callees is fine, since
    // what is being accumulated is a set and a function already visited adds nothing a second time.
    while queue.nonEmpty do
      val name = queue.dequeue()

      if seen.add(name) then
        for f <- byName.get(name) do
          val s = summarize(f, vtables)

          reads ++= s.vals
          queue ++= s.calls

    reads.toSet
  }

  /** What one tree names: the `val`s read out of it and the functions it can call. */
  private case class Refs(vals: Set[String], calls: Set[String])

  private def summarize(root: Any, vtables: List[TVtable]): Refs = {
    val vals  = mutable.HashSet.empty[String]
    val calls = mutable.HashSet.empty[String]

    // Every table for a trait supplies one function per slot, so a call at a slot can be answered
    // with the functions every implementation of that trait put there.
    def dynamic(recvTy: Type, slot: Int): Unit =
      val name = recvTy match
        case Type.Ptr(Type.Trait(n, _))    => Some(n)
        case Type.Ref(Type.Trait(n, _), _) => Some(n)
        case _                             => None

      for t <- vtables if name.contains(t.traitName); s <- t.slots.lift(slot) do calls += s.target

    // The walk descends through the shape of the tree rather than through a case per node, and
    // deliberately: a node added later is covered by it without anyone remembering to come back
    // here, where a match with a catch-all would silently stop following the new one's children.
    // Only the nodes that *name* something get a case, since a name is a string like any other and
    // the shape cannot tell them apart. A `Type` is where the descent stops — it holds no
    // expression, and a recursive one would otherwise be walked forever.
    def scan(x: Any): Unit = x match
      case _: Type            => ()
      case g: TGlobal         => vals += g.symbol
      case d: TDispatch       => calls += d.name
      case c: TCall           => calls += c.name; c.args.foreach(scan)
      case s: TStructInvCheck => calls += s.invFn; scan(s.value)
      case s: TCheckedStore   => calls += s.invFn; scan(s.store); scan(s.recv)
      case r: TRender =>
        r.slot match
          case Some(slot) => dynamic(r.value.ty, slot)
          case None       => calls += r.method
        scan(r.value); scan(r.spec)
      case v: TVCall =>
        dynamic(v.receiver.ty, v.slot)
        scan(v.receiver); v.args.foreach(scan)
      case xs: Iterable[?] => xs.foreach(scan)
      case p: Product      => p.productIterator.foreach(scan)
      case _               => ()

    scan(root)
    Refs(vals.toSet, calls.toSet)
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
