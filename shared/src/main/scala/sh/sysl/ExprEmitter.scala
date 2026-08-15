package sh.sysl

import ir.{Access, Arg, BinOp, CastOp, FCmp, ICmp, Inst, LType, Val}

/** The expression dispatch.
 *
 * This is the widest single `match` in the compiler and it stays one, because that is what it is:
 * every arm's outermost pattern is a node type, so the cases cannot shadow each other and the
 * compiler's exhaustiveness check is the guarantee that a new node has somewhere to go. Splitting
 * it across traits would trade that for a fall-through nobody could read.
 *
 * What the arms have in common is that none of them decides anything. A type is read off the node,
 * an instruction is selected from it, and the work that is more than an instruction has already
 * been lifted into a named method — `genSlice`, `genMatch`, `genTry`, `genForEach` on the traits
 * underneath, and the call seam and the arithmetic on `CallEmitter` and `ArithEmitter`. An arm that
 * is still long here is long because the *emitted code* is long, not because a decision is being
 * made in it.
 */
trait ExprEmitter extends ArithEmitter {

  /** Lowers an expression, returning the register or immediate holding its value (empty for a
   * unit-typed expression, whose value is never read).
   */
  protected def genExpr(expr: TExpr): String = expr match
    case TIntLit(v, _) => v.toString
    case TStrLit(s)    => stringValue(s)
    // Every interned constant is already laid down NUL-terminated, so a C string is the same global
    // read as a plain pointer — the terminator the sysl string ignores is exactly what C reads by.
    case TCStrLit(s)   => stringGlobal(s).render
    case TBoolLit(b)   => if b then "1" else "0"
    // A trait object is two words, so its null is a zeroed pair rather than a bare address.
    case TNullLit(ty)  => zero(ty)
    case TUnitLit()    => ""
    case TZero(ty)     => zero(ty)

    // A large one is built where it is going to live and read back out only because this caller
    // asked for a value; a small one is the `insertvalue` chain it always was.
    case e @ TArrayLit(_, arrayTy) if layout.indirect(arrayTy) => throughSlot(e)

    case TArrayLit(elems, arrayTy) =>
      val vals = elems.map(genExpr)
      var acc  = "zeroinitializer"
      for (v, i) <- vals.zipWithIndex do
        val r = freshTemp()
        emit(s"$r = insertvalue ${arrayTy.llvm} $acc, ${arrayTy.elem.llvm} $v, $i")
        acc = r
      acc

    // Built through memory with a loop rather than as an `insertvalue` chain, for the reason the
    // ARC walk gives: the count is a compile-time constant but it can be very large, and a repeat
    // count is where someone writes a large one on purpose. The value is generated once, above the
    // loop — every element is a copy of that one evaluation. Its references are borrowed here and
    // retained by whatever binds the array, whose ARC walk visits all n elements.
    case TArrayFill(value, arrayTy) if arrayTy.length == 0 =>
      genExpr(value); "zeroinitializer"

    case e: TArrayFill => throughSlot(e)

    // The same two forms, sized and owned rather than laid out in a frame. Each element the buffer
    // takes is a share of its own — the box holds them until its hook lets them go — so the value
    // that lands in one is retained as it is stored, exactly as a slot that binds an array is.
    case TBufLit(elems, sliceTy) =>
      val vals       = elems.map(genExpr)
      val (box, data) = genBuffer(sliceTy.elem, vals.length.toString)

      for (v, i) <- vals.zipWithIndex do
        retainValue(sliceTy.elem, v)
        val ep = freshTemp(); emit(Inst.Gep(Val.Raw(ep), sliceTy.elem.lty, Val.Raw(data), List(Arg(wordLty, Val.Int(i)))))
        emit(Inst.Store(sliceTy.elem.lty, Val.Raw(v), Val.Raw(ep), Access.Plain))

      bufferView(sliceTy, box, data, vals.length.toString)

    // The value is generated once, above the loop, which is what makes `[tick(); n]` one call
    // whose result lands in n places — the repeat's own rule (`07`), and the reason the count may
    // be zero and the call still happen.
    case TBufFill(value, count, sliceTy) =>
      val v           = genExpr(value)
      val n           = widenIndex(count)
      val (box, data) = genBuffer(sliceTy.elem, n)

      fillElements(sliceTy.elem, data, n, v)
      bufferView(sliceTy, box, data, n)

    case e @ TIndex(receiver, index, ty) =>
      val p = elementAddr(receiver, index)
      val r = freshTemp(); emit(s"$r = load${vol(e)} ${ty.llvm}, ptr $p"); r

    case TSlice(base, lo, hi, inclusive, sliceTy) =>
      genSlice(base, lo, hi, inclusive, sliceTy)

    case TLen(receiver, _) =>
      receiver.ty match
        case Type.Array(n, _) => genExpr(receiver); n.toString
        case w: Type.View =>
          val v = genExpr(receiver)
          val r = freshTemp(); emit(Inst.Extract(Val.Raw(r), w.lty, Val.Raw(v), List(2))); r
        case other => sys.error(s"unreachable length of ${other.llvm}")

    // A string and a `[]u8` are the same three words, so looking at one as the other is nothing
    // at all — the guarantee that was given up is the analyzer's business, not the machine's.
    case TBytes(receiver) =>
      genExpr(receiver)

    // A narrower float is the `double` constant rounded to it, which folds away entirely.
    case TFloatLit(bits, ty) =>
      if ty == Type.Real then bits
      else { val r = freshTemp(); emit(Inst.Cast(Val.Raw(r), CastOp.FPTrunc, LType.F(64), Val.Raw(bits), ty.lty)); r }

    case TCast(operand, ty) =>
      // A constrained operand converts from its base representation — `f64(m)` reaches the double a
      // `Meters` is, `int(age)` the i32 an `Age` is.
      convert(Type.underlying(operand.ty), ty, genExpr(operand))

    case TConstrainedValid(value, c) =>
      val v    = genExpr(value)
      val base = Type.underlying(c.base)
      val geLo = compareValue(">=", base, v, c.lo.get.toBigInt.toString)
      val leHi = compareValue(if c.exclusiveHi then "<" else "<=", base, v, c.hi.get.toBigInt.toString)
      val r = freshTemp(); emit(Inst.Bin(Val.Raw(r), BinOp.And, i1, Val.Raw(geLo), Val.Raw(leHi))); r

    case TConstrainedStep(value, c, up, _) =>
      val v    = genExpr(value)
      val base = Type.underlying(c.base)
      val last = if c.exclusiveHi then c.hi.get - 1 else c.hi.get
      // `Succ` traps at `Last`, `Pred` at `First`; the value is otherwise one step along the base.
      if up then trapUnless(Val.Raw(compareValue("<", base, v, last.toBigInt.toString)), "succ")
      else trapUnless(Val.Raw(compareValue(">", base, v, c.lo.get.toBigInt.toString)), "pred")
      val r = freshTemp(); emit(s"$r = ${if up then "add" else "sub"} ${base.llvm} $v, 1"); r

    case TConstrainedCheck(value, target) =>
      val v = genExpr(value)
      emitConstraintChecks(v, target)
      v

    case TStructInvCheck(value, struct, invFn) =>
      val v = genExpr(value)
      emitInvCheck(v, struct, invFn)
      v

    case TRecheck(after, recv, struct, invFn) =>
      // The write or the call runs first (yielding the value the expression is), then the struct it
      // could have changed is re-read so the invariant sees the new fields.
      val v = genExpr(after)
      emitInvCheck(genExpr(recv), struct, invFn)
      v

    // Nothing is stored for a zero-sized binding, so there is nothing to read back.
    case TLoad(_, ty) if Type.zeroSized(ty) => ""

    // The qualifier comes off the value's type for an ordinary local, whose slot is its own, and off
    // the recorded storage for a `ref`, whose slot is somebody else's and may be a register
    // (`03 § ref`, `03 § Device memory`).
    case TLoad(name, ty) =>
      val q = if refStorage.contains(name) then qualifier(refStorage(name)) else qualifier(ty)
      val r = freshTemp(); emit(s"$r = load$q ${ty.llvm}, ptr %$name.addr"); r

    case TResult(_) =>
      resultSSA.getOrElse(sys.error("'result' lowered outside an ensure postcondition"))

    case TOld(index, ty) =>
      val r = freshTemp(); emit(s"$r = load ${ty.llvm}, ptr %old.$index.addr"); r

    case TGlobal(symbol, ty, _) =>
      val r = freshTemp(); emit(s"$r = load${qualifier(ty)} ${ty.llvm}, ptr @$symbol"); r

    case e @ TDeref(operand, ty) =>
      val p = payloadAddr(operand)
      val r = freshTemp(); emit(s"$r = load${vol(e)} ${ty.llvm}, ptr $p"); r

    case TAddrOf(place, _) =>
      address(place)

    case TBox(value, refTy) =>
      genBox(value, refTy)

    case TStore(place, value, ty) if Type.zeroSized(ty) =>
      genExpr(value)
      address(place)
      ""

    // The three forms that write through a place go through `placeAddr`/`loadPlace`/`storePlace`
    // rather than through `address` and a `load`, because a **bitfield** is a range of a container
    // and has no address of its own (`Bitfields`). For every other place those three are the address
    // and the load and the store, unchanged.
    case TStore(place, value, ty) =>
      val v = genExpr(value)
      val p = placeAddr(place)
      storePlace(place, p, v)
      v

    case TUpdate(place, op, value, ty, dispatch, check) =>
      val p       = placeAddr(place)
      val cur     = loadPlace(place, p)
      val v       = genExpr(value)
      val updated = combine(op, ty, value.ty, dispatch, cur, v)

      for c <- check do emitConstraintChecks(updated, c)

      storePlace(place, p, updated, Some(cur))
      updated

    case TIncDec(place, op, pre, ty, check) =>
      val p   = placeAddr(place)
      val cur = loadPlace(place, p)
      val nv  = freshTemp(); emit(s"$nv = ${if op == "++" then "add" else "sub"} ${ty.llvm} $cur, 1")

      for c <- check do emitConstraintChecks(nv, c)

      storePlace(place, p, nv, Some(cur))
      if pre then nv else cur

    case TStr(arg) =>
      // A string renders to itself, its count already the argument's; every other type renders
      // into a fresh buffer this statement owns and releases.
      val v = genStr(arg)
      if arg.ty == Type.Str then v else ownTemp(v, Type.Str)

    case TFormat(arg, spec) =>
      ownTemp(genFormat(arg, spec), Type.Str)

    // Nothing to emit: dropping the ability to write is a fact about the type and not about the
    // three words, which are the same three words either way.
    case TConstView(arg) => genExpr(arg)

    // The bytes are copied into a string that owns them rather than viewed in place: a `[]u8` can
    // be written through afterwards, and a `string` whose bytes could change is not one that was
    // ever validated. The same copy is what `str(x)` finishes through, so both spend one allocation.
    case TFromBytes(arg) =>
      heap = true
      val fn  = request("sysl.str.from_bytes")(StringEmitter.fromBytes)
      val v   = genExpr(arg)
      val p   = freshTemp(); emit(Inst.Extract(Val.Raw(p), arg.ty.lty, Val.Raw(v), List(1)))
      val n   = freshTemp(); emit(Inst.Extract(Val.Raw(n), arg.ty.lty, Val.Raw(v), List(2)))
      val r   = freshTemp(); emit(s"$r = call ${Type.Str.llvm} @$fn(ptr $p, $word $n)")
      ownTemp(r, Type.Str)

    // Rendering into a buffer: a zeroed stack slot becomes the sink, the value writes itself into
    // it, and what landed there is copied into a string the statement owns. The slot is re-zeroed
    // on every arrival rather than once, since an alloca is hoisted to the entry block and a render
    // inside a loop meets the same one each time round.
    case TRender(value, method, spec, vslot) =>
      heap = true
      request("sysl.str.from_bytes")(StringEmitter.fromBytes)

      val table = bufferTable()
      val v     = genExpr(value)
      val s     = genExpr(spec)
      val slot  = emitAlloca(freshTemp(), bufferLayout)

      emit(s"store $bufferLayout zeroinitializer, ptr $slot")
      val a = freshTemp(); emit(s"$a = insertvalue ${Type.fatPointer} undef, ptr @$table, 0")
      val w = freshTemp(); emit(Inst.Insert(Val.Raw(w), LType.fat, Val.Raw(a), LType.Ptr, Val.Raw(slot), List(1)))

      // A trait object renders through the table it carries, so the callee and the receiver both
      // come out of the value: the data word is the receiver a slot's entry expects, exactly as it
      // is for any other call through one.
      vslot match
        case Some(n) =>
          val vt   = freshTemp(); emit(Inst.Extract(Val.Raw(vt), LType.fat, Val.Raw(v), List(0)))
          val data = freshTemp(); emit(Inst.Extract(Val.Raw(data), LType.fat, Val.Raw(v), List(1)))
          val e    = freshTemp(); emit(Inst.Gep(Val.Raw(e), LType.Ptr, Val.Raw(vt), List(Arg(wordLty, Val.Int(n)))))
          val fn   = freshTemp(); emit(Inst.Load(Val.Raw(fn), LType.Ptr, Val.Raw(e), Access.Plain))

          emit(s"call void $fn(ptr $data, ${Type.fatPointer} $w, ${spec.ty.llvm} $s)")
        case None =>
          emit(s"call void @$method(${value.ty.llvm} $v, ${Type.fatPointer} $w, ${spec.ty.llvm} $s)")

      val r = freshTemp()
      emit(s"$r = call ${Type.Str.llvm} @sysl.w.buf.finish(ptr $slot)")
      ownTemp(r, Type.Str)

    case TBinary(_, l, r, Type.Str) =>
      ownTemp(strConcat(genExpr(l), genExpr(r)), Type.Str)

    /** `p - q`, C's `ptrdiff_t`. The bytes between the two addresses, divided by the pointee's size
      * so the answer counts elements — the inverse of `&p[n]`, which strides by the same size.
      *
      * The divide is skipped for a one-byte pointee, which is not an optimization so much as the
      * common case: a `*u8` walk over bytes is what `memchr` and every other interior-pointer libc
      * function hands back, and `sdiv` by 1 would be noise in the emitted text a reader has to
      * discount.
      */
    case TBinary("-", l, r, _) if Type.underlying(l.ty).isInstanceOf[Type.Ptr] =>
      val stride = Type.underlying(l.ty) match
        case Type.Ptr(e) => layout.size(e)
        case _           => 1
      val (lv, rv) = (genExpr(l), genExpr(r))
      val (la, ra) = (freshTemp(), freshTemp())
      emit(Inst.Cast(Val.Raw(la), CastOp.PtrToInt, LType.Ptr, Val.Raw(lv), Type.isize.lty))
      emit(Inst.Cast(Val.Raw(ra), CastOp.PtrToInt, LType.Ptr, Val.Raw(rv), Type.isize.lty))
      val bytes = freshTemp()
      emit(Inst.Bin(Val.Raw(bytes), BinOp.Sub, Type.isize.lty, Val.Raw(la), Val.Raw(ra)))
      if stride <= 1 then bytes
      else
        val n = freshTemp()
        emit(Inst.Bin(Val.Raw(n), BinOp.SDiv, Type.isize.lty, Val.Raw(bytes), Val.Int(stride)))
        n

    case TBinary(op, l, r, _) =>
      // Arithmetic runs at the base representation — a derived type keeps its own identity in the
      // analyzer but is added, multiplied, and divided as the base it is laid out as. When an operand
      // was produced through a ranged type and the result could leave the base width, the operation
      // is overflow-detecting so a wrap cannot slip past the produce-site range check.
      val bt = Type.underlying(l.ty)
      val lv = genExpr(l)
      val rv = genExpr(r)

      bt match
        case it: Type.Integer if op == "<<" && isRanged(l.ty)  => checkedShl(it, lv, rv)
        case it: Type.Integer if overflowChecked(op, l, r, it) => checkedArith(op, it, lv, rv)
        case _                                                 => arith(op, bt, lv, rv)

    case TUnary("-", operand, ty) if Type.underlying(ty).isInstanceOf[Type.Integer] =>
      val v = genExpr(operand); val r = freshTemp(); emit(Inst.Bin(Val.Raw(r), BinOp.Sub, ty.lty, Val.Int(0), Val.Raw(v))); r
    case TUnary("-", operand, ty) if Type.underlying(ty).isInstanceOf[Type.Floating] =>
      val v = genExpr(operand); val r = freshTemp(); emit(s"$r = fneg ${ty.llvm} $v"); r
    case TUnary("!", operand, _) =>
      val v = genExpr(operand); val r = freshTemp(); emit(Inst.Bin(Val.Raw(r), BinOp.Xor, i1, Val.Raw(v), Val.Bool(true))); r
    case TUnary("~", operand, ty) =>
      val v = genExpr(operand); val r = freshTemp(); emit(Inst.Bin(Val.Raw(r), BinOp.Xor, ty.lty, Val.Raw(v), Val.Int(-1))); r
    case TUnary(op, _, _) =>
      sys.error(s"unreachable unary '$op'")

    // A fence orders the accesses around it and touches nothing, so there is no address to compute
    // and no value to hand back. `syncscope` is deliberately absent: the default is the whole
    // system, which is what a program sharing memory with another thread or a device needs.
    case TFence(ord) =>
      emit(s"fence ${Atomics.llvm(ord)}")
      "0"

    // One atomic access. `at` rather than the node's own type decides the width, since a store
    // answers nothing and would otherwise lower at `void`; the alignment is stated because LLVM
    // requires it on an atomic load or store and will not infer one.
    case TAtomic(op, addr, ops, ord, at, ty) =>
      val p    = genExpr(addr)
      val vs   = ops.map(genExpr)
      val ll   = at.llvm
      val ordr = Atomics.llvm(ord)
      val al   = layout.align(at)

      op match
        case "atomic_load" =>
          val r = freshTemp(); emit(s"$r = load atomic $ll, ptr $p $ordr, align $al"); r
        case "atomic_store" =>
          emit(s"store atomic $ll ${vs.head}, ptr $p $ordr, align $al")
          "0"
        // `cmpxchg` answers a pair — the value it found and whether it swapped — and what this hands
        // back is the value. A caller comparing it against what they expected learns the same thing
        // the flag would have told them, which is why the raw form has one result rather than a
        // tuple the library would immediately take apart.
        case "atomic_cas" =>
          val pair = freshTemp()
          emit(s"$pair = cmpxchg ptr $p, $ll ${vs.head}, $ll ${vs(1)} $ordr ${Atomics.failure(ord)}")
          val r = freshTemp(); emit(s"$r = extractvalue { $ll, i1 } $pair, 0"); r
        // The rest are one `atomicrmw`, which answers the value that was there *before* — the
        // property that makes an atomic increment usable as a ticket.
        case _ =>
          val kind = op.stripPrefix("atomic_") match
            case "swap" => "xchg"
            case k      => k
          val r = freshTemp(); emit(s"$r = atomicrmw $kind ptr $p, $ll ${vs.head} $ordr"); r

    // The operand is read into a register once and the comparisons index off that, which is the
    // whole reason these are a node rather than a tree of the operators they mean (`14 §5`).
    case TIntOp(op, operand, amount, width, ty) =>
      val v    = genExpr(operand)
      val n    = amount.map(genExpr)
      val bits = width.asInstanceOf[Type.Integer].bits
      val ll   = width.lty

      // The bits counted at the operand's own width, then resized to the `u32` the count is
      // answered in. A count is at most the width, so narrowing one from a type wider than 32 bits
      // cannot lose anything.
      def counted(base: String, of: String, zeroFlag: Boolean = false) =
        resize(intrinsic(base, ll, List(of), zeroFlag), width, ty)

      // `~x`, which is what turns a count of zeroes into a count of ones. The trait has both pairs
      // because the complement is the caller's to get wrong, not because the machine has four
      // instructions.
      def complement = { val r = freshTemp(); emit(s"$r = xor $ll $v, -1"); r }

      op match
        // `x < 0 ? -x : x`, and the negation is the wrapping one the language's own `-` is — so at
        // the most negative value both answer that value, rather than the member disagreeing with
        // the operator beside it.
        case "abs" =>
          val neg = freshTemp(); emit(s"$neg = sub $ll 0, $v")
          val lt  = freshTemp(); emit(s"$lt = icmp slt $ll $v, 0")
          val r   = freshTemp(); emit(s"$r = select i1 $lt, $ll $neg, $ll $v")
          r
        // Two comparisons rather than a subtraction of them, because `(x > 0) - (x < 0)` would have
        // to widen both booleans to the operand's width first and says less about what it computes.
        case "signum" =>
          val pos = freshTemp(); emit(s"$pos = icmp sgt $ll $v, 0")
          val neg = freshTemp(); emit(s"$neg = icmp slt $ll $v, 0")
          val hi  = freshTemp(); emit(s"$hi = select i1 $pos, $ll 1, $ll 0")
          val r   = freshTemp(); emit(s"$r = select i1 $neg, $ll -1, $ll $hi")
          r

        case "count_ones"  => counted("ctpop", v)
        case "count_zeros" => counted("ctpop", complement)

        // The `i1` the two counting intrinsics take says whether a zero operand is **poison**.
        // `false` is what makes zero answer the width instead, which is the answer the trait
        // documents and the only one that is the same number on every target.
        case "leading_zeros"  => counted("ctlz", v, zeroFlag = true)
        case "trailing_zeros" => counted("cttz", v, zeroFlag = true)
        case "leading_ones"   => counted("ctlz", complement, zeroFlag = true)
        case "trailing_ones"  => counted("cttz", complement, zeroFlag = true)

        case "reverse_bits" => intrinsic("bitreverse", ll, List(v))

        // A rotation is a funnel shift of the value with itself, which is how LLVM spells one: the
        // two halves are the same register, so the bits leaving the top arrive at the bottom.
        case "rotate_left"  => intrinsic("fshl", ll, List(v, v, rotateBy(n.get, amount.get.ty, bits)))
        case "rotate_right" => intrinsic("fshr", ll, List(v, v, rotateBy(n.get, amount.get.ty, bits)))

        case _ => sys.error(s"unreachable integer operation '$op'")

    // Short-circuit: `&&` evaluates its right side only when the left is true, `||` only when the
    // left is false — so a guard like `p != null && *p > 0` never runs the unsafe right side. The
    // result defaults to the left value and is overwritten by the right only when it is reached.
    case TLogical(op, l, r) =>
      val lv   = genExpr(l)
      val slot = emitAlloca(freshTemp(), "i1")
      emit(Inst.Store(i1, Val.Raw(lv), Val.Raw(slot), Access.Plain))
      val rhsL = freshLabel("sc.rhs")
      val endL = freshLabel("sc.end")
      if op == "&&" then emitTerm(Inst.CondBr(Val.Raw(lv), rhsL, endL))
      else emitTerm(Inst.CondBr(Val.Raw(lv), endL, rhsL))
      emitLabel(rhsL)
      // The right side gets its own temp region: anything it allocates is released before the
      // merge, and if the branch is skipped that code never runs at all.
      pushTemps()
      val rv = genExpr(r)
      emit(Inst.Store(i1, Val.Raw(rv), Val.Raw(slot), Access.Plain))
      popTemps()
      emitTerm(Inst.Br(endL))
      emitLabel(endL)
      val res = freshTemp(); emit(Inst.Load(Val.Raw(res), i1, Val.Raw(slot), Access.Plain)); res

    // One comparison has nothing to short-circuit, so it stays straight-line — which is what the
    // overwhelming majority of comparisons are.
    case TCompare(List(l, r), List(c)) =>
      val lv = genExpr(l)
      comparison(c, l.ty, lv, genExpr(r))

    // A **chain** short-circuits: `a < b < c` stops at the first comparison that fails, so a
    // side-effecting later operand does not run. That is what `01` specifies, and what writing the
    // chain out as `&&` already did.
    //
    // Each operand is still evaluated **exactly once**. Operand `k+1` is compared against operand
    // `k`, so the shared middle value has to be the same value both times — which is why this is
    // not a rewrite into `&&` over separate comparisons, and why the operands cannot simply be
    // evaluated where they are used.
    //
    // That sharing is also what makes the ownership bookkeeping interesting: an operand evaluated
    // in one block is used again in the next, so its temporaries outlive the block that made them
    // and cannot be released there. Each block therefore opens its own region, and the exits
    // **unwind them in reverse** — a path that leaves the chain early passes through exactly the
    // pops for the regions it entered, and no others. Where nothing is owned, which is the usual
    // case, every pop is empty and the ladder is branches alone.
    case TCompare(operands, cmps) =>
      val slot  = emitAlloca(freshTemp(), "i1")
      val exits = cmps.indices.map(_ => freshLabel("cmp.exit")).toList
      val endL  = freshLabel("cmp.end")

      var left = ""

      for k <- cmps.indices do
        pushTemps()
        if k == 0 then left = genExpr(operands.head)
        val right = genExpr(operands(k + 1))
        val c     = comparison(cmps(k), operands(k).ty, left, right)

        emit(Inst.Store(i1, Val.Raw(c), Val.Raw(slot), Access.Plain))

        if k == cmps.length - 1 then emitTerm(Inst.Br(exits(k)))
        else
          val nextL = freshLabel("cmp.next")
          emitTerm(Inst.CondBr(Val.Raw(c), nextL, exits(k)))
          emitLabel(nextL)

        left = right

      for k <- cmps.indices.reverse do
        emitLabel(exits(k))
        popTemps()
        emitTerm(Inst.Br(if k == 0 then endL else exits(k - 1)))

      emitLabel(endL)
      val res = freshTemp(); emit(Inst.Load(Val.Raw(res), i1, Val.Raw(slot), Access.Plain)); res

    case TSeq(exprs) =>
      exprs.foreach(genExpr); ""

    // One copy of an unrolled `for const` (`10 §10`), which is a block wherever it stands. A copy
    // that yields nothing is emitted for its effects, which is what every copy of a loop body does.
    case TBlockExpr(b) =>
      if Type.zeroSized(b.ty) then { genBlockVoid(b); "" }
      else genBlockValue(b)

    // A function's address is the symbol its **C-callable** entry is defined under, which is a
    // constant — there is nothing to compute and nothing to load, the way there is for the address
    // of a variable. For an exported function that entry is the thunk rather than the definition
    // (`CallEmitter.entryOf`), since a `*extern` may only hold something C can call.
    case TFuncAddr(_, entry, _) => s"@${entryOf(entry)}"

    // A call through one goes out under C's convention, because that is what the type said was at
    // the other end. It reuses the foreign path entire: what a `call` names in front of an indirect
    // callee is the result type and then the value, exactly where a direct one names the symbol.
    case TCallPtr(callee, args, _, ty) =>
      genForeignCall(s"${foreignResultType(ty)} ${genExpr(callee)}", args, ty)

    // The last thing a recursive function does, where it is a call to itself: a jump to its own
    // entry over the frame it already has (`TailCalls`).
    case c: TCall if isTailCall(c) => genTailSelfCall(c.args)

    // A call to a foreign function is lowered under the other side's convention rather than sysl's
    // own, which is a difference only an aggregate can see (`ForeignEmitter`).
    case TCall(name, args, ty, _) if foreigns.contains(name) =>
      genForeignCall(calleeOf(name, ty), args, ty)

    // A call to something declared `-> never` does not come back, so the block ends at it: what
    // follows in the same block is unreachable and `emit` drops it, which is exactly why a
    // diverging arm needs no special handling anywhere else.
    case TCall(name, args, ty, _) =>
      val staged = args.map(argValue)

      // `17 §4`: a call the compiler can see is a call to the same body checks that the measure has
      // gone down. It sits before the call rather than inside the callee, which is what lets it be
      // made out of values already in hand.
      if checksVariant(name) then genVariantAtCall(staged)
      genSyslCall(calleeOf(name, ty), formatArgs(staged), ty, None)

    // Erasing costs one word: the value goes on pointing where it pointed, and the table for the
    // type it is losing is a constant beside it. Nothing is retained — a counted object holds the
    // count its operand already had, which is what makes `f(&T)` and `f(&Trait)` the same handover.
    case TErase(operand, vtable, _) =>
      val d = genExpr(operand)
      val a = freshTemp(); emit(s"$a = insertvalue ${Type.fatPointer} undef, ptr @$vtable, 0")
      val b = freshTemp(); emit(Inst.Insert(Val.Raw(b), LType.fat, Val.Raw(a), LType.Ptr, Val.Raw(d), List(1)))
      b

    // A call whose callee is a word in the object's table rather than a name. The data word goes in
    // front of the declared arguments, which is the shape every slot was built to.
    case TVCall(receiver, slot, args, ty, _) =>
      val obj     = genExpr(receiver)
      val table   = freshTemp(); emit(Inst.Extract(Val.Raw(table), LType.fat, Val.Raw(obj), List(0)))
      val data    = freshTemp(); emit(Inst.Extract(Val.Raw(data), LType.fat, Val.Raw(obj), List(1)))
      val argVals = argList(args)
      val entry   = freshTemp(); emit(Inst.Gep(Val.Raw(entry), LType.Ptr, Val.Raw(table), List(Arg(wordLty, Val.Int(slot)))))
      val fn      = freshTemp(); emit(Inst.Load(Val.Raw(fn), LType.Ptr, Val.Raw(entry), Access.Plain))
      genSyslCall(s"${syslResult(ty)} $fn", Arg(LType.Ptr, Val.Raw(data)) :: argVals, ty, None)

    // The tail walk is the ABI's, so all three are LLVM's own: two intrinsic calls and the one
    // instruction whose lowering every backend supplies for it.
    case TVaStart(ap) =>
      usesVarargs = true
      emit(s"call void @llvm.va_start.p0(ptr ${genExpr(ap)})"); ""

    case TVaEnd(ap) =>
      usesVarargs = true
      emit(s"call void @llvm.va_end.p0(ptr ${genExpr(ap)})"); ""

    case TVaArg(ap, ty) =>
      val r = freshTemp()
      emit(s"$r = va_arg ptr ${genExpr(ap)}, ${ty.llvm}")
      r

    // The one place the C ABI is not the same on every machine, so the one place codegen reads the
    // target (`targets.md`). All three answers are a `ptr`, and all three start from the address of
    // the walk — what differs is whether the callee is handed that address, the value in it, or the
    // address of a copy of it.
    case TVaPass(ap) =>
      val addr = genExpr(ap)

      target.vaList match
        case VaListAbi.Address => addr

        case VaListAbi.Loaded =>
          val r = freshTemp(); emit(Inst.Load(Val.Raw(r), LType.Ptr, Val.Raw(addr), Access.Plain)); r

        case VaListAbi.Copied =>
          usesMemcpy = true
          val copy = emitAlloca(freshTemp(), Type.VaList.llvm)
          emit(s"call void @llvm.memcpy.p0.p0.i64(ptr align 8 $copy, ptr align 8 $addr, " +
            s"i64 ${target.vaListBytes}, i1 false)")
          copy

    case TVaCopy(dst, src) =>
      usesVaCopy = true
      // Both addresses are produced before either is used, so a copy onto a list read out of the
      // same expression cannot see a half-written destination.
      val d = genExpr(dst)
      val s = genExpr(src)
      emit(s"call void @llvm.va_copy.p0(ptr $d, ptr $s)"); ""

    case e @ TStructNew(struct, _) if layout.indirect(struct) => throughSlot(e)

    case e @ TEnumNew(en, _, _) if !en.simple && layout.indirect(en) => throughSlot(e)

    // A bitfield struct is one integer, so it is built by or-ing every field into place rather than
    // inserting each into a slot of its own — and the container then goes into the single slot the
    // emitted aggregate has (`Bitfields`). The arguments are still evaluated in written order, a
    // zero-sized one included: it contributes no bits and is not a reason to skip whatever computing
    // it does.
    case TStructNew(struct, args) if Bitfields.of(struct).isDefined =>
      val ranges = Bitfields.of(struct).get
      val vals   = args.zipWithIndex.map((a, i) => (genExpr(a), struct.fields(i)._2))
      val c      = buildBits(ranges, vals.collect { case (v, ft) if !Type.zeroSized(ft) => v })
      val r      = freshTemp()

      emit(Inst.Insert(Val.Raw(r), struct.lty, Val.Undef, containerLty(ranges), Val.Raw(c), List(0)))
      r

    case TStructNew(struct, args) =>
      val vals = args.map(genExpr)
      var acc  = "undef"
      for (v, i) <- vals.zipWithIndex if !Type.zeroSized(struct.fields(i)._2) do
        val r = freshTemp()
        emit(s"$r = insertvalue ${struct.llvm} $acc, ${struct.fields(i)._2.llvm} $v, ${struct.slot(i)}")
        acc = r
      acc

    case TEnumNew(en, variant, args) =>
      enumValue(en, variant, args.map(genExpr))

    case TEnumFromInt(value, en) =>
      genEnumFromInt(value, en)

    case TEnumTry(value, en, optTy, some, none) =>
      genEnumTry(value, en, optTy, some, none)

    case TDowngrade(value, weakTy) =>
      genDowngrade(value, weakTy)

    case TUpgrade(value, optTy, some, none) =>
      genUpgrade(value, optTy, some, none)

    case TEnumAttr(kind, en, arg, _) =>
      genEnumAttr(kind, en, arg)

    case TTry(operand, ok, fail, retEnum, retFail, _) =>
      genTry(operand, ok, fail, retEnum, retFail)

    case TField(receiver, _, ty) if Type.zeroSized(ty) =>
      genExpr(receiver); ""

    // A bitfield is read out of its container, and the container is reached the way the *receiver*
    // is: at its address where it has one, so that a field of a `volatile` register block is one
    // volatile load of the whole register — and out of the value otherwise. Which of those it is has
    // to be settled here rather than by the two cases below, because both of those take the address
    // of the **field**, and a bitfield has none (`15 §1`).
    case TField(receiver, index, _) if bitfieldOf(receiver.ty).isDefined =>
      val ranges = bitfieldOf(receiver.ty).get
      val ct     = containerLlvm(ranges)

      // The container is read at the receiver's address where the receiver is large enough to be
      // handed about through memory, and lifted out of its value otherwise — the same choice the two
      // cases below make, made here because both of those take the address of the **field** and a
      // bitfield has none.
      //
      // **A container holding any `volatile` field is read at its address whatever its size**, so
      // that reading a bitfield register is one volatile load of the register and not a load of the
      // struct followed by an `extractvalue` (`15 §1`). `volatile` is a property of the container
      // rather than of one range of it — every field of a bitfield struct is bits of the same word —
      // which is why the qualifier is asked of the receiver's storage and not of the field.
      val q = qualifier(receiver.placeTy)
      val c =
        if hasAddress(receiver) && (layout.indirect(receiver.ty) || q.nonEmpty) then
          val p = address(receiver)
          val t = freshTemp(); emit(s"$t = load$q $ct, ptr $p"); t
        else
          val rv = genExpr(receiver)
          val t  = freshTemp(); emit(Inst.Extract(Val.Raw(t), receiver.ty.lty, Val.Raw(rv), List(0))); t

      readBits(ranges, bitRange(receiver.ty, index).get, c)

    // A register is reached at its own address, because the ordinary lowering below would read the
    // whole block to get at one field of it — and reading a register block is not a way of reading
    // one register (`03 § Device memory`).
    case e @ TField(receiver, _, ty) if Type.volatileIn(e.placeTy) && hasAddress(receiver) =>
      val p = address(e)
      val r = freshTemp(); emit(s"$r = load volatile ${ty.llvm}, ptr $p"); r

    // So is a field of a **large** struct, for a reason that is arithmetic rather than hardware:
    // lifting one field out of a value means producing the whole value first, and for a receiver of
    // kilobytes that is a first-class aggregate emitted to read four bytes out of it.
    case e @ TField(receiver, _, ty) if layout.indirect(receiver.ty) && hasAddress(receiver) =>
      val p = address(e)
      val r = freshTemp(); emit(Inst.Load(Val.Raw(r), ty.lty, Val.Raw(p), Access.Plain)); r

    case TField(receiver, index, ty) =>
      val rv = genExpr(receiver); val r = freshTemp()
      emit(Inst.Extract(Val.Raw(r), receiver.ty.lty, Val.Raw(rv), List(fieldSlot(receiver.ty, index)))); r

    case TIf(cond, thenBlock, elseBlock, ty) =>
      genIf(cond, thenBlock, elseBlock, ty)

    case TMatch(scrutinee, arms, ty) =>
      genMatch(scrutinee, arms, ty)

    case w: TWhile   => genWhile(w)
    case d: TDoWhile => genDoWhile(d)
    case l: TLoop    => genLoop(l)
    case f: TCFor    => genCFor(f)
    case f: TFor     => genFor(f)
    case e: TForEach => genForEach(e)
    case i: TIterate => genIterate(i)
    case q: TQuantifier => genQuantifier(q)

    case TCheckedLoop(slot, varTy, loop) => genCheckedLoop(slot, varTy, loop)
}
