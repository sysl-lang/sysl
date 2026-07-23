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

  // Per-function emission state, reset at each function boundary.
  private var body       = new mutable.StringBuilder
  private var temp       = 0
  private var label      = 0
  private var terminated = false

  private def startFunction(): Unit = {
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
    out ++= "declare i32 @printf(ptr, ...)\n\n"

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

    for t <- funcTexts do out ++= t; out ++= "\n"
    out ++= mainText
    out.toString
  }

  private def genMain(stmts: List[TStmt]): String = {
    startFunction()
    stmts.foreach(genStmt)
    emitTerm("ret i32 0")
    s"define i32 @main() {\nentry:\n$body}\n"
  }

  private def genFunction(f: TFunc): String = {
    startFunction()

    for (name, ty) <- f.params do
      emit(s"%$name.addr = alloca ${ty.llvm}")
      emit(s"store ${ty.llvm} %$name.param, ptr %$name.addr")

    f.body.stmts.foreach(genStmt)

    f.body.result match
      case Some(r) if f.retTy != Type.Unit => emitTerm(s"ret ${f.retTy.llvm} ${genExpr(r)}")
      case Some(r)                         => genExpr(r); emitTerm("ret void")
      case None if f.retTy == Type.Unit    => emitTerm("ret void")
      case None                            => emitTerm(s"ret ${f.retTy.llvm} ${zero(f.retTy)}")

    val params = f.params.map { case (name, ty) => s"${ty.llvm} %$name.param" }.mkString(", ")
    s"define ${f.retTy.llvm} @${f.name}($params) {\nentry:\n$body}\n"
  }

  private def zero(ty: Type): String = ty match
    case Type.Int       => "0"
    case Type.Bool      => "0"
    case Type.Real      => "0x0000000000000000"
    case Type.Str       => "null"
    case _: Type.Struct => "zeroinitializer"
    case e: Type.Enum   => if e.simple then "0" else "zeroinitializer"
    case Type.Unit      => ""

  // --- statements ----------------------------------------------------------------------

  private def genStmt(stmt: TStmt): Unit = stmt match
    case TVarDecl(name, ty, init) =>
      val v = genExpr(init)
      emit(s"%$name.addr = alloca ${ty.llvm}")
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

    case TFor(name, lo, hi, inclusive, forBody) =>
      val loV   = genExpr(lo)
      val hiV   = genExpr(hi)
      val condL = freshLabel("for.cond")
      val bodyL = freshLabel("for.body")
      val endL  = freshLabel("for.end")
      emit(s"%$name.addr = alloca i32")
      emit(s"store i32 $loV, ptr %$name.addr")
      emitTerm(s"br label %$condL")
      emitLabel(condL)
      val iv   = freshTemp(); emit(s"$iv = load i32, ptr %$name.addr")
      val cmp  = freshTemp(); emit(s"$cmp = icmp ${if inclusive then "sle" else "slt"} i32 $iv, $hiV")
      emitTerm(s"br i1 $cmp, label %$bodyL, label %$endL")
      emitLabel(bodyL)
      forBody.foreach(genStmt)
      val cur = freshTemp(); emit(s"$cur = load i32, ptr %$name.addr")
      val nxt = freshTemp(); emit(s"$nxt = add i32 $cur, 1")
      emit(s"store i32 $nxt, ptr %$name.addr")
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
    case TFloatLit(b)  => b
    case TStrLit(s)    => stringGlobal(s)
    case TBoolLit(b)   => if b then "1" else "0"
    case TUnitLit()    => ""

    case TLoad(name, ty) =>
      val r = freshTemp(); emit(s"$r = load ${ty.llvm}, ptr %$name.addr"); r

    case TStore(name, value, ty) =>
      val v = genExpr(value); emit(s"store ${ty.llvm} $v, ptr %$name.addr"); v

    case TUpdate(name, op, value, ty) =>
      val cur     = freshTemp(); emit(s"$cur = load ${ty.llvm}, ptr %$name.addr")
      val v       = genExpr(value)
      val updated = arith(op.dropRight(1), ty, cur, v)
      emit(s"store ${ty.llvm} $updated, ptr %$name.addr")
      updated

    case TIncDec(name, op, pre) =>
      val cur = freshTemp(); emit(s"$cur = load i32, ptr %$name.addr")
      val nv  = freshTemp(); emit(s"$nv = ${if op == "++" then "add" else "sub"} i32 $cur, 1")
      emit(s"store i32 $nv, ptr %$name.addr")
      if pre then nv else cur

    case TBinary(op, l, r, _) =>
      arith(op, l.ty, genExpr(l), genExpr(r))

    case TUnary("-", operand, Type.Int) =>
      val v = genExpr(operand); val r = freshTemp(); emit(s"$r = sub i32 0, $v"); r
    case TUnary("-", operand, Type.Real) =>
      val v = genExpr(operand); val r = freshTemp(); emit(s"$r = fneg double $v"); r
    case TUnary("!", operand, _) =>
      val v = genExpr(operand); val r = freshTemp(); emit(s"$r = xor i1 $v, true"); r
    case TUnary("~", operand, _) =>
      val v = genExpr(operand); val r = freshTemp(); emit(s"$r = xor i32 $v, -1"); r
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
      if en.simple then variant.tag.toString
      else
        val tagged = freshTemp()
        emit(s"$tagged = insertvalue ${en.llvm} undef, i32 ${variant.tag}, 0")
        variant.payloadSlot match
          case None => tagged
          case Some(slot) =>
            val vals    = args.map(genExpr)
            var payload = "undef"
            for (v, i) <- vals.zipWithIndex do
              val r = freshTemp()
              emit(s"$r = insertvalue ${en.payloadLlvm(variant)} $payload, ${variant.fields(i)._2.llvm} $v, $i")
              payload = r
            val r = freshTemp()
            emit(s"$r = insertvalue ${en.llvm} $tagged, ${en.payloadLlvm(variant)} $payload, $slot")
            r

    case TField(receiver, index, ty) =>
      val rv = genExpr(receiver); val r = freshTemp()
      emit(s"$r = extractvalue ${receiver.ty.llvm} $rv, $index"); r

    case TSetField(name, struct, index, value, ty) =>
      val v = genExpr(value); val p = freshTemp()
      emit(s"$p = getelementptr ${struct.llvm}, ptr %$name.addr, i32 0, i32 $index")
      emit(s"store ${ty.llvm} $v, ptr $p")
      v

    case TIf(cond, thenBlock, elseBlock, ty) =>
      genIf(cond, thenBlock, elseBlock, ty)

    case TMatch(scrutinee, arms, ty) =>
      genMatch(scrutinee, arms, ty)

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
      val slot = freshTemp(); emit(s"$slot = alloca ${ty.llvm}")
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
    val slot = if ty == Type.Unit then "" else { val s = freshTemp(); emit(s"$s = alloca ${ty.llvm}"); s }

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
      emit(s"%$name.addr = alloca ${bty.llvm}")
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

  private def arith(op: String, ty: Type, lv: String, rv: String): String = {
    val (kind, instr) = ty match
      case Type.Int =>
        ("i32", op match
          case "+" => "add"; case "-" => "sub"; case "*" => "mul"; case "/" => "sdiv"; case "%" => "srem"
          case "<<" => "shl"; case ">>" => "ashr"; case "&" => "and"; case "|" => "or"; case "^" => "xor")
      case Type.Real =>
        ("double", op match
          case "+" => "fadd"; case "-" => "fsub"; case "*" => "fmul"; case "/" => "fdiv")
      case other => sys.error(s"unreachable arith on ${other.llvm}")
    val r = freshTemp(); emit(s"$r = $instr $kind $lv, $rv"); r
  }

  private def compareOne(op: String, a: TExpr, b: TExpr): String =
    compareValue(op, a.ty, genExpr(a), genExpr(b))

  private def compareValue(op: String, ty: Type, av: String, bv: String): String = {
    val r = freshTemp()
    ty match
      case Type.Int =>
        val pred = op match
          case "==" => "eq"; case "!=" => "ne"; case "<" => "slt"; case ">" => "sgt"; case "<=" => "sle"; case ">=" => "sge"
        emit(s"$r = icmp $pred i32 $av, $bv")
      case Type.Real =>
        val pred = op match
          case "==" => "oeq"; case "!=" => "one"; case "<" => "olt"; case ">" => "ogt"; case "<=" => "ole"; case ">=" => "oge"
        emit(s"$r = fcmp $pred double $av, $bv")
      case other => sys.error(s"unreachable compare on ${other.llvm}")
    r
  }

  // --- print ---------------------------------------------------------------------------

  private def genPrint(args: List[TExpr]): Unit = {
    val specs    = mutable.ListBuffer.empty[String]
    val callArgs = mutable.ListBuffer.empty[String]

    for arg <- args do
      arg.ty match
        case Type.Int  => specs += "%d"; callArgs += s"i32 ${genExpr(arg)}"
        case Type.Real => specs += "%g"; callArgs += s"double ${genExpr(arg)}"
        case Type.Str  => specs += "%s"; callArgs += s"ptr ${genExpr(arg)}"
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
}
