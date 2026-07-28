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
    val (base, len, elem) = receiver.ty match
      case Type.Array(n, e) => (address(receiver), n.toString, e)
      case w: Type.View =>
        val v = genExpr(receiver)
        val p = freshTemp(); emit(s"$p = extractvalue ${w.llvm} $v, 1")
        val l = freshTemp(); emit(s"$l = extractvalue ${w.llvm} $v, 2")
        (p, l, w.elem)
      case other => sys.error(s"unreachable index into ${other.llvm}")

    val i = widen64(index)
    boundsCheck(i, len)
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
        val p = freshTemp(); emit(s"$p = getelementptr ${boxName(array)}, ptr $r, i32 0, i32 2")
        (r, p, n.toString)
      case s: Type.View =>
        val v = genExpr(base)
        val o = freshTemp(); emit(s"$o = extractvalue ${s.llvm} $v, 0")
        val p = freshTemp(); emit(s"$p = extractvalue ${s.llvm} $v, 1")
        val l = freshTemp(); emit(s"$l = extractvalue ${s.llvm} $v, 2")
        (o, p, l)
      // Storage this frame owns, or a `*T` region: there is nothing to keep alive, so the
      // owner is null and counting it is a no-op. The escape analysis is what makes the first
      // of those safe, and nothing makes the second safe — that is what `*T` is.
      case Type.Array(n, _)             => ("null", address(base), n.toString)
      case Type.Ptr(Type.Array(n, _))   => ("null", genExpr(base), n.toString)
      case other                        => sys.error(s"unreachable slice of ${other.llvm}")

    val start = lo.map(widen64).getOrElse("0")

    // The check is on the half-open interval the view ends up naming. An inclusive high end
    // additionally has to name an element that exists, which is also what stops `hi + 1` from
    // wrapping past the end.
    val end = hi match
      case None => len
      case Some(h) =>
        val v = widen64(h)
        if !inclusive then v
        else
          val within = freshTemp(); emit(s"$within = icmp ult i64 $v, $len")
          trapUnless(within, "bounds")
          val e = freshTemp(); emit(s"$e = add i64 $v, 1"); e

    if hi.isDefined && !inclusive then
      val fits = freshTemp(); emit(s"$fits = icmp ule i64 $end, $len")
      trapUnless(fits, "bounds")

    val ordered = freshTemp(); emit(s"$ordered = icmp ule i64 $start, $end")
    trapUnless(ordered, "bounds")

    // A substring has to be a string, so both ends must fall between characters. This runs after
    // the bounds checks, which is what makes reading the byte at either end safe.
    if sliceTy == Type.Str then
      trapUnless(strBoundary(first, len, start), "boundary")
      trapUnless(strBoundary(first, len, end), "boundary")

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
  protected def genBuffer(elem: Type, n: String): (String, String) = {
    val bn = bufName(elem)
    checked = true

    val e1   = freshTemp(); emit(s"$e1 = getelementptr ${elem.llvm}, ptr null, i64 1")
    val esz  = freshTemp(); emit(s"$esz = ptrtoint ptr $e1 to i64")
    val h1   = freshTemp(); emit(s"$h1 = getelementptr $bn, ptr null, i32 0, i32 3")
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
    val lenp = freshTemp(); emit(s"$lenp = getelementptr $bn, ptr $p, i32 0, i32 2")
    emit(s"store i64 $n, ptr $lenp")
    val data = freshTemp(); emit(s"$data = getelementptr $bn, ptr $p, i32 0, i32 3")

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
