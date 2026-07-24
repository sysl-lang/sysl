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

  /** Checks a whole program, returning the first escape it finds. */
  def check(program: TProgram): Option[String] = new Escape(program).run()
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

  private def run(): Option[String] = {
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
    // *here* returning one is an escape — the array is about to go away.
    val bodies: List[(List[TStmt], Option[TExpr])] =
      program.funcs.map(f => (f.body.stmts, f.body.result)) :+ (program.main, None)

    bodies.iterator
      .map { (stmts, result) =>
        val walk = new Walk(Set.empty, returningEscapes = true)
        walk.seed(stmts, result)
        walk.escape
      }
      .collectFirst { case Some(msg) => msg }
  }

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
    val walk = new Walk(Set(param), returningEscapes = false)
    walk.seed(f.body.stmts, f.body.result)
    walk.escape.isDefined
  }

  /** One function's worth of tracking: which of its locals may hold a view that dies with the
   * frame, and the first place such a view gets out.
   */
  private class Walk(seeds: Set[String], returningEscapes: Boolean) {

    private var confined       = seeds
    var escape: Option[String] = None

    /** Whether an expression may carry a view the frame outlives. A call is the conservative
     * case: a result that may view any argument is treated as viewing all of them, which costs
     * precision only for a function that both takes a stack-backed slice and returns an
     * unrelated fresh one.
     */
    private def viewsFrame(e: TExpr): Boolean = carriesView(e.ty) && viewsFrameValue(e)

    private def viewsFrameValue(e: TExpr): Boolean = e match
      case TSlice(base, _, _, _, _) =>
        base.ty match
          // A view of a `*T` region is the programmer's problem, like every `*T` — it is
          // outside this analysis exactly as it is outside every other guarantee.
          case _: Type.Array => !viaPointer(base)
          case _             => viewsFrame(base)
      case TLoad(name, _)       => confined(name)
      case TCall(_, args, _)    => args.exists(viewsFrame)
      case TStructNew(_, args)  => args.exists(viewsFrame)
      case TEnumNew(_, _, args) => args.exists(viewsFrame)
      case TArrayLit(elems, _)  => elems.exists(viewsFrame)
      case TField(r, _, _)      => viewsFrame(r)
      case TIndex(r, _, _)      => viewsFrame(r)
      case TBytes(r)            => viewsFrame(r)
      // `str(s)` on a string is the identity, so it inherits its argument's view; on any other
      // type it allocates a fresh buffer, and that argument carries no view for `viewsFrame` to
      // find, so the one rule covers both.
      case TStr(a)              => viewsFrame(a)
      case TStore(_, v, _)      => viewsFrame(v)
      case TIf(_, t, e, _)      => blockValue(t) || e.exists(blockValue)
      case TMatch(_, arms, _)   => arms.exists(a => blockValue(a.body))
      // A loop's value comes from its `break`s and its `else`, so it views the frame when any of
      // those do — a `break` of a frame-backed slice out of the loop is the same escape route as
      // returning one.
      case w: TWhile            => loopViewsFrame(w.body, w.elseBlock)
      case f: TFor              => loopViewsFrame(f.body, f.elseBlock)
      case e: TForEach          => loopViewsFrame(e.body, e.elseBlock)
      case _                    => false

    private def blockValue(b: TBlock): Boolean = b.result.exists(viewsFrame)

    private def loopViewsFrame(body: List[TStmt], elseBlock: Option[TBlock]): Boolean =
      ownBreakValues(body).exists(viewsFrame) || elseBlock.exists(blockValue)

    private def viaPointer(e: TExpr): Boolean = e match
      case TDeref(operand, _) => operand.ty.isInstanceOf[Type.Ptr]
      case _                  => false

    /** Propagates through the function's own locals to a fixpoint, then looks for the places a
     * confined view could get out.
     */
    def seed(stmts: List[TStmt], result: Option[TExpr]): Unit = {
      var changed = true
      while changed do
        changed = false
        forEachStmt(stmts) {
          case TVarDecl(name, _, init) if !confined(name) && viewsFrame(init) =>
            confined += name; changed = true
          case TExprStmt(TStore(TLoad(name, _), v, _)) if !confined(name) && viewsFrame(v) =>
            confined += name; changed = true
          case _ =>
        }

      check(stmts)
      result.foreach(returned)
    }

    private def check(stmts: List[TStmt]): Unit = forEachStmt(stmts) {
      case TReturn(Some(v))  => returned(v)
      case TVarDecl(_, _, e) => escaping(e)
      case TExprStmt(e)      => escaping(e)
      // A `break value` carries the value out of the loop; walk it for the escape sites it may
      // contain. Whether it then leaves the frame is decided where the loop's own value is used.
      case TBreak(Some(v), _)   => escaping(v)
      case _                 =>
    }

    private def returned(v: TExpr): Unit =
      if returningEscapes && viewsFrame(v) then report("is returned")
      else escaping(v)

    /** Walks an expression for the places a confined view stops being confined: the heap, a
     * store into anything but a plain local of this function, and an argument the callee keeps.
     */
    private def escaping(e: TExpr): Unit = {
      e match
        case TBox(v, _) => if viewsFrame(v) then report("is put on the heap")

        case TStore(place, v, _) =>
          place match
            case _: TLoad => ()
            case _        => if viewsFrame(v) then report("is stored somewhere the frame does not own")

        case TCall(name, args, _) =>
          for (a, i) <- args.zipWithIndex do
            if viewsFrame(a) && kept(name, i) then report(s"is passed to '$name', which holds on to it")

        case _ =>

      children(e).foreach(escaping)
    }

    private def report(how: String): Unit =
      if escape.isEmpty then
        escape = Some(
          s"a slice of an array this frame owns $how, so it would outlive the array — " +
            "put the storage on the heap as '&[N]T', or return a length and let the caller " +
            "slice its own buffer",
        )
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
    case _                 => Nil
  }

  private def ownBreaksInExpr(e: TExpr): List[TExpr] = e match
    case _: TWhile | _: TFor | _: TForEach => Nil
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
    case TFor(_, _, _, _, _, body, el, _) => TBlock(body, None, Type.Unit) :: el.toList ::: children(e).flatMap(blocks)
    case TForEach(_, _, _, body, el, _)   => TBlock(body, None, Type.Unit) :: el.toList ::: children(e).flatMap(blocks)
    case _                  => children(e).flatMap(blocks)

  private def children(e: TExpr): List[TExpr] = e match
    case TBox(v, _)                 => List(v)
    case TCast(v, _)                => List(v)
    case TDeref(v, _)               => List(v)
    case TAddrOf(v, _)              => List(v)
    case TStore(p, v, _)            => List(p, v)
    case TUpdate(p, _, v, _)        => List(p, v)
    case TIncDec(p, _, _, _)        => List(p)
    case TBinary(_, l, r, _)        => List(l, r)
    case TUnary(_, v, _)            => List(v)
    case TLogical(_, l, r)          => List(l, r)
    case TCompare(ops, _)           => ops
    case TPrint(args)               => args
    case TCall(_, args, _)          => args
    case TStructNew(_, args)        => args
    case TEnumNew(_, _, args)       => args
    case TArrayLit(elems, _)        => elems
    case TIndex(r, i, _)            => List(r, i)
    case TLen(r)                    => List(r)
    case TBytes(r)                  => List(r)
    case TStr(a)                    => List(a)
    case TFormat(a, _)              => List(a)
    case TSlice(b, lo, hi, _, _)    => b :: lo.toList ::: hi.toList
    case TTry(v, _, _, _, _, _)     => List(v)
    case TField(r, _, _)            => List(r)
    case TIf(c, t, el, _)           => c :: t.result.toList ::: el.flatMap(_.result).toList
    case TMatch(s, arms, _)         => s :: arms.flatMap(a => a.guard.toList ::: a.body.result.toList)
    // A loop's own sub-expressions plus its `else` value; the `break` values reach `escaping`
    // through the body statements, so they are not repeated here.
    case TWhile(c, _, el, _)             => c :: el.flatMap(_.result).toList
    case TFor(_, _, lo, hi, _, _, el, _) => lo :: hi :: el.flatMap(_.result).toList
    case TForEach(_, _, seq, _, el, _)   => seq :: el.flatMap(_.result).toList
    case _                          => Nil
}
