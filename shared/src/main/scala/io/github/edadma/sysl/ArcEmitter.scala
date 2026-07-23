package io.github.edadma.sysl

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
    case _: Type.Slice       => true // the owner word, which may or may not be there
    case Type.Array(_, elem) => containsRef(elem)
    case s: Type.Struct      => s.fields.exists(f => containsRef(f._2))
    case e: Type.Enum        => e.variants.exists(_.fields.exists(f => containsRef(f._2)))
    case _                   => false

  /** The LLVM name of the box that holds a `T` on the heap: the refcount, the deallocation
   * hook, and the payload.
   */
  protected def boxName(payload: Type): String = {
    heap = true
    val n = s"%arc.${Type.mangle(payload)}"
    boxes.getOrElseUpdate(n, payload)
    n
  }

  /** Takes a share of everything a value refers to. A bare reference is one refcount; an
   * aggregate delegates to a per-type helper that walks its reference-carrying fields.
   */
  protected def retainValue(ty: Type, v: String): Unit = ty match
    case Type.Ref(_, sync) =>
      heap = true
      syncHeap ||= sync
      emit(s"call void @arc.retain${if sync then "_sync" else ""}(ptr $v)")
    case s: Type.Slice       => emit(s"call void @arc.retain_maybe(ptr ${owner(s, v)})")
    case t if containsRef(t) => emit(s"call void @${valueHelper(t, retain = true)}(${t.llvm} $v)")
    case _                   => ()

  /** Gives back a share of everything a value refers to. */
  protected def releaseValue(ty: Type, v: String): Unit = ty match
    case Type.Ref(_, sync) =>
      heap = true
      syncHeap ||= sync
      emit(s"call void @arc.release${if sync then "_sync" else ""}(ptr $v)")
    case s: Type.Slice       => emit(s"call void @arc.release_maybe(ptr ${owner(s, v)})")
    case t if containsRef(t) => emit(s"call void @${valueHelper(t, retain = false)}(${t.llvm} $v)")
    case _                   => ()

  /** The owner word of a slice value — the reference that keeps its elements alive, or null
   * when they are static, on a frame, or reached through a `*T`.
   */
  protected def owner(ty: Type.Slice, v: String): String = {
    heap = true
    maybeHeap = true
    val o = freshTemp(); emit(s"$o = extractvalue ${ty.llvm} $v, 0"); o
  }

  /** The function that destroys a box of this payload type: it lets go of whatever the payload
   * holds and then returns the storage. Installed in the box at construction, which is what
   * makes releasing a reference type-erased — a slice's owner has no static type to consult.
   */
  protected def dropFn(payload: Type): String = {
    if !containsRef(payload) then "@arc.free"
    else
      val m  = Type.mangle(payload)
      val bn = boxName(payload)

      "@" + request(s"arc.drop.$m") {
        inFunction(s"define private void @arc.drop.$m(ptr %p)") {
          val pa = freshTemp(); emit(s"$pa = getelementptr $bn, ptr %p, i32 0, i32 2")
          val v  = freshTemp(); emit(s"$v = load ${payload.llvm}, ptr $pa")
          releaseValue(payload, v)
          emit("call void @free(ptr %p)")
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
      for ((_, fty), i) <- fields.zipWithIndex if containsRef(fty) do
        val f = freshTemp()
        emit(s"$f = extractvalue $aggregate $value, $i")
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
          val payload = freshTemp()
          emit(s"$payload = extractvalue ${e.llvm} $v, ${variant.payloadSlot.get}")
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
    val v     = genExpr(value)

    val end  = freshTemp(); emit(s"$end = getelementptr $bn, ptr null, i32 1")
    val size = freshTemp(); emit(s"$size = ptrtoint ptr $end to i64")
    val p    = freshTemp(); emit(s"$p = call ptr @malloc(i64 $size)")

    emit(s"store i64 1, ptr $p")
    val hook = freshTemp(); emit(s"$hook = getelementptr $bn, ptr $p, i32 0, i32 1")
    emit(s"store ptr ${dropFn(inner)}, ptr $hook")

    val slot = freshTemp(); emit(s"$slot = getelementptr $bn, ptr $p, i32 0, i32 2")
    retainValue(inner, v)
    emit(s"store ${inner.llvm} $v, ptr $slot")

    ownTemp(p, refTy)
  }

  /** Where a pointer's pointee actually lives. A `*T` addresses its value directly; a `&T`
   * addresses the box, whose payload sits after the refcount and the deallocation hook.
   */
  protected def payloadAddr(operand: TExpr): String = {
    val p = genExpr(operand)

    operand.ty match
      case Type.Ref(inner, _) =>
        val r = freshTemp()
        emit(s"$r = getelementptr ${boxName(inner)}, ptr $p, i32 0, i32 2")
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
    for (v, ty) <- tempStack.head.reverse do releaseValue(ty, v)
    tempStack = tempStack.tail
  }

  protected def pushOwned(): Unit = owned = mutable.ListBuffer.empty[(String, Type)] :: owned

  /** Records a slot that holds a count of its own, after it has been written. */
  protected def ownSlot(name: String, ty: Type): Unit =
    if containsRef(ty) then owned.head += ((s"%$name.addr", ty))

  /** Emits the releases for the innermost scope without popping it — the guard-failure path out
   * of a match arm, where the bindings have been made but the arm is not taken.
   */
  protected def releaseOwned(): Unit =
    for (slot, ty) <- owned.head.reverse do
      val v = freshTemp(); emit(s"$v = load ${ty.llvm}, ptr $slot")
      releaseValue(ty, v)

  protected def popOwned(): Unit = { releaseOwned(); owned = owned.tail }

  /** Lets go of everything the function holds, for a `return` that leaves from the middle of
   * it. Nothing is popped: the block is terminated straight afterwards, so the scopes that
   * follow this one lexically emit their own releases into unreachable code and are dropped.
   */
  protected def releaseAll(): Unit = {
    for frame <- tempStack; (v, ty) <- frame.reverse do releaseValue(ty, v)
    for scope <- owned; (slot, ty) <- scope.reverse do
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
   * The `_maybe` pair is for slices, whose owner is null when the elements are static, on a
   * frame, or reached through a `*T`; the plain pair stays branch-free, since a reference is
   * non-null by construction.
   */
  val core: String =
    """%arc.header = type { i64, ptr }
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
      |  call void %f(ptr %p)
      |  ret void
      |}
      |
      |define private void @arc.release(ptr %p) {
      |entry:
      |  %c = load i64, ptr %p
      |  %n = sub i64 %c, 1
      |  store i64 %n, ptr %p
      |  %z = icmp eq i64 %n, 0
      |  br i1 %z, label %drop, label %live
      |drop:
      |  call void @arc.destroy(ptr %p)
      |  ret void
      |live:
      |  ret void
      |}
      |
      |define private void @arc.free(ptr %p) {
      |entry:
      |  call void @free(ptr %p)
      |  ret void
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
      |  br i1 %z, label %drop, label %live
      |drop:
      |  fence acquire
      |  call void @arc.destroy(ptr %p)
      |  ret void
      |live:
      |  ret void
      |}
      |
      |""".stripMargin

  /** The null-tolerant pair, emitted only into a module that slices something. */
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
