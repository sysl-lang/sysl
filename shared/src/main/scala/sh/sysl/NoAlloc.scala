package sh.sysl

/** What a module that declared `no alloc` may not do: make heap storage
 * (`capabilities.md § What alloc gates, precisely`).
 *
 * **It is asked of the typed tree rather than of each construction as it is built**, and that is the
 * whole design here. The constructions that allocate are written down in a dozen places across the
 * analyzer — a value boxed by its context, a slice whose elements are counted, every operation that
 * builds a fresh `string` — and a guard at each of them is a list nobody can read and that a
 * thirteenth site would quietly not join. The typed tree has the opposite property: a node that
 * allocates is a node, so the list below is the whole answer and a new one either appears in it or
 * is visibly absent.
 *
 * What is refused is **making** storage, never holding it. A `&T` handed in is retained and released
 * with no allocator in sight, because every object carries its own deallocation hook (`03`) — so an
 * allocator-free module can take a reference, keep it in a field, and drop it, and only the moment
 * the object came into being is gated.
 *
 * A module's own declarations are what it is held to. A call into a module that allocates is a
 * question about the module *graph*, and is answered where the graph is.
 */
trait NoAlloc extends AnalyzerBase {

  /** Whether `module` is allocator-free — because it gave the allocator up, or because the target it
   * is being built for has none (`capabilities.md § Two levels`, `packages.md § 2`).
   *
   * The two arrive at the same place by design: a module's effective set is the target's
   * intersected with its own narrowing, so a target that provides no allocator makes every module
   * allocator-free without a clause being written anywhere. That is what a bare-metal build wants —
   * `no alloc` on every file of a kernel is ceremony, and forgetting it on one file is the bug the
   * clause existed to prevent.
   *
   * **The library is exempt from the target's half, and that is not a loophole.** Its files are
   * compiled into every program, so applying this to them would report the allocating half of the
   * standard module as a mistake in source the program's author did not write and cannot change.
   * What a program does with that half is still refused, one step later and in the right place: the
   * call into it is in the program's own body, and `Allocators.blame` lands the diagnostic there.
   */
  protected def noAlloc(module: String): Boolean =
    (!targetProvides(Capability.Heap) && !std.carries(module)) ||
      moduleNarrows.get(module).exists(_.contains(Capability.Heap))

  /** Reports every construction that makes heap storage in a module that declared `no alloc`, and
   * every call out of one that arrives somewhere that does.
   *
   * The `main` statements are checked under the module of the file that carries them, since they are
   * that file's code however little they look like a declaration.
   */
  protected def checkNoAlloc(
      funcs: List[TFunc],
      vals: List[TVal],
      vtables: List[TVtable],
      main: List[TStmt],
      mainModule: String,
  ): Unit = {
    // Lazily, because building it walks every body in the program: a compilation with no clause
    // anywhere — which is almost all of them — should pay nothing at all for this pass.
    lazy val allocator = new Allocators(funcs, vtables)

    for f <- funcs if noAlloc(Modules.moduleOf(f.name)) do
      val why = because(Modules.moduleOf(f.name))

      scan(f.body, why)
      allocator.blame(f.body, why)
    for v <- vals if noAlloc(Modules.moduleOf(v.symbol)); init <- v.init do
      val why = because(Modules.moduleOf(v.symbol))

      scan(init, why)
      allocator.blame(init, why)
    if noAlloc(mainModule) then
      scan(main, because(mainModule))
      allocator.blame(main, because(mainModule))
  }

  /** Which of the two made this module allocator-free, said the way the diagnostic needs it.
   *
   * The distinction is the whole reason it is carried rather than assumed: a reader told their
   * module "declared 'no alloc'" when no file of it says any such thing would go looking for a
   * clause that is not there. Where the target is what has no allocator, the thing to change is the
   * config or the target, and the message has to point at that instead.
   */
  private def because(module: String): String =
    if moduleNarrows.get(module).exists(_.contains(Capability.Heap)) then "this module declared '@no_alloc'"
    else s"'${target.name}' provides no allocator"

  /** Which functions make heap storage, and which trees reach one (`13 §4` — *propagation is over
   * the module graph*).
   *
   * **The diagnostic lands at the call rather than at the import**, and the reason is the standard
   * library: `sysl` is one module and is half allocator-free, so a rule stated over modules would
   * refuse every `no alloc` module that names anything at all — `print` and `from_utf8` are the same
   * module, and only one of them allocates. What `capabilities.md` asks for is that an
   * allocator-free module "can only import and call things that are themselves no-alloc-compatible",
   * and calls are what this is stated over.
   *
   * The reachable set is the one `Reachability` computes, which **over-approximates** where a call's
   * target is decided at run time: a method-table slot is answered with what every table for that
   * trait put there. That is the right direction to be wrong in — a refusal names a function the
   * program might really arrive at — and it is why the answer is cached per tree rather than
   * recomputed.
   */
  private class Allocators(funcs: List[TFunc], vtables: List[TVtable]) {

    /** The functions whose own bodies make heap storage. A library's do; a program's do wherever it
     * was allowed to write one.
     */
    private val direct: Set[String] = funcs.filter(f => firstAllocation(f.body).isDefined).map(_.name).toSet

    /** Every allocating function this tree can arrive at. */
    private def reached(x: Any): Set[String] =
      Reachability.reachedFrom(List(x), funcs, vtables).calls.filter(direct)

    /** Reports the **smallest** sub-tree that still reaches an allocator, which is as close to the
     * call as the tree can put the caret: a body reaches one through some statement, that statement
     * through some expression, and the descent stops where no part of the node answers on its own.
     *
     * **Every such sub-tree, not merely the first.** A body that calls two allocating functions has
     * two things wrong with it and two places to change, and a reader told about one of them fixes it
     * and is told about the next — which is a worse experience than the direct-allocation walk gives
     * for the same mistake one step nearer. So where several children still reach, the descent
     * branches into all of them rather than picking one.
     *
     * That is not the same as reporting every node: the descent still stops as soon as no part of a
     * node answers on its own, which is what keeps `str(a) + str(b)` reaching one allocator from
     * being a message per node on the way down.
     */
    def blame(x: Any, why: String): Unit =
      if reached(x).nonEmpty then
        parts(x).filter(c => reached(c).nonEmpty) match
          case Nil  => report(x, why)
          case kids => kids.foreach(blame(_, why))

    /** One site, naming the allocator it reaches. Where it reaches several, the name is the least of
     * them rather than whichever the set happened to yield first — the site is what the reader has to
     * change, and a diagnostic that varied between runs would be a poor thing to assert on.
     */
    private def report(site: Any, why: String): Unit =
      for who <- reached(site).toList.sorted.headOption do
        recover(())(at(position(site))(err(s"this reaches '${Modules.show(who)}', which makes heap " +
          s"storage, and $why — an allocator-free module may only call what is allocator-free itself")))

    private def parts(x: Any): List[Any] = x match
      case _: Type         => Nil
      case xs: Iterable[?] => xs.toList
      case p: Product      => p.productIterator.toList
      case _               => Nil

    /** Where the caret goes: the node's own position, or the first one under it that has one — a
     * statement wrapping an expression carries none of its own.
     */
    private def position(x: Any): Option[Pos] = x match
      case p: Positioned if p.pos.isDefined => p.pos
      case _                                => parts(x).flatMap(c => position(c).toList).headOption
  }

  /** The first construction under `x` that makes heap storage, wherever it is. */
  private def firstAllocation(x: Any): Option[TExpr] = x match
    case _: Type                            => None
    case e: TExpr if allocates(e).isDefined => Some(e)
    case xs: Iterable[?]                    => xs.iterator.flatMap(firstAllocation).nextOption()
    case p: Product                         => p.productIterator.flatMap(firstAllocation).nextOption()
    case _                                  => None

  /** What a node allocates, said the way a reader would say it, or nothing for a node that does not.
   *
   * `TDowngrade` is deliberately absent: a weak reference is a count inside the box the strong one
   * already made (`03 § What it costs`), so weakening allocates nothing. Making a `weak T` is gated
   * all the same, one step earlier — the `&T` it has to come from is `TBox`, and there is no other
   * route to one.
   */
  private def allocates(e: TExpr): Option[String] = e match
    case _: TBox                       => Some("a reference")
    case _: TBufLit | _: TBufFill      => Some("a slice with storage of its own")
    case _: TStr | _: TRender          => Some("the string a value renders as")
    case _: TFormat                    => Some("the string a formatted value renders as")
    case _: TFromBytes                 => Some("a string built from bytes")
    case TBinary(_, _, _, Type.Str)    => Some("the string two strings join into")
    case _                             => None

  /** Walks a tree, reporting the outermost allocation on each path and going no deeper into one.
   *
   * Stopping at the outermost is what keeps `str(a) + str(b)` from being three messages about one
   * line. What a reader has to change is the expression, and the expression is the node that was
   * reported; the pieces inside it go away with it.
   *
   * The descent is through the shape of the tree rather than a case per node, for the reason
   * `Reachability`'s is: a node added later is walked without anyone remembering to come back here.
   */
  private def scan(x: Any, why: String): Unit = x match
    case _: Type => ()
    case e: TExpr if allocates(e).isDefined =>
      recover(())(at(e.pos)(err(s"${allocates(e).get} needs an allocator, and $why — it may hold and " +
        "release storage made elsewhere, and may make none of its own")))
    case xs: Iterable[?] => xs.foreach(scan(_, why))
    case p: Product      => p.productIterator.foreach(scan(_, why))
    case _               => ()
}
