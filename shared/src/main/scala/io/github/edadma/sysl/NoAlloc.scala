package io.github.edadma.sysl

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

  /** Whether `module` gave the allocator up. */
  protected def noAlloc(module: String): Boolean =
    moduleNarrows.get(module).exists(_.contains(Capability.Alloc))

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
      scan(f.body)
      allocator.blame(f.body)
    for v <- vals if noAlloc(Modules.moduleOf(v.symbol)) do
      scan(v.init)
      allocator.blame(v.init)
    if noAlloc(mainModule) then
      scan(main)
      allocator.blame(main)
  }

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

    /** An allocating function this tree can arrive at, if there is one. */
    private def reached(x: Any): Option[String] =
      Reachability.reachedFrom(List(x), funcs, vtables).calls.find(direct)

    /** Reports the **smallest** sub-tree that still reaches an allocator, which is as close to the
     * call as the tree can put the caret: a body reaches one through some statement, that statement
     * through some expression, and the descent stops where no part of the node answers on its own.
     *
     * Descending rather than testing every node is what keeps this one reachability walk per level
     * instead of one per node.
     */
    def blame(x: Any): Unit =
      for who <- reached(x) do
        val site = narrow(x)

        recover(())(at(position(site))(err(s"this reaches '${Modules.show(who)}', which makes heap " +
          "storage, and this module declared 'no alloc' — an allocator-free module may only call " +
          "what is allocator-free itself")))

    private def narrow(x: Any): Any =
      parts(x).find(c => reached(c).isDefined).map(narrow).getOrElse(x)

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
  private def scan(x: Any): Unit = x match
    case _: Type => ()
    case e: TExpr if allocates(e).isDefined =>
      recover(())(at(e.pos)(err(s"${allocates(e).get} needs an allocator, and this module declared " +
        "'no alloc' — it may hold and release storage made elsewhere, and may make none of its own")))
    case xs: Iterable[?] => xs.foreach(scan)
    case p: Product      => p.productIterator.foreach(scan)
    case _               => ()
}
