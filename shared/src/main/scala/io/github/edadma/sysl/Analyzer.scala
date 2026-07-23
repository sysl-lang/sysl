package io.github.edadma.sysl

import scala.collection.mutable

/** An error raised by the analyzer: an unknown name, a type mismatch, a wrong arity — any
 * rule that the structural parse cannot catch.
 */
case class AnalyzerError(message: String) extends RuntimeException(message)

/** The semantic pass: it resolves names, checks types, and turns the untyped `Program` into
 * a typed `TProgram` that codegen lowers directly. All diagnostics live here; codegen trusts
 * the tree it is handed.
 *
 * Declarations are hoisted, so functions and structs may be used before they appear and may
 * be mutually recursive. Each function (and the synthetic `main` around the top-level
 * statements) is its own naming context: a variable that shadows an outer one is renamed to a
 * unique register name, which keeps codegen's per-function SSA names distinct without the
 * analyzer having to understand LLVM.
 */
class Analyzer private (program: Program) {

  private val structDecls = mutable.LinkedHashMap.empty[String, StructDecl]
  private val structCache = mutable.LinkedHashMap.empty[String, Type.Struct]
  private val enumDecls   = mutable.LinkedHashMap.empty[String, EnumDecl]
  private val enumCache   = mutable.LinkedHashMap.empty[String, Type.Enum]
  private val funcSigs    = mutable.LinkedHashMap.empty[String, (List[(String, Type)], Type)]

  /** Every enum variant name maps to its enum, so a bare `Circle(5)` or `Empty` resolves
   * without qualification. Variant names are therefore unique across all enums.
   */
  private val variantOf = mutable.HashMap.empty[String, Type.Enum]

  // Per-function state, reset at each function boundary.
  private var scopes: List[mutable.LinkedHashMap[String, (String, Type)]] = Nil
  private val used                                                        = mutable.HashSet.empty[String]
  private var retTy: Type                                                 = Type.Unit

  private def err(msg: String): Nothing = throw AnalyzerError(msg)

  private def show(t: Type): String = t match
    case s: Type.Struct => s.name
    case e: Type.Enum   => e.name
    case Type.Int       => "int"
    case Type.Real      => "real"
    case Type.Bool      => "bool"
    case Type.Str       => "string"
    case Type.Unit      => "unit"

  // --- scopes and unique naming --------------------------------------------------------

  private def pushScope(): Unit = scopes = mutable.LinkedHashMap.empty[String, (String, Type)] :: scopes
  private def popScope(): Unit  = scopes = scopes.tail

  private def resetFunction(): Unit = {
    used.clear()
    scopes = List(mutable.LinkedHashMap.empty[String, (String, Type)])
  }

  private def freshName(base: String): String =
    if !used(base) then { used += base; base }
    else {
      var k = 1
      while used(s"$base.$k") do k += 1
      val n = s"$base.$k"
      used += n
      n
    }

  private def declare(name: String, ty: Type): String = {
    val unique = freshName(name)
    scopes.head(name) = (unique, ty)
    unique
  }

  private def lookupOpt(name: String): Option[(String, Type)] =
    scopes.collectFirst { case s if s.contains(name) => s(name) }

  private def lookup(name: String): (String, Type) =
    lookupOpt(name).getOrElse(err(s"undefined name '$name'"))

  // --- type resolution -----------------------------------------------------------------

  private def resolveType(t: TypeRef): Type = t match
    case NamedType(n) =>
      n match
        case "int" | "i32"  => Type.Int
        case "real" | "f64" => Type.Real
        case "bool"         => Type.Bool
        case "string"       => Type.Str
        case "unit"         => Type.Unit
        case other if structDecls.contains(other) => resolveStruct(other)
        case other if enumDecls.contains(other)   => resolveEnum(other)
        case other                                => err(s"unknown type '$other'")

  private def resolveStruct(name: String): Type.Struct =
    structCache.getOrElseUpdate(
      name, {
        val decl = structDecls(name)
        Type.Struct(name, decl.fields.map(f => (f.name, resolveType(f.typ))))
      },
    )

  /** Builds an enum type from its declaration. All-dataless variants make a *simple* enum
   * (integer constants, auto-incrementing from an optional explicit `= value`); any
   * data-carrying variant makes a *data* enum, whose variants take sequential tags and whose
   * payload-bearing variants each claim a slot in the aggregate.
   */
  private def resolveEnum(name: String): Type.Enum =
    enumCache.getOrElseUpdate(
      name, {
        val decl   = enumDecls(name)
        val simple = decl.variants.forall(_.fields.isEmpty)
        var nextTag  = 0
        var nextSlot = 1
        val variants = decl.variants.map { v =>
          if simple then
            val tag = v.value match
              case Some(IntLit(n, _)) => n.toInt
              case Some(_)            => err(s"the value of variant '${v.name}' must be an integer literal")
              case None               => nextTag
            nextTag = tag + 1
            Type.EnumVariant(v.name, tag, Nil, None)
          else
            if v.value.isDefined then
              err(s"variant '${v.name}' carries data, so it cannot also have an explicit value")
            val tag    = nextTag; nextTag += 1
            val fields = v.fields.map(f => (f.name, resolveType(f.typ)))
            val slot   = if fields.nonEmpty then { val s = nextSlot; nextSlot += 1; Some(s) } else None
            Type.EnumVariant(v.name, tag, fields, slot)
        }
        val en = Type.Enum(name, simple, variants)
        for v <- variants do
          if variantOf.contains(v.name) then
            err(s"variant name '${v.name}' is already used by enum '${variantOf(v.name).name}'")
          variantOf(v.name) = en
        en
      },
    )

  // --- program -------------------------------------------------------------------------

  private def analyze(): TProgram = {
    for stmt <- program.body do
      stmt match
        case s: StructDecl =>
          if typeNameTaken(s.name) then err(s"type '${s.name}' is already declared")
          structDecls(s.name) = s
        case e: EnumDecl =>
          if typeNameTaken(e.name) then err(s"type '${e.name}' is already declared")
          enumDecls(e.name) = e
        case _ =>

    enumDecls.keys.foreach(resolveEnum)
    structDecls.keys.foreach(resolveStruct)

    for stmt <- program.body do
      stmt match
        case f: FuncDecl =>
          if funcSigs.contains(f.name) then err(s"function '${f.name}' is already declared")
          val ps = f.params.map(p => (p.name, resolveType(p.typ)))
          val rt = f.retType.map(resolveType).getOrElse(Type.Unit)
          funcSigs(f.name) = (ps, rt)
        case _ =>

    val tfuncs = program.body.collect { case f: FuncDecl => analyzeFunc(f) }

    val mainStmts = program.body.filter {
      case _: FuncDecl | _: StructDecl | _: EnumDecl => false
      case _                                         => true
    }

    resetFunction()
    retTy = Type.Int
    val tmain = mainStmts.map(analyzeStmt)

    val tstructs = structDecls.keys.map(resolveStruct).toList
    val tenums   = enumDecls.keys.map(resolveEnum).filterNot(_.simple).toList
    TProgram(tstructs, tenums, tfuncs, tmain)
  }

  /** A struct and an enum share one type namespace, so a name may name at most one of them. */
  private def typeNameTaken(name: String): Boolean = structDecls.contains(name) || enumDecls.contains(name)

  private def analyzeFunc(f: FuncDecl): TFunc = {
    val (params, rt) = funcSigs(f.name)
    resetFunction()
    retTy = rt
    val tparams = params.map { case (n, t) => (declare(n, t), t) }
    val tbody   = analyzeValueBlock(f.body)

    if rt != Type.Unit && tbody.result.isDefined && tbody.ty != rt then
      err(s"function '${f.name}' should return ${show(rt)}, but its body yields ${show(tbody.ty)}")

    TFunc(f.name, tparams, rt, tbody)
  }

  // --- blocks --------------------------------------------------------------------------

  /** A block whose trailing expression (if any) is its value — a function body or an if/match
   * branch. Statements share one lexical scope with the result expression.
   */
  private def analyzeValueBlock(stmts: List[Stmt]): TBlock = {
    pushScope()
    val tb = analyzeBlockBody(stmts)
    popScope()
    tb
  }

  /** The body of a value block, using whatever scope the caller has established — a match arm
   * runs this after declaring its pattern bindings, so they are visible to the body.
   */
  private def analyzeBlockBody(stmts: List[Stmt]): TBlock =
    stmts.reverse match
      case ExprStmt(e) :: initRev =>
        val init = initRev.reverse.map(analyzeStmt)
        val tr   = analyzeExpr(e)
        TBlock(init, Some(tr), tr.ty)
      case _ =>
        TBlock(stmts.map(analyzeStmt), None, Type.Unit)

  /** A statement sequence used only for its effects (a loop body): a fresh scope, no value. */
  private def analyzeStmts(stmts: List[Stmt]): List[TStmt] = {
    pushScope()
    val r = stmts.map(analyzeStmt)
    popScope()
    r
  }

  // --- statements ----------------------------------------------------------------------

  private def analyzeStmt(stmt: Stmt): TStmt = stmt match
    case VarDecl(name, typOpt, init) =>
      val ti = analyzeExpr(init)
      if ti.ty == Type.Unit then err(s"cannot bind '$name' to a unit value")
      val declTy = typOpt.map(resolveType).getOrElse(ti.ty)
      if typOpt.isDefined && declTy != ti.ty then
        err(s"cannot initialize '$name': declared ${show(declTy)} but the value is ${show(ti.ty)}")
      TVarDecl(declare(name, declTy), declTy, ti)

    case ExprStmt(e) =>
      TExprStmt(analyzeExpr(e))

    case While(cond, body) =>
      TWhile(analyzeBool(cond), analyzeStmts(body))

    case For(name, iter, body) =>
      iter match
        case RangeExpr(Some(lo), Some(hi), inclusive) =>
          val tlo = analyzeExpr(lo)
          val thi = analyzeExpr(hi)
          if tlo.ty != Type.Int || thi.ty != Type.Int then err("a 'for' range iterates 'int' bounds")
          pushScope()
          val u  = declare(name, Type.Int)
          val tb = body.map(analyzeStmt)
          popScope()
          TFor(u, tlo, thi, inclusive, tb)
        case _ =>
          err("'for' iterates an integer range 'a..b' or 'a..<b'")

    case Return(opt) =>
      val tv = opt.map(analyzeExpr)
      tv match
        case Some(t) if retTy == Type.Unit => err("cannot return a value from a function with no return type")
        case Some(t) if t.ty != retTy      => err(s"return type mismatch: expected ${show(retTy)}, got ${show(t.ty)}")
        case None if retTy != Type.Unit    => err(s"this function must return a ${show(retTy)} value")
        case _                             =>
      TReturn(tv)

    case _: FuncDecl | _: StructDecl | _: EnumDecl =>
      err("functions, structs, and enums may only be declared at the top level")

  // --- expressions ---------------------------------------------------------------------

  private def analyzeBool(e: Expr): TExpr = {
    val t = analyzeExpr(e)
    if t.ty != Type.Bool then err(s"condition must be bool, got ${show(t.ty)}")
    t
  }

  private def analyzeExpr(expr: Expr): TExpr = expr match
    case IntLit(v, _)   => TIntLit(v, Type.Int)
    case FloatLit(t, _) => TFloatLit(hexDouble(t))
    case StrLit(s)      => TStrLit(s)
    case BoolLit(b)     => TBoolLit(b)
    case UnitLit()      => TUnitLit()

    case Ident(name) =>
      lookupOpt(name) match
        case Some((u, ty)) => TLoad(u, ty)
        case None =>
          variantOf.get(name) match
            case Some(en) =>
              val v = en.variant(name).get
              if v.fields.nonEmpty then err(s"variant '$name' carries data — construct it with '$name(…)'")
              TEnumNew(en, v, Nil)
            case None => err(s"undefined name '$name'")

    case Binary(op @ ("&&" | "||"), l, r) =>
      TLogical(op, analyzeBool(l), analyzeBool(r))

    case Binary(op, l, r) =>
      val tl  = analyzeExpr(l)
      val tr  = analyzeExpr(r)
      TBinary(op, tl, tr, arithType(op, tl.ty, tr.ty))

    case Unary("-", e) =>
      val t = analyzeExpr(e)
      if t.ty == Type.Int || t.ty == Type.Real then TUnary("-", t, t.ty)
      else err(s"unary '-' is not defined for ${show(t.ty)}")

    case Unary("!", e) =>
      TUnary("!", analyzeBool(e), Type.Bool)

    case Unary("~", e) =>
      val t = analyzeExpr(e)
      if t.ty == Type.Int then TUnary("~", t, Type.Int)
      else err(s"unary '~' is not defined for ${show(t.ty)}")

    case Unary(op, _) =>
      err(s"unary '$op' is not supported yet")

    case PreIncDec(op, Ident(n))  => incDec(op, n, pre = true)
    case PostIncDec(op, Ident(n)) => incDec(op, n, pre = false)
    case PreIncDec(op, _)         => err(s"'$op' needs a variable")
    case PostIncDec(op, _)        => err(s"'$op' needs a variable")

    case Compare(operands, ops) =>
      val ts = operands.map(analyzeExpr)
      for i <- ops.indices do
        val (a, b) = (ts(i), ts(i + 1))
        if a.ty != b.ty then err(s"cannot compare ${show(a.ty)} with ${show(b.ty)}")
        if a.ty != Type.Int && a.ty != Type.Real then err(s"'${ops(i)}' is not defined for ${show(a.ty)}")
      TCompare(ts, ops)

    case Assign("=", Ident(n), value) =>
      val (u, ty) = lookup(n)
      val tv      = analyzeExpr(value)
      if tv.ty != ty then err(s"cannot assign ${show(tv.ty)} to '$n' of type ${show(ty)}")
      TStore(u, tv, ty)

    case Assign("=", Field(Ident(n), f), value) =>
      val (u, ty) = lookup(n)
      ty match
        case s: Type.Struct =>
          val idx = s.fieldIndex(f)
          if idx < 0 then err(s"struct '${s.name}' has no field '$f'")
          val fty = s.fields(idx)._2
          val tv  = analyzeExpr(value)
          if tv.ty != fty then err(s"cannot assign ${show(tv.ty)} to field '$f' of type ${show(fty)}")
          TSetField(u, s, idx, tv, fty)
        case other =>
          err(s"cannot assign to field '$f' of ${show(other)}")

    case Assign(op, Ident(n), value) =>
      val (u, ty) = lookup(n)
      val binSym  = op.dropRight(1)
      val tv      = analyzeExpr(value)
      val rty     = arithType(binSym, ty, tv.ty)
      if rty != ty then err(s"'$op' would change the type of '$n'")
      TUpdate(u, op, tv, ty)

    case Assign(_, target, _) =>
      err("assignment target must be a variable or a field of one")

    case Call(Ident("print"), args) =>
      TPrint(args.map { a =>
        val t = analyzeExpr(a)
        t.ty match
          case Type.Unit                     => err("cannot print a unit value")
          case _: Type.Struct | _: Type.Enum => err(s"cannot print a ${show(t.ty)} value")
          case _                             => t
      })

    case Call(Ident(name), args) if variantOf.contains(name) =>
      val en = variantOf(name)
      val v  = en.variant(name).get
      if v.fields.isEmpty then err(s"variant '$name' takes no arguments — write it as '$name'")
      if args.length != v.fields.length then
        err(s"variant '$name' has ${v.fields.length} fields, but ${args.length} were given")
      val targs = args.zip(v.fields).map { case (a, (fname, fty)) =>
        val t = analyzeExpr(a)
        if t.ty != fty then err(s"field '$fname' of '$name' is ${show(fty)}, but ${show(t.ty)} was given")
        t
      }
      TEnumNew(en, v, targs)

    case Call(Ident(name), args) if structDecls.contains(name) =>
      val s = resolveStruct(name)
      if args.length != s.fields.length then
        err(s"struct '$name' has ${s.fields.length} fields, but ${args.length} were given")
      val targs = args.zip(s.fields).map { case (a, (fname, fty)) =>
        val t = analyzeExpr(a)
        if t.ty != fty then err(s"field '$fname' of '$name' is ${show(fty)}, but ${show(t.ty)} was given")
        t
      }
      TStructNew(s, targs)

    case Call(Ident(name), args) if funcSigs.contains(name) =>
      val (params, rt) = funcSigs(name)
      if args.length != params.length then
        err(s"function '$name' takes ${params.length} arguments, but ${args.length} were given")
      val targs = args.zip(params).map { case (a, (pname, pty)) =>
        val t = analyzeExpr(a)
        if t.ty != pty then err(s"argument '$pname' of '$name' is ${show(pty)}, but ${show(t.ty)} was given")
        t
      }
      TCall(name, targs, rt)

    case Call(Ident(name), _) =>
      err(s"undefined function '$name'")

    case Call(_, _) =>
      err("the thing being called must be a name")

    case Field(Ident(n), f) if lookupOpt(n).isEmpty && enumDecls.contains(n) =>
      val en = resolveEnum(n)
      en.variant(f) match
        case Some(v) if v.fields.isEmpty => TEnumNew(en, v, Nil)
        case Some(_)                     => err(s"variant '$n.$f' carries data — construct it with '$f(…)'")
        case None                        => err(s"enum '$n' has no variant '$f'")

    case Field(receiver, f) =>
      val tr = analyzeExpr(receiver)
      tr.ty match
        case s: Type.Struct =>
          val idx = s.fieldIndex(f)
          if idx < 0 then err(s"struct '${s.name}' has no field '$f'")
          TField(tr, idx, s.fields(idx)._2)
        case other =>
          err(s"cannot read field '$f' of ${show(other)}")

    case IfExpr(cond, thenBody, elseOpt) =>
      val tc    = analyzeBool(cond)
      val tThen = analyzeValueBlock(thenBody)
      val tElse = elseOpt.map(analyzeValueBlock)
      val ty = tElse match
        case Some(eb) if eb.ty == tThen.ty                                                => tThen.ty
        case Some(eb) if eb.ty != Type.Unit && tThen.ty != Type.Unit && eb.ty != tThen.ty =>
          err(s"if branches have different types: ${show(tThen.ty)} and ${show(eb.ty)}")
        case _ => Type.Unit
      TIf(tc, tThen, tElse, ty)

    case MatchExpr(scrut, arms) =>
      val ts    = analyzeExpr(scrut)
      val tarms = arms.map(analyzeArm(ts.ty, _))
      TMatch(ts, tarms, matchResultType(ts.ty, tarms))

    case _: RangeExpr =>
      err("a range is only allowed in a 'for' loop or a 'match' pattern")

    case CharLit(_)  => err("character literals are not supported yet")
    case _: Index    => err("indexing is not supported yet")
    case _: TryExpr  => err("the '?' operator is not supported yet")
    case _: Tuple    => err("tuples are not supported yet")

  private def incDec(op: String, name: String, pre: Boolean): TExpr = {
    val (u, ty) = lookup(name)
    if ty != Type.Int then err(s"'$op' is only defined for int")
    TIncDec(u, op, pre)
  }

  // --- match arms and patterns ---------------------------------------------------------

  /** Analyzes one arm in its own scope so pattern bindings are visible to the guard and body.
   * Alternatives (`a | b`) may not bind, since the body cannot know which alternative matched.
   */
  private def analyzeArm(scrutTy: Type, arm: MatchArm): TArm = {
    pushScope()
    val tpats = arm.patterns.map(analyzePattern(_, scrutTy))
    if tpats.length > 1 && tpats.exists(binds) then
      err("alternative patterns joined by '|' cannot bind a name")
    val tguard = arm.guard.map(analyzeBool)
    val tbody  = analyzeBlockBody(arm.body)
    popScope()
    TArm(tpats, tguard, tbody)
  }

  /** Turns one pattern into its typed form, declaring any bindings into the current scope. A
   * bare name is a nullary-variant pattern when it names a variant of the scrutinee's enum, and
   * a binding otherwise.
   */
  private def analyzePattern(p: Pattern, ty: Type): TPattern = p match
    case WildcardPattern => TWildPattern(ty)

    case LitPattern(v) =>
      val t = analyzeExpr(v)
      if t.ty != ty then err(s"pattern is ${show(t.ty)} but the value is ${show(ty)}")
      TLitPattern(t)

    case RangePattern(lo, hi, inclusive) =>
      if ty != Type.Int && ty != Type.Real then err(s"a range pattern needs a numeric value, not ${show(ty)}")
      val tl = analyzeExpr(lo)
      val th = analyzeExpr(hi)
      if tl.ty != ty || th.ty != ty then err(s"range pattern must match the ${show(ty)} value")
      TRangePattern(tl, th, inclusive)

    case IdentPattern(name) =>
      ty match
        case en: Type.Enum if en.variant(name).exists(_.fields.isEmpty) =>
          TVariantPattern(en, en.variant(name).get, Nil)
        case en: Type.Enum if en.variant(name).isDefined =>
          err(s"variant '$name' carries data — match it as '$name(…)'")
        case _ =>
          TBindPattern(declare(name, ty), ty)

    case VariantPattern(name, args) =>
      ty match
        case en: Type.Enum =>
          en.variant(name) match
            case Some(v) if v.fields.isEmpty =>
              err(s"variant '$name' takes no arguments — match it as '$name'")
            case Some(v) =>
              if args.length != v.fields.length then
                err(s"variant '$name' has ${v.fields.length} fields, but ${args.length} sub-patterns were given")
              TVariantPattern(en, v, args.zip(v.fields).map { case (a, (_, fty)) => analyzePattern(a, fty) })
            case None =>
              err(s"enum '${en.name}' has no variant '$name'")
        case other =>
          err(s"'$name(…)' matches an enum variant, but the value is ${show(other)}")

  /** Whether a pattern binds any name (directly or inside a variant's sub-patterns). */
  private def binds(p: TPattern): Boolean = p match
    case _: TBindPattern         => true
    case v: TVariantPattern      => v.args.exists(binds)
    case _                       => false

  /** A pattern that always matches, so an unguarded arm carrying it is a catch-all. */
  private def irrefutable(p: TPattern): Boolean = p match
    case _: TWildPattern | _: TBindPattern => true
    case _                                 => false

  /** Checks exhaustiveness and returns the value type of a match (`unit` unless every arm
   * yields the same non-unit type). An enum match must cover every variant or carry a
   * catch-all; a scalar match need only be exhaustive when it is used for a value.
   */
  private def matchResultType(scrutTy: Type, arms: List[TArm]): Type = {
    val bodyTys = arms.map(_.body.ty).distinct
    val valueTy = if bodyTys.size == 1 && bodyTys.head != Type.Unit then bodyTys.head else Type.Unit

    val hasCatchAll = arms.exists(a => a.guard.isEmpty && a.patterns.exists(irrefutable))

    scrutTy match
      case en: Type.Enum =>
        val covered = arms.filter(_.guard.isEmpty).flatMap(_.patterns).collect {
          case v: TVariantPattern if v.args.forall(irrefutable) => v.variant.tag
        }.toSet
        if !hasCatchAll && !en.variants.map(_.tag).toSet.subsetOf(covered) then
          val missing = en.variants.filterNot(v => covered(v.tag)).map(_.name)
          err(s"match on '${en.name}' is not exhaustive; missing ${missing.mkString(", ")} (add an 'else' arm)")
      case _ =>
        if valueTy != Type.Unit && !hasCatchAll then
          err("a 'match' that yields a value must be exhaustive — add an 'else' arm")

    valueTy
  }

  /** The result type of an arithmetic or bitwise binary operator on matching operands. */
  private def arithType(op: String, a: Type, b: Type): Type = {
    if a != b then err(s"'$op' needs matching types, got ${show(a)} and ${show(b)}")
    (a, op) match
      case (Type.Int, "+" | "-" | "*" | "/" | "%" | "<<" | ">>" | "&" | "|" | "^") => Type.Int
      case (Type.Real, "+" | "-" | "*" | "/")                                      => Type.Real
      case _ => err(s"operator '$op' is not defined for ${show(a)}")
  }

  /** Renders a decimal float literal as an LLVM hex double — the textual form that survives
   * the round-trip without losing bits.
   */
  private def hexDouble(text: String): String =
    f"0x${java.lang.Double.doubleToLongBits(text.toDouble)}%016X"
}

object Analyzer {

  /** Analyzes a program to a typed tree, or returns the first error message. */
  def analyze(program: Program): Either[String, TProgram] =
    try Right(new Analyzer(program).analyze())
    catch case AnalyzerError(msg) => Left(msg)
}
