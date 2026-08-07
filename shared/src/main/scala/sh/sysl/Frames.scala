package sh.sysl

import scala.collection.mutable

/** What a function's `@reads` and `@writes` cover, and the three rules that hold it to them
  * (`17 §7`).
  *
  * A frame is what makes a call something other than an eraser. Given `f()` with nothing written
  * down, the weakest precondition of anything after the call is `true` — every module variable might
  * have changed — so a prover that meets an unannotated call has to forget everything it knew. The
  * annotation is the only thing that stops that, which is why this is the most load-bearing item in
  * the chapter and not merely a documentation aid.
  *
  * **Asked of the typed tree**, for `Purity`'s reason: what touches module storage is decided across
  * the analyzer, and a guard at each site is a list nobody can read. Storage reached by name is a
  * `TGlobal` node, so looking at nodes is the whole answer.
  *
  * The three rules, and what each is for:
  *
  *   1. **Body conformance** — a read of `V` needs `V ∈ R ∪ W`, a write needs `V ∈ W`. `W` is
  *      readable on purpose: `count += 1` is a read and a write of one variable, and a form that
  *      common should not have to be declared twice.
  *   2. **Call-site subset** — an annotated caller may not call something whose frame is wider than
  *      its own, since it would then be doing through the callee what it promised not to do.
  *   3. **Strict closure** — an annotated function may call only annotated or `@pure` functions, and
  *      may not call through a value, dispatch through a trait object, or contain an `asm` block.
  *      Each of those is a call site with no declaration to consult, and a frame that covered them
  *      would be a promise made about code nobody looked at.
  *
  * **Unannotated is not defaulted to anything, and that is what makes adoption possible.** A
  * function with no frame has effects nobody has written down and may call and be called by
  * anything, exactly as before this existed. Only the annotated are checked, so the discipline
  * climbs from the leaves at whatever pace its author sets rather than arriving as a flag day.
  */
trait Frames extends ConstFolding {

  private val reported = mutable.Set.empty[Pos]

  /** The written names of a frame as the symbols the body will be seen to touch.
    *
    * Resolution happens once, here, rather than at each comparison: a frame written inside a module
    * names `count` and the body's `TGlobal` carries the qualified key, and comparing the two spellings
    * anywhere else would be comparing a name against a symbol.
    *
    * A name that resolves to nothing is refused rather than dropped. A frame is a promise, and one
    * quietly missing a name it could not find is a promise about less than it appears to cover —
    * which is worse than no promise, because a reader trusts it.
    */
  protected def frameSymbols(names: Option[List[String]], word: String): Option[Set[String]] =
    names.map(_.map { n =>
      globalKey(n).getOrElse(
        err(s"'@$word' names '$n', which is not module-level storage — a frame covers a module's " +
          "own 'val' and 'var' declarations, and a local or a parameter is not something a caller " +
          "could observe across the call")
      )
    }.toSet)

  /** Holds every annotated function to its frame. */
  protected def checkFrames(funcs: List[TFunc], externs: List[TExtern]): Unit = {
    val framed  = funcs.filter(f => f.reads.isDefined || f.writes.isDefined)
    val byName  = funcs.map(f => f.name -> f).toMap
    val pure    = funcs.filter(_.pure).map(_.name).toSet
    val foreign = externs.map(_.name).toSet

    reported.clear()

    for f <- framed do
      val w = f.writes.getOrElse(Set.empty)
      val r = f.reads.getOrElse(Set.empty) ++ w

      walk(f.body, r, w, byName, pure, foreign, None)
      // The clauses run on every call, so they are the function's effects as much as its body is.
      // `17 §7` says reads inside them count, which is the same rule `Purity` applies for the same
      // reason: an `ensure` that read a module variable would be a frame that did not cover it.
      f.requires.foreach((c, _) => walk(c, r, w, byName, pure, foreign, None))
      f.ensures.foreach((c, _) => walk(c, r, w, byName, pure, foreign, None))
      f.variant.foreach(walk(_, r, w, byName, pure, foreign, None))
  }

  /** The module storage a place ultimately names, or nothing where the path never reaches any.
    *
    * A write to *any part* of a global is a write of that global: after `buffer[i] = b` the variable
    * `buffer` holds something different, and a prover asking whether `buffer` changed across the
    * call needs the answer yes. Stopping at the root is what makes that true whatever the path is —
    * an element, a field, a field of an element.
    *
    * The walk stops at a `TDeref`, which is the one hop that leaves the storage the path started
    * from: writing through a pointer read out of a global changes what the pointer addresses, not
    * the global, and a frame naming the global would be describing the wrong storage.
    */
  private def root(place: TExpr): Option[String] = place match
    case TGlobal(symbol, _, _)  => Some(symbol)
    case _: TDeref              => None
    case TField(r, _, _)        => root(r)
    case TIndex(r, _, _)        => root(r)
    case TSlice(b, _, _, _, _)  => root(b)
    case _                      => None

  /** Walks a body, reporting each touch of module storage the frame does not cover.
    *
    * A place is handled before the generic descent reaches it, because the same `TGlobal` node means
    * a read in one position and a write in another and the tree does not say which on the node
    * itself. Everything *inside* a place — the index of `buffer[pos]`, the receiver of a field — is
    * still walked as ordinary reads, which is how `buffer[pos] = b` is seen to read `pos`.
    */
  private def walk(
      x: Any,
      r: Set[String],
      w: Set[String],
      byName: Map[String, TFunc],
      pure: Set[String],
      foreign: Set[String],
      where: Option[Pos],
  ): Unit = {
    val here = x match
      case p: Positioned if p.pos.isDefined => p.pos
      case _                                => where

    def on(y: Any): Unit = walk(y, r, w, byName, pure, foreign, here)

    /** The parts of a place that are themselves read — everything but the storage being named. */
    def within(place: TExpr): Unit = place match
      case TGlobal(_, _, _)      => ()
      case TField(rc, _, _)      => within(rc)
      case TIndex(rc, i, _)      => within(rc); on(i)
      case TSlice(b, lo, hi, _, _) => within(b); on(lo); on(hi)
      case other                 => on(other)

    def wrote(place: TExpr): Unit = root(place) match
      case Some(v) if !w(v) =>
        report(here, s"writes '${Modules.show(v)}', which its '@writes' does not name")
      case _ => ()

    def read(v: String): Unit =
      if !r(v) then
        report(here, s"reads '${Modules.show(v)}', which neither its '@reads' nor its '@writes' names")

    x match
      case _: Type => ()

      case _: TAsm =>
        report(here, "may contain no 'asm' block — what the instructions do is outside anything a " +
          "frame could describe")

      // A compound update and an increment read the place as well as writing it, which is exactly
      // why `W` is readable: `count += 1` names one variable and needs one annotation.
      case TStore(place, value, _)         => wrote(place); within(place); on(value)
      case TUpdate(place, _, value, _, _, _) =>
        wrote(place); root(place).foreach(read); within(place); on(value)
      case TIncDec(place, _, _, _, _) =>
        wrote(place); root(place).foreach(read); within(place)

      case TGlobal(symbol, _, _) => read(symbol)

      case TCall(name, args, _, _) =>
        callee(name, r, w, byName, pure, foreign, here)
        args.foreach(on)

      case _: TCallPtr =>
        report(here, "calls through a value, which names no declaration whose frame could be checked")
      case _: TVCall =>
        report(here, "dispatches through a trait object, where which body runs is settled while the " +
          "program is, so no frame can be read off the call")

      case xs: Iterable[?] => xs.foreach(on)
      case p: Product      => p.productIterator.foreach(on)
      case _               => ()
  }

  /** Rules 2 and 3 at one call: the callee has a frame, and its frame fits inside this one. */
  private def callee(
      name: String,
      r: Set[String],
      w: Set[String],
      byName: Map[String, TFunc],
      pure: Set[String],
      foreign: Set[String],
      here: Option[Pos],
  ): Unit =
    // `@pure` is `@reads() @writes()` and is therefore inside every frame there is, so it passes
    // without anything to compare — which is what lets a framed function call the library.
    if pure(name) then ()
    else if Closures.symbol(name) then
      report(here, "calls through a closure, which names no declaration whose frame could be checked")
    else if foreign(name) then
      report(here, s"calls '${Modules.show(name)}', which is an 'extern' and says nothing about what " +
        "it touches")
    else
      byName.get(name) match
        case None => ()
        case Some(g) if g.reads.isEmpty && g.writes.isEmpty =>
          report(here, s"calls '${Modules.show(name)}', which has no '@reads' or '@writes' — an " +
            "annotated function may call only annotated or '@pure' ones, since a frame cannot cover " +
            "what a callee never said")
        case Some(g) =>
          val gw = g.writes.getOrElse(Set.empty)
          val gr = g.reads.getOrElse(Set.empty) ++ gw

          // The offending *name* is what the diagnostic reports, per `17 §7` — a reader fixing this
          // needs to know which variable escaped the frame, not that a comparison failed.
          gw.find(!w(_)).foreach(v =>
            report(here, s"calls '${Modules.show(name)}', which writes '${Modules.show(v)}' — a name " +
              "this function's '@writes' does not have")
          )
          gr.find(!r(_)).foreach(v =>
            report(here, s"calls '${Modules.show(name)}', which reads '${Modules.show(v)}' — a name " +
              "neither this function's '@reads' nor its '@writes' has")
          )

  private def report(where: Option[Pos], what: String): Unit =
    if where.forall(reported.add) then recover(())(at(where)(err(s"a function with a frame $what")))
}
