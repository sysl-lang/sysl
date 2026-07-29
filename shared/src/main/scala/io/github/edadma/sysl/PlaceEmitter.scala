package io.github.edadma.sysl

/** Addressing a place, and building the composite values that have one.
 *
 * Two things live here because they are the same subject seen from either end. A **place** is
 * something with an address — a local's slot, a dereference, a field or element of either — and
 * reaching one is a `getelementptr` chain rather than a read of the value it sits in, which is what
 * lets `s.f.g = v` write through without copying anything out. A **composite value** is what those
 * addresses point into: an array, a slice, an enum and its payload.
 *
 * Every element access is bounds-checked, and a slice carries the owner it borrows from, so making
 * one is also an ownership event rather than pointer arithmetic alone.
 */
trait PlaceEmitter extends ArcEmitter with ScalarEmitter {

  /** The address of a place, as a `ptr` register or an existing slot name. Every place bottoms
   * out either in a local's stack slot or in a pointer the program already holds, so this walks
   * the field chain with `getelementptr` rather than reading values out with `extractvalue`.
   */
  protected def address(place: TExpr): String = place match
    case TLoad(name, _)     => s"%$name.addr"
    // A `val`'s storage is the global itself, so its address needs no instruction to compute — it
    // is what makes indexing one reach into the table rather than copy it out first.
    case TGlobal(symbol, _) => s"@$symbol"
    case TDeref(operand, _) => payloadAddr(operand)
    // A zero-sized field occupies nothing, so it is wherever its receiver is: the address is never
    // read or written through, and handing back the receiver's keeps the walk to it — and whatever
    // that walk evaluates — exactly as it is for every other field.
    case TField(receiver, _, ty) if Type.zeroSized(ty) => address(receiver)

    case TField(receiver, index, _) =>
      val base = address(receiver)
      val r    = freshTemp()
      emit(s"$r = getelementptr ${receiver.ty.llvm}, ptr $base, i32 0, i32 ${fieldSlot(receiver.ty, index)}")
      r
    case TIndex(receiver, index, _) => elementAddr(receiver, index)

    // A computed value has no address of its own, so reaching into one means giving it a slot
    // first. The analyzer has already refused to *assign* through anything but a real place, so
    // this only ever happens on the way to a read.
    case other =>
      val slot = emitAlloca(freshTemp(), other.ty.llvm)
      emit(s"store ${other.ty.llvm} ${genExpr(other)}, ptr $slot")
      slot

  /** Writes `v` into the place at `p`. A slot that holds a count takes one for the value arriving
   * and lets go of the one leaving, in that order, so assigning something to itself never briefly
   * drops the last count.
   */
  protected def storeInto(ty: Type, p: String, v: String): Unit =
    if containsRef(ty) then
      val old = freshTemp(); emit(s"$old = load ${ty.llvm}, ptr $p")
      retainValue(ty, v)
      emit(s"store ${ty.llvm} $v, ptr $p")
      releaseValue(ty, old)
    else emit(s"store ${ty.llvm} $v, ptr $p")

  /** `a, b = b, a` (`00 §2`), in the phases the form promises.
   *
   * Locating every place comes first, so an index that calls something calls it once and the calls
   * happen in written order. Then everything the statement reads is read — what each compound arm
   * finds in its place, and then the whole right side — and because no store has happened yet, every
   * operand of every arm sees the values the statement started with. The writes come left to right.
   *
   * **Every `invariant` re-check waits until all of them have landed**, and once per struct however
   * many of its fields were written. A struct whose invariant relates two fields is exactly what
   * this form is for, and checking after each field in turn would trap on the half-written state on
   * the way to a perfectly good one — `s.lo, s.hi = 6, 8` on a `Span` that was `1..5` would be
   * refused for the moment `lo` was `6` and `hi` still `5`.
   */
  protected def genMultiAssign(writes: List[TWrite]): Unit = {
    val addrs = writes.map(w => if Type.zeroSized(w.place.ty) then "" else address(w.place))

    // Every read takes a count for the statement, which is what a form that reads everything before
    // it writes anything needs and a single assignment does not. Writing into the first place lets
    // go of what was there, and in a swap what was there is exactly the value the second arm is
    // holding — so without a count of its own that value can be freed between being read and being
    // stored. The statement's counts go back at the end of the statement, as every temporary's do.
    def held(v: String, ty: Type): String = {
      retainValue(ty, v)
      ownTemp(v, ty)
    }

    val curs = writes.zip(addrs).map { (w, p) =>
      if w.op == "=" || p.isEmpty then ""
      else
        val c = freshTemp(); emit(s"$c = load ${w.place.ty.llvm}, ptr $p")
        held(c, w.place.ty)
    }
    val vals = writes.map(w => held(genExpr(w.value), w.value.ty))

    for ((w, p), (cur, v)) <- writes.zip(addrs).zip(curs.zip(vals)) do
      if p.nonEmpty then
        val ty = w.place.ty

        if w.op == "=" then storeInto(ty, p, v)
        else
          val updated = combine(w.op, ty, w.value.ty, w.dispatch, cur, v)

          // A compound arm has already read what was in the slot, so the release is of that value
          // rather than of a second load — which is `TUpdate`'s arrangement, for its reason.
          if containsRef(ty) then
            retainValue(ty, updated)
            emit(s"store ${ty.llvm} $updated, ptr $p")
            releaseValue(ty, cur)
          else emit(s"store ${ty.llvm} $updated, ptr $p")

    for (recv, struct, invFn) <- writes.flatMap(_.check).distinct do
      emitInvCheck(genExpr(recv), struct, invFn)
  }

  /** Where a written field index lands in the emitted aggregate, once the zero-sized fields before
   * it are dropped.
   */
  protected def fieldSlot(recvTy: Type, index: Int): Int = recvTy match
    case s: Type.Struct => s.slot(index)
    case _              => index

  /** The address of one element, after checking that it exists. An array is indexed from its
   * own storage; a slice is indexed from the pointer it carries.
   */
  protected def elementAddr(receiver: TExpr, index: TExpr): String = {
    // The length is what there is to check against, and a `*T` has none — so the pointer case
    // yields no length and the check is skipped. That is the whole difference between `p[i]` and
    // every other subscript, and it is `03`'s unchecked primitive doing what C's does.
    val (base, len, elem) = receiver.ty match
      case Type.Array(n, e) => (address(receiver), Some(n.toString), e)
      case w: Type.View =>
        val v = genExpr(receiver)
        val p = freshTemp(); emit(s"$p = extractvalue ${w.llvm} $v, 1")
        val l = freshTemp(); emit(s"$l = extractvalue ${w.llvm} $v, 2")
        (p, Some(l), w.elem)
      case Type.Ptr(e) => (genExpr(receiver), None, e)
      case other       => sys.error(s"unreachable index into ${other.llvm}")

    val i = widen64(index)
    for l <- len do boundsCheck(i, l)
    val r = freshTemp(); emit(s"$r = getelementptr ${elem.llvm}, ptr $base, i64 $i"); r
  }

  /** Takes a view of some of an array's, a slice's, or a string's elements. The base is evaluated
   * once and gives up three things — what keeps the elements alive, where the first of them is,
   * and how many there are — and the view is built by narrowing the last two and taking a share
   * of the first.
   */
  protected def genSlice(
      base: TExpr,
      lo: Option[TExpr],
      hi: Option[TExpr],
      inclusive: Boolean,
      sliceTy: Type.View,
  ): String = {
    val elem = sliceTy.elem

    val (ownerV, first, len) = base.ty match
      case Type.Ref(array @ Type.Array(n, _), _) =>
        val r = genExpr(base)
        val p = freshTemp(); emit(s"$p = getelementptr ${boxName(array)}, ptr $r, i32 0, i32 $headerFields")
        (r, p, Some(n.toString))
      case s: Type.View =>
        val v = genExpr(base)
        val o = freshTemp(); emit(s"$o = extractvalue ${s.llvm} $v, 0")
        val p = freshTemp(); emit(s"$p = extractvalue ${s.llvm} $v, 1")
        val l = freshTemp(); emit(s"$l = extractvalue ${s.llvm} $v, 2")
        (o, p, Some(l))
      // Storage this frame owns, or a `*T` region. A frame-backed array has no owner, so counting
      // it is a no-op — unless the escape analysis moved it to the heap, in which case the buffer
      // it moved into is exactly what a view of it must count against, and the whole promotion
      // comes down to naming that buffer here instead of `null`. Nothing makes a `*T` region safe;
      // that is what `*T` is.
      // Storage this frame owns, storage inside a box the walk went through, or a `*T` region.
      case Type.Array(n, _) =>
        val (own, addr) = addressOwned(base)
        (own, addr, Some(n.toString))
      case Type.Ptr(Type.Array(n, _)) => ("null", genExpr(base), Some(n.toString))
      // A `*T` region: no owner to count and no length to check the end against. The analyzer has
      // already insisted the end be written, since there is nothing here to supply one.
      case _: Type.Ptr                => ("null", genExpr(base), None)
      case other                      => sys.error(s"unreachable slice of ${other.llvm}")

    val start = lo.map(widen64).getOrElse("0")

    // The check is on the half-open interval the view ends up naming. An inclusive high end
    // additionally has to name an element that exists, which is also what stops `hi + 1` from
    // wrapping past the end.
    val end = hi match
      case None => len.getOrElse(sys.error("unreachable open-ended slice of a pointer"))
      case Some(h) =>
        val v = widen64(h)
        if !inclusive then v
        else
          for l <- len do
            val within = freshTemp(); emit(s"$within = icmp ult i64 $v, $l")
            trapUnless(within, "bounds")
          val e = freshTemp(); emit(s"$e = add i64 $v, 1"); e

    for l <- len if hi.isDefined && !inclusive do
      val fits = freshTemp(); emit(s"$fits = icmp ule i64 $end, $l")
      trapUnless(fits, "bounds")

    val ordered = freshTemp(); emit(s"$ordered = icmp ule i64 $start, $end")
    trapUnless(ordered, "bounds")

    // A substring has to be a string, so both ends must fall between characters. This runs after
    // the bounds checks, which is what makes reading the byte at either end safe.
    if sliceTy == Type.Str then
      // A string is a view and so always has one; only a `*T` region does not.
      val l = len.getOrElse(sys.error("unreachable string slice with no length"))

      trapUnless(strBoundary(first, l, start), "boundary")
      trapUnless(strBoundary(first, l, end), "boundary")

    val p = freshTemp(); emit(s"$p = getelementptr ${elem.llvm}, ptr $first, i64 $start")
    val n = freshTemp(); emit(s"$n = sub i64 $end, $start")

    emit(s"call void @arc.retain_maybe(ptr $ownerV)")
    maybeHeap = true
    heap = true

    val withOwner = freshTemp(); emit(s"$withOwner = insertvalue ${sliceTy.llvm} zeroinitializer, ptr $ownerV, 0")
    val withPtr   = freshTemp(); emit(s"$withPtr = insertvalue ${sliceTy.llvm} $withOwner, ptr $p, 1")
    val whole     = freshTemp(); emit(s"$whole = insertvalue ${sliceTy.llvm} $withPtr, i64 $n, 2")

    ownTemp(whole, sliceTy)
  }

  /** Storage for `n` elements, with one count taken for whoever is about to view it. Yields where
   * the box is and where its elements start.
   *
   * A count the program computed is where the arithmetic can go wrong, so the size is built with
   * checked arithmetic: a count that would wrap traps rather than allocating something smaller than
   * the elements that are about to be written into it, and an allocation that fails traps rather
   * than handing back a null those elements are then stored through. Both are `07 §Indexing`'s trap
   * for `07 §Indexing`'s reason — the guarantee is that a program with no `*T` in it cannot fault.
   */
  /** The buffer a promoted array lives in, or `null` for one that still lives in the frame.
   *
   * Only a plain local is ever promoted, which is the same shape the analysis names its roots by
   * (`05`): storage reached through a field or an index belongs to something else, and an array
   * parameter is the caller's layout, so neither is this body's to have moved.
   */
  protected def promotedOwner(base: TExpr): String = base match
    case TLoad(name, _)  => promotedBoxes.getOrElse(name, "null")
    case TIndex(r, _, _) => promotedOwner(r)
    case _               => "null"

  /** The dereference a place walk bottoms out at, following **receivers only**.
   *
   * A place is a chain of field and element steps over something with an address, and the thing at
   * the end of that chain is what decides who owns the storage. The index *expressions* along the
   * way are not part of the chain — `g.rows[p.i]` reaches `p` while it works out which row, and `p`
   * owns nothing here — so this follows the receiver of each step and nothing else.
   *
   * An element step is followed only through an array, because an array's elements are its own
   * storage. Through a slice they are somebody else's, and the slice already carries who.
   */
  private def spineRoot(place: TExpr): Option[TDeref] = place match
    case TField(r, _, _)                                => spineRoot(r)
    case TIndex(r, _, _) if r.ty.isInstanceOf[Type.Array] => spineRoot(r)
    case d: TDeref                                      => Some(d)
    case _                                              => None

  /** The address of an array place together with whatever keeps its storage alive: the box it lives
   * inside, the buffer a promotion moved it to, or nothing at all for storage on the frame.
   *
   * A place rooted at a **counted reference** is the box's, so the reference is evaluated once here
   * and the walk to the field continues from the payload it addresses — which is what lets a view of
   * a fixed array inside a `&Struct` name that box as its owner (`05`). Reaching for it structurally
   * rather than watching what the ordinary walk happened to compute is what keeps a `&Struct` inside
   * another `&Struct` honest: the box that owns the storage is the **innermost** one on the chain,
   * and the ordinary walk evaluates the outermost first.
   */
  protected def addressOwned(place: TExpr): (String, String) =
    spineRoot(place) match
      case Some(root @ TDeref(operand, inner)) if operand.ty.isInstanceOf[Type.Ref] =>
        val box = genExpr(operand)
        val at  = freshTemp()
        emit(s"$at = getelementptr ${boxName(inner)}, ptr $box, i32 0, i32 $headerFields")
        (box, addressUnder(place, root, at))

      case _ => (promotedOwner(place), address(place))

  /** The rest of a place walk, once its root has been evaluated and reached. Every step is the one
   * `address` takes; what differs is only where the chain starts.
   */
  private def addressUnder(place: TExpr, root: TDeref, at: String): String = place match
    case p if p eq root                          => at
    case TField(r, _, ty) if Type.zeroSized(ty)  => addressUnder(r, root, at)

    case TField(r, index, _) =>
      val base = addressUnder(r, root, at)
      val x    = freshTemp()
      emit(s"$x = getelementptr ${r.ty.llvm}, ptr $base, i32 0, i32 ${fieldSlot(r.ty, index)}")
      x

    case TIndex(r, index, _) =>
      val Type.Array(n, elem) = r.ty: @unchecked
      val base = addressUnder(r, root, at)
      val i    = widen64(index)
      boundsCheck(i, n.toString)
      val x = freshTemp(); emit(s"$x = getelementptr ${elem.llvm}, ptr $base, i64 $i")
      x

    case other => address(other)

  protected def genBuffer(elem: Type, n: String): (String, String) = {
    val bn = bufName(elem)
    checked = true

    val e1   = freshTemp(); emit(s"$e1 = getelementptr ${elem.llvm}, ptr null, i64 1")
    val esz  = freshTemp(); emit(s"$esz = ptrtoint ptr $e1 to i64")
    val h1   = freshTemp(); emit(s"$h1 = getelementptr $bn, ptr null, i32 0, i32 ${headerFields + 1}")
    val hsz  = freshTemp(); emit(s"$hsz = ptrtoint ptr $h1 to i64")

    val mul   = freshTemp(); emit(s"$mul = call { i64, i1 } @llvm.umul.with.overflow.i64(i64 $n, i64 $esz)")
    val bytes = freshTemp(); emit(s"$bytes = extractvalue { i64, i1 } $mul, 0")
    val over1 = freshTemp(); emit(s"$over1 = extractvalue { i64, i1 } $mul, 1")
    val add   = freshTemp(); emit(s"$add = call { i64, i1 } @llvm.uadd.with.overflow.i64(i64 $bytes, i64 $hsz)")
    val total = freshTemp(); emit(s"$total = extractvalue { i64, i1 } $add, 0")
    val over2 = freshTemp(); emit(s"$over2 = extractvalue { i64, i1 } $add, 1")
    val over  = freshTemp(); emit(s"$over = or i1 $over1, $over2")
    val fits  = freshTemp(); emit(s"$fits = xor i1 $over, true")
    trapUnless(fits, "size")

    val p   = freshTemp(); emit(s"$p = call ptr @malloc(i64 $total)")
    val got = freshTemp(); emit(s"$got = icmp ne ptr $p, null")
    trapUnless(got, "alloc")

    emit(s"store i64 1, ptr $p")
    val hook = freshTemp(); emit(s"$hook = getelementptr $bn, ptr $p, i32 0, i32 1")
    emit(s"store ptr ${dropBufFn(elem)}, ptr $hook")
    val wc   = freshTemp(); emit(s"$wc = getelementptr $bn, ptr $p, i32 0, i32 2")
    emit(s"store i64 1, ptr $wc")
    val lenp = freshTemp(); emit(s"$lenp = getelementptr $bn, ptr $p, i32 0, i32 $headerFields")
    emit(s"store i64 $n, ptr $lenp")
    val data = freshTemp(); emit(s"$data = getelementptr $bn, ptr $p, i32 0, i32 ${headerFields + 1}")

    (p, data)
  }

  /** The view of a whole buffer: the box keeps the elements alive, and the one count it was made
   * with is the count this view holds.
   */
  protected def bufferView(sliceTy: Type.Slice, box: String, data: String, n: String): String = {
    maybeHeap = true

    val withOwner = freshTemp(); emit(s"$withOwner = insertvalue ${sliceTy.llvm} zeroinitializer, ptr $box, 0")
    val withPtr   = freshTemp(); emit(s"$withPtr = insertvalue ${sliceTy.llvm} $withOwner, ptr $data, 1")
    val whole     = freshTemp(); emit(s"$whole = insertvalue ${sliceTy.llvm} $withPtr, i64 $n, 2")

    ownTemp(whole, sliceTy)
  }

  /** An index at 64 bits, keeping its signedness so a negative one stays negative through the
   * widening and then fails the unsigned bounds test.
   */
  protected def widen64(index: TExpr): String = Type.underlying(index.ty) match
    case i: Type.Integer => convert(i, Type.Integer(64, i.signed), genExpr(index))
    case other           => sys.error(s"unreachable index of type ${other.llvm}")

  /** Traps unless `i` names an element that exists. The comparison is unsigned at 64 bits, so a
   * negative index arrives as a very large one and fails the same test.
   */
  protected def boundsCheck(i: String, len: String): Unit = {
    val ok = freshTemp(); emit(s"$ok = icmp ult i64 $i, $len")
    trapUnless(ok, "bounds")
  }

  /** Builds an enum value from already-lowered payload values: the tag, then the variant's payload
   * aggregate written into the region every variant shares.
   *
   * The region is a union, so the payload cannot be dropped in with `insertvalue` — an aggregate
   * value has no operation that reinterprets part of it as another type. It goes through a stack
   * slot instead: written at the variant's own type, read back at the enum's. A nullary variant
   * never touches the region and so needs no slot at all.
   */
  protected def enumValue(en: Type.Enum, variant: Type.EnumVariant, vals: List[String]): String =
    if en.simple then variant.tag.toString
    else if !variant.carries then
      val tagged = freshTemp()
      emit(s"$tagged = insertvalue ${en.llvm} undef, i32 ${variant.tag}, 0")
      tagged
    else
      var payload = "undef"
      for (v, i) <- vals.zipWithIndex if !Type.zeroSized(variant.fields(i)._2) do
        val r = freshTemp()
        emit(s"$r = insertvalue ${en.payloadLlvm(variant)} $payload, " +
          s"${variant.fields(i)._2.llvm} $v, ${variant.slot(i)}")
        payload = r
      val slot = scratchSlot(en.llvm)
      emit(s"store i32 ${variant.tag}, ptr $slot")
      val p = payloadPtr(en, slot)
      emit(s"store ${en.payloadLlvm(variant)} $payload, ptr $p")
      val r = freshTemp()
      emit(s"$r = load ${en.llvm}, ptr $slot")
      r

  /** Whether an integer `v` of type `vt` equals one of the enum's declared discriminants — the
   * membership test both integer-to-enum conversions share. Every comparison is done at 64 bits
   * so a wide source cannot alias a narrow discriminant, matching how the checked `char`
   * conversion widens before testing.
   */
  protected def enumMembership(en: Type.Enum, vt: Type.Integer, v: String): String = {
    val wide = convert(vt, Type.Integer(64, vt.signed), v)
    en.variants.map { variant =>
      val eq = freshTemp(); emit(s"$eq = icmp eq i64 $wide, ${variant.tag}")
      eq
    }.reduceOption(orI1).getOrElse("false")
  }

  /** `Color(n)` — the checked cast. Traps unless `n` is a declared discriminant, then stores the
   * value at the enum's underlying width, which is the enum's representation.
   */
  protected def genEnumFromInt(value: TExpr, en: Type.Enum): String = {
    val vt = value.ty.asInstanceOf[Type.Integer]
    val v  = genExpr(value)
    trapUnless(enumMembership(en, vt, v), "enum")
    convert(vt, en.underlying, v)
  }

  /** `Color.try(n)` — the fallible constructor. The membership test picks the branch: a match
   * builds `Some(n as Color)`, a miss builds `None`. The result is an ordinary `Option[Color]`,
   * whose element has no refcount, so a merge slot needs no ownership bookkeeping.
   */
  protected def genEnumTry(value: TExpr, en: Type.Enum, optTy: Type.Enum,
                         some: Type.EnumVariant, none: Type.EnumVariant): String = {
    val vt    = value.ty.asInstanceOf[Type.Integer]
    val v     = genExpr(value)
    val ok    = enumMembership(en, vt, v)
    val slot  = emitAlloca(freshTemp(), optTy.llvm)
    val someL = freshLabel("try.some")
    val noneL = freshLabel("try.none")
    val endL  = freshLabel("try.end")

    emitTerm(s"br i1 $ok, label %$someL, label %$noneL")
    emitLabel(someL)
    val ev = convert(vt, en.underlying, v)
    emit(s"store ${optTy.llvm} ${enumValue(optTy, some, List(ev))}, ptr $slot")
    emitTerm(s"br label %$endL")
    emitLabel(noneL)
    emit(s"store ${optTy.llvm} ${enumValue(optTy, none, Nil)}, ptr $slot")
    emitTerm(s"br label %$endL")
    emitLabel(endL)
    val r = freshTemp(); emit(s"$r = load ${optTy.llvm}, ptr $slot"); r
  }

  /** Weakens a reference: the same address, counted in the box's third word instead of its first
   * (`03`). The strong count is untouched, which is the whole point — the edge this makes keeps
   * nothing alive.
   *
   * The value is registered as an owned temporary so the region that produced it gives the weak
   * share back, exactly as it would for a reference. What ends up holding it long-term is the slot
   * it is stored into, which takes a share of its own when it is written.
   */
  protected def genDowngrade(value: TExpr, weakTy: Type.Weak): String = {
    val v = genExpr(value)
    retainValue(weakTy, v)
    ownTemp(v, weakTy)
  }

  /** `w.get()` — asks the box whether the object is still there. A live one comes back with a count
   * taken for the caller, so what the `Some` carries is an ordinary owned reference; a dead one
   * gives a null address, which is the `None`.
   *
   * A trait object is rebuilt around the answer rather than reused: the method table it was carrying
   * is a constant that says nothing about whether the object is alive, so it is put back beside the
   * address only on the arm where there is one.
   */
  protected def genUpgrade(value: TExpr, optTy: Type.Enum, some: Type.EnumVariant,
                           none: Type.EnumVariant): String = {
    weakHeap = true
    val fat  = value.ty.asInstanceOf[Type.Weak].inner.isInstanceOf[Type.Trait]
    val v    = genExpr(value)
    val addr = if fat then { val b = freshTemp(); emit(s"$b = extractvalue ${Type.fatPointer} $v, 1"); b } else v

    val got   = freshTemp(); emit(s"$got = call ptr @arc.upgrade(ptr $addr)")
    val live  = freshTemp(); emit(s"$live = icmp ne ptr $got, null")
    val slot  = emitAlloca(freshTemp(), optTy.llvm)
    val someL = freshLabel("weak.live")
    val noneL = freshLabel("weak.gone")
    val endL  = freshLabel("weak.end")

    emitTerm(s"br i1 $live, label %$someL, label %$noneL")
    emitLabel(someL)
    val strong =
      if !fat then got
      else
        val tbl = freshTemp(); emit(s"$tbl = extractvalue ${Type.fatPointer} $v, 0")
        val f0  = freshTemp(); emit(s"$f0 = insertvalue ${Type.fatPointer} undef, ptr $tbl, 0")
        val f1  = freshTemp(); emit(s"$f1 = insertvalue ${Type.fatPointer} $f0, ptr $got, 1")
        f1
    emit(s"store ${optTy.llvm} ${enumValue(optTy, some, List(strong))}, ptr $slot")
    emitTerm(s"br label %$endL")
    emitLabel(noneL)
    emit(s"store ${optTy.llvm} ${enumValue(optTy, none, Nil)}, ptr $slot")
    emitTerm(s"br label %$endL")
    emitLabel(endL)
    val r = freshTemp(); emit(s"$r = load ${optTy.llvm}, ptr $slot")
    ownTemp(r, optTy)
  }

  /** Reads every field of a variant's payload out of an enum value. */
  protected def payloadFields(en: Type.Enum, variant: Type.EnumVariant, value: String): List[String] =
    if !variant.carries then Nil
    else
      val p = enumPayload(en, variant, value)
      variant.fields.indices.map { i =>
        if Type.zeroSized(variant.fields(i)._2) then ""
        else
          val f = freshTemp()
          emit(s"$f = extractvalue ${en.payloadLlvm(variant)} $p, ${variant.slot(i)}")
          f
      }.toList

  /** `expr?` — on success the payload becomes the expression's value; on failure the function
   * returns immediately with the failure re-wrapped in its own return type, carrying the error
   * payload across unchanged.
   */
  protected def genTry(
      operand: TExpr,
      ok: Type.EnumVariant,
      fail: Type.EnumVariant,
      retEnum: Type.Enum,
      retFail: Type.EnumVariant,
  ): String = {
    val en = operand.ty.asInstanceOf[Type.Enum]
    val v  = genExpr(operand)

    val tag  = freshTemp(); emit(s"$tag = extractvalue ${en.llvm} $v, 0")
    val isOk = freshTemp(); emit(s"$isOk = icmp eq i32 $tag, ${ok.tag}")

    val okL   = freshLabel("try.ok")
    val failL = freshLabel("try.fail")
    emitTerm(s"br i1 $isOk, label %$okL, label %$failL")

    emitLabel(failL)
    val failed = enumValue(retEnum, retFail, payloadFields(en, fail, v))
    retainValue(retEnum, failed)
    releaseAll()
    emitTerm(s"ret ${retEnum.llvm} $failed")

    emitLabel(okL)
    payloadFields(en, ok, v).head
  }
}
