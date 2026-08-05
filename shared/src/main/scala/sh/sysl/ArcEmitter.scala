package sh.sysl

import scala.collection.mutable

/** Automatic reference counting: where a count is taken, where it is given back, and what the
 * heap object looks like while it is held.
 *
 * The placement is uniform rather than clever, because that is what makes it checkable — every
 * named slot holds one count, every temporary holds one until its region ends, and every
 * function retains its parameters and returns its result with a count already taken. Nothing
 * here elides a redundant pair; that is a later pass over the same placement.
 */
trait ArcEmitter extends Emitter {

  /** Whether copying a value of this type has to touch a refcount. A raw pointer never does —
   * it is the mode that opts out of management — and a `&T` is a leaf, so a recursive type
   * cannot make this recur forever.
   */
  protected def containsRef(t: Type): Boolean = t match
    case _: Type.Ref         => true
    case _: Type.Weak        => true
    case _: Type.View        => true // the owner word, which may or may not be there
    case Type.Array(_, elem) => containsRef(elem)
    case s: Type.Struct      => s.fields.exists(f => containsRef(f._2))
    case e: Type.Enum        => e.variants.exists(_.fields.exists(f => containsRef(f._2)))
    case _                   => false

  /** Where a box's own contents begin, once the three header words — the strong count, the
   * destruction hook, and the weak count (`03 § What it costs`) — are behind them. A buffer's
   * element count sits here and its elements one further on.
   */
  protected val headerFields = 3

  /** The LLVM name of the box that holds a `T` on the heap: the two counts, the deallocation
   * hook, and the payload.
   */
  protected def boxName(payload: Type): String = {
    heap = true
    val n = s"%arc.${Type.mangle(payload)}"
    boxes.getOrElseUpdate(n, payload)
    n
  }

  /** The box a counted trait object holds, which is its second word — the first is the method
   * table, a constant that owns nothing.
   */
  protected def erasedBox(v: String): String = {
    val b = freshTemp(); emit(s"$b = extractvalue ${Type.fatPointer} $v, 1"); b
  }

  /** The address a weak reference counts against: the box itself, or — behind a trait object —
   * the second of its two words.
   */
  private def weakBox(ty: Type.Weak, v: String): String =
    if ty.inner.isInstanceOf[Type.Trait] then erasedBox(v) else v

  /** Takes a share of everything a value refers to. A bare reference is one refcount; an
   * aggregate delegates to a per-type helper that walks its reference-carrying fields.
   */
  protected def retainValue(ty: Type, v: String): Unit = ty match
    // A `&Trait` counts exactly as the `&T` it was made from does: the box carries its own
    // destructor, so letting go of one needs no more knowledge of the payload than this has.
    case Type.Ref(_: Type.Trait, sync) =>
      heap = true
      syncHeap ||= sync
      emit(s"call void @arc.retain${if sync then "_sync" else ""}(ptr ${erasedBox(v)})")

    case Type.Ref(_, sync) =>
      heap = true
      syncHeap ||= sync
      emit(s"call void @arc.retain${if sync then "_sync" else ""}(ptr $v)")
    case w: Type.Weak        => weakHeap = true; emit(s"call void @arc.weak_retain(ptr ${weakBox(w, v)})")
    case w: Type.View        => emit(s"call void @arc.retain_maybe(ptr ${owner(w, v)})")
    case t if containsRef(t) => emit(s"call void @${valueHelper(t, retain = true)}(${t.llvm} $v)")
    case _                   => ()

  /** Gives back a share of everything a value refers to. */
  protected def releaseValue(ty: Type, v: String): Unit = ty match
    case Type.Ref(_: Type.Trait, sync) =>
      heap = true
      syncHeap ||= sync
      emit(s"call void @arc.release${if sync then "_sync" else ""}(ptr ${erasedBox(v)})")

    case Type.Ref(_, sync) =>
      heap = true
      syncHeap ||= sync
      emit(s"call void @arc.release${if sync then "_sync" else ""}(ptr $v)")
    case w: Type.Weak        => weakHeap = true; emit(s"call void @arc.weak_release(ptr ${weakBox(w, v)})")
    case w: Type.View        => emit(s"call void @arc.release_maybe(ptr ${owner(w, v)})")
    case t if containsRef(t) => emit(s"call void @${valueHelper(t, retain = false)}(${t.llvm} $v)")
    case _                   => ()

  /** Takes a share of everything the value **at an address** refers to, and gives one back.
   *
   * The pair exists for a type `Layout.indirect` calls large, which never becomes a first-class
   * value: there is nothing to hand `retainValue`, and loading one so that there were would undo
   * the whole point of lowering it through memory. Everything smaller is read out and walked as
   * before, so the two forms agree on what they count — only on where they read it from.
   */
  protected def retainAt(ty: Type, p: String): Unit = walkAt(ty, p, retain = true)

  protected def releaseAt(ty: Type, p: String): Unit = walkAt(ty, p, retain = false)

  private def walkAt(ty: Type, p: String, retain: Boolean): Unit =
    if containsRef(ty) then
      if Layout.indirect(ty) then emit(s"call void @${slotHelper(ty, retain)}(ptr $p)")
      else
        val v = freshTemp(); emit(s"$v = load ${ty.llvm}, ptr $p")
        if retain then retainValue(ty, v) else releaseValue(ty, v)

  /** The retain / release helper a large aggregate takes at its address. Same walk as the one over
   * a value, reaching each reference-carrying member with `getelementptr` instead of lifting it out
   * of an aggregate the caller would have had to materialize first.
   */
  private def slotHelper(ty: Type, retain: Boolean): String = {
    val name = s"arc.${if retain then "copy" else "dispose"}_at.${Type.mangle(ty)}"

    request(name) {
      inFunction(s"define private void @$name(ptr %p)") {
        walkSlot(ty, "%p", retain)
        emitTerm("ret void")
      }
    }
  }

  private def walkSlot(ty: Type, p: String, retain: Boolean): Unit = {
    def each(fields: List[(String, Type)], aggregate: String, base: String): Unit =
      for ((_, fty), i) <- fields.zipWithIndex if containsRef(fty) do
        val f = freshTemp()
        emit(s"$f = getelementptr $aggregate, ptr $base, i32 0, i32 ${Type.slot(fields, i)}")
        walkAt(fty, f, retain)

    ty match
      case s: Type.Struct => each(s.fields, s.llvm, p)

      case Type.Array(n, elem) =>
        val i = emitAlloca(freshTemp(), "i64")
        emit(s"store i64 0, ptr $i")
        val condL = freshLabel("arc.each")
        val bodyL = freshLabel("arc.elem")
        val endL  = freshLabel("arc.done")
        emitTerm(s"br label %$condL")
        emitLabel(condL)
        val iv   = freshTemp(); emit(s"$iv = load i64, ptr $i")
        val more = freshTemp(); emit(s"$more = icmp ult i64 $iv, $n")
        emitTerm(s"br i1 $more, label %$bodyL, label %$endL")
        emitLabel(bodyL)
        val ep = freshTemp(); emit(s"$ep = getelementptr ${elem.llvm}, ptr $p, i64 $iv")
        walkAt(elem, ep, retain)
        val nxt = freshTemp(); emit(s"$nxt = add i64 $iv, 1")
        emit(s"store i64 $nxt, ptr $i")
        emitTerm(s"br label %$condL")
        emitLabel(endL)

      case e: Type.Enum =>
        val tag  = freshTemp(); emit(s"$tag = load i32, ptr $p")
        val endL = freshLabel("arc.done")
        for variant <- e.variants if variant.fields.exists(f => containsRef(f._2)) do
          val hitL  = freshLabel("arc.variant")
          val nextL = freshLabel("arc.next")
          val is    = freshTemp(); emit(s"$is = icmp eq i32 $tag, ${variant.tag}")
          emitTerm(s"br i1 $is, label %$hitL, label %$nextL")
          emitLabel(hitL)
          each(variant.fields, e.payloadLlvm(variant), payloadPtr(e, p))
          emitTerm(s"br label %$endL")
          emitLabel(nextL)
        emitTerm(s"br label %$endL")
        emitLabel(endL)

      case _ => ()
  }

  /** The owner word of a view — the reference that keeps its elements alive, or null when they
   * are static (every string literal), on a frame, or reached through a `*T`.
   */
  protected def owner(ty: Type.View, v: String): String = {
    heap = true
    maybeHeap = true
    val o = freshTemp(); emit(s"$o = extractvalue ${ty.llvm} $v, 0"); o
  }

  /** The function that destroys a box of this payload type: it lets go of whatever the payload
   * holds and then returns the storage. Installed in the box at construction, which is what
   * makes releasing a reference type-erased — a slice's owner has no static type to consult.
   */
  protected def dropFn(payload: Type): String = {
    if !containsRef(payload) then "null"
    else
      val m  = Type.mangle(payload)
      val bn = boxName(payload)

      "@" + request(s"arc.drop.$m") {
        inFunction(s"define private void @arc.drop.$m(ptr %p)") {
          val pa = freshTemp(); emit(s"$pa = getelementptr $bn, ptr %p, i32 0, i32 $headerFields")
          val v  = freshTemp(); emit(s"$v = load ${payload.llvm}, ptr $pa")
          releaseValue(payload, v)
          emitTerm("ret void")
        }
      }
  }

  /** The retain / release helper for an aggregate type, which walks the fields that carry
   * references. Emitted once per type rather than inlined, since a data enum needs a tag test
   * per reference-carrying variant.
   */
  private def valueHelper(ty: Type, retain: Boolean): String = {
    val name = s"arc.${if retain then "copy" else "dispose"}.${Type.mangle(ty)}"

    request(name) {
      inFunction(s"define private void @$name(${ty.llvm} %v)") {
        walkValue(ty, "%v", retain)
        emitTerm("ret void")
      }
    }
  }

  private def walkValue(ty: Type, v: String, retain: Boolean): Unit = {
    def each(fields: List[(String, Type)], aggregate: String, value: String): Unit =
      // A zero-sized field holds no reference, so it is skipped by the filter already — but the
      // index has to be the slot it landed in rather than the one it was written at, or a field
      // after one would be walked at the wrong offset.
      for ((_, fty), i) <- fields.zipWithIndex if containsRef(fty) do
        val f = freshTemp()
        emit(s"$f = extractvalue $aggregate $value, ${Type.slot(fields, i)}")
        if retain then retainValue(fty, f) else releaseValue(fty, f)

    ty match
      case s: Type.Struct => each(s.fields, s.llvm, v)

      // An array is walked with a loop rather than an unrolled chain: the element count is a
      // compile-time constant, but it can be very large, and the code for one element is not.
      case Type.Array(n, elem) =>
        val buf = emitAlloca(freshTemp(), ty.llvm)
        emit(s"store ${ty.llvm} $v, ptr $buf")
        val i = emitAlloca(freshTemp(), "i64")
        emit(s"store i64 0, ptr $i")
        val condL = freshLabel("arc.each")
        val bodyL = freshLabel("arc.elem")
        val endL  = freshLabel("arc.done")
        emitTerm(s"br label %$condL")
        emitLabel(condL)
        val iv   = freshTemp(); emit(s"$iv = load i64, ptr $i")
        val more = freshTemp(); emit(s"$more = icmp ult i64 $iv, $n")
        emitTerm(s"br i1 $more, label %$bodyL, label %$endL")
        emitLabel(bodyL)
        val ep = freshTemp(); emit(s"$ep = getelementptr ${elem.llvm}, ptr $buf, i64 $iv")
        val ev = freshTemp(); emit(s"$ev = load ${elem.llvm}, ptr $ep")
        if retain then retainValue(elem, ev) else releaseValue(elem, ev)
        val nxt = freshTemp(); emit(s"$nxt = add i64 $iv, 1")
        emit(s"store i64 $nxt, ptr $i")
        emitTerm(s"br label %$condL")
        emitLabel(endL)

      case e: Type.Enum =>
        val tag  = freshTemp(); emit(s"$tag = extractvalue ${e.llvm} $v, 0")
        val endL = freshLabel("arc.done")
        for variant <- e.variants if variant.fields.exists(f => containsRef(f._2)) do
          val hitL  = freshLabel("arc.variant")
          val nextL = freshLabel("arc.next")
          val is    = freshTemp(); emit(s"$is = icmp eq i32 $tag, ${variant.tag}")
          emitTerm(s"br i1 $is, label %$hitL, label %$nextL")
          emitLabel(hitL)
          val payload = enumPayload(e, variant, v)
          each(variant.fields, e.payloadLlvm(variant), payload)
          emitTerm(s"br label %$endL")
          emitLabel(nextL)
        emitTerm(s"br label %$endL")
        emitLabel(endL)

      case _ => ()
  }

  // --- allocation --------------------------------------------------------------------------

  /** Puts a value on the heap: one count for the reference this yields, the hook that will
   * destroy it, and a copy of the payload — whose own references the box now holds a share of.
   */
  protected def genBox(value: TExpr, refTy: Type.Ref): String = {
    val inner = refTy.inner
    val bn    = boxName(inner)
    // A large payload is written into the box rather than produced and then stored into it, for the
    // reason every other destination has: the value would be a first-class aggregate of kilobytes
    // for the length of one instruction. The address is not known until the box exists, so this is
    // the one destination that cannot be handed over before the expression runs.
    val v     = if Layout.indirect(inner) then "" else genExpr(value)

    val end  = freshTemp(); emit(s"$end = getelementptr $bn, ptr null, i32 1")
    val size = freshTemp(); emit(s"$size = ptrtoint ptr $end to i64")
    val p    = freshTemp(); emit(s"$p = call ptr @malloc(i64 $size)")

    emit(s"store i64 1, ptr $p")
    val hook = freshTemp(); emit(s"$hook = getelementptr $bn, ptr $p, i32 0, i32 1")
    emit(s"store ptr ${dropFn(inner)}, ptr $hook")
    // One weak share stands for every strong reference together, so the storage outlives the
    // object exactly as long as some weak reference is still asking about it (`03`).
    val wc = freshTemp(); emit(s"$wc = getelementptr $bn, ptr $p, i32 0, i32 2")
    emit(s"store i64 1, ptr $wc")

    val slot = freshTemp(); emit(s"$slot = getelementptr $bn, ptr $p, i32 0, i32 $headerFields")

    if Layout.indirect(inner) then genOwnedInto(slot, value)
    else
      retainValue(inner, v)
      emit(s"store ${inner.llvm} $v, ptr $slot")

    ownTemp(p, refTy)
  }

  /** The box that holds elements the program sized while running: the refcount, the deallocation
   * hook, how many elements there are, and then the elements. The count has to be *in* the box
   * because the hook is what destroys them and the hook is reached with no static type in sight —
   * which is the same reason `07` gives for the hook existing at all.
   */
  protected def bufName(elem: Type): String = {
    heap = true
    val n = s"%arc.buf.${Type.mangle(elem)}"
    bufs.getOrElseUpdate(n, elem)
    n
  }

  /** The function that destroys such a box: it lets go of each element it still holds and returns
   * the storage. Elements that hold nothing need no walk, so the plain free is the hook.
   */
  protected def dropBufFn(elem: Type): String = {
    if !containsRef(elem) then "null"
    else
      val m  = Type.mangle(elem)
      val bn = bufName(elem)

      "@" + request(s"arc.dropbuf.$m") {
        inFunction(s"define private void @arc.dropbuf.$m(ptr %p)") {
          val lenp = freshTemp(); emit(s"$lenp = getelementptr $bn, ptr %p, i32 0, i32 $headerFields")
          val n    = freshTemp(); emit(s"$n = load i64, ptr $lenp")
          val data = freshTemp(); emit(s"$data = getelementptr $bn, ptr %p, i32 0, i32 ${headerFields + 1}")

          eachElement(elem, data, n) { ep =>
            val ev = freshTemp(); emit(s"$ev = load ${elem.llvm}, ptr $ep")
            releaseValue(elem, ev)
          }
          emitTerm("ret void")
        }
      }
  }

  /** Puts one value in every one of `n` slots, taking a share for each — the elements belong to the
   * buffer, and it is the hook above that eventually lets them go.
   */
  protected def fillElements(elem: Type, data: String, n: String, v: String): Unit =
    eachElement(elem, data, n) { ep =>
      retainValue(elem, v)
      emit(s"store ${elem.llvm} $v, ptr $ep")
    }

  /** Walks `n` elements starting at `data`, handing each element's address to `body`. A loop rather
   * than a straight line for the reason the ARC walk over an array gives: the count is exactly the
   * thing that may be large here.
   */
  protected def eachElement(elem: Type, data: String, n: String)(body: String => Unit): Unit = {
    val i     = emitAlloca(freshTemp(), "i64")
    val condL = freshLabel("buf.test")
    val bodyL = freshLabel("buf.elem")
    val endL  = freshLabel("buf.done")

    emit(s"store i64 0, ptr $i")
    emitTerm(s"br label %$condL")
    emitLabel(condL)
    val iv   = freshTemp(); emit(s"$iv = load i64, ptr $i")
    val more = freshTemp(); emit(s"$more = icmp ult i64 $iv, $n")
    emitTerm(s"br i1 $more, label %$bodyL, label %$endL")
    emitLabel(bodyL)
    val ep = freshTemp(); emit(s"$ep = getelementptr ${elem.llvm}, ptr $data, i64 $iv")
    body(ep)
    val nxt = freshTemp(); emit(s"$nxt = add i64 $iv, 1")
    emit(s"store i64 $nxt, ptr $i")
    emitTerm(s"br label %$condL")
    emitLabel(endL)
  }

  /** Where a pointer's pointee actually lives. A `*T` addresses its value directly; a `&T`
   * addresses the box, whose payload sits after the refcount and the deallocation hook.
   */
  protected def payloadAddr(operand: TExpr): String = {
    val p = genExpr(operand)

    operand.ty match
      case Type.Ref(inner, _) =>
        val r = freshTemp()
        emit(s"$r = getelementptr ${boxName(inner)}, ptr $p, i32 0, i32 $headerFields")
        r
      case _ => p
  }

  // --- ownership regions ---------------------------------------------------------------------

  protected def pushTemps(): Unit = tempStack = mutable.ListBuffer.empty[(String, Type)] :: tempStack

  /** Records a value the expression owns outright — a fresh box, or a call result, which every
   * function returns with a count already taken.
   */
  protected def ownTemp(v: String, ty: Type): String = {
    if containsRef(ty) then tempStack.head += ((v, ty))
    v
  }

  /** Releases the innermost region's temporaries and pops it. */
  protected def popTemps(): Unit = {
    releaseTemps()
    tempStack = tempStack.tail
  }

  /** Emits the releases for the innermost region without popping it, and drops the region without
   * emitting them — the pair a caller needs when more than one edge leaves the region and each has
   * to let go for itself. An iterating loop is the case: what `next` gave back is released on the
   * way into the body and again on the way out of the loop, and those are different blocks.
   */
  protected def releaseTemps(): Unit = releaseValues(tempsHere)

  /** What the innermost temp region holds, for an edge that has to give those counts back somewhere
   * other than where the region is closed — a condition term that branches before it can release,
   * because the branch it guards retains out of the very value being held (`09 §12`).
   */
  protected def tempsHere: List[(String, Type)] = tempStack.head.toList

  protected def releaseValues(vs: List[(String, Type)]): Unit =
    for (v, ty) <- vs.reverse do releaseValue(ty, v)
  protected def dropTemps(): Unit    = tempStack = tempStack.tail

  /** Hands a statement to the scope being emitted. Nothing is emitted here — the `defer` itself
   * costs no instruction, and the statement is laid down at each edge that leaves the block.
   */
  protected def deferStmts(stmts: List[TStmt]): Unit = deferrals.head ++= stmts

  /** Runs one scope's deferred statements, last registered first.
   *
   * They go **before** that scope's releases, so every local the statement names is still alive
   * while it runs — including the one holding the resource it is closing, which is the whole point
   * of the form. Registration order is written order, so `reverse` is the LIFO `03 § defer`
   * promises.
   */
  private def runDeferrals(scope: mutable.ListBuffer[TStmt]): Unit = scope.reverse.foreach(genStmt)

  protected def pushOwned(): Unit = {
    owned = mutable.ListBuffer.empty[(String, Type)] :: owned
    deferrals = mutable.ListBuffer.empty[TStmt] :: deferrals
  }

  /** Records a slot that holds a count of its own, after it has been written. */
  protected def ownSlot(name: String, ty: Type): Unit =
    if containsRef(ty) then owned.head += ((s"%$name.addr", ty))

  /** Registers the buffer a promoted array lives in, so the scope that declared it gives back its
   * share on the way out. A buffer is what a `&T` points at, so it is registered as one and the
   * ordinary release path emits the `arc.release` — the only reason it needs a slot of its own is
   * that everything in `owned` is a place to load from rather than a value.
   *
   * The count is what makes promotion work: a view of the array retains this box when it is
   * returned or stored, so the release below takes the declaration's share and the storage outlives
   * the frame exactly as long as some view of it does.
   */
  protected def ownBox(name: String, box: String, elem: Type): Unit = {
    emitAlloca(s"%$name.box", "ptr")
    emit(s"store ptr $box, ptr %$name.box")
    owned.head += ((s"%$name.box", Type.Ref(elem, false)))
  }

  /** Emits the releases for the innermost scope without popping it — the guard-failure path out
   * of a match arm, where the bindings have been made but the arm is not taken.
   */
  protected def releaseOwned(): Unit = releaseSlots(ownedHere)

  /** What the innermost scope holds, for a path that has to give those counts back somewhere other
   * than where the scope ends.
   *
   * A condition's `is` bindings are the case (`09 §12`): the scope is popped at the end of the
   * branch the condition guards, but a later term of the same condition may fail, and *that* edge
   * leaves without ever reaching the branch. Its releases are emitted after the success path has
   * already popped, so the slots have to be read out while the scope is still there.
   */
  protected def ownedHere: List[(String, Type)] = owned.head.toList

  protected def releaseSlots(slots: List[(String, Type)]): Unit =
    for (slot, ty) <- slots.reverse do
      val v = freshTemp(); emit(s"$v = load ${ty.llvm}, ptr $slot")
      releaseValue(ty, v)

  protected def popOwned(): Unit = {
    runDeferrals(deferrals.head)
    releaseOwned()
    owned = owned.tail
    deferrals = deferrals.tail
  }

  /** Lets go of everything the function holds, for a `return` that leaves from the middle of
   * it. Nothing is popped: the block is terminated straight afterwards, so the scopes that
   * follow this one lexically emit their own releases into unreachable code and are dropped.
   */
  protected def releaseAll(): Unit = {
    for frame <- tempStack; (v, ty) <- frame.reverse do releaseValue(ty, v)

    // **Snapshot first, and it is load-bearing.** A deferred statement is emitted by `genStmt`, and
    // one containing an `if` or a `match` pushes and pops the very stacks this is walking — so the
    // scopes must be fixed before any of them runs. A lazy pairing here would read `owned` back
    // mid-walk and skip the releases of whatever it had moved past, which leaks rather than fails.
    val scopes = owned.zip(deferrals)

    // Outward, one scope at a time, each running what it deferred before giving up its counts — so
    // a statement deferred in an inner block still sees the outer block's locals, which are let go
    // only once the scope that owns them is itself being left.
    for (scope, ds) <- scopes do
      runDeferrals(ds)
      for (slot, ty) <- scope.reverse do
        val v = freshTemp(); emit(s"$v = load ${ty.llvm}, ptr $slot")
        releaseValue(ty, v)
  }

  /** Lets go of everything accrued since a loop was entered, for a `break`/`continue` that leaves
   * from the middle of the body — the ownership analogue of `releaseAll`, but bounded to the
   * loop's regions (whose depths the `GenLoop` recorded) rather than the whole function. Nothing
   * is popped: the block terminates right after, so the lexically-following pops emit into
   * unreachable code and are dropped.
   */
  protected def releaseToDepth(ownedDepth: Int, tempDepth: Int): Unit = {
    for frame <- tempStack.take(tempStack.length - tempDepth); (v, ty) <- frame.reverse do releaseValue(ty, v)

    // Snapshotted before anything runs, for `releaseAll`'s reason. `zip` needs no matching `take`:
    // the two stacks are the same length, so pairing the bounded one against the whole of the other
    // stops where the bound does, and the pairs still line up because both are innermost-first.
    val scopes = owned.take(owned.length - ownedDepth).zip(deferrals)

    for (scope, ds) <- scopes do
      runDeferrals(ds)
      for (slot, ty) <- scope.reverse do
        val v = freshTemp(); emit(s"$v = load ${ty.llvm}, ptr $slot")
        releaseValue(ty, v)
  }
}

object ArcEmitter {

  /** All of ARC that is the same for every type — which, because the destructor lives behind the
   * box's hook, is all of it but the hooks themselves.
   *
   * Taking a share needs no ordering: a count you already hold cannot reach zero underneath you,
   * so the atomic form is a relaxed increment. Giving one back publishes with release ordering
   * and acquires before destroying, so the thread that frees sees every other domain's writes.
   * The `_maybe` pair is for views — slices and strings — whose owner is null when the elements
   * are static, on a frame, or reached through a `*T`; the plain pair stays branch-free, since a
   * reference is non-null by construction.
   *
   * **Teardown is iterative, not recursive.** A destructor releases the references its payload
   * holds, so a chain of `&T` — a linked list, a degenerate tree — would otherwise recurse one C
   * stack frame per node and overflow the stack on a long one. Instead, when a count reaches zero
   * the object is pushed onto a worklist (reusing its now-dead count slot as the link) and the
   * *first* release to hit zero drains the list in a loop: each destructor it runs pushes more
   * work rather than recursing, so teardown depth is O(1) regardless of structure depth.
   *
   * **The worklist is per thread, because it is scratch space and not shared state.** Two threads
   * releasing the last reference to two unrelated `&sync` structures at the same moment both reach
   * `arc.reap`, and one list between them would have each overwrite the other's head — a queued
   * object dropped on the floor, or drained twice. Neither is a race the counts could prevent: the
   * counts are what got both threads here correctly. So the list is `thread_local` and each thread
   * drains its own, which is also what makes the `arc.draining` flag mean what it says, since it
   * asks whether *this* thread is already inside a drain further up its own stack.
   *
   * A target with no thread-local storage keeps the plain global (`Target.hasThreadLocalStorage`),
   * which is exactly as correct there as it was everywhere before: nothing on a bare target spawns.
   *
   * **The storage outlives the object when something weak still asks about it.** The strong count
   * reaching zero destroys the object — the payload's references are given back — but the bytes
   * come back only when the weak count follows, and the weak count holds one share on behalf of
   * every strong reference together (`03 § What it costs`). So a `get()` on a dead object reads
   * storage that is still there and finds a strong count of zero in it.
   *
   * That share is also what makes the worklist safe. A queued object has its strong slot on loan
   * as the list's link, and a weak release arriving in that window cannot free it, because the
   * share the strong references left behind has not been given back yet — `arc.destroy` is what
   * gives it back, after the hook has run and the link is no longer needed. The slot is put back
   * to zero before the hook runs, so nothing ever reads a link where a count should be.
   */
  def core(target: Target): String = {
    val tls = if target.hasThreadLocalStorage then "thread_local " else ""

    s"""%arc.header = type { i64, ptr, i64 }
      |
      |@arc.worklist = internal ${tls}global ptr null
      |@arc.draining = internal ${tls}global i1 false
      |
      |define private void @arc.retain(ptr %p) {
      |entry:
      |  %c = load i64, ptr %p
      |  %n = add i64 %c, 1
      |  store i64 %n, ptr %p
      |  ret void
      |}
      |
      |define private void @arc.destroy(ptr %p) {
      |entry:
      |  %h = getelementptr %arc.header, ptr %p, i32 0, i32 1
      |  %f = load ptr, ptr %h
      |  %none = icmp eq ptr %f, null
      |  br i1 %none, label %after, label %run
      |run:
      |  call void %f(ptr %p)
      |  br label %after
      |after:
      |  call void @arc.unshare(ptr %p)
      |  ret void
      |}
      |
      |define private void @arc.unshare(ptr %p) {
      |entry:
      |  %w = getelementptr %arc.header, ptr %p, i32 0, i32 2
      |  %c = load i64, ptr %w
      |  %n = sub i64 %c, 1
      |  store i64 %n, ptr %w
      |  %z = icmp eq i64 %n, 0
      |  br i1 %z, label %gone, label %kept
      |gone:
      |  call void @free(ptr %p)
      |  ret void
      |kept:
      |  ret void
      |}
      |
      |define private void @arc.reap(ptr %p) {
      |entry:
      |  %w = load ptr, ptr @arc.worklist
      |  store ptr %w, ptr %p
      |  store ptr %p, ptr @arc.worklist
      |  %d = load i1, ptr @arc.draining
      |  br i1 %d, label %done, label %drain
      |drain:
      |  store i1 true, ptr @arc.draining
      |  br label %loop
      |loop:
      |  %q = load ptr, ptr @arc.worklist
      |  %e = icmp eq ptr %q, null
      |  br i1 %e, label %finish, label %step
      |step:
      |  %next = load ptr, ptr %q
      |  store ptr %next, ptr @arc.worklist
      |  store i64 0, ptr %q
      |  call void @arc.destroy(ptr %q)
      |  br label %loop
      |finish:
      |  store i1 false, ptr @arc.draining
      |  ret void
      |done:
      |  ret void
      |}
      |
      |define private void @arc.release(ptr %p) {
      |entry:
      |  %c = load i64, ptr %p
      |  %n = sub i64 %c, 1
      |  store i64 %n, ptr %p
      |  %z = icmp eq i64 %n, 0
      |  br i1 %z, label %reap, label %live
      |reap:
      |  call void @arc.reap(ptr %p)
      |  ret void
      |live:
      |  ret void
      |}
      |
      |""".stripMargin
  }

  /** The three functions a `weak T` needs, emitted only into a module that holds one.
   *
   * `upgrade` is the whole of what separates a weak reference from a dangling one: it reads the
   * strong count, and a zero there means the object has already been destroyed, so the caller is
   * handed nothing instead of an address. A live count is taken, which is why the answer is a
   * reference the caller owns rather than one it has to be careful with.
   */
  val weak: String =
    """define private void @arc.weak_retain(ptr %p) {
      |entry:
      |  %empty = icmp eq ptr %p, null
      |  br i1 %empty, label %done, label %live
      |live:
      |  %w = getelementptr %arc.header, ptr %p, i32 0, i32 2
      |  %c = load i64, ptr %w
      |  %n = add i64 %c, 1
      |  store i64 %n, ptr %w
      |  ret void
      |done:
      |  ret void
      |}
      |
      |define private void @arc.weak_release(ptr %p) {
      |entry:
      |  %empty = icmp eq ptr %p, null
      |  br i1 %empty, label %done, label %live
      |live:
      |  call void @arc.unshare(ptr %p)
      |  ret void
      |done:
      |  ret void
      |}
      |
      |define private ptr @arc.upgrade(ptr %p) {
      |entry:
      |  %empty = icmp eq ptr %p, null
      |  br i1 %empty, label %gone, label %check
      |check:
      |  %c = load i64, ptr %p
      |  %z = icmp eq i64 %c, 0
      |  br i1 %z, label %gone, label %live
      |live:
      |  %n = add i64 %c, 1
      |  store i64 %n, ptr %p
      |  ret ptr %p
      |gone:
      |  ret ptr null
      |}
      |
      |""".stripMargin

  /** The atomic pair, emitted only into a module that has a `&sync` in it. */
  val atomic: String =
    """define private void @arc.retain_sync(ptr %p) {
      |entry:
      |  %o = atomicrmw add ptr %p, i64 1 monotonic
      |  ret void
      |}
      |
      |define private void @arc.release_sync(ptr %p) {
      |entry:
      |  %o = atomicrmw sub ptr %p, i64 1 release
      |  %z = icmp eq i64 %o, 1
      |  br i1 %z, label %reap, label %live
      |reap:
      |  fence acquire
      |  call void @arc.reap(ptr %p)
      |  ret void
      |live:
      |  ret void
      |}
      |
      |""".stripMargin

  /** The null-tolerant pair, emitted only into a module that holds a view of something. */
  val maybe: String =
    """define private void @arc.retain_maybe(ptr %p) {
      |entry:
      |  %null = icmp eq ptr %p, null
      |  br i1 %null, label %done, label %live
      |live:
      |  call void @arc.retain(ptr %p)
      |  ret void
      |done:
      |  ret void
      |}
      |
      |define private void @arc.release_maybe(ptr %p) {
      |entry:
      |  %null = icmp eq ptr %p, null
      |  br i1 %null, label %done, label %live
      |live:
      |  call void @arc.release(ptr %p)
      |  ret void
      |done:
      |  ret void
      |}
      |
      |""".stripMargin
}
