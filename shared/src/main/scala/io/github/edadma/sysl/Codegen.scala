package io.github.edadma.sysl

import scala.collection.mutable

/** Lowers a typed program (`TProgram`) to a textual LLVM IR module.
 *
 * Codegen makes no semantic decisions — the analyzer has already resolved every name, fixed
 * every type, and checked every rule. This pass is a straight translation: it selects
 * instructions from the types the tree carries and lays out basic blocks. Opaque pointers and
 * hex-double float constants keep the output stable across the textual round-trip.
 */
class Codegen private (program: TProgram) extends ArcEmitter with ScalarEmitter {

  // --- module --------------------------------------------------------------------------

  private def gen(): String = {
    val funcTexts = program.funcs.map(genFunction)
    val mainText  = genMain(program.main)

    // Emitting a runtime helper may ask for another (a destructor releases the references its
    // payload holds), so this runs until nothing new is requested.
    val runtimeTexts = mutable.ListBuffer.empty[String]
    while runtimeQueue.nonEmpty do runtimeTexts += runtimeQueue.dequeue()()

    val out = new mutable.StringBuilder
    out ++= "declare i32 @printf(ptr, ...)\n"
    if traps then out ++= "declare void @llvm.trap()\n"
    if heap then
      out ++= "declare ptr @malloc(i64)\n"
      out ++= "declare void @free(ptr)\n"
    if usesSnprintf then out ++= "declare i32 @snprintf(ptr, i64, ptr, ...)\n"
    for d <- satDecls do out ++= d + "\n"
    out ++= "\n"

    for s <- program.structs do
      out ++= s"${s.llvm} = type { ${s.fields.map(_._2.llvm).mkString(", ")} }\n"
    if program.structs.nonEmpty then out ++= "\n"

    for e <- program.enums do
      for v <- e.variants if v.payloadSlot.isDefined do
        out ++= s"${e.payloadLlvm(v)} = type { ${v.fields.map(_._2.llvm).mkString(", ")} }\n"
      val slots = "i32" :: e.variants.collect { case v if v.payloadSlot.isDefined => e.payloadLlvm(v) }
      out ++= s"${e.llvm} = type { ${slots.mkString(", ")} }\n"
    if program.enums.nonEmpty then out ++= "\n"

    // A box is the refcount, the function that frees it, and the payload — so ARC works the
    // same everywhere, and an object frees itself into whichever heap made it.
    for (name, payload) <- boxes do
      out ++= s"$name = type { i64, ptr, ${payload.llvm} }\n"
    if boxes.nonEmpty then out ++= "\n"

    if boolStrs then
      out ++= "@.true = private constant [5 x i8] c\"true\\00\"\n"
      out ++= "@.false = private constant [6 x i8] c\"false\\00\"\n"
    out ++= globals.toString
    if globals.nonEmpty || boolStrs then out ++= "\n"

    if charBuf then out ++= ScalarEmitter.utf8Encoder
    if heap then out ++= ArcEmitter.core
    if syncHeap then out ++= ArcEmitter.atomic
    if maybeHeap then out ++= ArcEmitter.maybe
    for t <- runtimeTexts do out ++= t; out ++= "\n"

    for t <- funcTexts do out ++= t; out ++= "\n"
    out ++= mainText
    out.toString
  }

  private def genMain(stmts: List[TStmt]): String = {
    startFunction()
    pushTemps()
    pushOwned()
    stmts.foreach(genStmt)
    releaseAll()
    emitTerm("ret i32 0")
    finishFunction("define i32 @main()")
  }

  /** A function owns its parameters and returns its result with a count already taken, so a
   * caller can hand over a temporary and a callee can store one without either having to know
   * what the other did with it.
   */
  private def genFunction(f: TFunc): String = {
    startFunction()
    pushTemps()
    pushOwned()

    for (name, ty) <- f.params do
      emitAlloca(s"%$name.addr", ty.llvm)
      emit(s"store ${ty.llvm} %$name.param, ptr %$name.addr")
      retainValue(ty, s"%$name.param")
      ownSlot(name, ty)

    f.body.stmts.foreach(genStmt)

    f.body.result match
      case Some(r) if f.retTy != Type.Unit =>
        val v = genExpr(r)
        retainValue(f.retTy, v)
        releaseAll()
        emitTerm(s"ret ${f.retTy.llvm} $v")
      case Some(r) =>
        genExpr(r); releaseAll(); emitTerm("ret void")
      case None if f.retTy == Type.Unit =>
        releaseAll(); emitTerm("ret void")
      case None =>
        releaseAll(); emitTerm(s"ret ${f.retTy.llvm} ${zero(f.retTy)}")

    val params = f.params.map { case (name, ty) => s"${ty.llvm} %$name.param" }.mkString(", ")
    finishFunction(s"define ${f.retTy.llvm} @${f.name}($params)")
  }

  private def zero(ty: Type): String = ty match
    case _: Type.Integer  => "0"
    case _: Type.Floating => "0.0"
    case Type.Char        => "0"
    case Type.Bool        => "0"
    case _: Type.Ptr      => "null"
    case _: Type.Ref      => "null"
    case _: Type.Struct   => "zeroinitializer"
    case _: Type.Array    => "zeroinitializer"
    case _: Type.View     => "zeroinitializer"
    case e: Type.Enum     => if e.simple then "0" else "zeroinitializer"
    case Type.Unit        => ""

  // --- statements ----------------------------------------------------------------------

  /** A statement is the region a temporary lives in: whatever it allocated or was handed is
   * released once the statement is over, leaving only what a slot has taken a count of.
   */
  private def genStmt(stmt: TStmt): Unit = {
    pushTemps()
    genStmtBody(stmt)
    popTemps()
  }

  private def genStmtBody(stmt: TStmt): Unit = stmt match
    case TVarDecl(name, ty, init) =>
      val v = genExpr(init)
      emitAlloca(s"%$name.addr", ty.llvm)
      retainValue(ty, v)
      emit(s"store ${ty.llvm} $v, ptr %$name.addr")
      ownSlot(name, ty)

    case TExprStmt(expr) =>
      genExpr(expr)

    case TReturn(opt) =>
      opt match
        case Some(t) =>
          val v = genExpr(t)
          retainValue(t.ty, v)
          releaseAll()
          emitTerm(s"ret ${t.ty.llvm} $v")
        case None =>
          releaseAll()
          emitTerm("ret void")

    // A `break`/`continue` leaves the body from the middle, so it unwinds the body's ownership
    // regions before jumping — the same discipline as `return`, bounded to the loop. `break v`
    // hands its value over with a count taken (so it survives the unwind) into the loop's slot.
    case TBreak(opt, depth) =>
      val loop = genLoops(depth)
      opt.foreach { t =>
        val v = genExpr(t)
        retainValue(t.ty, v)
        emit(s"store ${t.ty.llvm} $v, ptr ${loop.slot}")
      }
      releaseToDepth(loop.ownedDepth, loop.tempDepth)
      emitTerm(s"br label %${loop.breakL}")

    case TContinue(depth) =>
      val loop = genLoops(depth)
      releaseToDepth(loop.ownedDepth, loop.tempDepth)
      emitTerm(s"br label %${loop.continueL}")

  // --- expressions ---------------------------------------------------------------------

  /** Lowers an expression, returning the register or immediate holding its value (empty for a
   * unit-typed expression, whose value is never read).
   */
  protected def genExpr(expr: TExpr): String = expr match
    case TIntLit(v, _) => v.toString
    case TStrLit(s)    => stringValue(s)
    case TBoolLit(b)   => if b then "1" else "0"
    case TNullLit(_)   => "null"
    case TUnitLit()    => ""
    case TZero(ty)     => zero(ty)

    case TArrayLit(elems, arrayTy) =>
      val vals = elems.map(genExpr)
      var acc  = "zeroinitializer"
      for (v, i) <- vals.zipWithIndex do
        val r = freshTemp()
        emit(s"$r = insertvalue ${arrayTy.llvm} $acc, ${arrayTy.elem.llvm} $v, $i")
        acc = r
      acc

    case TIndex(receiver, index, ty) =>
      val p = elementAddr(receiver, index)
      val r = freshTemp(); emit(s"$r = load ${ty.llvm}, ptr $p"); r

    case TSlice(base, lo, hi, inclusive, sliceTy) =>
      genSlice(base, lo, hi, inclusive, sliceTy)

    case TLen(receiver) =>
      receiver.ty match
        case Type.Array(n, _) => genExpr(receiver); n.toString
        case w: Type.View =>
          val v = genExpr(receiver)
          val r = freshTemp(); emit(s"$r = extractvalue ${w.llvm} $v, 2"); r
        case other => sys.error(s"unreachable length of ${other.llvm}")

    // A string and a `[]u8` are the same three words, so looking at one as the other is nothing
    // at all — the guarantee that was given up is the analyzer's business, not the machine's.
    case TBytes(receiver) =>
      genExpr(receiver)

    // A narrower float is the `double` constant rounded to it, which folds away entirely.
    case TFloatLit(bits, ty) =>
      if ty == Type.Real then bits
      else { val r = freshTemp(); emit(s"$r = fptrunc double $bits to ${ty.llvm}"); r }

    case TCast(operand, ty) =>
      convert(operand.ty, ty, genExpr(operand))

    case TLoad(name, ty) =>
      val r = freshTemp(); emit(s"$r = load ${ty.llvm}, ptr %$name.addr"); r

    case TDeref(operand, ty) =>
      val p = payloadAddr(operand)
      val r = freshTemp(); emit(s"$r = load ${ty.llvm}, ptr $p"); r

    case TAddrOf(place, _) =>
      address(place)

    case TBox(value, refTy) =>
      genBox(value, refTy)

    case TStore(place, value, ty) =>
      val v = genExpr(value)
      val p = address(place)
      if containsRef(ty) then
        // The new value is retained before the old is released, so assigning something to
        // itself does not briefly drop the last count.
        val old = freshTemp(); emit(s"$old = load ${ty.llvm}, ptr $p")
        retainValue(ty, v)
        emit(s"store ${ty.llvm} $v, ptr $p")
        releaseValue(ty, old)
      else emit(s"store ${ty.llvm} $v, ptr $p")
      v

    case TUpdate(place, op, value, ty) =>
      val p       = address(place)
      val cur     = freshTemp(); emit(s"$cur = load ${ty.llvm}, ptr $p")
      val v       = genExpr(value)
      // A string slot holds a count, so `s += t` retains the fresh join for the slot and releases
      // what was there before, exactly like a plain assignment; every other type just overwrites.
      ty match
        case Type.Str =>
          val updated = ownTemp(strConcat(cur, v), Type.Str)
          retainValue(ty, updated)
          emit(s"store ${ty.llvm} $updated, ptr $p")
          releaseValue(ty, cur)
          updated
        case _ =>
          val updated = arith(op.dropRight(1), ty, cur, v)
          emit(s"store ${ty.llvm} $updated, ptr $p")
          updated

    case TIncDec(place, op, pre, ty) =>
      val w   = ty.llvm
      val p   = address(place)
      val cur = freshTemp(); emit(s"$cur = load $w, ptr $p")
      val nv  = freshTemp(); emit(s"$nv = ${if op == "++" then "add" else "sub"} $w $cur, 1")
      emit(s"store $w $nv, ptr $p")
      if pre then nv else cur

    case TStr(arg) =>
      // A string renders to itself, its count already the argument's; every other type renders
      // into a fresh buffer this statement owns and releases.
      val v = genStr(arg)
      if arg.ty == Type.Str then v else ownTemp(v, Type.Str)

    case TFormat(arg, spec) =>
      ownTemp(genFormat(arg, spec), Type.Str)

    case TBinary(_, l, r, Type.Str) =>
      ownTemp(strConcat(genExpr(l), genExpr(r)), Type.Str)

    case TBinary(op, l, r, _) =>
      arith(op, l.ty, genExpr(l), genExpr(r))

    case TUnary("-", operand, ty: Type.Integer) =>
      val v = genExpr(operand); val r = freshTemp(); emit(s"$r = sub ${ty.llvm} 0, $v"); r
    case TUnary("-", operand, ty: Type.Floating) =>
      val v = genExpr(operand); val r = freshTemp(); emit(s"$r = fneg ${ty.llvm} $v"); r
    case TUnary("!", operand, _) =>
      val v = genExpr(operand); val r = freshTemp(); emit(s"$r = xor i1 $v, true"); r
    case TUnary("~", operand, ty) =>
      val v = genExpr(operand); val r = freshTemp(); emit(s"$r = xor ${ty.llvm} $v, -1"); r
    case TUnary(op, _, _) =>
      sys.error(s"unreachable unary '$op'")

    // Short-circuit: `&&` evaluates its right side only when the left is true, `||` only when the
    // left is false — so a guard like `p != null && *p > 0` never runs the unsafe right side. The
    // result defaults to the left value and is overwritten by the right only when it is reached.
    case TLogical(op, l, r) =>
      val lv   = genExpr(l)
      val slot = emitAlloca(freshTemp(), "i1")
      emit(s"store i1 $lv, ptr $slot")
      val rhsL = freshLabel("sc.rhs")
      val endL = freshLabel("sc.end")
      if op == "&&" then emitTerm(s"br i1 $lv, label %$rhsL, label %$endL")
      else emitTerm(s"br i1 $lv, label %$endL, label %$rhsL")
      emitLabel(rhsL)
      // The right side gets its own temp region: anything it allocates is released before the
      // merge, and if the branch is skipped that code never runs at all.
      pushTemps()
      val rv = genExpr(r)
      emit(s"store i1 $rv, ptr $slot")
      popTemps()
      emitTerm(s"br label %$endL")
      emitLabel(endL)
      val res = freshTemp(); emit(s"$res = load i1, ptr $slot"); res

    case TCompare(operands, ops) =>
      // Each operand is evaluated exactly once — a chained comparison such as `1 < f() < 10` must
      // not run its middle operand twice — then adjacent values are compared and the results ANDed.
      val vals = operands.map(o => (o.ty, genExpr(o)))
      val cmps = ops.indices.map(i => compareValue(ops(i), vals(i)._1, vals(i)._2, vals(i + 1)._2)).toList
      cmps.reduce { (a, b) => val r = freshTemp(); emit(s"$r = and i1 $a, $b"); r }

    case TPrint(args) =>
      genPrint(args); ""

    case TCall(name, args, ty) =>
      val argVals = args.map(a => s"${a.ty.llvm} ${genExpr(a)}")
      if ty == Type.Unit then
        emit(s"call void @$name(${argVals.mkString(", ")})"); ""
      else
        val r = freshTemp(); emit(s"$r = call ${ty.llvm} @$name(${argVals.mkString(", ")})")
        ownTemp(r, ty)

    case TStructNew(struct, args) =>
      val vals = args.map(genExpr)
      var acc  = "undef"
      for (v, i) <- vals.zipWithIndex do
        val r = freshTemp()
        emit(s"$r = insertvalue ${struct.llvm} $acc, ${struct.fields(i)._2.llvm} $v, $i")
        acc = r
      acc

    case TEnumNew(en, variant, args) =>
      enumValue(en, variant, args.map(genExpr))

    case TTry(operand, ok, fail, retEnum, retFail, _) =>
      genTry(operand, ok, fail, retEnum, retFail)

    case TField(receiver, index, ty) =>
      val rv = genExpr(receiver); val r = freshTemp()
      emit(s"$r = extractvalue ${receiver.ty.llvm} $rv, $index"); r

    case TIf(cond, thenBlock, elseBlock, ty) =>
      genIf(cond, thenBlock, elseBlock, ty)

    case TMatch(scrutinee, arms, ty) =>
      genMatch(scrutinee, arms, ty)

    case w: TWhile   => genWhile(w)
    case f: TFor     => genFor(f)
    case e: TForEach => genForEach(e)

  /** The address of a place, as a `ptr` register or an existing slot name. Every place bottoms
   * out either in a local's stack slot or in a pointer the program already holds, so this walks
   * the field chain with `getelementptr` rather than reading values out with `extractvalue`.
   */
  private def address(place: TExpr): String = place match
    case TLoad(name, _)     => s"%$name.addr"
    case TDeref(operand, _) => payloadAddr(operand)
    case TField(receiver, index, _) =>
      val base = address(receiver)
      val r    = freshTemp()
      emit(s"$r = getelementptr ${receiver.ty.llvm}, ptr $base, i32 0, i32 $index")
      r
    case TIndex(receiver, index, _) => elementAddr(receiver, index)

    // A computed value has no address of its own, so reaching into one means giving it a slot
    // first. The analyzer has already refused to *assign* through anything but a real place, so
    // this only ever happens on the way to a read.
    case other =>
      val slot = emitAlloca(freshTemp(), other.ty.llvm)
      emit(s"store ${other.ty.llvm} ${genExpr(other)}, ptr $slot")
      slot

  /** The address of one element, after checking that it exists. An array is indexed from its
   * own storage; a slice is indexed from the pointer it carries.
   */
  private def elementAddr(receiver: TExpr, index: TExpr): String = {
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
  private def genSlice(
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

  /** An index at 64 bits, keeping its signedness so a negative one stays negative through the
   * widening and then fails the unsigned bounds test.
   */
  private def widen64(index: TExpr): String = index.ty match
    case i: Type.Integer => convert(i, Type.Integer(64, i.signed), genExpr(index))
    case other           => sys.error(s"unreachable index of type ${other.llvm}")

  /** Traps unless `i` names an element that exists. The comparison is unsigned at 64 bits, so a
   * negative index arrives as a very large one and fails the same test.
   */
  private def boundsCheck(i: String, len: String): Unit = {
    val ok = freshTemp(); emit(s"$ok = icmp ult i64 $i, $len")
    trapUnless(ok, "bounds")
  }

  /** Builds an enum value from already-lowered payload values: the tag, then the variant's
   * payload aggregate dropped into its slot.
   */
  private def enumValue(en: Type.Enum, variant: Type.EnumVariant, vals: List[String]): String =
    if en.simple then variant.tag.toString
    else
      val tagged = freshTemp()
      emit(s"$tagged = insertvalue ${en.llvm} undef, i32 ${variant.tag}, 0")
      variant.payloadSlot match
        case None => tagged
        case Some(slot) =>
          var payload = "undef"
          for (v, i) <- vals.zipWithIndex do
            val r = freshTemp()
            emit(s"$r = insertvalue ${en.payloadLlvm(variant)} $payload, ${variant.fields(i)._2.llvm} $v, $i")
            payload = r
          val r = freshTemp()
          emit(s"$r = insertvalue ${en.llvm} $tagged, ${en.payloadLlvm(variant)} $payload, $slot")
          r

  /** Reads every field of a variant's payload out of an enum value. */
  private def payloadFields(en: Type.Enum, variant: Type.EnumVariant, value: String): List[String] =
    variant.payloadSlot match
      case None => Nil
      case Some(slot) =>
        val p = freshTemp()
        emit(s"$p = extractvalue ${en.llvm} $value, $slot")
        variant.fields.indices.map { i =>
          val f = freshTemp()
          emit(s"$f = extractvalue ${en.payloadLlvm(variant)} $p, $i")
          f
        }.toList

  /** `expr?` — on success the payload becomes the expression's value; on failure the function
   * returns immediately with the failure re-wrapped in its own return type, carrying the error
   * payload across unchanged.
   */
  private def genTry(
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

  private def genIf(cond: TExpr, thenBlock: TBlock, elseBlock: Option[TBlock], ty: Type): String = {
    val c      = genExpr(cond)
    val thenL  = freshLabel("if.then")
    val elseL  = freshLabel("if.else")
    val endL   = freshLabel("if.end")
    val target = if elseBlock.isDefined then elseL else endL

    if ty == Type.Unit then
      emitTerm(s"br i1 $c, label %$thenL, label %$target")
      emitLabel(thenL)
      genBlockVoid(thenBlock)
      emitTerm(s"br label %$endL")
      elseBlock.foreach { eb =>
        emitLabel(elseL)
        genBlockVoid(eb)
        emitTerm(s"br label %$endL")
      }
      emitLabel(endL)
      ""
    else
      val slot = emitAlloca(freshTemp(), ty.llvm)
      emitTerm(s"br i1 $c, label %$thenL, label %$elseL")
      emitLabel(thenL)
      emit(s"store ${ty.llvm} ${genBlockValue(thenBlock)}, ptr $slot")
      emitTerm(s"br label %$endL")
      emitLabel(elseL)
      emit(s"store ${ty.llvm} ${genBlockValue(elseBlock.get)}, ptr $slot")
      emitTerm(s"br label %$endL")
      emitLabel(endL)
      // Each branch handed its value over with a count taken, so what the merge loads is the
      // one temporary the enclosing region has to let go of.
      val r = freshTemp(); emit(s"$r = load ${ty.llvm}, ptr $slot"); ownTemp(r, ty)
  }

  private def genMatch(scrutinee: TExpr, arms: List[TArm], ty: Type): String = {
    val sv   = genExpr(scrutinee)
    val sty  = scrutinee.ty
    val endL = freshLabel("match.end")
    val slot = if ty == Type.Unit then "" else emitAlloca(freshTemp(), ty.llvm)

    for arm <- arms do
      val bodyL = freshLabel("match.arm")
      val nextL = freshLabel("match.next")
      val patCond =
        arm.patterns.map(patternTest(_, sty, sv)).reduce(orI1)

      // Bindings are established only after the pattern matches, and a guard may reference them,
      // so a guarded arm branches first on the pattern, then binds, then tests the guard.
      // Only a single (non-alternative) pattern may bind.
      def bind(): Unit = if arm.patterns.length == 1 then patternBind(arm.patterns.head, sty, sv)

      arm.guard match
        case None =>
          emitTerm(s"br i1 $patCond, label %$bodyL, label %$nextL")
          emitLabel(bodyL)
          pushOwned()
          bind()
        case Some(g) =>
          val guardL = freshLabel("match.guard")
          emitTerm(s"br i1 $patCond, label %$guardL, label %$nextL")
          emitLabel(guardL)
          pushOwned()
          bind()
          pushTemps()
          val gv = genExpr(g)
          popTemps()
          // A guard that fails leaves an arm whose bindings were already made, so they are
          // given back before falling through to the next one.
          val unbindL = freshLabel("match.unbind")
          emitTerm(s"br i1 $gv, label %$bodyL, label %$unbindL")
          emitLabel(unbindL)
          releaseOwned()
          emitTerm(s"br label %$nextL")
          emitLabel(bodyL)

      if ty == Type.Unit then genBlockVoid(arm.body)
      else emit(s"store ${ty.llvm} ${genBlockValue(arm.body)}, ptr $slot")
      popOwned()
      emitTerm(s"br label %$endL")
      emitLabel(nextL)

    // Fallthrough with no matching arm: a value or enum match is exhaustive (the analyzer
    // required full coverage or a catch-all), so this point is unreachable; a plain scalar
    // statement match simply proceeds.
    if ty == Type.Unit then emitTerm(s"br label %$endL") else emitTerm("unreachable")
    emitLabel(endL)
    if ty == Type.Unit then ""
    else { val r = freshTemp(); emit(s"$r = load ${ty.llvm}, ptr $slot"); ownTemp(r, ty) }
  }

  /** The i1 result of testing a pattern against a value of type `ty`. Pattern tests are pure
   * value reads (`extractvalue`, comparisons), so nested variant fields are extracted and
   * tested unconditionally — a failed outer tag simply ANDs a `false` through.
   */
  private def patternTest(p: TPattern, ty: Type, value: String): String = p match
    case _: TWildPattern | _: TBindPattern => "true"
    case TLitPattern(v)                    => compareValue("==", v.ty, value, genExpr(v))
    case TRangePattern(lo, hi, inclusive) =>
      val loOk = compareValue(">=", lo.ty, value, genExpr(lo))
      val hiOk = compareValue(if inclusive then "<=" else "<", hi.ty, value, genExpr(hi))
      andI1(loOk, hiOk)
    case TVariantPattern(en, variant, args) =>
      val tagVal =
        if en.simple then value
        else { val t = freshTemp(); emit(s"$t = extractvalue ${en.llvm} $value, 0"); t }
      val tagOk = freshTemp(); emit(s"$tagOk = icmp eq i32 $tagVal, ${variant.tag}")
      if args.isEmpty then tagOk
      else
        val payload = freshTemp()
        emit(s"$payload = extractvalue ${en.llvm} $value, ${variant.payloadSlot.get}")
        args.zipWithIndex.foldLeft(tagOk) { case (acc, (arg, i)) =>
          val fv = freshTemp(); emit(s"$fv = extractvalue ${en.payloadLlvm(variant)} $payload, $i")
          andI1(acc, patternTest(arg, variant.fields(i)._2, fv))
        }

  /** Establishes the bindings a pattern introduces, once its arm has been taken. Only binding
   * and (nested) variant patterns carry bindings; the rest are no-ops.
   */
  private def patternBind(p: TPattern, ty: Type, value: String): Unit = p match
    case TBindPattern(name, bty) =>
      emitAlloca(s"%$name.addr", bty.llvm)
      retainValue(bty, value)
      emit(s"store ${bty.llvm} $value, ptr %$name.addr")
      ownSlot(name, bty)
    case TVariantPattern(en, variant, args) if args.exists(bindsAny) =>
      val payload = freshTemp()
      emit(s"$payload = extractvalue ${en.llvm} $value, ${variant.payloadSlot.get}")
      for (arg, i) <- args.zipWithIndex do
        val fv = freshTemp(); emit(s"$fv = extractvalue ${en.payloadLlvm(variant)} $payload, $i")
        patternBind(arg, variant.fields(i)._2, fv)
    case _ => ()

  private def bindsAny(p: TPattern): Boolean = p match
    case _: TBindPattern    => true
    case v: TVariantPattern => v.args.exists(bindsAny)
    case _                  => false

  /** ANDs / ORs two i1 values, folding away the `"true"` immediate a trivially-true pattern
   * produces so the emitted condition stays readable.
   */
  private def andI1(a: String, b: String): String =
    if a == "true" then b
    else if b == "true" then a
    else { val r = freshTemp(); emit(s"$r = and i1 $a, $b"); r }

  private def orI1(a: String, b: String): String =
    if a == "true" || b == "true" then "true"
    else { val r = freshTemp(); emit(s"$r = or i1 $a, $b"); r }

  // --- loops ---------------------------------------------------------------------------
  //
  // A loop is an expression: a `break value` stores into the loop's result slot and jumps to the
  // end, and normal completion runs the optional `else` (whose value feeds the same slot). When
  // the loop yields nothing (`unit`), there is no slot and the end is a plain merge. The `else`
  // target doubles as the end when there is no `else`, so a bare loop keeps its old shape.

  private def genWhile(w: TWhile): String = {
    val TWhile(cond, body, elseBlock, ty) = w
    val condL = freshLabel("while.cond")
    val bodyL = freshLabel("while.body")
    val endL  = freshLabel("while.end")
    val elseL = if elseBlock.isDefined then freshLabel("while.else") else endL
    val slot  = if ty == Type.Unit then "" else emitAlloca(freshTemp(), ty.llvm)
    genLoops = GenLoop(endL, condL, slot, ty, owned.length, tempStack.length) :: genLoops

    emitTerm(s"br label %$condL")
    emitLabel(condL)
    // The condition is re-evaluated every iteration, so whatever it borrows is let go before the
    // branch rather than accumulating in the enclosing statement's region.
    pushTemps()
    val c = genExpr(cond)
    popTemps()
    emitTerm(s"br i1 $c, label %$bodyL, label %$elseL")
    emitLabel(bodyL)
    pushOwned()
    body.foreach(genStmt)
    popOwned()
    emitTerm(s"br label %$condL")

    genLoops = genLoops.tail
    genLoopResult(slot, ty, elseL, endL, elseBlock)
  }

  private def genFor(f: TFor): String = {
    val TFor(name, varTy, lo, hi, inclusive, body, elseBlock, ty) = f
    val w     = varTy.llvm
    val loV   = genExpr(lo)
    val hiV   = genExpr(hi)
    val condL = freshLabel("for.cond")
    val bodyL = freshLabel("for.body")
    val stepL = freshLabel("for.step")
    val endL  = freshLabel("for.end")
    val elseL = if elseBlock.isDefined then freshLabel("for.else") else endL
    val slot  = if ty == Type.Unit then "" else emitAlloca(freshTemp(), ty.llvm)
    emitAlloca(s"%$name.addr", w)
    emit(s"store $w $loV, ptr %$name.addr")
    genLoops = GenLoop(endL, stepL, slot, ty, owned.length, tempStack.length) :: genLoops

    emitTerm(s"br label %$condL")
    emitLabel(condL)
    val iv  = freshTemp(); emit(s"$iv = load $w, ptr %$name.addr")
    val cmp = freshTemp(); emit(s"$cmp = icmp ${predicate(if inclusive then "<=" else "<", varTy)} $w $iv, $hiV")
    emitTerm(s"br i1 $cmp, label %$bodyL, label %$elseL")
    emitLabel(bodyL)
    pushOwned()
    body.foreach(genStmt)
    popOwned()
    // `continue` lands here so the counter still advances before the next test.
    emitTerm(s"br label %$stepL")
    emitLabel(stepL)
    val cur = freshTemp(); emit(s"$cur = load $w, ptr %$name.addr")
    val nxt = freshTemp(); emit(s"$nxt = add $w $cur, 1")
    emit(s"store $w $nxt, ptr %$name.addr")
    emitTerm(s"br label %$condL")

    genLoops = genLoops.tail
    genLoopResult(slot, ty, elseL, endL, elseBlock)
  }

  // The sequence is evaluated once, into the statement's own region, so a slice temporary stays
  // alive for the whole loop; the loop variable is a copy, released each iteration.
  private def genForEach(e: TForEach): String = {
    val TForEach(name, elemTy, seq, body, elseBlock, ty) = e
    val (base, len) = seq.ty match
      case Type.Array(n, _) => (address(seq), n.toString)
      case s: Type.Slice =>
        val v = genExpr(seq)
        val p = freshTemp(); emit(s"$p = extractvalue ${s.llvm} $v, 1")
        val l = freshTemp(); emit(s"$l = extractvalue ${s.llvm} $v, 2")
        (p, l)
      case other => sys.error(s"unreachable iteration over ${other.llvm}")

    val idx   = emitAlloca(freshTemp(), "i64")
    val condL = freshLabel("each.cond")
    val bodyL = freshLabel("each.body")
    val stepL = freshLabel("each.step")
    val endL  = freshLabel("each.end")
    val elseL = if elseBlock.isDefined then freshLabel("each.else") else endL
    val slot  = if ty == Type.Unit then "" else emitAlloca(freshTemp(), ty.llvm)
    emit(s"store i64 0, ptr $idx")
    genLoops = GenLoop(endL, stepL, slot, ty, owned.length, tempStack.length) :: genLoops

    emitTerm(s"br label %$condL")
    emitLabel(condL)
    val iv   = freshTemp(); emit(s"$iv = load i64, ptr $idx")
    val more = freshTemp(); emit(s"$more = icmp ult i64 $iv, $len")
    emitTerm(s"br i1 $more, label %$bodyL, label %$elseL")
    emitLabel(bodyL)
    val ep = freshTemp(); emit(s"$ep = getelementptr ${elemTy.llvm}, ptr $base, i64 $iv")
    val ev = freshTemp(); emit(s"$ev = load ${elemTy.llvm}, ptr $ep")
    emitAlloca(s"%$name.addr", elemTy.llvm)
    retainValue(elemTy, ev)
    emit(s"store ${elemTy.llvm} $ev, ptr %$name.addr")
    pushOwned()
    ownSlot(name, elemTy)
    body.foreach(genStmt)
    popOwned()
    emitTerm(s"br label %$stepL")
    emitLabel(stepL)
    val nxt = freshTemp(); emit(s"$nxt = add i64 $iv, 1")
    emit(s"store i64 $nxt, ptr $idx")
    emitTerm(s"br label %$condL")

    genLoops = genLoops.tail
    genLoopResult(slot, ty, elseL, endL, elseBlock)
  }

  /** Finishes a loop expression: run the `else` (if any) on the normal-completion path into the
   * result slot, then land at the end and hand the slot's value out as the enclosing region's to
   * release. A `unit` loop has no slot and yields nothing.
   */
  private def genLoopResult(slot: String, ty: Type, elseL: String, endL: String,
                            elseBlock: Option[TBlock]): String = {
    elseBlock.foreach { eb =>
      emitLabel(elseL)
      if ty == Type.Unit then genBlockVoid(eb)
      else emit(s"store ${ty.llvm} ${genBlockValue(eb)}, ptr $slot")
      emitTerm(s"br label %$endL")
    }
    emitLabel(endL)
    if ty == Type.Unit then ""
    else { val r = freshTemp(); emit(s"$r = load ${ty.llvm}, ptr $slot"); ownTemp(r, ty) }
  }

  private def genBlockVoid(b: TBlock): Unit = {
    pushTemps()
    pushOwned()
    b.stmts.foreach(genStmt)
    b.result.foreach(genExpr)
    popOwned()
    popTemps()
  }

  /** A branch's value, handed out with a count of its own. The block's locals and temporaries
   * are released before control leaves it — that is what keeps every release site dominating
   * the value it releases — so the result is retained first and becomes the caller's to let go.
   */
  private def genBlockValue(b: TBlock): String = {
    pushTemps()
    pushOwned()
    b.stmts.foreach(genStmt)
    val v = genExpr(b.result.get)
    retainValue(b.result.get.ty, v)
    popOwned()
    popTemps()
    v
  }

}

object Codegen {

  /** Lowers a typed program to an LLVM IR module. */
  def generate(program: TProgram): String = new Codegen(program).gen()
}
