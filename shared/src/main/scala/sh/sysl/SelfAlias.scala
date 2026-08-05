package sh.sysl

/** Whether a `*self` method lets a pointer into its receiver's own storage outlive the call.
 *
 * This is the half of the aliasing rule that a call site cannot see. `o.a.bump()` is allowed because
 * the call site still knows the whole place and re-runs `Outer`'s clause the moment `bump` returns
 * (`Aliasing.recheckAfter`) — and that is sound exactly as long as `bump` is finished with the
 * receiver when it returns. A `bump` that squirrels `&self.n` away somewhere the caller can reach
 * later would be handing out the severed alias `&o.a.n` by a route no `&` in the caller's source
 * spells, which is the one thing refusing `&` at the caller cannot catch.
 *
 * Two refinements keep the rule to that and no wider, and both were forced by library code that has
 * every right to exist:
 *
 *   - **`*self` only**, not every pointer parameter. A function that stores a `*T` argument in the
 *     value it returns — a reader handed to an iterator — is the ordinary way to build a cursor, and
 *     nothing about it severs a promise, because the caller wrote the `&` and the caller was checked.
 *   - **Inline storage only.** A field that is a reference or a view already points somewhere else,
 *     and storage on the other side of that hop is not the receiver's to lose: `&self.bytes[0]`,
 *     where `bytes` is a `[]u8`, escapes the buffer's storage, which outlives every frame anyway.
 *
 * The check is **function-local by construction**, which is what keeps it cheap and predictable, and
 * it is honest about where that stops: a pointer handed to a third function that stores it is out of
 * reach here, exactly as `03` says every guarantee about a `*T` is.
 */
object SelfAlias {

  /** Every way this body lets a pointer into `self`'s inline storage outlive the call, each with the
   * expression to report it against.
   */
  def check(f: TFunc): List[(String, Option[Pos])] = {

    /** The locals that may hold such a pointer. `self` seeds it: the receiver is one. */
    var confined = Set("self")

    /** The place each `ref` in this body names (`03 § ref`).
     *
     * A ref is a second name for storage, so `&r` is `&<the place r names>` — and the question this
     * pass asks of an address is *structural*, "is this a chain of steps from `*self`", which a bare
     * name cannot answer for itself. Without the map the leak is simply invisible: the same pointer,
     * out of the same method, written one line longer.
     */
    val refOf = {
      val found = scala.collection.mutable.Map.empty[String, TExpr]

      TreeWalk.forEachStmt(f.body.stmts) { case TRefDecl(n, _, place) => found(n) = place }
      found.toMap
    }

    /** Whether a value carries a pointer into the receiver's inline storage — directly, or inside
     * something built around one. A struct made of it, a branch that yields it and a box holding it
     * are all the same pointer with a wrapper on.
     */
    def carries(e: TExpr): Boolean = e match
      case TLoad(n, _)          => confined(n)
      case TAddrOf(place, _)    => ownStorage(place)
      case TStructNew(_, args)  => args.exists(carries)
      case TEnumNew(_, _, args) => args.exists(carries)
      case TArrayLit(elems, _)  => elems.exists(carries)
      case TArrayFill(v, _)     => carries(v)
      case TBufLit(elems, _)    => elems.exists(carries)
      case TBufFill(v, _, _)    => carries(v)
      case TBox(v, _)           => carries(v)
      case TCast(v, _)          => carries(v)
      case TStore(_, v, _)      => carries(v)
      case TRecheck(a, _, _, _) => carries(a)
      case TSeq(exprs)          => exprs.lastOption.exists(carries)
      case TIf(_, t, el, _)     => t.result.exists(carries) || el.flatMap(_.result).exists(carries)
      case TMatch(_, arms, _)   => arms.exists(_.body.result.exists(carries))
      // A loop's value comes from its `break`s and its `else`, so a pointer carried out of the loop
      // is carried out of whatever the loop's value is used for.
      case w: TWhile            => loopCarries(w.body, w.elseBlock)
      case d: TDoWhile          => loopCarries(d.body, d.elseBlock)
      case l: TLoop             => loopCarries(l.body, None)
      case f: TFor              => loopCarries(f.body, f.elseBlock)
      case e: TForEach          => loopCarries(e.body, e.elseBlock)
      case i: TIterate          => loopCarries(i.body, i.elseBlock)
      case c: TCFor             => loopCarries(c.body, c.elseBlock)
      case _                    => false

    def loopCarries(body: List[TStmt], elseBlock: Option[TBlock]): Boolean =
      TreeWalk.ownBreakValues(body).exists(carries) || elseBlock.flatMap(_.result).exists(carries)

    /** Whether a place *is* the receiver's inline storage: a chain of field and element steps from
     * `*self`, with nothing on the way that points elsewhere.
     */
    def ownStorage(p: TExpr): Boolean = p match
      case TDeref(ptr, _)  => carries(ptr)
      case TField(r, _, _) => stepsInto(r)
      case TIndex(r, _, _) => stepsInto(r)
      case TLoad(n, _) if refOf.contains(n) => ownStorage(refOf(n))
      case _               => false

    /** Whether stepping *through* this receiver stays in the same object. A reference, a view or a
     * weak reference is where the receiver's own storage ends and somebody else's begins.
     */
    def stepsInto(r: TExpr): Boolean = r.ty match
      case _: Type.View | _: Type.Ref | _: Type.Weak | _: Type.Ptr => false
      case _                                                       => ownStorage(r)

    // Which locals hold one is a fixpoint, because a body may pass the pointer along a chain of them
    // before letting it out. Nothing shrinks, so it settles.
    var changed = true

    while changed do
      changed = false

      def bind(n: String, v: TExpr): Unit =
        if !confined(n) && carries(v) then
          confined += n
          changed = true

      TreeWalk.forEachStmt(f.body.stmts) {
        case TVarDecl(n, _, init)                  => bind(n, init)
        // A `ref` into the receiver carries the receiver's storage under a new name, so the name
        // has to be tracked as one that does — otherwise `&r` would be the leak this pass exists to
        // catch, written one line longer and unseen.
        case TRefDecl(n, _, place)                 => bind(n, place)
        case TExprStmt(TStore(TLoad(n, _), v, _))  => bind(n, v)
        case TMultiAssign(writes) =>
          for w <- writes do
            w.place match
              case TLoad(n, _) => bind(n, w.value)
              case _           =>
        case _ =>
      }

    var found = List.empty[(String, Option[Pos])]

    def gotOut(at: TExpr, how: String): Unit =
      if carries(at) then
        found :+= (
          s"a '*self' method may not let a pointer into the receiver's own storage outlive the call, " +
            s"and this one $how — the receiver may be a field of a struct whose invariant reads it, and " +
            "a pointer that gets out is somewhere to write that names no such struct. Hand back a copy " +
            "of the value, or an index into it, and let the caller reach the storage through the " +
            "receiver it already has",
          at.pos,
        )

    /** A store into anything but a plain local of this body, wherever it is written. */
    def written(e: TExpr): Unit = {
      e match
        case TStore(place, v, _) =>
          place match
            case _: TLoad => ()
            case _        => gotOut(v, "is stored somewhere the call does not own")
        case TBox(v, _) => gotOut(v, "is put on the heap")
        case _          =>

      TreeWalk.children(e).foreach(written)
    }

    TreeWalk.forEachStmt(f.body.stmts) {
      case TReturn(Some(v))  => gotOut(v, "is returned"); written(v)
      case TExprStmt(e)      => written(e)
      case TVarDecl(_, _, e) => written(e)
      case TRefDecl(_, _, e) => written(e)
      case TBreak(Some(v), _) => written(v)
      case TMultiAssign(writes) =>
        for w <- writes do
          written(w.value)
          w.place match
            case _: TLoad => ()
            case _        => gotOut(w.value, "is stored somewhere the call does not own")
      case _ =>
    }

    f.body.result.foreach { v => gotOut(v, "is returned"); written(v) }
    found
  }
}
