package sh.sysl

import scala.collection.mutable

/** What a program can still arrive at once it has started: the `val`s an expression ends up reading,
 * and the functions it ends up calling.
 *
 * One walk answers both, because they are the same question asked of different roots. `13 §7` asks it
 * of a single `val`'s initializer — which storage has to be filled before this one — and `prune` asks
 * it of the whole program at once, so that a declaration nothing can reach costs the output nothing.
 *
 * **Only emission is filtered, never analysis.** A function nobody calls is analyzed and reported
 * exactly as one that is called, which is why this runs as its own pass over a program the analyzer
 * has already finished with, and after the checks that come between. A mistake is a mistake whether
 * or not the program would have run the line; what being unreachable costs a declaration is its place
 * in the output, which is the only thing it was costing the program.
 *
 * Two rules make the walk honest, and are worth stating here rather than at each use.
 *
 * **It over-approximates, never under.** Where a call's target is decided at run time, every function
 * it could land in is taken: a slot of a method table is answered with what every table for that
 * trait put there. The whole program is compiled at once, so that set is known even though the choice
 * is not. An over-approximation costs a function that is never called; an under-approximation costs a
 * call to a function that was never written, which is not a trade.
 *
 * **It descends through the shape of the tree rather than a case per node**, and deliberately: a node
 * added later is followed without anyone remembering to come back here, where a match with a
 * catch-all would silently stop descending into the new one's children. Only the nodes that *name*
 * something get a case, since a name is a `String` like any other and the shape cannot tell one from
 * a variable's or an operator's.
 */
object Reachability {

  /** The same program with everything unreachable dropped.
   *
   * The roots are the places the program can start from: the statements it runs, the `main` it runs
   * after those, the initializers that fill its storage before either, and the method tables a trait
   * object dispatches through — a table is a constant the program can read a function out of, so what
   * it points at is reachable whatever can be proved about the calls themselves.
   *
   * The tables and the types are left alone. A table is reached by erasing a value into a trait
   * object, and a type is emitted for its layout rather than for anything that runs, so neither is
   * what this pass is about; both are their own question.
   */
  def prune(program: TProgram): TProgram = {
    // **An interrupt handler is a root**, and it is the one kind of function that can never be
    // anything else: no program calls it — `15 §10` refuses that outright — so a walk starting from
    // what the program *runs* cannot reach it. Dropping one would leave the vector table pointing at
    // nothing, which is a fault at the worst available moment. It is entered by the processor, and
    // that is exactly what an entry point is.
    //
    // Its **body** is walked with the others, so whatever a handler calls survives because the
    // handler does. Only its own name has to be added by hand, since nothing names it.
    //
    // **An `@export`ed function is a root for the same reason** (`15 §12`). Nothing inside the
    // program need ever call it — the whole point is that something outside the program will, and
    // this compilation cannot see that caller any more than it can see the processor. A build with
    // no entry point at all is the case that makes this load bearing: every root above is absent
    // there, so an export that were not one would prune the artifact down to nothing.
    // **A destructor is a root for the third version of the same reason** (`03 § A destructor`).
    // What calls it is the release hook the emitter builds, and that is not a tree this walk can
    // see — it is generated from a payload type at the moment a box of that type is let go of. No
    // reachable body names one, so pruning it would leave the hook calling a symbol nothing defined,
    // and the failure would be at the link, against a name no line of the program contains.
    val handlers    = program.funcs.filter(_.conv.isDefined)
    val exported    = program.funcs.filter(_.exported.isDefined)
    val destructors = program.funcs.filter(f => program.destructors.values.toSet.contains(f.name))
    val entries     = handlers ::: exported ::: destructors
    val roots       = List(program.main, program.vals, program.vtables, program.entry, entries)
    val live     = reachedFrom(roots, program.funcs, program.vtables).calls ++ entries.map(_.name)

    program.copy(
      externs = program.externs.filter(e => live(e.name)),
      funcs = program.funcs.filter(f => live(f.name)),
    )
  }

  /** What a set of trees reaches: every `val` read and every function called, following each call
   * into the body it lands in.
   *
   * A plain reachability walk rather than a fixpoint. Recursion among the callees is fine — what is
   * being accumulated is a set, and a function already visited adds nothing a second time.
   */
  def reachedFrom(roots: List[Any], funcs: List[TFunc], vtables: List[TVtable]): Refs = {
    val byName = funcs.map(f => f.name -> f).toMap
    val vals   = mutable.HashSet.empty[String]
    val called = mutable.HashSet.empty[String]
    val queue  = mutable.Queue.empty[String]

    def take(r: Refs): Unit =
      vals ++= r.vals
      for c <- r.calls if called.add(c) do queue += c

    roots.foreach(r => take(summarize(r, vtables)))

    while queue.nonEmpty do for f <- byName.get(queue.dequeue()) do take(summarize(f, vtables))

    Refs(vals.toSet, called.toSet)
  }

  /** What one tree names, without following any of it: the `val`s read out of it and the functions
   * it can call.
   */
  case class Refs(vals: Set[String], calls: Set[String])

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

    // A `Type` is where the descent stops — it holds no expression, and a recursive one would
    // otherwise be walked forever. The one function name that lives inside a type rather than beside
    // it is a constrained subtype's `where` predicate, which is why the node checking against one
    // reads it out here instead of leaving it to the descent.
    def scan(x: Any): Unit = x match
      case _: Type            => ()
      case g: TGlobal         => vals += g.symbol
      case d: TDispatch       => calls += d.name
      case c: TCall           => calls += c.name; c.args.foreach(scan)
      // Taking a function's address is a use of it exactly as calling it is, and the only one whose
      // caller is not in this program: what happens to the address afterwards is C's business, so
      // there is nothing else that could keep the definition from being dropped.
      case a: TFuncAddr       => calls += a.name
      case p: TCallPtr        => scan(p.callee); p.args.foreach(scan)
      case s: TStructInvCheck => calls += s.invFn; scan(s.value)
      case r: TRecheck        => calls += r.invFn; scan(r.after); scan(r.recv)
      // A multi-assignment's arm carries its re-check as data rather than as a node, so the
      // predicate's name is a `String` the shape cannot tell from any other and has to be read out
      // here — exactly as `TRecheck`'s is, for the same reason.
      case w: TWrite =>
        w.check.foreach((recv, _, invFn) => { calls += invFn; scan(recv) })
        w.constraint.flatMap(_.predFn).foreach(calls += _)
        scan(w.place); scan(w.value); w.dispatch.foreach(scan)
      case c: TConstrainedCheck =>
        c.target.predFn.foreach(calls += _)
        scan(c.value)
      // A compound assignment and an increment carry their constraint as a `Type`, which the case
      // above skips, so the predicate they call is named here. **No program observes this today**,
      // and the reason is worth writing down rather than discovering twice: a value can only come to
      // have a `where`-carrying subtype through a site that checks it, so whatever produced the first
      // one has already named the predicate — measured by removing these two lines, which leaves the
      // suite green. They are here because the alternative to naming a function held as data is a
      // call to a definition that was pruned, and that failure is a link error rather than a test.
      case u: TUpdate =>
        u.check.flatMap(_.predFn).foreach(calls += _)
        scan(u.place); scan(u.value); u.dispatch.foreach(scan)
      case i: TIncDec =>
        i.check.flatMap(_.predFn).foreach(calls += _)
        scan(i.place)
      // The entry point names its `main` and the conversion that makes its arguments, neither of
      // which any tree calls: what calls them is the wrapper codegen lays down around them.
      case e: TEntry =>
        calls += e.func
        e.argsFn.foreach(calls += _)
        e.resultFn.foreach(calls += _)
      case s: TVSlot  => calls += s.target
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
}
