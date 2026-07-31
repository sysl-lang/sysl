package io.github.edadma.sysl

/** The expression dispatch, and the call seam it is built on.
 *
 * This is the widest single `match` in the compiler and it stays one, because that is what it is:
 * every arm's outermost pattern is a node type, so the cases cannot shadow each other and the
 * compiler's exhaustiveness check is the guarantee that a new node has somewhere to go. Splitting it
 * across traits would trade that for a fall-through nobody could read.
 *
 * What the arms have in common is that none of them decides anything. A type is read off the node,
 * an instruction is selected from it, and the work that is more than an instruction has already been
 * lifted into a named method on one of the traits underneath — `genSlice`, `genMatch`, `genTry`,
 * `genForEach`. An arm that is still long here is long because the *emitted code* is long, not
 * because a decision is being made in it.
 *
 * The call seam sits at the top for the same reason it exists at all: what a `call` names is not
 * simply the callee's sysl name. An `extern` may have been given a link name, the program's own
 * `main` is renamed out of the way of the entry point the platform starts, a variadic callee needs
 * its whole function type rather than just its result, and a foreign one is lowered under the
 * convention the other side was compiled against rather than sysl's own (`ForeignEmitter`). Four
 * questions, one answer each, asked once here instead of at every call site.
 */
trait ExprEmitter extends ControlFlowEmitter with VtableEmitter with WriterEmitter with StaticEmitter
    with ForeignEmitter with ContractEmitter with EnumAttrEmitter {

  /** The typed program being lowered, which the seam below reads to learn what a name resolves to. */
  protected val program: TProgram

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

  /** What a compound assignment stores: a slot that holds a count — a string, or a struct with a
   * reference in it — builds a fresh value the slot will take a count for, and a slot of anything
   * else is arithmetic.
   */
  protected def combine(op: String, ty: Type, valueTy: Type, dispatch: Option[TDispatch],
                        cur: String, v: String): String =
    (ty, dispatch) match
      case (_, Some(d))  => ownTemp(dispatchValue(d, ty, valueTy, cur, v, ty), ty)
      case (Type.Str, _) => ownTemp(strConcat(cur, v), Type.Str)
      // A constrained slot is arithmetic at the type it is laid out as, which is what the binary
      // path does too — the subtype names a set of values, not a second way to add. It detects
      // overflow on the same terms as well: `a += e` computes what `a = a + e` computes, so a range
      // that invites a wrap has to be caught here too, or the two spellings disagree about a value
      // the produce-site check would then never see.
      case _ =>
        val bin = op.dropRight(1)
        val bt  = Type.underlying(ty)

        bt match
          case it: Type.Integer if bin == "<<" && isRanged(ty)                       => checkedShl(it, cur, v)
          case it: Type.Integer if (bin == "+" || bin == "-" || bin == "*") && isRanged(ty) =>
            checkedArith(bin, it, cur, v)
          case _ => arith(bin, bt, cur, v)

  /** Whether an integer `+`/`-`/`*` needs the overflow-detecting form. Arithmetic on raw integers is
   * defined to wrap, so it is only in play when an operand was produced through a ranged (`within`)
   * type — the opt-in that makes arithmetic checked — and only then when the exact result the
   * operands' ranges allow can fall outside the base width. A range whose results always fit the
   * width stays on the plain path, so the common case (a small counter, an index) costs nothing.
   */
  private def overflowChecked(op: String, l: TExpr, r: TExpr, bt: Type.Integer): Boolean =
    (op == "+" || op == "-" || op == "*") && (isRanged(l.ty) || isRanged(r.ty)) && {
      val (alo, ahi) = interval(l, bt)
      val (blo, bhi) = interval(r, bt)
      val (rlo, rhi) = op match
        case "+" => (alo + blo, ahi + bhi)
        case "-" => (alo - bhi, ahi - blo)
        case "*" =>
          val corners = List(alo * blo, alo * bhi, ahi * blo, ahi * bhi)

          (corners.min, corners.max)
        case _ => sys.error(s"unreachable overflowChecked '$op'")

      rlo < baseMin(bt) || rhi > baseMax(bt)
    }

  /** A ranged constrained integer — a `within` type — is the opt-in that makes arithmetic checked;
   * a predicate-only subtype or a bare `new` derivation bounds no magnitude and does not qualify.
   */
  protected def isRanged(t: Type): Boolean = t match
    case c: Type.Constrained => (c.lo.isDefined || c.hi.isDefined) && Type.underlying(c).isInstanceOf[Type.Integer]
    case _                   => false

  /** The exact value interval an operand can hold: a literal is its own value; a ranged type is its
   * declared bounds (`..<` excludes the top); anything else spans the whole base width.
   */
  private def interval(e: TExpr, bt: Type.Integer): (BigInt, BigInt) = e match
    case TIntLit(v, _) => (v, v)
    case _ =>
      e.ty match
        case c: Type.Constrained if isRanged(c) =>
          val lo = c.lo.map(_.toBigInt).getOrElse(baseMin(bt))
          val hi = c.hi.map(h => if c.exclusiveHi then h.toBigInt - 1 else h.toBigInt).getOrElse(baseMax(bt))

          (lo, hi)
        case _ => (baseMin(bt), baseMax(bt))

  private def baseMin(t: Type.Integer): BigInt = if t.signed then -(BigInt(2).pow(t.bits - 1)) else BigInt(0)

  private def baseMax(t: Type.Integer): BigInt =
    if t.signed then BigInt(2).pow(t.bits - 1) - 1 else BigInt(2).pow(t.bits) - 1

  /** Every callee declared with a `...`, foreign or sysl's own, mapped to the LLVM function type a
   * call to it must name: result type, declared parameter types, ellipsis.
   */
  private val variadics: Map[String, String] =
    val fromExterns = program.externs.filter(_.variadic).map(e => e.name -> foreignFnType(e.retTy, e.params))
    val fromFuncs   = program.funcs.filter(_.variadic).map { f =>
      val params = Type.stored(f.params).map(_._2.llvm) :+ "..."

      f.name -> s"${if Type.noValue(f.retTy) then "void" else f.retTy.llvm} (${params.mkString(", ")})"
    }

    (fromExterns ++ fromFuncs).toMap

  /** The `extern`s a call may resolve to, so a foreign call is lowered by what the other side's
   * convention asks for rather than by what sysl's own would be (`ForeignEmitter`).
   */
  private val foreigns: Map[String, TExtern] = program.externs.map(e => e.name -> e).toMap

  /** The symbol a called name resolves to, which differs from the name for an `extern` given a link
   * name and for the program's own `main`. Everything else is emitted under its own name.
   *
   * `main` is renamed because the emitted entry point *is* `@main`: the platform starts the program
   * there, and a sysl function of that name would be a second definition of one symbol. The reserved
   * name it takes instead holds two separators, which no key can (`Modules.qualify` writes one), so it
   * cannot collide with anything a program or a module could be called.
   */
  protected val entrySymbol = s"${Modules.sep}${Modules.sep}main"

  private val symbols: Map[String, String] =
    program.externs.collect { case e if e.symbol != e.name => e.name -> e.symbol }.toMap ++
      program.entry.map(_.func -> entrySymbol)

  /** What a definition and every call to it name. */
  protected def symbolOf(name: String): String = symbols.getOrElse(name, name)

  /** What a `call` names. For an ordinary function that is the result type, which is all LLVM
   * needs; for a variadic one it is the callee's *whole* function type, because the argument list
   * alone does not say where the declared parameters stop and the ellipsis begins.
   */
  private def calleeOf(name: String, ty: Type): String =
    val symbol = symbolOf(name)
    // A foreign result may be named by a type the sysl signature never mentions — a coerced
    // aggregate, or `void` where the value comes back through an out-parameter.
    val result = if foreigns.contains(name) then foreignResultType(ty) else if Type.noValue(ty) then "void" else ty.llvm

    variadics.get(name) match
      case Some(fnTy) => s"$fnTy @$symbol"
      case None       => s"$result @$symbol"

  /** One comparison, over two values the caller is holding: an instruction where the operand type
   * has one, the method its `Eq`/`Ord` supplies otherwise.
   */
  private def comparison(c: TCmp, ty: Type, av: String, bv: String): String =
    c.dispatch match
      // A comparison stays homogeneous — `Eq` and `Ord` take no right-hand type — so both operands
      // are the one type here, unlike the arithmetic traits.
      case Some(d) => dispatchValue(d, ty, ty, av, bv, Type.Bool)
      // A constrained value compares through its base's instruction — the derived type has no
      // ordering of its own, it inherits the base's.
      case None    => compareValue(c.op, Type.underlying(ty), av, bv)

  /** Applies an operator's trait method to two **values** rather than two expressions (`14 §3`).
   *
   * That is the whole distinction from lowering a `TCall`: the forms that reach here — a comparison
   * chain and a compound assignment — each use one operand twice from a single evaluation, and the
   * value is already in a register. `swap` and `negate` carry the derivation of the comparisons the
   * catalog declares no method for (`14 §2`), so `a > b` calls the one `lt` its `impl` wrote.
   *
   * The two operands carry their **own** types, which an arithmetic trait no longer requires to be
   * the same one (`14 §7`): `c *= 2.0` passes a complex number and a `real`. A swap exchanges the
   * values and their types together, since it is the values that are being reordered.
   */
  private def dispatchValue(d: TDispatch, aty: Type, bty: Type, av: String, bv: String, resultTy: Type): String = {
    val (l, lty, r, rty) = if d.swap then (bv, bty, av, aty) else (av, aty, bv, bty)
    val res              = freshTemp()

    emit(s"$res = call ${calleeOf(d.name, resultTy)}(${lty.llvm} $l, ${rty.llvm} $r)")

    if !d.negate then res
    else
      val n = freshTemp(); emit(s"$n = xor i1 $res, true"); n
  }
  /** The zero value of a type — what a slot holds before anything is stored into it, and what a
   * function with no trailing expression returns.
   */
  protected def zero(ty: Type): String = ty match
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
    case Type.Weak(inner) => if inner.isInstanceOf[Type.Trait] then "zeroinitializer" else "null"
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
    // A result list belongs to a signature. The analyzer's one funnel turns it into the tuple its
    // parts lay out as before anything holds it, so codegen is only ever handed that tuple.
    case _: Type.Results  => sys.error("unreachable zero of a result list")
    // A trait is only ever the pointee of a `*Trait` / `&Trait`, both handled above; resolving a
    // bare trait name is a diagnostic, so nothing of this type is ever laid out.
    case _: Type.Trait    => sys.error("unreachable zero of a trait")
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

    case TConstrainedValid(value, c) =>
      val v    = genExpr(value)
      val base = Type.underlying(c.base)
      val geLo = compareValue(">=", base, v, c.lo.get.toBigInt.toString)
      val leHi = compareValue(if c.exclusiveHi then "<" else "<=", base, v, c.hi.get.toBigInt.toString)
      val r = freshTemp(); emit(s"$r = and i1 $geLo, $leHi"); r

    case TConstrainedStep(value, c, up, _) =>
      val v    = genExpr(value)
      val base = Type.underlying(c.base)
      val last = if c.exclusiveHi then c.hi.get - 1 else c.hi.get
      // `Succ` traps at `Last`, `Pred` at `First`; the value is otherwise one step along the base.
      if up then trapUnless(compareValue("<", base, v, last.toBigInt.toString), "succ")
      else trapUnless(compareValue(">", base, v, c.lo.get.toBigInt.toString), "pred")
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
      storeInto(ty, p, v)
      v

    case TUpdate(place, op, value, ty, dispatch, check) =>
      val p       = address(place)
      val cur     = freshTemp(); emit(s"$cur = load ${ty.llvm}, ptr $p")
      val v       = genExpr(value)
      val updated = combine(op, ty, value.ty, dispatch, cur, v)

      for c <- check do emitConstraintChecks(updated, c)

      if containsRef(ty) then
        retainValue(ty, updated)
        emit(s"store ${ty.llvm} $updated, ptr $p")
        releaseValue(ty, cur)
      else emit(s"store ${ty.llvm} $updated, ptr $p")
      updated

    case TIncDec(place, op, pre, ty, check) =>
      val w   = ty.llvm
      val p   = address(place)
      val cur = freshTemp(); emit(s"$cur = load $w, ptr $p")
      val nv  = freshTemp(); emit(s"$nv = ${if op == "++" then "add" else "sub"} $w $cur, 1")

      for c <- check do emitConstraintChecks(nv, c)

      emit(s"store $w $nv, ptr $p")
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
      val p   = freshTemp(); emit(s"$p = extractvalue ${arg.ty.llvm} $v, 1")
      val n   = freshTemp(); emit(s"$n = extractvalue ${arg.ty.llvm} $v, 2")
      val r   = freshTemp(); emit(s"$r = call ${Type.Str.llvm} @$fn(ptr $p, i64 $n)")
      ownTemp(r, Type.Str)

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
    case TRender(value, method, spec, vslot) =>
      heap = true
      request("sysl.str.from_bytes")(StringEmitter.fromBytes)

      val table = bufferTable()
      val v     = genExpr(value)
      val s     = genExpr(spec)
      val slot  = emitAlloca(freshTemp(), bufferLayout)

      emit(s"store $bufferLayout zeroinitializer, ptr $slot")
      val a = freshTemp(); emit(s"$a = insertvalue ${Type.fatPointer} undef, ptr @$table, 0")
      val w = freshTemp(); emit(s"$w = insertvalue ${Type.fatPointer} $a, ptr $slot, 1")

      // A trait object renders through the table it carries, so the callee and the receiver both
      // come out of the value: the data word is the receiver a slot's entry expects, exactly as it
      // is for any other call through one.
      vslot match
        case Some(n) =>
          val vt   = freshTemp(); emit(s"$vt = extractvalue ${Type.fatPointer} $v, 0")
          val data = freshTemp(); emit(s"$data = extractvalue ${Type.fatPointer} $v, 1")
          val e    = freshTemp(); emit(s"$e = getelementptr ptr, ptr $vt, i64 $n")
          val fn   = freshTemp(); emit(s"$fn = load ptr, ptr $e")

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
        case Type.Ptr(e) => Layout.size(e)
        case _           => 1
      val (lv, rv) = (genExpr(l), genExpr(r))
      val (la, ra) = (freshTemp(), freshTemp())
      emit(s"$la = ptrtoint ptr $lv to ${Type.Isize.llvm}")
      emit(s"$ra = ptrtoint ptr $rv to ${Type.Isize.llvm}")
      val bytes = freshTemp()
      emit(s"$bytes = sub ${Type.Isize.llvm} $la, $ra")
      if stride <= 1 then bytes
      else
        val n = freshTemp()
        emit(s"$n = sdiv ${Type.Isize.llvm} $bytes, $stride")
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
      val v = genExpr(operand); val r = freshTemp(); emit(s"$r = sub ${ty.llvm} 0, $v"); r
    case TUnary("-", operand, ty) if Type.underlying(ty).isInstanceOf[Type.Floating] =>
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

    // A call to a foreign function is lowered under the other side's convention rather than sysl's
    // own, which is a difference only an aggregate can see (`ForeignEmitter`).
    case TCall(name, args, ty, _) if foreigns.contains(name) =>
      genForeignCall(calleeOf(name, ty), args, ty)

    // A call to something declared `-> never` does not come back, so the block ends at it: what
    // follows in the same block is unreachable and `emit` drops it, which is exactly why a
    // diverging arm needs no special handling anywhere else.
    case TCall(name, args, ty, _) =>
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
    case TVCall(receiver, slot, args, ty, _) =>
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

    // The one place the C ABI is not the same on every machine, so the one place codegen reads the
    // target (`targets.md`). All three answers are a `ptr`, and all three start from the address of
    // the walk — what differs is whether the callee is handed that address, the value in it, or the
    // address of a copy of it.
    case TVaPass(ap) =>
      val addr = genExpr(ap)

      target.vaList match
        case VaListAbi.Address => addr

        case VaListAbi.Loaded =>
          val r = freshTemp(); emit(s"$r = load ptr, ptr $addr"); r

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
    case i: TIterate => genIterate(i)
}
