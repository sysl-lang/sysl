package io.github.edadma.sysl

import scala.collection.mutable

import TreeWalk.{children, forEachStmt, ownBreakValues}

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
        val walk = new Walk(Map.empty, returningEscapes = true, localArrays(stmts), refBindings(stmts))
        walk.seed(stmts, result)
        (who, walk)
      }

    val refused = borrowed ::: walks.flatMap(noAllocPromotion) ::: walks.flatMap(_._2.escape)

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
    // The writing is **not** the first slot: `Writer: Fallible`, and a trait offers what it requires
    // before what it declares. Reaching for the head would ask this question of `failed`, which
    // keeps nothing, and would quietly stop refusing the implementations this check exists for —
    // so the slot is read from the one place the layout is written down.
    val offenders =
      for
        vt   <- program.vtables if vt.traitName == Library.key("Writer")
        slot <- vt.slots.lift(WriterEmitter.writeSlot).toList if keeps((slot.target, 1))
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
  private class Walk(
      seeds: Map[String, View],
      returningEscapes: Boolean,
      locals: Set[String] = Set.empty,
      refOf: Map[String, TExpr] = Map.empty,
  ) {

    private var confined       = seeds
    var escape: Option[String] = None

    /** The arrays this body must allocate on the heap. */
    val promoted = mutable.Set.empty[String]

    /** Why each of them moved, in the order the escapes were found. */
    val why = mutable.ListBuffer.empty[String]

    /** The same promotions with their parts still separate — the array, where the view that carried
     * it out was written, and how — for the one reader that has to say something else about them: a
     * module that declared `no alloc` has nowhere to promote into, so what is silent everywhere else
     * is a diagnostic there.
     */
    val sites = mutable.ListBuffer.empty[(String, Option[Pos], String)]

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
      case TCallPtr(_, args, _, _)  => View.any(args.map(views))
      case TStructNew(_, args)  => View.any(args.map(views))
      case TStructInvCheck(v, _, _) => views(v)
      case TRecheck(after, _, _, _) => views(after)
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
      // A `ref` declares no storage, so it is never itself a root — the array it names is whatever
      // its place was rooted at. Without this step the name would answer `unnamed`, and an escape
      // through it would be reported as one with nowhere to promote to rather than moving the array
      // it actually views.
      case TLoad(name, _) if refOf.contains(name) => arrayRoot(refOf(name))
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
          // A `ref` name reaches the storage its place reached (`03 § ref`), so it views whatever
          // that place views. Without this the name would view nothing, and a slice taken through it
          // would look like a view of storage the frame does not own.
          case TRefDecl(name, _, place)                 => bind(name, views(place))
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
      case TRefDecl(_, _, e) => escaping(e)
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

        // Nothing is known about what is at the other end of a function pointer — not even which
        // program compiled it — so the worst is assumed of every argument, exactly as `12 §1` has
        // this analysis assume it of an `extern`.
        case TCallPtr(_, args, _, _) =>
          for a <- args do
            if viewsFrame(a) then gets_out(a, "is passed through a function pointer, which may hold on to it")

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
        sites += ((name, at.pos, how))

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

  /** A promotion in a module that declared `no alloc`, said as the refusal it is there.
   *
   * Promotion is deliberately silent everywhere else (`05 § Promotion is silent, not hidden`) — the
   * program means what it said and the storage quietly moves. A module that gave the allocator up
   * has nowhere for it to move to, so the same fact has to be reported, and it is reported against
   * the view that leaves the frame rather than against the array: the array is fine, and what the
   * reader has to change is where its contents go.
   *
   * The body's module is read off the function's key, or is the entry point's for the statements a
   * program runs, which belong to the file that carries them however little they look declared.
   */
  private def noAllocPromotion(walk: (Option[String], Walk)): List[String] = {
    val (who, w) = walk
    val module   = who.map(Modules.moduleOf).getOrElse(program.mainModule)

    if !program.noAllocModules(module) then Nil
    else
      w.sites.toList.map((name, pos, how) =>
        Diagnostic.render(
          s"this view of '$name' $how, so the array would move to the heap to outlive the frame — " +
            "and this module declared 'no alloc', so there is nothing to move it into. Keep the view " +
            "inside the frame, or take the storage from a caller as a '[]T' parameter, which is " +
            "already wherever its owner put it",
          pos,
        ),
      )
  }

  /** The arrays a body declares for itself, which are the ones it may move to the heap. A `[N]T`
   * parameter is storage the caller laid out, so it is deliberately not here.
   */
  private def localArrays(stmts: List[TStmt]): Set[String] = {
    val found = mutable.Set.empty[String]

    forEachStmt(stmts) { case TVarDecl(name, _: Type.Array, _) => found += name }
    found.toSet
  }

  /** The place each `ref` in a body stands for, so a walk that reaches one of those names can carry
   * on to the storage it really names (`03 § ref`). A ref declares nothing, which is why it is
   * gathered separately from the arrays above rather than counted among them.
   */
  private def refBindings(stmts: List[TStmt]): Map[String, TExpr] = {
    val found = mutable.Map.empty[String, TExpr]

    forEachStmt(stmts) { case TRefDecl(name, _, place) => found(name) = place }
    found.toMap
  }

}
