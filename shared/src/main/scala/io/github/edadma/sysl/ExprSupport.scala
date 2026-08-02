package io.github.edadma.sysl

/** The few things the expression traits ask of each other.
 *
 * `ExprAnalysis` holds the dispatch and the three traits beside it hold groups of forms, so anything
 * more than one of them needs has to sit *above* all of them — which is what this is. It is
 * deliberately small: a helper belongs with the form that uses it, and only a helper with two
 * callers in different groups ends up here.
 *
 * `analyzeValueAt` is declared and not defined, for the reason `AnalyzerBase` declares `analyzeExpr`
 * that way: a form that rewrites itself into another form — a qualified name folded into the one name
 * its module keys it under — has to re-enter the dispatch, and the dispatch is below this.
 */
trait ExprSupport extends SpecialForms with PatternAnalysis with StmtAnalysis {

  /** Analyzes an expression that has already been rewritten into another form, at the position the
   * form it came from had. `analyzeExpr` would set the rewritten node's own position, which a node
   * the parser never saw does not have.
   */
  protected def analyzeValueAt(expr: Expr, expected: Option[Type], discarded: Boolean = false): TExpr

  /** What an array form's elements should be analyzed as, given what the form itself is expected to
   * produce. A `string` is not on the list: its elements are bytes, but writing one is a validity
   * question rather than an arrangement of elements.
   */
  protected def elementWanted(want: Type): Option[Type] = want match
    case Type.Array(_, e) => Some(e)
    case Type.Slice(e, _) => Some(e)
    case _                => None

  /** A reference written as a chain of plain names: `std.fs.read` is `["std", "fs", "read"]`.
   * `None` for anything else, since a chain interrupted by a call or a subscript is a value being
   * read from rather than a path being named.
   */
  protected def chain(e: Expr): Option[List[String]] = e match
    case Ident(n)    => Some(List(n))
    case Field(r, f) => chain(r).map(_ :+ f)
    case _           => None

  /** A reference reaching into a module, rewritten with the module folded into the name it
   * qualifies — `std.fs.read` becomes the one name `std.fs`'s `read` is keyed under — or `None`
   * where the chain names no module.
   *
   * That rewrite is the whole of what qualified access needs: what is left is `read(…)`,
   * `Point(…)`, `Shape.Circle(…)` — the ordinary forms, resolved by the cases that already handle
   * them, against tables that were keyed this way to begin with.
   *
   * Two rules decide it, and both are `13 §3`'s. **A local binding shadows a module name**, so a
   * chain whose head is bound to a value is a field read and nothing else — which is why this
   * cannot be a pre-pass over the tree and has to be asked where the scopes are. And the
   * **longest** module prefix wins, so a module `a.b` is reached as one rather than as `a`'s `b`.
   *
   * A head that names no module is read as an import of one, which is what makes the `fs` of
   * `import std.fs` a prefix everywhere a written path is.
   */
  protected def throughModule(e: Expr): Option[Expr] =
    for
      written <- chain(e) if written.length > 1 && lookupOpt(written.head).isEmpty
      path = if namesModule(written.head) then written
             else importedModule(written.head).fold(written)(_.split('.').toList ::: written.tail)
      k <- (path.length - 1).to(1, -1).find(n => moduleNames(path.take(n).mkString(".")))
    yield
      val module = path.take(k).mkString(".")
      val rest   = path.drop(k)

      // The key this builds is spelled the way the compiler spells its own references, so resolving
      // it says nothing about which module wrote it — but *this* is a path a file wrote, in the
      // terms of the body being read, so the dependency it makes is recorded here (`13 §6`).
      dependsOn(module)
      rest.tail.foldLeft[Expr](Ident(Modules.qualify(module, rest.head)))((acc, n) => Field(acc, n))
        .setPos(e.pos)

  /** Whether a place bottoms out in something bound by a `val` — either a module-level one or a
   * local. Reaching *into* one keeps the property: an element of a read-only array is read-only,
   * and so is a field of a read-only struct.
   */
  protected def readOnly(t: TExpr): Boolean = t match
    // An `extern` variable is the one global that is not one: the storage belongs to whoever laid it
    // down, and reaching it is the foreign seam rather than a promise this program made (`12 §1`).
    case g: TGlobal         => !g.writable
    // A `ref` is read-only exactly when the storage it found is (`03 § ref`), which is what lets the
    // property survive being given a shorter name: reaching into a `val` keeps it, and a ref is a
    // way of reaching in.
    case TLoad(name, _)     => readOnlyLocals(name) || refPlaces.get(name).exists(readOnly)
    case TField(recv, _, _) => readOnly(recv)
    // Only where the elements are the receiver's own storage. A slice's are somebody else's, and
    // whose they are is exactly what a slice does not record.
    case TIndex(recv, _, _) =>
      recv.ty match
        case _: Type.View => false
        // A `val *T` fixes the address, not what is at it — exactly as `*p = v` through one is
        // already allowed. C's `T *const p` reads the same way.
        case _: Type.Ptr  => false
        case _            => readOnly(recv)
    case _ => false
}
