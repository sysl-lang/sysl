package io.github.edadma.sysl

import scala.collection.mutable

/** Escape analysis for slices, as specified in `05-escape-analysis.md`.
 *
 * A slice keeps its buffer alive through its owner word — except when there is no owner,
 * which is exactly the case of a slice of an array this frame owns. Such a view is valid
 * until the frame returns and no longer, so this pass finds the ones that could be reached
 * afterwards and rejects them.
 *
 * The programmer writes nothing. Two things make that affordable: the answer for a whole
 * function collapses to one bit per parameter — *does the callee let this argument outlive the
 * call* — and monomorphization means every body is available to be asked. The bits are
 * computed bottom-up over the call graph and iterated to a fixpoint, so a recursive function
 * converges on the truth rather than on the conservative answer.
 */
object Escape {

  /** Which local arrays each body must allocate on the heap rather than in its frame.
   *
   * An array is in here when a view of it gets out of the frame that declared it, and the answer
   * to that is promotion rather than a diagnostic (`05 § What happens when a slice escapes`): the
   * storage becomes an ARC buffer, the slice's owner points at it, and it lives exactly as long as
   * the last view of it. Only arrays that are **both** sliced and escaped are here; one that is
   * merely read, or whose views stay in the frame, keeps its stack slot.
   *
   * Keyed by function name, with `main`'s statements separate because they are not a function.
   */
  case class Promotions(
      byFunc: Map[String, Set[String]],
      inMain: Set[String],
      /** One line per promotion, in source order, for `--explain-escapes` (`05 § Promotion is
       * silent, not hidden`). Silent promotion earns the objection that an allocation appears which
       * nothing in the source asked for, and the answer is discoverability rather than ceremony:
       * the common case costs no reading, and "why did this allocate?" always has an answer.
       */
      explanations: List[String] = Nil,
  ) {
    def apply(func: Option[String]): Set[String] =
      func.fold(inMain)(byFunc.getOrElse(_, Set.empty))
  }

  object Promotions {
    val none: Promotions = Promotions(Map.empty, Set.empty)
  }

  /** Analyzes a whole program: either the escapes that are *not* promotable, rendered as one
   * report, or the promotions to make.
   */
  def check(program: TProgram): Either[String, Promotions] = new Escape(program).run()
}

private class Escape(program: TProgram) {

  private val funcs = program.funcs.map(f => f.name -> f).toMap

  /** For each function, which of its slice-typed parameters it lets outlive the call. Starts
   * optimistic — nothing is kept — and grows until it stops changing, which is what makes a
   * recursive function converge on its real behaviour.
   */
  private val keeps = mutable.HashMap.empty[(String, Int), Boolean].withDefaultValue(false)

  /** A function whose body is not here keeps everything, since nothing can tell whether the
   * foreign side holds on to what it was handed.
   */
  private def kept(name: String, i: Int): Boolean = !funcs.contains(name) || keeps((name, i))

  private def run(): Either[String, Escape.Promotions] = {
    var changed = true
    while changed do
      changed = false
      for f <- program.funcs do
        for ((name, ty), i) <- f.params.zipWithIndex if carriesView(ty) do
          if !keeps((f.name, i)) && kepts(f, name) then
            keeps((f.name, i)) = true
            changed = true

    // With the summaries settled, the frame-backed views themselves are the question. Nothing
    // is seeded: a view that dies with the frame is one taken of an array the frame owns, and
    // *here* returning one is the case promotion exists for — the array is about to go away, so
    // the array moves to the heap rather than the program being refused.
    val bodies: List[(Option[String], List[TStmt], Option[TExpr])] =
      program.funcs.map(f => (Some(f.name), f.body.stmts, f.body.result)) :+ ((None, program.main, None))

    val walks =
      bodies.map { (who, stmts, result) =>
        val walk = new Walk(Map.empty, returningEscapes = true, localArrays(stmts))
        walk.seed(stmts, result)
        (who, walk)
      }

    val refused = borrowed ::: walks.flatMap(_._2.escape)

    if refused.nonEmpty then Left(Diagnostic.report(refused))
    else
      Right(
        Escape.Promotions(
          walks.collect { case (Some(n), w) if w.promoted.nonEmpty => n -> w.promoted.toSet }.toMap,
          walks.collectFirst { case (None, w) => w.promoted.toSet }.getOrElse(Set.empty),
          walks.flatMap(_._2.why.toList),
        ),
      )
  }

  /** A `Writer` is handed a **borrowed** view of the bytes to write, and that is the whole reason
   * the sink takes bytes rather than a string: a renderer writes into a buffer on its own stack and
   * passes a slice of it, which is what keeps rendering allocation-free (`14 §2`).
   *
   * Nothing in the type says "borrowed", so it is checked here instead of trusted. An
   * implementation that lets those bytes outlive the call is rejected, which is what licenses the
   * call site below to pass a frame-backed slice through a `Writer` at all.
   */
  private def borrowed: List[String] = {
    // Both flavours of a type's table name the same implementation, so the offender is named once
    // however many ways the program erased it.
    val offenders =
      for
        vt   <- program.vtables if vt.traitName == Library.key("Writer")
        slot <- vt.slots.headOption.toList if keeps((slot.target, 1))
      yield slot.target

    offenders.distinct.map { name =>
      Diagnostic.render(
        // The trait is named by its key, never spelled: a program with a `Writer` of its own reaches
        // the library's only by path, and a message that spells it plainly names the wrong trait to
        // the one reader who has to tell them apart.
        s"'$name' keeps the bytes it is written, but a '${Modules.show(Library.key("Writer"))}' " +
          "borrows them for the call — they may be a view of the caller's stack, so copy what the " +
          "writer needs into storage of its own",
        None,
      )
    }
  }

  /** Whether a trait object's methods borrow what they are passed rather than possibly keeping it.
   * `Writer` is the one trait that says so, and the check above is what makes it true.
   */
  private def borrows(ty: Type): Boolean =
    Type.erasedTrait(ty).exists(_.name == Library.key("Writer"))

  /** Whether a value of this type could carry a view of somebody's elements. */
  private def carriesView(t: Type): Boolean = t match
    case _: Type.View        => true
    case Type.Array(_, elem) => carriesView(elem)
    case s: Type.Struct      => s.fields.exists(f => carriesView(f._2))
    case e: Type.Enum        => e.variants.exists(_.fields.exists(f => carriesView(f._2)))
    case _                   => false

  /** Whether a function lets a parameter outlive the *call* — which returning a view of it
   * does not. A returned view goes up one frame, where the storage it names is still alive;
   * that is the difference `05` draws between keeping a thing and viewing it.
   */
  private def kepts(f: TFunc, param: String): Boolean = {
    val walk = new Walk(Map(param -> View.unnamed), returningEscapes = false)
    walk.seed(f.body.stmts, f.body.result)
    walk.gotOut
  }

  /** What frame-owned storage a value may view: the local arrays it roots at by name, and whether
   * it also views frame storage this pass cannot name.
   *
   * The distinction is what decides promotion from diagnostic. A view rooted at a plain local array
   * has somewhere to move the storage to; one rooted at a field of a local struct, or at an array
   * parameter the caller passed by value, does not — promoting the first would be `05 § Deferred`'s
   * unspecified "promotion of aggregates", and the second is storage this frame was handed rather
   * than storage it made.
   */
  private case class View(named: Set[String], anonymous: Boolean) {
    def nonEmpty: Boolean  = named.nonEmpty || anonymous
    def ++(o: View): View  = View(named ++ o.named, anonymous || o.anonymous)
  }

  private object View {
    val none: View                = View(Set.empty, anonymous = false)
    val unnamed: View             = View(Set.empty, anonymous = true)
    def of(name: String): View    = View(Set(name), anonymous = false)
    def any(vs: Iterable[View]): View = vs.foldLeft(none)(_ ++ _)
  }

  /** One function's worth of tracking: which of its locals may hold a view that dies with the
   * frame, which arrays those views root at, and the first escape that has nowhere to be promoted.
   */
  private class Walk(seeds: Map[String, View], returningEscapes: Boolean, locals: Set[String] = Set.empty) {

    private var confined       = seeds
    var escape: Option[String] = None

    /** The arrays this body must allocate on the heap. */
    val promoted = mutable.Set.empty[String]

    /** Why each of them moved, in the order the escapes were found. */
    val why = mutable.ListBuffer.empty[String]

    /** Whether any confined view left the frame at all, promotable or not. This is what a parameter
     * summary asks — "does the callee let this outlive the call" is a yes/no, and the roots that
     * answer promotion are not its question.
     */
    var gotOut = false

    /** Whether an expression may carry a view the frame outlives. A call is the conservative
     * case: a result that may view any argument is treated as viewing all of them, which costs
     * precision only for a function that both takes a stack-backed slice and returns an
     * unrelated fresh one.
     */
    private def viewsFrame(e: TExpr): Boolean = views(e).nonEmpty

    /** Which frame-owned storage a value may view. Answering with the *roots* rather than with a
     * yes/no is the whole of what promotion needed from this pass: the escape sites already knew
     * that something got out, and what they could not say was which array to move.
     */
    private def views(e: TExpr): View = if !carriesView(e.ty) then View.none else viewsValue(e)

    private def viewsValue(e: TExpr): View = e match
      case TSlice(base, _, _, _, _) =>
        base.ty match
          // A view of a `*T` region is the programmer's problem, like every `*T` — it is
          // outside this analysis exactly as it is outside every other guarantee. Storage inside a
          // counted object is not the frame's either: it is on the heap already, and the view names
          // the box that holds it as its owner, so it outlives this body by construction.
          case _: Type.Array => if viaPointer(base) || viaReference(base) then View.none else arrayRoot(base)
          case _             => views(base)
      case TLoad(name, _)       => confined.getOrElse(name, View.none)
      case TCall(_, args, _, _)    => View.any(args.map(views))
      case TVCall(_, _, args, _, _) => View.any(args.map(views))
      case TStructNew(_, args)  => View.any(args.map(views))
      case TStructInvCheck(v, _, _) => views(v)
      case TCheckedStore(store, _, _, _) => views(store)
      case TEnumNew(_, _, args) => View.any(args.map(views))
      case TArrayLit(elems, _)  => View.any(elems.map(views))
      case TArrayFill(v, _)     => views(v)
      // The storage a buffer form makes is its own and outlives every frame, so the view it yields
      // is never the frame's — but what it was filled *with* may still be, which is the same rule
      // the two array forms above follow and the same route out.
      case TBufLit(elems, _)    => View.any(elems.map(views))
      case TBufFill(v, _, _)    => views(v)
      case TField(r, _, _)      => views(r)
      case TIndex(r, _, _)      => views(r)
      case TBytes(r)            => views(r)
      // `str(s)` on a string is the identity, so it inherits its argument's view; on any other
      // type it allocates a fresh buffer, and that argument carries no view for `views` to find,
      // so the one rule covers both.
      case TStr(a)              => views(a)
      case TStore(_, v, _)      => views(v)
      case TIf(_, t, e, _)      => blockValue(t) ++ View.any(e.map(blockValue))
      case TMatch(_, arms, _)   => View.any(arms.map(a => blockValue(a.body)))
      // A loop's value comes from its `break`s and its `else`, so it views the frame when any of
      // those do — a `break` of a frame-backed slice out of the loop is the same escape route as
      // returning one.
      case w: TWhile            => loopViews(w.body, w.elseBlock)
      case f: TFor              => loopViews(f.body, f.elseBlock)
      case e: TForEach          => loopViews(e.body, e.elseBlock)
      case i: TIterate          => loopViews(i.body, i.elseBlock)
      case _                    => View.none

    /** The array a slice's base names, when that is a local of this body.
     *
     * An **index** step is walked through: an element of a local array of arrays is part of that
     * array's storage, so moving the whole thing moves the element with it. A **field** step is
     * not, because the storage belongs to a struct and moving it would be `05 § Deferred`'s
     * unspecified "promotion of aggregates" — the choice between moving the field alone and moving
     * the struct has not been made. An array the caller passed **by value** is storage this frame
     * was handed rather than storage it made, so it is not this body's to move either. Both of
     * those come back unnamed and are reported as they always were.
     */
    private def arrayRoot(base: TExpr): View = base match
      case TLoad(name, _) if locals(name) => View.of(name)
      case TIndex(r, _, _)                => arrayRoot(r)
      case _                              => View.unnamed

    private def blockValue(b: TBlock): View = View.any(b.result.map(views))

    private def loopViews(body: List[TStmt], elseBlock: Option[TBlock]): View =
      View.any(ownBreakValues(body).map(views)) ++ View.any(elseBlock.map(blockValue))

    /** Whether the storage an array place names is reached through a raw pointer, and so is not
     * this frame's to lose. The question is about the *root* of the place rather than about its
     * last step: `p.table[i].bytes` is as much the caller's storage as `*p` is, and stopping at the
     * dereference would call a field of somebody else's struct a local array.
     */
    private def viaPointer(e: TExpr): Boolean = e match
      case TDeref(operand, _) => operand.ty.isInstanceOf[Type.Ptr]
      case TField(r, _, _)    => viaPointer(r)
      case TIndex(r, _, _)    => viaPointer(r)
      case _                  => false

    /** Whether the storage an array place names lives inside a **counted object** — a fixed array
     * that is a field of a `&Struct`, or an element of one.
     *
     * Such storage is on the heap already, so there is nothing to promote and nothing to refuse:
     * the view is built with the box as its owner and takes a count of it, exactly as a view of a
     * `&[N]T` does. The question is about the root for the same reason `viaPointer`'s is — what
     * matters is what the walk started from, not what its last step was.
     */
    private def viaReference(e: TExpr): Boolean = e match
      case TDeref(operand, _) => operand.ty.isInstanceOf[Type.Ref]
      case TField(r, _, _)    => viaReference(r)
      case TIndex(r, _, _)    => viaReference(r)
      case _                  => false

    /** Propagates through the function's own locals to a fixpoint, then looks for the places a
     * confined view could get out.
     */
    def seed(stmts: List[TStmt], result: Option[TExpr]): Unit = {
      var changed = true
      while changed do
        changed = false

        def bind(name: String, v: View): Unit =
          val had = confined.getOrElse(name, View.none)
          val now = had ++ v
          if now != had then
            confined += name -> now
            changed = true

        forEachStmt(stmts) {
          case TVarDecl(name, _, init)                  => bind(name, views(init))
          case TExprStmt(TStore(TLoad(name, _), v, _))  => bind(name, views(v))
          // A multi-assignment's arms are stores, and one landing in a plain local binds that local
          // to what it was given for the same reason a single one does.
          case TMultiAssign(writes) =>
            for w <- writes do
              w.place match
                case TLoad(name, _) => bind(name, views(w.value))
                case _              =>
          case _                                        =>
        }

      check(stmts)
      result.foreach(returned)
    }

    private def check(stmts: List[TStmt]): Unit = forEachStmt(stmts) {
      case TReturn(Some(v))  => returned(v)
      case TVarDecl(_, _, e) => escaping(e)
      case TExprStmt(e)      => escaping(e)
      // Each arm is a store, so each is walked as one: a view that lands anywhere but a plain local
      // of this body has left it, exactly as it would after a single `=`.
      case TMultiAssign(writes) =>
        for w <- writes do
          escaping(w.value)
          w.place match
            case _: TLoad => escaping(w.place)
            case _        => if viewsFrame(w.value) then gets_out(w.value, "is stored somewhere the frame does not own")
      // A `break value` carries the value out of the loop; walk it for the escape sites it may
      // contain. Whether it then leaves the frame is decided where the loop's own value is used.
      case TBreak(Some(v), _)   => escaping(v)
      case _                 =>
    }

    private def returned(v: TExpr): Unit =
      if returningEscapes && viewsFrame(v) then gets_out(v, "is returned")
      else escaping(v)

    /** Walks an expression for the places a confined view stops being confined: the heap, a
     * store into anything but a plain local of this function, and an argument the callee keeps.
     */
    private def escaping(e: TExpr): Unit = {
      e match
        case TBox(v, _) => if viewsFrame(v) then gets_out(v, "is put on the heap")

        case TStore(place, v, _) =>
          place match
            case _: TLoad => ()
            case _        => if viewsFrame(v) then gets_out(v, "is stored somewhere the frame does not own")

        case TCall(name, args, _, _) =>
          for (a, i) <- args.zipWithIndex do
            if viewsFrame(a) && kept(name, i) then gets_out(a, s"is passed to '$name', which holds on to it")

        // Which body a trait object's call reaches is decided at run time, so there is no one
        // summary to consult — the conservative answer is the only sound one, exactly as it is for
        // a function whose body this program does not have. A `Writer` is the exception, and only
        // because every implementation of one has been checked to borrow rather than keep.
        case TVCall(recv, _, args, _, _) if !borrows(recv.ty) =>
          for a <- args do
            if viewsFrame(a) then gets_out(a, "is passed through a trait object, which may hold on to it")

        case _ =>

      children(e).foreach(escaping)
    }

    /** A confined view has left the frame. Where it roots at arrays this body declared, they are
     * promoted — the storage moves to the heap and the program is unchanged otherwise (`05 § What
     * happens when a slice escapes`). Where it roots at storage that cannot be moved, the escape is
     * reported against the expression that causes it, so the caret lands on the slice that leaves
     * the frame rather than on the function as a whole.
     */
    private def gets_out(at: TExpr, how: String): Unit = {
      val v = views(at)

      for name <- v.named if !promoted(name) do
        why += Diagnostic.explain(s"'$name' is promoted to the heap, because this view of it $how", at.pos)

      promoted ++= v.named
      gotOut = true

      if v.anonymous && escape.isEmpty then
        escape = Some(
          Diagnostic.render(
            s"a slice of an array this frame owns $how, so it would outlive the array, and the " +
              "storage is not this body's to move — it is a field of a value, or an array a caller " +
              "passed by value. Declare it as a '[]T', which makes a buffer of its own and owns it, " +
              "or as a '&[N]T' where the length is fixed",
            at.pos,
          ),
        )
    }
  }

  /** The arrays a body declares for itself, which are the ones it may move to the heap. A `[N]T`
   * parameter is storage the caller laid out, so it is deliberately not here.
   */
  private def localArrays(stmts: List[TStmt]): Set[String] = {
    val found = mutable.Set.empty[String]

    forEachStmt(stmts) { case TVarDecl(name, _: Type.Array, _) => found += name }
    found.toSet
  }

  // --- tree walking ----------------------------------------------------------------------

  /** Applies `f` to every statement, including the ones nested in loop and branch bodies. Loops
   * are expressions now, so they are reached through the `blocks` an expression carries rather
   * than as statements of their own.
   */
  private def forEachStmt(stmts: List[TStmt])(f: PartialFunction[TStmt, Unit]): Unit =
    for s <- stmts do
      f.applyOrElse(s, (_: TStmt) => ())
      s match
        case TExprStmt(e)     => blocks(e).foreach(b => forEachStmt(b.stmts)(f))
        case TVarDecl(_, _, e) => blocks(e).foreach(b => forEachStmt(b.stmts)(f))
        case TReturn(Some(e))  => blocks(e).foreach(b => forEachStmt(b.stmts)(f))
        case TBreak(Some(e), _)   => blocks(e).foreach(b => forEachStmt(b.stmts)(f))
        case TMultiAssign(writes) =>
          for w <- writes; e <- List(w.place, w.value); b <- blocks(e) do forEachStmt(b.stmts)(f)
        case _                 =>

  /** The `break` values that belong to a loop with these body statements — those in its body but
   * not inside a nested loop, whose `break`s are that loop's. Walks through the branch bodies of
   * `if`/`match`, which do not introduce a new loop.
   */
  private def ownBreakValues(stmts: List[TStmt]): List[TExpr] = stmts.flatMap {
    case TBreak(Some(v), _)   => List(v)
    case TExprStmt(e)      => ownBreaksInExpr(e)
    case TVarDecl(_, _, e) => ownBreaksInExpr(e)
    case TReturn(Some(e))  => ownBreaksInExpr(e)
    case TMultiAssign(writes) => writes.flatMap(w => ownBreaksInExpr(w.place) ::: ownBreaksInExpr(w.value))
    case _                 => Nil
  }

  private def ownBreaksInExpr(e: TExpr): List[TExpr] = e match
    case _: TWhile | _: TLoop | _: TFor | _: TForEach | _: TCFor | _: TIterate => Nil
    case TIf(_, t, el, _)   => ownBreakValues(t.stmts) ::: el.toList.flatMap(b => ownBreakValues(b.stmts))
    case TMatch(_, arms, _) => arms.flatMap(a => ownBreakValues(a.body.stmts))
    case _                  => Nil

  /** Every block an expression contains, so a statement nested inside an `if`, a `match`, or a
   * loop used as a value is walked too. A loop's body is wrapped as a block; its `else` is one.
   */
  private def blocks(e: TExpr): List[TBlock] = e match
    case TIf(_, t, el, _)   => t :: el.toList ::: children(e).flatMap(blocks)
    case TMatch(_, arms, _) => arms.map(_.body) ::: children(e).flatMap(blocks)
    case TWhile(_, body, el, _)           => TBlock(body, None, Type.Unit) :: el.toList ::: children(e).flatMap(blocks)
    case TLoop(body, _)                   => TBlock(body, None, Type.Unit) :: children(e).flatMap(blocks)
    // The init and the step are statements of the loop's own scope, so they are walked as part of
    // the block its body makes rather than as expressions beside it.
    case TCFor(init, _, step, body, el, _) =>
      TBlock(init ::: body ::: step, None, Type.Unit) :: el.toList ::: children(e).flatMap(blocks)
    case TFor(_, _, _, _, _, body, el, _) => TBlock(body, None, Type.Unit) :: el.toList ::: children(e).flatMap(blocks)
    case TForEach(_, _, _, body, el, _)   => TBlock(body, None, Type.Unit) :: el.toList ::: children(e).flatMap(blocks)
    case TIterate(_, _, _, _, _, body, el, _) =>
      TBlock(body, None, Type.Unit) :: el.toList ::: children(e).flatMap(blocks)
    case _                  => children(e).flatMap(blocks)

  private def children(e: TExpr): List[TExpr] = e match
    case TBox(v, _)                 => List(v)
    case TCast(v, _)                => List(v)
    case TDeref(v, _)               => List(v)
    case TAddrOf(v, _)              => List(v)
    case TStore(p, v, _)            => List(p, v)
    case TUpdate(p, _, v, _, _, _)  => List(p, v)
    case TIncDec(p, _, _, _, _)     => List(p)
    case TBinary(_, l, r, _)        => List(l, r)
    case TUnary(_, v, _)            => List(v)
    case TLogical(_, l, r)          => List(l, r)
    case TCompare(ops, _)           => ops
    case TSeq(exprs)                => exprs
    case TCall(_, args, _, _)          => args
    // Which function a trait object's call reaches is a run-time word, so there is no parameter
    // list to ask whether an argument is kept — a callee that might keep anything is exactly what
    // `kept` already assumes of a name it does not recognise.
    case TVCall(r, _, args, _, _)   => r :: args
    case TErase(v, _, _)            => List(v)
    case TStructNew(_, args)        => args
    case TStructInvCheck(v, _, _)   => List(v)
    case TCheckedStore(store, recv, _, _) => List(store, recv)
    case TEnumNew(_, _, args)       => args
    case TEnumFromInt(v, _)         => List(v)
    case TEnumTry(v, _, _, _, _)    => List(v)
    case TDowngrade(v, _)           => List(v)
    case TUpgrade(v, _, _, _)       => List(v)
    case TArrayLit(elems, _)        => elems
    case TArrayFill(v, _)           => List(v)
    case TBufLit(elems, _)          => elems
    case TBufFill(v, n, _)          => List(v, n)
    case TIndex(r, i, _)            => List(r, i)
    case TLen(r)                    => List(r)
    case TBytes(r)                  => List(r)
    case TStr(a)                    => List(a)
    // The string it yields owns a copy, so nothing of the argument's storage survives in it — the
    // walk is here for the argument's own sake.
    case TFromBytes(a)              => List(a)
    case TFormat(a, _)              => List(a)
    // A render's result is a fresh string that owns its own bytes, so it views nothing here; what
    // is worth walking is the value and the specifier it hands the implementation.
    case TRender(v, _, s, _)        => List(v, s)
    case TSlice(b, lo, hi, _, _)    => b :: lo.toList ::: hi.toList
    // A `va_list` carries no view of this frame, and a value read out of the tail came from the
    // caller's — so walking these finds nothing, and they are here to keep the walk complete
    // rather than because anything can escape through them.
    case TVaStart(ap)               => List(ap)
    case TVaEnd(ap)                 => List(ap)
    case TVaArg(ap, _)              => List(ap)
    case TVaCopy(d, s)              => List(d, s)
    case TVaPass(ap)                => List(ap)
    case TTry(v, _, _, _, _, _)     => List(v)
    case TField(r, _, _)            => List(r)
    case TIf(c, t, el, _)           => c :: t.result.toList ::: el.flatMap(_.result).toList
    case TMatch(s, arms, _)         => s :: arms.flatMap(a => a.guard.toList ::: a.body.result.toList)
    // A loop's own sub-expressions plus its `else` value; the `break` values reach `escaping`
    // through the body statements, so they are not repeated here.
    case TWhile(c, _, el, _)             => c :: el.flatMap(_.result).toList
    case TFor(_, _, lo, hi, _, _, el, _) => lo :: hi :: el.flatMap(_.result).toList
    case TForEach(_, _, seq, _, el, _)   => seq :: el.flatMap(_.result).toList
    // The cursor's initializer and the `next` call that reads it are both the loop's own, and the
    // element the loop binds comes out of the second — so both are walked.
    case TIterate(_, _, init, next, _, _, el, _) => init :: next :: el.flatMap(_.result).toList
    case _                          => Nil
}
