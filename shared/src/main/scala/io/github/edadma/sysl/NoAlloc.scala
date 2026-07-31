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

  /** Reports every construction that makes heap storage in a module that declared `no alloc`.
   *
   * The `main` statements are checked under the module of the file that carries them, since they are
   * that file's code however little they look like a declaration.
   */
  protected def checkNoAlloc(funcs: List[TFunc], vals: List[TVal], main: List[TStmt], mainModule: String)
      : Unit = {
    for f <- funcs if noAlloc(Modules.moduleOf(f.name)) do scan(f.body)
    for v <- vals if noAlloc(Modules.moduleOf(v.symbol)) do scan(v.init)
    if noAlloc(mainModule) then scan(main)
  }

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
