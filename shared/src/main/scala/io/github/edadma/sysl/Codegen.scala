package io.github.edadma.sysl

import scala.collection.mutable

/** Lowers a typed program (`TProgram`) to a textual LLVM IR module.
 *
 * Codegen makes no semantic decisions — the analyzer has already resolved every name, fixed
 * every type, and checked every rule. This pass is a straight translation: it selects
 * instructions from the types the tree carries and lays out basic blocks. Opaque pointers and
 * hex-double float constants keep the output stable across the textual round-trip.
 */
class Codegen private (program: TProgram) {

  private val globals  = new mutable.StringBuilder
  private var strId    = 0
  private var boolStrs = false
  private var charBuf  = false
  private var traps    = false

  // Per-function emission state, reset at each function boundary.
  private var prologue   = new mutable.StringBuilder
  private var body       = new mutable.StringBuilder
  private var temp       = 0
  private var label      = 0
  private var terminated = false

  private def startFunction(): Unit = {
    prologue = new mutable.StringBuilder
    body = new mutable.StringBuilder
    temp = 0
    label = 0
    terminated = false
  }

  private def freshTemp(): String            = { temp += 1; s"%t$temp" }
  private def freshLabel(s: String): String  = { label += 1; s"$s$label" }

  /** Emits a plain instruction, unless the current block is already terminated. */
  private def emit(line: String): Unit =
    if !terminated then { body ++= "  "; body ++= line; body ++= "\n" }

  /** Emits a stack slot into the function's entry block rather than where it is needed.
   * Every name is unique within a function, so hoisting is safe — and it keeps a slot inside
   * a loop from growing the stack on every iteration.
   */
  private def emitAlloca(name: String, ty: String): String = {
    prologue ++= s"  $name = alloca $ty\n"
    name
  }

  /** Emits a block terminator (`br` / `ret` / `unreachable`) and marks the block closed. */
  private def emitTerm(line: String): Unit =
    if !terminated then { body ++= "  "; body ++= line; body ++= "\n"; terminated = true }

  private def emitLabel(l: String): Unit = { body ++= l; body ++= ":\n"; terminated = false }

  // --- string interning ----------------------------------------------------------------

  private def stringGlobal(s: String): String = {
    strId += 1
    val name           = s"@.str$strId"
    val (escaped, len) = encode(s)
    globals ++= s"$name = private constant [$len x i8] c\"$escaped\"\n"
    name
  }

  private def encode(s: String): (String, Int) = {
    val bytes = s.getBytes("UTF-8")
    val sb    = new mutable.StringBuilder
    for b <- bytes do
      val u = b & 0xff
      if u == '"'.toInt || u == '\\'.toInt || u < 0x20 || u >= 0x7f then sb ++= f"\\$u%02X"
      else sb += u.toChar
    sb ++= "\\00"
    (sb.toString, bytes.length + 1)
  }

  // --- module --------------------------------------------------------------------------

  private def gen(): String = {
    val funcTexts = program.funcs.map(genFunction)
    val mainText  = genMain(program.main)

    val out = new mutable.StringBuilder
    out ++= "declare i32 @printf(ptr, ...)\n"
    if traps then out ++= "declare void @llvm.trap()\n"
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

    if boolStrs then
      out ++= "@.true = private constant [5 x i8] c\"true\\00\"\n"
      out ++= "@.false = private constant [6 x i8] c\"false\\00\"\n"
    out ++= globals.toString
    if globals.nonEmpty || boolStrs then out ++= "\n"

    if charBuf then out ++= Codegen.utf8Encoder

    for t <- funcTexts do out ++= t; out ++= "\n"
    out ++= mainText
    out.toString
  }

  private def genMain(stmts: List[TStmt]): String = {
    startFunction()
    stmts.foreach(genStmt)
    emitTerm("ret i32 0")
    s"define i32 @main() {\nentry:\n$prologue$body}\n"
  }

  private def genFunction(f: TFunc): String = {
    startFunction()

    for (name, ty) <- f.params do
      emitAlloca(s"%$name.addr", ty.llvm)
      emit(s"store ${ty.llvm} %$name.param, ptr %$name.addr")

    f.body.stmts.foreach(genStmt)

    f.body.result match
      case Some(r) if f.retTy != Type.Unit => emitTerm(s"ret ${f.retTy.llvm} ${genExpr(r)}")
      case Some(r)                         => genExpr(r); emitTerm("ret void")
      case None if f.retTy == Type.Unit    => emitTerm("ret void")
      case None                            => emitTerm(s"ret ${f.retTy.llvm} ${zero(f.retTy)}")

    val params = f.params.map { case (name, ty) => s"${ty.llvm} %$name.param" }.mkString(", ")
    s"define ${f.retTy.llvm} @${f.name}($params) {\nentry:\n$prologue$body}\n"
  }

  private def zero(ty: Type): String = ty match
    case _: Type.Integer  => "0"
    case _: Type.Floating => "0.0"
    case Type.Char        => "0"
    case Type.Bool        => "0"
    case Type.Str         => "null"
    case _: Type.Ptr      => "null"
    case _: Type.Ref      => "null"
    case _: Type.Struct   => "zeroinitializer"
    case e: Type.Enum     => if e.simple then "0" else "zeroinitializer"
    case Type.Unit        => ""

  // --- statements ----------------------------------------------------------------------

  private def genStmt(stmt: TStmt): Unit = stmt match
    case TVarDecl(name, ty, init) =>
      val v = genExpr(init)
      emitAlloca(s"%$name.addr", ty.llvm)
      emit(s"store ${ty.llvm} $v, ptr %$name.addr")

    case TExprStmt(expr) =>
      genExpr(expr)

    case TWhile(cond, whileBody) =>
      val condL = freshLabel("while.cond")
      val bodyL = freshLabel("while.body")
      val endL  = freshLabel("while.end")
      emitTerm(s"br label %$condL")
      emitLabel(condL)
      emitTerm(s"br i1 ${genExpr(cond)}, label %$bodyL, label %$endL")
      emitLabel(bodyL)
      whileBody.foreach(genStmt)
      emitTerm(s"br label %$condL")
      emitLabel(endL)

    case TFor(name, ty, lo, hi, inclusive, forBody) =>
      val w     = ty.llvm
      val loV   = genExpr(lo)
      val hiV   = genExpr(hi)
      val condL = freshLabel("for.cond")
      val bodyL = freshLabel("for.body")
      val endL  = freshLabel("for.end")
      emitAlloca(s"%$name.addr", w)
      emit(s"store $w $loV, ptr %$name.addr")
      emitTerm(s"br label %$condL")
      emitLabel(condL)
      val iv  = freshTemp(); emit(s"$iv = load $w, ptr %$name.addr")
      val cmp = freshTemp(); emit(s"$cmp = icmp ${predicate(if inclusive then "<=" else "<", ty)} $w $iv, $hiV")
      emitTerm(s"br i1 $cmp, label %$bodyL, label %$endL")
      emitLabel(bodyL)
      forBody.foreach(genStmt)
      val cur = freshTemp(); emit(s"$cur = load $w, ptr %$name.addr")
      val nxt = freshTemp(); emit(s"$nxt = add $w $cur, 1")
      emit(s"store $w $nxt, ptr %$name.addr")
      emitTerm(s"br label %$condL")
      emitLabel(endL)

    case TReturn(opt) =>
      opt match
        case Some(t) => emitTerm(s"ret ${t.ty.llvm} ${genExpr(t)}")
        case None    => emitTerm("ret void")

  // --- expressions ---------------------------------------------------------------------

  /** Lowers an expression, returning the register or immediate holding its value (empty for a
   * unit-typed expression, whose value is never read).
   */
  private def genExpr(expr: TExpr): String = expr match
    case TIntLit(v, _) => v.toString
    case TStrLit(s)    => stringGlobal(s)
    case TBoolLit(b)   => if b then "1" else "0"
    case TNullLit(_)   => "null"
    case TUnitLit()    => ""

    // A narrower float is the `double` constant rounded to it, which folds away entirely.
    case TFloatLit(bits, ty) =>
      if ty == Type.Real then bits
      else { val r = freshTemp(); emit(s"$r = fptrunc double $bits to ${ty.llvm}"); r }

    case TCast(operand, ty) =>
      convert(operand.ty, ty, genExpr(operand))

    case TLoad(name, ty) =>
      val r = freshTemp(); emit(s"$r = load ${ty.llvm}, ptr %$name.addr"); r

    case TDeref(operand, ty) =>
      val p = genExpr(operand)
      val r = freshTemp(); emit(s"$r = load ${ty.llvm}, ptr $p"); r

    case TAddrOf(place, _) =>
      address(place)

    case TStore(place, value, ty) =>
      val v = genExpr(value); emit(s"store ${ty.llvm} $v, ptr ${address(place)}"); v

    case TUpdate(place, op, value, ty) =>
      val p       = address(place)
      val cur     = freshTemp(); emit(s"$cur = load ${ty.llvm}, ptr $p")
      val v       = genExpr(value)
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

    case TLogical(op, l, r) =>
      val lv = genExpr(l); val rv = genExpr(r); val res = freshTemp()
      emit(s"$res = ${if op == "&&" then "and" else "or"} i1 $lv, $rv"); res

    case TCompare(operands, ops) =>
      val cmps = ops.indices.map(i => compareOne(ops(i), operands(i), operands(i + 1))).toList
      cmps.reduce { (a, b) => val r = freshTemp(); emit(s"$r = and i1 $a, $b"); r }

    case TPrint(args) =>
      genPrint(args); ""

    case TCall(name, args, ty) =>
      val argVals = args.map(a => s"${a.ty.llvm} ${genExpr(a)}")
      if ty == Type.Unit then
        emit(s"call void @$name(${argVals.mkString(", ")})"); ""
      else
        val r = freshTemp(); emit(s"$r = call ${ty.llvm} @$name(${argVals.mkString(", ")})"); r

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

  /** The address of a place, as a `ptr` register or an existing slot name. Every place bottoms
   * out either in a local's stack slot or in a pointer the program already holds, so this walks
   * the field chain with `getelementptr` rather than reading values out with `extractvalue`.
   */
  private def address(place: TExpr): String = place match
    case TLoad(name, _) => s"%$name.addr"
    case TDeref(operand, _) => genExpr(operand)
    case TField(receiver, index, _) =>
      val base = address(receiver)
      val r    = freshTemp()
      emit(s"$r = getelementptr ${receiver.ty.llvm}, ptr $base, i32 0, i32 $index")
      r
    case other => sys.error(s"unreachable address of ${other.getClass.getSimpleName}")

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
    emitTerm(s"ret ${retEnum.llvm} ${enumValue(retEnum, retFail, payloadFields(en, fail, v))}")

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
      val r = freshTemp(); emit(s"$r = load ${ty.llvm}, ptr $slot"); r
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
          bind()
        case Some(g) =>
          val guardL = freshLabel("match.guard")
          emitTerm(s"br i1 $patCond, label %$guardL, label %$nextL")
          emitLabel(guardL)
          bind()
          emitTerm(s"br i1 ${genExpr(g)}, label %$bodyL, label %$nextL")
          emitLabel(bodyL)

      if ty == Type.Unit then genBlockVoid(arm.body)
      else emit(s"store ${ty.llvm} ${genBlockValue(arm.body)}, ptr $slot")
      emitTerm(s"br label %$endL")
      emitLabel(nextL)

    // Fallthrough with no matching arm: a value or enum match is exhaustive (the analyzer
    // required full coverage or a catch-all), so this point is unreachable; a plain scalar
    // statement match simply proceeds.
    if ty == Type.Unit then emitTerm(s"br label %$endL") else emitTerm("unreachable")
    emitLabel(endL)
    if ty == Type.Unit then "" else { val r = freshTemp(); emit(s"$r = load ${ty.llvm}, ptr $slot"); r }
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
      emit(s"store ${bty.llvm} $value, ptr %$name.addr")
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

  private def genBlockVoid(b: TBlock): Unit = {
    b.stmts.foreach(genStmt)
    b.result.foreach(genExpr)
  }

  private def genBlockValue(b: TBlock): String = {
    b.stmts.foreach(genStmt)
    genExpr(b.result.get)
  }

  // --- arithmetic and comparison -------------------------------------------------------

  /** Arithmetic wraps at the operand's declared width, which plain LLVM integer instructions
   * already do — the width is in the type, so no masking is needed even for an odd one.
   * Signedness picks between the division, remainder, and right-shift pairs.
   */
  private def arith(op: String, ty: Type, lv: String, rv: String): String = {
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

  private def compareOne(op: String, a: TExpr, b: TExpr): String =
    compareValue(op, a.ty, genExpr(a), genExpr(b))

  /** The `icmp` / `fcmp` predicate for an operator at a type. `char` compares by scalar
   * value, so it uses the unsigned predicates over its `i32` representation.
   */
  private def predicate(op: String, ty: Type): String = ty match
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

  private def compareValue(op: String, ty: Type, av: String, bv: String): String = {
    val instr = if ty.isInstanceOf[Type.Floating] then "fcmp" else "icmp"
    val r     = freshTemp()

    emit(s"$r = $instr ${predicate(op, ty)} ${ty.llvm} $av, $bv")
    r
  }

  // --- conversions ---------------------------------------------------------------------

  /** Lowers an explicit scalar conversion. Every case is a single LLVM cast, except the
   * partial `char(u)` — the one conversion that can fail, and so the one that checks.
   */
  private def convert(from: Type, to: Type, v: String): String = (from, to) match
    case _ if from == to => v

    case (a: Type.Integer, b: Type.Integer) =>
      if b.bits == a.bits then v
      else if b.bits < a.bits then castOp("trunc", a, b, v)
      else castOp(if a.signed then "sext" else "zext", a, b, v)

    case (a: Type.Integer, b: Type.Floating)  => castOp(if a.signed then "sitofp" else "uitofp", a, b, v)
    case (a: Type.Floating, b: Type.Integer)  => castOp(if b.signed then "fptosi" else "fptoui", a, b, v)
    case (a: Type.Floating, b: Type.Floating) => castOp(if b.bits > a.bits then "fpext" else "fptrunc", a, b, v)

    case (Type.Char, b: Type.Integer) => convert(Type.Integer(32, signed = false), b, v)
    case (a: Type.Integer, Type.Char) => checkedChar(a, v)

    case _ => sys.error(s"unreachable conversion from ${from.llvm} to ${to.llvm}")

  private def castOp(instr: String, from: Type, to: Type, v: String): String = {
    val r = freshTemp(); emit(s"$r = $instr ${from.llvm} $v to ${to.llvm}"); r
  }

  /** `char(u)` — a checked conversion. A Unicode scalar value is at most `0x10FFFF` and never
   * a surrogate; anything else traps, in the same runtime-safety category as a bounds check.
   * The test runs at 64 bits so a wide source cannot smuggle a value past it.
   */
  private def checkedChar(from: Type.Integer, v: String): String = {
    traps = true

    val wide     = convert(from, Type.Integer(64, from.signed), v)
    val inRange  = freshTemp(); emit(s"$inRange = icmp ule i64 $wide, 1114111")
    val belowLow = freshTemp(); emit(s"$belowLow = icmp ult i64 $wide, 55296")
    val aboveTop = freshTemp(); emit(s"$aboveTop = icmp ugt i64 $wide, 57343")
    val scalar   = freshTemp(); emit(s"$scalar = or i1 $belowLow, $aboveTop")
    val ok       = freshTemp(); emit(s"$ok = and i1 $inRange, $scalar")

    val okL   = freshLabel("char.ok")
    val badL  = freshLabel("char.bad")
    emitTerm(s"br i1 $ok, label %$okL, label %$badL")
    emitLabel(badL)
    emit("call void @llvm.trap()")
    emitTerm("unreachable")
    emitLabel(okL)

    castOp("trunc", Type.Integer(64, from.signed), Type.Char, wide)
  }

  // --- print ---------------------------------------------------------------------------

  private def genPrint(args: List[TExpr]): Unit = {
    val specs    = mutable.ListBuffer.empty[String]
    val callArgs = mutable.ListBuffer.empty[String]

    for arg <- args do
      arg.ty match
        // Varargs promote, so a narrow value is widened here rather than left to the ABI.
        case i: Type.Integer =>
          val wide = if i.bits <= 32 then Type.Integer(32, i.signed) else Type.Integer(64, i.signed)
          val v    = convert(i, wide, genExpr(arg))
          specs += (if i.signed then (if wide.bits == 32 then "%d" else "%lld")
                    else if wide.bits == 32 then "%u"
                    else "%llu")
          callArgs += s"${wide.llvm} $v"

        case f: Type.Floating =>
          val v = convert(f, Type.Real, genExpr(arg))
          specs += "%g"; callArgs += s"double $v"

        case Type.Char =>
          charBuf = true
          val cp  = genExpr(arg)
          val buf = emitAlloca(freshTemp(), "[5 x i8]")
          val enc = freshTemp()
          emit(s"$enc = call ptr @sysl.utf8(i32 $cp, ptr $buf)")
          specs += "%s"; callArgs += s"ptr $enc"

        case Type.Str => specs += "%s"; callArgs += s"ptr ${genExpr(arg)}"

        case Type.Bool =>
          boolStrs = true
          val v   = genExpr(arg)
          val sel = freshTemp()
          emit(s"$sel = select i1 $v, ptr @.true, ptr @.false")
          specs += "%s"; callArgs += s"ptr $sel"

        case other => sys.error(s"unreachable print of ${other.llvm}")

    val fmt = stringGlobal(specs.mkString(" ") + "\n")
    val r   = freshTemp()
    emit(s"$r = call i32 (ptr, ...) @printf(${(s"ptr $fmt" :: callArgs.toList).mkString(", ")})")
  }
}

object Codegen {

  /** Lowers a typed program to an LLVM IR module. */
  def generate(program: TProgram): String = new Codegen(program).gen()

  /** Encodes one Unicode scalar value as NUL-terminated UTF-8 into a caller-supplied
   * five-byte buffer, so printing a `char` is an ordinary `%s` argument alongside the rest.
   * Emitted only into modules that print one.
   */
  private val utf8Encoder: String =
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
