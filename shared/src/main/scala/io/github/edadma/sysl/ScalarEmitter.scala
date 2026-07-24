package io.github.edadma.sysl

import scala.collection.mutable

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
    val instr = ty match
      case i: Type.Integer =>
        op match
          case "+"  => "add"
          case "-"  => "sub"
          case "*"  => "mul"
          case "/"  => if i.signed then "sdiv" else "udiv"
          case "%"  => if i.signed then "srem" else "urem"
          case "<<" => "shl"
          case ">>" => if i.signed then "ashr" else "lshr"
          case "&"  => "and"
          case "|"  => "or"
          case "^"  => "xor"
          case _    => sys.error(s"unreachable arith '$op'")
      case _: Type.Floating =>
        op match
          case "+" => "fadd"
          case "-" => "fsub"
          case "*" => "fmul"
          case "/" => "fdiv"
          case _   => sys.error(s"unreachable arith '$op'")
      case other => sys.error(s"unreachable arith on ${other.llvm}")

    val r = freshTemp(); emit(s"$r = $instr ${ty.llvm} $lv, $rv"); r
  }

  /** The `icmp` / `fcmp` predicate for an operator at a type. `char` compares by scalar
   * value, so it uses the unsigned predicates over its `i32` representation.
   */
  protected def predicate(op: String, ty: Type): String = ty match
    // Equality only: a bool and an address have no ordering, so no signed/unsigned choice.
    case Type.Bool | _: Type.Ptr | _: Type.Ref =>
      op match
        case "==" => "eq"; case "!=" => "ne"
        case _    => sys.error(s"unreachable compare '$op'")
    case Type.Char | Type.Integer(_, false, _) =>
      op match
        case "==" => "eq"; case "!=" => "ne"
        case "<"  => "ult"; case ">" => "ugt"; case "<=" => "ule"; case ">=" => "uge"
        case _    => sys.error(s"unreachable compare '$op'")
    case _: Type.Integer =>
      op match
        case "==" => "eq"; case "!=" => "ne"
        case "<"  => "slt"; case ">" => "sgt"; case "<=" => "sle"; case ">=" => "sge"
        case _    => sys.error(s"unreachable compare '$op'")
    case _: Type.Floating =>
      op match
        case "==" => "oeq"; case "!=" => "one"
        case "<"  => "olt"; case ">" => "ogt"; case "<=" => "ole"; case ">=" => "oge"
        case _    => sys.error(s"unreachable compare '$op'")
    case other => sys.error(s"unreachable compare on ${other.llvm}")

  protected def compareValue(op: String, ty: Type, av: String, bv: String): String =
    // Two strings are ordered by their bytes, which is a call rather than an instruction; every
    // operator then reads the same -1 / 0 / 1 the way it would read a subtraction.
    if ty == Type.Str then
      val c = strCmp(av, bv)
      val r = freshTemp()
      emit(s"$r = icmp ${predicate(op, Type.Int)} i32 $c, 0")
      r
    else
      val instr = if ty.isInstanceOf[Type.Floating] then "fcmp" else "icmp"
      val r     = freshTemp()
      emit(s"$r = $instr ${predicate(op, ty)} ${ty.llvm} $av, $bv")
      r

  // --- conversions ---------------------------------------------------------------------

  /** Lowers an explicit scalar conversion. Every case is a single LLVM cast, except the
   * partial `char(u)` — the one conversion that can fail, and so the one that checks.
   */
  protected def convert(from: Type, to: Type, v: String): String = (from, to) match
    case _ if from == to => v

    case (a: Type.Integer, b: Type.Integer) =>
      if b.bits == a.bits then v
      else if b.bits < a.bits then castOp("trunc", a, b, v)
      else castOp(if a.signed then "sext" else "zext", a, b, v)

    case (a: Type.Integer, b: Type.Floating)  => castOp(if a.signed then "sitofp" else "uitofp", a, b, v)
    case (a: Type.Floating, b: Type.Integer)  => saturatingCast(a, b, v)
    case (a: Type.Floating, b: Type.Floating) => castOp(if b.bits > a.bits then "fpext" else "fptrunc", a, b, v)

    case (Type.Char, b: Type.Integer) => convert(Type.Integer(32, signed = false), b, v)
    case (a: Type.Integer, Type.Char) => checkedChar(a, v)

    // A simple enum is stored at its underlying integer width, so converting it to another
    // integer is just that integer conversion; every enum value is already in range.
    case (e: Type.Enum, b: Type.Integer) => convert(e.underlying, b, v)

    case _ => sys.error(s"unreachable conversion from ${from.llvm} to ${to.llvm}")

  private def castOp(instr: String, from: Type, to: Type, v: String): String = {
    val r = freshTemp(); emit(s"$r = $instr ${from.llvm} $v to ${to.llvm}"); r
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
    val r = freshTemp()
    emit(s"$r = call ${to.llvm} @$name(${from.llvm} $v)")
    r
  }

  /** `char(u)` — a checked conversion. A Unicode scalar value is at most `0x10FFFF` and never
   * a surrogate; anything else traps, in the same runtime-safety category as a bounds check.
   * The test runs at 64 bits so a wide source cannot smuggle a value past it.
   */
  private def checkedChar(from: Type.Integer, v: String): String = {
    val wide     = convert(from, Type.Integer(64, from.signed), v)
    val inRange  = freshTemp(); emit(s"$inRange = icmp ule i64 $wide, 1114111")
    val belowLow = freshTemp(); emit(s"$belowLow = icmp ult i64 $wide, 55296")
    val aboveTop = freshTemp(); emit(s"$aboveTop = icmp ugt i64 $wide, 57343")
    val scalar   = freshTemp(); emit(s"$scalar = or i1 $belowLow, $aboveTop")
    val ok       = freshTemp(); emit(s"$ok = and i1 $inRange, $scalar")

    trapUnless(ok, "char")
    castOp("trunc", Type.Integer(64, from.signed), Type.Char, wide)
  }

  /** Traps unless a condition holds. This is the shape every runtime check takes: a compare, a
   * branch, `llvm.trap`, and an unreachable arm, with the checked path falling through.
   */
  protected def trapUnless(ok: String, what: String): Unit = {
    traps = true

    val okL  = freshLabel(s"$what.ok")
    val badL = freshLabel(s"$what.bad")

    emitTerm(s"br i1 $ok, label %$okL, label %$badL")
    emitLabel(badL)
    emit("call void @llvm.trap()")
    emitTerm("unreachable")
    emitLabel(okL)
  }

  // --- print ---------------------------------------------------------------------------

  /** `print` is one `printf` per run of arguments that `printf` can carry. A string breaks the
   * run, because it is written by length rather than up to a terminator, so the format built so
   * far is flushed — with the separator that would have preceded the string tacked on — and a
   * new one starts after it.
   */
  protected def genPrint(args: List[TExpr]): Unit = {
    val specs    = mutable.ListBuffer.empty[String]
    val callArgs = mutable.ListBuffer.empty[String]

    def flush(tail: String): Unit =
      if specs.nonEmpty || tail.nonEmpty then
        val fmt = stringGlobal(specs.mkString + tail)
        val r   = freshTemp()
        emit(s"$r = call i32 (ptr, ...) @printf(${(s"ptr $fmt" :: callArgs.toList).mkString(", ")})")
        specs.clear()
        callArgs.clear()

    for (arg, argIndex) <- args.zipWithIndex do
      val sep = if argIndex == 0 then "" else " "

      def spec(s: String, callArg: String): Unit = { specs += sep + s; callArgs += callArg }

      arg.ty match
        // Varargs promote, so a narrow value is widened here rather than left to the ABI.
        case i: Type.Integer =>
          val wide = if i.bits <= 32 then Type.Integer(32, i.signed) else Type.Integer(64, i.signed)
          val v    = convert(i, wide, genExpr(arg))
          spec(
            if i.signed then (if wide.bits == 32 then "%d" else "%lld")
            else if wide.bits == 32 then "%u"
            else "%llu",
            s"${wide.llvm} $v",
          )

        case f: Type.Floating =>
          spec("%g", s"double ${convert(f, Type.Real, genExpr(arg))}")

        case Type.Char =>
          charBuf = true
          val cp  = genExpr(arg)
          val buf = emitAlloca(freshTemp(), "[5 x i8]")
          val enc = freshTemp()
          emit(s"$enc = call ptr @sysl.utf8(i32 $cp, ptr $buf)")
          spec("%s", s"ptr $enc")

        case Type.Str =>
          flush(sep)
          printStr(genExpr(arg))

        case Type.Bool =>
          boolStrs = true
          val v   = genExpr(arg)
          val sel = freshTemp()
          emit(s"$sel = select i1 $v, ptr @.true, ptr @.false")
          spec("%s", s"ptr $sel")

        case other => sys.error(s"unreachable print of ${other.llvm}")

    flush("\n")
  }

  /** `str(x)` — a value's string form. A `string` is returned unchanged, since it already is one;
   * every other type is rendered into a fresh owning buffer, so the result is an owned temporary
   * the enclosing statement releases. A `bool` renders to one of two immortal literals and needs
   * no allocation at all.
   */
  protected def genStr(arg: TExpr): String = arg.ty match
    case Type.Str =>
      // Identity: the same value, its count already the argument's to manage.
      genExpr(arg)

    case Type.Bool =>
      boolStrs = true
      val v   = genExpr(arg)
      val ptr = freshTemp(); emit(s"$ptr = select i1 $v, ptr @.true, ptr @.false")
      val len = freshTemp(); emit(s"$len = select i1 $v, i64 4, i64 5")
      strView("null", ptr, len)

    case Type.Char =>
      charBuf = true
      heap = true
      request("sysl.str.from_bytes")(StringEmitter.fromBytes)
      val fn = request("sysl.str.char")(StringEmitter.char)
      val cp = genExpr(arg)
      val r  = freshTemp()
      emit(s"$r = call ${Type.Str.llvm} @$fn(i32 $cp)")
      r

    case i: Type.Integer =>
      heap = true
      request("sysl.str.from_bytes")(StringEmitter.fromBytes)
      val fn     = request("sysl.str.int")(StringEmitter.int)
      val wide   = convert(i, Type.Integer(64, i.signed), genExpr(arg))
      val signed = if i.signed then "1" else "0"
      val r      = freshTemp()
      emit(s"$r = call ${Type.Str.llvm} @$fn(i64 $wide, i1 $signed)")
      r

    case f: Type.Floating =>
      heap = true
      usesSnprintf = true
      val fn = request("sysl.str.float")(StringEmitter.float)
      val v  = convert(f, Type.Real, genExpr(arg))
      val r  = freshTemp()
      emit(s"$r = call ${Type.Str.llvm} @$fn(double $v)")
      r

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
    val r   = freshTemp()

    if FormatSpec.isStr(c) then
      val fn     = request("sysl.str.fmt_s")(StringEmitter.fmtStr)
      val (p, n) = strBytes(genExpr(arg))
      emit(s"$r = call ${Type.Str.llvm} @$fn(ptr $fmt, ptr $p, i64 $n)")
    else if FormatSpec.isFloat(c) then
      val fn = request("sysl.str.fmt_f")(StringEmitter.fmtFloat)
      val v  = convert(arg.ty.asInstanceOf[Type.Floating], Type.Real, genExpr(arg))
      emit(s"$r = call ${Type.Str.llvm} @$fn(ptr $fmt, double $v)")
    else
      // A signed conversion widens by the value's own signedness, so a decimal keeps its value; an
      // unsigned one reads the bits as unsigned, so `%x` shows exactly the value's own width. Both
      // end at 64 bits and print through a `%ll…`.
      val fn = request("sysl.str.fmt_i")(StringEmitter.fmtInt)
      val i  = arg.ty.asInstanceOf[Type.Integer]
      val v =
        if FormatSpec.isSignedInt(c) then convert(i, Type.Integer(64, i.signed), genExpr(arg))
        else
          val unsigned = convert(i, Type.Integer(i.bits, signed = false), genExpr(arg))
          convert(Type.Integer(i.bits, signed = false), Type.Integer(64, signed = false), unsigned)
      emit(s"$r = call ${Type.Str.llvm} @$fn(ptr $fmt, i64 $v)")

    r
  }

  /** Builds a string value from its three words. */
  private def strView(owner: String, ptr: String, len: String): String = {
    val v0 = freshTemp(); emit(s"$v0 = insertvalue ${Type.Str.llvm} undef, ptr $owner, 0")
    val v1 = freshTemp(); emit(s"$v1 = insertvalue ${Type.Str.llvm} $v0, ptr $ptr, 1")
    val v2 = freshTemp(); emit(s"$v2 = insertvalue ${Type.Str.llvm} $v1, i64 $len, 2")
    v2
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
