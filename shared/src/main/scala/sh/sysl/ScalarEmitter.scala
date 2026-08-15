package sh.sysl

import ir.{Access, Arg, BinOp, CastOp, FCmp, ICmp, Inst, LType, Val}

/** The scalar end of codegen: arithmetic, comparison, conversion, and printing.
 *
 * Everything here follows from `01-scalar-types-and-operators.md` — arithmetic wraps at the
 * operand's declared width and never promotes, ordering distinguishes signed from unsigned,
 * and every conversion between types is one the programmer wrote.
 *
 * The two operations that are not scalar at all — comparing and printing a `string` — are
 * dispatched from here because that is where an operator and a `print` argument arrive, but
 * what they do with the bytes belongs to `StringEmitter`.
 */
trait ScalarEmitter extends StringEmitter {

  /** Arithmetic wraps at the operand's declared width, which plain LLVM integer instructions
   * already do — the width is in the type, so no masking is needed even for an odd one.
   * Signedness picks between the division, remainder, and right-shift pairs.
   */
  protected def arith(op: String, ty: Type, lv: String, rv: String): String = {
    // **A vector picks its instruction from the lane and emits at the whole register.** LLVM spells
    // both widths of the word with one mnemonic — `fadd <4 x float>` is the same opcode as `fadd
    // float` — so lane-wise arithmetic needs no operation of its own, only the type it is written
    // at. Integer `/` and `%` are the one pair that would need more than this, because their guards
    // below reduce a lane-wise comparison to a single `i1`; the analyzer refuses those on a vector,
    // so nothing reaches here needing a guard it cannot build.
    val lane = ty match
      case Type.Vector(_, elem) => Type.underlying(elem)
      case other                => other

    val instr = lane match
      case i: Type.Integer =>
        op match
          case "+"  => BinOp.Add
          case "-"  => BinOp.Sub
          case "*"  => BinOp.Mul
          case "/"  => if i.signed then BinOp.SDiv else BinOp.UDiv
          case "%"  => if i.signed then BinOp.SRem else BinOp.URem
          case "<<" => BinOp.Shl
          case ">>" => if i.signed then BinOp.AShr else BinOp.LShr
          case "&"  => BinOp.And
          case "|"  => BinOp.Or
          case "^"  => BinOp.Xor
          case _    => sys.error(s"unreachable arith '$op'")
      // A mask's lane is an `i1`, and the three bitwise instructions are the same ones an integer
      // takes — which is why this arrives here at all rather than needing a path of its own. Only a
      // *vector* of `bool` reaches it: a scalar `bool` has `&&` and `||`, which are control flow and
      // are lowered somewhere else entirely.
      case Type.Bool =>
        op match
          case "&" => BinOp.And
          case "|" => BinOp.Or
          case "^" => BinOp.Xor
          case _   => sys.error(s"unreachable mask arith '$op'")
      case _: Type.Floating =>
        op match
          case "+" => BinOp.FAdd
          case "-" => BinOp.FSub
          case "*" => BinOp.FMul
          case "/" => BinOp.FDiv
          case _   => sys.error(s"unreachable arith '$op'")
      case other => sys.error(s"unreachable arith on ${other.llvm}")

    // Integer division and remainder have two inputs LLVM leaves undefined and would miscompile: a
    // zero divisor always, and — for signed operands — INT_MIN by -1, whose true quotient overflows
    // the width. A zero divisor traps; signed `/` traps on that overflow; signed `%` returns its
    // defined zero by dividing by 1 in the one case -1 would make the remainder undefined, since
    // `a % -1` is 0 for every `a`. None of this touches raw multiplication or addition, which wrap.
    var divisor = rv
    ty match
      case i: Type.Integer if op == "/" || op == "%" =>
        val nz = freshReg(); emit(Inst.IntCmp(nz, ICmp.Ne, ty.lty, Val.Raw(rv), Val.Int(0)))
        trapUnless(nz, "div")
        if i.signed then
          val neg1 = freshReg(); emit(Inst.IntCmp(neg1, ICmp.Eq, ty.lty, Val.Raw(rv), Val.Int(-1)))
          if op == "/" then
            val min   = -(BigInt(2).pow(i.bits - 1))
            val isMin = freshReg(); emit(Inst.IntCmp(isMin, ICmp.Eq, ty.lty, Val.Raw(lv), Val.Int(min)))
            val ovf   = freshReg(); emit(Inst.Bin(ovf, BinOp.And, i1, isMin, neg1))
            val ok    = freshReg(); emit(Inst.Bin(ok, BinOp.Xor, i1, ovf, Val.Bool(true)))
            trapUnless(ok, "overflow")
          else
            val safe = freshReg()

            emit(Inst.Select(safe, neg1, ty.lty, Val.Int(1), Val.Raw(rv)))
            divisor = safe.render
      case _ =>

    val r = freshReg(); emit(Inst.Bin(r, instr, ty.lty, Val.Raw(lv), Val.Raw(divisor))); r.render
  }

  /** Integer `+`, `-`, `*` where the operands' declared ranges allow a result the base width cannot
   * hold, so a plain instruction would wrap before the range check at the produce site could see it.
   * The overflow-detecting intrinsic traps on a representation overflow, so what the range check then
   * examines is the true result. Raw integer arithmetic is defined to wrap and never reaches here;
   * a range narrow enough that its results always fit the width stays on the plain path above.
   */
  protected def checkedArith(op: String, ty: Type.Integer, lv: String, rv: String): String = {
    val name = op match
      case "+" => "add"
      case "-" => "sub"
      case "*" => "mul"
      case _   => sys.error(s"unreachable checkedArith '$op'")
    val fn = s"llvm.${if ty.signed then "s" else "u"}$name.with.overflow.${ty.llvm}"
    satDecls += s"declare {${ty.llvm}, i1} @$fn(${ty.llvm}, ${ty.llvm})"

    // The intrinsic hands back the value and the overflow flag together, and it is the one place
    // the compiler writes an aggregate LLVM names without a name: `{i32, i1}`, spelled with no
    // interior spaces because that is what the declaration above it says.
    val both = LType.Named(s"{${ty.llvm}, i1}")
    val pair = freshReg()

    emit(Inst.Call(Some(pair), both.render, Val.Global(fn),
                   List(Arg(ty.lty, Val.Raw(lv)), Arg(ty.lty, Val.Raw(rv)))))
    val v   = freshReg(); emit(Inst.Extract(v, both, pair, List(0)))
    val ovf = freshReg(); emit(Inst.Extract(ovf, both, pair, List(1)))
    val ok  = freshReg(); emit(Inst.Bin(ok, BinOp.Xor, i1, ovf, Val.Bool(true)))
    trapUnless(ok, "overflow")
    v.render
  }

  /** A left shift of a value produced through a ranged type. There is no overflow intrinsic for a
   * shift, so the check is direct: the amount must be below the width — the machine shift is
   * undefined at or above it — and shifting the result back must recover the value, since a bit
   * pushed out of the top is exactly the overflow. Raw shifts, the hot bit-manipulation path, are
   * left to wrap and never reach here.
   */
  protected def checkedShl(ty: Type.Integer, lv: String, sh: String): String = {
    val amtOk = freshReg(); emit(Inst.IntCmp(amtOk, ICmp.Ult, ty.lty, Val.Raw(sh), Val.Int(ty.bits)))
    trapUnless(amtOk, "overflow")

    val r    = freshReg(); emit(Inst.Bin(r, BinOp.Shl, ty.lty, Val.Raw(lv), Val.Raw(sh)))
    val back = freshReg()

    emit(Inst.Bin(back, if ty.signed then BinOp.AShr else BinOp.LShr, ty.lty, r, Val.Raw(sh)))
    val ok = freshReg(); emit(Inst.IntCmp(ok, ICmp.Eq, ty.lty, back, Val.Raw(lv)))
    trapUnless(ok, "overflow")
    r.render
  }

  /** The `icmp` / `fcmp` predicate for an operator at a type. `char` compares by scalar
   * value, so it uses the unsigned predicates over its `i32` representation.
   */
  protected def predicate(op: String, ty: Type): String = ty match
    case _: Type.Floating => floatPred(op).render
    case other            => intPred(op, other).render

  /** The `icmp` predicate for an operator at a type. `char` compares by scalar value, so it uses the
   * unsigned predicates over its `i32` representation.
   */
  protected def intPred(op: String, ty: Type): ICmp = ty match
    // Equality only: a bool and an address have no ordering, so no signed/unsigned choice.
    case Type.Bool | _: Type.Ptr | _: Type.Ref | _: Type.CFn =>
      op match
        case "==" => ICmp.Eq; case "!=" => ICmp.Ne
        case _    => sys.error(s"unreachable compare '$op'")
    case Type.Char | Type.Integer(_, false, _) =>
      op match
        case "==" => ICmp.Eq;  case "!=" => ICmp.Ne
        case "<"  => ICmp.Ult; case ">"  => ICmp.Ugt
        case "<=" => ICmp.Ule; case ">=" => ICmp.Uge
        case _    => sys.error(s"unreachable compare '$op'")
    case _: Type.Integer =>
      op match
        case "==" => ICmp.Eq;  case "!=" => ICmp.Ne
        case "<"  => ICmp.Slt; case ">"  => ICmp.Sgt
        case "<=" => ICmp.Sle; case ">=" => ICmp.Sge
        case _    => sys.error(s"unreachable compare '$op'")
    case other => sys.error(s"unreachable compare on ${other.llvm}")

  /** The `fcmp` predicate. IEEE 754 makes `!=` the negation of `==`, and a `NaN` is equal to nothing
   * including itself — so `!=` is **unordered** or not equal (`une`), not ordered and not equal
   * (`one`). Every other float comparison is the ordered one, which is what makes all four of `==`,
   * `<`, `<=` and `>=` false at a `NaN` while `!=` is true.
   */
  protected def floatPred(op: String): FCmp = op match
    case "==" => FCmp.Oeq; case "!=" => FCmp.Une
    case "<"  => FCmp.Olt; case ">"  => FCmp.Ogt
    case "<=" => FCmp.Ole; case ">=" => FCmp.Oge
    case _    => sys.error(s"unreachable compare '$op'")

  protected def compareValue(op: String, base: Type, av: String, bv: String): String = {
    // A constrained subtype is laid out as the type it narrows (`16 §1`), so it is compared as that
    // one — its values are that type's values, and the range it was declared with is checked where
    // it is *produced* rather than where two of them are ordered. Done here rather than at each
    // caller because every caller wants it: an ordinary `n < 6` arrives already reduced, and a
    // pattern's test does not, which is what left `n match 1..6` reaching a signedness question
    // asked of a type that has no answer to it.
    // A **simple** enum is its discriminant — `Type.Enum.llvm` delegates to the storage integer, so
    // the value in hand is already one — and equality on it is that integer's compare. Read here for
    // the same reason a constrained subtype is: every caller wants it, and the signedness question
    // has no answer asked of the enum itself.
    val ty = Type.underlying(base) match
      case e: Type.Enum if e.simple => e.underlying
      case other                    => other

    // Two strings are ordered by their bytes, which is a call rather than an instruction; every
    // operator then reads the same -1 / 0 / 1 the way it would read a subtraction.
    if ty == Type.Str then
      val c = strCmp(av, bv)
      val r = freshReg()
      emit(Inst.IntCmp(r, intPred(op, Type.Int), i32, Val.Raw(c), Val.Int(0)))
      r.render
    else
      val r = freshReg()

      ty match
        case _: Type.Floating => emit(Inst.FloatCmp(r, floatPred(op), ty.lty, Val.Raw(av), Val.Raw(bv)))
        case _                => emit(Inst.IntCmp(r, intPred(op, ty), ty.lty, Val.Raw(av), Val.Raw(bv)))

      r.render
  }

  // --- conversions ---------------------------------------------------------------------

  /** Lowers an explicit scalar conversion. Every case is a single LLVM cast, except the
   * partial `char(u)` — the one conversion that can fail, and so the one that checks.
   */
  protected def convert(from: Type, to: Type, v: String): String = (from, to) match
    case _ if from == to => v

    case (a: Type.Integer, b: Type.Integer) =>
      if b.bits == a.bits then v
      else if b.bits < a.bits then castOp(CastOp.Trunc, a, b, v)
      else castOp(if a.signed then CastOp.SExt else CastOp.ZExt, a, b, v)

    case (a: Type.Integer, b: Type.Floating)  => castOp(if a.signed then CastOp.SIToFP else CastOp.UIToFP, a, b, v)
    case (a: Type.Floating, b: Type.Integer)  => saturatingCast(a, b, v)
    case (a: Type.Floating, b: Type.Floating) => castOp(if b.bits > a.bits then CastOp.FPExt else CastOp.FPTrunc, a, b, v)

    case (Type.Char, b: Type.Integer) => convert(Type.Integer(32, signed = false), b, v)
    case (a: Type.Integer, Type.Char) => checkedChar(a, v)

    // A simple enum is stored at its underlying integer width, so converting it to another
    // integer is just that integer conversion; every enum value is already in range.
    case (e: Type.Enum, b: Type.Integer) => convert(e.underlying, b, v)

    // The raw tier (`03 § Reinterpreting storage`). Two pointee types are the same `ptr` under
    // opaque pointers, so reading one as the other is nothing at all at this level — which is
    // exactly the claim the language is making about it.
    case (_: Type.Ptr, _: Type.Ptr)      => v
    case (a: Type.Ptr, b: Type.Integer)  => castOp(CastOp.PtrToInt, a, b, v)
    case (a: Type.Integer, b: Type.Ptr)  => castOp(CastOp.IntToPtr, a, b, v)

    // An address of code and an address of bytes are the same word, which is what makes `dlsym`
    // usable in one direction and a `*u8` callback table usable in the other (`12 §6a`).
    case (_: Type.Ptr, _: Type.CFn)      => v
    case (_: Type.CFn, _: Type.Ptr)      => v
    case (_: Type.CFn, _: Type.CFn)      => v
    case (a: Type.CFn, b: Type.Integer)  => castOp(CastOp.PtrToInt, a, b, v)
    case (a: Type.Integer, b: Type.CFn)  => castOp(CastOp.IntToPtr, a, b, v)

    case _ => sys.error(s"unreachable conversion from ${from.llvm} to ${to.llvm}")

  private def castOp(instr: CastOp, from: Type, to: Type, v: String): String = {
    val r = freshReg(); emit(Inst.Cast(r, instr, from.lty, Val.Raw(v), to.lty)); r.render
  }

  /** Float-to-integer, saturating. A plain `fptosi`/`fptoui` is poison when the source is out of
   * the target's range or is NaN, and what the hardware then does differs by target — so the same
   * program would print different numbers on different machines. The `llvm.fpto{s,u}i.sat`
   * intrinsics pin it down everywhere: out of range clamps to the type's minimum or maximum, and
   * NaN becomes zero. `int()` stays total; `char()` remains the one conversion that traps.
   */
  private def saturatingCast(from: Type.Floating, to: Type.Integer, v: String): String = {
    val op   = if to.signed then "fptosi.sat" else "fptoui.sat"
    val name = s"llvm.$op.${to.llvm}.f${from.bits}"
    satDecls += s"declare ${to.llvm} @$name(${from.llvm})"
    val r = freshReg()
    emit(Inst.Call(Some(r), to.llvm, Val.Global(name), List(Arg(from.lty, Val.Raw(v)))))
    r.render
  }

  /** `char(u)` — a checked conversion. A Unicode scalar value is at most `0x10FFFF` and never
   * a surrogate; anything else traps, in the same runtime-safety category as a bounds check.
   * The test runs at 64 bits so a wide source cannot smuggle a value past it.
   */
  private def checkedChar(from: Type.Integer, v: String): String = {
    val wide     = convert(from, Type.Integer(64, from.signed), v)
    val i64      = LType.I(64)
    val inRange  = freshReg(); emit(Inst.IntCmp(inRange, ICmp.Ule, i64, Val.Raw(wide), Val.Int(1114111)))
    val belowLow = freshReg(); emit(Inst.IntCmp(belowLow, ICmp.Ult, i64, Val.Raw(wide), Val.Int(55296)))
    val aboveTop = freshReg(); emit(Inst.IntCmp(aboveTop, ICmp.Ugt, i64, Val.Raw(wide), Val.Int(57343)))
    val scalar   = freshReg(); emit(Inst.Bin(scalar, BinOp.Or, i1, belowLow, aboveTop))
    val ok       = freshReg(); emit(Inst.Bin(ok, BinOp.And, i1, inRange, scalar))

    trapUnless(ok, "char")
    castOp(CastOp.Trunc, Type.Integer(64, from.signed), Type.Char, wide)
  }

  /** Traps unless a condition holds. This is the shape every runtime check takes: a compare, a
   * branch, `llvm.trap`, and an unreachable arm, with the checked path falling through.
   */
  protected def trapUnless(ok: Val, what: String): Unit = {
    traps = true

    val okL  = freshLabel(s"$what.ok")
    val badL = freshLabel(s"$what.bad")

    emitTerm(Inst.CondBr(ok, okL, badL))
    emitLabel(badL)
    emit(Inst.Call(None, "void", Val.Global("llvm.trap"), Nil))
    emitTerm(Inst.Unreachable)
    emitLabel(okL)
  }

  /** `str(x)` — a value's string form. A `string` is returned unchanged, since it already is one;
   * every other type is rendered into a fresh owning buffer, so the result is an owned temporary
   * the enclosing statement releases. A `bool` renders to one of two immortal literals and needs
   * no allocation at all.
   */
  protected def genStr(arg: TExpr): String = Type.underlying(arg.ty) match
    case Type.Str =>
      // Identity: the same value, its count already the argument's to manage.
      genExpr(arg)

    case Type.Bool =>
      boolStrs = true
      val v   = genExpr(arg)
      val ptr = freshReg()
      val len = freshReg()

      emit(Inst.Select(ptr, Val.Raw(v), LType.Ptr, Val.Global(".true"), Val.Global(".false")))
      emit(Inst.Select(len, Val.Raw(v), wordLty, Val.Int(4), Val.Int(5)))
      strView(Val.Null, ptr, len)

    case Type.Char =>
      charBuf = true
      heap = true
      request("sysl.str.from_bytes")(StringEmitter.fromBytes)
      val fn = request("sysl.str.char")(StringEmitter.char)
      val cp = genExpr(arg)
      val r  = freshReg()
      emit(Inst.Call(Some(r), Type.Str.llvm, Val.Global(fn), List(Arg(i32, Val.Raw(cp)))))
      r.render

    // Rendered at a width that holds the value, which for anything past 64 bits is **the value's
    // own**. Up to 64 it goes through the renderer every program has always used — one instance,
    // shared, and the overwhelmingly common case.
    //
    // **Beyond that the width may not be clamped**, which is what this did while 128 was the
    // ceiling and every wider value was unreachable. Rendering an `i256` through a 128-bit renderer
    // would not be a lossy answer, it would be the wrong number: the conversion below narrows first
    // and the digits printed are of the truncated value. A renderer is generated per width instead,
    // and `StringEmitter.intName` gives each its own symbol.
    case i: Type.Integer =>
      heap = true
      request("sysl.str.from_bytes")(StringEmitter.fromBytes)
      val bits   = if i.bits > 64 then i.bits else 64
      val fn     = request(StringEmitter.intName(bits))(StringEmitter.int(bits))
      val wide   = convert(i, Type.Integer(bits, i.signed), genExpr(arg))
      val r = freshReg()

      emit(Inst.Call(Some(r), Type.Str.llvm, Val.Global(fn),
                     List(Arg(LType.I(bits), Val.Raw(wide)), Arg(i1, Val.Int(if i.signed then 1 else 0)))))
      r.render

    case f: Type.Floating =>
      heap = true
      usesSnprintf = true
      val fn = request("sysl.str.float")(StringEmitter.float)
      val v  = convert(f, Type.Real, genExpr(arg))
      val r  = freshReg()
      emit(Inst.Call(Some(r), Type.Str.llvm, Val.Global(fn), List(Arg(LType.F(64), Val.Raw(v)))))
      r.render

    case other => sys.error(s"unreachable str of ${other.llvm}")

  /** `format(x, spec)` — one value rendered through a printf specifier, into a fresh owning string.
   * A numeric value is widened and handed to `snprintf` with the C form of the specifier; a string
   * is copied NUL-terminated and handed to `snprintf` as a `%s`, so width, precision, and
   * justification are C's to apply. The result is always a fresh buffer this statement owns.
   */
  protected def genFormat(arg: TExpr, spec: String): String = {
    heap = true
    usesSnprintf = true

    val c   = FormatSpec.conversion(spec)
    val fmt = stringGlobal(FormatSpec.cFormat(spec))
    val r   = freshReg()

    def call(fn: String, rest: List[Arg]): Unit =
      emit(Inst.Call(Some(r), Type.Str.llvm, Val.Global(fn), Arg(LType.Ptr, fmt) :: rest))

    if FormatSpec.isStr(c) then
      val fn     = request("sysl.str.fmt_s")(StringEmitter.fmtStr)
      val (p, n) = strBytes(genExpr(arg))
      call(fn, List(Arg(LType.Ptr, Val.Raw(p)), Arg(wordLty, Val.Raw(n))))
    else if FormatSpec.isFloat(c) then
      val fn = request("sysl.str.fmt_f")(StringEmitter.fmtFloat)
      val v  = convert(Type.underlying(arg.ty).asInstanceOf[Type.Floating], Type.Real, genExpr(arg))
      call(fn, List(Arg(LType.F(64), Val.Raw(v))))
    else
      // A signed conversion widens by the value's own signedness, so a decimal keeps its value; an
      // unsigned one reads the bits as unsigned, so `%x` shows exactly the value's own width. Both
      // end at 64 bits and print through a `%ll…`.
      val fn = request("sysl.str.fmt_i")(StringEmitter.fmtInt)
      val i  = Type.underlying(arg.ty).asInstanceOf[Type.Integer]
      val v =
        if FormatSpec.isSignedInt(c) then convert(i, Type.Integer(64, i.signed), genExpr(arg))
        else
          val unsigned = convert(i, Type.Integer(i.bits, signed = false), genExpr(arg))
          convert(Type.Integer(i.bits, signed = false), Type.Integer(64, signed = false), unsigned)
      call(fn, List(Arg(LType.I(64), Val.Raw(v))))

    r.render
  }

  /** Builds a string value from its three words. */
  private def strView(owner: Val, ptr: Val, len: Val): String = {
    val str = Type.Str.lty
    val v0  = freshReg(); emit(Inst.Insert(v0, str, Val.Undef, LType.Ptr, owner, List(0)))
    val v1  = freshReg(); emit(Inst.Insert(v1, str, v0, LType.Ptr, ptr, List(1)))
    val v2  = freshReg(); emit(Inst.Insert(v2, str, v1, wordLty, len, List(2)))
    v2.render
  }
}

object ScalarEmitter {

  /** Encodes one Unicode scalar value as NUL-terminated UTF-8 into a caller-supplied
   * five-byte buffer, so printing a `char` is an ordinary `%s` argument alongside the rest.
   * Emitted only into modules that print one.
   */
  val utf8Encoder: String =
    """define private ptr @sysl.utf8(i32 %cp, ptr %buf) {
      |entry:
      |  %ascii = icmp ult i32 %cp, 128
      |  br i1 %ascii, label %one, label %wide
      |one:
      |  %a0 = trunc i32 %cp to i8
      |  store i8 %a0, ptr %buf
      |  %a1 = getelementptr i8, ptr %buf, i32 1
      |  store i8 0, ptr %a1
      |  ret ptr %buf
      |wide:
      |  %short = icmp ult i32 %cp, 2048
      |  br i1 %short, label %two, label %wider
      |two:
      |  %b0 = lshr i32 %cp, 6
      |  %b1 = or i32 %b0, 192
      |  %b2 = trunc i32 %b1 to i8
      |  store i8 %b2, ptr %buf
      |  %b3 = and i32 %cp, 63
      |  %b4 = or i32 %b3, 128
      |  %b5 = trunc i32 %b4 to i8
      |  %b6 = getelementptr i8, ptr %buf, i32 1
      |  store i8 %b5, ptr %b6
      |  %b7 = getelementptr i8, ptr %buf, i32 2
      |  store i8 0, ptr %b7
      |  ret ptr %buf
      |wider:
      |  %bmp = icmp ult i32 %cp, 65536
      |  br i1 %bmp, label %three, label %four
      |three:
      |  %c0 = lshr i32 %cp, 12
      |  %c1 = or i32 %c0, 224
      |  %c2 = trunc i32 %c1 to i8
      |  store i8 %c2, ptr %buf
      |  %c3 = lshr i32 %cp, 6
      |  %c4 = and i32 %c3, 63
      |  %c5 = or i32 %c4, 128
      |  %c6 = trunc i32 %c5 to i8
      |  %c7 = getelementptr i8, ptr %buf, i32 1
      |  store i8 %c6, ptr %c7
      |  %c8 = and i32 %cp, 63
      |  %c9 = or i32 %c8, 128
      |  %c10 = trunc i32 %c9 to i8
      |  %c11 = getelementptr i8, ptr %buf, i32 2
      |  store i8 %c10, ptr %c11
      |  %c12 = getelementptr i8, ptr %buf, i32 3
      |  store i8 0, ptr %c12
      |  ret ptr %buf
      |four:
      |  %d0 = lshr i32 %cp, 18
      |  %d1 = or i32 %d0, 240
      |  %d2 = trunc i32 %d1 to i8
      |  store i8 %d2, ptr %buf
      |  %d3 = lshr i32 %cp, 12
      |  %d4 = and i32 %d3, 63
      |  %d5 = or i32 %d4, 128
      |  %d6 = trunc i32 %d5 to i8
      |  %d7 = getelementptr i8, ptr %buf, i32 1
      |  store i8 %d6, ptr %d7
      |  %d8 = lshr i32 %cp, 6
      |  %d9 = and i32 %d8, 63
      |  %d10 = or i32 %d9, 128
      |  %d11 = trunc i32 %d10 to i8
      |  %d12 = getelementptr i8, ptr %buf, i32 2
      |  store i8 %d11, ptr %d12
      |  %d13 = and i32 %cp, 63
      |  %d14 = or i32 %d13, 128
      |  %d15 = trunc i32 %d14 to i8
      |  %d16 = getelementptr i8, ptr %buf, i32 3
      |  store i8 %d15, ptr %d16
      |  %d17 = getelementptr i8, ptr %buf, i32 4
      |  store i8 0, ptr %d17
      |  ret ptr %buf
      |}
      |
      |""".stripMargin
}
