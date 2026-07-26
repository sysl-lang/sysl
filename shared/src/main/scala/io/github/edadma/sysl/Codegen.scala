package io.github.edadma.sysl

import scala.collection.mutable

/** Lowers a typed program (`TProgram`) to a textual LLVM IR module.
 *
 * Codegen makes no semantic decisions — the analyzer has already resolved every name, fixed
 * every type, and checked every rule. This pass is a straight translation: it selects
 * instructions from the types the tree carries and lays out basic blocks. Opaque pointers and
 * hex-double float constants keep the output stable across the textual round-trip.
 *
 * The work is split across traits the way the analyzer's is: `Emitter` holds the buffers, block
 * state, and name counters, `ArcEmitter` the ownership runtime, `StringEmitter` and `ScalarEmitter`
 * the two ends of the value world, `PlaceEmitter` addressing and composite values, and
 * `ControlFlowEmitter` everything that makes a basic block. What stays here is the spine — the
 * module assembly, statements, and the expression dispatch the traits call back into.
 */
class Codegen private (program: TProgram) extends ControlFlowEmitter {

  // --- module --------------------------------------------------------------------------

  private def gen(): String = {
    val funcTexts = program.funcs.map(genFunction)
    val mainText  = genMain(program.main)

    // Emitting a runtime helper may ask for another (a destructor releases the references its
    // payload holds), so this runs until nothing new is requested.
    val runtimeTexts = mutable.ListBuffer.empty[String]
    while runtimeQueue.nonEmpty do runtimeTexts += runtimeQueue.dequeue()()

    val out = new mutable.StringBuilder
    if traps then out ++= "declare void @llvm.trap()\n"
    if heap then
      out ++= "declare ptr @malloc(i64)\n"
      out ++= "declare void @free(ptr)\n"
    if usesSnprintf then out ++= "declare i32 @snprintf(ptr, i64, ptr, ...)\n"
    if usesVarargs then
      out ++= "declare void @llvm.va_start.p0(ptr)\n"
      out ++= "declare void @llvm.va_end.p0(ptr)\n"

    // An `extern` the program calls is declared here, unless the symbol is already declared — by the
    // runtime, whose own spelling of `malloc` is the one its code calls, or by an earlier `extern`,
    // since two declarations may name one symbol under different sysl names. A module may not
    // declare one symbol twice.
    val declared = mutable.Set("llvm.trap") ++
      (if heap then Set("malloc", "free") else Set.empty) ++
      (if usesSnprintf then Set("snprintf") else Set.empty)

    for e <- program.externs if declared.add(e.symbol) do
      val params = e.params.map(_.llvm) ++ Option.when(e.variadic)("...")
      out ++= s"declare ${e.retTy.llvm} @${e.symbol}(${params.mkString(", ")})\n"

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

  /** Every callee declared with a `...`, foreign or sysl's own, mapped to the LLVM function type a
   * call to it must name: result type, declared parameter types, ellipsis.
   */
  private val variadics: Map[String, String] =
    val fromExterns = program.externs.filter(_.variadic).map(e => e.name -> (e.retTy, e.params.map(_.llvm)))
    val fromFuncs   = program.funcs.filter(_.variadic).map(f => f.name -> (f.retTy, f.params.map(_._2.llvm)))

    (fromExterns ++ fromFuncs).map { case (name, (retTy, params)) =>
      name -> s"${if Type.noValue(retTy) then "void" else retTy.llvm} (${(params :+ "...").mkString(", ")})"
    }.toMap

  /** The symbol a called name resolves to, which differs from the name only for an `extern` given a
   * link name. Everything a program defines is emitted under its own name.
   */
  private val symbols: Map[String, String] =
    program.externs.collect { case e if e.symbol != e.name => e.name -> e.symbol }.toMap

  /** What a `call` names. For an ordinary function that is the result type, which is all LLVM
   * needs; for a variadic one it is the callee's *whole* function type, because the argument list
   * alone does not say where the declared parameters stop and the ellipsis begins.
   */
  private def calleeOf(name: String, ty: Type): String =
    val symbol = symbols.getOrElse(name, name)

    variadics.get(name) match
      case Some(fnTy) => s"$fnTy @$symbol"
      case None       => s"${if Type.noValue(ty) then "void" else ty.llvm} @$symbol"

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

    // A `never` result is `void` like `unit`: the body's trailing expression diverges, so it has
    // already terminated the block and the `ret` emitted here is dropped.
    f.body.result match
      case Some(r) if !Type.noValue(f.retTy) =>
        val v = genExpr(r)
        retainValue(f.retTy, v)
        releaseAll()
        emitTerm(s"ret ${f.retTy.llvm} $v")
      case Some(r) =>
        genExpr(r); releaseAll(); emitTerm("ret void")
      case None if Type.noValue(f.retTy) =>
        releaseAll(); emitTerm("ret void")
      case None =>
        releaseAll(); emitTerm(s"ret ${f.retTy.llvm} ${zero(f.retTy)}")

    val declared = f.params.map { case (name, ty) => s"${ty.llvm} %$name.param" }
    val params   = (declared ++ Option.when(f.variadic)("...")).mkString(", ")
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
    case Type.VaList      => "zeroinitializer"
    case e: Type.Enum     => if e.simple then "0" else "zeroinitializer"
    case Type.Unit        => ""
    // Nothing is lowered from a program that has an error, and a type the analyzer could not work
    // out is only ever produced by one, so reaching here would mean codegen ran on a broken tree.
    case Type.Unknown     => sys.error("unreachable zero of an unknown type")
    // `never` has no values, so nothing ever starts at one: every path that would need this
    // diverges before reaching it.
    case Type.Never       => sys.error("unreachable zero of 'never'")
    // A type parameter reaches codegen from nowhere: the pass that stands one in for itself is
    // for diagnostics and throws its tree away, and monomorphization substitutes a concrete type
    // before anything is emitted.
    case _: Type.Abstract => sys.error("unreachable zero of a type parameter")

  // --- statements ----------------------------------------------------------------------

  /** A statement is the region a temporary lives in: whatever it allocated or was handed is
   * released once the statement is over, leaving only what a slot has taken a count of.
   */
  protected def genStmt(stmt: TStmt): Unit = {
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
    // Every interned constant is already laid down NUL-terminated, so a C string is the same global
    // read as a plain pointer — the terminator the sysl string ignores is exactly what C reads by.
    case TCStrLit(s)   => stringGlobal(s)
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

    // One comparison has nothing to short-circuit, so it stays straight-line — which is what the
    // overwhelming majority of comparisons are.
    case TCompare(List(l, r), List(op)) =>
      val lv = genExpr(l)
      compareValue(op, l.ty, lv, genExpr(r))

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
    case TCompare(operands, ops) =>
      val slot  = emitAlloca(freshTemp(), "i1")
      val exits = ops.indices.map(_ => freshLabel("cmp.exit")).toList
      val endL  = freshLabel("cmp.end")

      var left = ""

      for k <- ops.indices do
        pushTemps()
        if k == 0 then left = genExpr(operands.head)
        val right = genExpr(operands(k + 1))
        val c     = compareValue(ops(k), operands(k).ty, left, right)

        emit(s"store i1 $c, ptr $slot")

        if k == ops.length - 1 then emitTerm(s"br label %${exits(k)}")
        else
          val nextL = freshLabel("cmp.next")
          emitTerm(s"br i1 $c, label %$nextL, label %${exits(k)}")
          emitLabel(nextL)

        left = right

      for k <- ops.indices.reverse do
        emitLabel(exits(k))
        popTemps()
        emitTerm(s"br label %${if k == 0 then endL else exits(k - 1)}")

      emitLabel(endL)
      val res = freshTemp(); emit(s"$res = load i1, ptr $slot"); res

    case TSeq(exprs) =>
      exprs.foreach(genExpr); ""

    // A call to something declared `-> never` does not come back, so the block ends at it: what
    // follows in the same block is unreachable and `emit` drops it, which is exactly why a
    // diverging arm needs no special handling anywhere else.
    case TCall(name, args, ty) =>
      val argVals = args.map(a => s"${a.ty.llvm} ${genExpr(a)}")
      val callee  = calleeOf(name, ty)
      if Type.noValue(ty) then
        emit(s"call $callee(${argVals.mkString(", ")})")
        if ty == Type.Never then emitTerm("unreachable")
        ""
      else
        val r = freshTemp(); emit(s"$r = call $callee(${argVals.mkString(", ")})")
        ownTemp(r, ty)

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

    case TEnumFromInt(value, en) =>
      genEnumFromInt(value, en)

    case TEnumTry(value, en, optTy, some, none) =>
      genEnumTry(value, en, optTy, some, none)

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

}

object Codegen {

  /** Lowers a typed program to an LLVM IR module. */
  def generate(program: TProgram): String = new Codegen(program).gen()
}
