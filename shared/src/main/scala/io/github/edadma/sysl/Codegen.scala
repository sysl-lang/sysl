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
class Codegen private (program: TProgram) extends ControlFlowEmitter with VtableEmitter with WriterEmitter {

  // --- module --------------------------------------------------------------------------

  private def gen(): String = {
    val funcTexts  = program.funcs.map(genFunction)
    val mainText   = genMain(program.main)
    // The tables come after the bodies because a table only exists for a type something erased, and
    // it may ask for an adapter, which the queue below is what emits.
    val vtableText = program.vtables.map(genVtable).mkString

    // Emitting a runtime helper may ask for another (a destructor releases the references its
    // payload holds), so this runs until nothing new is requested.
    val runtimeTexts = mutable.ListBuffer.empty[String]
    while runtimeQueue.nonEmpty do runtimeTexts += runtimeQueue.dequeue()()

    val out = new mutable.StringBuilder
    if traps then out ++= "declare void @llvm.trap()\n"
    if heap then
      out ++= "declare ptr @malloc(i64)\n"
      out ++= "declare void @free(ptr)\n"
    if checked then
      out ++= "declare { i64, i1 } @llvm.umul.with.overflow.i64(i64, i64)\n"
      out ++= "declare { i64, i1 } @llvm.uadd.with.overflow.i64(i64, i64)\n"
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
      val params = e.params.filterNot(Type.zeroSized).map(_.llvm) ++ Option.when(e.variadic)("...")
      out ++= s"declare ${e.retTy.llvm} @${e.symbol}(${params.mkString(", ")})\n"

    for d <- satDecls do out ++= d + "\n"
    out ++= "\n"

    // A zero-sized field is skipped: the aggregate is what occupies storage, and `Type.slot` is what
    // keeps every index that reaches into it agreeing with what was laid down here.
    for s <- program.structs do
      out ++= s"${s.llvm} = type { ${s.stored.map(_._2.llvm).mkString(", ")} }\n"
    if program.structs.nonEmpty then out ++= "\n"

    for e <- program.enums do
      for v <- e.variants if v.payloadSlot.isDefined do
        out ++= s"${e.payloadLlvm(v)} = type { ${v.stored.map(_._2.llvm).mkString(", ")} }\n"
      val slots = "i32" :: e.variants.collect { case v if v.payloadSlot.isDefined => e.payloadLlvm(v) }
      out ++= s"${e.llvm} = type { ${slots.mkString(", ")} }\n"
    if program.enums.nonEmpty then out ++= "\n"

    // A box is the refcount, the function that frees it, and the payload — so ARC works the
    // same everywhere, and an object frees itself into whichever heap made it.
    for (name, payload) <- boxes do
      out ++= s"$name = type { i64, ptr, ${payload.llvm} }\n"
    // A buffer is the same box with the element count in front of elements there may be any number
    // of, so the hook — which is reached with no static type — can still find them all.
    for (name, elem) <- bufs do
      out ++= s"$name = type { i64, ptr, i64, [0 x ${elem.llvm}] }\n"
    if boxes.nonEmpty || bufs.nonEmpty then out ++= "\n"

    if boolStrs then
      out ++= "@.true = private constant [5 x i8] c\"true\\00\"\n"
      out ++= "@.false = private constant [6 x i8] c\"false\\00\"\n"
    // A `val` is `private` because the whole program is one module here: nothing outside it can name
    // one, so hiding the symbol costs nothing and lets the optimizer see every read of the table.
    for v <- program.vals do
      out ++= s"@${v.symbol} = private constant ${v.ty.llvm} ${constantValue(v.init)}\n"
    out ++= globals.toString
    out ++= vtableText
    if globals.nonEmpty || boolStrs || vtableText.nonEmpty || program.vals.nonEmpty then out ++= "\n"

    if charBuf then out ++= ScalarEmitter.utf8Encoder
    if heap then out ++= ArcEmitter.core
    if syncHeap then out ++= ArcEmitter.atomic
    if maybeHeap then out ++= ArcEmitter.maybe
    for t <- runtimeTexts do out ++= t; out ++= "\n"

    for t <- funcTexts do out ++= t; out ++= "\n"
    out ++= mainText
    out.toString
  }

  /** A `val`'s initializer as a **constant expression** — text laid straight into the object file,
   * with no instruction emitted for any of it.
   *
   * This is the one place a value is lowered without a basic block to put it in, which is why it is
   * a separate walk rather than a call into `genExpr`: the ordinary lowering of a narrow float is an
   * `fptrunc`, and of a repeat is a loop, and neither exists before `main` starts. The analyzer has
   * already held the tree to the small set handled here.
   */
  private def constantValue(t: TExpr): String = t match
    case TIntLit(v, _)  => v.toString
    case TBoolLit(b)    => if b then "1" else "0"
    case TFloatLit(bits, ty) => constantFloat(bits, ty)
    case TArrayLit(elems, arrayTy) =>
      val elem = arrayTy.elem.llvm
      s"[${elems.map(e => s"$elem ${constantValue(e)}").mkString(", ")}]"
    case TArrayFill(value, arrayTy) =>
      val elem = arrayTy.elem.llvm
      val v    = constantValue(value)
      s"[${List.fill(arrayTy.length)(s"$elem $v").mkString(", ")}]"
    case other => sys.error(s"unreachable constant ${other.getClass.getSimpleName}")

  /** A float constant at the width it is stored at.
   *
   * LLVM's hex form always spells a `double`, and it refuses one that a narrower type could not hold
   * exactly — so a `f32` constant is the source value rounded to a float *first* and then written
   * back out as the double that float is. That is the same rounding the `fptrunc` in the ordinary
   * path performs, done here instead of at run time.
   */
  private def constantFloat(bits: String, ty: Type): String = {
    val d = java.lang.Double.longBitsToDouble(java.lang.Long.parseUnsignedLong(bits.drop(2), 16))

    if ty == Type.Real then bits
    else f"0x${java.lang.Double.doubleToLongBits(d.toFloat.toDouble)}%016X"
  }

  /** The arguments of a call, as an LLVM argument list.
   *
   * Every one is evaluated, in the order it was written, because the *effect* of an argument is
   * owed whatever its type — but a zero-sized one is then not passed, since the callee has no
   * parameter to receive it. That keeps the two sides of the call agreeing with `genFunction`,
   * which drops the same parameters from the signature.
   */
  private def argList(args: List[TExpr]): List[String] =
    args.flatMap { a =>
      val v = genExpr(a)

      Option.unless(Type.zeroSized(a.ty))(s"${a.ty.llvm} $v")
    }

  /** Every callee declared with a `...`, foreign or sysl's own, mapped to the LLVM function type a
   * call to it must name: result type, declared parameter types, ellipsis.
   */
  private val variadics: Map[String, String] =
    val fromExterns =
      program.externs.filter(_.variadic).map(e => e.name -> (e.retTy, e.params.filterNot(Type.zeroSized).map(_.llvm)))
    val fromFuncs   = program.funcs.filter(_.variadic).map(f => f.name -> (f.retTy, Type.stored(f.params).map(_._2.llvm)))

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

  /** One comparison, over two values the caller is holding: an instruction where the operand type
   * has one, the method its `Eq`/`Ord` supplies otherwise.
   */
  private def comparison(c: TCmp, ty: Type, av: String, bv: String): String =
    c.dispatch match
      case Some(d) => dispatchValue(d, ty, av, bv, Type.Bool)
      case None    => compareValue(c.op, ty, av, bv)

  /** Applies an operator's trait method to two **values** rather than two expressions (`14 §3`).
   *
   * That is the whole distinction from lowering a `TCall`: the forms that reach here — a comparison
   * chain and a compound assignment — each use one operand twice from a single evaluation, and the
   * value is already in a register. `swap` and `negate` carry the derivation of the comparisons the
   * catalog declares no method for (`14 §2`), so `a > b` calls the one `lt` its `impl` wrote.
   */
  private def dispatchValue(d: TDispatch, ty: Type, av: String, bv: String, resultTy: Type): String = {
    val (l, r) = if d.swap then (bv, av) else (av, bv)
    val res    = freshTemp()

    emit(s"$res = call ${calleeOf(d.name, resultTy)}(${ty.llvm} $l, ${ty.llvm} $r)")

    if !d.negate then res
    else
      val n = freshTemp(); emit(s"$n = xor i1 $res, true"); n
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
  /** The postconditions of the function currently being emitted, checked before every return,
   * and the SSA value `result` denotes while a postcondition is being lowered. */
  private var ensures:   List[(TExpr, Option[String])] = Nil
  private var resultSSA: Option[String]                = None

  /** Emits a contract clause as a trap-on-false check, discarding any temporaries the condition
   * allocated before the trap so the checked path stays leak-free. */
  private def emitContract(cond: TExpr, kind: String): Unit = {
    pushTemps()
    val ok = genExpr(cond)
    popTemps()
    trapUnless(ok, kind)
  }

  /** Emits the `within`-range checks for a value produced into a constrained subtype: a lower- and
   * upper-bound compare, each trapping on violation. Integer and `char` bounds compare at the base
   * width (unsigned for `char` and the unsigned integers, which `compareValue` reads off the type);
   * float bounds compare in double precision, widening a narrower value so one rendering of the
   * bound serves every float width.
   */
  private def emitRangeChecks(v: String, c: Type.Constrained): Unit =
    Type.underlying(c.base) match
      case f: Type.Floating =>
        val wide =
          if f.bits == 64 then v
          else { val r = freshTemp(); emit(s"$r = fpext ${f.llvm} $v to double"); r }
        for lo <- c.lo do trapUnless(fcmpConst("oge", wide, lo), "within")
        for hi <- c.hi do trapUnless(fcmpConst(if c.exclusiveHi then "olt" else "ole", wide, hi), "within")
      case base =>
        for lo <- c.lo do trapUnless(compareValue(">=", base, v, lo.toBigInt.toString), "within")
        for hi <- c.hi do
          trapUnless(compareValue(if c.exclusiveHi then "<" else "<=", base, v, hi.toBigInt.toString), "within")

  private def fcmpConst(pred: String, wide: String, bound: BigDecimal): String = {
    val c = f"0x${java.lang.Double.doubleToLongBits(bound.toDouble)}%016X"
    val r = freshTemp(); emit(s"$r = fcmp $pred double $wide, $c"); r
  }

  /** Runs every postcondition with `result` bound to the value about to be returned. */
  private def emitEnsures(result: Option[String]): Unit =
    if ensures.nonEmpty then
      resultSSA = result
      for (cond, _) <- ensures do emitContract(cond, "ensure")
      resultSSA = None

  private def genFunction(f: TFunc): String = {
    startFunction()
    pushTemps()
    pushOwned()
    ensures = f.ensures

    // A zero-sized parameter is not an argument: there is nothing to receive and nothing to keep,
    // so it takes no slot and the emitted signature below does not mention it.
    for (name, ty) <- f.params if !Type.zeroSized(ty) do
      emitAlloca(s"%$name.addr", ty.llvm)
      emit(s"store ${ty.llvm} %$name.param, ptr %$name.addr")
      retainValue(ty, s"%$name.param")
      ownSlot(name, ty)

    for (cond, _) <- f.requires do emitContract(cond, "require")

    // Each `old(e)` snapshots the entry value into a hidden owned slot, exactly as a `var` would,
    // so a postcondition can compare the returned state against the state on entry. The slot is
    // released with every other local at each return.
    for (oldExpr, i) <- f.olds.zipWithIndex do
      pushTemps()
      val v = genExpr(oldExpr)
      emitAlloca(s"%old.$i.addr", oldExpr.ty.llvm)
      retainValue(oldExpr.ty, v)
      emit(s"store ${oldExpr.ty.llvm} $v, ptr %old.$i.addr")
      ownSlot(s"old.$i", oldExpr.ty)
      popTemps()

    f.body.stmts.foreach(genStmt)

    // A `never` result is `void` like `unit`: the body's trailing expression diverges, so it has
    // already terminated the block and the `ret` emitted here is dropped.
    f.body.result match
      case Some(r) if !Type.noValue(f.retTy) =>
        val v = genExpr(r)
        retainValue(f.retTy, v)
        emitEnsures(Some(v))
        releaseAll()
        emitTerm(s"ret ${f.retTy.llvm} $v")
      case Some(r) =>
        genExpr(r); emitEnsures(None); releaseAll(); emitTerm("ret void")
      case None if Type.noValue(f.retTy) =>
        emitEnsures(None); releaseAll(); emitTerm("ret void")
      case None =>
        releaseAll(); emitTerm(s"ret ${f.retTy.llvm} ${zero(f.retTy)}")

    val declared = Type.stored(f.params).map { case (name, ty) => s"${ty.llvm} %$name.param" }
    val params   = (declared ++ Option.when(f.variadic)("...")).mkString(", ")
    finishFunction(s"define ${f.retTy.llvm} @${f.name}($params)")
  }

  private def zero(ty: Type): String = ty match
    // Nothing is stored for a zero-sized value, so its zero is nothing at all — the same empty
    // register every other read of one yields.
    case t if Type.zeroSized(t) => ""
    // A constrained subtype is laid out as its base, so its zero is the base's zero.
    case c: Type.Constrained => zero(Type.underlying(c))
    case _: Type.Integer  => "0"
    case _: Type.Floating => "0.0"
    case Type.Char        => "0"
    case Type.Bool        => "0"
    // A trait object is two words, so its zero is a zeroed pair rather than a null address — and,
    // like every null pointer, calling through one is the programmer's business.
    case _: Type.Ptr | _: Type.Ref if Type.erased(ty) => "zeroinitializer"
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
    // A trait is only ever the pointee of a `*Trait` / `&Trait`, both handled above; resolving a
    // bare trait name is a diagnostic, so nothing of this type is ever laid out.
    case _: Type.Trait    => sys.error("unreachable zero of a trait")

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
    // A zero-sized binding is not a slot: the initializer still runs, for its effect, and there is
    // nothing to keep afterwards. Every later read of the name yields nothing in the same way.
    case TVarDecl(_, ty, init) if Type.zeroSized(ty) =>
      genExpr(init)

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
          emitEnsures(Some(v))
          releaseAll()
          emitTerm(s"ret ${t.ty.llvm} $v")
        case None =>
          emitEnsures(None)
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
    // A trait object is two words, so its null is a zeroed pair rather than a bare address.
    case TNullLit(ty)  => zero(ty)
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

    // Built through memory with a loop rather than as an `insertvalue` chain, for the reason the
    // ARC walk gives: the count is a compile-time constant but it can be very large, and a repeat
    // count is where someone writes a large one on purpose. The value is generated once, above the
    // loop — every element is a copy of that one evaluation. Its references are borrowed here and
    // retained by whatever binds the array, whose ARC walk visits all n elements.
    case TArrayFill(value, arrayTy) =>
      val v = genExpr(value)

      if arrayTy.length == 0 then "zeroinitializer"
      else
        val buf   = emitAlloca(freshTemp(), arrayTy.llvm)
        val i     = emitAlloca(freshTemp(), "i64")
        val condL = freshLabel("fill.test")
        val bodyL = freshLabel("fill.elem")
        val endL  = freshLabel("fill.done")

        emit(s"store i64 0, ptr $i")
        emitTerm(s"br label %$condL")
        emitLabel(condL)
        val iv   = freshTemp(); emit(s"$iv = load i64, ptr $i")
        val more = freshTemp(); emit(s"$more = icmp ult i64 $iv, ${arrayTy.length}")
        emitTerm(s"br i1 $more, label %$bodyL, label %$endL")
        emitLabel(bodyL)
        val ep = freshTemp(); emit(s"$ep = getelementptr ${arrayTy.elem.llvm}, ptr $buf, i64 $iv")
        emit(s"store ${arrayTy.elem.llvm} $v, ptr $ep")
        val nxt = freshTemp(); emit(s"$nxt = add i64 $iv, 1")
        emit(s"store i64 $nxt, ptr $i")
        emitTerm(s"br label %$condL")
        emitLabel(endL)

        val r = freshTemp(); emit(s"$r = load ${arrayTy.llvm}, ptr $buf"); r

    // The same two forms, sized and owned rather than laid out in a frame. Each element the buffer
    // takes is a share of its own — the box holds them until its hook lets them go — so the value
    // that lands in one is retained as it is stored, exactly as a slot that binds an array is.
    case TBufLit(elems, sliceTy) =>
      val vals       = elems.map(genExpr)
      val (box, data) = genBuffer(sliceTy.elem, vals.length.toString)

      for (v, i) <- vals.zipWithIndex do
        retainValue(sliceTy.elem, v)
        val ep = freshTemp(); emit(s"$ep = getelementptr ${sliceTy.elem.llvm}, ptr $data, i64 $i")
        emit(s"store ${sliceTy.elem.llvm} $v, ptr $ep")

      bufferView(sliceTy, box, data, vals.length.toString)

    // The value is generated once, above the loop, which is what makes `[tick(); n]` one call
    // whose result lands in n places — the repeat's own rule (`07`), and the reason the count may
    // be zero and the call still happen.
    case TBufFill(value, count, sliceTy) =>
      val v           = genExpr(value)
      val n           = widen64(count)
      val (box, data) = genBuffer(sliceTy.elem, n)

      fillElements(sliceTy.elem, data, n, v)
      bufferView(sliceTy, box, data, n)

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
      // A constrained operand converts from its base representation — `f64(m)` reaches the double a
      // `Meters` is, `int(age)` the i32 an `Age` is.
      convert(Type.underlying(operand.ty), ty, genExpr(operand))

    case TConstrainedCheck(value, target) =>
      val v = genExpr(value)
      emitRangeChecks(v, target)
      v

    // Nothing is stored for a zero-sized binding, so there is nothing to read back.
    case TLoad(_, ty) if Type.zeroSized(ty) => ""

    case TLoad(name, ty) =>
      val r = freshTemp(); emit(s"$r = load ${ty.llvm}, ptr %$name.addr"); r

    case TResult(_) =>
      resultSSA.getOrElse(sys.error("'result' lowered outside an ensure postcondition"))

    case TOld(index, ty) =>
      val r = freshTemp(); emit(s"$r = load ${ty.llvm}, ptr %old.$index.addr"); r

    case TGlobal(symbol, ty) =>
      val r = freshTemp(); emit(s"$r = load ${ty.llvm}, ptr @$symbol"); r

    case TDeref(operand, ty) =>
      val p = payloadAddr(operand)
      val r = freshTemp(); emit(s"$r = load ${ty.llvm}, ptr $p"); r

    case TAddrOf(place, _) =>
      address(place)

    case TBox(value, refTy) =>
      genBox(value, refTy)

    case TStore(place, value, ty) if Type.zeroSized(ty) =>
      genExpr(value)
      address(place)
      ""

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

    case TUpdate(place, op, value, ty, dispatch) =>
      val p       = address(place)
      val cur     = freshTemp(); emit(s"$cur = load ${ty.llvm}, ptr $p")
      val v       = genExpr(value)
      // A slot that holds a count — a string, or a struct with a reference in it — retains the fresh
      // value for the slot and releases what was there before, exactly like a plain assignment; a
      // slot of anything else just overwrites.
      val updated = (ty, dispatch) match
        case (_, Some(d))  => ownTemp(dispatchValue(d, ty, cur, v, ty), ty)
        case (Type.Str, _) => ownTemp(strConcat(cur, v), Type.Str)
        case _             => arith(op.dropRight(1), ty, cur, v)

      if containsRef(ty) then
        retainValue(ty, updated)
        emit(s"store ${ty.llvm} $updated, ptr $p")
        releaseValue(ty, cur)
      else emit(s"store ${ty.llvm} $updated, ptr $p")
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

    // A sink with no state: the table is the compiler's and the data word is null, because a writer
    // over standard output has nothing to point at.
    case TStdout() =>
      val a = freshTemp(); emit(s"$a = insertvalue ${Type.fatPointer} undef, ptr @${stdoutTable()}, 0")
      val b = freshTemp(); emit(s"$b = insertvalue ${Type.fatPointer} $a, ptr null, 1")
      b

    // Rendering into a buffer: a zeroed stack slot becomes the sink, the value writes itself into
    // it, and what landed there is copied into a string the statement owns. The slot is re-zeroed
    // on every arrival rather than once, since an alloca is hoisted to the entry block and a render
    // inside a loop meets the same one each time round.
    case TRender(value, method, spec) =>
      heap = true
      request("sysl.str.from_bytes")(StringEmitter.fromBytes)

      val table = bufferTable()
      val v     = genExpr(value)
      val s     = genExpr(spec)
      val slot  = emitAlloca(freshTemp(), bufferLayout)

      emit(s"store $bufferLayout zeroinitializer, ptr $slot")
      val a = freshTemp(); emit(s"$a = insertvalue ${Type.fatPointer} undef, ptr @$table, 0")
      val w = freshTemp(); emit(s"$w = insertvalue ${Type.fatPointer} $a, ptr $slot, 1")
      emit(s"call void @$method(${value.ty.llvm} $v, ${Type.fatPointer} $w, ${spec.ty.llvm} $s)")

      val r = freshTemp()
      emit(s"$r = call ${Type.Str.llvm} @sysl.w.buf.finish(ptr $slot)")
      ownTemp(r, Type.Str)

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

        emit(s"store i1 $c, ptr $slot")

        if k == cmps.length - 1 then emitTerm(s"br label %${exits(k)}")
        else
          val nextL = freshLabel("cmp.next")
          emitTerm(s"br i1 $c, label %$nextL, label %${exits(k)}")
          emitLabel(nextL)

        left = right

      for k <- cmps.indices.reverse do
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
      val argVals = argList(args)
      val callee  = calleeOf(name, ty)
      if Type.noValue(ty) then
        emit(s"call $callee(${argVals.mkString(", ")})")
        if ty == Type.Never then emitTerm("unreachable")
        ""
      else
        val r = freshTemp(); emit(s"$r = call $callee(${argVals.mkString(", ")})")
        ownTemp(r, ty)

    // Erasing costs one word: the value goes on pointing where it pointed, and the table for the
    // type it is losing is a constant beside it. Nothing is retained — a counted object holds the
    // count its operand already had, which is what makes `f(&T)` and `f(&Trait)` the same handover.
    case TErase(operand, vtable, _) =>
      val d = genExpr(operand)
      val a = freshTemp(); emit(s"$a = insertvalue ${Type.fatPointer} undef, ptr @$vtable, 0")
      val b = freshTemp(); emit(s"$b = insertvalue ${Type.fatPointer} $a, ptr $d, 1")
      b

    // A call whose callee is a word in the object's table rather than a name. The data word goes in
    // front of the declared arguments, which is the shape every slot was built to.
    case TVCall(receiver, slot, args, ty) =>
      val obj     = genExpr(receiver)
      val table   = freshTemp(); emit(s"$table = extractvalue ${Type.fatPointer} $obj, 0")
      val data    = freshTemp(); emit(s"$data = extractvalue ${Type.fatPointer} $obj, 1")
      val argVals = argList(args)
      val entry   = freshTemp(); emit(s"$entry = getelementptr ptr, ptr $table, i64 $slot")
      val fn      = freshTemp(); emit(s"$fn = load ptr, ptr $entry")
      val passed  = (s"ptr $data" :: argVals).mkString(", ")

      if Type.noValue(ty) then
        emit(s"call void $fn($passed)")
        if ty == Type.Never then emitTerm("unreachable")
        ""
      else
        val r = freshTemp(); emit(s"$r = call ${ty.llvm} $fn($passed)")
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

    case TTry(operand, ok, fail, retEnum, retFail, _) =>
      genTry(operand, ok, fail, retEnum, retFail)

    case TField(receiver, _, ty) if Type.zeroSized(ty) =>
      genExpr(receiver); ""

    case TField(receiver, index, ty) =>
      val rv = genExpr(receiver); val r = freshTemp()
      emit(s"$r = extractvalue ${receiver.ty.llvm} $rv, ${fieldSlot(receiver.ty, index)}"); r

    case TIf(cond, thenBlock, elseBlock, ty) =>
      genIf(cond, thenBlock, elseBlock, ty)

    case TMatch(scrutinee, arms, ty) =>
      genMatch(scrutinee, arms, ty)

    case w: TWhile   => genWhile(w)
    case l: TLoop    => genLoop(l)
    case f: TCFor    => genCFor(f)
    case f: TFor     => genFor(f)
    case e: TForEach => genForEach(e)

}

object Codegen {

  /** Lowers a typed program to an LLVM IR module. */
  def generate(program: TProgram): String = new Codegen(program).gen()
}
