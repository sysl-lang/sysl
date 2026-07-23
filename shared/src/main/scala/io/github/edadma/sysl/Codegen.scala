package io.github.edadma.sysl

import scala.collection.mutable

/** A compile error carrying a message. The parser reports its own errors; this is for the
 * lowering stage (unknown name, unsupported form, type mismatch).
 */
case class CodegenError(message: String) extends RuntimeException(message)

/** The value types the bring-up codegen understands. This stands in for the full type
 * system: enough to type literals, arithmetic, comparisons, and `print`.
 */
enum Ty:
  case Int  // i32 — the `int` default
  case Real // f64 — the `real` default
  case Bool // i1
  case Str  // ptr to UTF-8 bytes
  case Unit

  def llvm: String = this match
    case Ty.Int  => "i32"
    case Ty.Real => "double"
    case Ty.Bool => "i1"
    case Ty.Str  => "ptr"
    case Ty.Unit => "void"

/** Lowers a `Program` to a textual LLVM IR module (opaque pointers, `printf`-backed
 * `print`). The whole program body becomes the entry function `main`.
 *
 * This is a single pass that carries a small type environment and computes expression types
 * as it emits — the analyzer and codegen are not yet separated. It is deliberately a thin
 * vertical slice: enough to run real arithmetic-and-loops programs, and structured so the
 * type pass can be lifted out later.
 */
class Codegen private () {

  private val body     = new mutable.StringBuilder
  private val globals  = new mutable.StringBuilder
  private var temp     = 0
  private var label    = 0
  private var strId    = 0
  private var boolStrs = false

  /** name -> (llvm register holding the alloca ptr, type) */
  private val vars = mutable.LinkedHashMap.empty[String, (String, Ty)]

  private def freshTemp(): String  = { temp += 1; s"%t$temp" }
  private def freshLabel(s: String): String = { label += 1; s"$s$label" }

  private def emit(line: String): Unit = { body ++= "  "; body ++= line; body ++= "\n" }
  private def emitLabel(l: String): Unit = { body ++= l; body ++= ":\n" }

  /** Interns a NUL-terminated string constant and returns the global's `ptr`. */
  private def stringGlobal(s: String): String = {
    strId += 1
    val name          = s"@.str$strId"
    val (escaped, len) = encode(s)

    globals ++= s"$name = private constant [$len x i8] c\"$escaped\"\n"
    name
  }

  /** Encodes a string as an LLVM byte array body (with trailing `\00`) plus its length. */
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

  private def gen(program: Program): String = {
    for stmt <- program.body do genStmt(stmt)

    val out = new mutable.StringBuilder
    out ++= "declare i32 @printf(ptr, ...)\n\n"
    if boolStrs then
      out ++= "@.true = private constant [5 x i8] c\"true\\00\"\n"
      out ++= "@.false = private constant [6 x i8] c\"false\\00\"\n"
    out ++= globals.toString
    if globals.nonEmpty || boolStrs then out ++= "\n"
    out ++= "define i32 @main() {\n"
    out ++= "entry:\n"
    out ++= body.toString
    out ++= "  ret i32 0\n"
    out ++= "}\n"
    out.toString
  }

  // --- statements ----------------------------------------------------------------------

  private def genStmt(stmt: Stmt): Unit = stmt match
    case VarDecl(name, _, init) =>
      val (ty, v) = genExpr(init)
      if ty == Ty.Unit then throw CodegenError(s"cannot bind '$name' to a unit value")
      val ptr = s"%$name.addr"
      // A fresh binding shadows re-declaration; the register name is derived from the source
      // name, so a program that re-declares the same name in one scope is rejected here.
      if vars.contains(name) then throw CodegenError(s"'$name' is already declared")
      emit(s"$ptr = alloca ${ty.llvm}")
      emit(s"store ${ty.llvm} $v, ptr $ptr")
      vars(name) = (ptr, ty)

    case ExprStmt(expr) =>
      genExpr(expr)

    case If(cond, thenBody, elseBody) =>
      val c        = genBool(cond)
      val thenL    = freshLabel("if.then")
      val elseL    = freshLabel("if.else")
      val endL     = freshLabel("if.end")
      val hasElse  = elseBody.nonEmpty
      emit(s"br i1 $c, label %$thenL, label %${if hasElse then elseL else endL}")
      emitLabel(thenL)
      thenBody.foreach(genStmt)
      emit(s"br label %$endL")
      if hasElse then
        emitLabel(elseL)
        elseBody.foreach(genStmt)
        emit(s"br label %$endL")
      emitLabel(endL)

    case While(cond, whileBody) =>
      val condL = freshLabel("while.cond")
      val bodyL = freshLabel("while.body")
      val endL  = freshLabel("while.end")
      emit(s"br label %$condL")
      emitLabel(condL)
      val c = genBool(cond)
      emit(s"br i1 $c, label %$bodyL, label %$endL")
      emitLabel(bodyL)
      whileBody.foreach(genStmt)
      emit(s"br label %$condL")
      emitLabel(endL)

  // --- expressions ---------------------------------------------------------------------

  /** Lowers an expression and returns its type and the register/immediate holding its value. */
  private def genExpr(expr: Expr): (Ty, String) = expr match
    case IntLit(v, _)   => (Ty.Int, v.toString)
    case FloatLit(t, _) => (Ty.Real, hexDouble(t))
    case StrLit(s)      => (Ty.Str, stringGlobal(s))
    case BoolLit(b)     => (Ty.Bool, if b then "1" else "0")

    case Ident(name) =>
      val (ptr, ty) = vars.getOrElse(name, throw CodegenError(s"undefined name '$name'"))
      val r         = freshTemp()
      emit(s"$r = load ${ty.llvm}, ptr $ptr")
      (ty, r)

    case Call(Ident("print"), args) =>
      genPrint(args)
      (Ty.Unit, "")

    case Call(callee, _) =>
      throw CodegenError(s"only the built-in 'print' can be called so far, not $callee")

    case Assign("=", Ident(name), value) =>
      val (ptr, ty) = vars.getOrElse(name, throw CodegenError(s"undefined name '$name'"))
      val (vty, v)  = genExpr(value)
      if vty != ty then throw CodegenError(s"cannot assign ${vty.llvm} to '$name' of type ${ty.llvm}")
      emit(s"store ${ty.llvm} $v, ptr $ptr")
      (ty, v)

    case Assign(op, Ident(name), value) =>
      val binSym    = op.dropRight(1) // "+=" -> "+"
      val (ptr, ty) = vars.getOrElse(name, throw CodegenError(s"undefined name '$name'"))
      val cur       = freshTemp()
      emit(s"$cur = load ${ty.llvm}, ptr $ptr")
      val (rty, updated) = genArith(binSym, (ty, cur), genExpr(value))
      emit(s"store ${rty.llvm} $updated, ptr $ptr")
      (rty, updated)

    case Assign(_, target, _) =>
      throw CodegenError(s"assignment target must be a name, not $target")

    case Binary("&&" | "||", _, _) =>
      (Ty.Bool, genBool(expr))

    case Binary(op, l, r) =>
      genArith(op, genExpr(l), genExpr(r))

    case Unary("-", operand) =>
      genExpr(operand) match
        case (Ty.Int, v)  => val r = freshTemp(); emit(s"$r = sub i32 0, $v"); (Ty.Int, r)
        case (Ty.Real, v) => val r = freshTemp(); emit(s"$r = fneg double $v"); (Ty.Real, r)
        case (ty, _)      => throw CodegenError(s"unary '-' is not defined for ${ty.llvm}")

    case Unary("!", operand) =>
      val v = genBool(operand)
      val r = freshTemp()
      emit(s"$r = xor i1 $v, true")
      (Ty.Bool, r)

    case Unary("~", operand) =>
      genExpr(operand) match
        case (Ty.Int, v) => val r = freshTemp(); emit(s"$r = xor i32 $v, -1"); (Ty.Int, r)
        case (ty, _)     => throw CodegenError(s"unary '~' is not defined for ${ty.llvm}")

    case PreIncDec(op, Ident(name)) =>
      val (ptr, ty) = vars.getOrElse(name, throw CodegenError(s"undefined name '$name'"))
      if ty != Ty.Int then throw CodegenError(s"'$op' is only defined for int")
      val cur = freshTemp(); emit(s"$cur = load i32, ptr $ptr")
      val nv  = freshTemp(); emit(s"$nv = ${if op == "++" then "add" else "sub"} i32 $cur, 1")
      emit(s"store i32 $nv, ptr $ptr")
      (Ty.Int, nv)

    case PostIncDec(op, Ident(name)) =>
      val (ptr, ty) = vars.getOrElse(name, throw CodegenError(s"undefined name '$name'"))
      if ty != Ty.Int then throw CodegenError(s"'$op' is only defined for int")
      val cur = freshTemp(); emit(s"$cur = load i32, ptr $ptr")
      val nv  = freshTemp(); emit(s"$nv = ${if op == "++" then "add" else "sub"} i32 $cur, 1")
      emit(s"store i32 $nv, ptr $ptr")
      (Ty.Int, cur)

    case c: Compare => (Ty.Bool, genBool(c))

    case other =>
      throw CodegenError(s"expression form not yet supported: $other")

  /** Arithmetic and bitwise binary ops on matching numeric operands. */
  private def genArith(op: String, left: (Ty, String), right: (Ty, String)): (Ty, String) = {
    val (lty, lv) = left
    val (rty, rv) = right
    if lty != rty then throw CodegenError(s"'$op' needs matching types, got ${lty.llvm} and ${rty.llvm}")

    def int(instr: String): (Ty, String)  = { val r = freshTemp(); emit(s"$r = $instr i32 $lv, $rv"); (Ty.Int, r) }
    def real(instr: String): (Ty, String) = { val r = freshTemp(); emit(s"$r = $instr double $lv, $rv"); (Ty.Real, r) }

    (lty, op) match
      case (Ty.Int, "+")  => int("add")
      case (Ty.Int, "-")  => int("sub")
      case (Ty.Int, "*")  => int("mul")
      case (Ty.Int, "/")  => int("sdiv")
      case (Ty.Int, "%")  => int("srem")
      case (Ty.Int, "<<") => int("shl")
      case (Ty.Int, ">>") => int("ashr")
      case (Ty.Int, "&")  => int("and")
      case (Ty.Int, "|")  => int("or")
      case (Ty.Int, "^")  => int("xor")
      case (Ty.Real, "+") => real("fadd")
      case (Ty.Real, "-") => real("fsub")
      case (Ty.Real, "*") => real("fmul")
      case (Ty.Real, "/") => real("fdiv")
      case _ => throw CodegenError(s"operator '$op' is not defined for ${lty.llvm}")
  }

  /** Lowers a boolean-valued expression to an `i1` register or immediate. */
  private def genBool(expr: Expr): String = expr match
    case BoolLit(b) => if b then "1" else "0"

    case Binary("&&", l, r) =>
      val lv = genBool(l); val rv = genBool(r)
      val res = freshTemp(); emit(s"$res = and i1 $lv, $rv"); res

    case Binary("||", l, r) =>
      val lv = genBool(l); val rv = genBool(r)
      val res = freshTemp(); emit(s"$res = or i1 $lv, $rv"); res

    case Unary("!", operand) =>
      val v = genBool(operand); val r = freshTemp(); emit(s"$r = xor i1 $v, true"); r

    case Compare(operands, ops) =>
      val comparisons =
        ops.indices.map(i => compareOne(ops(i), operands(i), operands(i + 1))).toList
      comparisons.reduce { (a, b) => val r = freshTemp(); emit(s"$r = and i1 $a, $b"); r }

    case Ident(_) =>
      genExpr(expr) match
        case (Ty.Bool, v) => v
        case (ty, _)      => throw CodegenError(s"condition must be bool, got ${ty.llvm}")

    case other =>
      genExpr(other) match
        case (Ty.Bool, v) => v
        case (ty, _)      => throw CodegenError(s"condition must be bool, got ${ty.llvm}")

  private def compareOne(op: String, a: Expr, b: Expr): String = {
    val (aty, av) = genExpr(a)
    val (bty, bv) = genExpr(b)
    if aty != bty then throw CodegenError(s"cannot compare ${aty.llvm} with ${bty.llvm}")
    val r = freshTemp()

    aty match
      case Ty.Int =>
        val pred = op match
          case "==" => "eq"; case "!=" => "ne"
          case "<"  => "slt"; case ">" => "sgt"; case "<=" => "sle"; case ">=" => "sge"
        emit(s"$r = icmp $pred i32 $av, $bv")
      case Ty.Real =>
        val pred = op match
          case "==" => "oeq"; case "!=" => "one"
          case "<"  => "olt"; case ">" => "ogt"; case "<=" => "ole"; case ">=" => "oge"
        emit(s"$r = fcmp $pred double $av, $bv")
      case ty =>
        throw CodegenError(s"'$op' is not defined for ${ty.llvm}")
    r
  }

  // --- print ---------------------------------------------------------------------------

  /** `print(a, b, ...)` — the arguments printed space-separated, then a newline, via one
   * `printf`. The space separator is a friendly default, not a fixed part of the language.
   */
  private def genPrint(args: List[Expr]): Unit = {
    val specs    = mutable.ListBuffer.empty[String]
    val callArgs = mutable.ListBuffer.empty[String]

    for arg <- args do
      genExpr(arg) match
        case (Ty.Int, v)  => specs += "%d"; callArgs += s"i32 $v"
        case (Ty.Real, v) => specs += "%g"; callArgs += s"double $v"
        case (Ty.Str, v)  => specs += "%s"; callArgs += s"ptr $v"
        case (Ty.Bool, v) =>
          boolStrs = true
          val sel = freshTemp()
          emit(s"$sel = select i1 $v, ptr @.true, ptr @.false")
          specs += "%s"; callArgs += s"ptr $sel"
        case (Ty.Unit, _) => throw CodegenError("cannot print a unit value")

    val fmtGlobal = stringGlobal(specs.mkString(" ") + "\n")
    val allArgs   = (s"ptr $fmtGlobal" :: callArgs.toList).mkString(", ")
    val r         = freshTemp()
    emit(s"$r = call i32 (ptr, ...) @printf($allArgs)")
  }

  /** Renders a decimal float literal as an LLVM hex double, the format that never loses bits
   * across the textual round-trip.
   */
  private def hexDouble(text: String): String =
    f"0x${java.lang.Double.doubleToLongBits(text.toDouble)}%016X"
}

object Codegen {

  /** Lowers a program to an LLVM IR module, or returns the codegen error message. */
  def generate(program: Program): Either[String, String] =
    try Right(new Codegen().gen(program))
    catch case CodegenError(msg) => Left(msg)
}
